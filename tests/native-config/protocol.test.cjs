const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const core = import('../../shared/js/power-log-core.mjs');
const fixture = name =>
  JSON.parse(
    fs.readFileSync(path.join(process.cwd(), 'tests/fixtures/contract/watch-protocol', `${name}.json`), 'utf8'),
  );

test('generated protocol timestamp export returns exact floored microsecond strings', async () => {
  const { protocolTimestampMicroseconds } = await core;
  for (const value of fixture('timestamps').cases) {
    if (value.accepted) assert.equal(protocolTimestampMicroseconds(value.input), value.comparisonMicroseconds);
    else assert.throws(() => protocolTimestampMicroseconds(value.input), new RegExp(value.rejection));
  }
});
