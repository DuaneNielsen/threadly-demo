# Cloud Deploy — Resume Plan

**Pause point:** Phases A–G complete and verified by curl. Site is publicly reachable at `https://demo.agenticdemo.dev/` — Caddy + oauth2-proxy gate the UI behind Google sign-in, `/webhook` is bypass-routed and bearer-protected, fluent-bit posts ERRORs with the bearer header, and Phase 1 analysis still completes end-to-end at ~$0.16. Tip is `b2a7990`. Phase H curl tests pass; the remaining browser-required tests (steps 18, 19, 22, 23, 24) need a real Google sign-in and a partner account for the unlisted-email check.

**Date paused:** 2026-04-28 (Phases F + G + curlable Phase H complete).

**Publicly reachable.** From any browser: `GET https://demo.agenticdemo.dev/` redirects to `/oauth2/sign_in` (Google OAuth gate). From any client: `POST /webhook` with the correct `Authorization: Bearer …` returns 200 and triggers analysis; missing or wrong bearer returns 401.

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
- **oauth2-proxy v7+ rejects standard-base64 cookie secrets.** `openssl rand -base64 32` produces 44 chars with `+`/`/` — oauth2-proxy tries `base64.RawURLEncoding.DecodeString` (URL-safe base64 only), fails on `+`/`/`, falls back to treating the raw 44-char string as the AES key, and bails with `cookie_secret must be 16, 24, or 32 bytes`. Generate URL-safe instead: `python3 -c "import os, base64; print(base64.urlsafe_b64encode(os.urandom(32)).rstrip(b'=').decode())"` → 43 chars. Stored as v3 in Secret Manager; v1 (broken) and v2 (URL-safe but with `<<<` newline) are destroyed.
- **`gcloud secrets versions add … <<< "$val"` adds a trailing `\n`.** Bash here-strings append a newline. Use `printf '%s' "$val" | gcloud secrets versions add …` for byte-exact secret content. The cookie-secret v2 was 44 bytes (43+`\n`) before re-pushing as v3 with printf.
- **Don't bind demo-server to `127.0.0.1`** — fluent-bit reaches it via the docker bridge gateway (`host.docker.internal:host-gateway`), not the VM's loopback. Phase G.13 made this mistake and produced `no upstream connections available to host.docker.internal:5000` until reverted in `b2a7990`. The actual external boundary for `:5000` is the GCP firewall (only 80/443 are open), so `0.0.0.0` is fine.
- **Caddy `forward_auth` returns the upstream's status verbatim by default.** A bare `forward_auth 127.0.0.1:4180 { uri /oauth2/auth }` block sends 401 to the browser instead of redirecting to the sign-in flow. The canonical pattern is `handle_response @unauth { redir * /oauth2/sign_in?rd=… }` — the Caddyfile in `orchestrator/caddy/Caddyfile` has it.

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
- **Fluent Bit directory bind-mount applied:** `monitoring/docker-compose.yml` now mounts `/tmp:/var/log/host-tmp:ro` (not specific files); `fluent-bit.conf` paths updated to `/var/log/host-tmp/{threadly,payments}.log`. Originals saved as `.orig` on the VM. Committed as part of `b8b4df1` (Phase E.4) — local config and VM config are now in sync; this is the canonical layout.
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

### Phase F — Reverse proxy + auth ✅ DONE 2026-04-28

Outcomes:
- **`/etc/oauth2-proxy/` populated:** `client-id.env` (mode 644, the GOCSPX… ID is non-secret), `emails.txt` (mode 644, seed `duane.nielsen.rocks@gmail.com`). System user `oauth2-proxy` (uid 994) created — referenced by the unit's `User=`.
- **`oauth2-proxy.service` active** on `127.0.0.1:4180` (Google provider, allowlisted emails, cookie-secure=true, samesite=lax). Confirmed `/ping` → 200; startup log shows `using authenticated emails file …` and `OAuthProxy configured for Google Client ID`.
- **`orchestrator/caddy/Caddyfile` is canonical** (committed in `b65e60f`); installed at `/etc/caddy/Caddyfile`. Three handlers: bearer-only `/webhook` bypass, direct reverse_proxy for `/oauth2/*`, `forward_auth` + redirect-to-sign-in on 401 for everything else. Rate_limit was DROPPED (third-party plugin not worth a custom Caddy build — bearer + 60s server.js cooldown is the rate budget).
- **Caddy reloaded with auto-TLS:** Let's Encrypt cert obtained via `tls-alpn-01` in ~3 seconds (E7 issuer, expires 2026-07-26). HTTP→HTTPS 308 redirect is automatic.

