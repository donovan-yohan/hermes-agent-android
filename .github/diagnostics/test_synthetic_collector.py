import copy
import unittest
from synthetic_collector import verify


class SyntheticCollectorTest(unittest.TestCase):
    def setUp(self):
        # Explicitly fabricated parser fixture, never Android evidence.
        name = 'SyntheticFocusDenialTest#deliberateDenialCapturesOwnerBeforeTeardown'
        self.result = dict(raw_exit=1, timed_out=False, same_apks=True,
                           exact_identity_multiset=True, exact_start_finish=True,
                           cases=[dict(id=name, skipped=False, failures=[
                               'Activity not awake, unlocked and input-focused within 15000 ms'])],
                           failure_events=['com.hermesagent.mobile.device.' + name])
        identity = ('main=true activityAttached=true dialogAttached=true activityDisplay=0 '
                    'dialogDisplay=0 activityFocus=false dialogFocus=true activityWindowIdFocus=false '
                    'dialogWindowIdFocus=true distinctTokens=true activityToken=client-1 '
                    'dialogToken=client-2 activityDestroyed=false dialogShowing=true')
        self.lines = ['FocusSnapshot: SYNTHETIC DENIAL_ESTABLISHED',
                      'FocusSnapshot: BEGIN test=synthetic',
                      'FocusSnapshot: WINDOW_IDENTITY phase=before uptime=1 ' + identity,
                      'FocusSnapshot: PROBE input begin=2 end=3',
                      'FocusSnapshot: FocusedWindows:',
                      'FocusSnapshot:     displayId=0, name=abc com.hermesagent.mobile/androidx.activity.ComponentActivity',
                      'FocusSnapshot: WINDOW_IDENTITY phase=after uptime=4 ' + identity,
                      'FocusSnapshot: PROBE activity activities begin=5',
                      'FocusSnapshot: END test=synthetic',
                      'FocusSnapshot: SYNTHETIC ORIGINAL_FAILURE frames=12 lifecycle=RESUMED destroyed=false attached=true focus=false',
                      'FocusSnapshot: SYNTHETIC BEFORE_DIALOG_DISMISS frames=13 lifecycle=RESUMED destroyed=false attached=true focus=false',
                      'FocusSnapshot: SYNTHETIC LIFECYCLE ON_DESTROY']

    def test_actual_decors_probed_on_main_around_input(self):
        from pathlib import Path
        root = Path(__file__).resolve().parents[2]
        source = (root / 'app/src/androidTest/kotlin/com/hermesagent/mobile/device/SyntheticFocusDenialTest.kt').read_text()
        capture = (root / 'app/src/androidTest/kotlin/com/hermesagent/mobile/device/FailureFocusSnapshot.kt').read_text()
        self.assertIn('val activityDecor = compose.activity.window.decorView', source)
        self.assertIn('val dialogDecor = checkNotNull(dialog.window).decorView', source)
        self.assertIn('activityDecor.windowId?.isFocused', source)
        self.assertIn('dialogDecor.windowId?.isFocused', source)
        self.assertIn('FailureFocusSnapshot.syntheticIdentityProbe = { phase ->', source)
        self.assertIn('title = owner.window.attributes.title', source)
        self.assertLess(capture.index('identity("before")'), capture.index('probe("input")'))
        self.assertLess(capture.index('probe("input")'), capture.index('identity("after")'))

    def test_no_optional_dependency_receipt_required(self):
        from pathlib import Path
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/api34-focus-diagnostic.yml').read_text()
        self.assertNotIn('diagnosticResolvedVersions', workflow)
        self.assertNotIn('api34-resolved-versions.txt', workflow)
        self.assertFalse((root / '.github/diagnostics/resolved-versions.init.gradle').exists())
        self.assertTrue(verify(self.result, self.lines))

    def test_expected_deliberate_failure(self):
        self.assertTrue(verify(self.result, self.lines))

    def test_title_alone_is_not_owner_evidence(self):
        self.assertFalse(verify(self.result, [x for x in self.lines if 'WINDOW_IDENTITY ' not in x]))

    def test_missing_duplicate_reordered_capture(self):
        for i in range(len(self.lines)):
            self.assertFalse(verify(self.result, self.lines[:i] + self.lines[i+1:]))
        self.assertFalse(verify(self.result, self.lines + [self.lines[1]]))
        self.assertFalse(verify(self.result, self.lines[::-1]))

    def test_identical_titles_wrong_owner_is_rejected(self):
        # Same WM/component title on both roles: only exact WindowId is authority.
        self.assertTrue(verify(self.result, self.lines))
        for field in ('dialogFocus', 'dialogWindowIdFocus', 'dialogAttached', 'distinctTokens'):
            with self.subTest(field=field):
                self.assertFalse(verify(self.result, [x.replace(field + '=true', field + '=false') for x in self.lines]))
        for old, new in [('activityWindowIdFocus=false', 'activityWindowIdFocus=true'),
                         ('activityFocus=false', 'activityFocus=true'),
                         ('dialogToken=client-2', 'dialogToken=client-1'),
                         ('dialogToken=client-2', 'dialogToken=null'),
                         ('dialogDisplay=0', 'dialogDisplay=1')]:
            self.assertFalse(verify(self.result, [x.replace(old, new) for x in self.lines]))
        changed = self.lines.copy()
        changed[6] = changed[6].replace('client-2', 'client-3')
        self.assertFalse(verify(self.result, changed))

    def test_history_owner_and_dead_owner_rejected(self):
        lines = self.lines.copy()
        lines[5] = 'FocusSnapshot: displayId=1 name=unrelated'
        lines.insert(9, 'FocusSnapshot: displayId=0 name=PR344_SYNTHETIC_FOCUS_DENIAL')
        self.assertFalse(verify(self.result, lines))
        self.assertFalse(verify(self.result, [x.replace('destroyed=false', 'destroyed=true') for x in self.lines]))

    def test_success_wrong_failure_and_hash_mismatch_rejected(self):
        for field, value in [('raw_exit', 0), ('timed_out', True), ('same_apks', False), ('failure_events', [])]:
            result = copy.deepcopy(self.result)
            result[field] = value
            self.assertFalse(verify(result, self.lines))
        result = copy.deepcopy(self.result)
        result['cases'][0]['failures'] = ['Synthetic dialog did not acquire focus']
        self.assertFalse(verify(result, self.lines))


if __name__ == '__main__':
    unittest.main()
