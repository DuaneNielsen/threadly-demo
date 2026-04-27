# Cloud Deploy — Resume Plan

**Pause point:** monorepo `threadly-demo` is live on public github.com. GCP work hasn't started yet.

**Date paused:** 2026-04-27.

The architectural plan lives in `orchestrator/CLOUD_DEPLOY_PLAN.md` — read that first for the WHY. This file is the operational checklist for picking up where we left off.

## State of the world

### Repos
- `~/projects/threadly-demo/` — public monorepo, pushed to `https://github.com/DuaneNielsen/threadly-demo` (main branch, tags `threadly-v1.0`, `threadly-v1.1`, `payments-v1.0`).
- `~/projects/agentic-sre-demo/` — original repo, pushed to Broadcom GHE `IMS/ims-exempt-agentic-sre-demo`. Lives on as the pre-monorepo source of truth; its content is now mirrored under `threadly-demo/orchestrator/`.
- `~/projects/threadly/` and `~/projects/threadly-payments/` — original sibling repos, also mirrored under monorepo subdirs. No remotes; safe to leave or delete.

### Locked decisions (all settled in prior conversation)
1. **Hostname:** `demo.agenticdemo.dev` — domain owned via Cloudflare Registrar, DNS at Cloudflare.
2. **`gh pr create` target:** public github.com via dedicated bot account (e.g. `threadly-sre-bot`). PAT in Secret Manager. Phase-2 code-fix path opens a real PR partners can click.
3. **Repo materialization on the VM:** clone on startup from `https://github.com/DuaneNielsen/threadly-demo`. Iteration is `gcloud ssh` → `git pull` → `systemctl restart`.
4. **Partner allowlist:** explicit `--authenticated-emails-file=/etc/oauth2-proxy/emails.txt`. Add a partner = SSH in, append email, `systemctl reload oauth2-proxy`.
5. **Cost target:** ~$30/mo always-on (e2-medium VM, no LB, Caddy + oauth2-proxy on the box, Anthropic pay-as-you-go with $50/mo safety cap).
6. **Stack choice:** GCE VM, NOT Cloud Run/GKE. Reasons in CLOUD_DEPLOY_PLAN.md "Why GCE VM" section.

### What's already done
- Sanitization of agentic-sre-demo (DX OI / Tailscale / personal paths removed, legacy v1 files deleted)
- SQLite migration committed across all three apps (rebuilt v1.0 + v1.1 of threadly + v1.0 of threadly-payments — all JARs verified to start cleanly)
- Fluent Bit firstline parser updated for Spring Boot 3.4 ISO-8601 log format
- Demo verified end-to-end locally: `/products/7` → 500 → Fluent Bit webhook → Claude analysis → 3 options surfaced in UI
- Monorepo built via `git subtree add` (history + tags preserved)
- Public github push: `https://github.com/DuaneNielsen/threadly-demo`

### Caveats to remember
- **JAR sizes:** GitHub flagged warnings (~55-66MB each, over the 50MB recommended limit). Push went through (under 100MB hard limit) but `git clone` is slow. If it bites in production, move JARs to GitHub Releases and have `deploy.sh` curl them. Defer for now.
- **`orchestrator/CLOUD_DEPLOY_PLAN.md` paths are pre-monorepo.** The plan was written when threadly + threadly-payments were sibling repos. Inside the monorepo, sibling-relative paths (`../threadly`, `../threadly-payments`) still work because `orchestrator/` is at the same level as the apps. So `.env.example` and `deploy.sh` don't need changes for the cloud — same relative layout.
- **Fluent Bit bind-mount goes stale on app redeploy.** Logback replaces the inode; fluent-bit holds the old one. Workaround so far: `docker compose restart fluent-bit` after every redeploy. For cloud, switch to a *directory* bind-mount (mount `/tmp/` instead of the specific files) — more robust to file replacement. Implement this as part of the VM provisioning script.

## Remaining work, in order

