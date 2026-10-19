"""Lifecycle tracer bullet; uses the actual upstream runtime, not route fakes."""
import json
import os
from pathlib import Path
import secrets
import select
import subprocess
import sys
import tempfile
import unittest
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent


class RealServerLifecycleTest(unittest.TestCase):
    def test_existing_non_fixture_state_is_refused_without_modification(self):
        with tempfile.TemporaryDirectory(prefix="refuse-", dir=os.environ["TMPDIR"]) as tmp:
            sentinel = Path(tmp) / "unrelated.txt"
            sentinel.write_text("untouched")
            result = subprocess.run(
                [sys.executable, "-I", "-B", str(HERE / "server.py"), "--upstream",
                 os.environ["HM_CRON_SMOKE_UPSTREAM"], "--state-dir", tmp],
                env={"PATH": "/usr/bin:/bin", "HM_CRON_SMOKE_TOKEN": secrets.token_urlsafe(32)},
                text=True, capture_output=True, timeout=30,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("state directory is not an isolated smoke fixture", result.stderr)
            self.assertEqual(["unrelated.txt"], sorted(p.name for p in Path(tmp).iterdir()))
            self.assertEqual("untouched", sentinel.read_text())

    def test_actual_router_requires_header_and_discovers_empty_named_store(self):
        server = HERE / "server.py"
        self.assertTrue(server.is_file(), "real-upstream server driver is not implemented")
        upstream = os.environ["HM_CRON_SMOKE_UPSTREAM"]
        with tempfile.TemporaryDirectory(prefix="lifecycle-", dir=os.environ["TMPDIR"]) as tmp:
            token = secrets.token_urlsafe(32)
            env = {"HOME": tmp, "PATH": "/usr/bin:/bin", "TMPDIR": tmp,
                   "HM_CRON_SMOKE_TOKEN": token}
            process = subprocess.Popen(
                [sys.executable, "-I", str(server), "--upstream", upstream, "--state-dir", tmp],
                env=env, cwd=tmp, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True,
            )
            assert process.stdout is not None and process.stdin is not None
            try:
                ready, _, _ = select.select([process.stdout], [], [], 30)
                self.assertTrue(ready, "server failed to announce readiness")
                marker = process.stdout.readline().strip()
                self.assertRegex(marker, r"^HM_CRON_SMOKE_PORT=\d+$")
                url = "http://127.0.0.1:" + marker.split("=")[1] + "/api/cron/jobs?profile=worker_alpha"
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                with self.assertRaises(urllib.error.HTTPError) as refused:
                    opener.open(url)
                self.assertEqual(401, refused.exception.code)
                refused.exception.close()
                request = urllib.request.Request(url, headers={"X-Hermes-Session-Token": token})
                with opener.open(request) as response:
                    self.assertEqual([], json.load(response))
            finally:
                if process.poll() is None:
                    process.stdin.write("quit\n")
                    process.stdin.flush()
                stdout, stderr = process.communicate(timeout=20)
                self.assertEqual(0, process.returncode, stderr)
                self.assertEqual("", stdout)
                self.assertNotIn(token, stderr)


if __name__ == "__main__":
    unittest.main()
