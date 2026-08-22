#!/usr/bin/env python3
"""Regression tests for the exact-head workflow contract checker."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path

CHECKER_PATH = Path(__file__).resolve().parents[1] / "check-ci-workflow.py"
spec = importlib.util.spec_from_file_location("check_ci_workflow", CHECKER_PATH)
assert spec and spec.loader
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class CiWorkflowCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_workflow = checker.WORKFLOW
        self.valid_text = self.original_workflow.read_text(encoding="utf-8")
        self.temp = tempfile.TemporaryDirectory()
        setattr(checker, "WORKFLOW", Path(self.temp.name) / "workflow.yml")

    def tearDown(self) -> None:
        setattr(checker, "WORKFLOW", self.original_workflow)
        self.temp.cleanup()

    def _run(self, text: str) -> int:
        checker.WORKFLOW.write_text(text, encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            return checker.main()

    def test_accepts_repository_workflow(self) -> None:
        self.assertEqual(0, self._run(self.valid_text))

    def test_rejects_commented_out_gradle_gate(self) -> None:
        broken = self.valid_text.replace(
            "        run: ./gradlew check assembleDebug --no-daemon",
            "        # run: ./gradlew check assembleDebug --no-daemon",
        )
        self.assertEqual(1, self._run(broken))


if __name__ == "__main__":
    unittest.main()
