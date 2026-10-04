"""Reduced collector receipts must survive AGP's proven target uninstall."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
class RetainedTest(unittest.TestCase):
    def test_uninstall_fallback_is_nonce_bound_unique_and_resanitized(self):
        spec = importlib.util.spec_from_file_location('focus', ROOT / 'scripts/focus_diagnostic.py')
        assert spec and spec.loader
        gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gate)
        nonce = 'a' * 32
        value = json.dumps(dict(schema=1, before=None, owner=dict(display=0, ownerPid=123, ownerUid=1000), after=None))
        for mode in ('valid', 'stale', 'duplicate', 'secret'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                logs = root / 'app/build/outputs/androidTest-results/connected'
                logs.mkdir(parents=True)
                marker = ('b' * 32 if mode == 'stale' else nonce)
                payload = value if mode != 'secret' else value[:-1] + ', "title":"private"}'
                line = '10-04 00:00:00 I HermesFocusReduced: ' + marker + ':1:' + payload + '\n'
                (logs / 'logcat-test.txt').write_text(line * (2 if mode == 'duplicate' else 1))
                def adb(*args):
                    if args[0] == 'get-serialno': return gate.SERIAL
                    if args[0] == 'shell': return nonce + ':' + gate.SERIAL + ':' + gate.PROVENANCE
                    raise FileNotFoundError('target uninstalled')
                with patch.object(gate, 'adb', side_effect=adb): gate.collect(root, nonce)
                receipt = root / ('app/build/focus-' + nonce + '/readiness-1.json')
                self.assertEqual(receipt.exists(), mode == 'valid')
                if receipt.exists(): self.assertEqual(json.loads(receipt.read_text()), json.loads(value))
if __name__ == '__main__': unittest.main()
