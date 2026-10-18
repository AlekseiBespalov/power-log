import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import type { Page } from '@playwright/test';
import type * as Harness from '../fixtures/crash-path-harness';
import { browserTestBundle } from './browser-test-bundle';
declare global {
  interface Window {
    crashPaths: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/fixtures/crash-path-harness.ts', 'crashPaths');
export const interruptionPoints = [false, true] as const;
export async function webCrashPath(page: Page, afterCommit: boolean, origin: string) {
  await page.route(`${origin}/**`, route =>
    route.fulfill({ contentType: 'text/html', body: '<title>Interrupted ride</title>' }),
  );
  await page.goto(origin);
  await page.addScriptTag({ content: bundle });
  const id = await page.evaluate(async () => (await window.crashPaths.start()).id!);
  await page.evaluate(() => {
    for (let i = 0; i < 16; i++) window.crashPaths.sample(i);
  });
  await page.waitForFunction(async id => (await window.crashPaths.store.get(id)).telemetryCount === 16, id);
  await page.evaluate(afterCommit => {
    window.crashPaths.arm(afterCommit);
    window.crashPaths.sample(16);
  }, afterCommit);
  await page.waitForFunction(() => window.crashPaths.reached);
  await page.reload();
  await page.addScriptTag({ content: bundle });
  const result = await page.evaluate(id => window.crashPaths.finishSaved(id), id);
  const count = afterCommit ? 17 : 16;
  assert.equal(result.before.phase, 'interrupted');
  assert.equal(result.saved.phase, 'completed');
  assert.equal(result.record.sampleHz, 8);
  assert.equal(result.record.telemetryCount, count);
  assert.equal(result.record.elapsedSeconds, (count - 1) / 8);
  assert.equal(result.record.endedAt, new Date(Date.UTC(2026, 0, 1) + ((count - 1) / 8) * 1000).toISOString());
  assert.deepEqual(
    result.rows.map(row => row.originalSequence),
    Array.from({ length: count }, (_, i) => i),
  );
  assert.deepEqual(
    result.rows.map(row => row.elapsedSeconds),
    Array.from({ length: count }, (_, i) => i / 8),
  );
  for (const kind of ['fit', 'zip'] as const) {
    const download = page.waitForEvent('download');
    await page.evaluate(
      async ({ id, kind }) => {
        const api = window.crashPaths;
        const request = { rideId: id, context: { platform: 'web' as const, exportedAt: '2026-01-01T00:10:00.000Z' } };
        if (kind === 'fit') await api.exportFit(api.browserExportSource, api.openBrowserExportSink, request);
        else await api.exportZip(api.browserExportSource, api.openBrowserExportSink, request);
      },
      { id, kind },
    );
    const saved = await download;
    assert.equal(await saved.failure(), null);
    const bytes = readFileSync((await saved.path())!);
    assert.ok(bytes.length > 100);
    if (kind === 'fit') assert.equal(bytes.toString('ascii', 8, 12), '.FIT');
    else assert.equal(bytes.readUInt32LE(0), 0x04034b50);
  }
}
