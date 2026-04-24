# Closed-Loop Remediation Demo (v2)

AI-driven incident remediation with human-in-the-loop: monitoring detects an error, fires a webhook, Claude Code diagnoses the issue and presents remediation options, the user picks an action, and Claude executes it.

## Architecture

```
 +---------+          +------------+     HTTP     +---------------------+
 | Browser | -------> |  Threadly  | -----------> |  threadly-payments  |
 +---------+  :8180   |  (store)   |    :8181     |   (fake PSP)        |
                      +------------+              +---------------------+
                            |                             |
                     /tmp/threadly.log             /tmp/payments.log
                            |                             |
                            +------ Fluent Bit -----------+
                                       |
                                    Loki / Grafana (:3001)
                                       |
                                    webhook -> server.js (:5000) -> Claude
```

- **Monitoring:** Fluent Bit + Loki + Grafana (docker-compose in `monitoring/`)
- **Demo apps:**
  - **Threadly** — Spring Boot t-shirt store with cart/checkout/orders (`/home/duane/projects/threadly/`, port 8180). Tagline: "threads never drop."
  - **threadly-payments** — Stripe-style fake PSP (`/home/duane/projects/threadly-payments/`, port 8181) called by Threadly's `PaymentClient`.
- **Server:** Node.js HTTP server (no dependencies), configurable via `.env`
- **Tunnel:** Tailscale Funnel → `https://thor.tailc6067a.ts.net/` (optional, for remote webhooks)
- **Web UI:** `http://localhost:5000` — dark-themed SPA with SSE streaming
- **Grafana:** `http://localhost:3001` — Threadly + Payments logs dashboard (anonymous access)
- **DX OI tenant (optional):** ITOM-DX-DEMO-DEV (tenant 1857, dxi-na1.saas.broadcom.com)

## Two-Phase Claude

### Analysis Phase
Claude receives the alarm, reads logs and source code, and outputs structured JSON with:
- Root cause diagnosis (error type, file, line, user impact, code snippet, log excerpt)
- 3 remediation options sorted by confidence:
  1. **Rollback** — deploy previous known-good version via `deploy.sh`
  2. **Code Fix** — edit source, create branch, commit, open PR via `gh`
  3. **SNOW Ticket** — create a mock ServiceNow ticket JSON file

### Remediation Phase
When the user clicks an action button, Claude is dispatched again with that option's prompt to execute it.

## Files

| File | Purpose |
|------|---------|
| `server.js` | Node.js server: webhook receiver, Claude dispatch, SSE, web UI |
| `public/index.html` | Web UI — trigger card, analysis terminal, RCA panel, option cards |
| `public/style.css` | Dark-themed styling |
| `deploy.sh` | Deploy Threadly: copies versioned JAR to active, restarts app, passes `--payments.url` |
| `deploy-payments.sh` | Deploy threadly-payments: same pattern, port 8181 |
| `CLAUDE_SRE_ANALYSIS_PHASE.md` | Analysis phase system prompt (diagnose, output JSON) |
| `CLAUDE_SRE_REMEDIATION_PHASE.md` | Remediation phase system prompt (execute chosen action) |
| `.env.example` | Configuration template — copy to `.env` |
| `setup.sh` | Prerequisites checker and `.env` creator |
| `package.json` | Project metadata and npm scripts |
| `README.md` | Installation and usage guide |
| `CLAUDE_SRE.md` | Legacy v1 system prompt (kept for reference) |
| `webhook-receiver.py` | Legacy v1 Python webhook receiver (kept for reference) |
| `watch-and-fix.sh` | Alternate log-watcher mode (tails log directly, no webhook) |
| `tail-sre.py` | Pretty-printer for `/tmp/claude-sre.log` |
| `dxoi-channel-import.json` | DX OI channel/policy import config |

## Demo Apps

### Threadly (storefront)

- **Location:** `APP_DIR` (default `../threadly`)
- **Port:** `APP_PORT` (default 8180)
- **Framework:** Spring Boot 3.4, Thymeleaf, JPA/Hibernate
- **Database:** PostgreSQL (via `docker-compose.yml` in `APP_DIR`) for the default profile; H2 in-memory for `h2` profile
- **Java:** 21
- Calls threadly-payments via `PaymentClient` (URL from `payments.url`; `deploy.sh` passes `--payments.url=$PAYMENTS_URL`)

### threadly-payments (fake PSP)

- **Location:** `PAYMENTS_DIR` (default `../threadly-payments`)
- **Port:** `PAYMENTS_PORT` (default 8181)
- **Framework:** Spring Boot 3.4, JPA/Hibernate (H2 in-memory)
- **Java:** 21
- **API:** `POST /v1/charges`, `GET /v1/charges/{id}`, `GET /actuator/health`
- **Test cards:** `4242...` succeeds, `4000...0002` = card_declined, `4000...9995` = insufficient_funds, `4000...0069` = expired_card, `4000...0119` = 500 (future bug-plant surface)

