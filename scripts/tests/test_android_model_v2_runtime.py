"""Runtime driver regression tests; not capture evidence."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('capture_v2', ROOT / '.chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py')
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)

class RuntimeV2Test(unittest.TestCase):
    def test_named_field_boundary_uses_accessibility_description_not_caption(self):
        self.assertTrue(hasattr(capture, 'accessibility_boundary_matches'))
        nodes = [{'text': 'Model ID', 'content_description': ''}, {'text': 'synthetic-planner-v1', 'content_description': 'Model ID'}]
        self.assertEqual(1, capture.accessibility_boundary_matches(nodes, 'Model ID'))
        self.assertEqual(2, capture.accessibility_boundary_matches(nodes + [nodes[1]], 'Model ID'))

    def test_rpc_samples_straddle_the_real_screenshot(self):
        order = []
        with patch.object(capture, 'verify_app_identity'), patch.object(capture, 'accessibility_snapshot', return_value={'nodes': []}), patch.object(capture, 'adb', side_effect=lambda *a, **k: order.append('png') or b'png'):
            def sample():
                order.append('runtime')
                return {'sample': len(order)}
            png, bracket = capture.bracketed_screenshot(None, 'package', 'activity', 'label', runtime_reader=sample)
        self.assertEqual(['runtime', 'png', 'runtime'], order)
        self.assertEqual(b'png', png)
        self.assertEqual(1, bracket['runtime']['before']['sample'])
        self.assertEqual(3, bracket['runtime']['after']['sample'])

    def test_runtime_identity_mismatch_is_not_relabelled(self):
        spec = capture.request(capture.load_catalog(), 'bot-model-config', 'bot-model-loaded', 'dark', fixture_id='bot-model-config-synthetic-v2', platform='android')
        with self.assertRaisesRegex(SystemExit, 'another fixture'):
            capture.model_v2_proof(spec, {'fixture_id':'bot-model-config-synthetic-v1'}, {}, [], [], 'a'*64)

    def test_missing_runtime_export_fails_closed(self):
        self.assertTrue(hasattr(capture, 'read_model_runtime'), 'v2 needs real runtime reader')
        with patch.object(capture, 'shell', return_value='Bundle[{}]'):
            with self.assertRaises(SystemExit):
                capture.read_model_runtime(None, capture.DEFAULT_PACKAGE)

    def test_explicit_fixture_is_resolved_before_device_provenance(self):
        import sys
        argv = ['capture', '--name', 'bot-model-config--bot-model-loaded', '--state', 'bot-model-loaded', '--theme', 'dark', '--fixture-id', 'bot-model-config-synthetic-v2', '--git-sha', 'a'*40, '--apk', __file__, '--apk-kind', 'debug', '--activity', 'com.hermesagent.mobile.ProfileAvatarsParityActivity', '--ordered-actions', '["scroll:Choose model"]', '--expected-accessibility', 'Choose model']
        with patch.object(sys, 'argv', argv), patch.object(capture, 'adb', return_value='device'), patch.object(capture, 'installed_apk_provenance', side_effect=RuntimeError('preflight passed')):
            with self.assertRaisesRegex(RuntimeError, 'preflight passed'):
                capture.main()

if __name__ == '__main__': unittest.main()
