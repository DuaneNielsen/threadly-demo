# Caddy reverse proxy (cloud VM)

Caddy fronts the demo at `https://demo.agenticdemo.dev/`, terminating TLS (Let's Encrypt via tls-alpn-01) and gating browser traffic through oauth2-proxy. The `/webhook` endpoint bypasses oauth (bearer-token-protected by `server.js` instead).

## Install

```bash
# Caddy itself comes from the Cloudsmith apt package; the unit at
# /lib/systemd/system/caddy.service is package-provided. We only ship config.
sudo install -m 0644 Caddyfile /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy   # or restart on first install
```

## Routing

```
demo.agenticdemo.dev
  POST /webhook        -> 127.0.0.1:5000  (no oauth; bearer-protected by server.js)
       /oauth2/*       -> 127.0.0.1:4180  (oauth2-proxy: sign-in, callback, sign-out)
       /*              -> 127.0.0.1:5000  (oauth-gated via forward_auth)
```

On a 401 from `/oauth2/auth`, Caddy redirects the browser to `/oauth2/sign_in?rd=…` so the user lands on the sign-in flow rather than a bare 401.

## What's NOT here

- **Rate limiting on `/webhook`** — needs the third-party `caddy-ratelimit` plugin (not in mainline Caddy 2). Skipped because (a) bearer auth gates incoming requests, and (b) `server.js` enforces a 60-second cooldown between Claude dispatches, capping cost at ~$0.16/min worst-case if the bearer ever leaks.
- **Custom Caddy build** — we use stock apt-installed Caddy so apt upgrades work.

## Cert storage

Caddy stores ACME state under `/var/lib/caddy/.local/share/caddy/`. Don't delete unless intentionally re-issuing.
