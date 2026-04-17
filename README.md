# Closed-Loop Remediation Demo

AI-driven incident remediation with human-in-the-loop: DX Operational Intelligence detects an error, fires a webhook, Claude Code diagnoses the issue and presents remediation options, the user picks an action, and Claude executes it.

## Prerequisites

- **Node.js** >= 18
- **Claude Code** CLI (`claude`)
- **Java** 21 (for Spring PetClinic)
- **GitHub CLI** (`gh`) — optional, for code-fix PR creation
- **Tailscale** — optional, for exposing the webhook to DX OI

## Quick Start

```bash
bash setup.sh        # checks prerequisites, creates .env
# review .env and adjust paths if needed
npm start            # starts the server
```

Open `http://localhost:5000` in a browser.

## Configuration

All settings are in `.env` (copied from `.env.example` by setup). Environment variables override `.env` values.

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `5000` | Server port |
| `COOLDOWN` | `60` | Seconds between dispatches |
| `BUILDS_DIR` | `../builds` | Path to versioned JAR builds |
| `PETCLINIC_DIR` | `../spring-petclinic` | Path to PetClinic git repo |
| `APP_PORT` | `8180` | PetClinic application port |
| `PETCLINIC_LOG` | `/tmp/petclinic.log` | PetClinic log file |
| `PETCLINIC_STDOUT_LOG` | `/tmp/petclinic-stdout.log` | PetClinic stdout log |
| `CLAUDE_LOG` | `/tmp/claude-sre.log` | Claude session log |
| `DEMO_HOST` | `thor` | Hostname in simulated alarms |

Paths can be relative (resolved from this directory) or absolute.

## Directory Layout

The demo expects this sibling structure:

```
demo/
├── builds/
│   ├── v1.0/spring-petclinic-4.0.0-SNAPSHOT.jar
│   ├── v1.1/spring-petclinic-4.0.0-SNAPSHOT.jar
│   └── active/
│       ├── spring-petclinic.jar
│       └── version.txt
├── spring-petclinic/          # git clone with v1.0 and v1.1 tags
└── closed-loop/               # this directory
```

## Demo Runbook

### Setup

1. Deploy the buggy version: `./deploy.sh v1.1`
2. Verify: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8180/owners/1` should return `500`
3. Start server: `npm start`
4. Open `http://localhost:5000`

### Run

1. Click **Trigger Simulated Alarm** (or wait for a real DX OI webhook)
2. Watch the analysis phase stream in the terminal panel
3. Review the root cause analysis and three remediation options
4. Click an action to execute it
5. Watch the remediation phase stream the execution

### Test without UI

```bash
curl -X POST http://localhost:5000/simulate
```

## DX OI Integration

To receive real alarms from DX Operational Intelligence:

1. Expose the server: `tailscale funnel <PORT>`
2. Update the webhook URL in `dxoi-channel-import.json` to your Tailscale hostname
3. Import the channel config: `dx-do channel import importFile=dxoi-channel-import.json`

## How It Works

1. **Webhook/Simulate** — an alarm arrives (real or simulated)
2. **Analysis Phase** — Claude Code reads logs and source code, outputs a structured diagnosis with 3 remediation options (rollback, code fix, ServiceNow ticket)
3. **User Choice** — the operator picks an option from the web UI
4. **Remediation Phase** — Claude Code executes the chosen action (deploys, creates PR, or files ticket)
