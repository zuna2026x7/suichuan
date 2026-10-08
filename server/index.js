"use strict";

// 随传取件服务 (Suichuan pickup service) — Node.js port of worker/src/index.js
//
// API-compatible with the Cloudflare Worker version: the Android app cannot
// tell the two apart. Pure Node standard library (http/fs/crypto/path), no
// npm dependencies, Node 18+.
//
// Storage (under SUICHUAN_DATA_DIR, default ./data next to this file):
//   records/<code>.json   one JSON file per transfer (the worker's KV record
//                         plus an internal `expiresAt` in ms)
//   files/<code>.bin      the uploaded package file, streamed to/from disk
//
// Flow (identical to the worker):
//   1. POST   /t            register metadata -> { code, deleteToken,
//                           uploadToken } (uploadToken is single-use)
//   2. PUT    /f/:token     upload the raw file body (streamed to a temp
//                           file, renamed into place only after size checks)
//   3. GET    /t/:code      metadata + { ready }; never exposes tokens
//   4. GET    /f/:code      download, with single-Range support (206/416)
//   5. DELETE /t/:code      early removal; needs the deleteToken as an
//                           x-delete-token header or ?token= query param
//   6. POST   /log          client diagnostic log upload (only ever sent
//                           when the user taps the in-app button); stored
//                           verbatim under logs/, no auth — the log
//                           contains no tokens by construction
//
// POST /t also accepts { kind: "text", text }: a text message instead of
// an app. The record stores the text itself and is ready the moment it is
// created — no PUT follows (the issued upload token is born spent, so a
// PUT against it gets the usual already-uploaded 409). GET /t returns
// kind:"text" plus the full text for these records only; file records
// keep the exact view they have always had.
//
// Expiry mirrors the worker's KV TTL: a record dies ttlSeconds after
// creation; a successful upload re-arms it for its remaining TTL (floored
// at 60s, exactly like the worker's remainingTtlSeconds). Expired records
// are removed lazily on access, at boot, and by a periodic sweep — together
// with their payload file, just as the worker's file keys share the
// record's TTL.
//
// Config (environment):
//   PORT               listen port (default 8080)
//   HOST               listen address (default 0.0.0.0)
//   SUICHUAN_DATA_DIR  data directory (default <server dir>/data)
//   SUICHUAN_MAX_BYTES absolute per-file cap (default 100 MiB, the
//                      Cloudflare request limit the worker lives under)
//   SUICHUAN_SWEEP_MS  expiry sweep interval (default 10 minutes)

const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { Transform } = require("node:stream");
const { pipeline } = require("node:stream/promises");

// ----- Constants mirrored from the worker -----
const MAX_TTL_SECONDS = 72 * 60 * 60; // 72h
const DEFAULT_TTL_SECONDS = 24 * 60 * 60; // 24h
const MIN_TTL_SECONDS = 60; // KV rejects expirationTtl below 60
const SIZE_SLACK_BYTES = 1024 * 1024; // tolerance for the upload size check
const APK_CONTENT_TYPE = "application/vnd.android.package-archive";

const PORT = (() => {
  const n = Number(process.env.PORT);
  return process.env.PORT !== undefined && Number.isFinite(n) && n >= 0
    ? n
    : 8080;
})();
const HOST = process.env.HOST || "0.0.0.0";
const DATA_DIR = process.env.SUICHUAN_DATA_DIR
  ? path.resolve(process.env.SUICHUAN_DATA_DIR)
  : path.join(__dirname, "data");
const MAX_BYTES = (() => {
  const n = Number(process.env.SUICHUAN_MAX_BYTES);
  return process.env.SUICHUAN_MAX_BYTES !== undefined &&
    Number.isFinite(n) &&
    n > 0
    ? Math.floor(n)
    : 100 * 1024 * 1024;
})();
const SWEEP_MS = (() => {
  const n = Number(process.env.SUICHUAN_SWEEP_MS);
  return process.env.SUICHUAN_SWEEP_MS !== undefined &&
    Number.isFinite(n) &&
    n > 0
    ? Math.floor(n)
    : 10 * 60 * 1000;
})();

