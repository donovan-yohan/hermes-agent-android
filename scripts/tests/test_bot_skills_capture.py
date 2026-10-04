import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

class SkillsCaptureContractTest(unittest.TestCase):
    def test_fixture_catalog_and_workflow_register_same_states(self):
        catalog = json.loads((ROOT / 'docs/parity/visual-capture-surfaces.json').read_text())
        spec = catalog['surfaces']['bot-installed-skills']
        fixture = (ROOT / 'app/src/debug/kotlin/com/hermesagent/mobile/BotSkillsParityActivity.kt').read_text()
        workflow = (ROOT / '.github/workflows/visual-parity-capture.yml').read_text()
        self.assertIn('bot-installed-skills', workflow)
        self.assertEqual(12, len(spec['states']))
        for state in spec['states']:
            self.assertIn('"' + state + '"', fixture)
            self.assertIn('- ' + state, workflow)
        self.assertIn('.BotSkillsParityActivity', (ROOT / 'app/src/debug/AndroidManifest.xml').read_text())

    def test_missing_or_expired_skills_brackets_are_rejected(self):
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from visual_parity_contract import load_catalog, request, validate_receipt
        for state in ('skills-loading', 'skills-pending'):
            spec = request(load_catalog(), 'bot-installed-skills', state, 'light')
            label = spec['state_spec']['post_interaction_accessibility']
            evidence = {'expected_description': label, 'nodes': [{'text': label}]}
            receipt = dict(schema_version=1, surface='bot-installed-skills', state=state,
                           fixture_id=spec['fixture_id'], theme='light', viewport={}, android_git_sha='a'*40,
                           apk_sha256='b'*64, installed_apk_sha256='b'*64, apk_kind='debug',
                           application=dict(component=spec['android_activity'], package_name='com.hermesagent.mobile.debug',
                                            version_code='1', version_name='test', signing_certificate_sha256='c'*64),
                           interactions=[], accessibility=evidence)
            with self.assertRaisesRegex(ValueError, 'bracket'):
                validate_receipt(receipt, 'android')
            receipt['screenshot_bracket'] = dict(before=evidence, after=evidence, timing=dict(
                basis='monotonic-before-fixture-launch', deadline_seconds=20,
                screenshot_start_seconds=10, screenshot_end_seconds=11, postcheck_seconds=12))
            validate_receipt(receipt, 'android')
            receipt['screenshot_bracket']['timing']['postcheck_seconds'] = 20
            with self.assertRaisesRegex(ValueError, '20-second'):
                validate_receipt(receipt, 'android')

    def test_loading_and_pending_require_measured_launch_and_deadline(self):
        contract = (ROOT / 'scripts/visual_parity_contract.py').read_text()
        worker = (ROOT / '.chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py').read_text()
        shell = (ROOT / 'scripts/capture-android-visual-parity.sh').read_text()
        for state in ('skills-loading', 'skills-pending'):
            self.assertIn('"' + state + '"', contract)
            self.assertIn('"' + state + '"', worker)
        self.assertIn('"$CAPTURE_SURFACE" == "bot-installed-skills"', shell)

if __name__ == '__main__':
    unittest.main()
