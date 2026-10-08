#!/usr/bin/env node
'use strict';
// Synthetic-only confidentiality regression. Never print captured child output
// or report bytes, even when an assertion fails.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const replay = path.join(__dirname, 'snapshot_action_replay.cjs');
const i = process.argv.indexOf('--modules');
assert(i >= 0 && process.argv[i + 1], 'explicit --modules argument required');
const root = fs.mkdtempSync(path.join(process.env.TMPDIR || path.dirname(__dirname), 'replay-security-'));
const output = path.join(root, 'receipts');
const sentinel = 'synthetic-fixture-secret-not-a-credential-7329';
const result = cp.spawnSync(process.execPath, [replay, '--modules', process.argv[i + 1], '--output', output], {
  // Exercise an inherited secret without importing any real credentials.
  env: {PATH: process.env.PATH, TMPDIR: root, FAKE_SECRET: sentinel, AVD_CACHE_HIT: 'true'}, encoding: 'utf8', timeout: 300000
});
assert(!(result.stdout || '').includes(sentinel), 'synthetic secret reached stdout');
assert(!(result.stderr || '').includes(sentinel), 'synthetic secret reached stderr');
assert.equal(result.status, 0, 'replay must pass; child output intentionally withheld');
function scan(dir) {
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) scan(file);
    else assert(!fs.readFileSync(file).includes(Buffer.from(sentinel)), 'synthetic secret reached persisted fixture/report bytes');
  }
}
scan(output);
const report = JSON.parse(fs.readFileSync(path.join(output, 'report.json'), 'utf8'));
const scenarios = ['miss', 'kill-failure', 'kill-success-live', 'delayed-exit', 'incomplete-save', 'hit', 'divergent-config', 'missing-snapshot', 'sdk-update', 'connected-failure'];
assert.deepEqual(report.results.map(r => r.name), scenarios, 'all ten scenarios preserved');
assert.deepEqual(report.errors, []);
const fields = new Set(['inputs', 'events', 'failures', 'helpers', 'launch', 'connected', 'saved', 'lifecycleError']);
for (const name of scenarios) {
  const receipt = JSON.parse(fs.readFileSync(path.join(output, name + '.json'), 'utf8'));
  assert(Object.keys(receipt).every(key => fields.has(key)), 'receipt field contract changed');
  assert(!Object.hasOwn(receipt, 'env'), 'execution env must not be a receipt field');
  assert(!Object.hasOwn(receipt, 'dir'), 'execution directory must not be a receipt field');
  assert(Array.isArray(receipt.events));
  for (const helper of receipt.helpers) {
    if (helper.command === 'verify-miss' || helper.command === 'seal') {
      assert(helper.stdout.includes('stage=cache-miss-provenance status=passed'),
             'creator hooks and sealing must resolve actual workflow miss provenance');
    } else {
      assert(!helper.stdout.includes('stage=cache-miss-provenance'),
             'record and restored-hit verification must not use a miss bypass');
    }
  }
  for (const event of receipt.events) {
    assert(Object.keys(event).every(key => key === 'command' || key === 'args'), 'command contract changed');
    assert.equal(typeof event.command, 'string');
    if (Object.hasOwn(event, 'args')) assert(Array.isArray(event.args));
  }
}
console.log('PASS replay confidentiality: synthetic secret absent from console and persisted bytes; ten scenarios; explicit receipt/argument contract');
console.log('Fresh synthetic evidence directory: ' + output);
