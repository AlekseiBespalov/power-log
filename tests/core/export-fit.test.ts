import { describe, expect, it } from 'vitest';
import { exportFit } from '../../src/core/export/fit-export';
import { ExportError, type ExportSource } from '../../src/core/export/types';
import {
  CONTEXT_TIME,
  FIT_START,
  MemorySink,
  MemorySource,
  fitFailure,
  readFit,
  records,
  runFit,
  type GpsRow,
  type HealthRow,
  type LifecycleRow,
  type RideSpec,
  type TelemetryRow,
} from '../fixtures/export/fit/ride';

const power = (t: number, humanPowerW = 100, values: Partial<TelemetryRow> = {}): TelemetryRow => ({
  t,
  humanPowerW,
  cadenceRpm: 80,
  ...values,
});

const sessionOf = (bytes: Uint8Array) => readFit(bytes).find(message => message.global === 18)!;
const lapsOf = (bytes: Uint8Array) => readFit(bytes).filter(message => message.global === 19);
const fieldsOf = (bytes: Uint8Array) => records(readFit(bytes)).map(message => Object.fromEntries(message.fields));

describe('FIT failures', () => {
  it.each([
    ['a lifecycle-only ride', { end: 10 }],
    ['a ride with only energy Health rows', { end: 10, health: [{ t: 1, activeEnergyKcal: 5 }] }],
    [
      'a GPS-only ride whose fixes are all rejected',
      { end: 10, gps: [1, 2, 3].map(t => ({ t, latitude: 1, longitude: 1, horizontalAccuracyM: 60 })) },
    ],
    ['telemetry only while paused', { end: 10, lifecycle: [{ t: 0, action: 'pause' }], telemetry: [power(5)] }],
    [
      'telemetry without any FIT value',
      { end: 10, telemetry: [{ t: 1, humanPowerW: 40000, cadenceRpm: 300, motorInputPowerW: 5, motorTempC: 30 }] },
    ],
  ] as [string, RideSpec][])('fails %s with noRecords', async (_, ride) => {
    const failure = await fitFailure(ride);
    expect(failure.code).toBe('noRecords');
    expect(failure.message).toContain('ZIP is still available');
    expect(failure.sink.aborted).toBe(true);
    expect(failure.sink.committed).toBeNull();
    expect(failure.source.closed).toBeGreaterThan(0);
  });

  it.each([
    [9_999, null],
    [10_000, null],
    [10_001, 'limit'],
  ])('handles %d lifecycle events', async (count, code) => {
    const lifecycle: LifecycleRow[] = Array.from({ length: count }, (_, i) => ({
      t: i === count - 1 ? 20 : (i * 10) / count,
      action: i === count - 1 ? 'stop' : i === 0 ? 'start' : 'stop',
    }));
    const ride: RideSpec = { end: 20, lifecycle, telemetry: [power(1)] };
    if (code) expect((await fitFailure(ride)).code).toBe(code);
    else expect((await runFit(ride)).result.records).toBe(1);
  });

  it('fails rides longer than 31 days', async () => {
    expect((await fitFailure({ end: 2_678_400.001, telemetry: [power(1)] })).code).toBe('limit');
    expect((await runFit({ end: 2_678_400, telemetry: [power(1)] }, { recordInterval: 30 })).result.records).toBe(1);
  });

  it('fails a start time a FIT file cannot hold', async () => {
    const failure = await fitFailure({ end: 10, startedAt: '1990-01-01T00:00:00.000Z', telemetry: [power(1)] });
    expect(failure.code).toBe('limit');
    expect(failure.sink.aborted).toBe(true);
    expect((await fitFailure({ end: 10, startedAt: 'yesterday', telemetry: [power(1)] })).code).toBe('gate');
  });

  it('aborts and closes when the ride changes before commit', async () => {
    const source = new MemorySource({ end: 5, telemetry: [power(1)] });
    let closes = 0;
    source.close = async () => {
      closes++;
      if (closes === 1) throw new ExportError('changed', 'changed');
    };
    const sink = new MemorySink();
    const error = await exportFit(source, async () => sink, {
      rideId: 'ride-1',
      context: { exportedAt: CONTEXT_TIME, platform: 'ios' },
    }).catch((caught: unknown) => caught);
    expect((error as ExportError).code).toBe('changed');
    expect(sink.aborted).toBe(true);
    expect(sink.committed).toBeNull();
    expect(closes).toBe(2);
  });

  it('cancels before opening and while reading', async () => {
    const aborted = new AbortController();
    aborted.abort();
    const source = new MemorySource({ end: 5, telemetry: [power(1)] });
    const before = await exportFit(source, async () => new MemorySink(), {
      rideId: 'ride-1',
      context: { exportedAt: CONTEXT_TIME, platform: 'ios' },
      signal: aborted.signal,
    }).catch((caught: unknown) => caught);
    expect((before as ExportError).code).toBe('cancelled');
    expect(source.requests).toEqual([]);
    const controller = new AbortController();
    const reading = new MemorySource({
      end: 600,
      telemetry: Array.from({ length: 600 }, (_, i) => power(i)),
      pageSize: 50,
    });
    const page = reading.page.bind(reading);
    reading.page = async request => {
      if (request.projection === 'telemetry' && request.after !== null) controller.abort();
      return page(request);
    };
    const sink = new MemorySink();
    const during = await exportFit(reading as ExportSource, async () => sink, {
      rideId: 'ride-1',
      context: { exportedAt: CONTEXT_TIME, platform: 'ios' },
      signal: controller.signal,
    }).catch((caught: unknown) => caught);
    expect((during as ExportError).code).toBe('cancelled');
    expect(sink.aborted).toBe(true);
    expect(reading.closed).toBe(1);
  });
});

