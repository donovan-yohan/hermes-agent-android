#!/usr/bin/env python3
"""Test-only server->client request driver for the #307 live Gateway smoke.

NOT production code, and not a fake Gateway. This process starts the *real*
upstream `hermes serve` ASGI app (`hermes_cli.web_server.start_server`, headless)
imported from the pinned snapshot, on loopback, against a throwaway `HERMES_HOME`
and the run's ephemeral session token read from the environment. Nothing here
replaces the Gateway, its socket, its dispatch or its settlement.

What it adds is the one thing a test needs and no production path offers: a
control channel that parks a *real* `tui_gateway.server_requests.send()` on a
*real* bound session and reports how it settled, so the Android client on the
other end of the socket can be asserted against the server's actual behaviour.

Protocol (all on stdin in, stdout out, one JSON object per line):

    in   {"op": "request", "session_id": "<sid>", "method": "clarify",
          "params": {...}, "timeout": 5}
    out  HM307 RESULT {"answerable": true, "settled": true, ...}

stdout carries **only** `HM307 ` marker lines: at start, `os.dup2(2, 1)` points
fd 1 at stderr and the marker handle keeps a private duplicate of the original
pipe, so no library log, warning or traceback can be mistaken for a marker.
The session token is never written to stdout, stderr, or any file.
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import threading
import time
import urllib.request

MARK = "HM307 "


def _marker_handle():
    """A private handle on the JVM-visible stdout pipe, with fd 1 redirected to stderr."""
    handle = os.fdopen(os.dup(1), "w", buffering=1)
    os.dup2(2, 1)
    return handle


def _free_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def _wait_health(port: int, token: str, deadline_s: float) -> bool:
    """The real readiness route, the same one the client's health check calls."""
    request = urllib.request.Request(f"http://127.0.0.1:{port}/api/health")
    request.add_header("X-Hermes-Session-Token", token)
    end = time.monotonic() + deadline_s
    while time.monotonic() < end:
        try:
            with urllib.request.urlopen(request, timeout=2) as response:
                if response.status == 200:
                    return True
        except Exception:
            time.sleep(0.25)
    return False


def _answerable(gateway_server, sid: str):
    """The server's own predicate for 'a request for this session can be answered'."""
    predicate = getattr(gateway_server, "_session_client_answers_requests", None)
    return bool(predicate(sid)) if predicate is not None else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True, help="root of the read-only upstream snapshot")
    args = parser.parse_args()

    # The snapshot is imported, never written to: no __pycache__ under it.
    sys.dont_write_bytecode = True
    sys.path.insert(0, args.project)

    out = _marker_handle()

    token = os.environ.get("HERMES_DASHBOARD_SESSION_TOKEN") or ""
    hermes_home = os.environ.get("HERMES_HOME") or ""
    if not token or not hermes_home:
        out.write(f"{MARK}ERROR missing HERMES_DASHBOARD_SESSION_TOKEN or HERMES_HOME\n")
        return 2

    port = _free_loopback_port()

    def serve() -> None:
        from hermes_cli.web_server import start_server

        start_server(host="127.0.0.1", port=port, open_browser=False, headless=True)

    threading.Thread(target=serve, name="hm307-server", daemon=True).start()
    if not _wait_health(port, token, 180.0):
        out.write(f"{MARK}ERROR server never answered /api/health on 127.0.0.1:{port}\n")
        return 3

    from tui_gateway import server as gateway_server
    from tui_gateway import server_requests

    out.write(f"{MARK}READY port={port} pid={os.getpid()} python={sys.version.split()[0]}\n")

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            command = json.loads(line)
        except json.JSONDecodeError:
            out.write(f"{MARK}ERROR unparseable control line\n")
            continue
        if not isinstance(command, dict):
            continue
        if command.get("op") == "quit":
            break
        if command.get("op") != "request":
            out.write(f"{MARK}ERROR unknown op\n")
            continue

        sid = str(command.get("session_id") or "")
        method = str(command.get("method") or "")
        params = command.get("params") if isinstance(command.get("params"), dict) else {}
        raw_timeout = command.get("timeout")
        timeout = None if raw_timeout is None else float(raw_timeout)
        # Read *before* the send: this is the gate the request is subject to.
        answerable = _answerable(gateway_server, sid)

        started = time.monotonic()
        result = server_requests.send(method, sid, params, timeout=timeout)
        out.write(f"{MARK}RESULT " + json.dumps({
            "session_id": sid,
            "method": method,
            "answerable": answerable,
            "settled": result is not None,
            "result": result,
            "elapsed_ms": int((time.monotonic() - started) * 1000),
        }, sort_keys=True) + "\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
