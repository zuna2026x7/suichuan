// Local end-to-end test of the Node pickup server (server/index.js).
//
//   node server/test-local.mjs
//
// Spawns the real server on a random port with a temp data dir and drives
// it over HTTP: full flow, a >10MB round trip, Range cases, oversize
// rollback (with and without Content-Length), a declared-oversize
// create rejected up front, POST /log storage + its 413 cap, text
// transfers (exact round-trip, born ready, PUT -> 409, 64KiB cap,
// empty -> 400), delete-token checks,
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
async function startServer(extraEnv = {}, dir = dataDir) {
  const child = spawn(process.execPath, ["index.js"], {
    cwd: serverDir,
    env: {
      ...process.env,
      PORT: "0",
      HOST: "127.0.0.1",
      SUICHUAN_DATA_DIR: dir,
      ...extraEnv,
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

  // ----- POST /t fails fast on an oversize declaration (no record made) -----
  {
    const capDir = fs.mkdtempSync(
      path.join(os.tmpdir(), "suichuan-node-cap-")
    );
    const capServer = await startServer(
      { SUICHUAN_MAX_BYTES: String(1024 * 1024) },
      capDir
    );
    try {
      const over = await createTransfer(capServer.base, {
        sizeBytes: String(2 * 1024 * 1024),
      });
      eq("POST /t oversize declaration -> 413", over.res.status, 413);
      eq(
        "POST /t oversize message",
        over.body.error,
        "文件太大了：单个应用最大支持 1MB。"
      );
      eq("POST /t oversize returns no code", over.body.code, undefined);
      check(
        "POST /t oversize wrote no record",
        fs.readdirSync(path.join(capDir, "records")).length === 0,
        fs.readdirSync(path.join(capDir, "records")).join(",")
      );
      // A normal create at/under the cap still succeeds afterwards.
      const ok = await createTransfer(capServer.base, {
        sizeBytes: "1024",
      });
      eq("POST /t normal create after 413", ok.res.status, 200);
      check(
        "Normal create code is 6 digits",
        /^\d{6}$/.test(ok.body.code),
        ok.body.code
      );
    } finally {
      await stopServer(capServer.child);
      fs.rmSync(capDir, { recursive: true, force: true });
    }
  }

  // ----- POST /log stores the client diagnostic log -----
  {
    const logsDir = path.join(dataDir, "logs");
    const logBody = "2026-10-07 10:00:00.000 [DL] probe -> 206 total=1234\n";
    const res = await fetch(base + "/log", {
      method: "POST",
      headers: {
        "Content-Type": "text/plain; charset=utf-8",
        "x-suichuan-info": "0.1.0;Pixel 8;16",
      },
      body: logBody,
    });
    eq("POST /log status", res.status, 200);
    eq("POST /log ok flag", (await res.json()).ok, true);
    const files1 = fs.existsSync(logsDir) ? fs.readdirSync(logsDir) : [];
    eq("POST /log stores one file", files1.length, 1);
    check(
      "log filename shape",
      files1.length === 1 &&
        /^\d{4}-\d{2}-\d{2}T.*-[0-9a-f]{6}\.log$/.test(files1[0]),
      files1.join(",")
    );
    const stored =
      files1.length === 1
        ? fs.readFileSync(path.join(logsDir, files1[0]), "utf8")
        : "";
    check(
      "log first line is # info header",
      stored.startsWith("# 0.1.0;Pixel 8;16\n"),
      JSON.stringify(stored.slice(0, 40))
    );
    check("log body stored verbatim", stored.endsWith(logBody), "");

    // Without the info header the body is stored exactly as sent.
    const plainBody = "line one\nline two\n";
    const res2 = await fetch(base + "/log", {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: plainBody,
    });
    eq("POST /log no header status", res2.status, 200);
    const files2 = fs.readdirSync(logsDir);
    eq("POST /log second file stored", files2.length, 2);
    check(
      "no-header log content equals body exactly",
      files2
        .map((f) => fs.readFileSync(path.join(logsDir, f), "utf8"))
        .includes(plainBody),
      ""
    );

    // Over the 1 MiB cap -> 413, and nothing new is stored.
    const res3 = await fetch(base + "/log", {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: Buffer.alloc(1024 * 1024 + 1, 0x61),
    });
    eq("POST /log oversize -> 413", res3.status, 413);
    eq(
      "POST /log oversize stored nothing",
      fs.readdirSync(logsDir).length,
      2
    );
  }

  // ----- Text transfers: the message itself is the payload -----
  {
    const sampleText = "你好，随传！📱 这是一条测试文字。\n第二行也在这里。";
    const res = await fetch(base + "/t", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        kind: "text",
        text: sampleText,
        ttlSeconds: 259200,
      }),
    });
    eq("POST /t text status", res.status, 200);
    const textCreated = await res.json();
    check(
      "text code is 6 digits",
      /^\d{6}$/.test(textCreated.code),
      textCreated.code
    );
    check(
      "text create returns an uploadToken",
      /^[0-9a-f]{48}$/.test(textCreated.uploadToken),
      textCreated.uploadToken
    );
    eq("text ttlSeconds echoed", textCreated.ttlSeconds, 259200);

    const metaRes = await fetch(base + "/t/" + textCreated.code);
    const view = await metaRes.json();
    eq("GET /t text status", metaRes.status, 200);
    eq("text record is ready at creation", view.ready, true);
    eq("text kind in view", view.kind, "text");
    eq("text round-trips exactly", view.text, sampleText);
    eq("text appName defaults", view.appName, "文字消息");
    eq(
      "text sizeBytes is the UTF-8 byte length",
      view.sizeBytes,
      Buffer.byteLength(sampleText, "utf8")
    );
    eq("text view leaks no deleteToken", view.deleteToken, undefined);
    eq("text view leaks no uploadToken", view.uploadToken, undefined);

    // Nothing is left to upload: the token was born spent, so a PUT
    // against it gets the already-uploaded answer, not a 404.
    const put = await fetch(base + "/f/" + textCreated.uploadToken, {
      method: "PUT",
      body: Buffer.from("nope"),
    });
    eq("PUT to a text transfer -> 409", put.status, 409);
    eq(
      "PUT to a text transfer message",
      (await put.json()).error,
      "这个文件已经上传过了。"
    );

    // Exactly at the 64 KiB cap is fine.
    const atCap = await fetch(base + "/t", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ kind: "text", text: "a".repeat(65536) }),
    });
    eq("text of exactly 65536 bytes -> 200", atCap.status, 200);
    const atCapBody = await atCap.json();
    const atCapMeta = await fetch(base + "/t/" + atCapBody.code);
    eq(
      "at-cap text sizeBytes",
      (await atCapMeta.json()).sizeBytes,
      65536
    );
    const delCap = await fetch(base + "/t/" + atCapBody.code, {
      method: "DELETE",
      headers: { "x-delete-token": atCapBody.deleteToken },
    });
    eq("at-cap text cleanup DELETE", delCap.status, 200);
    await delCap.text();

    // One byte over the cap -> 413, and no code is issued.
    const over = await fetch(base + "/t", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ kind: "text", text: "a".repeat(65537) }),
    });
    eq("text of 65537 bytes -> 413", over.status, 413);
    eq(
      "oversize text message",
      (await over.json()).error,
      "文字太长了：一段文字最多 64KB。"
    );

    // Empty, whitespace-only and missing text -> 400.
    for (const bad of [
      { kind: "text", text: "" },
      { kind: "text", text: "   \n " },
      { kind: "text" },
      { kind: "text", text: 42 },
    ]) {
      const r = await fetch(base + "/t", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(bad),
      });
      eq("empty/missing text -> 400", r.status, 400);
      eq(
        "empty text message",
        (await r.json()).error,
        "文字内容是空的，没法生成取件码。"
      );
    }

    // DELETE removes a text transfer like any other.
    const del = await fetch(base + "/t/" + textCreated.code, {
      method: "DELETE",
      headers: { "x-delete-token": textCreated.deleteToken },
    });
    eq("text DELETE status", del.status, 200);
    eq("text DELETE ok", (await del.json()).ok, true);
    const gone = await fetch(base + "/t/" + textCreated.code);
    eq("GET /t text after DELETE -> 404", gone.status, 404);
    await gone.text();
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
    eq("no kind on a file record", body.kind, undefined);
    eq("no text on a file record", body.text, undefined);
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
