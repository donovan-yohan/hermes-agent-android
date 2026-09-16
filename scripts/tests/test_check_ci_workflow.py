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
        self.original_build_file = checker.BUILD_FILE
        self.valid_text = self.original_workflow.read_text(encoding="utf-8")
        self.valid_build_text = self.original_build_file.read_text(encoding="utf-8")
        self.temp = tempfile.TemporaryDirectory()
        setattr(checker, "WORKFLOW", Path(self.temp.name) / "workflow.yml")
        setattr(checker, "BUILD_FILE", Path(self.temp.name) / "build.gradle.kts")

    def tearDown(self) -> None:
        setattr(checker, "WORKFLOW", self.original_workflow)
        setattr(checker, "BUILD_FILE", self.original_build_file)
        self.temp.cleanup()

    def _run(self, text: str, build_text: str | None = None) -> int:
        checker.WORKFLOW.write_text(text, encoding="utf-8")
        checker.BUILD_FILE.write_text(
            build_text if build_text is not None else self.valid_build_text,
            encoding="utf-8",
        )
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

    def test_rejects_unexported_rolling_keystore_path(self) -> None:
        broken = self.valid_text.replace(
            "          echo \"HERMES_ROLLING_DEBUG_KEYSTORE_PATH=$keystore\" >> \"$GITHUB_ENV\"\n",
            "",
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_restoring_into_agp_default_keystore_path(self) -> None:
        broken = self.valid_text.replace(
            '          keystore="$RUNNER_TEMP/rolling-debug.keystore"\n',
            '          keystore="$HOME/.android/debug.keystore"\n',
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_verification_against_an_implicit_keystore(self) -> None:
        broken = self.valid_text.replace(
            '            -keystore "$HERMES_ROLLING_DEBUG_KEYSTORE_PATH" \\\n',
            '            -keystore "$HOME/.android/debug.keystore" \\\n',
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_cleanup_without_pre_export_fallback(self) -> None:
        broken = self.valid_text.replace(
            '${HERMES_ROLLING_DEBUG_KEYSTORE_PATH:-$RUNNER_TEMP/rolling-debug.keystore}',
            '${HERMES_ROLLING_DEBUG_KEYSTORE_PATH:-}',
            1,
        )
        self.assertNotEqual(self.valid_text, broken)
        self.assertEqual(1, self._run(broken))

    def test_rejects_renamed_rolling_keystore_env(self) -> None:
        broken_build = self.valid_build_text.replace(
            'providers.environmentVariable("HERMES_ROLLING_DEBUG_KEYSTORE_PATH").orNull',
            'providers.environmentVariable("IGNORED_KEYSTORE_PATH").orNull',
            1,
        )
        self.assertNotEqual(self.valid_build_text, broken_build)
        self.assertEqual(1, self._run(self.valid_text, broken_build))

    def test_rejects_block_commented_signing_config(self) -> None:
        broken_build = self.valid_build_text.replace(
            'getByName("debug")', '/* getByName("debug") */', 1
        )
        self.assertNotEqual(self.valid_build_text, broken_build)
        self.assertEqual(1, self._run(self.valid_text, broken_build))

    # The pin-citation job. Every rule above has a test that breaks it; these do
    # the same for the rules that keep `verify-pin-citations.py` wired into CI,
    # because a gate nobody runs is the failure this job was created to end.
    # Each asserts the *specific* failure, so a rule going dead is caught rather
    # than being masked by an unrelated one that happens to trip too.

    def _run_captured(self, text: str) -> tuple[int, str]:
        checker.WORKFLOW.write_text(text, encoding="utf-8")
        checker.BUILD_FILE.write_text(self.valid_build_text, encoding="utf-8")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = checker.main()
        return code, output.getvalue()

    def _without_citations_job(self) -> str:
        start = self.valid_text.index("  citations:\n")
        end = self.valid_text.index("  instrumented:\n")
        return self.valid_text[:start] + self.valid_text[end:]

    def _assert_reports(self, broken: str, expected: str) -> None:
        self.assertNotEqual(self.valid_text, broken)
        code, output = self._run_captured(broken)
        self.assertEqual(1, code, output)
        self.assertIn(expected, output)

    def test_rejects_removed_pin_citation_job(self) -> None:
        self._assert_reports(
            self._without_citations_job(),
            "the pin-citation job is missing",
        )

    def test_rejects_unbounded_pin_citation_job(self) -> None:
        self._assert_reports(
            self.valid_text.replace("    timeout-minutes: 10\n", "", 1),
            "the pin-citation job must be time-bounded",
        )

    def test_rejects_pin_citation_job_gated_on_another_job(self) -> None:
        broken = self.valid_text.replace(
            "  citations:\n", "  citations:\n    needs: check\n", 1
        )
        self._assert_reports(
            broken,
            "the pin-citation job must not depend on another job",
        )

    def test_rejects_pin_citation_job_on_a_fixed_branch(self) -> None:
        # A fixed range either blames a branch for pins `main` moved under it or
        # checks nothing at all; both get the gate switched off.
        broken = self.valid_text.replace(
            "${{ github.event.pull_request.base.sha || github.event.before }}",
            "origin/main",
            1,
        )
        self._assert_reports(
            broken,
            "the pin-citation job must check the pull request's own range",
        )

    def test_rejects_pin_citation_job_without_range_history(self) -> None:
        broken = self.valid_text.replace("          fetch-depth: 0\n", "", 1)
        self._assert_reports(
            broken,
            "must fetch the range's history",
        )

    def test_rejects_pin_citation_job_without_fetch(self) -> None:
        broken = self.valid_text.replace(
            '            --upstream "$upstream" --fetch\n',
            '            --upstream "$upstream"\n',
            1,
        )
        self._assert_reports(broken, "must pass --fetch")

    def test_rejects_pin_citation_job_without_self_test(self) -> None:
        # Without the fixture the gate can pass by checking nothing whenever the
        # range happens to move no pin, which is most ranges.
        broken = self.valid_text.replace(
            "          python3 scripts/verify-pin-citations.py --self-test\n",
            "",
            1,
        )
        self._assert_reports(broken, "must run --self-test")

    def test_rejects_pin_citation_job_without_its_own_upstream(self) -> None:
        # The job's whole reason for being separate: it obtains the upstream
        # checkout itself. Left to a workstation's checkout it proves nothing in
        # CI, which is the only place it runs unattended.
        broken = self.valid_text.replace(
            '          git clone --quiet --filter=blob:none --no-checkout "$UPSTREAM_URL" "$upstream"\n',
            "",
            1,
        )
        self._assert_reports(
            broken,
            "the pin-citation job must obtain its own upstream checkout",
        )


if __name__ == "__main__":
    unittest.main()
