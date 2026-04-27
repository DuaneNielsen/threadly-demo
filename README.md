# Closed-Loop Remediation Demo

AI-driven incident remediation with human-in-the-loop: a monitoring agent (Fluent Bit, or any tool that can POST a JSON webhook) detects an error, fires a webhook, Claude Code diagnoses the issue and presents remediation options, the user picks an action, and Claude executes it.

Demo apps: **Threadly** — a Spring Boot t-shirt store with cart + checkout, and **threadly-payments** — a Stripe-style fake payment service it calls. A divide-by-zero bug is planted in Threadly's discount calculator, and the payment service has a card number (`4000 0000 0000 0119`) that triggers 500s for future bug planting. Tagline: "threads never drop."

## Prerequisites

- **Node.js** >= 18
- **Claude Code** CLI (`claude`)
- **Java** 21 (for Threadly)
- **Docker** (for the Fluent Bit + Loki + Grafana monitoring stack)
- **sqlite3** CLI — optional, for inspecting the demo DBs
- **GitHub CLI** (`gh`) — optional, for code-fix PR creation
- **A tunnel/reverse-proxy** (Tailscale Funnel, ngrok, Caddy, etc.) — optional, only if you want to receive webhooks from a remote source

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
| `THREADLY_DB` | `/tmp/threadly.db` | SQLite DB file for Threadly (persisted across deploys) |
| `PAYMENTS_DIR` | `../threadly-payments` | Path to the payment service repo |
| `PAYMENTS_BUILDS_DIR` | `../threadly-payments/builds` | Path to versioned JAR builds for payments |
| `PAYMENTS_NAME` | `ThreadlyPayments` | Display name for the payment service |
| `PAYMENTS_PORT` | `8181` | Payment service port |
| `PAYMENTS_JAR_NAME` | `threadly-payments.jar` | JAR filename in `PAYMENTS_BUILDS_DIR/vN.N/` |
| `PAYMENTS_LOG` | `/tmp/payments.log` | Payment service log (tailed by Fluent Bit) |
| `PAYMENTS_STDOUT_LOG` | `/tmp/payments-stdout.log` | Payment service stdout |
| `PAYMENTS_URL` | `http://localhost:8181` | URL passed to Threadly for payment calls |
| `PAYMENTS_DB` | `/tmp/payments.db` | SQLite DB file for payments (persisted across deploys) |
| `CLAUDE_LOG` | `/tmp/claude-sre.log` | Claude session log |
| `DEMO_HOST` | `thor` | Hostname in simulated alarms |
| `DEMO_TITLE` | `Threadly Closed-Loop Remediation` | Web UI title |

Paths can be relative (resolved from this directory) or absolute.

## Directory Layout

The demo expects this sibling structure:

```
projects/
├── agentic-sre-demo/              # this directory
│   ├── server.js
│   ├── deploy.sh                  # deploys Threadly
│   ├── deploy-payments.sh         # deploys the payment service
│   ├── monitoring/                # Fluent Bit + Loki + Grafana
│   └── ...
├── threadly/                      # t-shirt store (cart, checkout, orders)
│   ├── src/
│   ├── pom.xml
│   └── builds/
│       ├── v1.0/threadly.jar      # clean
│       ├── v1.1/threadly.jar      # planted bug
│       └── active/
└── threadly-payments/             # fake Stripe-style PSP
    ├── src/
    ├── pom.xml
    └── builds/
        ├── v1.0/threadly-payments.jar
        └── active/
```

## Demo Runbook

### Setup

1. Start the monitoring stack: `cd monitoring && docker compose up -d`
2. Deploy the payment service: `./deploy-payments.sh v1.0`
3. Deploy the buggy Threadly version: `./deploy.sh v1.1`
4. Verify: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8180/products/7` should return `500` (freebie triggers the divide-by-zero)
5. Verify payments: `curl -s http://localhost:8181/actuator/health`
6. Start server: `npm start`
7. Open `http://localhost:5000`

### Run

1. Click **Trigger Simulated Alarm** (or wait for a real Fluent Bit webhook from an actual error)
2. Watch the analysis phase stream in the terminal panel
3. Review the root cause analysis and three remediation options
4. Click an action to execute it
5. Watch the remediation phase stream the execution

### Test without UI

```bash
curl -X POST http://localhost:5000/simulate
```

## Public Webhook Access (optional)

To receive alarms from a remote source (a SaaS monitor, an external Fluent Bit, etc.) the `/webhook` endpoint needs to be reachable from the internet. Any tunnel or reverse-proxy works — Tailscale Funnel, ngrok, or Caddy with a real domain. The webhook expects a JSON body; see the `/simulate` endpoint for the canonical payload shape.

## How It Works

1. **Webhook/Simulate** — an alarm arrives (real or simulated). Fluent Bit tails `APP_LOG`, filters ERROR lines, and POSTs a JSON payload to `/webhook`.
2. **Analysis Phase** — Claude Code reads logs and source code, outputs a structured diagnosis with 3 remediation options (rollback, code fix, ServiceNow ticket)
3. **User Choice** — the operator picks an option from the web UI
4. **Remediation Phase** — Claude Code executes the chosen action (deploys, creates PR, or files ticket)
