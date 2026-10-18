import { afterAll, beforeAll, expect, it } from 'vitest';
import { chromium, type Browser, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import { browserAnalyticsFixtures, approvedChartOverrides } from '../fixtures/analytics-fixtures';
import type * as Harness from '../fixtures/browser-ride-harness';
declare global {
  interface Window {
    webCoreTests: typeof Harness;
  }
}
const bundle = browserTestBundle('tests/fixtures/browser-ride-harness.ts', 'webCoreTests');
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ headless: true });
});
afterAll(async () => {
  await browser?.close();
});
async function run<T>(work: (page: Page) => Promise<T>): Promise<T> {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.route('http://127.0.0.1:43170/**', route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Web core</title>' }),
    );
    await page.goto('http://127.0.0.1:43170/');
    await page.addScriptTag({ content: bundle });
    return await work(page);
  } finally {
    await context.close();
  }
}
for (const fixture of browserAnalyticsFixtures)
  it(
    `composes the browser store contract fixture: ${fixture.name}`,
    async () =>
      run(async page => {
        const result = await page.evaluate(async fixture => {
          const api = window.webCoreTests;
          let record = await api.store.begin(
            'owner',
            { indoor: fixture.input.create.indoor, recordGPS: fixture.input.create.recordGPS, sampleHz: 4 },
            fixture.input.create.startedAt,
          );
          let sequence = 0,
            browserSequence = 2;
          let extraLifecycle = 2;
          const rejected: string[] = [];
          let timer = 0,
            previous = 0,
            running = true;
          for (const event of fixture.input.events) {
            const elapsed = event.elapsedSeconds;
            if (running) timer += elapsed - previous;
            previous = elapsed;
            if (event.kind === 'lifecycle') {
              const action = event.payload.action as 'start' | 'pause' | 'resume' | 'lap' | 'stop';
              if (action === 'start') {
                extraLifecycle--;
                continue;
              }
              if (action === 'lap' && !running) {
                const error = await api.store
                  .transition(record.id, 'owner', action, elapsed, timer, event.timestamp)
                  .then(
                    () => null,
                    error => error.message,
                  );
                if (typeof error !== 'string' || !error.includes('unavailable'))
                  throw new Error('Paused Lap was not rejected');
                rejected.push(event.eventId);
                extraLifecycle--;
                continue;
              }
              record = await api.store.transition(record.id, 'owner', action, elapsed, timer, event.timestamp);
              browserSequence = Number(await api.store.producerHighWater(record.id, 'browser')) + 1;
              if (action === 'stop') extraLifecycle--;
              if (action === 'pause') running = false;
              if (action === 'resume') running = true;
              if (action === 'stop') running = false;
            } else if (event.kind === 'telemetry') {
              const row = {
                ...api.browserRow(elapsed, sequence++),
                humanPowerW: -1,
                cadenceRpm: 255,
                ...event.payload,
                timestamp: event.timestamp,
                recordingId: record.id,
                eventId: event.eventId,
                queryUs: api.timestampUs(event.timestamp),
                active: running,
              };
              record = await api.store.append(record.id, 'owner', [row], elapsed, timer, event.timestamp);
            } else {
              const row = {
                ...event.payload,
                recordingId: record.id,
                eventId: event.eventId,
                producer: 'browser' as const,
                producerSequence: String(browserSequence++),
                elapsedUs: api.secondsUs(elapsed),
                queryUs: api.timestampUs(event.timestamp),
                elapsedSeconds: elapsed,
                timestamp: event.timestamp,
                originalTimestamp: event.timestamp,
                originalElapsedSeconds: elapsed,
                acquiredAtSeconds: elapsed,
                clockEpoch: 'fixture',
                active: running,
                interval: 0,
                distanceBarrier: false,
                originalAltitudeDatum: 'msl' as const,
              };
              record = await api.store.appendLocations(
                record.id,
                'owner',
                [row as Parameters<typeof api.store.appendLocations>[2][number]],
                elapsed,
                timer,
                event.timestamp,
              );
            }
          }
          if (!record.endedAt)
            record = await api.store.transition(
              record.id,
              'owner',
              'stop',
              fixture.input.stopElapsedSeconds,
              timer + (running ? fixture.input.stopElapsedSeconds - previous : 0),
              fixture.input.endedAt,
            );
          const recorder = api.createRecorder().recorder;
          const detail = await recorder.read(record.id);
          const chart = api.monitor(record.id),
            description = await chart.describeSource({ generation: 1 });
          const statistics = [];
          for (const query of fixture.input.queries)
            statistics.push(
              await chart.rangeStats({ ...query, generation: 1, expectedRevision: description.revision }),
            );
          const originals = await api.store.analysisPage('ride-lifecycle', record.id, null, record.revision);
          return { detail, statistics, extraLifecycle, originals, rejected };
        }, fixture);
        for (const event of fixture.input.events.filter(event => event.kind === 'lifecycle')) {
          if (result.rejected.includes(event.eventId)) {
            expect(event.payload.action).toBe('lap');
            expect(event.elapsedSeconds).toBe(2);
            continue;
          }
          const original = result.originals.find(
            row =>
              'action' in row && row.action === event.payload.action && row.elapsedSeconds === event.elapsedSeconds,
          );
          expect(original).toMatchObject({
            action: event.payload.action,
            timestamp: event.timestamp,
            elapsedSeconds: event.elapsedSeconds,
          });
          expect(original).not.toHaveProperty('actionRequest');
        }
        const summary = result.detail.summary as unknown as Record<string, unknown>;
        for (const [field, expected] of Object.entries(fixture.expectedSummary)) {
          if (
            field === 'id' ||
            field === 'provenance' ||
            field === 'distance' ||
            field === 'routePreview' ||
            field === 'completeness'
          )
            continue;
          if (field === 'eventCount')
            expect(summary[field]).toBe((BigInt(expected as string) + BigInt(result.extraLifecycle)).toString());
          else if (['telemetryCount', 'locationCount', 'healthCount'].includes(field))
            expect(summary[field]).toBe(String(expected));
          else if (field === 'lapCount' && fixture.name === 'laps-and-pause-boundary') expect(summary[field]).toBe(1);
          else if (typeof expected === 'number')
            expect(summary[field], `${fixture.name}.${field}`).toBeCloseTo(expected, 5);
          else expect(summary[field], `${fixture.name}.${field}`).toEqual(expected);
        }
        for (const field of fixture.expectedAbsentSummaryFields) expect(summary).not.toHaveProperty(field);
        expect(summary.completeness).toEqual(fixture.expectedSummary.completeness);
        expect(summary.provenance).toEqual(fixture.expectedSummary.provenance);
        expect(summary.distance).toEqual(fixture.expectedSummary.distance);
        const route = fixture.expectedSummary.routePreview;
        if (Array.isArray(route)) expect(summary.routePreview).toEqual(route);
        const overrides = approvedChartOverrides[fixture.name] ?? [];
        expect(overrides).toHaveLength(fixture.input.queries.length);
        for (const [index, values] of overrides.entries()) {
          const actual = result.statistics[index]!;
          if (actual.status !== 'ok') throw new Error('Unexpected revision');
          for (const [metric, fields] of Object.entries(values))
            for (const [field, expected] of Object.entries(fields)) {
              const stats = actual.statistics[metric] as unknown as Record<string, unknown>;
              if (field === 'min' || field === 'max') {
                const point = expected as Record<string, unknown>;
                const eventId = String(point.observationId).replace(/^event:/, '');
                const event = fixture.input.events.find(event => event.eventId === eventId);
                if (!event) throw new Error('Missing expected extrema original');
                const sequence = fixture.input.events
                  .filter(event => event.kind === 'telemetry')
                  .findIndex(event => event.eventId === eventId);
                const observationId =
                  event.kind === 'telemetry'
                    ? `${result.detail.metadata.id}:${sequence}`
                    : `${result.detail.metadata.id}:${eventId}`;
                expect(stats[field]).toMatchObject({ ...point, observationId });
              } else expect(stats[field], `${fixture.name}.${metric}.${field}`).toEqual(expected);
            }
        }
      }),
    60000,
  );