describe('FIT laps', () => {
  it('ignores laps at 0 and at E and collapses repeated laps', async () => {
    const { bytes } = await runFit({
      end: 10,
      lifecycle: [
        { t: 0, action: 'start' },
        { t: 0, action: 'lap' },
        { t: 4, action: 'lap' },
        { t: 4, action: 'lap' },
        { t: 10, action: 'lap' },
        { t: 10, action: 'stop' },
      ],
      telemetry: [power(1)],
    });
    expect(lapsOf(bytes).map(lap => [lap.fields.get(254), lap.fields.get(2)! - FIT_START, lap.fields.get(24)])).toEqual(
      [
        [0, 0, 0],
        [1, 4, 7],
      ],
    );
    expect(sessionOf(bytes).fields.get(26)).toBe(2);
  });

  it('writes 4096 laps and fails at 4097', async () => {
    const laps = (count: number): RideSpec => ({
      end: 5000,
      lifecycle: [
        { t: 0, action: 'start' },
        ...Array.from({ length: count - 1 }, (_, i) => ({ t: i + 1, action: 'lap' })),
        { t: 5000, action: 'stop' },
      ],
      telemetry: [power(0.5)],
    });
    const { bytes } = await runFit(laps(4096));
    const written = lapsOf(bytes);
    expect(written).toHaveLength(4096);
    expect(written[4095]!.fields.get(254)).toBe(4095);
    expect(written[4095]!.fields.get(24)).toBe(7);
    const failure = await fitFailure(laps(4097));
    expect(failure.code).toBe('limit');
    expect(failure.message).toContain('4096 laps');
    expect(failure.sink.length).toBe(0);
  });

  it('writes the zero-duration final lap of an empty ride', async () => {
    const { bytes } = await runFit({ end: 0, telemetry: [power(0)] });
    const [lap] = lapsOf(bytes);
    expect(lap!.fields.get(7)).toBe(0);
    expect(fieldsOf(bytes)).toEqual([{ 253: FIT_START, 7: 100, 4: 80 }]);
  });
});

