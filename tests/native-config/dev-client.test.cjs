const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { AndroidConfig, compileModsAsync } = require('@expo/config-plugins');
const { getPrebuildConfigAsync } = require('@expo/prebuild-config');
const plist = require('@expo/plist').default;

const roots = [];
const linked = ['node_modules', 'plugins'];

function nativeProject() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-dev-client-'));
  roots.push(root);
  for (const file of ['app.config.ts', 'package.json']) fs.copyFileSync(file, path.join(root, file));
  for (const directory of linked) fs.symlinkSync(path.resolve(directory), path.join(root, directory));
  const ios = 'package/ios/HelloWorld/Info.plist';
  const android = 'package/android/app/src/main/AndroidManifest.xml';
  execFileSync('tar', ['-xzf', path.resolve('node_modules/expo/template.tgz'), '-C', root, ios, android]);
  for (const [from, to] of [
    [ios, 'ios/PowerLog/Info.plist'],
    [android, 'android/app/src/main/AndroidManifest.xml'],
  ]) {
    fs.mkdirSync(path.dirname(path.join(root, to)), { recursive: true });
    fs.renameSync(path.join(root, from), path.join(root, to));
  }
  return root;
}

function setEnvironment(values) {
  for (const [key, value] of Object.entries(values))
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
}

async function prebuild(root, developmentClient) {
  const previous = { POWER_LOG_DEV_CLIENT: process.env.POWER_LOG_DEV_CLIENT, POWER_LOG_LAB: process.env.POWER_LOG_LAB };
  setEnvironment({ POWER_LOG_DEV_CLIENT: developmentClient ? '1' : undefined, POWER_LOG_LAB: undefined });
  try {
    const platforms = ['ios', 'android'];
    const { exp } = await getPrebuildConfigAsync(root, { platforms });
    const { modResults } = (await compileModsAsync(exp, { projectRoot: root, platforms, introspect: true }))._internal;
    fs.writeFileSync(path.join(root, 'ios/PowerLog/Info.plist'), plist.build(modResults.ios.infoPlist));
    await AndroidConfig.Manifest.writeAndroidManifestAsync(
      path.join(root, 'android/app/src/main/AndroidManifest.xml'),
      modResults.android.manifest,
    );
    return { infoPlist: modResults.ios.infoPlist, manifest: modResults.android.manifest };
  } finally {
    setEnvironment(previous);
  }
}

test.after(() => {
  for (const root of roots) {
    for (const directory of linked) fs.unlinkSync(path.join(root, directory));
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('store prebuilds leave out the development client that Expo prebuild applies whenever it is installed', async () => {
  const { checkInfoPlist } = await import('../../scripts/check-apple-release-surface.mjs');
  const store = await prebuild(nativeProject(), false);
  const development = await prebuild(nativeProject(), true);
  checkInfoPlist(store.infoPlist);
  assert.throws(() => checkInfoPlist(development.infoPlist), {
    actual: ['NSLocalNetworkUsageDescription', 'NSBonjourServices', 'NSAllowsLocalNetworking', 'exp+power-log'],
  });
  const schemes = AndroidConfig.Scheme.getSchemesFromManifest(store.manifest);
  assert.ok(schemes.length > 0 && !schemes.includes('exp+power-log'));
  assert.deepEqual(AndroidConfig.Scheme.getSchemesFromManifest(development.manifest), [...schemes, 'exp+power-log']);
});

test('store and development prebuilds replace each other over the same native project', async () => {
  const store = await prebuild(nativeProject(), false);
  const development = await prebuild(nativeProject(), true);
  const root = nativeProject();
  for (const expected of [development, store, development, store])
    assert.deepEqual(await prebuild(root, expected === development), expected);
});
