// 随传取件服务 (Suichuan pickup service)
//
// A deliberately tiny Cloudflare Worker. Bindings:
//   - KV namespace `TRANSFERS` (required) — one record per pickup code: the
//     transfer metadata (app name, size, checksum, file name), its tokens,
//     and an `uploaded` flag. Records expire (max 72h). By default the
//     package file itself also lives here, split into chunks (see the file
//     storage section below).
//   - R2 bucket `FILES` (optional) — if bound, the package file is stored as
//     a single object at `files/<code>` instead of KV chunks. R2 requires a
//     payment method on file even for its free tier, so the deployed MVP
//     runs WITHOUT this binding; it stays as an opportunistic fast path if
//     a bucket ever exists. (Independently, the Android app falls back to
//     Litterbox temporary hosting when no backend is configured at all.)
//
// Flow:
//   1. POST /t            sender registers metadata -> { code, deleteToken,
//                         uploadToken } (uploadToken is single-use)
//   2. PUT  /f/:token     sender uploads the raw file body -> stored via the
//                         file-storage layer, record marked uploaded, token
//                         key deleted
//   3. GET  /t/:code      receiver reads metadata + { ready }
//   4. GET  /f/:code      receiver downloads the file
//   5. DELETE /t/:code    sender removes everything early (needs deleteToken)
//   6. scheduled()        hourly: in R2 mode, deletes objects whose KV record
//                         has already expired (orphans left behind by TTL);
//                         KV-stored files expire with their record's TTL, so
//                         there is nothing to sweep for them.
//
// Abuse note: a 6-digit code has only 1,000,000 combinations, so treat codes
// as a convenience, not a secret. Entries expire quickly (max 72h), deletes
// need the deleteToken, uploads need the single-use uploadToken, and if this
// ever serves the public you should add rate limiting (e.g. Cloudflare Rate
// Limiting rules) in front of GET /t/:code. The QR code / share text carries
// the full payload and does not strictly depend on this service.
//
// Cloudflare free-plan limits worth knowing: a request through a Worker can
// carry at most ~100MB, so a single transfer is capped around that size.
// Workers KV's free tier is 1GB of total storage (25MB max per value, which
// is why files are chunked at 10 MiB); R2's free tier is 10GB.

const MAX_TTL_SECONDS = 72 * 60 * 60; // 72h
const DEFAULT_TTL_SECONDS = 24 * 60 * 60; // 24h
const MIN_TTL_SECONDS = 60; // KV rejects expirationTtl below 60
const SIZE_SLACK_BYTES = 1024 * 1024; // tolerance for the upload size check
const APK_CONTENT_TYPE = "application/vnd.android.package-archive";
const CHUNK_SIZE_BYTES = 10 * 1024 * 1024; // KV file chunk size (cap is 25MB)

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

// Parse a single-range "Range: bytes=..." header against the file size.
// Returns { start, end } (inclusive, end clamped to the last byte), the
// string "unsatisfiable" when start is at/past EOF (caller answers 416),
// or null when the header is absent or malformed and should be ignored
// (caller serves the normal 200 full response). Multi-range headers are
// not supported and count as malformed here.
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

async function readRecord(env, code) {
  const stored = await env.TRANSFERS.get(code);
  if (stored === null) return null;
  try {
    return JSON.parse(stored);
  } catch (e) {
    return null;
  }
}

// How much of a record's TTL is left, in seconds. KV cannot extend an
// existing key's expiry, so anything re-put (the record after upload, the
// file keys) recomputes it from createdAt. The result is floored at 60s,
// which can slightly extend a nearly-expired transfer; acceptable here.
function remainingTtlSeconds(record) {
  const elapsed = Math.floor((Date.now() - (record.createdAt || Date.now())) / 1000);
  return Math.max(
    MIN_TTL_SECONDS,
    (record.ttlSeconds || DEFAULT_TTL_SECONDS) - elapsed
  );
}

