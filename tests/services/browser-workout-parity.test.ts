import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import { BrowserRideStore } from '../../src/services/browser-ride-store';
import { workoutSourcePresentation } from '../../src/core/workout-presentation';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { browserRide } from '../support/browser-ride';
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
  const record = browserRide();
  vi.spyOn(store, 'recoverOrphan').mockResolvedValue(null);
  const begin = vi.spyOn(store, 'begin').mockImplementation(async () => {
    record.phase = 'running';
    return record;
  });
  vi.spyOn(store, 'remove').mockResolvedValue();
  const append = vi.spyOn(store, 'append').mockResolvedValue(record);
  vi.spyOn(store, 'transition').mockImplementation(async (_id, _token, action) => {
    record.phase = action === 'pause' ? 'paused' : action === 'resume' ? 'running' : 'completed';
    return record;
  });
  const recorder = new BrowserWorkoutRecorder(store, () => now);
  const source = new TestTelemetryAdapter(() => now);
  recorder.setTelemetrySource(source);
  return {
    recorder,
    source,
    begin,
    append,
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
  expect((await h.recorder.getState()).streams.cyc.status).toBe('off');
  await h.recorder.start({ indoor: true });
  try {
    expect((await h.recorder.getState()).streams.cyc.status).toBe('waiting');
    h.time(11);
    h.sample(1);
    await vi.advanceTimersByTimeAsync(250);
    expect((await h.recorder.getState()).streams.cyc.status).toBe('receiving');
    h.time(16.999);
    expect((await h.recorder.getState()).streams.cyc.status).toBe('receiving');
    h.time(17);
    const stale = await h.recorder.getState();
    expect(stale.streams.cyc.status).toBe('stale');
    expect(workoutSourcePresentation(stale, { indoor: true }, false).bike.label).toBe('No recent samples');
    expect((await h.recorder.pause()).streams.cyc.status).toBe('paused');
    h.sample(2);
    await vi.advanceTimersByTimeAsync(250);
    expect((await h.recorder.getState()).streams.cyc.status).toBe('paused');
    expect((await h.recorder.resume()).streams.cyc.status).toBe('receiving');
    expect((await h.recorder.stop()).streams.cyc.status).toBe('off');
    await h.recorder.start({ indoor: true });
    expect((await h.recorder.getState()).streams.cyc.status).toBe('waiting');
  } finally {
    await h.recorder.discard((await h.recorder.getState()).id!);
  }
});
it.each([0.1, 5.999])('admits Start during a %s second reconnect without recording held values', async age => {
  const h = harness();
  await h.source.connect();
  h.sample(0);
  h.time(10 + age);
  h.source.emitState({ status: 'reconnecting' });
  const started = await h.recorder.start({ indoor: true });
  try {
    expect(started.phase).toBe('running');
    expect(started.streams.cyc.status).toBe('waiting');
    expect(h.append).not.toHaveBeenCalled();
    h.time(20);
    h.source.emitState({ status: 'connected' });
    h.sample(1);
    await vi.advanceTimersByTimeAsync(250);
    expect(h.append.mock.calls[0]![2]).toHaveLength(1);
    expect(h.append.mock.calls[0]![2][0]!.elapsedSeconds).toBeCloseTo(10 - age);
  } finally {
    await h.recorder.discard(started.id!);
  }
});
it.each(['expired', 'never received', 'disconnected'] as const)('refuses Start when the source is %s', async reason => {
  const h = harness();
  await h.source.connect();
  if (reason !== 'never received') h.sample(0);
  h.time(16);
  h.source.emitState({ status: reason === 'disconnected' ? 'idle' : 'reconnecting' });
  await expect(h.recorder.start({ indoor: true })).rejects.toThrow('Connect a data source first');
  expect(h.begin).not.toHaveBeenCalled();
});
