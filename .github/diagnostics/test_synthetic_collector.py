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
        self.lines = ['FocusSnapshot: SYNTHETIC DENIAL_ESTABLISHED',
                      'FocusSnapshot: BEGIN test=synthetic', 'FocusSnapshot: PROBE input begin=1',
                      'FocusSnapshot: FocusedWindows:',
                      'FocusSnapshot:     displayId=0, name=PR344_SYNTHETIC_FOCUS_DENIAL',
                      'FocusSnapshot: PROBE activity activities begin=2',
                      'FocusSnapshot: END test=synthetic',
                      'FocusSnapshot: SYNTHETIC ORIGINAL_FAILURE frames=12 lifecycle=RESUMED destroyed=false attached=true focus=false',
                      'FocusSnapshot: SYNTHETIC BEFORE_DIALOG_DISMISS frames=13 lifecycle=RESUMED destroyed=false attached=true focus=false',
                      'FocusSnapshot: SYNTHETIC LIFECYCLE ON_DESTROY']

    def test_expected_deliberate_failure(self):
        self.assertTrue(verify(self.result, self.lines))

    def test_missing_duplicate_reordered_capture(self):
        for i in range(len(self.lines)):
            self.assertFalse(verify(self.result, self.lines[:i] + self.lines[i+1:]))
        self.assertFalse(verify(self.result, self.lines + [self.lines[1]]))
        self.assertFalse(verify(self.result, self.lines[::-1]))

    def test_history_owner_and_dead_owner_rejected(self):
        lines = self.lines.copy()
        lines[4] = 'FocusSnapshot: displayId=0 name=unrelated'
        lines.insert(6, 'FocusSnapshot: displayId=0 name=PR344_SYNTHETIC_FOCUS_DENIAL')
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
