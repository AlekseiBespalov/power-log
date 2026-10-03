import { describe, expect, it } from 'vitest';
import type { ExportErrorCode, ExportPlatform, ExportRideMetadata, ProjectionName } from '../../src/core/export/types';
import { exportZip } from '../../src/core/export/zip-export';
import {
  MemorySink,
  MemorySource,
  entryText,
  readZip,
  type CanonicalRide,
  type Row,
} from '../fixtures/export/zip/harness';

const METADATA: ExportRideMetadata = {
  startedAt: '2026-01-01T00:00:00.000Z',
  endedAt: '2026-01-01T00:00:10.000Z',
  ownerTiming: null,
  indoor: false,
  interrupted: false,
  watchEnabled: false,
  saveToHealth: false,
  recordGPS: false,
  health: { provider: null, state: 'notRequested', workoutUUID: null, export: null },
  watchSyncState: 'notRequired',
  finalizationState: 'complete',
  example: false,
  sampleHz: null,
};

const PRODUCERS: Record<ExportPlatform, string> = { ios: 'phone', android: 'phone', web: 'browser' };

function lifecycle(platform: ExportPlatform, actions: [number, string, number?][]): Row[] {
  return actions.map(([elapsedSeconds, action, interrupted], index) => ({
    elapsedSeconds,
    timestamp: `t${index}`,
    producer: PRODUCERS[platform],
    action,
    ...(platform === 'web' ? {} : { interrupted: interrupted ?? 0 }),
    ...(platform === 'ios' ? { producerSequence: index + 1, cycSequence: 0 } : {}),
  }));
}

function ride(
  platform: ExportPlatform,
  rows: Partial<Record<ProjectionName, Row[]>>,
  producers: CanonicalRide['open']['producers'] = { gps: [], health: [] },
): CanonicalRide {
  return {
    rideId: 'table-ride',
    context: { exportedAt: '2026-01-01T01:00:00.000Z', platform },
    open: { metadata: METADATA, elapsedEnd: 10, producers, distanceProfile: null },
    identities: { c: { vendor: 'cyc', model: null, firmware: null, protocol: null } },
    rows: {
      lifecycle: lifecycle(platform, [
        [0, 'start'],
        [10, platform === 'web' ? 'save' : 'stop'],
      ]),
      ...rows,
    },
  };
}

async function run(canonical: CanonicalRide) {
  const source = new MemorySource(canonical);
  const sink = new MemorySink();
  const outcome = await exportZip(source, async () => sink, {
    rideId: canonical.rideId,
    context: canonical.context,
  }).then(
    () => null,
    (error: { code?: ExportErrorCode; message?: string }) => error,
  );
  return { source, sink, outcome };
}

async function table(canonical: CanonicalRide, name: string): Promise<string[]> {
  const { sink, outcome } = await run(canonical);
  expect(outcome).toBeNull();
  const entry = readZip(sink.file).find(item => item.name === `PowerLog-original/${name}`)!;
  return entryText(entry).split('\n').slice(1, -1);
}

async function failure(canonical: CanonicalRide): Promise<ExportErrorCode | undefined> {
  const { sink, source, outcome } = await run(canonical);
  expect(sink.committed).toBeNull();
  expect(sink.aborted).toBe(true);
  expect(source.closed).toEqual(['canonical-session']);
  return outcome?.code;
}

