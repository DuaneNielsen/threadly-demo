# Cloud Deployment Plan — agentic-sre-demo on GCP

Goal: host the demo on GCP at ~$30/mo, gate access with Google login so coworkers/partners can use it, and isolate Claude credentials so the personal account is never at risk.

**Hostname:** `demo.agenticdemo.dev` (apex `agenticdemo.dev` purchased 2026-04-26 via Cloudflare Registrar, DNS managed in Cloudflare).

## Threat model

- **Public endpoint** — bots, abuse, free LLM proxy attempts
- **Box compromise** — exfil of API key, pivot to other systems
- **Rogue partner** — runs up Claude bills via the legitimate UI
- **Webhook path** — must stay reachable from machine sources (Fluent Bit, DX OI) but not be a free credential-less endpoint

## Why GCE VM (not Cloud Run / GKE)

The current stack is tightly coupled through the filesystem:

- `deploy.sh` kills the running JAR by port (`lsof -ti:8180`) and respawns it — needs persistent process tree
- Threadly + Payments + Fluent Bit share `/tmp/threadly.log`, `/tmp/payments.log` via bind mounts
- SQLite DBs live at `/tmp/*.db` and persist across deploys
- Phase-2 remediation runs `claude -p --dangerously-skip-permissions` to edit Java source and run `deploy.sh` — request-scoped containers can't host that

Cloud Run scales to zero and loses the state machine. GKE is overkill for one tenant. Lift-and-shift the existing docker-compose + JAR setup onto one small VM.

## Target architecture (cheap path — no Load Balancer)

```
                 Partners (Google accounts)
                          |
                          v
                +-----------------------+
                |  Caddy (TLS via LE)   |  port 443 on the VM
                +----------+------------+
                           |
                  path-routed
                           |
              /webhook -> bearer-token check, direct
                          to server.js (machine sources)
              /        -> oauth2-proxy (Google OAuth,
                          email allowlist) -> server.js
                           |
                           v
        +-------------------------------------------------+
        | GCE VM (e2-medium, Debian 12, OS Login)         |
        |  systemd units:                                 |
        |    caddy.service                                |
        |    oauth2-proxy.service                         |
        |    threadly.service        (port 8180)          |
        |    threadly-payments.service (port 8181)        |
        |    demo-server.service     (server.js, :5000)   |
        |    monitoring.service      (docker compose)     |
        |  claude CLI (API-key auth, no OAuth on disk)    |
        +-------------------------------------------------+
                          |
                Service account: read-only on
                two specific Secret Manager secrets
                          |
                          v
                +-------------------+
                | Secret Manager    |
                |  anthropic-api-key  (pay-as-you-go,
                |                      safety cap $50/mo)
                |  webhook-bearer-token
                |  oauth2-proxy-client-secret
                |  oauth2-proxy-cookie-secret
                +-------------------+
```

No HTTPS LB, no IAP. Caddy + oauth2-proxy on the VM provides the same Google-login UX for free.

## Security controls

### Claude credential isolation (the main thing you asked about)

1. Anthropic Console → Workspaces → create `agentic-sre-demo`
2. Generate API key inside that workspace. Pay-as-you-go billing — no monthly minimum. Set a *safety cap* (suggest $50/mo) so a runaway loop can't surprise you.
3. Push to Secret Manager: `gcloud secrets create anthropic-api-key --data-file=-`
4. VM startup pulls the secret, exports `ANTHROPIC_API_KEY` into `server.js`'s environment
5. Wipe `~/.claude` OAuth tokens on the VM — API-key auth only. Verify by running `claude config get` and confirming no subscription auth is present.
6. Personal account is now zero-blast-radius if the VM is compromised: rotate the workspace key and the box is dead.

### Access control

- **UI / SSE / `/execute` / `/simulate` / `/reset`** — Caddy proxies through oauth2-proxy. oauth2-proxy uses a Google OAuth client; allowlist is either `--email-domain` (allow whole Google Workspace domains) or `--authenticated-emails-file` (explicit list of emails). Adding a partner = SSH in, append email, `systemctl reload oauth2-proxy`.
- **`/webhook`** — Caddy bypasses oauth2-proxy on this path and forwards directly to `server.js`. `server.js` checks `Authorization: Bearer $WEBHOOK_BEARER_TOKEN`. Add a GCE firewall rule allowlisting the source IPs of DX OI / Fluent Bit if they're known.
- **SSH** — IAP TCP tunnel only (`gcloud compute ssh --tunnel-through-iap`). No public port 22, no firewall rule for `0.0.0.0/0:22`.

### Blast radius caps

- Anthropic spend cap = hard ceiling on AI cost
- `server.js` 60s cooldown + state machine = max one Claude dispatch in flight
- VM service account: `roles/secretmanager.secretAccessor` on **named secrets only**, nothing project-wide
- Shielded VM, OS Login enforced
- Caddy rate-limit on `/webhook` (Caddy `rate_limit` directive) — 10 rpm per IP

### Auditability

- Cloud Logging from the VM (stdout)
- oauth2-proxy logs every authenticated request with the user's email
- `/webhook` token-auth hits logged with source IP

## Deployment steps

### Phase 1: GCP project setup

1. Pick or create project. Enable APIs: `compute`, `secretmanager`, `dns` (if using Cloud DNS).
2. Create service account `demo-vm@PROJECT.iam.gserviceaccount.com`. No project-level roles yet — grant per-secret IAM in step 5.

### Phase 2: DNS

