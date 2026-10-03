import { describe, expect, it } from 'vitest';
import { fitClock } from '../../src/core/export/clock';
import {
  FIT_MAX_RECORD_INTERVAL,
  WitnessSeconds,
  countRecords,
  needsPlanning,
  planRecordInterval,
  recordSegments,
} from '../../src/core/export/fit/planner';
import {
  DISTANCE,
  GPS,
  HEART_RATE,
  RecordEmitter,
  TELEMETRY,
  type WitnessRows,
} from '../../src/core/export/fit/records';
import type { FitWriter } from '../../src/core/export/fit/writer';
import { createSlicer } from '../../src/core/export/pages';
import { ExportError } from '../../src/core/export/types';

const slicer = createSlicer();
const clock = (startMs = 0) => fitClock({ startSec: 1767225600, startMs }, startMs);

interface Step {
  stream: number;
  second: number;
  interval: number;
  run: number;
}

const NONE = Float64Array.of(NaN);
const converted = new WeakMap<Step, WitnessRows>();

function rows({ stream, second, interval, run }: Step): WitnessRows {
  const row = {
    rows: 1,
    elapsed: Float64Array.of(second),
    second: Float64Array.of(second),
    interval: Int32Array.of(interval),
    run: Int32Array.of(run),
    witness: Uint8Array.of(1),
  };
  if (stream === TELEMETRY)
    return { ...row, stream, power: NONE, cadence: NONE, assist: NONE, motor: NONE, channels: [] };
  if (stream === GPS) return { ...row, stream, latitude: NONE, longitude: NONE, altitude: NONE, speed: NONE };
  if (stream === HEART_RATE) return { ...row, stream, heartRate: NONE };
  return { ...row, stream: DISTANCE, distance: NONE };
}

function witness(step: Step): WitnessRows {
  let result = converted.get(step);
  if (!result) converted.set(step, (result = rows(step)));
  return result;
}

function emitted(steps: readonly Step[], interval: number): number {
  let records = 0;
  const writer = { message: () => records++, drain: async () => undefined } as unknown as FitWriter;
  const emitter = new RecordEmitter(writer, clock(), interval, () => undefined);
  for (const step of steps) emitter.witnesses(witness(step), 0, 1);
  emitter.finish();
  expect(emitter.records).toBe(records);
  return records;
}

function seconds(steps: readonly Step[]): WitnessSeconds {
  const collector = new WitnessSeconds();
  for (const step of steps) collector.witnesses(witness(step), 0, 1);
  return collector;
}

function random(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state ^ (state >>> 15), 0x2c1b3c6d) + 0x9e3779b9) >>> 0;
    state ^= state >>> 13;
    return (state >>> 0) / 4294967296;
  };
}

function ride(seed: number, length: number): Step[] {
  const next = random(seed);
  const steps: Step[] = [];
  const runs = [1, 1];
  let second = Math.floor(next() * 3);
  let interval = 1;
  for (let i = 0; i < length; i++) {
    const r = next();
    second += r < 0.35 ? 0 : r < 0.8 ? 1 : r < 0.92 ? 2 : Math.floor(next() * 9);
    if (next() < 0.04) interval++;
    const stream = [TELEMETRY, TELEMETRY, GPS, HEART_RATE, DISTANCE][Math.floor(next() * 5)]!;
    if ((stream === TELEMETRY || stream === GPS) && next() < 0.08) runs[stream]!++;
    steps.push({ stream, second, interval, run: stream === TELEMETRY || stream === GPS ? runs[stream]! : 0 });
  }
  return steps;
}

