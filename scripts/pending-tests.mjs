import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { relative, resolve } from 'node:path';
import {
  allTestSourceRoots,
  browserCases,
  declaredClasses,
  discoverTests,
  moduleNames,
  testSourceRoots,
  testTargets,
} from '../shared/scripts/discover-common-tests.mjs';

export const pendingFile = 'tests/pending.json';

const pendingRunners = ['kotlin', 'vitest', 'playwright'];

const literalName = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$/;

function kotlinProblem(test, classes, tests, browser) {
  if (typeof test !== 'string' || !test) return 'has no test name';
  if (/[*?]/.test(test)) return 'holds a wildcard, which could leave out tests that are not pending';
  if (!literalName.test(test)) return 'is not a fully qualified class or class.method name';
  if (classes.has(test) || browser.has(test)) return null;
  const dot = test.lastIndexOf('.');
  if (tests.has(`${test.slice(0, dot)}#${test.slice(dot + 1)}`)) return null;
  return 'names no class or test in the shared modules’ test sources, and no browser case or suite they register';
}

function vitestProblem(root, test) {
  if (typeof test !== 'string' || !test) return 'has no file';
  const path = resolve(root, test);
  if (!existsSync(path)) return 'names no file';
  if (!statSync(path).isFile()) return 'is a directory, which could leave out tests that are not pending';
  if (!test.endsWith('.test.ts')) return 'is not a Vitest file (*.test.ts)';
  return null;
}

function playwrightProblem(test) {
  if (typeof test !== 'string' || !test) return 'has no test name';
  const [file, ...titles] = test.split(' > ');
  if (!file.endsWith('.spec.ts') || !titles.length || titles.some(title => !title))
    return 'is not a spec file and a full test title ("name.spec.ts > title")';
  return null;
}

export function readPending(root, file = resolve(root, pendingFile)) {
  const pending = JSON.parse(readFileSync(file, 'utf8'));
  const shared = resolve(root, 'shared');
  const classes = declaredClasses(allTestSourceRoots(shared));
  const tests = new Set(
    moduleNames.flatMap(module =>
      testTargets.flatMap(target => discoverTests(testSourceRoots(module, target, shared)).map(row => row.identity)),
    ),
  );
  const browser = new Set(browserCases(shared).flatMap(registered => [registered.name, registered.suite]));
  const problems = [];
  for (const runner of pendingRunners) {
    const entries = pending[runner];
    if (!Array.isArray(entries)) {
      problems.push(`${pendingFile} has no ${runner} list`);
      continue;
    }
    const seen = new Set();
    entries.forEach((entry, index) => {
      const test = entry?.test;
      const where = `${pendingFile} ${runner} entry ${index + 1} (${JSON.stringify(test)})`;
      const problem =
        runner === 'kotlin'
          ? kotlinProblem(test, classes, tests, browser)
          : runner === 'vitest'
            ? vitestProblem(root, test)
            : playwrightProblem(test);
      if (problem) problems.push(`${where} ${problem}`);
      if (seen.has(test)) problems.push(`${where} repeats an earlier entry`);
      seen.add(test);
      if (typeof entry?.waitsFor !== 'string' || !entry.waitsFor)
        problems.push(`${where} doesn't say what it waits for`);
    });
  }
  if (problems.length) throw new Error(problems.join('\n'));
  return pending;
}

export function pendingTests(root, runner) {
  const tests = readPending(root)[runner].map(entry => entry.test);
  if (runner !== 'playwright' || !tests.length) return tests;
  const problems = playwrightEntryProblems(tests, playwrightTestNames(root));
  if (problems.length) throw new Error(problems.join('\n'));
  return tests;
}

const colours = new RegExp(`${String.fromCharCode(27)}\\[[0-9;]*m`, 'g');

function playwrightOutcomes(report) {
  const outcomes = [];
  const visit = (suite, titles) => {
    for (const spec of suite.specs ?? [])
      for (const test of spec.tests ?? [])
        outcomes.push({
          name: [spec.file, ...titles, spec.title].join(' > '),
          status: test.status,
          error: test.results?.at(-1)?.error?.message?.replace(colours, ''),
        });
    for (const child of suite.suites ?? [])
      visit(child, child.title && child.title !== child.file ? [...titles, child.title] : titles);
  };
  for (const suite of report.suites ?? []) visit(suite, []);
  return outcomes;
}

