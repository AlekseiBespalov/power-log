import { describe, expect, it } from 'vitest';
import { exportCsv } from '../../src/core/export/csv-export';
import { FIT_MAX_RIDE_SECONDS } from '../../src/core/export/timeline';
import { ExportError } from '../../src/core/export/types';
import {
  CONTEXT_TIME,
  FIT_START,
  MemorySink,
  MemorySource,
  readFit,
  records,
  runCsv,
  runFit,
  type RideSpec,
  type TelemetryRow,
} from '../fixtures/export/fit/ride';

const seconds = (rows: readonly Readonly<Record<string, string>>[]) =>
  rows.map(row => (Date.parse(row.timestamp!) - Date.parse('2026-01-01T00:00:00.000Z')) / 1000);

const fitSeconds = (bytes: Uint8Array) => records(readFit(bytes)).map(record => record.fields.get(253)! - FIT_START);

describe('one-file CSV export', () => {
  const telemetry = (t: number, values: Partial<TelemetryRow> = {}): TelemetryRow => ({
    t,
    humanPowerW: 100,
    cadenceRpm: 80,
    motorInputPowerW: 300,
    batteryVoltageV: 50.25,
    ...values,
  });

  const ride: RideSpec = {
    end: 4,
    telemetry: [
      telemetry(1),
      telemetry(1.25, { humanPowerW: 110 }),
      telemetry(2, { motorInputPowerW: -50 }),
      telemetry(3),
    ],
    gps: [1, 2, 3].map(t => ({
      t,
      latitude: 48.1234567,
      longitude: 11.5,
      altitudeMeters: 520.25,
      horizontalAccuracyM: 3,
      verticalAccuracyM: 3,
      speedMps: 5.125,
    })),
    health: [{ t: 2, heartRateBpm: 120.5, representation: 'rawSeries' }],
  };

  it('writes the FIT record seconds as rows with every value as a named column', async () => {
    const csv = await runCsv(ride);
    expect(csv.header).toEqual([
      'timestamp',
      'elapsedSeconds',
      'lap',
      'latitude',
      'longitude',
      'altitudeMeters',
      'speedMps',
      'distanceMeters',
      'heartRateBpm',
      'humanPowerW',
      'cadenceRpm',
      'motorInputPowerW',
      'assistLevel',
      'batteryVoltageV',
      'batteryCurrentA',
      'motorCurrentA',
      'motorRpm',
      'motorTempC',
      'controllerTempC',
      'pedalTorqueNm',
      'controllerEnergyWh',
      'controllerChargeAh',
    ]);
    expect(seconds(csv.rows)).toEqual(fitSeconds((await runFit(ride)).bytes));
    expect(csv.rows[0]).toMatchObject({
      timestamp: '2026-01-01T00:00:01Z',
      elapsedSeconds: '1',
      lap: '1',
      latitude: '48.1234567',
      longitude: '11.5',
      altitudeMeters: '520.25',
      speedMps: '5.125',
      heartRateBpm: '',
      humanPowerW: '105',
      cadenceRpm: '80',
      motorInputPowerW: '300',
      batteryVoltageV: '50.25',
      batteryCurrentA: '',
    });
    expect(csv.rows[1]).toMatchObject({ heartRateBpm: '120.5', motorInputPowerW: '-50' });
    expect(csv.result).toMatchObject({ uri: 'memory://power-log-ride-1.csv', rows: csv.rows.length });
    expect(csv.kinds).toEqual(['csv']);
    expect(csv.source.closed).toBeGreaterThan(0);
  });

  it('counts laps and leaves paused seconds out', async () => {
    const paused: RideSpec = {
      end: 9,
      lifecycle: [
        { t: 0, action: 'start' },
        { t: 2.5, action: 'lap' },
        { t: 4.5, action: 'pause' },
        { t: 6.5, action: 'resume' },
        { t: 9, action: 'stop' },
      ],
      telemetry: Array.from({ length: 9 }, (_, i) => telemetry(i + 0.2)),
    };
    const csv = await runCsv(paused);
    expect(seconds(csv.rows)).toEqual([0, 1, 2, 3, 4, 7, 8]);
    expect(csv.rows.map(row => row.lap)).toEqual(['1', '1', '1', '2', '2', '2', '2']);
    expect(seconds(csv.rows)).toEqual(fitSeconds((await runFit(paused)).bytes));
  });

  it.each(['controller', 'calculated'] as const)('names the ride totals by the %s source', async source => {
    const consumption: RideSpec = {
      end: 4,
      batteryConsumptionSource: source,
      telemetry: Array.from({ length: 33 }, (_, i) => ({
        t: i / 8,
        humanPowerW: 100,
        batteryVoltageV: 50,
        batteryCurrentA: 10,
        consumedWh: 123 + i * 0.5,
        consumedAh: 12 + i * 0.01,
      })),
    };
    const csv = await runCsv(consumption);
    expect(csv.header.slice(-2)).toEqual([`${source}EnergyWh`, `${source}ChargeAh`]);
    const last = csv.rows.at(-1)!;
    expect(last[`${source}EnergyWh`]).toBe(source === 'controller' ? '16' : '0.5556');
    expect(last[`${source}ChargeAh`]).toBe(source === 'controller' ? '0.32' : '0.0111');
  });

  it('names the CSV file when a ride is too long for a per-second export', async () => {
    const source = new MemorySource({ end: FIT_MAX_RIDE_SECONDS + 1 });
    const failure = await exportCsv(source, async () => new MemorySink(), {
      rideId: 'ride-1',
      context: { exportedAt: CONTEXT_TIME, platform: source.platform },
    }).catch((error: unknown) => error);
    expect(failure).toMatchObject({
      code: 'limit',
      message: 'A CSV file cannot hold a ride longer than 31 days. The ride-data ZIP is still available.',
    });
  });

  it('fails without rows and discards the unfinished file', async () => {
    const source = new MemorySource({ end: 4 });
    const sink = new MemorySink();
    const failure = await exportCsv(source, async () => sink, {
      rideId: 'ride-1',
      context: { exportedAt: CONTEXT_TIME, platform: source.platform },
    }).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ExportError);
    expect(failure).toMatchObject({ code: 'noRecords', message: expect.stringContaining('CSV file') });
    expect(sink.aborted).toBe(true);
    expect(source.closed).toBeGreaterThan(0);
  });
});
