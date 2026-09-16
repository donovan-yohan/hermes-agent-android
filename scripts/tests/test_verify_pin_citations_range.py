#!/usr/bin/env python3
"""Regression tests for the pin-citation range gate.

The gate's own `--self-test` builds a real fixture repository and proves the
pass/fail shapes end to end. These are the unit-level properties around it: that
a range moving no pin stays quiet, that a citation under a pin the change did not
move is left alone, and that the fixture is actually reachable from the gate the
build runs.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import re
import sys
import tempfile
import unittest
from unittest import mock
CHECKER_PATH = pathlib.Path(__file__).resolve().parents[1] / "verify-pin-citations.py"
spec = importlib.util.spec_from_file_location("verify_pin_citations_range", CHECKER_PATH)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

FULL_SHA = re.compile(r"^[0-9a-f]{40}$")


class CitationPinTest(unittest.TestCase):
    """Which pin a citation line is answerable to."""

    def setUp(self) -> None:
        self.moved = "4" * 40
        self.other = "a" * 40

    def test_own_line_names_the_moved_pin(self) -> None:
        lines = [f"* (`path/to/x.ts:12-14` @ `{self.moved}`),"]
        self.assertEqual(self.moved, gate.citation_pin(lines, 1, [self.moved]))

    def test_wrapped_line_names_the_moved_pin(self) -> None:
        lines = ["* Pinned to upstream `path/to/x.ts:12-14` @", f"* `{self.moved}`."]
        self.assertEqual(self.moved, gate.citation_pin(lines, 1, [self.moved]))

    def test_abbreviated_pin_matches_its_full_sha(self) -> None:
        lines = [f"* and `path/to/x.ts:12-14 @ {self.moved[:10]}`"]
        self.assertEqual(self.moved, gate.citation_pin(lines, 1, [self.moved]))

    def test_another_pin_is_left_alone(self) -> None:
        lines = [f"* kept at an older pin (`path/to/x.ts:12-14` @ `{self.other}`)"]
        self.assertIsNone(gate.citation_pin(lines, 1, [self.moved]))

    def test_page_declared_pin_governs_a_citation_below_it(self) -> None:
        lines = [
            "## Pin",
            "",
            f"| fixture | `{self.moved}` |",
            "",
            "| Question | Path |",
            "|---|---|",
            "| entry point | `path/to/x.ts:12-14` |",
        ]
        self.assertEqual(self.moved, gate.citation_pin(lines, 7, [self.moved]))

    def test_a_table_row_does_not_lend_its_pin_to_the_next_row(self) -> None:
        # Row 2 sits directly under row 1's pin: row 2 is its own citation, and
        # reading row 1's SHA as its tail would check the wrong claim.
        lines = [
            f"| a | `path/to/x.ts:12-14` @ `{self.other}` |",
            "| b | `path/to/y.ts:3` |",
        ]
        self.assertIsNone(gate.citation_pin(lines, 2, [self.moved]))


class CitationExtractionTest(unittest.TestCase):
    """What counts as a citation at all."""

    def test_a_fingerprint_fixture_is_not_a_citation_of_line_zero(self) -> None:
        text = 'const val FINGERPRINT = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01"'
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(gate, "resolve_path", return_value=None))
            found = list(gate.citations(text, "old"))
        self.assertEqual([], found)

    def test_a_span_out_of_the_old_files_bounds_is_not_checkable(self) -> None:
        # Judged at the old pin: a `:9999` on an 83-line file was never a citation.
        with contextlib.ExitStack() as stack:
            stack.enter_context(
                mock.patch.object(gate, "resolve_path", return_value="lib/tiny.ts")
            )
            stack.enter_context(mock.patch.object(gate, "blob", return_value=["a"] * 83))
            reason, resolved = gate.carried_verdict("old", "new", "lib/tiny.ts", [(9999, 9999)])
        self.assertIsNone(reason)
        self.assertIsNone(resolved)

    def test_a_path_gone_from_the_new_pin_is_reported(self) -> None:
        def fake_blob(sha: str, _path: str):
            return ["a"] * 83 if sha == "old" else None

        with contextlib.ExitStack() as stack:
            stack.enter_context(
                mock.patch.object(gate, "resolve_path", side_effect=lambda sha, _p: "lib/x.ts" if sha == "old" else None)
            )
            stack.enter_context(mock.patch.object(gate, "blob", side_effect=fake_blob))
            reason, _ = gate.carried_verdict("old", "new", "lib/x.ts", [(1, 2)])
        self.assertEqual("path-missing", reason)

    def test_an_in_place_edit_still_holds(self) -> None:
        def fake_blob(sha: str, _path: str):
            return ["top", "old middle", "bottom"] if sha == "old" else ["top", "new middle", "bottom"]

        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(gate, "resolve_path", return_value="lib/x.ts"))
            stack.enter_context(mock.patch.object(gate, "blob", side_effect=fake_blob))
            reason, _ = gate.carried_verdict("old", "new", "lib/x.ts", [(1, 3)])
        self.assertIsNone(reason)

    def test_a_moved_boundary_is_reported_as_a_drift(self) -> None:
        def fake_blob(sha: str, _path: str):
            return ["top", "middle", "bottom"] if sha == "old" else ["other", "middle", "bottom"]

        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(gate, "resolve_path", return_value="lib/x.ts"))
            stack.enter_context(mock.patch.object(gate, "blob", side_effect=fake_blob))
            reason, _ = gate.carried_verdict("old", "new", "lib/x.ts", [(1, 3)])
        self.assertEqual("construct-moved", reason)


class SelfTestTest(unittest.TestCase):
    """The fixture the acceptance criterion names must actually run."""

    def test_self_test_runs_the_fixture_without_error(self) -> None:
        # No stdout claim here: `self_test()` is the fixture itself and `main()`
        # prints. Asserting on a print would test the wrong function.
        gate.self_test()

    def test_main_self_test_flag_reports_its_claim(self) -> None:
        output = io.StringIO()
        with mock.patch.object(sys, "argv", [str(CHECKER_PATH), "--self-test"]):
            with contextlib.redirect_stdout(output):
                gate.main()
        self.assertIn("fails a drifted span", output.getvalue())


class ExitCodeContractTest(unittest.TestCase):
    """Exit 1 is a verdict; a tool failure must not borrow it.

    The invariant script turns exit 1 into "a pin move left a citation that is not
    true", so a range the tool cannot resolve — a bad revision, a malformed spec —
    reported as 1 sends someone hunting citations that are fine while the real
    fault stays hidden. It is its own code: 3, "could not be decided".
    """

    def _run_range(self, spec: str) -> int:
        output = io.StringIO()
        with mock.patch.object(
            sys, "argv", [str(CHECKER_PATH), "--check-range", spec, "--repo", "."]
        ):
            with contextlib.redirect_stdout(output):
                with self.assertRaises(SystemExit) as caught:
                    gate.main()
        self.assertNotEqual(1, caught.exception.code, output.getvalue())
        return caught.exception.code

    def test_an_unresolvable_head_is_not_a_verdict(self) -> None:
        self.assertEqual(3, self._run_range("HEAD..deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"))

    def test_a_malformed_range_is_not_a_verdict(self) -> None:
        self.assertEqual(3, self._run_range("HEAD"))

    def test_an_unresolvable_base_is_not_a_verdict(self) -> None:
        self.assertEqual(3, self._run_range("not-a-revision..HEAD"))

    def test_a_missing_upstream_checkout_is_a_named_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            gate._ensured.clear()
            with mock.patch.object(gate, "UPSTREAM", pathlib.Path(directory) / "absent"):
                with self.assertRaisesRegex(RuntimeError, "does not have"):
                    gate.ensure_sha("d" * 40, fetch=False)
            gate._ensured.clear()

    def test_a_missing_upstream_checkout_is_not_a_verdict(self) -> None:
        # The same failure reached through the CLI: when the range check cannot
        # run, the CLI must not exit 1. `check_range_spec` is stubbed to raise the
        # way an unreadable pin does, so the test does not depend on repo history.
        output = io.StringIO()
        with mock.patch.object(
            sys, "argv", [str(CHECKER_PATH), "--check-range", "HEAD..HEAD", "--repo", "."]
        ):
            with mock.patch.object(
                gate, "check_range_spec", side_effect=RuntimeError("upstream checkout does not have that pin")
            ):
                with contextlib.redirect_stdout(output):
                    with self.assertRaises(SystemExit) as caught:
                        gate.main()
        self.assertEqual(3, caught.exception.code, output.getvalue())
        self.assertIn("could not run", output.getvalue())


if __name__ == "__main__":
    unittest.main()
