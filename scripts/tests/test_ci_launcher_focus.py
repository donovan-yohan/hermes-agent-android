#!/usr/bin/env python3
"""Host-only guard for the disposable workflow's launcher intervention."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/android-exact-head.yml'
LAUNCHER = 'com.google.android.apps.nexuslauncher'


class CiLauncherFocusTest(unittest.TestCase):
    def test_workflow_disables_only_known_launcher_between_prep_and_tests(self):
        text = WORKFLOW.read_text()
        lane = text.split('      - name: Run the instrumented lane\n', 1)[1]
        lane = lane.split('      - name:', 1)[0]
        self.assertIn('          emulator-port: 5554\n', lane)
        self.assertIn('    env:\n      ANDROID_SERIAL: emulator-5554\n',
                      text.split('  instrumented:\n', 1)[1].split('    steps:', 1)[0])
        self.assertIn('-no-snapshot-save', lane)
        commands = [line.strip() for line in lane.split('          script: |\n', 1)[1].splitlines()
                    if line.strip() and not line.lstrip().startswith('#')]
        intervention = (f'ci_launcher_state="$(adb -s "$ANDROID_SERIAL" shell pm disable-user --user 0 {LAUNCHER})"'
                        f' && test "$(printf \'%s\' "$ci_launcher_state" | tr -d \'\\r\')"'
                        f' = "Package {LAUNCHER} new state: disabled-user"')
        self.assertEqual(commands[:3], [
            'python3 scripts/ci_avd_cache.py verify',
            './scripts/prepare-ci-emulator.sh',
            intervention,
        ])
        self.assertIn('&& FOCUS_DIAGNOSTIC=true FOCUS_NONCE=', commands[-1])
        self.assertTrue(commands[-1].endswith('python3 scripts/ci_prebuilt.py connected'))
        self.assertEqual(text.count(' shell pm disable-user '), 1)
        self.assertNotIn(' shell am force-stop ', text)
        self.assertNotIn('hide_error_dialogs', text)
        # Execute the actual workflow intervention. Refusal/incorrect readback must
        # fail before workload; only the known synthetic launcher may be touched.
        with tempfile.TemporaryDirectory() as directory:
            adb = Path(directory) / 'adb'
            log = Path(directory) / 'calls.jsonl'
            adb.write_text('#!/usr/bin/env python3\nimport json, os, sys\n'
                           'with open(os.environ["ADB_LOG"], "a") as f: f.write(json.dumps(sys.argv[1:]) + "\\n")\n'
                           'print(os.environ["ADB_RESULT"] + "\\r")\n'
                           'sys.exit(int(os.environ["ADB_EXIT"]))\n')
            adb.chmod(0o755)
            expected = f'Package {LAUNCHER} new state: disabled-user'
            for output, exit_code, succeeds in [(expected, 0, True), (expected, 1, False),
                    (f'Package {LAUNCHER} new state: enabled', 0, False),
                    ('', 1, False), ('Error: protected package', 1, False)]:
                with self.subTest(output=output, exit_code=exit_code):
                    log.write_text('')
                    result = subprocess.run(['sh', '-ec', commands[2]], capture_output=True, text=True,
                        env={**os.environ, 'PATH': directory + ':' + os.environ['PATH'],
                             'ANDROID_SERIAL': 'emulator-5554', 'ADB_LOG': str(log),
                             'ADB_RESULT': output, 'ADB_EXIT': str(exit_code)})
                    self.assertEqual(result.returncode == 0, succeeds)
                    self.assertEqual([json.loads(line) for line in log.read_text().splitlines()],
                        [['-s', 'emulator-5554', 'shell', 'pm', 'disable-user', '--user', '0', LAUNCHER]])

    def test_normal_preparation_never_stops_a_package(self):
        # The workflow is the opt-in: invoking preparation normally (even in CI)
        # must retain its old behavior. No device is used by this host test.
        with tempfile.TemporaryDirectory() as directory:
            adb = Path(directory) / 'adb'
            log = Path(directory) / 'calls.jsonl'
            adb.write_text('#!/usr/bin/env python3\nimport json, os, sys\n'
                           'with open(os.environ["ADB_LOG"], "a") as f: f.write(json.dumps(sys.argv[1:]) + "\\n")\n'
                           'if sys.argv[1:] == ["shell", "ime", "list", "-s", "-a"]: print("synthetic/.Ime")\n')
            adb.chmod(0o755)
            for ci in ('false', 'true'):
                with self.subTest(github_actions=ci):
                    log.write_text('')
                    subprocess.run(['bash', str(ROOT / 'scripts/prepare-ci-emulator.sh')], check=True,
                                   capture_output=True, text=True,
                                   env={**os.environ, 'PATH': directory + ':' + os.environ['PATH'],
                                        'ADB_LOG': str(log), 'GITHUB_ACTIONS': ci,
                                        'ANDROID_SERIAL': 'synthetic-personal-device'})
                    calls = [json.loads(line) for line in log.read_text().splitlines()]
                    self.assertEqual(calls, [
                        ['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'],
                        ['shell', 'wm', 'dismiss-keyguard'],
                        ['shell', 'svc', 'power', 'stayon', 'true'],
                        ['shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1'],
                        ['shell', 'ime', 'list', '-s', '-a'],
                        ['shell', 'ime', 'enable', 'synthetic/.Ime'],
                        ['shell', 'ime', 'set', 'synthetic/.Ime'],
                    ])


if __name__ == '__main__':
    unittest.main()