export function playwrightTestNames(root) {
  const env = { ...process.env };
  delete env.PLAYWRIGHT_JSON_OUTPUT_NAME;
  const listed = spawnSync('npx', ['playwright', 'test', '--list', '--reporter=json'], {
    cwd: root,
    env,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (listed.status !== 0) throw new Error(`playwright test --list failed:\n${listed.stderr || listed.stdout}`);
  return new Set(playwrightOutcomes(JSON.parse(listed.stdout)).map(row => row.name));
}

export function playwrightEntryProblems(tests, names) {
  return tests.flatMap((test, index) =>
    names.has(test)
      ? []
      : [
          `${pendingFile} playwright entry ${index + 1} (${JSON.stringify(test)}) names no test that playwright test --list prints; give its spec file and full title exactly`,
        ],
  );
}

export const notImplemented = message => /NotImplementedError|An operation is not implemented/.test(message ?? '');

const firstLine = message => (message ?? '').trim().split('\n')[0];

export const browserSuiteOf = name => name.slice(0, name.lastIndexOf('.'));

const coversBrowserCase = (entry, name) => entry === name || entry === browserSuiteOf(name);

export function browserPendingProblems(cases, pending) {
  const problems = [];
  const accepted = [];
  for (const { name, failure } of cases) {
    if (failure === null) continue;
    const listed = pending.some(entry => coversBrowserCase(entry, name));
    if (listed && notImplemented(failure)) accepted.push(name);
    else
      problems.push(
        `${name} failed${listed ? ' with something other than not-implemented' : ''}: ${firstLine(failure)}`,
      );
  }
  for (const entry of pending) {
    const passing = cases.filter(row => row.failure === null && coversBrowserCase(entry, row.name));
    if (!passing.length) continue;
    if (passing.some(row => row.name === entry)) problems.push(`${entry} passes, so its ${pendingFile} entry must go`);
    else
      problems.push(
        `${entry} is pending as a whole suite, but some of its cases pass (${passing.map(row => row.name).join(', ')}): list only the cases that still wait`,
      );
  }
  return { problems, accepted };
}

const passedInPlaywright = status => status === 'expected' || status === 'flaky';

export function playwrightPendingProblems(report, pending) {
  const problems = (report.errors ?? []).map(
    error => `Playwright failed outside a test: ${firstLine(error.message?.replace(colours, ''))}`,
  );
  const accepted = [];
  for (const { name, status, error } of playwrightOutcomes(report)) {
    const listed = pending.includes(name);
    if (passedInPlaywright(status)) {
      if (listed) problems.push(`${name} passes, so its ${pendingFile} entry must go`);
    } else if (status !== 'skipped') {
      if (listed) accepted.push(name);
      else problems.push(`${name} failed: ${firstLine(error)}`);
    }
  }
  return { problems, accepted };
}

export function vitestPendingProblems(root, report, pendingFiles) {
  const problems = [];
  const accepted = [];
  for (const file of report.testResults ?? []) {
    const path = relative(root, file.name);
    const listed = pendingFiles.includes(path);
    const tests = file.assertionResults ?? [];
    if (!tests.length && file.status === 'failed') {
      if (listed && notImplemented(file.message)) accepted.push(`${path}: ${firstLine(file.message)}`);
      else problems.push(`${path} did not run: ${firstLine(file.message)}`);
      continue;
    }
    let ran = 0;
    let passed = 0;
    for (const test of tests) {
      const name = `${path} > ${[...test.ancestorTitles, test.title].join(' > ')}`;
      if (test.status === 'passed') passed++;
      if (test.status === 'passed' || test.status === 'failed') ran++;
      if (test.status !== 'failed') continue;
      const messages = test.failureMessages ?? [];
      if (listed && messages.length && messages.every(notImplemented)) accepted.push(name);
      else
        problems.push(
          `${name} failed${listed ? ' with something other than not-implemented' : ''}: ${firstLine(messages[0])}`,
        );
    }
    if (listed && ran > 0 && passed === ran) problems.push(`${path} passes, so its ${pendingFile} entry must go`);
  }
  return { problems, accepted };
}
