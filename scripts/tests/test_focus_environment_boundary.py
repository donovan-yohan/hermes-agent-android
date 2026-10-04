"""Execute the workflow prebuild mocks under its actual inherited environment."""
import os
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]

class EnvironmentBoundaryTest(unittest.TestCase):
    def test_workflow_host_gate_is_connected_only(self):
        workflow = (ROOT / '.github/workflows/visual-parity-capture.yml').read_text()
        binding = workflow.split('- name: Bind fresh synthetic run paths', 1)[1].split('- name:', 1)[0]
        env = dict(os.environ, FOCUS_NONCE='a' * 32,
                   FOCUS_DISPOSABLE='api34-run-scoped-snapshot', ANDROID_SERIAL='emulator-5554')
        env.pop('FOCUS_DIAGNOSTIC', None)
        if 'echo "FOCUS_DIAGNOSTIC=true"' in binding:
            env['FOCUS_DIAGNOSTIC'] = 'true'
        result = subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s',
                                 'scripts/tests', '-p', 'test_ci_prebuilt.py', '-v'],
                                cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('FOCUS_DIAGNOSTIC', env)
        connected = workflow.split('- name: Run the instrumented lane', 1)[1].split('- name:', 1)[0]
        self.assertIn('FOCUS_DIAGNOSTIC=true python3 scripts/ci_prebuilt.py connected', connected)
        snapshot = workflow.split('- name: Run snapshot comparison on the prebuilt APK pair', 1)[1].split('- name:', 1)[0]
        self.assertIn('FOCUS_DIAGNOSTIC=true python3 scripts/ci_prebuilt.py connected', snapshot)
        self.assertEqual(workflow.count('FOCUS_DIAGNOSTIC=true'), 2)

if __name__ == '__main__':
    unittest.main()
