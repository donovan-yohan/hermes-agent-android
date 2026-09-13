#!/usr/bin/env python3
"""Regression tests for visual-parity capture provenance and catalog guards."""
from __future__ import annotations

import importlib.util
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "visual_parity_contract.py"
spec = importlib.util.spec_from_file_location("visual_parity_contract", SCRIPT)
assert spec and spec.loader
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


class VisualParityContractTest(unittest.TestCase):
    def android_receipt(self) -> dict:
        return {
            "schema_version": 1, "surface": "composer-url-chip", "state": "url-chip",
            "fixture_id": "composer-url-chip-synthetic-v1", "theme": "dark",
            "viewport": {"size": "1080x2400"}, "android_git_sha": "a" * 40,
            "apk_sha256": "b" * 64, "apk_kind": "debug",
        }

    def desktop_receipt(self) -> dict:
        return {
            "schema_version": 1, "surface": "composer-status-stack", "state": "goal-active",
            "fixture_id": "composer-status-stack-synthetic-v1", "theme": "light",
            "viewport": {"width": 1220, "height": 800}, "desktop_upstream_sha": "a" * 40,
            "fixture_origin": "pinned-desktop-e2e-mock",
        }

    def test_catalog_allows_declared_issue_states_only(self) -> None:
        catalog = contract.load_catalog(Path(__file__).resolve().parents[2] / "docs/parity/visual-capture-surfaces.json")
        self.assertEqual("72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd", contract.request(catalog, "composer-url-chip", "url-chip", "dark")["desktop_sha"])
        with self.assertRaises(ValueError):
            contract.request(catalog, "composer-url-chip", "invented", "dark")

    def test_android_receipt_requires_apk_hash_and_source_sha(self) -> None:
        contract.validate_receipt(self.android_receipt(), "android")
        receipt = self.android_receipt()
        receipt["apk_sha256"] = "not-a-hash"
        with self.assertRaises(ValueError):
            contract.validate_receipt(receipt, "android")

    def test_desktop_receipt_requires_real_e2e_origin(self) -> None:
        contract.validate_receipt(self.desktop_receipt(), "desktop")
        receipt = self.desktop_receipt()
        receipt["fixture_origin"] = "handwritten-html"
        with self.assertRaises(ValueError):
            contract.validate_receipt(receipt, "desktop")

    def test_packet_rejects_secret_and_private_path(self) -> None:
        for unsafe in ("Authorization: Bearer x", "/home/someone/private", "-----BEGIN PRIVATE KEY-----"):
            with self.subTest(unsafe=unsafe):
                receipt = self.android_receipt()
                receipt["note"] = unsafe
                with self.assertRaises(ValueError):
                    contract.validate_receipt(receipt, "android")

    def test_check_receipt_cli_refuses_unsafe_committed_packet(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            packet = Path(directory) / "contract.json"
            receipt = self.desktop_receipt()
            receipt["note"] = "C:\\Users\\person"
            packet.write_text(json.dumps(receipt), encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, contract.main(["check-receipt", "--platform", "desktop", "--receipt", str(packet)]))


if __name__ == "__main__":
    unittest.main()