const RECORDS_DIR = path.join(DATA_DIR, "records");
const FILES_DIR = path.join(DATA_DIR, "files");
const LOGS_DIR = path.join(DATA_DIR, "logs");

// Client diagnostic logs are capped well above the app's own ~256KB
// file cap; anything bigger is not a log anymore.
const LOG_MAX_BYTES = 1024 * 1024; // 1 MiB

// One text transfer carries at most 64 KiB of UTF-8 text.
const TEXT_MAX_BYTES = 64 * 1024;

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, x-delete-token, x-suichuan-info",
};

const SIZE_MISMATCH_ERROR = "上传的文件大小和登记的不一致，可能选错了文件。";

// Human-readable rendering of the size cap for error messages
// (2147483648 -> "2GB", 104857600 -> "100MB").
function formatLimit(bytes) {
  const GIB = 1024 * 1024 * 1024;
  const MIB = 1024 * 1024;
  if (bytes % GIB === 0) return bytes / GIB + "GB";
  if (bytes % MIB === 0) return bytes / MIB + "MB";
  return bytes + " 字节";
}

// ----- Helpers copied from the worker (same semantics, Node APIs) -----

function randomDigits(length) {
  const bytes = crypto.randomBytes(length);
  let out = "";
  for (const b of bytes) out += String(b % 10);
  return out;
}

// Hex token: 24 random bytes -> 48 hex chars (upload/delete tokens).
function randomToken() {
  return crypto.randomBytes(24).toString("hex");
}

// Keep only the metadata fields we know about; coerce sizeBytes to a number.
function pickMeta(payload) {
  const size = Number(payload.sizeBytes);
  return {
    appName: typeof payload.appName === "string" ? payload.appName : "",
    packageName:
      typeof payload.packageName === "string" ? payload.packageName : "",
    versionName:
      typeof payload.versionName === "string" ? payload.versionName : "",
    sizeBytes: Number.isFinite(size) && size >= 0 ? Math.floor(size) : 0,
    sha256: typeof payload.sha256 === "string" ? payload.sha256 : "",
    fileName: typeof payload.fileName === "string" ? payload.fileName : "",
  };
}

function clampTtl(payload) {
  const raw = Number(payload.ttlSeconds);
  const wanted =
    Number.isFinite(raw) && raw > 0 ? Math.floor(raw) : DEFAULT_TTL_SECONDS;
  return Math.max(MIN_TTL_SECONDS, Math.min(wanted, MAX_TTL_SECONDS));
}

// What a receiver may see: metadata + ready flag. Never the tokens.
// Text transfers additionally expose their kind and the message itself —
// the text IS the payload. File transfers never gain either field.
function publicView(record) {
  const view = {
    appName: record.appName,
    packageName: record.packageName,
    versionName: record.versionName,
    sizeBytes: record.sizeBytes,
    sha256: record.sha256,
    fileName: record.fileName,
    ready: record.uploaded === true,
  };
  if (record.kind === "text") {
    view.kind = "text";
    view.text = record.text;
  }
  return view;
}

