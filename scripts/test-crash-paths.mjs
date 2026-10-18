import { execFileSync } from 'node:child_process';
execFileSync(process.execPath, ['scripts/test-android-crash-paths.mjs'], { stdio: 'inherit' });
execFileSync('npm', ['run', 'test:browser', '--', 'tests/browser/crash-paths.test.ts'], { stdio: 'inherit' });
console.log(
  'Interrupted recording checks passed: Android emulator and real browser reload. Physical Health qualification remains separate.',
);
