"""Acceptance runner contract: every check must be real REST/storage evidence."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


class AcceptanceTest(unittest.TestCase):
    def test_runner_exercises_persisted_named_profile_and_script_trigger(self):
        runner = HERE / "acceptance.py"
        self.assertTrue(runner.is_file(), "real REST acceptance runner is not implemented")
        with tempfile.TemporaryDirectory(prefix="acceptance-", dir=os.environ["TMPDIR"]) as tmp:
            result = subprocess.run(
                [sys.executable, "-I", "-B", str(runner), "--upstream",
                 os.environ["HM_CRON_SMOKE_UPSTREAM"], "--state-dir", tmp],
                text=True, capture_output=True, timeout=90,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            receipt = json.loads(result.stdout)
            self.assertEqual("passed", receipt["status"])
            self.assertEqual("real-upstream-cron-router", receipt["boundary"])
            expected = {
                "auth_gate", "named_profile", "all_profiles", "create_readback", "update_readback",
                "pause_readback", "resume_readback", "delivery_discovery", "blueprint_discovery",
                "blueprint_instantiate", "empty_history", "script_trigger", "script_output",
                "execution_ledger", "restart_persistence", "delete_readback", "token_not_persisted",
            }
            self.assertEqual(expected, set(receipt["checks"]))
            self.assertEqual(len(expected), receipt["check_count"])
            self.assertEqual(0, receipt["agent_imports"])


if __name__ == "__main__":
    unittest.main()
