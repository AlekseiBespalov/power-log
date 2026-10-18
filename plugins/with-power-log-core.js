const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { withDangerousMod, withSettingsGradle } = require('expo/config-plugins');

function configureCoreSettings(contents) {
  const marker = "includeBuild('../shared')";
  return contents.includes(marker) ? contents : `${contents.trimEnd()}\n\n${marker}\n`;
}

// CocoaPods finds vendored frameworks only under the pod's root:
// https://github.com/CocoaPods/CocoaPods/blob/1.16.2/lib/cocoapods/sandbox/path_list.rb
function stageCoreFramework(projectRoot) {
  const source = path.join(projectRoot, 'shared/build/XCFrameworks/release/PowerLogCore.xcframework');
  const destination = path.join(projectRoot, 'modules/power-log-bridge/ios/Frameworks/PowerLogCore.xcframework');
  if (!fs.existsSync(path.join(source, 'Info.plist'))) {
    throw new Error(`Build the Apple core before staging ${source}`);
  }
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.rmSync(destination, { recursive: true, force: true });
  fs.cpSync(source, destination, { recursive: true, verbatimSymlinks: true });
}

module.exports = config => {
  config = withSettingsGradle(config, mod => {
    mod.modResults.contents = configureCoreSettings(mod.modResults.contents);
    return mod;
  });
  return withDangerousMod(config, [
    'ios',
    async mod => {
      if (process.env.POWER_LOG_CORE_STAGED !== '1')
        execFileSync('sh', [path.join(mod.modRequest.projectRoot, 'shared/scripts/build-apple.sh')], {
          cwd: mod.modRequest.projectRoot,
          stdio: 'inherit',
        });
      stageCoreFramework(mod.modRequest.projectRoot);
      return mod;
    },
  ]);
};
module.exports.configureCoreSettings = configureCoreSettings;
module.exports.stageCoreFramework = stageCoreFramework;
