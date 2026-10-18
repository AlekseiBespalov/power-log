const assert = require('node:assert/strict');
const { readFileSync, readdirSync } = require('node:fs');
const { basename, join } = require('node:path');
const { test } = require('node:test');

const bridge = 'modules/power-log-bridge';
const read = path => readFileSync(path, 'utf8');

test('Expo and both native platforms expose the role-named bridge', () => {
  const config = JSON.parse(read(`${bridge}/expo-module.config.json`));
  assert.deepEqual(config.android.modules, ['app.powerlog.bridge.PowerLogBridgeModule']);
  assert.deepEqual(config.apple.modules, ['PowerLogBridgeModule']);
  assert.deepEqual(config.apple.appDelegateSubscribers, ['PowerLogBridgeAppDelegateSubscriber']);
  assert.match(read(`${bridge}/index.ts`), /requireOptionalNativeModule<PowerLogBridge>\('PowerLogBridge'\)/);
  assert.match(read(`${bridge}/ios/PowerLogBridge.podspec`), /s\.name\s*= 'PowerLogBridge'/);
  assert.match(read(`${bridge}/ios/PowerLogBridgeModule.swift`), /Name\("PowerLogBridge"\)/);
  assert.match(
    read(`${bridge}/android/src/main/java/app/powerlog/bridge/PowerLogBridgeModule.kt`),
    /Name\("PowerLogBridge"\)/,
  );
});

test('shared domain declarations reserve vendor names for the controller decoder', () => {
  const root = 'shared/src/commonMain/kotlin/app/powerlog/core';
  const decoder = join(root, 'telemetry', 'cyc');
  const vendor = basename(decoder);
  const vendorType = vendor[0].toUpperCase() + vendor.slice(1);
  const visit = path => {
    for (const entry of readdirSync(path, { withFileTypes: true })) {
      const file = join(path, entry.name);
      if (file === decoder) continue;
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
});
