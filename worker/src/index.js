// 随传取件服务 (Suichuan pickup service)
//
// A deliberately tiny Cloudflare Worker with two bindings:
//   - KV namespace `TRANSFERS` — one record per pickup code: the transfer
//     metadata (app name, size, checksum, file name), its tokens, and an
//     `uploaded` flag. Records expire (max 72h).
//   - R2 bucket `FILES` — the package file itself, at key `files/<code>`,
//     but only when this backend is used. (The Android app falls back to
//     Litterbox temporary hosting when no backend is configured.)
//
// Flow:
//   1. POST /t            sender registers metadata -> { code, deleteToken,
//                         uploadToken } (uploadToken is single-use)
//   2. PUT  /f/:token     sender uploads the raw file body -> stored in R2,
//                         record marked uploaded, token key deleted
//   3. GET  /t/:code      receiver reads metadata + { ready }
//   4. GET  /f/:code      receiver downloads the file from R2
//   5. DELETE /t/:code    sender removes everything early (needs deleteToken)
//   6. scheduled()        hourly: deletes R2 objects whose KV record has
//                         already expired (orphans left behind by TTL)
//
// Abuse note: a 6-digit code has only 1,000,000 combinations, so treat codes
// as a convenience, not a secret. Entries expire quickly (max 72h), deletes
// need the deleteToken, uploads need the single-use uploadToken, and if this
// ever serves the public you should add rate limiting (e.g. Cloudflare Rate
// Limiting rules) in front of GET /t/:code. The QR code / share text carries
// the full payload and does not strictly depend on this service.
//
// Cloudflare free-plan limits worth knowing: a request through a Worker can
// carry at most ~100MB, so a single transfer is capped around that size, and
// R2's free tier is 10GB of storage.

const MAX_TTL_SECONDS = 72 * 60 * 60; // 72h
const DEFAULT_TTL_SECONDS = 24 * 60 * 60; // 24h
const MIN_TTL_SECONDS = 60; // KV rejects expirationTtl below 60
const SIZE_SLACK_BYTES = 1024 * 1024; // tolerance for the upload size check
const APK_CONTENT_TYPE = "application/vnd.android.package-archive";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, x-delete-token",
};

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...CORS_HEADERS,
    },
  });
}

function randomDigits(length) {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  let out = "";
  for (const b of bytes) out += String(b % 10);
  return out;
}

