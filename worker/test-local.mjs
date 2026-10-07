// Local end-to-end test of the pickup worker, using in-memory mocks of the
// Cloudflare KV and R2 bindings. No wrangler or network needed:
//
//   node worker/test-local.mjs
//
// The full flow (POST /t -> PUT /f/:token -> GET /t/:code (ready)
// -> GET /f/:code (identical bytes) -> token reuse fails -> oversize upload
// rejected -> DELETE token checks -> record gone) runs TWICE: once with an
// R2 binding (FILES) and once without one, where files are stored as chunks
// in KV. The KV run adds chunk-accounting tests with a 23 MiB file
// (3 data chunks + 1 manifest), a no-Content-Length oversize upload whose
// written chunks must be rolled back, and DELETE removing every file key.
// The R2 run also checks that scheduled() removes an orphan R2 object.

import assert from "node:assert/strict";
import worker from "./src/index.js";

// ----- Mock KV: Map-backed, ignores TTL (tests never wait for expiry) -----
// Values are byte-preserving: ArrayBuffer / Uint8Array values are stored as
// copied bytes (real KV stores raw bytes), strings as strings. put() accepts
// and records options (e.g. expirationTtl) without acting on them.
class MockKV {
  constructor() {
    this.store = new Map();
    this.putOptions = new Map();
  }
  async get(key, type) {
    if (!this.store.has(key)) return null;
    const value = this.store.get(key);
    if (type === "arrayBuffer") {
      const bytes =
        value instanceof Uint8Array
          ? value
          : new TextEncoder().encode(String(value));
      return bytes.slice().buffer; // a copy, like a fresh KV read
    }
    // Default (text) get.
    return value instanceof Uint8Array
      ? new TextDecoder().decode(value)
      : value;
  }
  async put(key, value, options) {
    this.putOptions.set(key, options || {});
    if (value instanceof ArrayBuffer) {
      const copy = new Uint8Array(value.byteLength);
      copy.set(new Uint8Array(value));
      this.store.set(key, copy);
    } else if (value instanceof Uint8Array) {
      this.store.set(key, value.slice());
    } else {
      this.store.set(key, String(value));
    }
  }
  async delete(key) {
    this.store.delete(key);
  }
  async list() {
    return { keys: [...this.store.keys()].map((name) => ({ name })) };
  }
}

// ----- Mock R2: Map-backed object store of byte arrays -----
class MockR2 {
  constructor() {
    this.store = new Map();
  }
  async put(key, body, options = {}) {
    let bytes;
    if (body instanceof Uint8Array) {
      bytes = body;
    } else if (body instanceof ArrayBuffer) {
      bytes = new Uint8Array(body);
    } else {
      // A ReadableStream (e.g. request.body).
      bytes = new Uint8Array(await new Response(body).arrayBuffer());
    }
    this.store.set(key, {
      bytes,
      httpMetadata: options.httpMetadata || {},
      customMetadata: options.customMetadata || {},
    });
    // Like real R2, put() resolves to an object descriptor with a size.
    return { key, size: bytes.length };
  }
  async get(key) {
    const entry = this.store.get(key);
    if (!entry) return null;
    return {
      body: new Response(entry.bytes).body,
      size: entry.bytes.length,
      httpMetadata: entry.httpMetadata,
      customMetadata: entry.customMetadata,
    };
  }
  async delete(key) {
    this.store.delete(key);
  }
  async list({ prefix = "", limit = 1000 } = {}) {
    const objects = [...this.store.keys()]
      .filter((k) => k.startsWith(prefix))
      .slice(0, limit)
      .map((key) => ({ key }));
    return { objects, truncated: false };
  }
}

const ctx = { waitUntil(p) { this.promise = p; } };

function call(env, method, path, options = {}) {
  const request = new Request("https://test.local" + path, { method, ...options });
  return worker.fetch(request, env, ctx);
}

const json = async (res) => JSON.parse(await res.text());

const meta = {
  appName: "测试应用",
  packageName: "com.example.test",
  versionName: "1.0.0",
  sha256: "deadbeef",
  fileName: "test.apk",
};

// Keys of every KV-stored file chunk/manifest for a pickup code.
function fileKeysOf(env, code) {
  return [...env.TRANSFERS.store.keys()].filter((k) =>
    k.startsWith("file:" + code + ":")
  );
}

