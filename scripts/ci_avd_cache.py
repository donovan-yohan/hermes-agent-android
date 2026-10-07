#!/usr/bin/env python3
"""Bind AVD snapshots to installed SDK bytes, host and launcher configuration.

SDK installation precedes record. The pinned runner's idempotent installation
must leave those bytes unchanged; verify runs both before boot and before the
workload because a421e438's pre-launch error handler does not prevent boot.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import platform
import shlex
import stat
import subprocess
import sys
import time

WORKFLOW = Path('.github/workflows/android-exact-head.yml')
RECEIPT = Path('app/build/ci-avd-cache.json')
SDK_DIRS = ('emulator', 'system-images/android-34/google_apis/x86_64', 'cmdline-tools')
MANIFEST = 'creator-manifest.json'
# Native Saver/Loader require these; ram.img is optional file-backed RAM,
# not the saved RAM stream. snapshot.pb changes its counters on successful load.
SNAPSHOT_FILES = ('snapshot.pb', 'ram.bin', 'textures.bin', 'hardware.ini')


def host_identity() -> dict:
    cpu = Path('/proc/cpuinfo').read_text()
    fields = {'vendor_id', 'model name', 'flags', 'Features'}
    selected = sorted({line.strip() for line in cpu.splitlines()
                       if line.partition(':')[0].strip() in fields})
    if not selected:
        raise ValueError('host CPU compatibility fields missing')
    return {'arch': platform.machine(), 'kernel': platform.release(),
            'image': os.environ['ImageVersion'], 'cpu': selected}


def identity(sdk: Path, workflow: Path, host: dict) -> str:
    required = (sdk / 'emulator/emulator', sdk / SDK_DIRS[1] / 'system.img')
    if not all(path.is_file() for path in required):
        raise ValueError('required installed SDK inputs missing')
    digest = hashlib.sha256(json.dumps(host, sort_keys=True).encode())
    # Includes both action pins, creation/consumption settings and AVD identity.
    digest.update(workflow.read_bytes())
    deadline = time.monotonic() + 120
    directories: list[str] = list(SDK_DIRS)
    # rglob does not traverse directory symlinks. Include latest explicitly if
    # it points outside the versioned tools tree the setup action materialized.
    if (sdk / 'cmdline-tools/latest').is_symlink():
        directories.append('cmdline-tools/latest')
    for directory in directories:
        root = sdk / directory
        files = sorted(path for path in root.rglob('*') if path.is_file())
        if not files and not (directory == 'cmdline-tools' and 'cmdline-tools/latest' in directories):
            raise ValueError('installed SDK directory missing or empty')
        for path in files:
            digest.update(str(path.relative_to(sdk)).encode() + b'\0')
            digest.update(str(path.stat().st_size).encode() + b'\0')
            with path.open('rb') as stream:
                while True:
                    if time.monotonic() > deadline:
                        raise ValueError('installed SDK identity exceeded 120 seconds')
                    block = stream.read(1024 * 1024)
                    if not block:
                        break
                    digest.update(block)
            digest.update(b'\0')
    return 'avd-v3-' + digest.hexdigest()


def record(receipt: Path, key: str) -> None:
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({'key': key}) + '\n')


def configuration(config: Path) -> dict:
    # createAvd appends duplicate identical hw.cpu.ncore lines on reuse.
    current = {}
    for line in config.read_text().splitlines():
        if '=' not in line or line.lstrip().startswith('#'):
            continue
        name, value = (part.strip() for part in line.split('=', 1))
        current[name] = value
    if not current:
        raise ValueError('AVD configuration missing')
    return current


class AvdLayoutError(ValueError):
    """A literal constraint code, never native input or exception text."""

    def __init__(self, reason: str):
        self.reason = reason
        # Preserve the existing direct-call symlink rejection contract.
        super().__init__('AVD directory must not be a symlink'
                         if reason == 'directory-type' else reason)


def avd_layout(config: Path) -> None:
    # Only the standard named AVD layout is supported. Native lookup prefers
    # path.rel relative to ANDROID_AVD_HOME's parent, then falls back to path.
    home = config.parent.parent
    if (not home.is_absolute() or '..' in home.parts or home.name != 'avd'
            or config != home / 'test.avd/config.ini'):
        raise AvdLayoutError('layout-shape')
    for directory in (*reversed(home.parents), home, config.parent):
        try:
            mode = directory.lstat().st_mode
        except OSError:
            raise AvdLayoutError('directory-read') from None
        if not stat.S_ISDIR(mode):
            raise AvdLayoutError('directory-type')
    for directory in (config.parent / 'snapshots', config.parent / 'snapshots/default_boot'):
        try:
            invalid = ((directory.exists() or directory.is_symlink())
                       and not stat.S_ISDIR(directory.lstat().st_mode))
        except OSError:
            raise AvdLayoutError('snapshot-ancestor-read') from None
        if invalid:
            raise AvdLayoutError('snapshot-ancestor-type')
    locator = home / 'test.ini'
    for path in (locator, config, config.parent / MANIFEST):
        if path == config.parent / MANIFEST and not path.exists() and not path.is_symlink():
            continue  # Miss bootstrap only; ordinary verify still requires it.
        try:
            mode = path.lstat().st_mode
        except OSError:
            raise AvdLayoutError('file-read') from None
        if not stat.S_ISREG(mode):
            raise AvdLayoutError('file-type')
    values = {}
    try:
        lines = locator.read_text().splitlines()
    except (OSError, UnicodeError):
        raise AvdLayoutError('locator-read') from None
    for line in lines:
        if '=' not in line or line.lstrip().startswith('#'):
            continue
        name, value = (part.strip() for part in line.split('=', 1))
        if name in values:
            raise AvdLayoutError('locator-duplicate')
        values[name] = value
    if values.get('path') != str(config.parent.resolve()):
        raise AvdLayoutError('locator-path')
    if 'path.rel' in values and values['path.rel'] != 'avd/test.avd':
        raise AvdLayoutError('locator-relative-path')


def creator_quiescence() -> None:
    # Independent of the action's swallowed adb shutdown result. Never signal
    # processes. ps supplies a fresh, untruncated host process observation;
    # matching this SDK's test/5554 creator must disappear before artifact reads.
    sdk_emulator = Path(os.environ['ANDROID_HOME']).resolve() / 'emulator'
    deadline = time.monotonic() + 30
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('creator did not become quiescent within 30 seconds')
        result = subprocess.run(['ps', '-ww', '-eo', 'uid=,pid=,args='],
                                capture_output=True, text=True, check=True,
                                timeout=min(5, remaining))
        live = False
        for line in result.stdout.splitlines():
            uid, pid, command = line.strip().split(None, 2)
            if int(uid) != os.getuid():
                continue
            args = shlex.split(command)
            if not args or not Path(args[0]).is_relative_to(sdk_emulator):
                continue
            if ('@test' in args or any(args[i:i + 2] in (['-avd', 'test'], ['-port', '5554'])
                                      for i in range(len(args) - 1))):
                live = True
        if not live:
            return
        time.sleep(min(0.25, remaining))


class CreationConfigDelta(ValueError):
    """A creator-only display-label transition, or fixed-category refusal."""

    def __init__(self, initial: dict, current: dict):
        changed = {name for name in initial.keys() | current.keys()
                   if name not in initial or name not in current or initial[name] != current[name]}
        # SDK AvdManager's display label is not AvdId, a path or a profile.
        # No prefix/category exemption: disk geometry, image/resource/user
        # constraints and every unknown field remain immutable during creation.
        self.bootstrap_metadata_only = changed <= {'avd.ini.displayname'}
        categories = set()
        for name in changed:
            # Lexical diagnostics only, never an acceptance rule. Unknown
            # fields stay 'other' and are not in the exact-key allowlist.
            if name.startswith('hw.'):
                categories.add('hardware')
            elif name.startswith(('snapshot.', 'fastboot.')):
                categories.add('snapshot')
            elif name.endswith('.path') or name.startswith('image.sysdir.'):
                categories.add('path')
            else:
                categories.add('other')
        self.categories = tuple(category for category in ('hardware', 'snapshot', 'path', 'other')
                                if category in categories)
        super().__init__('creation AVD configuration changed')


def config_fingerprint(current: dict) -> str:
    return hashlib.sha256(json.dumps(current, sort_keys=True).encode()).hexdigest()


def metadata_fingerprint(data: bytes) -> str:
    # snapshot.proto: only successful_loads (field 11) is expected to change
    # during compatible loading. Keep every other field bound, and refuse the
    # native failure/invalid-load markers so cold-boot fallback cannot hide them.
    offset = 0
    fields = set()
    stable = bytearray()

    def varint() -> int:
        nonlocal offset
        value = 0
        for shift in range(0, 70, 7):
            if offset >= len(data):
                break
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value
        raise ValueError('malformed snapshot metadata')

    while offset < len(data):
        start = offset
        tag = varint()
        field, wire = tag >> 3, tag & 7
        fields.add(field)
        if field == 0:
            raise ValueError('malformed snapshot metadata')
        if wire == 0:
            value = varint()
            if field in (7, 10) and value != 0:
                raise ValueError('native snapshot load failed')
        elif wire == 2:
            size = varint()
            offset += size
        elif wire in (1, 5):
            offset += 8 if wire == 1 else 4
        else:
            raise ValueError('unsupported snapshot metadata wire type')
        if offset > len(data) or (field in (1, 7, 10, 11) and wire != 0):
            raise ValueError('malformed snapshot metadata')
        if field != 11:
            stable.extend(data[start:offset])
    if not {1, 3, 4, 5}.issubset(fields):
        raise ValueError('incomplete snapshot metadata')
    return hashlib.sha256(stable).hexdigest()


def snapshot_files(config: Path) -> dict:
    directory = config.parent / 'snapshots/default_boot'
    for ancestor in (directory.parent, directory):
        if not stat.S_ISDIR(ancestor.lstat().st_mode):
            raise ValueError('snapshot ancestor must be a nonsymlink directory')
    result = {}
    for name in SNAPSHOT_FILES:
        path = directory / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise ValueError('saved default_boot snapshot incomplete')
        if name == 'snapshot.pb':
            result[name] = metadata_fingerprint(path.read_bytes())
        else:
            digest = hashlib.sha256()
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(block)
            result[name] = digest.hexdigest()
    return result


def verify(receipt: Path, key: str, config: Path, mode: str = 'required',
           *, stage=lambda _: None) -> None:
    stage('avd-layout')
    avd_layout(config)
    stage('receipt-read')
    saved = json.loads(receipt.read_text())
    stage('receipt-key')
    if saved['key'] != key:
        raise ValueError('installed SDK or launcher identity changed after cache key capture')
    stage('config-read')
    current = configuration(config)
    manifest = config.parent / MANIFEST
    if mode == 'miss' and not manifest.exists():
        stage('creation-config')
        if 'config' in saved and saved['config'] != current:
            if not isinstance(saved['config'], dict):
                raise ValueError('creation AVD configuration changed')
            delta = CreationConfigDelta(saved['config'], current)
            if not delta.bootstrap_metadata_only:
                raise delta
        # This is a complete creator sample, not a consumer baseline. seal
        # requires exact equality again after exit; restored manifests never
        # enter this branch and consumers bind every field, including the label.
        saved['config'] = current
        stage('receipt-write')
        receipt.write_text(json.dumps(saved, sort_keys=True) + '\n')
        return
    stage('verification-mode')
    if mode not in ('miss', 'required'):
        raise ValueError('unknown verification mode')
    stage('manifest-read')
    creator = json.loads(manifest.read_text())
    stage('manifest-identity')
    if creator['schema'] != 1 or creator['key'] != key:
        raise ValueError('creator snapshot identity or configuration differs')
    stage('manifest-config')
    if (creator['config'] != current
            or creator['config_fingerprint'] != config_fingerprint(current)):
        raise ValueError('creator snapshot identity or configuration differs')
    stage('snapshot-files')
    if creator['snapshots'] != snapshot_files(config):
        raise ValueError('creator snapshot identity or configuration differs')


def seal(receipt: Path, key: str, config: Path, *, stage=lambda _: None) -> None:
    stage('avd-layout')
    avd_layout(config)
    stage('creator-quiescence')
    creator_quiescence()
    # Action return/kill-command success alone is not exit or completed save.
    stage('receipt-read')
    saved = json.loads(receipt.read_text())
    stage('config-read')
    current = configuration(config)
    stage('creation-identity')
    if saved['key'] != key or saved.get('config') != current:
        raise ValueError('snapshot creation identity or configuration changed')
    stage('snapshot-files')
    creator = {'schema': 1, 'key': key, 'config': current,
               'config_fingerprint': config_fingerprint(current),
               'snapshots': snapshot_files(config)}
    # Never overwrite a restored creator baseline, including malformed ones.
    stage('manifest-write')
    with (config.parent / MANIFEST).open('x') as stream:
        stream.write(json.dumps(creator, sort_keys=True) + '\n')


def main(argv: list[str] | None = None) -> int:
    # Only fixed phase/category enums reach logs. Never emit exceptions, paths, config
    # names/values, identity bytes or subprocess output. Direct helper callers
    # remain quiet; CLI diagnostics are not compatibility evidence themselves.
    current_stage = None

    def stage(value: str) -> None:
        nonlocal current_stage
        if current_stage is not None:
            print(f'AVD guard stage={current_stage} status=passed', flush=True)
        current_stage = value
        print(f'AVD guard stage={current_stage} status=started', flush=True)

    try:
        stage('command')
        command, = sys.argv[1:] if argv is None else argv
        stage('installed-identity')
        key = identity(Path(os.environ['ANDROID_HOME']), WORKFLOW, host_identity())
        if command == 'record':
            stage('receipt-write')
            record(RECEIPT, key)
            stage('key-output')
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write(f'key={key}\n')
        elif command in ('verify', 'verify-miss', 'seal'):
            stage('avd-input')
            config = Path(os.environ['ANDROID_AVD_HOME']) / 'test.avd/config.ini'
            if command == 'seal':
                seal(RECEIPT, key, config, stage=stage)
            else:
                verify(RECEIPT, key, config, mode='miss' if command == 'verify-miss' else 'required', stage=stage)
        else:
            stage('command-dispatch')
            raise ValueError('unknown command')
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f'AVD guard stage={current_stage} status=failed', file=sys.stderr, flush=True)
        if current_stage == 'creation-config' and isinstance(error, CreationConfigDelta):
            for category in error.categories:
                print(f'AVD config delta category={category}', file=sys.stderr, flush=True)
        if current_stage == 'avd-layout' and isinstance(error, AvdLayoutError):
            print(f'AVD guard stage=avd-layout reason={error.reason}', file=sys.stderr, flush=True)
        print('::error::AVD cache compatibility verification failed; refusing the workload.', file=sys.stderr)
        return 1
    print(f'AVD guard stage={current_stage} status=passed', flush=True)
    print('AVD installed-byte compatibility identity verified.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