// Make a stored fileName safe for a Content-Disposition header value.
function sanitizeFileName(name) {
  const cleaned = (name || "")
    .replace(/["\\]/g, "_")
    .replace(/[^\x20-\x7E]/g, "_")
    .trim();
  return cleaned.length > 0 ? cleaned : "package.apk";
}

// Parse a single-range "Range: bytes=..." header against the file size.
// Returns { start, end } (inclusive, end clamped to the last byte), the
// string "unsatisfiable" when start is at/past EOF (caller answers 416),
// or null when the header is absent or malformed and should be ignored
// (caller serves the normal 200 full response). Multi-range headers are
// not supported and count as malformed here.
// (Copied verbatim from the worker.)
function parseRangeHeader(header, total) {
  if (!header) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match) return null;
  const first = match[1];
  const last = match[2];
  if (first === "" && last === "") return null;
  let start;
  let end;
  if (first === "") {
    // Suffix range "bytes=-N": the last N bytes of the file.
    start = Math.max(total - Number(last), 0);
    end = total - 1;
  } else {
    start = Number(first);
    end = last === "" ? total - 1 : Math.min(Number(last), total - 1);
  }
  if (start >= total) return "unsatisfiable";
  if (end < start) return null; // e.g. bytes=500-400: malformed, ignore it
  return { start, end };
}

// How much of a record's TTL is left, in seconds. The worker's KV cannot
// extend an existing key's expiry, so anything re-put recomputes it from
// createdAt; the result is floored at 60s, which can slightly extend a
// nearly-expired transfer. Same formula here, applied to `expiresAt`.
function remainingTtlSeconds(record) {
  const elapsed = Math.floor(
    (Date.now() - (record.createdAt || Date.now())) / 1000
  );
  return Math.max(
    MIN_TTL_SECONDS,
    (record.ttlSeconds || DEFAULT_TTL_SECONDS) - elapsed
  );
}

// ----- In-memory state (single process; disk is the durable copy) -----

const records = new Map(); // code -> record (includes internal code/expiresAt)
const uploadTokens = new Map(); // uploadToken -> code
const uploadsInFlight = new Set(); // uploadTokens with a PUT currently streaming

function recordPath(code) {
  return path.join(RECORDS_DIR, code + ".json");
}

function payloadPath(code) {
  return path.join(FILES_DIR, code + ".bin");
}

function safeUnlink(file) {
  try {
    fs.unlinkSync(file);
  } catch (e) {
    // Already gone (or never written): cleanup is best-effort.
  }
}

// Atomic record write: temp file + rename, so a crash never leaves a
// half-written record behind.
function persistRecord(record) {
  const tmp =
    recordPath(record.code) +
    ".tmp-" +
    crypto.randomBytes(6).toString("hex");
  fs.writeFileSync(tmp, JSON.stringify(record));
  fs.renameSync(tmp, recordPath(record.code));
}

function deleteTransfer(code) {
  const record = records.get(code);
  if (record && record.uploadToken) uploadTokens.delete(record.uploadToken);
  records.delete(code);
  safeUnlink(recordPath(code));
  safeUnlink(payloadPath(code));
}

function isExpired(record) {
  return Date.now() >= record.expiresAt;
}

// Lazy expiry: an expired record (and its file) disappears on first touch,
// exactly as if its KV keys had hit their TTL.
function getRecord(code) {
  const record = records.get(code);
  if (!record) return null;
  if (isExpired(record)) {
    deleteTransfer(code);
    return null;
  }
  return record;
}

// ----- Boot: load persisted records, drop expired/orphaned state -----
function loadFromDisk() {
  fs.mkdirSync(RECORDS_DIR, { recursive: true });
  fs.mkdirSync(FILES_DIR, { recursive: true });

  // Leftover upload temp files and record temp files are crash debris.
  for (const name of fs.readdirSync(FILES_DIR)) {
    if (name.startsWith(".tmp-")) safeUnlink(path.join(FILES_DIR, name));
  }
  for (const name of fs.readdirSync(RECORDS_DIR)) {
    if (!name.endsWith(".json") || name.includes(".tmp-")) {
      safeUnlink(path.join(RECORDS_DIR, name));
    }
  }

  for (const name of fs.readdirSync(RECORDS_DIR)) {
    if (!name.endsWith(".json")) continue;
    const code = name.slice(0, -".json".length);
    let record = null;
    try {
      record = JSON.parse(
        fs.readFileSync(path.join(RECORDS_DIR, name), "utf8")
      );
    } catch (e) {
      record = null;
    }
    if (!record || typeof record !== "object") {
      safeUnlink(recordPath(code));
      safeUnlink(payloadPath(code));
      continue;
    }
    record.code = code;
    if (typeof record.expiresAt !== "number") {
      record.expiresAt =
        (record.createdAt || Date.now()) +
        (record.ttlSeconds || DEFAULT_TTL_SECONDS) * 1000;
    }
    if (isExpired(record)) {
      safeUnlink(recordPath(code));
      safeUnlink(payloadPath(code));
      continue;
    }
    if (record.uploaded !== true) {
      // A payload without a completed upload is an orphan (the process
      // died mid-PUT, before the rename + record update finished).
      safeUnlink(payloadPath(code));
    }
    records.set(code, record);
    if (record.uploaded !== true && record.uploadToken) {
      uploadTokens.set(record.uploadToken, code);
    }
  }
}

// ----- Periodic expiry sweep (lazy expiry on access covers the rest) -----
function sweepExpired() {
  for (const code of [...records.keys()]) {
    const record = records.get(code);
    if (record && isExpired(record)) deleteTransfer(code);
  }
}

// ----- HTTP plumbing -----

function sendJson(res, status, body, extraHeaders) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": String(Buffer.byteLength(text)),
    ...CORS_HEADERS,
    ...(extraHeaders || {}),
  });
  res.end(text);
}

