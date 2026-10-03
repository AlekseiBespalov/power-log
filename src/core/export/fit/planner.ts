import type { FitClock } from '../clock';
import type { ExportSlicer } from '../pages';
import { ExportError } from '../types';
import { BinBreaks, NEW_RECORD, RecordBins, type MergeConsumer, type WitnessRows, type WitnessTie } from './records';

export const FIT_RECORD_LIMIT = 99_998;
export const FIT_MAX_RECORD_INTERVAL = 3600;
const COUNT_BATCH = 4096;

export const noRecords = () =>
  new ExportError(
    'noRecords',
    'This ride has no power, cadence, heart rate, GPS or distance readings for a FIT file. The ride-data ZIP is still available.',
  );

const tooFragmented = () =>
  new ExportError(
    'limit',
    'This ride has too many separate stretches of readings (reconnects, pauses or gaps) for the 99,998-point limit of Garmin Connect.',
  );

export function needsPlanning(clock: FitClock, end: number, limit = FIT_RECORD_LIMIT): boolean {
  return clock.second(end) - clock.second(0) + 1 > limit;
}

export class WitnessSeconds implements MergeConsumer {
  seconds = new Int32Array(1024);
  flags = new Uint8Array(1024);
  count = 0;
  private readonly breaks = new BinBreaks();
  private readonly tieBreaks: boolean[] = [];

  witnesses(rows: WitnessRows, from: number, to: number): void {
    const { stream, witness, second, interval, run } = rows;
    for (let i = from; i < to; i++)
      if (witness[i] === 1) this.add(second[i]!, this.breaks.next(stream, interval[i]!, run[i]!));
  }

  tie(tie: WitnessTie): void {
    this.breaks.tie(tie, this.tieBreaks);
    this.add(tie.second, this.tieBreaks.includes(true));
  }

  lifecycle(): void {}

  get breakCount(): number {
    let breaks = 0;
    for (let i = 0; i < this.count; i++) breaks += this.flags[i]!;
    return breaks;
  }

  private add(second: number, brk: boolean): void {
    const last = this.count - 1;
    if (last >= 0 && this.seconds[last] === second) {
      if (brk) this.flags[last] = 1;
      return;
    }
    if (this.count === this.seconds.length) {
      const seconds = new Int32Array(2 * this.count);
      const flags = new Uint8Array(2 * this.count);
      seconds.set(this.seconds);
      flags.set(this.flags);
      this.seconds = seconds;
      this.flags = flags;
    }
    this.seconds[this.count] = second;
    this.flags[this.count] = brk ? 1 : 0;
    this.count++;
  }
}

export interface RecordSegments {
  readonly starts: Int32Array;
  readonly ends: Int32Array;
  readonly flags: Uint8Array;
  readonly count: number;
}

export function recordSegments(witnesses: WitnessSeconds): RecordSegments {
  const { seconds, flags, count } = witnesses;
  const starts = new Int32Array(count);
  const ends = new Int32Array(count);
  const segmentFlags = new Uint8Array(count);
  let segments = 0;
  for (let i = 0; i < count; i++) {
    const second = seconds[i]!;
    if (segments > 0 && flags[i] === 0 && second === ends[segments - 1]! + 1) {
      ends[segments - 1] = second;
      continue;
    }
    starts[segments] = second;
    ends[segments] = second;
    segmentFlags[segments] = flags[i]!;
    segments++;
  }
  return { starts, ends, flags: segmentFlags, count: segments };
}

function countSegments(segments: RecordSegments, bins: RecordBins, from: number, to: number): number {
  const { starts, ends, flags } = segments;
  let records = 0;
  for (let k = from; k < to; k++) {
    if (bins.next(starts[k]!, flags[k] === 1) === NEW_RECORD) records++;
    if (ends[k]! > starts[k]!) records += bins.span(ends[k]!);
  }
  return records;
}

export async function countRecords(
  segments: RecordSegments,
  origin: number,
  interval: number,
  slicer: ExportSlicer,
): Promise<number> {
  const bins = new RecordBins(origin, interval);
  let records = 0;
  for (let from = 0; from < segments.count; from += COUNT_BATCH) {
    const to = Math.min(segments.count, from + COUNT_BATCH);
    records += countSegments(segments, bins, from, to);
    if (to - from === COUNT_BATCH) await slicer.tick();
  }
  return records;
}

export interface RecordPlan {
  readonly interval: number;
  readonly records: number;
  readonly breaks: number;
  readonly seconds: number;
  readonly candidates: readonly number[];
}

export async function planRecordInterval(
  witnesses: WitnessSeconds,
  origin: number,
  slicer: ExportSlicer,
  limit = FIT_RECORD_LIMIT,
): Promise<RecordPlan> {
  const seconds = witnesses.count;
  if (seconds === 0) throw noRecords();
  const breaks = witnesses.breakCount;
  if (breaks > limit) throw tooFragmented();
  const segments = recordSegments(witnesses);
  const candidates: number[] = [];
  for (let interval = Math.ceil(seconds / limit); interval <= FIT_MAX_RECORD_INTERVAL; interval++) {
    candidates.push(interval);
    const records = await countRecords(segments, origin, interval, slicer);
    if (records <= limit) return { interval, records, breaks, seconds, candidates };
  }
  throw tooFragmented();
}