describe('FIT GPS producer', () => {
  const both = (t: number): GpsRow[] => [
    { t, producer: 'phone', latitude: 1, longitude: 2, horizontalAccuracyM: 5 },
    { t, producer: 'watch', latitude: 3, longitude: 4, horizontalAccuracyM: 5 },
  ];
  const latitudes = (bytes: Uint8Array) => fieldsOf(bytes).map(fields => Math.round((fields[0]! * 180) / 2 ** 31));

  it('uses the selected gps:watch profile on a phone-owned ride with both producers', async () => {
    const { bytes, source } = await runFit({
      end: 3,
      gps: [...both(1), ...both(2)],
      profile: { source: 'gps:watch', kind: 'gps', producer: 'watch' },
      distance: [{ start: 1, end: 2, meters: 5 }],
    });
    expect(latitudes(bytes)).toEqual([3, 3]);
    expect(source.requests).not.toContain('gpsDiscovery');
  });

  it('chooses the owner’s GPS with good fixes, else the other producer', async () => {
    expect(latitudes((await runFit({ end: 3, gps: [...both(1), ...both(2)] })).bytes)).toEqual([1, 1]);
    expect(latitudes((await runFit({ end: 3, watchEnabled: true, gps: [...both(1)] })).bytes)).toEqual([3]);
    const poorPhone = [
      { t: 1, producer: 'phone' as const, latitude: 1, longitude: 2, horizontalAccuracyM: 51 },
      { t: 1, producer: 'watch' as const, latitude: 3, longitude: 4, horizontalAccuracyM: 50 },
    ];
    const run = await runFit({ end: 3, gps: poorPhone });
    expect(latitudes(run.bytes)).toEqual([3]);
    expect(run.source.requests).toContain('gpsDiscovery');
  });

  it('merges equal-time fixes into one record whether or not their clock epoch changes', async () => {
    for (const epochs of [
      ['a', 'a'],
      ['a', 'b'],
    ]) {
      const { bytes } = await runFit({
        end: 3,
        gps: epochs.map((epoch, i) => ({ t: 1, epoch, latitude: i + 1, longitude: 0, horizontalAccuracyM: 5 })),
      });
      expect(latitudes(bytes)).toEqual([2]);
    }
  });

  it('wraps longitude semicircles that round to 2^31 − 1 or more', async () => {
    const longitudes = [180, 180 - 1e-7, 180 - 2e-7, -180];
    const { bytes } = await runFit({
      end: 5,
      gps: longitudes.map((longitude, i) => ({ t: i, latitude: 0, longitude, horizontalAccuracyM: 1 })),
    });
    expect(fieldsOf(bytes).map(fields => fields[1])).toEqual([-2147483648, -2147483648, 2147483646, -2147483648]);
  });
});

describe('FIT heart rate continuity', () => {
  const hr = (t: number, heartRateBpm: number, values: Partial<HealthRow> = {}): HealthRow => ({
    t,
    heartRateBpm,
    representation: 'rawSeries',
    ...values,
  });
  const average = async (ride: RideSpec) => sessionOf((await runFit(ride)).bytes).fields.get(16);

  it('integrates only between rows in one interval, clock epoch, connection epoch and interruption run', async () => {
    const base: HealthRow[] = [hr(0, 100), hr(1, 100)];
    const tail = (values: Partial<HealthRow> = {}) => [hr(2, 200, values), hr(3, 240, values)];
    expect(await average({ end: 10, health: base })).toBe(100);
    expect(await average({ end: 10, health: [...base, ...tail()] })).toBe(157);
    expect(
      await average({
        end: 10,
        lifecycle: [
          { t: 0, action: 'start' },
          { t: 1.5, action: 'pause' },
          { t: 2, action: 'resume' },
          { t: 10, action: 'stop' },
        ],
        health: [...base, ...tail()],
      }),
    ).toBe(160);
    expect(await average({ end: 10, health: [...base, ...tail({ epoch: 'b' })] })).toBe(160);
    expect(await average({ end: 10, health: [...base, ...tail({ connectionEpoch: 'c' })] })).toBe(160);
    expect(
      await average({
        end: 10,
        lifecycle: [
          { t: 0, action: 'start', sequence: 1 },
          { t: 1.5, action: 'resume', sequence: 2, interrupted: true, cyc: 0 },
          { t: 10, action: 'stop', sequence: 3 },
        ],
        health: [...base, ...tail()],
      }),
    ).toBe(160);
  });

  it('keeps the chain across non-heart-rate rows and breaks it at an invalid value', async () => {
    expect(await average({ end: 10, health: [hr(0, 100), { t: 1, activeEnergyKcal: 3 }, hr(2, 120)] })).toBe(110);
    expect(await average({ end: 10, health: [hr(0, 100), hr(1, 0), hr(2, 140), hr(3, 160)] })).toBe(150);
    expect(await average({ end: 30, health: [hr(0, 100), hr(10, 120), hr(20.5, 200)] })).toBe(110);
  });
});

