import { describe, expect, it } from 'vitest';
import { effectiveWorkoutOptions, normalizeWorkoutState, type WorkoutState } from '../../src/core/workouts';
import {
  workoutFinishPresentation,
  workoutHealthPresentation,
  workoutSourcePresentation,
  workoutStartReadiness,
} from '../../src/core/workout-presentation';
import { workoutHistoryRevision } from '../../src/services/workout-state-delivery';
import { presentationFailure, presentationFailureCodes } from '../../shared/js';
import rideSnapshots from '../fixtures/contract/ride-snapshots.json';
import rideOptions from '../fixtures/contract/ride-options.json';
import failures from '../fixtures/contract/presentation/failures.json';

const snapshots = rideSnapshots.cases as unknown as { platform: string; name: string; wire: WorkoutState }[];
const capabilities = rideOptions.capabilities as Record<string, WorkoutState['capabilities']>;

describe('shared ride snapshot consumers', () => {
  it.each(snapshots)('$platform: $name preserves local identity, phase and Health state', ({ wire }) => {
    const state = normalizeWorkoutState(wire);
    expect(state.id).toBe(wire.id);
    expect(state.phase).toBe(wire.phase);
    expect(state.healthKitState).toBe(wire.healthKitState);
    expect(state.collectionRevision).toBe(wire.collectionRevision ?? null);
    expect(workoutFinishPresentation(state).detail).not.toMatch(/sync|Watch to finish/);
    expect(workoutHealthPresentation(state, state.capabilities).label).toBeTruthy();
    expect(workoutHistoryRevision({ ...state, historyRevision: '99' })).not.toBe(workoutHistoryRevision(state));
  });
  it('normalizes unknown stream states without inventing GPS accuracy', () => {
    const wire = snapshots.find(value => value.wire.phase === 'running')!.wire;
    const state = normalizeWorkoutState({
      ...wire,
      streams: {
        controller: { status: 'future' },
        heartRate: { status: 'future' },
        gps: { status: 'future', source: 'phone', accuracyMeters: null },
      },
    });
    expect(state.streams.controller.status).toBe('waiting');
    expect(state.streams.gps.accuracyMeters).toBeNull();
    expect(workoutSourcePresentation(state, state, false).gps.detail).toBe('Phone');
  });
});

describe('shared failure and session facts', () => {
  const idle = snapshots.find(({ platform, name }) => platform === 'ios' && name === 'idle')!.wire;
  const options = { indoor: true, useWatch: false, saveToHealth: false, recordGPS: false, sampleHz: 8 as const };

  it('offers the whole shared vocabulary and nothing else', () => {
    expect(presentationFailureCodes().sort()).toEqual(failures.vocabulary.map(entry => entry.code).sort());
    for (const entry of failures.vocabulary)
      expect(presentationFailure(entry.code)).toMatchObject({
        ...entry,
        text: `${entry.message} ${entry.nextStep}`.trimEnd(),
      });
  });

  it.each(failures.bridge.snapshot.cases)('$platform: $name', ({ wire }) => {
    const state = normalizeWorkoutState({ ...idle, ...wire } as unknown as WorkoutState);
    expect(state.failure).toEqual(wire.failure);
    const readiness = workoutStartReadiness(state, options, true);
    if (wire.sessionBusy || ('endingOwner' in wire && wire.endingOwner))
      expect(readiness).toMatchObject({ available: false, error: 'rideFinishing' });
    else expect(readiness).toMatchObject({ available: true, error: null });
  });

  it.each(failures.unknownInputs)('reads a native failure %j only by its code', code => {
    const state = normalizeWorkoutState({
      ...idle,
      failure: {
        code,
        message: 'BridgeException: Invalid retained owner timing (at PowerLogBridge/PowerLogBridgeModule.swift:11)',
      },
    } as unknown as WorkoutState);
    expect(state.failure).toEqual({ code: 'unknown', message: presentationFailure('unknown').text });
  });
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
