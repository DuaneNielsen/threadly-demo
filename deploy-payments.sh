#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Load .env if present (env vars take precedence)
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -a
    source "$SCRIPT_DIR/.env"
    set +a
fi

BUILDS_DIR="${PAYMENTS_BUILDS_DIR:-../threadly-payments/builds}"
# Resolve relative paths against script directory
if [[ "$BUILDS_DIR" != /* ]]; then
    BUILDS_DIR="$(cd "$SCRIPT_DIR/$BUILDS_DIR" && pwd)"
fi
ACTIVE_DIR="$BUILDS_DIR/active"
JAR_NAME="${PAYMENTS_JAR_NAME:-threadly-payments.jar}"
PAYMENTS_PORT="${PAYMENTS_PORT:-8181}"
LOG_FILE="${PAYMENTS_LOG:-/tmp/payments.log}"
STDOUT_LOG="${PAYMENTS_STDOUT_LOG:-/tmp/payments-stdout.log}"
APP_NAME="${PAYMENTS_NAME:-ThreadlyPayments}"

usage() {
    echo "Usage: $0 <version>"
    echo "  e.g.: $0 v1.0"
    echo ""
    echo "Available versions:"
    for d in "$BUILDS_DIR"/v*/; do
        [ -d "$d" ] && echo "  $(basename "$d")"
    done
    exit 1
}

[ $# -lt 1 ] && usage
VERSION="$1"
SOURCE_JAR="$BUILDS_DIR/$VERSION/$JAR_NAME"

if [ ! -f "$SOURCE_JAR" ]; then
    echo "ERROR: JAR not found: $SOURCE_JAR"
    exit 1
fi

echo "=== Deploying $APP_NAME $VERSION ==="

PID=$(lsof -ti:$PAYMENTS_PORT 2>/dev/null || true)
if [ -n "$PID" ]; then
    echo "Stopping current instance (PID $PID)..."
    kill "$PID" 2>/dev/null || true
    sleep 2
    if kill -0 "$PID" 2>/dev/null; then
        kill -9 "$PID" 2>/dev/null || true
        sleep 1
    fi
fi

mkdir -p "$ACTIVE_DIR"
echo "Copying $VERSION JAR to active..."
cp "$SOURCE_JAR" "$ACTIVE_DIR/$JAR_NAME"
echo "$VERSION" > "$ACTIVE_DIR/version.txt"

: > "$LOG_FILE"

echo "Starting $APP_NAME $VERSION..."
java -jar "$ACTIVE_DIR/$JAR_NAME" \
    --server.port=$PAYMENTS_PORT \
    --logging.file.name="$LOG_FILE" \
    > "$STDOUT_LOG" 2>&1 &

NEW_PID=$!
echo "Started PID $NEW_PID"

echo "Waiting for health check..."
for i in $(seq 1 60); do
    if curl -sf http://localhost:$PAYMENTS_PORT/actuator/health > /dev/null 2>&1; then
        echo "=== $APP_NAME $VERSION is UP (took ${i}s) ==="
        exit 0
    fi
    if ! kill -0 "$NEW_PID" 2>/dev/null; then
        echo "ERROR: Process died during startup"
        tail -20 "$STDOUT_LOG" || true
        exit 1
    fi
    sleep 1
done

echo "ERROR: Health check timed out after 60s"
exit 1
