const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { getConfig } = require('@expo/config');
const { IOSConfig } = require('@expo/config-plugins');
const plist = require('@expo/plist').default;
const withWatch = require('../../plugins/with-power-log-watch');
const withActivity = require('../../plugins/with-power-log-live-activity');
const { projectFixture } = require('./project-fixture.cjs');

function appConfig(buildNumber, version = '2.4.6') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-config-'));
  const previous = process.env.POWER_LOG_IOS_BUILD_NUMBER;
  try {
    fs.copyFileSync('app.config.ts', path.join(root, 'app.config.ts'));
    fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({ name: 'power-log', version }));
    if (buildNumber === undefined) delete process.env.POWER_LOG_IOS_BUILD_NUMBER;
    else process.env.POWER_LOG_IOS_BUILD_NUMBER = buildNumber;
    return getConfig(root, { skipPlugins: true, skipSDKVersionRequirement: true }).exp;
  } finally {
    if (previous === undefined) delete process.env.POWER_LOG_IOS_BUILD_NUMBER;
    else process.env.POWER_LOG_IOS_BUILD_NUMBER = previous;
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('iOS build numbers default to one and accept positive integer environment values', () => {
  assert.equal(appConfig().ios.buildNumber, '1');
  assert.equal(appConfig('73').ios.buildNumber, '73');
});

test('iOS build numbers reject malformed, non-positive and unsafe integers', () => {
  for (const value of ['', '0', '-1', '1.5', '1e2', '+1', '01', ' 1', '1 ', 'NaN', 'Infinity', '9007199254740992']) {
    assert.throws(() => appConfig(value), /Invalid iOS build number/, value);
  }
});

test('phone, Watch and Live Activity bundle versions follow config on initial and repeated prebuilds', async () => {
  const project = projectFixture(), projectRoot = process.cwd();
  for (const [version, buildNumber] of [['2.4.6', '73'], ['3.7.9', '108']]) {
    const config = withWatch(withActivity(appConfig(buildNumber, version)));
    await config.mods.ios.xcodeproj({ ...config, modResults: project, modRequest: { projectRoot } });
    const phone = IOSConfig.Version.setBuildNumber(config, IOSConfig.Version.setVersion(config, {}));
    assert.equal(phone.CFBundleShortVersionString, version);
    assert.equal(phone.CFBundleVersion, buildNumber);
    const objects = project.hash.project.objects;
    const targets = Object.values(objects.PBXNativeTarget).filter(target => target.isa === 'PBXNativeTarget');
    assert.equal(targets.length, 3);
    for (const name of ['PowerLogWatch', 'PowerLogActivity']) {
      const target = targets.find(target => target.name.replaceAll('"', '') === name);
      for (const reference of objects.XCConfigurationList[target.buildConfigurationList].buildConfigurations) {
        const settings = objects.XCBuildConfiguration[reference.value].buildSettings;
        assert.equal(settings.MARKETING_VERSION, phone.CFBundleShortVersionString);
        assert.equal(settings.CURRENT_PROJECT_VERSION, phone.CFBundleVersion);
        const info = plist.parse(fs.readFileSync(path.resolve(projectRoot, 'ios', settings.INFOPLIST_FILE.replaceAll('"', '')), 'utf8'));
        assert.equal(info.CFBundleShortVersionString, '$(MARKETING_VERSION)');
        assert.equal(info.CFBundleVersion, '$(CURRENT_PROJECT_VERSION)');
      }
    }
  }
});

test('the phone app declares its own privacy manifest so pod aggregation never rewrites the Watch manifest', () => {
  assert.deepEqual(appConfig().ios.privacyManifests, { NSPrivacyTracking: false, NSPrivacyAccessedAPITypes: [] });
});
