import { spawn, spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { androidEnvironment, root } from './android-environment.mjs';
import { checkApk } from './check-android-release-surface.mjs';
import { checkBundle } from './check-apple-release-surface.mjs';

const app = 'app.powerlog.mobile';
const sliceLine = /\[power-log\] slice (ok|failed.*)/;
const limitMs = 180_000;

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} ${args.join(' ')} exited with ${result.status}`);
}

function output(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0)
    throw new Error(`${command} ${args.join(' ')} exited with ${result.status}: ${result.stderr}`);
  return result.stdout;
}

export function sliceOutcome(text) {
  return sliceLine.exec(text)?.[1] ?? null;
}

export function rejectsDevTools(build, scan) {
  try {
    scan();
  } catch (error) {
    if (!/carries the dev tools/.test(error.message))
      throw new Error(`The release scan of ${build} failed before it reached the dev tools: ${error.message}`);
    console.log(`The release scan rejects ${build}: it carries the dev tools.`);
    return;
  }
  throw new Error(`The release scan passed ${build}, which carries the dev tools`);
}

function awaitSlice(name, command, args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] });
    let seen = '';
    const finish = (error, outcome) => {
      clearTimeout(timer);
      child.kill('SIGTERM');
      if (error) reject(error);
      else resolve(outcome);
    };
    const read = chunk => {
      seen += chunk;
      const outcome = sliceOutcome(seen);
      if (outcome) finish(null, outcome);
      else seen = seen.slice(-4096);
    };
    child.stdout.setEncoding('utf8').on('data', read);
    child.stderr.setEncoding('utf8').on('data', read);
    child.on('error', error => finish(error));
    child.on('exit', () => finish(new Error(`${name}: the app's output ended before the slice line`)));
    const timer = setTimeout(() => finish(new Error(`${name}: no slice line within ${limitMs / 1000} s`)), limitMs);
  });
}

function prebuild(platform, env) {
  const marker = join(root, platform, '.power-log-flavor');
  let flavor = null;
  try {
    flavor = readFileSync(marker, 'utf8').trim();
  } catch {}
  run(
    process.execPath,
    ['scripts/prebuild.mjs', '--platform', platform, '--no-install', ...(flavor === 'development' ? [] : ['--clean'])],
    { env },
  );
  writeFileSync(marker, 'development\n');
}

function bootedIphone(env) {
  const devices = Object.values(
    JSON.parse(output('xcrun', ['simctl', 'list', 'devices', 'booted', '-j'], { env })).devices,
  )
    .flat()
    .filter(device => device.state === 'Booted' && device.name.includes('iPhone'));
  if (devices.length !== 1)
    throw new Error('Boot exactly one iPhone simulator, or name one with POWER_LOG_SLICE_SIMULATOR.');
  return devices[0].udid;
}

async function iphone() {
  const env = { ...process.env, POWER_LOG_DEV_CLIENT: '1', POWER_LOG_APPLE_SLICE: 'simulator', LANG: 'en_US.UTF-8' };
  const simulator = env.POWER_LOG_SLICE_SIMULATOR ?? bootedIphone(env);
  prebuild('ios', env);
  run('sh', ['scripts/install-pods.sh'], { env });
  run(
    'xcodebuild',
    [
      '-workspace',
      'ios/PowerLog.xcworkspace',
      '-scheme',
      'PowerLog',
      '-configuration',
      'Debug',
      '-sdk',
      'iphonesimulator',
      '-destination',
      'generic/platform=iOS Simulator',
      '-derivedDataPath',
      'ios/build',
      'build',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGNING_REQUIRED=NO',
      'CODE_SIGN_IDENTITY=-',
      `ARCHS=${process.arch === 'arm64' ? 'arm64' : 'x86_64'}`,
    ],
    { env },
  );
  const bundle = join(root, 'ios/build/Build/Products/Debug-iphonesimulator/PowerLog.app');
  rejectsDevTools('the Debug .app', () => checkBundle(bundle));
  run('xcrun', ['simctl', 'install', simulator, bundle], { env });
  try {
    return await awaitSlice(
      'iPhone simulator',
      'xcrun',
      [
        'simctl',
        'launch',
        '--console-pty',
        '--terminate-running-process',
        simulator,
        app,
        '-PowerLogDevBike',
        'YES',
        '-PowerLogDevSlice',
        'YES',
      ],
      env,
    );
  } finally {
    spawnSync('xcrun', ['simctl', 'terminate', simulator, app], { env });
  }
}

async function android() {
  const env = androidEnvironment({ POWER_LOG_DEV_CLIENT: '1' });
  const serial = env.ANDROID_SERIAL ?? 'emulator-5554';
  if (!/^emulator-\d+$/.test(serial)) throw new Error(`The slice runs only on an emulator, not ${serial}.`);
  const adb = (...args) => output(join(env.ANDROID_HOME, 'platform-tools/adb'), ['-s', serial, ...args], { env });
  const abi = adb('shell', 'getprop', 'ro.product.cpu.abi').trim();
  prebuild('android', env);
  run('./gradlew', [':app:assembleDebug', '--console=plain', `-PreactNativeArchitectures=${abi}`], {
    cwd: join(root, 'android'),
    env,
  });
  const apk = join(root, 'android/app/build/outputs/apk/debug/app-debug.apk');
  rejectsDevTools('app-debug.apk', () => checkApk(apk));
  adb('install', '-r', '-t', apk);
  for (const permission of ['BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION'])
    adb('shell', 'pm', 'grant', app, `android.permission.${permission}`);
  adb('shell', 'am', 'force-stop', app);
  adb(
    'shell',
    `run-as ${app} sh -c 'mkdir -p files && echo "{\\"bike\\": true, \\"slice\\": true}" > files/dev-switches.json'`,
  );
  adb('logcat', '-c');
  const slice = awaitSlice(
    'Android emulator',
    join(env.ANDROID_HOME, 'platform-tools/adb'),
    ['-s', serial, 'logcat', '-v', 'raw', '-s', 'System.out:I'],
    env,
  );
  adb('shell', 'am', 'start', '-n', `${app}/.MainActivity`);
  try {
    return await slice;
  } finally {
    adb('shell', `run-as ${app} rm -f files/dev-switches.json`);
    adb('shell', 'am', 'force-stop', app);
  }
}

if (process.argv[1]?.endsWith('thin-slice-phones.mjs')) {
  const platforms = process.argv.slice(2);
  const outcomes = [];
  for (const [name, slice] of [
    ['ios', iphone],
    ['android', android],
  ]) {
    if (platforms.length && !platforms.includes(`--${name}`)) continue;
    const outcome = await slice();
    console.log(`Thin slice on ${name}: ${outcome}`);
    outcomes.push(outcome);
  }
  if (outcomes.some(outcome => outcome !== 'ok')) process.exitCode = 1;
}
