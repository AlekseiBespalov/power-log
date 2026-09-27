import { afterEach, expect, it, vi } from 'vitest';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import { BrowserRideStore } from '../../src/services/browser-ride-store';
import {
  effectiveWorkoutOptions,
  unavailableWorkoutState,
  type WorkoutOptions,
  type WorkoutState,
} from '../../src/core/workouts';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { browserRide } from '../support/browser-ride';
import rideSnapshots from '../fixtures/contract/ride-snapshots.json';
import rideOptions from '../fixtures/contract/ride-options.json';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it('reports foreground browser recording without native owners, Health or GPS', async () => {
  const recorder = new BrowserWorkoutRecorder();
  const state = await recorder.getState();
  expect(state.capabilities).toEqual({
    phoneWorkout: false,
    watchWorkout: false,
    phoneHealth: false,
    watchHealth: false,
    healthProvider: null,
    gps: false,
    foregroundOnly: true,
  });
  const options = effectiveWorkoutOptions(
    { indoor: false, useWatch: true, saveToHealth: true, recordGPS: true },
    state.capabilities,
  );
  expect(options).toEqual({ indoor: false, useWatch: false, saveToHealth: false, recordGPS: false, sampleHz: 2 });
  expect(await recorder.requestPermissions(options)).toMatchObject({
    health: { available: false },
    location: 'unavailable',
  });
});

function expectSnapshotKeys(actual: object, expected: object) {
  expect(Object.keys(actual).sort()).toEqual(Object.keys(expected).sort());
  for (const [key, value] of Object.entries(expected)) {
    const received: unknown = (actual as Record<string, unknown>)[key];
    expect(received).not.toBeUndefined();
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      expect(received).not.toBeNull();
      expect(typeof received).toBe('object');
      expectSnapshotKeys(received as object, value);
    }
  }
}

it('keeps the retained snapshot key set in browser reads, commands and events', async () => {
  vi.stubGlobal('indexedDB', {});
  vi.stubGlobal('navigator', {
    locks: { request: (_name: string, _options: unknown, work: (lock: object) => Promise<void>) => work({}) },
  });
  const store = new BrowserRideStore();
  const record = browserRide();
  vi.spyOn(store, 'recoverOrphan').mockResolvedValue(null);
  vi.spyOn(store, 'begin').mockResolvedValue(record);
  vi.spyOn(store, 'transition').mockImplementation(async (_id, _token, action) => ({
    ...record,
    phase: action === 'save' ? 'completed' : 'paused',
    endedAt: action === 'save' ? '2026-01-01T00:00:01.000Z' : undefined,
  }));
  vi.spyOn(store, 'remove').mockResolvedValue();
  const recorder = new BrowserWorkoutRecorder(store);
  const source = new TestTelemetryAdapter();
  recorder.setTelemetrySource(source);
  await source.connect();
  const states: WorkoutState[] = [await recorder.getState()];
  const unsubscribe = recorder.subscribe(state => states.push(state));
  try {
    states.push(await recorder.start({ indoor: true }));
    states.push(await recorder.pause());
    states.push(await recorder.stop());
    states.push(await recorder.remove(record.id));
    states.push(await recorder.getState());
    expect(new Set(states.map(state => state.phase))).toEqual(new Set(['idle', 'running', 'paused', 'completed']));
    for (const fixture of rideSnapshots.cases) {
      expectSnapshotKeys(unavailableWorkoutState, fixture.wire);
      for (const state of states) {
        expectSnapshotKeys(state, fixture.wire);
        expectSnapshotKeys(JSON.parse(JSON.stringify(state)) as object, fixture.wire);
        expect(state.sealRevision).toBeNull();
        expect(state.verifiedSealRevision).toBeNull();
      }
    }
  } finally {
    unsubscribe();
    if ((await recorder.getState()).id === record.id) await recorder.discard(record.id);
  }
});

const invalidOptions = [
  ...[...rideOptions.rejectedSampleRates, ...rideOptions.rejectedSampleRateTypes, NaN, Infinity, -Infinity].map(
    sampleHz => ({ indoor: false, sampleHz }),
  ),
  ...rideOptions.rejectedOptionValues.map(({ field, value }) => ({ indoor: false, [field]: value })),
  {},
];

it.each(invalidOptions)('rejects malformed browser options before any recording effect: %j', async input => {
  const requestLock = vi.fn();
  vi.stubGlobal('navigator', { locks: { request: requestLock } });
  const store = new BrowserRideStore();
  const recover = vi.spyOn(store, 'recoverOrphan');
  const begin = vi.spyOn(store, 'begin');
  const recorder = new BrowserWorkoutRecorder(store);
  const source = new TestTelemetryAdapter();
  recorder.setTelemetrySource(source);
  await source.connect();
  const readSource = vi.spyOn(source, 'getState');
  const setOwner = vi.spyOn(source, 'setWorkoutOwner');
  const listener = vi.fn();
  const unsubscribe = recorder.subscribe(listener);
  try {
    await expect(recorder.requestPermissions(input as WorkoutOptions)).rejects.toThrow();
    await expect(recorder.start(input as WorkoutOptions)).rejects.toThrow();
    expect(requestLock).not.toHaveBeenCalled();
    expect(recover).not.toHaveBeenCalled();
    expect(begin).not.toHaveBeenCalled();
    expect(readSource).not.toHaveBeenCalled();
    expect(setOwner).not.toHaveBeenCalled();
    expect(source.sampleRates).toEqual([]);
    expect(listener).not.toHaveBeenCalled();
  } finally {
    unsubscribe();
  }
});
