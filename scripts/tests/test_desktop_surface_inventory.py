"""Offline regressions for the inventory's evidence boundaries, not parity tests."""
import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'desktop_surface_inventory.py'
spec = importlib.util.spec_from_file_location('inventory', SCRIPT)
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = pathlib.Path(self.temp.name)
        (self.repo / 'owner.md').write_text('owner')
        self.data = {'upstream_sha': inventory.PIN, 'android_sha': inventory.BASE,
                     'semantic_complete': False, 'source_ownership': [], 'families': [{
                         'id': 'one', 'controls': ['actual operation'],
                         'desktop': [{'path': 'apps/desktop/src/one.tsx', 'start': 1, 'end': 2}],
                         'authority': 'backend', 'android': 'omitted', 'classification': 'omission',
                         'owner': '#73', 'parity_pages': ['owner.md'], 'review_limit': 'not rendered'}]}

    def test_valid_bounded_inventory_does_not_become_complete(self):
        self.assertEqual(inventory.validate(self.data, self.repo), [])
        self.assertFalse(self.data['semantic_complete'])

    def test_duplicate_family_rejected(self):
        self.data['families'].append(copy.deepcopy(self.data['families'][0]))
        self.assertIn('duplicate family id', inventory.validate(self.data, self.repo))

    def test_wrong_sha_rejected(self):
        self.data['upstream_sha'] = 'main'
        self.assertIn('incorrect frozen revisions', inventory.validate(self.data, self.repo))

    def test_missing_owner_rejected(self):
        del self.data['families'][0]['owner']
        self.assertIn('one: missing owner', inventory.validate(self.data, self.repo))

    def test_missing_parity_page_rejected(self):
        self.data['families'][0]['parity_pages'] = ['missing.md']
        self.assertTrue(any('missing parity page' in e for e in inventory.validate(self.data, self.repo)))

    def test_unsupported_class_rejected(self):
        self.data['families'][0]['classification'] = 'looks fine'
        self.assertIn('one: invalid classification', inventory.validate(self.data, self.repo))

    def test_citation_bounds_are_verified_against_git_object(self):
        with patch.object(inventory, 'git', return_value='only one line\n') as git:
            self.assertIn('one: citation exceeds source', inventory.validate(self.data, self.repo, self.repo))
            git.assert_called_once_with(self.repo, 'show', inventory.PIN + ':apps/desktop/src/one.tsx')

    def test_source_owner_uses_specific_prefix(self):
        rules = [{'prefix': 'apps/', 'family': 'support'}, {'prefix': 'apps/desktop/src/app/', 'family': 'ui'}]
        self.assertEqual(inventory.source_owner('apps/desktop/src/app/new.tsx', rules), 'ui')
        self.assertIsNone(inventory.source_owner('untracked/new.tsx', rules))

    def test_ambiguous_source_owner_rejected(self):
        rules = [{'prefix': 'apps/', 'family': f} for f in ('a', 'b')]
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            inventory.source_owner('apps/file.ts', rules)

    def test_unowned_discovery_rejected(self):
        with patch.object(inventory, 'discover', return_value=[{'path': 'new.tsx'}]):
            with self.assertRaisesRegex(ValueError, 'unowned source'):
                inventory.source_ledger(self.repo, self.data)

    def test_discovery_includes_plain_js_but_not_tests(self):
        files = ['apps/desktop/src/plugins/radio/plugin.js', 'apps/desktop/src/app/page.tsx',
                 'apps/desktop/src/app/page.test.tsx', 'apps/desktop/src/app/style.css']
        def git(root, *args):
            if args[0] == 'rev-parse': return inventory.PIN
            if args[0] == 'ls-tree': return '\n'.join(files)
            self.fail('unexpected Git call: ' + str(args))
        sources = {p: '<button onClick={go}>Open</button>\n' for p in files[:2]}
        with patch.object(inventory, 'git', side_effect=git), patch.object(inventory, 'read_sources', return_value=sources):
            found = inventory.discover(self.repo)
        self.assertEqual([f['path'] for f in found], sorted(files[:2]))
        self.assertEqual([len(f['signals']) for f in found], [1, 1])

    def test_wrong_discovery_head_rejected_before_enumeration(self):
        with patch.object(inventory, 'git', return_value='other') as git:
            with self.assertRaisesRegex(ValueError, 'HEAD'):
                inventory.discover(self.repo)
            self.assertEqual(git.call_count, 1)

    def test_rule_cannot_name_nonexistent_family(self):
        self.data['source_ownership'] = [{'prefix': 'apps/desktop/src/', 'family': 'unknown'}]
        self.assertIn('source rule names unknown family', inventory.validate(self.data, self.repo))

    def test_batch_reader_preserves_blob_bytes_and_newlines(self):
        from types import SimpleNamespace
        body = 'one\r\nλ\n'.encode()
        raw = b'oid blob ' + str(len(body)).encode() + b'\n' + body + b'\n'
        with patch.object(inventory.subprocess, 'run', return_value=SimpleNamespace(stdout=raw)) as run:
            result = inventory.read_sources(self.repo, ['apps/a.ts'])
        self.assertEqual(result['apps/a.ts'].encode(), body)
        self.assertIn('cat-file', run.call_args.args[0])
        self.assertEqual(run.call_args.kwargs['env']['GIT_NO_LAZY_FETCH'], '1')
        self.assertEqual(run.call_args.kwargs['env']['GIT_ALLOW_PROTOCOL'], '')
        self.assertEqual(run.call_args.kwargs['input'], (inventory.PIN + ':apps/a.ts\n').encode())

    def test_batch_reader_rejects_missing_object(self):
        from types import SimpleNamespace
        with patch.object(inventory.subprocess, 'run', return_value=SimpleNamespace(stdout=b'path missing\n')):
            with self.assertRaisesRegex(ValueError, 'unreadable'):
                inventory.read_sources(self.repo, ['apps/missing.ts'])

    def test_batch_reader_rejects_truncated_blob(self):
        from types import SimpleNamespace
        with patch.object(inventory.subprocess, 'run', return_value=SimpleNamespace(stdout=b'oid blob 99\nshort\n')):
            with self.assertRaisesRegex(ValueError, 'truncated'):
                inventory.read_sources(self.repo, ['apps/a.ts'])

    def test_android_citation_uses_frozen_base_not_worktree(self):
        self.data['families'][0]['android_evidence'] = [{'path': 'app/src/main/File.kt', 'start': 1, 'end': 2}]
        with patch.object(inventory, 'git', return_value='one\n') as git:
            self.assertEqual(inventory.validate_android_citations(self.data, self.repo), ['one: Android citation exceeds baseline'])
            git.assert_called_once_with(self.repo, 'show', inventory.BASE + ':app/src/main/File.kt')

    def test_path_traversal_in_citation_is_rejected(self):
        self.data['families'][0]['desktop'][0]['path'] = 'apps/desktop/../../outside'
        self.assertIn('one: unsafe source citation', inventory.validate(self.data, self.repo))

    def test_snapshot_records_hash_counts_and_owner_not_feature_claim(self):
        self.data['source_ownership'] = [{'prefix': 'apps/', 'family': 'one'}]
        source = {'path': 'apps/a.ts', 'lines': 2, 'sha256': 'digest', 'signals': [{'line': 1}]}
        with patch.object(inventory, 'discover', return_value=[source]):
            result = inventory.source_ledger(self.repo, self.data)
        self.assertEqual(result['files'][0], {'path': 'apps/a.ts', 'lines': 2, 'sha256': 'digest', 'family': 'one', 'candidate_signal_count': 1})
        self.assertNotIn('semantic_complete', result)

    def test_committed_records_have_no_private_paths(self):
        path = SCRIPT.parents[1] / 'docs/parity/desktop-surface-audit.json'
        raw = path.read_text()
        self.assertNotIn('/Users/', raw)
        self.assertNotIn('/home/', raw)
        self.assertEqual(json.loads(raw)['android_sha'], inventory.BASE)


if __name__ == '__main__':
    unittest.main()
