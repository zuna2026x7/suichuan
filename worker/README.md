# 随传取件码服务（Cloudflare Worker）

一个单文件的 Cloudflare Worker，只做一件事：把一次传输的**元数据**（应用名、大小、校验值、下载链接）存到 KV 里，给它一个 6 位取件码。

**文件本身不在这里。** 在这个 MVP 里，安装包文件上传到 Litterbox（猫盒的临时服务），72 小时自动过期；Worker 只管「取件码 → 元数据」的查询。所以 Worker 没有流量成本，KV 免费额度完全够用。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/t` | 传一份 payload JSON（`appName / packageName / versionName / sizeBytes / sha256 / fileName / downloadUrl`，可带 `ttlSeconds`），返回 `{ code, deleteToken, ttlSeconds }`。有效期最长 72 小时，默认 24 小时。 |
| GET | `/t/:code` | 用 6 位取件码查元数据；不存在/过期返回 404 和一句人话错误。返回内容里不含 deleteToken。 |
| DELETE | `/t/:code?token=...` | 发送方提前删除；必须带注册时返回的 `deleteToken`（也可以用请求头 `x-delete-token`）。 |
| GET | `/` 或 `/health` | 健康检查，返回 `{ ok: true }`。 |

已开启 CORS，方便以后做网页版接收页。

## 部署步骤

需要一个 Cloudflare 账号（免费即可）和 Node.js。

```bash
cd worker
npm install -g wrangler        # 或者用 npx wrangler
npx wrangler login

# 1) 建 KV，并把输出的 id 填进 wrangler.toml
npx wrangler kv namespace create TRANSFERS
cp wrangler.toml.example wrangler.toml
# 编辑 wrangler.toml，把 REPLACE_WITH_YOUR_KV_NAMESPACE_ID 换成上一步的 id

# 2) 部署
npx wrangler deploy
```

部署成功会得到一个地址，形如 `https://suichuan-pickup.<你的子域>.workers.dev`。

## 让 App 用上取件码

把这个地址填到项目根目录 `gradle.properties` 里：

```
transferApiBase=https://suichuan-pickup.<你的子域>.workers.dev
```

重新打包后，发送完成页就会显示 6 位取件码；不填（留空）时 App 只用二维码/分享文字工作，不受影响。

本地调试：`npx wrangler dev`，然后把 `transferApiBase` 临时指向它给出的本地地址（注意安卓真机不能用电脑的 localhost，要用局域网 IP 或部署后的地址）。

## 安全说明（先说清楚）

6 位数字码只有 100 万种组合，本质上是「方便手输的入口」，不是保险箱。代码里已经做了：最长 72 小时过期、删除必须凭 deleteToken、查码不返回 deleteToken。如果以后要公开给陌生人用，请在 Cloudflare 上再加一层限流（Rate Limiting 规则），限制同一 IP 猜码的频率。真正高熵的是二维码/分享文字里的完整内容，那条路径不依赖这个服务。
