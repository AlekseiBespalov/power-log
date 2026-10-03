import { describe, expect, it } from 'vitest';
import { fitClock } from '../../src/core/export/clock';
import { WitnessSeconds } from '../../src/core/export/fit/planner';
import {
  DISTANCE,
  GPS,
  HEART_RATE,
  RecordEmitter,
  TELEMETRY,
  WitnessTie,
  mergeWitnesses,
  type LifecycleGroup,
  type LifecycleItem,
  type MergeConsumer,
  type TelemetryRows,
  type WitnessRows,
  type WitnessStream,
} from '../../src/core/export/fit/records';
import type { FitWriter } from '../../src/core/export/fit/writer';
import type { ActivityInterval } from '../../src/core/export/timeline';
import { FIT_START, readFit, records, runFit, type LifecycleRow, type TelemetryRow } from '../fixtures/export/fit/ride';

const clock = () => fitClock({ startSec: 1767225600, startMs: 0 }, 0);

interface Row {
  stream: number;
  elapsed: number;
  interval: number;
  run: number;
  value: number;
}

class TelemetryPages implements WitnessStream {
  head = NaN;
  index = 0;
  rows!: TelemetryRows;
  private next = 0;

  constructor(
    readonly rank: number,
    private readonly count: number,
    private readonly row: (index: number) => Row,
  ) {}

  async load(): Promise<void> {
    const rows = Math.min(4096, this.count - this.next);
    if (rows === 0) {
      this.head = Infinity;
      return;
    }
    const page = Array.from({ length: rows }, (_, i) => this.row(this.next + i));
    this.next += rows;
    const elapsed = Float64Array.from(page, row => row.elapsed);
    const values = Float64Array.from(page, row => row.value);
    this.rows = {
      stream: TELEMETRY,
      rows,
      elapsed,
      second: elapsed.map(Math.round),
      interval: Int32Array.from(page, row => row.interval),
      run: Int32Array.from(page, row => row.run),
      witness: new Uint8Array(rows).fill(1),
      power: values,
      cadence: values,
      assist: values,
      motor: values,
      channels: [],
    };
    this.index = 0;
    this.head = elapsed[0]!;
  }

  advance(to: number): void {
    this.index = to;
    this.head = to < this.rows.rows ? this.rows.elapsed[to]! : NaN;
  }
}

describe('equal-elapsed groups', () => {
  it('keep bounded state per group however many witnesses tie', async () => {
    const half = 100_000;
    const intervals: ActivityInterval[] = [
      { id: 1, open: 0, close: 5 },
      { id: 2, open: 5, close: 10 },
    ];
    const groups: LifecycleGroup[] = [
      { elapsed: 0, items: [{ kind: 'start', elapsed: 0, interval: 1 }] },
      {
        elapsed: 5,
        items: [
          { kind: 'stop', elapsed: 5, interval: 1 },
          { kind: 'start', elapsed: 5, interval: 2 },
        ],
      },
    ];
    const telemetry = new TelemetryPages(2, 4 * half, i =>
      i < 2 * half
        ? { stream: TELEMETRY, elapsed: 0, interval: 1, run: i < half ? 1 : 2, value: 100 }
        : { stream: TELEMETRY, elapsed: 5, interval: i < 3 * half ? 1 : 2, run: i < 3 * half ? 2 : 3, value: 200 },
    );
    const witnesses: number[] = [];
    const ties: { count: number; segments: number; interval: number }[] = [];
    const order: string[] = [];
    const consumer: MergeConsumer = {
      witnesses: (rows, from, to) => {
        for (let i = from; i < to; i++) if (rows.witness[i] === 1) witnesses.push(rows.elapsed[i]!);
        if (order[order.length - 1] !== 'witness') order.push('witness');
      },
      tie: tie => {
        ties.push({ count: tie.count, segments: tie.segments.length, interval: tie.interval });
        order.push('tie');
      },
      lifecycle: (item: LifecycleItem) => order.push(item.kind),
    };
    let ticks = 0;
    await mergeWitnesses([telemetry], groups, intervals, consumer, { tick: () => (ticks++, Promise.resolve()) });
    expect(ties).toEqual([
      { count: 2 * half, segments: 2, interval: 1 },
      { count: half, segments: 1, interval: 2 },
    ]);
    expect(witnesses).toHaveLength(half);
    expect(order).toEqual(['start', 'tie', 'witness', 'stop', 'start', 'tie']);
    expect(ticks).toBeGreaterThanOrEqual(Math.floor((4 * half) / 256));
  });
});

function random(seed: number): () => number {
  let state = seed * 2654435761;
  return () => {
    state = (state ^ (state << 13)) >>> 0;
    state = (state ^ (state >>> 17)) >>> 0;
    state = (state ^ (state << 5)) >>> 0;
    return state / 4294967296;
  };
}

function witness(stream: number, second: number, interval: number, run: number, value: number): WitnessRows {
  const one = (x: number) => Float64Array.of(x);
  const row = {
    rows: 1,
    elapsed: one(second),
    second: one(second),
    interval: Int32Array.of(interval),
    run: Int32Array.of(run),
    witness: Uint8Array.of(1),
  };
  if (stream === TELEMETRY)
    return {
      ...row,
      stream,
      power: one(value),
      cadence: one(value % 7 === 0 ? NaN : value % 200),
      motor: one(value - 150),
      assist: one(value % 3 === 0 ? NaN : value % 5),
      channels: Array.from({ length: 10 }, (_, j) => one((value + j) % 4 === 0 ? NaN : value + j)),
    };
  if (stream === GPS)
    return {
      ...row,
      stream,
      latitude: one(value / 1000),
      longitude: one(-value / 1000),
      altitude: one(value % 3 === 0 ? NaN : value),
      speed: one(value % 2 === 0 ? NaN : value / 10),
    };
  if (stream === HEART_RATE) return { ...row, stream, heartRate: one(60 + (value % 150)) };
  return { ...row, stream: DISTANCE, distance: one(value) };
}

