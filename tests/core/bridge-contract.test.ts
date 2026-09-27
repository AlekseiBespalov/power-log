import { describe, expect, it } from 'vitest';
import { effectiveWorkoutOptions, normalizeWorkoutState, type WorkoutState } from '../../src/core/workouts';
import {
  workoutFinishPresentation,
  workoutHealthPresentation,
  workoutSourcePresentation,
} from '../../src/core/workout-presentation';
import { workoutHistoryRevision } from '../../src/services/workout-state-delivery';
import rideSnapshots from '../fixtures/contract/ride-snapshots.json';
import rideOptions from '../fixtures/contract/ride-options.json';

const snapshots = rideSnapshots.cases as { platform: string; name: string; wire: WorkoutState }[];
const capabilities = rideOptions.capabilities as Record<string, WorkoutState['capabilities']>;

describe('shared ride snapshot consumers', () => {
  const expected: Record<string, object> = {
    'ios/idle': {
      sources: { bike: { label: 'Not connected' }, gps: { label: 'Off' } },
      health: { label: 'Apple Health', detail: null },
    },
    'ios/phone ride running with Health and GPS': {
      sources: {
        bike: { label: 'Receiving samples' },
        heartRate: { label: 'Waiting for heart rate' },
        gps: { label: 'Receiving GPS', detail: 'Phone · ±4 m' },
      },
      health: { label: 'Apple Health', detail: null },
    },
    'ios/Watch ride finishing after Health saved': {
      sources: { gps: { label: 'Weak GPS signal', detail: 'Watch' } },
      finish: { label: 'Syncing…', detail: 'Saved to Apple Health. Syncing Watch data…' },
    },
    'ios/unresolved recovery with an error': {
      sources: { bike: { label: 'Not selected' }, gps: { label: 'Off' } },
      recoveryMessage: 'Stop requested from the original owner. No recording completion has been confirmed.',
      error: 'Bluetooth is turned off.',
    },
    'ios/deletion signal': {
      sources: { bike: { label: 'Not connected' } },
      history: '[null,"idle","5b1d7c9e-0a4f-4e0b-8a61-7d2c3e9f4a10",null]',
    },
    'ios/largest safe revision': {
      sources: { gps: { label: 'Off' } },
      history:
        '["3f6c1f0e-6f2d-4c43-9b7e-2a4d8f1b9c21","completed","",[9007199254740991,9007199254740991,9007199254740991,"complete"]]',
    },
    'android/idle': {
      sources: { gps: { label: 'Off' } },
      health: { label: 'Health Connect', detail: null },
    },
    'android/ride running with Health Connect': {
      sources: {
        bike: { label: 'Receiving samples' },
        heartRate: { label: 'Not selected' },
        gps: { label: 'Receiving GPS', detail: 'Phone · ±4 m' },
      },
      health: { label: 'Health Connect', detail: null },
    },
    'android/paused indoor ride without Health Connect': {
      sources: { bike: { label: 'Paused' }, gps: { label: 'Off for indoor ride' } },
      health: { label: 'Health', detail: null },
    },
  };

  it.each(snapshots)('$platform: $name', ({ platform, name, wire }) => {
    const state = normalizeWorkoutState(wire);
    const presentation = {
      sources: workoutSourcePresentation(state, state, false),
      health: workoutHealthPresentation(state, state.capabilities),
      finish: workoutFinishPresentation(state),
      history: workoutHistoryRevision(state),
      recoveryMessage: state.recoveryMessage,
      error: state.error,
    };
    expect(expected[`${platform}/${name}`]).toBeDefined();
    expect(presentation).toMatchObject(expected[`${platform}/${name}`]!);
    if (wire.streams.gps.accuracyMeters === null) expect(state.streams.gps.accuracyMeters).toBeNull();
    if (name === 'largest safe revision') {
      expect(state.collectionRevision).toBe(Number.MAX_SAFE_INTEGER);
      expect(state.sealRevision).toBe(Number.MAX_SAFE_INTEGER);
      expect(state.verifiedSealRevision).toBe(Number.MAX_SAFE_INTEGER);
    }
    if (name === 'deletion signal') {
      expect(state.lastDeletedWorkoutId).toBe('3f6c1f0e-6f2d-4c43-9b7e-2a4d8f1b9c21');
      expect(presentation.history).not.toBe(workoutHistoryRevision({ ...state, historyRevision: '' }));
    }
  });

  it.each(snapshots.filter(({ wire }) => wire.phase === 'running'))(
    '$platform: unknown stream statuses become waiting and null accuracy stays unavailable',
    ({ wire }) => {
      const state = normalizeWorkoutState({
        ...wire,
        streams: {
          cyc: { status: 'future' },
          heartRate: { status: 'future' },
          gps: { status: 'future', source: 'phone', accuracyMeters: null },
        },
      });
      expect(state.streams).toEqual({
        cyc: { status: 'waiting' },
        heartRate: { status: 'waiting' },
        gps: { status: 'waiting', source: 'phone', accuracyMeters: null },
      });
      expect(workoutSourcePresentation(state, state, false).gps).toEqual({
        label: 'Waiting for GPS',
        detail: 'Phone',
        tone: 'neutral',
      });
      state.streams.gps.status = 'receiving';
      expect(workoutSourcePresentation(state, state, false).gps.detail).toBe('Phone');
    },
  );
});

describe('shared ride options policy', () => {
  it.each(rideOptions.cases)('$name', fixture => {
    expect(effectiveWorkoutOptions(fixture.input, capabilities[fixture.capabilities]!)).toEqual(fixture.expected);
  });

  describe.each(Object.entries(capabilities))('%s', (_name, profile) => {
    it.each([...rideOptions.rejectedSampleRates, ...rideOptions.rejectedSampleRateTypes, NaN, Infinity, -Infinity])(
      'rejects sample rate %j',
      sampleHz => {
        expect(() => effectiveWorkoutOptions({ indoor: false, sampleHz }, profile)).toThrow('Sample rate');
      },
    );
    it.each(rideOptions.rejectedOptionValues)('rejects $field = $value', ({ field, value }) => {
      expect(() => effectiveWorkoutOptions({ indoor: false, [field]: value }, profile)).toThrow(field);
    });
    it('requires an indoor boolean even when no optional capability is available', () => {
      expect(() => effectiveWorkoutOptions({ indoor: undefined }, profile)).toThrow('indoor');
    });
    it.each([false, true])('treats explicit null options as omitted with indoor = %s', indoor => {
      expect(
        effectiveWorkoutOptions(
          { indoor, useWatch: null, saveToHealth: null, recordGPS: null, sampleHz: null },
          profile,
        ),
      ).toEqual(effectiveWorkoutOptions({ indoor }, profile));
    });
  });
});
