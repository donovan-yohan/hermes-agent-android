"""Host-only build-before-boot contracts; never start Gradle or adb."""
from pathlib import Path
import unittest
import importlib.util
import json
import tempfile
from unittest.mock import patch
import subprocess

SCRIPT = Path(__file__).resolve().parents[1] / 'ci_prebuilt.py'

ROOT = Path(__file__).resolve().parents[2]


class WorkflowTest(unittest.TestCase):
    def test_assembly_precedes_both_emulator_boots(self):
        text = (ROOT / '.github/workflows/android-exact-head.yml').read_text()
        lane = text.split('  instrumented:\n', 1)[1].split('  prune:', 1)[0]
        build = lane.index('./gradlew :app:assembleDebug :app:assembleDebugAndroidTest')
        self.assertLess(build, lane.index('- name: Create the AVD snapshot'))
        self.assertLess(build, lane.index('- name: Run the instrumented lane'))
        self.assertEqual(lane.count('./gradlew :app:assembleDebug'), 1)


class IdentityTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('ci_prebuilt', SCRIPT)
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

    def test_exact_apks_and_metadata_required(self):
        self.gate.verify(self.root)
        (self.root / self.gate.APKS[0]).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'identity'):
            self.gate.verify(self.root)

    def test_missing_or_changed_inputs_never_start_gradle(self):
        for kind in ('apk', 'metadata', 'manifest', 'missing'):
            with self.subTest(kind=kind):
                apk = self.root / self.gate.APKS[0]
                target = {'apk': apk, 'missing': apk,
                          'metadata': apk.with_name('output-metadata.json'),
                          'manifest': self.root / self.gate.MANIFEST}[kind]
                original = target.read_bytes()
                if kind == 'missing':
                    target.unlink()
                else:
                    target.write_text('{}')
                with patch.object(self.gate.subprocess, 'run') as run:
                    with self.assertRaises((ValueError, KeyError, FileNotFoundError)):
                        self.gate.run(self.root, preflight=False)
                    run.assert_not_called()
                target.write_bytes(original)

    def test_metadata_cannot_redirect_or_add_apks(self):
        metadata = (self.root / self.gate.APKS[0]).with_name('output-metadata.json')
        for elements in ([{'outputFile': '../other.apk', 'filters': []}], [],
                         [{'outputFile': 'app-debug.apk', 'filters': []}] * 2,
                         [{'outputFile': 'app-debug.apk', 'filters': ['split']} ]):
            with self.subTest(elements=elements):
                metadata.write_text(json.dumps({'elements': elements}))
                with self.assertRaisesRegex(ValueError, 'metadata'):
                    self.gate.record(self.root)

    def test_failure_propagates_and_post_run_identity_is_checked(self):
        with patch.object(self.gate.subprocess, 'run',
                          side_effect=subprocess.CalledProcessError(7, 'synthetic')):
            with self.assertRaises(subprocess.CalledProcessError):
                self.gate.run(self.root, preflight=False)
        def mutate(*args, **kwargs):
            (self.root / self.gate.APKS[0]).write_bytes(b'rebuilt')
        with patch.object(self.gate.subprocess, 'run', side_effect=mutate):
            with self.assertRaisesRegex(ValueError, 'identity'):
                self.gate.run(self.root, preflight=False)

    def test_preflight_and_connected_use_same_guard_without_cache(self):
        self.assertTrue(hasattr(self.gate, 'run'), 'connected wrapper missing')
        with patch.object(self.gate.subprocess, 'run') as run:
            self.gate.run(self.root, preflight=True)
            first = run.call_args.args[0]
            self.gate.run(self.root, preflight=False)
            second = run.call_args.args[0]
        self.assertEqual(first, second + ['--dry-run'])
        self.assertIn('--no-configuration-cache', second)
        self.assertIn('scripts/ci-prebuilt.gradle', second)
        self.assertEqual(second.count(':app:connectedDebugAndroidTest'), 1)
        self.assertNotIn('--continue', second)


class GraphGuardTest(unittest.TestCase):
    def test_real_groovy_guard_rejects_graph_and_input_mutations(self):
        guard = ROOT / 'scripts/ci-prebuilt.gradle'
        self.assertTrue(guard.exists(), 'task graph guard missing')
        # Load only Groovy's interpreter jars. This does NOT start Gradle,
        # resolve dependencies, acquire its locks, compile the app or use adb.
        libs = list((Path.home() / '.gradle/wrapper/dists').glob(
            'gradle-9.1.0-*/**/gradle-9.1.0/lib/groovy-4.0.28.jar'))
        self.assertTrue(libs, 'run after assembly downloads the pinned wrapper')
        result = subprocess.run(['java', '-cp', str(libs[0]), 'groovy.ui.GroovyMain',
                                 str(ROOT / 'scripts/tests/ci_prebuilt_guard_test.groovy'),
                                 str(guard)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('guard scenarios passed', result.stdout)


if __name__ == '__main__':
    unittest.main()
