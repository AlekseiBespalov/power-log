import { spawnSync } from 'node:child_process';
import { resolve, join } from 'node:path';
import { homedir } from 'node:os';

const root = resolve(import.meta.dirname, '..');
const env = { ...process.env, EXPO_NO_DOTENV: '1', POWER_LOG_ANDROID_PREVIEW: '1' };
if (!env.JAVA_HOME && process.platform === 'darwin') {
  const java = spawnSync('/usr/libexec/java_home', ['-v', '17'], { encoding: 'utf8' });
  if (java.status === 0) env.JAVA_HOME = java.stdout.trim();
}
if (!env.ANDROID_HOME && process.platform === 'darwin') env.ANDROID_HOME = join(homedir(), 'Library/Android/sdk');
const run = (command, args, cwd = root) => {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
};
const emulator = process.argv.includes('--emulator');
let architecture;
if (emulator) {
  const adb = join(env.ANDROID_HOME ?? '', 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  const devices = spawnSync(adb, ['devices'], { encoding: 'utf8', env });
  const serial = devices.stdout?.split('\n').find(line => /^emulator-\d+\s+device$/.test(line.trim()))?.split(/\s+/)[0];
  if (!serial) throw new Error('Start an Android emulator first. This test does not target physical phones.');
  env.ANDROID_SERIAL = serial;
  architecture = spawnSync(adb, ['-s', serial, 'shell', 'getprop', 'ro.product.cpu.abi'], { encoding: 'utf8', env }).stdout.trim();
  if (!['arm64-v8a', 'x86_64', 'x86', 'armeabi-v7a'].includes(architecture)) throw new Error('Unsupported emulator architecture');
}
run(process.execPath, ['scripts/build-android.mjs', '--prepare', '--preview']);
run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', [':cyc-bridge:testReleaseUnitTest', ...(emulator ? [':cyc-bridge:connectedDebugAndroidTest'] : []), '--console=plain', ...(architecture ? [`-PreactNativeArchitectures=${architecture}`] : [])], join(root, 'android'));
