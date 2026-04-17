#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ERRORS=0

check() {
    if command -v "$1" &>/dev/null; then
        echo "  [OK] $1 ($($1 --version 2>&1 | head -1))"
    else
        echo "  [MISSING] $1 — $2"
        ERRORS=$((ERRORS + 1))
    fi
}

check_optional() {
    if command -v "$1" &>/dev/null; then
        echo "  [OK] $1 ($($1 --version 2>&1 | head -1))"
    else
        echo "  [SKIP] $1 — optional ($2)"
    fi
}

echo "=== Closed-Loop Remediation Demo Setup ==="
echo ""

# Load .env if it exists (for path validation)
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -a
    source "$SCRIPT_DIR/.env"
    set +a
fi

BUILDS_DIR="${BUILDS_DIR:-../builds}"
PETCLINIC_DIR="${PETCLINIC_DIR:-../spring-petclinic}"

# Resolve relative paths
[[ "$BUILDS_DIR" != /* ]] && BUILDS_DIR="$SCRIPT_DIR/$BUILDS_DIR"
[[ "$PETCLINIC_DIR" != /* ]] && PETCLINIC_DIR="$SCRIPT_DIR/$PETCLINIC_DIR"

echo "Prerequisites:"
check node "required (>=18) — https://nodejs.org"
check claude "required — Claude Code CLI"
check java "required (21) — for Spring PetClinic"
check_optional gh "needed for code-fix PR creation"
check_optional tailscale "needed for DX OI webhook tunnel"
echo ""

echo "Directory structure:"
for dir in "$BUILDS_DIR/v1.0" "$BUILDS_DIR/v1.1" "$BUILDS_DIR/active"; do
    if [ -d "$dir" ]; then
        echo "  [OK] $dir"
    else
        echo "  [MISSING] $dir"
        ERRORS=$((ERRORS + 1))
    fi
done

if [ -d "$PETCLINIC_DIR" ]; then
    echo "  [OK] $PETCLINIC_DIR"
else
    echo "  [MISSING] $PETCLINIC_DIR"
    ERRORS=$((ERRORS + 1))
fi
echo ""

# Create .env from .env.example
if [ ! -f "$SCRIPT_DIR/.env" ]; then
    if [ -f "$SCRIPT_DIR/.env.example" ]; then
        cp "$SCRIPT_DIR/.env.example" "$SCRIPT_DIR/.env"
        echo "Created .env from .env.example — review and adjust paths."
    else
        echo "[WARN] No .env.example found to copy."
    fi
else
    echo ".env already exists."
fi
echo ""

if [ "$ERRORS" -gt 0 ]; then
    echo "=== $ERRORS issue(s) found. Fix them and re-run. ==="
    exit 1
fi

echo "=== Setup complete! ==="
echo "  Review .env and adjust paths if needed."
echo "  Start: npm start"