it('commits delayed GPS originals behind a telemetry checkpoint without changing their acquisition time', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.webCoreTests;
      let record = await api.store.begin(
        'owner',
        { indoor: false, recordGPS: true, sampleHz: 4 },
        '2026-01-01T00:00:00Z',
      );
      record = await api.store.append(
        record.id,
        'owner',
        [{ ...api.browserRow(5, 0), recordingId: record.id }],
        5,
        5,
        '2026-01-01T00:00:05Z',
      );
      const location = {
        recordingId: record.id,
        eventId: crypto.randomUUID(),
        producer: 'browser' as const,
        producerSequence: '2',
        elapsedUs: '2000000',
        queryUs: api.timestampUs('2026-01-01T00:00:02Z'),
        elapsedSeconds: 2,
        originalElapsedSeconds: 2,
        timestamp: '2026-01-01T00:00:02Z',
        originalTimestamp: '2026-01-01T00:00:02Z',
        acquiredAtSeconds: 2,
        clockEpoch: 'fixture',
        active: true,
        interval: 0,
        distanceBarrier: false,
        latitude: 0,
        longitude: 0,
        horizontalAccuracyM: 5,
        originalAltitudeDatum: 'wgs84Ellipsoid' as const,
      };
      record = await api.store.appendLocations(record.id, 'owner', [location], 6, 6, '2026-01-01T00:00:06Z');
      return { record, locations: (await api.store.locationsAfter(record.id, null, 256)).rows };
    });
    expect(result.record).toMatchObject({ elapsedSeconds: 6, timerSeconds: 6, telemetryCount: 1, locationCount: 1 });
    expect(result.locations).toHaveLength(1);
    expect(result.locations[0]).toMatchObject({
      elapsedSeconds: 2,
      originalTimestamp: '2026-01-01T00:00:02Z',
      acquiredAtSeconds: 2,
    });
  }));

