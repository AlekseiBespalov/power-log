import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { runChecks } from '../../scripts/checks.mjs';
import { checkTasks } from '../../scripts/check-tasks.mjs';
import { gateSwiftCoverage } from '../../scripts/check-suite-coverage.mjs';
import { checkBundle, releaseText } from '../../scripts/check-apple-release-surface.mjs';
import { checkTestResults } from '../../shared/scripts/check-test-results.mjs';

type Context = {
  root: string;
  full: boolean;
  env: Record<string, string>;
  exec: (command: string, args?: string[]) => Promise<void>;
};
type Task = {
  id: string;
  tier?: string;
  inputs?: string[];
  needs?: string[];
  entrypoints?: string[];
  run: string[] | ((context: Context) => Promise<void>);
};

const directories: string[] = [];
afterEach(() => {
  for (const directory of directories.splice(0)) rmSync(directory, { recursive: true, force: true });
});

function temporary() {
  const directory = mkdtempSync(join(tmpdir(), 'power-log-checks-'));
  directories.push(directory);
  return directory;
}

function write(path: string, content: string | Buffer) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

function check(id: string, options: Partial<Task> = {}): Task {
  return { id, tier: 'everyday', inputs: [], run: async () => {}, ...options };
}

async function run(state: string, files: Map<string, string>, tasks: Task[], options: Record<string, unknown> = {}) {
  const lines: string[] = [];
  const result = await runChecks({
    root: state,
    tasks,
    stateDirectory: state,
    env: { ...process.env, POWER_LOG_SKIP_CONNECTED: '1' },
    print: (line: string) => lines.push(line),
    listFiles: () => new Map(files),
    jobs: 4,
    ...options,
  });
  return { ...result, output: lines.join('\n') };
}

describe('change-selected checks', () => {
  it('reruns a check whose inputs changed and skips the checks whose inputs did not', async () => {
    const state = temporary();
    const files = new Map([
      ['src/view.tsx', 'one'],
      ['modules/power-log-bridge/ios/WorkoutDistanceStore.swift', 'one'],
    ]);
    const ran: string[] = [];
    const tasks = [
      check('ts', { inputs: ['src/**'], run: async () => void ran.push('ts') }),
      check('swift', { inputs: ['modules/**/*.swift'], run: async () => void ran.push('swift') }),
      check('swift-consumer', { needs: ['swift'], run: async () => void ran.push('swift-consumer') }),
    ];
    await run(state, files, tasks);
    expect(ran.sort()).toEqual(['swift', 'swift-consumer', 'ts']);
    ran.length = 0;
    expect((await run(state, files, tasks)).unchanged).toEqual(['ts', 'swift', 'swift-consumer']);
    expect(ran).toEqual([]);
    files.set('modules/power-log-bridge/ios/WorkoutDistanceStore.swift', 'two');
    const changed = await run(state, files, tasks);
    expect(ran).toEqual(['swift', 'swift-consumer']);
    expect(changed.output).toContain('swift: changed modules/power-log-bridge/ios/WorkoutDistanceStore.swift');
    ran.length = 0;
    files.delete('src/view.tsx');
    await run(state, files, tasks);
    expect(ran).toEqual(['ts']);
  });

  it('runs every check in the full tier, including release checks, despite earlier passes', async () => {
    const state = temporary();
    const files = new Map([['src/view.tsx', 'one']]);
    const ran: string[] = [];
    const tasks = [
      check('unit', { inputs: ['src/**'], run: async () => void ran.push('unit') }),
      check('device-build', { tier: 'release', inputs: ['src/**'], run: async () => void ran.push('device-build') }),
    ];
    await run(state, files, tasks);
    expect(ran).toEqual(['unit']);
    ran.length = 0;
    await run(state, files, tasks, { full: true });
    expect(ran.sort()).toEqual(['device-build', 'unit']);
    ran.length = 0;
    await run(state, files, tasks, { full: true });
    expect(ran.sort()).toEqual(['device-build', 'unit']);
  });

  it('fails the run when a check fails, blocks its dependents and records no pass for it', async () => {
    const state = temporary();
    const files = new Map([['src/view.tsx', 'one']]);
    const ran: string[] = [];
    const tasks = [
      check('broken', { inputs: ['src/**'], run: ['sh', '-c', 'echo "expected 3 to equal 4"; exit 3'] }),
      check('after-broken', { needs: ['broken'], run: async () => void ran.push('after-broken') }),
      check('independent', { run: async () => void ran.push('independent') }),
    ];
    const result = await run(state, files, tasks);
    expect(result.failed).toEqual(['broken', 'after-broken']);
    expect(result.passed).toEqual(['independent']);
    expect(ran).toEqual(['independent']);
    expect(result.output).toContain('expected 3 to equal 4');
    expect((await run(state, files, tasks)).failed).toEqual(['broken', 'after-broken']);
  });
});

