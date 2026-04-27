# Cloud Deploy — Resume Plan

**Pause point:** Phases A through E.4 complete. VM is provisioned, secrets staged, all 4 backend services running under systemd (threadly + threadly-payments + demo-server + monitoring), 80/443 firewall is open, and the full pipeline is verified end-to-end on the VM (ERROR → fluent-bit → webhook → Claude → 3 remediation options at ~$0.27/analysis). Next step is Phase F — Caddy reverse proxy + oauth2-proxy + email allowlist.

**Date paused:** 2026-04-27 (Phase E.4 complete).

**Nothing publicly reachable yet** — backend services listen on the VM but Caddy is not yet running, so 80/443 are open at the firewall but nothing answers. `https://demo.agenticdemo.dev/` will resolve via DNS but connection times out until Phase F brings Caddy up.

The architectural plan lives in `orchestrator/CLOUD_DEPLOY_PLAN.md` — read that first for the WHY. This file is the operational checklist for picking up where we left off. Live cloud-deploy state (project IDs, IPs, secret paths, common ops) is in the auto-loaded memory file `cloud_deploy_resources.md` for the agentic-sre-demo project.

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

Pre-cloud:
- Sanitization of agentic-sre-demo (DX OI / Tailscale / personal paths removed, legacy v1 files deleted)
- SQLite migration committed across all three apps (rebuilt v1.0 + v1.1 of threadly + v1.0 of threadly-payments — all JARs verified to start cleanly)
- Fluent Bit firstline parser updated for Spring Boot 3.4 ISO-8601 log format
- Demo verified end-to-end locally: `/products/7` → 500 → Fluent Bit webhook → Claude analysis → 3 options surfaced in UI
- Monorepo built via `git subtree add` (history + tags preserved)
- Public github push: `https://github.com/DuaneNielsen/threadly-demo`

Phase A — Pre-flight (2026-04-27):
- Bot GitHub account `threadlysrebot` created via Continue-with-Google (email `threadlysrebot@gmail.com`); collaborator with Write access on `DuaneNielsen/threadly-demo`. Fine-grained PAT (Contents+PRs:write) at `~/.secrets/threadlysrebot.pat`.
- Anthropic workspace `agentic-sre-demo` created with $50/mo cap. API key at `~/.secrets/anthropic-demo.key`.

Phase B — GCP project (2026-04-27):
- Project `agentic-sre-demo` created (project number `699002265226`), billed to "Duane VISA" (`017DA2-B3B32A-999368`).
- APIs enabled: `compute`, `secretmanager`, `iap`, `oslogin` (auto-pulled).
- Service account `demo-vm@agentic-sre-demo.iam.gserviceaccount.com` created — zero project-level roles, only per-secret bindings.

Phase C — DNS + OAuth (2026-04-27):
- Cloudflare API token at `~/.secrets/cloudflare-agenticdemo.pat` (Zone:DNS:Edit + Zone:Read on `agenticdemo.dev`, expires 2027-04-27). NOT pushed to Secret Manager — local DNS automation only.
- A record `demo.agenticdemo.dev` → `34.136.214.114` (DNS only, not proxied). Zone ID `e790be0a65988ff895da8a6996f279c2`, record ID `3fc6d9a0798808a0a4f31f48b2d94e79`.
- Google OAuth client `oauth2-proxy-demo-vm` created in Auth Platform → Clients. Redirect URI `https://demo.agenticdemo.dev/oauth2/callback`. Client ID at `~/.secrets/oauth2-proxy-client-id` (664), secret at `~/.secrets/oauth2-proxy-client-secret` (600). Consent screen in Testing mode — add partner emails as test users in Auth Platform → Audience.

Phase D — Secret Manager (2026-04-27):
- 5 secrets pushed: `anthropic-api-key`, `webhook-bearer-token`, `oauth2-proxy-client-secret`, `oauth2-proxy-cookie-secret`, `bot-github-pat`.
- Each granted `roles/secretmanager.secretAccessor` to the `demo-vm` SA (per-secret, not project-wide).
- The two random tokens were generated locally with `openssl rand -base64 32` and saved to `~/.secrets/webhook-bearer-token` and `~/.secrets/oauth2-proxy-cookie-secret`.

