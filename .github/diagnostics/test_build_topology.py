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

    def test_build_precedes_every_emulator_action(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/api34-focus-diagnostic.yml').read_text()
        self.assertLess(workflow.index('./gradlew :app:assembleDebug'), workflow.index('uses: reactivecircus/'))
        self.assertEqual(workflow.count('./gradlew :app:assembleDebug'), 1)
        guard = (root / '.github/diagnostics/prebuilt-only.gradle').read_text()
        self.assertIn("task.enabled = task.path == ':app:connectedDebugAndroidTest'", guard)
        self.assertIn('assert graph.allTasks.count { it.enabled } == 1', guard)
