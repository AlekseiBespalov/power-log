const { withAppBuildGradle } = require('expo/config-plugins');

function configureSigning(contents) {
  const marker = '// Power Log release signing';
  const clean = contents.replace(/\n*\/\/ Power Log release signing\nandroid \{[\s\S]*?\n\}\n?/g, '').trimEnd();
  // Release builds never silently use Expo's public debug key.
  return `${clean}

${marker}
android {
    signingConfigs {
        powerLogRelease {
            if (System.getenv('POWER_LOG_ANDROID_KEYSTORE')) {
                storeFile file(System.getenv('POWER_LOG_ANDROID_KEYSTORE'))
                storePassword System.getenv('POWER_LOG_ANDROID_STORE_PASSWORD')
                keyAlias System.getenv('POWER_LOG_ANDROID_KEY_ALIAS')
                keyPassword System.getenv('POWER_LOG_ANDROID_KEY_PASSWORD')
            }
        }
    }
    buildTypes.release.signingConfig = System.getenv('POWER_LOG_ANDROID_PREVIEW') == '1' ? signingConfigs.debug : signingConfigs.powerLogRelease
}
`;
}
module.exports = config => withAppBuildGradle(config, mod => {
  mod.modResults.contents = configureSigning(mod.modResults.contents);
  return mod;
});
module.exports.configureSigning = configureSigning;
