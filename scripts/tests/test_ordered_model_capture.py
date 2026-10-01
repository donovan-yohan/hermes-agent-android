"""Ordered capture driver tests: mocked platform I/O, not emulator pixel proof."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("ordered_capture", ROOT / ".chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py")
assert spec is not None and spec.loader is not None
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


def tree(label):
    root = ET.Element("hierarchy")
    ET.SubElement(root, "node", {"text": label, "enabled": "true", "clickable": "true", "package": capture.DEFAULT_PACKAGE, "bounds": "[0,0][100,100]"})
    return root


class OrderedModelCaptureTest(unittest.TestCase):
    def test_all_steps_run_in_order_and_keep_evidence(self):
        with patch.object(capture, "verify_app_identity"), \
             patch.object(capture, "screen_size", return_value=(411, 891)), \
             patch.object(capture, "ui_hierarchy", side_effect=[tree("Other"), tree("Enter manually"), tree("Enter manually"), tree("Model ID")]), \
             patch.object(capture, "accessibility_snapshot", return_value={"nodes": []}) as snapshot, \
             patch.object(capture, "shell") as shell:
            actions = ["scroll:Enter manually", "tap:Enter manually", "scroll:Model ID"]
            result = capture.ordered_accessibility_actions(None, actions)
            self.assertEqual(actions, [r["action"] for r in result])
            self.assertEqual(["swipe", "tap"], [call.args[2] for call in shell.call_args_list])
            self.assertEqual(3, snapshot.call_count)

    def test_refuses_missing_tap_instead_of_claiming_evidence(self):
        with patch.object(capture, "verify_app_identity"), \
             patch.object(capture, "screen_size", return_value=(411, 891)), \
             patch.object(capture, "ui_hierarchy", return_value=tree("Other")), \
             patch.object(capture, "shell") as shell:
            with self.assertRaises(SystemExit):
                capture.ordered_accessibility_actions(None, ["tap:Enter manually"])
            shell.assert_not_called()

    def test_scroll_is_bounded_and_unknown_actions_refuse(self):
        with patch.object(capture, "verify_app_identity"), \
             patch.object(capture, "screen_size", return_value=(411, 891)), \
             patch.object(capture, "ui_hierarchy", return_value=tree("Other")), \
             patch.object(capture, "shell") as shell:
            with self.assertRaises(SystemExit):
                capture.ordered_accessibility_actions(None, ["scroll:Missing"])
            self.assertEqual(16, shell.call_count)
            with self.assertRaises(SystemExit):
                capture.ordered_accessibility_actions(None, ["invent:Saved"])


if __name__ == "__main__":
    unittest.main()