describe('events.csv actions', () => {
  it('maps iPhone interruptions to interrupted pauses and keeps the flag on other actions', async () => {
    const rows = lifecycle('ios', [
      [0, 'start'],
      [1, 'interruption'],
      [1, 'resume'],
      [2, 'pause', 1],
      [3, 'resume'],
      [4, 'lap'],
      [10, 'stop', 1],
    ]);
    expect(await table(ride('ios', { lifecycle: rows }), 'events.csv')).toEqual([
      't0,0,,start,false,phone',
      't1,1,,pause,true,phone',
      't2,1,,resume,false,phone',
      't3,2,,pause,true,phone',
      't4,3,,resume,false,phone',
      't5,4,,lap,false,phone',
      't6,10,,stop,true,phone',
    ]);
  });

  it("marks Android's terminal stop of an interrupted ride", async () => {
    const rows = lifecycle('android', [
      [0, 'start'],
      [10, 'stop', 1],
    ]);
    expect(await table(ride('android', { lifecycle: rows }), 'events.csv')).toEqual([
      't0,0,,start,false,phone',
      't1,10,,stop,true,phone',
    ]);
  });

  it('maps web save and interrupted to stops', async () => {
    const rows = lifecycle('web', [
      [0, 'start'],
      [2, 'pause'],
      [3, 'resume'],
      [4, 'lap'],
      [9, 'interrupted'],
      [10, 'save'],
    ]);
    expect(await table(ride('web', { lifecycle: rows }), 'events.csv')).toEqual([
      't0,0,,start,false,browser',
      't1,2,,pause,false,browser',
      't2,3,,resume,false,browser',
      't3,4,,lap,false,browser',
      't4,9,,stop,true,browser',
      't5,10,,stop,false,browser',
    ]);
  });

  it.each([
    ['ios', 'save'],
    ['ios', 'interrupted'],
    ['ios', 'discard'],
    ['android', 'interruption'],
    ['android', 'save'],
    ['web', 'stop'],
    ['web', 'interruption'],
  ] as [ExportPlatform, string][])('fails a %s ride with the action %s', async (platform, action) => {
    const rows = lifecycle(platform, [
      [0, 'start'],
      [5, action],
      [10, platform === 'web' ? 'save' : 'stop'],
    ]);
    expect(await failure(ride(platform, { lifecycle: rows }))).toBe('page');
  });

  it.each([
    ['ios', 'cyc'],
    ['ios', 'browser'],
    ['android', 'watch'],
    ['android', 'browser'],
    ['web', 'phone'],
  ] as [ExportPlatform, string][])('fails a %s event recorded by %s', async (platform, producer) => {
    const rows = lifecycle(platform, [
      [0, 'start'],
      [10, platform === 'web' ? 'save' : 'stop'],
    ]).map(row => ({ ...row, producer }));
    expect(await failure(ride(platform, { lifecycle: rows }))).toBe('page');
  });

  it('accepts Watch events on iPhone rides', async () => {
    const rows = lifecycle('ios', [
      [0, 'start'],
      [10, 'stop'],
    ]).map(row => ({ ...row, producer: 'watch' }));
    expect(await table(ride('ios', { lifecycle: rows }), 'events.csv')).toEqual([
      't0,0,,start,false,watch',
      't1,10,,stop,false,watch',
    ]);
  });
});

describe('limits that apply only to FIT', () => {
  it('exports a ride with 10,001 lifecycle events', async () => {
    const laps = Array.from({ length: 9_999 }, (_, index): [number, string] => [(index + 1) / 1000, 'lap']);
    const actions: [number, string][] = [[0, 'start'], ...laps, [10, 'stop']];
    const events = await table(ride('ios', { lifecycle: lifecycle('ios', actions) }), 'events.csv');
    expect(events).toHaveLength(10_001);
    expect([events[0], events[1], events[10_000]]).toEqual([
      't0,0,,start,false,phone',
      't1,0.001,,lap,false,phone',
      't10000,10,,stop,false,phone',
    ]);
  });

  it.each(['android', 'web'] as const)('exports a %s ride longer than 31 days', async platform => {
    const end = 2_678_401;
    const captured: Row = platform === 'web' ? { active: 1, interval: 0 } : { active: 1 };
    const base = ride(platform, {
      lifecycle: lifecycle(platform, [
        [0, 'start'],
        [end, platform === 'web' ? 'save' : 'stop'],
      ]),
      telemetry: [{ elapsedSeconds: 2_678_400.5, timestamp: 'r', connection: 'c', humanPowerW: 90, ...captured }],
    });
    const canonical: CanonicalRide = { ...base, open: { ...base.open, elapsedEnd: end } };
    expect(await table(canonical, 'telemetry.csv')).toEqual(['r,2678400.5,1,1,1,90,,,,,,,,,,,,,,,,,']);
    expect(await table(canonical, 'events.csv')).toEqual([
      `t0,0,,start,false,${PRODUCERS[platform]}`,
      `t1,2678401,,stop,false,${PRODUCERS[platform]}`,
    ]);
  });
});

const HEART_RATE = 'HKQuantityTypeIdentifierHeartRate';
const healthRow = (values: Row, producer = 'watch'): Row => ({
  elapsedSeconds: 1,
  timestamp: 'end',
  producer,
  producerSequence: 1,
  ...values,
});
const healthRide = (rows: Row[]) => ride('ios', { healthZip: rows }, { gps: [], health: ['watch'] });

