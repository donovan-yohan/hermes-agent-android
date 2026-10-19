#!/usr/bin/env python3
"""Own a real-upstream server while a Kotlin/JVM or other command runs.

The test command receives HM_CRON_SMOKE_URL and HM_CRON_SMOKE_TOKEN only in its
process environment, never argv or a properties file. Do not print either.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from acceptance import Server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("a command following -- is required")
    args.state_dir.mkdir(parents=True, exist_ok=True)
    server = Server(args.upstream.resolve(strict=True), args.state_dir.resolve())
    try:
        server.start()
        env = dict(os.environ)
        env["HM_CRON_SMOKE_URL"] = server.base
        env["HM_CRON_SMOKE_TOKEN"] = server.token
        return subprocess.run(command, env=env, check=False).returncode
    finally:
        server.stop()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"HM_CRON_SMOKE_COMMAND_FAILED:{type(exc).__name__}", file=sys.stderr)
        raise SystemExit(1)