// Read a whole request body (JSON payloads only — uploads are streamed).
// Bodies past the cap are drained and discarded, then reported via the
// thrown error, so the client still gets a clean response.
async function readBody(req, cap) {
  const chunks = [];
  let total = 0;
  let overflow = false;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > cap) {
      overflow = true;
      chunks.length = 0;
    } else if (!overflow) {
      chunks.push(chunk);
    }
  }
  if (overflow) throw new Error("body too large");
  return Buffer.concat(chunks);
}

function drainRequest(req, cap) {
  return new Promise((resolve) => {
    let total = 0;
    let done = false;
    const finish = (value) => {
      if (!done) {
        done = true;
        resolve(value);
      }
    };
    req.on("data", (chunk) => {
      total += chunk.length;
      if (total > cap) {
        req.pause();
        finish(false);
      }
    });
    req.on("end", () => finish(true));
    req.on("error", () => finish(false));
    req.on("close", () => finish(false));
  });
}

// The worker's header-based 413 is produced before the body is stored.
// Small bodies are drained first so the client reliably reads the 413;
// for large declared bodies, answer at once and close the connection
// after the response has been flushed.
async function rejectSizeMismatch(req, res, declared) {
  const DRAINABLE = 4 * 1024 * 1024;
  if (Number.isFinite(declared) && declared <= DRAINABLE) {
    let drained = false;
    try {
      drained = await drainRequest(req, MAX_BYTES);
    } catch (e) {
      return; // client went away mid-drain; nothing to answer
    }
    if (drained) {
      if (!res.writableEnded) {
        sendJson(res, 413, { error: SIZE_MISMATCH_ERROR });
      }
      return;
    }
  }
  if (res.writableEnded) return;
  sendJson(res, 413, { error: SIZE_MISMATCH_ERROR }, { Connection: "close" });
  res.once("finish", () => {
    try {
      req.socket.end();
    } catch (e) {
      // Socket already gone.
    }
  });
}

async function streamFile(res, file, start, end) {
  if (end < start) {
    res.end();
    return;
  }
  const rs = fs.createReadStream(file, { start, end });
  try {
    await pipeline(rs, res);
  } catch (e) {
    // Client disconnected mid-download, or the file vanished after the
    // stat: the status line is already sent, so just tear the response
    // down (pipeline has already destroyed the read stream).
    try {
      res.destroy();
    } catch (err) {
      // Already destroyed.
    }
  }
}

// One line per completed /t or /f request, so a failed transfer can be
// diagnosed afterwards from the journal (systemd captures stdout). The
// path is logged with the pickup code only: upload/delete tokens are
// never written out — PUT /f/<token> is logged as the literal "/f/:token",
// and query strings (which may carry a delete token) are never logged.
function logRequestOnFinish(req, res, logPath, startedAt) {
  // The response's Content-Length is captured as it goes through
  // writeHead: by "finish" time Node has already discarded its header
  // store, so getHeader() can no longer see it. The wrapper is a pure
  // pass-through and changes nothing about the response itself.
  let responseBytes;
  const writeHead = res.writeHead;
  res.writeHead = function (statusCode, ...rest) {
    for (const arg of rest) {
      if (Array.isArray(arg)) {
        for (let i = 0; i + 1 < arg.length; i += 2) {
          if (String(arg[i]).toLowerCase() === "content-length") {
            responseBytes = arg[i + 1];
          }
        }
      } else if (arg && typeof arg === "object") {
        for (const key of Object.keys(arg)) {
          if (key.toLowerCase() === "content-length") {
            responseBytes = arg[key];
          }
        }
      }
    }
    return writeHead.call(this, statusCode, ...rest);
  };
  res.on("finish", () => {
    const range = req.headers.range;
    console.log(
      new Date().toISOString() +
        " " +
        req.method +
        " " +
        logPath +
        " " +
        res.statusCode +
        " bytes=" +
        (responseBytes === undefined ? "-" : responseBytes) +
        " ms=" +
        (Date.now() - startedAt) +
        (range ? " range=" + range : "")
    );
  });
}

