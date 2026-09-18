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
REDACTED = "<redacted>"

#: Everything the child is allowed to inherit *and* keep through sanitization.
#: The harness sets all of these explicitly; anything else Hermes-shaped is
#: stripped, because this process is launched from inside a live Hermes session
#: and inheriting that session's environment would point the probe at the user's
#: own runtime.
_KEEP_ENV = {
    "HERMES_HOME",
    "HOME",
    "PATH",
    "LANG",
    "TZ",
    "TMPDIR",
}

#: Run-scoped values the harness must supply: the ephemeral token this run minted
#: (never the user's), and the marker flag naming it for the redaction pass.
_RUN_ENV = {
    "HERMES_DASHBOARD_SESSION_TOKEN",
    "HM307_TOKEN_ENV_NAME",
}

#: Namespaces that must never reach the probe: no inherited model-provider
#: credentials, no messaging or VCS tokens.
_CREDENTIAL_NAMESPACES = (
    "ANTHROPIC", "OPENAI", "OLLAMA", "GOOGLE", "GEMINI", "XAI", "GROQ", "MISTRAL",
    "DEEPSEEK", "OPENROUTER", "NOUS", "HF", "HUGGINGFACE", "GITHUB", "GH", "AWS",
    "AZURE", "TELEGRAM", "DISCORD", "SLACK", "TWILIO", "BRAVE", "SERP", "EXA",
    "TAVILY", "ELEVENLABS", "DASHSCOPE", "MOONSHOT", "FIREWORKS", "TOGETHER",
)


def _sanitize_environment() -> None:
    """Drop inherited Hermes/provider variables the harness did not set.

    A blocklist by namespace, deliberately not claimed as an allowlist: the real
    guarantee is on the caller's side, where `LiveGatewayDriver.start` builds the
    child's environment from an empty map and sets only what it needs. This is
    belt-and-braces for a caller that launched the driver by hand from a shell
    that already had a Hermes session's environment in it.
    """
    for name in list(os.environ):
        if name in _KEEP_ENV or name in _RUN_ENV:
            continue
        if name.startswith("HERMES_"):
            del os.environ[name]
            continue
        if name.split("_", 1)[0] in _CREDENTIAL_NAMESPACES:
            del os.environ[name]


def _install_stderr_redaction(secrets: list[str]) -> None:
    """Scrub *secrets* from everything written to fd 2.

    The server is a real uvicorn app and its access log line carries the request
    line, which for `/api/ws` includes `?token=`. fd 2 is where those lines go,
    and the caller redirects it to a file it may read back into a failure
    message, so redaction is installed on the descriptor itself rather than
    trusting a logging config: any handler, any library, any thread — including
    ones that captured `sys.stderr` before this ran — still writes through fd 2.

    Reads are raw `os.read` on the pipe, not a buffered text stream: a buffered
    `read(n)` on a pipe blocks until n bytes *or* EOF, so a log line would sit
    unflushed for the life of the process and then die with the pump thread.

    A fixed "hold back N bytes" carry is *not* enough, and the difference is the
    whole correctness of this function: if a secret is split across two reads,
    the first release can end part-way through it and the next release begins
    with the tail, so the two halves land adjacently in the file and the token
    is in the log after all. What is held back instead is every byte from the
    earliest position that could be the *start* of a secret still waiting for
    its remainder — so no fragment of a secret is ever released before the whole
    of it is in the buffer, where it can be replaced.
    """
    live = [s for s in secrets if s]
    if not live:
        return
    # Compared as bytes: the buffer is raw pipe bytes and the secrets are the
    # ASCII the socket query carried. A `str` comparison here raises, and a
    # pump that dies on the first line releases its remaining buffer unredacted.
    needles = [s.encode() for s in live]
    longest = max(len(needle) for needle in needles)
    real_stderr = os.dup(2)
    read_fd, write_fd = os.pipe()
    os.dup2(write_fd, 2)
    os.close(write_fd)
    pending = bytearray()

    def safe_cut() -> int:
        """Length of the prefix of *pending* that can contain no partial secret.

        Only the final `longest` bytes can start a secret whose remainder is
        still missing, so the scan is bounded to them. A fragment that is a
        *proper* prefix of a needle at position 0 counts too — that is the
        byte-at-a-time case, and skipping it released every partial token one
        character at a time until the whole of it sat in the log. A fragment
        that *is* the whole needle is not held back: `emit` replaces it.
        """
        tail_start = max(0, len(pending) - longest)
        for position in range(tail_start, len(pending)):
            fragment = bytes(pending[position:])
            if any(
                len(fragment) < len(needle) and needle.startswith(fragment) for needle in needles
            ):
                return position
        return len(pending)

    def emit(chunk: bytes | bytearray) -> None:
        text = bytes(chunk).decode("utf-8", errors="replace")
        for secret in live:
            text = text.replace(secret, REDACTED)
        os.write(real_stderr, text.encode("utf-8", errors="replace"))

    def drain(final: bool = False) -> None:
        """Release the safe prefix of the buffer; on a *final* drain drop the tail.

        The tail is whatever is left once no further bytes can complete a secret:
        a partial prefix of one. It is dropped, never emitted — a truncated log
        line is recoverable, a leaked token is not. This is the whole reason
        shutdown is not a `flush`: flushing would write that prefix, and a pump
        still running would then write the rest of the secret after it.
        """
        cut = safe_cut()
        if cut:
            emit(pending[:cut])
            del pending[:cut]
        if final:
            del pending[:]

    def pump() -> None:
        try:
            while True:
                chunk = os.read(read_fd, 8192)
                if not chunk:
                    break
                pending.extend(chunk)
                drain()
        except Exception:  # noqa: BLE001 - a failed pump must not release raw bytes
            del pending[:]
        drain(final=True)
        os.close(read_fd)

    # No exit flush: the daemon pump owns the buffer, and at interpreter exit it
    # is raced by the remaining writes it exists to scrub. Its last act is a
    # final drain, so anything it did not get to is dropped rather than printed.
    threading.Thread(target=pump, name="hm307-stderr-redaction", daemon=True).start()


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


def _provenance(project: str) -> dict:
    """Attest what was actually imported: snapshot SHA, and the module a real load resolves to."""
    import subprocess

    import hermes_cli.web_server as web
    import tui_gateway.server as gateway_server

    try:
        sha = subprocess.run(
            ["git", "-C", project, "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=20, check=True,
        ).stdout.strip()
    except Exception:
        sha = "unknown"
    return {
        "project": project,
        "snapshot_sha": sha,
        "gateway_server_module": getattr(gateway_server, "__file__", "?"),
        "web_server_module": getattr(web, "__file__", "?"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True, help="root of the read-only upstream snapshot")
    args = parser.parse_args()

    # The snapshot is imported, never written to: no __pycache__ under it.
    sys.dont_write_bytecode = True
    sys.path.insert(0, args.project)

    # Sanitize before anything imports the server, so no inherited gateway or
    # provider state can reach the runtime this probe starts.
    _sanitize_environment()

    # Everything written to fd 2 from here on is scrubbed of this run's token.
    # Installed before the server import so no import-time log can precede it.
    token_env_name = os.environ.get("HM307_TOKEN_ENV_NAME") or "HERMES_DASHBOARD_SESSION_TOKEN"
    _install_stderr_redaction([os.environ.get(token_env_name, "")])

    out = _marker_handle()

    token = os.environ.get(token_env_name) or ""
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

    out.write(f"{MARK}READY port={port} pid={os.getpid()} python={sys.version.split()[0]} "
              f"provenance={json.dumps(_provenance(args.project), sort_keys=True)}\n")

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
