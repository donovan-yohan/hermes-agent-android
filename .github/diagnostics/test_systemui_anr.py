import unittest
from unittest.mock import patch
from pathlib import Path
import tempfile
from systemui_anr import extract, collect


class SystemUiAllowlist(unittest.TestCase):
    def test_success_and_optout_do_not_probe_or_write(self):
        with tempfile.TemporaryDirectory() as temp, patch('systemui_anr.subprocess.check_output') as adb:
            out = Path(temp) / 'absent'
            with patch('systemui_anr.allowed', return_value=True):
                collect(out, False, 'nonce')
            with patch('systemui_anr.allowed', return_value=False):
                collect(out, True, 'nonce')
            adb.assert_not_called()
            self.assertFalse(out.exists())

    def test_serial_mismatch_never_bugreports(self):
        with patch('systemui_anr.allowed', return_value=True), patch('systemui_anr.subprocess.check_output', return_value='wrong'), patch('systemui_anr.subprocess.run') as report:
            with self.assertRaises(RuntimeError):
                collect(Path('/not-created'), True, 'nonce')
            report.assert_not_called()

    def test_only_exact_process(self):
        text = '''----- pid 1 at 2026-10-02 00:00:00 -----
Cmd line: private.app
"main" prio=5 tid=1 Native
  at private.Secret.read(Secret.java:1)
----- end 1 -----
----- pid 2 at 2026-10-02 00:01:00 -----
Cmd line: com.android.systemui
"main" prio=5 tid=1 Native
  at android.os.BinderProxy.transactNative(Native method)
  at com.android.systemui.Test.run(Test.java:1)
secret=/private/path https://private.example a@private.example
----- end 2 -----
'''
        result = str(extract(text))
        self.assertIn('BinderProxy.transactNative', result)
        self.assertNotIn('private', result)
        self.assertNotIn('Secret', result)

    def test_bounded_reason_and_cpu(self):
        text = '''ANR in com.android.systemui
PID: 42
Reason: Input dispatching timed out (https://private.example /data/private)
CPU usage from 100ms to 200ms later:
  50% 42/com.android.systemui: 20% user + 30% kernel
  20% 99/private.app: 20% user
  80% TOTAL: 40% user + 40% kernel
ANR in private.app
Reason: private reason
'''
        result = str(extract(text))
        self.assertIn('Input dispatching timed out', result)
        self.assertIn('TOTAL', result)
        self.assertNotIn('private', result)
        self.assertLess(len(result), 5000)

    def test_wrong_identity_and_unframed_rejected(self):
        self.assertFalse(extract('Cmd line: com.android.systemui\n  at android.os.Binder.run(Binder.java:1)')['traces'])
        self.assertFalse(extract('ANR in com.android.systemui.fake\nReason: bad')['records'])

    def test_thread_and_record_caps(self):
        text = ('ANR in com.android.systemui\nReason: Input dispatching timed out\n' * 1000)
        self.assertLessEqual(len(extract(text)['records']), 8)
        self.assertLess(len(str(extract(text))), 60000)


if __name__ == '__main__':
    unittest.main()
