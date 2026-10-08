"""Fresh creators bind final quiescent state; restored consumers stay strict."""
import json
import os
import unittest
from unittest.mock import patch

import test_ci_avd_cache as fixture

cache = fixture.cache


class CreatorTransitionTest(unittest.TestCase):
    identity = fixture.AvdCacheTest.identity
    create_snapshot = fixture.AvdCacheTest.create_snapshot

    def setUp(self):
        fixture.AvdCacheTest.setUp(self)
        self.initial = cache.configuration(self.avd)
        self.initial['avd.ini.displayname'] = 'Synthetic initial label'
        self.write_config(self.initial)
        cache.record(self.receipt, self.identity())
        cache.verify(self.receipt, self.identity(), self.avd, mode='miss')

    def write_config(self, values):
        self.avd.write_text(''.join(f'{name}={value}\n' for name, value in values.items()))

    def test_final_label_transition_seals_without_resampling(self):
        current = {**self.initial, 'avd.ini.displayname': 'Synthetic final label'}
        self.write_config(current)
        self.assertEqual(json.loads(self.receipt.read_text()), {'key': self.identity()})
        self.create_snapshot()
        manifest = json.loads((self.avd.parent / cache.MANIFEST).read_text())
        self.assertEqual(manifest['config'], current)
        self.assertEqual(manifest['config_fingerprint'], cache.config_fingerprint(current))
        cache.verify(self.receipt, self.identity(), self.avd)

    def test_hardware_disk_metadata_path_and_unknown_final_mutations_are_allowed(self):
        self.create_snapshot()
        manifest = self.avd.parent / cache.MANIFEST
        manifest.unlink()
        fields = ('hw.ramSize', 'hw.gpu.mode', 'hw.cpu.ncore',
                    'image.sysdir.1', 'disk.dataPartition.size',
                    'disk.dataPartition.path', 'sdcard.size', 'sdcard.path',
                    'vm.heapSize', 'abi.type', 'AvdId', 'tag.id', 'target',
                    'skin.name', 'skin.path', 'snapshot.present',
                    'fastboot.forceColdBoot', 'user.constraint',
                    'unknown.metadata', 'avd.ini.displayname.path')
        before = self.receipt.read_bytes()
        for name in fields:
            for operation in ('change', 'add', 'remove'):
                with self.subTest(field=name, operation=operation):
                    current = {**self.initial, name: 'synthetic initial'}
                    self.write_config(current)
                    cache.verify(self.receipt, self.identity(), self.avd, mode='miss')
                    if operation == 'remove':
                        del current[name]
                    elif operation == 'add':
                        current[name + '.new'] = 'synthetic final'
                    else:
                        current[name] = 'synthetic final'
                    self.write_config(current)
                    # No postboot sample: seal trusts its own final config.
                    cache.seal(self.receipt, self.identity(), self.avd)
                    sealed = json.loads(manifest.read_text())
                    self.assertEqual(sealed['config'], current)
                    self.assertEqual(sealed['config_fingerprint'], cache.config_fingerprint(current))
                    self.assertEqual(sealed['snapshots'], cache.snapshot_files(self.avd))
                    for mode in ('required', 'miss'):
                        cache.verify(self.receipt, self.identity(), self.avd, mode=mode)
                    manifest.unlink()
                    self.assertEqual(self.receipt.read_bytes(), before)
                    self.assertFalse(manifest.exists())

    def test_fresh_label_addition_and_removal_leave_receipt_key_only(self):
        for values in ({key: value for key, value in self.initial.items()
                        if key != 'avd.ini.displayname'}, self.initial):
            self.write_config(values)
            cache.verify(self.receipt, self.identity(), self.avd, mode='miss')
            self.assertEqual(json.loads(self.receipt.read_text()), {'key': self.identity()})

    def test_final_config_may_change_between_sample_and_seal(self):
        self.create_snapshot()
        manifest = self.avd.parent / cache.MANIFEST
        manifest.unlink()
        before = self.receipt.read_bytes()
        self.write_config({**self.initial, 'avd.ini.displayname': 'Unsampled late label'})
        cache.seal(self.receipt, self.identity(), self.avd)
        self.assertEqual(self.receipt.read_bytes(), before)
        self.assertEqual(json.loads(manifest.read_text())['config'], cache.configuration(self.avd))

    def test_seal_reads_layout_and_complete_config_only_after_quiescence(self):
        self.create_snapshot()
        (self.avd.parent / cache.MANIFEST).unlink()
        observations = []
        original = cache.configuration
        original_layout = cache.avd_layout

        def layout(path):
            observations.append('layout')
            return original_layout(path)

        def quiescence():
            observations.append('quiescent')

        def configuration(path):
            observations.append('config')
            return original(path)

        with patch.object(cache, 'creator_quiescence', side_effect=quiescence), \
                patch.object(cache, 'avd_layout', side_effect=layout), \
                patch.object(cache, 'configuration', side_effect=configuration):
            cache.seal(self.receipt, self.identity(), self.avd)
        self.assertEqual(observations, ['quiescent', 'layout', 'config'])

    def test_cli_seal_computes_installed_identity_after_quiescence(self):
        self.create_snapshot()
        manifest = self.avd.parent / cache.MANIFEST
        manifest.unlink()
        observations = []
        original = cache.identity

        def quiescence():
            observations.append('quiescent')
            (self.sdk / 'emulator/emulator').write_bytes(b'final SDK mutation')

        def identity(*args):
            observations.append('identity')
            return original(*args)

        with patch.dict(os.environ, {'ANDROID_AVD_HOME': str(self.avd.parent.parent),
                                     'AVD_CACHE_HIT': ''}), \
                patch.object(cache, 'WORKFLOW', self.workflow), \
                patch.object(cache, 'RECEIPT', self.receipt), \
                patch.object(cache, 'host_identity', return_value={'arch': 'x86_64', 'cpu': 'fixture'}), \
                patch.object(cache, 'creator_quiescence', side_effect=quiescence), \
                patch.object(cache, 'identity', side_effect=identity):
            self.assertEqual(cache.main(['seal']), 1)
        self.assertEqual(observations, ['quiescent', 'identity'])
        self.assertFalse(manifest.exists())

    def test_sealed_consumers_refuse_all_fields_changed_added_removed_in_both_modes(self):
        self.write_config({**self.initial, 'avd.ini.displayname': 'Synthetic final label'})
        cache.verify(self.receipt, self.identity(), self.avd, mode='miss')
        self.create_snapshot()
        manifest = self.avd.parent / cache.MANIFEST
        # A fresh run receipt must not adopt a restored config, even in miss mode
        # when a creator manifest exists. Every field is bound, including label.
        cache.record(self.receipt, self.identity())
        before = self.receipt.read_bytes()
        baseline = cache.configuration(self.avd)
        for name in (*baseline, 'disk.dataPartition.size', 'image.sysdir.1', 'unknown.metadata'):
            for operation in ('change', 'add', 'remove'):
                current = {**baseline, name: 'synthetic baseline'}
                # Seal each baseline independently so removal of added fields
                # is tested against an actual complete creator manifest.
                manifest.unlink()
                self.write_config(current)
                cache.seal(self.receipt, self.identity(), self.avd)
                sealed = manifest.read_bytes()
                if operation == 'remove':
                    del current[name]
                elif operation == 'add':
                    current[name + '.new'] = 'synthetic mutation'
                else:
                    current[name] = 'synthetic mutation'
                self.write_config(current)
                for mode in ('required', 'miss'):
                    with self.subTest(field=name, operation=operation, mode=mode):
                        with self.assertRaises(ValueError):
                            cache.verify(self.receipt, self.identity(), self.avd, mode=mode)
                        self.assertEqual(self.receipt.read_bytes(), before)
                        self.assertEqual(manifest.read_bytes(), sealed)


if __name__ == '__main__':
    unittest.main()
