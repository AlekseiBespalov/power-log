import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { extname } from 'node:path';

if (!global.gc) throw new Error('Run with node --expose-gc tests/core/csv-import-heap.mjs');
registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(specifier.startsWith('.') && !extname(specifier) ? `${specifier}.ts` : specifier, context);
  },
});
const { createCsvParser } = await import('../../src/core/recordings.ts');
const { syntheticCsvChunks, syntheticCsvSample } = await import('./csv-fixture.ts');
global.gc();
const initialHeapUsed = process.memoryUsage().heapUsed;
let peakHeapUsed = initialHeapUsed;
let bytes = 0; let chunks = 0; let parseMilliseconds = 0;
const started = performance.now();
const parser = createCsvParser();
for (const chunk of syntheticCsvChunks(700_000)) {
  peakHeapUsed = Math.max(peakHeapUsed, process.memoryUsage().heapUsed);
  bytes += chunk.length; chunks += 1;
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
console.log(JSON.stringify({
  node: process.version, samples: recording.samples.length, chunkBytes: 1024 * 1024, chunks, bytes,
  elapsedMilliseconds, parseMilliseconds, initialHeapUsed, peakHeapUsed,
  retainedHeapUsed: process.memoryUsage().heapUsed,
  heapSampling: 'before and after every generated 1 MiB chunk; GC only before and after the run',
}, null, 2));
