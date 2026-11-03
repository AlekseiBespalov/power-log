const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const plist = require('@expo/plist').default;

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

test('the Apple release check rejects a final Info.plist that keeps development client entries', async () => {
  const { checkBundle } = await import('../../scripts/check-apple-release-surface.mjs');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-release-surface-'));
  try {
    const app = path.join(root, 'PowerLog.app');
    fs.mkdirSync(app);
    fs.writeFileSync(path.join(app, 'PowerLog'), new Uint8Array([0xcf, 0xfa, 0xed, 0xfe, 0x0c, 0x00, 0x00, 0x01]));
    const store = {
      CFBundleExecutable: 'PowerLog',
      CFBundleURLTypes: [{ CFBundleURLSchemes: ['power-log', 'app.powerlog.mobile'] }],
      NSAppTransportSecurity: { NSAllowsArbitraryLoads: false },
    };
    const build = info => {
      fs.writeFileSync(path.join(app, 'Info.plist'), plist.build(info));
      return checkBundle(app);
    };
    assert.equal(build(store).length, 1);
    for (const [leftover, entry] of [
      ['NSLocalNetworkUsageDescription', { NSLocalNetworkUsageDescription: 'Expo Dev Launcher uses the local network.' }],
      ['NSBonjourServices', { NSBonjourServices: ['_expo._tcp'] }],
      ['NSAllowsLocalNetworking', { NSAppTransportSecurity: { NSAllowsArbitraryLoads: false, NSAllowsLocalNetworking: true } }],
      ['exp+power-log', { CFBundleURLTypes: [...store.CFBundleURLTypes, { CFBundleURLSchemes: ['exp+power-log'] }] }],
    ])
      assert.throws(() => build({ ...store, ...entry }), { actual: [leftover] }, leftover);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
