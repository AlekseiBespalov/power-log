const { createHash } = require('node:crypto');
const { readdirSync, readFileSync } = require('node:fs');
const { join, resolve } = require('node:path');

const root = resolve(__dirname, '..');
function sourceDigest(checkout = root) {
  const hash = createHash('sha256');
  function visit(path) {
    for (const item of readdirSync(join(checkout, path), { withFileTypes: true }).sort((a, b) =>
      a.name.localeCompare(b.name),
    )) {
      const name = `${path}/${item.name}`;
      if (item.isDirectory() && !['build', 'node_modules', '__pycache__'].includes(item.name)) visit(name);
      else if (item.isFile())
        hash
          .update(name)
          .update('\0')
          .update(readFileSync(join(checkout, name)))
          .update('\0');
    }
  }
  // This APK exercises native Android storage and a fake Health provider; browser/UI code is not linked into it.
  for (const path of ['shared/src/commonMain', 'modules/power-log-bridge/android/src', 'tests/fixtures/contract'])
    visit(path);
  for (const path of [
    'modules/power-log-bridge/android/build.gradle',
    'shared/build.gradle.kts',
    'shared/settings.gradle.kts',
    'shared/gradle.properties',
    'scripts/crash-proof.cjs',
    'scripts/prepare-android-crash-tests.mjs',
    'scripts/test-android-crash-paths.mjs',
    'scripts/android-crash-apk.mjs',
    'scripts/android-environment.mjs',
    'scripts/build-android.mjs',
    'scripts/expand-contract-fixtures.py',
    'shared/scripts/protocol_fixtures.py',
    'package.json',
    'package-lock.json',
  ])
    hash
      .update(path)
      .update('\0')
      .update(readFileSync(join(checkout, path)))
      .update('\0');
  return hash.digest('hex');
}
exports.sourceDigest = sourceDigest;
