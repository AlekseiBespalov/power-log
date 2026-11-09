import { existsSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { discoverTests, moduleNames, testSourceRoots } from './discover-common-tests.mjs';
import { pendingTests as listedPending, notImplemented, pendingFile } from '../../scripts/pending-tests.mjs';

const runnableTargets = ['jvmTest', 'jsNodeTest', 'macosArm64Test', 'iosSimulatorArm64Test'];

const platformSuffixes = ['jvm', 'js, node', 'macosArm64', 'iosSimulatorArm64'];
const platformClasses = ['jsNodeTest', 'macosArm64Test', 'iosSimulatorArm64Test'];

export function normalizeTestIdentity(name, classname) {
  for (const suffix of platformSuffixes) {
    const ending = `[${suffix}]`;
    if (name.endsWith(ending)) {
      name = name.slice(0, -ending.length);
      break;
    }
  }
  for (const prefix of platformClasses) {
    if (classname.startsWith(`${prefix}.`)) {
      classname = classname.slice(prefix.length + 1);
      break;
    }
  }
  return `${classname.replaceAll('$', '.')}#${name}`;
}

const repository = resolve(import.meta.dirname, '../..');
const shared = resolve(repository, 'shared');

export const pendingKotlinTests = () => listedPending(repository, 'kotlin');

export function pending(identity, tests) {
  const [suite, name] = identity.split('#');
  return tests.some(test => test === suite || test === `${suite}.${name}`);
}

const separatelyRun = ['app.powerlog.runtime.boundaries.', 'app.powerlog.application.exports.RideGoldenPagesTest#'];

export function requiredTestIdentities(
  sourceRoots,
  { everyday = false, pendingTests = pendingKotlinTests(), select } = {},
) {
  return discoverTests([sourceRoots].flat())
    .map(row => row.identity)
    .filter(identity =>
      select
        ? select.some(suite => identity.startsWith(`${suite}#`))
        : !separatelyRun.some(prefix => identity.startsWith(prefix)),
    )
    .filter(
      identity => !everyday || (!identity.split('#')[0].endsWith('VolumeTest') && !pending(identity, pendingTests)),
    );
}

export function checkTestOutcomes(
  target,
  resultsRoot,
  sourceRoots,
  { everyday = false, pendingTests = pendingKotlinTests(), select } = {},
) {
  if (!runnableTargets.includes(target)) throw new Error(`Unknown runnable target ${target}`);
  const required = requiredTestIdentities(sourceRoots, { everyday, pendingTests, select });
  const outcomes = [];
  if (!required.length) return { count: 0, outcomes };
  const directory = resolve(resultsRoot, target);
  if (!existsSync(directory)) throw new Error(`${target} did not run the required suites.`);
  const files = readdirSync(directory)
    .filter(name => name.endsWith('.xml'))
    .map(name => resolve(directory, name));
  if (!files.length) throw new Error(`${target} did not run the required suites.`);
  const parsed = spawnSync('python3', [resolve(import.meta.dirname, 'parse-junit.py'), ...files], { encoding: 'utf8' });
  if (parsed.error) throw parsed.error;
  if (parsed.status !== 0) throw new Error(`${target}: invalid JUnit XML: ${parsed.stderr.trim()}`);
  const report = JSON.parse(parsed.stdout);
  const problems = [];
  const identities = new Map();
  for (const row of report.cases) {
    const identity = normalizeTestIdentity(row.name, row.classname);
    identities.set(identity, (identities.get(identity) ?? 0) + 1);
    const listed = !everyday && pending(identity, pendingTests);
    if (listed) outcomes.push({ identity, target, status: row.status });
    if (row.status === 'skipped') problems.push(`${target} skipped ${identity}`);
    else if (row.status === 'failed' && !listed) problems.push(`${target} failed ${identity}`);
    else if (row.status === 'failed' && !notImplemented(row.message))
      problems.push(`${target}: pending ${identity} fails with something other than not-implemented`);
  }
  if (report.failed && !report.cases.some(row => row.status !== 'passed'))
    problems.push(`${target} has a failed or skipped suite outside its test cases`);
  if (problems.length) throw new Error(problems.join('\n'));
  for (const [identity, count] of identities) {
    if (count !== 1) throw new Error(`${target} duplicated testcase ${identity} (${count} testcases).`);
  }
  const missing = [];
  for (const identity of required) {
    const count = identities.get(identity) ?? 0;
    if (count === 0) missing.push(identity);
  }
  if (missing.length) throw new Error(`${target} did not run ${missing.join(', ')}.`);
  return { count: required.length, outcomes };
}

export function checkTestResults(target, resultsRoot, sourceRoots, options) {
  return checkTestOutcomes(target, resultsRoot, sourceRoots, options).count;
}

const invoked = resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url);
const targets = invoked ? process.argv.slice(2) : [];
export function pendingEntryProblems(pendingTests, outcomes) {
  return pendingTests.flatMap(entry => {
    const own = outcomes.filter(outcome => pending(outcome.identity, [entry]));
    const passing = own.filter(outcome => outcome.status === 'passed');
    if (!passing.length) return [];
    if (own.some(outcome => outcome.identity.split('#')[0] === entry)) {
      const names = passing.map(outcome => `${outcome.target} ${outcome.identity}`).join(', ');
      return [
        `${entry} is pending as a whole class, but some of its tests pass (${names}): list only the tests that still wait`,
      ];
    }
    return passing.length === own.length ? [`${entry} passes, so its ${pendingFile} entry must go`] : [];
  });
}

if (invoked) {
  const everyday = targets.includes('--everyday');
  const select = targets.filter((_, index) => targets[index - 1] === '--select');
  const names = targets.filter((argument, index) => !argument.startsWith('--') && targets[index - 1] !== '--select');
  if (!names.length) throw new Error('Name the required test targets.');
  const pendingTests = pendingKotlinTests();
  const outcomes = [];
  for (const target of names)
    for (const module of moduleNames) {
      const roots = testSourceRoots(module, target, shared);
      const checked = checkTestOutcomes(target, resolve(shared, module, 'build/test-results'), roots, {
        everyday,
        pendingTests,
        select: select.length ? select : undefined,
      });
      outcomes.push(...checked.outcomes);
      const count = checked.count;
      if (count)
        console.log(`${module} ${target}: all ${count} required${everyday ? ' everyday' : ''} tests executed.`);
    }
  const failing = outcomes.filter(outcome => outcome.status === 'failed');
  if (failing.length)
    console.log(
      `${failing.length} pending tests fail as not implemented, as ${pendingFile} expects:\n${failing
        .map(outcome => `  ${outcome.target} ${outcome.identity}`)
        .join('\n')}`,
    );
  const problems = pendingEntryProblems(pendingTests, outcomes);
  if (problems.length) throw new Error(problems.join('\n'));
}
