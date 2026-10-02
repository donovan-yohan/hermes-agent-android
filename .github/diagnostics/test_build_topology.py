import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import build_topology as topology


class BuildTopologyTest(unittest.TestCase):
    def test_opt_out_writes_nothing(self):
        with patch.object(topology, 'allowed', return_value=False):
            with self.assertRaises(AssertionError):
                topology.phase('pre-build')

    def test_bounded_phases_and_prebuilt_manifest(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, RUNNER_TEMP=tmp), patch.object(topology, 'allowed', return_value=True), patch.object(topology, 'sample', return_value={'epoch': 1}), patch.object(topology, 'hashes', return_value={'app': 'abc'}):
            topology.phase('pre-build')
            topology.phase('post-build')
            self.assertEqual(json.loads((Path(tmp) / 'prebuilt-apks.json').read_text()), {'app': 'abc'})
            self.assertEqual(len((Path(tmp) / 'topology-phases.jsonl').read_text().splitlines()), 2)
            with self.assertRaises(AssertionError):
                topology.phase('arbitrary')

    def test_optional_failures_persist_before_later_probes(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, RUNNER_TEMP=tmp, ANDROID_HOME=tmp, ANDROID_SERIAL='emulator-5554'), patch.object(topology, 'allowed', return_value=True), patch.object(topology, 'sample', side_effect=OSError('optional sample failed')):
            sdk = Path(tmp)
            (sdk / 'emulator').mkdir()
            (sdk / 'emulator/source.properties').write_text('Pkg.Revision=37.2.12\n')
            def accel(*args, **kwargs):
                saved = json.loads((sdk / 'topology-environment.json').read_text())
                self.assertEqual(saved['emulator']['value']['properties']['Pkg.Revision'], '37.2.12')
                self.assertEqual(saved['image_properties']['status'], 'unavailable')
                self.assertNotIn('-version', args[0])
                raise subprocess.CalledProcessError(127, args[0], stderr='libpulse.so.0 missing')
            def fingerprint(*args, **kwargs):
                saved = json.loads((sdk / 'topology-environment.json').read_text())
                self.assertEqual(saved['acceleration']['exit'], 127)
                return 'actual-test-fixture-fingerprint'
            with patch.object(topology.subprocess, 'run', side_effect=accel), patch.object(topology.subprocess, 'check_output', side_effect=fingerprint):
                topology.phase('post-boot')
            saved = json.loads((sdk / 'topology-environment.json').read_text())
            self.assertEqual(saved['fingerprint']['status'], 'ok')

    def test_optional_storage_and_subprocess_failures_do_not_escape(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, RUNNER_TEMP=tmp, ANDROID_HOME=tmp, ANDROID_SERIAL='emulator-5554'), patch.object(topology, 'allowed', return_value=True), patch.object(topology, 'sample', return_value={}), patch.object(Path, 'write_text', side_effect=OSError('read-only')), patch.object(topology.subprocess, 'run', side_effect=TimeoutError), patch.object(topology.subprocess, 'check_output', side_effect=OSError) as adb:
            topology.phase('post-boot')
            adb.assert_called_once()
            # Mandatory APK manifest is deliberately not best-effort.
            with patch.object(topology, 'hashes', return_value={'app': 'abc'}), self.assertRaises(OSError):
                topology.phase('post-build')

    def test_build_precedes_every_emulator_action(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/api34-focus-diagnostic.yml').read_text()
        self.assertLess(workflow.index('./gradlew :app:assembleDebug'), workflow.index('uses: reactivecircus/'))
        self.assertEqual(workflow.count('./gradlew :app:assembleDebug'), 1)
        guard = (root / '.github/diagnostics/prebuilt-only.gradle').read_text()
        self.assertIn("task.enabled = task.path == ':app:connectedDebugAndroidTest'", guard)
        self.assertIn('assert graph.allTasks.count { it.enabled } == 1', guard)
