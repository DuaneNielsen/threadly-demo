# Closed-Loop Remediation Demo

AI-driven incident remediation with human-in-the-loop: DX Operational Intelligence (or Fluent Bit) detects an error, fires a webhook, Claude Code diagnoses the issue and presents remediation options, the user picks an action, and Claude executes it.

Demo app: **Threadly** — a three-tier Spring Boot t-shirt store with a divide-by-zero bug planted in its discount calculator. Tagline: "threads never drop."

## Prerequisites

- **Node.js** >= 18
- **Claude Code** CLI (`claude`)
- **Java** 21 (for Threadly)
- **Docker** (for the Fluent Bit + Loki + Grafana stack, and optional Postgres)
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
| `BUILDS_DIR` | `../threadly/builds` | Path to versioned JAR builds |
| `APP_DIR` | `../threadly` | Path to the demo app repo |
| `APP_NAME` | `Threadly` | Display name |
| `APP_PORT` | `8180` | Demo app port |
| `APP_JAR_NAME` | `threadly.jar` | JAR filename in `BUILDS_DIR/vN.N/` |
| `APP_LOG` | `/tmp/threadly.log` | App log file (tailed by Fluent Bit) |
| `APP_STDOUT_LOG` | `/tmp/threadly-stdout.log` | App stdout log |
| `APP_PROFILES` | `h2` | Spring profile (`h2` for embedded, empty for Postgres) |
| `CLAUDE_LOG` | `/tmp/claude-sre.log` | Claude session log |
| `DEMO_HOST` | `thor` | Hostname in simulated alarms |
| `DEMO_TITLE` | `Threadly Closed-Loop Remediation` | Web UI title |

Paths can be relative (resolved from this directory) or absolute.

## Directory Layout

The demo expects this sibling structure:

```
projects/
├── agentic-sre-demo/           # this directory
│   ├── server.js
│   ├── deploy.sh
│   ├── monitoring/             # Fluent Bit + Loki + Grafana
│   └── ...
└── threadly/
    ├── src/                    # Spring Boot source
    ├── pom.xml
    ├── docker-compose.yml      # Postgres (optional)
    └── builds/
        ├── v1.0/threadly.jar   # clean
        ├── v1.1/threadly.jar   # planted bug
        └── active/             # deploy.sh swaps in here
```

## Demo Runbook

### Setup

1. Start the monitoring stack: `cd monitoring && docker compose up -d`
2. Deploy the buggy version: `./deploy.sh v1.1`
3. Verify: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8180/products/7` should return `500` (freebie triggers the divide-by-zero)
4. Start server: `npm start`
5. Open `http://localhost:5000`

### Run

1. Click **Trigger Simulated Alarm** (or wait for a real Fluent Bit / DX OI webhook)
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

1. **Webhook/Simulate** — an alarm arrives (real or simulated). Fluent Bit tails `APP_LOG`, filters ERROR lines, and POSTs a DX-OI-style payload to `/webhook`.
2. **Analysis Phase** — Claude Code reads logs and source code, outputs a structured diagnosis with 3 remediation options (rollback, code fix, ServiceNow ticket)
3. **User Choice** — the operator picks an option from the web UI
4. **Remediation Phase** — Claude Code executes the chosen action (deploys, creates PR, or files ticket)
