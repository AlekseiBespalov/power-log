import { afterAll, beforeAll, expect, it, vi } from 'vitest';
import { chromium, type Browser } from '@playwright/test';
import * as browserBundles from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-ride-harness';
const preparation = vi.spyOn(browserBundles, 'browserTestBundle');
const bundle = browserBundles.browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'inspectionTests');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
  preparation.mockRestore();
});
async function run<T>(work: (h: typeof Harness) => Promise<T>): Promise<T> {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.route('http://127.0.0.1:43119/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html>' }),
    );
    await page.goto('http://127.0.0.1:43119/');
    await page.addScriptTag({
      content: bundle,
    });
    return await page.evaluate(
      work =>
        new Function('h', `return (${work})(h)`)(
          (window as unknown as { inspectionTests: typeof Harness }).inspectionTests,
        ),
      work.toString(),
    );
  } finally {
    await context.close();
  }
}
it('retrieves anchored equal-time locations and complete brackets past missing metric rows', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1),
      stamp = new Date(base).toISOString();
    const ride = await h.store.begin('owner', { indoor: false, recordGPS: true, sampleHz: 8 }, stamp);
    const ids = [
      '00000000-0000-4000-8000-000000000001',
      '00000000-0000-4000-8000-000000000002',
      '00000000-0000-4000-8000-000000000003',
    ];
    const points = [0, 0, 0, 1, 2, 3].map((time, index) => ({
      recordingId: ride.id,
      eventId: ids[index] ?? crypto.randomUUID(),
      producer: 'browser' as const,
      producerSequence: String(index + 2),
      timestamp: new Date(base + time * 1000).toISOString(),
      queryUs: String(BigInt(base + time * 1000) * 1000n),
      elapsedUs: String(time * 1000000),
      originalTimestamp: new Date(base + time * 1000).toISOString(),
      elapsedSeconds: time,
      originalElapsedSeconds: time,
      acquiredAtSeconds: time,
      clockEpoch: 'epoch',
      active: true,
      interval: 0,
      distanceBarrier: false,
      latitude: 0,
      longitude: 0,
      horizontalAccuracyM: 5,
      originalAltitudeDatum: 'unknown' as const,
      ...(index === 3 || index === 4 ? {} : { speedMps: index + 1 }),
    }));
    const record = await h.store.appendLocations(ride.id, 'owner', points, 3, 3, points.at(-1)!.timestamp);
    const source = h.monitor(ride.id);
    const request = { generation: 1, expectedRevision: record.revision, metrics: ['speedMps'] };
    return {
      anchor: await source.inspectAt({
        ...request,
        seconds: 0,
        anchor: { metric: 'speedMps', observationId: `${ride.id}:${ids[1]}` },
      }),
      bracket: await source.inspectAt({ ...request, seconds: 1.5 }),
    };
  });
  expect(result.anchor).toMatchObject({ status: 'ok', points: { speedMps: { value: 2, elapsedSeconds: 0 } } });
  expect(result.bracket).toMatchObject({ status: 'ok', points: { speedMps: { value: 1, elapsedSeconds: 0 } } });
});

