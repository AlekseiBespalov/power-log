import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { availableParallelism } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const bridge = 'modules/power-log-bridge/ios';
const support = `${bridge}/Tests/Support`;
const entry = name => `${bridge}/Tests/${name}/main.swift`;
const exportSource = `${bridge}/ExportSource.swift`;
const bridgeFiles = names => names.map(name => `${bridge}/${name}.swift`);
export const librarySources = [
  ...bridgeFiles([
    'WorkoutActivityRuntime',
    'PowerLogRideAttributes',
    'WorkoutActivityPresentation',
    'MonitorInspection',
    'TelemetryAdapter',
    'TelemetryCaptureClock',
    'PowerLogCapture',
    'WorkoutDistance',
    'WorkoutDistanceStore',
    'WorkoutTypes',
    'RideSnapshot',
    'PowerLogStore',
    'WorkoutArchive',
    'WorkoutControl',
    'WorkoutSync',
    'WorkoutHealthMembers',
    'WorkoutHealthSeconds',
    'WorkoutCompanion',
    'MonitorData',
    'WorkoutAnalysis',
  ]),
  ...['GPSFixtures', 'InterruptionFixture', 'ActivityFixtures', 'Telemetry', 'InspectionFixtures', 'HealthSamples'].map(
    name => `${support}/${name}.swift`,
  ),
];
export function suiteFixtures() {
  return [];
}
const separatelyCompiled = new Set([`${bridge}/Tests/EnginePacing/CoreBluetooth.swift`]);
const siblings = main =>
  readdirSync(resolve(import.meta.dirname, '..', dirname(main)))
    .filter(name => name.endsWith('.swift') && name !== 'main.swift')
    .map(name => `${dirname(main)}/${name}`)
    .filter(path => !separatelyCompiled.has(path))
    .sort();
const suite = (id, main, sources = [], options = {}) => ({
  id,
  kind: 'suite',
  main,
  sources: [...new Set([...sources, ...siblings(main)])].filter(path => !librarySources.includes(path)),
  optimize: false,
  framework: 'host',
  ...options,
});
const command = (id, inputs, options) => ({ id, kind: 'command', inputs, ...options });
const iosTypecheckSources = readdirSync(resolve(import.meta.dirname, '..', bridge))
  .filter(
    name =>
      name.endsWith('.swift') &&
      !['PowerLogBridgeModule.swift', 'PowerLogBridgeAppDelegateSubscriber.swift'].includes(name),
  )
  .map(name => `${bridge}/${name}`);