// ---------------------------------------------------------------------------
// File storage abstraction — the only part of this Worker that knows where
// file bytes live. Interface:
//   putFile(code, request, record) -> { size }   (throws on storage failure)
//   getFile(code)                  -> { body, size, contentType } | null
//   getFileInfo(code)              -> { size, contentType } | null
//   getFileRange(code, start, end) -> { body, size, contentType } | null
//                                     (start/end inclusive; only the bytes
//                                     of that range are ever read/buffered)
//   deleteFile(code)               -> removes whatever putFile wrote
//
// R2 backend (env.FILES bound): the original behavior — one object at
// `files/<code>`, streamed in and out.
//
// Workers KV backend (default): the file is split into 10 MiB chunks in the
// same TRANSFERS namespace — `file:<code>:<index>` per chunk plus a manifest
// `file:<code>:meta` (JSON: { chunks, sizeBytes, sha256, fileName,
// contentType }). Every file key is written with the transfer record's
// remaining TTL, so a file self-expires together with its record and needs
// no cleanup sweep.
// ---------------------------------------------------------------------------
function fileStorage(env) {
  if (env.FILES) {
    return {
      async putFile(code, request, record) {
        const storedObject = await env.FILES.put("files/" + code, request.body, {
          httpMetadata: { contentType: APK_CONTENT_TYPE },
          customMetadata: {
            sha256: record.sha256 || "",
            fileName: record.fileName || "",
          },
        });
        return { size: storedObject && storedObject.size };
      },
      async getFile(code) {
        const object = await env.FILES.get("files/" + code);
        if (object === null) return null;
        return {
          body: object.body,
          size: object.size,
          contentType:
            (object.httpMetadata && object.httpMetadata.contentType) ||
            APK_CONTENT_TYPE,
        };
      },
      async getFileInfo(code) {
        const head = await env.FILES.head("files/" + code);
        if (head === null) return null;
        return {
          size: head.size,
          contentType:
            (head.httpMetadata && head.httpMetadata.contentType) ||
            APK_CONTENT_TYPE,
        };
      },
      async getFileRange(code, start, end) {
        // R2 serves the range itself; only the requested bytes come back.
        const object = await env.FILES.get("files/" + code, {
          range: { offset: start, length: end - start + 1 },
        });
        if (object === null) return null;
        return {
          body: object.body,
          size: end - start + 1,
          contentType:
            (object.httpMetadata && object.httpMetadata.contentType) ||
            APK_CONTENT_TYPE,
        };
      },
      async deleteFile(code) {
        await env.FILES.delete("files/" + code);
      },
    };
  }

  const kv = env.TRANSFERS;
  const chunkKey = (code, index) => "file:" + code + ":" + index;
  const manifestKey = (code) => "file:" + code + ":meta";

  async function readManifest(code) {
    const raw = await kv.get(manifestKey(code));
    if (raw === null) return null;
    try {
      return JSON.parse(raw);
    } catch (e) {
      return null;
    }
  }

  async function deleteKvFile(code) {
    const manifest = await readManifest(code);
    await kv.delete(manifestKey(code));
    if (manifest && typeof manifest.chunks === "number") {
      for (let i = 0; i < manifest.chunks; i++) {
        await kv.delete(chunkKey(code, i));
      }
    }
  }

  return {
    async putFile(code, request, record) {
      // The whole body is buffered in memory: KV values are discrete blobs,
      // so there is no streaming write anyway — and the Workers memory
      // limit is consistent with the ~100MB per-transfer cap noted above.
      const buffer = await request.arrayBuffer();
      const size = buffer.byteLength;
      const chunks = Math.ceil(size / CHUNK_SIZE_BYTES);
      const expirationTtl = remainingTtlSeconds(record);
      try {
        for (let i = 0; i < chunks; i++) {
          const start = i * CHUNK_SIZE_BYTES;
          const slice = buffer.slice(
            start,
            Math.min(start + CHUNK_SIZE_BYTES, size)
          );
          await kv.put(chunkKey(code, i), slice, { expirationTtl });
        }
        // Manifest last: its presence is what marks the file as complete.
        const manifest = {
          chunks,
          sizeBytes: size,
          sha256: record.sha256 || "",
          fileName: record.fileName || "",
          contentType: APK_CONTENT_TYPE,
        };
        await kv.put(manifestKey(code), JSON.stringify(manifest), {
          expirationTtl,
        });
      } catch (e) {
        // Don't leave a partial upload behind.
        await deleteKvFile(code).catch(() => {});
        throw e;
      }
      return { size };
    },

    async getFile(code) {
      const manifest = await readManifest(code);
      if (manifest === null) return null;
      let index = 0;
      const body = new ReadableStream({
        async pull(controller) {
          if (index >= manifest.chunks) {
            controller.close();
            return;
          }
          const buf = await kv.get(chunkKey(code, index), "arrayBuffer");
          if (buf === null) {
            // A chunk vanished (e.g. its TTL fired mid-download): fail the
            // download instead of silently serving truncated bytes.
            controller.error(new Error("文件不完整，缺少数据块。"));
            return;
          }
          index += 1;
          controller.enqueue(new Uint8Array(buf));
        },
      });
      return {
        body,
        size: manifest.sizeBytes,
        contentType: manifest.contentType || APK_CONTENT_TYPE,
      };
    },

    async getFileInfo(code) {
      const manifest = await readManifest(code);
      if (manifest === null) return null;
      return {
        size: manifest.sizeBytes,
        contentType: manifest.contentType || APK_CONTENT_TYPE,
      };
    },

    async getFileRange(code, start, end) {
      const manifest = await readManifest(code);
      if (manifest === null) return null;
      // Fetch only the chunks the range overlaps and slice off the edges:
      // a segment request touches at most a couple of 10 MiB chunks, never
      // the whole file.
      const firstChunk = Math.floor(start / CHUNK_SIZE_BYTES);
      const lastChunk = Math.floor(end / CHUNK_SIZE_BYTES);
      const parts = [];
      let length = 0;
      for (let i = firstChunk; i <= lastChunk; i++) {
        const buf = await kv.get(chunkKey(code, i), "arrayBuffer");
        if (buf === null) {
          // A chunk vanished (e.g. its TTL fired): treat the file as gone
          // rather than serving a range with a hole in it.
          return null;
        }
        const chunkStart = i * CHUNK_SIZE_BYTES;
        const from = Math.max(start - chunkStart, 0);
        const to = Math.min(end - chunkStart + 1, buf.byteLength);
        const slice = new Uint8Array(buf, from, to - from);
        parts.push(slice);
        length += slice.length;
      }
      let body;
      if (parts.length === 1) {
        body = parts[0];
      } else {
        body = new Uint8Array(length);
        let offset = 0;
        for (const part of parts) {
          body.set(part, offset);
          offset += part.length;
        }
      }
      return {
        body,
        size: length,
        contentType: manifest.contentType || APK_CONTENT_TYPE,
      };
    },

    deleteFile: deleteKvFile,
  };
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

    // PUT /f/:uploadToken — upload the raw package file into file storage
    // (R2 if bound, chunked KV otherwise).
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

      const storage = fileStorage(env);
      let stored;
      try {
        stored = await storage.putFile(code, request, record);
      } catch (e) {
        return jsonResponse({ error: "文件保存失败，请稍后再试。" }, 502);
      }

      // Authoritative size check: some clients send no Content-Length header
      // (chunked uploads), so also verify what actually landed in storage
      // and roll it back if it is wildly different from the registered size.
      if (
        stored &&
        typeof stored.size === "number" &&
        Math.abs(stored.size - record.sizeBytes) > SIZE_SLACK_BYTES
      ) {
        await storage.deleteFile(code);
        return jsonResponse(
          { error: "上传的文件大小和登记的不一致，可能选错了文件。" },
          413
        );
      }

      // Mark uploaded, keeping the record alive for (roughly) its remaining
      // TTL — KV cannot extend the original expiry, so recompute it.
      record.uploaded = true;
      await env.TRANSFERS.put(code, JSON.stringify(record), {
        expirationTtl: remainingTtlSeconds(record),
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

    // GET /f/:code — receiver downloads the file from file storage.
    if (request.method === "GET" && parts.length === 2 && parts[0] === "f") {
      const code = parts[1];
      if (!/^\d{6}$/.test(code)) {
        return jsonResponse({ error: "取件码应该是 6 位数字。" }, 400);
      }
      const record = await readRecord(env, code);
      if (record === null || record.uploaded !== true) {
        return jsonResponse({ error: "文件还没有上传好，或者已经过期了。" }, 404);
      }
      const storage = fileStorage(env);
      const info = await storage.getFileInfo(code);
      if (info === null) {
        return jsonResponse({ error: "文件不存在，可能已经过期了。" }, 404);
      }
      const disposition =
        'attachment; filename="' + sanitizeFileName(record.fileName) + '"';

      // HTTP Range support: the app downloads big files as parallel
      // segments, because a single cross-border stream is often throttled.
      const range = parseRangeHeader(request.headers.get("Range"), info.size);
      if (range === "unsatisfiable") {
        return new Response(
          JSON.stringify({ error: "请求的下载范围不对。" }),
          {
            status: 416,
            headers: {
              "Content-Type": "application/json; charset=utf-8",
              "Content-Range": "bytes */" + info.size,
              "Accept-Ranges": "bytes",
              ...CORS_HEADERS,
            },
          }
        );
      }
      if (range) {
        const part = await storage.getFileRange(code, range.start, range.end);
        if (part === null) {
          return jsonResponse({ error: "文件不存在，可能已经过期了。" }, 404);
        }
        return new Response(part.body, {
          status: 206,
          headers: {
            "Content-Type": part.contentType || APK_CONTENT_TYPE,
            "Content-Disposition": disposition,
            "Content-Range":
              "bytes " + range.start + "-" + range.end + "/" + info.size,
            "Content-Length": String(range.end - range.start + 1),
            "Accept-Ranges": "bytes",
            "Access-Control-Allow-Origin": "*",
          },
        });
      }

      const file = await storage.getFile(code);
      if (file === null) {
        return jsonResponse({ error: "文件不存在，可能已经过期了。" }, 404);
      }
      const headers = {
        "Content-Type": file.contentType || APK_CONTENT_TYPE,
        "Content-Disposition": disposition,
        "Accept-Ranges": "bytes",
        "Access-Control-Allow-Origin": "*",
      };
      if (typeof file.size === "number") {
        headers["Content-Length"] = String(file.size);
      }
      return new Response(file.body, { status: 200, headers });
    }

    // DELETE /t/:code — sender-side early removal; needs the deleteToken
    // either as ?token=... or as an "x-delete-token" header. Removes the KV
    // record, any leftover upload-token key, and the stored file.
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
      await fileStorage(env).deleteFile(code);
      return jsonResponse({ ok: true });
    }

    if (url.pathname === "/" || url.pathname === "/health") {
      return jsonResponse({ ok: true, service: "suichuan-pickup" });
    }

    return jsonResponse({ error: "没有这个接口。" }, 404);
  },

  // Hourly cleanup (Cron Trigger "0 * * * *"): a transfer's KV record dies
  // with its TTL, but in R2 mode its object would live forever — delete any
  // object whose pickup code no longer has a KV record.
  //
  // KV-stored files need no sweep: every chunk and manifest was written
  // with the record's TTL, so they expire on their own. Without an R2
  // binding this handler is deliberately a no-op.
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
