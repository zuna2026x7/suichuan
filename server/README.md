# 随传取件服务 — Node 版 (suichuan-pickup)

A Node.js port of the Cloudflare Worker in `../worker/`, for running on a
plain Linux server (e.g. a domestic cloud VPS so receiver phones in China
don't have to cross the border to workers.dev). API-compatible with the
Worker endpoint for endpoint — the Android app only needs its
`transferApiBase` pointed at this server's origin.

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

Don't forget the firewall / cloud security group: inbound TCP **8080**
must be open for the phones to reach it.

## Configuration (environment variables)

| Variable            | Default             | Meaning                                  |
| ------------------- | ------------------- | ---------------------------------------- |
| `PORT`              | `8080`              | Listen port                              |
| `HOST`              | `0.0.0.0`           | Listen address                           |
| `SUICHUAN_DATA_DIR` | `<server dir>/data` | Where records + files are stored         |
| `SUICHUAN_MAX_BYTES`| `104857600` (100MiB)| Absolute per-file cap (matches the Worker's Cloudflare limit) |
| `SUICHUAN_SWEEP_MS` | `600000` (10 min)   | How often expired transfers are swept    |

## Storage layout

```
data/
  records/<code>.json   one record per pickup code (metadata + tokens + TTL)
  files/<code>.bin      the uploaded package file
```

Records expire with their TTL (72h max, same as the Worker): expiry is
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