const watchTypecheckSources = [
  ...bridgeFiles(['WorkoutCompanion', 'WorkoutCompanionTransport', 'WorkoutNativeHealth', 'WorkoutHealthMetrics']),
  ...readdirSync(resolve(import.meta.dirname, '..', 'apple/WatchApp'))
    .filter(name => name.endsWith('.swift'))
    .map(name => `apple/WatchApp/${name}`),
];
const typecheck = (sdk, framework, target, sources) => context => [
  '/usr/bin/xcrun',
  '--sdk',
  sdk,
  'swiftc',
  '-typecheck',
  '-swift-version',
  '5',
  ...(framework ? ['-F', `shared/build/bin/${framework}/releaseFramework`, '-framework', 'PowerLogCore'] : []),
  '-module-cache-path',
  join(context.cache, `modules-${sdk}`),
  '-target',
  target,
  '-sdk',
  spawnSync('/usr/bin/xcrun', ['--sdk', sdk, '--show-sdk-path'], { encoding: 'utf8', env: context.env }).stdout.trim(),
  ...sources,
];
export const swiftUnits = [
  suite('telemetry', `${bridge}/Tests/main.swift`, bridgeFiles(['TelemetryDiagnostics'])),
  suite('read-errors', entry('ReadErrors'), [`${support}/SummaryReview.swift`]),
  suite('companion', entry('Companion')),
  suite('watch-engine', entry('WatchEngine'), [
    'apple/WatchApp/WatchWorkoutEngine.swift',
    'apple/WatchApp/WatchWorkoutPresentation.swift',
  ]),
  suite(
    'phone-engine',
    entry('PhoneEngine'),
    bridgeFiles(['WorkoutEngine', 'WorkoutPreferences', 'WorkoutExampleRides']),
    {
      defines: ['POWER_LOG_ENGINE_TEST'],
    },
  ),
  suite('preferences', entry('Preferences'), bridgeFiles(['WorkoutPreferences'])),
  suite('health-metrics', entry('HealthMetrics'), bridgeFiles(['WorkoutHealthMetrics'])),
  suite('interrupted-finish', entry('InterruptedFinish'), bridgeFiles(['ExportSource'])),
  suite('storage', entry('Storage')),
  suite('capture', entry('Capture')),
  suite('engine-pacing', entry('EnginePacing'), bridgeFiles(['TelemetryDiagnostics', 'ControllerEngine']), {
    coreBluetooth: `${bridge}/Tests/EnginePacing/CoreBluetooth.swift`,
  }),
  suite(
    'controller-capture',
    `${bridge}/Tests/Capture/Controller/main.swift`,
    bridgeFiles(['TelemetryDiagnostics', 'ControllerEngine']),
    {
      coreBluetooth: `${bridge}/Tests/EnginePacing/CoreBluetooth.swift`,
    },
  ),
  suite('live-timing', entry('LiveTiming')),
  suite('activity-runtime', entry('ActivityRuntime')),
  suite('monitor', entry('Monitor')),
  suite('monitor-distance', entry('MonitorDistance'), [`${support}/DistanceFixtures.swift`]),
  suite('energy-per-distance', entry('EnergyPerDistance')),
  suite('example-rides', entry('ExampleRides'), bridgeFiles(['WorkoutExampleRides'])),
  command(
    'screenshot-examples',
    [
      ...librarySources,
      ...bridgeFiles(['WorkoutExampleRides', 'ExportSource']),
      'artifacts/app-store/source/generate.sh',
      'artifacts/app-store/source/generate.mjs',
      'artifacts/app-store/source/main.swift',
      'artifacts/app-store/source/check-iphone-examples.mjs',
      'shared/scripts/build-apple.sh',
      'src/core/**',
      'shared/js/**',
      'package.json',
      'package-lock.json',
    ],
    {
      framework: 'host',
      tools: ['swift', 'node', 'java'],
      resources: ['gradle:shared'],
      main: 'artifacts/app-store/source/main.swift',
      command: context => [
        'sh',
        'artifacts/app-store/source/generate.sh',
        join(context.scratch, 'PowerLog'),
        '--smoke',
      ],
    },
  ),
  suite('export-source', entry('ExportSource'), bridgeFiles(['ExportSource', 'WorkoutExampleRides']), {
    inputs: ['tests/fixtures/export/native.ts'],
    tools: ['swift', 'node'],
  }),
  suite('export-sink', entry('ExportSink'), bridgeFiles(['ExportSource', 'ExportSink'])),
  suite('distance', entry('WorkoutDistance'), [`${support}/DistanceFixtures.swift`]),
  suite('distance-build', entry('DistanceBuild'), [`${support}/DistanceFixtures.swift`]),
  suite('projection-cache', entry('ProjectionCache')),
  suite('plot-summary', entry('PlotSummary')),
  suite('range-statistics', entry('RangeStatistics')),
  suite('level-of-detail', entry('LevelOfDetail')),
  suite(
    'raster',
    entry('Raster'),
    bridgeFiles(['Scene', 'Renderer', 'Worker', 'Frames', 'Markers', 'View'].map(part => `MonitorRaster${part}`)),
  ),
  suite('catalog', 'tests/catalog-native/main.swift'),
  suite('workout-summary', entry('WorkoutSummary'), [`${support}/SummaryFixtures.swift`], {
    optimize: true,
    estimate: 60,
    args: context => [join(context.scratch, 'workout-summary')],
  }),
  command(
    'lab-bootstrap',
    [
      'scripts/test-apple-sdk-bootstrap.sh',
      `${bridge}/WorkoutLabCrash.swift`,
      `${bridge}/PowerLogBridgeAppDelegateSubscriber.swift`,
      'tests/lab-apple/sdk-bootstrap/UIKit.swift',
      'tests/lab-apple/sdk-bootstrap/ExpoModulesCore.swift',
      'tests/lab-apple/sdk-bootstrap/main.swift',
    ],
    {
      main: 'tests/lab-apple/sdk-bootstrap/main.swift',
      framework: 'host',
      command: () => ['sh', 'scripts/test-apple-sdk-bootstrap.sh'],
    },
  ),
  command('expo-module-parse', bridgeFiles(['PowerLogBridgeModule', 'PowerLogBridgeAppDelegateSubscriber']), {
    command: () => [
      '/usr/bin/xcrun',
      'swiftc',
      '-frontend',
      '-parse',
      ...bridgeFiles(['PowerLogBridgeModule', 'PowerLogBridgeAppDelegateSubscriber']),
    ],
  }),
  command('privacy-manifest', [`${bridge}/PrivacyInfo.xcprivacy`], {
    command: () => ['/usr/bin/plutil', '-lint', `${bridge}/PrivacyInfo.xcprivacy`],
  }),
  command('ios-typecheck', iosTypecheckSources, {
    tier: 'release',
    framework: 'devices',
    command: typecheck('iphoneos', 'iosArm64', 'arm64-apple-ios26.0', iosTypecheckSources),
  }),
  command('watchos-typecheck', watchTypecheckSources, {
    tier: 'release',
    framework: 'host',
    command: typecheck('watchos', null, 'arm64-apple-watchos10.0', watchTypecheckSources),
  }),
  suite('live-ride-soak', entry('LiveRideSoak'), [exportSource], { optimize: true, long: true, estimate: 150 }),
  suite('whole-ride', entry('WholeRide'), [], { optimize: true, long: true, estimate: 300 }),
];

