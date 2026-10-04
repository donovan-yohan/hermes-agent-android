"""Execute the installed pinned action's createAvd, not a YAML-only model."""
import subprocess
import unittest
from pathlib import Path

class AvdIsolationTest(unittest.TestCase):
    def test_actual_creation_contract(self):
        fixture = Path(__file__).parent / "fixtures/emulator-manager-a421e438.js"
        script = r"""
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const commands = [];
const context = {exports: {}, console: {log() {}, warn() {}}, process: {env: {ANDROID_AVD_HOME: '/home/runner/.android/avd'}}, require(name) {
  if (name === '@actions/exec') return {exec: async command => commands.push(command)};
  if (name === 'fs') return {existsSync: path => path.endsWith('/test.avd')};
  throw Error(name);
}};
vm.runInNewContext(fs.readFileSync(process.argv[1], 'utf8'), context);
(async () => {
 const create = name => context.exports.createAvd('x86_64', name, '', '', false, false, '', 'pixel_6', '', '', '34', 'google_apis');
 await create('test'); assert.equal(commands.length, 0); // reused action-default AVD
 await create('focus-cold-123-1'); assert.equal(commands.length, 1);
 assert(commands[0].includes('avdmanager create avd --force -n "focus-cold-123-1"'));
 assert(!commands[0].includes(' -p ')); // actual action does not supply private path
 console.log('actual pinned createAvd: reused default rejected; unique name creates fresh disk');
})().catch(e => { console.error(e); process.exit(1); });
"""
        result = subprocess.run(['node', '-e', script, str(fixture)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('unique name creates fresh disk', result.stdout)

if __name__ == '__main__':
    unittest.main()
