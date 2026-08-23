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

    def _remove_step(
        self, text: str, start_marker: str, end_marker: str
    ) -> tuple[str, str]:
        start = text.index(start_marker)
        end = text.index(end_marker)
        return text[start:end], text[:start] + text[end:]

    def _insert_before(self, text: str, block: str, marker: str) -> str:
        at = text.index(marker)
        return text[:at] + block + text[at:]

    def test_accepts_repository_workflow(self) -> None:
        self.assertEqual(0, self._run(self.valid_text))

    def test_rejects_commented_out_gradle_gate(self) -> None:
        broken = self.valid_text.replace(
            "        run: ./gradlew check assembleDebug --no-daemon --no-build-cache",
            "        # run: ./gradlew check assembleDebug --no-daemon --no-build-cache",
        )
        self.assertEqual(1, self._run(broken))

    def test_rejects_removed_prune_job(self) -> None:
        head, marker, _ = self.valid_text.partition("  prune:\n")
        self.assertTrue(marker, "workflow no longer declares a prune job")
        self.assertEqual(1, self._run(head))

    def test_rejects_removed_workflow_permissions(self) -> None:
        broken = self.valid_text.replace("permissions:\n  contents: read\n", "", 1)
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_removed_rolling_upload(self) -> None:
        broken = self.valid_text.replace(
            "github.event_name == 'push' && env.ROLLING_ARTIFACT",
            "github.event_name == 'push' && 'wrong-artifact'",
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_prune_without_pipefail_shell(self) -> None:
        broken = self.valid_text.replace(
            "      - name: Remove superseded latest APK artifacts\n        shell: bash\n",
            "      - name: Remove superseded latest APK artifacts\n",
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_always_upload_after_failed_gate(self) -> None:
        broken = self.valid_text.replace(
            "        id: apk\n",
            "        if: always()\n        id: apk\n",
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_prune_without_current_artifact_exclusion(self) -> None:
        broken = self.valid_text.replace(
            " | select(.id != ${CURRENT_ARTIFACT_ID})", "", 1
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_workflow_level_actions_write(self) -> None:
        broken = self.valid_text.replace(
            "permissions:\n  contents: read",
            "permissions:\n  actions: write\n  contents: read",
            1,
        ).replace("      actions: write\n", "", 1)
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_prune_without_check_dependency(self) -> None:
        broken = self.valid_text.replace("    needs: check\n", "", 1)
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_removed_rolling_signing_step(self) -> None:
        broken = self.valid_text.replace(
            "      - name: Restore rolling debug keystore\n", "", 1
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_rolling_signing_on_pull_requests(self) -> None:
        broken = self.valid_text.replace(
            "      - name: Restore rolling debug keystore\n        if: github.event_name == 'push'\n",
            "      - name: Restore rolling debug keystore\n        if: always()\n",
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_prune_on_pull_requests(self) -> None:
        broken = self.valid_text.replace(
            "  prune:\n    name: prune superseded latest APKs\n    if: github.event_name == 'push'\n",
            "  prune:\n    name: prune superseded latest APKs\n    if: always()\n",
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_signing_restored_after_gradle(self) -> None:
        signing_step, without_signing = self._remove_step(
            self.valid_text,
            "      - name: Restore rolling debug keystore\n",
            "      - name: Check and assemble exact head\n",
        )
        broken = self._insert_before(
            without_signing, signing_step, "      - name: Upload debug APK\n"
        )
        self.assertEqual(1, self._run(broken))

    def test_rejects_removal_step_without_always(self) -> None:
        broken = self.valid_text.replace(
            "      - name: Remove rolling debug keystore\n        if: always()\n",
            "      - name: Remove rolling debug keystore\n"
            "        if: github.event_name == 'push'\n",
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_cached_signed_apk_outputs(self) -> None:
        broken = self.valid_text.replace(" --no-build-cache", "", 1)
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_removed_signing_verification(self) -> None:
        _, broken = self._remove_step(
            self.valid_text,
            "      - name: Verify rolling APK signing identity\n",
            "      - name: Upload debug APK\n",
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_signing_verification_on_pull_requests(self) -> None:
        broken = self.valid_text.replace(
            "      - name: Verify rolling APK signing identity\n"
            "        if: github.event_name == 'push'\n",
            "      - name: Verify rolling APK signing identity\n"
            "        if: always()\n",
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_signing_verification_after_upload(self) -> None:
        verification_step, without_verification = self._remove_step(
            self.valid_text,
            "      - name: Verify rolling APK signing identity\n",
            "      - name: Upload debug APK\n",
        )
        broken = self._insert_before(
            without_verification,
            verification_step,
            "      - name: Remove rolling debug keystore\n",
        )
        self.assertEqual(1, self._run(broken))


if __name__ == "__main__":
    unittest.main()
