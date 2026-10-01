import json
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]

class BotAvatarCaptureRegistrationTest(unittest.TestCase):
    def test_all_states_have_truthful_catalog_and_workflow_dispatch(self):
        catalog = json.loads((ROOT / 'docs/parity/visual-capture-surfaces.json').read_text())
        self.assertIn('bot-avatar-editor', catalog['surfaces'])
        spec = catalog['surfaces']['bot-avatar-editor']
        expected = {'bot-avatar-' + s for s in ('loaded', 'loading', 'picking', 'cancel', 'error', 'changed', 'saved', 'cleared')}
        self.assertEqual(expected, set(spec['states']))
        workflow = (ROOT / '.github/workflows/visual-parity-capture.yml').read_text()
        self.assertIn('bot-avatar-editor', workflow)
        for state in expected:
            self.assertIn('- ' + state, workflow)
            self.assertTrue(spec['states'][state]['fixture_note'])
        self.assertIn('BOT_AVATAR_CAPTURE_STATES', (ROOT / 'app/src/debug/kotlin/com/hermesagent/mobile/ProfileAvatarsParityActivity.kt').read_text())
        self.assertEqual('587e673e2a2fae0616d8b750bb189217080f621a', spec['desktop_sha'])

    def test_fixture_default_uses_system_insets_and_surface_before_scrolling(self):
        source = (ROOT / 'app/src/debug/kotlin/com/hermesagent/mobile/BotAvatarParityFixture.kt').read_text()
        self.assertIn('systemInsets: WindowInsets = WindowInsets.systemBars', source)
        self.assertLess(source.index('.background('), source.index('.windowInsetsPadding(systemInsets)'))
        self.assertLess(source.index('.windowInsetsPadding(systemInsets)'), source.index('.verticalScroll('))

    def test_avatar_dispatch_uses_ordered_actions_and_measured_launch(self):
        source = (ROOT / 'scripts/capture-android-visual-parity.sh').read_text()
        self.assertEqual(2, source.count('"$CAPTURE_SURFACE" == "bot-avatar-editor"'))
        self.assertIn('request["surface"] in ("bot-model-config", "bot-avatar-editor")', source)

    def test_avatar_loading_receipt_rejects_missing_or_late_bracket(self):
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from visual_parity_contract import load_catalog, request, validate_receipt
        spec = request(load_catalog(), 'bot-avatar-editor', 'bot-avatar-loading', 'light')
        label = spec['state_spec']['post_interaction_accessibility']
        evidence = {'expected_description': label, 'nodes': [{'text': label}]}
        receipt = dict(schema_version=1, surface='bot-avatar-editor', state='bot-avatar-loading',
                       fixture_id=spec['fixture_id'], theme='light', viewport={}, android_git_sha='a'*40,
                       apk_sha256='b'*64, installed_apk_sha256='b'*64, apk_kind='debug',
                       application=dict(component=spec['android_activity'], package_name='com.hermesagent.mobile.debug',
                                        version_code='1', version_name='test', signing_certificate_sha256='c'*64),
                       interactions=spec['state_spec']['interaction'], accessibility=evidence)
        with self.assertRaisesRegex(ValueError, 'bracket'): validate_receipt(receipt, 'android')
        receipt['screenshot_bracket'] = dict(before=evidence, after=evidence, timing=dict(
            basis='monotonic-before-fixture-launch', deadline_seconds=60,
            screenshot_start_seconds=40, screenshot_end_seconds=41, postcheck_seconds=42))
        validate_receipt(receipt, 'android')
        receipt['screenshot_bracket']['timing']['postcheck_seconds'] = 60
        with self.assertRaisesRegex(ValueError, '60-second'): validate_receipt(receipt, 'android')

if __name__ == '__main__': unittest.main()