it('resolves a sub-microsecond cursor at an original gap edge without lending into the gap', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 8, 8);
    const record = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 8 },
      new Date(base).toISOString(),
    );
    const rows = [2, 8, 9].map((time, sequence) => ({
      ...h.browserRow(time, sequence, new Date(base + time * 1000).toISOString()),
      recordingId: record.id,
      humanPowerW: time === 8 ? 160 : 100,
    }));
    await h.store.append(record.id, 'owner', rows, 9, 9, rows.at(-1)!.timestamp);
    const source = h.monitor(record.id);
    const request = {
      generation: 1,
      expectedRevision: (await source.describeSource({ generation: 1 })).revision,
      metrics: ['humanPowerW'],
    };
    return {
      edge: await source.inspectAt({ ...request, seconds: 7.999999855138079 }),
      gap: await source.inspectAt({ ...request, seconds: 7.99999 }),
      explicit: await source.inspectAt({
        ...request,
        seconds: 7.999999855138079,
        anchor: { metric: 'humanPowerW', observationId: `${record.id}:0` },
      }),
    };
  });
  expect(result.edge).toMatchObject({
    status: 'ok',
    points: { humanPowerW: { value: 160, elapsedSeconds: 8, timestamp: '2026-09-08T00:00:08.000Z' } },
  });
  expect(result.gap).toMatchObject({ status: 'ok', points: { humanPowerW: null } });
  expect(result.explicit).toMatchObject({ status: 'ok', points: { humanPowerW: null } });
});
it('uses capture-built statistics checkpoints and prepares distance only when requested', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const ride = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 8 },
      new Date(base).toISOString(),
    );
    for (let offset = 0; offset < 4096; offset += 64) {
      const rows = Array.from({ length: 64 }, (_, i) => ({
        ...h.browserRow((offset + i) / 8, offset + i, new Date(base + (offset + i) * 125).toISOString()),
        recordingId: ride.id,
        humanPowerW: 100,
      }));
      await h.store.append(
        ride.id,
        'owner',
        rows,
        rows.at(-1)!.elapsedSeconds,
        rows.at(-1)!.elapsedSeconds,
        rows.at(-1)!.timestamp,
      );
    }
    await h.store.transition(ride.id, 'owner', 'stop', 512, 512, new Date(base + 512000).toISOString());
    let originals = 0;
    const originalPage = h.store.page.bind(h.store),
      analysisPage = h.store.analysisPage.bind(h.store);
    h.store.page = async (...args) => {
      const rows = await originalPage(...args);
      originals += rows.length;
      return rows;
    };
    h.store.analysisPage = async (...args) => {
      const rows = await analysisPage(...args);
      if (args[0] === 'samples') originals += rows.length;
      return rows;
    };
    const source = h.monitor(ride.id);
    const description = await source.describeSource({ generation: 1 });
    const statistics = await source.rangeStats({
      generation: 1,
      expectedRevision: description.revision,
      metrics: ['humanPowerW'],
      startSeconds: 0,
      endSeconds: 512,
    });
    return { statistics, originals, distance: (await h.peekBrowserDistance(ride.id, 'auto')) !== undefined };
  });
  expect(result.statistics).toMatchObject({
    status: 'ok',
    statistics: { humanPowerW: { count: 4096, sampleMean: 100, coveredSeconds: 511.875 } },
  });
  expect(result.originals).toBeLessThanOrEqual(256);
  expect(result.distance).toBe(false);
}, 120000);

it('reuses prepared capture originals for each core statistics bucket', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const ride = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 8 },
      new Date(base).toISOString(),
    );
    const original = h.InspectionMetricBucket.prototype.append;
    let converted = 0;
    h.InspectionMetricBucket.prototype.append = function (...args) {
      converted++;
      return original.apply(this, args);
    };
    try {
      for (let offset = 0; offset < 128; offset += 64) {
        const rows = Array.from({ length: 64 }, (_, i) => ({
          ...h.browserRow((offset + i) / 8, offset + i, new Date(base + (offset + i) * 125).toISOString()),
          recordingId: ride.id,
          humanPowerW: 100,
        }));
        await h.store.append(
          ride.id,
          'owner',
          rows,
          rows.at(-1)!.elapsedSeconds,
          rows.at(-1)!.elapsedSeconds,
          rows.at(-1)!.timestamp,
        );
      }
    } finally {
      h.InspectionMetricBucket.prototype.append = original;
    }
    const source = h.monitor(ride.id);
    const description = await source.describeSource({ generation: 1 });
    return {
      converted,
      statistics: await source.rangeStats({
        generation: 1,
        expectedRevision: description.revision,
        metrics: ['humanPowerW'],
        startSeconds: 0,
        endSeconds: 16,
      }),
    };
  });
  expect(result.converted).toBe(0);
  expect(result.statistics).toMatchObject({
    status: 'ok',
    statistics: { humanPowerW: { count: 128, sampleMean: 100, coveredSeconds: 15.875 } },
  });
});

