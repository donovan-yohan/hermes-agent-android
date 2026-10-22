#!/usr/bin/env python3
"""Frozen syntax reconciliation, plus separately reviewed semantic control records.

TypeScript is a tooling dependency, never installed in or imported from upstream.
AST candidates are NOT shipping-control, reachability, or parity assertions.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import desktop_surface_inventory as family

ROOT = Path(__file__).resolve().parents[1]
REGISTRIES = {
    'routes': 'apps/desktop/src/app/routes.ts',
    'sidebar': 'apps/desktop/src/app/chat/sidebar/index.tsx',
    'settings-navigation': 'apps/desktop/src/app/settings/index.tsx',
    'settings-manifest': 'apps/desktop/src/app/settings/settings-manifest.ts',
    'settings-schema': 'apps/desktop/src/app/settings/constants.ts',
    'settings-subpages': 'apps/desktop/src/app/settings/subpages.ts',
    'appearance-subpages': 'apps/desktop/src/app/settings/appearance-subpages.ts',
    'config-subpages': 'apps/desktop/src/app/settings/config-subpages.ts',
    'other-subpages': 'apps/desktop/src/app/settings/other-subpages.ts',
    'statusbar-root': 'apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx',
    'plugin-host': 'apps/desktop/src/contrib/plugin.ts',
    'palette': 'apps/desktop/src/app/command-palette/index.tsx',
    'keybinds': 'apps/desktop/src/lib/keybinds/actions.ts',
    'native-menu': 'apps/desktop/electron/main.ts',
    'native-window-menu': 'apps/desktop/electron/window-menu.ts',
    'contribution-root': 'apps/desktop/src/app/contrib/controller.tsx',
    'plugin-discovery': 'apps/desktop/src/contrib/plugins.ts',
    'plugin-settings': 'apps/desktop/src/contrib/settings-pages.ts',
}

def boundary(path):
    if re.search(r'(?:^|/)(?:__tests__|__mocks__|fixtures|e2e)(?:/|$)|\.(?:test|spec|stories|test-util)\.', path):
        return 'test-or-fixture'
    if re.search(r'(?:^|/)(?:dev-fixtures|simulated-api|test-utils)\.|/dev/', path):
        return 'development-support'
    if '/hello-runtime/' in path:
        return 'runtime-example-not-bundled'
    return 'production-source-candidate'  # NOT a mounted-control claim


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def snapshot(upstream, data):
    if family.git(upstream, 'rev-parse', 'HEAD').strip() != family.PIN:
        raise ValueError('wrong upstream HEAD')
    paths = sorted(p for p in family.git(upstream, 'ls-tree', '-r', '--name-only', family.PIN, '--', *family.ROOTS).splitlines()
                   if p.endswith(('.js', '.ts', '.tsx', '.mjs')))
    boundaries = [{'path': p, 'boundary': boundary(p)} for p in paths]
    production = [p for p in paths if boundary(p) == 'production-source-candidate']
    sources = family.read_sources(upstream, production)
    raw = subprocess.run(['node', str(ROOT / 'scripts/desktop_control_ast.cjs')],
                         input=json.dumps([{'path': p, 'text': sources[p]} for p in production]),
                         text=True, capture_output=True, check=True)
    candidates = []
    for record in json.loads(raw.stdout):
        path = record['path']
        item = {k: record[k] for k in ('kind', 'path', 'start', 'end', 'offset', 'end_offset')}
        item['id'] = f'{path}:{record["offset"]}-{record["end_offset"]}:{record["kind"]}'
        item['family'] = family.source_owner(path, data['source_ownership'])
        if not item['family']:
            raise ValueError('unowned candidate: ' + path)
        lines = sources[path].splitlines(keepends=True)
        item['span_sha256'] = digest(''.join(lines[record['start']-1:record['end']]))
        item['semantic_status'] = 'not-certified'
        if 'properties' in record:
            item['literal_identity'] = {p['name']: p['static_value'] for p in record['properties']
                                        if p['name'] in ('id', 'path', 'area', 'role', 'to', 'keys') and 'static_value' in p}
            item['property_spans'] = [{k: p[k] for k in ('name', 'start', 'end')} for p in record['properties']]
        if 'component' in record:
            item['component'] = record['component']
            item['events'] = [h['event'] for h in record['handlers']]
            item['handler_references'] = sorted({r['name'] for h in record['handlers'] for r in h['references']})
            item['handler_bindings'] = [{'event': h['event'], 'references': h['references']} for h in record['handlers']]
        elif 'callee' in record:
            item['callee'] = record['callee']
        elif 'name' in record:
            item['registry'] = record['name']
        elif 'properties' in record:
            item['properties'] = [p['name'] for p in record['properties']]
        item['context'] = [{k: v for k, v in c.items() if k in ('kind', 'name', 'start', 'end')} for c in record.get('context', [])]
        candidates.append(item)
    # Enumerate plugin roots using the ACTUAL shipping glob, not */plugin.runtime.js.
    plugins = [p for p in production if re.fullmatch(r'apps/desktop/src/plugins/[^/]+/plugin\.(?:js|ts|tsx)', p)]
    registry_rows = []
    for name, path in dict(REGISTRIES, **{f'bundled:{p.split("/")[-2]}': p for p in plugins}).items():
        registry_rows.append({'id': name, 'path': path, 'source_sha256': digest(sources[path]),
                              'candidate_ids': [c['id'] for c in candidates if c['path'] == path],
                              'boundary': 'registry-not-control-count'})
    return {'upstream_sha': family.PIN, 'android_sha': family.BASE, 'semantic_complete': False,
            'method': 'TypeScript 5.9.3 syntax only; local symbols/import origins indexed without evaluation; not a call graph',
            'boundaries': boundaries, 'registries': registry_rows, 'candidates': candidates}


def validate_controls(data, repo, upstream=None):
    errors = []
    if data.get('upstream_sha') != family.PIN or data.get('android_sha') != family.BASE:
        errors.append('wrong revisions')
    if data.get('semantic_complete') is not False:
        errors.append('whole-tree semantic acceptance must remain withheld')
    families = json.loads((repo / 'docs/parity/desktop-surface-audit.json').read_text())
    ids = {f['id'] for f in families['families']}
    seen = set()
    all_citations = []
    for c in data.get('controls', []):
        name = c.get('id', '?')
        if name in seen:
            errors.append('duplicate control: ' + name)
        seen.add(name)
        for key in ('id', 'family', 'group', 'operation', 'mount', 'handler', 'authority', 'android_status', 'android_boundary', 'parity_page', 'issue', 'source'):
            if not c.get(key): errors.append(name + ': missing ' + key)
        if c.get('family') not in ids: errors.append(name + ': unknown family')
        if c.get('android_status') not in ('implemented-bounded', 'WIP', 'omission', 'non-goal', 'drift'):
            errors.append(name + ': invalid Android status')
        if not re.fullmatch(r'#[1-9][0-9]*', c.get('issue', '')):
            errors.append(name + ': invalid issue')
        page = c.get('parity_page', '')
        if not page.startswith('docs/parity/') or '..' in page.split('/') or not (repo / page).is_file():
            errors.append(name + ': invalid parity page')
        for s in c.get('source', []):
            if s.get('sha') != family.PIN or not s.get('path', '').startswith(('apps/desktop/', 'tui_gateway/', 'hermes_cli/')) or '..' in s.get('path', '').split('/'):
                errors.append(name + ': invalid immutable citation')
                continue
            if boundary(s['path']) != 'production-source-candidate':
                errors.append(name + ': nonshipping source claimed as control')
            if type(s.get('start')) is not int or type(s.get('end')) is not int or not 0 < s['start'] <= s['end']:
                errors.append(name + ': malformed span')
            else: all_citations.append((name, s))
    for s in data.get('source_tests_read', []):
        if s.get('sha') != family.PIN or not s.get('path', '').startswith('apps/desktop/') or '..' in s.get('path', '').split('/') or not re.search(r'\.(test|spec)\.', s.get('path', '')):
            errors.append('invalid test-reading citation')
        elif type(s.get('start')) is not int or type(s.get('end')) is not int or not 0 < s['start'] <= s['end']:
            errors.append('malformed test-reading span')
        else:
            all_citations.append(('test-reading', s))
    android_sources = {}
    for c in data.get('controls', []):
        if not c.get('android_evidence'):
            errors.append(c['id'] + ': missing Android evidence')
        for s in c.get('android_evidence', []):
            if s.get('sha') != family.BASE or not s.get('path', '').startswith('app/src/main/') or '..' in s.get('path', '').split('/') or type(s.get('start')) is not int or type(s.get('end')) is not int or not 0 < s['start'] <= s['end']:
                errors.append(c['id'] + ': invalid Android citation')
                continue
            if s['path'] not in android_sources:
                try:
                    android_sources[s['path']] = family.git(repo, 'show', f'{family.BASE}:{s["path"]}').splitlines()
                except subprocess.CalledProcessError:
                    errors.append(c['id'] + ': Android baseline file missing')
                    continue
            if s['end'] > len(android_sources[s['path']]):
                errors.append(c['id'] + ': Android span out of bounds')
    if upstream:
        if family.git(upstream, 'rev-parse', 'HEAD').strip() != family.PIN:
            errors.append('wrong upstream HEAD')
        sources = family.read_sources(upstream, sorted({s['path'] for _, s in all_citations}))
        for name, s in all_citations:
            if s['end'] > len(sources[s['path']].splitlines()): errors.append(name + ': out of bounds')
    return errors


def reconcile(ledger, records, data):
    """Join immutable syntax identities to reviewed UI spans, without upgrading evidence."""
    owners = {f['id']: f for f in data['families']}
    candidates = {c['id']: c for c in ledger['candidates']}
    roots = []
    for root in ledger['registries']:
        entries = []
        for cid in root['candidate_ids']:
            c = candidates[cid]
            owner = owners[c['family']]
            matches = [r['id'] for r in records['controls'] if any(
                s['path'] == c['path'] and s['start'] <= c['start'] and c['end'] <= s['end']
                for s in r['source'][:1])]
            entries.append({'candidate_id': cid, 'kind': c['kind'], 'start': c['start'], 'end': c['end'],
                            'literal_identity': c.get('literal_identity', {}), 'registry': c.get('registry'),
                            'family': c['family'], 'parity_pages': owner['parity_pages'], 'issue_owner': owner['owner'],
                            'overlapping_reviewed_ui_records': matches,
                            'status': 'syntax-only-requires-reachability-and-handler-review'})
        roots.append({'id': root['id'], 'path': root['path'], 'source_sha256': root['source_sha256'], 'entries': entries})
    return {'upstream_sha': family.PIN, 'android_sha': family.BASE, 'semantic_complete': False,
            'meaning': 'All configured registry roots reconciled syntactically; overlap is a navigation aid, never semantic acceptance. Repeated entrypoints are not distinct features.',
            'registries': roots}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--upstream', type=Path)
    p.add_argument('--snapshot', action='store_true')
    p.add_argument('--verify-ast', type=Path)
    p.add_argument('--reconcile', action='store_true')
    p.add_argument('--verify-reconciliation', type=Path)
    p.add_argument('--controls', type=Path, default=ROOT / 'docs/parity/desktop-control-records.json')
    args = p.parse_args()
    data = json.loads((ROOT / 'docs/parity/desktop-surface-audit.json').read_text())
    if args.snapshot or args.verify_ast:
        if not args.upstream: p.error('AST operations require --upstream')
        found = snapshot(args.upstream, data)
        if args.snapshot:
            # One bounded record per line keeps a source-candidate diff reviewable.
            parts = []
            for key, value in found.items():
                if isinstance(value, list):
                    parts.append('  ' + json.dumps(key) + ': [\n' + ',\n'.join('    ' + json.dumps(v, separators=(',', ':')) for v in value) + '\n  ]')
                else:
                    parts.append('  ' + json.dumps(key) + ': ' + json.dumps(value))
            print('{\n' + ',\n'.join(parts) + '\n}')
            return
        if found != json.loads(args.verify_ast.read_text()): p.exit(1, 'AST ledger mismatch\n')
        print(f'AST ledger verified: {len(found["candidates"])} syntax candidates; {len(found["registries"])} registry roots; not features')
    controls = json.loads(args.controls.read_text())
    if args.reconcile or args.verify_reconciliation:
        ledger = json.loads((ROOT / 'docs/parity/desktop-control-candidates.json').read_text())
        joined = reconcile(ledger, controls, data)
        if args.reconcile:
            print(json.dumps(joined, indent=2))
            return
        if joined != json.loads(args.verify_reconciliation.read_text()):
            p.exit(1, 'registry reconciliation mismatch\n')
        print(f'registry reconciliation verified: {len(joined["registries"])} roots, semantic acceptance withheld')
    errors = validate_controls(controls, ROOT, args.upstream)
    print(json.dumps({'reviewed_control_records': len(controls['controls']), 'semantic_complete': False, 'errors': errors}, indent=2))
    raise SystemExit(bool(errors))

if __name__ == '__main__':
    main()
