import { InspectionPlotBucket, InspectionPlotQuery, InspectionPlotCache } from '../../shared/js';
import { browserInspectionItem } from '../../src/services/browser-analytics';
import { describe, expect, it } from 'vitest';
import { workoutExportReady } from '../../src/core/workouts';
import { metadataOf, normalize, rowPoint } from '../../src/services/browser-ride-store';
import { browserRide, browserRow } from '../support/browser-ride';

describe('required browser ride metadata', () => {
  it('drops removed metric names while retaining strict structural validation', () => {
    const record = browserRide();
    expect(normalize({ ...record, availableMetrics: ['humanPowerW', 'removedMetric'] }).availableMetrics).toEqual([
      'humanPowerW',
    ]);
    expect(() => normalize({ ...record, availableMetrics: ['humanPowerW', 42] })).toThrow();
  });
  it('exports a completed browser ride sealed at its final revision and nothing earlier', () => {
    const active = browserRide({ elapsedSeconds: 75, timerSeconds: 60 });
    expect(workoutExportReady(metadataOf(active))).toBe(false);
    expect(
      workoutExportReady(
        metadataOf({ ...active, phase: 'completed', endedAt: '2026-01-01T01:00:00Z', writer: undefined }),
      ),
    ).toBe(true);
    expect(workoutExportReady(metadataOf({ ...active, phase: 'interrupted', writer: undefined }))).toBe(false);
  });
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
  it('retains hard-boundary markers and original geometry in persisted aggregates', () => {
    const a = { ...browserRow(1, 0), recordingId: 'interrupted' },
      b = { ...browserRow(2, 1), recordingId: 'interrupted', interruptionIndex: 1 };
    expect(rowPoint(b, 'humanPowerW')).toMatchObject({ elapsedSeconds: 2, timestamp: b.timestamp });
    const bucket = new InspectionPlotBucket('humanPowerW', '0', '16000000');
    [a, b].map(browserInspectionItem).forEach(item => bucket.append(item, true));
    const aggregate = structuredClone({ plot: bucket.aggregate().payload });
    const query = new InspectionPlotQuery('humanPowerW', '0', '16000000', 1, 16384);
    query.merge(new InspectionPlotCache(aggregate.plot));
    expect(query.result().map(point => point.segment)).toEqual([0, 1]);
    expect(query.result().map(point => point.original.timestamp)).toEqual([a.timestamp, b.timestamp]);
  });
  it.each([
    'id',
    'startedAt',
    'telemetryCount',
    'locationCount',
    'lifecycleCount',
    'originalMembershipCount',
    'recordGPS',
    'sampleHz',
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
    { id: 'not-a-uuid' },
    { id: 'ABCDEF00-0000-0000-0000-000000000001' },
    { startedAt: '2026-02-30T00:00:00Z' },
    { checkpointAt: 'yesterday' },
    { telemetryCount: -1 },
    { telemetryCount: 1.5 },
    { revision: 0 },
    { lapCount: NaN },
    { elapsedSeconds: Infinity },
    { timerSeconds: 1 },
    { indoor: 'false' },
    { interrupted: 1 },
    { availableMetrics: [42] },
  ])('rejects malformed fields: %j', patch => {
    expect(() => normalize({ ...browserRide(), ...patch })).toThrow();
  });
  it('requires a writer only while a ride is owned and requires a saved cutoff', () => {
    expect(() => normalize(browserRide({ writer: undefined }))).toThrow('owner');
    const saved = browserRide({ phase: 'completed', writer: undefined, endedAt: '2026-01-01T00:00:00Z' });
    expect(normalize(saved)).toEqual(saved);
    expect(metadataOf(saved)).toMatchObject({ phase: 'completed', storage: 'browser' });
    expect(() => normalize({ ...saved, writer: 'owner' })).toThrow('completed');
    expect(() => normalize({ ...saved, endedAt: undefined })).toThrow('completed');
    expect(() => normalize(browserRide({ endedAt: saved.endedAt }))).toThrow('owner');
  });
});

it.each([undefined, null, 3, NaN, '8'])('refuses an unadmitted frozen rate %s', sampleHz => {
  expect(() => normalize({ ...browserRide(), sampleHz })).toThrow('sampleHz');
});
it('projects every original membership and frozen GPS/rate options', () => {
  const ride = browserRide({ telemetryCount: 2, locationCount: 1, lifecycleCount: 3, sampleHz: 8, recordGPS: true });
  expect(metadataOf(ride)).toMatchObject({ eventCount: '6', collectionRevision: '1', sampleHz: 8, recordGPS: true });
});

it('retains the largest revision and declares local-only Health results', () => {
  const ride = browserRide({ revision: '9007199254740991' });
  expect(metadataOf(ride)).toMatchObject({
    collectionRevision: '9007199254740991',
    healthKitState: 'notRequested',
  });
});
