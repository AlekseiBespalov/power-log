import { describe, expect, it } from 'vitest';
import {
  effectiveWorkoutOptions,
  normalizeWorkoutState,
  unavailableWorkoutState,
  type WorkoutState,
} from '../../src/core/workouts';
import { workoutHealthPresentation, workoutSourcePresentation } from '../../src/core/workout-presentation';
import { workoutPermissionAction } from '../../src/core/workout-permissions';

const profiles: { name: string; capabilities: WorkoutState['capabilities'] }[] = [
  {
    name: 'iOS 16',
    capabilities: {
      phoneWorkout: true,
      watchWorkout: false,
      phoneHealth: false,
      watchHealth: false,
      healthProvider: 'appleHealth',
      gps: true,
      foregroundOnly: false,
    },
  },
  {
    name: 'iOS 17–25',
    capabilities: {
      phoneWorkout: true,
      watchWorkout: true,
      phoneHealth: false,
      watchHealth: true,
      healthProvider: 'appleHealth',
      gps: true,
      foregroundOnly: false,
    },
  },
  {
    name: 'iOS 26',
    capabilities: {
      phoneWorkout: true,
      watchWorkout: true,
      phoneHealth: true,
      watchHealth: true,
      healthProvider: 'appleHealth',
      gps: true,
      foregroundOnly: false,
    },
  },
  {
    name: 'iPhone without HealthKit',
    capabilities: {
      phoneWorkout: true,
      watchWorkout: false,
      phoneHealth: false,
      watchHealth: false,
      healthProvider: null,
      gps: true,
      foregroundOnly: false,
    },
  },
  {
    name: 'Android with Health Connect',
    capabilities: {
      phoneWorkout: true,
      watchWorkout: false,
      phoneHealth: true,
      watchHealth: false,
      healthProvider: 'healthConnect',
      gps: true,
      foregroundOnly: false,
    },
  },
  {
    name: 'Android without Health Connect',
    capabilities: {
      phoneWorkout: true,
      watchWorkout: false,
      phoneHealth: false,
      watchHealth: false,
      healthProvider: null,
      gps: true,
      foregroundOnly: false,
    },
  },
  {
    name: 'web',
    capabilities: {
      phoneWorkout: false,
      watchWorkout: false,
      phoneHealth: false,
      watchHealth: false,
      healthProvider: null,
      gps: false,
      foregroundOnly: true,
    },
  },
];

describe.each(profiles)('$name effective options', ({ capabilities }) => {
  it.each([true, false, undefined])(
    'keeps Health preference %s through Phone → Watch → Phone before Start',
    saveToHealth => {
      for (const useWatch of [false, true, false]) {
        const requested = Object.freeze({ indoor: false, useWatch, saveToHealth, sampleHz: 8 as const });
        const effective = effectiveWorkoutOptions(requested, capabilities);
        const watchOwner = useWatch && capabilities.watchWorkout;
        expect(effective).toEqual({
          indoor: false,
          useWatch: watchOwner,
          saveToHealth: saveToHealth !== false && (watchOwner ? capabilities.watchHealth : capabilities.phoneHealth),
          recordGPS: capabilities.gps,
          sampleHz: 8,
        });
        expect(requested.saveToHealth).toBe(saveToHealth);
      }
    },
  );

  it('keeps the capability contract at the snapshot boundary', () => {
    expect(normalizeWorkoutState({ ...unavailableWorkoutState, capabilities }).capabilities).toEqual(capabilities);
  });
});

it('uses each owner’s Health capability independently', () => {
  for (const watchWorkout of [true, false]) {
    for (const phoneHealth of [true, false]) {
      for (const watchHealth of [true, false]) {
        const capabilities = {
          phoneWorkout: true,
          watchWorkout,
          phoneHealth,
          watchHealth,
          healthProvider: 'appleHealth' as const,
          gps: true,
          foregroundOnly: false,
        };
        expect(effectiveWorkoutOptions({ indoor: true, useWatch: false }, capabilities).saveToHealth).toBe(phoneHealth);
        expect(effectiveWorkoutOptions({ indoor: true, useWatch: true }, capabilities).saveToHealth).toBe(
          watchWorkout ? watchHealth : phoneHealth,
        );
      }
    }
  }
});

it('defaults GPS from indoor mode, honors explicit choices and disables unavailable GPS', () => {
  const capabilities = profiles[1]!.capabilities;
  for (const useWatch of [true, false]) {
    for (const indoor of [true, false]) {
      expect(effectiveWorkoutOptions({ indoor, useWatch }, capabilities).recordGPS).toBe(!indoor);
      for (const recordGPS of [true, false]) {
        expect(effectiveWorkoutOptions({ indoor, useWatch, recordGPS }, capabilities).recordGPS).toBe(recordGPS);
        expect(
          effectiveWorkoutOptions({ indoor, useWatch, recordGPS }, { ...capabilities, gps: false }).recordGPS,
        ).toBe(false);
      }
    }
  }
  expect(effectiveWorkoutOptions({ indoor: true, useWatch: false }, capabilities).sampleHz).toBe(2);
});

it('does not treat malformed capability values as grants or providers', () => {
  expect(
    normalizeWorkoutState({
      ...unavailableWorkoutState,
      capabilities: {
        phoneWorkout: 'true',
        watchWorkout: 1,
        phoneHealth: 'yes',
        watchHealth: null,
        healthProvider: 'unknown',
        gps: 1,
        foregroundOnly: 'true',
      },
    }).capabilities,
  ).toEqual({
    phoneWorkout: false,
    watchWorkout: false,
    phoneHealth: false,
    watchHealth: false,
    healthProvider: null,
    gps: false,
    foregroundOnly: false,
  });
});

it('explains unavailable phone Health saving while keeping the preference usable by the Watch', () => {
  const capabilities = profiles[1]!.capabilities;
  const requested = { indoor: true, useWatch: false, saveToHealth: true };
  expect(workoutHealthPresentation(requested, capabilities)).toEqual({
    label: 'Apple Health',
    detail: 'Phone-recorded rides cannot be saved to Apple Health on this device. This ride will stay in Power Log.',
  });
  expect(workoutPermissionAction(null, effectiveWorkoutOptions(requested, capabilities)).action).toBe('none');
  expect(workoutHealthPresentation({ ...requested, useWatch: true }, capabilities).detail).toBeNull();
  expect(workoutHealthPresentation({ ...requested, saveToHealth: false }, capabilities).detail).toBeNull();
  expect(workoutHealthPresentation(requested, profiles[4]!.capabilities).label).toBe('Health Connect');
});

it('presents only available sources before Start and retains the frozen owner during a ride', () => {
  const state = { ...unavailableWorkoutState, capabilities: profiles[0]!.capabilities };
  const requested = { indoor: false, useWatch: true };
  expect(workoutSourcePresentation(state, requested, true)).toMatchObject({
    heartRate: { label: 'Not selected' },
    gps: { detail: 'Phone' },
  });
  expect(
    workoutSourcePresentation(
      {
        ...state,
        phase: 'running',
        useWatch: true,
        streams: { ...state.streams, heartRate: { status: 'receiving' } },
      },
      { indoor: true, useWatch: false },
      true,
    ).heartRate,
  ).toMatchObject({ label: 'Receiving samples', detail: 'Apple Watch' });
});
