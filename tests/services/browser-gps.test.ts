import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from 'vitest';
import { BrowserGPS } from '../../src/services/browser-gps';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import { BrowserRideStore, type RideLocationRow } from '../../src/services/browser-ride-store';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { syntheticSample } from '../fixtures/synthetic-sample';
import { browserRide } from '../support/browser-ride';
import { browserMemoryStore } from '../support/browser-memory-store';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
async function harness() {
  vi.useFakeTimers();
  let time = 0;
  let fix: PositionCallback | undefined;
  let fail: PositionErrorCallback | undefined;
  const clearWatch = vi.fn();
  const geolocation = {
    watchPosition: vi.fn((receive: PositionCallback, error: PositionErrorCallback) => {
      fix = receive;
      fail = error;
      return 1;
    }),
    clearWatch,
  };
  vi.stubGlobal('navigator', {
    geolocation,
    locks: { request: (_: string, __: unknown, work: (lock: object) => Promise<void>) => work({}) },
  });
  vi.stubGlobal('indexedDB', {});
  const store = new BrowserRideStore();
  const record = browserRide({ indoor: false, recordGPS: true });
  const memory = browserMemoryStore(store, record);
  const { transition, appendLocations: append, append: samples } = memory;
  const recorder = new BrowserWorkoutRecorder(
    store,
    () => time,
    () => new Date(Date.UTC(2026, 0, 1) + time * 1000).toISOString(),
  );
  const source = new TestTelemetryAdapter(() => time);
  recorder.setTelemetrySource(source);
  await source.connect();
  return {
    recorder,
    source,
    append,
    samples,
    transition,
    clearWatch,
    geolocation,
    failure: (code = 1, message = 'Denied') => fail?.({ code, message } as GeolocationPositionError),
    time: (next: number) => {
      time = next;
    },
    fix: (seconds: number, fields: Partial<GeolocationCoordinates> = {}) =>
      fix?.({
        timestamp: Date.UTC(2026, 0, 1) + seconds * 1000,
        coords: {
          latitude: 0,
          longitude: seconds * 0.00001,
          accuracy: 5,
          altitude: 12,
          altitudeAccuracy: -1,
          heading: 360,
          speed: 40.000001,
          ...fields,
        },
      } as GeolocationPosition),
  };
}
it('records GPS with a connected bike, retaining raw time and altitude evidence', async () => {
  const h = await harness();
  const started = await h.recorder.start({ indoor: false, recordGPS: true, sampleHz: 4 });
  expect(started).toMatchObject({ phase: 'running', recordGPS: true, saveToHealth: false });
  h.time(2);
  h.fix(1);
  await vi.advanceTimersByTimeAsync(250);
  const row: RideLocationRow = h.append.mock.calls[0]![2][0]!;
  expect(row).toMatchObject({
    elapsedSeconds: 1,
    acquiredAtSeconds: 1,
    timestamp: '2026-01-01T00:00:01.000Z',
    originalTimestamp: '2026-01-01T00:00:01.000Z',
    speedMps: 40.000001,
    courseDegrees: 360,
    originalAltitudeDatum: 'wgs84Ellipsoid',
    originalAltitudeMeters: 12,
    originalVerticalAccuracyM: -1,
  });
  expect(row).not.toHaveProperty('altitudeMeters');
  expect(row).not.toHaveProperty('verticalAccuracyM');
  await h.recorder.discard(started.id!);
  expect(h.clearWatch).toHaveBeenCalled();
});
it('applies the frozen rate before claiming the connected bike for a GPS ride', async () => {
  const h = await harness();
  const rate = vi.spyOn(h.source, 'setSampleRate');
  const owner = vi.spyOn(h.source, 'setWorkoutOwner');
  const started = await h.recorder.start({ indoor: false, recordGPS: true, sampleHz: 4 });
  expect(rate).toHaveBeenCalledWith(4);
  expect(owner).toHaveBeenCalledWith('test-bike');
  expect(rate.mock.invocationCallOrder[0]).toBeLessThan(owner.mock.invocationCallOrder[0]!);
  await h.recorder.discard(started.id!);
});
it('rejects stale, future, duplicate and out-of-order fixes and retains a continuity barrier', async () => {
  const h = await harness();
  const started = await h.recorder.start({ indoor: false, recordGPS: true });
  h.time(1);
  h.fix(1);
  h.fix(1);
  h.fix(0.5);
  h.fix(2);
  await vi.advanceTimersByTimeAsync(250);
  h.time(17);
  h.fix(1);
  h.fix(17);
  await vi.advanceTimersByTimeAsync(250);
  expect(h.append.mock.calls.flatMap(call => call[2])).toHaveLength(2);
  expect(h.append.mock.calls.at(-1)![2][0]!.distanceBarrier).toBe(true);
  await h.recorder.discard(started.id!);
});

