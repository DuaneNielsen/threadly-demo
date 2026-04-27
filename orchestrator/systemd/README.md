# systemd units (cloud VM)

Production unit files installed at `/etc/systemd/system/` on the GCE VM (`threadly-demo` in `agentic-sre-demo`). Source-of-truth lives here so the cloud deploy is reproducible.

## Install

```bash
sudo install -m 0644 -t /etc/systemd/system \
  threadly.service threadly-payments.service demo-server.service \
  monitoring.service oauth2-proxy.service
sudo systemctl daemon-reload
```

## Dependencies

- `threadly-payments.service` — independent JVM, starts first
- `threadly.service` — Requires `threadly-payments.service`
- `demo-server.service` — After `threadly.service` (soft dep)
- `monitoring.service` — After `docker.service`
- `oauth2-proxy.service` — independent (Phase F: needs `/etc/oauth2-proxy/client-id.env` + `emails.txt` to start)
- `caddy.service` — apt-provided, config in `/etc/caddy/Caddyfile`

## Env files

Read from `/etc/threadly-demo/*.env` (root:root mode 600), populated by Phase D from Secret Manager:

- `anthropic.env` — `ANTHROPIC_API_KEY`
- `webhook.env` — `WEBHOOK_BEARER_TOKEN`
- `gh.env` — `GH_TOKEN` (bot PAT)
- `oauth2-proxy.env` — `OAUTH2_PROXY_CLIENT_SECRET`, `OAUTH2_PROXY_COOKIE_SECRET`

`OAUTH2_PROXY_CLIENT_ID` lives at `/etc/oauth2-proxy/client-id.env` (not in Secret Manager — public-by-design value).

## Operator

All JVM/Node units run as the OS Login user `duane_nielsen_rocks_gmail_com` (matches `/opt/threadly-demo/` ownership). `monitoring.service` runs as root for docker access. `oauth2-proxy.service` runs as a dedicated `oauth2-proxy` system user (created in Phase F).

## Iteration

```bash
gcloud compute ssh threadly-demo --zone=us-central1-a --tunnel-through-iap \
  --project=agentic-sre-demo
cd /opt/threadly-demo && git pull
sudo systemctl restart threadly threadly-payments demo-server
```
