"""Inventory evidence tests: syntax extraction is deliberately not semantic proof."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('controls', ROOT / 'scripts/desktop_control_inventory.py')
import sys
sys.path.insert(0, str(ROOT / 'scripts'))
controls = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controls)

class ControlInventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            result = subprocess.run(['node', str(ROOT / 'scripts/desktop_control_ast.cjs'), '--version'], text=True, capture_output=True)
            cls.has_parser = result.returncode == 0 and result.stdout.strip() == '5.9.3'
        except FileNotFoundError:
            cls.has_parser = False

    def test_boundaries_exclude_examples_tests_and_development(self):
        for path in ('src/panel.test.tsx', 'src/fixtures/demo.tsx', 'src/billing/dev-fixtures.ts', 'src/test-utils.ts', 'src/hello-runtime/plugin.runtime.js'):
            self.assertNotEqual(controls.boundary(path), 'production-source-candidate', path)
        self.assertEqual(controls.boundary('src/plugins/radio/plugin.js'), 'production-source-candidate')

    def ast(self, text):
        if not self.has_parser:
            self.skipTest('AST fixtures require Node + TypeScript 5.9.3 through NODE_PATH; committed-record tests still run')
        return json.loads(subprocess.run(['node', str(ROOT / 'scripts/desktop_control_ast.cjs')],
            input=json.dumps([{'path': 'apps/desktop/src/test-page.tsx', 'text': text}]),
            text=True, capture_output=True, check=True).stdout)

    def test_nested_multiline_callbacks_and_collection_conditions(self):
        rows = self.ast('import {save} from "./api"; const go = () => save(); const UI = () => enabled && rows.map(row => <Button\n onClick={() => go()} disabled={busy}>Save</Button>);')
        button = next(r for r in rows if r['kind'] == 'jsx-control-candidate')
        self.assertEqual(button['start'], 1)
        self.assertEqual(button['end'], 2)
        self.assertEqual(button['handlers'][0]['references'][0]['name'], 'go')
        self.assertTrue(any(c['kind'] == 'collection-template' for c in button['context']))
        self.assertTrue(any(c['kind'] == 'short-circuit' for c in button['context']))

    def test_comments_and_strings_are_not_controls(self):
        self.assertEqual(self.ast('// <Button onClick={fake}/>\nconst text = "onClick={fake}";'), [])

    def test_native_roles_and_palette_run_objects_are_enumerated(self):
        rows = self.ast('const menu = [{role:"copy"}, {id:"save", run:()=>save()}];')
        self.assertEqual(len([r for r in rows if r['kind'] == 'action-object-candidate']), 2)

    def test_compiled_jsx_and_shorthand_handlers_are_not_lost(self):
        rows = self.ast('const onChange = value => save(value); jsx(SearchField, {onChange}); jsxs(Button, {onClick: () => play()});')
        factory = [r for r in rows if r['kind'] == 'factory-control-candidate']
        self.assertEqual(len(factory), 2)
        self.assertEqual(factory[0]['handlers'][0]['event'], 'onChange')
        self.assertEqual(factory[0]['handlers'][0]['references'][0]['name'], 'onChange')

    def test_menu_object_does_not_need_id(self):
        rows = self.ast('const items = [{label:"Delete", onSelect: () => remove()}];')
        self.assertEqual(len([r for r in rows if r['kind'] == 'action-object-candidate']), 1)

    def test_parser_failure_is_not_an_empty_inventory(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.ast('const broken = <Button onClick={;')

    def test_static_route_paths_and_schema_keys_are_preserved(self):
        rows = self.ast('const ROOT = "/settings"; const APP_ROUTES = [{id:"settings",path:ROOT}]; const SECTIONS = [{id:"voice",keys:["tts.provider","stt.provider"]}];')
        props = [r['properties'] for r in rows if r['kind'] == 'action-object-candidate']
        self.assertEqual(next(p['static_value'] for p in props[0] if p['name']=='path'), '/settings')
        self.assertEqual(next(p['static_value'] for p in props[1] if p['name']=='keys'), ['tts.provider','stt.provider'])

    def test_manifest_spread_is_not_silently_lost(self):
        rows = self.ast('const SETTINGS_MANIFEST = {notifications:{enableAll:{copy:t=>t}, ...Object.fromEntries(kinds.map(k=>[k, {}]))}} as const;')
        names = [r.get('name') for r in rows]
        self.assertIn('SETTINGS_MANIFEST.notifications.enableAll', names)
        self.assertEqual(len([r for r in rows if r['kind'] == 'registry-dynamic-spread']), 1)

    def test_callback_parameter_is_not_certified_as_authority(self):
        rows = self.ast('function UI({onClick}) { return <button onClick={onClick}/> }')
        row = next(r for r in rows if r['kind'] == 'jsx-control-candidate')
        self.assertEqual(row['handlers'][0]['references'], [])
        self.assertNotIn('semantic_complete', row)

    def test_committed_record_bounds_and_owners(self):
        data = json.loads((ROOT / 'docs/parity/desktop-control-records.json').read_text())
        self.assertEqual(controls.validate_controls(data, ROOT), [])
        self.assertFalse(data['semantic_complete'])

    def test_reject_complete_claim_duplicate_unsafe_and_nonshipping(self):
        data = json.loads((ROOT / 'docs/parity/desktop-control-records.json').read_text())
        data['semantic_complete'] = True
        data['controls'].append(copy.deepcopy(data['controls'][0]))
        data['controls'][0]['source'][0]['path'] = 'apps/desktop/src/a.test.tsx'
        data['controls'][1]['source'][0]['path'] = 'apps/desktop/../../escape'
        errors = controls.validate_controls(data, ROOT)
        self.assertTrue(any('withheld' in e for e in errors))
        self.assertTrue(any('duplicate' in e for e in errors))
        self.assertTrue(any('nonshipping' in e for e in errors))
        self.assertTrue(any('immutable' in e for e in errors))

    def test_candidates_are_not_reviewed_control_claims(self):
        data = json.loads((ROOT / 'docs/parity/desktop-control-candidates.json').read_text())
        self.assertFalse(data['semantic_complete'])
        ids = [r['id'] for r in data['candidates']]
        self.assertEqual(len(ids), len(set(ids)))
        for c in data['candidates']:
            self.assertEqual(c['semantic_status'], 'not-certified')
            self.assertEqual(controls.boundary(c['path']), 'production-source-candidate')
        self.assertEqual({r['id'] for r in data['registries'] if not r['id'].startswith('bundled:')}, set(controls.REGISTRIES))
        plugin_roots = {r['path'] for r in data['registries'] if r['id'].startswith('bundled:')}
        self.assertTrue(any(p.endswith('/radio/plugin.js') for p in plugin_roots))
        self.assertFalse(any('hello-runtime' in p for p in plugin_roots))

if __name__ == '__main__':
    unittest.main()
