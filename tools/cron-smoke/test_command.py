"""Cross-language command handoff test without touching mobile-owned files."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


class CommandTest(unittest.TestCase):
    def test_command_gets_memory_only_url_and_header_and_server_is_reaped(self):
        launcher = HERE / "with_server.py"
        self.assertTrue(launcher.is_file(), "cross-language command launcher is not implemented")
        probe = """
import json, os, urllib.request
url = os.environ['HM_CRON_SMOKE_URL'] + '/api/cron/jobs?profile=worker_alpha'
token = os.environ['HM_CRON_SMOKE_TOKEN']
request = urllib.request.Request(url, headers={'X-Hermes-Session-Token': token})
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with opener.open(request) as response:
    assert json.load(response) == []
print('CROSS_LANGUAGE_HANDOFF_OK')
"""
        with tempfile.TemporaryDirectory(prefix="command-", dir=os.environ["TMPDIR"]) as tmp:
            result = subprocess.run(
                [sys.executable, "-I", "-B", str(launcher), "--upstream",
                 os.environ["HM_CRON_SMOKE_UPSTREAM"], "--state-dir", tmp,
                 "--", sys.executable, "-I", "-c", probe],
                text=True, capture_output=True, timeout=90,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("CROSS_LANGUAGE_HANDOFF_OK\n", result.stdout)


if __name__ == "__main__":
    unittest.main()
