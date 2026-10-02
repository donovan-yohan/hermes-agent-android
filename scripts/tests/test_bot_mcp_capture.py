import importlib.util
import json
from pathlib import Path
import unittest
from typing import Any

ROOT = Path(__file__).resolve().parents[2]

class McpCaptureContractTest(unittest.TestCase):
    def test_runtime_reader_rejects_wrong_identity_and_finished_pending_operation(self):
        import base64
        import sys
        from unittest.mock import patch
        sys.path.insert(0, str(ROOT / 'scripts'))
        path = ROOT / '.chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py'
        module_spec = importlib.util.spec_from_file_location('mcp_capture_worker', path)
        assert module_spec is not None and module_spec.loader is not None
        worker = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(worker)
        sample: dict[str, Any] = dict(fixture='bot-configured-mcp-synthetic-v1', scenario='mcp-pending', theme='light',
                      requests=[dict(method='PUT', outcome='pending')])
        def encoded():
            return 'Bundle[{snapshot=' + base64.b64encode(json.dumps(sample).encode()).decode() + '}]'
        with patch.object(worker, 'shell', side_effect=lambda *args: encoded()) as shell:
            self.assertEqual(sample, worker.read_mcp_runtime(None, 'synthetic', 'mcp-pending', 'light'))
            self.assertIn('content://synthetic.mcp-runtime', shell.call_args.args)
            sample['theme'] = 'dark'
            with self.assertRaisesRegex(SystemExit, 'another fixture'):
                worker.read_mcp_runtime(None, 'synthetic', 'mcp-pending', 'light')
            sample['theme'] = 'light'
            sample['requests'][0]['outcome'] = 'completed'
            with self.assertRaisesRegex(SystemExit, 'pending operation'):
                worker.read_mcp_runtime(None, 'synthetic', 'mcp-pending', 'light')

    def test_worker_brackets_actual_mcp_runtime_export(self):
        worker = (ROOT / '.chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py').read_text()
        fixture = (ROOT / 'app/src/debug/kotlin/com/hermesagent/mobile/BotMcpParityActivity.kt').read_text()
        self.assertIn('read_mcp_runtime(args.serial, args.package, args.state, args.theme)', worker)
        self.assertIn('bot-configured-mcp-synthetic-v1', fixture)
        self.assertIn('put("theme", theme)', fixture)
        self.assertIn('android.permission.DUMP', (ROOT / 'app/src/debug/AndroidManifest.xml').read_text())

    def test_fixture_catalog_and_workflow_register_same_states(self):
        catalog = json.loads((ROOT / 'docs/parity/visual-capture-surfaces.json').read_text())
        spec = catalog['surfaces']['bot-configured-mcp']
        fixture = (ROOT / 'app/src/debug/kotlin/com/hermesagent/mobile/BotMcpParityActivity.kt').read_text()
        workflow = (ROOT / '.github/workflows/visual-parity-capture.yml').read_text()
        self.assertIn('bot-configured-mcp', workflow)
        self.assertEqual(13, len(spec['states']))
        for state in spec['states']:
            self.assertIn('"' + state + '"', fixture)
            self.assertIn('- ' + state, workflow)
        self.assertIn('.BotMcpParityActivity', (ROOT / 'app/src/debug/AndroidManifest.xml').read_text())

    def test_missing_or_expired_mcp_brackets_are_rejected(self):
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from visual_parity_contract import load_catalog, request, validate_receipt
        for state in ('mcp-loading', 'mcp-pending'):
            spec = request(load_catalog(), 'bot-configured-mcp', state, 'light')
            label = spec['state_spec']['post_interaction_accessibility']
            evidence = {'expected_description': label, 'nodes': [{'text': label}]}
            receipt = dict(schema_version=1, surface='bot-configured-mcp', state=state,
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
        for state in ('mcp-loading', 'mcp-pending'):
            self.assertIn('"' + state + '"', contract)
            self.assertIn('"' + state + '"', worker)
        self.assertIn('"$CAPTURE_SURFACE" == "bot-configured-mcp"', shell)

if __name__ == '__main__':
    unittest.main()