// ----- The full flow, run once per storage mode -----
async function runFlow(env, mode) {
  const payloadBytes = new TextEncoder().encode("FAKE-APK-BYTES-0123456789");
  const flowMeta = { ...meta, sizeBytes: String(payloadBytes.length) }; // client sends strings

  // 1. Register a transfer
  let res = await call(env, "POST", "/t", {
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(flowMeta),
  });
  assert.equal(res.status, 200, `[${mode}] POST /t should succeed`);
  const created = await json(res);
  assert.match(created.code, /^\d{6}$/, "code should be 6 digits");
  assert.ok(created.uploadToken && created.uploadToken.length >= 32, "uploadToken should be 32+ hex chars");
  assert.ok(created.deleteToken, "deleteToken should be returned");
  console.log(`[${mode}] POST /t -> code`, created.code, "uploadToken", created.uploadToken.length, "hex chars");

  // 2. Not ready yet, and no tokens leak
  res = await call(env, "GET", "/t/" + created.code);
  assert.equal(res.status, 200);
  const before = await json(res);
  assert.equal(before.ready, false, "ready should be false before upload");
  assert.equal(before.appName, "测试应用");
  assert.equal(before.deleteToken, undefined, "GET /t must not leak deleteToken");
  assert.equal(before.uploadToken, undefined, "GET /t must not leak uploadToken");
  console.log(`[${mode}] GET /t/:code before upload -> ready=false, no tokens leaked`);

  // 3. Upload the file with the token
  res = await call(env, "PUT", "/f/" + created.uploadToken, {
    headers: { "Content-Type": "application/vnd.android.package-archive" },
    body: payloadBytes,
  });
  assert.equal(res.status, 200, `[${mode}] PUT /f/:token should succeed`);
  assert.deepEqual(await json(res), { ok: true });
  console.log(`[${mode}] PUT /f/:uploadToken -> ok`);

  // 4. Ready now
  res = await call(env, "GET", "/t/" + created.code);
  const after = await json(res);
  assert.equal(after.ready, true, "ready should be true after upload");
  console.log(`[${mode}] GET /t/:code after upload -> ready=true`);

  // 5. Download returns the identical bytes with the APK content type
  res = await call(env, "GET", "/f/" + created.code);
  assert.equal(res.status, 200);
  assert.equal(
    res.headers.get("Content-Type"),
    "application/vnd.android.package-archive"
  );
  assert.match(res.headers.get("Content-Disposition") || "", /test\.apk/);
  const downloaded = new Uint8Array(await res.arrayBuffer());
  assert.deepEqual(downloaded, payloadBytes, "downloaded bytes must be identical");
  console.log(`[${mode}] GET /f/:code -> identical bytes, APK content type`);

  // 6. The upload token is single-use
  res = await call(env, "PUT", "/f/" + created.uploadToken, { body: payloadBytes });
  assert.equal(res.status, 404, "reusing the upload token should fail");
  console.log(`[${mode}] PUT /f/:uploadToken again -> 404 (single-use)`);

  // 7. Oversize guard: registered size 10 bytes, body ~1.1MB
  res = await call(env, "POST", "/t", {
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...flowMeta, sizeBytes: "10" }),
  });
  assert.equal(res.status, 200);
  const created2 = await json(res);
  const bigBody = new Uint8Array(1100 * 1024);
  res = await call(env, "PUT", "/f/" + created2.uploadToken, { body: bigBody });
  assert.equal(res.status, 413, "wildly oversize upload should be rejected");
  console.log(`[${mode}] PUT oversize body -> 413`);

  // 8. DELETE needs the right deleteToken, and removes the file too
  res = await call(env, "DELETE", "/t/" + created.code + "?token=wrong-token");
  assert.equal(res.status, 403, "DELETE with wrong token should fail");
  res = await call(env, "DELETE", "/t/" + created.code + "?token=" + created.deleteToken);
  assert.equal(res.status, 200, "DELETE with right token should succeed");
  assert.deepEqual(await json(res), { ok: true });
  res = await call(env, "GET", "/t/" + created.code);
  assert.equal(res.status, 404, "record should be gone after DELETE");
  res = await call(env, "GET", "/f/" + created.code);
  assert.equal(res.status, 404, "download should be gone after DELETE");
  if (env.FILES) {
    assert.equal(await env.FILES.get("files/" + created.code), null, "R2 object should be gone too");
  } else {
    assert.deepEqual(fileKeysOf(env, created.code), [], "KV file keys should be gone too");
  }
  console.log(`[${mode}] DELETE wrong token -> 403, right token -> ok, record + file gone`);
}

// ================= R2 mode (FILES bound) =================
const envR2 = { TRANSFERS: new MockKV(), FILES: new MockR2() };
await runFlow(envR2, "R2");

// 9. scheduled() removes orphan R2 objects
await envR2.FILES.put("files/999999", new TextEncoder().encode("orphan"));
assert.notEqual(await envR2.FILES.get("files/999999"), null);
await worker.scheduled({ cron: "0 * * * *" }, envR2, { waitUntil() {} });
assert.equal(await envR2.FILES.get("files/999999"), null, "orphan should be removed by scheduled()");
console.log("[R2] scheduled() -> orphan R2 object removed");
console.log("R2 MODE PASSED");

