import { afterEach, describe, expect, it, vi } from 'vitest';
import { deviceAdapter } from '../../src/services/device.native';
import { nativeMonitorSource } from '../../src/services/monitor-source.native';
import { samplePresentationTime } from '../../src/core/types';
import { SessionPresentation } from '../../src/services/session-presentation';
import { telemetryDisplay } from '../../src/core/telemetry-display';
import { syntheticSample } from '../fixtures/synthetic-sample';

const native = vi.hoisted(() => ({
  listeners: new Map<string, (value: unknown) => void>(),
  visibility: undefined as ((active: boolean) => void) | undefined,
  getMonotonicSeconds: vi.fn(),
  describeMonitorSource: vi.fn(),
}));
vi.mock('../../modules/cyc-bridge', () => ({
  default: {
    getMonotonicSeconds: native.getMonotonicSeconds,
    describeMonitorSource: native.describeMonitorSource,
    addListener: (name: string, listener: (value: unknown) => void) => {
      native.listeners.set(name, listener);
      return { remove: () => native.listeners.delete(name) };
    },
  },
}));
vi.mock('../../src/services/app-visibility', () => ({
  subscribeAppVisibility: (listener: (active: boolean) => void) => {
    native.visibility = listener;
    return () => {
      native.visibility = undefined;
    };
  },
}));
afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});
const settle = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve();
};

describe('native telemetry delivery', () => {
  it('invalidates retained connection evidence throughout foreground resync after Android sleep', async () => {
    vi.useFakeTimers();
    native.getMonotonicSeconds.mockResolvedValue(1000);
    const presentation = new SessionPresentation();
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    const sample = vi.fn((value, delivery) => presentation.receiveSample(value, delivery));
    const unsubscribe = deviceAdapter.subscribe({ device: vi.fn(), state: vi.fn(), sample });
    try {
      await settle();
      native.listeners.get('onSample')!({ ...syntheticSample(0, 0), acquiredAtMonotonic: 1000 });
      await vi.advanceTimersByTimeAsync(250);
      expect(presentation.getSnapshot().display).toBe('live');
      const retained = sample.mock.calls[0]![1];
      native.visibility!(false);
      presentation.setActive(false);
      let finish!: (value: number) => void;
      native.getMonotonicSeconds.mockImplementationOnce(
        () =>
          new Promise(resolve => {
            finish = resolve;
          }),
      );
      native.visibility!(true);
      presentation.setActive(true);
      expect(samplePresentationTime(retained)).toBeNull();
      expect(presentation.getSnapshot().display).toBe('unavailable');
      let described = false;
      const describing = nativeMonitorSource('live')
        .describeSource({ generation: 1 })
        .then(() => {
          described = true;
        });
      await settle();
      expect(described).toBe(false);
      finish(4600.25);
      await describing;
      expect(samplePresentationTime(retained)).toBe(-3600);
      expect(presentation.getSnapshot().display).toBe('unavailable');
      expect(sample).toHaveBeenCalledTimes(1);
    } finally {
      presentation.setActive(false);
      unsubscribe();
    }
  });

  it('keeps an event created eight seconds before delivery stale across a UTC jump', async () => {
    vi.useFakeTimers();
    native.getMonotonicSeconds.mockImplementation(async () => performance.now() / 1000 + 1000);
    const sample = vi.fn();
    const unsubscribe = deviceAdapter.subscribe({ device: vi.fn(), state: vi.fn(), sample });
    try {
      await settle();
      const original = syntheticSample(1, 1, new Date().toISOString());
      const event = { ...original, acquiredAtMonotonic: 1000 };
      await vi.advanceTimersByTimeAsync(8000);
      vi.setSystemTime(Date.now() - 3_600_000);
      native.listeners.get('onSample')!(event);
      expect(sample).toHaveBeenLastCalledWith(original, expect.objectContaining({ acquiredAtMonotonic: 1000 }));
      expect(sample.mock.calls[0]![0]).not.toHaveProperty('acquiredAtMonotonic');
      expect(telemetryDisplay('connected', samplePresentationTime(sample.mock.calls[0]![1]), 8)).toBe('unavailable');
      for (const acquiredAtMonotonic of [-1, NaN, Infinity, undefined]) {
        native.listeners.get('onSample')!({ ...original, acquiredAtMonotonic });
        expect(samplePresentationTime(sample.mock.calls.at(-1)![1])).toBeNull();
      }
    } finally {
      unsubscribe();
    }
    expect(native.listeners.size).toBe(0);
    expect(native.visibility).toBeUndefined();
  });

  it('shares the clock with monitor sources and resynchronizes on foreground and every minute while subscribed', async () => {
    vi.useFakeTimers();
    let offset = 2000;
    native.getMonotonicSeconds.mockImplementation(async () => performance.now() / 1000 + offset);
    const sample = vi.fn();
    const unsubscribe = deviceAdapter.subscribe({ device: vi.fn(), state: vi.fn(), sample });
    try {
      await settle();
      const source = nativeMonitorSource('live');
      expect(source.mapMonotonicSeconds!(2000)).toBe(0);
      expect(native.getMonotonicSeconds).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(60_000);
      expect(native.getMonotonicSeconds).toHaveBeenCalledTimes(2);
      offset = 2001;
      native.visibility!(false);
      expect(native.getMonotonicSeconds).toHaveBeenCalledTimes(2);
      native.visibility!(true);
      await settle();
      expect(native.getMonotonicSeconds).toHaveBeenCalledTimes(3);
      expect(source.mapMonotonicSeconds!(2061)).toBe(60);
      native.listeners.get('onSample')!({ ...syntheticSample(1, 1), acquiredAtMonotonic: 2061 });
      expect(samplePresentationTime(sample.mock.calls.at(-1)![1])).toBe(60);
      const response = { nowSeconds: 10, monotonicAt: 2061 };
      native.describeMonitorSource.mockResolvedValue(response);
      expect(await source.describeSource({ generation: 1 })).toBe(response);
    } finally {
      unsubscribe();
    }
    await vi.advanceTimersByTimeAsync(60_000);
    expect(native.getMonotonicSeconds).toHaveBeenCalledTimes(3);
  });
});
