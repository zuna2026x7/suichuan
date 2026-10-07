# 随传取件服务（Cloudflare Worker + KV + R2）

一个单文件的 Cloudflare Worker，管两样东西：

- **取件码记录（KV `TRANSFERS`）**：每次传输的元数据（应用名、大小、校验值、文件名）、删除/上传凭证和「是否已上传」标记，按 6 位取件码存，最长 72 小时过期。
- **安装包文件（R2 `FILES`）**：文件本体存在 R2 的 `files/<取件码>` 下，通过 Worker 上传和下载。

也就是说，只要这个后端部署好了，**App 就完全不依赖任何第三方临时托管**：发送端向 Worker 要取件码、把文件 PUT 上去，接收端凭取件码查信息、再把文件下载下来。只有在 App 没有配置后端地址时，才会退回 Litterbox 临时托管 + 二维码/分享文字的模式。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/t` | 传元数据 JSON（`appName / packageName / versionName / sizeBytes / sha256 / fileName`，可带 `ttlSeconds`，最长 72h，默认 24h），返回 `{ code, deleteToken, uploadToken, ttlSeconds }`。此时还没有文件。 |
| PUT | `/f/:uploadToken` | 上传文件原始内容（不是表单）。凭证一次性使用，成功后即失效；文件大小和登记的差太多（>1MB）会被拒绝。成功后该取件码变为可下载。 |
| GET | `/t/:code` | 用 6 位取件码查元数据，返回里多一个 `ready`：`true` 表示文件已上传可下载，`false` 表示对方还没传完。返回内容**不含**任何凭证。 |
| GET | `/f/:code` | 下载文件，`Content-Type: application/vnd.android.package-archive`，并带登记的文件名。 |
| DELETE | `/t/:code?token=...` | 发送方提前删除；必须带 `deleteToken`（也可以用请求头 `x-delete-token`）。记录、上传凭证和 R2 文件一起删。 |
| GET | `/` 或 `/health` | 健康检查，返回 `{ ok: true }`。 |

另外有一个每小时的定时任务（Cron Trigger）：KV 记录过期后，把 R2 里没人认领的孤儿文件删掉，免得桶越堆越满。

已开启 CORS，方便以后做网页版接收页。

本地自测（不需要 wrangler、不需要网络）：

```bash
node worker/test-local.mjs   # 用内存版 KV/R2 把全流程跑一遍
node --check worker/src/index.js
```

## 部署步骤（Cloudflare 控制台方式）

需要一个 Cloudflare 账号（免费即可）。既可以用下面的网页控制台方式，也可以用后面的 wrangler 命令行方式，二选一。

### 控制台方式

1. **建 KV**：控制台 → Storage & Databases → KV → Create namespace，名字随意（比如 `suichuan-transfers`）。
2. **建 R2 桶**：控制台 → R2 → Create bucket，桶名必须是 `suichuan-files`（和 wrangler.toml.example 里保持一致；改了桶名也要同步改配置）。R2 第一次用可能要求先在控制台开通（免费额度内不收费）。
3. **建 Worker**：控制台 → Workers & Pages → Create Worker，名字比如 `suichuan-pickup`，把 `src/index.js` 的内容粘贴进去部署（或用 wrangler 从本目录部署，见下）。
4. **绑定（最关键的一步）**：进这个 Worker 的 Settings → Bindings，添加两个绑定，变量名必须一字不差：
   - KV Namespace 绑定：变量名 `TRANSFERS` → 选第 1 步的 KV。
   - R2 Bucket 绑定：变量名 `FILES` → 选第 2 步的桶 `suichuan-files`。
   绑定完要重新部署一次才生效。
5. **加定时任务**：Worker 的 Settings（或 Triggers）→ Cron Triggers → Add，表达式填 `0 * * * *`（每小时整点清理孤儿文件）。

### wrangler 命令行方式

```bash
cd worker
npm install -g wrangler        # 或者用 npx wrangler
npx wrangler login

npx wrangler kv namespace create TRANSFERS     # 把输出的 id 填进 wrangler.toml
npx wrangler r2 bucket create suichuan-files
cp wrangler.toml.example wrangler.toml
# 编辑 wrangler.toml，把 REPLACE_WITH_YOUR_KV_NAMESPACE_ID 换成上面的 id
# （R2 绑定、每小时 Cron 都已经写在示例文件里了）
npx wrangler deploy
```

部署成功会得到一个地址，形如 `https://suichuan-pickup.<你的子域>.workers.dev`。

## 让 App 用上后端

把这个地址填到项目根目录 `gradle.properties` 里：

```
transferApiBase=https://suichuan-pickup.<你的子域>.workers.dev
```

重新打包后：发送走后端（取件码 + 文件都存在自己这里），接收端输 6 位码就能下载；后端万一连不上，发送页会报错并给出「改用临时托管」的按钮，不会卡死。不填（留空）时 App 只用 Litterbox + 二维码/分享文字工作。

## 免费额度与限制（先说清楚）

- **单个文件上限约 100MB**：文件要经过 Worker 中转，Cloudflare 免费版对经过 Worker 的请求体有约 100MB 上限，超大的应用（大型游戏）传不了，这是目前这套方案的硬顶。
- **R2 免费额度 10GB**：存量超过会开始计费或拒绝写入；文件最长只存 72 小时，加上每小时清理，正常个人使用离这个上限很远。
- KV 免费额度对个人使用完全够。

## 安全说明

6 位数字码只有 100 万种组合，本质上是「方便手输的入口」，不是保险箱。代码里已经做了：最长 72 小时过期、上传必须凭一次性 uploadToken、删除必须凭 deleteToken、查码不返回任何凭证、上传大小校验。如果以后要公开给陌生人用，请在 Cloudflare 上再加一层限流（Rate Limiting 规则），限制同一 IP 猜码的频率。
