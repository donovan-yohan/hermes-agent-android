#!/usr/bin/env python3
"""Tests for Android visual-capture identity, installed bytes, and a11y evidence."""
from __future__ import annotations

import importlib.util
import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT_PATH = Path(__file__).resolve().parents[2] / ".chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py"
spec = importlib.util.spec_from_file_location("capture_android_reference", SCRIPT_PATH)
assert spec and spec.loader
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class AndroidCaptureIdentityTest(unittest.TestCase):
    def test_hashes_signing_certificate_der_without_display_label(self) -> None:
        output = """Signer certificate details changed\n-----BEGIN CERTIFICATE-----\nY2VydGlmaWNhdGU=\n-----END CERTIFICATE-----\n"""
        self.assertEqual(hashlib.sha256(b"certificate").hexdigest(), capture.signing_certificate_sha256(output))

    def test_rejects_missing_signing_certificate_pem(self) -> None:
        with self.assertRaisesRegex(SystemExit, "did not emit"):
            capture.signing_certificate_sha256("Signer #1 certificate SHA-256 digest: aabbcc")

    def test_resolves_apksigner_from_path_first(self) -> None:
        with mock.patch.object(capture.shutil, "which", return_value="/tools/apksigner"):
            self.assertEqual("/tools/apksigner", capture.resolve_apksigner())

    def test_resolves_newest_installed_sdk_apksigner(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            old = sdk / "build-tools/9.0.0/apksigner"
            newest = sdk / "build-tools/35.0.0/apksigner"
            for binary in (old, newest):
                binary.parent.mkdir(parents=True)
                binary.touch(mode=0o755)
            with mock.patch.object(capture.shutil, "which", return_value=None), \
                 mock.patch.dict(capture.os.environ, {"ANDROID_HOME": str(sdk)}, clear=True):
                self.assertEqual(str(newest), capture.resolve_apksigner())

    def test_rejects_missing_apksigner(self) -> None:
        with mock.patch.object(capture.shutil, "which", return_value=None), \
             mock.patch.dict(capture.os.environ, {}, clear=True):
            with self.assertRaisesRegex(SystemExit, "apksigner was not found"):
                capture.resolve_apksigner()

    def test_accepts_resolved_focused_expected_activity(self) -> None:
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        focused = f"mCurrentFocus=Window{{synthetic u0 {component}}}"
        with mock.patch.object(capture, "shell", side_effect=[component, focused]):
            identity = capture.verify_app_identity("emulator-5554", "com.hermesagent.mobile.debug", "com.hermesagent.mobile.MainActivity")
        self.assertEqual(component, identity["component"])
        self.assertEqual(focused, identity["focused_window"])

    def test_rejects_wrong_focused_activity(self) -> None:
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        with mock.patch.object(capture, "shell", side_effect=[component, "mCurrentFocus=Window{synthetic u0 com.example/.Wrong}", ""]):
            with self.assertRaises(SystemExit):
                capture.verify_app_identity("emulator-5554", "com.hermesagent.mobile.debug", "com.hermesagent.mobile.MainActivity")

    def test_falls_back_to_plain_dumpsys_window_on_api_36_images(self) -> None:
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        plain = f"mCurrentFocus=Window{{synthetic u0 {component}}}\nmFocusedApp=ActivityRecord{{synthetic u0 {component} t503}}"
        with mock.patch.object(capture, "shell", side_effect=[component, "no focus", plain]) as shell:
            identity = capture.verify_app_identity("emulator-5554", "com.hermesagent.mobile.debug", "com.hermesagent.mobile.MainActivity")
        self.assertEqual(3, shell.call_count)
        self.assertIn("mFocusedApp", identity["focused_window"])

    def test_post_interaction_accessibility_snapshot_requires_named_state(self) -> None:
        xml = '<hierarchy><node class="Row" text="Queue · 2 · parked" content-desc="Queue, 2 messages, parked, expand" clickable="true" enabled="true" selected="false" /></hierarchy>'
        with mock.patch.object(capture, "shell", side_effect=["UI hierarchy dumped", xml]):
            evidence = capture.accessibility_snapshot("emulator-5554", "Queue, 2 messages, parked, expand")
        self.assertEqual("Queue, 2 messages, parked, expand", evidence["expected_description"])
        self.assertEqual(1, len(evidence["nodes"]))

    def test_rejects_missing_post_interaction_accessibility_state(self) -> None:
        xml = '<hierarchy><node text="Queue" content-desc="Queue, 2 messages, parked, collapse" /></hierarchy>'
        with mock.patch.object(capture, "shell", side_effect=["UI hierarchy dumped", xml]):
            with self.assertRaises(SystemExit):
                capture.accessibility_snapshot("emulator-5554", "Queue, 2 messages, parked, expand")

    def test_pulls_installed_base_apk_and_records_package_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / "local.apk"
            local.write_bytes(b"exact installed bytes")

            def adb(serial, *args, binary=False):
                self.assertEqual("pull", args[0])
                Path(args[2]).write_bytes(local.read_bytes())
                return "1 file pulled"

            package_dump = "versionCode=42 minSdk=23\nversionName=1.2.3\n"
            signer = "-----BEGIN CERTIFICATE-----\nY2VydGlmaWNhdGU=\n-----END CERTIFICATE-----\n"
            with mock.patch.object(capture, "shell", side_effect=["package:/data/app/example/base.apk", package_dump]), \
                 mock.patch.object(capture, "adb", side_effect=adb), \
                 mock.patch.object(capture, "resolve_apksigner", return_value="/sdk/apksigner") as resolve, \
                 mock.patch.object(capture.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=signer)):
                provenance = capture.installed_apk_provenance("emulator-5554", "com.hermesagent.mobile.debug", local)
        resolve.assert_called_once_with()
        self.assertEqual(provenance["apk_sha256"], provenance["installed_apk_sha256"])
        self.assertEqual("42", provenance["version_code"])
        self.assertEqual("1.2.3", provenance["version_name"])
        self.assertEqual(hashlib.sha256(b"certificate").hexdigest(), provenance["signing_certificate_sha256"])


if __name__ == "__main__":
    unittest.main()
