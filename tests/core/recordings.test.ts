import { describe, expect, it } from 'vitest';
import { MAX_CSV_BYTES, SAMPLE_COLUMNS, summarizeRecording, validateSample, validateTimestamp } from '../../src/core';
import { sample } from './helpers';
import { exportCsv } from '../helpers/export-csv';
import { parseCsv } from '../support/csv';

describe('validated recording imports and exports', () => {
  it('preserves hard interruption counts and rejects absent, invalid or decreasing counts', () => {
    const samples = [sample(0), sample(1, { interruptionIndex: 1 }), sample(2, { interruptionIndex: 2 })];
    expect(parseCsv(exportCsv(samples)).samples).toEqual(samples);
    expect(SAMPLE_COLUMNS).toHaveLength(26);
    expect(SAMPLE_COLUMNS.slice(-2)).toEqual(['connectionEpoch', 'interruptionIndex']);
    for (const interruptionIndex of [undefined, -1, 0.5, Infinity, NaN, '1', Number.MAX_SAFE_INTEGER + 1])
      expect(() => validateSample({ ...sample(), interruptionIndex })).toThrow('Interruption index');
    expect(() => parseCsv(exportCsv([samples[1]!, sample(2)]))).toThrow('Interruption index must not decrease');
    expect(() => parseCsv(exportCsv(samples).replace(/,interruptionIndex/, ''))).toThrow('26-column');
  });
  it('round-trips the full column set with absent optional values', () => {
    const samples = [sample(0), sample(0.5), sample(1)].map(value => {
      const { connectionEpoch: _epoch, ...sample } = value;
      return sample;
    });
    const text = exportCsv(samples);
    expect(text.split('\n')[0]).toBe(SAMPLE_COLUMNS.join(','));
    expect(parseCsv(text)).toEqual({ samples });
  });
  it('supports quoted fields, BOM, CRLF and reordered known columns', () => {
    const first = sample();
    const columns = [...SAMPLE_COLUMNS].reverse();
    const text =
      '\uFEFF' +
      columns.map(key => `"${key}"`).join(',') +
      '\r\n' +
      columns.map(key => `"${first[key] ?? ''}"`).join(',') +
      '\r\n';
    expect(parseCsv(text).samples).toEqual([first]);
  });
  it('accepts valid header-only recordings from stop before the first sample', () => {
    expect(parseCsv(exportCsv([])).samples).toEqual([]);
    expect(summarizeRecording([])).toMatchObject({
      sampleCount: 0,
      averageHumanPowerW: null,
      peakHumanPowerW: null,
      humanEnergyWh: 0,
    });
  });
  it('rejects missing, duplicate, extra and unknown columns', () => {
    const good = exportCsv([sample()]);
    expect(() => parseCsv(good.replace('cadenceRpm,', ''))).toThrow('column');
    expect(() => parseCsv(good.replace('cadenceRpm', 'humanPowerW'))).toThrow('duplicate');
    expect(() => parseCsv(good.replace('interruptionIndex\n', 'interruptionIndex,location\n'))).toThrow('column');
    expect(() => parseCsv('')).toThrow();
    expect(() => parseCsv(good.replace('\n2026', ',extra\n2026'))).toThrow('column');
  });
  it.each(['NaN', 'Infinity', '1e999', '=1+1', '', '0x10'])(
    'rejects nonnumeric and nonfinite CSV values: %s',
    value => {
      const first = sample();
      const values = SAMPLE_COLUMNS.map(key => (key === 'cadenceRpm' ? value : String(first[key])));
      expect(() => parseCsv(SAMPLE_COLUMNS.join(',') + '\n' + values.join(','))).toThrow();
    },
  );
  it('rejects malformed quoting, row lengths, oversized input and control characters', () => {
    const good = exportCsv([sample()]);
    expect(() => parseCsv(good.replace('2026', '"2026'))).toThrow('quoted');
    expect(() => parseCsv(good.replace('2026', '""oops2026'))).toThrow('quoting');
    expect(() => parseCsv(good.trimEnd() + ',5\n')).toThrow('count');
    expect(() => parseCsv(' '.repeat(MAX_CSV_BYTES + 1))).toThrow('256 MiB');
    expect(() => parseCsv(good + '\u0000')).toThrow('characters');
    expect(() => parseCsv(good + '\n')).toThrow('column count');
  });
  it('validates UTC calendar dates, sequence ordering and elapsed ordering', () => {
    expect(() => validateTimestamp('2026-02-30T00:00:00Z')).toThrow('calendar');
    expect(() => validateTimestamp('2026-01-01T24:00:00Z')).toThrow();
    expect(() => validateTimestamp('2026-01-01T00:00:00+02:00')).toThrow('UTC');
    expect(validateTimestamp('2026-01-01T00:00:00.123456789Z')).toBe('2026-01-01T00:00:00.123456789Z');
    expect(() => parseCsv(exportCsv([sample(), sample(0.5, { sequence: 0 })]))).toThrow('increase');
    expect(() => parseCsv(exportCsv([sample(1), sample(0.5, { sequence: 3 })]))).toThrow('increase');
    expect(() => validateSample({ ...sample(), cadenceRpm: undefined })).toThrow('numeric');
    expect(() => validateSample(sample(0, { motorInputPowerW: 50 }))).toThrow('voltage');
  });
  it('preserves an actual wall-clock correction but still orders samples monotonically', () => {
    const samples = [sample(0), sample(0.5, { timestamp: '2025-12-31T23:59:00Z' })];
    expect(parseCsv(exportCsv(samples)).samples).toEqual(samples);
    expect(summarizeRecording(samples)).toMatchObject({ coveredSeconds: 0.5, clockDiscontinuities: 1 });
  });
});

