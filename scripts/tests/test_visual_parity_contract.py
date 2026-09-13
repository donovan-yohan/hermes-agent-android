#!/usr/bin/env python3
"""Regression tests for visual-parity capture provenance and catalog guards."""
from __future__ import annotations

import contextlib
import importlib.util
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
            "apk_sha256": "b" * 64, "installed_apk_sha256": "b" * 64, "apk_kind": "debug",
            "interactions": [], "accessibility": {"expected_description": None, "nodes": []},
            "application": {
                "component": "com.hermesagent.mobile.debug/com.hermesagent.mobile.ComposerReferenceParityActivity",
                "package_name": "com.hermesagent.mobile.debug", "version_code": "42",
                "version_name": "1.0", "signing_certificate_sha256": "c" * 64,
            },
        }

    def desktop_receipt(self) -> dict:
        return {
            "schema_version": 1, "surface": "composer-status-stack", "state": "goal-active",
            "fixture_id": "composer-status-stack-synthetic-v1", "theme": "light",
            "viewport": {"width": 1220, "height": 800},
            "desktop_upstream_sha": "564aef2946c436500a5e80ee117b66b789b3f99a",
            "fixture_origin": "pinned-desktop-e2e-mock",
        }

    def test_catalog_allows_declared_states_only(self) -> None:
        catalog = contract.load_catalog(Path(__file__).resolve().parents[2] / "docs/parity/visual-capture-surfaces.json")
        self.assertEqual("72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd", contract.request(catalog, "composer-url-chip", "url-chip", "dark")["desktop_sha"])
        with self.assertRaises(ValueError):
            contract.request(catalog, "composer-url-chip", "invented", "dark")

    def test_android_receipt_requires_installed_byte_proof_and_fixture_activity(self) -> None:
        contract.validate_receipt(self.android_receipt(), "android")
        for key, value in (("installed_apk_sha256", "a" * 64), ("fixture_id", "wrong-fixture")):
            with self.subTest(key=key):
                receipt = self.android_receipt()
                receipt[key] = value
                with self.assertRaises(ValueError):
                    contract.validate_receipt(receipt, "android")
        receipt = self.android_receipt()
        receipt["application"]["component"] = "com.hermesagent.mobile.debug/com.hermesagent.mobile.ComposerStatusParityActivity"
        with self.assertRaises(ValueError):
            contract.validate_receipt(receipt, "android")

    def test_receipt_rejects_wrong_surface_state_and_desktop_pin(self) -> None:
        receipt = self.android_receipt()
        receipt["state"] = "goal-active"
        with self.assertRaises(ValueError):
            contract.validate_receipt(receipt, "android")
        receipt = self.desktop_receipt()
        receipt["desktop_upstream_sha"] = "a" * 40
        with self.assertRaises(ValueError):
            contract.validate_receipt(receipt, "desktop")

    def test_interaction_state_requires_retained_accessibility_evidence(self) -> None:
        receipt = self.desktop_receipt()  # Confirm the control baseline first.
        contract.validate_receipt(receipt, "desktop")
        android = self.android_receipt()
        android.update({
            "surface": "composer-status-stack", "state": "background-open",
            "fixture_id": "composer-status-stack-synthetic-v1",
            "interactions": ["tap:Background"],
            "accessibility": {"expected_description": "Background, 1, collapse", "nodes": []},
        })
        android["application"]["component"] = "com.hermesagent.mobile.debug/com.hermesagent.mobile.ComposerStatusParityActivity"
        contract.validate_receipt(android, "android")
        android["accessibility"]["expected_description"] = None
        with self.assertRaises(ValueError):
            contract.validate_receipt(android, "android")

    def test_collapsed_queue_retains_initial_accessibility_without_a_tap(self) -> None:
        android = self.android_receipt()
        android.update({
            "surface": "composer-status-stack", "state": "queue-parked-collapsed",
            "fixture_id": "composer-status-stack-synthetic-v1",
            "interactions": [],
            "accessibility": {"expected_description": "Queue, 2 messages, parked, expand", "nodes": []},
        })
        android["application"]["component"] = "com.hermesagent.mobile.debug/com.hermesagent.mobile.ComposerStatusParityActivity"
        contract.validate_receipt(android, "android")

        android["interactions"] = ["tap:Queue"]
        with self.assertRaises(ValueError):
            contract.validate_receipt(android, "android")

    def test_packet_rejects_secret_and_private_path(self) -> None:
        for unsafe in ("Authorization: Bearer ***", "/home/someone/private", "-----BEGIN PRIVATE KEY-----"):
            with self.subTest(unsafe=unsafe):
                receipt = self.android_receipt()
                receipt["note"] = unsafe
                with self.assertRaises(ValueError):
                    contract.validate_receipt(receipt, "android")

    def test_check_receipt_cli_refuses_unsafe_packet(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            packet = Path(directory) / "contract.json"
            receipt = self.desktop_receipt()
            receipt["note"] = "C:\\Users\\person"
            packet.write_text(json.dumps(receipt), encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, contract.main(["check-receipt", "--platform", "desktop", "--receipt", str(packet)]))


if __name__ == "__main__":
    unittest.main()
