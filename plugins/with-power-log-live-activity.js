const { withInfoPlist, withXcodeProject } = require('@expo/config-plugins');

const targetName = 'PowerLogActivity';
const unquote = value => String(value ?? '').replace(/^"|"$/g, '');
const intentPath = '../apple/LiveActivity/PowerLogRideIntent.swift';
const sources = [
  '../modules/cyc-bridge/ios/PowerLogRideAttributes.swift',
  intentPath,
  '../apple/LiveActivity/PowerLogRideWidget.swift',
];

function configureActivityProject(project, bundleIdentifier, teamId) {
  const objects = project.hash.project.objects;
  objects.PBXTargetDependency ??= {};
  objects.PBXContainerItemProxy ??= {};
  const targets = project.pbxNativeTargetSection();
  const phoneID = project.getFirstTarget().uuid;
  const phone = targets[phoneID];
  let targetID = Object.keys(targets).find(key => targets[key]?.isa === 'PBXNativeTarget' && unquote(targets[key].name) === targetName);
  if (!targetID) targetID = project.addTarget(targetName, 'app_extension', targetName, `${bundleIdentifier}.activity`).uuid;
  const target = targets[targetID];
  if (!phone.dependencies.some(reference => objects.PBXTargetDependency[reference.value]?.target === targetID)) {
    project.addTargetDependency(phoneID, [targetID]);
  }
  const configurations = project.pbxXCBuildConfigurationSection();
  for (const reference of project.pbxXCConfigurationList()[target.buildConfigurationList].buildConfigurations) {
    const configuration = configurations[reference.value];
    Object.assign(configuration.buildSettings, {
      PRODUCT_BUNDLE_IDENTIFIER: `"${bundleIdentifier}.activity"`,
      INFOPLIST_FILE: '"../apple/LiveActivity/Info.plist"',
      CODE_SIGN_STYLE: 'Automatic', GENERATE_INFOPLIST_FILE: 'NO',
      SDKROOT: 'iphoneos', SUPPORTED_PLATFORMS: '"iphoneos iphonesimulator"',
      IPHONEOS_DEPLOYMENT_TARGET: '26.0', TARGETED_DEVICE_FAMILY: '"1,2"',
      APPLICATION_EXTENSION_API_ONLY: 'YES',
      SWIFT_VERSION: '5.0', SWIFT_STRICT_CONCURRENCY: 'targeted',
      MARKETING_VERSION: '0.1.0', CURRENT_PROJECT_VERSION: '1', SKIP_INSTALL: 'YES',
      LD_RUNPATH_SEARCH_PATHS: '"$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks"',
      SWIFT_OPTIMIZATION_LEVEL: configuration.name === 'Debug' ? '"-Onone"' : '"-O"',
    });
    if (teamId) configuration.buildSettings.DEVELOPMENT_TEAM = teamId;
  }
  if (!target.buildPhases.some(reference => objects.PBXSourcesBuildPhase?.[reference.value])) {
    project.addBuildPhase(sources, 'PBXSourcesBuildPhase', 'Sources', targetID);
    project.addBuildPhase([], 'PBXFrameworksBuildPhase', 'Frameworks', targetID);
    project.addBuildPhase([], 'PBXResourcesBuildPhase', 'Resources', targetID);
  }
  // The intent is discoverable in both targets; LiveActivityIntent executes in the app process.
  const intentReference = Object.entries(project.pbxFileReferenceSection())
    .find(([, file]) => file?.isa === 'PBXFileReference' && unquote(file.path) === intentPath)?.[0];
  const phoneSources = phone.buildPhases.map(reference => objects.PBXSourcesBuildPhase?.[reference.value]).find(Boolean);
  if (!intentReference || !phoneSources) throw new Error('Live Activity intent source phase is missing.');
  if (!phoneSources.files.some(reference => objects.PBXBuildFile[reference.value]?.fileRef === intentReference)) {
    const buildID = project.generateUuid();
    objects.PBXBuildFile[buildID] = { isa: 'PBXBuildFile', fileRef: intentReference, fileRef_comment: 'PowerLogRideIntent.swift' };
    objects.PBXBuildFile[`${buildID}_comment`] = 'PowerLogRideIntent.swift in Sources';
    phoneSources.files.push({ value: buildID, comment: 'PowerLogRideIntent.swift in Sources' });
  }
  for (const reference of project.pbxXCConfigurationList()[phone.buildConfigurationList].buildConfigurations) {
    const settings = configurations[reference.value].buildSettings;
    const value = settings.SWIFT_ACTIVE_COMPILATION_CONDITIONS || '$(inherited)';
    const conditions = (Array.isArray(value) ? value : [value]).flatMap(condition => unquote(condition).split(/\s+/));
    if (!conditions.includes('POWER_LOG_APP')) {
      settings.SWIFT_ACTIVE_COMPILATION_CONDITIONS = `"${[...conditions, 'POWER_LOG_APP'].join(' ')}"`;
    }
  }
  const attributes = project.getFirstProject().firstProject.attributes;
  attributes.TargetAttributes ??= {};
  attributes.TargetAttributes[targetID] = {
    CreatedOnToolsVersion: '26.0', ProvisioningStyle: 'Automatic',
    ...(teamId ? { DevelopmentTeam: teamId } : {}),
  };
  return project;
}

module.exports = config => {
  config = withInfoPlist(config, config => {
    config.modResults.NSSupportsLiveActivities = true;
    return config;
  });
  return withXcodeProject(config, config => {
    configureActivityProject(config.modResults, config.ios.bundleIdentifier, config.ios.appleTeamId);
    return config;
  });
};
module.exports.configureActivityProject = configureActivityProject;