it('reads a closed GPS instant and exhausts empty windows without widening the scan', async () =>
  run(async page => {
    const result = await page.evaluate(async () => {
      const api = window.webCoreTests;
      let record = await api.store.begin(
        'owner',
        { indoor: false, recordGPS: true, sampleHz: 4 },
        '2026-01-01T00:00:00Z',
      );
      const rows = [2, 2, 4].map((elapsed, index) => {
        const timestamp = new Date(Date.UTC(2026, 0, 1) + elapsed * 1000).toISOString();
        return {
          recordingId: record.id,
          eventId: crypto.randomUUID(),
          producer: 'browser' as const,
          producerSequence: String(index + 2),
          elapsedUs: api.secondsUs(elapsed),
          queryUs: api.timestampUs(timestamp),
          timestamp,
          originalTimestamp: timestamp,
          elapsedSeconds: elapsed,
          originalElapsedSeconds: elapsed,
          acquiredAtSeconds: elapsed,
          clockEpoch: 'fixture',
          active: true,
          interval: 0,
          distanceBarrier: false,
          latitude: 0,
          longitude: index * 0.00001,
          horizontalAccuracyM: 5,
          originalAltitudeDatum: 'unknown' as const,
        };
      });
      await api.store.appendLocations(record.id, 'owner', rows, 4, 4, '2026-01-01T00:00:04Z');
      record = await api.store.transition(record.id, 'owner', 'stop', 4, 4, '2026-01-01T00:00:04Z');
      const read = (start: string, end: string, after: Parameters<typeof api.store.locationPage>[3] = null) =>
        api.store.locationPage(record.id, start, end, after, record.revision);
      const exact = await read('2000000', '2000000');
      const tail = exact.at(-1)!;
      return {
        exact: exact.map(row => row.eventId),
        expected: rows.slice(0, 2).map(row => row.eventId),
        exhausted: await read('2000000', '2000000', [tail.elapsedUs, tail.producer, tail.producerSequence]),
        past: await read('2000000', '2000000', ['4000000', 'browser', '4']),
        reversed: await read('4000000', '2000000'),
        absent: await read('3000000', '3000000'),
      };
    });
    expect(result.exact).toEqual(result.expected);
    expect(result.exhausted).toEqual([]);
    expect(result.past).toEqual([]);
    expect(result.reversed).toEqual([]);
    expect(result.absent).toEqual([]);
  }));
