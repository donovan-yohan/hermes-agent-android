"""Creator-only label sampling; consumers bind the complete sealed config.

The display-label key is sourced from SDK revision-12 AvdManager; this fixture
is not evidence of the unknown OTHER mutation in native run 37574259445.
"""
import json
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

    def test_sourced_label_transition_samples_complete_postboot_then_seals(self):
        current = {**self.initial, 'avd.ini.displayname': 'Synthetic final label'}
        self.write_config(current)
        cache.verify(self.receipt, self.identity(), self.avd, mode='miss')
        self.assertEqual(json.loads(self.receipt.read_text())['config'], current)
        self.create_snapshot()
        manifest = json.loads((self.avd.parent / cache.MANIFEST).read_text())
        self.assertEqual(manifest['config'], current)
        self.assertEqual(manifest['config_fingerprint'], cache.config_fingerprint(current))
        cache.verify(self.receipt, self.identity(), self.avd)

    def test_critical_and_unknown_deltas_never_rebind_creator_receipt(self):
        # OTHER is only lexical: disk geometry, image/profile identity,
        # resource constraints and unknown keys are not safe metadata.
        critical = ('hw.ramSize', 'hw.gpu.mode', 'hw.cpu.ncore',
                    'image.sysdir.1', 'disk.dataPartition.size',
                    'disk.dataPartition.path', 'sdcard.size', 'sdcard.path',
                    'vm.heapSize', 'abi.type', 'AvdId', 'tag.id', 'target',
                    'skin.name', 'skin.path', 'snapshot.present',
                    'fastboot.forceColdBoot', 'user.constraint',
                    'unknown.metadata', 'avd.ini.displayname.path')
        before = self.receipt.read_bytes()
        for name in critical:
            with self.subTest(field=name):
                self.write_config({**self.initial, name: 'synthetic changed input',
                                   'avd.ini.displayname': 'Synthetic final label'})
                with self.assertRaises(ValueError):
                    cache.verify(self.receipt, self.identity(), self.avd, mode='miss')
                self.assertEqual(self.receipt.read_bytes(), before)
                self.assertFalse((self.avd.parent / cache.MANIFEST).exists())
        for name in ('hw.ramSize', 'hw.cpu.ncore'):
            with self.subTest(removed=name):
                self.write_config({key: value for key, value in self.initial.items() if key != name})
                with self.assertRaises(ValueError):
                    cache.verify(self.receipt, self.identity(), self.avd, mode='miss')
                self.assertEqual(self.receipt.read_bytes(), before)

    def test_label_addition_and_removal_are_creator_only(self):
        for values in ({key: value for key, value in self.initial.items()
                        if key != 'avd.ini.displayname'}, self.initial):
            self.write_config(values)
            cache.verify(self.receipt, self.identity(), self.avd, mode='miss')
            self.assertEqual(json.loads(self.receipt.read_text())['config'], values)

    def test_even_allowed_metadata_cannot_change_between_sample_and_seal(self):
        self.create_snapshot()
        manifest = self.avd.parent / cache.MANIFEST
        manifest.unlink()
        before = self.receipt.read_bytes()
        self.write_config({**self.initial, 'avd.ini.displayname': 'Unsampled late label'})
        with self.assertRaises(ValueError):
            cache.seal(self.receipt, self.identity(), self.avd)
        self.assertEqual(self.receipt.read_bytes(), before)
        self.assertFalse(manifest.exists())

    def test_seal_reads_complete_config_only_after_quiescence(self):
        self.create_snapshot()
        (self.avd.parent / cache.MANIFEST).unlink()
        observations = []
        original = cache.configuration

        def quiescence():
            observations.append('quiescent')

        def configuration(path):
            observations.append('config')
            return original(path)

        with patch.object(cache, 'creator_quiescence', side_effect=quiescence), \
                patch.object(cache, 'configuration', side_effect=configuration):
            cache.seal(self.receipt, self.identity(), self.avd)
        self.assertEqual(observations, ['quiescent', 'config'])

    def test_sealed_consumers_refuse_label_change_in_both_modes_without_adoption(self):
        self.write_config({**self.initial, 'avd.ini.displayname': 'Synthetic final label'})
        cache.verify(self.receipt, self.identity(), self.avd, mode='miss')
        self.create_snapshot()
        manifest = self.avd.parent / cache.MANIFEST
        sealed = manifest.read_bytes()
        # A fresh run receipt must not adopt a restored config, even in miss mode
        # when a creator manifest exists. Every field is bound, including label.
        cache.record(self.receipt, self.identity())
        before = self.receipt.read_bytes()
        self.write_config({**self.initial, 'avd.ini.displayname': 'Synthetic mutated label'})
        for mode in ('required', 'miss'):
            with self.subTest(mode=mode):
                with self.assertRaises(ValueError):
                    cache.verify(self.receipt, self.identity(), self.avd, mode=mode)
                self.assertEqual(self.receipt.read_bytes(), before)
                self.assertEqual(manifest.read_bytes(), sealed)


if __name__ == '__main__':
    unittest.main()
