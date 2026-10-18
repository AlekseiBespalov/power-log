const fs = require('node:fs');
const path = require('node:path');
const { withAppDelegate, withInfoPlist, withXcodeProject } = require('@expo/config-plugins');

function patchAppDelegate(contents) {
  if (contents.includes('if PowerLogLab.isRequested { return true }')) return contents;
  const launch = /(didFinishLaunchingWithOptions[\s\S]*?\)\s*->\s*Bool\s*\{)/;
  if (!launch.test(contents)) throw new Error('Lab could not find the AppDelegate launch method');
  return contents.replace(launch, '$1\n    if PowerLogLab.isRequested { return true }');
}

module.exports = config => {
  if (process.env.POWER_LOG_LAB !== '1') return config;
  config = withAppDelegate(config, config => {
    if (config.modResults.language !== 'swift') throw new Error('Lab requires a Swift AppDelegate');
    config.modResults.contents = patchAppDelegate(config.modResults.contents);
    return config;
  });
  config = withInfoPlist(config, config => {
    const manifest = config.modResults.UIApplicationSceneManifest;
    if (!manifest?.UISceneConfigurations) throw new Error('Lab requires the scene lifecycle');
    for (const configurations of Object.values(manifest.UISceneConfigurations))
      for (const scene of configurations)
        scene.UISceneDelegateClassName = '$(PRODUCT_MODULE_NAME).PowerLogLabSceneDelegate';
    return config;
  });
  return withXcodeProject(config, config => {
    const project = config.modResults;
    const phone = project.getFirstTarget();
    const objects = project.hash.project.objects;
    let group = Object.keys(objects.PBXGroup).find(key => objects.PBXGroup[key]?.name === 'PowerLogLab');
    if (!group) {
      group = project.pbxCreateGroup('PowerLogLab');
      objects.PBXGroup[project.getFirstProject().firstProject.mainGroup].children.push({
        value: group,
        comment: 'PowerLogLab',
      });
    }
    for (const file of fs
      .readdirSync(path.join(config.modRequest.projectRoot, 'lab/apple'))
      .filter(file => file.endsWith('.swift'))
      .sort()) {
      const source = `../lab/apple/${file}`;
      if (!project.hasFile(source)) project.addSourceFile(source, { target: phone.uuid }, group);
    }
    if (!project.pbxGroupByName('Resources')) {
      const resources = project.pbxCreateGroup('Resources');
      objects.PBXGroup[project.getFirstProject().firstProject.mainGroup].children.push({
        value: resources,
        comment: 'Resources',
      });
    }
    for (const target of Object.values(objects.PBXNativeTarget).filter(
      target =>
        target.isa === 'PBXNativeTarget' && ['PowerLog', 'PowerLogWatch'].includes(target.name.replaceAll('"', '')),
    )) {
      for (const reference of objects.XCConfigurationList[target.buildConfigurationList].buildConfigurations) {
        const settings = objects.XCBuildConfiguration[reference.value].buildSettings;
        const value = settings.SWIFT_ACTIVE_COMPILATION_CONDITIONS || '$(inherited)';
        const conditions = (Array.isArray(value) ? value : [value]).flatMap(condition =>
          String(condition).replace(/^"|"$/g, '').split(/\s+/),
        );
        if (!conditions.includes('POWER_LOG_LAB'))
          settings.SWIFT_ACTIVE_COMPILATION_CONDITIONS = `"${[...conditions, 'POWER_LOG_LAB'].join(' ')}"`;
      }
      if (target !== phone.firstTarget) {
        const targetId = Object.keys(objects.PBXNativeTarget).find(key => objects.PBXNativeTarget[key] === target);
        for (const source of ['../lab/apple/watch/PowerLogWatchLab.swift'])
          if (!project.hasFile(source)) project.addSourceFile(source, { target: targetId }, group);
        for (const resource of ['watch-build.json']) {
          const source = `../test-results/lab-apple/assets/${resource}`;
          if (!project.hasFile(source)) project.addResourceFile(source, { target: targetId }, group);
        }
      }
    }
    for (const resource of ['build.json']) {
      const source = `../test-results/lab-apple/assets/${resource}`;
      if (!project.hasFile(source)) project.addResourceFile(source, { target: phone.uuid }, group);
    }
    return config;
  });
};
module.exports.patchAppDelegate = patchAppDelegate;