describe('ride summaries', () => {
  it.each([{ connectionEpoch: 'next' }, { interruptionIndex: 1 }, { connectionEpoch: undefined }])(
    'excludes cross-run energy and coverage while keeping the observed duration: %j',
    boundary => {
      const samples = [sample(0), sample(1, boundary)];
      expect(summarizeRecording(samples)).toMatchObject({
        durationSeconds: 1,
        coveredSeconds: 0,
        humanEnergyWh: 0,
        motorInputEnergyWh: 0,
        averageHumanPowerW: null,
        gapCount: 1,
      });
    },
  );
  it('accepts a shared boundary time only where a hard interruption begins', () => {
    const first = validateSample(sample(1));
    expect(
      validateSample({ ...sample(1), sequence: first.sequence + 1, interruptionIndex: 1 }, first).elapsedSeconds,
    ).toBe(1);
    expect(() => validateSample({ ...sample(1), sequence: first.sequence + 1 }, first)).toThrow('must increase');
  });
  it('integrates imported samples that carry no connection epoch', () => {
    const samples = [sample(0, { connectionEpoch: undefined }), sample(1, { connectionEpoch: undefined })];
    expect(summarizeRecording(samples)).toMatchObject({ coveredSeconds: 1, gapCount: 0 });
  });
  it('uses time-weighted rider energy and battery input separately', () => {
    const samples = [
      sample(0, { humanPowerW: 100, cadenceRpm: 60, batteryVoltageV: 50, batteryCurrentA: 2, motorInputPowerW: 100 }),
      sample(1, { humanPowerW: 200, cadenceRpm: 80, batteryVoltageV: 50, batteryCurrentA: 4, motorInputPowerW: 200 }),
      sample(3, { humanPowerW: 400, cadenceRpm: 100, batteryVoltageV: 50, batteryCurrentA: 6, motorInputPowerW: 300 }),
    ];
    const summary = summarizeRecording(samples);
    expect(summary.averageHumanPowerW).toBe(250);
    expect(summary.humanEnergyWh).toBeCloseTo(750 / 3600);
    expect(summary.motorInputEnergyWh).toBeCloseTo(650 / 3600);
    expect(summary.averageCadenceRpm).toBeCloseTo(250 / 3);
    expect(summary.peakHumanPowerW).toBe(400);
  });
  it('reports a reconnect outage without adding synthetic zero power or outage energy', () => {
    const samples = [
      sample(0, { humanPowerW: 100 }),
      sample(1, { humanPowerW: 100 }),
      sample(10, { humanPowerW: 100 }),
      sample(11, { humanPowerW: 100 }),
    ];
    const summary = summarizeRecording(samples);
    expect(summary).toMatchObject({ durationSeconds: 11, coveredSeconds: 2, gapCount: 1, averageHumanPowerW: 100 });
    expect(summary.humanEnergyWh).toBeCloseTo(200 / 3600);
  });
  it('does not infer energy or average from a single point', () => {
    expect(summarizeRecording([sample(0, { humanPowerW: 100 })])).toMatchObject({
      averageHumanPowerW: null,
      peakHumanPowerW: 100,
      humanEnergyWh: 0,
      coveredSeconds: 0,
    });
  });
});