const unitById = id => {
  const found = swiftUnits.find(unit => unit.id === id);
  if (!found) throw new Error(`Unknown Swift unit ${id}`);
  return found;
};

export const swiftRunners = {
  '': () => swiftUnits.filter(unit => !unit.long),
  '--lab': () => ['companion', 'storage', 'capture'].map(unitById),
  '--live-soak': () => [unitById('live-ride-soak')],
  '--only': ids => ids.split(',').map(unitById),
};

const deviceFrameworks = new Set(['ios-typecheck', 'watchos-typecheck']);

function digest(parts) {
  const hash = createHash('sha256');
  for (const part of parts) hash.update(part).update('\0');
  return hash.digest('hex').slice(0, 24);
}

export class SwiftHost {
  constructor({ root, env = process.env, cache = resolve(root, 'test-results/gates/cache/swift') }) {
    this.root = root;
    this.env = env;
    this.cache = cache;
    this.framework = resolve(root, 'shared/build/bin/macosArm64/releaseFramework');
    this.memo = new Map();
  }

  contents(paths) {
    return paths.flatMap(path => [path, readFileSync(resolve(this.root, path))]);
  }

  toolchain() {
    this.version ??= spawnSync('xcrun', ['swiftc', '--version'], { encoding: 'utf8', env: this.env }).stdout;
    return this.version;
  }

  frameworkDigest(names = ['PowerLogCore', 'Headers/PowerLogCore.h', 'Modules/module.modulemap']) {
    const framework = join(this.framework, 'PowerLogCore.framework');
    return digest(names.map(name => readFileSync(join(framework, name))));
  }

  artifact(name, key, build, context) {
    const target = join(this.cache, `${name}-${key}`);
    if (!this.memo.has(target))
      this.memo.set(
        target,
        (async () => {
          if (existsSync(join(target, '.complete'))) return target;
          const staging = `${target}.${process.pid}.tmp`;
          rmSync(staging, { recursive: true, force: true });
          mkdirSync(staging, { recursive: true });
          try {
            await build(staging);
            writeFileSync(join(staging, '.complete'), '');
            rmSync(target, { recursive: true, force: true });
            renameSync(staging, target);
          } finally {
            rmSync(staging, { recursive: true, force: true });
          }
          return target;
        })().catch(error => {
          this.memo.delete(target);
          throw error;
        }),
      );
    return this.memo.get(target).then(directory => {
      context.write(`Using ${relative(this.root, directory)}`);
      return directory;
    });
  }

