#!/usr/bin/env python3
"""CI-only identity check for the two APKs assembled before emulator boot."""
import hashlib
import json
import argparse
import subprocess
import re
import focus_diagnostic
from pathlib import Path

APKS = ('app/build/outputs/apk/debug/app-debug.apk',
        'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
MANIFEST = 'app/build/ci-prebuilt.json'


def identity(root):
    hashes = {}
    for name in APKS:
        apk = root / name
        metadata = apk.with_name('output-metadata.json')
        elements = json.loads(metadata.read_text())['elements']
        if (len(elements) != 1 or elements[0]['outputFile'] != apk.name
                or elements[0].get('filters') != []):
            raise ValueError('prebuilt APK metadata mismatch')
        for path in (apk, metadata):
            hashes[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashes


def record(root):
    (root / MANIFEST).write_text(json.dumps(identity(root), sort_keys=True) + '\n')


def verify(root):
    if json.loads((root / MANIFEST).read_text()) != identity(root):
        raise ValueError('prebuilt APK identity mismatch')


def verify_outcomes(output):
    # Gradle 9.1 plain/lifecycle emits explicit outcomes even with configuration
    # cache. Bare headers mean execution, NOT reuse. No graph/cache callbacks.
    tasks = re.findall(r'^> Task (:\S+?)(?: (.*))?$', output, re.M)
    required = {':app:' + name for name in (
        'compileDebugKotlin', 'compileDebugAndroidTestKotlin',
        'packageDebug', 'packageDebugAndroidTest')}
    reused = {name for name, outcome in tasks if outcome in ('UP-TO-DATE', 'FROM-CACHE')}
    if not required <= reused or (':app:connectedDebugAndroidTest', '') not in tasks:
        raise ValueError('missing explicit APK producer reuse or connected task evidence')
    for name, outcome in tasks:
        if name != ':app:connectedDebugAndroidTest' and outcome not in (
                'UP-TO-DATE', 'FROM-CACHE', 'NO-SOURCE', 'SKIPPED'):
            raise ValueError(f'connected stage executed build task: {name} {outcome}')


def run(root):
    # Keep the normal graph: AGP mapped providers require completed producers,
    # including UP-TO-DATE tasks, not exclusions or disabled actions.
    command = ['./gradlew', ':app:connectedDebugAndroidTest', '--no-daemon',
               '--no-build-cache', '--console=plain']
    verify(root)
    nonce = focus_diagnostic.arm()
    command += focus_diagnostic.arguments(nonce)
    original = None
    try:
        result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
        print(result.stdout, end='', flush=True)
        result.check_returncode()
        verify_outcomes(result.stdout)
    except BaseException as error:
        original = error
        raise
    finally:
        try:
            verify(root)
        except BaseException:
            if original is None:
                raise
        finally:
            try:
                focus_diagnostic.collect(root, nonce)
            except Exception:
                pass
    print('Prebuilt APK/metadata identity and explicit task reuse verified', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('record', 'connected'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.phase == 'record':
        record(root)
    else:
        try:
            run(root)
        except subprocess.CalledProcessError as failure:
            raise SystemExit(failure.returncode)
