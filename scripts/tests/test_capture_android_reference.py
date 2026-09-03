#!/usr/bin/env python3
"""Tests for Android visual-capture application identity fencing."""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path
from unittest import mock

SCRIPT_PATH = (
    Path(__file__).resolve().parents[2]
    / ".chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py"
)
spec = importlib.util.spec_from_file_location("capture_android_reference", SCRIPT_PATH)
assert spec and spec.loader
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class AndroidCaptureIdentityTest(unittest.TestCase):
    def test_accepts_resolved_focused_expected_activity(self) -> None:
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        focused = f"mCurrentFocus=Window{{synthetic u0 {component}}}"
        with mock.patch.object(capture, "shell", side_effect=[component, focused]):
            identity = capture.verify_app_identity(
                "emulator-5554",
                "com.hermesagent.mobile.debug",
                "com.hermesagent.mobile.MainActivity",
            )
        self.assertEqual(component, identity["component"])
        self.assertEqual(focused, identity["focusedWindow"])

    def test_rejects_wrong_focused_activity(self) -> None:
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        with mock.patch.object(
            capture,
            "shell",
            side_effect=[component, "mCurrentFocus=Window{synthetic u0 com.example/.Wrong}"],
        ):
            with self.assertRaises(SystemExit):
                capture.verify_app_identity(
                    "emulator-5554",
                    "com.hermesagent.mobile.debug",
                    "com.hermesagent.mobile.MainActivity",
                )

    def test_falls_back_to_plain_dumpsys_window_on_api_36_images(self) -> None:
        """API 36+ emits no focus lines under the `windows` subcommand.

        Before this fallback the gate aborted every capture on a Pixel 10 Pro
        emulator — `capture target is not the focused Android activity` — with
        the right app in the foreground the whole time.
        """
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        plain = f"  mCurrentFocus=Window{{synthetic u0 {component}}}\n  mFocusedApp=ActivityRecord{{synthetic u0 {component} t503}}"
        with mock.patch.object(
            capture,
            "shell",
            side_effect=[component, "Window #0 Window{...}: no focus here", plain],
        ) as shell:
            identity = capture.verify_app_identity(
                "emulator-5554",
                "com.hermesagent.mobile.debug",
                "com.hermesagent.mobile.MainActivity",
            )
        self.assertEqual(3, shell.call_count)
        self.assertEqual(("dumpsys", "window", "windows"), shell.call_args_list[1].args[1:])
        self.assertEqual(("dumpsys", "window"), shell.call_args_list[2].args[1:])
        self.assertIn("mFocusedApp", identity["focusedWindow"])

    def test_does_not_ask_twice_when_the_subcommand_answers(self) -> None:
        """Older images answer `dumpsys window windows`; the fallback stays unused."""
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        with mock.patch.object(
            capture,
            "shell",
            side_effect=[component, f"mCurrentFocus=Window{{synthetic u0 {component}}}"],
        ) as shell:
            capture.verify_app_identity(
                "emulator-5554",
                "com.hermesagent.mobile.debug",
                "com.hermesagent.mobile.MainActivity",
            )
        self.assertEqual(2, shell.call_count)

    def test_accepts_a_compose_popup_that_leaves_the_activity_on_focused_app(self) -> None:
        """A dropdown or dialog owns `mCurrentFocus`; the activity is on `mFocusedApp`.

        Matching the two lines separately would refuse every menu capture, which
        is three of the eight states this pass rendered.
        """
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        popup = f"  mCurrentFocus=Window{{a011ee4 u0 Pop-Up Window}}\n  mFocusedApp=ActivityRecord{{98904812 u0 {component} t503}}"
        with mock.patch.object(capture, "shell", side_effect=[component, popup]):
            identity = capture.verify_app_identity(
                "emulator-5554",
                "com.hermesagent.mobile.debug",
                "com.hermesagent.mobile.MainActivity",
            )
        self.assertIn("Pop-Up Window", identity["focusedWindow"])

    def test_rejects_a_popup_belonging_to_another_app(self) -> None:
        """The joined reading is not a way to pass on the popup line alone."""
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        popup = "  mCurrentFocus=Window{a011ee4 u0 Pop-Up Window}\n  mFocusedApp=ActivityRecord{1 u0 com.example/.Wrong t1}"
        with mock.patch.object(capture, "shell", side_effect=[component, popup, popup]):
            with self.assertRaises(SystemExit):
                capture.verify_app_identity(
                    "emulator-5554",
                    "com.hermesagent.mobile.debug",
                    "com.hermesagent.mobile.MainActivity",
                )

    def test_rejects_when_neither_dumpsys_form_reports_focus(self) -> None:
        """No focus reading is a refusal, not a pass — the fallback is not a bypass."""
        component = "com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity"
        with mock.patch.object(capture, "shell", side_effect=[component, "", ""]):
            with self.assertRaises(SystemExit) as raised:
                capture.verify_app_identity(
                    "emulator-5554",
                    "com.hermesagent.mobile.debug",
                    "com.hermesagent.mobile.MainActivity",
                )
        self.assertIn("no mCurrentFocus", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
