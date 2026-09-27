import { beforeEach, describe, expect, it, vi } from 'vitest';
import { workouts } from '../../src/services/workouts.native';
import { unavailableWorkoutState } from '../../src/core/workouts';

const native = vi.hoisted(() => {
  const bridge = {
    getWorkoutPermissions: vi.fn(),
    requestWorkoutPermissions: vi.fn(),
    discardWorkout: vi.fn(),
    exportWorkoutArchive: vi.fn(),
    listWorkouts: vi.fn(),
  };
  return { bridge: bridge as typeof bridge | null };
});
vi.mock('../../modules/cyc-bridge', () => ({
  get default() {
    return native.bridge;
  },
}));
beforeEach(() => {
  vi.clearAllMocks();
});

describe('native workout permissions', () => {
  it.each([true, false])('passes the ride options to the sole endpoint with Health %s', async saveToHealth => {
    const options = { indoor: true, useWatch: false, saveToHealth, recordGPS: false, sampleHz: 4 as const };
    const permissions = { health: { available: true, writeAuthorization: {} }, location: 'denied' };
    native.bridge!.requestWorkoutPermissions.mockResolvedValue(permissions);
    expect(await workouts.requestPermissions(options)).toBe(permissions);
    expect(native.bridge!.requestWorkoutPermissions).toHaveBeenCalledExactlyOnceWith(options);
  });

  it('propagates native validation errors', async () => {
    native.bridge!.requestWorkoutPermissions.mockRejectedValue(new Error('Sample rate must be 2, 4, or 8 Hz.'));
    await expect(workouts.requestPermissions({ indoor: true, useWatch: false, sampleHz: 3 as never })).rejects.toThrow(
      'Sample rate must be',
    );
  });

  it('dispatches required methods without per-method build gates', async () => {
    native.bridge!.discardWorkout.mockResolvedValue(unavailableWorkoutState);
    native.bridge!.exportWorkoutArchive.mockResolvedValue('file:///example.zip');
    native.bridge!.listWorkouts.mockResolvedValue([]);
    await workouts.getPermissions();
    expect(native.bridge!.getWorkoutPermissions).toHaveBeenCalledOnce();
    expect(await workouts.discard('ride')).toEqual(unavailableWorkoutState);
    expect(native.bridge!.discardWorkout).toHaveBeenCalledExactlyOnceWith('ride');
    expect(await workouts.exportOriginal('ride')).toBe('file:///example.zip');
    expect(await workouts.list()).toEqual({ records: [], unreadableCount: 0, unindexedCount: 0 });
  });

  it('keeps development-only examples optional', async () => {
    await expect(workouts.addExampleRides!()).rejects.toThrow('Example rides need a development build.');
  });

  it('handles an absent native module with a single module gate', async () => {
    const bridge = native.bridge;
    native.bridge = null;
    try {
      expect(await workouts.getState()).toEqual(unavailableWorkoutState);
      expect(await workouts.list()).toEqual({ records: [], unreadableCount: 0, unindexedCount: 0 });
      expect(() => workouts.subscribe(vi.fn())()).not.toThrow();
      expect(() => workouts.start({ indoor: true, useWatch: false })).toThrow('Install the current Power Log build');
      expect(() => workouts.requestPermissions({ indoor: true, useWatch: false })).toThrow(
        'Install the current Power Log build',
      );
    } finally {
      native.bridge = bridge;
    }
  });
});