describe('health.csv values', () => {
  it.each([
    ['rawSeries', 1, 'sample', ''],
    ['rawSeries', 5, 'sample', ''],
    ['rawQuantity', null, 'sample', ''],
    ['rawQuantity', 0, 'sample', ''],
    ['rawQuantity', 1, 'sample', ''],
    ['rawQuantity', 2, 'aggregate', '2'],
    ['builderMostRecent', 4, 'latest', ''],
    ['cumulativeWorkoutTotal', null, 'cumulative', ''],
    ['finalWorkoutTotal', null, 'final', ''],
  ])('gives a %s row with sampleCount %s the kind %s', async (representation, sampleCount, kind, count) => {
    const rows = [healthRow({ identifier: HEART_RATE, value: 80, representation, sampleCount })];
    expect(await table(healthRide(rows), 'health.csv')).toEqual([
      `end,,1,1,watch,,heartRate,80,/min,${kind},${count},`,
    ]);
  });

  it.each(['workoutAssociation', 'healthTombstone', 'workoutMetadata'])(
    'skips %s rows, which are not measurements',
    async representation => {
      const rows = [healthRow({ heartRateBpm: 80, identifier: HEART_RATE, value: 80, representation }, 'phone')];
      expect(await table(healthRide(rows), 'health.csv')).toEqual([]);
    },
  );

  it.each(['raw', 'aggregate', 'constructor', '__proto__', 'toString', ''])(
    'fails a row with the representation %j',
    async representation => {
      const rows = [healthRow({ heartRateBpm: 80, representation })];
      expect(await failure(healthRide(rows))).toBe('page');
    },
  );

  it('writes rows without a representation with their metric fallback, or no kind', async () => {
    const rows = [
      healthRow({
        heartRateBpm: 70,
        activeEnergyKcal: 1,
        basalEnergyKcal: 2,
        distanceMeters: 3,
        riderPowerW: 4,
        cadenceRpm: 5,
        speedMps: 6,
        identifier: 'HKQuantityTypeIdentifierRespiratoryRate',
        value: 14,
      }),
      healthRow({ identifier: 'HKQuantityTypeIdentifierStepCount', value: 9, unit: 'count' }),
    ];
    expect(await table(healthRide(rows), 'health.csv')).toEqual([
      'end,,1,1,watch,,heartRate,70,/min,latest,,',
      'end,,1,1,watch,,activeEnergy,1,kcal,cumulative,,',
      'end,,1,1,watch,,basalEnergy,2,kcal,cumulative,,',
      'end,,1,1,watch,,distance,3,m,cumulative,,',
      'end,,1,1,watch,,power,4,W,,,',
      'end,,1,1,watch,,cadence,5,/min,,,',
      'end,,1,1,watch,,speed,6,m/s,,,',
      'end,,1,1,watch,,respiratoryRate,14,/min,,,',
      'end,,1,1,watch,,HKQuantityTypeIdentifierStepCount,9,count,,,',
    ]);
  });

  it.each([
    ['HKQuantityTypeIdentifierHeartRateVariabilitySDNN', 41.5, 'heartRateVariabilitySDNN', '41.5', 'ms'],
    ['HKQuantityTypeIdentifierPhysicalEffort', 3.5, 'physicalEffort', '3.5', 'kcal/(kg.h)'],
    ['HKQuantityTypeIdentifierCyclingFunctionalThresholdPower', 250, 'functionalThresholdPower', '250', 'W'],
    ['HKQuantityTypeIdentifierWorkoutEffortScore', 7, 'workoutEffort', '7', '{score}'],
    ['HKQuantityTypeIdentifierEstimatedWorkoutEffortScore', 6.5, 'estimatedWorkoutEffort', '6.5', '{score}'],
    ['HKQuantityTypeIdentifierCyclingCadence', 88, 'cadence', '88', '/min'],
    ['HKQuantityTypeIdentifierCyclingSpeed', 7.25, 'speed', '7.25', 'm/s'],
    ['HKQuantityTypeIdentifierDistanceCycling', 15, 'distance', '15', 'm'],
    ['HKQuantityTypeIdentifierBasalEnergyBurned', 0.5, 'basalEnergy', '0.5', 'kcal'],
    ['HKQuantityTypeIdentifierOxygenSaturation', 0.955, 'oxygenSaturation', '95.5', '%'],
    ['HKQuantityTypeIdentifierOxygenSaturation', 1, 'oxygenSaturation', '100', '%'],
    ['HKQuantityTypeIdentifierOxygenSaturation', 1e-7, 'oxygenSaturation', '0.00001', '%'],
    ['HKQuantityTypeIdentifierOxygenSaturation', -0.25, 'oxygenSaturation', '-25', '%'],
  ])('maps %s %d to %s %s %s', async (identifier, value, metric, written, unit) => {
    const rows = [healthRow({ identifier, value, unit: 'stored', representation: 'rawQuantity', sampleCount: 1 })];
    expect(await table(healthRide(rows), 'health.csv')).toEqual([
      `end,,1,1,watch,,${metric},${written},${unit},sample,,`,
    ]);
  });

  it('writes the named value once when the generic value names the same quantity', async () => {
    const rows = [healthRow({ heartRateBpm: 81, identifier: HEART_RATE, value: 82, representation: 'rawSeries' })];
    expect(await table(healthRide(rows), 'health.csv')).toEqual(['end,,1,1,watch,,heartRate,81,/min,sample,,']);
  });

  it('writes nothing for a row without values', async () => {
    const rows = [healthRow({ identifier: HEART_RATE, representation: 'rawQuantity', sampleCount: 1 })];
    expect(await table(healthRide(rows), 'health.csv')).toEqual([]);
  });

  it.each<{ problem: string; values: Row }>([
    { problem: 'a value without its identifier', values: { value: 3, representation: 'rawQuantity' } },
    {
      problem: 'a fractional aggregate count',
      values: { identifier: HEART_RATE, value: 80, representation: 'rawQuantity', sampleCount: 2.5 },
    },
    {
      problem: 'an infinite generic value',
      values: { identifier: HEART_RATE, value: Infinity, representation: 'rawQuantity' },
    },
    { problem: 'an infinite named value', values: { heartRateBpm: -Infinity, representation: 'rawSeries' } },
    {
      problem: 'an infinite scaled value',
      values: {
        identifier: 'HKQuantityTypeIdentifierOxygenSaturation',
        value: Infinity,
        representation: 'rawQuantity',
      },
    },
  ])('fails a Health row with $problem', async ({ values }) => {
    expect(await failure(healthRide([healthRow(values)]))).toBe('page');
  });

  it('fails a Health row from a producer the ride does not list', async () => {
    expect(await failure(healthRide([healthRow({ heartRateBpm: 80 }, 'phone')]))).toBe('page');
  });

  it('is header only on Android and web', async () => {
    expect(await table(ride('android', {}), 'health.csv')).toEqual([]);
    expect(await table(ride('web', {}), 'health.csv')).toEqual([]);
  });
});