Phase E.1 — Static IP + DNS (2026-04-27):
- Reserved regional address `demo-vm-ip` in us-central1 → `34.136.214.114`.
- Cloudflare A record updated from `0.0.0.0` placeholder to the real IP.

Phase E.2 — VM provisioned (2026-04-27):
- VM `threadly-demo` running in `us-central1-a`, e2-medium, Debian 12, shielded, OS Login enforced, 20GB pd-balanced boot disk.
- Service account `demo-vm@...` attached with `cloud-platform` scope (so Secret Manager works).
- Tags `http-server`, `https-server` set on the instance — but NO matching firewall rules exist yet (add in Phase E.4 once Caddy is installed).
- SSH locked down: `default-allow-ssh` and `default-allow-rdp` deleted from the default network. Only `allow-iap-ssh` (tcp:22 from `35.235.240.0/20`) remains. SSH command:
  ```bash
  gcloud compute ssh threadly-demo --zone=us-central1-a --tunnel-through-iap --project=agentic-sre-demo
  ```

### Caveats to remember
- **JAR sizes:** GitHub flagged warnings (~55-66MB each, over the 50MB recommended limit). Push went through (under 100MB hard limit) but `git clone` is slow. If it bites in production, move JARs to GitHub Releases and have `deploy.sh` curl them. Defer for now.
- **`orchestrator/CLOUD_DEPLOY_PLAN.md` paths are pre-monorepo.** The plan was written when threadly + threadly-payments were sibling repos. Inside the monorepo, sibling-relative paths (`../threadly`, `../threadly-payments`) still work because `orchestrator/` is at the same level as the apps. So `.env.example` and `deploy.sh` don't need changes for the cloud — same relative layout.
- **Fluent Bit bind-mount stale-inode workaround (RESOLVED in Phase E.4).** Originally the per-file bind mount went stale on app redeploy (Logback replaced the inode; fluent-bit held the old one). The canonical config now uses a directory bind-mount `/tmp:/var/log/host-tmp:ro` plus `inotify_watcher false` plus a persistent `DB` so tail-state survives container restarts. No longer a caveat — kept here for archaeology.

## Remaining work, in order

### Phase A — Pre-flight ✅ DONE 2026-04-27
See "What's already done" above. Outcomes: bot GitHub account + PAT, Anthropic workspace + capped key.

### Phase B — GCP project ✅ DONE 2026-04-27
See "What's already done" above. Outcomes: project `agentic-sre-demo`, APIs enabled, `demo-vm` SA created.

### Phase C — DNS + OAuth ✅ DONE 2026-04-27
See "What's already done" above. Outcomes: A record live, OAuth client created, consent screen in Testing mode.

### Phase D — Secret Manager ✅ DONE 2026-04-27
See "What's already done" above. Outcomes: 5 secrets stored, `demo-vm` SA bound to each.

### Phase E.1 — Static IP + DNS ✅ DONE 2026-04-27
See "What's already done" above. Outcomes: `34.136.214.114` reserved + DNS pointed at it.

### Phase E.2 — VM provisioned ✅ DONE 2026-04-27
See "What's already done" above. Outcomes: `threadly-demo` running, IAP-only SSH, `default-allow-ssh`/`-rdp` removed.

### Phase E.3 — Install + configure on the VM ✅ DONE 2026-04-27

