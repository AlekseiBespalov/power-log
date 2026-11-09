import { spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { existsSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs';
import { dirname, join, normalize, resolve } from 'node:path';
import { androidEnvironment } from './android-environment.mjs';
import { pendingFile, pendingTests, playwrightPendingProblems, vitestPendingProblems } from './pending-tests.mjs';
import { moduleNames } from '../shared/scripts/discover-common-tests.mjs';
import { SwiftHost, swiftTasks, swiftUnits } from './swift-suites.mjs';

const definition = ['scripts/checks.mjs', 'scripts/check-tasks.mjs', 'scripts/reap-check-lock.py'];
const nodeDependencies = ['package.json', 'package-lock.json', 'patches/**', '.npmrc', '.nvmrc'];
const javascript = [
  ...nodeDependencies,
  'src/**',
  'modules/power-log/index.ts',
  'modules/power-log/src/**',
  'modules/power-log/expo-module.config.json',
  'shared/generated/**',
  'assets/**',
  'plugins/**',
  'app.config.ts',
  'tsconfig.json',
];
const testSupport = ['tests/support/**', 'tests/helpers/**', 'tests/fixtures/**'];
const kotlinBuild = [
  'shared/*.kts',
  'shared/*/build.gradle.kts',
  'shared/build-logic/**',
  'shared/gradle.properties',
  'shared/gradle/**',
  'shared/gradlew',
];
const kotlin = ['shared/*/src/**', 'shared/generated/kotlin/**', ...kotlinBuild, 'shared/scripts/protocol_fixtures.py'];
const kotlinApple = [
  ...['commonMain', 'appleMain', 'sqliteMain'].map(set => `shared/*/src/${set}/**`),
  'shared/generated/kotlin/**',
  'shared/*/src/debugMain/**',
  'tests/fixtures/contract/telemetry/cyc/**',
  'tests/fixtures/protocol.json',
  ...kotlinBuild,
];
const everything = ['**', '!docs/**', '!**/*.md', '!artifacts/**', '!.github/**', '!LICENSE'];
const android = [
  ...everything,
  '!modules/power-log/ios/**',
  '!targets/**',
  '!tests/native-live-activity/**',
  '!tests/e2e/**',
  '!tests/public-web/**',
];
const androidSources = [
  ...nodeDependencies,
  'modules/power-log/android/**',
  'modules/power-log/expo-module.config.json',
  ...['commonMain', 'javaMain', 'jvmMain', 'androidMain', 'sqliteMain'].map(set => `shared/*/src/${set}/**`),
  'shared/runtime/src/debugMain/**',
  'shared/runtime/src/androidUnitTest/**',
  ...['commonTest', 'sqliteTest', 'androidUnitTest', 'androidSharedTest'].map(set => `shared/data/src/${set}/**`),
  'shared/application/src/fakes/**',
  'shared/application/src/contracts/**',
  'tests/pending.json',
  'shared/generated/kotlin/**',
  ...kotlinBuild,
  'app.config.ts',
  'plugins/**',
  'tests/fixtures/**',
  'tests/helpers/browser-test-bundle.ts',
  'scripts/test-android.mjs',
  'scripts/build-android.mjs',
  'scripts/android-environment.mjs',
  'scripts/check-android-release-surface.mjs',
  'scripts/expand-contract-fixtures.py',
];
const importPattern = /\b(?:from|import|require)\s*\(?\s*['"]([^'"]+)['"]/g;
const importExtensions = ['', '.mjs', '.js', '.cjs', '.json', '/index'].flatMap(suffix =>
  suffix === '/index' || suffix === ''
    ? ['', '.web', '.native', '.ios', '.android']
        .flatMap(platform =>
          ['.ts', '.tsx', ...(suffix ? ['.mjs', '.js'] : [])].map(extension => `${suffix}${platform}${extension}`),
        )
        .concat(suffix)
    : [suffix],
);

export function importClosure(root, entries) {
  const seen = new Set();
  const visit = path => {
    if (seen.has(path)) return;
    seen.add(path);
    if (path.endsWith('/**')) return;
    for (const [, specifier] of readFileSync(resolve(root, path), 'utf8').matchAll(importPattern)) {
      const base = specifier.startsWith('@/')
        ? join('src', specifier.slice(2))
        : specifier.startsWith('.')
          ? normalize(join(dirname(path), specifier))
          : undefined;
      if (!base || base.startsWith('..')) continue;
      const files = importExtensions
        .map(extension => base + extension)
        .filter(candidate => existsSync(resolve(root, candidate)) && statSync(resolve(root, candidate)).isFile());
      files.forEach(visit);
      if (!files.length && existsSync(resolve(root, base))) visit(`${base}/**`);
    }
  };
  entries.forEach(visit);
  return [...seen].sort();
}

const pending = pendingFile;

const reportsVariable = 'POWER_LOG_TEST_REPORTS';

function vitestReportFile(context, id) {
  const directory = context.env[reportsVariable] ?? (context.full ? context.scratch : undefined);
  return directory && join(directory, `${id.replace(/[^\w.-]+/g, '-')}.json`);
}

// The release tier accepts a pending test that fails as not implemented, so a release run reads the reports and
// fails on anything else instead of on the runner's exit status.
async function vitest(context, id, args, options = {}) {
  const report = vitestReportFile(context, id);
  const command = [
    'vitest',
    'run',
    ...args,
    ...(report ? ['--reporter=default', '--reporter=json', `--outputFile.json=${report}`] : []),
  ];
  if (!context.full) return context.exec('npx', command, options);
  await context.exec('npx', command, options).catch(() => undefined);
  const { problems, accepted } = vitestPendingProblems(
    context.root,
    JSON.parse(readFileSync(report, 'utf8')),
    pendingTests(context.root, 'vitest'),
  );
  if (accepted.length) context.write(`Pending, failing as not implemented:\n${accepted.join('\n')}`);
  if (problems.length) throw new Error(problems.join('\n'));
}

async function kotlinTests(context, tasks, check) {
  const run = gradle(context, tasks);
  if (!context.full) await run;
  else await run.catch(() => undefined);
  await context.exec('node', ['shared/scripts/check-test-results.mjs', ...check]);
}

function clearKotlinResults(root, targets) {
  for (const module of moduleNames)
    for (const target of targets)
      rmSync(resolve(root, 'shared', module, 'build/test-results', target), { recursive: true, force: true });
}
const browserLong = ['tests/browser/long-ride-export.test.ts'];
const rideGoldenPages = 'app.powerlog.application.exports.RideGoldenPagesTest';
const importedCsv = 'tests/goldens/imported-csv.test.ts';
const browserConfig = ['--config', 'vitest.browser.config.ts', '--hookTimeout', '30000'];
const framework = 'shared/runtime/build/bin/macosArm64/debugFramework/PowerLogCore.framework/PowerLogCore';

function gradleEnvironment(root) {
  return {
    npm_config_cache: resolve(root, 'shared/build/npm-cache'),
    npm_config_registry: 'https://registry.npmjs.org',
    npm_config_userconfig: '/dev/null',
  };
}

function gradle(context, tasks) {
  const offline = context.env.POWER_LOG_GRADLE_OFFLINE === '1' ? ['--offline'] : [];
  return context.exec(
    resolve(context.root, 'shared/gradlew'),
    ['-p', 'shared', '--console=plain', ...offline, ...tasks],
    {
      env: gradleEnvironment(context.root),
    },
  );
}

export function lockfileDifferences(lockfile, installed) {
  const wanted = lockfile.packages ?? {};
  const present = installed?.packages ?? {};
  const differences = [];
  for (const [path, entry] of Object.entries(wanted)) {
    if (!path) continue;
    const actual = present[path];
    if (!actual) {
      if (!entry.optional && !entry.devOptional && !entry.peer) differences.push(`${path} is not installed`);
    } else if (actual.version !== entry.version || actual.integrity !== entry.integrity)
      differences.push(`${path} ${actual.version} differs from ${entry.version}`);
  }
  for (const path of Object.keys(present))
    if (path && !wanted[path]) differences.push(`${path} is not in package-lock.json`);
  return differences;
}

async function dependencies(context) {
  const read = path => {
    try {
      return JSON.parse(readFileSync(resolve(context.root, path), 'utf8'));
    } catch {
      return undefined;
    }
  };
  const differences = lockfileDifferences(read('package-lock.json'), read('node_modules/.package-lock.json'));
  if (differences.length) {
    context.write(`node_modules differs from package-lock.json (${differences.slice(0, 5).join('; ')}); reinstalling.`);
    return context.exec('npm', ['ci']);
  }
  context.write('node_modules matches package-lock.json.');
  await context.exec('npx', ['patch-package', '--error-on-fail']);
}

function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolvePort(port));
    });
  });
}