Operational deltas:
- Add a partner: SSH in, append email to `/etc/oauth2-proxy/emails.txt`, `sudo systemctl reload oauth2-proxy` (oauth2-proxy watches the file but reload guarantees pickup).
- Open a port externally: edit Caddyfile, `sudo systemctl reload caddy`.

### Phase G — server.js + fluent-bit minor changes ✅ DONE 2026-04-28

Outcomes (all in `b65e60f`, with G.13 reverted in `b2a7990` — see gotcha below):
- **Bearer check on `/webhook`** — `crypto.timingSafeEqual` against `WEBHOOK_BEARER_TOKEN`. If the env var is unset (local dev), the check is bypassed; preserves the no-auth-locally contract.
- **`audit()` helper** appends one timestamped line per user action (`/simulate`, `/execute`, `/reset`, `/deploy`) to `/tmp/claude-sre.log`, including `X-Auth-Request-Email` (or `anon` if missing). The header is forwarded by Caddy via `copy_headers X-Auth-Request-Email` in the forward_auth block.
- **fluent-bit `[OUTPUT] http`** for both `threadly_error` and `payments_error` now carries `Header Authorization Bearer ${WEBHOOK_BEARER_TOKEN}`. Env passthrough chain: `webhook.env` → systemd → `docker compose` → fluent-bit container env → conf substitution. Verified end-to-end: `/products/7` → 500 → fluent-bit → demo-server (`[WEBHOOK] Received alarm: Threadly Log Error`) → Phase 1 analysis → 3 options at ~$0.16.

### Phase H — Verify ✅ curl path DONE 2026-04-28; browser path PENDING

Curl-verifiable steps complete:
- ✅ **20.** `POST /webhook` no bearer → 401; wrong bearer → 401; correct bearer → 200 + analysis fires.
- ✅ **21.** External `curl` with bearer kicks off a real Phase 1 dispatch end-to-end.
- ✅ **In-VM end-to-end.** `/products/7` → 500 → fluent-bit (with bearer header) → demo-server bearer check passes → runPhase1 → state=`awaiting_choice` with 3 options (rollback / fix / SNOW), confidence 95/85/40.
- ✅ **`GET /` unauthenticated** → 302 redirect to `/oauth2/sign_in?rd=…`; `/oauth2/sign_in` itself returns 302 to Google.

