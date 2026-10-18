import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { androidEnvironment, root } from './android-environment.mjs';
import { sourceDigest } from './crash-proof.cjs';
import { validateCrashApk } from './android-crash-apk.mjs';

const env = androidEnvironment({ ANDROID_SERIAL: 'emulator-5554' });
const adb = join(env.ANDROID_HOME, 'platform-tools/adb');
const pkg = 'app.powerlog.bridge.test';
const directory = join(root, 'test-results/interrupted/android');
mkdirSync(directory, { recursive: true });
function run(args) {
  const result = spawnSync(adb, ['-s', 'emulator-5554', ...args], {
    env,
    encoding: 'utf8',
    timeout: 120000,
    maxBuffer: 8 << 20,
  });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, result.stderr);
  return result.stdout + result.stderr;
}
const apk = join(
  root,
  'modules/power-log-bridge/android/build/outputs/apk/androidTest/debug/power-log-bridge-debug-androidTest.apk',
);
const build = validateCrashApk(apk, sourceDigest());
assert.match(run(['shell', 'getprop', 'ro.kernel.qemu']).trim(), /^1$/);
run(['install', '-r', '-t', apk]);
const result = run([
  'shell',
  'am',
  'instrument',
  '-w',
  '-e',
  'class',
  'app.powerlog.bridge.CrashPathsTest',
  `${pkg}/androidx.test.runner.AndroidJUnitRunner`,
]);
writeFileSync(join(directory, 'instrumentation.log'), result);
assert.match(result, /OK \(2 tests\)/, result);
writeFileSync(
  join(directory, 'result.json'),
  JSON.stringify({
    ...build,
    passed: 2,
    evidence: 'emulator SQLite reopen and fake-provider uncertain submission; no physical Health qualification',
    checkedAt: new Date().toISOString(),
  }) + '\n',
);
console.log(
  'Android interrupted recording checks: 2 passed on emulator-5554 (SQLite reopen, uncertain Health no replay).',
);
