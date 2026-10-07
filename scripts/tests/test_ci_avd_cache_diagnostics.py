"""Fixed-enum diagnostics do not change the cache compatibility contract."""
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

    def test_creation_delta_is_fixed_categories_only_and_still_refused(self):
        cache.verify(self.receipt, 'fixture-key', self.config, mode='miss')
        baseline = self.receipt.read_bytes()
        self.config.write_text('hw.cpu.ncore=4\nfastboot.forceColdBoot=yes\n'
                               'image.sysdir.1=PRIVATE-DETAIL\n'
                               'PRIVATE-KEY=PRIVATE-VALUE\n')
        result, output = self.invoke()
        self.assertEqual(result, 1)
        deltas = [line for line in output.splitlines() if line.startswith('AVD config delta ')]
        self.assertEqual(deltas, [f'AVD config delta category={category}'
                                  for category in ('hardware', 'snapshot', 'path', 'other')])
        self.assertEqual(self.receipt.read_bytes(), baseline)
        for forbidden in ('PRIVATE-DETAIL', 'PRIVATE-KEY', 'PRIVATE-VALUE',
                          'hw.cpu.ncore', 'hw.ramSize', 'fastboot.forceColdBoot',
                          'image.sysdir.1', str(self.root), 'fixture-key'):
            self.assertNotIn(forbidden, output)

    def test_creation_delta_ignores_unchanged_keys_and_covers_removal(self):
        cache.verify(self.receipt, 'fixture-key', self.config, mode='miss')
        self.config.write_text('hw.cpu.ncore=2\n')
        result, output = self.invoke()
        self.assertEqual(result, 1)
        self.assertEqual([line for line in output.splitlines()
                          if line.startswith('AVD config delta ')],
                         ['AVD config delta category=hardware'])

    def test_direct_creation_failure_remains_quiet(self):
        cache.verify(self.receipt, 'fixture-key', self.config, mode='miss')
        self.config.write_text('hw.cpu.ncore=4\n')
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            with self.assertRaises(ValueError):
                cache.verify(self.receipt, 'fixture-key', self.config, mode='miss')
        self.assertEqual(output.getvalue(), '')

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

    def assert_layout_reason(self, reason):
        result, output = self.invoke()
        self.assertEqual(result, 1)
        self.assertIn(f'AVD guard stage=avd-layout reason={reason}', output)
        self.assertNotIn('stage=receipt-key', output)
        self.assertNotIn(str(self.root), output)
        self.assertNotIn('PRIVATE-DETAIL', output)

    def test_locator_constraints_have_distinct_fixed_reasons(self):
        locator = self.config.parent.parent / 'test.ini'
        valid = locator.read_text()
        cases = (
            ('path=PRIVATE-DETAIL\npath.rel=avd/test.avd\n', 'locator-path'),
            (f'path={self.config.parent}\npath.rel=avd/PRIVATE-DETAIL.avd\n',
             'locator-relative-path'),
            (valid + 'target=android-34\ntarget=android-34\n', 'locator-duplicate'),
            (valid + f'path={self.config.parent}\n', 'locator-duplicate'),
        )
        for text, reason in cases:
            with self.subTest(reason=reason, text_index=cases.index((text, reason))):
                locator.write_text(text)
                self.assert_layout_reason(reason)
        locator.write_bytes(b'\xff')
        self.assert_layout_reason('locator-read')

    def test_missing_and_symlink_files_still_reject(self):
        locator = self.config.parent.parent / 'test.ini'
        locator.unlink()
        self.assert_layout_reason('file-read')
        locator.symlink_to(self.config)
        self.assert_layout_reason('file-type')

    def test_snapshot_and_directory_constraints_still_reject(self):
        snapshots = self.config.parent / 'snapshots'
        snapshots.symlink_to(self.root)
        self.assert_layout_reason('snapshot-ancestor-type')
        snapshots.unlink()
        original = self.config.parent
        moved = original.with_name('owned-copy.avd')
        original.rename(moved)
        original.symlink_to(moved, target_is_directory=True)
        self.assert_layout_reason('directory-type')

    def test_shape_and_directory_read_have_fixed_reasons(self):
        original = self.config
        self.config = self.root / 'PRIVATE-DETAIL/test.avd/config.ini'
        self.assert_layout_reason('layout-shape')
        self.config = original
        with patch.object(Path, 'lstat', side_effect=OSError('PRIVATE-DETAIL')):
            self.assert_layout_reason('directory-read')

    def test_normal_sdk_locator_fields_are_not_duplicates(self):
        locator = self.config.parent.parent / 'test.ini'
        locator.write_text('avd.ini.encoding=UTF-8\n' + locator.read_text()
                           + 'target=android-34\n')
        self.assertEqual(self.invoke()[0], 0)


if __name__ == '__main__':
    unittest.main()