// Hex token: 24 random bytes -> 48 hex chars (upload/delete tokens).
function randomToken() {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

// Keep only the metadata fields we know about; coerce sizeBytes to a number.
function pickMeta(payload) {
  const size = Number(payload.sizeBytes);
  return {
    appName: typeof payload.appName === "string" ? payload.appName : "",
    packageName: typeof payload.packageName === "string" ? payload.packageName : "",
    versionName: typeof payload.versionName === "string" ? payload.versionName : "",
    sizeBytes: Number.isFinite(size) && size >= 0 ? Math.floor(size) : 0,
    sha256: typeof payload.sha256 === "string" ? payload.sha256 : "",
    fileName: typeof payload.fileName === "string" ? payload.fileName : "",
  };
}

function clampTtl(payload) {
  const raw = Number(payload.ttlSeconds);
  const wanted = Number.isFinite(raw) && raw > 0 ? Math.floor(raw) : DEFAULT_TTL_SECONDS;
  return Math.max(MIN_TTL_SECONDS, Math.min(wanted, MAX_TTL_SECONDS));
}

// What a receiver may see: metadata + ready flag. Never the tokens.
function publicView(record) {
  return {
    appName: record.appName,
    packageName: record.packageName,
    versionName: record.versionName,
    sizeBytes: record.sizeBytes,
    sha256: record.sha256,
    fileName: record.fileName,
    ready: record.uploaded === true,
  };
}

// Make a stored fileName safe for a Content-Disposition header value.
function sanitizeFileName(name) {
  const cleaned = (name || "")
    .replace(/["\\]/g, "_")
    .replace(/[^\x20-\x7E]/g, "_")
    .trim();
  return cleaned.length > 0 ? cleaned : "package.apk";
}

async function readRecord(env, code) {
  const stored = await env.TRANSFERS.get(code);
  if (stored === null) return null;
  try {
    return JSON.parse(stored);
  } catch (e) {
    return null;
  }
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter((p) => p.length > 0);

    // POST /t — register a transfer's metadata, get code + tokens.
    if (request.method === "POST" && parts.length === 1 && parts[0] === "t") {
      let payload;
      try {
        payload = await request.json();
      } catch (e) {
        return jsonResponse({ error: "请求内容不是有效的 JSON。" }, 400);
      }
      if (!payload || typeof payload.fileName !== "string" || payload.fileName.length === 0) {
        return jsonResponse({ error: "缺少 fileName，没法生成取件码。" }, 400);
      }

      const ttlSeconds = clampTtl(payload);
      const deleteToken = randomToken();
      const uploadToken = randomToken();
      const record = {
        ...pickMeta(payload),
        deleteToken,
        uploadToken,
        uploaded: false,
        createdAt: Date.now(),
        ttlSeconds, // internal: lets PUT recompute the remaining KV TTL
      };
      const recordJson = JSON.stringify(record);

      // Retry a few times if the random code is already taken.
      for (let attempt = 0; attempt < 8; attempt++) {
        const code = randomDigits(6);
        const existing = await env.TRANSFERS.get(code);
        if (existing !== null) continue;
        await env.TRANSFERS.put(code, recordJson, { expirationTtl: ttlSeconds });
        // Reverse lookup so PUT /f/:uploadToken can find its transfer.
        await env.TRANSFERS.put("upload:" + uploadToken, code, {
          expirationTtl: ttlSeconds,
        });
        return jsonResponse({ code, deleteToken, uploadToken, ttlSeconds });
      }
      return jsonResponse({ error: "取件码生成失败，请稍后再试。" }, 503);
    }

    // PUT /f/:uploadToken — upload the raw package file into R2.
    // The token is single-use: its lookup key is deleted on success.
    if (request.method === "PUT" && parts.length === 2 && parts[0] === "f") {
      const uploadToken = parts[1];
      const code = await env.TRANSFERS.get("upload:" + uploadToken);
      if (code === null) {
        return jsonResponse({ error: "上传凭证不存在或已经过期。" }, 404);
      }
      const record = await readRecord(env, code);
      if (record === null) {
        await env.TRANSFERS.delete("upload:" + uploadToken);
        return jsonResponse({ error: "这个传输不存在，或者已经过期了。" }, 404);
      }
      if (record.uploadToken !== uploadToken) {
        return jsonResponse({ error: "上传凭证不对。" }, 403);
      }
      if (record.uploaded) {
        return jsonResponse({ error: "这个文件已经上传过了。" }, 409);
      }
      if (!env.FILES) {
        return jsonResponse({ error: "服务器还没有配置好文件存储。" }, 500);
      }

      // Size check, only when the client sent a Content-Length header:
      // reject when it differs from the registered size by more than ~1MB
      // (a wildly different length means the wrong file is being uploaded).
      const lengthHeader = request.headers.get("Content-Length");
      if (lengthHeader !== null) {
        const declared = Number(lengthHeader);
        if (
          !Number.isFinite(declared) ||
          Math.abs(declared - record.sizeBytes) > SIZE_SLACK_BYTES
        ) {
          return jsonResponse(
            { error: "上传的文件大小和登记的不一致，可能选错了文件。" },
            413
          );
        }
      }
      if (!request.body) {
        return jsonResponse({ error: "没有收到文件内容。" }, 400);
      }

      let storedObject;
      try {
        storedObject = await env.FILES.put("files/" + code, request.body, {
          httpMetadata: { contentType: APK_CONTENT_TYPE },
          customMetadata: {
            sha256: record.sha256 || "",
            fileName: record.fileName || "",
          },
        });
      } catch (e) {
        return jsonResponse({ error: "文件保存失败，请稍后再试。" }, 502);
      }

      // Authoritative size check: some clients send no Content-Length header
      // (chunked uploads), so also verify what actually landed in R2 and
      // roll it back if it is wildly different from the registered size.
      if (
        storedObject &&
        typeof storedObject.size === "number" &&
        Math.abs(storedObject.size - record.sizeBytes) > SIZE_SLACK_BYTES
      ) {
        await env.FILES.delete("files/" + code);
        return jsonResponse(
          { error: "上传的文件大小和登记的不一致，可能选错了文件。" },
          413
        );
      }

      // Mark uploaded, keeping the record alive for (roughly) its remaining
      // TTL — KV cannot extend the original expiry, so recompute it. The
      // minimum is 60s, which can slightly extend a nearly-expired record;
      // acceptable for this MVP.
      const elapsed = Math.floor((Date.now() - (record.createdAt || Date.now())) / 1000);
      const remaining = Math.max(MIN_TTL_SECONDS, (record.ttlSeconds || DEFAULT_TTL_SECONDS) - elapsed);
      record.uploaded = true;
      await env.TRANSFERS.put(code, JSON.stringify(record), {
        expirationTtl: remaining,
      });
      // Single-use: the upload token dies here.
      await env.TRANSFERS.delete("upload:" + uploadToken);
      return jsonResponse({ ok: true });
    }

    // GET /t/:code — receiver looks a transfer up by its pickup code.
    if (request.method === "GET" && parts.length === 2 && parts[0] === "t") {
      const code = parts[1];
      if (!/^\d{6}$/.test(code)) {
        return jsonResponse({ error: "取件码应该是 6 位数字。" }, 400);
      }
      const record = await readRecord(env, code);
      if (record === null) {
        return jsonResponse({ error: "这个取件码不存在，或者已经过期了。" }, 404);
      }
      return jsonResponse(publicView(record));
    }

    // GET /f/:code — receiver downloads the file straight from R2.
    if (request.method === "GET" && parts.length === 2 && parts[0] === "f") {
      const code = parts[1];
      if (!/^\d{6}$/.test(code)) {
        return jsonResponse({ error: "取件码应该是 6 位数字。" }, 400);
      }
      const record = await readRecord(env, code);
      if (record === null || record.uploaded !== true) {
        return jsonResponse({ error: "文件还没有上传好，或者已经过期了。" }, 404);
      }
      if (!env.FILES) {
        return jsonResponse({ error: "服务器还没有配置好文件存储。" }, 500);
      }
      const object = await env.FILES.get("files/" + code);
      if (object === null) {
        return jsonResponse({ error: "文件不存在，可能已经过期了。" }, 404);
      }
      const headers = {
        "Content-Type":
          (object.httpMetadata && object.httpMetadata.contentType) || APK_CONTENT_TYPE,
        "Content-Disposition":
          'attachment; filename="' + sanitizeFileName(record.fileName) + '"',
        "Access-Control-Allow-Origin": "*",
      };
      if (typeof object.size === "number") {
        headers["Content-Length"] = String(object.size);
      }
      return new Response(object.body, { status: 200, headers });
    }

    // DELETE /t/:code — sender-side early removal; needs the deleteToken
    // either as ?token=... or as an "x-delete-token" header. Removes the KV
    // record, any leftover upload-token key, and the R2 object.
    if (request.method === "DELETE" && parts.length === 2 && parts[0] === "t") {
      const code = parts[1];
      const token =
        url.searchParams.get("token") || request.headers.get("x-delete-token") || "";
      const record = await readRecord(env, code);
      if (record === null) {
        return jsonResponse({ error: "这个取件码不存在，或者已经过期了。" }, 404);
      }
      if (!token || token !== record.deleteToken) {
        return jsonResponse({ error: "删除凭证不对，不能删除。" }, 403);
      }
      await env.TRANSFERS.delete(code);
      if (record.uploadToken) {
        await env.TRANSFERS.delete("upload:" + record.uploadToken);
      }
      if (env.FILES) {
        await env.FILES.delete("files/" + code);
      }
      return jsonResponse({ ok: true });
    }

    if (url.pathname === "/" || url.pathname === "/health") {
      return jsonResponse({ ok: true, service: "suichuan-pickup" });
    }

    return jsonResponse({ error: "没有这个接口。" }, 404);
  },

  // Hourly cleanup (Cron Trigger "0 * * * *"): a transfer's KV record dies
  // with its TTL, but its R2 object would live forever — delete any object
  // whose pickup code no longer has a KV record.
  //
  // Limitation (fine for MVP): only the first page of the R2 listing is
  // scanned (up to 1000 objects). If the bucket ever grows past that, page
  // through list cursors here.
  async scheduled(event, env, ctx) {
    if (!env.FILES || !env.TRANSFERS) return;
    // Awaited directly (instead of ctx.waitUntil) so the work is guaranteed
    // to finish before the scheduled invocation settles.
    const listing = await env.FILES.list({ prefix: "files/", limit: 1000 });
    const objects = listing.objects || [];
    for (const object of objects) {
      const code = object.key.slice("files/".length);
      const record = await env.TRANSFERS.get(code);
      if (record === null) {
        await env.FILES.delete(object.key);
      }
    }
  },
};
