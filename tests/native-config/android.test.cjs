const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { configureSigning } = require('../../plugins/with-power-log-android');

test('Android prebuild signing stays idempotent and release keys are not silently replaced by debug keys', () => {
  const first = configureSigning('android { compileSdkVersion 36 }\n');
  assert.equal(configureSigning(configureSigning(first)), first);
  const followed = configureSigning(first + '\n// Another plugin\nandroid { lint { abortOnError true } }\n');
  assert.match(followed, /\/\/ Another plugin\nandroid \{ lint \{ abortOnError true \} \}/);
  assert.equal(configureSigning(followed), followed);
  assert.match(first, /POWER_LOG_ANDROID_PREVIEW.*== '1' \? signingConfigs.debug : signingConfigs.powerLogRelease/);
  assert.match(first, /storePassword System.getenv\('POWER_LOG_ANDROID_STORE_PASSWORD'\)/);
});

test('Android capture uses non-exported foreground services and private file sharing', () => {
  const manifest = fs.readFileSync('modules/cyc-bridge/android/src/main/AndroidManifest.xml', 'utf8');
  assert.match(manifest, /android:foregroundServiceType="connectedDevice\|location"/);
  assert.match(manifest, /<service[^>]*android:exported="false"/);
  assert.match(manifest, /<provider[^>]*android:exported="false"/);
  assert.match(manifest, /<activity android:name=".HealthPermissionsActivity" android:exported="false"/);
  assert.doesNotMatch(manifest, /WRITE_EXTERNAL_STORAGE|ACCESS_BACKGROUND_LOCATION|health.READ_/);
  const paths = fs.readFileSync('modules/cyc-bridge/android/src/main/res/xml/power_log_files.xml', 'utf8');
  assert.match(paths, /path="exports\/"/);
  assert.doesNotMatch(paths, /root-path|external-path/);
});