Outcomes:
- **Deps installed** via apt (with third-party repos) + npm + binary release: docker-ce 29.4.1, docker-compose-plugin v5.1.3, temurin-21-jdk (Adoptium — Debian 12 main only ships Java 17), nodejs 20.20.2, gh 2.91.0, caddy 2.11.2, oauth2-proxy v7.6.0, `@anthropic-ai/claude-code` 2.1.119, lsof (deploy.sh dependency).
- **Repo cloned** to `/opt/threadly-demo/`, chown'd to `duane_nielsen_rocks_gmail_com` (OS Login user). All 3 JARs (~67MB each) committed to repo so no `mvnw package` needed on the VM.
- **Secrets materialized** to `/etc/threadly-demo/{anthropic,webhook,oauth2-proxy,gh}.env` — root:root mode 600, dir 700. systemd-style EnvironmentFile fragments for Phase E.4. NOTE: `OAUTH2_PROXY_CLIENT_ID` is NOT yet on the VM (it's local at `~/.secrets/oauth2-proxy-client-id`); copy it over when wiring up oauth2-proxy.
- **Claude CLI verified** with API-key auth — no `~/.claude` directory existed (clean state), `claude -p "PONG"` responds correctly when `ANTHROPIC_API_KEY` is in env.
- **Apps running** in foreground (NOT yet under systemd): payments v1.0 on :8181, threadly v1.1 on :8180, both `/actuator/health` returning UP. `/products/7` returns HTTP 500 — planted bug fires as expected.
- **Fluent Bit directory bind-mount applied:** `monitoring/docker-compose.yml` now mounts `/tmp:/var/log/host-tmp:ro` (not specific files); `fluent-bit.conf` paths updated to `/var/log/host-tmp/{threadly,payments}.log`. Originals saved as `.orig`. **These changes are NOT committed yet** — when you commit them along with the Phase G changes, the directory mount fix becomes the official version.
- **Monitoring stack up:** loki/fluent-bit/grafana containers via docker compose, all healthy. Verified end-to-end: trigger errors → ship to Loki → `{app="threadly"} |~ "ERROR"` returns lines.
- **Webhook attempts are firing but failing silently** because nothing is listening on `:5000` yet (server.js comes up under systemd in Phase E.4).

Gotchas hit and resolved (so future-you doesn't re-step on them):
- **Caddy GPG key path:** cloudsmith's `debian.deb.txt` hardcodes `signed-by=/usr/share/keyrings/caddy-stable-archive-keyring.gpg`. If you put the key in `/etc/apt/keyrings/` instead, apt-update fails with `NO_PUBKEY ABA1F9B8875A6661`.
- **`lsof` not in base Debian 12** — `deploy.sh` uses it to find/kill the existing process on the port. Install it explicitly.
- **Glob expansion under sudo:** `sudo chmod 600 /etc/threadly-demo/*.env` — when the dir is mode 700 and owned by root, the *outer* user shell expands the glob and gets nothing. Use `sudo bash -c "chmod 600 /etc/threadly-demo/*.env"` to expand under sudo.
- **`apt update` warnings re: NumPy in IAP tunnel** are cosmetic — ignore.

### Phase E.4 — systemd units + opening 80/443 ✅ DONE 2026-04-27

Outcomes:
- **Firewall:** `allow-https` rule created (tcp:80, tcp:443 from 0.0.0.0/0 → `https-server` tag). Project still only has IAP-SSH (`allow-iap-ssh`) for SSH; `default-allow-ssh`/`-rdp` remain deleted.
- **Unit files committed to repo at `orchestrator/systemd/`** (source of truth — re-installable on a fresh VM):
  - `threadly.service` (Type=simple, runs java directly; Requires=threadly-payments)
  - `threadly-payments.service`
  - `demo-server.service` (EnvironmentFile pulls in /etc/threadly-demo/{anthropic,webhook,gh}.env)
  - `monitoring.service` (Type=oneshot wrapper around `docker compose up -d` / `down`; EnvironmentFile pulls in webhook.env so `WEBHOOK_BEARER_TOKEN` is available to fluent-bit container after Phase G.16)
  - `oauth2-proxy.service` (installed but NOT enabled — needs Phase F config: `/etc/oauth2-proxy/client-id.env`, `/etc/oauth2-proxy/emails.txt`, `oauth2-proxy` system user)
  - `caddy.service` is already provided by the apt package at `/lib/systemd/system/caddy.service` — Phase F just needs to write `/etc/caddy/Caddyfile` and `systemctl enable --now caddy`.
- **All 4 backend units active + enabled.** JVM units run as the OS Login user `duane_nielsen_rocks_gmail_com`. monitoring.service runs as root (docker compose).
- **Pipeline verified end-to-end on the VM:** `/products/7` → 500 → fluent-bit → webhook (HTTP 200) → demo-server `[WEBHOOK] Received alarm: Threadly Log Error` → Claude analysis (~76s) → state=`awaiting_choice` with 3 remediation options. One full analysis cost ~$0.27 (Sonnet 4.6 + Haiku 4.5 mix). Reset state to idle to avoid accidental remediation triggers.

**Gotcha hit + fixed during E.4: fluent-bit parser regex didn't accept UTC `Z` suffix.** The `springboot_firstline` parser regex required `[+-]\d{2}:\d{2}` for the timezone. The cloud VM is UTC, so Spring Boot logs `2026-04-27T22:45:39.735Z` (Zulu suffix) — the parser failed on every line, so the `level` field was never extracted, the `rewrite_tag` filter never matched `^(ERROR)$`, and zero webhooks fired. Fix in `orchestrator/monitoring/fluent-bit/parsers.conf`: `(?:Z|[+-]\d{2}:\d{2})`. Originally only verified locally where TZ was non-UTC. Don't re-introduce.

### Issues cleared during Phase E.4

All three resolved + verified on the VM in the same session:

1. **`deploy.sh` / `deploy-payments.sh` systemd-aware.** Both scripts now detect `systemctl cat <unit>` and use the `cp + sudo systemctl restart + health-poll` path; otherwise they fall back to the original `kill PID + java -jar &` for local dev. The `SYSTEMD_UNIT` env var lets the unit name be overridden. Verified on VM: `deploy.sh v1.0` → version.txt=v1.0, `/products/7`=200, `threadly.service` still `active`; `deploy.sh v1.1` rolls back to buggy state cleanly.
2. **Headless `claude` permissions.** Phase 1 analysis allowlist switched from fine-grained `Bash(tail *),Bash(grep *),...` patterns (which Claude Code doesn't reliably match across pipes like `tail ... | grep ... | tail ...`) to plain `Read,Grep,Glob,Bash` plus `--dangerously-skip-permissions`. Edit/Write are still excluded from Phase 1 so the read-only-investigation contract holds. Phase 2 allowlist gained `Write` (needed by the SNOW-ticket remediation path which writes a JSON file). Verified: latest run had **0 permission_denials** (down from 3-4), $0.16 cost (down from $0.27), 61s duration (down from 83s).
3. **fluent-bit log re-replay on restart.** Added `DB /var/log/fluent-bit/{threadly,payments}.db` to both tail inputs, plus a named docker volume `fluent-bit-state` mounted at `/var/log/fluent-bit`. Tail offsets now persist across `systemctl restart monitoring`. Verified: webhook count stayed at 2 across a second restart with no new ERRORs in the log. Also synced local `docker-compose.yml` to use the directory bind-mount (`/tmp:/var/log/host-tmp:ro`) and pass `WEBHOOK_BEARER_TOKEN` env through to the fluent-bit container — that env will be consumed by Phase G.16's HTTP output bearer header.

### Phase F — Reverse proxy + auth
11. **oauth2-proxy** runs on `127.0.0.1:4180`. Key flags:
    - `--provider=google`
    - `--client-id` and `--client-secret` from Secret Manager
    - `--cookie-secret` from Secret Manager
    - `--authenticated-emails-file=/etc/oauth2-proxy/emails.txt` (start with just your email + a couple test partners)
    - `--reverse-proxy=true` `--upstream=http://127.0.0.1:5000`
    - `--redirect-url=https://demo.agenticdemo.dev/oauth2/callback`
12. **Caddy** at `/etc/caddy/Caddyfile`:
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
13. Bind `server.js` to `127.0.0.1:5000` (not `0.0.0.0`) — Caddy is the only thing in front of it, no need for it to listen externally.

### Phase G — server.js minor changes
14. Add `Authorization: Bearer $WEBHOOK_BEARER_TOKEN` check on `/webhook`. **Skip the check if `WEBHOOK_BEARER_TOKEN` env var is unset** — preserves local-dev behavior with no auth.
15. Read `X-Auth-Request-Email` header on UI requests, log it to `/tmp/claude-sre.log` so there's an audit trail of who triggered what.
16. Update `monitoring/fluent-bit/fluent-bit.conf` HTTP output to send the bearer token too (matching server.js's expectation):
    ```
    [OUTPUT]
        Name http
        Match threadly_error
        ...
        Header Authorization Bearer ${WEBHOOK_BEARER_TOKEN}
    ```
    The bearer needs to be available to the fluent-bit container — env var passed through `docker-compose.yml`.
17. Commit these changes to the monorepo and push (becomes the first non-trivial post-extraction commit).

### Phase H — Verify
18. From a partner Google account: hit `https://demo.agenticdemo.dev/` → Google login → UI loads.
19. From an unlisted Google account → 403 from oauth2-proxy.
20. `curl -X POST https://demo.agenticdemo.dev/webhook -d '{}'` (no bearer) → 401.
21. With bearer header → analysis fires, streams to UI.
22. Click rollback remediation → confirm `deploy.sh v1.0` runs, app comes back healthy serving 200 on `/products/7`.
23. Click code-fix remediation → confirm a real PR appears at `https://github.com/DuaneNielsen/threadly-demo/pulls`.
24. Check oauth2-proxy logs for the partner's email.

## Open questions to resolve at resume time
- Does `claude` CLI on the VM need any non-default config (e.g. `claude config set api-key-helper`)? Check what's needed for headless API-key-only auth on a fresh box.
- JAR sizes in the monorepo (~55-66MB each) — does `git clone` complete fast enough on the VM, or move to GitHub Releases?
- Caddy `rate_limit` directive: needs the third-party plugin or first-party feature? Verify which Caddy module is required and pull it in via `caddy build` if needed.

## Files of interest
- `orchestrator/CLOUD_DEPLOY_PLAN.md` — architectural plan with cost table, threat model, security rationale
- `orchestrator/server.js` — webhook receiver, state machine, Claude dispatch
- `orchestrator/CLAUDE_SRE_ANALYSIS_PHASE.md` and `_REMEDIATION_PHASE.md` — system prompts
- `orchestrator/deploy.sh` and `deploy-payments.sh` — JAR swap + restart
- `orchestrator/monitoring/docker-compose.yml` — Loki + Fluent Bit + Grafana stack
- `orchestrator/monitoring/fluent-bit/parsers.conf` — JUST FIXED for Spring Boot 3.4 format, don't break it
- `orchestrator/.env.example` — config template

## Scratch space at resume
When you pick this up at Phase E.3, first verify state from your laptop:
```bash
gcloud config list                                                          # should show project=agentic-sre-demo
gcloud compute instances list --project=agentic-sre-demo                    # threadly-demo, RUNNING
dig +short demo.agenticdemo.dev                                             # 34.136.214.114
```
SSH in:
```bash
gcloud compute ssh threadly-demo --zone=us-central1-a --tunnel-through-iap --project=agentic-sre-demo
```

Drift checks before continuing into Phase E.4:
```bash
# Apps still running?
lsof -ti:8180 -ti:8181 || echo "(apps not running — re-deploy via deploy.sh first)"
curl -sf http://localhost:8180/actuator/health | head -c 80
curl -sf http://localhost:8181/actuator/health | head -c 80
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8180/products/7  # expect 500

# Monitoring stack still up?
sudo docker compose -f /opt/threadly-demo/orchestrator/monitoring/docker-compose.yml ps

# Secrets still in place?
sudo ls -la /etc/threadly-demo/

# Repo at expected commit?
git -C /opt/threadly-demo log --oneline -3
```

If any of those have drifted, see Phase E.3 outcomes above for what to restore. Then start at Phase E.4.
