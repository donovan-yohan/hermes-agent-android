#!/usr/bin/env python3
"""One-shot synthetic CI failure-only SystemUI ANR extraction. Never upload raw inputs."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import zipfile

PACKAGE = 'com.android.systemui'


def sanitize(line):
    line = re.sub(r'https?://\S+|[\w.+-]+@[\w.-]+', '[redacted]', line)
    line = re.sub(r'/(?:[^\s()]+)', '[path]', line)
    return line[:600]


def extract(text):
    records, traces = [], []
    lines = text.splitlines()
    # ActivityManager multiline records or DropBox headers. No generic log export.
    for i, line in enumerate(lines):
        if not re.search(r'(?:ANR in |Process: )com\.android\.systemui\s*$', line):
            continue
        record = [sanitize(line)]
        for value in lines[i + 1:i + 100]:
            if re.search(r'ANR in |Process: |----- (?:pid|end)', value):
                break
            # Prefixes may carry timestamps and ActivityManager pid/tid; trim them.
            value = re.sub(r'^.*?ActivityManager\s*:\s*', '', value).strip()
            if re.match(r'(?:PID: \d+|(?:Reason|Subject): |CPU usage from |\d[\d.]*% TOTAL:)', value):
                record.append(sanitize(value))
            elif re.match(r'[\d.]+% \d+/com\.android\.systemui:', value):
                # Slash here is part of pid/process identity, not a path.
                record.append(value[:600])
        records.append(record[:15])
        if len(records) == 8:
            break
    # Select only explicit per-process ART trace blocks, never adjacent processes.
    for match in re.finditer(r'^----- pid \d+ at [^\n]+-----\n(.*?)^----- end \d+ -----', text, re.M | re.S):
        block = match.group(1)
        if not re.search(r'^Cmd line: com\.android\.systemui\s*$', block, re.M):
            continue
        trace = [match.group(0).splitlines()[0], 'Cmd line: ' + PACKAGE]
        selected = False
        count = 0
        for line in block.splitlines():
            if line.startswith('"'):
                selected = bool(re.match(r'"(?:main|RenderThread|binder:[\d_]+|Binder:[\d_]+|android\.ui|android\.fg)"', line))
                if selected:
                    count += 1
                    if count > 16:
                        break
                    trace.append(line[:200])
                continue
            if selected and (re.match(r'\s+at (?:android\.|com\.android\.|java\.|kotlin\.|kotlinx\.|dalvik\.)[\w.$<>]+\([\w.$ :<>-]+\)\s*$', line)
                             or re.match(r'\s+native: #\d+ pc ', line)
                             or re.match(r'\s+- (?:waiting|locked|sleeping)', line)
                             or re.match(r'\s+\| (?:sysTid=|state=|held mutexes=)', line)):
                trace.append(sanitize(line))
            if len(trace) >= 240:
                break
        traces.append(trace)
        if len(traces) >= 4:
            break
    return {'records': records, 'traces': traces}


def allowed():
    return (os.environ.get('GITHUB_ACTIONS') == 'true'
            and os.environ.get('GITHUB_REPOSITORY') == 'donovan-yohan/hermes-agent-android'
            and os.environ.get('GITHUB_HEAD_REF') == 'diagnostic/api34-focus-651d659'
            and os.environ.get('SYSTEMUI_ANR_OPT_IN') == 'pr344-single-synthetic-run'
            and os.environ.get('FOCUS_SYNTHETIC_OPT_IN') == 'b291-failure-only'
            and os.environ.get('ANDROID_SERIAL') == 'emulator-5554')


def collect(out, failed, nonce):
    if not failed or not allowed():
        return
    adb = ['adb', '-s', 'emulator-5554']
    def call(*args, timeout=30):
        return subprocess.check_output(adb + list(args), text=True, stderr=subprocess.DEVNULL, timeout=timeout)
    # Validate the same already-attested device and invocation before broad ephemeral input.
    if (call('get-serialno').strip() != 'emulator-5554'
            or call('emu', 'avd', 'name').splitlines()[0] != 'test'
            or call('shell', 'getprop', 'ro.build.version.sdk').strip() != '34'
            or call('shell', 'getprop', 'debug.hermes.focus_nonce').strip() != nonce):
        raise RuntimeError('synthetic binding rejected')
    result = {'epoch': time.time(), 'records': [], 'traces': [], 'status': []}
    sdk = Path(os.environ['ANDROID_HOME'])
    result['versions'] = {}
    for name, relative in (('emulator', 'emulator/source.properties'),
                           ('system_image', 'system-images/android-34/google_apis/x86_64/source.properties')):
        properties = (sdk / relative).read_text()
        result['versions'][name] = dict(re.findall(r'^(Pkg.Revision|AndroidVersion.ApiLevel|SystemImage.TagId|SystemImage.Abi)=(.*)$', properties, re.M))
    result['device_clock'] = call('shell', 'date', '+%s').strip()
    result['device_uptime'] = call('shell', 'cat', '/proc/uptime').strip()
    result['device_build'] = call('shell', 'getprop', 'ro.build.fingerprint').strip()[:200]
    # Every extraction stays bounded; raw data is never printed or put in artifact paths.
    for label, args in (
        ('logcat', ['logcat', '-b', 'system', '-d', '-v', 'epoch', 'ActivityManager:E', '*:S']),
        ('dropbox', ['shell', 'dumpsys', 'dropbox', '--print', 'system_app_anr']),
    ):
        try:
            parsed = extract(call(*args))
            for key in ('records', 'traces'):
                result[key].extend(parsed[key])
            result['status'].append(label + ':read')
        except Exception as exc:
            result['status'].append(label + ':' + type(exc).__name__)
    # bugreport exists only in a new private temporary directory on the fresh runner;
    # never extracted to disk and always deleted, including timeout/failure paths.
    try:
        with tempfile.TemporaryDirectory(prefix='systemui-private-', dir=os.environ['RUNNER_TEMP']) as temp:
            target = Path(temp) / 'report.zip'
            subprocess.run(adb + ['bugreport', str(target)], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=180, check=True)
            with zipfile.ZipFile(target) as archive:
                for info in archive.infolist():
                    if not (re.fullmatch(r'FS/data/anr/[^/]+', info.filename)
                            or re.fullmatch(r'bugreport-[^/]+\.txt', info.filename)):
                        continue
                    if info.file_size > 80_000_000:
                        result['status'].append('oversize-member-skipped')
                        continue
                    parsed = extract(archive.read(info).decode(errors='replace'))
                    for key in ('records', 'traces'):
                        result[key].extend(parsed[key])
            result['status'].append('bugreport:read-and-deleted')
    except Exception as exc:
        result['status'].append('bugreport:' + type(exc).__name__)
    for key, limit in (('records', 8), ('traces', 4)):
        unique = list(dict.fromkeys(json.dumps(value) for value in result[key]))[:limit]
        result[key] = [json.loads(value) for value in unique]
    (out / 'systemui-anr.json').write_text(json.dumps(result, indent=2)[:180000])