  async contractFixtures(context) {
    const sources = readdirSync(resolve(this.root, 'tests/fixtures/contract'), { recursive: true, withFileTypes: true })
      .filter(item => item.isFile())
      .map(item => relative(this.root, join(item.parentPath, item.name)))
      .sort();
    const script = ['scripts/expand-contract-fixtures.py'];
    const directory = await this.artifact(
      'contract',
      digest([...script, ...this.contents([...script, 'shared/scripts/protocol_fixtures.py', ...sources])]),
      staging => context.exec('python3', [...script, join(staging, 'contract')]),
      context,
    );
    return join(directory, 'contract');
  }

  compile(context, name, inputs, argumentsFor, extra = []) {
    const command = output => ['swiftc', '-module-cache-path', join(this.cache, 'modules'), ...argumentsFor(output)];
    return this.artifact(
      name,
      digest([this.toolchain(), JSON.stringify(command('<output>')), ...extra, ...this.contents(inputs)]),
      staging => context.exec('/usr/bin/xcrun', command(staging)),
      context,
    );
  }

  library(optimize, context) {
    const sources = librarySources;
    return this.compile(
      context,
      optimize ? 'library-optimized' : 'library',
      sources,
      output => [
        optimize ? '-O' : '-Onone',
        '-j',
        String(availableParallelism()),
        '-assert-config',
        'Debug',
        '-swift-version',
        '5',
        '-parse-as-library',
        '-enable-testing',
        '-module-name',
        'PowerLogHostBase',
        '-emit-module',
        '-emit-module-path',
        join(output, 'PowerLogHostBase.swiftmodule'),
        '-emit-library',
        '-static',
        '-o',
        join(output, 'libPowerLogHostBase.a'),
        ...sources,
        '-F',
        this.framework,
      ],
      [this.frameworkDigest(['Headers/PowerLogCore.h', 'Modules/module.modulemap'])],
    );
  }

  coreBluetooth(path, context) {
    return this.compile(context, 'core-bluetooth', [path], output => [
      '-swift-version',
      '5',
      '-emit-module',
      '-emit-library',
      '-module-name',
      'CoreBluetooth',
      '-Xlinker',
      '-install_name',
      '-Xlinker',
      '@rpath/libCoreBluetooth.dylib',
      path,
      '-o',
      join(output, 'libCoreBluetooth.dylib'),
    ]);
  }

  async binary(unit, context) {
    if (unit.kind === 'standalone') {
      const sources = [...unit.sources, unit.main];
      const directory = await this.compile(context, `binary-${unit.id}`, sources, output => [
        '-swift-version',
        '5',
        ...sources,
        '-o',
        join(output, unit.id),
      ]);
      return join(directory, unit.id);
    }
    const sources = [...suiteFixtures(unit.main), ...unit.sources, unit.main];
    const library = await this.library(unit.optimize, context);
    const bluetooth = unit.coreBluetooth ? await this.coreBluetooth(unit.coreBluetooth, context) : undefined;
    const directory = await this.compile(
      context,
      `binary-${unit.id}`,
      sources,
      output => [
        ...(unit.defines ?? []).flatMap(value => ['-D', value]),
        '-whole-module-optimization',
        ...(unit.optimize ? ['-O', '-num-threads', '8'] : ['-Onone']),
        '-assert-config',
        'Debug',
        '-swift-version',
        '5',
        '-I',
        library,
        '-L',
        library,
        '-lPowerLogHostBase',
        '-Xfrontend',
        '-testable-import-module',
        '-Xfrontend',
        'PowerLogHostBase',
        ...(bluetooth
          ? ['-I', bluetooth, '-L', bluetooth, '-lCoreBluetooth', '-Xlinker', '-rpath', '-Xlinker', bluetooth]
          : []),
        ...sources,
        '-F',
        this.framework,
        '-framework',
        'PowerLogCore',
        '-lsqlite3',
        '-lcompression',
        '-o',
        join(output, unit.id),
      ],
      [this.frameworkDigest()],
    );
    return join(directory, unit.id);
  }

