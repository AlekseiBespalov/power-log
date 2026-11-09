import { ExportError } from './types';

export const FIT_EPOCH_SECONDS = 631065600;
const UTC_TIMESTAMP = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?(?:Z|\+00:00)$/;

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

export function parseStartedAt(startedAt: string): ExportStart {
  const start = typeof startedAt === 'string' ? parseUtc(startedAt) : null;
  if (!start) throw new ExportError('exportNotReady', "This ride's start time can't be read, so it can't be exported.");
  return start;
}

export function fitClock(start: ExportStart): FitClock {
  const base = start.startSec;
  const f = start.startMs / 1000;
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
