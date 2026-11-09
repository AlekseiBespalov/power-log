const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { withDangerousMod, withGradleProperties, withSettingsGradle } = require('expo/config-plugins');

function configureCoreSettings(contents) {
  const marker = "includeBuild('../shared')";
  return contents.includes(marker) ? contents : `${contents.trimEnd()}\n\n${marker}\n`;
}

function configureCoreProperties(properties) {
  const memory = {
    type: 'property',
    key: 'org.gradle.jvmargs',
    value: '-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8',
  };
  const current = item => item.type === 'property' && item.key === memory.key;
  return properties.some(current) ? properties.map(item => (current(item) ? memory : item)) : [...properties, memory];
}

function coreFlavor(env = process.env) {
  return env.POWER_LOG_DEV_CLIENT === '1' ? 'development' : 'release';
}

function appleSlice(env = process.env) {
  return env.POWER_LOG_APPLE_SLICE === 'simulator' ? 'simulator' : 'device';
}

// CocoaPods finds vendored frameworks only under the pod's root:
// https://github.com/CocoaPods/CocoaPods/blob/1.16.2/lib/cocoapods/sandbox/path_list.rb
function stageCoreFramework(projectRoot, flavor = 'release') {
  const build = flavor === 'development' ? 'debug' : 'release';
  const source = path.join(projectRoot, `shared/runtime/build/XCFrameworks/${build}/PowerLogCore.xcframework`);
  const frameworks = path.join(projectRoot, 'modules/power-log/ios/Frameworks');
  const destination = path.join(frameworks, 'PowerLogCore.xcframework');
  if (!fs.existsSync(path.join(source, 'Info.plist'))) {
    throw new Error(`Build the Apple core before staging ${source}`);
  }
  fs.mkdirSync(frameworks, { recursive: true });
  fs.rmSync(destination, { recursive: true, force: true });
  fs.cpSync(source, destination, { recursive: true, verbatimSymlinks: true });
  fs.writeFileSync(path.join(frameworks, 'PowerLogCore.flavor'), `${flavor}\n`);
}

module.exports = config => {
  config = withSettingsGradle(config, mod => {
    mod.modResults.contents = configureCoreSettings(mod.modResults.contents);
    return mod;
  });
  config = withGradleProperties(config, mod => {
    mod.modResults = configureCoreProperties(mod.modResults);
    return mod;
  });
  return withDangerousMod(config, [
    'ios',
    async mod => {
      const flavor = coreFlavor();
      if (process.env.POWER_LOG_CORE_STAGED !== '1')
        execFileSync(
          'sh',
          [
            path.join(mod.modRequest.projectRoot, 'shared/scripts/build-apple.sh'),
            ...(flavor === 'development' ? ['--dev-tools', '--slice', appleSlice()] : []),
          ],
          { cwd: mod.modRequest.projectRoot, stdio: 'inherit' },
        );
      stageCoreFramework(mod.modRequest.projectRoot, flavor);
      return mod;
    },
  ]);
};
module.exports.configureCoreSettings = configureCoreSettings;
module.exports.configureCoreProperties = configureCoreProperties;
module.exports.stageCoreFramework = stageCoreFramework;
module.exports.coreFlavor = coreFlavor;
module.exports.appleSlice = appleSlice;
