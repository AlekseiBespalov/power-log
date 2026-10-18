const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const kotlin = directory =>
  fs
    .readdirSync(directory, { recursive: true })
    .filter(name => name.endsWith('.kt'))
    .map(name => fs.readFileSync(path.join(directory, name), 'utf8'))
    .join('\n');

test('Android capture identity injection and commit observers exist only in debug sources', () => {
  const android = 'modules/power-log-bridge/android/src';
  assert.doesNotMatch(kotlin(`${android}/main`), /telemetryEventId|var (before|after)TransactionEnd/);
  assert.doesNotMatch(kotlin(`${android}/release`), /\bvar |->|WeakHashMap/);
  const runner = fs.readFileSync('scripts/test-android.mjs', 'utf8');
  assert.match(runner, /compileReleaseKotlin/);
  assert.match(runner, /check-android-release-surface/);
});
