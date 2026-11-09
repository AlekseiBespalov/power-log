const assert = require('node:assert/strict');
const { Buffer } = require('node:buffer');
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
      [
        'NSLocalNetworkUsageDescription',
        { NSLocalNetworkUsageDescription: 'Expo Dev Launcher uses the local network.' },
      ],
      ['NSBonjourServices', { NSBonjourServices: ['_expo._tcp'] }],
      [
        'NSAllowsLocalNetworking',
        { NSAppTransportSecurity: { NSAllowsArbitraryLoads: false, NSAllowsLocalNetworking: true } },
      ],
      ['exp+power-log', { CFBundleURLTypes: [...store.CFBundleURLTypes, { CFBundleURLSchemes: ['exp+power-log'] }] }],
    ])
      assert.throws(() => build({ ...store, ...entry }), { actual: [leftover] }, leftover);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('the Apple release check rejects a Mach-O that carries the dev tools in UTF-8 or UTF-16LE', async () => {
  const { checkBundle } = await import('../../scripts/check-apple-release-surface.mjs');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-release-surface-'));
  try {
    const app = path.join(root, 'PowerLog.app');
    fs.mkdirSync(app);
    fs.writeFileSync(path.join(app, 'Info.plist'), plist.build({ CFBundleExecutable: 'PowerLog' }));
    const header = Buffer.from([0xcf, 0xfa, 0xed, 0xfe, 0x0c, 0x00, 0x00, 0x01]);
    const binary = body => fs.writeFileSync(path.join(app, 'PowerLog'), Buffer.concat([header, body]));
    binary(Buffer.from('app.powerlog.runtime.host', 'utf8'));
    assert.equal(checkBundle(app).length, 1);
    for (const encoding of ['utf8', 'utf16le']) {
      binary(Buffer.from('power-log-dev-tools', encoding));
      assert.throws(() => checkBundle(app), /carries the dev tools/, encoding);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('the Apple release check names the dev tools in a development client bundle before its Info.plist', async () => {
  const { checkBundle } = await import('../../scripts/check-apple-release-surface.mjs');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-release-surface-'));
  try {
    const app = path.join(root, 'PowerLog.app');
    fs.mkdirSync(app);
    fs.writeFileSync(
      path.join(app, 'Info.plist'),
      plist.build({ CFBundleExecutable: 'PowerLog', CFBundleURLTypes: [{ CFBundleURLSchemes: ['exp+power-log'] }] }),
    );
    const header = Buffer.from([0xcf, 0xfa, 0xed, 0xfe, 0x0c, 0x00, 0x00, 0x01]);
    fs.writeFileSync(path.join(app, 'PowerLog'), Buffer.concat([header, Buffer.from('power-log-dev-tools')]));
    assert.throws(() => checkBundle(app), /carries the dev tools/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('the thin slice requires the release scans to reject its debug builds for the dev tools', async () => {
  const { rejectsDevTools } = await import('../../scripts/thin-slice-phones.mjs');
  const failing = message => () => {
    throw new Error(message);
  };
  assert.doesNotThrow(() => rejectsDevTools('app-debug.apk', failing('classes2.dex carries the dev tools')));
  assert.throws(() => rejectsDevTools('app-debug.apk', () => 5), /passed app-debug\.apk/);
  assert.throws(
    () => rejectsDevTools('the Debug .app', failing('Store builds declare no local network access')),
    /failed before it reached the dev tools: Store builds/,
  );
});

test('the Android release check reads every dex: the runtime host present, the dev tools absent', async () => {
  const { checkApk } = await import('../../scripts/check-android-release-surface.mjs');
  const { execFileSync } = require('node:child_process');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-release-apk-'));
  try {
    const apk = (name, dexes) => {
      const directory = path.join(root, name);
      fs.mkdirSync(directory);
      for (const [file, text] of Object.entries(dexes)) fs.writeFileSync(path.join(directory, file), `dex\n${text}`);
      fs.writeFileSync(path.join(directory, 'AndroidManifest.xml'), 'manifest');
      execFileSync('zip', ['-q', '-r', path.join(root, `${name}.apk`), '.'], { cwd: directory });
      return path.join(root, `${name}.apk`);
    };
    const host = 'Lapp/powerlog/runtime/host/HostFacade;';
    assert.equal(checkApk(apk('release', { 'classes.dex': 'Landroid/app/Activity;', 'classes2.dex': host })), 2);
    assert.throws(() => checkApk(apk('bare', { 'classes.dex': 'Landroid/app/Activity;' })), /carries no runtime host/);
    assert.throws(
      () => checkApk(apk('debug', { 'classes.dex': host, 'classes3.dex': 'Lapp/powerlog/runtime/dev/DemoBike;' })),
      /classes3\.dex .* carries the dev tools/,
    );
    assert.throws(() => checkApk(apk('marker', { 'classes.dex': `${host}power-log-dev-tools` })), /dev tools/);
    const r8 = '~~R8{"backend":"dex","compilation-mode":"release","min-api":28,"version":"8.12.14"}';
    assert.throws(() => checkApk(apk('minified', { 'classes.dex': `${host}${r8}` })), /minified by R8/);
    const d8 = '~~D8{"backend":"dex","compilation-mode":"release","min-api":28,"version":"8.12.14"}';
    assert.equal(checkApk(apk('dexed', { 'classes.dex': `${host}${d8}` })), 1);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