// ----- Request handlers -----

async function handleCreate(req, res) {
  let raw;
  try {
    raw = await readBody(req, 1024 * 1024);
  } catch (e) {
    return sendJson(res, 400, { error: "请求内容不是有效的 JSON。" });
  }
  let payload;
  try {
    payload = JSON.parse(raw.toString("utf8"));
  } catch (e) {
    return sendJson(res, 400, { error: "请求内容不是有效的 JSON。" });
  }
  // Text transfers (kind:"text") carry their whole payload in this one
  // request: the record stores the text and is ready at creation. File
  // transfers keep the validation they have always had.
  const isText =
    payload !== null &&
    typeof payload === "object" &&
    payload.kind === "text";
  let meta;
  let extra = {};
  if (isText) {
    const text = payload.text;
    if (typeof text !== "string" || text.trim().length === 0) {
      return sendJson(res, 400, {
        error: "文字内容是空的，没法生成取件码。",
      });
    }
    const textBytes = Buffer.byteLength(text, "utf8");
    if (textBytes > TEXT_MAX_BYTES) {
      return sendJson(res, 413, {
        error: "文字太长了：一段文字最多 64KB。",
      });
    }
    meta = {
      appName:
        typeof payload.appName === "string" &&
        payload.appName.trim().length > 0
          ? payload.appName
          : "文字消息",
      packageName: "",
      versionName: "",
      sizeBytes: textBytes,
      sha256: "",
      fileName: "",
    };
    extra = { kind: "text", text };
  } else {
    if (
      !payload ||
      typeof payload.fileName !== "string" ||
      payload.fileName.length === 0
    ) {
      return sendJson(res, 400, { error: "缺少 fileName，没法生成取件码。" });
    }

    // Fail fast: a declared size over the cap can never upload successfully
    // (the PUT stream would be torn down mid-transfer), so refuse here,
    // before any record exists.
    meta = pickMeta(payload);
    if (meta.sizeBytes > MAX_BYTES) {
      return sendJson(res, 413, {
        error: `文件太大了：单个应用最大支持 ${formatLimit(MAX_BYTES)}。`,
      });
    }
  }

  const ttlSeconds = clampTtl(payload);
  const deleteToken = randomToken();
  const uploadToken = randomToken();
  const record = {
    ...meta,
    ...extra,
    deleteToken,
    uploadToken,
    // A text record already holds its content, so it is born uploaded:
    // GET /t reports ready, and a PUT against its token hits the
    // already-uploaded 409 in handleUpload.
    uploaded: isText,
    createdAt: Date.now(),
    ttlSeconds, // internal: lets PUT recompute the remaining TTL
  };
  record.expiresAt = record.createdAt + ttlSeconds * 1000;

  // Retry a few times if the random code is already taken.
  for (let attempt = 0; attempt < 8; attempt++) {
    const code = randomDigits(6);
    const existing = records.get(code);
    if (existing) {
      if (isExpired(existing)) {
        deleteTransfer(code);
      } else {
        continue;
      }
    }
    record.code = code;
    records.set(code, record);
    uploadTokens.set(uploadToken, code);
    try {
      persistRecord(record);
    } catch (e) {
      records.delete(code);
      uploadTokens.delete(uploadToken);
      return sendJson(res, 503, { error: "取件码生成失败，请稍后再试。" });
    }
    return sendJson(res, 200, { code, deleteToken, uploadToken, ttlSeconds });
  }
  return sendJson(res, 503, { error: "取件码生成失败，请稍后再试。" });
}

