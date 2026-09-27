import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { androidEnvironment, root, runner } from './android-environment.mjs';

const env = androidEnvironment({ POWER_LOG_ANDROID_PREVIEW: '1' });
const run = runner(env);
const emulator = process.argv.includes('--emulator');
let architecture;
if (emulator) {
  const adb = join(env.ANDROID_HOME ?? '', 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  const devices = spawnSync(adb, ['devices'], { encoding: 'utf8', env });
  const serial = devices.stdout
    ?.split('\n')
    .find(line => /^emulator-\d+\s+device$/.test(line.trim()))
    ?.split(/\s+/)[0];
  if (!serial) throw new Error('Start an Android emulator first. This test does not target physical phones.');
  env.ANDROID_SERIAL = serial;
  architecture = spawnSync(adb, ['-s', serial, 'shell', 'getprop', 'ro.product.cpu.abi'], {
    encoding: 'utf8',
    env,
  }).stdout.trim();
  if (!['arm64-v8a', 'x86_64', 'x86', 'armeabi-v7a'].includes(architecture))
    throw new Error('Unsupported emulator architecture');
}
run(process.execPath, ['scripts/build-android.mjs', '--prepare', '--preview']);
run(
  process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
  [
    ':cyc-bridge:testReleaseUnitTest',
    ...(emulator ? [':cyc-bridge:connectedDebugAndroidTest'] : []),
    '--console=plain',
    ...(architecture ? [`-PreactNativeArchitectures=${architecture}`] : []),
  ],
  join(root, 'android'),
);
