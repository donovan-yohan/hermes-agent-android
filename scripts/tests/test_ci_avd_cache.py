"""Installed SDK byte identity is independent of ordinary cache misses."""
import importlib.util
import subprocess

import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ci_avd_cache', ROOT / 'scripts/ci_avd_cache.py')
assert spec and spec.loader
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class AvdCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        # macOS TMPDIR can traverse /var -> /private/var. Canonicalize only
        # our owned fixture root; runtime AVD inputs must still reject aliases.
        self.root = Path(self.temp.name).resolve()
        self.sdk = self.root / 'sdk'
        for name in ('emulator/emulator', 'emulator/lib64/gpu.so',
                     'system-images/android-34/google_apis/x86_64/system.img',
                     'system-images/android-34/google_apis/x86_64/source.properties',
                     'cmdline-tools/latest/bin/avdmanager'):
            path = self.sdk / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture')
        self.workflow = self.root / 'workflow.yml'
        self.workflow.write_text('launcher: fixture')
        self.receipt = self.root / 'receipt.json'
        self.avd = self.root / 'avd/test.avd/config.ini'
        self.avd.parent.mkdir(parents=True)
        self.avd.write_text('hw.cpu.ncore=2\nhw.ramSize=2048\n')
        (self.avd.parent.parent / 'test.ini').write_text(f'path={self.avd.parent}\npath.rel=avd/test.avd\n')
        environment = patch.dict(os.environ, {'ANDROID_HOME': str(self.sdk)})
        environment.start()
        self.addCleanup(environment.stop)

    def test_raw_symlink_temp_ancestor_still_refuses(self):

        # Give the alias the supported layout: refusal must be the ancestor,
        # not merely the directory name or locator spelling.
        wrapper = self.root / 'wrapper'
        wrapper.symlink_to(self.root, target_is_directory=True)
        config = wrapper / 'avd/test.avd/config.ini'
        with self.assertRaisesRegex(ValueError, 'AVD directory must not be a symlink'):
            cache.avd_layout(config)

    def identity(self):
        return cache.identity(self.sdk, self.workflow, {'arch': 'x86_64', 'cpu': 'fixture'})

    def create_snapshot(self):
        cache.verify(self.receipt, self.identity(), self.avd, mode='miss')
        directory = self.avd.parent / 'snapshots/default_boot'
        directory.mkdir(parents=True)
        for name in cache.SNAPSHOT_FILES:
            (directory / name).write_bytes(b'\x08\x01\x1a\x00\x22\x00\x2a\x00\x50\x00\x58\x00' if name == 'snapshot.pb' else b'offline fixture')
        cache.seal(self.receipt, self.identity(), self.avd)

    def test_deterministic_identity_and_ordinary_miss(self):
        identity = self.identity()
        self.assertEqual(identity, self.identity())
        cache.record(self.receipt, identity)
        cache.verify(self.receipt, identity, self.avd, mode='miss')
        # Only explicit miss bootstrap may precede snapshot creation.
        self.assertFalse((self.avd.parent / 'snapshots').exists())
        with self.assertRaises(OSError):
            cache.verify(self.receipt, identity, self.avd)
        self.create_snapshot()
        cache.verify(self.receipt, identity, self.avd)

    def test_emulator_image_and_profile_tool_changes_invalidate(self):
        original = self.identity()
        for relative in ('emulator/emulator', 'emulator/lib64/gpu.so',
                         'system-images/android-34/google_apis/x86_64/system.img',
                         'system-images/android-34/google_apis/x86_64/source.properties',
                         'cmdline-tools/latest/bin/avdmanager'):
            with self.subTest(relative=relative):
                path = self.sdk / relative
                before = path.read_bytes()
                path.write_bytes(b'changed')
                self.assertNotEqual(original, self.identity())
                path.write_bytes(before)

    def test_launch_configuration_changes_invalidate(self):
        before = self.identity()
        self.workflow.write_text('launcher: different gpu/cores/ram/name/action')
        self.assertNotEqual(before, self.identity())

    def test_host_changes_invalidate(self):
        self.assertNotEqual(self.identity(), cache.identity(
            self.sdk, self.workflow, {'arch': 'arm64', 'cpu': 'other'}))

    def test_missing_sdk_fails_closed(self):
        (self.sdk / 'emulator/emulator').unlink()
        with self.assertRaises(ValueError):
            self.identity()

    def test_identity_has_shared_wall_clock_bound(self):
        with patch.object(cache.time, 'monotonic', side_effect=[0, 121]):
            with self.assertRaisesRegex(ValueError, '120 seconds'):
                self.identity()

    def test_latest_tools_symlink_target_is_hashed(self):
        latest = self.sdk / 'cmdline-tools/latest'
        latest.rename(self.root / 'external-tools')
        latest.symlink_to(self.root / 'external-tools', target_is_directory=True)
        original = self.identity()
        (latest / 'bin/avdmanager').write_bytes(b'changed external profile tool')
        self.assertNotEqual(original, self.identity())

    def test_action_install_mismatch_fails_closed(self):
        cache.record(self.receipt, self.identity())
        (self.sdk / 'emulator/emulator').write_bytes(b'action updated emulator')
        with self.assertRaises(ValueError):
            cache.verify(self.receipt, self.identity(), self.avd)

    def test_different_creation_consumer_avd_config_fails_closed(self):
        cache.record(self.receipt, self.identity())
        self.create_snapshot()
        self.avd.write_text('hw.cpu.ncore=4\nhw.ramSize=2048\n')
        with self.assertRaises(ValueError):
            cache.verify(self.receipt, self.identity(), self.avd)

    def test_action_duplicate_config_append_is_semantically_equal(self):
        cache.record(self.receipt, self.identity())
        self.create_snapshot()
        with self.avd.open('a') as stream:
            stream.write('hw.cpu.ncore=2\n')
        cache.verify(self.receipt, self.identity(), self.avd)

    def test_cli_outputs_and_nonzero_mismatch(self):
        env = {'ANDROID_HOME': str(self.sdk), 'ANDROID_AVD_HOME': str(self.avd.parent.parent),
               'GITHUB_OUTPUT': str(self.root / 'outputs'), 'AVD_CACHE_HIT': ''}
        with patch.dict(os.environ, env), patch.object(cache, 'WORKFLOW', self.workflow), \
                patch.object(cache, 'RECEIPT', self.receipt), patch.object(cache, 'host_identity', return_value={'arch': 'x86_64', 'cpu': 'fixture'}):
            self.assertEqual(0, cache.main(['record']))
            self.assertRegex((self.root / 'outputs').read_text(), r'^key=avd-v3-[0-9a-f]{64}\n$')
            self.assertEqual(0, cache.main(['verify-miss']))
            self.assertEqual(1, cache.main(['verify']))
            self.create_snapshot()
            self.assertEqual(0, cache.main(['verify']))
            (self.sdk / 'emulator/emulator').write_bytes(b'updated')
            self.assertEqual(1, cache.main(['verify']))


    def test_b3_swallowed_shutdown_complete_files_cannot_seal_live_creator(self):
        cache.record(self.receipt, self.identity())
        self.create_snapshot()
        manifest = self.avd.parent / cache.MANIFEST
        manifest.unlink()
        key = self.identity()
        live = f'{os.getuid()} 123 {self.sdk}/emulator/qemu/linux-x86_64/qemu-system-x86_64 -avd test -port 5554\n'
        with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, live)), \
                patch.object(cache.time, 'monotonic', side_effect=[0, 0, 31]), \
                patch.object(cache.time, 'sleep'):
            with self.assertRaises(ValueError):
                cache.seal(self.receipt, key, self.avd)
        self.assertFalse(manifest.exists())

    def test_b3_delayed_exit_waits_before_reading_saved_files(self):
        cache.record(self.receipt, self.identity())
        self.create_snapshot()
        (self.avd.parent / cache.MANIFEST).unlink()
        live = f'{os.getuid()} 123 {self.sdk}/emulator/emulator -avd test -port 5554\n'
        observations = []
        def process_list(*args, **kwargs):
            observations.append('scan')
            return subprocess.CompletedProcess([], 0, live if len(observations) == 1 else '')
        key = self.identity()
        with patch('subprocess.run', side_effect=process_list), patch.object(cache.time, 'sleep') as wait:
            cache.seal(self.receipt, key, self.avd)
        self.assertEqual(observations, ['scan', 'scan'])
        wait.assert_called_once()

    def test_b3_unknown_process_observation_refuses(self):
        for error in (subprocess.TimeoutExpired('ps', 5), subprocess.CalledProcessError(1, 'ps')):
            with self.subTest(error=type(error).__name__), patch('subprocess.run', side_effect=error):
                with self.assertRaises(subprocess.SubprocessError):
                    cache.creator_quiescence()

    def test_b3_unrelated_processes_do_not_block_owned_creator(self):
        unrelated = (f'{os.getuid()} 123 {self.sdk}/emulator/emulator -avd other -port 5556\n'
                     f'{os.getuid()} 124 /outside/emulator -avd test -port 5554\n'
                     f'{os.getuid() + 1} 125 {self.sdk}/emulator/emulator -avd test -port 5554\n')
        with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, unrelated)) as scan:
            cache.creator_quiescence()
        self.assertEqual(scan.call_args.kwargs['timeout'], 5)

    def test_unmatched_quote_in_unrelated_ps_command_does_not_block_quiescence(self):
        output = f'{os.getuid()} 123 /usr/bin/tool unmatched\'argument\n'
        with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, output)):
            cache.creator_quiescence()

    def test_unmatched_quote_in_same_ps_output_cannot_hide_live_creator(self):
        output = (f'{os.getuid()} 123 /usr/bin/tool unmatched\'argument\n'
                  f'{os.getuid()} 124 {self.sdk}/emulator/emulator @test -port 5554\n')
        with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 0, output)), \
                patch.object(cache.time, 'monotonic', side_effect=[0, 0, 31]), \
                patch.object(cache.time, 'sleep'):
            with self.assertRaisesRegex(ValueError, 'did not become quiescent'):
                cache.creator_quiescence()


if __name__ == '__main__':
    unittest.main()
