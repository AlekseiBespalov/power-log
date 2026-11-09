import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { availableParallelism } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ios = 'modules/power-log/ios';
const entry = name => `${ios}/Tests/${name}/main.swift`;
const iosFiles = names => names.map(name => `${ios}/${name}.swift`);
export const librarySources = [...iosFiles(['ridecard/PowerLogRideAttributes'])];
const siblings = main =>
  readdirSync(resolve(import.meta.dirname, '..', dirname(main)))
    .filter(name => name.endsWith('.swift') && name !== 'main.swift')
    .map(name => `${dirname(main)}/${name}`)
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
const standalone = (id, main, sources) => ({
  id,
  kind: 'standalone',
  main,
  sources: [...sources, ...siblings(main)],
});
const command = (id, inputs, options) => ({ id, kind: 'command', inputs, ...options });
const swiftIn = folder =>
  readdirSync(resolve(import.meta.dirname, '..', folder))
    .filter(name => name.endsWith('.swift'))
    .map(name => `${folder}/${name}`)
    .sort();
const importsExpo = path =>
  /^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:internal|public|private|fileprivate|package)\s+)?import\s+ExpoModulesCore\b/m.test(
    readFileSync(resolve(import.meta.dirname, '..', path), 'utf8'),
  );
const appSwift = [
  '',
  'host',
  'device',
  'ble',
  'location',
  'permissions',
  'health',
  'keepalive',
  'ridecard',
  'watch',
  'files',
]
  .map(folder => (folder ? `${ios}/${folder}` : ios))
  .flatMap(swiftIn);
const iosTypecheckSources = appSwift.filter(path => !importsExpo(path));
const expoFiles = appSwift.filter(importsExpo);
const watchTypecheckSources = [
  `${ios}/health/HealthTypes.swift`,
  `${ios}/health/WorkoutSession.swift`,
  ...['', '/protocol', '/ride', '/link', '/text', '/views'].flatMap(folder => swiftIn(`targets/watch${folder}`)),
];
const typecheck = (sdk, framework, target, sources) => context => [
  '/usr/bin/xcrun',
  '--sdk',
  sdk,
  'swiftc',
  '-typecheck',
  '-swift-version',
  '5',
  ...(framework ? ['-F', `shared/runtime/build/bin/${framework}/releaseFramework`, '-framework', 'PowerLogCore'] : []),
  '-module-cache-path',
  join(context.cache, `modules-${sdk}`),
  '-target',
  target,
  '-sdk',
  spawnSync('/usr/bin/xcrun', ['--sdk', sdk, '--show-sdk-path'], { encoding: 'utf8', env: context.env }).stdout.trim(),
  ...sources,
];
export const swiftUnits = [
  suite('device-info', entry('DeviceInfo'), [`${ios}/device/AppleDeviceInfo.swift`]),
  suite('host-smoke', entry('HostSmoke'), [
    `${ios}/files/AppleFilePicker.swift`,
    `${ios}/device/AppleDeviceInfo.swift`,
    `${ios}/permissions/ApplePermissionGate.swift`,
    `${ios}/host/SessionBox.swift`,
  ]),
  suite('crash-hook', entry('CrashHook'), [
    `${ios}/files/AppleFilePicker.swift`,
    `${ios}/device/AppleDeviceInfo.swift`,
    `${ios}/permissions/ApplePermissionGate.swift`,
  ]),
  suite('ble-central', entry('BleCentral'), [`${ios}/ble/AppleBleCentral.swift`, `${ios}/ble/CoreBluetoothSeam.swift`]),
  standalone('watch-app', 'targets/watch/Tests/App/main.swift', [
    `${ios}/health/HealthTypes.swift`,
    `${ios}/health/WorkoutSession.swift`,
    ...['protocol', 'ride', 'link', 'text'].flatMap(folder => swiftIn(`targets/watch/${folder}`)),
    'targets/watch/views/WatchRideModel.swift',
  ]),
  standalone('watch-protocol', 'targets/watch/Tests/Protocol/main.swift', [
    `${ios}/health/HealthTypes.swift`,
    'targets/watch/protocol/WatchMessages.swift',
  ]),
  suite('watch-link', entry('WatchLink'), [
    `${ios}/watch/AppleWatchLink.swift`,
    `${ios}/watch/SystemWatchSession.swift`,
  ]),
  suite('location-source', entry('LocationSource'), [`${ios}/location/AppleLocationSource.swift`]),
  suite('workout-session', entry('WorkoutSession'), [
    `${ios}/health/HealthTypes.swift`,
    `${ios}/health/WorkoutSession.swift`,
  ]),
  suite(
    'health-provider',
    entry('HealthProvider'),
    [
      `${ios}/health/HealthTypes.swift`,
      `${ios}/health/WorkoutSession.swift`,
      `${ios}/health/AppleHealthProvider.swift`,
    ],
    { defines: ['POWER_LOG_DEV_TOOLS'] },
  ),
  suite('permission-gate', entry('PermissionGate'), [`${ios}/permissions/ApplePermissionGate.swift`]),
  suite('recording-keep-alive', entry('RecordingKeepAlive'), [`${ios}/keepalive/AppleRecordingKeepAlive.swift`]),
  suite('ride-card', entry('RideCard'), [`${ios}/ridecard/AppleRideCard.swift`]),
  suite('host-foreground', entry('HostForeground'), [`${ios}/host/PowerLogForeground.swift`]),
  suite(
    'host',
    entry('Host'),
    [
      'host/PowerLogHost.swift',
      'host/PowerLogForeground.swift',
      'host/PowerLogBackground.swift',
      'host/AppleUiContext.swift',
      'device/AppleDeviceInfo.swift',
      'ble/AppleBleCentral.swift',
      'ble/CoreBluetoothSeam.swift',
      'location/AppleLocationSource.swift',
      'permissions/ApplePermissionGate.swift',
      'keepalive/AppleRecordingKeepAlive.swift',
      'ridecard/AppleRideCard.swift',
      'watch/AppleWatchLink.swift',
      'watch/SystemWatchSession.swift',
      'files/AppleFilePicker.swift',
    ].map(path => `${ios}/${path}`),
    { stubs: { UIKit: `${ios}/Tests/Host/UIKit/UIKit.swift` } },
  ),
  suite('export-sink', entry('ExportSink'), [`${ios}/files/ExportSinks.swift`]),
  suite('file-picker', entry('FilePicker'), [`${ios}/files/AppleFilePicker.swift`]),
  suite(
    'raster',
    entry('Raster'),
    iosFiles(['Scene', 'Renderer', 'Worker', 'Frames', 'Markers', 'View'].map(part => `charts/ChartRaster${part}`)),
  ),
  command('expo-module-parse', expoFiles, {
    command: () => ['/usr/bin/xcrun', 'swiftc', '-frontend', '-parse', ...expoFiles],
  }),
  command('privacy-manifest', [`${ios}/PrivacyInfo.xcprivacy`], {
    command: () => ['/usr/bin/plutil', '-lint', `${ios}/PrivacyInfo.xcprivacy`],
  }),
  command('ios-typecheck', iosTypecheckSources, {
    tier: 'release',
    framework: 'devices',
    command: typecheck('iphoneos', 'iosArm64', 'arm64-apple-ios26.0', iosTypecheckSources),
  }),
  command(
    'health-simulator',
    [
      `${ios}/health/HealthTypes.swift`,
      `${ios}/health/WorkoutSession.swift`,
      `${ios}/health/AppleHealthProvider.swift`,
      entry('WorkoutSession'),
      entry('HealthProvider'),
      'scripts/test-health-simulator.sh',
    ],
    {
      tier: 'release',
      framework: 'devices',
      command: () => ['sh', 'scripts/test-health-simulator.sh'],
    },
  ),
  command('watchos-typecheck', watchTypecheckSources, {
    tier: 'release',
    framework: 'host',
    command: typecheck('watchos', null, 'arm64-apple-watchos10.0', watchTypecheckSources),
  }),
];

