import { spawnSync } from 'node:child_process';
console.log(`Node ${process.version} (use Node 24 LTS)`);
for (const [label, command, args] of [
  ['Selected Apple developer tools', 'xcode-select', ['-p']],
  ['Full Xcode', 'xcodebuild', ['-version']],
  ['iOS simulator tooling', 'xcrun', ['simctl', 'help']],
  ['CocoaPods', 'pod', ['--version']],
]) {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  console.log(`\n${label}: ${result.status === 0 ? 'available' : 'missing / not selected'}`);
  console.log(
    (result.stdout || result.stderr || result.error?.message || '').trim().split('\n').slice(0, 3).join('\n'),
  );
}
console.log(
  '\nBluetooth testing on iPhone needs a physical device, Developer Mode, a cable for initial installation, and Xcode signing with your Apple ID. Android builds and tests need JDK 17 and Android Studio’s SDK/platform tools; set JAVA_HOME and ANDROID_HOME if they are not detected.',
);
