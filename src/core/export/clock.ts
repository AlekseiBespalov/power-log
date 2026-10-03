import type { ExportSlicer } from './pages';
import { ExportError, type ExportColumns, type ExportPlatform } from './types';

export const FIT_EPOCH_SECONDS = 631065600;
const SELECTION_BATCH = 4096;
const UTC_TIMESTAMP = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?(?:Z|\+00:00)$/;
const MINUTE_LENGTH = 'YYYY-MM-DDTHH:MM:'.length;
const DIGIT_ZERO = '0'.charCodeAt(0);

export interface ExportStart {
  readonly startSec: number;
  readonly startMs: number;
}

export interface FitClock {
  readonly base: number;
  readonly f: number;
  second(t: number): number;
  seconds(elapsed: Float64Array, rows: number): Float64Array;
  time(t: number): number;
  timestamp(second: number): number;
}

export function nearest(x: number): number {
  const r = Math.floor(x);
  return x - r >= 0.5 ? r + 1 : r;
}

export function roundHalfAway(x: number): number {
  // Subtracting from 0 keeps a zero result unsigned.
  return x < 0 ? 0 - nearest(-x) : nearest(x);
}

function parseUtc(value: string): ExportStart | null {
  const match = UTC_TIMESTAMP.exec(value);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  if (hour > 23 || minute > 59 || second > 59) return null;
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  date.setUTCHours(hour, minute, second);
  return { startSec: date.getTime() / 1000, startMs: match[7] === undefined ? 0 : Number(match[7]) };
}

function digits(value: string, from: number, count: number): number {
  let result = 0;
  for (let i = from; i < from + count; i++) {
    const digit = value.charCodeAt(i) - DIGIT_ZERO;
    if (!(digit >= 0 && digit <= 9)) return NaN;
    result = result * 10 + digit;
  }
  return result;
}

export function parseStartedAt(startedAt: string): ExportStart {
  const start = typeof startedAt === 'string' ? parseUtc(startedAt) : null;
  if (!start) throw new ExportError('gate', 'The ride start time is not a UTC timestamp that can be exported.');
  return start;
}

const xorshift = (x: number) => {
  x ^= x << 13;
  x ^= x >>> 17;
  return x ^ (x << 5);
};

export async function lowerMedian(values: Float64Array, count: number, slicer: ExportSlicer): Promise<number> {
  if (!Number.isSafeInteger(count) || count < 1 || count > values.length)
    throw new RangeError('A median needs at least one value');
  const k = Math.floor((count - 1) / 2);
  let lo = 0;
  let hi = count - 1;
  let seed = 0x2545f491;
  let work = 0;
  while (lo < hi) {
    seed = xorshift(seed);
    const pivot = values[lo + ((seed >>> 0) % (hi - lo + 1))]!;
    let i = lo;
    let j = hi;
    while (i <= j) {
      if (values[i]! < pivot) i++;
      else if (values[j]! > pivot) j--;
      else {
        const swap = values[i]!;
        values[i++] = values[j]!;
        values[j--] = swap;
      }
      if (++work === SELECTION_BATCH) {
        work = 0;
        await slicer.tick();
      }
    }
    if (k <= j) hi = j;
    else if (k >= i) lo = i;
    else return values[k]!;
  }
  return values[k]!;
}

export class FitAnchor {
  private values = new Float64Array(1024);
  private count = 0;
  private minute: string | null = null;
  private minuteStart = 0;

  constructor(
    readonly platform: ExportPlatform,
    readonly start: ExportStart,
  ) {}

  get samples(): number {
    return this.count;
  }

  add(elapsed: number, timestamp: string | null | undefined): void {
    if (this.platform !== 'android' || !Number.isFinite(elapsed) || elapsed < 0 || typeof timestamp !== 'string')
      return;
    const time = this.parse(timestamp);
    if (!time) return;
    if (this.count === this.values.length) {
      const grown = new Float64Array(this.count * 2);
      grown.set(this.values);
      this.values = grown;
    }
    this.values[this.count++] = (time.startSec - this.start.startSec) * 1000 + time.startMs - nearest(elapsed * 1000);
  }

  addPage(page: { rows: number; columns: ExportColumns<'gpsDiscovery'> }, intervals: Int32Array): void {
    if (this.platform !== 'android' || page.rows === 0) return;
    const { elapsedSeconds, timestamp } = page.columns;
    if (!elapsedSeconds || !timestamp)
      throw new ExportError('page', 'The ride data could not be read for export: GPS times are missing.');
    for (let i = 0; i < page.rows; i++) if (intervals[i] !== 0) this.add(elapsedSeconds[i]!, timestamp[i]);
  }

  // A timestamp that repeats the minute of the last one parsed differs only in what UTC_TIMESTAMP reads after it.
  private parse(timestamp: string): ExportStart | null {
    if (this.minute === null || !timestamp.startsWith(this.minute)) {
      const time = parseUtc(timestamp);
      if (time) {
        this.minute = timestamp.slice(0, MINUTE_LENGTH);
        this.minuteStart = time.startSec - digits(timestamp, MINUTE_LENGTH, 2);
      }
      return time;
    }
    const fraction = timestamp[MINUTE_LENGTH + 2] === '.';
    const zone = MINUTE_LENGTH + (fraction ? 6 : 2);
    const zoned =
      timestamp.length === zone + 1
        ? timestamp[zone] === 'Z'
        : timestamp.length === zone + 6 && timestamp.endsWith('+00:00');
    const second = digits(timestamp, MINUTE_LENGTH, 2);
    const milliseconds = fraction ? digits(timestamp, MINUTE_LENGTH + 3, 3) : 0;
    if (!zoned || !(second <= 59) || Number.isNaN(milliseconds)) return null;
    return { startSec: this.minuteStart + second, startMs: milliseconds };
  }

  value(slicer: ExportSlicer): Promise<number> {
    if (this.count === 0) return Promise.resolve(this.start.startMs);
    return lowerMedian(this.values, this.count, slicer);
  }
}

export function fitClock(start: ExportStart, anchor: number): FitClock {
  if (!Number.isSafeInteger(anchor)) throw new RangeError('The FIT clock anchor must be whole milliseconds');
  const remainder = ((anchor % 1000) + 1000) % 1000;
  const base = start.startSec + (anchor - remainder) / 1000;
  const f = remainder / 1000;
  return {
    base,
    f,
    second: t => nearest(f + t),
    seconds: (elapsed, rows) => {
      const seconds = new Float64Array(rows);
      for (let i = 0; i < rows; i++) seconds[i] = nearest(f + elapsed[i]!);
      return seconds;
    },
    time: t => base + nearest(f + t) - FIT_EPOCH_SECONDS,
    timestamp: second => base - FIT_EPOCH_SECONDS + second,
  };
}
