import { existsSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { commonTestRoot, discoverCommonTests } from './discover-common-tests.mjs';
export { discoverCommonTests } from './discover-common-tests.mjs';

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

export function requiredTestIdentities(sourceRoot = commonTestRoot, { everyday = false } = {}) {
  return discoverCommonTests(sourceRoot)
    .map(row => row.identity)
    .filter(identity => !everyday || !identity.split('#')[0].endsWith('VolumeTest'));
}

export function checkTestResults(
  target,
  resultsRoot = resolve(import.meta.dirname, '../build/test-results'),
  sourceRoot = commonTestRoot,
  { everyday = false } = {},
) {
  if (!runnableTargets.includes(target)) throw new Error(`Unknown runnable target ${target}`);
  const required = requiredTestIdentities(sourceRoot, { everyday });
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
  if (report.failed) throw new Error(`${target} has failed or skipped tests.`);
  const identities = new Map();
  for (const row of report.cases) {
    const identity = normalizeTestIdentity(row.name, row.classname);
    identities.set(identity, (identities.get(identity) ?? 0) + 1);
  }
  for (const [identity, count] of identities) {
    if (count !== 1) throw new Error(`${target} duplicated testcase ${identity} (${count} testcases).`);
  }
  const missing = [];
  for (const identity of required) {
    const count = identities.get(identity) ?? 0;
    if (count === 0) missing.push(identity);
  }
  if (missing.length) throw new Error(`${target} did not run ${missing.join(', ')}.`);
  return required.length;
}

const invoked = resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url);
const targets = invoked ? process.argv.slice(2) : [];
if (invoked) {
  const everyday = targets[0] === '--everyday';
  const names = everyday ? targets.slice(1) : targets;
  if (!names.length) throw new Error('Name the required test targets.');
  for (const target of names) {
    const count = checkTestResults(target, undefined, undefined, { everyday });
    console.log(`${target}: all ${count} required${everyday ? ' everyday' : ''} common tests executed.`);
  }
}