export function checkTasks({ root, env = process.env }) {
  const cache = resolve(root, 'test-results/gates/cache');
  const host = new SwiftHost({ root, env, cache: join(cache, 'swift') });
  const task = (id, title, options) => ({
    id,
    title,
    tier: 'everyday',
    ...options,
    inputs: [...(options.inputs ?? []), ...definition],
  });
  const tasks = [
    task('deps', 'Installed packages match package-lock.json', {
      inputs: nodeDependencies,
      files: [resolve(root, 'node_modules/.package-lock.json')],
      tools: ['node'],
      estimate: 5,
      run: dependencies,
    }),
    task('audit', 'Dependency audit', {
      tier: 'release',
      inputs: [...nodeDependencies, 'scripts/audit.mjs'],
      needs: ['deps'],
      run: ['node', 'scripts/audit.mjs'],
    }),
    task('generated:fingerprint', 'Checked-in generated outputs match their recorded inputs', {
      groups: ['check', 'core'],
      needs: ['deps'],
      inputs: [
        'shared/*/src/commonMain/**',
        'shared/*/src/jsMain/**',
        'shared/application/src/jvmTest/kotlin/app/powerlog/application/api/generator/**',
        ...kotlinBuild,
        'shared/generated/**',
        'shared/scripts/sync-generated.mjs',
        'shared/scripts/js-output.mjs',
      ],
      tools: ['node'],
      estimate: 1,
      run: ['node', 'shared/scripts/sync-generated.mjs', '--check'],
    }),
    task('js:types', 'TypeScript types', {
      groups: ['check'],
      inputs: [
        ...javascript,
        'modules/power-log/src/**/*.{ts,tsx}',
        '*.ts',
        'tests/**/*.{ts,tsx}',
        'scripts/*.mjs',
        'shared/scripts/*.mjs',
      ],
      needs: ['deps'],
      tools: ['node'],
      weight: 2,
      run: ['npx', 'tsc', '--noEmit', '--incremental', '--tsBuildInfoFile', join(cache, 'tsc.tsbuildinfo')],
    }),
    task('js:lint', 'ESLint', {
      groups: ['check'],
      inputs: [...nodeDependencies, '**/*.{js,jsx,ts,tsx,mjs,cjs}', 'eslint.config.js'],
      needs: ['deps'],
      tools: ['node'],
      weight: 2,
      run: context =>
        context.exec('npx', [
          'eslint',
          '.',
          ...(context.full
            ? []
            : ['--cache', '--cache-location', join(cache, 'eslint'), '--cache-strategy', 'content']),
        ]),
    }),
    task('js:format', 'Prettier formatting', {
      groups: ['check', 'format'],
      inputs: [
        ...nodeDependencies,
        'src/**/*.{ts,tsx}',
        'tests/**/*.{ts,tsx}',
        'modules/power-log/*.ts',
        'modules/power-log/src/**/*.{ts,tsx}',
        'plugins/*.js',
        'scripts/*.mjs',
        'shared/scripts/*.mjs',
        'shared/generated/js/*/package.json',
        '*.ts',
        '*.js',
        '.prettierrc',
        '.prettierignore',
        'scripts/format.sh',
      ],
      needs: ['deps'],
      tools: ['node'],
      run: context =>
        context.exec('sh', ['scripts/format.sh', '--ts', '--check'], {
          env: context.full ? {} : { POWER_LOG_PRETTIER_CACHE: join(cache, 'prettier') },
        }),
    }),
    task('js:unit', 'Vitest unit and regression suites', {
      groups: ['check'],
      inputs: [
        ...javascript,
        ...testSupport,
        'modules/power-log/src/**',
        'tests/exports/**',
        'tests/repo/**',
        'tests/module/**',
        'tests/features/**',
        'tests/components/**',
        pending,
        'vitest.config.ts',
        'eslint.config.js',
        'scripts/*.{mjs,py}',
        'shared/scripts/*.{mjs,py}',
      ],
      env: ['FIT_PYTHON'],
      needs: ['deps'],
      tools: ['node', 'python', 'fit'],
      weight: 4,
      isolate: true,
      estimate: 35,
      run: context =>
        vitest(context, 'js:unit', [
          '--maxWorkers',
          '4',
          ...(context.full ? [] : pendingTests(context.root, 'vitest').flatMap(test => ['--exclude', test])),
        ]),
    }),
    task('js:node', 'Native configuration, security and notice suites', {
      groups: ['check'],
      inputs: [
        ...everything,
        '!src/**',
        '!tests/core/**',
        '!tests/exports/**',
        '!tests/repo/**',
        '!tests/components/**',
        '!tests/support/**',
        '!tests/e2e/**',
        '!tests/public-web/**',
      ],
      needs: ['deps', 'core:host'],
      tools: ['node', 'java'],
      weight: 2,
      estimate: 10,
      run: async context => {
        await context.exec('npm', ['run', 'test:native-config']);
        await context.exec('npm', ['run', 'test:security']);
        await context.exec('npm', ['run', 'test:notices']);
      },
    }),
    task('js:web-build', 'Web export for a dedicated hostname', {
      groups: ['check', 'web'],
      inputs: [...javascript, 'scripts/build-pages.mjs', 'scripts/web-notices.mjs'],
      needs: ['deps'],
      resources: ['dist'],
      tools: ['node'],
      weight: 8,
      isolate: true,
      estimate: 20,
      run: ['node', 'scripts/build-pages.mjs', '/'],
    }),
    task('js:public-web', 'Public Pages export', {
      groups: ['web'],
      tier: 'release',
      inputs: [...javascript, 'scripts/build-pages.mjs', 'scripts/web-notices.mjs', 'tests/public-web/**'],
      needs: ['deps'],
      resources: ['dist'],
      tools: ['node'],
      weight: 12,
      isolate: true,
      estimate: 20,
      run: ['npm', 'run', 'test:public-web'],
    }),
    task('js:browser', 'Browser suites', {
      groups: ['browser'],
      inputs: [
        ...javascript,
        ...testSupport,
        'tests/browser/**',
        'vitest.browser.config.ts',
        ...importClosure(
          root,
          readdirSync(resolve(root, 'tests/browser'))
            .filter(file => file.endsWith('.test.ts'))
            .map(file => `tests/browser/${file}`),
        ),
      ],
      env: ['POWER_LOG_BROWSER_STRESS_SIZE'],
      needs: ['deps'],
      resources: ['crash-results'],
      tools: ['node'],
      weight: 6,
      isolate: true,
      estimate: 60,
      run: context =>
        vitest(context, 'js:browser', [
          ...browserConfig,
          '--maxWorkers',
          '4',
          ...browserLong.flatMap(file => ['--exclude', file]),
        ]),
    }),
    task('js:browser-long', 'Browser long-ride retention and the long ride exported from IndexedDB', {
      groups: ['browser'],
      tier: 'release',
      inputs: [...javascript, ...kotlin, ...testSupport, 'tests/browser/**', 'vitest.browser.config.ts'],
      env: ['POWER_LOG_BROWSER_STRESS_SIZE'],
      needs: ['deps'],
      resources: ['gradle:shared'],
      tools: ['java', 'node'],
      weight: 4,
      isolate: true,
      estimate: 60,
      run: async context => {
        await gradle(context, [':runtime:jsTestTestDevelopmentExecutableCompileSync']);
        await vitest(context, 'js:browser-long', [...browserConfig, ...browserLong], {
          env: { POWER_LOG_BROWSER_STRESS: '1' },
        });
      },
    }),
    task('js:e2e', 'Playwright end-to-end suites', {
      groups: ['browser'],
      tier: 'release',
      inputs: [
        ...javascript,
        ...kotlin,
        ...testSupport,
        'tests/e2e/**',
        'playwright.config.ts',
        'scripts/pending-tests.mjs',
        pending,
      ],
      needs: ['deps'],
      resources: ['crash-results', 'gradle:shared'],
      tools: ['java', 'node'],
      weight: 8,
      isolate: true,
      estimate: 90,
      run: async context => {
        await gradle(context, [':runtime:jsTestTestDevelopmentExecutableCompileSync']);
        const listed = pendingTests(context.root, 'playwright');
        const report = join(context.env[reportsVariable] ?? context.scratch, 'js-e2e.json');
        rmSync(report, { force: true });
        const failure = await context
          .exec('npx', ['playwright', 'test', '--reporter=list,json'], {
            env: { POWER_LOG_WEB_PORT: String(await freePort()), PLAYWRIGHT_JSON_OUTPUT_NAME: report },
          })
          .catch(error => error);
        if (failure && !existsSync(report)) throw failure;
        const { problems, accepted } = playwrightPendingProblems(JSON.parse(readFileSync(report, 'utf8')), listed);
        if (accepted.length)
          context.write(`Pending, failing until their feature reaches the new runtime:\n${accepted.join('\n')}`);
        if (problems.length) throw new Error(problems.join('\n'));
      },
    }),
    task('core:host', 'Kotlin macOS framework', {
      groups: ['core'],
      inputs: kotlinApple,
      outputs: [resolve(root, framework)],
      resources: ['gradle:shared'],
      tools: ['java'],
      weight: 6,
      estimate: 60,
      run: context => gradle(context, [':runtime:linkDebugFrameworkMacosArm64']),
    }),
    task('core:tests', 'Kotlin JVM and Node common tests', {
      groups: ['core'],
      inputs: [
        ...kotlin,
        'tests/fixtures/**',
        'shared/scripts/check-test-results.mjs',
        'shared/scripts/discover-common-tests.mjs',
        'shared/scripts/parse-junit.py',
        'scripts/pending-tests.mjs',
        pending,
      ],
      needs: ['core:host'],
      resources: ['gradle:shared'],
      tools: ['java', 'node'],
      weight: 4,
      estimate: 120,
      run: async context => {
        if (context.full) clearKotlinResults(context.root, ['jvmTest', 'jsNodeTest']);
        await kotlinTests(
          context,
          ['jvmTest', 'jsNodeTest', '--continue', ...(context.full ? [] : ['-PpowerLogEveryday'])],
          [...(context.full ? [] : ['--everyday']), 'jvmTest', 'jsNodeTest'],
        );
      },
    }),
    task('kotlin:boundaries', 'Konsist boundary rules over the shared Kotlin and the Android module', {
      groups: ['core'],
      inputs: ['shared/**/*.{kt,kts}', 'modules/power-log/android/src/**/*.kt', ...kotlinBuild],
      resources: ['gradle:shared'],
      tools: ['java'],
      weight: 2,
      estimate: 20,
      run: context =>
        gradle(context, [':runtime:jvmTest', '--tests', 'app.powerlog.runtime.boundaries.*', '-PpowerLogBoundaries']),
    }),
    task('core:node', 'Node suites against the generated core JS', {
      groups: ['core'],
      inputs: [...nodeDependencies, 'shared/generated/**', 'tests/core/shared-core-*.test.mjs', ...testSupport],
      needs: ['deps'],
      tools: ['node'],
      weight: 2,
      run: context => context.exec('sh', ['-c', 'node --test tests/core/shared-core-*.test.mjs']),
    }),
    task('core:format', 'Kotlin formatting', {
      groups: ['core', 'format'],
      inputs: [
        'modules/power-log/android/src/**/*.{kt,kts}',
        'shared/**/*.{kt,kts}',
        '!shared/generated/**',
        '.editorconfig',
        'scripts/format.sh',
      ],
      tools: ['java'],
      weight: 2,
      run: ['sh', 'scripts/format.sh', '--kotlin', '--check'],
    }),
    task('core:simulators', 'Kotlin macOS and iOS simulator tests', {
      groups: ['core'],
      tier: 'release',
      inputs: [
        ...kotlin,
        'tests/fixtures/**',
        'shared/scripts/check-test-results.mjs',
        'shared/scripts/discover-common-tests.mjs',
        'scripts/pending-tests.mjs',
        pending,
      ],
      needs: ['core:host'],
      resources: ['gradle:shared'],
      tools: ['java', 'swift'],
      weight: 8,
      estimate: 300,
      run: async context => {
        clearKotlinResults(context.root, ['macosArm64Test', 'iosSimulatorArm64Test']);
        await kotlinTests(
          context,
          ['macosArm64Test', 'iosSimulatorArm64Test', '--continue'],
          ['macosArm64Test', 'iosSimulatorArm64Test'],
        );
      },
    }),
    task('core:browser', 'Kotlin/JS browser suites of data and runtime in Chromium', {
      groups: ['core', 'browser'],
      tier: 'release',
      inputs: [
        ...kotlin,
        ...nodeDependencies,
        'tests/browser/kotlin/**',
        'tests/helpers/browser-test-bundle.ts',
        'tests/fixtures/export/storable/**',
        'tests/fixtures/contract/ports/**',
        'tests/fixtures/protocol.json',
        'vitest.kotlin.config.ts',
      ],
      needs: ['deps'],
      resources: ['gradle:shared'],
      tools: ['java', 'node'],
      weight: 4,
      isolate: true,
      estimate: 60,
      run: async context => {
        await gradle(context, [
          ':data:jsTestTestDevelopmentExecutableCompileSync',
          ':runtime:jsTestTestDevelopmentExecutableCompileSync',
        ]);
        await vitest(context, 'core:browser', ['--config', 'vitest.kotlin.config.ts']);
      },
    }),
    task('goldens:ride-pages', 'Ride goldens loaded into the archive and written as their export pages', {
      groups: ['core', 'goldens'],
      tier: 'release',
      inputs: ['shared/**', 'tests/fixtures/rides/**'],
      resources: ['gradle:shared'],
      tools: ['java'],
      weight: 4,
      estimate: 60,
      run: context => {
        rmSync(resolve(context.root, 'shared/application/build/test-results/jvmTest', `TEST-${rideGoldenPages}.xml`), {
          force: true,
        });
        return kotlinTests(
          context,
          [':application:jvmTest', '--tests', rideGoldenPages, '-PpowerLogRideGoldens'],
          ['--select', rideGoldenPages, 'jvmTest'],
        );
      },
    }),
    task('goldens:rides', 'Ride goldens replayed from their pages through the encoders', {
      groups: ['goldens'],
      tier: 'release',
      inputs: [
        ...nodeDependencies,
        'shared/generated/**',
        'modules/power-log/src/**',
        'tests/fixtures/rides/**',
        'tests/fixtures/export/zip/harness.ts',
        'tests/goldens/**',
        'tests/support/**',
        'vitest.goldens.config.ts',
      ],
      needs: ['deps', 'goldens:ride-pages'],
      tools: ['node', 'python'],
      weight: 2,
      isolate: true,
      estimate: 30,
      run: context =>
        vitest(context, 'goldens:rides', [
          '--config',
          'vitest.goldens.config.ts',
          'tests/goldens/ride-goldens.test.ts',
        ]),
    }),
    task('goldens:imports', "Imported CSVs replayed from EncoderPagesTest's pages through the imported-CSV encoder", {
      groups: ['goldens'],
      inputs: [
        ...nodeDependencies,
        'shared/generated/**',
        'modules/power-log/src/**',
        'tests/fixtures/export/zip/**',
        importedCsv,
        'tests/support/**',
        'vitest.goldens.config.ts',
        'scripts/pending-tests.mjs',
        'shared/scripts/discover-common-tests.mjs',
        pending,
      ],
      needs: ['deps', 'core:tests'],
      tools: ['node'],
      weight: 1,
      estimate: 10,
      run: async context => {
        if (!context.full && pendingTests(context.root, 'vitest').includes(importedCsv))
          return context.write(`${importedCsv} is pending in ${pending}, so the everyday tier leaves it out.`);
        await vitest(context, 'goldens:imports', ['--config', 'vitest.goldens.config.ts', importedCsv]);
      },
    }),
    task('core:devices', 'Device test compilation and the release XCFramework', {
      groups: ['core'],
      tier: 'release',
      inputs: kotlin,
      outputs: [resolve(root, 'shared/runtime/build/XCFrameworks/release/PowerLogCore.xcframework/Info.plist')],
      needs: ['core:host'],
      resources: ['gradle:shared'],
      tools: ['java', 'swift'],
      weight: 8,
      estimate: 90,
      run: context => gradle(context, ['compileTestKotlinIosArm64', ':runtime:assemblePowerLogCoreReleaseXCFramework']),
    }),
    task('core:watch-links', 'Watch Release link for both architectures', {
      groups: ['core'],
      tier: 'release',
      inputs: everything,
      needs: ['deps', 'core:devices'],
      resources: ['ios-project', 'core:staged-framework'],
      tools: ['swift', 'node'],
      weight: 8,
      estimate: 150,
      run: async context => {
        const marker = resolve(root, 'shared/build/qualification/watch-links.done');
        rmSync(marker, { force: true });
        return context.exec('sh', ['shared/scripts/check-watch-links.sh'], { env: { POWER_LOG_CORE_STAGED: '1' } });
      },
    }),
    task('swift:format', 'Swift formatting', {
      groups: ['swift', 'format'],
      inputs: [
        '{modules/power-log/ios,targets/watch,targets/live-activity,artifacts/app-store/source,scripts}/**/*.swift',
        '.swift-format',
        'scripts/format.sh',
      ],
      tools: ['swift'],
      weight: 2,
      run: ['sh', 'scripts/format.sh', '--swift', '--check'],
    }),
    task('swift:coverage', 'Every Swift test entry point has a gate check', {
      groups: ['swift'],
      inputs: ['**/main.swift', 'scripts/swift-suites.mjs', 'scripts/check-suite-coverage.mjs'],
      tools: ['node'],
      run: ['node', 'scripts/check-suite-coverage.mjs'],
    }),
    task('apple:release-surface', 'Expo, the runtime and dev tools stay out of Swift adapters', {
      groups: ['swift'],
      inputs: ['scripts/check-apple-release-surface.mjs', 'modules/power-log/ios/**/*.swift', 'targets/**/*.swift'],
      tools: ['node'],
      run: [
        'node',
        '--input-type=module',
        '-e',
        'import {checkSwiftBoundaries} from "./scripts/check-apple-release-surface.mjs"; checkSwiftBoundaries(".")',
      ],
    }),
    ...swiftTasks({
      host,
      units: swiftUnits.filter(unit => !unit.variant),
      frameworks: { host: 'core:host', devices: 'core:devices' },
    }).map(swift => ({
      ...swift,
      inputs: [...swift.inputs, ...definition],
      needs: [...swift.needs, ...(swift.tools.includes('node') ? ['deps'] : [])],
    })),
    task('android:unit', 'Android Robolectric suites and release compilation', {
      groups: ['android'],
      inputs: [...androidSources],
      needs: ['deps'],
      resources: ['android-project', 'gradle:shared'],
      tools: ['java', 'android', 'node', 'python'],
      weight: 6,
      estimate: 200,
      run: context => context.exec('node', ['scripts/test-android.mjs', ...(context.full ? [] : ['--everyday'])]),
    }),
    task(
      'android:kotlin-metadata',
      'Kotlin, kotlinx and androidx.sqlite on the Android release classpath at or below their pins',
      {
        groups: ['android'],
        tier: 'release',
        inputs: [...androidSources],
        needs: ['deps'],
        resources: ['android-project', 'gradle:shared'],
        tools: ['java', 'android', 'node'],
        weight: 4,
        estimate: 60,
        run: async context => {
          await context.exec('node', ['scripts/build-android.mjs', '--prepare', '--debug-signed']);
          await context.exec('./gradlew', [':power-log:powerLogKotlinMetadata', '--console=plain'], {
            cwd: resolve(context.root, 'android'),
            env: androidEnvironment({ ...context.env, POWER_LOG_ANDROID_DEBUG_SIGNING: '1' }),
          });
        },
      },
    ),
    task('android:apk', 'Android release APK, signed with the debug key, and its release-surface scan', {
      groups: ['android'],
      tier: 'release',
      inputs: android,
      needs: ['deps'],
      resources: ['android-project', 'gradle:shared'],
      tools: ['java', 'android', 'node'],
      weight: 8,
      estimate: 200,
      run: async context => {
        await context.exec('npm', ['run', 'build:android', '--', '--debug-signed', '--arch=arm64-v8a']);
        await context.exec('node', [
          'scripts/check-android-release-surface.mjs',
          '--apk',
          'android/app/build/outputs/apk/release/app-release.apk',
        ]);
      },
    }),
    task('ios:build', 'iPhone simulator Release build with the Watch app', {
      groups: ['ios'],
      tier: 'release',
      inputs: everything,
      needs: ['deps', 'core:devices'],
      after: ['core:watch-links'],
      resources: ['ios-project', 'core:staged-framework'],
      tools: ['swift', 'node'],
      weight: 10,
      estimate: 300,
      run: context => context.exec('npm', ['run', 'build:ios-simulator'], { env: { POWER_LOG_CORE_STAGED: '1' } }),
    }),
    task('thin-slice:phones', 'The thin slice on an iPhone simulator and an Android emulator with the demo bike', {
      groups: ['ios', 'android'],
      tier: 'demand',
      inputs: [
        'shared/**',
        'modules/power-log/**',
        'plugins/**',
        'scripts/thin-slice-phones.mjs',
        'scripts/check-apple-release-surface.mjs',
        'scripts/check-android-release-surface.mjs',
      ],
      env: ['POWER_LOG_SLICE_SIMULATOR', 'ANDROID_SERIAL'],
      needs: ['deps'],
      resources: ['ios-project', 'android-project', 'gradle:shared', 'emulator', 'core:staged-framework'],
      tools: ['swift', 'java', 'android', 'node'],
      weight: 10,
      estimate: 1200,
      run: ['node', 'scripts/thin-slice-phones.mjs'],
    }),
    task('android:sqlite-api28', "The repository suite on Android 9's system SQLite, on the PowerLog_API28 emulator", {
      groups: ['android'],
      tier: 'demand',
      inputs: ['shared/**'],
      needs: ['deps'],
      resources: ['gradle:shared'],
      tools: ['java', 'android'],
      weight: 6,
      estimate: 180,
      run: async context => {
        const serial = 'emulator-5556';
        const adb = join(context.env.ANDROID_HOME ?? '', 'platform-tools/adb');
        const sdk = spawnSync(adb, ['-s', serial, 'shell', 'getprop', 'ro.build.version.sdk'], { encoding: 'utf8' });
        if (sdk.stdout?.trim() !== '28')
          throw new Error(`${serial} is not an Android 9 (API 28) emulator: start the PowerLog_API28 AVD on port 5556`);
        await context.exec(
          resolve(context.root, 'shared/gradlew'),
          ['-p', 'shared', '--console=plain', ':data:connectedDebugAndroidTest'],
          { env: { ...gradleEnvironment(context.root), ANDROID_SERIAL: serial } },
        );
      },
    }),
    task('generation:all', 'A cold build matches every checked-in generated output', {
      groups: ['core'],
      tier: 'release',
      inputs: [
        ...kotlin,
        'shared/generated/**',
        'shared/scripts/sync-generated.mjs',
        'shared/scripts/js-output.mjs',
        'scripts/check-generation.mjs',
      ],
      needs: ['deps', 'core:host'],
      resources: ['gradle:shared'],
      tools: ['java', 'node'],
      weight: 4,
      estimate: 60,
      run: context =>
        context.exec('node', ['scripts/check-generation.mjs', '--generated'], { env: gradleEnvironment(root) }),
    }),
    task('generation:native', 'Clean and repeated native prebuild', {
      tier: 'release',
      inputs: everything,
      needs: ['deps', 'core:devices'],
      resources: ['ios-project', 'android-project', 'gradle:shared', 'core:staged-framework'],
      tools: ['java', 'node'],
      weight: 4,
      estimate: 60,
      run: context =>
        context.exec('node', ['scripts/check-generation.mjs', '--native'], { env: gradleEnvironment(root) }),
    }),
  ];
  return tasks;
}
