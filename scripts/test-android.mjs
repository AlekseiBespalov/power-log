import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { androidEnvironment, root, runner } from './android-environment.mjs';

const fixtures = mkdtempSync(join(tmpdir(), 'powerlog-contract-'));
process.on('exit', () => rmSync(fixtures, { recursive: true, force: true }));
const env = androidEnvironment({
  POWER_LOG_ANDROID_DEBUG_SIGNING: '1',
  POWER_LOG_CONTRACT_FIXTURES: join(fixtures, 'contract'),
});
const run = runner(env);
run('python3', ['scripts/expand-contract-fixtures.py', env.POWER_LOG_CONTRACT_FIXTURES]);
run(process.execPath, ['scripts/build-android.mjs', '--prepare', '--debug-signed']);
const gradleArguments = [
  '--console=plain',
  '--build-cache',
  '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8',
  '-Pkotlin.compiler.execution.strategy=in-process',
  ...(process.argv.includes('--everyday') ? ['-PpowerLogEveryday'] : []),
  ...(env.POWER_LOG_GRADLE_OFFLINE === '1' ? ['--offline'] : []),
];
const runGradle = tasks =>
  run(
    process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
    [...tasks, ...gradleArguments],
    join(root, 'android'),
  );
runGradle([':power-log:testDebugUnitTest', ':power-log:compileReleaseKotlin']);
run(join(root, 'shared', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew'), [
  '-p',
  'shared',
  ':runtime:testDebugUnitTest',
  ':data:testDebugUnitTest',
  '--console=plain',
  ...(process.argv.includes('--everyday') ? ['-PpowerLogEveryday'] : []),
  ...(env.POWER_LOG_GRADLE_OFFLINE === '1' ? ['--offline'] : []),
]);