it('stops GPS when hidden and rejects callbacks from a cleared watch after visibility returns', async () => {
  const document = Object.assign(new EventTarget(), { visibilityState: 'visible' });
  vi.stubGlobal('document', document);
  const h = await harness();
  const started = await h.recorder.start({ indoor: false, recordGPS: true });
  const oldReceive = h.geolocation.watchPosition.mock.calls[0]![0];
  h.time(1);
  h.fix(1);
  await vi.advanceTimersByTimeAsync(250);
  document.visibilityState = 'hidden';
  document.dispatchEvent(new Event('visibilitychange'));
  expect(h.clearWatch).toHaveBeenCalledTimes(1);
  h.time(2);
  h.fix(2);
  await vi.advanceTimersByTimeAsync(250);
  document.visibilityState = 'visible';
  document.dispatchEvent(new Event('visibilitychange'));
  h.time(3);
  oldReceive({
    timestamp: Date.UTC(2026, 0, 1) + 2000,
    coords: { latitude: 0, longitude: 0.02, accuracy: 5 },
  } as GeolocationPosition);
  h.fix(3);
  await vi.advanceTimersByTimeAsync(250);
  expect(h.append.mock.calls.flatMap(call => call[2]).map(row => row.elapsedSeconds)).toEqual([1, 3]);
  expect(h.append.mock.calls.at(-1)![2][0]!.distanceBarrier).toBe(true);
  await h.recorder.discard(started.id!);
});
it('maps age and clock uncertainty at their exact admission boundaries', () => {
  let now = 20,
    jump = 1;
  const base = Date.UTC(2026, 0, 1);
  const gps = new BrowserGPS(
    () => now,
    () => new Date(base + (now + jump) * 1000).toISOString(),
  );
  const fix = (seconds: number) =>
    ({
      timestamp: base + seconds * 1000,
      coords: { latitude: 0, longitude: seconds * 0.00001, accuracy: 5, speed: 2 },
    }) as GeolocationPosition;
  gps.begin();
  expect(gps.map(fix(6), 0, 0, new Date(base).toISOString())).toMatchObject({
    elapsedSeconds: 5,
    distanceBarrier: false,
  });
  now = 21;
  jump = 2.001;
  expect(gps.map(fix(23.001), 0, 0, new Date(base).toISOString())).toMatchObject({
    elapsedSeconds: 21,
    distanceBarrier: true,
  });
  now = 36;
  expect(gps.map(fix(38.001), 0, 0, new Date(base).toISOString())).toMatchObject({ distanceBarrier: true });
  now = 36.001;
  expect(gps.map(fix(38.002), 0, 0, new Date(base).toISOString())).toMatchObject({ distanceBarrier: false });
  expect(gps.map(fix(38.002001), 0, 0, new Date(base).toISOString())).toBeUndefined();
});

it('allocates one ordered browser producer sequence across GPS and lifecycle commands', async () => {
  const h = await harness();
  const started = await h.recorder.start({ indoor: false, recordGPS: true });
  h.time(1);
  h.fix(1);
  await h.recorder.pause(started.id!);
  expect(h.append.mock.calls[0]![2][0]!.producerSequence).toBe('2');
  expect(h.transition.mock.calls[0]![6]).toBe('3');
  await h.recorder.resume(started.id!);
  h.time(2);
  h.fix(2);
  await vi.advanceTimersByTimeAsync(250);
  expect(h.transition.mock.calls[1]![6]).toBe('4');
  expect(h.append.mock.calls[1]![2][0]!.producerSequence).toBe('5');
  await h.recorder.discard(started.id!);
});