it('batches original distance interval writes through IndexedDB', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const ride = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 8 },
      new Date(base).toISOString(),
    );
    const rows = Array.from({ length: 128 }, (_, i) => ({
      ...h.browserRow(i / 8, i, new Date(base + i * 125).toISOString()),
      recordingId: ride.id,
      controllerSpeedMps: 5,
      speedRaw: 18,
      controllerModel: 'X6',
      controllerProtocol: '5.3',
      firmwareLabel: '20260101',
    }));
    for (let offset = 0; offset < rows.length; offset += 64) {
      const batch = rows.slice(offset, offset + 64);
      await h.store.append(
        ride.id,
        'owner',
        batch,
        batch.at(-1)!.elapsedSeconds,
        batch.at(-1)!.elapsedSeconds,
        batch.at(-1)!.timestamp,
      );
    }
    const original = IDBObjectStore.prototype.put;
    let pending = 0,
      peak = 0;
    IDBObjectStore.prototype.put = function (...args) {
      const request = original.apply(this, args);
      if (this.name === 'distance-intervals') {
        peak = Math.max(peak, ++pending);
        request.addEventListener('success', () => {
          pending--;
        });
        request.addEventListener('error', () => {
          pending--;
        });
      }
      return request;
    };
    try {
      const handle = await h.ensureBrowserDistance(ride.id, 'auto');
      return { peak, intervals: (await h.browserDistanceIntervals(handle, null, 256))!.length };
    } finally {
      IDBObjectStore.prototype.put = original;
    }
  });
  expect(result.peak).toBeGreaterThan(1);
  expect(result.intervals).toBe(127);
});

it('persists fractional live originals through a retained canonical encoder', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const ride = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 2 },
      new Date(base).toISOString(),
    );
    const original = h.InspectionCacheEncoder.prototype.statistics;
    let encoded = 0;
    h.InspectionCacheEncoder.prototype.statistics = function (...args) {
      encoded++;
      return original.apply(this, args);
    };
    const times = [0.1234567890123, 0.6234567890123];
    try {
      for (let index = 0; index < times.length; index++) {
        const seconds = times[index]!;
        const timestamp = new Date(base + seconds * 1000).toISOString();
        await h.store.append(
          ride.id,
          'owner',
          [{ ...h.browserRow(seconds, index, timestamp), recordingId: ride.id, humanPowerW: 100 + index }],
          seconds,
          seconds,
          timestamp,
        );
      }
      const source = h.monitor(ride.id);
      const description = await source.describeSource({ generation: 1 });
      return {
        encoded,
        times: (
          await source.readPlot({
            generation: 1,
            expectedRevision: description.revision,
            metrics: ['humanPowerW'],
            startSeconds: 0,
            endSeconds: 16,
            buckets: 16,
          })
        ).status,
        inspection: await source.inspectAt({
          generation: 1,
          expectedRevision: description.revision,
          metrics: ['humanPowerW'],
          seconds: times[1]!,
          anchor: { metric: 'humanPowerW', observationId: `${ride.id}:1` },
        }),
      };
    } finally {
      h.InspectionCacheEncoder.prototype.statistics = original;
    }
  });
  expect(result.encoded).toBeGreaterThan(0);
  expect(result.times).toBe('ok');
  expect(result.inspection).toMatchObject({
    status: 'ok',
    points: { humanPowerW: { value: 101, elapsedSeconds: 0.6234567890123 } },
  });
});

it('reuses core eligibility indexes while preserving complete warm equal-time anchors', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const ride = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 8 },
      new Date(base).toISOString(),
    );
    for (let offset = 0; offset < 128; offset += 64) {
      const rows = Array.from({ length: 64 }, (_, i) => ({
        ...h.browserRow((offset + i) / 8, offset + i, new Date(base + (offset + i) * 125).toISOString()),
        recordingId: ride.id,
        humanPowerW: 100,
      }));
      await h.store.append(
        ride.id,
        'owner',
        rows,
        rows.at(-1)!.elapsedSeconds,
        rows.at(-1)!.elapsedSeconds,
        rows.at(-1)!.timestamp,
      );
    }
    const equal = [50, 900].map((value, i) => ({
      ...h.browserRow(10.25, 128 + i, new Date(base + 10250).toISOString()),
      recordingId: ride.id,
      humanPowerW: value,
    }));
    const record = await h.store.append(ride.id, 'owner', equal, 15.875, 15.875, new Date(base + 15875).toISOString());
    const source = h.monitor(ride.id);
    const request = { generation: 1, expectedRevision: record.revision, metrics: ['humanPowerW'] };
    await source.inspectAt({ ...request, seconds: 10.03 });
    await source.inspectAt({ ...request, seconds: 10.155 });
    const original = h.InspectionOriginalInput.prototype.plotValid;
    let admissions = 0;
    h.InspectionOriginalInput.prototype.plotValid = function (...args) {
      admissions++;
      return original.apply(this, args);
    };
    try {
      const result = await source.inspectAt({
        ...request,
        seconds: 10.25,
        anchor: { metric: 'humanPowerW', observationId: `${ride.id}:128` },
      });
      return { admissions, result };
    } finally {
      h.InspectionOriginalInput.prototype.plotValid = original;
    }
  });
  expect(result.admissions).toBeLessThanOrEqual(4);
  expect(result.result).toMatchObject({ status: 'ok', points: { humanPowerW: { value: 50, elapsedSeconds: 10.25 } } });
});