### Phase A — Pre-flight (no GCP spend yet)
1. **Bot GitHub account.** Create `threadly-sre-bot` (or similar) on github.com. Generate a PAT scoped to `repo` on the public `DuaneNielsen/threadly-demo` repo. Save the PAT — we'll push it to Secret Manager later.
2. **Anthropic workspace.** In Anthropic Console → Workspaces → create `agentic-sre-demo`. Generate API key inside that workspace. Set monthly spend limit to $50 as a safety cap (pay-as-you-go, not a fee).

### Phase B — GCP project setup
3. Pick or create GCP project. The user set up gcloud + Gemini API on 2026-04-26 — check `gcloud config list` for the active project.
4. Enable APIs: `compute`, `secretmanager`, `iap` (TCP forwarding for SSH), `dns` (only if migrating DNS to Cloud DNS — current Cloudflare DNS is fine).
5. Create service account `demo-vm@PROJECT.iam.gserviceaccount.com`. NO project-level roles yet — we'll grant per-secret IAM in Phase D.

### Phase C — Domain + OAuth client
6. **DNS placeholder:** add an A record `demo.agenticdemo.dev` → `0.0.0.0` (will update to real IP in Phase E). DNS-only at Cloudflare, NOT proxied through Cloudflare's CDN (Caddy needs to see real client IPs and serve LE certs directly).
7. **OAuth client:** GCP Console → APIs & Services → Credentials → create OAuth 2.0 Client ID (type: web). Authorized redirect URI: `https://demo.agenticdemo.dev/oauth2/callback`. Save client ID and secret.

### Phase D — Secrets
8. Generate two random tokens locally:
   - Webhook bearer: `openssl rand -base64 32`
   - oauth2-proxy cookie secret: `openssl rand -base64 32`
9. `gcloud secrets create` for: `anthropic-api-key`, `webhook-bearer-token`, `oauth2-proxy-client-secret`, `oauth2-proxy-cookie-secret`, `bot-github-pat`.
10. Grant `roles/secretmanager.secretAccessor` on each secret to the `demo-vm` service account (per-secret, not project-wide).

### Phase E — VM provision
11. Reserve a static external IP (free while attached to a running VM). Update the Cloudflare A record to point at it.
12. Provision e2-medium VM:
    - Debian 12
    - Shielded VM
    - OS Login enforced
    - Attach `demo-vm` service account
    - Attach the static IP
    - NO public 22 firewall rule (SSH via IAP TCP tunnel: `gcloud compute ssh --tunnel-through-iap`)
13. Startup script (or run manually after first SSH):
    - Install: `docker`, `docker compose`, `openjdk-21`, `nodejs-20` (via NodeSource), `gh`, `caddy`, `oauth2-proxy` (binary release from quay.io/oauth2-proxy), the `claude` CLI (`npm i -g @anthropic-ai/claude-code` — check current package name).
    - `git clone https://github.com/DuaneNielsen/threadly-demo /opt/threadly-demo`
    - Materialize `.env` and configs from Secret Manager via `gcloud secrets versions access`.
    - **IMPORTANT:** wipe any `~/.claude` OAuth tokens — API-key auth only. Verify no subscription auth is present after install.
    - Build the JARs: `cd /opt/threadly-demo/threadly && ./mvnw package -DskipTests`, same for `threadly-payments`. (Or — if JAR sizes in git become a problem, fetch from GitHub Releases instead.)
    - `cd /opt/threadly-demo/orchestrator && ./deploy-payments.sh v1.0 && ./deploy.sh v1.1`
    - **Use a directory bind-mount in `monitoring/docker-compose.yml`** — mount `/tmp/` not specific files (avoids the inode-staleness issue we hit locally).
    - `cd monitoring && docker compose up -d`
14. systemd units, all `Restart=on-failure`:
    - `threadly.service` (the storefront JAR)
    - `threadly-payments.service`
    - `demo-server.service` (`node server.js`)
    - `monitoring.service` (the docker-compose stack — wraps `docker compose up -d` / `down`)
    - `caddy.service`
    - `oauth2-proxy.service`

