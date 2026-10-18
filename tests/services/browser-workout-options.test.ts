import { afterEach, expect, it, vi } from 'vitest';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import { BrowserRideStore } from '../../src/services/browser-ride-store';
import { effectiveWorkoutOptions, type WorkoutOptions } from '../../src/core/workouts';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { browserRide } from '../support/browser-ride';
import rideOptions from '../fixtures/contract/ride-options.json';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it('reports unavailable GPS when the browser has no Geolocation API', async () => {
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
  expect(options).toEqual({
    indoor: false,
    useWatch: false,
    saveToHealth: false,
    recordGPS: false,
    sampleHz: 2,
    distanceSource: 'controller',
    batteryConsumptionSource: 'controller',
  });
  expect(await recorder.requestPermissions(options)).toMatchObject({
    health: { available: false },
    location: 'unavailable',
  });
});

it('reports a foreign owner without inventing a pending action and rejects every ride control', async () => {
  vi.stubGlobal('indexedDB', {});
  vi.stubGlobal('navigator', {
    locks: { request: (_name: string, _options: unknown, work: (lock: null) => Promise<void>) => work(null) },
  });
  const store = new BrowserRideStore();
  const record = browserRide();
  vi.spyOn(store, 'current').mockResolvedValue(record);
  const transition = vi.spyOn(store, 'transition');
  const remove = vi.spyOn(store, 'remove');
  const recorder = new BrowserWorkoutRecorder(store);
  const state = await recorder.getState();
  expect(state.pendingAction).toBeNull();
  expect(state.foreignOwner).toBe('anotherBrowserTab');
  for (const command of ['pause', 'resume', 'lap', 'stop', 'discard'] as const)
    await expect(recorder[command](record.id)).rejects.toMatchObject({ code: 'ownerForeign' });
  expect(transition).not.toHaveBeenCalled();
  expect(remove).not.toHaveBeenCalled();
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
  const recover = vi.spyOn(store, 'interruptCurrent');
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

it('loads the approved browser GPS options through the production adapter', async () => {
  const fixture = rideOptions.cases.find(item => item.capabilities === 'browser')!;
  vi.stubGlobal('navigator', {
    geolocation: { getCurrentPosition: (receive: PositionCallback) => receive({} as GeolocationPosition) },
    permissions: { query: async () => ({ state: 'granted' }) },
  });
  const recorder = new BrowserWorkoutRecorder();
  const state = await recorder.getState();
  expect(state.capabilities).toEqual(rideOptions.capabilities.browser);
  expect(effectiveWorkoutOptions(fixture.input, state.capabilities)).toEqual({
    ...fixture.expected,
    batteryConsumptionSource: 'controller',
  });
  expect(await recorder.requestPermissions(fixture.input as WorkoutOptions)).toMatchObject({
    location: 'authorizedWhenInUse',
    locationAccuracyAuthorization: 'unknown',
  });
});
