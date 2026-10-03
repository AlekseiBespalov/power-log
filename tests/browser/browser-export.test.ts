import { readFileSync } from 'node:fs';
import { inflateRawSync, crc32 as zlibCrc32 } from 'node:zlib';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { chromium, type Browser, type Download, type Page } from '@playwright/test';
import { browserTestBundle } from '../helpers/browser-test-bundle';
import type * as Harness from '../fixtures/browser-export-harness';
import { PROJECTIONS } from '../../src/core/export/catalog';
import type {
  ExportContext,
  ExportFileKind,
  ExportKind,
  ProjectionColumn,
  ProjectionName,
} from '../../src/core/export/types';

declare global {
  interface Window {
    exportTests: typeof Harness;
  }
}
const ORIGIN = 'http://127.0.0.1:43131';
const bundle = browserTestBundle('tests/fixtures/browser-export-harness.ts', 'exportTests');
const DAY = 24 * 60 * 60 * 1000;
const MiB = 1024 * 1024;
const at = (seconds: number) => new Date(Date.UTC(2026, 0, 1) + seconds * 1000).toISOString();
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
    await page.route(`${ORIGIN}/**`, route =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Browser export</title>' }),
    );
    await page.goto(`${ORIGIN}/`);
    await page.addScriptTag({ content: bundle });
    return await work(page);
  } finally {
    await context.close();
  }
}
async function withDownload<T>(page: Page, work: () => Promise<T>): Promise<{ result: T; download: Download }> {
  const [download, result] = await Promise.all([page.waitForEvent('download'), work()]);
  return { result, download };
}
const fileOf = async (download: Download) => readFileSync((await download.path())!);
const decode = (base64: string) => Buffer.from(base64, 'base64');

const TELEMETRY_ZIP = [
  'elapsedSeconds',
  'timestamp',
  'active',
  'interval',
  'connection',
  'humanPowerW',
  'cadenceRpm',
  'motorInputPowerW',
  'batteryVoltageV',
  'batteryCurrentA',
  'motorCurrentA',
  'motorRpm',
  'pedalTorqueNm',
  'controllerTempC',
  'motorTempC',
  'consumedAh',
  'consumedWh',
  'throttleVoltageV',
  'faultCode',
  'assistLevel',
  'controllerSpeedMps',
  'raceMode',
  'speedRaw',
];
const ZIP_ONLY = ['timestamp', 'throttleVoltageV', 'faultCode', 'controllerSpeedMps', 'raceMode', 'speedRaw'];
const TELEMETRY_FIT = TELEMETRY_ZIP.filter(name => !ZIP_ONLY.includes(name));
const LIFECYCLE_ZIP = ['elapsedSeconds', 'timestamp', 'producer', 'action'];
const DISTANCE_FIT = ['start', 'end', 'meters', 'segment', 'startSpeed', 'endSpeed'];
const sorted = (names: readonly string[]) => [...names].sort();
function catalogColumns(projection: ProjectionName, kind: ExportKind): string[] {
  const consumers = kind === 'zip' ? ['zip'] : ['fit', 'discovery'];
  const columns: readonly ProjectionColumn[] = PROJECTIONS[projection].columns;
  return columns
    .filter(column => column.platforms.includes('web') && column.consumers.some(item => consumers.includes(item)))
    .map(column => column.name);
}
function telemetryColumns(rows: readonly object[], names: readonly string[]) {
  const value = (row: Record<string, unknown>, name: string) => {
    if (name === 'active') return row.active ? 1 : 0;
    if (name === 'connection') return row.connectionEpoch;
    if (name === 'controllerSpeedMps') return row.controllerSpeedMps ?? NaN;
    return row[name];
  };
  return Object.fromEntries(names.map(name => [name, rows.map(row => value(row as Record<string, unknown>, name))]));
}
function joined(pages: { columns: Record<string, unknown[]> }[]): Record<string, unknown[]> {
  const result: Record<string, unknown[]> = {};
  for (const page of pages)
    for (const [name, values] of Object.entries(page.columns)) (result[name] ??= []).push(...values);
  return result;
}
function increasing(cursors: (number[] | null)[]): boolean {
  return cursors.every((cursor, index) => {
    if (!cursor) return false;
    if (index === 0) return true;
    const previous = cursors[index - 1]!;
    for (let i = 0; i < cursor.length; i++) if (cursor[i] !== previous[i]) return cursor[i]! > previous[i]!;
    return false;
  });
}
function zipLocalHeader(crc: number, compressed: number, size: number): Buffer {
  const header = Buffer.alloc(30);
  header.writeUInt32LE(0x04034b50, 0);
  header.writeUInt32LE(crc, 14);
  header.writeUInt32LE(compressed, 18);
  header.writeUInt32LE(size, 22);
  return header;
}