// ================= KV mode (no FILES binding) =================
const envKV = { TRANSFERS: new MockKV() };
await runFlow(envKV, "KV");

// scheduled() must be a safe no-op without an R2 binding.
await worker.scheduled({ cron: "0 * * * *" }, envKV, { waitUntil() {} });
console.log("[KV] scheduled() without FILES -> no-op, no crash");

// ----- Multi-chunk round trip: 23 MiB -> ceil(23/10) = 3 chunks + manifest -----
const BIG_SIZE = 23 * 1024 * 1024;
const bigBytes = new Uint8Array(BIG_SIZE);
for (let i = 0; i < BIG_SIZE; i++) bigBytes[i] = i % 251; // patterned, not zeros

let res = await call(envKV, "POST", "/t", {
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ ...meta, sizeBytes: String(BIG_SIZE), fileName: "big.apk" }),
});
assert.equal(res.status, 200);
const big = await json(res);

res = await call(envKV, "PUT", "/f/" + big.uploadToken, { body: bigBytes });
assert.equal(res.status, 200, "23 MiB PUT should succeed in KV mode");

const bigKeys = fileKeysOf(envKV, big.code);
const bigDataKeys = bigKeys.filter((k) => !k.endsWith(":meta"));
assert.equal(bigDataKeys.length, 3, "23 MiB should be stored as 3 data chunks");
assert.ok(bigKeys.includes("file:" + big.code + ":meta"), "manifest key should exist");
assert.equal(bigKeys.length, 4, "expected exactly 3 chunks + 1 manifest");

const manifest = JSON.parse(await envKV.TRANSFERS.get("file:" + big.code + ":meta"));
assert.equal(manifest.chunks, 3, "manifest should record 3 chunks");
assert.equal(manifest.sizeBytes, BIG_SIZE, "manifest should record the full size");
for (const k of bigKeys) {
  const opts = envKV.TRANSFERS.putOptions.get(k);
  assert.equal(typeof (opts && opts.expirationTtl), "number", "every file key gets an expirationTtl");
  assert.ok(opts.expirationTtl >= 60, "expirationTtl should be a sane TTL");
}
console.log("[KV] 23 MiB upload -> 3 chunks + manifest, all with expirationTtl");

res = await call(envKV, "GET", "/f/" + big.code);
assert.equal(res.status, 200);
assert.equal(res.headers.get("Content-Length"), String(BIG_SIZE));
const bigDownloaded = new Uint8Array(await res.arrayBuffer());
assert.equal(bigDownloaded.length, BIG_SIZE);
assert.deepEqual(bigDownloaded, bigBytes, "multi-chunk download must be byte-identical");
console.log("[KV] GET /f/:code -> 23 MiB byte-identical round trip");

// ----- Oversize upload WITHOUT Content-Length: chunks written, then rolled back -----
res = await call(envKV, "POST", "/t", {
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ ...meta, sizeBytes: "10" }),
});
assert.equal(res.status, 200);
const over = await json(res);
const streamBody = new ReadableStream({
  start(controller) {
    controller.enqueue(new Uint8Array(6 * 1024 * 1024));
    controller.enqueue(new Uint8Array(6 * 1024 * 1024)); // 12 MiB total -> 2 chunks
    controller.close();
  },
});
res = await call(envKV, "PUT", "/f/" + over.uploadToken, {
  body: streamBody,
  duplex: "half", // stream body: undici sends no Content-Length header
});
assert.equal(res.status, 413, "oversize chunked upload should be rejected");
assert.deepEqual(fileKeysOf(envKV, over.code), [], "rejected upload must leave no chunks behind");
console.log("[KV] PUT oversize (no Content-Length) -> 413, written chunks rolled back");

// ----- DELETE removes manifest + all chunks -----
res = await call(envKV, "DELETE", "/t/" + big.code + "?token=" + big.deleteToken);
assert.equal(res.status, 200);
assert.deepEqual(fileKeysOf(envKV, big.code), [], "DELETE must remove manifest + all chunks");
res = await call(envKV, "GET", "/f/" + big.code);
assert.equal(res.status, 404, "GET /f after DELETE should be 404");
res = await call(envKV, "GET", "/t/" + big.code);
assert.equal(res.status, 404, "GET /t after DELETE should be 404");
console.log("[KV] DELETE -> manifest + all chunks removed, GETs are 404");
console.log("KV MODE PASSED");

console.log("ALL TESTS PASSED");
