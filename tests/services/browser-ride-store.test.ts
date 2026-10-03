import { describe, expect, it } from 'vitest';
import { metadataOf, normalize, rowAggregate, rowPoint, mergeAggregate } from '../../src/services/browser-ride-store';
import { browserRide, browserRow } from '../support/browser-ride';

describe('required browser ride metadata', () => {
  it.each(['2025-12-31T23:00:00Z', '2026-01-01T01:00:00Z'])(
    'retains measured catalog elapsed with cutoff %s',
    endedAt => {
      const active = browserRide({ elapsedSeconds: 75, timerSeconds: 60 });
      expect(metadataOf(active).elapsedSeconds).toBe(75);
      expect(metadataOf({ ...active, phase: 'completed', endedAt, writer: undefined }).elapsedSeconds).toBe(75);
      expect(
        metadataOf({ ...active, phase: 'completed', endedAt, writer: undefined, interrupted: true }).elapsedSeconds,
      ).toBe(75);
    },
  );
  it('retains hard-boundary markers and integration evidence in persisted aggregates', () => {
    const a = { ...browserRow(1, 0), recordingId: 'interrupted' },
      b = { ...browserRow(2, 1), recordingId: 'interrupted', interruptionIndex: 1 };
    expect(rowPoint(b, 'humanPowerW', a)).toMatchObject({ startsSegment: true });
    const aggregate = structuredClone(
      mergeAggregate(rowAggregate(a, 'humanPowerW'), rowAggregate(b, 'humanPowerW')!, 'humanPowerW'),
    );
    expect(aggregate).toMatchObject({
      covered: 0,
      integral: 0,
      firstInterruptionIndex: 0,
      lastInterruptionIndex: 1,
      firstConnectionEpoch: a.connectionEpoch,
      lastConnectionEpoch: b.connectionEpoch,
    });
    expect(aggregate.runs).toHaveLength(2);
    expect(aggregate.runs[1]!.first.startsSegment).toBe(true);
    const c = { ...b, elapsedSeconds: 3, sequence: 2 };
    const extended = mergeAggregate(aggregate, rowAggregate(c, 'humanPowerW')!, 'humanPowerW');
    expect(extended.covered).toBe(1);
    expect(extended.runs).toHaveLength(2);
    expect(extended.runs[1]!.first.startsSegment).toBe(true);
  });
  it.each([
    'id',
    'startedAt',
    'samples',
    'interrupted',
    'phase',
    'indoor',
    'revision',
    'elapsedSeconds',
    'timerSeconds',
    'checkpointAt',
    'availableMetrics',
    'lapCount',
  ])('rejects missing %s', field => {
    const record: Record<string, unknown> = { ...browserRide() };
    delete record[field];
    expect(() => normalize(record)).toThrow();
  });
  it.each([
    { id: '../private' },
    { id: '' },
    { startedAt: '2026-02-30T00:00:00Z' },
    { checkpointAt: 'yesterday' },
    { samples: -1 },
    { samples: 1.5 },
    { revision: 0 },
    { lapCount: NaN },
    { elapsedSeconds: Infinity },
    { timerSeconds: 1 },
    { indoor: 'false' },
    { interrupted: 1 },
    { availableMetrics: ['bogus'] },
  ])('rejects malformed fields: %j', patch => {
    expect(() => normalize({ ...browserRide(), ...patch })).toThrow();
  });
  it('requires a writer only while a ride is owned and requires a saved cutoff', () => {
    expect(() => normalize(browserRide({ writer: undefined }))).toThrow('owner');
    const saved = browserRide({ phase: 'completed', writer: undefined, endedAt: '2026-01-01T00:00:00Z' });
    expect(normalize(saved)).toEqual(saved);
    expect(metadataOf(saved)).toMatchObject({ phase: 'completed', finalizationState: 'complete', storage: 'browser' });
    expect(() => normalize({ ...saved, writer: 'owner' })).toThrow('completed');
    expect(() => normalize({ ...saved, endedAt: undefined })).toThrow('completed');
    expect(() => normalize(browserRide({ endedAt: saved.endedAt }))).toThrow('owner');
  });
});
