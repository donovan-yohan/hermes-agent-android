#!/usr/bin/env python3
"""Bounded, explicitly synthetic phase evidence for the one-shot topology trial."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from systemui_anr import allowed
from systemui_host import sample

APKS = ['app/build/outputs/apk/debug/app-debug.apk',
        'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']


def hashes():
    return {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in APKS}


def phase(name):
    assert allowed(), 'synthetic binding rejected'
    assert name in ('pre-build', 'post-build', 'pre-emulator', 'post-boot', 'pre-connected', 'post-connected')
    root = Path(os.environ['RUNNER_TEMP'])
    try:
        record = dict(phase=name, **sample())
        path = root / 'topology-phases.jsonl'
        assert not path.exists() or path.stat().st_size < 32000
        with path.open('a') as f:
            f.write(json.dumps(record) + '\n')
    except Exception as exc:
        print('optional phase telemetry unavailable: ' + type(exc).__name__, file=sys.stderr)
    if name == 'post-build':
        (root / 'prebuilt-apks.json').write_text(json.dumps(hashes(), indent=2))
    if name == 'post-boot':
        sdk = Path(os.environ['ANDROID_HOME'])
        evidence = {}
        def probe(field, action):
            try:
                evidence[field] = {'status': 'ok', 'value': action()}
            except Exception as exc:
                evidence[field] = {'status': 'unavailable', 'error_type': type(exc).__name__}
                if isinstance(exc, subprocess.CalledProcessError):
                    evidence[field].update(exit=exc.returncode, output=(exc.stderr or '')[:2000])
            # Persist before the next probe; storage failure must not skip probes/tests.
            try:
                (root / 'topology-environment.json').write_text(json.dumps(evidence, indent=2))
            except Exception as exc:
                print('optional environment persistence unavailable: ' + type(exc).__name__, file=sys.stderr)
        def properties(relative, keys):
            path = sdk / relative
            values = {k.strip(): v.strip() for line in path.read_text().splitlines() if '=' in line
                      for k, v in [line.split('=', 1)] if k.strip() in keys}
            if not all(key in values for key in keys):
                raise ValueError('required package properties missing')
            return {'source': relative, 'properties': values}
        probe('image_properties', lambda: properties(
            'system-images/android-34/google_apis/x86_64/source.properties',
            ('Pkg.Revision', 'AndroidVersion.ApiLevel', 'SystemImage.Abi', 'SystemImage.TagId')))
        probe('emulator', lambda: properties('emulator/source.properties', ('Pkg.Revision',)))
        def acceleration():
            check = subprocess.run([str(sdk / 'emulator/emulator'), '-accel-check'],
                                   text=True, capture_output=True, timeout=30, check=True)
            return {'exit': check.returncode, 'output': (check.stdout + check.stderr)[:2000]}
        probe('acceleration', acceleration)
        probe('fingerprint', lambda: subprocess.check_output(
            ['adb', '-s', os.environ['ANDROID_SERIAL'], 'shell', 'getprop', 'ro.build.fingerprint'],
            text=True, stderr=subprocess.PIPE, timeout=30).strip()[:2000])


if __name__ == '__main__':
    phase(sys.argv[1])
