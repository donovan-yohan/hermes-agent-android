#!/usr/bin/env python3
"""Structural guard for the manual visual-parity evidence workflow."""
from __future__ import annotations

import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/visual-parity-capture.yml"
CAPTURE_SCRIPT = ROOT / "scripts/capture-android-visual-parity.sh"


class VisualParityWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = WORKFLOW.read_text(encoding="utf-8")
        self.capture_script = CAPTURE_SCRIPT.read_text(encoding="utf-8")

    def test_is_manual_immutable_ref_and_artifact_only(self) -> None:
        for required in ("workflow_dispatch:", "Exact immutable 40-character", "contents: read", "Upload Android packet only", "^[0-9a-f]{40}$", "CHECKED_OUT_REF"):
            self.assertIn(required, self.text)
        for forbidden in ("contents: write", "pull-requests: write", "git commit", "gh issue"):
            self.assertNotIn(forbidden, self.text)

    def test_all_actions_are_immutable_sha_pinned(self) -> None:
        actions = re.findall(r"^[ \t]*(?:-[ \t]+)?uses:[ \t]+([^\s#]+)", self.text, flags=re.MULTILINE)
        self.assertEqual(7, len(actions))
        for action in actions:
            with self.subTest(action=action):
                self.assertRegex(action, r"^[\w.-]+/[\w.-]+@[0-9a-f]{40}$")

    def test_installed_apk_and_post_interaction_evidence_are_required(self) -> None:
        for required in (
            "adb install -r", "grep -qx 'Success'", "--expected-accessibility",
            "check-receipt", "--platform android", "--git-sha", "--apk-kind debug",
        ):
            self.assertIn(required, self.capture_script)

    def test_swiping_states_swipe_and_do_not_wait_for_an_offscreen_row(self) -> None:
        # The lane reads the catalogued interaction by kind, forwards the bounded
        # swipe to the capture, and refuses an interaction it cannot perform.
        for required in ("swipe:list-up", "--swipe-list-up", "unsupported catalogued interaction"):
            self.assertIn(required, self.capture_script)
        self.assertIn('"$swipe_list_up"', self.capture_script)
        # The shell's pre-wait is gated on the state not being a swiping one:
        # its subject is below the fold until the drag moves it into view.
        self.assertIn('-n "$expected_accessibility" && -z "$swipe_list_up"', self.capture_script)

    def test_emulator_runner_enters_bash_explicitly(self) -> None:
        match = re.search(r"^\s+script: (.+)$", self.text, flags=re.MULTILINE)
        if match is None:
            self.fail("emulator runner needs a single-line script command")
        command = match.group(1)
        for expression, value in (
            ("${{ inputs.surface }}", "composer-status-stack"),
            ("${{ inputs.state }}", "queue-parked-collapsed"),
            ("${{ inputs.theme }}", "dark"),
        ):
            command = command.replace(expression, value)
        self.assertNotIn("\\", command)
        self.assertEqual(0, subprocess.run(["/bin/sh", "-n", "-c", command], check=False).returncode)
        self.assertIn("bash scripts/capture-android-visual-parity.sh", command)
        self.assertIn("#!/usr/bin/env bash", self.capture_script)
        self.assertIn("set -euo pipefail", self.capture_script)


if __name__ == "__main__":
    unittest.main()
