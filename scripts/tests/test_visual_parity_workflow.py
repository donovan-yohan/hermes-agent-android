#!/usr/bin/env python3
"""Structural guard for the manual visual-parity evidence workflow."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/visual-parity-capture.yml"


class VisualParityWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = WORKFLOW.read_text(encoding="utf-8")

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
            "check-receipt --platform android", "--git-sha", "--apk-kind debug",
        ):
            self.assertIn(required, self.text)


if __name__ == "__main__":
    unittest.main()
