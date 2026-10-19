#!/usr/bin/env python3
"""Isolated real cron-router host, NOT full Gateway/native-auth acceptance.

Only the outer loopback credential gate and lifecycle are harness code. Every
/api/cron route is the untouched pinned upstream APIRouter and real stores.
"""
from __future__ import annotations

import argparse
import contextlib
import hmac
import logging
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading

PIN = "5fc490e2b34123212d73ef316086f939567a725a"
TOKEN_ENV = "HM_CRON_SMOKE_TOKEN"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--state-dir", type=Path, required=True)
    args = parser.parse_args()
    upstream = args.upstream.resolve(strict=True)
    state = args.state_dir.resolve(strict=True)
    token = os.environ.get(TOKEN_ENV, "")
    if len(token) < 32 or not token.isascii():
        parser.error("HM_CRON_SMOKE_TOKEN must contain at least 32 ASCII characters")
    # Re-exec BEFORE importing any upstream code. Do not inherit credentials,
    # PYTHONPATH, proxy settings, the caller's real HOME or profile selection.
    if os.environ.get("HM_CRON_SMOKE_ISOLATED") != str(state):
        marker = state / ".hm-cron-smoke-fixture"
        if marker.is_file() and marker.read_text(encoding="utf-8") == PIN + "\n":
            pass  # A prior smoke fixture may be reopened to verify persistence.
        elif any(state.iterdir()):
            parser.error("state directory is not an isolated smoke fixture")
        else:
            marker.write_text(PIN + "\n", encoding="utf-8")
        home, tmp = state / "home", state / "tmp"
        home.mkdir(exist_ok=True)
        tmp.mkdir(exist_ok=True)
        env = {
            "HOME": str(home), "HERMES_HOME": str(home / ".hermes"),
            "TMPDIR": str(tmp), "TMP": str(tmp), "TEMP": str(tmp),
            "PATH": "/usr/bin:/bin", "LANG": "en_US.UTF-8", "TZ": "UTC",
            "HERMES_TIMEZONE": "UTC", "PYTHONDONTWRITEBYTECODE": "1",
            TOKEN_ENV: token, "HM_CRON_SMOKE_ISOLATED": str(state),
        }
        os.chdir(state)
        os.execve(sys.executable, [sys.executable, "-I", "-B", str(Path(__file__).resolve()),
                                  "--upstream", str(upstream), "--state-dir", str(state)], env)
    if Path(os.environ["HOME"]).resolve() != state / "home":
        parser.error("isolation marker does not match HOME")
    if Path(os.environ["HERMES_HOME"]).resolve() != state / "home" / ".hermes":
        parser.error("isolation marker does not match HERMES_HOME")
    revision = subprocess.check_output(
        ["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
    if revision != PIN:
        parser.error("upstream revision does not match the smoke pin")
    if subprocess.run(["git", "-C", str(upstream), "diff", "--quiet", "HEAD"]).returncode:
        parser.error("tracked upstream source has modifications")
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(upstream))
    logging.disable(logging.CRITICAL)
    readiness = sys.stdout
    # Upstream print statements cannot contaminate the port-only protocol.
    with contextlib.redirect_stdout(sys.stderr):
        serve(state, token, readiness)


def serve(state: Path, token: str, readiness) -> None:
    import asyncio
    from fastapi import FastAPI, Request
    from fastapi.responses import JSONResponse
    import uvicorn
    from hermes_cli.web_routers.cron import router
    from hermes_cli.web_server_cron import _cron_profile_dicts
    from hermes_state import SessionDB

    root = Path(os.environ["HERMES_HOME"])
    for home in (root, root / "profiles" / "worker_alpha"):
        home.mkdir(parents=True, exist_ok=True)
        # Clean fixture configuration, not a user's live config. No providers.
        config = home / "config.yaml"
        if not config.exists():
            config.write_text("timezone: UTC\ncron:\n  scheduler: builtin\n", encoding="utf-8")
        scripts = home / "scripts"
        scripts.mkdir(exist_ok=True)
        fixture = scripts / "harmless.sh"
        fixture.write_text("#!/bin/sh\nprintf '%s\\n' 'HM_CRON_SCRIPT_OK'\n", encoding="utf-8")
        fixture.chmod(0o700)
        db = SessionDB(db_path=home / "state.db")
        db.close()
    discovered = {item["name"] for item in _cron_profile_dicts()}
    if not {"default", "worker_alpha"}.issubset(discovered):
        raise RuntimeError("actual upstream profile discovery missed fixture homes")

    if "run_agent" in sys.modules:
        raise RuntimeError("agent module unexpectedly imported during setup")

    # Defense-in-depth: prevent accidental agent construction/outbound provider
    # calls. These guards do not supply fake route/scheduler responses.
    def audit(event, args):
        if event == "import" and args[0] == "run_agent":
            raise RuntimeError("smoke harness forbids agent execution")
        if event == "socket.connect":
            address = args[1]
            if isinstance(address, tuple) and address[0] not in ("127.0.0.1", "::1"):
                raise RuntimeError("smoke harness forbids outbound network")
    sys.addaudithook(audit)
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def credential_gate(request: Request, call_next):
        supplied = request.headers.get("X-Hermes-Session-Token", "")
        if not hmac.compare_digest(supplied.encode(), token.encode()):
            return JSONResponse({"detail": "Unauthorized"}, status_code=401)
        return await call_next(request)

    app.include_router(router)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.listen(128)
    config = uvicorn.Config(app, access_log=False, log_config=None, log_level="critical",
                            lifespan="off", ws="none")

    class ReadyServer(uvicorn.Server):
        async def startup(self, sockets=None):
            await super().startup(sockets=sockets)
            if self.started:
                readiness.write(f"HM_CRON_SMOKE_PORT={port}\n")
                readiness.flush()

    server = ReadyServer(config)

    def read_quit():
        # EOF also stops the process: a crashed caller must not leave a server.
        for line in sys.stdin:
            if line.strip() == "quit":
                break
        server.should_exit = True

    threading.Thread(target=read_quit, name="smoke-stdin", daemon=True).start()
    try:
        asyncio.run(server.serve(sockets=[sock]))
    finally:
        sock.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        # Exception details can include request material or local paths.
        print(f"HM_CRON_SMOKE_FAILED:{type(exc).__name__}", file=sys.stderr)
        raise SystemExit(1)
