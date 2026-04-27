#!/usr/bin/env python3
"""
Pretty-prints /tmp/claude-sre.log (Claude stream-json) for live tailing.

Usage:  ./tail-sre.py           # follow the log
        ./tail-sre.py --all     # replay from start, then follow
"""
import json
import sys
import time
from pathlib import Path

LOG = Path("/tmp/claude-sre.log")

# ANSI colors (tty only)
def c(code, s):
    return f"\033[{code}m{s}\033[0m" if sys.stdout.isatty() else s
DIM   = lambda s: c("2", s)
BOLD  = lambda s: c("1", s)
CYAN  = lambda s: c("36", s)
GREEN = lambda s: c("32", s)
YELL  = lambda s: c("33", s)
RED   = lambda s: c("31", s)
MAG   = lambda s: c("35", s)


def shorten(s, n=200):
    s = s.replace("\n", " ").strip()
    return s if len(s) <= n else s[:n] + "…"


def render(line: str):
    line = line.rstrip()
    if not line:
        return

    # Plain banner lines from server.js dispatch logs
    if not line.startswith("{"):
        if line.startswith("==="):
            return  # skip separator bars, banner handles it
        if line.startswith("[") and "DISPATCH" in line:
            print()
            print(BOLD(CYAN(line)))
            return
        if line.startswith("Error:"):
            print(DIM(shorten(line, 300)))
            return
        if line.startswith("--- Claude stream"):
            print(DIM("  ── stream ──"))
            return
        if line.startswith("--- session end"):
            print(BOLD(GREEN(f"  ✓ {line.strip('- ')}")))
            return
        print(DIM(line))
        return

    try:
        ev = json.loads(line)
    except json.JSONDecodeError:
        print(DIM(shorten(line)))
        return

    t = ev.get("type")
    st = ev.get("subtype")

    if t == "system":
        if st == "init":
            model = ev.get("model", "?")
            tools = ev.get("tools", [])
            print(DIM(f"  init · model={model} · tools={len(tools)}"))
        # skip hook_started / hook_response noise
        return

    if t == "assistant":
        msg = ev.get("message", {})
        for block in msg.get("content", []):
            bt = block.get("type")
            if bt == "text":
                txt = block.get("text", "").strip()
                if txt:
                    print(CYAN("  » ") + shorten(txt, 400))
            elif bt == "tool_use":
                name = block.get("name", "?")
                inp = block.get("input", {})
                print(YELL(f"  ⚡ {name}") + " " + DIM(fmt_tool_input(name, inp)))
            elif bt == "thinking":
                pass  # skip thinking by default
        return

    if t == "user":
        # tool_result
        msg = ev.get("message", {})
        for block in msg.get("content", []):
            if block.get("type") == "tool_result":
                content = block.get("content", "")
                if isinstance(content, list):
                    content = " ".join(x.get("text", "") for x in content if isinstance(x, dict))
                is_err = block.get("is_error")
                prefix = RED("  ✗ ") if is_err else DIM("  ← ")
                print(prefix + DIM(shorten(str(content), 160)))
        return

    if t == "result":
        dur = ev.get("duration_ms", 0) / 1000
        turns = ev.get("num_turns", "?")
        cost = ev.get("total_cost_usd", 0)
        is_err = ev.get("is_error")
        tag = RED("ERROR") if is_err else GREEN("OK")
        print(BOLD(f"  {tag}") + DIM(f"  {turns} turns · {dur:.1f}s · ${cost:.3f}"))
        return


def fmt_tool_input(name, inp):
    if name in ("Read", "Edit", "Write"):
        return inp.get("file_path", "")
    if name == "Bash":
        return shorten(inp.get("command", ""), 120)
    if name == "Grep":
        return f"{inp.get('pattern','')}  {inp.get('path','')}"
    if name == "Glob":
        return inp.get("pattern", "")
    return shorten(json.dumps(inp), 120)


def follow(replay_all=False):
    import subprocess
    if not LOG.exists():
        LOG.touch()
    args = ["tail", "-n", "+1" if replay_all else "0", "-F", str(LOG)]
    proc = subprocess.Popen(args, stdout=subprocess.PIPE, text=True, bufsize=1)
    try:
        for line in proc.stdout:
            render(line)
    finally:
        proc.terminate()


if __name__ == "__main__":
    try:
        follow(replay_all="--all" in sys.argv)
    except KeyboardInterrupt:
        pass