  async run(unit, context, extra = []) {
    const local = { ...context, extra, cache: this.cache };
    if (unit.kind === 'command') {
      const [program, ...args] = unit.command(local);
      return context.exec(program, args);
    }
    const env = { POWER_LOG_CONTRACT_FIXTURES: await this.contractFixtures(context) };
    const binary = await this.binary(unit, context);
    if (!unit.projection) return context.exec(binary, unit.args?.(local) ?? [], { env });
    const output = join(context.scratch, 'bridge-projection');
    mkdirSync(output, { recursive: true });
    const descriptor = openSync(join(output, 'log'), 'w');
    let status = 0;
    try {
      await context.exec(binary, [output], { env, stdout: descriptor, stderr: descriptor });
    } catch {
      status = 1;
    } finally {
      closeSync(descriptor);
    }
    writeFileSync(join(output, 'status'), `${status}\n`);
    context.write(readFileSync(join(output, 'log'), 'utf8'));
    await context.exec('npx', ['vitest', 'run', 'tests/services/workout-swift-projection.test.ts'], {
      env: { POWER_LOG_BRIDGE_PROJECTION: output },
    });
  }
}

export const swiftFixtures = [
  'tests/fixtures/contract/**',
  'tests/fixtures/protocol.json',
  'tests/fixtures/distance-controller.json',
  'tests/fixtures/health/**',
];

const swiftDefinition = [
  'scripts/swift-suites.mjs',
  'scripts/expand-contract-fixtures.py',
  'shared/scripts/protocol_fixtures.py',
  ...swiftFixtures,
];

export function unitInputs(unit) {
  if (unit.kind === 'command') return [...unit.inputs, 'scripts/swift-suites.mjs'];
  const sources = [
    ...(unit.kind === 'suite' ? [...librarySources, ...suiteFixtures(unit.main)] : []),
    ...unit.sources,
    ...(unit.coreBluetooth ? [unit.coreBluetooth] : []),
    unit.main,
  ];
  return [...new Set([...sources, ...(unit.inputs ?? []), ...swiftDefinition])];
}

export function swiftTasks({ host, units, frameworks = {}, extra = [] }) {
  return units.map(unit => ({
    id: `swift:${unit.id}`,
    title: `Swift ${unit.kind === 'command' ? 'check' : 'suite'} ${unit.id}`,
    tier: unit.tier ?? (unit.long ? 'release' : 'everyday'),
    groups: ['swift'],
    inputs: unitInputs(unit),
    tools: unit.tools ?? (unit.projection ? ['swift', 'node'] : ['swift']),
    resources: unit.resources ?? [],
    needs: unit.framework && frameworks[unit.framework] ? [frameworks[unit.framework]] : [],
    isolate: true,
    alone: Boolean(unit.alone),
    entrypoints: unit.main ? [unit.main] : [],
    estimate: unit.estimate ?? (unit.long ? 90 : 20),
    run: context => host.run(unit, context, extra),
  }));
}

async function main() {
  const root = resolve(import.meta.dirname, '..');
  const argv = process.argv.slice(2);
  const flag = argv[0]?.startsWith('--') ? argv[0] : '';
  const selection = flag === '--only' ? argv[1] : undefined;
  const extra = argv.slice(flag ? (selection === undefined ? 1 : 2) : 0);
  if (!swiftRunners[flag] || (flag === '--only' && !selection))
    throw new Error('Usage: bash scripts/test-swift.sh [--lab | --live-soak | --only id[,id]] [suite arguments]');
  const units = swiftRunners[flag](selection);
  const macos = units.every(unit => !deviceFrameworks.has(unit.id));
  const apple = spawnSync('sh', ['shared/scripts/build-apple.sh', ...(macos ? ['--macos'] : [])], {
    cwd: root,
    stdio: 'inherit',
  });
  if (apple.status !== 0) process.exit(apple.status ?? 1);
  const { runChecks } = await import('./checks.mjs');
  const tasks = swiftTasks({ host: new SwiftHost({ root }), units, extra }).map(task => ({
    ...task,
    tier: 'everyday',
  }));
  const result = await runChecks({
    root,
    tasks,
    force: true,
    keepRecords: false,
    echo: true,
    stateDirectory: resolve(root, 'test-results/swift'),
  });
  if (result.failed.length) process.exit(1);
  console.log(
    flag
      ? `Swift ${selection ?? flag.slice(2)} checks passed.`
      : 'Native software checks and both SDK typechecks passed. Full app builds and physical device checks run separately.',
  );
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) await main();