it('rebuilds successor checkpoints and ancestors after an earlier original arrives', async () => {
  const results = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const results = [];
    for (const offset of [15, 63]) {
      const end = offset + 18;
      const ride = await h.store.begin(
        'owner',
        { indoor: true, recordGPS: false, sampleHz: 8 },
        new Date(base).toISOString(),
      );
      const rows = [offset, offset + 1, offset + 2, end].map((time, sequence) => ({
        ...h.browserRow(time, sequence, new Date(base + time * 1000).toISOString()),
        recordingId: ride.id,
        humanPowerW: 100,
      }));
      await h.store.append(ride.id, 'owner', rows, end, end, rows.at(-1)!.timestamp);
      const record = await h.store.append(
        ride.id,
        'owner',
        [
          {
            ...h.browserRow(offset + 0.5, 4, new Date(base + (offset + 0.5) * 1000).toISOString()),
            recordingId: ride.id,
            humanPowerW: 900,
          },
        ],
        end,
        end,
        rows.at(-1)!.timestamp,
      );
      const source = h.monitor(ride.id);
      results.push({
        offset,
        range: await source.rangeStats({
          generation: 1,
          expectedRevision: record.revision,
          metrics: ['humanPowerW'],
          startSeconds: 0,
          endSeconds: end,
        }),
        parent: await h.browserStatistics(record, h.store, -1, 2048, ['humanPowerW']),
      });
      await h.store.transition(ride.id, 'owner', 'stop', end, end, rows.at(-1)!.timestamp);
    }
    return results;
  });
  for (const result of results) {
    expect
      .soft(result.range, `leaf successor after ${result.offset}`)
      .toMatchObject({ status: 'ok', statistics: { humanPowerW: { count: 5, integral: 600, coveredSeconds: 2 } } });
    expect
      .soft(result.parent, `parent successor after ${result.offset}`)
      .toMatchObject({ humanPowerW: { count: 5, integral: 600, coveredSeconds: 2 } });
  }
});

it('falls back to originals when an interior statistics checkpoint cannot decode', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const ride = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 8 },
      new Date(base).toISOString(),
    );
    const rows = [15, 16, 17, 33].map((time, sequence) => ({
      ...h.browserRow(time, sequence, new Date(base + time * 1000).toISOString()),
      recordingId: ride.id,
      humanPowerW: 100,
    }));
    const record = await h.store.append(ride.id, 'owner', rows, 33, 33, rows.at(-1)!.timestamp);
    await h.browserTransaction(['ride-tiles'], 'readwrite', async tx => {
      const store = tx.objectStore('ride-tiles');
      const tiles = await h.idbRequest<{ stats: { humanPowerW: { checkpoint: string } } }[]>(store.getAll());
      for (const tile of tiles) {
        tile.stats.humanPowerW.checkpoint = '{"damaged":true}';
        await h.idbRequest(store.put(tile));
      }
    });
    return h.monitor(ride.id).rangeStats({
      generation: 1,
      expectedRevision: record.revision,
      metrics: ['humanPowerW'],
      startSeconds: 0,
      endSeconds: 33,
    });
  });
  expect(result).toMatchObject({
    status: 'ok',
    statistics: { humanPowerW: { count: 4, integral: 200, coveredSeconds: 2 } },
  });
});

it('retains supported distance boundaries in their owning tiles and raw fallbacks', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const ride = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 8 },
      new Date(base).toISOString(),
    );
    const rows = [15.875, 16.125].map((time, sequence) => ({
      ...h.browserRow(time, sequence, new Date(base + time * 1000).toISOString()),
      recordingId: ride.id,
      controllerSpeedMps: 5,
      speedRaw: 18,
      controllerModel: 'X6',
      controllerProtocol: '5.3',
      firmwareLabel: '20260101',
    }));
    await h.store.append(ride.id, 'owner', rows, 32, 32, new Date(base + 32000).toISOString());
    const handle = await h.ensureBrowserDistance(ride.id, 'controller');
    const read = async () =>
      Promise.all(
        [
          [0, 32],
          [16, 16.0625],
          [0, 256],
        ].map(([start, end]) => h.plotBrowserDistance(handle, start!, end!, 32)),
      );
    const cached = await read();
    await h.browserTransaction(['distance-tiles'], 'readwrite', tx =>
      h.idbRequest(tx.objectStore('distance-tiles').clear()),
    );
    return { cached, raw: await read() };
  });
  for (const plots of [result.cached, result.raw])
    for (const points of plots) {
      expect(points.map(point => point.elapsedSeconds)).toEqual([15.875, 16.125]);
      expect(points.map(point => point.value)).toEqual([0, 1.25]);
      expect(points.map(point => point.startsSegment)).toEqual([true, false]);
    }
});

