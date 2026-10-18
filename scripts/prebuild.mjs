import { spawnSync } from 'node:child_process';
import { mkdirSync, rmSync } from 'node:fs';

// Expo prebuild renames template files across every platform folder, so an iOS and an Android
// prebuild running at once read each other's files mid-write.
const lock = process.env.POWER_LOG_PREBUILD_LOCK;
const pause = new Int32Array(new SharedArrayBuffer(4));
while (lock) {
  try {
    mkdirSync(lock);
    break;
  } catch (error) {
    if (error.code !== 'EEXIST') throw error;
    Atomics.wait(pause, 0, 0, 250);
  }
}
try {
  const result = spawnSync(process.execPath, ['node_modules/expo/bin/cli', 'prebuild', ...process.argv.slice(2)], {
    stdio: 'inherit',
  });
  process.exitCode = result.status ?? 1;
} finally {
  if (lock) rmSync(lock, { recursive: true, force: true });
}
