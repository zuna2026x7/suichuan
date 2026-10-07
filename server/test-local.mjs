// Local end-to-end test of the Node pickup server (server/index.js).
//
//   node server/test-local.mjs
//
// Spawns the real server on a random port with a temp data dir and drives
// it over HTTP: full flow, a >10MB round trip, Range cases, oversize
// rollback (with and without Content-Length), delete-token checks,
// persistence across a restart, expiry after a restart, and finally a
// natural TTL expiry (ttlSeconds=1 clamps to the 60s minimum — the wait
// at the end is real, mirroring the worker's KV TTL floor).
//
// Prints PASS/FAIL lines; exits non-zero if anything failed.

import { spawn } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const serverDir = path.dirname(fileURLToPath(import.meta.url));
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "suichuan-node-test-"));
const recordsDir = path.join(dataDir, "records");
const filesDir = path.join(dataDir, "files");

const APK = "application/vnd.android.package-archive";
const meta = {
  appName: "测试应用",
  packageName: "com.example.test",
  versionName: "1.0.0",
  sha256: "deadbeef",
  fileName: "test.apk",
};

let failures = 0;
function check(name, cond, extra) {
  if (cond) {
    console.log("PASS:", name);
  } else {
    failures++;
    console.log("FAIL:", name, extra !== undefined ? String(extra) : "");
  }
}
function eq(name, actual, expected) {
  check(
    name,
    actual === expected,
    `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`
  );
}

const sha256 = (buf) => crypto.createHash("sha256").update(buf).digest("hex");

function patterned(n) {
  const buf = Buffer.alloc(n);
  for (let i = 0; i < n; i++) buf[i] = i % 251;
  return buf;
}

// ----- server process management -----
async function startServer() {
  const child = spawn(process.execPath, ["index.js"], {
    cwd: serverDir,
    env: {
      ...process.env,
      PORT: "0",
      HOST: "127.0.0.1",
      SUICHUAN_DATA_DIR: dataDir,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stderr.on("data", (d) => process.stderr.write("[server] " + d));
  const port = await new Promise((resolve, reject) => {
    let buf = "";
    const timer = setTimeout(
      () => reject(new Error("server did not start within 15s")),
      15000
    );
    child.stdout.on("data", (d) => {
      buf += d;
      const m = /listening on http:\/\/[^:]+:(\d+)/.exec(buf);
      if (m) {
        clearTimeout(timer);
        resolve(Number(m[1]));
      }
    });
    child.on("exit", (c) => {
      clearTimeout(timer);
      reject(new Error("server exited early with code " + c));
    });
  });
  return { child, base: `http://127.0.0.1:${port}` };
}

function stopServer(child) {
  return new Promise((resolve) => {
    let done = false;
    const finish = () => {
      if (!done) {
        done = true;
        resolve();
      }
    };
    child.once("exit", finish);
    try {
      child.kill("SIGTERM");
    } catch (e) {
      finish();
    }
    setTimeout(() => {
      try {
        child.kill("SIGKILL");
      } catch (e) {
        // already gone
      }
      finish();
    }, 3000);
  });
}

// PUT without a Content-Length header (chunked transfer encoding), the
// way the worker harness exercises the no-Content-Length oversize path.
function putChunked(urlString, chunks) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlString);
    const req = http.request(
      {
        hostname: u.hostname,
        port: u.port,
        path: u.pathname,
        method: "PUT",
        headers: { "Content-Type": APK },
      },
      (res) => {
        const bufs = [];
        res.on("data", (c) => bufs.push(c));
        res.on("end", () =>
          resolve({
            status: res.statusCode,
            headers: res.headers,
            body: Buffer.concat(bufs),
          })
        );
      }
    );
    req.on("error", reject);
    for (const c of chunks) req.write(c);
    req.end();
  });
}

async function createTransfer(base, overrides = {}) {
  const res = await fetch(base + "/t", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...meta, ...overrides }),
  });
  return { res, body: await res.json() };
}

