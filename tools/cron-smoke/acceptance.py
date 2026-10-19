#!/usr/bin/env python3
"""Run isolated real-upstream REST acceptance; emit a path/token-free receipt."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import select
import sqlite3
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

PIN = "5fc490e2b34123212d73ef316086f939567a725a"
HERE = Path(__file__).resolve().parent
PROFILE = "worker_alpha"


class Server:
    """Reusable process owner for Python or a cross-language test launcher."""

    def __init__(self, upstream: Path, state: Path):
        self.upstream = upstream
        self.state = state
        self.token = secrets.token_urlsafe(32)
        self.process = None
        self.base = ""
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def start(self):
        env = {"HOME": str(self.state), "TMPDIR": str(self.state),
               "PATH": "/usr/bin:/bin", "HM_CRON_SMOKE_TOKEN": self.token}
        self.process = subprocess.Popen(
            [sys.executable, "-I", "-B", str(HERE / "server.py"), "--upstream",
             str(self.upstream), "--state-dir", str(self.state)],
            env=env, cwd=self.state, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True,
        )
        assert self.process.stdout is not None
        ready, _, _ = select.select([self.process.stdout], [], [], 45)
        if not ready:
            self.stop()
            raise AssertionError("real upstream server readiness timed out")
        marker = self.process.stdout.readline().strip()
        if not re.fullmatch(r"HM_CRON_SMOKE_PORT=\d+", marker):
            self.stop()
            raise AssertionError("real upstream server did not announce a port")
        self.base = "http://127.0.0.1:" + marker.split("=")[1]
        return self

    def stop(self):
        if self.process is None:
            return
        process, self.process = self.process, None
        if process.poll() is None:
            assert process.stdin is not None
            try:
                process.stdin.write("quit\n")
                process.stdin.flush()
            except BrokenPipeError:
                pass
        try:
            stdout, stderr = process.communicate(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            raise AssertionError("server did not stop on stdin quit") from None
        assert self.token not in stdout + stderr, "credential leaked into server output"
        assert not stdout, "server emitted unexpected protocol output"
        assert process.returncode == 0, "real upstream server failed (details suppressed)"

    def request(self, method: str, path: str, body=None, profile=PROFILE, auth=True, status=200):
        separator = "&" if "?" in path else "?"
        url = self.base + path + separator + urllib.parse.urlencode({"profile": profile})
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["X-Hermes-Session-Token"] = self.token
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = json.load(response)
            actual = response.status
        assert actual == status, f"{method} {path}: HTTP {actual}, expected {status}"
        return payload


def exercise(upstream: Path, state: Path) -> dict:
    checks = []
    root = state / "home" / ".hermes"
    owner = root / "profiles" / PROFILE
    server = Server(upstream, state)
    try:
        server.start()
        server.request("GET", "/api/cron/jobs", auth=False, status=401)
        checks.append("auth_gate")
        assert server.request("GET", "/api/cron/jobs") == []
        assert server.request("GET", "/api/cron/jobs", profile="default") == []
        checks.append("named_profile")

        job = server.request("POST", "/api/cron/jobs", {
            "name": "Cron smoke fixture", "prompt": "", "schedule": "0 9 * * *",
            "deliver": "local", "model": None, "provider": None,
            "script": "harmless.sh", "no_agent": True, "repeat": "forever", "paused": False,
        })
        job_id = job["id"]
        path = "/api/cron/jobs/" + urllib.parse.quote(job_id, safe="")
        saved = server.request("GET", path)
        assert saved["profile"] == PROFILE
        assert saved["prompt"] == "" and saved["script"] == "harmless.sh" and saved["no_agent"] is True
        assert saved["deliver"] == "local" and saved["enabled"] is True
        checks.append("create_readback")
        assert {item["id"] for item in server.request("GET", "/api/cron/jobs", profile="all")} == {job_id}
        assert server.request("GET", "/api/cron/jobs", profile="default") == []
        checks.append("all_profiles")

        server.request("PUT", path, {"updates": {
            "name": "Cron smoke edited", "schedule": "30 10 * * 1-5",
            "prompt": "", "deliver": "local", "model": "fixture-model", "provider": "fixture-provider",
        }})
        saved = server.request("GET", path)
        assert saved["name"] == "Cron smoke edited"
        assert saved["model"] == "fixture-model" and saved["provider"] == "fixture-provider"
        assert saved["schedule"]["expr"] == "30 10 * * 1-5"
        server.request("PUT", path, {"updates": {"model": None, "provider": None}})
        saved = server.request("GET", path)
        assert saved.get("model") is None and saved.get("provider") is None
        assert saved["no_agent"] is True and saved["script"] == "harmless.sh" and saved["prompt"] == ""
        checks.append("update_readback")
        server.request("POST", path + "/pause")
        paused = server.request("GET", path)
        assert paused["enabled"] is False and paused["state"] == "paused"
        checks.append("pause_readback")
        server.request("POST", path + "/resume")
        resumed = server.request("GET", path)
        assert resumed["enabled"] is True and resumed["state"] == "scheduled"
        checks.append("resume_readback")

        targets = server.request("GET", "/api/cron/delivery-targets")["targets"]
        assert any(item["id"] == "local" and item["home_target_set"] for item in targets)
        checks.append("delivery_discovery")
        blueprints = server.request("GET", "/api/cron/blueprints")["blueprints"]
        blueprint = next(item for item in blueprints if item["key"] == "custom-reminder")
        assert {item["name"] for item in blueprint["fields"]} == {"what", "time", "recurrence", "deliver"}
        checks.append("blueprint_discovery")
        instantiated = server.request("POST", "/api/cron/blueprints/instantiate", {
            "blueprint": "custom-reminder", "values": {
                "what": "harmless fixture reminder", "time": "14:25",
                "recurrence": "weekdays", "deliver": "local",
            },
        })
        blueprint_id = instantiated["id"]
        blueprint_path = "/api/cron/jobs/" + urllib.parse.quote(blueprint_id, safe="")
        from_blueprint = server.request("GET", blueprint_path)
        assert from_blueprint["prompt"] == "Remind the user: harmless fixture reminder"
        assert from_blueprint["schedule"]["expr"] == "25 14 * * 1-5"
        checks.append("blueprint_instantiate")
        assert server.request("GET", path + "/runs")["runs"] == []
        checks.append("empty_history")

        # The only trigger is a no_agent job backed by an actual printf script.
        fired = server.request("POST", path + "/trigger")
        saved = server.request("GET", path)
        assert fired["last_run_at"] == saved["last_run_at"] and saved["last_run_at"]
        assert saved["last_status"] == "ok", "script-only fire did not complete successfully"
        checks.append("script_trigger")
        history = server.request("GET", path + "/runs?limit=20")["runs"]
        assert len(history) == 1 and history[0]["source"] == "cron_output"
        assert "HM_CRON_SCRIPT_OK" in history[0]["preview"]
        output = list((owner / "cron" / "output" / job_id).glob("*.md"))
        assert len(output) == 1 and "HM_CRON_SCRIPT_OK" in output[0].read_text()
        checks.append("script_output")
        with sqlite3.connect(owner / "cron" / "executions.db") as db:
            attempts = db.execute("SELECT status FROM executions WHERE job_id = ?", (job_id,)).fetchall()
        assert attempts == [("completed",)]
        checks.append("execution_ledger")
        stored = json.loads((owner / "cron" / "jobs.json").read_text())["jobs"]
        assert {item["id"] for item in stored} == {job_id, blueprint_id}
        server.stop()
        server.start()
        persisted = server.request("GET", path)
        assert persisted["name"] == "Cron smoke edited" and persisted["last_run_at"] == saved["last_run_at"]
        assert server.request("GET", path + "/runs")["runs"][0]["source"] == "cron_output"
        assert server.request("GET", blueprint_path)["id"] == blueprint_id
        checks.append("restart_persistence")
        for target in (path, blueprint_path):
            assert server.request("DELETE", target) == {"ok": True}
        assert server.request("GET", "/api/cron/jobs") == []
        assert json.loads((owner / "cron" / "jobs.json").read_text())["jobs"] == []
        checks.append("delete_readback")
    finally:
        server.stop()
    token_bytes = server.token.encode()
    assert not any(token_bytes in p.read_bytes() for p in state.rglob("*") if p.is_file())
    checks.append("token_not_persisted")
    return {
        "status": "passed", "upstream": PIN, "boundary": "real-upstream-cron-router",
        "profile": PROFILE, "checks": checks, "check_count": len(checks),
        "agent_imports": 0, "registration_failure_injected": False,
        "limits": ["not full Gateway startup", "not production authentication", "no scheduler ticker",
                   "no external provider registration", "no LLM or platform delivery"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--state-dir", type=Path, required=True,
                        help="new empty scratch directory; retained for inspection")
    args = parser.parse_args()
    args.state_dir.mkdir(parents=True, exist_ok=True)
    state = args.state_dir.resolve()
    if any(state.iterdir()):
        parser.error("acceptance requires an empty state directory")
    receipt = exercise(args.upstream.resolve(strict=True), state)
    rendered = json.dumps(receipt, indent=2, sort_keys=True) + "\n"
    (state / "receipt.json").write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        # Assertions contain only fixture labels/HTTP statuses, never responses.
        detail = str(exc) if isinstance(exc, AssertionError) else type(exc).__name__
        print("HM_CRON_ACCEPTANCE_FAILED: " + detail, file=sys.stderr)
        raise SystemExit(1)
