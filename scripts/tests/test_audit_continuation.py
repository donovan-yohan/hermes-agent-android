"""Evidence integrity, not a semantic-completeness or runtime parity certificate."""
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AuditContinuationTest(unittest.TestCase):
    def load(self):
        return json.loads((ROOT / 'docs/parity/desktop-audit-continuation.json').read_text())

    def test_historical_inventory_is_preserved(self):
        raw = (ROOT / 'docs/parity/desktop-control-records.json').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),
                         '111e413a49b0e413b13bcb2fa741bf37e308a4019f8cb5d9adc79e07992b1dbe')

    def test_all_families_have_explicit_remaining_acceptance_owners(self):
        data = self.load()
        families = json.loads((ROOT / 'docs/parity/desktop-surface-audit.json').read_text())
        expected = {f['id'] for f in families['families']}
        actual = [f for c in data['remaining_categories'] for f in c['families']]
        self.assertEqual(set(actual), expected)
        self.assertEqual(len(actual), len(set(actual)))
        self.assertFalse(data['semantic_complete'])
        self.assertEqual(data['coverage_claim'], 'finite ownership reconciliation, not exhaustive control certification')
        for category in data['remaining_categories']:
            self.assertIn(category['classification'], ('review-debt', 'implementation-gap', 'mixed'))
            self.assertRegex(category['issue_owner'], r'^#[1-9][0-9]*$')
            for field in ('remaining', 'acceptance', 'evidence'):
                self.assertTrue(category[field])

    def test_new_controls_do_not_promote_definitions_to_implementation(self):
        data = self.load()
        rows = data['controls']
        self.assertEqual(len(rows), len({r['id'] for r in rows}))
        self.assertGreaterEqual(len(rows), 10)
        for row in rows:
            for field in ('mount', 'handler', 'authority', 'android_boundary', 'source'):
                self.assertTrue(row[field])
            self.assertIn(row['classification'], ('omission', 'drift', 'non-goal'))
            self.assertRegex(row['issue_owner'], r'^#[1-9][0-9]*$')
            for cite in row['source']:
                self.assertEqual(cite['sha'], data['upstream_sha'])
                self.assertGreater(cite['start'], 0)
                self.assertGreaterEqual(cite['end'], cite['start'])
                self.assertNotIn('..', cite['path'].split('/'))
        by_id = {r['id']: r for r in rows}
        self.assertIn('Electron', by_id['catalog.probe']['authority'])
        self.assertIn('120', by_id['catalog.install']['authority'])
        self.assertIn('uncertain', by_id['catalog.install']['handler'])
        self.assertIn('not', by_id['catalog.tier']['authority'])

    def test_current_target_and_contract_delta_are_explicit(self):
        data = self.load()
        self.assertEqual(data['upstream_sha'], '92663ae8054ae81f207f46d83512d3f879dc698d')
        self.assertEqual(data['android_sha'], '65080e8a37e8d546869528e43c162503015b94c7')
        self.assertEqual(len(data['changed_paths']), 27)
        self.assertEqual(data['contract_assessment'], 'additive catalog tier; no demonstrated existing-client wire repair')
        ledger = (ROOT / 'app/src/test/kotlin/com/hermesagent/mobile/ui/theme/DesktopThemeLedger.kt').read_text()
        self.assertIn('PINNED_SHA = "' + data['upstream_sha'] + '"', ledger)
        self.assertNotIn('/Users/', json.dumps(data))


if __name__ == '__main__':
    unittest.main()
