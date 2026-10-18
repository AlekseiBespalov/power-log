import { presentationFailure } from '../../shared/js';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import { BrowserRideStore } from '../../src/services/browser-ride-store';
import { workoutSourcePresentation } from '../../src/core/workout-presentation';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { browserMemoryStore } from '../support/browser-memory-store';
import { syntheticSample } from '../fixtures/synthetic-sample';

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal('indexedDB', {});
  vi.stubGlobal('navigator', {
    locks: { request: (_name: string, _options: unknown, work: (lock: object) => Promise<void>) => work({}) },
  });
});
afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function harness() {
  let now = 10;
  const store = new BrowserRideStore();
  const { begin, append, record } = browserMemoryStore(store);
  const recorder = new BrowserWorkoutRecorder(store, () => now);
  const source = new TestTelemetryAdapter(() => now);
  recorder.setTelemetrySource(source);
  return {
    store,
    recorder,
    source,
    begin,
    append,
    record,
    time: (value: number) => {
      now = value;
    },
    sample: (sequence: number) =>
      source.emitSample({
        ...syntheticSample(now, sequence),
        connectionEpoch: source.connectionEpoch,
      }),
  };
}
it('reports waiting, receiving, stale, paused and off from this ride’s committed observations', async () => {
  const h = harness();
  await h.source.connect();
  h.sample(0);
  expect((await h.recorder.getState()).streams.controller.status).toBe('off');
  await h.recorder.start({ indoor: true });
  try {
    expect((await h.recorder.getState()).streams.controller.status).toBe('waiting');
    h.time(11);
    h.sample(1);
    await vi.advanceTimersByTimeAsync(250);
    expect((await h.recorder.getState()).streams.controller.status).toBe('receiving');
    h.time(16.999);
    expect((await h.recorder.getState()).streams.controller.status).toBe('receiving');
    h.time(17);
    const stale = await h.recorder.getState();
    expect(stale.streams.controller.status).toBe('stale');
    expect(workoutSourcePresentation(stale, { indoor: true }, false).bike.label).toBe('No recent samples');
    expect((await h.recorder.pause()).streams.controller.status).toBe('paused');
    h.sample(2);
    await vi.advanceTimersByTimeAsync(250);
    expect((await h.recorder.getState()).streams.controller.status).toBe('paused');
    expect((await h.recorder.resume()).streams.controller.status).toBe('receiving');
    expect((await h.recorder.stop()).streams.controller.status).toBe('off');
    await h.recorder.start({ indoor: true });
    expect((await h.recorder.getState()).streams.controller.status).toBe('waiting');
  } finally {
    await h.recorder.discard((await h.recorder.getState()).id!);
  }
});
it.each([0.1, 5.999, 6])('rejects a new Start during a %s second reconnect even with GPS enabled', async age => {
  const h = harness();
  await h.source.connect();
  h.sample(0);
  h.time(10 + age);
  h.source.emitState({ status: 'reconnecting' });
  await expect(h.recorder.start({ indoor: false, recordGPS: true })).rejects.toMatchObject({ code: 'bikeRequired' });
  expect(h.begin).not.toHaveBeenCalled();
  expect(h.append).not.toHaveBeenCalled();
  h.source.emitState({ status: 'connected' });
  const started = await h.recorder.start({ indoor: true });
  expect(started.phase).toBe('running');
  await h.recorder.discard(started.id!);
});
it.each(['no adapter', 'disconnected', 'connecting', 'missing device'] as const)(
  'rejects GPS-enabled Start with %s before creating a ride',
  async reason => {
    const h = harness();
    const recorder = reason === 'no adapter' ? new BrowserWorkoutRecorder(h.store) : h.recorder;
    if (reason === 'connecting') h.source.emitState({ status: 'connecting', deviceId: 'test-bike' });
    if (reason === 'missing device') h.source.emitState({ status: 'connected', deviceId: undefined });
    await expect(recorder.start({ indoor: false, recordGPS: true })).rejects.toMatchObject({ code: 'bikeRequired' });
    expect(h.begin).not.toHaveBeenCalled();
    expect(h.store.interruptCurrent).not.toHaveBeenCalled();
    expect(h.source.sampleRates).toEqual([]);
  },
);
it('allows an accepted Start to finish setup and record after a same-bike reconnect', async () => {
  const h = harness();
  await h.source.connect();
  vi.spyOn(h.source, 'setSampleRate').mockImplementationOnce(async () => {
    expect(h.begin).toHaveBeenCalledOnce();
    h.source.emitState({ status: 'reconnecting' });
  });
  const started = await h.recorder.start({ indoor: true });
  expect(started.phase).toBe('running');
  expect(started.streams.controller.status).toBe('waiting');
  expect(h.append).not.toHaveBeenCalled();
  h.time(20);
  h.source.emitState({ status: 'connected' });
  h.sample(1);
  await vi.advanceTimersByTimeAsync(250);
  expect(h.append.mock.calls[0]![2]).toHaveLength(1);
  expect(h.append.mock.calls[0]![2][0]!.elapsedSeconds).toBe(10);
  expect((await h.recorder.stop(started.id!)).phase).toBe('completed');
});
it.each(['controller', 'calculated'] as const)(
  'passes the admitted sample rate and freezes %s consumption at durable Start',
  async batteryConsumptionSource => {
    const h = harness();
    await h.source.connect();
    const requested = { indoor: true, sampleHz: 8 as const, batteryConsumptionSource };
    const started = await h.recorder.start(requested);
    requested.batteryConsumptionSource = batteryConsumptionSource === 'controller' ? 'calculated' : 'controller';
    expect(h.begin).toHaveBeenCalledWith(
      expect.any(String),
      { indoor: true, recordGPS: false, sampleHz: 8, distanceSource: 'controller', batteryConsumptionSource },
      expect.any(String),
    );
    await h.recorder.discard(started.id!);
  },
);

