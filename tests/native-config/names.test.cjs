const assert = require('node:assert/strict');
const { readFileSync, readdirSync } = require('node:fs');
const { basename, join } = require('node:path');
const { test } = require('node:test');

const bridge = 'modules/power-log';
const read = path => readFileSync(path, 'utf8');

test('Expo and both native platforms expose the module as PowerLog, from the PowerLogPlatform pod', () => {
  const config = JSON.parse(read(`${bridge}/expo-module.config.json`));
  assert.deepEqual(config.android.modules, ['app.powerlog.platform.host.PowerLogModule']);
  assert.deepEqual(config.apple.modules, ['PowerLogModule']);
  assert.deepEqual(config.apple.appDelegateSubscribers, ['PowerLogAppDelegateSubscriber']);
  assert.match(read(`${bridge}/src/native-module.ts`), /requireOptionalNativeModule<[^>]+>\('PowerLog'\)/);
  assert.match(read(`${bridge}/ios/PowerLogPlatform.podspec`), /s\.name\s*= 'PowerLogPlatform'/);
  assert.match(read(`${bridge}/ios/host/PowerLogModule.swift`), /Name\("PowerLog"\)/);
  assert.match(
    read(`${bridge}/android/src/main/kotlin/app/powerlog/platform/host/PowerLogModule.kt`),
    /Name\("PowerLog"\)/,
  );
});

test('shared domain declarations reserve vendor names for the controller decoder', () => {
  const root = 'shared/domain/src/commonMain/kotlin/app/powerlog/domain';
  const decoders = [join(root, 'core', 'controller', 'cyc'), join(root, 'telemetry', 'cyc')];
  const vendor = basename(decoders[0]);
  const vendorType = vendor[0].toUpperCase() + vendor.slice(1);
  const visit = path => {
    for (const entry of readdirSync(path, { withFileTypes: true })) {
      const file = join(path, entry.name);
      if (decoders.includes(file)) continue;
      if (entry.isDirectory()) visit(file);
      else if (entry.name.endsWith('.kt')) {
        assert.doesNotMatch(
          read(file),
          new RegExp(`\\b(?:class|object|interface|typealias)\\s+${vendorType}\\w*`),
          file,
        );
        assert.doesNotMatch(read(file), new RegExp(`\\b${vendor}(?:Seconds|Insertion|Endpoints|Sequence)\\b`), file);
      }
    }
  };
  visit(root);
  visit('shared/kit/src/commonMain/kotlin/app/powerlog/kit');
});
