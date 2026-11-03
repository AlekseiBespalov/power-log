const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const plist = require('@expo/plist').default;
const withWatch = require('../../plugins/with-power-log-watch');
const { projectFixture } = require('./project-fixture.cjs');

const watchSources = fs
  .readdirSync('apple/WatchApp')
  .filter(file => file.endsWith('.swift'))
  .map(file => `apple/WatchApp/${file}`)
  .concat(
    ['WorkoutCompanion', 'WorkoutCompanionTransport', 'WorkoutNativeHealth', 'WorkoutHealthMetrics'].map(
      name => `modules/power-log-bridge/ios/${name}.swift`,
    ),
  )
  .sort();

test('prebuild embeds the Watch privacy manifest once alongside assets and preserves the compiled source set', async () => {
  const project = projectFixture(),
    projectRoot = process.cwd();
  const config = withWatch({ version: '2.4.6', ios: { bundleIdentifier: 'app.powerlog.test', buildNumber: '73' } });
  const expectedSources = watchSources.map(file => `../${file}`);
  for (let repetition = 0; repetition < 3; repetition++) {
    await config.mods.ios.xcodeproj({ ...config, modResults: project, modRequest: { projectRoot } });
    const objects = project.hash.project.objects;
    const targets = Object.entries(objects.PBXNativeTarget).filter(([, target]) => target.isa === 'PBXNativeTarget');
    assert.equal(targets.length, 2);
    const [watchID, watch] = targets.find(([, target]) => target.name.replaceAll('"', '') === 'PowerLogWatch');
    const phone = project.getFirstTarget().firstTarget;
    assert.equal(
      phone.dependencies.filter(reference => objects.PBXTargetDependency[reference.value].target === watchID).length,
      1,
    );
    for (const [phaseType, expected] of [
      ['PBXSourcesBuildPhase', expectedSources],
      ['PBXResourcesBuildPhase', ['../apple/WatchApp/PrivacyInfo.xcprivacy', '../assets/Assets.xcassets']],
    ]) {
      const phases = watch.buildPhases.map(reference => objects[phaseType][reference.value]).filter(Boolean);
      assert.equal(phases.length, 1);
      const files = phases[0].files
        .map(reference =>
          objects.PBXFileReference[objects.PBXBuildFile[reference.value].fileRef].path.replaceAll('"', ''),
        )
        .sort();
      assert.deepEqual(files, expected);
    }
    const watchReferences = Object.entries(objects.PBXFileReference).filter(
      ([key, reference]) =>
        !key.endsWith('_comment') &&
        /^"?\.\.\/(apple\/WatchApp|modules\/power-log-bridge\/ios|assets\/Assets\.xcassets)/.test(reference.path),
    );
    assert.equal(watchReferences.length, expectedSources.length + 2);
    const grouped = Object.values(objects.PBXGroup)
      .filter(group => typeof group === 'object')
      .flatMap(group => group.children.map(child => child.value));
    for (const [key] of watchReferences) assert.equal(grouped.filter(value => value === key).length, 1);
  }
});

test('Watch and PowerLogBridge privacy manifests declare only app-local defaults and elapsed timers', () => {
  for (const file of ['apple/WatchApp/PrivacyInfo.xcprivacy', 'modules/power-log-bridge/ios/PrivacyInfo.xcprivacy']) {
    const manifest = plist.parse(fs.readFileSync(file, 'utf8'));
    assert.equal(manifest.NSPrivacyTracking, false);
    assert.deepEqual(manifest.NSPrivacyTrackingDomains, []);
    assert.deepEqual(manifest.NSPrivacyCollectedDataTypes, []);
    assert.deepEqual(
      manifest.NSPrivacyAccessedAPITypes.map(entry => ({ ...entry })),
      [
        {
          NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategoryUserDefaults',
          NSPrivacyAccessedAPITypeReasons: ['CA92.1'],
        },
        {
          NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategorySystemBootTime',
          NSPrivacyAccessedAPITypeReasons: ['35F9.1'],
        },
      ],
    );
  }
});

test('the Watch states its Health use and asks for no location because it never runs Core Location', () => {
  const info = plist.parse(fs.readFileSync('apple/WatchApp/Info.plist', 'utf8'));
  assert.match(info.NSHealthShareUsageDescription, /\S/);
  assert.match(info.NSHealthUpdateUsageDescription, /\S/);
  assert.deepEqual(
    Object.keys(info).filter(key => key.startsWith('NSLocation')),
    [],
  );
  for (const file of watchSources)
    assert.doesNotMatch(fs.readFileSync(file, 'utf8'), /CLLocationManager|requestWhenInUseAuthorization/, file);
});
