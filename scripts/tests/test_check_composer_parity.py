#!/usr/bin/env python3
"""Regression tests for the checked composer parity contract."""
from __future__ import annotations

import contextlib
import copy
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

CHECKER_PATH = Path(__file__).resolve().parents[1] / "check-composer-parity.py"
spec = importlib.util.spec_from_file_location("check_composer_parity", CHECKER_PATH)
assert spec and spec.loader
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ComposerParityCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.upstream = self.root / "desktop"
        source = self.upstream / "apps/desktop/src/app/chat/composer"
        source.mkdir(parents=True)
        (source / "known.ts").write_text("export const known = true;\n", encoding="utf-8")
        (source / "known.test.ts").write_text("export const tested = true;\n", encoding="utf-8")
        self._git("init", "-q")
        self._git("config", "user.email", "test@example.invalid")
        self._git("config", "user.name", "Composer parity test")
        self._git("add", ".")
        self._git("commit", "-m", "fixture")
        self.sha = self._git("rev-parse", "HEAD").strip()
        self.manifest_path = self.root / "manifest.json"
        self.matrix_path = self.root / "matrix.json"
        self.ledger_path = self.root / "ledger.json"
        self._write_contract()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _git(self, *args: str) -> str:
        return subprocess.check_output(["git", "-C", str(self.upstream), *args], text=True)

    def _manifest(self) -> dict:
        return {
            "schema_version": 1,
            "desktop_sha": self.sha,
            "desktop_scope": {
                "roots": ["apps/desktop/src/app/chat/composer"],
                "integration_paths": [],
            },
            "desktop_inventory": [
                {"path": "apps/desktop/src/app/chat/composer/known.ts", "kind": "source", "capability_ids": ["editor"]},
                {"path": "apps/desktop/src/app/chat/composer/known.test.ts", "kind": "test", "capability_ids": ["editor"]},
            ],
            "capabilities": [
                {
                    "id": "editor",
                    "category": "editor/draft/references",
                    "family": "editor/draft/references",
                    "visible_contract": "fixture editor",
                    "state_authority": "ui-only",
                    "platform_dependency": "fixture platform",
                    "android_status": "parity",
                    "android_behavior": "fixture behavior",
                    "android_tests": "fixture test",
                    "desktop_citations": {
                        "source": ["apps/desktop/src/app/chat/composer/known.ts:1"],
                        "tests": ["apps/desktop/src/app/chat/composer/known.test.ts:1"],
                    },
                    "capture_states": ["editor-empty"],
                }
            ],
        }

    def _matrix(self) -> dict:
        return {
            "schema_version": 1,
            "desktop_sha": self.sha,
            "variants": {
                "themes": ["light", "dark"],
                "container_widths": [561, 560, 321, 320, 319],
                "orientations": ["portrait", "landscape"],
                "ime": ["closed", "open"],
                "font_scale": ["default", "large"],
                "motion": ["normal", "reduced"],
            },
            "harness_contract": {
                "desktop": "Use a disposable pinned export and e2e mock fixture; never bare npm run perf:serve.",
                "android_identity": {
                    "package": "com.hermesagent.mobile.debug",
                    "activity": "com.hermesagent.mobile.MainActivity",
                    "verification": "Record resolve-activity and focused-window evidence.",
                },
            },
            "states": [{
                "id": "editor-empty",
                "fixture": "composer-empty-fixture",
                "test_only": True,
                "contains_private_data": False,
                "capture_targets": ["desktop", "android"],
            }],
        }

    def _write_contract(self, manifest: dict | None = None, matrix: dict | None = None) -> None:
        manifest = manifest or self._manifest()
        self.manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        self.matrix_path.write_text(json.dumps(matrix or self._matrix()), encoding="utf-8")
        paths = self._git("ls-tree", "-r", "--name-only", manifest["desktop_sha"], "--", "apps/desktop/src/app/chat/composer").splitlines()
        line_counts = {
            path: len(self._git("show", f"{manifest['desktop_sha']}:{path}").splitlines())
            for path in paths
        }
        blob_oids = {
            path: self._git("rev-parse", f"{manifest['desktop_sha']}:{path}").strip()
            for path in paths
        }
        self.ledger_path.write_text(json.dumps({
            "schema_version": 1,
            "desktop_sha": manifest["desktop_sha"],
            "path_line_counts": line_counts,
            "path_blob_oids": blob_oids,
        }), encoding="utf-8")

    def _run(self, *extra: str, with_upstream: bool = True) -> int:
        args = [
            "--manifest", str(self.manifest_path),
            "--matrix", str(self.matrix_path),
            "--ledger", str(self.ledger_path),
        ]
        if with_upstream:
            args += ["--upstream", str(self.upstream)]
        args += extra
        with contextlib.redirect_stdout(io.StringIO()):
            return checker.main(args)

    def test_accepts_complete_classified_fixture(self) -> None:
        self.assertEqual(0, self._run())

    def test_accepts_complete_classified_fixture_offline(self) -> None:
        self.assertEqual(0, self._run(with_upstream=False))

    def test_rejects_unclassified_desktop_source(self) -> None:
        extra = self.upstream / "apps/desktop/src/app/chat/composer/new.ts"
        extra.write_text("export const newSource = true;\n", encoding="utf-8")
        self._git("add", ".")
        self._git("commit", "-m", "new desktop source")
        manifest = self._manifest()
        manifest["desktop_sha"] = self._git("rev-parse", "HEAD").strip()
        self._write_contract(manifest)
        self.assertEqual(1, self._run())

    def test_rejects_unresolved_citation(self) -> None:
        manifest = self._manifest()
        manifest["capabilities"][0]["desktop_citations"]["source"] = [
            "apps/desktop/src/app/chat/composer/missing.ts:1"
        ]
        self._write_contract(manifest)
        self.assertEqual(1, self._run())

    def test_rejects_capture_matrix_pin_drift(self) -> None:
        matrix = self._matrix()
        matrix["desktop_sha"] = "0" * 40
        self._write_contract(matrix=matrix)
        self.assertEqual(1, self._run())

    def test_rejects_missing_capture_variant(self) -> None:
        matrix = self._matrix()
        matrix["variants"]["container_widths"].remove(320)
        self._write_contract(matrix=matrix)
        self.assertEqual(1, self._run())

    def test_rejects_wrong_android_capture_identity(self) -> None:
        matrix = self._matrix()
        matrix["harness_contract"]["android_identity"]["package"] = "com.example.wrong"
        self._write_contract(matrix=matrix)
        self.assertEqual(1, self._run())

    def test_rejects_private_fixture_marker(self) -> None:
        matrix = self._matrix()
        matrix["states"][0]["fixture"] = "composer-credential-fixture"
        self._write_contract(matrix=matrix)
        self.assertEqual(1, self._run())

    def test_rejects_unmatched_capture_targets(self) -> None:
        matrix = self._matrix()
        matrix["states"][0]["capture_targets"] = ["android"]
        self._write_contract(matrix=matrix)
        self.assertEqual(1, self._run())

    def test_rejects_empty_desktop_tests_without_gap(self) -> None:
        manifest = self._manifest()
        manifest["capabilities"][0]["desktop_citations"]["tests"] = []
        self._write_contract(manifest)
        self.assertEqual(1, self._run())

    def test_rejects_citation_past_end_of_file(self) -> None:
        manifest = self._manifest()
        manifest["capabilities"][0]["desktop_citations"]["source"] = [
            "apps/desktop/src/app/chat/composer/known.ts:2"
        ]
        self._write_contract(manifest)
        self.assertEqual(1, self._run())

    def test_rejects_citation_classified_to_another_capability(self) -> None:
        manifest = self._manifest()
        other = copy.deepcopy(manifest["capabilities"][0])
        other["id"] = "other"
        manifest["capabilities"].append(other)
        manifest["desktop_inventory"][0]["capability_ids"] = ["other"]
        manifest["desktop_inventory"][1]["capability_ids"] = ["editor", "other"]
        self._write_contract(manifest)
        self.assertEqual(1, self._run())

    def test_rejects_blob_oid_drift(self) -> None:
        self._write_contract()
        ledger = json.loads(self.ledger_path.read_text(encoding="utf-8"))
        first_path = next(iter(ledger["path_blob_oids"]))
        ledger["path_blob_oids"][first_path] = "0" * 40
        self.ledger_path.write_text(json.dumps(ledger), encoding="utf-8")
        self.assertEqual(1, self._run())

    def test_malformed_inventory_reports_failure_without_crashing(self) -> None:
        manifest = self._manifest()
        manifest["desktop_inventory"].append("bad")
        self._write_contract(manifest)
        self.assertEqual(1, self._run())

    def test_rejects_invalid_mobile_adapted_or_not_applicable_reason(self) -> None:
        for status in ("mobile-adapted", "not-applicable"):
            with self.subTest(status=status):
                manifest = self._manifest()
                capability = manifest["capabilities"][0]
                capability["android_status"] = status
                capability["deviation"] = {
                    "reason": "not implemented yet",
                    "evidence": "fixture evidence",
                    "approved_by": "fixture approval",
                }
                self._write_contract(manifest)
                self.assertEqual(1, self._run())

    def test_completion_mode_rejects_missing_or_partial_rows(self) -> None:
        for status in ("missing", "partial"):
            with self.subTest(status=status):
                manifest = self._manifest()
                manifest["capabilities"][0]["android_status"] = status
                self._write_contract(manifest)
                self.assertEqual(1, self._run("--require-complete"))


if __name__ == "__main__":
    unittest.main()
