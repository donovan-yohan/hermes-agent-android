"""Strict enum-only candidate export. No device calls."""

import json
from pathlib import Path
import sys
import unittest
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import focus_diagnostic as focus


class CandidateSchemaTest(unittest.TestCase):
    def record(self) -> dict[str, Any]:
        return dict(schema=5, before=None, after=None, owner=None, ownerStatus='PROBE_FAILURE',
                    parserShape=None, ownerMetadata=None, currentAnrCandidate=dict(
                        source='ACTIVITY_MANAGER_ERROR_STATE', scope='CALLER_USER',
                        status='CURRENT_ANR_CANDIDATE', cause='NONE', role='APP_PROCESS',
                        reason='INPUT_DISPATCH_TIMEOUT'))

    def test_roundtrip_and_fixed_unknown(self):
        value = self.record()
        self.assertEqual(value, json.loads(focus.sanitize(json.dumps(value))))
        for cause in ('NOT_ATTESTED', 'ALREADY_ATTEMPTED', 'NO_RECORD', 'MULTIPLE',
                      'INVALID_RECORD', 'OVERFLOW', 'ACQUISITION_FAILED', 'CLEANUP_FAILED', 'TIMEOUT'):
            value['currentAnrCandidate'].update(status='UNKNOWN', cause=cause, role='UNKNOWN', reason='UNKNOWN')
            self.assertEqual(value, json.loads(focus.sanitize(json.dumps(value))))

    def test_exact_keys_enums_and_consistency(self):
        for key in ('pid', 'uid', 'name', 'shortMsg', 'longMsg', 'stackTrace', 'title'):
            value = self.record(); value['currentAnrCandidate'][key] = 'PRIVATE'
            with self.assertRaises(ValueError): focus.sanitize(json.dumps(value))
        for key in self.record()['currentAnrCandidate']:
            for bad in ('PRIVATE', None, [], {}, 1, True):
                value = self.record(); value['currentAnrCandidate'][key] = bad
                with self.assertRaises(ValueError): focus.sanitize(json.dumps(value))
            value = self.record(); del value['currentAnrCandidate'][key]
            with self.assertRaises(ValueError): focus.sanitize(json.dumps(value))
        for changes in (dict(status='MATCHED'), dict(cause='TIMEOUT'), dict(status='UNKNOWN'),
                        dict(status='UNKNOWN', cause='TIMEOUT'), dict(scope='ALL_USERS')):
            value = self.record(); value['currentAnrCandidate'].update(changes)
            with self.assertRaises(ValueError): focus.sanitize(json.dumps(value))
        value = self.record(); value['currentAnrCandidate'] = None
        with self.assertRaises(ValueError): focus.sanitize(json.dumps(value))
        value = self.record(); del value['currentAnrCandidate']
        with self.assertRaises(ValueError): focus.sanitize(json.dumps(value))

    def test_old_schema_cannot_smuggle_candidate(self):
        value = self.record(); value['schema'] = 4
        with self.assertRaises(ValueError): focus.sanitize(json.dumps(value))
        del value['currentAnrCandidate']
        self.assertEqual(value, json.loads(focus.sanitize(json.dumps(value))))

    def test_permission_scope_and_attestation_call_path(self):
        root = Path(__file__).resolve().parents[2]
        device = root / 'app/src/androidTest/kotlin/com/hermesagent/mobile/device'
        snapshot = (device / 'FailureFocusSnapshot.kt').read_text()
        capture = snapshot.split('fun capture(activity:', 1)[1]
        self.assertLess(capture.index('arm()'), capture.index('armedNonce ?: return'))
        self.assertLess(capture.index('armedNonce ?: return'), capture.index('CurrentAnrSnapshot.capture()'))
        self.assertIn('android.os.Build.VERSION.SDK_INT != 34', snapshot)
        for gate in ('focusSnapshotEnabled', 'focusSnapshotNonce', 'focusSnapshotSerial',
                     'focusSnapshotDisposable', 'debug.hermes.focus_attestation', 'ro.boot.qemu'):
            self.assertIn(gate, snapshot)
        adapter = (device / 'CurrentAnrSnapshot.kt').read_text()
        self.assertIn('adoptShellPermissionIdentity(Manifest.permission.DUMP)', adapter)
        self.assertNotIn('INTERACT_ACROSS_USERS', adapter)
        for path in (root / 'app/src').rglob('*.kt'):
            if path.name != 'CurrentAnrSnapshot.kt':
                self.assertNotIn('adoptShellPermissionIdentity', path.read_text())
                self.assertNotIn('dropShellPermissionIdentity', path.read_text())
