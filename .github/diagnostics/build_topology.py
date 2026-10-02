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
    record = dict(phase=name, **sample())
    path = root / 'topology-phases.jsonl'
    assert not path.exists() or path.stat().st_size < 32000
    with path.open('a') as f:
        f.write(json.dumps(record) + '\n')
    if name == 'post-build':
        (root / 'prebuilt-apks.json').write_text(json.dumps(hashes(), indent=2))
    if name == 'post-boot':
        sdk = Path(os.environ['ANDROID_HOME'])
        props = sdk / 'system-images/android-34/google_apis/x86_64/source.properties'
        revision = {k: v for line in props.read_text().splitlines() if '=' in line
                    for k, v in [line.split('=', 1)] if k.strip() in ('Pkg.Revision', 'AndroidVersion.ApiLevel', 'SystemImage.Abi', 'SystemImage.TagId')}
        check = subprocess.run([str(sdk / 'emulator/emulator'), '-accel-check'], text=True, capture_output=True, timeout=30)
        adb = ['adb', '-s', os.environ['ANDROID_SERIAL']]
        fingerprint = subprocess.check_output(adb + ['shell', 'getprop', 'ro.build.fingerprint'], text=True, timeout=30).strip()
        version = subprocess.check_output([str(sdk / 'emulator/emulator'), '-version'], text=True, timeout=30).splitlines()[0]
        evidence = dict(image_properties=revision, fingerprint=fingerprint, emulator=version,
                        accel_exit=check.returncode, accel_output=(check.stdout + check.stderr)[:2000])
        (root / 'topology-environment.json').write_text(json.dumps(evidence, indent=2))
        assert check.returncode == 0, 'acceleration availability check failed'


if __name__ == '__main__':
    phase(sys.argv[1])
