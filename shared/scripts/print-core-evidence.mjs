import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkTestResults } from './check-test-results.mjs';

const targets = [
  ['JVM', 'jvmTest', 'compileTestKotlinJvm'],
  ['JS (Node)', 'jsNodeTest', 'compileTestKotlinJs'],
  ['macOS arm64', 'macosArm64Test', 'compileTestKotlinMacosArm64', 'linkDebugTestMacosArm64'],
  [
    'iOS simulator arm64',
    'iosSimulatorArm64Test',
    'compileTestKotlinIosSimulatorArm64',
    'linkDebugTestIosSimulatorArm64',
  ],
  ['iOS device arm64', null, 'compileTestKotlinIosArm64', 'linkReleaseFrameworkIosArm64'],
];

export function coreEvidence(log, resultsRoot, watchLinked) {
  const tasks = new Map();
  for (const match of log.matchAll(/^> Task :([^\s]+)([^\n]*)$/gm)) tasks.set(match[1], match[2].trim());
  const stale = [...tasks].filter(
    ([name, status]) => /(?:^|:)(?:[^:]*Test|test)$/.test(name) && /SKIPPED|NO-SOURCE/.test(status),
  );
  const succeeded = name => tasks.has(name) && !/FAILED|SKIPPED|NO-SOURCE/.test(tasks.get(name));
  const rows = targets.map(([target, task, compile, link]) => {
    let level = 'unproved';
    if (succeeded(compile)) level = 'compiled';
    if (link && succeeded(link)) level = 'linked';
    let suites = 'not executed';
    let error;
    if (task) {
      try {
        suites = `${checkTestResults(task, resultsRoot)} required suites passed`;
        if (!succeeded(task)) throw new Error(`${task} did not complete successfully in this run.`);
        level = /UP-TO-DATE|FROM-CACHE/.test(tasks.get(task)) ? 'executed (unchanged inputs)' : 'executed';
      } catch (failure) {
        error = failure.message;
        suites = error;
      }
    } else if (succeeded(compile)) {
      suites = 'required test sources compiled; not executed';
    } else {
      error = `${compile} did not complete successfully in this run.`;
      suites = error;
    }
    return { target, level, suites, error };
  });
  rows.push({
    target: 'Native Watch app',
    level: watchLinked ? 'linked (Release; architecture checked)' : 'unproved',
    suites: 'native Swift target; no Kotlin common runtime',
    error: watchLinked ? undefined : 'Native Watch Release link was not confirmed in this run.',
  });
  for (const [task, status] of stale)
    rows.push({
      target: task,
      level: 'not executed',
      suites: status,
      error: `${task} did not execute in this run (${status}).`,
    });
  return rows;
}

function main([logFile, resultsRoot, watchStatus]) {
  const rows = coreEvidence(readFileSync(logFile, 'utf8'), resultsRoot, watchStatus === '0');
  console.log('\nCore qualification evidence for this run:');
  console.log('| Target | Evidence level | Required suites |');
  console.log('| --- | --- | --- |');
  for (const row of rows) console.log(`| ${row.target} | ${row.level} | ${row.suites} |`);
  console.log('Physical device qualification is separate; no target is device-qualified by this gate.');
  if (rows.some(row => row.error || row.level === 'unproved') || watchStatus !== '0') process.exitCode = 1;
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) main(process.argv.slice(2));
