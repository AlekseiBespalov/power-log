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
  assert.match(first, /POWER_LOG_ANDROID_PREVIEW'\) == '1' \? signingConfigs.debug : System/);
  assert.match(first, /storePassword System.getenv\('POWER_LOG_ANDROID_STORE_PASSWORD'\)/);
});

test('Android App Bundles are signed with the upload key and carry native symbol tables', () => {
  const gradle = configureSigning('android { compileSdkVersion 36 }\n');
  assert.match(
    gradle,
    /System.getenv\('POWER_LOG_ANDROID_BUNDLE'\) == '1' \? signingConfigs.powerLogUpload : signingConfigs.powerLogRelease/,
  );
  const upload = gradle.match(/powerLogUpload \{[\s\S]*?\n {8}\}/)[0];
  for (const setting of ['storeFile file', 'storePassword', 'keyAlias', 'keyPassword'])
    assert.match(upload, new RegExp(`${setting}[ (]System.getenv\\('POWER_LOG_ANDROID_UPLOAD_`));
  assert.match(gradle, /buildTypes.release.ndk.debugSymbolLevel = 'SYMBOL_TABLE'/);
});

test('Android capture uses non-exported foreground services and private file sharing', () => {
  const manifest = fs.readFileSync('modules/power-log-bridge/android/src/main/AndroidManifest.xml', 'utf8');
  assert.match(manifest, /android:foregroundServiceType="connectedDevice\|location"/);
  assert.match(manifest, /<service[^>]*android:exported="false"/);
  assert.match(manifest, /<provider[^>]*android:exported="false"/);
  assert.match(manifest, /<activity android:name=".HealthPermissionsActivity" android:exported="false"/);
  assert.doesNotMatch(manifest, /WRITE_EXTERNAL_STORAGE|ACCESS_BACKGROUND_LOCATION/);
  assert.deepEqual(
    [...manifest.matchAll(/android\.permission\.health\.(READ_[A-Z_]+)/g)].map(match => match[1]).sort(),
    [
      'READ_ACTIVE_CALORIES_BURNED',
      'READ_DISTANCE',
      'READ_EXERCISE',
      'READ_HEALTH_DATA_IN_BACKGROUND',
      'READ_HEART_RATE',
      'READ_HEART_RATE_VARIABILITY',
      'READ_OXYGEN_SATURATION',
      'READ_POWER',
      'READ_RESPIRATORY_RATE',
      'READ_SPEED',
    ],
  );
  assert.doesNotMatch(
    fs.readFileSync('modules/power-log-bridge/android/src/androidTest/AndroidManifest.xml', 'utf8'),
    /READ_HEALTH_DATA_HISTORY/,
  );
  const paths = fs.readFileSync('modules/power-log-bridge/android/src/main/res/xml/power_log_files.xml', 'utf8');
  assert.match(paths, /path="exports\/"/);
  assert.doesNotMatch(paths, /root-path|external-path/);
});

test('Health Connect privacy links reach the privacy activity from the rationale action and permission usage', () => {
  const manifest = fs.readFileSync('modules/power-log-bridge/android/src/main/AndroidManifest.xml', 'utf8');
  const activity = manifest.match(/<activity android:name=".HealthPrivacyActivity"[^>]*>([\s\S]*?)<\/activity>/);
  assert.ok(activity);
  assert.match(activity[0], /android:exported="true"/);
  assert.match(activity[1], /<action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" \/>/);
  const alias = manifest.match(
    /<activity-alias[^>]*android:targetActivity=".HealthPrivacyActivity"[^>]*>([\s\S]*?)<\/activity-alias>/,
  );
  assert.ok(alias);
  assert.match(alias[0], /android:exported="true"/);
  assert.match(alias[0], /android:permission="android.permission.START_VIEW_PERMISSION_USAGE"/);
  assert.match(alias[1], /<action android:name="android.intent.action.VIEW_PERMISSION_USAGE" \/>/);
  assert.match(alias[1], /<category android:name="android.intent.category.HEALTH_PERMISSIONS" \/>/);
});