describe('record planning against brute-force emission', () => {
  it('counts exactly the records the emitter writes at every interval, above both bounds', async () => {
    for (let seed = 1; seed <= 60; seed++) {
      const steps = ride(seed, 20 + (seed % 7) * 15);
      const collected = seconds(steps);
      const segments = recordSegments(collected);
      const breaks = collected.breakCount;
      const distinct = collected.count;
      for (let interval = 1; interval <= 40; interval++) {
        const planned = await countRecords(segments, 0, interval, slicer);
        expect(planned, `seed ${seed}, I ${interval}`).toBe(emitted(steps, interval));
        expect(planned).toBeGreaterThanOrEqual(breaks);
        expect(planned).toBeGreaterThanOrEqual(Math.ceil(distinct / interval));
      }
    }
  });

  it('chooses the smallest interval whose emission fits the limit', async () => {
    let checked = 0;
    let failed = 0;
    for (let seed = 100; seed < 160; seed++) {
      const steps = ride(seed, 60);
      const counts = new Map<number, number>();
      const count = (interval: number) => {
        if (!counts.has(interval)) counts.set(interval, emitted(steps, interval));
        return counts.get(interval)!;
      };
      const collected = seconds(steps);
      const limits = new Set([count(1), count(1) - 1, Math.ceil(count(1) / 2), count(6), collected.breakCount, 2]);
      for (const limit of limits) {
        if (limit < 1) continue;
        let brute = 0;
        for (let interval = 1; interval <= FIT_MAX_RECORD_INTERVAL && brute === 0; interval++)
          if (count(interval) <= limit) brute = interval;
        const plan = await planRecordInterval(collected, 0, slicer, limit).catch((error: unknown) => error);
        if (brute === 0) {
          expect(plan).toBeInstanceOf(ExportError);
          failed++;
          continue;
        }
        const lower = Math.ceil(collected.count / limit);
        expect(plan, `seed ${seed}, limit ${limit}`).toMatchObject({
          interval: brute,
          records: count(brute),
          candidates: Array.from({ length: brute - lower + 1 }, (_, i) => lower + i),
        });
        checked++;
      }
    }
    expect(checked).toBeGreaterThan(150);
    expect(failed).toBeGreaterThan(10);
  });

  it('merges a break inside a second into that second’s record, but not into an older one', async () => {
    const steps: Step[] = [
      { stream: TELEMETRY, second: 10, interval: 1, run: 1 },
      { stream: TELEMETRY, second: 11, interval: 1, run: 1 },
      { stream: TELEMETRY, second: 11, interval: 1, run: 2 },
      { stream: GPS, second: 11, interval: 2, run: 1 },
      { stream: TELEMETRY, second: 11, interval: 2, run: 2 },
    ];
    expect(emitted(steps, 1)).toBe(2);
    expect(emitted(steps, 3)).toBe(2);
    expect(emitted(steps.slice(0, 2), 3)).toBe(1);
    expect(await countRecords(recordSegments(seconds(steps)), 0, 3, slicer)).toBe(2);
    expect(await countRecords(recordSegments(seconds(steps.slice(0, 2))), 0, 3, slicer)).toBe(1);
  });

  it('treats the first witness of a stream as continuing its run', () => {
    expect(
      emitted(
        [
          { stream: HEART_RATE, second: 0, interval: 1, run: 0 },
          { stream: TELEMETRY, second: 1, interval: 1, run: 7 },
          { stream: GPS, second: 2, interval: 1, run: 3 },
        ],
        5,
      ),
    ).toBe(1);
  });
});

describe('planner bounds', () => {
  it('needs no planning while the span of possible seconds fits the limit', () => {
    expect(needsPlanning(clock(), 99_997)).toBe(false);
    expect(needsPlanning(clock(), 99_997.49)).toBe(false);
    expect(needsPlanning(clock(), 99_997.5)).toBe(true);
    expect(needsPlanning(clock(500), 99_997.99)).toBe(false);
    expect(needsPlanning(clock(500), 99_998)).toBe(true);
  });

  it('starts at ceil(W / limit) and takes the first count that fits', async () => {
    const steps = Array.from({ length: 99_999 }, (_, i) => ({ stream: TELEMETRY, second: 2 * i, interval: 1, run: 1 }));
    const collected = seconds(steps);
    const segments = recordSegments(collected);
    expect(await countRecords(segments, 0, 1, slicer)).toBe(99_999);
    expect(await countRecords(segments, 0, 2, slicer)).toBe(99_999);
    expect(Math.ceil(collected.count / 99_998)).toBe(2);
    expect(await planRecordInterval(collected, 0, slicer)).toEqual({
      interval: 3,
      records: 66_666,
      breaks: 1,
      seconds: 99_999,
      candidates: [2, 3],
    });
  });

  it('fails at once when the break-free stretches alone exceed the limit', async () => {
    const steps = Array.from({ length: 99_999 }, (_, i) => ({ stream: TELEMETRY, second: i, interval: 1, run: i + 1 }));
    const collected = seconds(steps);
    expect(collected.breakCount).toBe(99_999);
    let ticks = 0;
    const counting = { tick: () => (ticks++, Promise.resolve()) };
    const error = await planRecordInterval(collected, 0, counting).catch((caught: unknown) => caught);
    expect((error as ExportError).code).toBe('limit');
    expect(ticks).toBe(0);
    const fits = Array.from({ length: 99_998 }, (_, i) => ({ stream: TELEMETRY, second: i, interval: 1, run: i + 1 }));
    expect(await planRecordInterval(seconds(fits), 0, slicer)).toMatchObject({ interval: 1, records: 99_998 });
  });

  it('fails when no interval up to an hour fits', async () => {
    const steps: Step[] = [];
    for (let i = 0; i < 40; i++) steps.push({ stream: TELEMETRY, second: i * 3600, interval: 1, run: 1 });
    const collected = seconds(steps);
    expect(FIT_MAX_RECORD_INTERVAL).toBe(3600);
    const error = await planRecordInterval(collected, 0, slicer, 39).catch((caught: unknown) => caught);
    expect((error as ExportError).code).toBe('limit');
    expect(await planRecordInterval(collected, 0, slicer, 40)).toMatchObject({ interval: 1 });
  });

  it('fails without witnesses', async () => {
    const error = await planRecordInterval(new WitnessSeconds(), 0, slicer).catch((caught: unknown) => caught);
    expect((error as ExportError).code).toBe('noRecords');
  });
});
