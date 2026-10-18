import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { androidEnvironment, root, runner } from './android-environment.mjs';

const fixtures = mkdtempSync(join(tmpdir(), 'powerlog-contract-'));
process.on('exit', () => rmSync(fixtures, { recursive: true, force: true }));
const env = androidEnvironment({
  POWER_LOG_ANDROID_PREVIEW: '1',
  POWER_LOG_CONTRACT_FIXTURES: join(fixtures, 'contract'),
});
const run = runner(env);
let emulator = process.argv.includes('--emulator');
const automaticEmulator = process.argv.includes('--if-emulator');
let architecture;
if (emulator || automaticEmulator) {
  const adb = join(env.ANDROID_HOME ?? '', 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  const devices = spawnSync(adb, ['devices'], { encoding: 'utf8', env });
  if (devices.error || devices.status !== 0)
    throw new Error(`Cannot inspect emulator availability: ${devices.error?.message ?? devices.stderr}`);
  const serial = devices.stdout
    ?.split('\n')
    .filter(line => /^emulator-\d+\s+device$/.test(line.trim()))
    .map(line => line.split(/\s+/)[0])
    .find(serial => serial === 'emulator-5554');
  if (!serial && emulator) throw new Error('Start emulator-5554 first. Connected tests target only that emulator.');
  if (!serial && /^emulator-\d+\s+device$/m.test(devices.stdout ?? ''))
    throw new Error('The connected gate requires emulator-5554; another emulator is running.');
  emulator = Boolean(serial);
  if (emulator) {
    env.ANDROID_SERIAL = serial;
    architecture = spawnSync(adb, ['-s', serial, 'shell', 'getprop', 'ro.product.cpu.abi'], {
      encoding: 'utf8',
      env,
    }).stdout.trim();
    if (!['arm64-v8a', 'x86_64', 'x86', 'armeabi-v7a'].includes(architecture))
      throw new Error('Unsupported emulator architecture');
  } else {
    console.log('Android connected suites not executed: emulator-5554 is unavailable. JVM suites still run.');
  }
}
run(process.execPath, ['scripts/prepare-android-crash-tests.mjs']);
run('python3', ['scripts/expand-contract-fixtures.py', env.POWER_LOG_CONTRACT_FIXTURES]);
run(process.execPath, ['scripts/build-android.mjs', '--prepare', '--preview']);
const gradleArguments = [
  `-Pandroid.testInstrumentationRunnerArguments.powerlogVolume=${process.argv.includes('--full') ? 'full' : 'smoke'}`,
  '--console=plain',
  ...(architecture
    ? [`-PreactNativeArchitectures=${architecture}`, `-Pandroid.injected.device.serial=${env.ANDROID_SERIAL}`]
    : []),
  '--build-cache',
  '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8',
  '-Pkotlin.compiler.execution.strategy=in-process',
  ...(process.argv.includes('--everyday') ? ['-PpowerLogEveryday'] : []),
  ...(env.POWER_LOG_GRADLE_OFFLINE === '1' ? ['--offline'] : []),
];
const runGradle = tasks =>
  run(
    process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
    [...tasks, ...gradleArguments],
    join(root, 'android'),
  );
runGradle([
  ':power-log-bridge:testDebugUnitTest',
  ':power-log-bridge:compileReleaseKotlin',
  ...(emulator ? [':power-log-bridge:assembleDebugAndroidTest'] : []),
]);
if (emulator) {
  const adb = join(env.ANDROID_HOME, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  const onEmulator = args => run(adb, ['-s', env.ANDROID_SERIAL, ...args]);
  onEmulator([
    'install',
    '-r',
    '-t',
    join(
      root,
      'modules/power-log-bridge/android/build/outputs/apk/androidTest/debug/power-log-bridge-debug-androidTest.apk',
    ),
  ]);
  const manifest = readFileSync(
    join(root, 'modules/power-log-bridge/android/src/androidTest/AndroidManifest.xml'),
    'utf8',
  );
  for (const permission of manifest.matchAll(/android:name="(android\.permission\.health\.[^"]+)"/g))
    onEmulator(['shell', 'pm', 'grant', 'app.powerlog.bridge.test', permission[1]]);
  runGradle([':power-log-bridge:connectedDebugAndroidTest']);
}

run(process.execPath, ['scripts/check-android-release-surface.mjs']);

if (emulator) run(process.execPath, ['scripts/test-crash-paths.mjs']);
