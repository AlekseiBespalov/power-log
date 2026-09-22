const fs = require('node:fs');
const path = require('node:path');
const { withXcodeProject } = require('@expo/config-plugins');

const targetName = 'PowerLogWatch';
const unquote = value => String(value ?? '').replace(/^"|"$/g, '');

/** Maintained Swift sources stay outside Expo's generated ios directory. */
function configureWatchProject(project, projectRoot, bundleIdentifier, teamId) {
  const objects = project.hash.project.objects;
  // xcode.addTargetDependency silently does nothing if these sections are absent
  // from the original one-target Expo template.
  objects.PBXTargetDependency ??= {};
  objects.PBXContainerItemProxy ??= {};
  const nativeTargets = project.pbxNativeTargetSection();
  let targetId = Object.keys(nativeTargets).find(key => nativeTargets[key]?.isa === 'PBXNativeTarget' && unquote(nativeTargets[key].name) === targetName);
  if (!targetId) targetId = project.addTarget(targetName, 'watch2_app', targetName, `${bundleIdentifier}.watchkitapp`).uuid;
  const target = nativeTargets[targetId];
  const phoneId = project.getFirstTarget().uuid;
  const phone = nativeTargets[phoneId];
  if (!phone.dependencies.some(reference => objects.PBXTargetDependency[reference.value]?.target === targetId)) {
    project.addTargetDependency(phoneId, [targetId]);
  }
  // Modern watchOS apps put the SwiftUI executable directly in the app target.
  target.productType = '"com.apple.product-type.application"';

  const configurationList = project.pbxXCConfigurationList()[target.buildConfigurationList];
  const configurations = project.pbxXCBuildConfigurationSection();
  for (const reference of configurationList.buildConfigurations) {
    const configuration = configurations[reference.value];
    Object.assign(configuration.buildSettings, {
      PRODUCT_BUNDLE_IDENTIFIER: `"${bundleIdentifier}.watchkitapp"`,
      POWER_LOG_PHONE_BUNDLE_IDENTIFIER: `"${bundleIdentifier}"`,
      INFOPLIST_FILE: '"../apple/WatchApp/Info.plist"',
      CODE_SIGN_ENTITLEMENTS: '"../apple/WatchApp/PowerLogWatch.entitlements"',
      CODE_SIGN_STYLE: 'Automatic',
      GENERATE_INFOPLIST_FILE: 'NO',
      SDKROOT: 'watchos', SUPPORTED_PLATFORMS: '"watchos watchsimulator"',
      WATCHOS_DEPLOYMENT_TARGET: '10.0', TARGETED_DEVICE_FAMILY: '4',
      SWIFT_VERSION: '5.0', SWIFT_STRICT_CONCURRENCY: 'targeted',
      MARKETING_VERSION: '0.1.0', CURRENT_PROJECT_VERSION: '1',
      ENABLE_BITCODE: 'NO', SKIP_INSTALL: 'YES',
      ASSETCATALOG_COMPILER_APPICON_NAME: 'AppIcon',
      LD_RUNPATH_SEARCH_PATHS: '"$(inherited) @executable_path/Frameworks"',
      OTHER_LDFLAGS: '"$(inherited) -lsqlite3 -lcompression"',
      SWIFT_OPTIMIZATION_LEVEL: configuration.name === 'Debug' ? '"-Onone"' : '"-O"',
    });
    if (teamId) configuration.buildSettings.DEVELOPMENT_TEAM = teamId;
    delete configuration.buildSettings.IPHONEOS_DEPLOYMENT_TARGET;
  }
  const sourcePaths = fs.readdirSync(path.join(projectRoot, 'apple/WatchApp'))
    .filter(file => file.endsWith('.swift')).sort().map(file => `../apple/WatchApp/${file}`);
  sourcePaths.push(...['WorkoutDistance', 'WorkoutDistanceStore', 'WorkoutTypes', 'PowerLogStore', 'WorkoutArchive', 'WorkoutControl', 'WorkoutTransfer', 'WorkoutSync'].map(name => `../modules/cyc-bridge/ios/${name}.swift`));

  // Rebuild only this target's source phase so subsequent prebuilds pick up new
  // maintained files without duplicate compilation or stale source references.
  for (const phaseType of ['PBXSourcesBuildPhase', 'PBXFrameworksBuildPhase', 'PBXResourcesBuildPhase']) {
    const section = objects[phaseType] ?? {};
    target.buildPhases = target.buildPhases.filter(reference => {
      const phase = section[reference.value];
      if (!phase) return true;
      for (const file of phase.files ?? []) {
        delete objects.PBXBuildFile[file.value];
        delete objects.PBXBuildFile[`${file.value}_comment`];
      }
      delete section[reference.value]; delete section[`${reference.value}_comment`];
      return false;
    });
  }
  project.addBuildPhase(sourcePaths, 'PBXSourcesBuildPhase', 'Sources', targetId);
  project.addBuildPhase(['../assets/Assets.xcassets'], 'PBXResourcesBuildPhase', 'Resources', targetId);
  project.addBuildPhase([], 'PBXFrameworksBuildPhase', 'Frameworks', targetId);
  const attributes = project.getFirstProject().firstProject.attributes;
  attributes.TargetAttributes ??= {};
  attributes.TargetAttributes[targetId] = {
    CreatedOnToolsVersion: '26.0', ProvisioningStyle: 'Automatic',
    SystemCapabilities: { 'com.apple.HealthKit': { enabled: 1 } },
    ...(teamId ? { DevelopmentTeam: teamId } : {}),
  };
  return project;
}

module.exports = config => withXcodeProject(config, config => {
  configureWatchProject(config.modResults, config.modRequest.projectRoot,
    config.ios.bundleIdentifier, config.ios.appleTeamId);
  return config;
});
module.exports.configureWatchProject = configureWatchProject;
