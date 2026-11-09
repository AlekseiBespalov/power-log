import { mkdirSync, copyFileSync } from 'node:fs';
import { join } from 'node:path';
import { androidEnvironment, root, runner } from './android-environment.mjs';

const debugSigned = process.argv.includes('--debug-signed');
const prepare = process.argv.includes('--prepare');
const bundle = process.argv.includes('--bundle');
const env = androidEnvironment({
  POWER_LOG_ANDROID_DEBUG_SIGNING: debugSigned ? '1' : '0',
  POWER_LOG_ANDROID_BUNDLE: bundle ? '1' : '0',
});
for (const key of Object.keys(env))
  if (key.startsWith('EXPO_PUBLIC_') || key === 'POWER_LOG_APPLE_TEAM_ID') delete env[key];
const run = runner(env);
if (!debugSigned && !prepare) {
  const signing = bundle ? 'POWER_LOG_ANDROID_UPLOAD_' : 'POWER_LOG_ANDROID_';
  for (const key of ['KEYSTORE', 'STORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD'].map(name => signing + name)) {
    if (!env[key])
      throw new Error(
        `Set ${key} for a signed ${bundle ? 'App Bundle' : 'release'}, or use --debug-signed for a build that only the emulator and the checks install.`,
      );
  }
}
run(process.execPath, ['scripts/prebuild.mjs', '--platform', 'android', '--no-install']);
if (!prepare) {
  const arch = process.argv.find(arg => arg.startsWith('--arch='))?.slice(7);
  if (arch && !/^(arm64-v8a|x86_64|armeabi-v7a)(,(arm64-v8a|x86_64|armeabi-v7a))*$/.test(arch))
    throw new Error('Unsupported Android architecture');
  run(
    process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
    [
      ':power-log:testDebugUnitTest',
      bundle ? ':app:bundleRelease' : ':app:assembleRelease',
      '--console=plain',
      ...(arch ? [`-PreactNativeArchitectures=${arch}`] : []),
      ...(env.POWER_LOG_GRADLE_OFFLINE === '1' ? ['--offline'] : []),
    ],
    join(root, 'android'),
  );
  const output = join(root, 'artifacts/builds/android');
  mkdirSync(output, { recursive: true });
  const [extension, built] = bundle
    ? ['aab', 'bundle/release/app-release.aab']
    : ['apk', 'apk/release/app-release.apk'];
  const artifact = join(output, `power-log${debugSigned ? '-debug-signed' : ''}.${extension}`);
  copyFileSync(join(root, 'android/app/build/outputs', built), artifact);
  console.log(`Android ${bundle ? 'App Bundle' : 'APK'}: ${artifact}`);
}
