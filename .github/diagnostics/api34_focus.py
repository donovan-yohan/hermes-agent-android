#!/usr/bin/env python3
"""Disposable CI-only focus probe. Never run against a personal device."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ['ANDROID_SERIAL'] == 'emulator-5580'
mode = sys.argv[1]
assert mode in ('standalone', 'predecessor', 'full')
out = Path(os.environ['RUNNER_TEMP']) / 'api34-focus' / mode
out.mkdir(parents=True, exist_ok=True)
adb = ['adb', '-s', 'emulator-5580']

def call(*args):
    return subprocess.check_output(adb + list(args), text=True, timeout=30).strip()

assert call('get-serialno') == 'emulator-5580'
assert call('shell', 'getprop', 'ro.build.version.sdk') == '34'
assert call('shell', 'getprop', 'ro.product.cpu.abi') == 'x86_64'
identity = call('emu', 'avd', 'name')
assert 'focus-' + mode in identity
apks = ['app/build/outputs/apk/debug/app-debug.apk',
        'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
manifest = {'sha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
            'avd': identity, 'api': 34, 'serial': 'emulator-5580',
            'apks': {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in apks}}
(out / 'identity.json').write_text(json.dumps(manifest, indent=2))
for apk in apks:
    call('install', '-r', apk)
installed = call('shell', 'pm', 'list', 'instrumentation')
runners = re.findall(r'^instrumentation:(\S+) \(target=com\.hermesagent\.mobile\.debug\)$', installed, re.M)
assert len(runners) == 1, 'Expected one instrumentation targeting disposable debug package'
runner = runners[0]
assert runner == 'com.hermesagent.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner'
# Same preparation as the existing lane; ANDROID_SERIAL explicitly selects our AVD.
subprocess.run(['bash', 'scripts/prepare-ci-emulator.sh'], check=True, timeout=45)
composer = 'com.hermesagent.mobile.device.ComposerImeTest#theComposerFieldTakesARealInputConnection'
predecessor = 'com.hermesagent.mobile.device.ActivityRecreateTest#theOpenDestinationSurvivesARealActivityRecreate'
full_suite = [predecessor, composer,
    'com.hermesagent.mobile.device.MainActivityBootTest#coldLaunchDoesNotPerformNetworkWorkOnMainThread',
    'com.hermesagent.mobile.device.OrientationTest#rotatingKeepsTheConnectionCopyAndMovesToTheWideLayout',
    'com.hermesagent.mobile.device.PlatformAccessibilityTest#theChromeActionsReachThePlatformAccessibilityTree',
    'com.hermesagent.mobile.device.TouchTargetTest#everyOnScreenActionMeetsTheTouchFloorAtTheDeviceDensity',
    'com.hermesagent.mobile.device.TouchTargetTest#theComposerAndChromeControlsKeepTheirOwnTouchFloor']
expected = {'standalone': [composer], 'predecessor': [predecessor, composer], 'full': full_suite}[mode]
start = threading.Event()
stop = threading.Event()
# Allowlist focus ownership only. Full dumps live only in process memory.
probes = {
    'window': (['window', 'windows'], r'mCurrentFocus=|mFocusedApp=|mTopFocusedDisplayId=|mDisplayId=|^\s*Window #'),
    'input': (['input'], r'FocusedApplications:|FocusedWindows:|FocusedApplication:|FocusedWindow:|focusedWindow=|focusedApplication=|^\s*displayId=|^\s*Display \d+ \[.*(?:Window|Activity|name=)'),
    'activity': (['activity', 'activities'], r'topResumedActivity=|mResumedActivity:|^Display #|^\s*\* Task\{|^\s*RootTask #'),
}

def probe(item):
    name, (args, pattern) = item
    began = time.monotonic()
    try:
        result = subprocess.run(adb + ['shell', 'dumpsys'] + args, text=True,
                                capture_output=True, timeout=2)
        return {'probe': name, 'monotonic': began, 'exit': result.returncode,
                'fields': [s for s in result.stdout.splitlines() if re.search(pattern, s)]}
    except subprocess.TimeoutExpired:
        return {'probe': name, 'monotonic': began, 'error': 'timeout'}

def sample():
    while not start.wait(.1):
        if stop.is_set():
            return
    # Cover unchanged 15s readiness wait; hard cap 40 rounds / 20s wall time.
    deadline = time.monotonic() + 20
    with (out / 'focus.jsonl').open('w') as f, concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for index in range(40):
            if stop.is_set() or time.monotonic() >= deadline:
                break
            for result in pool.map(probe, probes.items()):
                f.write(json.dumps({'sample': index, **result}) + '\n')
                f.flush()
            if stop.wait(.5):
                break

# Bounded intervention on this guarded, dedicated synthetic CI AVD only.
# Do not suppress error dialogs, force-stop the app, retry, or change test waits.
launcher = 'com.google.android.apps.nexuslauncher'
assert call('shell', 'pm', 'path', launcher).startswith('package:')
before = [probe(item) for item in probes.items()]
call('shell', 'am', 'force-stop', '--user', '0', launcher)
after = [probe(item) for item in probes.items()]
(out / 'preparation.json').write_text(json.dumps({
    'action': 'force-stop', 'package': launcher, 'user': 0,
    'before': before, 'after': after}, indent=2))
thread = threading.Thread(target=sample)
thread.start()
cmd = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', ','.join(expected), runner]
process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
timed_out = threading.Event()
def expire():
    timed_out.set()
    process.kill()
timer = threading.Timer(150, expire)
timer.start()
fields = {}
order = []
codes = []
lines = []
with (out / 'instrumentation.txt').open('w') as raw:
    for line in process.stdout:
        raw.write(line)
        raw.flush()
        lines.append(line)
        match = re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
        if match:
            fields[match[1]] = match[2].strip()
        match = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if match:
            code = int(match[1])
            codes.append(code)
            if code == 1:
                test = fields.get('class', '') + '#' + fields.get('test', '')
                order.append(test)
                if test == composer:
                    start.set()
            elif fields.get('class', '').endswith('ComposerImeTest'):
                stop.set()
            fields = {}
rc = process.wait()
timer.cancel()
stop.set()
thread.join(timeout=5)
text = ''.join(lines)
passed = (rc == 0 and not timed_out.is_set() and order == expected
          and codes == [n for _ in expected for n in (1, 0)]
          and 'INSTRUMENTATION_CODE: -1' in text
          and re.search(r'OK \(' + str(len(expected)) + r' tests?\)', text) is not None)
result = {'raw_exit': rc, 'timed_out': timed_out.is_set(), 'status_codes': codes,
          'expected_order': expected, 'actual_order': order, 'order_verified': order == expected,
          'passed': passed}
(out / 'result.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result, indent=2))
sys.exit(rc if rc > 0 else (0 if passed else 1))
