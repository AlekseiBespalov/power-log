const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const { createRequire } = require('node:module');
const test = require('node:test');

test('router query parsing handles malformed UTF-8 without unbounded CPU work', () => {
  // Isolate the parser so a regression to the vulnerable decoder cannot hang the test runner.
  const result = spawnSync(process.execPath, ['-e', `
    const assert = require('node:assert/strict');
    const query = require('query-string');
    const invalid = '%FF'.repeat(4000);
    assert.equal(query.parse('value=' + invalid).value, invalid);
    assert.equal(query.parse('value=caf%C3%A9+%26+%F0%9F%9A%B2').value, 'café & 🚲');
    assert.equal(query.parse(query.stringify({ value: 'café & 🚲' })).value, 'café & 🚲');
  `], { encoding: 'utf8', timeout: 3000 });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
});

test('Xcode project generation can still use its patched UUID dependency', () => {
  const xcodeRequire = createRequire(require.resolve('xcode'));
  const { v4, validate, version } = xcodeRequire('uuid');
  const id = v4();
  assert.equal(validate(id), true);
  assert.equal(version(id), 4);
});
