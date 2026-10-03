import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

if (!global.gc) throw new Error('Run with node --expose-gc tests/core/csv-import-heap.mjs');
registerHooks({
  resolve(specifier, context, next) {
    if (/^\.\.?\//.test(specifier) && !/\.[cm]?[jt]s$/.test(specifier) && context.parentURL?.endsWith('.ts')) {
      const url = new URL(`${specifier}.ts`, context.parentURL);
      if (existsSync(fileURLToPath(url))) return { url: url.href, shortCircuit: true };
    }
    return next(specifier, context);
  },
  load(url, context, next) {
    if (!url.endsWith('.ts')) return next(url, context);
    const compilerOptions = { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 };
    const source = ts.transpileModule(readFileSync(fileURLToPath(url), 'utf8'), { compilerOptions }).outputText;
    return { format: 'module', source, shortCircuit: true };
  },
});
const { createCsvParser } = await import('../../src/core/recordings.ts');
const { syntheticCsvChunks, syntheticCsvSample } = await import('./csv-fixture.ts');
global.gc();
const initialHeapUsed = process.memoryUsage().heapUsed;
let peakHeapUsed = initialHeapUsed;
let bytes = 0;
let chunks = 0;
let parseMilliseconds = 0;
const started = performance.now();
const parser = createCsvParser();
for (const chunk of syntheticCsvChunks(700_000)) {
  peakHeapUsed = Math.max(peakHeapUsed, process.memoryUsage().heapUsed);
  bytes += chunk.length;
  chunks += 1;
  const parseStarted = performance.now();
  parser.write(chunk);
  parseMilliseconds += performance.now() - parseStarted;
  peakHeapUsed = Math.max(peakHeapUsed, process.memoryUsage().heapUsed);
}
const recording = parser.finish();
const elapsedMilliseconds = performance.now() - started;
peakHeapUsed = Math.max(peakHeapUsed, process.memoryUsage().heapUsed);
assert.equal(recording.samples.length, 700_000);
assert.deepEqual(recording.samples[0], syntheticCsvSample());
assert.deepEqual(recording.samples[699_999], syntheticCsvSample(699_999));
global.gc();
console.log(
  JSON.stringify(
    {
      node: process.version,
      samples: recording.samples.length,
      chunkBytes: 1024 * 1024,
      chunks,
      bytes,
      elapsedMilliseconds,
      parseMilliseconds,
      initialHeapUsed,
      peakHeapUsed,
      retainedHeapUsed: process.memoryUsage().heapUsed,
      heapSampling: 'before and after every generated 1 MiB chunk; GC only before and after the run',
    },
    null,
    2,
  ),
);
