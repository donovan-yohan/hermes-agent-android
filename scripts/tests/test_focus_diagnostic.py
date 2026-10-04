import json
import os
import sys
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import focus_diagnostic as focus
import ci_prebuilt as prebuilt

NONCE = 'a' * 32
ENV = {'FOCUS_DIAGNOSTIC': 'true', 'FOCUS_NONCE': NONCE,
       'ANDROID_SERIAL': focus.SERIAL, 'FOCUS_DISPOSABLE': focus.PROVENANCE}
RECORD = json.dumps({'schema': 1, 'before': None, 'owner':
                    {'display': 0, 'ownerPid': 123, 'ownerUid': 10001}, 'after': None})


class FocusDiagnosticTests(unittest.TestCase):
    def test_opt_out_has_no_adb_or_directories(self):
        with patch.object(focus, 'adb') as adb, patch.object(Path, 'mkdir') as mkdir:
            self.assertIsNone(focus.arm({}))
            focus.collect(Path('.'), None)
            adb.assert_not_called()
            mkdir.assert_not_called()
            self.assertEqual(focus.arguments(None), [])

    def test_bindings(self):
        for key, value in [('ANDROID_SERIAL', 'emulator-5556'), ('FOCUS_NONCE', 'bad'),
                           ('FOCUS_DISPOSABLE', 'personal'), ('GRADLE_OPTS', 'focusSnapshotNonce=x')]:
            with patch.object(focus, 'adb') as adb, self.assertRaises(ValueError):
                focus.arm(dict(ENV, **{key: value}))
            adb.assert_not_called()

    def test_attestation_and_serial_mismatch(self):
        good = [focus.SERIAL, '1', '34', NONCE + ':' + focus.SERIAL + ':' + focus.PROVENANCE]
        with patch.object(focus, 'adb', side_effect=good):
            self.assertEqual(focus.arm(ENV), NONCE)
        for index in range(4):
            bad = good.copy()
            bad[index] = 'wrong'
            with patch.object(focus, 'adb', side_effect=bad), self.assertRaises(ValueError):
                focus.arm(ENV)

    def test_secret_fields_duplicate_overflow_rejected(self):
        self.assertEqual(json.loads(focus.sanitize(RECORD))['owner']['ownerPid'], 123)
        for text in [RECORD.replace('"schema": 1', '"schema": 1, "title": "SECRET"'),
                     RECORD.replace('"schema": 1', '"schema": 1, "schema": 1'),
                     RECORD.replace('123', '2147483648'), RECORD.replace('123', 'true'),
                     'SECRET' * 1000]:
            with self.assertRaises((ValueError, TypeError)):
                focus.sanitize(text)

    def test_probe_and_storage_errors_do_not_skip_second_probe(self):
        for failure in ('probe', 'mkdir', 'write', 'stale'):
            values = [focus.SERIAL, NONCE + ':' + focus.SERIAL + ':' + focus.PROVENANCE]
            values += [OSError('SECRET'), RECORD] if failure == 'probe' else [RECORD, RECORD]
            with patch.object(focus, 'adb', side_effect=values) as adb, \
                 patch.object(Path, 'mkdir', side_effect=OSError('SECRET') if failure in ('mkdir', 'stale') else None), \
                 patch.object(Path, 'write_text', side_effect=OSError('SECRET') if failure == 'write' else None) as write:
                focus.collect(Path('.'), NONCE)
                self.assertEqual(adb.call_count, 4)
                if failure in ('mkdir', 'stale'):
                    write.assert_not_called()

    def test_collection_rechecks_serial_before_any_storage(self):
        with patch.object(focus, 'adb', return_value='emulator-5556'), patch.object(Path, 'mkdir') as mkdir:
            focus.collect(Path('.'), NONCE)
            mkdir.assert_not_called()

    def test_original_nonzero_and_launch_exception_survive_finalization(self):
        failures = [subprocess.CalledProcessError(23, ['gradle']), OSError('launch')]
        for original in failures:
            with patch.object(prebuilt.focus_diagnostic, 'arm', return_value=None), \
                 patch.object(prebuilt, 'verify', side_effect=[None, OSError('storage')]), \
                 patch.object(prebuilt.subprocess, 'run', side_effect=original), \
                 patch.object(prebuilt.focus_diagnostic, 'collect', side_effect=OSError('probe')) as collect:
                with self.assertRaises(type(original)) as raised:
                    prebuilt.run(Path('.'))
                self.assertIs(raised.exception, original)
                collect.assert_called_once()

    def test_agp_graph_unchanged_and_original_exit(self):
        result = subprocess.CompletedProcess([], 17, 'failed\n')
        with patch.object(prebuilt.focus_diagnostic, 'arm', return_value=NONCE), \
             patch.object(prebuilt, 'verify'), patch.object(prebuilt.subprocess, 'run', return_value=result) as run, \
             patch.object(prebuilt.focus_diagnostic, 'collect', side_effect=OSError()):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                prebuilt.run(Path('.'))
            self.assertEqual(raised.exception.returncode, 17)
            command = run.call_args.args[0]
            self.assertEqual(command[:5], ['./gradlew', ':app:connectedDebugAndroidTest', '--no-daemon', '--no-build-cache', '--console=plain'])
            self.assertFalse(any('class=' in arg or 'am instrument' in arg for arg in command))

    def test_rule_and_workflow_contract(self):
        root = Path(__file__).resolve().parents[2]
        rule = (root / 'app/src/androidTest/kotlin/com/hermesagent/mobile/device/WindowReadinessRule.kt').read_text()
        self.assertLess(rule.index('FailureFocusSnapshot.capture(activity)'), rule.index('base.evaluate()'))
        self.assertIn('throw failure', rule)
        workflow = (root / '.github/workflows/visual-parity-capture.yml').read_text()
        self.assertLess(workflow.index(':app:assembleDebugAndroidTest'), workflow.index('Run the instrumented lane'))
        self.assertEqual(workflow.count('am force-stop'), 1)
        self.assertEqual(workflow.count('./gradlew :app:assembleDebug :app:assembleDebugAndroidTest'), 1)
        self.assertEqual(workflow.count('python3 scripts/ci_prebuilt.py record'), 1)
        self.assertEqual(workflow.count('uses: reactivecircus/android-emulator-runner@'), 1)
        self.assertIn('force-avd-creation: true', workflow)
        self.assertIn('avd-name: focus-cold-${{ github.run_id }}-${{ github.run_attempt }}', workflow)
        self.assertIn('emulator-options: -wipe-data -no-snapshot-load -no-snapshot-save ', workflow)
        self.assertNotIn('actions/cache', workflow)
        self.assertNotIn('am instrument', workflow)
        self.assertNotIn('hide_error_dialogs', workflow)
        self.assertIn('python3 scripts/ci_prebuilt.py connected', workflow)
        self.assertIn('github.run_attempt == 1', workflow)


if __name__ == '__main__':
    unittest.main()
