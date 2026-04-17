#!/usr/bin/env bash
# watch-and-fix.sh — Tail PetClinic log, detect errors, dispatch Claude to fix them
set -euo pipefail

LOGFILE="/tmp/petclinic.log"
SRE_CONTEXT="$(dirname "$0")/CLAUDE_SRE.md"
PROJECT_DIR="/home/duane/dx-do/demo/spring-petclinic"
COOLDOWN=30  # seconds between dispatches (avoid hammering on repeated errors)

last_dispatch=0

echo "=== Closed-Loop Remediation Watcher ==="
echo "Log file:    $LOGFILE"
echo "SRE context: $SRE_CONTEXT"
echo "Project dir: $PROJECT_DIR"
echo "Cooldown:    ${COOLDOWN}s"
echo "Watching for errors..."
echo ""

# Collect error + stack trace lines, then dispatch
error_buffer=""
collecting=false

dispatch_claude() {
    local error_text="$1"
    local now
    now=$(date +%s)

    # Cooldown check
    if (( now - last_dispatch < COOLDOWN )); then
        echo "[$(date '+%H:%M:%S')] Skipping dispatch (cooldown active)"
        return
    fi
    last_dispatch=$now

    echo ""
    echo "============================================"
    echo "[$(date '+%H:%M:%S')] ERROR DETECTED - Dispatching Claude..."
    echo "============================================"
    echo "$error_text" | head -5
    echo "..."
    echo ""

    # Dispatch Claude in print mode with the SRE context appended to default system prompt
    cat <<PROMPT | claude -p \
        --append-system-prompt-file "$SRE_CONTEXT" \
        --add-dir "$PROJECT_DIR" \
        --allowedTools "Read,Edit,Grep,Glob,Bash"
An error was detected in the Spring PetClinic application log.

Here is the error from the log:

\`\`\`
${error_text}
\`\`\`

Diagnose the root cause by reading the source code at the file and line number indicated in the stack trace. Then apply a minimal fix to the source file. Do NOT commit to git. After fixing, explain what you found and what you changed.
PROMPT

    echo ""
    echo "[$(date '+%H:%M:%S')] Claude finished. Watching for more errors..."
    echo ""
}

# Tail the log and buffer ERROR + stack trace
tail -n 0 -F "$LOGFILE" 2>/dev/null | while IFS= read -r line; do
    # Start collecting on ERROR line
    if [[ "$line" == *"ERROR"* ]]; then
        collecting=true
        error_buffer="$line"
        continue
    fi

    # Continue collecting stack trace lines (tab-indented "at" lines in Java stack traces)
    if $collecting; then
        if [[ "$line" =~ ^[[:space:]]+at\  ]] || [[ "$line" == *"Caused by:"* ]] || [[ "$line" =~ [0-9]+\ more ]]; then
            error_buffer="${error_buffer}
${line}"
        else
            # Stack trace ended — dispatch
            dispatch_claude "$error_buffer"
            collecting=false
            error_buffer=""
        fi
    fi
done
