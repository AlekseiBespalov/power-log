const fs = require('node:fs');
const path = require('node:path');
const { withXcodeProject } = require('@expo/config-plugins');

const targetName = 'PowerLogWatch';
const unquote = value => String(value ?? '').replace(/^"|"$/g, '');

function watchSources(projectRoot) {
  return fs
    .readdirSync(path.join(projectRoot, 'targets/watch'), { recursive: true })
    .filter(file => file.endsWith('.swift') && !file.split(path.sep).includes('Tests'))
    .map(file => `targets/watch/${file.split(path.sep).join('/')}`)
    .concat(['WorkoutSession', 'HealthTypes'].map(name => `modules/power-log/ios/health/${name}.swift`))
    .sort();
}

function configureWatchProject(project, projectRoot, config) {
  const {
    version,
    ios: { bundleIdentifier, appleTeamId: teamId, buildNumber },
  } = config;
  const objects = project.hash.project.objects;
  // xcode.addTargetDependency silently does nothing if these sections are absent
  // from the original one-target Expo template.
  objects.PBXTargetDependency ??= {};
  objects.PBXContainerItemProxy ??= {};
  const nativeTargets = project.pbxNativeTargetSection();
  let targetId = Object.keys(nativeTargets).find(
    key => nativeTargets[key]?.isa === 'PBXNativeTarget' && unquote(nativeTargets[key].name) === targetName,
  );
  if (!targetId)
    targetId = project.addTarget(targetName, 'watch2_app', targetName, `${bundleIdentifier}.watchkitapp`).uuid;
  const target = nativeTargets[targetId];
  const phoneId = project.getFirstTarget().uuid;
  const phone = nativeTargets[phoneId];
  if (!phone.dependencies.some(reference => objects.PBXTargetDependency[reference.value]?.target === targetId)) {
    project.addTargetDependency(phoneId, [targetId]);
  }
  target.productType = '"com.apple.product-type.application"';

  const configurationList = project.pbxXCConfigurationList()[target.buildConfigurationList];
  const configurations = project.pbxXCBuildConfigurationSection();
  for (const reference of configurationList.buildConfigurations) {
    const configuration = configurations[reference.value];
    Object.assign(configuration.buildSettings, {
      PRODUCT_BUNDLE_IDENTIFIER: `"${bundleIdentifier}.watchkitapp"`,
      POWER_LOG_PHONE_BUNDLE_IDENTIFIER: `"${bundleIdentifier}"`,
      INFOPLIST_FILE: '"../targets/watch/Info.plist"',
      CODE_SIGN_ENTITLEMENTS: '"../targets/watch/PowerLogWatch.entitlements"',
      CODE_SIGN_STYLE: 'Automatic',
      GENERATE_INFOPLIST_FILE: 'NO',
      SDKROOT: 'watchos',
      SUPPORTED_PLATFORMS: '"watchos watchsimulator"',
      WATCHOS_DEPLOYMENT_TARGET: '10.0',
      TARGETED_DEVICE_FAMILY: '4',
      SWIFT_VERSION: '5.0',
      SWIFT_STRICT_CONCURRENCY: 'targeted',
      MARKETING_VERSION: version,
      CURRENT_PROJECT_VERSION: buildNumber,
      ENABLE_BITCODE: 'NO',
      SKIP_INSTALL: 'YES',
      ASSETCATALOG_COMPILER_APPICON_NAME: 'AppIcon',
      LD_RUNPATH_SEARCH_PATHS: '"$(inherited) @executable_path/Frameworks"',
      OTHER_LDFLAGS: '"$(inherited)"',
      SWIFT_OPTIMIZATION_LEVEL: configuration.name === 'Debug' ? '"-Onone"' : '"-O"',
    });
    if (teamId) configuration.buildSettings.DEVELOPMENT_TEAM = teamId;
    delete configuration.buildSettings.IPHONEOS_DEPLOYMENT_TARGET;
  }
  const sourcePaths = watchSources(projectRoot).map(file => `../${file}`);

  const expectedPhases = [
    ['PBXSourcesBuildPhase', sourcePaths],
    ['PBXResourcesBuildPhase', ['../assets/Assets.xcassets', '../targets/watch/PrivacyInfo.xcprivacy']],
    ['PBXFrameworksBuildPhase', []],
  ];
  let phases = expectedPhases.map(([type]) => ({
    buildPhase: target.buildPhases.map(reference => objects[type]?.[reference.value]).find(Boolean),
  }));
  const current = expectedPhases.every(([type, paths], index) => {
    const matches = target.buildPhases.filter(reference => objects[type]?.[reference.value]);
    const files = phases[index].buildPhase?.files ?? [];
    return (
      matches.length === 1 &&
      files.length === paths.length &&
      files.every(
        (file, fileIndex) =>
          unquote(objects.PBXFileReference[objects.PBXBuildFile[file.value]?.fileRef]?.path) === paths[fileIndex],
      )
    );
  });
  if (!current) {
    for (const phaseType of ['PBXSourcesBuildPhase', 'PBXFrameworksBuildPhase', 'PBXResourcesBuildPhase']) {
      const section = objects[phaseType] ?? {};
      target.buildPhases = target.buildPhases.filter(reference => {
        const phase = section[reference.value];
        if (!phase) return true;
        for (const file of phase.files ?? []) {
          const fileRef = objects.PBXBuildFile[file.value]?.fileRef;
          if (fileRef) {
            delete objects.PBXFileReference[fileRef];
            delete objects.PBXFileReference[`${fileRef}_comment`];
          }
          delete objects.PBXBuildFile[file.value];
          delete objects.PBXBuildFile[`${file.value}_comment`];
        }
        delete section[reference.value];
        delete section[`${reference.value}_comment`];
        return false;
      });
    }
    phases = [
      project.addBuildPhase(sourcePaths, 'PBXSourcesBuildPhase', 'Sources', targetId),
      project.addBuildPhase(
        ['../assets/Assets.xcassets', '../targets/watch/PrivacyInfo.xcprivacy'],
        'PBXResourcesBuildPhase',
        'Resources',
        targetId,
      ),
    ];
    phases.push(project.addBuildPhase([], 'PBXFrameworksBuildPhase', 'Frameworks', targetId));
  }
  // CocoaPods resolves a file's path through its group; an ungrouped reference makes
  // React Native's privacy manifest aggregation fail during pod install.
  let groupKey = Object.keys(objects.PBXGroup).find(key => unquote(objects.PBXGroup[key]?.name) === targetName);
  if (!groupKey) {
    groupKey = project.pbxCreateGroup(targetName);
    objects.PBXGroup[project.getFirstProject().firstProject.mainGroup].children.push({
      value: groupKey,
      comment: targetName,
    });
  }
  objects.PBXGroup[groupKey].children = phases.flatMap(({ buildPhase }) =>
    buildPhase.files.map(file => {
      const fileRef = objects.PBXBuildFile[file.value].fileRef;
      return { value: fileRef, comment: objects.PBXFileReference[`${fileRef}_comment`] };
    }),
  );
  const attributes = project.getFirstProject().firstProject.attributes;
  attributes.TargetAttributes ??= {};
  attributes.TargetAttributes[targetId] = {
    CreatedOnToolsVersion: '26.0',
    ProvisioningStyle: 'Automatic',
    SystemCapabilities: { 'com.apple.HealthKit': { enabled: 1 } },
    ...(teamId ? { DevelopmentTeam: teamId } : {}),
  };
  // Expo selects the first application entry rather than PBXProject target order.
  objects.PBXNativeTarget = Object.fromEntries([
    [phoneId, nativeTargets[phoneId]],
    [`${phoneId}_comment`, nativeTargets[`${phoneId}_comment`]],
    ...Object.entries(nativeTargets).filter(([key]) => key !== phoneId && key !== `${phoneId}_comment`),
  ]);
  return project;
}

module.exports = config =>
  withXcodeProject(config, config => {
    configureWatchProject(config.modResults, config.modRequest.projectRoot, config);
    return config;
  });
module.exports.configureWatchProject = configureWatchProject;
module.exports.watchSources = watchSources;
