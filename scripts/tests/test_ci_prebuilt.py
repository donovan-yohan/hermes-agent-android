"""Host-only build-before-boot contracts; never start Gradle or adb."""
from pathlib import Path
import unittest
import importlib.util
import json
import tempfile
from unittest.mock import patch
import subprocess

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
            with self.assertRaises(subprocess.CalledProcessError) as failure:
                self.gate.run(self.root)
            self.assertEqual(failure.exception.returncode, 7)
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


if __name__ == '__main__':
    unittest.main()