describe('browser export source over the real IndexedDB store', () => {
  it('admits only saved rides and returns their frozen metadata', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.exportTests;
        const context: ExportContext = { exportedAt: '2026-02-01T00:00:00.000Z', platform: 'web' };
        const source = new api.BrowserExportSource();
        const attempt = (rideId: string, extra: Record<string, unknown> = {}) =>
          api.failure(() => source.open({ rideId, kind: 'zip', context, ...extra }));
        const ride = await api.store.begin('owner', true, api.at(0));
        const running = await attempt(ride.id);
        await api.store.transition(ride.id, 'owner', 'pause', 5, 5, api.at(5));
        const paused = await attempt(ride.id);
        await api.store.transition(ride.id, 'owner', 'save', 9, 5, api.at(9));
        const zip = await source.open({ rideId: ride.id, kind: 'zip', context });
        const fit = await source.open({ rideId: ride.id, kind: 'fit', context });
        const recovered = await api.store.begin('second', false, api.at(20));
        await api.store.append(
          recovered.id,
          'second',
          [{ ...api.browserRow(3, 0, api.at(23)), recordingId: recovered.id }],
          3,
          2,
          api.at(23),
        );
        await api.store.recoverOrphan();
        const interrupted = await source.open({ rideId: recovered.id, kind: 'zip', context });
        return {
          running,
          paused,
          zip,
          fit,
          interrupted,
          interruptedEvents: await api.pages(source, interrupted.session, 'lifecycle'),
          missing: await attempt('missing-ride'),
          csv: await attempt(ride.id, { kind: 'csv' }),
          phone: await attempt(ride.id, { context: { ...context, platform: 'ios' } }),
          distance: await attempt(ride.id, { kind: 'fit', distanceSource: 'satellite' }),
          sessions: new Set([zip.session, fit.session, interrupted.session]).size,
        };
      });
      expect(result.running).toEqual({ code: 'gate', message: 'Save the ride before exporting it.' });
      expect(result.paused).toMatchObject({ code: 'gate' });
      expect(result.missing).toMatchObject({ code: 'deleted' });
      expect(result.csv).toMatchObject({ code: 'unsupported' });
      expect(result.phone).toMatchObject({ code: 'unsupported' });
      expect(result.distance).toMatchObject({ code: 'unsupported' });
      expect(result.sessions).toBe(3);
      expect(result.zip.elapsedEnd).toBe(9);
      expect(result.zip.producers).toEqual({ gps: [], health: [] });
      expect(result.zip.distanceProfile).toBeNull();
      expect(result.zip.metadata).toEqual({
        startedAt: at(0),
        endedAt: at(9),
        ownerTiming: { timestamp: at(9), elapsedSeconds: 9, timerSeconds: 5 },
        indoor: true,
        interrupted: false,
        watchEnabled: false,
        saveToHealth: false,
        recordGPS: false,
        health: { provider: null, state: 'notRequested', workoutUUID: null, export: null },
        watchSyncState: 'notRequired',
        finalizationState: 'complete',
        example: false,
        sampleHz: null,
      });
      expect(result.fit).toMatchObject({ elapsedEnd: 9, distanceProfile: null, metadata: result.zip.metadata });
      expect(result.interrupted).toMatchObject({
        elapsedEnd: 3,
        metadata: {
          startedAt: at(20),
          endedAt: at(23),
          ownerTiming: { timestamp: at(23), elapsedSeconds: 3, timerSeconds: 2 },
          indoor: false,
          interrupted: true,
        },
      });
      expect(result.interruptedEvents.flatMap(page => page.columns.action)).toEqual(['start', 'interrupted']);
    }));

  it('pages telemetry at the 4096-row ceiling with the web catalog columns and per-pass connections', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.exportTests;
        const context: ExportContext = { exportedAt: '2026-02-01T00:00:00.000Z', platform: 'web' };
        const first = { controllerModel: 'X6', firmwareLabel: '20260101', controllerProtocol: '5.3' };
        const second = { controllerModel: 'X12', firmwareLabel: '20260102', controllerProtocol: '6.1' };
        const elapsed = (index: number) => (index < 4094 ? index : index < 4098 ? 4093 : index - 4) * 0.125;
        const row = (index: number) => ({
          elapsedSeconds: elapsed(index),
          humanPowerW: index,
          active: index < 1000 || index >= 1500,
          interval: index < 1500 ? 0 : 1,
          ...(index < 4098
            ? { ...first, connectionEpoch: 'connection-a', controllerSpeedMps: 5, speedRaw: 18 }
            : { ...second, connectionEpoch: 'connection-b' }),
        });
        const range = (from: number, to: number) => Array.from({ length: to - from }, (_, i) => row(from + i));
        const ride = await api.seedRide([
          { rows: range(0, 1000) },
          { action: 'pause', elapsed: 124.95, timer: 124.95 },
          { rows: range(1000, 1500) },
          { action: 'resume', elapsed: 187.45, timer: 124.95 },
          { rows: range(1500, 4100) },
          { action: 'save', elapsed: 600, timer: 537.5 },
        ]);
        const source = new api.BrowserExportSource();
        const zip = await source.open({ rideId: ride.id, kind: 'zip', context });
        const fit = await source.open({ rideId: ride.id, kind: 'fit', context });
        const zipPages = await api.pages(source, zip.session, 'telemetry');
        const again = await source.page({ session: zip.session, projection: 'telemetry', after: null });
        return {
          stored: await api.storedRows(ride.id),
          zipPages,
          fitPages: await api.pages(source, fit.session, 'telemetry'),
          again: { rows: again.rows, connections: again.connections },
        };
      });
      expect(result.stored).toHaveLength(4100);
      const [first, second] = result.zipPages;
      expect(result.zipPages).toHaveLength(2);
      expect([first!.rows, first!.done, first!.last]).toEqual([4096, false, [4093 * 0.125, 4095]]);
      expect([second!.rows, second!.done, second!.last]).toEqual([4, true, [4095 * 0.125, 4099]]);
      expect(first!.connections).toEqual([
        { token: 'connection-a', vendor: 'cyc', model: 'X6', firmware: '20260101', protocol: '5.3' },
      ]);
      expect(second!.connections).toEqual([
        { token: 'connection-b', vendor: 'cyc', model: 'X12', firmware: '20260102', protocol: '6.1' },
      ]);
      expect(result.again).toEqual({ rows: 4096, connections: first!.connections });
      for (const [pages, names, kind] of [
        [result.zipPages, TELEMETRY_ZIP, 'zip'],
        [result.fitPages, TELEMETRY_FIT, 'fit'],
      ] as const) {
        expect(sorted(names)).toEqual(sorted(catalogColumns('telemetry', kind)));
        for (const page of pages) {
          expect(sorted(Object.keys(page.columns))).toEqual(sorted(names));
          for (const [name, buffer] of Object.entries(page.buffers))
            expect(buffer, name).toEqual([0, page.rows * 8, page.rows * 8]);
        }
        expect(joined(pages)).toEqual(telemetryColumns(result.stored, names));
      }
      expect(result.fitPages.map(page => [page.rows, page.done, page.last, page.connections])).toEqual(
        result.zipPages.map(page => [page.rows, page.done, page.last, page.connections]),
      );
      const columns = joined(result.zipPages);
      expect(columns.active!.slice(998, 1002)).toEqual([1, 1, 0, 0]);
      expect(columns.interval!.slice(1498, 1502)).toEqual([0, 0, 1, 1]);
      expect(columns.controllerSpeedMps!.slice(4096)).toEqual([5, 5, NaN, NaN]);
      expect(columns.connection!.slice(4096)).toEqual(['connection-a', 'connection-a', 'connection-b', 'connection-b']);
      expect(columns.humanPowerW).toEqual(Array.from({ length: 4100 }, (_, index) => index));
    }));

  it('splits equal-elapsed groups across any row or byte ceiling without skipping or repeating rows', async () =>
    run(async page => {
      const groups = [3, 1, 2, 5, 1, 1, 4, 2, 6, 1, 3, 2, 1, 5];
      const result = await page.evaluate(async groups => {
        const api = window.exportTests;
        const context: ExportContext = { exportedAt: '2026-02-01T00:00:00.000Z', platform: 'web' };
        const rows = groups
          .flatMap((count, group) => Array.from({ length: count }, () => group * 0.5))
          .map((elapsedSeconds, index) => ({ elapsedSeconds, humanPowerW: 100 + index }));
        const ride = await api.seedRide([{ rows }, { action: 'save', elapsed: 10 }]);
        const rowBytes = 21 * 8 + api.at(0).length + 'test-epoch'.length;
        const read = async (limits: { maxRows?: number; maxBytes?: number }) => {
          const source = new api.BrowserExportSource(limits);
          const { session } = await source.open({ rideId: ride.id, kind: 'zip', context });
          return api.pages(source, session, 'telemetry');
        };
        const tiny = new api.BrowserExportSource({ maxBytes: rowBytes - 1 });
        const { session } = await tiny.open({ rideId: ride.id, kind: 'zip', context });
        return {
          bySize: {
            1: await read({ maxRows: 1 }),
            2: await read({ maxRows: 2 }),
            17: await read({ maxRows: 17 }),
            4096: await read({}),
          },
          byBytes: [await read({ maxBytes: 3 * rowBytes }), await read({ maxBytes: 4 * rowBytes - 1 })],
          oversized: await api.failure(() => tiny.page({ session, projection: 'telemetry', after: null })),
          invalid: await api.failure(async () => new api.BrowserExportSource({ maxRows: 4097 })),
        };
      }, groups);
      const expected: number[][] = [];
      groups.forEach((count, group) => {
        for (let i = 0; i < count; i++) expected.push([group * 0.5, expected.length]);
      });
      const cases = [
        ...Object.entries(result.bySize).map(([size, pages]) => [Number(size), pages] as const),
        ...result.byBytes.map(pages => [3, pages] as const),
      ];
      for (const [size, pages] of cases) {
        expect(pages).toHaveLength(Math.ceil(expected.length / size));
        expect(pages.map(page => page.rows)).toEqual(
          pages.map((_, index) => Math.min(size, expected.length - index * size)),
        );
        expect(pages.map(page => page.done)).toEqual(pages.map((_, index) => index === pages.length - 1));
        expect(pages.map(page => page.last)).toEqual(
          pages.map((_, index) => expected[Math.min(expected.length, (index + 1) * size) - 1]),
        );
        expect(increasing(pages.map(page => page.last))).toBe(true);
        const columns = joined(pages);
        expect(columns.elapsedSeconds!.map((elapsed, index) => [elapsed, columns.humanPowerW![index]])).toEqual(
          expected.map(([elapsed, sequence]) => [elapsed, 100 + sequence!]),
        );
      }
      expect(result.oversized).toMatchObject({ code: 'limit' });
      expect(result.invalid).toMatchObject({ message: 'Invalid export page limit' });
    }));

  it('pages lifecycle by revision and the controller distance profile by end', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.exportTests;
        const context: ExportContext = { exportedAt: '2026-02-01T00:00:00.000Z', platform: 'web' };
        const identity = { controllerModel: 'X6', firmwareLabel: '20260101', controllerProtocol: '5.3' };
        const moving = (second: number, interval: number) => {
          const speed = 4 + (second % 5);
          return { elapsedSeconds: second, ...identity, controllerSpeedMps: speed, speedRaw: speed * 3.6, interval };
        };
        const ride = await api.seedRide([
          { rows: Array.from({ length: 10 }, (_, second) => moving(second, 0)) },
          { action: 'pause', elapsed: 9.5, timer: 9.5 },
          { rows: [10, 11].map(second => ({ ...moving(second, 0), active: false })) },
          { action: 'resume', elapsed: 11.5, timer: 9.5 },
          { rows: [12, 13].map(second => moving(second, 1)) },
          { action: 'lap', elapsed: 13.5, timer: 11.5 },
          { rows: [14, 15].map(second => moving(second, 1)) },
          { action: 'save', elapsed: 16, timer: 14 },
        ]);
        const events = await api.browserTransaction(['ride-lifecycle'], 'readonly', tx =>
          api.idbRequest(tx.objectStore('ride-lifecycle').getAll(IDBKeyRange.bound([ride.id], [ride.id, []]))),
        );
        const one = new api.BrowserExportSource({ maxRows: 1 });
        const five = new api.BrowserExportSource({ maxRows: 5 });
        const fit = await five.open({ rideId: ride.id, kind: 'fit', context });
        const zip = await one.open({ rideId: ride.id, kind: 'zip', context });
        const single = await one.open({ rideId: ride.id, kind: 'fit', context });
        const gps = await five.open({ rideId: ride.id, kind: 'fit', context, distanceSource: 'gps:phone' });
        const chosen = await five.open({ rideId: ride.id, kind: 'fit', context, distanceSource: 'controller' });
        const still = await api.seedRide([
          { rows: [{ elapsedSeconds: 0 }, { elapsedSeconds: 1 }] },
          { action: 'save', elapsed: 2 },
        ]);
        const unmeasured = await five.open({ rideId: still.id, kind: 'fit', context });
        return {
          events: events as { revision: number; action: string; elapsedSeconds: number; timestamp: string }[],
          profiles: [fit.distanceProfile, chosen.distanceProfile, gps.distanceProfile, unmeasured.distanceProfile],
          lifecycleZip: await api.pages(one, zip.session, 'lifecycle'),
          lifecycleFit: await api.pages(five, fit.session, 'lifecycle'),
          distance: await api.pages(five, fit.session, 'distance'),
          distanceOne: await api.pages(one, single.session, 'distance'),
          withoutProfile: [
            await api.pages(five, gps.session, 'distance'),
            await api.pages(five, unmeasured.session, 'distance'),
          ],
        };
      });
      expect(result.profiles).toEqual([
        { source: 'controller', kind: 'controller' },
        { source: 'controller', kind: 'controller' },
        null,
        null,
      ]);
      const { events } = result;
      expect(events.map(event => event.action)).toEqual(['start', 'pause', 'resume', 'lap', 'save']);
      expect(sorted(LIFECYCLE_ZIP)).toEqual(sorted(catalogColumns('lifecycle', 'zip')));
      expect(result.lifecycleZip.map(page => [page.rows, page.done, page.last])).toEqual(
        events.map((event, index) => [1, index === events.length - 1, [event.revision]]),
      );
      expect(increasing(result.lifecycleZip.map(page => page.last))).toBe(true);
      expect(joined(result.lifecycleZip)).toEqual({
        elapsedSeconds: events.map(event => event.elapsedSeconds),
        timestamp: events.map(event => event.timestamp),
        producer: events.map(() => 'browser'),
        action: events.map(event => event.action),
      });
      expect(result.lifecycleFit).toEqual([
        {
          rows: 5,
          last: [events[4]!.revision],
          done: true,
          connections: undefined,
          columns: {
            elapsedSeconds: [0, 9.5, 11.5, 13.5, 16],
            producer: ['browser', 'browser', 'browser', 'browser', 'browser'],
            action: ['start', 'pause', 'resume', 'lap', 'save'],
          },
          buffers: { elapsedSeconds: [0, 40, 40] },
        },
      ]);
      const speed = (second: number) => 4 + (second % 5);
      const intervals = [...Array.from({ length: 9 }, (_, i) => [i, i + 1, 0]), [12, 13, 1], [13, 14, 1], [14, 15, 1]];
      expect(sorted(DISTANCE_FIT)).toEqual(sorted(catalogColumns('distance', 'fit')));
      expect(result.distance.map(page => [page.rows, page.done, page.last])).toEqual([
        [5, false, [5, 5]],
        [5, false, [13, 13]],
        [2, true, [15, 15]],
      ]);
      expect(joined(result.distance)).toEqual({
        start: intervals.map(([start]) => start),
        end: intervals.map(([, end]) => end),
        meters: intervals.map(([start, end]) => (speed(start!) + speed(end!)) / 2),
        segment: intervals.map(([, , segment]) => segment),
        startSpeed: intervals.map(([start]) => speed(start!)),
        endSpeed: intervals.map(([, end]) => speed(end!)),
      });
      expect(result.distanceOne.map(page => page.last)).toEqual(intervals.map(([, end]) => [end, end]));
      expect(increasing(result.distanceOne.map(page => page.last))).toBe(true);
      for (const pages of result.withoutProfile)
        expect(pages).toEqual([
          {
            rows: 0,
            last: null,
            done: true,
            connections: undefined,
            columns: Object.fromEntries(DISTANCE_FIT.map(name => [name, []])),
            buffers: Object.fromEntries(DISTANCE_FIT.map(name => [name, [0, 0, 0]])),
          },
        ]);
    }));

  it('fails a page with changed after the ride changes and with deleted once it is gone', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.exportTests;
        const context: ExportContext = { exportedAt: '2026-02-01T00:00:00.000Z', platform: 'web' };
        const identity = { controllerModel: 'X6', firmwareLabel: '20260101', controllerProtocol: '5.3' };
        const ride = await api.seedRide([
          {
            rows: Array.from({ length: 30 }, (_, second) => ({
              elapsedSeconds: second,
              ...identity,
              controllerSpeedMps: 5,
              speedRaw: 18,
            })),
          },
          { action: 'save', elapsed: 30 },
        ]);
        const source = new api.BrowserExportSource({ maxRows: 10 });
        const bump = () =>
          api.browserTransaction(['recordings'], 'readwrite', async tx => {
            const store = tx.objectStore('recordings');
            const record = (await api.idbRequest(store.get(ride.id))) as { revision: number };
            await api.idbRequest(store.put({ ...record, revision: record.revision + 1 }));
          });
        const attempt = (session: string, projection: string, after: number[] | null = null) =>
          api.failure(() => source.page({ session, projection: projection as 'telemetry', after }));
        const zip = await source.open({ rideId: ride.id, kind: 'zip', context });
        const fit = await source.open({ rideId: ride.id, kind: 'fit', context });
        const firstTelemetry = await source.page({ session: zip.session, projection: 'telemetry', after: null });
        const firstDistance = await source.page({ session: fit.session, projection: 'distance', after: null });
        await bump();
        const changed = {
          telemetry: await attempt(zip.session, 'telemetry', firstTelemetry.last),
          lifecycle: await attempt(zip.session, 'lifecycle'),
          gps: await attempt(zip.session, 'gps'),
          distance: await attempt(fit.session, 'distance', firstDistance.last),
          healthFit: await attempt(fit.session, 'healthFit'),
        };
        const reopened = await source.open({ rideId: ride.id, kind: 'fit', context });
        const beforeRebuild = await source.page({ session: reopened.session, projection: 'distance', after: null });
        await api.browserTransaction(['distance-profiles'], 'readwrite', tx =>
          api.idbRequest(tx.objectStore('distance-profiles').delete(ride.id)),
        );
        await api.ensureBrowserDistance(ride.id);
        const rebuilt = await attempt(reopened.session, 'distance', beforeRebuild.last);
        const unaffected = await source.page({ session: reopened.session, projection: 'telemetry', after: null });
        const last = await source.open({ rideId: ride.id, kind: 'fit', context });
        const lastDistance = await source.page({ session: last.session, projection: 'distance', after: null });
        await api.store.remove(ride.id);
        return {
          firstTelemetry: firstTelemetry.rows,
          firstDistance: firstDistance.rows,
          changed,
          beforeRebuild: beforeRebuild.rows,
          rebuilt,
          unaffected: unaffected.rows,
          lastDistance: lastDistance.rows,
          deleted: {
            telemetry: await attempt(last.session, 'telemetry'),
            lifecycle: await attempt(last.session, 'lifecycle'),
            distance: await attempt(last.session, 'distance', lastDistance.last),
            gps: await attempt(last.session, 'gps'),
            open: await api.failure(() => source.open({ rideId: ride.id, kind: 'zip', context })),
          },
        };
      });
      expect([result.firstTelemetry, result.firstDistance, result.beforeRebuild, result.lastDistance]).toEqual([
        10, 10, 10, 10,
      ]);
      for (const outcome of Object.values(result.changed)) expect(outcome).toMatchObject({ code: 'changed' });
      expect(result.rebuilt).toMatchObject({ code: 'changed' });
      expect(result.unaffected).toBe(10);
      for (const outcome of Object.values(result.deleted)) expect(outcome).toMatchObject({ code: 'deleted' });
    }));

  it('opens FIT without distance when the distance data is unreadable, except for deleted rides and storage failures', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.exportTests;
        const context: ExportContext = { exportedAt: '2026-02-01T00:00:00.000Z', platform: 'web' };
        const identity = { controllerModel: 'X6', firmwareLabel: '20260101', controllerProtocol: '5.3' };
        const rows = Array.from({ length: 300 }, (_, second) => ({
          elapsedSeconds: second,
          ...identity,
          controllerSpeedMps: 5,
          speedRaw: 18,
        }));
        const ride = await api.seedRide([{ rows }, { action: 'save', elapsed: 300 }]);
        const source = new api.BrowserExportSource();
        const healthy = await source.open({ rideId: ride.id, kind: 'fit', context });
        await api.browserTransaction(['distance-profiles'], 'readwrite', async tx => {
          const profiles = tx.objectStore('distance-profiles');
          const profile = (await api.idbRequest(profiles.get(ride.id))) as Record<string, unknown>;
          await api.idbRequest(profiles.put({ ...profile, generation: '' }));
        });
        const broken = await source.open({ rideId: ride.id, kind: 'fit', context });
        const transaction = IDBDatabase.prototype.transaction;
        IDBDatabase.prototype.transaction = function (stores, ...rest) {
          if ([stores].flat().includes('distance-profiles'))
            throw new DOMException('Simulated storage failure', 'UnknownError');
          return transaction.call(this, stores, ...rest);
        };
        const storage = await source.open({ rideId: ride.id, kind: 'fit', context }).then(
          () => null,
          (error: Error) => ({ name: error.name, message: error.message, dom: error instanceof DOMException }),
        );
        IDBDatabase.prototype.transaction = transaction;
        const gone = await api.seedRide([{ rows }, { action: 'save', elapsed: 300 }]);
        const opening = api.failure(() => source.open({ rideId: gone.id, kind: 'fit', context }));
        await api.store.remove(gone.id);
        return {
          healthy: healthy.distanceProfile,
          broken: broken.distanceProfile,
          distance: await api.pages(source, broken.session, 'distance'),
          telemetry: (await api.pages(source, broken.session, 'telemetry')).reduce((sum, page) => sum + page.rows, 0),
          storage,
          deleted: await opening,
          remaining: await api.store.find(gone.id),
        };
      });
      expect(result.healthy).toEqual({ source: 'controller', kind: 'controller' });
      expect(result.broken).toBeNull();
      expect(result.distance).toEqual([
        {
          rows: 0,
          last: null,
          done: true,
          connections: undefined,
          columns: Object.fromEntries(DISTANCE_FIT.map(name => [name, []])),
          buffers: Object.fromEntries(DISTANCE_FIT.map(name => [name, [0, 0, 0]])),
        },
      ]);
      expect(result.telemetry).toBe(300);
      expect(result.storage).toEqual({ name: 'UnknownError', message: 'Simulated storage failure', dom: true });
      expect(result.deleted).toMatchObject({ code: 'deleted' });
      expect(result.remaining).toBeUndefined();
    }));

  it('rejects projections outside the session, malformed cursors, unreadable rows and closed sessions', async () =>
    run(async page => {
      const result = await page.evaluate(async () => {
        const api = window.exportTests;
        const context: ExportContext = { exportedAt: '2026-02-01T00:00:00.000Z', platform: 'web' };
        const ride = await api.seedRide([
          { rows: [0, 1, 2].map(second => ({ elapsedSeconds: second })) },
          { action: 'save', elapsed: 3 },
        ]);
        const source = new api.BrowserExportSource();
        const zip = await source.open({ rideId: ride.id, kind: 'zip', context });
        const fit = await source.open({ rideId: ride.id, kind: 'fit', context });
        const attempt = (session: string, projection: string, after: unknown = null) =>
          api.failure(() => source.page({ session, projection: projection as 'telemetry', after: after as null }));
        const empty = async (session: string, projection: string) =>
          api.plain(await source.page({ session, projection: projection as 'gps', after: null }));
        const outside = {
          zipDistance: await attempt(zip.session, 'distance'),
          zipDiscovery: await attempt(zip.session, 'gpsDiscovery'),
          zipHealthFit: await attempt(zip.session, 'healthFit'),
          fitHealthZip: await attempt(fit.session, 'healthZip'),
          unknown: await attempt(zip.session, 'locations'),
        };
        const absent = [
          await empty(zip.session, 'gps'),
          await empty(zip.session, 'healthZip'),
          await empty(fit.session, 'gps'),
          await empty(fit.session, 'gpsDiscovery'),
          await empty(fit.session, 'healthFit'),
        ];
        const cursors = [
          await attempt(zip.session, 'telemetry', [1]),
          await attempt(zip.session, 'telemetry', [1, 2, 3]),
          await attempt(zip.session, 'telemetry', [Number.NaN, 0]),
          await attempt(zip.session, 'telemetry', ['1', 0]),
          await attempt(zip.session, 'lifecycle', [1, 2]),
        ];
        await api.browserTransaction(['samples', 'ride-lifecycle'], 'readwrite', async tx => {
          const samples = tx.objectStore('samples');
          const sample = (await api.idbRequest(samples.get([ride.id, 1]))) as Record<string, unknown>;
          await api.idbRequest(samples.put({ ...sample, humanPowerW: 'strong' }));
          const lifecycle = tx.objectStore('ride-lifecycle');
          const event = (await api.idbRequest(lifecycle.get([ride.id, 1]))) as Record<string, unknown>;
          await api.idbRequest(lifecycle.put({ ...event, action: 'teleport' }));
        });
        const unreadable = [await attempt(zip.session, 'telemetry'), await attempt(zip.session, 'lifecycle')];
        await source.close(zip.session);
        await source.close(zip.session);
        await source.close('never-opened');
        const closed = await attempt(zip.session, 'telemetry');
        const stillOpen = (await source.page({ session: fit.session, projection: 'gps', after: null })).done;
        await api.browserTransaction(['recordings'], 'readwrite', async tx => {
          const recordings = tx.objectStore('recordings');
          const record = (await api.idbRequest(recordings.get(ride.id))) as Record<string, unknown>;
          await api.idbRequest(recordings.put({ ...record, phase: 'teleported' }));
        });
        unreadable.push(
          await attempt(fit.session, 'gps'),
          await api.failure(() => source.open({ rideId: ride.id, kind: 'zip', context })),
        );
        return { outside, absent, cursors, unreadable, closed, stillOpen };
      });
      for (const outcome of Object.values(result.outside)) expect(outcome).toMatchObject({ code: 'unsupported' });
      for (const page of result.absent)
        expect(page).toEqual({
          rows: 0,
          last: null,
          done: true,
          connections: undefined,
          columns: {},
          buffers: {},
        });
      for (const outcome of result.cursors) expect(outcome).toMatchObject({ code: 'cursor' });
      expect(result.unreadable).toHaveLength(4);
      for (const outcome of result.unreadable)
        expect(outcome).toMatchObject({ code: 'gate', message: expect.stringContaining("can't read this ride") });
      expect(result.unreadable.slice(2).map(outcome => outcome!.message)).toEqual([
        "Power Log can't read this ride's saved data, so it can't be exported. Invalid recording phase.",
        "Power Log can't read this ride's saved data, so it can't be exported. Invalid recording phase.",
      ]);
      expect(result.closed).toMatchObject({ code: 'cancelled' });
      expect(result.stillOpen).toBe(true);
    }));
});

