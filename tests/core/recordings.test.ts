import { describe, expect, it } from 'vitest';
import { MAX_CSV_BYTES, parseCsv, PYTHON_CSV_COLUMNS, SAMPLE_COLUMNS, SELECTED_MASK, summarizeRecording, TELEMETRY_FIELDS, validateRecording, validateSample, validateTimestamp } from '../../src/core';
import { sample } from './helpers';
import { exportCsv } from '../helpers/export-csv';

const device = { source: 'device' } as const;

describe('validated recording imports and exports', () => {
  it('round-trips native columns with explicit source and no fake missing fields', () => {
    const samples = [sample(0), sample(0.5), sample(1)];
    const text = exportCsv(samples);
    expect(text.split('\n')[0]).toBe(SAMPLE_COLUMNS.join(','));
    expect(parseCsv(text, device)).toEqual({ samples, source: 'device', format: 'power-log' });
    // @ts-expect-error Caller must provide provenance even for legacy CSV files.
    expect(() => parseCsv(text, {})).toThrow('source');
  });
  it('supports quoted fields, BOM, CRLF and reordered known columns', () => {
    const first = sample(); const columns = [...SAMPLE_COLUMNS].reverse();
    const text = '\uFEFF' + columns.map(key => `"${key}"`).join(',') + '\r\n' + columns.map(key => `"${first[key] ?? ''}"`).join(',') + '\r\n';
    expect(parseCsv(text, device).samples).toEqual([first]);
  });
  it('accepts valid header-only recordings from stop before the first sample', () => {
    expect(parseCsv(exportCsv([]), device).samples).toEqual([]);
    expect(summarizeRecording([])).toMatchObject({ sampleCount: 0, averageHumanPowerW: null, peakHumanPowerW: null, humanEnergyWh: 0 });
  });
  it('rejects missing, duplicate, extra and unknown columns', () => {
    const good = exportCsv([sample()]);
    expect(() => parseCsv(good.replace('cadenceRpm,', ''), device)).toThrow('column');
    expect(() => parseCsv(good.replace('cadenceRpm', 'humanPowerW'), device)).toThrow('duplicate');
    expect(() => parseCsv(good.replace('connectionEpoch\n', 'connectionEpoch,location\n'), device)).toThrow('column');
    expect(() => parseCsv('', device)).toThrow();
    expect(() => parseCsv(good.replace('\n2026', ',extra\n2026'), device)).toThrow('column');
  });
  it.each(['NaN', 'Infinity', '1e999', '=1+1', '', '0x10'])('rejects nonnumeric and nonfinite CSV values: %s', value => {
    const first = sample(); const values = SAMPLE_COLUMNS.map(key => key === 'cadenceRpm' ? value : String(first[key]));
    expect(() => parseCsv(SAMPLE_COLUMNS.join(',') + '\n' + values.join(','), device)).toThrow();
  });
  it('rejects malformed quoting, row lengths, oversized input and control characters', () => {
    const good = exportCsv([sample()]);
    expect(() => parseCsv(good.replace('2026', '"2026'), device)).toThrow('quoted');
    expect(() => parseCsv(good.replace('2026', '""oops2026'), device)).toThrow('quoting');
    expect(() => parseCsv(good.trimEnd() + ',5\n', device)).toThrow('count');
    expect(() => parseCsv(' '.repeat(MAX_CSV_BYTES + 1), device)).toThrow('32 MiB');
    expect(() => parseCsv(good + '\u0000', device)).toThrow('characters');
    expect(() => parseCsv(good + '\n', device)).toThrow('column count');
  });
  it('validates UTC calendar dates, sequence ordering and elapsed ordering', () => {
    expect(() => validateTimestamp('2026-02-30T00:00:00Z')).toThrow('calendar');
    expect(() => validateTimestamp('2026-01-01T24:00:00Z')).toThrow();
    expect(() => validateTimestamp('2026-01-01T00:00:00+02:00')).toThrow('UTC');
    expect(validateTimestamp('2026-01-01T00:00:00.123456789Z')).toBe('2026-01-01T00:00:00.123456789Z');
    expect(() => exportCsv([sample(), sample(0.5, { sequence: 0 })])).toThrow('increase');
    expect(() => exportCsv([sample(1), sample(0.5, { sequence: 3 })])).toThrow('increase');
    expect(() => validateSample({ ...sample(), cadenceRpm: undefined })).toThrow('numeric');
    expect(() => validateSample(sample(0, { motorInputPowerW: 50 }))).toThrow('voltage');
  });
  it('preserves an actual wall-clock correction but still orders samples monotonically', () => {
    const samples = [sample(0), sample(0.5, { timestamp: '2025-12-31T23:59:00Z' })];
    expect(parseCsv(exportCsv(samples), device).samples).toEqual(samples);
    expect(summarizeRecording(samples)).toMatchObject({ coveredSeconds: 0.5, clockDiscontinuities: 1 });
    expect(validateRecording({ id: 'ride-1', source: 'device', samples: 2, uri: 'file:///private/ride-1.csv', startedAt: samples[0]!.timestamp, endedAt: samples[1]!.timestamp }).id).toBe('ride-1');
  });
  it('imports the real Python schema, scales nothing twice and normalizes its UTC offset', () => {
    const first = sample(); const fields: Record<string, string> = {};
    fields.utc = '2026-01-01T00:00:00.000+00:00'; fields.elapsed_s = '0'; fields.sample = '0';
    fields.response_command = '50'; fields.field_mask = `0x${SELECTED_MASK.toString(16)}`; fields.battery_input_power_w = String(first.motorInputPowerW);
    let fieldIndex = 5;
    TELEMETRY_FIELDS.forEach((group, bit) => {
      for (const [name] of group) {
        fields[PYTHON_CSV_COLUMNS[fieldIndex++]!] = (SELECTED_MASK & (1 << bit)) ? String(first[name as keyof typeof first]) : '';
      }
    });
    const legacy = PYTHON_CSV_COLUMNS.join(',') + '\n' + PYTHON_CSV_COLUMNS.map(key => fields[key]).join(',') + '\n';
    expect(parseCsv(legacy, device)).toEqual({ samples: [first], source: 'device', format: 'cyc-python' });
    expect(() => parseCsv(legacy.replace('0x3c0fb8f', '0x80000000'), device)).toThrow('profile');
  });
  it('refuses path traversal and malformed recording metadata', () => {
    const good = { id: 'ride-1', uri: 'file:///private/ride-1.csv', startedAt: sample().timestamp, samples: 0, source: 'device' };
    expect(validateRecording(good).samples).toBe(0);
    for (const id of ['../secret', '/private/secret', 'ride..csv', 'a/b', '']) expect(() => validateRecording({ ...good, id })).toThrow('identifier');
    expect(() => validateRecording({ ...good, samples: -1 })).toThrow('count');
    expect(() => validateRecording({ ...good, source: 'replay' })).toThrow('source');
  });
});

describe('ride summaries', () => {
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
    const samples = [sample(0, { humanPowerW: 100 }), sample(1, { humanPowerW: 100 }), sample(10, { humanPowerW: 100 }), sample(11, { humanPowerW: 100 })];
    const summary = summarizeRecording(samples);
    expect(summary).toMatchObject({ durationSeconds: 11, coveredSeconds: 2, gapCount: 1, averageHumanPowerW: 100 });
    expect(summary.humanEnergyWh).toBeCloseTo(200 / 3600);
  });
  it('does not infer energy or average from a single point', () => {
    expect(summarizeRecording([sample(0, { humanPowerW: 100 })])).toMatchObject({ averageHumanPowerW: null, peakHumanPowerW: 100, humanEnergyWh: 0, coveredSeconds: 0 });
  });
});