it('falls back to supported distance originals when all derived tile payloads are damaged', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const ride = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 8 },
      new Date(base).toISOString(),
    );
    const rows = [15.875, 16.125].map((time, sequence) => ({
      ...h.browserRow(time, sequence, new Date(base + time * 1000).toISOString()),
      recordingId: ride.id,
      controllerSpeedMps: 5,
      speedRaw: 18,
      controllerModel: 'X6',
      controllerProtocol: '5.3',
      firmwareLabel: '20260101',
    }));
    await h.store.append(ride.id, 'owner', rows, 32, 32, new Date(base + 32000).toISOString());
    const handle = await h.ensureBrowserDistance(ride.id, 'controller');
    await h.browserTransaction(['distance-tiles'], 'readwrite', async tx => {
      const store = tx.objectStore('distance-tiles');
      const tiles = await h.idbRequest<{ plot: string }[]>(store.getAll());
      for (const tile of tiles) {
        tile.plot = '{"damaged":true}';
        await h.idbRequest(store.put(tile));
      }
    });
    return Promise.all([32, 256].map(end => h.plotBrowserDistance(handle, 0, end, 32)));
  });
  for (const points of result) expect(points.map(point => point.elapsedSeconds)).toEqual([15.875, 16.125]);
});

it('validates warm pinned inspection once and cancels a revision changed at publication', async () => {
  const result = await run(async h => {
    const base = Date.UTC(2026, 0, 1);
    const ride = await h.store.begin(
      'owner',
      { indoor: true, recordGPS: false, sampleHz: 8 },
      new Date(base).toISOString(),
    );
    const rows = Array.from({ length: 128 }, (_, sequence) => ({
      ...h.browserRow(sequence / 8, sequence, new Date(base + sequence * 125).toISOString()),
      recordingId: ride.id,
      humanPowerW: 100,
    }));
    for (let offset = 0; offset < rows.length; offset += 64) {
      const batch = rows.slice(offset, offset + 64),
        tail = batch.at(-1)!;
      await h.store.append(ride.id, 'owner', batch, tail.elapsedSeconds, tail.elapsedSeconds, tail.timestamp);
    }
    const source = h.monitor(ride.id),
      description = await source.describeSource({ generation: 1 });
    const request = { generation: 1, expectedRevision: description.revision, metrics: ['humanPowerW'], seconds: 10.03 };
    await source.inspectAt(request);
    await source.inspectAt({ ...request, seconds: 10.155 });
    const get = h.store.get.bind(h.store);
    let reads = 0,
      change = false;
    h.store.get = async (...args) => {
      reads++;
      if (change) {
        change = false;
        const row = {
          ...h.browserRow(16, 128, new Date(base + 16000).toISOString()),
          recordingId: ride.id,
          humanPowerW: 900,
        };
        await h.store.append(ride.id, 'owner', [row], 16, 16, row.timestamp);
      }
      return get(...args);
    };
    try {
      const accepted = await source.inspectAt({ ...request, seconds: 10.28 });
      const warmReads = reads;
      change = true;
      const stale = await source.inspectAt({ ...request, seconds: 10.405 });
      const freshDescription = await source.describeSource({ generation: 2 });
      const fresh = await source.inspectAt({
        ...request,
        generation: 2,
        expectedRevision: freshDescription.revision,
        seconds: 16,
        anchor: { metric: 'humanPowerW', observationId: `${ride.id}:128` },
      });
      return { warmReads, accepted, stale, fresh };
    } finally {
      h.store.get = get;
    }
  });
  expect(result.warmReads).toBe(1);
  expect(result.accepted).toMatchObject({
    status: 'ok',
    points: { humanPowerW: { value: 100, elapsedSeconds: 10.25 } },
  });
  expect(result.stale.status).toBe('retry');
  expect(result.fresh).toMatchObject({ status: 'ok', points: { humanPowerW: { value: 900, elapsedSeconds: 16 } } });
});
