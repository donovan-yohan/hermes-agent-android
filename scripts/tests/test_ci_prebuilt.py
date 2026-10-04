"""Host-only build-before-boot contracts; never start Gradle or adb."""
from pathlib import Path
import unittest
import importlib.util
import json
import tempfile
from unittest.mock import patch
import subprocess
import sys
import os
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import focus_diagnostic as focus

ROOT = Path(__file__).resolve().parents[2]


class PrebuiltTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('ci_prebuilt', ROOT / 'scripts/ci_prebuilt.py')
        assert spec and spec.loader
        self.gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.gate)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for apk in self.gate.APKS:
            path = self.root / apk
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'synthetic apk')
            path.with_name('output-metadata.json').write_text(json.dumps({
                'elements': [{'outputFile': path.name, 'filters': []}]}))
        self.gate.record(self.root)

    def output(self, outcome='UP-TO-DATE'):
        return ''.join(f'> Task :app:{name} {outcome}\n' for name in (
            'compileDebugKotlin', 'compileDebugAndroidTestKotlin',
            'packageDebug', 'packageDebugAndroidTest')) + (
            '> Task :app:compileDebugJavaWithJavac NO-SOURCE\n'
            '> Task :app:preDebugAndroidTestBuild SKIPPED\n'
            '> Task :app:connectedDebugAndroidTest\n')

    def test_missing_or_changed_inputs_never_start_gradle(self):
        self.gate.verify(self.root)
        for kind in ('apk', 'metadata', 'manifest', 'missing'):
            with self.subTest(kind=kind):
                apk = self.root / self.gate.APKS[0]
                target = {'apk': apk, 'missing': apk,
                          'metadata': apk.with_name('output-metadata.json'),
                          'manifest': self.root / self.gate.MANIFEST}[kind]
                original = target.read_bytes()
                target.unlink() if kind == 'missing' else target.write_text('{}')
                with patch.object(self.gate.subprocess, 'run') as run:
                    with self.assertRaises((ValueError, KeyError, FileNotFoundError)):
                        self.gate.run(self.root)
                    run.assert_not_called()
                target.write_bytes(original)

    def test_metadata_cannot_redirect_or_add_apks(self):
        metadata = (self.root / self.gate.APKS[0]).with_name('output-metadata.json')
        for elements in ([{'outputFile': '../other.apk', 'filters': []}], [],
                         [{'outputFile': 'app-debug.apk', 'filters': []}] * 2,
                         [{'outputFile': 'app-debug.apk', 'filters': ['split']}]):
            with self.subTest(elements=elements):
                metadata.write_text(json.dumps({'elements': elements}))
                with self.assertRaisesRegex(ValueError, 'metadata'):
                    self.gate.record(self.root)

    def test_failure_propagates_and_post_run_identity_is_checked(self):
        result = subprocess.CompletedProcess([], 7, self.output())
        with patch.object(self.gate.subprocess, 'run', return_value=result):
            with self.assertRaises(subprocess.CalledProcessError):
                self.gate.run(self.root)
        def mutate(*args, **kwargs):
            (self.root / self.gate.APKS[0]).write_bytes(b'rebuilt')
            return result
        with patch.object(self.gate.subprocess, 'run', side_effect=mutate):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                self.gate.run(self.root)
            self.assertEqual(raised.exception.returncode, 7)
        with self.assertRaisesRegex(ValueError, 'identity'):
            self.gate.verify(self.root)

    def test_normal_graph_and_explicit_outcomes(self):
        self.assertTrue(hasattr(self.gate, 'verify_outcomes'))
        for outcome in ('UP-TO-DATE', 'FROM-CACHE'):
            result = subprocess.CompletedProcess([], 0, self.output(outcome))
            with patch.object(self.gate.subprocess, 'run', return_value=result) as run:
                self.gate.run(self.root)
            self.assertEqual(run.call_args.args[0], [
                './gradlew', ':app:connectedDebugAndroidTest', '--no-daemon',
                '--no-build-cache', '--console=plain'])

    def test_rejects_execution_unknown_or_missing_evidence(self):
        self.assertTrue(hasattr(self.gate, 'verify_outcomes'))
        good = self.output()
        for bad in ('', 'BUILD SUCCESSFUL', good.replace(' UP-TO-DATE', ''),
                    good.replace('UP-TO-DATE', 'UNKNOWN'),
                    good.replace('UP-TO-DATE', 'SKIPPED'),
                    good.replace('> Task :app:packageDebug UP-TO-DATE\n', ''),
                    good.replace('> Task :app:connectedDebugAndroidTest\n', ''),
                    good + '> Task :app:compileNewVariant\n',
                    good + '> Task :app:dexBuilderDebug\n',
                    good + '> Task :app:packageDebug FAILED\n'):
            with self.subTest(output=bad), self.assertRaises(ValueError):
                self.gate.verify_outcomes(bad)



    def test_passive_environment_boundary_and_optout(self):
        workflow = (ROOT / '.github/workflows/android-exact-head.yml').read_text()
        self.assertEqual(workflow.count('FOCUS_DIAGNOSTIC=true'), 1)
        connected = workflow.split('- name: Run the instrumented lane', 1)[1].split('- name:', 1)[0]
        self.assertIn('FOCUS_DIAGNOSTIC=true FOCUS_NONCE=', connected)
        env = dict(os.environ, ANDROID_SERIAL=focus.SERIAL)
        env.pop('FOCUS_DIAGNOSTIC', None)
        child = subprocess.run([sys.executable, '-m', 'unittest',
                                'scripts.tests.test_ci_prebuilt.PrebuiltTest.test_normal_graph_and_explicit_outcomes'],
                               cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(child.returncode, 0, child.stdout + child.stderr)
        with patch.dict(os.environ, {}, clear=True), patch.object(focus, 'adb') as adb:
            focus.collect(self.root, focus.arm())
            adb.assert_not_called()
        self.assertFalse(list((self.root / 'app/build').glob('focus-*')))

    def test_original_exception_identity_survives_observer_and_identity_errors(self):
        original = subprocess.CalledProcessError(23, ['gradle'])
        with patch.object(focus, 'arm', return_value=None), patch.object(focus, 'collect', side_effect=OSError()), \
             patch.object(self.gate, 'verify', side_effect=[None, OSError()]), \
             patch.object(self.gate.subprocess, 'run', side_effect=original):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                self.gate.run(self.root)
            self.assertIs(raised.exception, original)

    def test_retention_after_target_uninstall_is_nonce_bound_unique_schema2(self):
        nonce = 'a' * 32
        value = dict(schema=2, before=None, after=None, parserShape=None,
                     ownerStatus='MATCHED', owner=dict(display=0, ownerPid=123, ownerUid=1000))
        for mode in ('valid', 'stale', 'duplicate', 'secret'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                logs = root / 'app/build/outputs/androidTest-results/connected'
                logs.mkdir(parents=True)
                marker = 'b' * 32 if mode == 'stale' else nonce
                payload = dict(value, title='private') if mode == 'secret' else value
                line = 'I HermesFocusReduced: ' + marker + ':1:' + json.dumps(payload) + '\n'
                (logs / 'logcat-test.txt').write_text(line * (2 if mode == 'duplicate' else 1))
                def adb(*args):
                    if args[0] == 'get-serialno': return focus.SERIAL
                    if args == ('shell', 'getprop', 'debug.hermes.focus_attestation'):
                        return nonce + ':' + focus.SERIAL + ':' + focus.PROVENANCE
                    raise AssertionError('No pull or target probe after AGP uninstall')
                with patch.object(focus, 'adb', side_effect=adb): focus.collect(root, nonce)
                receipt = root / ('app/build/focus-' + nonce + '/readiness-1.json')
                self.assertEqual(receipt.exists(), mode == 'valid')
                if receipt.exists(): self.assertEqual(json.loads(receipt.read_text()), value)

    def test_cli_preserves_gradle_exit_status(self):
        scripts = self.root / 'scripts'
        scripts.mkdir()
        for name in ('ci_prebuilt.py', 'focus_diagnostic.py'):
            (scripts / name).write_text((ROOT / 'scripts' / name).read_text())
        code = """import runpy, sys, subprocess
from unittest.mock import patch
sys.path.insert(0, 'scripts')
import focus_diagnostic, ci_prebuilt
from pathlib import Path
ci_prebuilt.record(Path.cwd())
sys.argv = ['ci_prebuilt.py', 'connected']
with patch('focus_diagnostic.arm', return_value=None), patch('focus_diagnostic.collect'), patch('subprocess.run', side_effect=subprocess.CalledProcessError(23, ['gradle'])):
    runpy.run_path('scripts/ci_prebuilt.py', run_name='__main__')
"""
        child = subprocess.run([sys.executable, '-c', code], cwd=self.root, capture_output=True, text=True)
        self.assertEqual(child.returncode, 23, child.stderr)

    def test_binding_and_safe_schema(self):
        env = dict(FOCUS_DIAGNOSTIC='true', FOCUS_NONCE='a' * 32,
                   ANDROID_SERIAL=focus.SERIAL, FOCUS_DISPOSABLE=focus.PROVENANCE)
        for key, val in [('ANDROID_SERIAL', 'emulator-5556'), ('FOCUS_NONCE', 'bad'),
                         ('FOCUS_DISPOSABLE', 'personal'), ('GRADLE_OPTS', 'focusSnapshotNonce=x')]:
            with patch.object(focus, 'adb') as adb, self.assertRaises(ValueError):
                focus.arm(dict(env, **{key: val}))
            adb.assert_not_called()
        for text in ('{"schema":2,"schema":2}', '{"title":"private"}', 'x' * 4097):
            with self.assertRaises(ValueError): focus.sanitize(text)

if __name__ == '__main__':
    unittest.main()
