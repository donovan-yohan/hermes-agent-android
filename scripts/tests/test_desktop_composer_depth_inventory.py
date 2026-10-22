"""Record regressions only; these assertions are not independent source review."""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def serialized_objects(text):
    decoder = json.JSONDecoder()
    pos = text.index('"controls": [') + len('"controls": [')
    rows = []
    while True:
        while text[pos].isspace() or text[pos] == ',':
            pos += 1
        if text[pos] == ']':
            return rows
        _, end = decoder.raw_decode(text, pos)
        rows.append(text[pos:end])
        pos = end


class ComposerDepthInventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = (ROOT / 'docs/parity/desktop-control-records.json').read_text()
        cls.data = json.loads(cls.raw)
        cls.rows = {r['id'].removeprefix('composer-depth.'): r
                    for r in cls.data['controls'] if r['id'].startswith('composer-depth.')}

    def test_original_375_serialized_objects_are_byte_preserved(self):
        original = '\n'.join(serialized_objects(self.raw)[:375]).encode()
        self.assertEqual(hashlib.sha256(original).hexdigest(),
                         '34c8d2f4d8d0172dead6937ed406633d19fdca124778bc5b99389938a313b6fc')

    def test_bounded_increment_cannot_claim_complete_or_features(self):
        self.assertEqual(len(self.rows), 43)
        self.assertEqual(len(self.data['controls']), 418)
        self.assertFalse(self.data['semantic_complete'])
        for row in self.rows.values():
            self.assertTrue(row['android_evidence'])
            self.assertTrue(row['lifecycle'])
            self.assertIn('source read', row['review'])

    def test_native_capabilities_elsewhere_are_not_invented_omissions(self):
        self.assertEqual(self.rows['pick-files']['android_status'], 'implemented-bounded')
        self.assertEqual(self.rows['queue-discard-lost']['android_status'], 'implemented-bounded')
        self.assertIn('separate', self.rows['queue-delete']['android_boundary'])
        self.assertIn('elsewhere', self.rows['voice-start']['android_boundary'])
        self.assertEqual(self.rows['queue-steer']['android_status'], 'drift')
        self.assertIn('repository.redirect', self.rows['queue-steer']['android_boundary'])

    def test_local_redirect_equivalence_is_not_a_false_writer_regression(self):
        boundary = self.rows['queue-steer']['android_boundary']
        self.assertIn('NOT a local writer mismatch', boundary)
        self.assertIn('SERVER-owned prompt.steer_queued', boundary)
        self.assertIn('Desktop local', boundary)

    def test_concrete_voice_effects_and_reference_toggle_not_generic_forwarders(self):
        self.assertIn('handle.cancel', self.rows['voice-mute']['handler'])
        self.assertIn('handleTurn(true)', self.rows['voice-stop-listening']['handler'])
        self.assertIn('session?.close', self.rows['voice-end']['handler'])
        self.assertIn('600ms', self.rows['moa-reference-enabled']['handler'])
        self.assertIn('PUT /api/model/moa', self.rows['moa-reference-enabled']['authority'])
        self.assertIn('NOT an @', self.rows['moa-reference-enabled']['mount'])

    def test_conditional_conversation_definitions_are_not_shipping_capability(self):
        for name in ('voice-start', 'voice-end', 'voice-mute'):
            self.assertEqual(self.rows[name]['android_status'], 'omission')
            self.assertIn('no assignment', self.rows[name]['android_boundary'])
        self.assertEqual(self.rows['dictation']['android_status'], 'implemented-bounded')
        self.assertTrue(any(c['path'].endswith('/MainActivity.kt')
                            for c in self.rows['dictation']['android_evidence']))

    def test_no_phantom_completion_clear_or_shared_primitive_feature(self):
        self.assertNotIn('completion-clear', self.rows)
        self.assertIn('NOT an independent SearchField', self.rows['completion-query']['mount'])
        self.assertIn('No independent Clear', self.rows['model-search']['mount'])
        self.assertFalse(any(r['id'].endswith('ContextMenuItem') for r in self.rows.values()))
        self.assertIn('ambient', self.rows['completion-query']['authority'])
        self.assertIn('unknown keeps', self.rows['queue-edit-save']['handler'])


if __name__ == '__main__':
    unittest.main()
