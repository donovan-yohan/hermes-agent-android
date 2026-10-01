"""Pure fail-closed validation of the disposable instrumentation stream."""
from collections import Counter
import re


def validate(text, expected, *, require_order, raw_exit=0, timed_out=False):
    fields, starts, successes, codes = {}, [], [], []
    active = None
    paired = True
    for line in text.splitlines():
        match = re.fullmatch(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
        if match:
            fields[match[1]] = match[2].strip()
        match = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if not match:
            continue
        code = int(match[1])
        codes.append(code)
        identity = fields.get('class', '') + '#' + fields.get('test', '')
        if code == 1:
            paired = paired and active is None
            starts.append(identity)
            active = identity
        elif code == 0:
            paired = paired and active == identity
            successes.append(identity)
            active = None
        else:
            paired = False
        fields = {}
    exact = Counter(starts) == Counter(expected) == Counter(successes)
    order_verified = starts == expected if require_order else None
    passed = (raw_exit == 0 and not timed_out and paired and active is None
              and exact and (not require_order or order_verified)
              and codes == [n for _ in expected for n in (1, 0)]
              and re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', text, re.M) == ['-1']
              and re.findall(r'^OK \((\d+) tests?\)\s*$', text, re.M) == [str(len(expected))]
              and not re.search(r'^INSTRUMENTATION_(?:FAILED|ABORTED):', text, re.M))
    return {'raw_exit': raw_exit, 'timed_out': timed_out, 'status_codes': codes,
            'expected_tests': expected, 'actual_order': starts,
            'successful_tests': successes, 'exact_test_multiset_verified': exact,
            'order_required': require_order, 'order_verified': order_verified,
            'paired_statuses_verified': paired and active is None, 'passed': bool(passed)}
