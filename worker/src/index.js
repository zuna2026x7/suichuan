// 随传取件码服务 (Suichuan pickup-code service)
//
// A deliberately tiny Cloudflare Worker. It only stores the *metadata* of a
// transfer (app name, size, checksum, download URL) under a 6-digit pickup
// code in KV. The package file itself never passes through here — in this
// MVP it lives on Litterbox and expires there after 72h.
//
// Abuse note: a 6-digit code has only 1,000,000 combinations, so treat codes
// as a convenience, not a secret. Entries expire quickly (max 72h), a code
// is deleted-verifiable only with its deleteToken, and if this ever serves
// the public you should add rate limiting (e.g. Cloudflare Rate Limiting
// rules or a Durable Object counter per IP/code) in front of GET /t/:code.
// The QR code / share text carries the full payload and does not depend on
// this service at all.

const MAX_TTL_SECONDS = 72 * 60 * 60; // 72h, matches Litterbox
const DEFAULT_TTL_SECONDS = 24 * 60 * 60; // 24h

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
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

function randomToken() {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type",
        },
      });
    }

    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter((p) => p.length > 0);

    // POST /t — register a transfer, get a pickup code.
    if (request.method === "POST" && parts.length === 1 && parts[0] === "t") {
      let payload;
      try {
        payload = await request.json();
      } catch (e) {
        return jsonResponse({ error: "请求内容不是有效的 JSON。" }, 400);
      }
      if (!payload || typeof payload.downloadUrl !== "string" || payload.downloadUrl.length === 0) {
        return jsonResponse({ error: "缺少 downloadUrl，没法生成取件码。" }, 400);
      }

      const ttlRaw = Number(payload.ttlSeconds);
      const ttlSeconds = Math.min(
        Number.isFinite(ttlRaw) && ttlRaw > 0 ? Math.floor(ttlRaw) : DEFAULT_TTL_SECONDS,
        MAX_TTL_SECONDS
      );

      const deleteToken = randomToken();
      const record = JSON.stringify({ ...payload, deleteToken });

      // Retry a few times if the random code is already taken.
      for (let attempt = 0; attempt < 8; attempt++) {
        const code = randomDigits(6);
        const existing = await env.TRANSFERS.get(code);
        if (existing !== null) continue;
        await env.TRANSFERS.put(code, record, { expirationTtl: ttlSeconds });
        return jsonResponse({ code, deleteToken, ttlSeconds });
      }
      return jsonResponse({ error: "取件码生成失败，请稍后再试。" }, 503);
    }

    // GET /t/:code — look a transfer up by its pickup code.
    if (request.method === "GET" && parts.length === 2 && parts[0] === "t") {
      const code = parts[1];
      if (!/^\d{6}$/.test(code)) {
        return jsonResponse({ error: "取件码应该是 6 位数字。" }, 400);
      }
      const stored = await env.TRANSFERS.get(code);
      if (stored === null) {
        return jsonResponse({ error: "这个取件码不存在，或者已经过期了。" }, 404);
      }
      const record = JSON.parse(stored);
      // Never hand out the delete token to whoever holds the code.
      delete record.deleteToken;
      return jsonResponse(record);
    }

    // DELETE /t/:code — sender-side early removal; needs the deleteToken
    // either as ?token=... or as an "x-delete-token" header.
    if (request.method === "DELETE" && parts.length === 2 && parts[0] === "t") {
      const code = parts[1];
      const token = url.searchParams.get("token") || request.headers.get("x-delete-token") || "";
      const stored = await env.TRANSFERS.get(code);
      if (stored === null) {
        return jsonResponse({ error: "这个取件码不存在，或者已经过期了。" }, 404);
      }
      const record = JSON.parse(stored);
      if (!token || token !== record.deleteToken) {
        return jsonResponse({ error: "删除凭证不对，不能删除。" }, 403);
      }
      await env.TRANSFERS.delete(code);
      return jsonResponse({ ok: true });
    }

    if (url.pathname === "/" || url.pathname === "/health") {
      return jsonResponse({ ok: true, service: "suichuan-pickup" });
    }

    return jsonResponse({ error: "没有这个接口。" }, 404);
  },
};
