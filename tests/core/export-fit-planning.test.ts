import { describe, expect, it } from 'vitest';
import { FIT_RECORD_LIMIT } from '../../src/core/export/fit/planner';
import {
  FIT_START,
  fitFailure,
  readFit,
  records,
  runFit,
  type DistanceRow,
  type GpsRow,
  type HealthRow,
  type LifecycleRow,
  type RideSpec,
  type TelemetryRow,
} from '../fixtures/export/fit/ride';

function random(seed: number): () => number {
  let state = seed * 2654435761;
  return () => {
    state = (state ^ (state << 13)) >>> 0;
    state = (state ^ (state >>> 17)) >>> 0;
    state = (state ^ (state << 5)) >>> 0;
    return state / 4294967296;
  };
}

const tenth = (value: number) => Math.round(value * 10) / 10;

function smallRide(seed: number): RideSpec {
  const next = random(seed);
  const end = 20 + Math.floor(next() * 20);
  const lifecycle: LifecycleRow[] = [{ t: 0, action: 'start', sequence: 1 }];
  let t = 2 + next() * 4;
  let sequence = 2;
  while (t < end - 4) {
    const choice = next();
    if (choice < 0.35) {
      lifecycle.push({ t: tenth(t), action: 'pause', sequence: sequence++ });
      lifecycle.push({ t: tenth(t + 1 + next() * 2), action: 'resume', sequence: sequence++ });
    } else if (choice < 0.55) {
      const at = tenth(t);
      lifecycle.push({ t: at, action: 'pause', sequence: sequence++, interrupted: true, cyc: 1000, epoch: 'old' });
      lifecycle.push({ t: at, action: 'resume', sequence: sequence++, epoch: 'new' });
    } else lifecycle.push({ t: tenth(t), action: 'lap', sequence: sequence++ });
    t += 3 + next() * 5;
  }
  lifecycle.push({ t: end, action: 'stop', sequence });
  const telemetry: TelemetryRow[] = [];
  let connection = 'a';
  let epoch = 'e1';
  for (let at = 0; at <= end + 0.5; at += 0.1 + Math.floor(next() * 4) * 0.25) {
    if (next() < 0.05) connection = connection === 'a' ? 'b' : 'a';
    if (next() < 0.04) epoch = epoch === 'e1' ? 'e2' : 'e1';
    const kind = next();
    const row: TelemetryRow = { t: tenth(at), connection, epoch };
    if (kind < 0.55) Object.assign(row, { humanPowerW: Math.floor(next() * 400), cadenceRpm: 80, batteryVoltageV: 50 });
    else if (kind < 0.7)
      Object.assign(row, { humanPowerW: 40000, cadenceRpm: 300, motorInputPowerW: 70000, motorTempC: 3276.7 });
    else if (kind < 0.8) Object.assign(row, { humanPowerW: -5, controllerTempC: -3276.8, batteryVoltageV: 49 });
    else if (kind < 0.9) Object.assign(row, { assistLevel: Math.floor(next() * 5), motorInputPowerW: -20 });
    else Object.assign(row, { motorInputPowerW: 150, motorTempC: 40 });
    telemetry.push(row);
  }
  const gps: GpsRow[] = [];
  for (let at = next() * 2; at <= end; at += 0.5 + next() * 2) {
    const r = next();
    gps.push({
      t: tenth(at),
      producer: r < 0.3 ? 'watch' : 'phone',
      latitude: 45,
      longitude: 7 + at * 0.00001,
      horizontalAccuracyM: r > 0.85 ? 60 : 5,
      distanceBarrier: r > 0.8 && r <= 0.85,
      altitudeMeters: 100 + at,
      verticalAccuracyM: 3,
      speedMps: 4,
    });
  }
  const health: HealthRow[] = [];
  for (let at = next() * 3; at <= end; at += 1 + next() * 3)
    health.push({
      t: tenth(at),
      heartRateBpm: next() < 0.1 ? 0 : 100 + Math.floor(next() * 50),
      representation: 'rawSeries',
    });
  const distance: DistanceRow[] = [];
  for (let at = next() * 2; at < end - 1;) {
    const length = tenth(0.5 + next() * 3);
    const start = tenth(at);
    const stop = Math.min(end, tenth(start + length));
    if (stop > start) distance.push({ start, end: stop, meters: stop - start, startSpeed: 1, endSpeed: 1 });
    at = stop + (next() < 0.5 ? 0 : tenth(next() * 2));
  }
  return {
    platform: 'ios',
    end,
    lifecycle,
    telemetry,
    gps,
    health,
    profile: { source: 'controller', kind: 'controller' },
    distance,
  };
}

