"""Host privacy preconditions; no device or Gradle execution."""
import os
import unittest
from unittest.mock import patch
import api34_connected as lane


class FailureOnlyHostGate(unittest.TestCase):
    env = {'GITHUB_ACTIONS': 'true', 'FOCUS_SYNTHETIC_OPT_IN': 'b291-failure-only',
           'ANDROID_SERIAL': 'emulator-5554', 'RUNNER_TEMP': '/not-created'}

    def test_opted_out_never_calls_adb(self):
        for key in ('GITHUB_ACTIONS', 'FOCUS_SYNTHETIC_OPT_IN', 'ANDROID_SERIAL'):
            with self.subTest(key=key), patch.dict(os.environ, {**self.env, key: ''}, clear=True), \
                    patch.object(lane.subprocess, 'check_output') as call:
                with self.assertRaises(AssertionError):
                    lane.main()
                call.assert_not_called()

    def test_serial_mismatch_does_not_probe_or_create_outputs(self):
        with patch.dict(os.environ, self.env, clear=True), \
                patch.object(lane.subprocess, 'check_output', return_value='emulator-5556') as call:
            with self.assertRaises(AssertionError):
                lane.main()
            self.assertEqual(call.call_count, 1)
            self.assertEqual(call.call_args.args[0], ['adb', '-s', 'emulator-5554', 'get-serialno'])

    def test_wrong_api_rejected_before_nonce(self):
        with patch.dict(os.environ, self.env, clear=True), \
                patch.object(lane.subprocess, 'check_output', side_effect=['emulator-5554', '37']) as call:
            with self.assertRaises(AssertionError):
                lane.main()
            self.assertEqual(call.call_count, 2)

    def test_non_disposable_avd_rejected(self):
        with patch.dict(os.environ, self.env, clear=True), \
                patch.object(lane.subprocess, 'check_output', side_effect=['emulator-5554', '34', 'x86_64', 'personal']) as call:
            with self.assertRaises(AssertionError):
                lane.main()
            self.assertEqual(call.call_count, 4)