describe('FIT record witnesses', () => {
  it('forms records only from rows that supply a FIT value', async () => {
    const { bytes } = await runFit({
      end: 10,
      telemetry: [
        power(1, 100),
        { t: 2, humanPowerW: 40000, cadenceRpm: 300, assistLevel: 2.5, motorInputPowerW: 70000, motorTempC: 3276.7 },
        { t: 3, humanPowerW: -1, batteryVoltageV: 48.123456789 },
        { t: 4, assistLevel: 254 },
        { t: 5, humanPowerW: NaN, consumedWh: 1e39 },
        { t: 6, humanPowerW: 32766, cadenceRpm: 254, motorInputPowerW: -0.4 },
      ],
    });
    expect(fieldsOf(bytes)).toEqual([
      { 253: FIT_START + 1, 7: 100, 4: 80 },
      { 253: FIT_START + 3 },
      { 253: FIT_START + 4, 119: 254 },
      { 253: FIT_START + 6, 7: 32766, 4: 254 },
    ]);
    const developer = records(readFit(bytes)).map(message => Object.fromEntries(message.developer));
    expect(developer).toEqual([{}, { 1: Math.fround(48.123456789) }, {}, { 0: 0 }]);
  });

  it('writes distance points only inside activity intervals', async () => {
    const { bytes } = await runFit({
      end: 10,
      lifecycle: [
        { t: 0, action: 'start' },
        { t: 3, action: 'pause' },
        { t: 5, action: 'resume' },
        { t: 10, action: 'stop' },
      ],
      profile: { source: 'controller', kind: 'controller' },
      distance: [
        { start: 1, end: 3, meters: 4, startSpeed: 2, endSpeed: 2 },
        { start: 3.5, end: 4.5, meters: 2, startSpeed: 2, endSpeed: 2 },
        { start: 6, end: 7, meters: 2, startSpeed: 2, endSpeed: 2 },
      ],
    });
    expect(fieldsOf(bytes).map(fields => [fields[253]! - FIT_START, fields[5]])).toEqual([
      [1, 0],
      [3, 400],
      [6, 600],
      [7, 800],
    ]);
  });

  it('rounds a heart rate, a mean power and a mean cadence half away from zero', async () => {
    const { bytes } = await runFit({
      end: 5,
      telemetry: [power(1, 100, { cadenceRpm: 80 }), power(1.25, 101, { cadenceRpm: 81 })],
      health: [{ t: 2, heartRateBpm: 120.5, representation: 'rawSeries' }],
    });
    expect(fieldsOf(bytes)).toEqual([
      { 253: FIT_START + 1, 7: 101, 4: 81 },
      { 253: FIT_START + 2, 3: 121 },
    ]);
  });
});

describe('FIT message order', () => {
  const order = (bytes: Uint8Array) =>
    readFit(bytes)
      .slice(12)
      .map(message => {
        const time = message.fields.get(253)! - FIT_START;
        if (message.global === 21) return `${message.fields.get(1) === 0 ? 'start' : 'stop'} ${time}`;
        return `${{ 18: 'session', 19: 'lap', 20: 'record', 34: 'activity' }[message.global]} ${time}`;
      });

  it('writes a record whose interval closes in an equal-elapsed group before the whole group', async () => {
    const { bytes } = await runFit({
      platform: 'android',
      end: 10,
      lifecycle: [
        { t: 0, action: 'start' },
        { t: 5, action: 'lap' },
        { t: 5, action: 'pause' },
        { t: 6, action: 'resume' },
        { t: 10, action: 'stop' },
      ],
      telemetry: [power(4), power(5), power(7)],
    });
    expect(order(bytes)).toEqual([
      'start 0',
      'record 4',
      'record 5',
      'lap 5',
      'stop 5',
      'start 6',
      'record 7',
      'stop 10',
      'lap 10',
      'session 10',
      'activity 10',
    ]);
  });

  it('writes a record of an interval that opens at E between its start and the terminal stop', async () => {
    const { bytes } = await runFit({
      end: 10,
      lifecycle: [
        { t: 0, action: 'start' },
        { t: 8, action: 'pause' },
        { t: 10, action: 'resume' },
        { t: 10, action: 'stop' },
      ],
      telemetry: [power(7), power(10, 200)],
    });
    expect(order(bytes)).toEqual([
      'start 0',
      'record 7',
      'stop 8',
      'start 10',
      'record 10',
      'stop 10',
      'lap 10',
      'session 10',
      'activity 10',
    ]);
  });

  it('starts a new bin when the activity interval changes, whatever the record interval', async () => {
    const { bytes } = await runFit(
      {
        end: 10,
        lifecycle: [
          { t: 0, action: 'start' },
          { t: 2.5, action: 'pause' },
          { t: 3, action: 'resume' },
          { t: 10, action: 'stop' },
        ],
        health: [1, 2, 3.5, 4].map((t, i) => ({ t, heartRateBpm: 100 + 10 * i, representation: 'rawSeries' })),
      },
      { recordInterval: 10 },
    );
    expect(fieldsOf(bytes)).toEqual([
      { 253: FIT_START + 1, 3: 110 },
      { 253: FIT_START + 4, 3: 130 },
    ]);
  });
});

