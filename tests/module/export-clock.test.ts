import { describe, expect, it } from 'vitest';
import {
  FIT_EPOCH_SECONDS,
  fitClock,
  nearest,
  parseStartedAt,
  roundHalfAway,
  type ExportStart,
} from '../../modules/power-log/src/exports/clock';
import { ExportError } from '../../modules/power-log/src/exports/types';

const unix = (iso: string) => Date.parse(iso) / 1000;
const fit = (iso: string) => unix(iso) - FIT_EPOCH_SECONDS;

function gateCode(startedAt: unknown) {
  try {
    parseStartedAt(startedAt as string);
  } catch (error) {
    return error instanceof ExportError ? error.code : 'other';
  }
  return undefined;
}

describe('FIT rounding', () => {
  it('rounds ties to the later second', () => {
    const inputs = [0, 0.25, 0.5, 1.5, 2.5, 3.5, 2.4999999999999996, 0.49999999999999994, 1e9 + 0.5, 7];
    expect(inputs.map(nearest)).toEqual([0, 0, 1, 2, 3, 4, 2, 0, 1e9 + 1, 7]);
  });

  it('rounds measurements half away from zero', () => {
    const inputs = [-0.5, -1.5, -2.5, -2.4999999999999996, -0.49999999999999994, 0.5, 2.5, -7];
    expect(inputs.map(roundHalfAway)).toEqual([-1, -2, -3, -2, 0, 1, 3, -7]);
    expect(Object.is(roundHalfAway(-0.25), 0)).toBe(true);
  });
});

describe('ride start', () => {
  it.each([
    ['2026-03-04T05:06:07.890Z', '2026-03-04T05:06:07Z', 890],
    ['2026-03-04T05:06:07.890+00:00', '2026-03-04T05:06:07Z', 890],
    ['2026-03-04T05:06:07.000Z', '2026-03-04T05:06:07Z', 0],
    ['2026-03-04T05:06:07Z', '2026-03-04T05:06:07Z', 0],
    ['2026-03-04T05:06:07+00:00', '2026-03-04T05:06:07Z', 0],
    ['2024-02-29T23:59:59.999Z', '2024-02-29T23:59:59Z', 999],
    ['2000-01-01T00:00:00.001Z', '2000-01-01T00:00:00Z', 1],
  ])('reads %s', (startedAt, seconds, startMs) => {
    expect(parseStartedAt(startedAt)).toEqual({ startSec: unix(seconds), startMs });
  });

  it('keeps four-digit years below 100', () => {
    expect(parseStartedAt('0099-12-31T23:59:59Z').startSec).toBe(
      Date.UTC(2099, 11, 31, 23, 59, 59) / 1000 - 2000 * 31556952,
    );
  });

  it.each([
    '2026-03-04T05:06:07.89Z',
    '2026-03-04T05:06:07.8901Z',
    '2026-03-04T05:06:07.890+01:00',
    '2026-03-04T05:06:07.890-00:00',
    '2026-03-04T05:06:07.890+0000',
    '2026-03-04T05:06:07.890',
    '2026-03-04T05:06:07.890z',
    '2026-03-04t05:06:07.890Z',
    '2026-03-04 05:06:07.890Z',
    ' 2026-03-04T05:06:07.890Z',
    '2026-03-04T05:06:07.890Z ',
    '2026-03-04T05:06:07.890Z\n',
    '2026-03-04T05:06Z',
    '2026-3-04T05:06:07Z',
    '+02026-03-04T05:06:07Z',
    '2025-02-29T00:00:00Z',
    '2100-02-29T00:00:00Z',
    '2026-04-31T00:00:00Z',
    '2026-13-01T00:00:00Z',
    '2026-00-10T00:00:00Z',
    '2026-01-00T00:00:00Z',
    '2026-01-01T24:00:00Z',
    '2026-01-01T23:60:00Z',
    '2026-01-01T23:59:60Z',
    '1767225600',
    '',
    1767225600,
    null,
  ])('rejects %j', startedAt => {
    expect(gateCode(startedAt)).toBe('exportNotReady');
  });
});

describe('FIT time', () => {
  const clock = (startedAt: string) => {
    const start: ExportStart = parseStartedAt(startedAt);
    return fitClock(start);
  };

  it('rounds once from the phone anchor across a day boundary', () => {
    const value = clock('2026-01-01T23:59:59.750Z');
    expect([value.base, value.f]).toEqual([unix('2026-01-01T23:59:59Z'), 0.75]);
    expect(value.time(0)).toBe(fit('2026-01-02T00:00:00Z'));
    expect(value.time(0.25)).toBe(fit('2026-01-02T00:00:00Z'));
    expect(value.time(0.7)).toBe(fit('2026-01-02T00:00:00Z'));
    expect(value.time(0.75)).toBe(fit('2026-01-02T00:00:01Z'));
    expect(value.time(86_400)).toBe(fit('2026-01-03T00:00:00Z'));
    expect(value.second(0)).toBe(1);
    expect(value.timestamp(value.second(0.75))).toBe(value.time(0.75));
  });

  it('anchors elapsed 0.4 at the start fraction 250 ms', () => {
    const value = clock('2026-01-01T00:00:00.250Z');
    expect(value.f).toBe(0.25);
    expect(value.second(0.4)).toBe(1);
    expect(value.time(0.4)).toBe(fit('2026-01-01T00:00:01Z'));
  });
});