async function handleUpload(req, res, uploadToken) {
  const code = uploadTokens.get(uploadToken);
  if (code === undefined) {
    return sendJson(res, 404, { error: "上传凭证不存在或已经过期。" });
  }
  const record = records.get(code);
  if (!record) {
    uploadTokens.delete(uploadToken);
    return sendJson(res, 404, { error: "这个传输不存在，或者已经过期了。" });
  }
  if (isExpired(record)) {
    // In the worker, the upload-token key and the record share a TTL, so
    // an expired pair surfaces as a missing token.
    deleteTransfer(code);
    return sendJson(res, 404, { error: "上传凭证不存在或已经过期。" });
  }
  if (record.uploadToken !== uploadToken) {
    return sendJson(res, 403, { error: "上传凭证不对。" });
  }
  if (record.uploaded) {
    return sendJson(res, 409, { error: "这个文件已经上传过了。" });
  }
  // Single-use under concurrency: a second PUT for the same token while
  // one is already streaming gets the same answer as a replayed token.
  if (uploadsInFlight.has(uploadToken)) {
    return sendJson(res, 409, { error: "这个文件已经上传过了。" });
  }

  // Size check, only when the client sent a Content-Length header:
  // reject when it differs from the registered size by more than ~1MB
  // (a wildly different length means the wrong file is being uploaded).
  const lengthHeader = req.headers["content-length"];
  if (lengthHeader !== undefined) {
    const declared = Number(lengthHeader);
    if (
      !Number.isFinite(declared) ||
      Math.abs(declared - record.sizeBytes) > SIZE_SLACK_BYTES
    ) {
      return rejectSizeMismatch(req, res, declared);
    }
  }
  const hasBody =
    lengthHeader !== undefined ||
    req.headers["transfer-encoding"] !== undefined;
  if (!hasBody) {
    return sendJson(res, 400, { error: "没有收到文件内容。" });
  }

  uploadsInFlight.add(uploadToken);
  const tmpPath = path.join(
    FILES_DIR,
    ".tmp-" + code + "-" + crypto.randomBytes(6).toString("hex")
  );
  try {
    // Stream to a temp file — never buffer the body in memory. Counting
    // happens in flight: once the received size is guaranteed to fail the
    // post-store size check (or passes the absolute cap), further bytes
    // are swallowed instead of written, and the upload is rejected after
    // the body finishes, exactly like the worker's store-then-rollback.
    const softLimit = Math.min(
      record.sizeBytes + SIZE_SLACK_BYTES,
      MAX_BYTES
    );
    const hardLimit = MAX_BYTES + SIZE_SLACK_BYTES;
    let written = 0;
    let exceeded = false;
    const counter = new Transform({
      transform(chunk, _enc, cb) {
        written += chunk.length;
        if (written > hardLimit) {
          cb(new Error("upload exceeds hard cap"));
          return;
        }
        if (exceeded || written > softLimit) {
          exceeded = true;
          cb(null); // swallow: counted, but no longer written to disk
          return;
        }
        cb(null, chunk);
      },
    });

    let streamError = null;
    try {
      await pipeline(req, counter, fs.createWriteStream(tmpPath));
    } catch (e) {
      streamError = e;
    }
    if (streamError) {
      safeUnlink(tmpPath);
      if (streamError.message === "upload exceeds hard cap") {
        // The pipeline tore the request down; answer only if we still can.
        if (!res.writableEnded && !res.destroyed) {
          try {
            sendJson(res, 413, { error: SIZE_MISMATCH_ERROR });
          } catch (e) {
            // Socket already gone.
          }
        }
        return;
      }
      // Client abort or a disk error: the worker answers 502 for storage
      // failures; if the client is gone there is nobody to answer.
      if (!res.writableEnded && !res.destroyed && !req.socket.destroyed) {
        return sendJson(res, 502, { error: "文件保存失败，请稍后再试。" });
      }
      return;
    }

    // Authoritative size check: some clients send no Content-Length header
    // (chunked uploads), so also verify what actually arrived and roll it
    // back if it is wildly different from the registered size.
    if (Math.abs(written - record.sizeBytes) > SIZE_SLACK_BYTES) {
      safeUnlink(tmpPath);
      return sendJson(res, 413, { error: SIZE_MISMATCH_ERROR });
    }

    try {
      fs.renameSync(tmpPath, payloadPath(code));
    } catch (e) {
      safeUnlink(tmpPath);
      return sendJson(res, 502, { error: "文件保存失败，请稍后再试。" });
    }

    // Mark uploaded, keeping the record alive for (roughly) its remaining
    // TTL — same recomputation the worker does when it re-puts the record.
    record.uploaded = true;
    record.expiresAt = Date.now() + remainingTtlSeconds(record) * 1000;
    try {
      persistRecord(record);
    } catch (e) {
      record.uploaded = false;
      safeUnlink(payloadPath(code));
      return sendJson(res, 502, { error: "文件保存失败，请稍后再试。" });
    }
    // Single-use: the upload token dies here.
    uploadTokens.delete(uploadToken);
    return sendJson(res, 200, { ok: true });
  } finally {
    uploadsInFlight.delete(uploadToken);
  }
}

