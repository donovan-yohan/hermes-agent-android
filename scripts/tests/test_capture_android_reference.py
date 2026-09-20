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

    def test_rejects_empty_signing_certificate_pem(self) -> None:
        output = "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n"
        with self.assertRaisesRegex(SystemExit, "empty PEM"):
            capture.signing_certificate_sha256(output)

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

    def test_retries_until_expected_description_reaches_platform_tree(self) -> None:
        missing = '<hierarchy><node class="Row" content-desc="Composer status" /></hierarchy>'
        expected = '<hierarchy><node class="Row" content-desc="Queue, 2 messages, parked, expand" /></hierarchy>'
        with mock.patch.object(capture, "shell", side_effect=["UI hierarchy dumped", missing, "UI hierarchy dumped", expected]), \
             mock.patch.object(capture.time, "sleep") as sleep:
            evidence = capture.accessibility_snapshot(
                "emulator-5554",
                "Queue, 2 messages, parked, expand",
                attempts=2,
            )
        self.assertEqual(1, len(evidence["nodes"]))
        sleep.assert_called_once_with(0.25)

    def test_rejects_missing_post_interaction_accessibility_state(self) -> None:
        xml = '<hierarchy><node text="Queue" content-desc="Queue, 2 messages, parked, collapse" /></hierarchy>'
        with mock.patch.object(capture, "shell", side_effect=["UI hierarchy dumped", xml]):
            with self.assertRaises(SystemExit):
                capture.accessibility_snapshot("emulator-5554", "Queue, 2 messages, parked, expand", attempts=1)

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
                 mock.patch.object(capture.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=signer)) as run:
                provenance = capture.installed_apk_provenance("emulator-5554", "com.hermesagent.mobile.debug", local)
        resolve.assert_called_once_with()
        signer_command = run.call_args.args[0]
        self.assertEqual(["/sdk/apksigner", "verify", "--verbose", "--print-certs-pem"], signer_command[:4])
        self.assertEqual("base.apk", Path(signer_command[4]).name)
        self.assertEqual(provenance["apk_sha256"], provenance["installed_apk_sha256"])
        self.assertEqual("42", provenance["version_code"])
        self.assertEqual("1.2.3", provenance["version_name"])
        self.assertEqual(hashlib.sha256(b"certificate").hexdigest(), provenance["signing_certificate_sha256"])