### Versioning

Both services have git tags `v1.0` (clean) and `v1.1` (buggy where applicable). Threadly v1.1 has the divide-by-zero bug; payments currently has a single v1.0 JAR.

Pre-built JARs:
```
threadly/builds/v1.0/threadly.jar                   # clean (+ checkout)
threadly/builds/v1.1/threadly.jar                   # planted bug (+ checkout)
threadly/builds/active/...                          # deploy.sh swaps here
threadly-payments/builds/v1.0/threadly-payments.jar
threadly-payments/builds/active/...                 # deploy-payments.sh swaps here
```

### Deploy

```bash
# Payment service first (Threadly needs it)
./deploy-payments.sh v1.0

# Then Threadly
./deploy.sh v1.1

# Check deployed versions
cat ../threadly/builds/active/version.txt
cat ../threadly-payments/builds/active/version.txt
```

### The Bug

In `com/threadly/discount/DiscountCalculator.java` `percentOff()` method — the null/zero guards around `BigDecimal.divide(originalPrice, ...)` are removed in v1.1. The seed data includes an "I'm a Teapot" promo item with `original_price = 0.00`, so rendering its detail page throws `ArithmeticException: / by zero`.

**Trigger:** `curl http://localhost:8180/products/7` → HTTP 500

**Fix:** Restore the `originalPrice == null` and `originalPrice.signum() == 0` guards before the division.

## Server (server.js)

### Start

```bash
npm start
# or: node server.js > /tmp/demo-server.log 2>&1 &
```

### Configuration

All paths and ports are configurable via `.env` (see `.env.example`). Environment variables override `.env` values. Prompt files (`CLAUDE_SRE_ANALYSIS_PHASE.md`, `CLAUDE_SRE_REMEDIATION_PHASE.md`) use `{{PLACEHOLDER}}` tokens resolved at runtime by server.js:

- Threadly: `{{APP_DIR}}`, `{{APP_NAME}}`, `{{APP_PORT}}`, `{{APP_LOG}}`, `{{BUILDS_DIR}}`, `{{DEPLOY_SCRIPT}}`
- Payments: `{{PAYMENTS_DIR}}`, `{{PAYMENTS_NAME}}`, `{{PAYMENTS_PORT}}`, `{{PAYMENTS_LOG}}`, `{{PAYMENTS_BUILDS_DIR}}`, `{{PAYMENTS_DEPLOY_SCRIPT}}`, `{{PAYMENTS_URL}}`
- Shared: `{{CLOSED_LOOP_DIR}}`

`server.js` passes BOTH `APP_DIR` and `PAYMENTS_DIR` via `--add-dir` so Claude can read both codebases.

### Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/` | GET | Web UI |
| `/health` | GET | Health check |
| `/status` | GET | Current state, version, trigger, diagnosis, options |
| `/events` | GET | SSE stream for real-time updates |
| `/webhook` | POST | DX OI / Fluent Bit alarm webhook receiver |
| `/simulate` | POST | Trigger with simulated alarm payload |
| `/execute` | POST | Execute a remediation option (`{ optionId: 1 }`) |
| `/reset` | POST | Reset state to idle |

### State Machine

```
idle → analyzing → awaiting_choice → executing → idle
```

- 60-second cooldown between dispatches
- Only one dispatch at a time

### SSE Events

| Event | Data |
|-------|------|
| `status` | `{ state, version }` |
| `trigger` | Alarm details (name, severity, host, agent, metric, etc.) |
| `phase1_stream` | Claude analysis text output |
| `phase1_tool` | Tool usage (file reads, bash commands) |
| `phase1_meta` | Cost, duration, turns |
| `phase1_complete` | Parsed diagnosis + options + raw JSON |
| `phase2_start` | Action being executed |
| `phase2_stream` | Claude execution text output |
| `phase2_tool` | Tool usage |
| `phase2_meta` | Cost, duration, turns |
| `phase2_complete` | Result |
| `error` | Error messages |

## Monitoring Stack (Fluent Bit + Loki + Grafana)

All containers live in `monitoring/` and are managed via docker-compose.

```bash
cd monitoring
docker compose up -d      # start all
docker compose down        # stop all
docker compose logs -f     # follow logs
```

### Components

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| Fluent Bit | `fluent/fluent-bit:4.0` | - | Tails `threadly.log` + `payments.log`, ships to Loki, webhooks ERRORs for both |
| Loki | `grafana/loki:3.4.2` | 3100 | Log storage/query |
| Grafana | `grafana/grafana:11.6.0` | 3001 (configurable via `GRAFANA_PORT`) | Dashboard UI (anonymous admin access) |

