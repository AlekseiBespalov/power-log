import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';

export function appleSourceDigest() {
  const hash = createHash('sha256');
  function visit(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const file = `${directory}/${entry.name}`;
      if (entry.isDirectory() && !['build', 'Frameworks', 'Tests', '__pycache__'].includes(entry.name)) visit(file);
      else if (entry.isFile()) hash.update(file).update('\0').update(readFileSync(file)).update('\0');
    }
  }
  for (const directory of ['lab/apple', 'modules/power-log-bridge/ios', 'apple/WatchApp', 'shared/src/commonMain'])
    visit(directory);
  for (const file of [
    'plugins/with-power-log-lab.js',
    'plugins/with-power-log-watch.js',
    'scripts/test-apple-crash-paths.mjs',
    'scripts/apple-crash-runner.mjs',
    'scripts/test-lab-apple.sh',
    'scripts/build-ios-simulator.sh',
    'scripts/check-ios-bundle.mjs',
    'package.json',
    'package-lock.json',
    'scripts/prepare-apple-crash-tests.mjs',
  ])
    hash.update(file).update(readFileSync(file));
  return hash.digest('hex');
}
export function prepareAppleCrashAssets() {
  const digest = appleSourceDigest();
  const directory = 'test-results/lab-apple/assets';
  mkdirSync(directory, { recursive: true });
  assert.equal(appleSourceDigest(), digest, 'Sources changed while preparing Lab assets');
  writeFileSync(`${directory}/build.json`, JSON.stringify({ sourceDigest: digest }) + '\n');
  copyFileSync(`${directory}/build.json`, `${directory}/watch-build.json`);
}
if (process.argv[1]?.endsWith('prepare-apple-crash-tests.mjs')) prepareAppleCrashAssets();
