# threadly-demo

AI-driven incident remediation demo. A Spring Boot e-commerce app + a fake payment service have a divide-by-zero bug; Fluent Bit ships the error to a Node.js orchestrator that dispatches Claude Code to diagnose the failure, present three remediation options, and execute the operator's choice.

## Layout

| Directory | Purpose |
|---|---|
| `threadly/` | Storefront app — Spring Boot + Thymeleaf + SQLite (port 8180). Tagline: "threads never drop." |
| `threadly-payments/` | Stripe-style fake PSP — Spring Boot + SQLite (port 8181). |
| `orchestrator/` | Node.js webhook receiver + state machine + web UI (port 5000). Dispatches `claude -p` for two-phase analysis + remediation. |

## Quick start

Each subdirectory has its own `README.md` and `CLAUDE.md` with details. The minimum viable demo:

```bash
cd orchestrator
bash setup.sh                                    # check prereqs, create .env
cd ../threadly-payments && ./mvnw package -DskipTests   # build PSP JAR
cd ../threadly && ./mvnw package -DskipTests           # build storefront JAR
cd ../orchestrator
./deploy-payments.sh v1.0
./deploy.sh v1.1                                 # deploys the buggy version
cd monitoring && docker compose up -d            # Fluent Bit + Loki + Grafana
cd .. && npm start                               # orchestrator + web UI
```

Open `http://localhost:5000`, hit `http://localhost:8180/products/7` to trigger the planted bug, watch Claude analyze and propose fixes.

## Tags

Per-app version tags use prefixes to disambiguate:

- `threadly-v1.0` — clean storefront
- `threadly-v1.1` — divide-by-zero in `DiscountCalculator.percentOff()` triggered by `/products/7`
- `payments-v1.0` — initial PSP

## Prerequisites

- Java 21 (Threadly + Payments)
- Node.js 20+ (orchestrator)
- Docker + docker-compose (Fluent Bit / Loki / Grafana)
- Claude Code CLI (`claude`) — orchestrator dispatches headless `claude -p`
- GitHub CLI (`gh`) — optional, for the code-fix remediation path