function handleGetMeta(req, res, code) {
  if (!/^\d{6}$/.test(code)) {
    return sendJson(res, 400, { error: "取件码应该是 6 位数字。" });
  }
  const record = getRecord(code);
  if (record === null) {
    return sendJson(res, 404, {
      error: "这个取件码不存在，或者已经过期了。",
    });
  }
  return sendJson(res, 200, publicView(record));
}

async function handleDownload(req, res, code) {
  if (!/^\d{6}$/.test(code)) {
    return sendJson(res, 400, { error: "取件码应该是 6 位数字。" });
  }
  const record = getRecord(code);
  if (record === null || record.uploaded !== true) {
    return sendJson(res, 404, {
      error: "文件还没有上传好，或者已经过期了。",
    });
  }
  let stat;
  try {
    stat = fs.statSync(payloadPath(code));
  } catch (e) {
    return sendJson(res, 404, { error: "文件不存在，可能已经过期了。" });
  }
  const total = stat.size;
  const disposition =
    'attachment; filename="' + sanitizeFileName(record.fileName) + '"';

  // HTTP Range support: the app downloads big files as parallel segments,
  // because a single cross-border stream is often throttled.
  const range = parseRangeHeader(req.headers.range, total);
  if (range === "unsatisfiable") {
    res.writeHead(416, {
      "Content-Type": "application/json; charset=utf-8",
      "Content-Range": "bytes */" + total,
      "Accept-Ranges": "bytes",
      ...CORS_HEADERS,
    });
    res.end(JSON.stringify({ error: "请求的下载范围不对。" }));
    return;
  }
  if (range) {
    res.writeHead(206, {
      "Content-Type": APK_CONTENT_TYPE,
      "Content-Disposition": disposition,
      "Content-Range":
        "bytes " + range.start + "-" + range.end + "/" + total,
      "Content-Length": String(range.end - range.start + 1),
      "Accept-Ranges": "bytes",
      "Access-Control-Allow-Origin": "*",
    });
    await streamFile(res, payloadPath(code), range.start, range.end);
    return;
  }

  res.writeHead(200, {
    "Content-Type": APK_CONTENT_TYPE,
    "Content-Disposition": disposition,
    "Accept-Ranges": "bytes",
    "Access-Control-Allow-Origin": "*",
    "Content-Length": String(total),
  });
  await streamFile(res, payloadPath(code), 0, total - 1);
}

// POST /log — the client app's diagnostic log, sent only when the user
// explicitly taps 「上传日志帮我看看」. Stored verbatim under logs/ as
// "<ISO timestamp>-<6 hex>.log"; when the client sent an x-suichuan-info
// header (app version; device model; Android release) it becomes the
// file's first line, prefixed with "# ". No auth: the log contains no
// tokens by construction (the client redacts before writing).
async function handleLogUpload(req, res) {
  let body;
  try {
    body = await readBody(req, LOG_MAX_BYTES);
  } catch (e) {
    return sendJson(res, 413, { error: "日志太大了，没法上传。" });
  }
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const name = stamp + "-" + crypto.randomBytes(3).toString("hex") + ".log";
  const info = req.headers["x-suichuan-info"];
  let data = body;
  if (typeof info === "string" && info.length > 0) {
    const header = Buffer.from(
      "# " + info.replace(/[\r\n]+/g, " ") + "\n",
      "utf8"
    );
    data = Buffer.concat([header, body]);
  }
  try {
    fs.mkdirSync(LOGS_DIR, { recursive: true });
    fs.writeFileSync(path.join(LOGS_DIR, name), data);
  } catch (e) {
    return sendJson(res, 502, { error: "日志保存失败，请稍后再试。" });
  }
  return sendJson(res, 200, { ok: true });
}

