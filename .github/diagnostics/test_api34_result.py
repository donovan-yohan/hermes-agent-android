"""Fixtures: unmodified synthetic streams from Actions run 36933545768.
Negative cases mutate those streams; they are not claimed device executions.
"""
import ast
from pathlib import Path
import unittest
from api34_result import validate

HERE = Path(__file__).parent

def expected_tests():
    tree = ast.parse((HERE / 'api34_focus.py').read_text())
    scope = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in ('composer', 'predecessor', 'full_suite') for t in node.targets):
            exec(compile(ast.Module(body=[node], type_ignores=[]), '<constants>', 'exec'), scope)
    return scope['full_suite']

class ResultTests(unittest.TestCase):
    def setUp(self):
        self.expected = expected_tests()
        self.text = (HERE / 'fixtures/full.txt').read_text()

    def check(self, text, **kwargs):
        return validate(text, self.expected, require_order=False, **kwargs)['passed']

    def test_retained_full_success_accepts_runner_order(self):
        self.assertTrue(self.check(self.text))

    def test_retained_focused_successes(self):
        for mode, expected in [('standalone', self.expected[1:2]), ('predecessor', self.expected[:2])]:
            with self.subTest(mode=mode):
                result = validate((HERE / f'fixtures/{mode}.txt').read_text(), expected, require_order=mode == 'predecessor')
                self.assertTrue(result['passed'])

    def test_retained_actual_failure_rejected(self):
        # Unmodified failed synthetic baseline run 36932396114, head 88445f5d.
        text = (HERE / 'fixtures/standalone-failure.txt').read_text()
        self.assertFalse(validate(text, self.expected[1:2], require_order=False)['passed'])

    def test_predecessor_order_remains_mandatory(self):
        text = (HERE / 'fixtures/predecessor.txt').read_text()
        self.assertFalse(validate(text, self.expected[:2][::-1], require_order=True)['passed'])
        self.assertTrue(validate(text, self.expected[:2][::-1], require_order=False)['passed'])

    def test_duplicate_replacing_missing_method_rejected(self):
        old, new = [x.split('#')[1] for x in self.expected[-2:]]
        self.assertFalse(self.check(self.text.replace(old, new)))

    def test_missing_terminal_success_rejected(self):
        self.assertFalse(self.check(self.text.replace('INSTRUMENTATION_STATUS_CODE: 0', '', 1)))

    def test_failure_rejected_even_with_success_footer(self):
        self.assertFalse(self.check(self.text.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -2', 1)))

    def test_duplicate_stream_rejected(self):
        self.assertFalse(self.check(self.text + self.text))

    def test_wrong_success_identity_rejected(self):
        self.assertFalse(self.check(self.text.replace('INSTRUMENTATION_STATUS: test=', 'INSTRUMENTATION_STATUS: test=wrong', 1)))

    def test_exit_timeout_and_missing_footer_rejected(self):
        self.assertFalse(self.check(self.text, raw_exit=1))
        self.assertFalse(self.check(self.text, timed_out=True))
        self.assertFalse(self.check(self.text.replace('INSTRUMENTATION_CODE: -1', '')))
        self.assertFalse(self.check(self.text.replace('OK (7 tests)', '')))

if __name__ == '__main__':
    unittest.main()