it.each([
  ['the bike rejects the sample rate', 'bikeUnavailable'],
  ['the bike changes while starting', 'startInterrupted'],
] as const)(
  'removes a start that fails after the ride began when %s and admits the next Start',
  async (_name, code) => {
    const h = harness();
    await h.source.connect();
    h.sample(0);
    const discard = vi.mocked(h.store.discard);
    if (code === 'bikeUnavailable')
      vi.spyOn(h.source, 'setSampleRate').mockRejectedValueOnce(
        new DOMException('GATT operation failed for unknown reason.', 'NetworkError'),
      );
    else
      vi.spyOn(h.source, 'getState').mockResolvedValueOnce({
        ...(await h.source.getState()),
        deviceId: 'another-bike',
      });
    const failed = await h.recorder.start({ indoor: true }).catch(error => error);
    expect(failed).toMatchObject({ code, message: presentationFailure(code).text });
    expect(h.begin).toHaveBeenCalledOnce();
    expect(discard).toHaveBeenCalledOnce();
    expect(discard.mock.calls[0]![0]).toBe((await h.begin.mock.results[0]!.value).id);
    expect(await h.recorder.getState()).toMatchObject({ id: null, phase: 'idle', failure: null, pendingAction: null });
    const started = await h.recorder.start({ indoor: true });
    try {
      expect(started.phase).toBe('running');
    } finally {
      await h.recorder.discard(started.id!);
    }
  },
);

it('interrupts at the last committed reading on storage failure, without replaying lost samples', async () => {
  const h = harness();
  await h.source.connect();
  const started = await h.recorder.start({ indoor: true, sampleHz: 8 });
  h.time(11);
  h.sample(1);
  await vi.advanceTimersByTimeAsync(250);
  h.append.mockRejectedValueOnce(new Error('quota'));
  h.time(12);
  h.sample(2);
  await vi.advanceTimersByTimeAsync(250);
  expect((await h.recorder.getState()).phase).toBe('interrupted');
  await vi.advanceTimersByTimeAsync(60000);
  expect(h.append).toHaveBeenCalledTimes(2);
  h.time(90);
  const finished = await h.recorder.stop(started.id!);
  expect(finished.phase).toBe('completed');
  expect(h.record().elapsedSeconds).toBe(1);
});