describe('browser export sink', () => {
  const context: ExportContext = { exportedAt: '2026-02-01T00:00:00.000Z', platform: 'web' };
  const staging = (entries: Harness.Entry[]) =>
    entries.filter(entry => entry.path.startsWith('.powerlog-export-')).map(entry => entry.path);
  const committed = (entries: Harness.Entry[]) =>
    entries.filter(entry => entry.kind === 'file' && entry.path.includes('/')).map(entry => entry.path.split('/')[1]);

  it('writes, patches and deflates an OPFS staging file, then commits it in its own directory as a download', async () =>
    run(async page => {
      const { result, download } = await withDownload(page, () =>
        page.evaluate(async context => {
          const api = window.exportTests;
          const sink = await api.openBrowserExportSink('zip', context);
          const written: string[] = [];
          let crc = 0;
          await sink.write(new Uint8Array(30));
          await sink.beginDeflate();
          const buffer = new Uint8Array(1024 * 1024);
          for (let round = 0; round < 12; round++) {
            if (round % 2) api.fillRandom(buffer);
            else for (let i = 0; i < buffer.length; i++) buffer[i] = i % 61 === 0 ? 10 : 48 + ((i * 7 + round) % 10);
            written.push(api.base64(buffer));
            crc = api.crc32(buffer, crc);
            await sink.write(buffer);
          }
          const long = api.randomBytes(3 * 1024 * 1024 + 5);
          written.push(api.base64(long));
          crc = api.crc32(long, crc);
          await sink.write(long);
          const stage = await sink.endDeflate();
          await sink.writeAt(0, Uint8Array.of(0x50, 0x4b, 0x03, 0x04));
          const sizes = new Uint8Array(12);
          const view = new DataView(sizes.buffer);
          view.setUint32(0, stage.crc32, true);
          view.setUint32(4, stage.outputBytes, true);
          view.setUint32(8, stage.inputBytes, true);
          await sink.writeAt(14, sizes);
          await sink.write(new TextEncoder().encode('central directory'));
          const staged = await api.listing();
          const { uri } = await sink.commit('ride.zip');
          const entries = await api.listing();
          const folder = entries.find(entry => entry.kind === 'directory')?.path;
          return {
            written,
            crc,
            stage,
            staged,
            uri,
            entries,
            file: folder ? await api.readExport(`${folder}/ride.zip`) : '',
          };
        }, context),
      );
      const input = Buffer.concat(result.written.map(decode));
      const file = decode(result.file);
      const compressed = file.length - 30 - 'central directory'.length;
      expect(result.stage).toEqual({ crc32: zlibCrc32(input), inputBytes: input.length, outputBytes: compressed });
      expect(result.crc).toBe(result.stage.crc32);
      expect(file.subarray(0, 30)).toEqual(zipLocalHeader(result.stage.crc32, compressed, input.length));
      expect(Buffer.compare(inflateRawSync(file.subarray(30, 30 + compressed)), input)).toBe(0);
      expect(file.subarray(30 + compressed).toString()).toBe('central directory');
      expect(download.suggestedFilename()).toBe('ride.zip');
      expect(Buffer.compare(await fileOf(download), file)).toBe(0);
      expect(result.uri).toMatch(/^blob:/);
      const folder = result.entries[0]!.path;
      expect(folder).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
      expect(result.entries.map(entry => [entry.path, entry.kind, entry.size])).toEqual([
        [folder, 'directory', undefined],
        [`${folder}/ride.zip`, 'file', file.length],
      ]);
      const name = `.powerlog-export-${folder}.part`;
      expect(result.staged.find(entry => entry.path === name)).toMatchObject({ kind: 'file', size: 0 });
      expect(result.staged.every(entry => entry.path.startsWith(name))).toBe(true);
    }));

  it('deletes the staging file on abort, cancels a write in flight and never removes a committed file', async () =>
    run(async page => {
      const { result, download } = await withDownload(page, () =>
        page.evaluate(async context => {
          const api = window.exportTests;
          const first = await api.openBrowserExportSink('zip', context);
          await first.write(new Uint8Array(1000));
          await first.beginDeflate();
          await first.write(api.randomBytes(2 * 1024 * 1024));
          const during = await api.listing();
          await first.abort();
          const afterAbort = await api.listing();
          const late = [
            await api.failure(() => first.write(new Uint8Array(1))),
            await api.failure(() => first.commit('late.zip')),
            await api.failure(() => first.abort()),
          ];
          const kept = await api.openBrowserExportSink('fit', context);
          await kept.write(Uint8Array.of(1, 2, 3));
          await kept.commit('ride.fit');
          await kept.abort();
          const afterCommit = await api.listing();
          const busy = await api.openBrowserExportSink('zip', context);
          await busy.beginDeflate();
          const writing = api.failure(() => busy.write(api.randomBytes(32 * 1024 * 1024)));
          await new Promise(resolve => setTimeout(resolve, 30));
          await busy.abort();
          return { during, afterAbort, late, afterCommit, inFlight: await writing, afterBusy: await api.listing() };
        }, context),
      );
      expect(staging(result.during).length).toBeGreaterThan(0);
      expect(result.afterAbort).toEqual([]);
      expect(result.late).toEqual([
        { code: 'cancelled', message: 'The export was cancelled.' },
        { code: 'cancelled', message: 'The export was cancelled.' },
        null,
      ]);
      expect(download.suggestedFilename()).toBe('ride.fit');
      expect([...(await fileOf(download))]).toEqual([1, 2, 3]);
      const folder = result.afterCommit[0]!.path;
      expect(result.afterCommit.map(entry => [entry.path, entry.size])).toEqual([
        [folder, undefined],
        [`${folder}/ride.fit`, 3],
      ]);
      expect(result.inFlight).toMatchObject({ code: 'cancelled' });
      expect(result.afterBusy).toEqual(result.afterCommit);
    }));

  it('cleans staging files at each open and committed exports only once they are older than 24 hours', async () =>
    run(async page => {
      const result = await page.evaluate(
        async ({ context, day }) => {
          const api = window.exportTests;
          const save = async (name: string) => {
            const sink = await api.openBrowserExportSink('zip', context);
            await sink.write(new TextEncoder().encode(name));
            await sink.commit(name);
          };
          const modified = async (name: string) =>
            (await api.listing()).find(entry => entry.path.endsWith(`/${name}`))!.lastModified!;
          await save('a.zip');
          await new Promise(resolve => setTimeout(resolve, 20));
          await save('b.zip');
          const live = await api.openBrowserExportSink('zip', context);
          await live.write(new Uint8Array(64));
          const stale = crypto.randomUUID();
          await api.leaveFile(`.powerlog-export-${stale}.part`, 10);
          await api.leaveFile(`.powerlog-export-${stale}.part.crswap`, 20);
          await api.leaveFile('notes.txt', 3);
          const before = await api.listing();
          const second = await api.openBrowserExportSink('fit', context);
          await second.write(new Uint8Array(8));
          const afterOpen = await api.listing();
          const a = await modified('a.zip');
          const b = await modified('b.zip');
          await api.cleanBrowserExports(a + day + 1);
          const afterA = await api.listing();
          await api.cleanBrowserExports(b + day);
          const atBoundary = await api.listing();
          await api.cleanBrowserExports(b + day + 1);
          const afterB = await api.listing();
          const held = afterOpen
            .find(entry => entry.path.endsWith('.part'))!
            .path.slice('.powerlog-export-'.length, -'.part'.length);
          const orphan = crypto.randomUUID();
          for (const folder of [held, orphan])
            await (await api.exportsDirectory()).getDirectoryHandle(folder, { create: true });
          await api.cleanBrowserExports();
          const folders = (await api.listing()).filter(entry => entry.kind === 'directory').map(entry => entry.path);
          await live.commit('live.zip');
          await second.commit('second.fit');
          await api.cleanBrowserExports();
          return {
            stale,
            a,
            b,
            before,
            afterOpen,
            afterA,
            atBoundary,
            afterB,
            held,
            orphan,
            folders,
            final: await api.listing(),
          };
        },
        { context, day: DAY },
      );
      expect(result.folders).toEqual([result.held]);
      expect(result.orphan).not.toBe(result.held);
      const stale = [`.powerlog-export-${result.stale}.part`, `.powerlog-export-${result.stale}.part.crswap`];
      expect(result.b).toBeGreaterThan(result.a);
      expect(staging(result.before)).toEqual(expect.arrayContaining(stale));
      expect(committed(result.before)).toEqual(expect.arrayContaining(['a.zip', 'b.zip']));
      expect(staging(result.afterOpen)).not.toEqual(expect.arrayContaining([stale[0]]));
      expect(staging(result.afterOpen).some(path => path.includes(result.stale))).toBe(false);
      expect(staging(result.afterOpen).filter(path => path.endsWith('.part'))).toHaveLength(2);
      expect(result.afterOpen.map(entry => entry.path)).toContain('notes.txt');
      expect(committed(result.afterOpen).sort()).toEqual(['a.zip', 'b.zip']);
      expect(committed(result.afterA)).toEqual(['b.zip']);
      expect(result.afterA.filter(entry => entry.kind === 'directory')).toHaveLength(1);
      expect(staging(result.afterA)).toEqual(staging(result.afterOpen));
      expect(committed(result.atBoundary)).toEqual(['b.zip']);
      expect(committed(result.afterB)).toEqual([]);
      expect(staging(result.afterB)).toEqual(staging(result.afterOpen));
      expect(committed(result.final).sort()).toEqual(['live.zip', 'second.fit']);
      expect(staging(result.final)).toEqual([]);
      expect(result.final.map(entry => entry.path)).toContain('notes.txt');
    }));

  it.each(['afterFolderCheck', 'everyAttempt'] as const)(
    'keeps an export whose commit finishes while another cleanup inspects its folder (%s)',
    async hold =>
      run(async page => {
        const result = await page.evaluate(hold => window.exportTests.cleanDuringCommit(hold), hold);
        expect(result.entries.map(entry => entry.path)).toEqual([result.uuid, `${result.uuid}/committed.zip`]);
        expect(decode(result.content!).toString()).toBe('committed during cleanup');
      }),
  );

  it('falls back to an in-memory sink capped at 128 MiB without OPFS', async () =>
    run(async page => {
      const { result, download } = await withDownload(page, () =>
        page.evaluate(async context => {
          const api = window.exportTests;
          api.withoutOpfs();
          const text = new TextEncoder().encode('time,power\n' + '0,100\n'.repeat(20_000));
          const sink = await api.openBrowserExportSink('zip', context);
          await sink.write(new Uint8Array(30));
          await sink.beginDeflate();
          await sink.write(text);
          const stage = await sink.endDeflate();
          const crc = new Uint8Array(4);
          new DataView(crc.buffer).setUint32(0, stage.crc32, true);
          await sink.writeAt(14, crc);
          await sink.write(Uint8Array.of(7));
          const { uri } = await sink.commit('memory.zip');
          const limit = api.BROWSER_EXPORT_MEMORY_LIMIT;
          const block = new Uint8Array(8 * 1024 * 1024);
          const full = await api.openBrowserExportSink('zip', context);
          for (let size = 0; size < limit; size += block.length) await full.write(block);
          const overflow = await api.failure(() => full.write(new Uint8Array(1)));
          await full.abort();
          const deflated = await api.openBrowserExportSink('zip', context);
          for (let size = 0; size < limit - block.length; size += block.length) await deflated.write(block);
          await deflated.write(block.subarray(0, block.length - 65_536));
          await deflated.beginDeflate();
          const deflateOverflow = await api.failure(async () => {
            await deflated.write(api.randomBytes(1024 * 1024));
            await deflated.endDeflate();
          });
          await deflated.abort();
          return { text: api.base64(text), stage, uri, limit, overflow, deflateOverflow, opfs: await api.listing() };
        }, context),
      );
      const text = decode(result.text);
      const file = await fileOf(download);
      expect(download.suggestedFilename()).toBe('memory.zip');
      expect(result.uri).toMatch(/^blob:/);
      expect(result.stage).toEqual({
        crc32: zlibCrc32(text),
        inputBytes: text.length,
        outputBytes: file.length - 31,
      });
      expect(file.readUInt32LE(14)).toBe(zlibCrc32(text));
      expect(file.subarray(0, 14)).toEqual(Buffer.alloc(14));
      expect(Buffer.compare(inflateRawSync(file.subarray(30, file.length - 1)), text)).toBe(0);
      expect(file.at(-1)).toBe(7);
      expect(result.limit).toBe(128 * MiB);
      expect(result.overflow).toMatchObject({ code: 'limit', message: expect.stringContaining('128 MiB') });
      expect(result.deflateOverflow).toMatchObject({ code: 'limit', message: expect.stringContaining('128 MiB') });
      expect(result.opfs).toEqual([]);
    }));

  it.each(['OPFS', 'memory'])('yields to the event loop while the %s sink deflates a long buffer', async storage =>
    run(async page => {
      const result = await page.evaluate(
        async ({ context, storage }) => {
          const api = window.exportTests;
          if (storage === 'memory') api.withoutOpfs();
          const sink = await api.openBrowserExportSink('zip', context);
          const data = api.randomBytes(32 * 1024 * 1024);
          await sink.beginDeflate();
          const { stallMs } = await api.longestStall(() => sink.write(data));
          const stage = await sink.endDeflate();
          await sink.abort();
          return { stallMs, crc: stage.crc32 === api.crc32(data), inputBytes: stage.inputBytes };
        },
        { context, storage },
      );
      expect(result.crc).toBe(true);
      expect(result.inputBytes).toBe(32 * MiB);
      expect(result.stallMs).toBeLessThan(200);
    }),
  );

  it('rejects writes it cannot honour', async () =>
    run(async page => {
      const result = await page.evaluate(async context => {
        const api = window.exportTests;
        type Sink = Awaited<ReturnType<typeof api.openBrowserExportSink>>;
        const ten = new Uint8Array(10);
        const fresh = async (setup: (sink: Sink) => Promise<unknown>, misuse: (sink: Sink) => Promise<unknown>) => {
          const sink = await api.openBrowserExportSink('zip', context);
          await setup(sink);
          const outcome = await api.failure(() => misuse(sink));
          await sink.abort();
          return outcome;
        };
        const staged = async (sink: Sink) => {
          await sink.write(ten);
          await sink.beginDeflate();
          await sink.write(ten);
        };
        const names = [];
        for (const name of ['', 'a/b', 'a\\b', '..', '.', 'line\nbreak'])
          names.push(
            await fresh(
              async () => {},
              sink => sink.commit(name),
            ),
          );
        const saved = await api.openBrowserExportSink('fit', context);
        await saved.write(ten);
        await saved.commit('done.fit');
        return {
          beyondEnd: await fresh(
            sink => sink.write(ten),
            sink => sink.writeAt(8, new Uint8Array(4)),
          ),
          negative: await fresh(
            sink => sink.write(ten),
            sink => sink.writeAt(-1, new Uint8Array(1)),
          ),
          intoStage: await fresh(staged, sink => sink.writeAt(8, new Uint8Array(4))),
          beforeStage: await fresh(staged, async sink => {
            await sink.writeAt(2, new Uint8Array(8));
            await sink.endDeflate();
          }),
          nested: await fresh(
            sink => sink.beginDeflate(),
            sink => sink.beginDeflate(),
          ),
          unopened: await fresh(
            async () => {},
            sink => sink.endDeflate(),
          ),
          commitInStage: await fresh(
            sink => sink.beginDeflate(),
            sink => sink.commit('ride.zip'),
          ),
          names,
          overlap: await fresh(
            async () => {},
            async sink => {
              const first = sink.write(new Uint8Array(1024));
              const second = sink.write(new Uint8Array(1));
              await first;
              await second;
            },
          ),
          afterFailure: await fresh(
            async sink => {
              await sink.write(ten);
              await sink.writeAt(20, ten).catch(() => {});
            },
            sink => sink.write(ten),
          ),
          afterCommit: await api.failure(() => saved.write(ten)),
          kind: await api.failure(() => api.openBrowserExportSink('gpx' as ExportFileKind, context)),
        };
      }, context);
      for (const key of ['beyondEnd', 'negative', 'intoStage', 'nested', 'unopened', 'commitInStage'] as const)
        expect(result[key], key).toMatchObject({ code: 'sink' });
      expect(result.beforeStage).toBeNull();
      expect(result.names).toEqual(Array(6).fill({ code: 'sink', message: 'The export file name is not valid.' }));
      expect(result.overlap).toEqual({ code: 'sink', message: 'Export writes must not overlap.' });
      expect(result.afterFailure).toMatchObject({ code: 'sink', message: expect.stringContaining('earlier write') });
      expect(result.afterCommit).toEqual({ code: 'sink', message: 'This export file is already saved.' });
      expect(result.kind).toMatchObject({ code: 'unsupported' });
    }));
});
