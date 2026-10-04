"""Narrow disposable-only gates and resanitization; never save raw adb output."""
import json
import os
import re
import subprocess
from pathlib import Path

SERIAL = 'emulator-5554'
PROVENANCE = 'api34-run-scoped-snapshot'


def adb(*args):
    return subprocess.run(['adb', '-s', SERIAL, *args], check=True,
                          capture_output=True, text=True, timeout=5).stdout.strip()


def arm(env=None):
    env = os.environ if env is None else env
    if env.get('FOCUS_DIAGNOSTIC') != 'true':
        return None
    nonce = env.get('FOCUS_NONCE', '')
    if (env.get('ANDROID_SERIAL') != SERIAL or not re.fullmatch('[a-f0-9]{32}', nonce)
            or env.get('FOCUS_DISPOSABLE') != PROVENANCE):
        raise ValueError('diagnostic binding rejected')
    # No alternate Gradle property channel may replace or duplicate runner gates.
    if any('focusSnapshot' in value for key, value in env.items()
           if key in ('GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS')
           or key.startswith('ORG_GRADLE_PROJECT_')):
        raise ValueError('duplicate diagnostic property channel')
    expected = nonce + ':' + SERIAL + ':' + PROVENANCE
    if (adb('get-serialno') != SERIAL
            or adb('shell', 'getprop', 'ro.boot.qemu') != '1'
            or adb('shell', 'getprop', 'ro.build.version.sdk') != '34'
            or adb('shell', 'getprop', 'debug.hermes.focus_attestation') != expected):
        raise ValueError('diagnostic attestation rejected')
    return nonce


def arguments(nonce):
    return [] if nonce is None else [
        '-Pandroid.testInstrumentationRunnerArguments.' + key + '=' + value
        for key, value in [('focusSnapshotEnabled', 'true'), ('focusSnapshotNonce', nonce),
                           ('focusSnapshotSerial', SERIAL), ('focusSnapshotDisposable', PROVENANCE)]]


def sanitize(text):
    if len(text) > 4096:
        raise ValueError('oversize record')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('duplicate field')
            result[key] = value
        return result
    value = json.loads(text, object_pairs_hook=pairs)
    if set(value) != {'schema', 'before', 'owner', 'after'} or type(value['schema']) is not int or value['schema'] != 1:
        raise ValueError('schema rejected')
    for name in ('before', 'after', 'owner'):
        part = value[name]
        if part is None:
            continue
        numbers = {'display', 'ownerPid', 'ownerUid'} if name == 'owner' else {'display'}
        flags = set() if name == 'owner' else {'attached', 'destroyed', 'activityFocus', 'decorFocus', 'windowIdFocus'}
        if not isinstance(part, dict) or set(part) != numbers | flags:
            raise ValueError('fields rejected')
        if any(type(part[k]) is not int or not 0 <= part[k] <= 2147483647 for k in numbers):
            raise ValueError('number rejected')
        if any(type(part[k]) is not bool for k in flags):
            raise ValueError('flag rejected')
    return json.dumps(value, sort_keys=True) + '\n'


def collect(root, nonce):
    if nonce is None:
        return
    if (adb('get-serialno') != SERIAL or adb('shell', 'getprop', 'debug.hermes.focus_attestation') !=
            nonce + ':' + SERIAL + ':' + PROVENANCE):
        return
    # Fresh exact directory only; no wildcard pulls or historic additional outputs.
    destination = root / 'app/build' / ('focus-' + nonce)
    writable = False
    try:
        destination.mkdir(exist_ok=False)
        writable = True
    except Exception:
        # Storage failure must not skip either independent device probe.
        pass
    for index in (1, 2):
        try:
            text = adb('exec-out', 'run-as', 'com.hermesagent.mobile.debug', 'cat',
                       'files/focus-' + nonce + '/readiness-' + str(index) + '.json')
            reduced = sanitize(text)
            if writable:
                (destination / ('readiness-' + str(index) + '.json')).write_text(reduced)
        except Exception:
            # AGP normally uninstalls the target before this host finalizer.
            # Recover only exact nonce/index reduced records from retained logs.
            try:
                records = []
                for path in (root / 'app/build/outputs/androidTest-results/connected').rglob('logcat-*.txt'):
                    for line in path.read_text(errors='replace').splitlines():
                        match = re.search(r'HermesFocusReduced\s*:\s*' + re.escape(nonce) + ':' + str(index) + r':(\{.*\})$', line)
                        if match:
                            records.append(sanitize(match.group(1)))
                if writable and len(records) == 1:
                    (destination / ('readiness-' + str(index) + '.json')).write_text(records[0])
            except Exception:
                pass