function replay(prefix: WitnessRows[], tied: WitnessRows[], interval: number, asTie: boolean) {
  const written: number[][] = [];
  const writer = {
    message: (_: string, values: Float64Array, developer: Float64Array) => written.push([...values, ...developer]),
    drain: async () => undefined,
  } as unknown as FitWriter;
  const emitter = new RecordEmitter(writer, clock(), interval, () => undefined);
  const planner = new WitnessSeconds();
  for (const item of prefix) {
    emitter.witnesses(item, 0, 1);
    planner.witnesses(item, 0, 1);
  }
  if (asTie) {
    const tie = new WitnessTie(tied[0]!.interval[0]!, tied[0]!.second[0]!);
    for (const item of tied) tie.add(item, 0);
    emitter.tie(tie);
    planner.tie(tie);
  } else
    for (const item of tied) {
      emitter.witnesses(item, 0, 1);
      planner.witnesses(item, 0, 1);
    }
  emitter.finish();
  return {
    written,
    seconds: [...planner.seconds.subarray(0, planner.count)],
    flags: [...planner.flags.subarray(0, planner.count)],
  };
}

describe('a tie summary', () => {
  it('writes the records and planner seconds that replaying each tied witness writes', () => {
    for (let seed = 1; seed <= 400; seed++) {
      const next = random(seed);
      const pick = (values: readonly number[]) => values[Math.floor(next() * values.length)]!;
      const second = 6 + Math.floor(next() * 3);
      const interval = 1 + Math.floor(next() * 2);
      const runs = [1 + Math.floor(next() * 2), 1 + Math.floor(next() * 2)];
      const prefix: WitnessRows[] = [];
      for (let i = 0, count = Math.floor(next() * 5); i < count; i++) {
        const stream = pick([TELEMETRY, GPS, HEART_RATE, DISTANCE]);
        prefix.push(witness(stream, second - pick([0, 0, 1, 2]), 1, stream < 2 ? runs[stream]! : 0, i * 13 + 100));
      }
      prefix.sort((a, b) => a.second[0]! - b.second[0]!);
      const tied: WitnessRows[] = [];
      for (let i = 0, count = 1 + Math.floor(next() * 12); i < count; i++) {
        const stream = pick([TELEMETRY, TELEMETRY, GPS, GPS, HEART_RATE, DISTANCE]);
        if (stream < 2 && next() < 0.2) runs[stream]! += 1;
        tied.push(witness(stream, second, interval, stream < 2 ? runs[stream]! : 0, Math.floor(next() * 400)));
      }
      for (const binInterval of [1, 2, 3, 7])
        expect(replay(prefix, tied, binInterval, true), `seed ${seed}, I ${binInterval}`).toEqual(
          replay(prefix, tied, binInterval, false),
        );
    }
  });

  it('splits between the pending record and a new one when a run changes inside a longer bin', () => {
    const prefix = [witness(TELEMETRY, 9, 1, 1, 100)];
    const tied = [witness(TELEMETRY, 10, 1, 1, 110), witness(TELEMETRY, 10, 1, 2, 130)];
    const expected = replay(prefix, tied, 3, false);
    expect(expected.written.map(values => [values[0], values[7]])).toEqual([
      [FIT_START + 9, 105],
      [FIT_START + 10, 130],
    ]);
    expect(replay(prefix, tied, 3, true)).toEqual(expected);
  });
});

describe('a FIT export with large equal-elapsed groups', () => {
  it('writes the records of 400,000 tied readings', async () => {
    const half = 100_000;
    const lifecycle: LifecycleRow[] = [
      { t: 0, action: 'start', sequence: 1 },
      { t: 5, action: 'pause', sequence: 2, interrupted: true, epoch: 'old', cyc: 3 * half },
      { t: 5, action: 'resume', sequence: 3, epoch: 'new' },
      { t: 10, action: 'stop', sequence: 4 },
    ];
    const telemetry: TelemetryRow[] = [];
    for (let i = 0; i < 2 * half; i++) telemetry.push({ t: 0, humanPowerW: i % 2 === 0 ? 100 : 200, cadenceRpm: 80 });
    for (let i = 0; i < 2 * half; i++) telemetry.push({ t: 5, humanPowerW: i < half ? 300 : 500, cadenceRpm: 90 });
    telemetry.push({ t: 7, humanPowerW: 50, cadenceRpm: 70 });
    const { bytes, result } = await runFit({ end: 10, lifecycle, telemetry });
    expect(result.records).toBe(3);
    const messages = readFit(bytes).slice(12);
    expect(records(messages).map(message => Object.fromEntries(message.fields))).toEqual([
      { 253: FIT_START, 7: 150, 4: 80 },
      { 253: FIT_START + 5, 7: 400, 4: 90 },
      { 253: FIT_START + 7, 7: 50, 4: 70 },
    ]);
    expect(messages.map(message => message.global)).toEqual([21, 20, 20, 21, 21, 20, 21, 19, 18, 34]);
  }, 60_000);
});
