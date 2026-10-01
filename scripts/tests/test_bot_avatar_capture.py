import json
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]

class BotAvatarCaptureRegistrationTest(unittest.TestCase):
    def test_all_states_have_truthful_catalog_and_workflow_dispatch(self):
        catalog = json.loads((ROOT / 'docs/parity/visual-capture-surfaces.json').read_text())
        self.assertIn('bot-avatar-editor', catalog['surfaces'])
        spec = catalog['surfaces']['bot-avatar-editor']
        expected = {'bot-avatar-' + s for s in ('loaded', 'loading', 'picking', 'cancel', 'error', 'changed', 'saved', 'cleared')}
        self.assertEqual(expected, set(spec['states']))
        workflow = (ROOT / '.github/workflows/visual-parity-capture.yml').read_text()
        self.assertIn('bot-avatar-editor', workflow)
        for state in expected:
            self.assertIn('- ' + state, workflow)
            self.assertTrue(spec['states'][state]['fixture_note'])
        self.assertIn('BOT_AVATAR_CAPTURE_STATES', (ROOT / 'app/src/debug/kotlin/com/hermesagent/mobile/ProfileAvatarsParityActivity.kt').read_text())
        self.assertEqual('587e673e2a2fae0616d8b750bb189217080f621a', spec['desktop_sha'])

if __name__ == '__main__': unittest.main()
