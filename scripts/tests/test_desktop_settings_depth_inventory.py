"""Regression scaffolding for a bounded hand-read increment, not semantic proof."""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
START = '64bd45c887569d0668d8553ba428bae3539066b2'
PIN = '81a55437f3f31a9f12a30c53b6c5d49d420898aa'
BASE = '65080e8a37e8d546869528e43c162503015b94c7'
RECORDS = 'docs/parity/desktop-control-records.json'


def serialized_controls(text):
    decoder = json.JSONDecoder()
    pos = text.index('"controls": [') + len('"controls": [')
    objects = []
    while True:
        while text[pos].isspace() or text[pos] == ',':
            pos += 1
        if text[pos] == ']':
            return objects
        _, end = decoder.raw_decode(text, pos)
        objects.append(text[pos:end].encode())
        pos = end


class SettingsDepthInventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = (ROOT / RECORDS).read_text()
        cls.data = json.loads(cls.raw)
        cls.rows = {r['id']: r for r in cls.data['controls']}
        cls.new = cls.data['controls'][295:]

    def test_original_295_serialized_objects_and_ids_are_unchanged(self):
        original = subprocess.check_output(
            ['git', '-C', str(ROOT), 'show', f'{START}:{RECORDS}'], text=True)
        self.assertEqual(len(serialized_controls(original)), 295)
        self.assertEqual(serialized_controls(self.raw)[:295], serialized_controls(original))
        self.assertEqual(self.data['controls'][:295], json.loads(original)['controls'])

    def test_exact_bounded_count_and_no_whole_tree_promotion(self):
        self.assertEqual(len(self.new), 80)
        self.assertEqual(len(self.data['controls']), 375)
        self.assertEqual(len(self.rows), 375)
        self.assertFalse(self.data['semantic_complete'])
        self.assertEqual(self.data['upstream_sha'], PIN)
        self.assertEqual(self.data['android_sha'], BASE)
        for row in self.new:
            self.assertTrue(row['id'].startswith('settings-depth.'))
            self.assertTrue(row['lifecycle'])
            self.assertIn('source read', row['review'])
            self.assertTrue(all(c['sha'] == PIN for c in row['source']))
            self.assertTrue(all(c['sha'] == BASE for c in row['android_evidence']))

    def test_syntax_and_source_ownership_snapshots_remain_byte_identical(self):
        for path in ('docs/parity/desktop-control-candidates.json',
                     'docs/parity/desktop-source-ownership.json'):
            original = subprocess.check_output(['git', '-C', str(ROOT), 'show', f'{START}:{path}'])
            self.assertEqual((ROOT / path).read_bytes(), original)
        ledger = json.loads((ROOT / 'docs/parity/desktop-control-candidates.json').read_text())
        self.assertEqual(len(ledger['candidates']), 3283)
        self.assertEqual(len(ledger['registries']), 21)
        self.assertEqual(hashlib.sha256((ROOT / 'docs/parity/desktop-control-candidates.json').read_bytes()).hexdigest(),
                         '3576657d662098e0a2c87b2997871876f7bb1d2539487419c8f13caf0bd1fe81')

    def test_specialized_authority_does_not_collapse_into_dispatcher(self):
        r = lambda name: self.rows['settings-depth.' + name]
        for name in ('appearance.mode', 'appearance.skin'):
            self.assertEqual(r(name)['android_status'], 'drift')
            self.assertEqual(r(name)['issue'], '#266')
        self.assertEqual(r('appearance.intro')['android_status'], 'implemented-bounded')
        for name in ('appearance.reactions', 'appearance.tips', 'appearance.tours'):
            self.assertIn('config.set', r(name)['authority'])
            self.assertIn('swallowed', r(name)['authority'])
        self.assertIn('session reveal', r('appearance.files')['handler'])
        self.assertIn('modeBound', r('appearance.reasoning-collapse')['handler'])
        self.assertIn('550ms', r('appearance.chat-font-field')['authority'])
        self.assertIn('not claimed aborted', r('appearance.terminal-font-reset')['lifecycle'])

    def test_endpoint_race_and_native_capability_limits_stay_explicit(self):
        r = lambda name: self.rows['settings-depth.' + name]
        self.assertIn('saving does not disable Test', r('endpoint.test')['mount'])
        self.assertIn('testing does not disable Save', r('endpoint.save')['mount'])
        self.assertIn('No request/form-version guard', r('endpoint.test')['lifecycle'])
        self.assertIn('direct-config', r('endpoint.delete')['mount'])
        self.assertIn('not credential removal', r('endpoint.key')['handler'])
        self.assertIn('profiles.configure', r('model.apply')['android_boundary'])
        self.assertIn('not completed account setup', r('model.setup-handoff')['handler'])
        self.assertIn('not all epoch guarded', r('model.activate-key')['lifecycle'])
        self.assertIn('No explicit !result.ok', r('model.reasoning-default')['authority'])
        self.assertFalse(any('config-field.tsx' in c['path'] for row in self.new for c in row['source']))


if __name__ == '__main__':
    unittest.main()
