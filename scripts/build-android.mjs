import { mkdirSync, copyFileSync } from 'node:fs';
import { join } from 'node:path';
import { androidEnvironment, root, runner } from './android-environment.mjs';

const preview = process.argv.includes('--preview');
const prepare = process.argv.includes('--prepare');
const env = androidEnvironment({ POWER_LOG_ANDROID_PREVIEW: preview ? '1' : '0' });
for (const key of Object.keys(env))
  if (key.startsWith('EXPO_PUBLIC_') || key === 'POWER_LOG_APPLE_TEAM_ID') delete env[key];
const run = runner(env);
if (!preview && !prepare)
  for (const key of [
    'POWER_LOG_ANDROID_KEYSTORE',
    'POWER_LOG_ANDROID_STORE_PASSWORD',
    'POWER_LOG_ANDROID_KEY_ALIAS',
    'POWER_LOG_ANDROID_KEY_PASSWORD',
  ]) {
    if (!env[key]) throw new Error(`Set ${key} for a signed release, or use --preview for a separate test app.`);
  }
run(process.execPath, ['node_modules/expo/bin/cli', 'prebuild', '--platform', 'android', '--no-install']);
if (!prepare) {
  const arch = process.argv.find(arg => arg.startsWith('--arch='))?.slice(7);
  if (arch && !/^(arm64-v8a|x86_64|armeabi-v7a)(,(arm64-v8a|x86_64|armeabi-v7a))*$/.test(arch))
    throw new Error('Unsupported Android architecture');
  run(
    process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
    [
      ':cyc-bridge:testReleaseUnitTest',
      ':app:assembleRelease',
      '--console=plain',
      ...(arch ? [`-PreactNativeArchitectures=${arch}`] : []),
    ],
    join(root, 'android'),
  );
  const output = join(root, 'artifacts/builds/android');
  mkdirSync(output, { recursive: true });
  const apk = join(output, `power-log${preview ? '-preview' : ''}.apk`);
  copyFileSync(join(root, 'android/app/build/outputs/apk/release/app-release.apk'), apk);
  console.log(`Android APK: ${apk}`);
}
