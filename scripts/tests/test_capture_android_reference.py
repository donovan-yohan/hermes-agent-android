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


if __name__ == "__main__":
    unittest.main()