3. Hostname: `demo.agenticdemo.dev` — domain already owned, DNS at Cloudflare.
4. Create an A record `demo` → VM's static IP (provisioned in Phase 4). DNS-only, NOT proxied through Cloudflare (Caddy needs to see real client IPs and serve LE certs directly). Caddy will auto-provision a Let's Encrypt cert on first start.

### Phase 3: OAuth client + secrets

5. Google Cloud Console → APIs & Services → Credentials → create OAuth 2.0 Client ID (type: web). Authorized redirect URI: `https://demo.agenticdemo.dev/oauth2/callback`.
6. Anthropic Console: new workspace, new API key, $50/mo safety cap.
7. Generate two random secrets:
   - Webhook bearer: `openssl rand -base64 32`
   - oauth2-proxy cookie secret: `openssl rand -base64 32`
8. `gcloud secrets create` for: `anthropic-api-key`, `webhook-bearer-token`, `oauth2-proxy-client-secret`, `oauth2-proxy-cookie-secret`. Grant `secretAccessor` on each to the VM service account.

### Phase 4: VM provision

9. Reserve a static external IP (free while attached to a running VM).
10. Provision **e2-medium** (2 vCPU, 4 GB), Debian 12, shielded, OS Login on, attach `demo-vm` service account, attach the static IP, no public 22.
11. Startup script:
    - Install: `docker`, `docker compose`, `openjdk-21`, `nodejs-20`, `gh`, `caddy`, `oauth2-proxy`, the `claude` CLI.
    - Clone the three repos (agentic-sre-demo, threadly, threadly-payments).
    - Materialize `.env` and oauth2-proxy/Caddy configs from Secret Manager.
    - `./deploy-payments.sh v1.0 && ./deploy.sh v1.1`
    - `cd monitoring && docker compose up -d`
12. systemd units: `threadly`, `threadly-payments`, `demo-server`, `monitoring`, `oauth2-proxy`, `caddy`. All `Restart=on-failure`.

### Phase 5: Caddy + oauth2-proxy config

13. **oauth2-proxy** runs on `127.0.0.1:4180`. Key flags:
    - `--provider=google`
    - `--client-id`, `--client-secret` (from Secret Manager)
    - `--cookie-secret` (from Secret Manager)
    - `--authenticated-emails-file=/etc/oauth2-proxy/emails.txt` (start with your email + a couple partners)
    - `--reverse-proxy=true` `--upstream=http://127.0.0.1:5000`
14. **Caddy** Caddyfile sketch:
    ```
    demo.agenticdemo.dev {
        @webhook path /webhook
        handle @webhook {
            rate_limit { ... }
            reverse_proxy 127.0.0.1:5000
        }
        handle {
            forward_auth 127.0.0.1:4180 {
                uri /oauth2/auth
                copy_headers X-Auth-Request-Email
            }
            reverse_proxy 127.0.0.1:5000
        }
    }
    ```
15. server.js binds to `127.0.0.1:5000` only (LB-equivalent traffic comes from Caddy on the same box).

### Phase 6: server.js changes (small)

16. Add bearer-token check on `/webhook`. Skip the check if `WEBHOOK_BEARER_TOKEN` env var is unset (preserves local-dev behavior).
17. Read `X-Auth-Request-Email` header on UI requests, log it to `/tmp/claude-sre.log` so there's an audit trail of who triggered what.

### Phase 7: Verify

18. From a partner Google account: hit `https://demo.agenticdemo.dev/` → Google login → UI loads.
19. From an unlisted Google account: → 403 from oauth2-proxy.
20. `curl -X POST https://demo.agenticdemo.dev/webhook -d '{}'` (no bearer) → 401.
21. With bearer → analysis fires, streams to UI.
22. Click a remediation card; confirm `deploy.sh` swaps the JAR and the app comes back healthy.
23. Check oauth2-proxy logs for the partner's email.

## Cost

| Item | Monthly |
|---|---|
| e2-medium VM | ~$25 |
| Static IP (attached to running VM) | $0 |
| Egress (low traffic) | ~$3 |
| Anthropic API (pay-as-you-go, real usage) | $1-5 |
| Domain / DNS | ~$1 (or $0 with DuckDNS) |
| **Total** | **~$30/mo** |

Anthropic safety cap is $50/mo *ceiling*, not a fee. Realistic demo usage is $1-5.

Optional: Cloud Scheduler stop/start the VM overnight + weekends → ~$10/mo total. Adds 30-60s warm-up before a demo.

## Decisions (locked 2026-04-26)

1. **Hostname:** `demo.agenticdemo.dev` (Cloudflare Registrar, Cloudflare DNS).
2. **`gh pr create`:** public github.com via dedicated bot account (e.g. `threadly-sre-bot`). PAT stored in Secret Manager. Phase-2 code-fix path opens a real PR partners can click into.
3. **Repo materialization:** clone on startup from public github.com. Iteration is `gcloud ssh` → `git pull` → `systemctl restart`. Requires pushing `threadly` and `threadly-payments` to public github.com first.
4. **Partner allowlist:** explicit `--authenticated-emails-file=/etc/oauth2-proxy/emails.txt`. Add a partner = SSH in, append, `systemctl reload oauth2-proxy`.

## Out of scope (v1)

- HA / multi-instance — single VM is fine for a demo
- Real ServiceNow integration — phase-2 SNOW path stays as mock JSON
- Replacing Tailscale Funnel — Caddy + oauth2-proxy supersedes it for partner access; keep Funnel for local dev only
- Switching Claude → Gemini — separate conversation, not driven by the cloud move
