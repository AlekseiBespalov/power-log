const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { getConfig } = require('@expo/config');
const withLab = require('../../plugins/with-power-log-lab');
const { configureWatchProject } = require('../../plugins/with-power-log-watch');
const { configureActivityProject } = require('../../plugins/with-power-log-live-activity');
const { withBuildProperties } = require('expo-build-properties');
const { projectFixture } = require('./project-fixture.cjs');

function config(lab) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-lab-config-'));
  const previous = process.env.POWER_LOG_LAB;
  try {
    fs.copyFileSync('app.config.ts', path.join(root, 'app.config.ts'));
    fs.copyFileSync('package.json', path.join(root, 'package.json'));
    if (lab) process.env.POWER_LOG_LAB = '1';
    else delete process.env.POWER_LOG_LAB;
    return getConfig(root, { skipPlugins: true, skipSDKVersionRequirement: true }).exp;
  } finally {
    if (previous === undefined) delete process.env.POWER_LOG_LAB;
    else process.env.POWER_LOG_LAB = previous;
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('Lab identities are distinct while the native project name remains PowerLog', () => {
  const lab = config(true);
  assert.equal(lab.name, 'Power Log');
  assert.equal(lab.ios.infoPlist.CFBundleDisplayName, 'Power Log Lab');
  assert.equal(lab.ios.bundleIdentifier, 'app.powerlog.lab');
  assert.equal(lab.scheme, 'power-log-lab');
  const project = projectFixture();
  configureWatchProject(project, process.cwd(), lab);
  configureActivityProject(project, lab);
  const objects = project.hash.project.objects;
  for (const [name, suffix] of [
    ['PowerLogWatch', '.watchkitapp'],
    ['PowerLogActivity', '.activity'],
  ]) {
    const target = Object.values(objects.PBXNativeTarget).find(
      target => target.isa === 'PBXNativeTarget' && target.name.replaceAll('"', '') === name,
    );
    for (const reference of objects.XCConfigurationList[target.buildConfigurationList].buildConfigurations)
      assert.equal(
        objects.XCBuildConfiguration[reference.value].buildSettings.PRODUCT_BUNDLE_IDENTIFIER,
        `"app.powerlog.lab${suffix}"`,
      );
  }
});

test('without the Lab flag the plugin leaves every production prebuild byte untouched', () => {
  const previous = process.env.POWER_LOG_LAB;
  delete process.env.POWER_LOG_LAB;
  try {
    const production = config(false);
    assert.equal(production.name, 'Power Log');
    assert.equal(production.scheme, 'power-log');
    assert.equal(production.ios.infoPlist.CFBundleDisplayName, undefined);
    const project = projectFixture();
    configureWatchProject(project, process.cwd(), production);
    configureActivityProject(project, production);
    production.modResults = project;
    const beforeProject = project.writeSync();
    const beforeConfig = JSON.stringify(production);
    assert.equal(withLab(production), production);
    assert.equal(JSON.stringify(production), beforeConfig);
    assert.equal(project.writeSync(), beforeProject);
    assert.equal(production.mods, undefined);
  } finally {
    if (previous === undefined) delete process.env.POWER_LOG_LAB;
    else process.env.POWER_LOG_LAB = previous;
  }
});

test('Lab prebuild registers owner harnesses only in their Lab targets', async () => {
  const previous = process.env.POWER_LOG_LAB;
  process.env.POWER_LOG_LAB = '1';
  try {
    const project = projectFixture();
    const lab = config(true);
    configureWatchProject(project, process.cwd(), lab);
    configureActivityProject(project, lab);
    const modified = withLab(lab);
    assert.ok(modified.mods?.ios?.xcodeproj);
    await modified.mods.ios.xcodeproj({ ...modified, modResults: project, modRequest: { projectRoot: process.cwd() } });
    const objects = project.hash.project.objects;
    const phone = project.getFirstTarget().firstTarget;
    for (const reference of objects.XCConfigurationList[phone.buildConfigurationList].buildConfigurations) {
      const settings = objects.XCBuildConfiguration[reference.value].buildSettings;
      assert.equal(settings.SWIFT_ACTIVE_COMPILATION_CONDITIONS.match(/POWER_LOG_APP/g)?.length, 1);
      assert.equal(settings.SWIFT_ACTIVE_COMPILATION_CONDITIONS.match(/POWER_LOG_LAB/g)?.length, 1);
    }
    const activity = Object.values(objects.PBXNativeTarget).find(
      target => target.isa === 'PBXNativeTarget' && target.name.replaceAll('"', '') === 'PowerLogActivity',
    );
    for (const reference of objects.XCConfigurationList[activity.buildConfigurationList].buildConfigurations)
      assert.equal(objects.XCBuildConfiguration[reference.value].buildSettings.POWER_LOG_URL_SCHEME, '"power-log-lab"');
    const sources = target =>
      target.buildPhases
        .flatMap(reference => objects.PBXSourcesBuildPhase?.[reference.value]?.files ?? [])
        .map(reference =>
          objects.PBXFileReference[objects.PBXBuildFile[reference.value].fileRef].path.replaceAll('"', ''),
        );
    const expected = fs
      .readdirSync('lab/apple')
      .filter(name => name.endsWith('.swift'))
      .map(name => `../lab/apple/${name}`)
      .sort();
    assert.ok(expected.length > 0);
    assert.deepEqual(
      sources(phone)
        .filter(name => name.startsWith('../lab/'))
        .sort(),
      expected,
    );
    for (const target of Object.values(objects.PBXNativeTarget).filter(
      target => target.isa === 'PBXNativeTarget' && target !== phone,
    ))
      assert.deepEqual(
        sources(target).filter(name => name.startsWith('../lab/')),
        target.name.replaceAll('"', '') === 'PowerLogWatch' ? ['../lab/apple/watch/PowerLogWatchLab.swift'] : [],
      );
    const watch = Object.values(objects.PBXNativeTarget).find(
      target => target.isa === 'PBXNativeTarget' && target.name.replaceAll('"', '') === 'PowerLogWatch',
    );
    const resources = watch.buildPhases
      .flatMap(reference => objects.PBXResourcesBuildPhase?.[reference.value]?.files ?? [])
      .map(reference =>
        objects.PBXFileReference[objects.PBXBuildFile[reference.value].fileRef].path.replaceAll('"', ''),
      );
    assert.ok(resources.includes('../test-results/lab-apple/assets/watch-build.json'));
    assert.equal(
      withLab.patchAppDelegate(
        'didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil\n  ) -> Bool {\n    let delegate = ReactNativeDelegate()',
      ),
      'didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil\n  ) -> Bool {\n    if PowerLogLab.isRequested { return true }\n    let delegate = ReactNativeDelegate()',
    );
    assert.throws(() => withLab.patchAppDelegate('changed template'), /launch method/);
  } finally {
    if (previous === undefined) delete process.env.POWER_LOG_LAB;
    else process.env.POWER_LOG_LAB = previous;
  }
});

test('Lab scene handling composes with Expo scene support across repeated prebuilds', async () => {
  const previous = process.env.POWER_LOG_LAB;
  process.env.POWER_LOG_LAB = '1';
  try {
    const lab = config(true);
    lab._internal.projectRoot = process.cwd();
    const composed = withBuildProperties(withLab(lab), { ios: { enableSceneSupport: true } });
    let previousPlist = {};
    for (let repeat = 0; repeat < 3; repeat++) {
      const result = await composed.mods.ios.infoPlist({
        ...composed,
        modResults: { ...previousPlist, ...lab.ios.infoPlist },
        modRequest: { projectRoot: process.cwd() },
      });
      previousPlist = result.modResults;
      assert.equal(
        previousPlist.UIApplicationSceneManifest.UISceneConfigurations.UIWindowSceneSessionRoleApplication[0]
          .UISceneDelegateClassName,
        '$(PRODUCT_MODULE_NAME).PowerLogLabSceneDelegate',
      );
    }
  } finally {
    if (previous === undefined) delete process.env.POWER_LOG_LAB;
    else process.env.POWER_LOG_LAB = previous;
  }
});

test('the Lab simulator build uses Xcode local signing and checks the bundle before installation', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'power-log-lab-build-'));
  try {
    fs.mkdirSync(path.join(root, 'scripts'));
    fs.mkdirSync(path.join(root, 'ios'));
    const bin = path.join(root, 'bin');
    fs.mkdirSync(bin);
    fs.copyFileSync('scripts/build-ios-simulator.sh', path.join(root, 'scripts/build-ios-simulator.sh'));
    fs.copyFileSync('scripts/install-pods.sh', path.join(root, 'scripts/install-pods.sh'));
    const stub =
      '#!/bin/sh\nname=$(basename "$0")\nprintf "%s" "$name" >> "$POWER_LOG_TEST_COMMANDS"\nfor arg in "$@"; do printf "|%s" "$arg" >> "$POWER_LOG_TEST_COMMANDS"; done\nprintf "\\n" >> "$POWER_LOG_TEST_COMMANDS"\n';
    for (const name of ['node', 'bundle', 'xcodebuild', 'npm', 'codesign']) {
      const file = path.join(bin, name);
      fs.writeFileSync(file, stub);
      fs.chmodSync(file, 0o755);
    }
    for (const [index, lab] of [false, true, false].entries()) {
      const output = path.join(root, `commands-${index}`);
      const result = spawnSync('/bin/sh', [path.join(root, 'scripts/build-ios-simulator.sh')], {
        env: {
          ...process.env,
          PATH: `${bin}:${process.env.PATH}`,
          POWER_LOG_LAB: lab ? '1' : '0',
          POWER_LOG_TEST_COMMANDS: output,
        },
        encoding: 'utf8',
      });
      assert.equal(result.status, 0, result.stderr);
      const commands = fs
        .readFileSync(output, 'utf8')
        .trim()
        .split('\n')
        .map(line => line.split('|'));
      const build = commands.find(command => command[0] === 'xcodebuild');
      const prebuild = commands.findIndex(command => command[0] === 'node' && command[1] === 'scripts/prebuild.mjs');
      assert.ok(prebuild >= 0, 'the build runs the flavor prebuild');
      assert.ok(commands[prebuild].includes('--clean'), 'each flavor switch rebuilds the native project');
      assert.ok(build.includes('PowerLog'));
      assert.ok(build.includes('ios/PowerLog.xcworkspace'));
      assert.ok(build.includes(`CODE_SIGNING_ALLOWED=${lab ? 'YES' : 'NO'}`));
      if (lab) {
        assert.ok(build.includes('CODE_SIGNING_REQUIRED=NO'));
        assert.ok(build.includes('CODE_SIGN_IDENTITY=-'));
        const bundle = commands.findIndex(command => command[0] === 'npm' && command.includes('test:ios-bundle'));
        const signing = commands.findIndex(command => command[0] === 'codesign');
        assert.ok(bundle > commands.indexOf(build) && signing > bundle);
        assert.deepEqual(commands[signing].slice(1, 4), ['--verify', '--deep', '--strict']);
        assert.equal(commands.filter(command => command[0] === 'codesign').length, 1);
      } else {
        assert.ok(commands.every(command => command[0] !== 'codesign' && command[0] !== 'npm'));
      }
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