it('discards after storage failure without waiting for a retained retry journal', async () => {
  const h = harness();
  await h.source.connect();
  const started = await h.recorder.start({ indoor: true });
  h.append.mockRejectedValueOnce(new Error('quota'));
  h.time(11);
  h.sample(1);
  await vi.advanceTimersByTimeAsync(250);
  expect((await h.recorder.discard(started.id!)).phase).toBe('idle');
  expect(h.append).toHaveBeenCalledTimes(1);
});

it('preserves running time and sample continuity across a slow Lap commit', async () => {
  const h = harness();
  await h.source.connect();
  const started = await h.recorder.start({ indoor: true, sampleHz: 8 });
  h.time(20);
  h.sample(1);
  const append = h.append.getMockImplementation()!;
  h.append.mockImplementationOnce(async (...args) => {
    h.time(25);
    return append(...args);
  });
  await h.recorder.lap();
  h.time(30);
  h.sample(2);
  await h.recorder.stop(started.id!);
  expect(h.record().timerSeconds).toBe(20);
  expect(h.append.mock.calls.flatMap(call => call[2]).map(row => row.interval)).toEqual([0, 0]);
});

it('bounds a stalled writer and marks the lost span without mutating its in-flight batch', async () => {
  const h = harness();
  await h.source.connect();
  const started = await h.recorder.start({ indoor: true, sampleHz: 8 });
  const append = h.append.getMockImplementation()!;
  let release!: () => void;
  const stalled = new Promise<void>(resolve => {
    release = resolve;
  });
  h.append.mockImplementationOnce(async (...args) => {
    await stalled;
    return append(...args);
  });
  for (let sequence = 0; sequence < 308; sequence++) {
    h.time(11 + sequence / 8);
    h.sample(sequence);
  }
  expect(h.append).toHaveBeenCalledOnce();
  expect(h.append.mock.calls[0]![2].map(row => row.interruptionIndex)).toEqual(Array(8).fill(0));
  release();
  await h.recorder.stop(started.id!);
  const rows = h.append.mock.calls.flatMap(call => call[2]);
  expect(rows.map(row => row.originalSequence)).toEqual([
    ...Array.from({ length: 8 }, (_, i) => i),
    ...Array.from({ length: 256 }, (_, i) => i + 52),
  ]);
  expect(rows.slice(8).every(row => row.interruptionIndex === 44)).toBe(true);
  expect(rows.at(-1)!.elapsedSeconds).toBe(1 + 307 / 8);
});

it('marks a short hidden-tab gap even when the controller connection remains the same', async () => {
  const document = Object.assign(new EventTarget(), { visibilityState: 'visible' });
  vi.stubGlobal('document', document);
  const h = harness();
  await h.source.connect();
  const started = await h.recorder.start({ indoor: true, sampleHz: 8 });
  h.time(11);
  h.sample(1);
  await vi.advanceTimersByTimeAsync(250);
  document.visibilityState = 'hidden';
  document.dispatchEvent(new Event('visibilitychange'));
  h.time(11.5);
  h.sample(2);
  document.visibilityState = 'visible';
  document.dispatchEvent(new Event('visibilitychange'));
  h.time(12);
  h.sample(3);
  await h.recorder.stop(started.id!);
  const rows = h.append.mock.calls.flatMap(call => call[2]);
  expect(rows.map(row => row.originalSequence)).toEqual([1, 3]);
  expect(rows.map(row => row.interruptionIndex)).toEqual([0, 2]);
  expect(rows[0]!.connectionEpoch).toBe(rows[1]!.connectionEpoch);
});
