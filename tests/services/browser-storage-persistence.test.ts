import { afterEach, describe, expect, it, vi } from 'vitest';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { browserRide } from '../support/browser-ride';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe('browser storage persistence', () => {
  it.each([true, false, 'reject', 'missing'] as const)('reports storage status: %s', async result => {
    vi.stubGlobal('navigator', {
      storage:
        result === 'missing'
          ? undefined
          : {
              persisted: async () => {
                if (result === 'reject') throw new Error('Denied');
                return result;
              },
            },
    });
    const { browserStoragePersistence } = await import('../../src/services/browser-ride-store');
    expect(await browserStoragePersistence()).toBe(
      result === true ? 'persisted' : result === false ? 'not persisted' : 'unavailable',
    );
  });
  it.each(['granted', 'denied', 'reject', 'missing', 'pending'] as const)(
    'requests once at first recording without blocking capture: %s',
    async result => {
      vi.resetModules();
      const persist = vi.fn(() =>
        result === 'pending'
          ? new Promise<boolean>(() => {})
          : result === 'reject'
            ? Promise.reject(new Error('Denied'))
            : Promise.resolve(result === 'granted'),
      );
      vi.stubGlobal('navigator', {
        storage: result === 'missing' ? undefined : { persist },
        locks: { request: (_name: string, _options: unknown, work: (lock: object) => Promise<void>) => work({}) },
      });
      vi.stubGlobal('indexedDB', {});
      const { BrowserRideStore } = await import('../../src/services/browser-ride-store');
      const { BrowserWorkoutRecorder } = await import('../../src/services/browser-workout-recorder');
      const store = new BrowserRideStore(),
        source = new TestTelemetryAdapter(),
        record = browserRide();
      vi.spyOn(store, 'recoverOrphan').mockResolvedValue(null);
      vi.spyOn(store, 'begin').mockResolvedValue(record);
      vi.spyOn(store, 'remove').mockResolvedValue();
      const recorder = new BrowserWorkoutRecorder(store);
      recorder.setTelemetrySource(source);
      await source.connect();
      expect(persist).not.toHaveBeenCalled();
      try {
        expect((await recorder.start({ indoor: true, useWatch: false })).phase).toBe('running');
        await recorder.discard(record.id);
        expect((await recorder.start({ indoor: true, useWatch: false })).phase).toBe('running');
        expect(persist).toHaveBeenCalledTimes(result === 'missing' ? 0 : 1);
      } finally {
        await recorder.discard(record.id);
      }
    },
  );
});
