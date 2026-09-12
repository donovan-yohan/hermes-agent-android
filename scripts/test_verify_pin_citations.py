#!/usr/bin/env python3
"""Focused deterministic tests for the citation repin verifier."""

from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

MODULE = pathlib.Path(__file__).with_name("verify-pin-citations.py")
spec = importlib.util.spec_from_file_location("verify_pin_citations", MODULE)
assert spec and spec.loader
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class VerifyPinCitationsTest(unittest.TestCase):
    def setUp(self) -> None:
        verifier._blobs.clear()
        verifier._tree_paths.clear()

    def test_typed_extensions_keep_full_path_and_ranges(self) -> None:
        text = "apps/desktop/src/x.tsx:12-14,20 and packages/ui/button.jsx:7"
        with patch.object(verifier, "resolve_path", side_effect=lambda _, path: path):
            found = list(verifier.citations(text, "old"))
        self.assertEqual(
            found,
            [
                ("apps/desktop/src/x.tsx", [(12, 14), (20, 20)], True),
                ("packages/ui/button.jsx", [(7, 7)], True),
            ],
        )

    def test_empty_ranges_are_unattributable(self) -> None:
        with patch.object(verifier, "resolve_path", return_value="apps/a.ts"), patch.object(
            verifier, "blob", return_value=["one"]
        ):
            classification, evidence = verifier.classify("apps/a.ts", "old", "new")
        self.assertEqual(classification, "unattributable")
        self.assertEqual(evidence[0]["result"], "unattributable")

    def test_unique_suffix_resolves(self) -> None:
        def fake_blob(_sha: str, path: str):
            return ["x"] if path == "apps/desktop/src/store/a.ts" else None

        with patch.object(verifier, "blob", side_effect=fake_blob), patch.object(
            verifier, "tree_paths", return_value=["apps/desktop/src/store/a.ts"]
        ):
            self.assertEqual(verifier.resolve_path("old", "store/a.ts"), "apps/desktop/src/store/a.ts")

    def test_ambiguous_suffix_is_unattributable(self) -> None:
        with patch.object(verifier, "blob", return_value=None), patch.object(
            verifier, "tree_paths", return_value=["a/store/a.ts", "b/store/a.ts"]
        ):
            self.assertIsNone(verifier.resolve_path("old", "store/a.ts"))

    def test_provenance_is_excluded(self) -> None:
        self.assertTrue(verifier.is_provenance("docs/parity/visual/foo/report.html"))
        self.assertFalse(verifier.is_provenance("docs/parity/foo.md"))

    def test_changed_bytes_reject_a_citation(self) -> None:
        def fake_blob(sha: str, _path: str):
            return ["same", "old"] if sha == "old" else ["same", "new"]

        with patch.object(verifier, "blob", side_effect=fake_blob), patch.object(
            verifier, "resolve_path", return_value="apps/a.ts"
        ):
            self.assertFalse(verifier.holds("old", "new", "apps/a.ts", [(1, 2)]))

    def test_unchanged_bytes_accept_a_citation(self) -> None:
        with patch.object(verifier, "blob", return_value=["same", "also same"]), patch.object(
            verifier, "resolve_path", return_value="apps/a.ts"
        ):
            self.assertTrue(verifier.holds("old", "new", "apps/a.ts", [(1, 2)]))

    def test_scan_failure_aborts_instead_of_reporting_zero_files(self) -> None:
        failed = subprocess.CompletedProcess(["grep"], 2, stdout="", stderr="permission denied")
        with patch.object(verifier.subprocess, "run", return_value=failed):
            with self.assertRaisesRegex(RuntimeError, "grep exit 2"):
                verifier.stamped_files("old")

    def test_scan_no_match_is_an_empty_success(self) -> None:
        no_match = subprocess.CompletedProcess(["grep"], 1, stdout="", stderr="")
        with patch.object(verifier.subprocess, "run", return_value=no_match):
            self.assertEqual(verifier.stamped_files("old"), [])

    def test_nul_delimited_scan_preserves_spaces_in_paths(self) -> None:
        found = subprocess.CompletedProcess(["grep"], 0, stdout="./one file.md\0./two.kt\0", stderr="")
        with patch.object(verifier.subprocess, "run", return_value=found):
            self.assertEqual(verifier.stamped_files("old"), ["./one file.md", "./two.kt"])

    def test_main_scan_failure_removes_a_stale_plan(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            plan = pathlib.Path(directory) / "plan.json"
            plan.write_text("stale\n")
            argv = [str(MODULE), "old", "new", "--json", str(plan)]
            with patch.object(sys, "argv", argv), patch.object(
                verifier, "stamped_files", side_effect=RuntimeError("scan failed")
            ):
                with self.assertRaisesRegex(RuntimeError, "scan failed"):
                    verifier.main()
            self.assertFalse(plan.exists())

    def test_existing_blob_git_failure_aborts(self) -> None:
        failure = subprocess.CalledProcessError(128, ["git", "show"])
        with patch.object(verifier, "tree_paths", return_value=["apps/a.ts"]), patch.object(
            verifier.subprocess, "run", side_effect=failure
        ):
            with self.assertRaises(subprocess.CalledProcessError):
                verifier.blob("old", "apps/a.ts")


if __name__ == "__main__":
    unittest.main()
