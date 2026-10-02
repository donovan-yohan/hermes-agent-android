#!/usr/bin/env python3
"""CI-only identity check for the two APKs assembled before emulator boot."""
import hashlib
import json
import argparse
import subprocess
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


def run(root, *, preflight):
    # AGP 8.13.2 connected inputs: APK directories/listings, test classes and R.
    # Exclude their canonical producers, not arbitrary graph tasks. The init
    # guard rejects any remaining task (including a newly added verification
    # dependency); it NEVER disables tasks or ignores failures.
    producers = ('packageDebug', 'packageDebugAndroidTest',
                 'createDebugApkListingFileRedirect',
                 'createDebugAndroidTestApkListingFileRedirect',
                 'compileDebugAndroidTestKotlin', 'compileDebugAndroidTestJavaWithJavac',
                 'processDebugAndroidTestResources')
    command = ['./gradlew', ':app:connectedDebugAndroidTest', '--no-daemon',
               '--no-build-cache', '--no-configuration-cache',
               '--init-script', 'scripts/ci-prebuilt.gradle']
    for task in producers:
        command.extend(['--exclude-task', ':app:' + task])
    if preflight:
        command.append('--dry-run')
    verify(root)
    try:
        subprocess.run(command, cwd=root, check=True)
    finally:
        verify(root)
    print('Prebuilt APK and metadata identity verified; ' +
          ('graph preflight only' if preflight else 'connected tests completed'), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('record', 'preflight', 'connected'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.phase == 'record':
        record(root)
    else:
        run(root, preflight=args.phase == 'preflight')
