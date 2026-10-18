const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { configureCoreSettings, stageCoreFramework } = require('../../plugins/with-power-log-core');
const { configureWatchProject } = require('../../plugins/with-power-log-watch');
const { configureActivityProject } = require('../../plugins/with-power-log-live-activity');
const { projectFixture } = require('./project-fixture.cjs');

function frameworks(project, name) {
  const objects = project.hash.project.objects;
  const target = Object.values(objects.PBXNativeTarget).find(
    target => target.isa === 'PBXNativeTarget' && target.name.replaceAll('"', '') === name,
  );
  return target.buildPhases
    .flatMap(reference => objects.PBXFrameworksBuildPhase[reference.value]?.files ?? [])
    .map(reference => objects.PBXFileReference[objects.PBXBuildFile[reference.value].fileRef].path.replaceAll('"', ''));
}

test('repeated prebuild leaves Watch and Live Activity independent of the core framework', () => {
  const project = projectFixture();
  const config = { version: '0.3.0', ios: { bundleIdentifier: 'app.powerlog.fixture', buildNumber: '1' } };
  let previous;
  for (let index = 0; index < 3; index++) {
    configureWatchProject(project, process.cwd(), config);
    configureActivityProject(project, config);
    assert.deepEqual(frameworks(project, 'PowerLogWatch'), []);
    assert.deepEqual(frameworks(project, 'PowerLogActivity'), []);
    const bytes = project.writeSync();
    if (previous) assert.equal(bytes, previous, 'Repeated native configuration changed project bytes');
    previous = bytes;
  }
});

test('Android prebuild keeps the standalone core composite include idempotent', () => {
  const original = 'pluginManagement { repositories { gradlePluginPortal() } }\n';
  const configured = configureCoreSettings(original);
  assert.equal(configured.match(/includeBuild\('\.\.\/shared'\)/g).length, 1);
  assert.equal(configured.includes('gradle.startParameter.projectCacheDir'), false);
  assert.equal(configureCoreSettings(configured), configured);
});

test('repeated prebuild selects the phone plist when serialized targets put Watch first', () => {
  const project = projectFixture();
  const config = { version: '0.3.0', ios: { bundleIdentifier: 'app.powerlog.fixture', buildNumber: '1' } };
  configureWatchProject(project, process.cwd(), config);
  const objects = project.hash.project.objects;
  const targets = objects.PBXNativeTarget;
  const watch = Object.keys(targets).find(
    key => targets[key]?.isa === 'PBXNativeTarget' && targets[key].name.replaceAll('"', '') === 'PowerLogWatch',
  );
  objects.PBXNativeTarget = {
    [watch]: targets[watch],
    [`${watch}_comment`]: targets[`${watch}_comment`],
    ...Object.fromEntries(Object.entries(targets).filter(([key]) => !key.startsWith(watch))),
  };
  configureWatchProject(project, process.cwd(), config);
  const { getInfoPlistPathFromPbxproj } = require('@expo/config-plugins/build/ios/utils/getInfoPlistPath');
  assert.equal(getInfoPlistPathFromPbxproj(project), 'PowerLog/PowerLog-Info.plist');
});

test('prebuild stages current framework contents under the pod root and removes obsolete files', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-core-pod-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const source = path.join(root, 'shared/build/XCFrameworks/release/PowerLogCore.xcframework');
  const destination = path.join(root, 'modules/power-log-bridge/ios/Frameworks/PowerLogCore.xcframework');
  fs.mkdirSync(source, { recursive: true });
  fs.writeFileSync(path.join(source, 'Info.plist'), 'first build');
  fs.symlinkSync('Info.plist', path.join(source, 'relative-link'));
  stageCoreFramework(root);
  assert.equal(fs.readFileSync(path.join(destination, 'Info.plist'), 'utf8'), 'first build');
  assert.equal(fs.readlinkSync(path.join(destination, 'relative-link')), 'Info.plist');

  fs.writeFileSync(path.join(destination, 'obsolete'), 'old slice');
  fs.writeFileSync(path.join(source, 'Info.plist'), 'second build');
  stageCoreFramework(root);
  assert.equal(fs.readFileSync(path.join(destination, 'Info.plist'), 'utf8'), 'second build');
  assert.equal(fs.existsSync(path.join(destination, 'obsolete')), false);
});

test('prebuild refuses to stage a missing Apple build', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-core-pod-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  assert.throws(() => stageCoreFramework(root), /Build the Apple core before staging/);
  assert.equal(fs.existsSync(path.join(root, 'modules')), false);
});
