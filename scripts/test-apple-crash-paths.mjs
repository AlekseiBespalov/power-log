import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { appleSourceDigest } from './prepare-apple-crash-tests.mjs';
import { AppleCrashRunner } from './apple-crash-runner.mjs';

export async function runAppleCrashPaths({
  argv = process.argv.slice(2),
  runner,
  directory = resolve('test-results/lab-apple/runtime', randomUUID()),
  sourceDigest = appleSourceDigest,
  print = console.log,
} = {}) {
  for (const argument of argv)
    assert.ok(['--no-build', '--phone-only', '--watch-only'].includes(argument), `Unknown argument: ${argument}`);
  assert.ok(!(argv.includes('--phone-only') && argv.includes('--watch-only')), 'Choose one simulator subset');
  mkdirSync(directory, { recursive: true });
  const manifest = { sourceDigest: sourceDigest(), status: 'running', results: [] };
  const save = () => writeFileSync(join(directory, 'run.json'), JSON.stringify(manifest, null, 2));
  save();
  runner ??= new AppleCrashRunner({ digest: manifest.sourceDigest });
  try {
    await runner.prepare({ build: !argv.includes('--no-build') });
    if (!argv.includes('--watch-only')) {
      manifest.results.push(await runner.launch('phone', 'local-record', 'passed'));
      const interrupted = await runner.launch('phone', 'interrupt-watch-prepare', 'killed');
      const finished = await runner.launch('phone', 'interrupt-watch-finish', 'passed');
      assert.equal(finished.ride, interrupted.ride, 'Interrupted Finish must retain the same application ride');
      assert.notEqual(finished.pid, interrupted.pid, 'Interruption must cross a real process restart');
      manifest.results.push(interrupted, finished);
      save();
    }
    if (!argv.includes('--phone-only')) {
      manifest.results.push(await runner.launch('watch', 'watch-cleanup', 'passed'));
      manifest.results.push(await runner.launch('watch', 'watch-native-finish', 'passed'));
      manifest.results.push(await runner.launch('watch', 'watch-cancel-start', 'passed'));
      manifest.results.push(await runner.launch('watch', 'watch-health-save', 'passed'));
    }
    manifest.status = 'passed';
    save();
    print(`Apple simulator runtime checks passed: ${directory}`);
  } catch (error) {
    manifest.status = 'failed';
    manifest.error = String(error);
    save();
    throw error;
  } finally {
    await runner.terminateAll();
  }
  return manifest;
}
if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) await runAppleCrashPaths();