describe('gps.csv and telemetry.csv rows', () => {
  const fix = (producer: string | null): Row => ({
    elapsedSeconds: 1,
    timestamp: 'f',
    producer,
    producerSequence: 1,
    latitude: 1,
    longitude: 2,
    distanceBarrier: 0,
  });

  it('fails a fix from a producer the ride does not list', async () => {
    const listed = { gps: ['phone'], health: [] } as CanonicalRide['open']['producers'];
    expect(await failure(ride('ios', { gps: [fix('watch')] }, listed))).toBe('page');
    expect(await failure(ride('ios', { gps: [fix(null)] }, listed))).toBe('page');
    expect(await table(ride('ios', { gps: [fix('phone')] }, listed), 'gps.csv')).toEqual([
      'f,1,1,1,phone,1,2,,,,,,,,,',
    ]);
  });

  const reading = (values: Row): Row => ({ elapsedSeconds: 1, timestamp: 'r', connection: 'c', ...values });

  it.each([
    [{ faultCode: 1.5 }],
    [{ assistLevel: 2 ** 53 }],
    [{ raceMode: Infinity }],
    [{ humanPowerW: Infinity }],
    [{ batteryCurrentA: -Infinity }],
  ])('fails a reading with %j', async values => {
    expect(await failure(ride('android', { telemetry: [reading({ active: 1, ...values })] }))).toBe('page');
  });

  it('starts a telemetry run at each interruption inside one activity interval', async () => {
    const events = lifecycle('ios', [
      [0, 'start'],
      [1.5, 'resume', 1],
      [10, 'pause', 1],
      [10, 'stop'],
    ]).map((row, index) => ({ ...row, cycSequence: [0, 1, 3, 0][index]! }));
    const rows = [1, 2, 10, 10].map((elapsedSeconds, index) =>
      reading({ elapsedSeconds, producerSequence: index + 1, clockEpoch: 'e', humanPowerW: 100 }),
    );
    expect(await table(ride('ios', { lifecycle: events, telemetry: rows }), 'telemetry.csv')).toEqual([
      'r,1,1,1,1,100,,,,,,,,,,,,,,,,,',
      'r,2,1,2,1,100,,,,,,,,,,,,,,,,,',
      'r,10,1,2,1,100,,,,,,,,,,,,,,,,,',
      'r,10,1,3,1,100,,,,,,,,,,,,,,,,,',
    ]);
  });

  it('writes web telemetry with captured activity and no iPhone envelope', async () => {
    const rows = [
      reading({ active: 1, interval: 0, humanPowerW: 120 }),
      { ...reading({ active: 0, interval: 0, humanPowerW: 0 }), elapsedSeconds: 2 },
    ];
    expect(await table(ride('web', { telemetry: rows }), 'telemetry.csv')).toEqual([
      'r,1,1,1,1,120,,,,,,,,,,,,,,,,,',
      'r,2,,2,1,0,,,,,,,,,,,,,,,,,',
    ]);
  });
});
