#!/usr/bin/env python3
"""Disposable synthetic CI: observe one unchanged connected full-suite invocation."""
import secrets
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from collections import Counter

EXPECTED = [
    'ActivityRecreateTest#theOpenDestinationSurvivesARealActivityRecreate',
    'ComposerImeTest#theComposerFieldTakesARealInputConnection',
    'MainActivityBootTest#coldLaunchDoesNotPerformNetworkWorkOnMainThread',
    'OrientationTest#rotatingKeepsTheConnectionCopyAndMovesToTheWideLayout',
    'PlatformAccessibilityTest#theChromeActionsReachThePlatformAccessibilityTree',
    'TouchTargetTest#everyOnScreenActionMeetsTheTouchFloorAtTheDeviceDensity',
    'TouchTargetTest#theComposerAndChromeControlsKeepTheirOwnTouchFloor',
]


SYNTHETIC = 'SyntheticFocusDenialTest#deliberateDenialCapturesOwnerBeforeTeardown'


def validate_xml(paths, expected=None):
    cases = []
    for path in paths:
        for case in ET.parse(path).getroot().iter('testcase'):
            cases.append({'id': case.attrib['classname'].removeprefix('com.hermesagent.mobile.device.') + '#' + case.attrib['name'],
                          'failures': [(x.text or '') for x in case if x.tag in ('failure', 'error')],
                          'skipped': any(x.tag == 'skipped' for x in case)})
    exact = Counter(c['id'] for c in cases) == Counter(EXPECTED if expected is None else expected)
    return {'cases': cases, 'exact_identity_multiset': exact,
            'passed': exact and all(not c['failures'] and not c['skipped'] for c in cases)}


def main():
    synthetic = os.environ.get('FOCUS_EXPERIMENT') == 'synthetic-collector-only'
    expected = [SYNTHETIC] if synthetic else EXPECTED
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert os.environ.get('FOCUS_SYNTHETIC_OPT_IN') == 'b291-failure-only'
    assert os.environ['ANDROID_SERIAL'] == 'emulator-5554'
    adb = ['adb', '-s', 'emulator-5554']
    def call(*args):
        return subprocess.check_output(adb + list(args), text=True, timeout=30).strip()
    assert call('get-serialno') == 'emulator-5554'
    assert call('shell', 'getprop', 'ro.build.version.sdk') == '34'
    assert call('shell', 'getprop', 'ro.product.cpu.abi') == 'x86_64'
    identity = call('emu', 'avd', 'name')
    assert identity.splitlines()[0] == 'test'
    out = Path(os.environ['RUNNER_TEMP']) / 'api34-focus' / 'full'
    out.mkdir(parents=True, exist_ok=False)
    apks = ['app/build/outputs/apk/debug/app-debug.apk',
            'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    def hashes():
        return {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in apks}
    manifest = {'sha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'avd': identity, 'api': 34, 'serial': 'emulator-5554', 'apks': hashes(),
                'monotonic': time.monotonic(), 'epoch': time.time(),
                'command': ['./gradlew', ':app:connectedDebugAndroidTest', '--no-daemon', '--no-build-cache']}
    (out / 'identity.json').write_text(json.dumps(manifest, indent=2))
    nonce = secrets.token_hex(16)
    call('shell', 'setprop', 'debug.hermes.focus_nonce', nonce)
    manifest['command'] += [
        '-Pandroid.testInstrumentationRunnerArguments.focusSnapshotNonce=' + nonce,
        '-Pandroid.testInstrumentationRunnerArguments.focusSnapshotSerial=emulator-5554',
    ]
    if synthetic:
        manifest['command'] += [
            '-Pandroid.testInstrumentationRunnerArguments.class=com.hermesagent.mobile.device.' + SYNTHETIC,
            '-Pandroid.testInstrumentationRunnerArguments.syntheticFocusDenial=PR344_ONLY',
        ]
        manifest['classification'] = 'DELIBERATE_FAILURE_COLLECTOR_VALIDATION_NOT_RECURRENCE'
    (out / 'identity.json').write_text(json.dumps(manifest, indent=2))
    current: dict[str, str | None] = {'test': None}
    logcat = subprocess.Popen(adb + ['logcat', '-v', 'epoch', '-T', '1', 'TestRunner:I', 'FocusSnapshot:I', '*:S'],
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    assert logcat.stdout is not None
    def events():
        with (out / 'test-events.jsonl').open('w') as f:
            for line in logcat.stdout:
                if 'FocusSnapshot:' in line:
                    with (out / 'focus.jsonl').open('a') as focus:
                        focus.write(json.dumps({'epoch': time.time(), 'line': line.strip()}) + '\n')
                    continue
                match = re.search(r'TestRunner: (started|finished|failed): (\w+)\((com\.hermesagent\.mobile\.device\.\w+)\)', line)
                if not match:
                    continue
                status, method, cls = match.groups()
                test = cls + '#' + method
                if status == 'started':
                    current['test'] = test
                f.write(json.dumps({'monotonic': time.monotonic(), 'epoch': time.time(),
                                    'status': status, 'test': test, 'line': line.strip()}) + '\n')
                f.flush()
                if status == 'finished':
                    current['test'] = None
    observer = threading.Thread(target=events)
    observer.start()
    timed_out = False
    rc = 1
    try:
        with (out / 'connected.txt').open('w') as raw:
            proc = subprocess.Popen(manifest['command'], stdout=raw, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                rc = proc.wait(timeout=300)
            except subprocess.TimeoutExpired:
                import signal
                timed_out = True
                os.killpg(proc.pid, signal.SIGKILL)
                rc = proc.wait()
    finally:
        logcat.terminate()
        try:
            logcat.wait(timeout=5)
        except subprocess.TimeoutExpired:
            logcat.kill()
            logcat.wait()
        observer.join(timeout=5)
    result = validate_xml(Path('app/build/outputs/androidTest-results/connected').glob('**/TEST-*.xml'), expected)
    events_seen = [json.loads(line) for line in (out / 'test-events.jsonl').read_text().splitlines()]
    expected_events = Counter('com.hermesagent.mobile.device.' + x for x in expected)
    result['exact_start_finish'] = all(
        Counter(e['test'] for e in events_seen if e['status'] == status) == expected_events
        for status in ('started', 'finished'))
    result['failure_events'] = [e['test'] for e in events_seen if e['status'] == 'failed']
    result['passed'] = result['passed'] and result['exact_start_finish'] and not result['failure_events']
    result.update(raw_exit=rc, timed_out=timed_out, apks_after=hashes())
    result['same_apks'] = manifest['apks'] == result['apks_after']
    result['passed'] = result['passed'] and rc == 0 and not timed_out and result['same_apks']
    if synthetic:
        from synthetic_collector import verify
        focus_path = out / 'focus.jsonl'
        focus_lines = [json.loads(line)['line'] for line in focus_path.read_text().splitlines()] if focus_path.exists() else []
        result['classification'] = 'DELIBERATE_FAILURE_COLLECTOR_VALIDATION_NOT_RECURRENCE'
        result['collector_verified'] = verify(result, focus_lines)
    (out / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    return rc if rc > 0 else (0 if result['passed'] else 1)


if __name__ == '__main__':
    sys.exit(main())
