// Local end-to-end test of the pickup worker, using in-memory mocks of the
// Cloudflare KV and R2 bindings. No wrangler or network needed:
//
//   node worker/test-local.mjs
//
// Drives the whole flow: POST /t -> PUT /f/:token -> GET /t/:code (ready)
// -> GET /f/:code (identical bytes) -> token reuse fails -> oversize upload
// rejected -> DELETE token checks -> record gone -> scheduled() removes an
// orphan R2 object.

import assert from "node:assert/strict";
import worker from "./src/index.js";

// ----- Mock KV: Map-backed, ignores TTL (tests never wait for expiry) -----
class MockKV {
  constructor() {
    this.store = new Map();
  }
  async get(key) {
    return this.store.has(key) ? this.store.get(key) : null;
  }
  async put(key, value) {
    this.store.set(key, String(value));
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

const env = { TRANSFERS: new MockKV(), FILES: new MockR2() };
const ctx = { waitUntil(p) { this.promise = p; } };

function call(method, path, options = {}) {
  const request = new Request("https://test.local" + path, { method, ...options });
  return worker.fetch(request, env, ctx);
}

const json = async (res) => JSON.parse(await res.text());

// ----- 1. Register a transfer -----
const payloadBytes = new TextEncoder().encode("FAKE-APK-BYTES-0123456789");
const meta = {
  appName: "测试应用",
  packageName: "com.example.test",
  versionName: "1.0.0",
  sizeBytes: String(payloadBytes.length), // the Android client sends strings
  sha256: "deadbeef",
  fileName: "test.apk",
};
let res = await call("POST", "/t", {
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(meta),
});
assert.equal(res.status, 200, "POST /t should succeed");
const created = await json(res);
assert.match(created.code, /^\d{6}$/, "code should be 6 digits");
assert.ok(created.uploadToken && created.uploadToken.length >= 32, "uploadToken should be 32+ hex chars");
assert.ok(created.deleteToken, "deleteToken should be returned");
console.log("POST /t -> code", created.code, "uploadToken", created.uploadToken.length, "hex chars");

// ----- 2. Not ready yet, and no tokens leak -----
res = await call("GET", "/t/" + created.code);
assert.equal(res.status, 200);
const before = await json(res);
assert.equal(before.ready, false, "ready should be false before upload");
assert.equal(before.appName, "测试应用");
assert.equal(before.deleteToken, undefined, "GET /t must not leak deleteToken");
assert.equal(before.uploadToken, undefined, "GET /t must not leak uploadToken");
console.log("GET /t/:code before upload -> ready=false, no tokens leaked");

// ----- 3. Upload the file with the token -----
res = await call("PUT", "/f/" + created.uploadToken, {
  headers: { "Content-Type": "application/vnd.android.package-archive" },
  body: payloadBytes,
});
assert.equal(res.status, 200, "PUT /f/:token should succeed");
assert.deepEqual(await json(res), { ok: true });
console.log("PUT /f/:uploadToken -> ok");

// ----- 4. Ready now -----
res = await call("GET", "/t/" + created.code);
const after = await json(res);
assert.equal(after.ready, true, "ready should be true after upload");
console.log("GET /t/:code after upload -> ready=true");

// ----- 5. Download returns the identical bytes with the APK content type -----
res = await call("GET", "/f/" + created.code);
assert.equal(res.status, 200);
assert.equal(
  res.headers.get("Content-Type"),
  "application/vnd.android.package-archive"
);
assert.match(res.headers.get("Content-Disposition") || "", /test\.apk/);
const downloaded = new Uint8Array(await res.arrayBuffer());
assert.deepEqual(downloaded, payloadBytes, "downloaded bytes must be identical");
console.log("GET /f/:code -> identical bytes, APK content type");

// ----- 6. The upload token is single-use -----
res = await call("PUT", "/f/" + created.uploadToken, { body: payloadBytes });
assert.equal(res.status, 404, "reusing the upload token should fail");
console.log("PUT /f/:uploadToken again -> 404 (single-use)");

// ----- 7. Oversize guard: registered size 10 bytes, body ~1.1MB -----
res = await call("POST", "/t", {
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ ...meta, sizeBytes: "10" }),
});
assert.equal(res.status, 200);
const created2 = await json(res);
const bigBody = new Uint8Array(1100 * 1024);
res = await call("PUT", "/f/" + created2.uploadToken, { body: bigBody });
assert.equal(res.status, 413, "wildly oversize upload should be rejected");
console.log("PUT oversize body -> 413");

// ----- 8. DELETE needs the right deleteToken -----
res = await call("DELETE", "/t/" + created.code + "?token=wrong-token");
assert.equal(res.status, 403, "DELETE with wrong token should fail");
res = await call("DELETE", "/t/" + created.code + "?token=" + created.deleteToken);
assert.equal(res.status, 200, "DELETE with right token should succeed");
assert.deepEqual(await json(res), { ok: true });
res = await call("GET", "/t/" + created.code);
assert.equal(res.status, 404, "record should be gone after DELETE");
assert.equal(await env.FILES.get("files/" + created.code), null, "R2 object should be gone too");
console.log("DELETE wrong token -> 403, right token -> ok, record + file gone");

// ----- 9. scheduled() removes orphan R2 objects -----
await env.FILES.put("files/999999", new TextEncoder().encode("orphan"));
assert.notEqual(await env.FILES.get("files/999999"), null);
await worker.scheduled({ cron: "0 * * * *" }, env, { waitUntil() {} });
assert.equal(await env.FILES.get("files/999999"), null, "orphan should be removed by scheduled()");
console.log("scheduled() -> orphan R2 object removed");

console.log("ALL TESTS PASSED");
