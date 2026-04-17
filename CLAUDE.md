# Closed-Loop Remediation Demo (v2)

AI-driven incident remediation with human-in-the-loop: DX OI detects an error, fires a webhook, Claude Code diagnoses the issue and presents remediation options, the user picks an action, and Claude executes it.

## Architecture

```
DX OI Alarm → Webhook POST → Tailscale Funnel → server.js (Node) → Analysis Phase Claude
                                                      ↓
                                                  Web UI (SSE)
                                                      ↓
                                              User picks action
                                                      ↓
                                              Remediation Phase Claude
```

- **DX OI tenant:** ITOM-DX-DEMO-DEV (tenant 1857, dxi-na1.saas.broadcom.com)
- **Demo app:** Spring PetClinic (Java/Spring Boot, H2 in-memory DB)
- **Server:** Node.js HTTP server (no dependencies), configurable via `.env`
- **Tunnel:** Tailscale Funnel → `https://thor.tailc6067a.ts.net/`
- **Web UI:** `http://localhost:5000` — dark-themed SPA with SSE streaming

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
| `deploy.sh` | Deploy script: copies versioned JAR to active, restarts app |
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

## Demo App: Spring PetClinic

- **Location:** configured via `PETCLINIC_DIR` in `.env` (default: `../spring-petclinic`)
- **Port:** configured via `APP_PORT` in `.env` (default: 8180)
- **GitHub:** https://github.com/spring-projects/spring-petclinic
- **Database:** H2 in-memory (resets on restart)

### Versioning

Two git tags in the spring-petclinic repo:
- **v1.0** — clean, no bugs
- **v1.1** — contains the divide-by-zero bug in `OwnerController.java`

Pre-built JARs:
```
demo/builds/
├── v1.0/spring-petclinic-4.0.0-SNAPSHOT.jar
├── v1.1/spring-petclinic-4.0.0-SNAPSHOT.jar
└── active/
    ├── spring-petclinic.jar   # copy of whichever version is deployed
    └── version.txt            # "v1.0" or "v1.1"
```

### Deploy

```bash
# Deploy a version (kills running app, copies JAR, starts, waits for health)
./deploy.sh v1.1

# Check what's deployed
cat ../builds/active/version.txt
```

### The Bug

In `OwnerController.java` `showOwner()` method — a "loyalty score" calculation divides `totalVisits / petsWithVisits` without guarding against zero. Most owners have pets with no visits, so viewing any owner detail page throws `ArithmeticException: / by zero`.

**Trigger:** `curl http://localhost:8180/owners/1` → HTTP 500

**Fix:** Add `petsWithVisits > 0 ?` ternary guard before the division.

## Server (server.js)

### Start

```bash
npm start
# or: node server.js > /tmp/demo-server.log 2>&1 &
```

### Configuration

All paths and ports are configurable via `.env` (see `.env.example`). Environment variables override `.env` values. Prompt files (`CLAUDE_SRE_ANALYSIS_PHASE.md`, `CLAUDE_SRE_REMEDIATION_PHASE.md`) use `{{PLACEHOLDER}}` tokens that are resolved at runtime by server.js.

### Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/` | GET | Web UI |
| `/health` | GET | Health check |
| `/status` | GET | Current state, version, trigger, diagnosis, options |
| `/events` | GET | SSE stream for real-time updates |
| `/webhook` | POST | DX OI alarm webhook receiver |
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

1. Deploy the buggy version: `./deploy.sh v1.1`
2. Verify bug: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8180/owners/1` → 500
3. Start server: `node server.js > /tmp/demo-server.log 2>&1 &`
4. Start Funnel: `tailscale funnel 5000`
5. Verify: `curl https://thor.tailc6067a.ts.net/health`
6. Open UI: `http://localhost:5000`

### During demo

1. Show the UI — status is idle, version is v1.1
2. Click "Trigger Simulated Alarm" (or wait for real DX OI alarm)
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
2. Stop PetClinic: `kill $(lsof -ti:8180)`
3. Stop Funnel: `tailscale funnel --remove 5000`
4. Reset to buggy version: `./deploy.sh v1.1`

## Git

The repo root is `/home/duane/dx-do/` (not this directory). To commit from the `closed-loop/` directory:

```bash
cd /home/duane/dx-do
git add demo/closed-loop/
git commit -m "your message"
```

Or use `git -C /home/duane/dx-do` from anywhere:

```bash
git -C /home/duane/dx-do add demo/closed-loop/
git -C /home/duane/dx-do commit -m "your message"
```

## Logs

| Log | Location |
|-----|----------|
| PetClinic | `/tmp/petclinic.log` |
| PetClinic stdout | `/tmp/petclinic-stdout.log` |
| Demo server | `/tmp/demo-server.log` |
| Claude SRE sessions | `/tmp/claude-sre.log` |

### Pretty-print Claude log

```bash
./tail-sre.py         # follow live
./tail-sre.py --all   # replay from start
```