it('records telemetry after the same bike reconnects during a GPS ride', async () => {
  const h = await harness();
  const started = await h.recorder.start({ indoor: false, recordGPS: true, sampleHz: 4 });
  h.source.emitState({ status: 'reconnecting' });
  h.time(2);
  await h.source.connect();
  h.source.emitSample({ ...syntheticSample(2, 0), connectionEpoch: h.source.connectionEpoch });
  await vi.advanceTimersByTimeAsync(250);
  expect(h.samples).toHaveBeenCalledTimes(1);
  expect(h.samples.mock.calls[0]![2][0]!.recordingId).toBe(started.id);
  await h.recorder.discard(started.id!);
});
it('uses shared GPS quality and provider denial precedence over a fresh fix', async () => {
  const h = await harness();
  const started = await h.recorder.start({ indoor: false, recordGPS: true });
  h.time(1);
  h.fix(1, { accuracy: 100 });
  await vi.advanceTimersByTimeAsync(250);
  expect((await h.recorder.getState()).streams.gps.status).toBe('weak');
  h.time(2);
  h.fix(2, { accuracy: 5 });
  await vi.advanceTimersByTimeAsync(250);
  expect((await h.recorder.getState()).streams.gps.status).toBe('receiving');
  h.failure();
  expect((await h.recorder.getState()).streams.gps.status).toBe('denied');
  await h.recorder.discard(started.id!);
});

it('retains a GPS provider failure across granted permission refresh until a new fix arrives', async () => {
  const h = await harness();
  Object.assign(navigator, { permissions: { query: async () => ({ state: 'granted' }) } });
  const started = await h.recorder.start({ indoor: false, recordGPS: true });
  h.time(1);
  h.fix(1, { accuracy: 5 });
  await vi.advanceTimersByTimeAsync(250);
  h.failure(2, 'Position unavailable');
  expect((await h.recorder.getState()).streams.gps.status).toBe('unavailable');
  expect((await h.recorder.getPermissions()).location).toBe('authorizedWhenInUse');
  expect(await h.recorder.getState()).toMatchObject({ streams: { gps: { status: 'unavailable' } }, failure: null });
  h.time(2);
  h.fix(2, { accuracy: 5 });
  await vi.advanceTimersByTimeAsync(250);
  expect(await h.recorder.getState()).toMatchObject({ streams: { gps: { status: 'receiving' } }, failure: null });
  await h.recorder.discard(started.id!);
});

it('loads GPS acquisition fixtures through the browser provider adapter', () => {
  const fixture = JSON.parse(
    readFileSync(new URL('../fixtures/contract/gps/acquisition.json', import.meta.url), 'utf8'),
  ) as {
    baseTimestamp: string;
    cases: {
      id: string;
      fixUs: string;
      receiptUs: string;
      uptime: number;
      origin?: number;
      epochStart?: number;
      stop?: number;
      timestamp?: string;
      latitude?: number;
      accuracy?: number;
      mapped: boolean;
      elapsed?: number;
    }[];
  };
  for (const row of fixture.cases.filter(
    row => row.stop === undefined && row.epochStart === undefined && !row.timestamp,
  )) {
    const base = Date.parse(fixture.baseTimestamp);
    const gps = new BrowserGPS(
      () => row.uptime,
      () => new Date(base + Number(row.receiptUs) / 1000).toISOString(),
    );
    gps.begin();
    const point = gps.map(
      {
        timestamp: base + Number(row.fixUs) / 1000,
        coords: {
          latitude: row.latitude ?? 40,
          longitude: 20,
          accuracy: row.accuracy ?? 5,
          altitude: null,
          altitudeAccuracy: null,
          heading: null,
          speed: null,
        },
      } as GeolocationPosition,
      row.origin ?? 0,
      row.origin ?? 0,
      fixture.baseTimestamp,
    );
    expect(Boolean(point), row.id).toBe(row.mapped);
    if (point) expect(point.elapsedSeconds, row.id).toBeCloseTo(row.elapsed!, 6);
  }
});

it.each([0, Number.MAX_VALUE])('rejects an unrepresentable provider timestamp %s without throwing', timestamp => {
  const gps = new BrowserGPS(
    () => 1,
    () => '2026-01-01T00:00:01Z',
  );
  gps.begin();
  const position = {
    timestamp,
    coords: {
      latitude: 0,
      longitude: 0,
      accuracy: 5,
      speed: null,
      heading: null,
      altitude: null,
      altitudeAccuracy: null,
    },
  } as GeolocationPosition;
  expect(gps.map(position, 0, 0, '2026-01-01T00:00:00Z')).toBeUndefined();
});
