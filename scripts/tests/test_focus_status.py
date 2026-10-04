"""Schema 2 contains only fixed statuses and existing reduced fields."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('focus_status_gate', ROOT / 'scripts/focus_diagnostic.py')
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

class StatusTest(unittest.TestCase):
    def test_allowlist_and_owner_consistency(self):
        for status in ('MISSING_CURRENT_SECTION', 'NO_FOCUSED_WINDOW', 'AMBIGUOUS', 'REJECTED', 'OVERFLOW', 'PROBE_FAILURE', 'MATCHED'):
            record = dict(schema=2, before=None, after=None, ownerStatus=status,
                          owner=dict(display=0, ownerPid=123, ownerUid=1000) if status == 'MATCHED' else None)
            self.assertEqual(json.loads(gate.sanitize(json.dumps(record))), record)
            record['owner'] = None if status == 'MATCHED' else dict(display=0, ownerPid=123, ownerUid=1000)
            with self.assertRaises(ValueError): gate.sanitize(json.dumps(record))
        for extra in ({'ownerStatus':'SECRET'}, {'ownerStatus':'REJECTED', 'title':'SECRET'}):
            record = dict(schema=2, before=None, after=None, owner=None, **extra)
            with self.assertRaises(ValueError): gate.sanitize(json.dumps(record))
