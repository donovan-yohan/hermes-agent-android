"""Capture safety regressions; synthetic platform I/O, not emulator proof."""
import copy
import unittest
from unittest.mock import patch
from test_ordered_model_capture import capture, tree
import test_visual_parity_contract as receipt_tests
contract = receipt_tests.contract


def receipt():
    r = receipt_tests.VisualParityContractTest().android_receipt()
    s = contract.request(contract.load_catalog(), 'bot-model-config', 'bot-model-loaded', 'dark')
    r.update(surface=s['surface'], state=s['state'], fixture_id=s['fixture_id'], interactions=['scroll:Choose model'],
             ordered_action_evidence=[{'action': 'scroll:Choose model', 'accessibility': {'nodes': [{'text': 'Choose model'}]}}],
             accessibility={'expected_description': 'Choose model', 'nodes': [{'text': 'Choose model'}]})
    r['application']['component'] = s['android_activity']
    return r


class ReceiptSafetyTest(unittest.TestCase):
    def test_empty_required_final_nodes_rejected(self):
        r = receipt()
        r['accessibility']['nodes'] = []
        with self.assertRaises(ValueError):
            contract.validate_receipt(r, 'android')

    def test_null_step_is_value_error(self):
        r = receipt()
        r['ordered_action_evidence'].append(None)
        with self.assertRaises(ValueError):
            contract.validate_receipt(r, 'android')

    def test_loading_receipt_requires_bracket_and_deadline(self):
        r = receipt()
        label = 'Loading model choices…'
        r.update(state='bot-model-inventory-loading', interactions=['scroll:' + label],
                 ordered_action_evidence=[{'action': 'scroll:' + label, 'accessibility': {'nodes': [{'text': label}]}}],
                 accessibility={'expected_description': label, 'nodes': [{'text': label}]})
        with self.assertRaises(ValueError):
            contract.validate_receipt(r, 'android')
        r['screenshot_bracket'] = {'before': r['accessibility'], 'after': r['accessibility'],
                                   'timing': {'basis': 'monotonic-before-fixture-launch', 'deadline_seconds': 20,
                                              'screenshot_start_seconds': 18, 'screenshot_end_seconds': 19, 'postcheck_seconds': 20}}
        with self.assertRaises(ValueError):
            contract.validate_receipt(r, 'android')
        r['screenshot_bracket']['timing']['postcheck_seconds'] = 19.5
        contract.validate_receipt(r, 'android')
        r['screenshot_bracket']['after'] = {'nodes': []}
        with self.assertRaises(ValueError):
            contract.validate_receipt(r, 'android')

    def test_tap_requires_connected_enabled_pre_tap_evidence(self):
        r = receipt()
        r.update(state='bot-model-manual', interactions=['scroll:Enter manually', 'tap:Enter manually', 'scroll:Model ID'],
                 accessibility={'expected_description': 'Model ID', 'nodes': [{'text': 'Model ID'}]})
        target = {'text': 'Enter manually', 'enabled': True, 'clickable': False, 'package': capture.DEFAULT_PACKAGE, 'bounds': '[0,0][100,100]'}
        ancestor = dict(target, text='', clickable=True)
        r['ordered_action_evidence'] = [
            {'action': 'scroll:Enter manually', 'accessibility': {'nodes': [target]}},
            {'action': 'tap:Enter manually', 'accessibility': {'nodes': [{'text': 'Model ID'}]},
             'pre_tap': {'target': target, 'clickable_ancestor': ancestor}},
            {'action': 'scroll:Model ID', 'accessibility': {'nodes': [{'text': 'Model ID'}]}}]
        with self.assertRaises(ValueError):
            contract.validate_receipt(r, 'android')
        pre = r['ordered_action_evidence'][1]['pre_tap']
        pre['ancestor_path'] = [target, ancestor]
        contract.validate_receipt(r, 'android')
        for key, value in [('target', dict(target, enabled=False)), ('target', dict(target, text='Other')), ('clickable_ancestor', dict(ancestor, clickable=False)), ('ancestor_path', [target])]:
            broken = copy.deepcopy(r)
            broken['ordered_action_evidence'][1]['pre_tap'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                contract.validate_receipt(broken, 'android')

    def test_scroll_requires_actual_label(self):
        r = receipt()
        r['ordered_action_evidence'][0]['accessibility']['nodes'] = []
        with self.assertRaises(ValueError):
            contract.validate_receipt(r, 'android')


class ScreenshotSafetyTest(unittest.TestCase):
    def test_loading_transition_during_screenshot_is_rejected(self):
        with patch.object(capture, 'accessibility_snapshot', side_effect=[{'nodes': [{'text': 'Loading'}]}, SystemExit('now timeout')]), patch.object(capture, 'verify_app_identity'), patch.object(capture, 'adb', return_value=b'png'), patch.object(capture.time, 'monotonic', return_value=1.0):
            with self.assertRaises(SystemExit):
                capture.bracketed_screenshot(None, capture.DEFAULT_PACKAGE, capture.DEFAULT_ACTIVITY, 'Loading', 0.0)

    def test_loading_deadline_crossed_during_screenshot_is_rejected(self):
        with patch.object(capture, 'accessibility_snapshot', return_value={'nodes': [{'text': 'Loading'}]}), patch.object(capture, 'verify_app_identity'), patch.object(capture, 'adb', return_value=b'png'), patch.object(capture.time, 'monotonic', side_effect=[19.0, 20.1, 20.2]):
            with self.assertRaises(SystemExit):
                capture.bracketed_screenshot(None, capture.DEFAULT_PACKAGE, capture.DEFAULT_ACTIVITY, 'Loading', 0.0)


class LaunchSafetyTest(unittest.TestCase):
    def test_identity_precedes_measured_launch_and_actions(self):
        import json
        import tempfile
        from pathlib import Path
        events = []
        label = 'Loading model choices…'
        argv = ['capture', '--name', 'bot-model-config--bot-model-inventory-loading', '--state', 'bot-model-inventory-loading', '--theme', 'dark', '--fixture-id', 'bot-model-config-synthetic-v1', '--git-sha', 'a' * 40, '--apk', __file__, '--apk-kind', 'debug', '--activity', 'com.hermesagent.mobile.ProfileAvatarsParityActivity', '--ordered-actions', json.dumps(['scroll:' + label]), '--expected-accessibility', label, '--launch-fixture']
        provenance = {'apk_sha256': 'b' * 64, 'installed_apk_sha256': 'b' * 64, 'version_code': '1', 'version_name': '1.0', 'signing_certificate_sha256': 'c' * 64}
        evidence = {'expected_description': label, 'nodes': [{'text': label}]}
        bracket = {'before': evidence, 'after': evidence, 'timing': {'basis': 'monotonic-before-fixture-launch', 'deadline_seconds': 20, 'screenshot_start_seconds': 1, 'screenshot_end_seconds': 2, 'postcheck_seconds': 3}}
        def shell(serial, *args):
            if args[0] == 'am':
                events.append('launch')
            return 'synthetic'
        def identity(*args):
            events.append('focus')
            return {'component': 'com.hermesagent.mobile.debug/com.hermesagent.mobile.ProfileAvatarsParityActivity'}
        with tempfile.TemporaryDirectory() as output, patch.object(capture.sys, 'argv', argv + ['--out', output]), patch.object(capture, 'adb', return_value='device'), patch.object(capture, 'installed_apk_provenance', side_effect=lambda *a: (events.append('apk') or provenance)), patch.object(capture.time, 'monotonic', side_effect=lambda: (events.append('clock') or 100)), patch.object(capture, 'shell', side_effect=shell), patch.object(capture, 'verify_app_identity', side_effect=identity), patch.object(capture, 'ordered_accessibility_actions', side_effect=lambda *a, **k: (events.append('actions') or [{'action': 'scroll:' + label, 'accessibility': evidence}])), patch.object(capture, 'bracketed_screenshot', return_value=(b'synthetic-test-only', bracket)) as screenshot:
            capture.main()
            self.assertEqual(['apk', 'clock', 'launch', 'focus', 'actions'], events[:5])
            self.assertEqual(100, screenshot.call_args.args[-1])
            saved = json.loads((Path(output) / 'contract.json').read_text())
            contract.validate_receipt(saved, 'android')
            self.assertEqual(b'synthetic-test-only', (Path(output) / 'reference.png').read_bytes())


class InputSafetyTest(unittest.TestCase):
    def test_wrong_current_focus_despite_expected_focused_app(self):
        focus = 'mCurrentFocus=Window{ x evil.pkg/evil.Activity}\nmFocusedApp=ActivityRecord{ com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity}'
        with patch.object(capture, 'shell', return_value='com.hermesagent.mobile.debug/com.hermesagent.mobile.MainActivity'), patch.object(capture, 'read_focus', return_value=focus):
            with self.assertRaises(SystemExit):
                capture.verify_app_identity(None, capture.DEFAULT_PACKAGE, capture.DEFAULT_ACTIVITY)

    def test_wrong_package_offscreen_and_nonclickable_never_tap(self):
        for attrs in ({'package': 'evil.pkg'}, {'bounds': '[400,0][500,100]'}, {'clickable': 'false'}):
            root = tree('Enter manually')
            node = next(root.iter('node'))
            node.attrib.update(package=capture.DEFAULT_PACKAGE, clickable='true')
            node.attrib.update(attrs)
            with self.subTest(attrs=attrs), patch.object(capture, 'screen_size', return_value=(411, 891)), patch.object(capture, 'ui_hierarchy', return_value=root), patch.object(capture, 'verify_app_identity'), patch.object(capture, 'accessibility_snapshot', return_value={'nodes': []}), patch.object(capture, 'shell') as shell:
                with self.assertRaises(SystemExit):
                    capture.ordered_accessibility_actions(None, ['tap:Enter manually'])
                shell.assert_not_called()

    def test_scroll_refuses_foreign_hierarchy_before_swipe(self):
        root = tree('Other')
        next(root.iter('node')).set('package', 'evil.pkg')
        with patch.object(capture, 'screen_size', return_value=(411, 891)), patch.object(capture, 'ui_hierarchy', return_value=root), patch.object(capture, 'verify_app_identity'), patch.object(capture, 'shell') as shell:
            with self.assertRaises(SystemExit):
                capture.ordered_accessibility_actions(None, ['scroll:Choose model'])
            shell.assert_not_called()

    def test_owned_snapshot_rejects_foreign_or_offscreen_label(self):
        for attrs in ({'package': 'evil.pkg'}, {'bounds': '[400,0][500,100]'}, {'visible-to-user': 'false'}):
            root = tree('Loading')
            next(root.iter('node')).attrib.update(attrs)
            with self.subTest(attrs=attrs), patch.object(capture, 'ui_hierarchy', return_value=root):
                with self.assertRaises(SystemExit):
                    capture.accessibility_snapshot(None, 'Loading', attempts=1, package=capture.DEFAULT_PACKAGE, viewport=(411, 891))

    def test_clickable_parent_retained_and_tapped(self):
        import xml.etree.ElementTree as ET
        root = tree('')
        parent = next(root.iter('node'))
        child = ET.SubElement(parent, 'node', dict(parent.attrib, text='Enter manually', clickable='false', bounds='[10,10][90,90]'))
        with patch.object(capture, 'screen_size', return_value=(411, 891)), patch.object(capture, 'ui_hierarchy', return_value=root), patch.object(capture, 'verify_app_identity') as focus, patch.object(capture, 'accessibility_snapshot', return_value={'nodes': []}), patch.object(capture, 'shell') as shell:
            step = capture.ordered_accessibility_actions(None, ['tap:Enter manually'])[0]
            self.assertEqual(False, step['pre_tap']['target']['clickable'])
            self.assertEqual(True, step['pre_tap']['clickable_ancestor']['clickable'])
            self.assertEqual(2, len(step['pre_tap']['ancestor_path']))
            self.assertEqual(2, focus.call_count)
            shell.assert_called_once_with(None, 'input', 'tap', '50', '50')

    def test_catalog_mismatch_prevents_any_input(self):
        import json
        argv = ['capture', '--name', 'bot-model-config--bot-model-loaded', '--state', 'bot-model-loaded', '--theme', 'dark', '--fixture-id', 'bot-model-config-synthetic-v1', '--git-sha', 'a' * 40, '--apk', __file__, '--apk-kind', 'debug', '--activity', 'com.hermesagent.mobile.ProfileAvatarsParityActivity', '--ordered-actions', json.dumps(['tap:Uncatalogued']), '--expected-accessibility', 'Choose model']
        with patch.object(capture.sys, 'argv', argv), patch.object(capture, 'adb', return_value='device'), patch.object(capture, 'installed_apk_provenance', side_effect=RuntimeError('must not get here')), patch.object(capture, 'ordered_accessibility_actions') as actions:
            with self.assertRaises((ValueError, SystemExit)):
                capture.main()
            actions.assert_not_called()

    def test_focus_loss_before_step_prevents_input(self):
        with patch.object(capture, 'screen_size', return_value=(411, 891)), patch.object(capture, 'ui_hierarchy', return_value=tree('Enter manually')), patch.object(capture, 'verify_app_identity', side_effect=SystemExit('lost focus')), patch.object(capture, 'shell') as shell:
            with self.assertRaises(SystemExit):
                capture.ordered_accessibility_actions(None, ['tap:Enter manually'])
            shell.assert_not_called()


if __name__ == '__main__':
    unittest.main()