class BoundedListSwipeTest(unittest.TestCase):
    """A state below the phone's fold is reached by real, bounded drags — or not at all."""

    PLAIN = '<hierarchy><node class="Row" text="Head row alpha. Idle. Updated 12 minutes ago" /></hierarchy>'
    TARGET = '<hierarchy><node class="Label" text="November 2025" /><node class="Row" text="November 2025 lima. Idle. Updated 309 days ago" /></hierarchy>'
    EMPTY = '<hierarchy><node class="Root" /></hierarchy>'

    def swipe_shell(self, pages: list[str], calls: list[tuple[str, ...]]):
        """Serve `wm size`, then publish the list page the drag count has reached."""
        state = {"swipes": 0}

        def fake(serial, *argv):
            calls.append(argv)
            if argv[:2] == ("wm", "size"):
                return "Physical size: 1440x3120\nOverride size: 1080x2400"
            if argv[:2] == ("uiautomator", "dump"):
                return "UI hierarchy dumped"
            if argv[0] == "cat":
                return pages[min(state["swipes"], len(pages) - 1)]
            if argv[:2] == ("input", "swipe"):
                state["swipes"] += 1
                return ""
            raise AssertionError(argv)

        return fake

    def test_swipes_a_real_drag_until_the_catalogued_row_publishes(self) -> None:
        calls: list[tuple[str, ...]] = []
        expected = "November 2025 lima. Idle. Updated 309 days ago"
        with mock.patch.object(capture, "shell", side_effect=self.swipe_shell([self.PLAIN, self.PLAIN, self.TARGET], calls)), \
             mock.patch.object(capture.time, "sleep"):
            result = capture.swipe_list_up("emulator-5554", expected, settle_attempts=1)
        drags = [call for call in calls if call[:2] == ("input", "swipe")]
        self.assertGreaterEqual(len(drags), 2)
        # Real drags on the screen the platform reported: centre, 0.75H -> 0.25H.
        self.assertEqual(("input", "swipe", "540", "1800", "540", "600", "350"), drags[0])
        self.assertEqual("1080x2400", result["screen"])
        self.assertEqual([540, 1800], result["drag"]["from"])
        self.assertEqual([540, 600], result["drag"]["to"])
        self.assertEqual(len(drags), result["swipes"])

    def test_derives_the_screen_from_the_platform_override_not_from_a_guess(self) -> None:
        with mock.patch.object(capture, "shell", return_value="Physical size: 1440x3120\nOverride size: 1080x2400"):
            self.assertEqual((1080, 2400), capture.screen_size("emulator-5554"))

    def test_rejects_a_screen_the_platform_cannot_report(self) -> None:
        with mock.patch.object(capture, "shell", return_value="unknown"):
            with self.assertRaisesRegex(SystemExit, "could not derive the screen size"):
                capture.screen_size("emulator-5554")

    def test_fails_when_the_row_never_publishes_within_the_bounded_swipes(self) -> None:
        calls: list[tuple[str, ...]] = []
        with mock.patch.object(capture, "shell", side_effect=self.swipe_shell([self.PLAIN], calls)), \
             mock.patch.object(capture.time, "sleep"):
            with self.assertRaisesRegex(SystemExit, "never published in 3 bounded list swipes"):
                capture.swipe_list_up("emulator-5554", "November 2025 lima. Idle. Updated 309 days ago", attempts=3, settle_attempts=1)
        self.assertEqual(3, len([call for call in calls if call[:2] == ("input", "swipe")]))

    def test_refuses_to_swipe_without_a_catalogued_row_to_scroll_to(self) -> None:
        with self.assertRaisesRegex(SystemExit, "needs the catalogued post-interaction description"):
            capture.swipe_list_up("emulator-5554", None)

    def test_fails_when_the_fixture_never_rendered_at_all(self) -> None:
        calls: list[tuple[str, ...]] = []
        with mock.patch.object(capture, "shell", side_effect=self.swipe_shell([self.EMPTY], calls)), \
             mock.patch.object(capture.time, "sleep"):
            with self.assertRaisesRegex(SystemExit, "never rendered"):
                capture.swipe_list_up("emulator-5554", "November 2025 lima. Idle. Updated 309 days ago", render_attempts=2)
        self.assertEqual([], [call for call in calls if call[:2] == ("input", "swipe")])


class InteractionReceiptTest(unittest.TestCase):
    """The receipt names the interactions that really ran, in the catalogued spelling."""

    def test_records_the_catalogued_swipe_and_tap_interactions(self) -> None:
        self.assertEqual([], capture.interaction_receipt(None, False))
        self.assertEqual(["tap:Background"], capture.interaction_receipt("Background", False))
        self.assertEqual(["swipe:list-up"], capture.interaction_receipt(None, True))
        self.assertEqual(["tap:Background", "swipe:list-up"], capture.interaction_receipt("Background", True))


class PublishedAccessibilityTest(unittest.TestCase):
    """A state published as `text` counts, not only one merged into `content-desc`."""

    def test_accepts_a_plain_text_node_as_the_catalogued_state(self) -> None:
        note = "Everything here is pinned. Unpin a chat to show it in recents."
        xml = f'<hierarchy><node class="Text" text="{note}" /></hierarchy>'
        with mock.patch.object(capture, "shell", side_effect=["UI hierarchy dumped", xml]):
            evidence = capture.accessibility_snapshot("emulator-5554", note)
        self.assertEqual(note, evidence["expected_description"])
        self.assertEqual(note, evidence["nodes"][0]["text"])

    def test_still_accepts_a_merged_content_description(self) -> None:
        xml = '<hierarchy><node class="Row" text="Queue · 2 · parked" content-desc="Queue, 2 messages, parked, expand" /></hierarchy>'
        with mock.patch.object(capture, "shell", side_effect=["UI hierarchy dumped", xml]):
            evidence = capture.accessibility_snapshot("emulator-5554", "Queue, 2 messages, parked, expand")
        self.assertEqual("Queue, 2 messages, parked, expand", evidence["expected_description"])


if __name__ == "__main__":
    unittest.main()