let server = null;
try {
  server = await startServer();
  let base = server.base;

  // ----- Root -----
  {
    const res = await fetch(base + "/");
    const body = await res.json();
    eq("GET / status", res.status, 200);
    eq("GET / ok", body.ok, true);
    eq("GET / service", body.service, "suichuan-pickup");
  }

  // ----- POST /t validation -----
  {
    const res = await fetch(base + "/t", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{not json",
    });
    eq("POST /t invalid JSON -> 400", res.status, 400);
    eq(
      "POST /t invalid JSON message",
      (await res.json()).error,
      "请求内容不是有效的 JSON。"
    );

    const res2 = await fetch(base + "/t", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ appName: "x" }),
    });
    eq("POST /t missing fileName -> 400", res2.status, 400);
    eq(
      "POST /t missing fileName message",
      (await res2.json()).error,
      "缺少 fileName，没法生成取件码。"
    );
  }

  // ----- Full flow, small patterned payload (app sends strings) -----
  const small = patterned(4096);
  const { res: createRes, body: created } = await createTransfer(base, {
    sizeBytes: String(small.length),
    ttlSeconds: "259200",
  });
  eq("POST /t status", createRes.status, 200);
  check("code is 6 digits", /^\d{6}$/.test(created.code), created.code);
  check(
    "uploadToken is 48 hex",
    /^[0-9a-f]{48}$/.test(created.uploadToken),
    created.uploadToken
  );
  check(
    "deleteToken is 48 hex",
    /^[0-9a-f]{48}$/.test(created.deleteToken),
    created.deleteToken
  );
  eq("ttlSeconds echoed", created.ttlSeconds, 259200);

  {
    const res = await fetch(base + "/t/" + created.code);
    const body = await res.json();
    eq("GET /t before upload status", res.status, 200);
    eq("ready=false before upload", body.ready, false);
    eq("meta appName", body.appName, "测试应用");
    eq("meta sizeBytes is a number", body.sizeBytes, small.length);
    eq("no deleteToken leak", body.deleteToken, undefined);
    eq("no uploadToken leak", body.uploadToken, undefined);
  }
  {
    const res = await fetch(base + "/t/12345");
    eq("GET /t short code -> 400", res.status, 400);
    eq(
      "GET /t short code message",
      (await res.json()).error,
      "取件码应该是 6 位数字。"
    );
    const other = String((Number(created.code) + 1) % 1000000).padStart(6, "0");
    const res2 = await fetch(base + "/t/" + other);
    eq("GET /t unknown code -> 404", res2.status, 404);
    eq(
      "GET /t unknown code message",
      (await res2.json()).error,
      "这个取件码不存在，或者已经过期了。"
    );
  }
  {
    const res = await fetch(base + "/f/" + created.code);
    eq("GET /f before upload -> 404", res.status, 404);
    eq(
      "GET /f before upload message",
      (await res.json()).error,
      "文件还没有上传好，或者已经过期了。"
    );
    const res2 = await fetch(base + "/f/" + "0".repeat(48), {
      method: "PUT",
      body: small,
    });
    eq("PUT unknown token -> 404", res2.status, 404);
    eq(
      "PUT unknown token message",
      (await res2.json()).error,
      "上传凭证不存在或已经过期。"
    );
  }

  {
    const res = await fetch(base + "/f/" + created.uploadToken, {
      method: "PUT",
      headers: { "Content-Type": APK },
      body: small,
    });
    eq("PUT file status", res.status, 200);
    eq("PUT file ok", (await res.json()).ok, true);

    const again = await fetch(base + "/f/" + created.uploadToken, {
      method: "PUT",
      body: small,
    });
    eq("PUT token reuse -> 404 (single-use)", again.status, 404);
    await again.text();
  }
  {
    const res = await fetch(base + "/t/" + created.code);
    eq("ready=true after upload", (await res.json()).ready, true);

    const dl = await fetch(base + "/f/" + created.code);
    eq("GET /f status", dl.status, 200);
    eq("GET /f content-type", dl.headers.get("content-type"), APK);
    eq(
      "GET /f content-length",
      dl.headers.get("content-length"),
      String(small.length)
    );
    eq("GET /f accept-ranges", dl.headers.get("accept-ranges"), "bytes");
    check(
      "GET /f content-disposition carries fileName",
      (dl.headers.get("content-disposition") || "").includes("test.apk"),
      dl.headers.get("content-disposition")
    );
    const bytes = Buffer.from(await dl.arrayBuffer());
    check("GET /f bytes identical", bytes.equals(small));
  }

  // ----- Range cases on the small file -----
  {
    const total = small.length;
    const ranged = (range) =>
      fetch(base + "/f/" + created.code, { headers: { Range: range } });

    let res = await ranged("bytes=0-99");
    eq("Range 0-99 status", res.status, 206);
    eq(
      "Range 0-99 content-range",
      res.headers.get("content-range"),
      `bytes 0-99/${total}`
    );
    eq("Range 0-99 content-length", res.headers.get("content-length"), "100");
    check(
      "Range 0-99 bytes",
      Buffer.from(await res.arrayBuffer()).equals(small.subarray(0, 100))
    );

    // The app's probe:
    res = await ranged("bytes=0-0");
    eq("Range probe 0-0 status", res.status, 206);
    eq(
      "Range probe 0-0 content-range",
      res.headers.get("content-range"),
      `bytes 0-0/${total}`
    );
    await res.arrayBuffer();

    res = await ranged(`bytes=${total - 100}-`);
    eq("Range open-ended status", res.status, 206);
    check(
      "Range open-ended bytes",
      Buffer.from(await res.arrayBuffer()).equals(small.subarray(total - 100))
    );

    res = await ranged("bytes=-50");
    eq("Range suffix status", res.status, 206);
    eq(
      "Range suffix content-range",
      res.headers.get("content-range"),
      `bytes ${total - 50}-${total - 1}/${total}`
    );
    check(
      "Range suffix bytes",
      Buffer.from(await res.arrayBuffer()).equals(small.subarray(total - 50))
    );

    res = await ranged(`bytes=${total}-`);
    eq("Range start==size -> 416", res.status, 416);
    eq(
      "Range 416 content-range",
      res.headers.get("content-range"),
      `bytes */${total}`
    );
    await res.text();

    res = await ranged("banana");
    eq("Range garbage -> 200", res.status, 200);
    check(
      "Range garbage serves full body",
      Buffer.from(await res.arrayBuffer()).equals(small)
    );
  }

  // ----- DELETE token checks (header variant) -----
  {
    let res = await fetch(base + "/t/" + created.code, { method: "DELETE" });
    eq("DELETE no token -> 403", res.status, 403);
    await res.text();
    res = await fetch(base + "/t/" + created.code, {
      method: "DELETE",
      headers: { "x-delete-token": "wrong-token" },
    });
    eq("DELETE wrong token -> 403", res.status, 403);
    eq(
      "DELETE wrong token message",
      (await res.json()).error,
      "删除凭证不对，不能删除。"
    );
    res = await fetch(base + "/t/" + created.code, {
      method: "DELETE",
      headers: { "x-delete-token": created.deleteToken },
    });
    eq("DELETE right token status", res.status, 200);
    eq("DELETE right token ok", (await res.json()).ok, true);

    res = await fetch(base + "/t/" + created.code);
    eq("GET /t after DELETE -> 404", res.status, 404);
    await res.text();
    res = await fetch(base + "/f/" + created.code);
    eq("GET /f after DELETE -> 404", res.status, 404);
    await res.text();
    check(
      "record file removed",
      !fs.existsSync(path.join(recordsDir, created.code + ".json"))
    );
    check(
      "payload file removed",
      !fs.existsSync(path.join(filesDir, created.code + ".bin"))
    );
  }

  // ----- >10MB round trip -----
  const BIG_SIZE = 12 * 1024 * 1024;
  const big = patterned(BIG_SIZE);
  const { body: bigCreated } = await createTransfer(base, {
    sizeBytes: String(BIG_SIZE),
    fileName: "big.apk",
  });
  {
    const res = await fetch(base + "/f/" + bigCreated.uploadToken, {
      method: "PUT",
      headers: { "Content-Type": APK },
      body: big,
    });
    eq("PUT 12MiB status", res.status, 200);
    await res.json();

    const dl = await fetch(base + "/f/" + bigCreated.code);
    eq("GET 12MiB status", dl.status, 200);
    const bytes = Buffer.from(await dl.arrayBuffer());
    eq("GET 12MiB length", bytes.length, BIG_SIZE);
    eq("GET 12MiB sha256 identical", sha256(bytes), sha256(big));

    // A range straddling the worker's 10MiB chunk boundary offset.
    const CHUNK = 10 * 1024 * 1024;
    const res2 = await fetch(base + "/f/" + bigCreated.code, {
      headers: { Range: `bytes=${CHUNK - 50}-${CHUNK + 49}` },
    });
    eq("Range spanning status", res2.status, 206);
    eq(
      "Range spanning content-range",
      res2.headers.get("content-range"),
      `bytes ${CHUNK - 50}-${CHUNK + 49}/${BIG_SIZE}`
    );
    check(
      "Range spanning bytes identical",
      Buffer.from(await res2.arrayBuffer()).equals(
        big.subarray(CHUNK - 50, CHUNK + 50)
      )
    );

    // DELETE via the ?token= query variant.
    const del = await fetch(
      base + "/t/" + bigCreated.code + "?token=" + bigCreated.deleteToken,
      { method: "DELETE" }
    );
    eq("DELETE via query token status", del.status, 200);
    await del.json();
  }

  // ----- Oversize with Content-Length (header check) -----
  {
    const { body: c } = await createTransfer(base, { sizeBytes: "10" });
    const res = await fetch(base + "/f/" + c.uploadToken, {
      method: "PUT",
      body: Buffer.alloc(1100 * 1024, 7),
    });
    eq("Oversize with Content-Length -> 413", res.status, 413);
    eq(
      "Oversize message",
      (await res.json()).error,
      "上传的文件大小和登记的不一致，可能选错了文件。"
    );
    const metaRes = await fetch(base + "/t/" + c.code);
    eq("Oversize transfer not ready", (await metaRes.json()).ready, false);
    const del = await fetch(base + "/t/" + c.code, {
      method: "DELETE",
      headers: { "x-delete-token": c.deleteToken },
    });
    eq("Oversize cleanup DELETE", del.status, 200);
    await del.text();
  }

  // ----- Oversize without Content-Length (chunked): stored then rolled back -----
  {
    const { body: c } = await createTransfer(base, { sizeBytes: "10" });
    const res = await putChunked(base + "/f/" + c.uploadToken, [
      Buffer.alloc(1024 * 1024, 1),
      Buffer.alloc(1024 * 1024, 2),
    ]);
    eq("Oversize chunked -> 413", res.status, 413);
    eq(
      "Oversize chunked message",
      JSON.parse(res.body.toString("utf8")).error,
      "上传的文件大小和登记的不一致，可能选错了文件。"
    );
    check(
      "Oversize chunked left no payload",
      !fs.existsSync(path.join(filesDir, c.code + ".bin"))
    );
    check(
      "Oversize chunked left no temp files",
      fs
        .readdirSync(filesDir)
        .every((n) => !n.startsWith(".tmp-")),
      fs.readdirSync(filesDir).join(",")
    );
    const metaRes = await fetch(base + "/t/" + c.code);
    eq("Oversize chunked not ready", (await metaRes.json()).ready, false);

    // The token survives a rolled-back upload (worker parity): a
    // correct-size retry succeeds.
    const retry = await fetch(base + "/f/" + c.uploadToken, {
      method: "PUT",
      body: Buffer.alloc(10, 3),
    });
    eq("Retry after rollback status", retry.status, 200);
    await retry.json();
    const meta2 = await fetch(base + "/t/" + c.code);
    eq("Retry made it ready", (await meta2.json()).ready, true);
    const del = await fetch(base + "/t/" + c.code, {
      method: "DELETE",
      headers: { "x-delete-token": c.deleteToken },
    });
    eq("Rollback-flow cleanup DELETE", del.status, 200);
    await del.text();
  }

  // ----- Persistence across restart, then expiry across restart -----
  const persistBytes = patterned(2048);
  const { body: pc } = await createTransfer(base, {
    sizeBytes: String(persistBytes.length),
    fileName: "persist.apk",
  });
  {
    const res = await fetch(base + "/f/" + pc.uploadToken, {
      method: "PUT",
      body: persistBytes,
    });
    eq("Persistence setup PUT", res.status, 200);
    await res.json();
  }
  await stopServer(server.child);
  server = await startServer();
  base = server.base;
  {
    const res = await fetch(base + "/t/" + pc.code);
    const body = await res.json();
    eq("Record survives restart", res.status, 200);
    eq("Ready survives restart", body.ready, true);
    const dl = await fetch(base + "/f/" + pc.code);
    check(
      "Payload survives restart byte-identical",
      Buffer.from(await dl.arrayBuffer()).equals(persistBytes)
    );
  }
  // Force the persisted record into the past; the next boot must treat it
  // as expired (record + payload gone), like KV TTL having fired.
  await stopServer(server.child);
  {
    const recFile = path.join(recordsDir, pc.code + ".json");
    const rec = JSON.parse(fs.readFileSync(recFile, "utf8"));
    rec.expiresAt = Date.now() - 1000;
    fs.writeFileSync(recFile, JSON.stringify(rec));
  }
  server = await startServer();
  base = server.base;
  {
    const res = await fetch(base + "/t/" + pc.code);
    eq("Expired record after restart -> 404", res.status, 404);
    eq(
      "Expired record message",
      (await res.json()).error,
      "这个取件码不存在，或者已经过期了。"
    );
    check(
      "Expired record file removed at boot",
      !fs.existsSync(path.join(recordsDir, pc.code + ".json"))
    );
    check(
      "Expired payload file removed at boot",
      !fs.existsSync(path.join(filesDir, pc.code + ".bin"))
    );
  }

  // ----- Natural TTL expiry: ttlSeconds=1 clamps to the 60s floor -----
  console.log(
    "INFO: waiting ~61s for the natural-expiry check (worker TTL floor is 60s)..."
  );
  const { body: ec } = await createTransfer(base, {
    sizeBytes: "4",
    ttlSeconds: 1,
  });
  eq("ttlSeconds=1 clamps to 60", ec.ttlSeconds, 60);
  {
    const res = await fetch(base + "/f/" + ec.uploadToken, {
      method: "PUT",
      body: Buffer.from([1, 2, 3, 4]),
    });
    eq("Expiry-flow PUT", res.status, 200);
    await res.json();
    const metaRes = await fetch(base + "/t/" + ec.code);
    eq("Expiry-flow ready before expiry", (await metaRes.json()).ready, true);
  }
  await new Promise((r) => setTimeout(r, 61000));
  {
    const res = await fetch(base + "/t/" + ec.code);
    eq("Naturally expired record -> 404", res.status, 404);
    await res.text();
    const res2 = await fetch(base + "/f/" + ec.code);
    eq("Naturally expired file -> 404", res2.status, 404);
    await res2.text();
    check(
      "Naturally expired payload removed (lazy expiry)",
      !fs.existsSync(path.join(filesDir, ec.code + ".bin"))
    );
  }
} finally {
  if (server) await stopServer(server.child);
  fs.rmSync(dataDir, { recursive: true, force: true });
}

if (failures > 0) {
  console.log(`TESTS FAILED: ${failures} check(s) failed`);
  process.exit(1);
}
console.log("ALL TESTS PASSED");
