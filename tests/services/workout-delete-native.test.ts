import { beforeEach, describe, expect, it, vi } from 'vitest';
import { workouts } from '../../src/services/workouts.native';
import { unavailableWorkoutState } from '../../src/core/workouts';

const native = vi.hoisted(() => ({ getWorkoutState: vi.fn(), deleteWorkout: vi.fn() }));
vi.mock('../../modules/cyc-bridge', () => ({ default: native }));
beforeEach(() => { vi.clearAllMocks(); });

describe('native ride deletion', () => {
  it('passes only the explicitly selected ride identity to the native deletion boundary', async () => {
    native.deleteWorkout.mockResolvedValue(unavailableWorkoutState);
    expect(await workouts.remove('selected-old-ride')).toBe(unavailableWorkoutState);
    expect(native.deleteWorkout).toHaveBeenCalledExactlyOnceWith('selected-old-ride');
    expect(native.getWorkoutState).not.toHaveBeenCalled();
  });
  it('propagates refusal rather than treating an active or busy ride as deleted', async () => {
    native.deleteWorkout.mockRejectedValue(new Error('Finish this ride first.'));
    await expect(workouts.remove('still-active')).rejects.toThrow('Finish this ride first.');
  });
});