function handleDelete(req, res, url, code) {
  const token =
    url.searchParams.get("token") || req.headers["x-delete-token"] || "";
  const record = getRecord(code);
  if (record === null) {
    return sendJson(res, 404, {
      error: "这个取件码不存在，或者已经过期了。",
    });
  }
  if (!token || token !== record.deleteToken) {
    return sendJson(res, 403, { error: "删除凭证不对，不能删除。" });
  }
  deleteTransfer(code);
  return sendJson(res, 200, { ok: true });
}

async function handle(req, res) {
  if (req.method === "OPTIONS") {
    res.writeHead(204, CORS_HEADERS);
    res.end();
    return;
  }

  const url = new URL(req.url, "http://localhost");
  const parts = url.pathname.split("/").filter((p) => p.length > 0);

  // Request logging for the transfer routes (see logRequestOnFinish).
  // Installed before dispatch so every /t, /f and /log response is
  // accounted for; the path is rebuilt from the segments, never taken
  // from the raw URL, so no token can leak into the log. (The /log body
  // itself is of course never logged — only method/path/status/bytes.)
  if (parts[0] === "t" || parts[0] === "f" || parts[0] === "log") {
    let logPath = "/" + parts[0];
    if (parts.length >= 2 && parts[0] !== "log") {
      logPath +=
        "/" + (req.method === "PUT" && parts[0] === "f" ? ":token" : parts[1]);
    }
    logRequestOnFinish(req, res, logPath, Date.now());
  }

  // POST /t — register a transfer's metadata, get code + tokens.
  if (req.method === "POST" && parts.length === 1 && parts[0] === "t") {
    return handleCreate(req, res);
  }

  // PUT /f/:uploadToken — upload the raw package file. The token is
  // single-use: it stops resolving as soon as an upload succeeds.
  if (req.method === "PUT" && parts.length === 2 && parts[0] === "f") {
    return handleUpload(req, res, parts[1]);
  }

  // GET /t/:code — receiver looks a transfer up by its pickup code.
  if (req.method === "GET" && parts.length === 2 && parts[0] === "t") {
    return handleGetMeta(req, res, parts[1]);
  }

  // GET /f/:code — receiver downloads the file.
  if (req.method === "GET" && parts.length === 2 && parts[0] === "f") {
    return handleDownload(req, res, parts[1]);
  }

  // DELETE /t/:code — sender-side early removal; needs the deleteToken
  // either as ?token=... or as an "x-delete-token" header.
  if (req.method === "DELETE" && parts.length === 2 && parts[0] === "t") {
    return handleDelete(req, res, url, parts[1]);
  }

  // POST /log — client diagnostic log upload (user-initiated only).
  if (req.method === "POST" && parts.length === 1 && parts[0] === "log") {
    return handleLogUpload(req, res);
  }

  if (url.pathname === "/" || url.pathname === "/health") {
    return sendJson(res, 200, {
      ok: true,
      service: "suichuan-pickup",
      runtime: "node",
    });
  }

  return sendJson(res, 404, { error: "没有这个接口。" });
}

// ----- Server bootstrap -----

loadFromDisk();
const sweepTimer = setInterval(sweepExpired, SWEEP_MS);
sweepTimer.unref();

const server = http.createServer((req, res) => {
  handle(req, res).catch((e) => {
    console.error("unhandled error:", e);
    if (!res.headersSent && !res.writableEnded) {
      try {
        sendJson(res, 500, { error: "服务器内部错误。" });
      } catch (err) {
        // Response already unusable.
      }
    } else {
      try {
        res.destroy();
      } catch (err) {
        // Already destroyed.
      }
    }
  });
});
// Uploads/downloads of ~100MB on slow links must not hit a request
// timeout (the app allows itself 10 minutes); expiry is the app's job.
server.requestTimeout = 0;

server.listen(PORT, HOST, () => {
  const addr = server.address();
  console.log(
    "suichuan-pickup (node) listening on http://" +
      HOST +
      ":" +
      addr.port +
      " data dir: " +
      DATA_DIR
  );
});
