import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, copyFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { homedir } from 'node:os';

const preview = process.argv.includes('--preview');
const prepare = process.argv.includes('--prepare');
const env = { ...process.env, EXPO_NO_DOTENV: '1', POWER_LOG_ANDROID_PREVIEW: preview ? '1' : '0' };
for (const key of Object.keys(env)) if (key.startsWith('EXPO_PUBLIC_') || key === 'POWER_LOG_APPLE_TEAM_ID') delete env[key];
if (!env.JAVA_HOME && process.platform === 'darwin') {
  const installed = spawnSync('/usr/libexec/java_home', ['-v', '17'], { encoding: 'utf8' });
  if (installed.status === 0) env.JAVA_HOME = installed.stdout.trim();
}
if (!env.JAVA_HOME && process.platform === 'darwin') {
  env.JAVA_HOME = ['/Applications/Android Studio.app/Contents/jbr/Contents/Home', join(homedir(), 'Applications/Android Studio.app/Contents/jbr/Contents/Home')].find(existsSync);
}
if (!env.ANDROID_HOME && process.platform === 'darwin') env.ANDROID_HOME = join(homedir(), 'Library/Android/sdk');
const root = resolve(import.meta.dirname, '..');
const run = (command, args, cwd = root) => {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
};
if (!preview && !prepare) for (const key of ['POWER_LOG_ANDROID_KEYSTORE', 'POWER_LOG_ANDROID_STORE_PASSWORD', 'POWER_LOG_ANDROID_KEY_ALIAS', 'POWER_LOG_ANDROID_KEY_PASSWORD']) {
  if (!env[key]) throw new Error(`Set ${key} for a signed release, or use --preview for a separate test app.`);
}
run(process.execPath, ['node_modules/expo/bin/cli', 'prebuild', '--platform', 'android', '--no-install']);
if (!prepare) {
  const arch = process.argv.find(arg => arg.startsWith('--arch='))?.slice(7);
  if (arch && !/^(arm64-v8a|x86_64|armeabi-v7a)(,(arm64-v8a|x86_64|armeabi-v7a))*$/.test(arch)) throw new Error('Unsupported Android architecture');
  run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', [':cyc-bridge:testReleaseUnitTest', ':app:assembleRelease', '--console=plain', ...(arch ? [`-PreactNativeArchitectures=${arch}`] : [])], join(root, 'android'));
  const output = join(root, 'artifacts/builds/android');
  mkdirSync(output, { recursive: true });
  const apk = join(output, `power-log${preview ? '-preview' : ''}.apk`);
  copyFileSync(join(root, 'android/app/build/outputs/apk/release/app-release.apk'), apk);
  console.log(`Android APK: ${apk}`);
}
