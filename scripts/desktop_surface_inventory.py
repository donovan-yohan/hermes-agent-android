#!/usr/bin/env python3
"""Read-only, pinned Desktop discovery receipts; not a semantic parity verdict."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

PIN = '81a55437f3f31a9f12a30c53b6c5d49d420898aa'
BASE = '65080e8a37e8d546869528e43c162503015b94c7'
ROOTS = ('apps/desktop/src/', 'apps/desktop/electron/')
SIGNALS = re.compile(r'onClick=|onSelect=|onCheckedChange=|onValueChange=|registerCommand\(|register[A-Z]\w*\(|ipcMain\.handle\(|Menu\.buildFromTemplate|<Route\b|path:|id:')

def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True)

def read_sources(upstream, paths):
    """Read immutable blobs in one read-only Git process (not one fork per file)."""
    payload = ''.join(f'{PIN}:{p}\n' for p in paths).encode()
    raw = subprocess.run(['git', '-C', str(upstream), 'cat-file', '--batch'], input=payload,
                         stdout=subprocess.PIPE, check=True).stdout
    result, offset = {}, 0
    for path in paths:
        end = raw.index(b'\n', offset)
        header = raw[offset:end].split()
        if len(header) != 3 or header[1] != b'blob':
            raise ValueError('unreadable source blob: ' + path)
        size = int(header[2])
        offset = end + 1
        if size < 0 or offset + size >= len(raw) or raw[offset + size:offset + size + 1] != b'\n':
            raise ValueError('truncated source blob: ' + path)
        result[path] = raw[offset:offset + size].decode('utf-8')
        offset += size + 1
    if offset != len(raw):
        raise ValueError('unexpected trailing batch output')
    return result


def discover(upstream):
    if git(upstream, 'rev-parse', 'HEAD').strip() != PIN:
        raise ValueError('upstream HEAD does not match inventory pin')
    paths = [p for p in sorted(git(upstream, 'ls-tree', '-r', '--name-only', PIN, '--', *ROOTS).splitlines())
             if p.endswith(('.js', '.ts', '.tsx', '.mjs')) and not re.search(r'\.(test|spec)\.', p)]
    sources = read_sources(upstream, paths)
    result = []
    for path in paths:
        text = sources[path]
        lines = text.splitlines()
        signals = [{'line': n, 'text': line.strip()} for n, line in enumerate(lines, 1) if SIGNALS.search(line)]
        result.append({'path': path, 'lines': len(lines), 'sha256': hashlib.sha256(text.encode()).hexdigest(), 'signals': signals})
    return result

def source_owner(path, rules):
    matches = [r for r in rules if path.startswith(r['prefix'])]
    if not matches:
        return None
    length = max(len(r['prefix']) for r in matches)
    best = {r['family'] for r in matches if len(r['prefix']) == length}
    if len(best) != 1:
        raise ValueError(f'ambiguous source owner: {path}')
    return best.pop()


def source_ledger(upstream, data):
    files = discover(upstream)
    result = []
    for f in files:
        owner = source_owner(f['path'], data['source_ownership'])
        if owner is None:
            raise ValueError('unowned source: ' + f['path'])
        result.append({k: f[k] for k in ('path', 'lines', 'sha256')} | {'family': owner, 'candidate_signal_count': len(f['signals'])})
    return {'upstream_sha': PIN, 'files': result}


def validate(data, repo, upstream=None):
    errors = []
    if data.get('upstream_sha') != PIN or data.get('android_sha') != BASE:
        errors.append('incorrect frozen revisions')
    records = data.get('families', [])
    ids = [r.get('id') for r in records]
    if len(ids) != len(set(ids)):
        errors.append('duplicate family id')
    for r in records:
        for key in ('id', 'controls', 'desktop', 'authority', 'android', 'classification', 'owner', 'parity_pages', 'review_limit'):
            if not r.get(key):
                errors.append(f'{r.get("id")}: missing {key}')
        if r.get('classification') not in ('mixed', 'implemented-bounded', 'omission', 'non-goal', 'drift'):
            errors.append(f'{r.get("id")}: invalid classification')
        for p in r.get('parity_pages', []):
            if not (repo / p).is_file():
                errors.append(f'{r.get("id")}: missing parity page {p}')
        for c in r.get('desktop', []) + r.get('backend_evidence', []) + r.get('source_tests_read', []):
            if not {'path', 'start', 'end'} <= set(c) or not isinstance(c['start'], int) or not isinstance(c['end'], int) or not 0 < c['start'] <= c['end']:
                errors.append(f'{r.get("id")}: malformed citation')
            elif not c['path'].startswith(('apps/desktop/', 'tui_gateway/', 'hermes_cli/')) or '..' in c['path'].split('/'):
                errors.append(f'{r.get("id")}: unsafe source citation')
            elif upstream:
                try:
                    lines = git(upstream, 'show', f'{PIN}:{c["path"]}').splitlines()
                    if c['end'] > len(lines):
                        errors.append(f'{r["id"]}: citation exceeds source')
                except subprocess.CalledProcessError:
                    errors.append(f'{r["id"]}: missing source {c["path"]}')
    for rule in data.get('source_ownership', []):
        if rule.get('family') not in ids:
            errors.append('source rule names unknown family')
        if not rule.get('prefix', '').startswith('apps/desktop/') or '..' in rule['prefix'].split('/'):
            errors.append('unsafe source prefix')
    return errors


def validate_android_citations(data, repo):
    errors = []
    for record in data['families']:
        for c in record.get('android_evidence', []):
            if not c['path'].startswith('app/src/') or '..' in c['path'].split('/') or not 0 < c['start'] <= c['end']:
                errors.append(record['id'] + ': malformed Android citation')
                continue
            try:
                if c['end'] > len(git(repo, 'show', f'{BASE}:{c["path"]}').splitlines()):
                    errors.append(record['id'] + ': Android citation exceeds baseline')
            except subprocess.CalledProcessError:
                errors.append(record['id'] + ': missing Android baseline source')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path)
    parser.add_argument('--inventory', type=Path, default=Path('docs/parity/desktop-surface-audit.json'))
    parser.add_argument('--discover', action='store_true', help='emit full reproducible discovery receipt to stdout')
    parser.add_argument('--snapshot', action='store_true', help='emit the compact family-owned source ledger')
    parser.add_argument('--verify-source-ledger', type=Path)
    parser.add_argument('--require-semantic-complete', action='store_true', help='refuse a bounded inventory; structural success never certifies semantics')
    args = parser.parse_args()
    if args.discover:
        if not args.upstream:
            parser.error('--discover requires --upstream')
        print(json.dumps({'upstream_sha': PIN, 'files': discover(args.upstream)}, indent=2))
        return
    data = json.loads(args.inventory.read_text())
    if args.snapshot or args.verify_source_ledger:
        if not args.upstream:
            parser.error('source ledger operations require --upstream')
        ledger = source_ledger(args.upstream, data)
        if args.snapshot:
            print('{\n  "upstream_sha": ' + json.dumps(PIN) + ',\n  "files": [\n' + ',\n'.join('    ' + json.dumps(f, sort_keys=True) for f in ledger['files']) + '\n  ]\n}')
            return
        expected = json.loads(args.verify_source_ledger.read_text())
        if expected != ledger:
            parser.exit(1, 'source ledger differs from pinned source; review added/removed/modified ownership before regeneration\n')
        print(f'source ledger verified: {len(ledger["files"])} files (not features)')
    errors = validate(data, Path('.'), args.upstream) + validate_android_citations(data, Path('.'))
    if args.require_semantic_complete and data.get('semantic_complete') is not True:
        errors.append('semantic completeness is not certified')
    counts = {}
    for r in data['families']:
        counts[r['classification']] = counts.get(r['classification'], 0) + 1
    print(json.dumps({'families': len(data['families']), 'classifications': counts, 'semantic_complete': data.get('semantic_complete', False), 'errors': errors}, indent=2))
    raise SystemExit(bool(errors))

if __name__ == '__main__':
    main()