### How it works

Fluent Bit runs two independent tail pipelines on the same log file:
1. **Loki pipeline** — all log lines ship to Loki for storage and Grafana dashboards
2. **Webhook pipeline** — ERROR lines are filtered, transformed via Lua into the webhook payload format, and POSTed to `http://host.docker.internal:5000/webhook`

The webhook fires within ~1-2 seconds of an ERROR appearing in the log.

### Grafana Dashboard

Pre-provisioned at `http://localhost:3001/d/threadly/threadly-logs`:
- Total Errors (stat panel)
- Error Rate (time series)
- Log Volume by Level (bar chart)
- Log Stream (all logs)
- Error Log Lines (filtered)

### Configuration

Fluent Bit config: `monitoring/fluent-bit/fluent-bit.conf`
Webhook payload transform: `monitoring/fluent-bit/webhook.lua`
Grafana datasource: `monitoring/grafana/provisioning/datasources/loki.yml`
Dashboard JSON: `monitoring/grafana/provisioning/dashboards/threadly.json`

## Tailscale Funnel

Exposes the server to the public internet so DX OI can reach it.

```bash
# Start
tailscale funnel 5000

# Public URL
https://thor.tailc6067a.ts.net/

# Test
curl https://thor.tailc6067a.ts.net/health
```

## DX OI Configuration

### Channel: "Claude SRE"

- **Type:** genericwebhook
- **URL:** `https://thor.tailc6067a.ts.net/webhook`
- **Auth:** TOKEN_AUTH (dummy token)
- **Payload:** alarm_name, severity, status, host, agent, metric_name, metric_value, component_name, message, alert_external_id, alarm_type, danger/caution thresholds

### Policy: "Claude Remediation Policy"

- **Status:** Active (disable when not demoing!)
- **Filter:** Non-service alarms with severity increase above Unknown
- **Linked to:** Claude SRE channel

### Manage via dx-do

```bash
/home/duane/dx-do/dx-do channel list output.format=json
/home/duane/dx-do/dx-do channel list-policies output.format=json
/home/duane/dx-do/dx-do channel export exportFile=channels-backup.json
/home/duane/dx-do/dx-do channel import importFile=dxoi-channel-import.json
```

## Full Demo Runbook

### Setup (before demo)

1. Deploy payment service: `./deploy-payments.sh v1.0`
2. Deploy buggy Threadly: `./deploy.sh v1.1`
3. Verify bug: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8180/products/7` → 500
4. Verify payments health: `curl -s http://localhost:8181/actuator/health`
5. Start monitoring: `cd monitoring && docker compose up -d`
6. Start server: `node server.js > /tmp/demo-server.log 2>&1 &`
7. (Optional) Start Funnel: `tailscale funnel 5000`
8. Verify: `curl https://thor.tailc6067a.ts.net/health`
9. Open UI: `http://localhost:5000`

### During demo

1. Show the UI — status is idle, version is v1.1
2. Click "Trigger Simulated Alarm" (or wait for real DX OI alarm, or `curl http://localhost:8180/products/7` for a real error)
3. Watch the analysis phase stream in the terminal panel
4. Trigger card shows the alarm details
5. RCA panel appears with diagnosis (toggle Formatted / Source JSON)
6. Three remediation cards appear — sorted by confidence, top one is recommended
7. Click an action button to execute
8. Remediation phase streams the execution
9. Completion banner shows result

### Test without UI

```bash
curl -X POST http://localhost:5000/simulate \
  -H "Content-Type: application/json" \
  -d '{}'
```

The simulate endpoint fills in realistic default alarm values.

### Teardown (after demo)

1. Stop server: `kill $(lsof -ti:5000)`
2. Stop Threadly: `kill $(lsof -ti:8180)`
3. Stop payments: `kill $(lsof -ti:8181)`
4. Stop Funnel: `tailscale funnel --remove 5000`
5. Reset to buggy version: `./deploy.sh v1.1` (and `./deploy-payments.sh v1.0`)

## Git

This directory is its own git repo (`/home/duane/projects/agentic-sre-demo/`). The demo apps live in sibling repos at `/home/duane/projects/threadly/` and `/home/duane/projects/threadly-payments/`. Commit to each repo independently.

## Logs

| Log | Location |
|-----|----------|
| Threadly | `/tmp/threadly.log` |
| Threadly stdout | `/tmp/threadly-stdout.log` |
| Payments | `/tmp/payments.log` |
| Payments stdout | `/tmp/payments-stdout.log` |
| Demo server | `/tmp/demo-server.log` |
| Claude SRE sessions | `/tmp/claude-sre.log` |

### Pretty-print Claude log

```bash
./tail-sre.py         # follow live
./tail-sre.py --all   # replay from start
```
