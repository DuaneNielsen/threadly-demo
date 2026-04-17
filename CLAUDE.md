# Closed-Loop Remediation Demo

AI-driven incident remediation: DX OI detects an error, fires a webhook, Claude Code diagnoses and fixes the code automatically.

## Architecture

```
DX OI Alarm → Webhook POST → Tailscale Funnel → webhook-receiver.py → Claude Code → Fix
```

- **DX OI tenant:** ITOM-DX-DEMO-DEV (tenant 1857, dxi-na1.saas.broadcom.com)
- **Demo app:** Spring PetClinic (Java/Spring Boot, H2 in-memory DB)
- **Webhook receiver:** Python HTTP server on port 5000
- **Tunnel:** Tailscale Funnel → `https://thor.tailc6067a.ts.net/`
- **SRE agent context:** `CLAUDE_SRE.md` (appended to Claude's system prompt)

## Files

| File | Purpose |
|------|---------|
| `webhook-receiver.py` | HTTP server that receives DX OI webhooks and dispatches Claude |
| `CLAUDE_SRE.md` | System prompt context for the headless Claude SRE agent |
| `watch-and-fix.sh` | Alternate log-watcher mode (tails log directly, no webhook needed) |
| `dxoi-channel-import.json` | DX OI channel/policy import config |

## Demo App: Spring PetClinic

- **Location:** `/home/duane/dx-do/demo/spring-petclinic/`
- **Port:** 8180
- **GitHub:** https://github.com/spring-projects/spring-petclinic
- **Database:** H2 in-memory (resets on restart)

### Start/Stop

```bash
# Start
cd /home/duane/dx-do/demo/spring-petclinic
./mvnw spring-boot:run -DskipTests > /tmp/petclinic-stdout.log 2>&1 &
# Spring Boot writes its own log to /tmp/petclinic.log (append) via
# logging.file.name in application.properties. Do NOT redirect stdout to
# /tmp/petclinic.log — `>` truncates it on restart and breaks Filebeat
# (filestream close_timeout=5m won't reliably re-harvest truncated files).

# Stop
kill $(lsof -ti:8180)

# Verify
curl -s -o /dev/null -w "%{http_code}" http://localhost:8180/
```

### The Bug

In `OwnerController.java` `showOwner()` method — a "loyalty score" calculation divides `totalVisits / petsWithVisits` without guarding against zero. Most owners have pets with no visits, so viewing any owner detail page throws `ArithmeticException: / by zero`.

**Trigger:** `curl http://localhost:8180/owners/1` → HTTP 500

**Fix:** Add `petsWithVisits > 0 ?` ternary guard before the division.

### Reset Bug After Demo

```bash
cd /home/duane/dx-do/demo/spring-petclinic
git checkout -- src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java
```

Then restart PetClinic to pick up the reverted (buggy) code.

## Webhook Receiver

### Start

```bash
# Dry run mode (log only, don't dispatch Claude)
python3 -u /home/duane/dx-do/demo/closed-loop/webhook-receiver.py > /tmp/webhook-receiver.log 2>&1 &

# Watch output
tail -f /tmp/webhook-receiver.log
```

### Modes

- **`DRY_RUN = True`** (default) — logs webhook payloads, does not call Claude
- **`DRY_RUN = False`** — dispatches Claude Code to diagnose and fix

Edit `DRY_RUN` in `webhook-receiver.py` and restart to switch modes.

### Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/webhook` | POST | DX OI alarm webhook receiver |
| `/simulate` | POST | Test with simulated alarm (bypasses DX OI) |
| `/health` | GET | Health check |
| `/` | GET | Service info |

### Test Without DX OI

```bash
curl -X POST http://localhost:5000/simulate \
  -H "Content-Type: application/json" \
  -d '{"error": "java.lang.ArithmeticException: / by zero\n\tat org.springframework.samples.petclinic.owner.OwnerController.showOwner(OwnerController.java:182)"}'
```

## Tailscale Funnel

Exposes the webhook receiver to the public internet so DX OI can reach it.

```bash
# Start (requires Funnel enabled on tailnet)
tailscale funnel 5000

# Public URL
https://thor.tailc6067a.ts.net/

# Test
curl https://thor.tailc6067a.ts.net/health
```

**Note:** First-time setup requires enabling Funnel at https://login.tailscale.com and running `sudo tailscale set --operator=$USER`.

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
# List channels
/home/duane/dx-do/dx-do channel list output.format=json

# List policies
/home/duane/dx-do/dx-do channel list-policies output.format=json

# Export all
/home/duane/dx-do/dx-do channel export exportFile=channels-backup.json

# Import
/home/duane/dx-do/dx-do channel import importFile=dxoi-channel-import.json
```

## Full Demo Runbook

### Setup (before demo)

1. Reset the bug: `cd /home/duane/dx-do/demo/spring-petclinic && git checkout .`
2. Start PetClinic: `./mvnw spring-boot:run -DskipTests > /tmp/petclinic-stdout.log 2>&1 &`
3. Verify bug: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8180/owners/1` → should be 500
4. Set `DRY_RUN = False` in `webhook-receiver.py`
5. Start receiver: `python3 -u webhook-receiver.py > /tmp/webhook-receiver.log 2>&1 &`
6. Start Funnel: `tailscale funnel 5000`
7. Verify: `curl https://thor.tailc6067a.ts.net/health`

### During demo

- Trigger via DX OI alarm (if wired up) or simulate:
  ```bash
  curl -X POST https://thor.tailc6067a.ts.net/simulate \
    -H "Content-Type: application/json" \
    -d '{"error": "java.lang.ArithmeticException: / by zero\n\tat org.springframework.samples.petclinic.owner.OwnerController.showOwner(OwnerController.java:182)"}'
  ```
- Watch Claude work: `tail -f /tmp/webhook-receiver.log`
- Verify fix: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8180/owners/1` → should be 200 after Claude restarts the app (or manual restart)

### Teardown (after demo)

1. Stop receiver: `kill $(lsof -ti:5000)`
2. Stop PetClinic: `kill $(lsof -ti:8180)`
3. Stop Funnel: `tailscale funnel --remove 5000`
4. Reset code: `cd /home/duane/dx-do/demo/spring-petclinic && git checkout .`
5. Set `DRY_RUN = True` in `webhook-receiver.py`

## Logs

| Log | Location |
|-----|----------|
| PetClinic | `/tmp/petclinic.log` |
| Webhook receiver | `/tmp/webhook-receiver.log` |
