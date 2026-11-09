import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { runChecks } from '../../scripts/checks.mjs';
import { checkTasks } from '../../scripts/check-tasks.mjs';
import { gateSwiftCoverage, swiftSuiteInventory } from '../../scripts/check-suite-coverage.mjs';
import { checkBundle, checkSwiftBoundaries, releaseText } from '../../scripts/check-apple-release-surface.mjs';
import {
  checkTestOutcomes,
  checkTestResults,
  pendingEntryProblems,
  requiredTestIdentities,
} from '../../shared/scripts/check-test-results.mjs';
import { browserCases, testSourceRoots } from '../../shared/scripts/discover-common-tests.mjs';
import {
  browserPendingProblems,
  pendingTests,
  playwrightEntryProblems,
  playwrightPendingProblems,
  readPending,
  vitestPendingProblems,
} from '../../scripts/pending-tests.mjs';

type Context = {
  root: string;
  full: boolean;
  env: Record<string, string>;
  exec: (command: string, args?: string[], options?: { cwd?: string; env?: Record<string, string> }) => Promise<void>;
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
    env: process.env,
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
      ['modules/power-log/ios/WorkoutDistanceStore.swift', 'one'],
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
    files.set('modules/power-log/ios/WorkoutDistanceStore.swift', 'two');
    const changed = await run(state, files, tasks);
    expect(ran).toEqual(['swift', 'swift-consumer']);
    expect(changed.output).toContain('swift: changed modules/power-log/ios/WorkoutDistanceStore.swift');
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

  it('runs a demand check only when --only names it, in neither tier', async () => {
    const state = temporary();
    const files = new Map([['src/view.tsx', 'one']]);
    const ran: string[] = [];
    const tasks = [
      check('unit', { inputs: ['src/**'], run: async () => void ran.push('unit') }),
      check('slice:phones', { tier: 'demand', inputs: ['src/**'], run: async () => void ran.push('slice:phones') }),
    ];
    await run(state, files, tasks);
    await run(state, files, tasks, { full: true });
    await run(state, files, tasks, { only: ['slice'] });
    expect(ran).toEqual(['unit', 'unit']);
    await run(state, files, tasks, { only: ['slice:phones'] });
    expect(ran).toEqual(['unit', 'unit', 'slice:phones']);
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
      'swift:watch-protocol',
      'swift:coverage',
      'core:tests',
      'js:unit',
      'goldens:imports',
      'js:browser',
      'android:unit',
    ];
    const release = [
      'core:simulators',
      'ios:build',
      'js:browser-long',
      'core:browser',
      'js:e2e',
      'goldens:ride-pages',
      'goldens:rides',
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

  it.runIf(process.platform === 'darwin')('runs the Kotlin metadata check with the Android SDK default', async () => {
    vi.stubEnv('ANDROID_HOME', undefined);
    const calls: { command: string; options?: { cwd?: string; env?: Record<string, string> } }[] = [];
    try {
      const run = task('android:kotlin-metadata').run as (context: Context) => Promise<void>;
      await run({
        root: '/checkout',
        full: true,
        env: {},
        exec: async (command, _args, options) => void calls.push({ command, options }),
      });
    } finally {
      vi.unstubAllEnvs();
    }
    expect(calls.find(call => call.command === './gradlew')?.options).toMatchObject({
      cwd: '/checkout/android',
      env: { ANDROID_HOME: join(homedir(), 'Library/Android/sdk'), POWER_LOG_ANDROID_DEBUG_SIGNING: '1' },
    });
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

  it('leaves the pending suites and tests out of the everyday tier only', () => {
    const root = temporary();
    write(
      join(root, 'source/app/ExampleTest.kt'),
      'package app\nimport kotlin.test.Test\nclass ExampleTest {\n  @Test fun first() {}\n  @Test fun second() {}\n}\nclass WaitingTest {\n  @Test fun later() {}\n}\n',
    );
    write(
      join(root, 'results/jvmTest/TEST-app.xml'),
      '<testsuite name="app" tests="1"><testcase name="first[jvm]" classname="app.ExampleTest"/></testsuite>',
    );
    const verify = (everyday: boolean) =>
      checkTestResults('jvmTest', join(root, 'results'), join(root, 'source'), {
        everyday,
        pendingTests: ['app.WaitingTest', 'app.ExampleTest.second'],
      });
    expect(verify(true)).toBe(1);
    expect(() => verify(false)).toThrow('jvmTest did not run app.ExampleTest#second, app.WaitingTest#later');
  });

  it('accepts a pending test only while it fails as not implemented, and a class entry only while all of its do', () => {
    const root = temporary();
    write(
      join(root, 'source/app/ExampleTest.kt'),
      'package app\nimport kotlin.test.Test\nclass ExampleTest {\n  @Test fun first() {}\n}\nclass WaitingTest {\n  @Test fun later() {}\n  @Test fun sooner() {}\n}\n',
    );
    const results = (later: string, sooner = later) =>
      write(
        join(root, 'results/jvmTest/TEST-app.xml'),
        `<testsuite name="app" tests="3"><testcase name="first[jvm]" classname="app.ExampleTest"/>${[
          ['later', later],
          ['sooner', sooner],
        ]
          .map(([name, outcome]) =>
            outcome === 'pass'
              ? `<testcase name="${name}[jvm]" classname="app.WaitingTest"/>`
              : `<testcase name="${name}[jvm]" classname="app.WaitingTest"><failure message="${outcome}"/></testcase>`,
          )
          .join('')}</testsuite>`,
      );
    const verify = () =>
      checkTestOutcomes('jvmTest', join(root, 'results'), join(root, 'source'), {
        pendingTests: ['app.WaitingTest'],
      });
    const problems = (entries = ['app.WaitingTest']) =>
      pendingEntryProblems(
        entries,
        checkTestOutcomes('jvmTest', join(root, 'results'), join(root, 'source'), { pendingTests: entries }).outcomes,
      );
    const notYet = 'kotlin.NotImplementedError: An operation is not implemented.';
    results(notYet);
    expect(verify().count).toBe(3);
    expect(problems()).toEqual([]);
    results('java.lang.IllegalStateException: broken');
    expect(verify).toThrow('pending app.WaitingTest#later fails with something other than not-implemented');
    results('pass');
    expect(problems()).toEqual([
      'app.WaitingTest is pending as a whole class, but some of its tests pass (jvmTest app.WaitingTest#later, jvmTest app.WaitingTest#sooner): list only the tests that still wait',
    ]);
    results('pass', notYet);
    expect(problems()).toEqual([
      'app.WaitingTest is pending as a whole class, but some of its tests pass (jvmTest app.WaitingTest#later): list only the tests that still wait',
    ]);
    expect(problems(['app.WaitingTest.sooner'])).toEqual([]);
    expect(problems(['app.WaitingTest.later', 'app.WaitingTest.sooner'])).toEqual([
      'app.WaitingTest.later passes, so its tests/pending.json entry must go',
    ]);
  });

  it('accepts a pending Vitest file only while its failures are not implemented', () => {
    const report = (file: string, statuses: [string, string?][]) => ({
      testResults: [
        {
          name: join(process.cwd(), file),
          status: 'failed',
          assertionResults: statuses.map(([status, message], index) => ({
            ancestorTitles: ['suite'],
            title: `case ${index}`,
            status,
            failureMessages: message ? [message] : [],
          })),
        },
      ],
    });
    const pending = ['tests/module/a.test.ts'];
    const notYet = 'Error: An operation is not implemented.';
    expect(
      vitestPendingProblems(process.cwd(), report('tests/module/a.test.ts', [['failed', notYet]]), pending),
    ).toEqual({
      problems: [],
      accepted: ['tests/module/a.test.ts > suite > case 0'],
    });
    expect(
      vitestPendingProblems(
        process.cwd(),
        report('tests/module/a.test.ts', [['failed', 'AssertionError: 1 != 2']]),
        pending,
      ).problems,
    ).toEqual([
      'tests/module/a.test.ts > suite > case 0 failed with something other than not-implemented: AssertionError: 1 != 2',
    ]);
    expect(
      vitestPendingProblems(process.cwd(), report('tests/module/a.test.ts', [['passed'], ['skipped']]), pending)
        .problems,
    ).toEqual(['tests/module/a.test.ts passes, so its tests/pending.json entry must go']);
    expect(
      vitestPendingProblems(process.cwd(), report('tests/module/b.test.ts', [['failed', notYet]]), pending).problems,
    ).toEqual(['tests/module/b.test.ts > suite > case 0 failed: Error: An operation is not implemented.']);
  });

  it('reads the repository pending list without a problem', () => {
    const pending = readPending(process.cwd());
    expect(pendingTests(process.cwd(), 'playwright')).toHaveLength(pending.playwright.length);
  }, 60_000);

  it('refuses a pending entry that could hide a test that is not pending, naming the entry', () => {
    const root = temporary();
    write(
      join(root, 'shared/application/src/jvmTest/kotlin/app/ExampleTest.kt'),
      'package app\nimport kotlin.test.Test\nclass ExampleTest {\n  @Test fun first() {}\n}\n',
    );
    write(join(root, 'tests/module/a.test.ts'), '');
    write(join(root, 'tests/module/helper.ts'), '');
    const pending = (kotlin: string[], vitest: string[], playwright: string[] = []) => {
      const entries = (tests: string[]) => tests.map(test => ({ test, waitsFor: 'the lane' }));
      write(
        join(root, 'tests/pending.json'),
        JSON.stringify({ kotlin: entries(kotlin), vitest: entries(vitest), playwright: entries(playwright) }),
      );
      return () => readPending(root);
    };
    expect(pending(['app.ExampleTest', 'app.ExampleTest.first'], ['tests/module/a.test.ts'])()).toMatchObject({
      kotlin: [{ test: 'app.ExampleTest' }, { test: 'app.ExampleTest.first' }],
    });
    expect(pending(['app.*'], [])).toThrow('kotlin entry 1 ("app.*") holds a wildcard');
    expect(pending(['app.Example?est'], [])).toThrow('kotlin entry 1 ("app.Example?est") holds a wildcard');
    expect(pending(['ExampleTest'], [])).toThrow('is not a fully qualified class or class.method name');
    expect(pending(['app.ExampleTest.second'], [])).toThrow('names no class or test');
    expect(pending([], ['tests/module'])).toThrow('vitest entry 1 ("tests/module") is a directory');
    expect(pending([], ['tests/module/b.test.ts'])).toThrow('vitest entry 1 ("tests/module/b.test.ts") names no file');
    expect(pending([], ['tests/module/helper.ts'])).toThrow('is not a Vitest file');
    expect(pending(['app.ExampleTest', 'app.ExampleTest'], [])).toThrow('kotlin entry 2 ("app.ExampleTest") repeats');
    expect(pending([], [], ['thin.spec.ts > rides', 'thin.spec.ts > a group > rides'])()).toMatchObject({
      playwright: [{ test: 'thin.spec.ts > rides' }, { test: 'thin.spec.ts > a group > rides' }],
    });
    for (const partial of ['thin.spec.ts', 'thin.test.ts > rides', 'thin.spec.ts > ', 'rides'])
      expect(pending([], [], [partial])).toThrow(
        `playwright entry 1 (${JSON.stringify(partial)}) is not a spec file and a full test title`,
      );
    expect(pending([], [], ['thin.spec.ts > rides', 'thin.spec.ts > rides'])).toThrow(
      'playwright entry 2 ("thin.spec.ts > rides") repeats',
    );
    write(join(root, 'tests/pending.json'), JSON.stringify({ kotlin: [], vitest: [] }));
    expect(() => readPending(root)).toThrow('tests/pending.json has no playwright list');
  });

  it('accepts a Playwright entry only when playwright test --list prints its spec file and full title', () => {
    const listed = new Set(['thin.spec.ts > rides', 'monitor.spec.ts > touch editor > drags']);
    expect(playwrightEntryProblems(['thin.spec.ts > rides', 'monitor.spec.ts > touch editor > drags'], listed)).toEqual(
      [],
    );
    expect(
      playwrightEntryProblems(
        ['thin.spec.ts > ride', 'monitor.spec.ts > drags', 'tests/e2e/thin.spec.ts > rides', 'thin.spec.ts > rides'],
        listed,
      ),
    ).toEqual(
      ['thin.spec.ts > ride', 'monitor.spec.ts > drags', 'tests/e2e/thin.spec.ts > rides'].map(
        (test, index) =>
          `tests/pending.json playwright entry ${index + 1} (${JSON.stringify(test)}) names no test that playwright test --list prints; give its spec file and full title exactly`,
      ),
    );
  });

  it('accepts a listed Playwright test while it fails, and refuses it once it passes', () => {
    const report = (...tests: [string, string, string?][]) => ({
      suites: [
        {
          title: 'thin.spec.ts',
          file: 'thin.spec.ts',
          specs: tests.map(([title, status, message]) => ({
            title,
            file: 'thin.spec.ts',
            tests: [
              { status, results: message ? [{ error: { message: `\u001b[31m${message}\u001b[39m\nat line 3` } }] : [] },
            ],
          })),
          suites: [
            {
              title: 'a group',
              file: 'thin.spec.ts',
              specs: [{ title: 'waits', file: 'thin.spec.ts', tests: [{ status: 'unexpected', results: [] }] }],
            },
          ],
        },
      ],
    });
    const pending = ['thin.spec.ts > rides', 'thin.spec.ts > a group > waits'];
    expect(
      playwrightPendingProblems(report(['rides', 'unexpected', 'Error: no rows'], ['skips', 'skipped']), pending),
    ).toEqual({
      problems: [],
      accepted: ['thin.spec.ts > rides', 'thin.spec.ts > a group > waits'],
    });
    expect(playwrightPendingProblems(report(['rides', 'expected']), pending).problems).toEqual([
      'thin.spec.ts > rides passes, so its tests/pending.json entry must go',
    ]);
    expect(playwrightPendingProblems(report(['charts', 'unexpected', 'Error: no chart']), pending).problems).toEqual([
      'thin.spec.ts > charts failed: Error: no chart',
    ]);
    expect(
      playwrightPendingProblems(
        { ...report(['rides', 'unexpected']), errors: [{ message: 'web server exited' }] },
        pending,
      ).problems,
    ).toEqual(['Playwright failed outside a test: web server exited']);
  });

  it('reads the browser cases the jsTest sources register, by literal Suite.case names only', () => {
    const root = temporary();
    const cases = (body: string) => {
      write(join(root, 'shared/data/src/jsTest/kotlin/app/web/Cases.kt'), `package app.web\n${body}`);
      return () => browserCases(join(root, 'shared'));
    };
    expect(
      cases(
        'internal fun browserCase(name: String, test: () -> Unit) = name to test\n' +
          'val cases = listOf(browserCase("IdbTest.writes") {}, browserCase(\n  "IdbTest.reads",\n) {})\n',
      )(),
    ).toMatchObject([
      { module: 'data', name: 'app.web.IdbTest.writes', suite: 'app.web.IdbTest' },
      { module: 'data', name: 'app.web.IdbTest.reads', suite: 'app.web.IdbTest' },
    ]);
    expect(cases('val name = "IdbTest.writes"\nval cases = listOf(browserCase(name) {})\n')).toThrow(
      'browserCase takes a literal "Suite.case" name',
    );
    expect(cases('val cases = listOf(browserCase("app.web.IdbTest.writes") {})\n')).toThrow(
      'browserCase takes a literal "Suite.case" name',
    );
    expect(cases('val cases = listOf(browserCase("IdbTest.writes") {}, browserCase("IdbTest.writes") {})\n')).toThrow(
      'app.web.IdbTest.writes is also registered',
    );
  });

  it('accepts a browser case or suite the jsTest sources register, and refuses any other name', () => {
    const root = temporary();
    write(
      join(root, 'shared/data/src/jsTest/kotlin/app/web/Cases.kt'),
      'package app.web\nval cases = listOf(browserCase("IdbTest.writes") {}, browserCase("IdbTest.reads") {})\n',
    );
    const pending = (kotlin: string[]) => {
      const entries = kotlin.map(test => ({ test, waitsFor: 'the lane' }));
      write(join(root, 'tests/pending.json'), JSON.stringify({ kotlin: entries, vitest: [], playwright: [] }));
      return () => readPending(root);
    };
    expect(pending(['app.web.IdbTest', 'app.web.IdbTest.reads'])()).toMatchObject({
      kotlin: [{ test: 'app.web.IdbTest' }, { test: 'app.web.IdbTest.reads' }],
    });
    for (const misspelt of ['app.web.IdbTest.read', 'app.web.IdbTes', 'app.web'])
      expect(pending([misspelt])).toThrow(
        `kotlin entry 1 (${JSON.stringify(misspelt)}) names no class or test in the shared modules’ test sources, and no browser case or suite they register`,
      );
  });

  it('holds a browser entry to failing as not implemented, and a suite entry to covering only waiting cases', () => {
    const notYet = 'NotImplementedError: An operation is not implemented: the lane';
    const run = (rows: [string, string | null][], pending: string[]) =>
      browserPendingProblems(
        rows.map(([name, failure]) => ({ name, failure })),
        pending,
      );
    expect(
      run(
        [
          ['app.IdbTest.writes', notYet],
          ['app.IdbTest.reads', notYet],
        ],
        ['app.IdbTest'],
      ),
    ).toEqual({
      problems: [],
      accepted: ['app.IdbTest.writes', 'app.IdbTest.reads'],
    });
    expect(
      run(
        [
          ['app.IdbTest.writes', notYet],
          ['app.IdbTest.reads', null],
        ],
        ['app.IdbTest'],
      ).problems,
    ).toEqual([
      'app.IdbTest is pending as a whole suite, but some of its cases pass (app.IdbTest.reads): list only the cases that still wait',
    ]);
    expect(
      run(
        [
          ['app.IdbTest.writes', notYet],
          ['app.IdbTest.reads', null],
        ],
        ['app.IdbTest.writes'],
      ),
    ).toEqual({
      problems: [],
      accepted: ['app.IdbTest.writes'],
    });
    expect(run([['app.IdbTest.writes', null]], ['app.IdbTest.writes']).problems).toEqual([
      'app.IdbTest.writes passes, so its tests/pending.json entry must go',
    ]);
    expect(run([['app.IdbTest.writes', 'AssertionError: expected 1']], ['app.IdbTest.writes']).problems).toEqual([
      'app.IdbTest.writes failed with something other than not-implemented: AssertionError: expected 1',
    ]);
    expect(run([['app.IdbTest.writes', notYet]], ['app.Idb', 'app', 'app.IdbTest.write']).problems).toEqual([
      `app.IdbTest.writes failed: ${notYet}`,
    ]);
  });

  it('requires the tests of every test source set, inherited ones included', () => {
    const root = temporary();
    const shared = join(root, 'shared');
    write(
      join(shared, 'application/src/contracts/kotlin/app/store/Contract.kt'),
      'package app.store\nimport kotlin.test.Test\nabstract class Contract {\n  @Test fun inherited() {}\n}\nclass ContractReadersTest {\n  @Test fun reads() {}\n}\n',
    );
    write(
      join(shared, 'data/src/jvmTest/kotlin/app/data/SqlTest.kt'),
      'package app.data\nimport app.store.Contract\nimport kotlin.test.Test\nclass SqlTest : Contract()\nclass OwnTest {\n  @Test fun own() {\n    data class Local(val a: Int)\n  }\n}\n',
    );
    write(
      join(shared, 'application/src/jvmTest/kotlin/app/AppTest.kt'),
      'package app\nimport kotlin.test.Test\nclass AppTest {\n  @Test fun runs() {}\n}\n',
    );
    const required = (module: string, target: string) =>
      requiredTestIdentities(testSourceRoots(module, target, shared), { pendingTests: [] }).sort();
    expect(required('data', 'jvmTest')).toEqual([
      'app.data.OwnTest#own',
      'app.data.SqlTest#inherited',
      'app.store.ContractReadersTest#reads',
    ]);
    expect(required('data', 'jsNodeTest')).toEqual(['app.store.ContractReadersTest#reads']);
    expect(required('application', 'jvmTest')).toEqual(['app.AppTest#runs', 'app.store.ContractReadersTest#reads']);
    expect(required('application', 'jsNodeTest')).toEqual([]);
  });

  it('reports a Swift entry point that no check runs', () => {
    const inventory = ['modules/power-log/ios/Tests/A/main.swift', 'modules/power-log/ios/Tests/B/main.swift'];
    expect(gateSwiftCoverage([check('swift:a', { entrypoints: [inventory[0]!] })], inventory)).toEqual([
      'modules/power-log/ios/Tests/B/main.swift has no gate check',
    ]);
  });

  it('inventories a clean checkout without empty test directories', () => {
    const root = temporary();
    const suite = 'modules/power-log/ios/Tests/A/main.swift';
    write(join(root, suite), '');
    expect(swiftSuiteInventory(root)).toEqual([suite]);
    expect(gateSwiftCoverage([], swiftSuiteInventory(root))).toEqual([`${suite} has no gate check`]);
  });

  it('rejects the dev tools in a shipping Apple bundle and keeps the release branch of their conditionals', () => {
    const root = join(temporary(), 'PowerLog.app');
    write(
      join(root, 'Info.plist'),
      '<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleExecutable</key><string>PowerLog</string></dict></plist>',
    );
    const machO = (body: string) => Buffer.concat([Buffer.from([0xcf, 0xfa, 0xed, 0xfe]), Buffer.from(body)]);
    write(join(root, 'PowerLog'), machO('shipping-body\0'));
    write(join(root, 'Frameworks/Provider.framework/Provider'), machO('power-log-dev-tools\0'));
    expect(() => checkBundle(root)).toThrow(/Provider\.framework\/Provider carries the dev tools/);
    const conditional = '#if POWER_LOG_DEV_TOOLS\ndevOnly()\n#else\nshipping()\n#endif';
    expect(releaseText(conditional, 'POWER_LOG_DEV_TOOLS')).toBe('shipping()');
  });

  it('keeps Expo and the runtime in the Swift host and the dev tools behind their flag', () => {
    const root = temporary();
    write(
      join(root, 'modules/power-log/ios/host/PowerLogModule.swift'),
      'import ExpoModulesCore\nlet host = HostFacade()\n',
    );
    write(join(root, 'modules/power-log/ios/charts/ChartRasterView.swift'), '  internal import ExpoModulesCore\n');
    write(
      join(root, 'targets/watch/PowerLogWatchApp.swift'),
      '#if POWER_LOG_DEV_TOOLS\nlet tools = DevToolsFactory()\n#endif\n',
    );
    write(join(root, 'targets/live-activity/PowerLogRideWidget.swift'), 'struct Widget {}\n');
    expect(() => checkSwiftBoundaries(root)).not.toThrow();
    write(join(root, 'modules/power-log/ios/ble/AppleBleCentral.swift'), 'import ExpoModulesCore\n');
    expect(() => checkSwiftBoundaries(root)).toThrow(/ble\/AppleBleCentral\.swift imports ExpoModulesCore/);
    write(join(root, 'modules/power-log/ios/ble/AppleBleCentral.swift'), 'let session: BridgeSession? = nil\n');
    expect(() => checkSwiftBoundaries(root)).toThrow(/ble\/AppleBleCentral\.swift reaches the runtime/);
    write(join(root, 'modules/power-log/ios/ble/AppleBleCentral.swift'), 'struct AppleBleCentral {}\n');
    write(join(root, 'targets/watch/PowerLogWatchApp.swift'), 'kill(getpid(), SIGKILL)\n');
    expect(() => checkSwiftBoundaries(root)).toThrow(/PowerLogWatchApp\.swift names the dev tools/);
    write(join(root, 'targets/watch/PowerLogWatchApp.swift'), 'struct App {}\n');
  });
});
