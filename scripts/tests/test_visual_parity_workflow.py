#!/usr/bin/env python3
"""Structural guard for the manual visual-parity evidence workflow."""
from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/visual-parity-capture.yml"


class VisualParityWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = WORKFLOW.read_text(encoding="utf-8")

    def test_is_manual_explicit_ref_and_artifact_only(self) -> None:
        for required in ("workflow_dispatch:", "ref:", "surface:", "state:", "contents: read", "Upload Android packet only"):
            self.assertIn(required, self.text)
        self.assertNotIn("contents: write", self.text)
        self.assertNotIn("pull-requests: write", self.text)
        self.assertNotIn("git commit", self.text)
        self.assertNotIn("gh issue", self.text)

    def test_reuses_pinned_kvm_emulator_not_exact_head_job(self) -> None:
        for required in (
            "Enable KVM",
            "reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d",
            "profile: pixel_6",
            "--git-sha",
            "--apk-kind debug",
            "check-receipt --platform android",
        ):
            self.assertIn(required, self.text)


if __name__ == "__main__":
    unittest.main()
