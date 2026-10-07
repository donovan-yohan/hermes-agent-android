"""Stage-only diagnostics do not change the cache compatibility contract."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ci_avd_cache', ROOT / 'scripts/ci_avd_cache.py')
assert spec and spec.loader
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class AvdGuardDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.config = self.root / 'avd/test.avd/config.ini'
        self.config.parent.mkdir(parents=True)
        self.config.write_text('hw.cpu.ncore=2\nhw.ramSize=2048\n')
        (self.config.parent.parent / 'test.ini').write_text(
            f'path={self.config.parent}\npath.rel=avd/test.avd\n')
        self.receipt = self.root / 'receipt.json'
        cache.record(self.receipt, 'fixture-key')

    def invoke(self, identity='fixture-key'):
        output = io.StringIO()
        with patch.dict(os.environ, {'ANDROID_HOME': str(self.root / 'sdk'),
                                     'ANDROID_AVD_HOME': str(self.config.parent.parent)}), \
                patch.object(cache, 'RECEIPT', self.receipt), \
                patch.object(cache, 'host_identity', return_value={}), \
                patch.object(cache, 'identity', return_value=identity), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            result = cache.main(['verify-miss'])
        return result, output.getvalue()

    def test_key_mismatch_is_distinguished_from_config_mutation(self):
        result, output = self.invoke('different-key')
        self.assertEqual(result, 1)
        self.assertIn('AVD guard stage=receipt-key status=failed', output)
        self.assertNotIn('stage=creation-config', output)
        cache.verify(self.receipt, 'fixture-key', self.config, mode='miss')
        self.config.write_text('hw.cpu.ncore=4\nhw.ramSize=2048\n')
        result, output = self.invoke()
        self.assertEqual(result, 1)
        self.assertIn('AVD guard stage=creation-config status=failed', output)

    def test_locator_failure_is_distinguished_from_identity_failure(self):
        (self.config.parent.parent / 'test.ini').write_text('path=wrong\npath.rel=avd/test.avd\n')
        result, output = self.invoke()
        self.assertEqual(result, 1)
        self.assertIn('AVD guard stage=avd-layout status=failed', output)
        self.assertNotIn('stage=receipt-key', output)
        with patch.object(cache, 'identity', side_effect=OSError('PRIVATE-DETAIL')):
            # invoke patches identity itself, so exercise main directly here.
            output = io.StringIO()
            with patch.dict(os.environ, {'ANDROID_HOME': str(self.root / 'sdk')}), \
                    patch.object(cache, 'host_identity', return_value={}), \
                    contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                self.assertEqual(cache.main(['verify-miss']), 1)
            self.assertIn('AVD guard stage=installed-identity status=failed', output.getvalue())
            self.assertNotIn('PRIVATE-DETAIL', output.getvalue())

    def test_success_and_output_are_fixed_stage_status_only(self):
        result, output = self.invoke()
        self.assertEqual(result, 0)
        allowed = {'command', 'installed-identity', 'avd-input', 'avd-layout',
                   'receipt-read', 'receipt-key', 'config-read', 'creation-config', 'receipt-write'}
        lines = [line for line in output.splitlines() if line.startswith('AVD guard ')]
        self.assertTrue(lines)
        for line in lines:
            prefix, stage, status = line.rsplit(' ', 2)
            self.assertEqual(prefix, 'AVD guard')
            self.assertIn(stage.removeprefix('stage='), allowed)
            self.assertIn(status, ('status=started', 'status=passed'))
        self.assertNotIn(str(self.root), output)
        self.assertNotIn('fixture-key', output)
        self.assertNotIn('hw.cpu.ncore', output)


if __name__ == '__main__':
    unittest.main()
