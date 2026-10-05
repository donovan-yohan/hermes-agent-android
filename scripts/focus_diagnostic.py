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
    schema = value.get('schema')
    fields = {'schema', 'before', 'owner', 'after', 'ownerStatus', 'parserShape'}
    if schema in (3, 4, 5):
        fields.add('ownerMetadata')
    if schema == 5:
        fields.add('currentAnrCandidate')
    if set(value) != fields or type(schema) is not int or schema not in (2, 3, 4, 5):
        raise ValueError('schema rejected')
    if schema in (2, 3, 4, 5):
        status = value['ownerStatus']
        shape = value['parserShape']
        if shape is not None and (status != 'REJECTED' or not isinstance(shape, dict) or
                set(shape) != {'focusedSection', 'displaySection', 'windowSection', 'numericPid', 'numericUid', 'windowEntryShape'} or
                any(type(flag) is not bool for flag in shape.values())):
            raise ValueError('shape rejected')
        if status not in ('MISSING_CURRENT_SECTION', 'NO_FOCUSED_WINDOW', 'AMBIGUOUS', 'REJECTED', 'OVERFLOW', 'PROBE_FAILURE', 'MATCHED') or (status == 'MATCHED') != (value['owner'] is not None):
            raise ValueError('status rejected')
    if schema in (3, 4, 5) and value['ownerMetadata'] is not None:
        metadata = value['ownerMetadata']
        types = {'UNKNOWN', 'BASE_APPLICATION', 'APPLICATION', 'APPLICATION_STARTING', 'APPLICATION_ATTACHED_DIALOG', 'SYSTEM_ALERT', 'KEYGUARD', 'KEYGUARD_DIALOG', 'SYSTEM_DIALOG', 'STATUS_BAR', 'NOTIFICATION_SHADE', 'INPUT_METHOD', 'INPUT_METHOD_DIALOG', 'NAVIGATION_BAR', 'ACCESSIBILITY_OVERLAY', 'APPLICATION_OVERLAY', 'SECURE_SYSTEM_OVERLAY', 'TOAST', 'SYSTEM_ERROR', 'DREAM', 'DISPLAY_OVERLAY', 'POINTER', 'DRAG', 'DOCK_DIVIDER'}
        configs = {'NO_INPUT_CHANNEL', 'NOT_VISIBLE', 'NOT_FOCUSABLE', 'NOT_TOUCHABLE', 'PREVENT_SPLITTING', 'DUPLICATE_TOUCH_TO_WALLPAPER', 'IS_WALLPAPER', 'PAUSE_DISPATCHING', 'TRUSTED_OVERLAY', 'WATCH_OUTSIDE_TOUCH', 'SLIPPERY', 'DISABLE_USER_ACTIVITY', 'DROP_INPUT', 'DROP_INPUT_IF_OBSCURED', 'SPY', 'INTERCEPTS_STYLUS', 'NOT_TOUCH_MODAL'}
        if (value['owner'] is None or not isinstance(metadata, dict) or
                set(metadata) != ({'processRole', 'windowType', 'inputConfig', 'alertClass'} if schema in (4, 5) else {'processRole', 'windowType', 'inputConfig'}) or
                metadata['processRole'] not in ('UNKNOWN', 'SYSTEM_SERVER', 'SYSTEM_UI') or
                not isinstance(metadata['windowType'], str) or metadata['windowType'] not in types):
            raise ValueError('metadata rejected')
        flags = metadata['inputConfig']
        if flags is not None and (not isinstance(flags, list) or len(flags) > len(configs) or
                any(not isinstance(flag, str) or flag not in configs for flag in flags) or len(set(flags)) != len(flags)):
            raise ValueError('input config rejected')
        if schema in (4, 5):
            alert = metadata['alertClass']
            if (alert not in ('APPLICATION_NOT_RESPONDING', 'APPLICATION_ERROR', 'OTHER', 'UNKNOWN') or
                    (alert != 'UNKNOWN' and (metadata['processRole'] != 'SYSTEM_SERVER' or metadata['windowType'] != 'SYSTEM_ALERT'))):
                raise ValueError('alert class rejected')
    if schema == 5:
        candidate = value['currentAnrCandidate']
        enums = {
            'source': {'ACTIVITY_MANAGER_ERROR_STATE'}, 'scope': {'CALLER_USER'},
            'status': {'CURRENT_ANR_CANDIDATE', 'UNKNOWN'},
            'cause': {'NONE', 'NOT_ATTESTED', 'ALREADY_ATTEMPTED', 'NO_RECORD', 'MULTIPLE',
                      'INVALID_RECORD', 'OVERFLOW', 'ACQUISITION_FAILED', 'CLEANUP_FAILED', 'TIMEOUT'},
            'role': {'APP_PROCESS', 'SYSTEM_UI', 'UNKNOWN'},
            'reason': {'INPUT_DISPATCH_TIMEOUT', 'UNKNOWN'},
        }
        if (not isinstance(candidate, dict) or set(candidate) != set(enums) or
                any(type(candidate[key]) is not str or candidate[key] not in allowed
                    for key, allowed in enums.items())):
            raise ValueError('candidate rejected')
        if ((candidate['status'] == 'CURRENT_ANR_CANDIDATE') != (candidate['cause'] == 'NONE') or
                (candidate['status'] == 'UNKNOWN' and
                 (candidate['role'] != 'UNKNOWN' or candidate['reason'] != 'UNKNOWN'))):
            raise ValueError('candidate consistency rejected')
    for name in ('before', 'after', 'owner'):
        part = value[name]
        if part is None:
            continue
        numbers = {'display', 'ownerPid', 'ownerUid'} if name == 'owner' else {'display', 'appPid', 'appUid'}
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