describe('record interval planning end to end', () => {
  it('chooses the interval a brute-force search over full emissions chooses', async () => {
    let planned = 0;
    for (let seed = 1; seed <= 24; seed++) {
      const ride = smallRide(seed);
      const counts: number[] = [];
      for (let interval = 1; interval <= ride.end + 2; interval++) {
        const run = await runFit(ride, { recordInterval: interval });
        expect(records(readFit(run.bytes))).toHaveLength(run.result.records);
        counts.push(run.result.records);
      }
      const last = counts[counts.length - 1]!;
      const limits = new Set([counts[0]! - 1, counts[1]!, counts[2]! - 1, Math.ceil(counts[0]! / 3), last, last - 1]);
      for (const limit of limits) {
        if (limit < 1) continue;
        const brute = counts.findIndex(count => count <= limit) + 1;
        if (brute === 0) {
          expect((await fitFailure(ride, { recordLimit: limit })).code).toBe('limit');
          continue;
        }
        const { result, source } = await runFit(ride, { recordLimit: limit });
        expect(result.recordInterval, `seed ${seed}, limit ${limit}`).toBe(brute);
        expect(result.records).toBe(counts[brute - 1]);
        expect(source.passes.telemetry).toBe(2);
        planned++;
      }
    }
    expect(planned).toBeGreaterThan(80);
  });

  it('gives the same file at every page size when it plans', async () => {
    for (const seed of [3, 7, 11]) {
      const ride = smallRide(seed);
      const tuning = { recordLimit: Math.ceil((await runFit(ride)).result.records / 2) };
      const reference = await runFit(ride, tuning);
      for (const pageSize of [1, 17])
        expect(
          Buffer.from((await runFit({ ...ride, pageSize }, tuning)).bytes).equals(Buffer.from(reference.bytes)),
        ).toBe(true);
    }
  });

  it('reads telemetry once while the possible seconds fit the limit', async () => {
    const ride = (end: number): RideSpec => ({
      end,
      telemetry: [
        { t: 1, humanPowerW: 100 },
        { t: end, humanPowerW: 100 },
      ],
    });
    const fits = await runFit(ride(99_997));
    expect(fits.source.passes.telemetry).toBe(1);
    expect(fits.result).toMatchObject({ recordInterval: 1, records: 2 });
    const plans = await runFit(ride(99_997.5));
    expect(plans.source.passes.telemetry).toBe(2);
    expect(plans.result).toMatchObject({ recordInterval: 1, records: 2 });
  });
});