Browser-required (you, with a real Google account):
- **18.** From your allowlisted Google account: hit `https://demo.agenticdemo.dev/` → Google login → UI loads, state=idle, version=v1.1.
- **19.** From an unlisted Google account → 403 page from oauth2-proxy. (To test: temporarily delete your email from `/etc/oauth2-proxy/emails.txt` and reload, OR use a second Google account that isn't in the file.)
- **22.** Click rollback remediation → confirm `deploy.sh v1.0` runs, `/products/7` returns 200. (The systemd-aware deploy.sh path was already verified during Phase E.4 issue cleanup.)
- **23.** Click code-fix remediation → confirm a real PR appears at `https://github.com/DuaneNielsen/threadly-demo/pulls`.
- **24.** `grep -E "AUDIT user=" /tmp/claude-sre.log` after a browser session — should show your email tied to each click.

## Open questions to resolve at resume time
- JAR sizes in the monorepo (~55-66MB each) — does `git clone` complete fast enough on the VM, or move to GitHub Releases? (Not yet a real problem; the VM was cloned successfully in Phase E.3.)
- OAuth consent screen is in Testing mode — Google currently lets up to 100 test users sign in. For partner expansion past that, either move to "In Production" (requires verification for sensitive scopes — we only use email/profile so should be quick) or stay in Testing and add each partner as a test user in Auth Platform → Audience.

## Files of interest
- `orchestrator/CLOUD_DEPLOY_PLAN.md` — architectural plan with cost table, threat model, security rationale
- `orchestrator/server.js` — webhook receiver, state machine, Claude dispatch
- `orchestrator/CLAUDE_SRE_ANALYSIS_PHASE.md` and `_REMEDIATION_PHASE.md` — system prompts
- `orchestrator/deploy.sh` and `deploy-payments.sh` — systemd-aware JAR swap (Phase E.4 made these dual-use: cloud uses `sudo systemctl restart`, local uses `kill PID + java -jar &`)
- `orchestrator/monitoring/docker-compose.yml` — Loki + Fluent Bit + Grafana stack (canonical layout: directory bind-mount + `fluent-bit-state` volume)
- `orchestrator/monitoring/fluent-bit/parsers.conf` — Spring Boot 3.4 firstline parser; regex accepts both `Z` and `[+-]HH:MM` timezone suffixes (Phase E.4 fix). Don't re-narrow the timezone match.
- `orchestrator/monitoring/fluent-bit/fluent-bit.conf` — tail inputs use `DB /var/log/fluent-bit/{threadly,payments}.db` for offset persistence
- `orchestrator/systemd/` — unit files installed at `/etc/systemd/system/` on the VM
- `orchestrator/caddy/Caddyfile` — Phase F config: bearer-only `/webhook`, oauth2-proxy `/oauth2/*` direct, forward_auth + redirect-to-sign-in for everything else
- `orchestrator/.env.example` — config template

## Scratch space at resume

Pick this up at Phase F. First verify state from your laptop:

```bash
gcloud config list                                                          # should show project=agentic-sre-demo
gcloud compute instances list --project=agentic-sre-demo                    # threadly-demo, RUNNING
dig +short demo.agenticdemo.dev                                             # 34.136.214.114
gcloud compute firewall-rules list --project=agentic-sre-demo               # expect allow-iap-ssh + allow-https + 2 default
```

SSH in:
```bash
gcloud compute ssh threadly-demo --zone=us-central1-a --tunnel-through-iap --project=agentic-sre-demo
```

Drift checks (covering Phases E + F):
```bash
# All 6 units active? (4 backend + oauth2-proxy + caddy)
for u in threadly-payments threadly demo-server monitoring oauth2-proxy caddy; do
    echo "$u: $(systemctl is-active $u) / $(systemctl is-enabled $u)"
done

# Apps healthy + bug fires?
curl -sf http://localhost:8180/actuator/health | head -c 80
curl -sf http://localhost:8181/actuator/health | head -c 80
curl -s -o /dev/null -w "/products/7 HTTP %{http_code}\n" http://localhost:8180/products/7   # expect 500
curl -s http://localhost:5000/health                                                          # expect ok / state=idle

# Webhook pipeline still live?
curl -s http://localhost:3100/ready                                                           # Loki: ready
sudo docker ps --format 'table {{.Names}}\t{{.Status}}'

# Phase F gates wired up?
curl -sf http://127.0.0.1:4180/ping                                                           # oauth2-proxy
sudo ss -tlnp | grep -E ':(80|443) '                                                          # caddy

# Repo at expected commit?
git -C /opt/threadly-demo log --oneline -3                                                    # b2a7990 should be tip
```

External-side checks (from your laptop):
```bash
curl -sI https://demo.agenticdemo.dev/                                                        # 302 to /oauth2/sign_in
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST https://demo.agenticdemo.dev/webhook    # 401 (no bearer)
TOKEN=$(cat ~/.secrets/webhook-bearer-token)
curl -s -X POST https://demo.agenticdemo.dev/webhook -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" -d '{"alarm_name":"resume drift","severity":"Info"}' # 200 + analysis fires (~$0.16)
```

If any of those have drifted, see the corresponding phase outcomes for what to restore. Otherwise the next session is just (a) the browser-side Phase H steps (18, 19, 22, 23, 24) and (b) optional ops hardening — log rotation for `/tmp/claude-sre.log`, Anthropic spend alert thresholds, and an OAuth consent-screen verification path if expanding past 100 partners.