const unitById = id => {
  const found = swiftUnits.find(unit => unit.id === id);
  if (!found) throw new Error(`Unknown Swift unit ${id}`);
  return found;
};

export const swiftRunners = {
  '': () => swiftUnits.filter(unit => !unit.long),
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
    this.framework = resolve(root, 'shared/runtime/build/bin/macosArm64/debugFramework');
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

  stub(name, path, context) {
    return this.compile(context, `stub-${name}`, [path], output => [
      '-swift-version',
      '5',
      '-emit-module',
      '-emit-library',
      '-module-name',
      name,
      '-Xlinker',
      '-install_name',
      '-Xlinker',
      `@rpath/lib${name}.dylib`,
      path,
      '-o',
      join(output, `lib${name}.dylib`),
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
    const sources = [...unit.sources, unit.main];
    const library = await this.library(unit.optimize, context);
    const stubs = await Promise.all(
      Object.entries(unit.stubs ?? {}).map(async ([name, path]) => [name, await this.stub(name, path, context)]),
    );
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
        ...stubs.flatMap(([name, stub]) => [
          '-I',
          stub,
          '-L',
          stub,
          `-l${name}`,
          '-Xlinker',
          '-rpath',
          '-Xlinker',
          stub,
        ]),
        ...sources,
        '-F',
        this.framework,
        '-framework',
        'PowerLogCore',
        '-lsqlite3',
        '-lcompression',
        '-Xlinker',
        '-dead_strip',
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
    return context.exec(binary, unit.args?.(local) ?? [], { env });
  }
}

export const swiftFixtures = ['tests/fixtures/contract/**'];

const swiftDefinition = [
  'scripts/swift-suites.mjs',
  'scripts/expand-contract-fixtures.py',
  'shared/scripts/protocol_fixtures.py',
  ...swiftFixtures,
];

export function unitInputs(unit) {
  if (unit.kind === 'command') return [...unit.inputs, 'scripts/swift-suites.mjs'];
  const sources = [
    ...(unit.kind === 'suite' ? librarySources : []),
    ...unit.sources,
    ...Object.values(unit.stubs ?? {}),
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
    tools: unit.tools ?? ['swift'],
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
    throw new Error('Usage: bash scripts/test-swift.sh [--only id[,id]] [suite arguments]');
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
