const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const {
  appleSlice,
  configureCoreProperties,
  configureCoreSettings,
  coreFlavor,
  stageCoreFramework,
} = require('../../plugins/with-power-log-core');
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

test('Android prebuild gives the daemon that also builds the shared modules enough memory, once', () => {
  const template = [
    { type: 'comment', value: 'Specifies the JVM arguments used for the daemon process.' },
    { type: 'property', key: 'org.gradle.jvmargs', value: '-Xmx2048m -XX:MaxMetaspaceSize=512m' },
    { type: 'property', key: 'android.useAndroidX', value: 'true' },
  ];
  const configured = configureCoreProperties(template);
  assert.deepEqual(
    configured.filter(item => item.key === 'org.gradle.jvmargs').map(item => item.value),
    ['-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'],
  );
  assert.equal(configured.length, template.length);
  assert.deepEqual(configureCoreProperties(configured), configured);
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
  const source = path.join(root, 'shared/runtime/build/XCFrameworks/release/PowerLogCore.xcframework');
  const destination = path.join(root, 'modules/power-log/ios/Frameworks/PowerLogCore.xcframework');
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

test('a development prebuild stages the debug framework and records each flavor beside the framework', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-core-pod-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const frameworks = path.join(root, 'modules/power-log/ios/Frameworks');
  for (const [flavor, build] of [
    ['development', 'debug'],
    ['release', 'release'],
  ]) {
    const source = path.join(root, `shared/runtime/build/XCFrameworks/${build}/PowerLogCore.xcframework`);
    fs.mkdirSync(source, { recursive: true });
    fs.writeFileSync(path.join(source, 'Info.plist'), `${build} build`);
    stageCoreFramework(root, flavor);
    assert.equal(fs.readFileSync(path.join(frameworks, 'PowerLogCore.xcframework/Info.plist'), 'utf8'), `${build} build`);
    assert.equal(fs.readFileSync(path.join(frameworks, 'PowerLogCore.flavor'), 'utf8'), `${flavor}\n`);
  }
});

test('only a development client builds the dev tools, for the device unless the simulator is named', () => {
  assert.equal(coreFlavor({}), 'release');
  assert.equal(coreFlavor({ POWER_LOG_DEV_CLIENT: '1' }), 'development');
  assert.equal(appleSlice({}), 'device');
  assert.equal(appleSlice({ POWER_LOG_APPLE_SLICE: 'device' }), 'device');
  assert.equal(appleSlice({ POWER_LOG_APPLE_SLICE: 'simulator' }), 'simulator');
});

test('the pod compiles the dev tools condition only over a development framework', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-podspec-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const podspec = path.join(root, 'PowerLogPlatform.podspec');
  fs.copyFileSync('modules/power-log/ios/PowerLogPlatform.podspec', podspec);
  const evaluate = path.join(root, 'evaluate.rb');
  fs.writeFileSync(
    evaluate,
    [
      'module Pod',
      '  class Spec',
      '    attr_reader :values',
      '    def initialize',
      '      @values = {}',
      '      yield self',
      '    end',
      "    def method_missing(name, *arguments) = name.end_with?('=') ? @values[name.to_s.chomp('=')] = arguments.first : nil",
      '    def respond_to_missing?(*) = true',
      '  end',
      'end',
      'spec = eval(File.read(ARGV[0]), binding, ARGV[0])',
      "print [spec.values['source_files'], spec.values['pod_target_xcconfig']['SWIFT_ACTIVE_COMPILATION_CONDITIONS']].inspect",
    ].join('\n'),
  );
  const conditions = (flavor, env = {}) => {
    fs.rmSync(path.join(root, 'Frameworks'), { recursive: true, force: true });
    if (flavor) {
      fs.mkdirSync(path.join(root, 'Frameworks'));
      fs.writeFileSync(path.join(root, 'Frameworks/PowerLogCore.flavor'), `${flavor}\n`);
    }
    return execFileSync('ruby', [evaluate, podspec], { encoding: 'utf8', env: { ...process.env, ...env } });
  };
  assert.equal(conditions(null), '["**/*.swift", nil]');
  assert.equal(conditions('release'), '["**/*.swift", nil]');
  assert.equal(conditions('development'), '["**/*.swift", "$(inherited) POWER_LOG_DEV_TOOLS"]');
});