describe('FIT session statistics', () => {
  const motor = (values: [number, number | null, string?][]): RideSpec => ({
    end: values[values.length - 1]![0] + 0.5,
    telemetry: values.map(([t, motorInputPowerW, epoch]) => ({
      ...power(t),
      ...(motorInputPowerW === null ? {} : { motorInputPowerW }),
      ...(epoch ? { epoch } : {}),
    })),
  });

  it.each([
    [
      'missing',
      motor([
        [0, 100],
        [0.5, null],
        [1, 300],
        [2, 500],
      ]),
      400,
      500,
    ],
    [
      'gaps',
      motor([
        [0, 100],
        [2.5, 300],
        [5.125, 1000],
        [6.125, 500],
      ]),
      357,
      1000,
    ],
    [
      'duplicate',
      motor([
        [0, 100],
        [0, 900],
        [1, 300],
      ]),
      600,
      900,
    ],
    ['isolated', motor([[0.125, 321.5]]), undefined, 322],
    [
      'negative',
      motor([
        [0, -300],
        [1, 100],
        [2, 50],
      ]),
      undefined,
      100,
    ],
    [
      'signed',
      motor([
        [0, -100],
        [1, 300],
      ]),
      100,
      300,
    ],
    [
      'negative-small',
      motor([
        [0, -0.4],
        [1, -0.4],
      ]),
      undefined,
      undefined,
    ],
    [
      'upper-invalid',
      motor([
        [0, 65534.5],
        [1, 65534.5],
      ]),
      undefined,
      undefined,
    ],
    [
      'upper-valid',
      motor([
        [0, 65534],
        [1, 65534],
      ]),
      65534,
      65534,
    ],
    [
      'rounding',
      motor([
        [0, 100],
        [1, 101],
      ]),
      101,
      101,
    ],
    [
      'epoch',
      motor([
        [0, 100, 'a'],
        [1, 300, 'b'],
        [2, 500, 'b'],
      ]),
      400,
      500,
    ],
  ] as [string, RideSpec, number | undefined, number | undefined][])(
    'writes the iPhone motor-%s battery power statistics',
    async (_, ride, average, maximum) => {
      const session = sessionOf((await runFit(ride)).bytes);
      expect([session.fields.get(129), session.fields.get(130)]).toEqual([average, maximum]);
    },
  );

  it('integrates rider power only within one interruption run of an activity interval', async () => {
    const telemetry = [power(0, 100), power(1, 100), power(2, 200), power(3, 240)];
    const totals = async (lifecycle?: LifecycleRow[]) => {
      const session = sessionOf((await runFit({ end: 10, lifecycle, telemetry })).bytes);
      return [session.fields.get(20), session.fields.get(21), session.fields.get(48)];
    };
    expect(await totals()).toEqual([157, 240, 470]);
    expect(
      await totals([
        { t: 0, action: 'start', sequence: 1 },
        { t: 1.5, action: 'resume', sequence: 2, interrupted: true, cyc: 0 },
        { t: 10, action: 'stop', sequence: 3 },
      ]),
    ).toEqual([160, 240, 320]);
  });

  it('keeps motor power statistics across GPS, Health and lap rows', async () => {
    const session = sessionOf(
      (
        await runFit({
          end: 2,
          lifecycle: [
            { t: 0, action: 'start' },
            { t: 0.75, action: 'lap' },
            { t: 2, action: 'stop' },
          ],
          telemetry: [power(0, 100, { motorInputPowerW: 100 }), power(1, 100, { motorInputPowerW: 300 })],
          gps: [{ t: 0.25, latitude: 0, longitude: 0 }],
          health: [{ t: 0.5, heartRateBpm: 123 }],
        })
      ).bytes,
    );
    expect([session.fields.get(129), session.fields.get(130)]).toEqual([200, 300]);
  });

  it('writes ascent and descent only after an altitude comparison', async () => {
    const fixes = (altitudes: number[]): GpsRow[] =>
      altitudes.map((altitudeMeters, t) => ({
        t,
        latitude: 45,
        longitude: 7 + t * 0.00001,
        horizontalAccuracyM: 4,
        altitudeMeters,
        verticalAccuracyM: Number.isNaN(altitudeMeters) ? NaN : 2,
      }));
    const climb = async (altitudes: number[]) => {
      const session = sessionOf((await runFit({ end: 10, gps: fixes(altitudes) })).bytes);
      return [session.fields.get(22), session.fields.get(23)];
    };
    expect(await climb([NaN, NaN, NaN])).toEqual([undefined, undefined]);
    expect(await climb([100, NaN, NaN])).toEqual([undefined, undefined]);
    expect(await climb([100, 101])).toEqual([0, 0]);
    expect(await climb([100, 104, 99.5])).toEqual([4, 5]);
  });
});