describe('record interval planning at the Garmin Connect limit', () => {
  const sparse = (count: number): RideSpec => ({
    end: 2 * count,
    telemetry: Array.from({ length: count }, (_, i) => ({ t: 2 * i, humanPowerW: 100 })),
  });

  it('keeps one-second records for 99,998 sparse witness seconds and plans for 99,999', async () => {
    const fits = await runFit(sparse(99_998));
    expect(fits.result).toMatchObject({ recordInterval: 1, records: 99_998 });
    expect(fits.source.passes.telemetry).toBe(2);
    const plans = await runFit(sparse(99_999));
    expect(plans.result).toMatchObject({ recordInterval: 3, records: 66_666 });
    const timestamps = records(readFit(plans.bytes)).map(message => message.fields.get(253)! - FIT_START);
    expect(timestamps.slice(0, 5)).toEqual([0, 4, 6, 10, 12]);
  });

  const pauseHeavy = (witnesses: number): RideSpec => {
    const lifecycle: LifecycleRow[] = [{ t: 0, action: 'start' }];
    const telemetry: TelemetryRow[] = [];
    for (let k = 0; telemetry.length < witnesses; k++) {
      if (k > 0) {
        lifecycle.push({ t: 21 * k - 1, action: 'pause' });
        lifecycle.push({ t: 21 * k, action: 'resume' });
      }
      for (let j = 0; j < 20 && telemetry.length < witnesses; j++) telemetry.push({ t: 21 * k + j, humanPowerW: 100 });
    }
    const end = telemetry[telemetry.length - 1]!.t + 1;
    lifecycle.push({ t: end, action: 'stop' });
    return { end, lifecycle, telemetry };
  };

  it('keeps one-second records for a pause-heavy ride with 99,998 witness seconds and plans for 99,999', async () => {
    const fits = await runFit(pauseHeavy(99_998));
    expect(fits.result).toMatchObject({ recordInterval: 1, records: 99_998 });
    const ride = pauseHeavy(99_999);
    expect(ride.lifecycle!.length).toBe(10_000);
    const plans = await runFit(ride);
    const intervals = Math.ceil(99_999 / 20);
    const perInterval = (k: number, seconds: number) =>
      Math.floor((21 * k + seconds - 1) / 2) - Math.floor((21 * k) / 2) + 1;
    let expected = 0;
    for (let k = 0; k < intervals; k++) expected += perInterval(k, Math.min(20, 99_999 - 20 * k));
    expect(plans.result).toMatchObject({ recordInterval: 2, records: expected });
    expect((await runFit(ride, { recordInterval: 2 })).result.records).toBe(expected);
  });

  it('plans from witness seconds, not from rejected GPS seconds', async () => {
    const gps: GpsRow[] = Array.from({ length: 150_000 }, (_, i) => ({
      t: i,
      latitude: 45,
      longitude: 7,
      horizontalAccuracyM: i % 2 === 0 ? 60 : 5,
    }));
    const { result } = await runFit({ end: 150_000, gps });
    expect(result).toMatchObject({ recordInterval: 1, records: 75_000 });
  });

  it('fails at once when break-free stretches alone exceed the limit', async () => {
    const telemetry = Array.from({ length: FIT_RECORD_LIMIT + 1 }, (_, i) => ({
      t: 2 * i,
      humanPowerW: 100,
      connection: `connection-${i}`,
    }));
    const failure = await fitFailure({ end: 2 * telemetry.length, telemetry });
    expect(failure.code).toBe('limit');
    expect(failure.source.passes.telemetry).toBe(1);
    expect(failure.sink.length).toBe(0);
    expect(failure.sink.aborted).toBe(false);
  });
});

describe('72-hour rides', () => {
  const hours72 = 72 * 3600;

  it('fails at once with a run break every 2 s', async () => {
    const telemetry = Array.from({ length: hours72 + 1 }, (_, i) => ({
      t: i,
      humanPowerW: 100,
      connection: Math.floor(i / 2) % 2 === 0 ? 'a' : 'b',
    }));
    const started = performance.now();
    const failure = await fitFailure({ end: hours72, telemetry });
    const elapsed = performance.now() - started;
    console.info(`72-h ride with a run break every 2 s: planning failed after ${elapsed.toFixed(0)} ms`);
    expect(failure.code).toBe('limit');
    expect(failure.source.passes.telemetry).toBe(1);
  }, 60_000);

  it('plans an uninterrupted ride to 3-second records', async () => {
    const telemetry = Array.from({ length: hours72 + 1 }, (_, i) => ({ t: i, humanPowerW: 100 }));
    const started = performance.now();
    const { result } = await runFit({ end: hours72, telemetry });
    const elapsed = performance.now() - started;
    console.info(`Uninterrupted 72-h ride: planned and exported in ${elapsed.toFixed(0)} ms`);
    expect(result).toMatchObject({ recordInterval: 3, records: hours72 / 3 + 1 });
  }, 60_000);
});
