import { createServer } from 'node:net';
import { existsSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, normalize, resolve } from 'node:path';
import { SwiftHost, swiftFixtures, swiftTasks, swiftUnits } from './swift-suites.mjs';

const definition = ['scripts/checks.mjs', 'scripts/check-tasks.mjs', 'scripts/reap-check-lock.py'];
const nodeDependencies = ['package.json', 'package-lock.json', 'patches/**', '.npmrc', '.nvmrc'];
const javascript = [
  ...nodeDependencies,
  'src/**',
  'modules/power-log-bridge/index.ts',
  'modules/power-log-bridge/expo-module.config.json',
  'shared/js/**',
  'assets/**',
  'plugins/**',
  'app.config.ts',
  'tsconfig.json',
];
const testSupport = ['tests/support/**', 'tests/helpers/**', 'tests/fixtures/**'];
const kotlin = [
  'shared/src/**',
  'shared/*.kts',
  'shared/gradle.properties',
  'shared/gradle/**',
  'shared/gradlew',
  'shared/scripts/protocol_fixtures.py',
];
const kotlinBuild = ['shared/*.kts', 'shared/gradle.properties', 'shared/gradle/**', 'shared/gradlew'];
const kotlinApple = ['shared/src/commonMain/**', 'shared/src/appleMain/**', ...kotlinBuild];
const everything = ['**', '!docs/**', '!**/*.md', '!artifacts/**', '!.github/**', '!LICENSE'];
const android = [
  ...everything,
  '!modules/power-log-bridge/ios/**',
  '!apple/**',
  '!lab/**',
  '!tests/lab-apple/**',
  '!tests/catalog-native/**',
  '!tests/native-live-activity/**',
  '!tests/e2e/**',
  '!tests/public-web/**',
];
const androidSources = [
  ...nodeDependencies,
  'modules/power-log-bridge/android/**',
  'modules/power-log-bridge/expo-module.config.json',
  'shared/src/commonMain/**',
  ...kotlinBuild,
  'app.config.ts',
  'plugins/**',
  'tests/fixtures/**',
  'tests/helpers/browser-test-bundle.ts',
  'scripts/test-android.mjs',
  'scripts/build-android.mjs',
  'scripts/android-environment.mjs',
  'scripts/prepare-android-crash-tests.mjs',
  'scripts/crash-proof.cjs',
  'scripts/check-android-release-surface.mjs',
  'scripts/android-crash-apk.mjs',
  'scripts/expand-contract-fixtures.py',
];
const crashHarnesses = [
  'tests/browser/crash-paths.test.ts',
  'tests/helpers/web-crash-path.ts',
  'tests/fixtures/crash-path-harness.ts',
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

const catalogTest = 'tests/core/shared-core-telemetry-catalog.test.ts';
const browserLong = 'tests/browser/browser-rides.test.ts';
const browserConfig = ['--config', 'vitest.browser.config.ts', '--hookTimeout', '30000'];
const framework = 'shared/build/bin/macosArm64/releaseFramework/PowerLogCore.framework/PowerLogCore';

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

async function coreEvidence(context) {
  const logs = ['core-tests', 'core-simulators', 'core-devices']
    .map(name => join(context.root, 'test-results/gates/logs', `${name}.log`))
    .filter(existsSync)
    .map(path => readFileSync(path, 'utf8'))
    .join('\n');
  const combined = join(context.scratch, 'gradle.log');
  writeFileSync(combined, logs);
  await context.exec(process.execPath, [
    'shared/scripts/print-core-evidence.mjs',
    combined,
    resolve(context.root, 'shared/build/test-results'),
    existsSync(resolve(context.root, 'shared/build/qualification/watch-links.done')) ? '0' : '1',
  ]);
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
    task('js:generated', 'Checked-in Kotlin/JS output matches its recorded inputs', {
      groups: ['check', 'core'],
      inputs: [
        'shared/src/commonMain/**',
        'shared/src/jsMain/**',
        'shared/*.kts',
        'shared/gradle.properties',
        'shared/gradle/**',
        'shared/js/**',
        'shared/scripts/sync-js.mjs',
        'shared/scripts/js-output.mjs',
      ],
      tools: ['node'],
      estimate: 1,
      run: ['node', 'shared/scripts/sync-js.mjs', '--check'],
    }),
    task('js:types', 'TypeScript types', {
      groups: ['check'],
      inputs: [...javascript, '*.ts', 'tests/**/*.{ts,tsx}', 'scripts/*.mjs', 'scripts/*.cjs', 'shared/scripts/*.mjs'],
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
        'modules/power-log-bridge/*.ts',
        'plugins/*.js',
        'scripts/*.mjs',
        'shared/scripts/*.mjs',
        'shared/js/package.json',
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
        'tests/core/**',
        'tests/services/**',
        'tests/export/**',
        'tests/fit-common/**',
        'vitest.config.ts',
        'eslint.config.js',
        'scripts/*.{mjs,cjs,py}',
        'shared/scripts/*.{mjs,py}',
        'modules/power-log-bridge/android/src/test/resources/**',
        ...(readFileSync(resolve(root, 'tests/core/catalog-authority.test.ts'), 'utf8').match(
          /modules\/power-log-bridge\/[\w/.-]+\.(?:swift|kt)/g,
        ) ?? []),
      ],
      env: ['FIT_PYTHON'],
      needs: ['deps'],
      tools: ['node', 'python', 'fit'],
      weight: 4,
      isolate: true,
      estimate: 35,
      run: ['npx', 'vitest', 'run', '--maxWorkers', '4', '--exclude', catalogTest],
    }),
    task('js:node', 'Native configuration, security and notice suites', {
      groups: ['check'],
      inputs: [
        ...everything,
        '!src/**',
        '!tests/core/**',
        '!tests/services/**',
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
      run: ['npx', 'vitest', 'run', ...browserConfig, '--maxWorkers', '4', '--exclude', browserLong],
    }),
    task('js:browser-long', 'Browser long-ride retention', {
      groups: ['browser'],
      tier: 'release',
      inputs: [...javascript, ...testSupport, 'tests/browser/**', 'vitest.browser.config.ts'],
      env: ['POWER_LOG_BROWSER_STRESS_SIZE'],
      needs: ['deps'],
      tools: ['node'],
      weight: 4,
      isolate: true,
      estimate: 60,
      run: context =>
        context.exec('npx', ['vitest', 'run', ...browserConfig, browserLong], {
          env: { POWER_LOG_BROWSER_STRESS: '1' },
        }),
    }),
    task('js:e2e', 'Playwright end-to-end suites', {
      groups: ['browser'],
      tier: 'release',
      inputs: [...javascript, ...testSupport, 'tests/e2e/**', 'playwright.config.ts'],
      needs: ['deps'],
      resources: ['crash-results'],
      tools: ['node'],
      weight: 8,
      isolate: true,
      estimate: 90,
      run: async context =>
        context.exec('npx', ['playwright', 'test'], { env: { POWER_LOG_WEB_PORT: String(await freePort()) } }),
    }),
    task('core:host', 'Kotlin macOS framework', {
      groups: ['core'],
      inputs: kotlinApple,
      outputs: [resolve(root, framework)],
      resources: ['gradle:shared'],
      tools: ['java'],
      weight: 6,
      estimate: 60,
      run: context => gradle(context, [':linkReleaseFrameworkMacosArm64']),
    }),
    task('core:tests', 'Kotlin JVM and Node common tests', {
      groups: ['core'],
      inputs: [
        ...kotlin,
        'tests/fixtures/**',
        'shared/scripts/check-test-results.mjs',
        'shared/scripts/discover-common-tests.mjs',
        'shared/scripts/parse-junit.py',
      ],
      needs: ['core:host'],
      resources: ['gradle:shared'],
      tools: ['java', 'node'],
      weight: 4,
      estimate: 120,
      run: async context => {
        await gradle(context, ['jvmTest', 'jsNodeTest', '--continue', ...(context.full ? [] : ['-PpowerLogEveryday'])]);
        await context.exec('node', [
          'shared/scripts/check-test-results.mjs',
          ...(context.full ? [] : ['--everyday']),
          'jvmTest',
          'jsNodeTest',
        ]);
      },
    }),
    task('core:node', 'Node suites against the generated core JS', {
      groups: ['core'],
      inputs: [...nodeDependencies, 'shared/js/**', 'tests/core/shared-core-*.test.mjs', ...testSupport],
      needs: ['deps'],
      tools: ['node'],
      weight: 2,
      run: context => context.exec('sh', ['-c', 'node --test tests/core/shared-core-*.test.mjs']),
    }),
    task('core:catalog', 'Telemetry catalog through the production monitor', {
      groups: ['check', 'core'],
      inputs: [...javascript, ...testSupport, catalogTest],
      needs: ['deps'],
      tools: ['node'],
      run: ['npx', 'vitest', 'run', catalogTest],
    }),
    ...['protocol', 'health', 'telemetry', 'presentation'].map(name =>
      task(`core:swift-${name}`, `Swift ${name} adapter against the core framework`, {
        groups: ['core'],
        inputs: [
          ...kotlinApple,
          `shared/scripts/check-${name}-swift.sh`,
          `tests/native-config/${name}-swift/**`,
          ...swiftFixtures,
        ],
        needs: ['core:host'],
        resources: ['gradle:shared'],
        tools: ['swift', 'java'],
        run: context => context.exec('sh', [`shared/scripts/check-${name}-swift.sh`], { env: gradleEnvironment(root) }),
      }),
    ),
    task('core:format', 'Kotlin formatting', {
      groups: ['core', 'format'],
      inputs: [
        'modules/power-log-bridge/android/src/**/*.{kt,kts}',
        'shared/**/*.{kt,kts}',
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
      inputs: [...kotlin, 'tests/fixtures/**', 'shared/scripts/check-test-results.mjs'],
      needs: ['core:host'],
      resources: ['gradle:shared'],
      tools: ['java', 'swift'],
      weight: 8,
      estimate: 300,
      run: async context => {
        await gradle(context, ['macosArm64Test', 'iosSimulatorArm64Test', '--continue']);
        await context.exec('node', [
          'shared/scripts/check-test-results.mjs',
          'macosArm64Test',
          'iosSimulatorArm64Test',
        ]);
      },
    }),
    task('core:devices', 'Device test compilation and the release XCFramework', {
      groups: ['core'],
      tier: 'release',
      inputs: kotlin,
      outputs: [resolve(root, 'shared/build/XCFrameworks/release/PowerLogCore.xcframework/Info.plist')],
      needs: ['core:host'],
      resources: ['gradle:shared'],
      tools: ['java', 'swift'],
      weight: 8,
      estimate: 90,
      run: context => gradle(context, ['compileTestKotlinIosArm64', 'assemblePowerLogCoreReleaseXCFramework']),
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
    task('core:pod', 'CocoaPods discovers the static core framework', {
      groups: ['core'],
      tier: 'release',
      inputs: [
        'tests/native-config/core-pod.test.rb',
        'modules/power-log-bridge/ios/PowerLogBridge.podspec',
        'Gemfile*',
      ],
      needs: ['core:watch-links'],
      resources: ['core:staged-framework'],
      run: ['bundle', 'exec', 'ruby', 'tests/native-config/core-pod.test.rb'],
    }),
    task('core:evidence', 'Core qualification evidence table', {
      groups: ['core'],
      tier: 'release',
      inputs: [...kotlin, 'shared/scripts/print-core-evidence.mjs', 'shared/scripts/check-test-results.mjs'],
      after: ['core:tests', 'core:simulators', 'core:devices', 'core:watch-links'],
      run: coreEvidence,
    }),
    task('swift:format', 'Swift formatting', {
      groups: ['swift', 'format'],
      inputs: [
        '{modules/power-log-bridge/ios,apple/WatchApp,apple/LiveActivity,lab/apple,tests/lab-apple,artifacts/app-store/source,tests/catalog-native,scripts}/**/*.swift',
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
    task('swift:lab-surface', 'Lab entry points stay out of release sources', {
      groups: ['swift'],
      inputs: [
        'scripts/check-apple-release-surface.mjs',
        'modules/power-log-bridge/ios/*.swift',
        'apple/WatchApp/*.swift',
        'modules/power-log-bridge/index.ts',
      ],
      tools: ['node'],
      run: [
        'node',
        '--input-type=module',
        '-e',
        'import {checkSources} from "./scripts/check-apple-release-surface.mjs"; checkSources()',
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
    task('android:connected', 'Android connected suites and crash paths on emulator-5554', {
      groups: ['android'],
      tier: 'release',
      requires: 'emulator',
      inputs: [
        ...androidSources,
        ...importClosure(root, crashHarnesses),
        'scripts/test-crash-paths.mjs',
        'scripts/test-android-crash-paths.mjs',
        'shared/scripts/check-test-results.mjs',
        'tests/export/**',
        'tests/fit-common/**',
        'vitest.browser.config.ts',
      ],
      env: ['FIT_PYTHON'],
      needs: ['android:unit'],
      resources: ['android-project', 'gradle:shared', 'emulator', 'crash-results'],
      tools: ['java', 'android', 'node', 'python'],
      weight: 6,
      estimate: 400,
      run: ['node', 'scripts/test-android.mjs', '--emulator'],
    }),
    task('android:apk', 'Android preview APK', {
      groups: ['android'],
      tier: 'release',
      inputs: android,
      needs: ['deps'],
      resources: ['android-project', 'gradle:shared'],
      tools: ['java', 'android', 'node'],
      weight: 8,
      estimate: 200,
      run: ['npm', 'run', 'build:android', '--', '--preview', '--arch=arm64-v8a'],
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
    task('generation:js', 'A cold Kotlin/JS compile matches the checked-in output', {
      groups: ['core'],
      tier: 'release',
      inputs: [
        ...kotlin,
        'shared/js/**',
        'shared/scripts/sync-js.mjs',
        'shared/scripts/js-output.mjs',
        'scripts/check-generation.mjs',
      ],
      needs: ['deps', 'core:host'],
      resources: ['gradle:shared'],
      tools: ['java', 'node'],
      weight: 4,
      estimate: 60,
      run: context => context.exec('node', ['scripts/check-generation.mjs', '--js'], { env: gradleEnvironment(root) }),
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
