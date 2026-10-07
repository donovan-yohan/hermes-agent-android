"""Offline B1/B2 attacks: fresh receipts must not adopt restored AVD state.

Synthetic snapshot bytes prove only the host-side file contract, not emulator
snapshot creation, compatible loading, device acceptance, or ANR causality.
Every record/bootstrap/seal/verify imports the real helper in a new process.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'scripts/ci_avd_cache.py'
ARTIFACTS = ('snapshot.pb', 'ram.bin', 'textures.bin', 'hardware.ini')
CONFIG = {
    'AvdId': 'test', 'avd.ini.displayname': 'test', 'hw.cpu.ncore': '2',
    'hw.ramSize': '2048', 'hw.gpu.enabled': 'yes',
    'hw.gpu.mode': 'swiftshader_indirect',
    'image.sysdir.1': 'system-images/android-34/google_apis/x86_64/',
}
# Keep API adaptation confined to legacy mode support. Unexpected exceptions,
# including API errors, are ERROR rather than a successful attack rejection.
CHILD = r'''
import hashlib, importlib.util, inspect, json, sys
from pathlib import Path
helper, operation, root, receipt, config, mode = sys.argv[1:]
spec = importlib.util.spec_from_file_location('ci_avd_cache', helper)
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)
root, receipt, config = Path(root), Path(receipt), Path(config)
key = cache.identity(root / 'sdk', root / 'workflow.yml',
                     {'arch': 'x86_64', 'cpu': ['synthetic-offline-cpu']})
manifest = config.parent / getattr(cache, 'MANIFEST', 'creator-manifest.json')
try:
    if operation == 'record':
        cache.record(receipt, key)
    elif operation == 'verify':
        if 'mode' in inspect.signature(cache.verify).parameters:
            cache.verify(receipt, key, config, mode=mode)
        else:
            cache.verify(receipt, key, config)
    elif operation == 'seal':
        if hasattr(cache, 'seal'):
            cache.seal(receipt, key, config)
        else:
            # The old helper ignores this fixture. New implementations use seal
            # instead, so these speculative fields never exercise their parser.
            values = dict(line.split('=', 1) for line in config.read_text().splitlines()
                          if '=' in line)
            files = config.parent / 'snapshots/default_boot'
            manifest.write_text(json.dumps({
                'schema': 1, 'key': key, 'config': values,
                'config_fingerprint': hashlib.sha256(json.dumps(values, sort_keys=True).encode()).hexdigest(),
                'snapshots': {name: hashlib.sha256((files / name).read_bytes()).hexdigest()
                              for name in ('snapshot.pb', 'ram.bin', 'textures.bin', 'hardware.ini')},
            }))
    else:
        raise RuntimeError('unknown fixture operation')
except (ValueError, OSError, KeyError) as error:
    print(json.dumps({'status': 'REJECTED', 'type': type(error).__name__}))
else:
    print(json.dumps({'status': 'ACCEPTED', 'key': key, 'manifest': str(manifest)}))
'''


class FreshRestoreAttackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='fugu-fresh-restore-')
        self.addCleanup(self.temp.cleanup)
        # Canonicalize our owned fixture, not the helper's runtime inputs.
        # macOS's inherited TMPDIR may include the /var symlink ancestor.
        self.root = Path(self.temp.name).resolve()
        for name in ('emulator/emulator',
                     'system-images/android-34/google_apis/x86_64/system.img',
                     'cmdline-tools/latest/bin/avdmanager'):
            path = self.root / 'sdk' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'synthetic SDK bytes; offline only')
        self.root.joinpath('workflow.yml').write_text('launcher: synthetic offline fixture\n')
        self.creator_receipt = self.root / 'creator-run/receipt.json'
        self.creator_config = self.root / 'creator/avd/test.avd/config.ini'
        self.creator_config.parent.mkdir(parents=True)
        self.write_config(self.creator_config, CONFIG)
        self.expect_accepted('record', self.creator_receipt, self.creator_config)
        self.expect_accepted('verify', self.creator_receipt, self.creator_config, 'miss')
        snapshots = self.creator_config.parent / 'snapshots/default_boot'
        snapshots.mkdir(parents=True)
        for name in ARTIFACTS:
            (snapshots / name).write_bytes(b'\x08\x01\x1a\x00\x22\x00\x2a\x00\x50\x00\x58\x00' if name == 'snapshot.pb' else ('synthetic offline snapshot ' + name).encode())
        sealed = self.expect_accepted('seal', self.creator_receipt, self.creator_config)
        self.manifest_name = Path(sealed['manifest']).name
        self.recipient_number = 0

    @staticmethod
    def write_config(path, values):
        path.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
        (path.parent.parent / 'test.ini').write_text(f'path={path.parent}\npath.rel=avd/test.avd\n')

    def invoke(self, operation, receipt, config, mode='required'):
        result = subprocess.run(
            [sys.executable, '-B', '-c', CHILD, str(HELPER), operation,
             str(self.root), str(receipt), str(config), mode],
            cwd=ROOT, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1', 'ANDROID_HOME': str(self.root / 'sdk')},
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0,
                         f'API/import error, not rejection: {result.stderr}\n{result.stdout}')
        return json.loads(result.stdout)

    def expect_accepted(self, operation, receipt, config, mode='required'):
        result = self.invoke(operation, receipt, config, mode)
        self.assertEqual(result['status'], 'ACCEPTED', f'fixture/control failed: {result}')
        return result

    def restore(self):
        self.recipient_number += 1
        recipient = self.root / f'recipient-{self.recipient_number}'
        receipt = recipient / 'run/receipt.json'
        config = recipient / 'avd/test.avd/config.ini'
        # Match Actions ordering: independent receipt captured BEFORE restore.
        self.expect_accepted('record', receipt, config)
        self.assertFalse(config.parent.exists())
        self.assertNotIn('config', json.loads(receipt.read_text()))
        shutil.copytree(self.creator_config.parent, config.parent)
        (config.parent.parent / 'test.ini').write_text(f'path={config.parent}\npath.rel=avd/test.avd\n')
        return receipt, config

    def expect_refused(self, receipt, config, attack):
        # Exercise both guards even if the first rejects; no new record between
        # them. Legacy adoption incorrectly accepts BOTH in separate processes.
        results = [self.invoke('verify', receipt, config) for _ in range(2)]
        self.assertEqual([r['status'] for r in results], ['REJECTED', 'REJECTED'],
                         f'{attack}: pre-launch and first-workload guards accepted attack: {results}')

    def test_b1_fresh_recipient_divergent_effective_config(self):
        attacks = {
            'RAM': ('hw.ramSize', '9999'),
            'GPU': ('hw.gpu.mode', 'host'),
            'image': ('image.sysdir.1', 'system-images/different/offline-image/'),
            'cores': ('hw.cpu.ncore', '4'),
            'AVD identity': ('AvdId', 'different-offline-avd'),
        }
        for attack, (field, value) in attacks.items():
            with self.subTest(attack=attack):
                receipt, config = self.restore()
                self.write_config(config, {**CONFIG, field: value})
                self.expect_refused(receipt, config, attack)

    def test_b1_fresh_recipient_missing_creator_manifest(self):
        receipt, config = self.restore()
        (config.parent / self.manifest_name).unlink()
        self.expect_refused(receipt, config, 'missing creator manifest')

    def test_b2_exact_hit_without_default_boot(self):
        receipt, config = self.restore()
        shutil.rmtree(config.parent / 'snapshots/default_boot')
        self.expect_refused(receipt, config, 'no default_boot directory')

    def test_b2_exact_hit_incomplete_default_boot(self):
        for name in ARTIFACTS:
            with self.subTest(missing=name):
                receipt, config = self.restore()
                (config.parent / 'snapshots/default_boot' / name).unlink()
                self.expect_refused(receipt, config, f'missing {name}')

    def test_b2_exact_hit_empty_snapshot_artifact(self):
        for name in ARTIFACTS:
            with self.subTest(empty=name):
                receipt, config = self.restore()
                (config.parent / 'snapshots/default_boot' / name).write_bytes(b'')
                self.expect_refused(receipt, config, f'empty {name}')

    def test_b2_exact_hit_changed_snapshot_bytes(self):
        for name in ARTIFACTS:
            with self.subTest(changed=name):
                receipt, config = self.restore()
                (config.parent / 'snapshots/default_boot' / name).write_bytes(b'different nonempty offline bytes')
                self.expect_refused(receipt, config, f'changed {name}')

    def test_manifest_malformed_stale_and_mismatched_fields_refuse(self):
        for mutation in ('malformed', 'schema', 'key', 'config', 'config_fingerprint', 'snapshots'):
            with self.subTest(mutation=mutation):
                receipt, config = self.restore()
                path = config.parent / self.manifest_name
                if mutation == 'malformed':
                    path.write_text('{')
                else:
                    manifest = json.loads(path.read_text())
                    manifest[mutation] = 'stale'
                    path.write_text(json.dumps(manifest))
                self.expect_refused(receipt, config, mutation)

    def test_metadata_failure_markers_and_malformed_wire_refuse(self):
        for data in (b'\x08\x01\x1a\x00\x22\x00\x2a\x00\x38\x01',
                     b'\x08\x01\x1a\x00\x22\x00\x2a\x00\x50\x01',
                     b'\x08\x01\x1a\x7f', b'\x08\x01', b'\x00', b'\x80'):
            with self.subTest(data=data):
                receipt, config = self.restore()
                (config.parent / 'snapshots/default_boot/snapshot.pb').write_bytes(data)
                self.expect_refused(receipt, config, 'native metadata invalid')

    def test_snapshot_symlink_refuses(self):
        for name in ARTIFACTS:
            with self.subTest(name=name):
                receipt, config = self.restore()
                path = config.parent / 'snapshots/default_boot' / name
                target = path.with_suffix('.external')
                path.rename(target)
                path.symlink_to(target)
                self.expect_refused(receipt, config, 'snapshot symlink')

    def test_seal_never_overwrites_existing_manifest(self):
        path = self.creator_config.parent / self.manifest_name
        for existing in (path.read_bytes(), b'{malformed existing manifest'):
            with self.subTest(existing=existing):
                path.write_bytes(existing)
                result = self.invoke('seal', self.creator_receipt, self.creator_config)
                self.assertEqual(result['status'], 'REJECTED')
                self.assertEqual(path.read_bytes(), existing)

    def test_seal_requires_complete_saved_files_and_creation_config(self):
        for mutation in (*ARTIFACTS, 'config', 'receipt'):
            with self.subTest(mutation=mutation):
                receipt, config = self.restore()
                manifest = config.parent / self.manifest_name
                manifest.unlink()
                self.expect_accepted('verify', receipt, config, 'miss')
                if mutation in ARTIFACTS:
                    (config.parent / 'snapshots/default_boot' / mutation).unlink()
                elif mutation == 'config':
                    self.write_config(config, {**CONFIG, 'hw.ramSize': '9999'})
                else:
                    saved = json.loads(receipt.read_text())
                    saved['key'] = 'stale'
                    receipt.write_text(json.dumps(saved))
                result = self.invoke('seal', receipt, config)
                self.assertEqual(result['status'], 'REJECTED')
                self.assertFalse(manifest.exists())

    def test_control_snapshot_metadata_load_counters_may_change(self):
        receipt, config = self.restore()
        # Snapshot::incrementSuccessfulLoads rewrites this metadata on disk.
        path = config.parent / 'snapshots/default_boot/snapshot.pb'
        path.write_bytes(path.read_bytes().replace(b'\x58\x00', b'\x58\x01'))
        self.expect_accepted('verify', receipt, config)

    def test_control_intact_restore_and_identical_appended_core(self):
        receipt, config = self.restore()
        with config.open('a') as stream:
            stream.write('hw.cpu.ncore=2\n')
        self.expect_accepted('verify', receipt, config)
        self.expect_accepted('verify', receipt, config)

    def test_control_miss_can_bootstrap_before_snapshot_exists(self):
        receipt = self.root / 'ordinary-miss/receipt.json'
        config = self.root / 'ordinary-miss/avd/test.avd/config.ini'
        config.parent.mkdir(parents=True)
        self.write_config(config, CONFIG)
        self.expect_accepted('record', receipt, config)
        self.expect_accepted('verify', receipt, config, 'miss')
        self.assertFalse((config.parent / 'snapshots').exists())


    def test_sdk_writer_omitted_relative_field_and_strict_present_values(self):
        # Revision-12 AvdManager.createAvdIniFile writes path unconditionally,
        # but omits path.rel outside the SDK handler's Android-folder prefix.
        receipt, config = self.restore()
        locator = config.parent.parent / 'test.ini'
        absolute = str(config.parent)
        omitted = f'avd.ini.encoding=UTF-8\npath={absolute}\ntarget=android-34\n'
        locator.write_text(omitted)
        self.expect_accepted('verify', receipt, config)
        self.expect_accepted('verify', receipt, config)
        (config.parent / self.manifest_name).unlink()
        self.expect_accepted('verify', receipt, config, 'miss')
        self.expect_accepted('seal', receipt, config)
        self.expect_accepted('verify', receipt, config)

        # A real alternate relative target must reject, not merely a missing
        # directory that some native resolver might bypass.
        other = config.parent.with_name('other.avd')
        shutil.copytree(config.parent, other)
        for relative in ('', 'avd/other.avd', '../avd/test.avd',
                         'avd/./test.avd', 'avd/test.avd/'):
            with self.subTest(relative=relative):
                locator.write_text(omitted + f'path.rel={relative}\n')
                self.expect_refused(receipt, config, 'present relative value')
        for path in (str(other), absolute + '/',
                     str(config.parent.parent) + '/./test.avd'):
            with self.subTest(path=path):
                locator.write_text(f'path={path}\n')
                self.expect_refused(receipt, config, 'absolute redirect or alias')
        for duplicate in (f'path={absolute}\n',
                          'path.rel=avd/test.avd\npath.rel=avd/test.avd\n',
                          'target=android-34\n'):
            with self.subTest(duplicate=duplicate):
                locator.write_text(omitted + duplicate)
                self.expect_refused(receipt, config, 'duplicate locator key')

    def test_b4_redirected_locator_and_symlink_boundary(self):
        for variant in ('redirect', 'absolute', 'relative', 'missing', 'locator', 'avd-parent', 'avd-home',
                        'test.avd', 'snapshots', 'default_boot', 'config.ini', 'creator-manifest.json'):
            with self.subTest(variant=variant):
                receipt, config = self.restore()
                locator = config.parent.parent / 'test.ini'
                if variant in ('redirect', 'absolute', 'relative'):
                    absolute = '/outside/unchecked.avd' if variant != 'relative' else str(config.parent)
                    relative = '../../outside/unchecked.avd' if variant != 'absolute' else 'avd/test.avd'
                    locator.write_text(f'path={absolute}\npath.rel={relative}\n')
                elif variant == 'missing':
                    locator.unlink()
                else:
                    selected = {'locator': locator, 'avd-parent': config.parent.parent.parent, 'avd-home': config.parent.parent,
                                'test.avd': config.parent, 'snapshots': config.parent / 'snapshots',
                                'default_boot': config.parent / 'snapshots/default_boot',
                                'config.ini': config, 'creator-manifest.json': config.parent / self.manifest_name}[variant]
                    outside = self.root / f'external-{self.recipient_number}'
                    selected.rename(outside)
                    selected.symlink_to(outside, target_is_directory=outside.is_dir())
                self.expect_refused(receipt, config, variant)


if __name__ == '__main__':
    unittest.main()