describe('tiers', () => {
  const tasks = checkTasks({ root: process.cwd(), env: process.env }) as Task[];
  const task = (id: string) => {
    const found = tasks.find(item => item.id === id);
    if (!found) throw new Error(`${id} is not a check`);
    return found;
  };
  const tiers = (ids: string[]) => Object.fromEntries(ids.map(id => [id, task(id).tier]));

  it('keeps the load-bearing checks in the everyday and release tiers', () => {
    const everyday = [
      'swift:companion',
      'swift:storage',
      'swift:capture',
      'swift:engine-pacing',
      'swift:live-timing',
      'swift:monitor',
      'swift:export-source',
      'swift:coverage',
      'core:tests',
      'js:unit',
      'js:browser',
      'android:unit',
    ];
    const release = [
      'swift:live-ride-soak',
      'core:simulators',
      'core:evidence',
      'android:connected',
      'ios:build',
      'js:browser-long',
    ];
    expect(tiers(everyday)).toEqual(Object.fromEntries(everyday.map(id => [id, 'everyday'])));
    expect(tiers(release)).toEqual(Object.fromEntries(release.map(id => [id, 'release'])));
  });

  it('checks exact common-test execution for every target the tier runs', async () => {
    const commands = async (id: string, full: boolean) => {
      const calls: string[][] = [];
      const run = task(id).run as (context: Context) => Promise<void>;
      await run({
        root: '/checkout',
        full,
        env: {},
        exec: async (command, args = []) => void calls.push([command, ...args]),
      });
      const tests = (call?: string[]) => (call ?? []).filter(word => /^[a-z][A-Za-z0-9]*Test$/.test(word));
      const gradle = calls.find(call => call[0]!.endsWith('/gradlew'));
      const checked = calls.find(call => call.includes('shared/scripts/check-test-results.mjs'));
      expect(tests(checked), id).toEqual(tests(gradle));
      return { targets: tests(gradle), everyday: calls.flat().filter(word => /everyday|Everyday/.test(word)) };
    };
    expect(await commands('core:tests', false)).toEqual({
      targets: ['jvmTest', 'jsNodeTest'],
      everyday: ['-PpowerLogEveryday', '--everyday'],
    });
    const release = [await commands('core:tests', true), await commands('core:simulators', true)];
    expect(release.flatMap(row => row.everyday)).toEqual([]);
    expect(release.flatMap(row => row.targets).sort()).toEqual(
      ['iosSimulatorArm64Test', 'jsNodeTest', 'jvmTest', 'macosArm64Test'].sort(),
    );
  });
});

describe('gate checkers', () => {
  it('fails a target that did not run a required common test and excludes volume classes only every day', () => {
    const root = temporary();
    write(
      join(root, 'source/app/ExampleTest.kt'),
      'package app\nimport kotlin.test.Test\nclass ExampleTest {\n  @Test fun first() {}\n  @Test fun second() {}\n}\nclass ExampleVolumeTest {\n  @Test fun large() {}\n}\n',
    );
    const results = (...cases: [string, string][]) =>
      write(
        join(root, 'results/jvmTest/TEST-app.xml'),
        `<testsuite name="app" tests="${cases.length}">${cases
          .map(([classname, name]) => `<testcase name="${name}[jvm]" classname="app.${classname}"/>`)
          .join('')}</testsuite>`,
      );
    const verify = (everyday: boolean) =>
      checkTestResults('jvmTest', join(root, 'results'), join(root, 'source'), { everyday });
    results(['ExampleTest', 'first']);
    expect(() => verify(true)).toThrow('jvmTest did not run app.ExampleTest#second');
    results(['ExampleTest', 'first'], ['ExampleTest', 'second']);
    expect(verify(true)).toBe(2);
    expect(() => verify(false)).toThrow('jvmTest did not run app.ExampleVolumeTest#large');
  });

  it('reports a Swift entry point that no check runs', () => {
    const inventory = [
      'modules/power-log-bridge/ios/Tests/A/main.swift',
      'modules/power-log-bridge/ios/Tests/B/main.swift',
    ];
    expect(gateSwiftCoverage([check('swift:a', { entrypoints: [inventory[0]!] })], inventory)).toEqual([
      'modules/power-log-bridge/ios/Tests/B/main.swift has no gate check',
    ]);
  });

  it('rejects Lab code in a shipping Apple bundle and in release branches of Lab conditionals', () => {
    const root = join(temporary(), 'PowerLog.app');
    write(
      join(root, 'Info.plist'),
      '<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleExecutable</key><string>PowerLog</string></dict></plist>',
    );
    const machO = (body: string) => Buffer.concat([Buffer.from([0xcf, 0xfa, 0xed, 0xfe]), Buffer.from(body)]);
    write(join(root, 'PowerLog'), machO('shipping-body\0'));
    write(join(root, 'Frameworks/Provider.framework/Provider'), machO('$sWorkoutLabCrashProviderSuccess\0'));
    expect(() => checkBundle(root)).toThrow(/Provider\.framework\/Provider/);
    expect(releaseText('#if POWER_LOG_LAB\nlabOnly()\n#else\nlabReceive()\n#endif')).toContain('labReceive');
  });
});