### Phase F — Reverse proxy + auth
15. **oauth2-proxy** runs on `127.0.0.1:4180`. Key flags:
    - `--provider=google`
    - `--client-id` and `--client-secret` from Secret Manager
    - `--cookie-secret` from Secret Manager
    - `--authenticated-emails-file=/etc/oauth2-proxy/emails.txt` (start with just your email + a couple test partners)
    - `--reverse-proxy=true` `--upstream=http://127.0.0.1:5000`
    - `--redirect-url=https://demo.agenticdemo.dev/oauth2/callback`
16. **Caddy** at `/etc/caddy/Caddyfile`:
    ```
    demo.agenticdemo.dev {
        @webhook path /webhook
        handle @webhook {
            rate_limit { ... per-IP, ~10/min ... }
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
17. Bind `server.js` to `127.0.0.1:5000` (not `0.0.0.0`) — Caddy is the only thing in front of it, no need for it to listen externally.

### Phase G — server.js minor changes
18. Add `Authorization: Bearer $WEBHOOK_BEARER_TOKEN` check on `/webhook`. **Skip the check if `WEBHOOK_BEARER_TOKEN` env var is unset** — preserves local-dev behavior with no auth.
19. Read `X-Auth-Request-Email` header on UI requests, log it to `/tmp/claude-sre.log` so there's an audit trail of who triggered what.
20. Update `monitoring/fluent-bit/fluent-bit.conf` HTTP output to send the bearer token too (matching server.js's expectation):
    ```
    [OUTPUT]
        Name http
        Match threadly_error
        ...
        Header Authorization Bearer ${WEBHOOK_BEARER_TOKEN}
    ```
    The bearer needs to be available to the fluent-bit container — env var passed through `docker-compose.yml`.
21. Commit these changes to the monorepo and push (becomes the first non-trivial post-extraction commit).

### Phase H — Verify
22. From a partner Google account: hit `https://demo.agenticdemo.dev/` → Google login → UI loads.
23. From an unlisted Google account → 403 from oauth2-proxy.
24. `curl -X POST https://demo.agenticdemo.dev/webhook -d '{}'` (no bearer) → 401.
25. With bearer header → analysis fires, streams to UI.
26. Click rollback remediation → confirm `deploy.sh v1.0` runs, app comes back healthy serving 200 on `/products/7`.
27. Click code-fix remediation → confirm a real PR appears at `https://github.com/DuaneNielsen/threadly-demo/pulls`.
28. Check oauth2-proxy logs for the partner's email.

## Open questions to resolve at resume time
- Which GCP project? Reuse the gcloud-configured one from 2026-04-26, or fresh project for this demo?
- Domain registrar / DNS still at Cloudflare? (Confirm by `dig agenticdemo.dev NS`.)
- Branch name on the bot GitHub account — does it need 2FA setup? PATs work without 2FA but the user's policy may differ.
- Does `claude` CLI on the VM need any non-default config (e.g. `claude config set api-key-helper`)? Check what's needed for headless API-key-only auth on a fresh box.

## Files of interest
- `orchestrator/CLOUD_DEPLOY_PLAN.md` — architectural plan with cost table, threat model, security rationale
- `orchestrator/server.js` — webhook receiver, state machine, Claude dispatch
- `orchestrator/CLAUDE_SRE_ANALYSIS_PHASE.md` and `_REMEDIATION_PHASE.md` — system prompts
- `orchestrator/deploy.sh` and `deploy-payments.sh` — JAR swap + restart
- `orchestrator/monitoring/docker-compose.yml` — Loki + Fluent Bit + Grafana stack
- `orchestrator/monitoring/fluent-bit/parsers.conf` — JUST FIXED for Spring Boot 3.4 format, don't break it
- `orchestrator/.env.example` — config template

## Scratch space at resume
When you pick this up, first run:
```bash
cd ~/projects/threadly-demo && git status && git log --oneline -10
gcloud config list
gcloud projects list 2>&1 | head -10
```
Confirm nothing has drifted, then start at Phase A.
