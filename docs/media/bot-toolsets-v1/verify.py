#!/usr/bin/env python3
"""Offline packet checks; no device, build, network or third-party dependency."""
from pathlib import Path
import hashlib
import json
import re
import struct
import sys

PACKET = Path(__file__).resolve().parent
REPO = PACKET.parents[2]
sys.path.insert(0, str(REPO / 'scripts'))
from visual_parity_contract import validate_receipt, reject_private


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node['bounds'])))


def inside(child, parent):
    a, b, c, d = bounds(child)
    x, y, z, w = bounds(parent)
    return x <= a < c <= z and y <= b < d <= w


def main():
    manifest = json.loads((PACKET / 'provenance.json').read_text())
    catalog = json.loads((REPO / 'docs/parity/visual-capture-surfaces.json').read_text())['surfaces']['bot-toolsets']
    expected = {(s, t) for s in catalog['states'] for t in ('light', 'dark')}
    seen = set()
    android = desktop = 0
    choices = {
        'defaults': [True, False], 'pinned': [True, False],
        'changed': [True, True], 'all-selected': [True, True],
        'empty-selection': [False, False], 'reset-confirmation': [True, False],
        'saved': [True, True], 'restored': [False, True], 'unconfirmed': [True, True],
    }
    for item in manifest['images']:
        identity = (item['platform'], item['state'], item['theme'])
        assert identity not in seen, identity
        seen.add(identity)
        image = PACKET / item['file']
        assert digest(image) == item['sha256'], image
        assert image.read_bytes()[:8] == b'\x89PNG\r\n\x1a\n'
        assert list(struct.unpack('>II', image.read_bytes()[16:24])) == item['dimensions']
        if item['platform'] == 'android':
            android += 1
            d = json.loads((PACKET / item['receipt']).read_text())
            validate_receipt(d, 'android')
            assert (d['state'], d['theme']) == identity[1:]
            assert d['android_git_sha'] == manifest['android_capture_source']
            assert d['apk_sha256'] == d['installed_apk_sha256'] == manifest['apk_sha256']
            assert d['screenshot_sha256'] == item['sha256']
            assert d['interactions'] == []
            label = catalog['states'][d['state']]['post_interaction_accessibility']
            for phase in ('before', 'after'):
                nodes = d['screenshot_bracket'][phase]['nodes']
                assert label in {n.get(k) for n in nodes for k in ('text', 'content_description')}
                rows = [n for n in nodes if n['checkable']]
                assert [n['checked'] for n in rows] == choices.get(d['state'], [])
                for row, name in zip(rows, ('Web', 'Terminal')):
                    # Compose exports the toggleable row as a checkable View, not CheckBox.
                    assert row['class'] == 'android.view.View'
                    assert row['enabled'] == (d['state'] not in ('reset-confirmation', 'unconfirmed'))
                    assert any(n['text'] == name and inside(n, row) for n in nodes)
                    _, top, _, bottom = bounds(row)
                    density = int(re.findall(r'\d+', d['viewport']['density'])[-1])
                    assert (bottom - top) * 160 / density >= 48
            if d['state'] == 'loading':
                timing = d['screenshot_bracket']['timing']
                assert timing['deadline_seconds'] == 20
                assert 0 <= timing['screenshot_start_seconds'] <= timing['screenshot_end_seconds'] <= timing['postcheck_seconds'] < 20
        else:
            desktop += 1
            d = json.loads((PACKET / item['observation']).read_text())
            reject_private(d)
            assert (d['state'], d['theme']) == identity[1:]
            assert d['normalization'] == dict(skin='mono', mode=d['theme'], locale='en-US', timezone='UTC', clock='2026-09-17T16:00:00.000Z')
            probe = d['probe']
            for event in probe['log']:
                assert event['request']['profile'] == 'synthetic-toolsets'
                assert event['request']['connectionId'] == 'local'
            if d['state'] == 'loading':
                assert probe['pendingReads'] > 0
                assert 'Loading capabilities...' in d['nodes']
            if d['state'] == 'error':
                assert sum(e['phase'] == 'refused' for e in probe['log']) == 4
                assert 'Skills failed to load' in d['text'] and 'Refresh skills' in d['text']
            if d['state'] == 'changed-autosave-pending':
                assert probe['pendingWrites'] == 1
            if d['state'] in ('saved-autosave', 'saved-reopened'):
                assert probe['pendingWrites'] == 0 and set(probe['selected']) == {'web', 'terminal'}
                assert any(e['phase'] == 'committed' for e in probe['log'])
            if d['state'] == 'saved-reopened':
                assert sum(e['phase'] == 'read' for e in probe['log']) >= 2
            try:
                validate_receipt(d, 'desktop')
            except ValueError as error:
                assert str(error) == 'receipt misses required common provenance'
            else:
                raise AssertionError('Observational Desktop evidence was restamped')
    assert {(s, t) for p, s, t in seen if p == 'android'} == expected
    assert (android, desktop) == (24, 14)
    assert len(list(PACKET.rglob('*.png'))) == 38
    for path, expected_hash in manifest['capture_source_files'].items():
        assert digest(REPO / path) == expected_hash, path
    dp = json.loads((PACKET / 'desktop/provenance.json').read_text())
    assert digest(PACKET / 'desktop/toolsets-current-harness.patch') == dp['patch_sha256']
    assert digest(PACKET / 'desktop/toolsets-current-reference.spec.ts') == dp['fixture_sha256']
    for path in PACKET.rglob('*.json'):
        reject_private(json.loads(path.read_text()))
    print('PASS: 24 Android canonical receipts; 14 Desktop observations (canonical rejection preserved); 38 original PNG hashes/dimensions; source manifest; native checkable View rows/48dp; loading/refusal/readback assertions; JSON privacy.')


if __name__ == '__main__':
    main()
