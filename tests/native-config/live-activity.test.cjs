const assert = require('node:assert/strict');
const test = require('node:test');
const xcode = require('xcode');
const { configureActivityProject } = require('../../plugins/with-power-log-live-activity');

function projectFixture() {
  const project = xcode.project('/unused/project.pbxproj');
  project.hash = { project: { rootObject: '000000000000000000000001', objects: {
    PBXProject: { '000000000000000000000001': { isa: 'PBXProject', attributes: {}, targets: [] } },
    PBXNativeTarget: {}, PBXBuildFile: {}, PBXFileReference: {}, XCConfigurationList: {}, XCBuildConfiguration: {},
    PBXGroup: { '000000000000000000000002': { isa: 'PBXGroup', name: 'Products', children: [] } },
  } } };
  const phone = project.addTarget('PowerLog', 'application', 'PowerLog', 'app.powerlog.test');
  project.addBuildPhase([], 'PBXSourcesBuildPhase', 'Sources', phone.uuid);
  return project;
}

function inspect(project) {
  const objects = project.hash.project.objects;
  const targets = Object.entries(objects.PBXNativeTarget).filter(([, target]) => target.isa === 'PBXNativeTarget');
  const [activityID, activity] = targets.find(([, target]) => target.name.replaceAll('"', '') === 'PowerLogActivity');
  const phone = project.getFirstTarget().firstTarget;
  const sources = target => target.buildPhases.flatMap(reference => objects.PBXSourcesBuildPhase[reference.value]?.files ?? [])
    .map(reference => objects.PBXFileReference[objects.PBXBuildFile[reference.value].fileRef].path.replaceAll('"', ''));
  return { objects, targets, phone, activityID, activity, sources };
}

test('prebuild embeds one activity and keeps native intent available in both targets', () => {
  const project = projectFixture();
  for (let repetition = 0; repetition < 3; repetition++) {
    configureActivityProject(project, 'app.powerlog.test', 'SYNTHETIC');
    const { objects, targets, phone, activityID, activity, sources } = inspect(project);
    assert.equal(targets.length, 2);
    assert.equal(phone.dependencies.filter(reference => objects.PBXTargetDependency[reference.value].target === activityID).length, 1);
    assert.equal(sources(phone).filter(source => source.endsWith('PowerLogRideIntent.swift')).length, 1);
    assert.deepEqual(sources(activity).sort(), [
      '../apple/LiveActivity/PowerLogRideIntent.swift',
      '../apple/LiveActivity/PowerLogRideWidget.swift',
      '../modules/cyc-bridge/ios/PowerLogRideAttributes.swift',
    ]);
    const embedded = phone.buildPhases.flatMap(reference => objects.PBXCopyFilesBuildPhase?.[reference.value]?.files ?? [])
      .filter(reference => objects.PBXBuildFile[reference.value].fileRef === activity.productReference);
    assert.equal(embedded.length, 1);
    for (const reference of objects.XCConfigurationList[phone.buildConfigurationList].buildConfigurations) {
      const conditions = objects.XCBuildConfiguration[reference.value].buildSettings.SWIFT_ACTIVE_COMPILATION_CONDITIONS;
      assert.equal(conditions.match(/POWER_LOG_APP/g)?.length, 1);
    }
    for (const reference of objects.XCConfigurationList[activity.buildConfigurationList].buildConfigurations) {
      const settings = objects.XCBuildConfiguration[reference.value].buildSettings;
      assert.equal(settings.IPHONEOS_DEPLOYMENT_TARGET, '26.0');
      assert.equal(settings.APPLICATION_EXTENSION_API_ONLY, 'YES');
      assert.equal(settings.SWIFT_ACTIVE_COMPILATION_CONDITIONS, undefined);
      assert.equal(settings.DEVELOPMENT_TEAM, 'SYNTHETIC');
    }
  }
});

test('prebuild updates the extension identity with the configured phone identity', () => {
  const project = projectFixture();
  configureActivityProject(project, 'app.powerlog.first');
  configureActivityProject(project, 'app.powerlog.second');
  const { objects, activity } = inspect(project);
  for (const reference of objects.XCConfigurationList[activity.buildConfigurationList].buildConfigurations) {
    assert.equal(objects.XCBuildConfiguration[reference.value].buildSettings.PRODUCT_BUNDLE_IDENTIFIER, '"app.powerlog.second.activity"');
  }
});
