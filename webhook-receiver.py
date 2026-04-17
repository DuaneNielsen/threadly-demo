#!/usr/bin/env python3
"""
Webhook receiver for DX OI closed-loop remediation demo.

Listens for POST /webhook from DX OI alarm notifications,
extracts the error context, and dispatches Claude Code to diagnose and fix.

Also supports POST /simulate for testing without DX OI.
"""
import json
import os
import subprocess
import threading
import time
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path

# Force unbuffered output for logging when redirected to file
os.environ["PYTHONUNBUFFERED"] = "1"
import builtins
_original_print = builtins.print
def print(*args, **kwargs):
    kwargs.setdefault("flush", True)
    _original_print(*args, **kwargs)

PORT = 5000
COOLDOWN = 60  # seconds between dispatches
DRY_RUN = False  # When True, just log payloads without dispatching Claude
SRE_CONTEXT = Path(__file__).parent / "CLAUDE_SRE.md"
PROJECT_DIR = Path(__file__).parent.parent / "spring-petclinic"

last_dispatch = 0
dispatch_lock = threading.Lock()
dispatch_in_progress = False


def dispatch_claude(error_summary: str, source: str = "webhook"):
    """Spawn Claude Code to diagnose and fix the error."""
    global last_dispatch, dispatch_in_progress

    if DRY_RUN:
        print(f"\n{'='*60}")
        print(f"  [DRY RUN] Would dispatch Claude ({source})")
        print(f"  Error: {error_summary[:200]}")
        print(f"{'='*60}\n")
        return {"status": "dry_run", "reason": "DRY_RUN is enabled"}

    with dispatch_lock:
        if dispatch_in_progress:
            print(f"  [SKIP] Claude SRE already running — ignoring this alarm")
            return {"status": "skipped", "reason": "in_progress"}
        now = time.time()
        if now - last_dispatch < COOLDOWN:
            print(f"  [SKIP] Cooldown active ({int(COOLDOWN - (now - last_dispatch))}s remaining)")
            return {"status": "skipped", "reason": "cooldown"}
        last_dispatch = now
        dispatch_in_progress = True

    print(f"\n{'='*60}")
    print(f"  DISPATCHING CLAUDE ({source})")
    print(f"  Error: {error_summary[:120]}...")
    print(f"{'='*60}\n")

    prompt = f"""An error was detected in the Spring PetClinic application.

Here is the error context from the monitoring system:

```
{error_summary}
```

Diagnose the root cause by reading the source code at the file and line number indicated in the stack trace. Then apply a minimal fix to the source file. Do NOT commit to git. After fixing, explain what you found and what you changed."""

    sre_log = open("/tmp/claude-sre.log", "a", buffering=1)
    sre_log.write(f"\n{'='*60}\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] DISPATCH ({source})\n{'='*60}\n")
    sre_log.write(f"Error: {error_summary}\n\n--- Claude stream ---\n")
    try:
        proc = subprocess.Popen(
            [
                "claude", "-p",
                "--append-system-prompt-file", str(SRE_CONTEXT),
                "--add-dir", str(PROJECT_DIR),
                "--allowedTools", "Read,Edit,Grep,Glob,Bash",
                "--output-format", "stream-json",
                "--verbose",
            ],
            stdin=subprocess.PIPE,
            stdout=sre_log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        proc.communicate(input=prompt, timeout=300)
        sre_log.write(f"\n--- session end (exit={proc.returncode}) ---\n")
        sre_log.close()
        print(f"  Claude session logged to /tmp/claude-sre.log (exit={proc.returncode})")
        return {"status": "completed"}

    except subprocess.TimeoutExpired:
        print("  [ERROR] Claude timed out after 300s")
        return {"status": "error", "reason": "timeout"}
    except Exception as e:
        print(f"  [ERROR] Failed to dispatch Claude: {e}")
        return {"status": "error", "reason": str(e)}
    finally:
        with dispatch_lock:
            dispatch_in_progress = False


class WebhookHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        """Health check endpoint."""
        if self.path == "/health":
            self._respond(200, {"status": "ok", "service": "closed-loop-remediation", "mode": "dry_run" if DRY_RUN else "live"})
        else:
            self._respond(200, {
                "service": "DX OI Closed-Loop Remediation Demo",
                "endpoints": {
                    "POST /webhook": "DX OI alarm webhook receiver",
                    "POST /simulate": "Simulate an alarm for testing",
                    "GET /health": "Health check",
                },
            })

    def do_POST(self):
        """Handle webhook POST from DX OI or simulation."""
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"

        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            self._respond(400, {"error": "Invalid JSON"})
            return

        if self.path == "/webhook":
            self._handle_webhook(payload)
        elif self.path == "/simulate":
            self._handle_simulate(payload)
        else:
            self._respond(404, {"error": "Not found"})

    def _handle_webhook(self, payload):
        """Handle DX OI webhook payload."""
        # Extract error info from DX OI alarm payload
        # DX OI genericwebhook sends whatever fields we configure
        error_summary = self._extract_error_from_dxoi(payload)

        print(f"\n[WEBHOOK] Received alarm from DX OI")
        print(f"  Payload: {json.dumps(payload, indent=2)[:500]}")

        # Acknowledge immediately, dispatch in background
        self._respond(200, {"status": "accepted"})

        thread = threading.Thread(target=dispatch_claude, args=(error_summary, "dxoi-webhook"))
        thread.start()

    def _handle_simulate(self, payload):
        """Handle simulated alarm for testing."""
        error_text = payload.get("error", "")
        if not error_text:
            self._respond(400, {"error": "Missing 'error' field in payload"})
            return

        print(f"\n[SIMULATE] Received test alarm")

        self._respond(200, {"status": "accepted"})

        thread = threading.Thread(target=dispatch_claude, args=(error_text, "simulation"))
        thread.start()

    def _extract_error_from_dxoi(self, payload):
        """Extract a useful error summary from DX OI alarm payload fields."""
        parts = []

        # Try common DX OI alarm fields
        for field in ["message", "Alarm Name", "alarm_name", "alarmName",
                       "Metric Name", "metric_name", "metricName",
                       "Component Name", "component_name",
                       "Agent", "agent", "host",
                       "Alarm Status", "status", "severity"]:
            val = payload.get(field)
            if val:
                parts.append(f"{field}: {val}")

        # If we got structured fields, join them
        if parts:
            return "\n".join(parts)

        # Fallback: dump the whole payload
        return json.dumps(payload, indent=2)

    def _respond(self, code, data):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def log_message(self, format, *args):
        """Suppress default access logs, we do our own logging."""
        pass


def main():
    print(f"{'='*60}")
    print(f"  DX OI Closed-Loop Remediation Demo")
    print(f"{'='*60}")
    print(f"  Listening on port {PORT}")
    print(f"  Mode:         {'DRY RUN (log only)' if DRY_RUN else 'LIVE (dispatching Claude)'}")
    print(f"  SRE context:  {SRE_CONTEXT}")
    print(f"  Project dir:  {PROJECT_DIR}")
    print(f"  Cooldown:     {COOLDOWN}s")
    print(f"")
    print(f"  Endpoints:")
    print(f"    POST /webhook   - DX OI alarm webhook")
    print(f"    POST /simulate  - Test with simulated alarm")
    print(f"    GET  /health    - Health check")
    print(f"{'='*60}")
    print(f"  Waiting for alarms...\n")

    server = HTTPServer(("0.0.0.0", PORT), WebhookHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.server_close()


if __name__ == "__main__":
    main()
