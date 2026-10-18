# 随传取件服务 (suichuan-pickup)

The pickup-code backend for the Android app: creates pickup codes, stores
uploaded apps / files / text for up to 72 hours, serves downloads (with
Range support) and accepts log uploads. Runs on a plain Linux server (e.g. a
domestic cloud VPS, so receiver phones in China don't have to cross the
border). The app reaches it through `transferApiBase` in the root
`gradle.properties` (currently `http://198.200.49.120:8081`).

It started as a port of the Cloudflare Worker that early versions used;
the Worker was retired in v0.6 and its directory removed, so this is now the
only backend. Some comments still mention "KV" / "the worker" where the
behaviour was kept compatible.

## API

| Method & path      | Purpose                                                     |
| ------------------ | ----------------------------------------------------------- |
| `POST /t`          | Create a transfer (`kind` = app / file / text, metadata, `ttlSeconds` ≤ 72h). Returns `{ code, uploadToken, deleteToken, ttlSeconds }`. Text is stored inline (≤ 64 KB). |
| `PUT /f/:uploadToken` | Upload the file body (≤ `SUICHUAN_MAX_BYTES`); the token is single-use. |
| `GET /t/:code`     | Look up a transfer's metadata by pickup code.               |
| `GET /f/:code`     | Download the file (supports `Range`).                       |
| `DELETE /t/:code`  | Delete early; needs `deleteToken` as `?token=` or `x-delete-token`. |
| `POST /log`        | Upload an app log (≤ 1 MiB).                                |
| `GET /`, `/health` | Health check: `{"ok":true,"service":"suichuan-pickup",...}` |

Errors are JSON `{ "error": "中文说明" }`; the app shows that text as is.

Pure Node standard library — **no npm dependencies**. Node 18+.

## Run it manually

```bash
cd server
node index.js
# suichuan-pickup (node) listening on http://0.0.0.0:8080 data dir: .../server/data
```

Smoke test:

```bash
curl http://127.0.0.1:8080/
# {"ok":true,"service":"suichuan-pickup","runtime":"node"}
```

## Run it as a service (systemd)

Assuming the repo lives at `/opt/suichuan` (so this directory is
`/opt/suichuan/server` — adjust the unit's `WorkingDirectory` if not):

```bash
sudo cp server/suichuan-pickup.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now suichuan-pickup
sudo systemctl status suichuan-pickup
sudo journalctl -u suichuan-pickup -f   # logs
```

Don't forget the firewall / cloud security group: the listen port must be
open for the phones to reach it (default **8080**; the current production
deployment is reached on **8081**, so keep the unit's `PORT` and the app's
`transferApiBase` in step).

Files bigger than `SUICHUAN_MAX_BYTES` are rejected; the app knows the
100 MiB limit and sends anything larger through Litterbox instead, so if you
raise the limit here, raise `BACKEND_MAX_BYTES` in the app too.

## Configuration (environment variables)

| Variable            | Default             | Meaning                                  |
| ------------------- | ------------------- | ---------------------------------------- |
| `PORT`              | `8080`              | Listen port                              |
| `HOST`              | `0.0.0.0`           | Listen address                           |
| `SUICHUAN_DATA_DIR` | `<server dir>/data` | Where records + files are stored         |
| `SUICHUAN_MAX_BYTES`| `104857600` (100MiB)| Absolute per-file cap (the app's `BACKEND_MAX_BYTES` matches it) |
| `SUICHUAN_SWEEP_MS` | `600000` (10 min)   | How often expired transfers are swept    |

## Storage layout

```
data/
  records/<code>.json   one record per pickup code (metadata + tokens + TTL)
  files/<code>.bin      the uploaded package file
```

Records expire with their TTL (72h max; the app always asks for 72h): expiry is
enforced lazily on access, at startup, and by the periodic sweep, which
deletes the record and its file together.

## Tests

```bash
node server/test-local.mjs
```

Spawns the server on a random port with a temp data dir and exercises the
full flow, Range downloads, oversize rollback, persistence and expiry.
Note: the final natural-expiry check waits ~61s, because TTLs are clamped
to a 60s minimum (same floor as Workers KV).
