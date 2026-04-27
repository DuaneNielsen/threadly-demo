#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Load .env if present (env vars take precedence)
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -a
    source "$SCRIPT_DIR/.env"
    set +a
fi

BUILDS_DIR="${BUILDS_DIR:-../builds}"
# Resolve relative paths against script directory
if [[ "$BUILDS_DIR" != /* ]]; then
    BUILDS_DIR="$(cd "$SCRIPT_DIR/$BUILDS_DIR" && pwd)"
fi
ACTIVE_DIR="$BUILDS_DIR/active"
JAR_NAME="${APP_JAR_NAME:-threadly.jar}"
APP_PORT="${APP_PORT:-8180}"
LOG_FILE="${APP_LOG:-/tmp/threadly.log}"
STDOUT_LOG="${APP_STDOUT_LOG:-/tmp/threadly-stdout.log}"
APP_NAME="${APP_NAME:-Threadly}"
APP_PROFILES="${APP_PROFILES:-}"
PAYMENTS_URL="${PAYMENTS_URL:-http://localhost:8181}"

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

# Detect deployment mode: if a matching systemd unit is loaded, hand off to it;
# otherwise fall back to the local-dev kill+fork pattern.
SYSTEMD_UNIT="${SYSTEMD_UNIT:-threadly}"
USE_SYSTEMD=0
if command -v systemctl >/dev/null 2>&1 && \
   systemctl cat "$SYSTEMD_UNIT.service" >/dev/null 2>&1; then
    USE_SYSTEMD=1
fi

# Stage the JAR in active/ — both modes need this first.
mkdir -p "$ACTIVE_DIR"
echo "Copying $VERSION JAR to active..."
cp "$SOURCE_JAR" "$ACTIVE_DIR/$JAR_NAME"
echo "$VERSION" > "$ACTIVE_DIR/version.txt"

# Truncate log so Fluent Bit only sees fresh lines from this version
: > "$LOG_FILE"

if [ "$USE_SYSTEMD" = "1" ]; then
    echo "systemd unit detected — restarting $SYSTEMD_UNIT.service..."
    sudo systemctl restart "$SYSTEMD_UNIT.service"
else
    # Local dev path: kill any existing JVM on this port, then fork a fresh one.
    PID=$(lsof -ti:$APP_PORT 2>/dev/null || true)
    if [ -n "$PID" ]; then
        echo "Stopping current instance (PID $PID)..."
        kill "$PID" 2>/dev/null || true
        sleep 2
        if kill -0 "$PID" 2>/dev/null; then
            kill -9 "$PID" 2>/dev/null || true
            sleep 1
        fi
    fi

    echo "Starting $APP_NAME $VERSION..."
    PROFILE_ARG=""
    if [ -n "$APP_PROFILES" ]; then
        PROFILE_ARG="--spring.profiles.active=$APP_PROFILES"
    fi

    java -jar "$ACTIVE_DIR/$JAR_NAME" \
        --server.port=$APP_PORT \
        --logging.file.name="$LOG_FILE" \
        --payments.url="$PAYMENTS_URL" \
        $PROFILE_ARG \
        > "$STDOUT_LOG" 2>&1 &

    NEW_PID=$!
    echo "Started PID $NEW_PID"
fi

# Wait for health (both modes)
echo "Waiting for health check..."
for i in $(seq 1 60); do
    if curl -sf http://localhost:$APP_PORT/actuator/health > /dev/null 2>&1; then
        echo "=== $APP_NAME $VERSION is UP (took ${i}s) ==="
        exit 0
    fi
    if [ "$USE_SYSTEMD" = "0" ] && ! kill -0 "${NEW_PID:-0}" 2>/dev/null; then
        echo "ERROR: Process died during startup"
        tail -20 "$STDOUT_LOG" || true
        exit 1
    fi
    sleep 1
done

echo "ERROR: Health check timed out after 60s"
exit 1
