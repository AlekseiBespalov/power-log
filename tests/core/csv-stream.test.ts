import { describe, expect, it } from 'vitest';
import { TABLES } from '../../src/core/export/catalog';
import { createCsvParser } from '../../src/core/recordings';
import { MAX_CSV_BYTES, MAX_RECORDING_DURATION_SECONDS, MAX_RECORDING_SAMPLES } from '../../src/core/validation';
import { parseCsv } from '../support/csv';
import { syntheticCsvChunks, syntheticCsvHeader, syntheticCsvRow, syntheticCsvSample } from './csv-fixture';

const first = syntheticCsvSample();
const header = syntheticCsvHeader + '\n';
const row = syntheticCsvRow(first) + '\n';
const names: string[] = TABLES[0].fields.map(field => field.name);
const withCell = (name: string, value: string): string => {
  const cells = syntheticCsvRow(first).split(',');
  cells[names.indexOf(name)] = value;
  return header + cells.join(',') + '\n';
};

function parseChunks(text: string, size: number) {
  const parser = createCsvParser();
  parser.write('');
  for (let start = 0; start < text.length; start += size) parser.write(text.slice(start, start + size));
  return parser.finish();
}

describe('incremental CSV import', () => {
  it('reads the telemetry.csv header of the ride-data ZIP', () => {
    expect(syntheticCsvHeader).toBe(names.join(','));
    expect(parseCsv(header + row).samples).toEqual([first]);
  });

  it.each([false, true])('accepts every split point with reordered columns and quoted=%s', quoted => {
    const samples = [first, syntheticCsvSample(1)];
    const order = [...names.keys()].reverse();
    const cells = (line: string) => line.split(',');
    const text =
      '﻿' +
      [
        order.map(i => names[i]).join(','),
        ...samples.map(sample => {
          const values = cells(syntheticCsvRow(sample));
          return order.map(i => (quoted ? `"${values[i]}"` : values[i])).join(',');
        }),
      ].join('\r\n') +
      '\r\n';
    for (let split = 0; split <= text.length; split += 1) {
      const parser = createCsvParser();
      parser.write(text.slice(0, split));
      parser.write('');
      parser.write(text.slice(split));
      expect(parser.finish()).toEqual({ samples });
    }
    expect(parseChunks(text, 1).samples).toEqual(samples);
  });

  it.each([false, true])('accepts header-only and unterminated final rows with trailing newline %s', newline => {
    expect(parseChunks(header.trimEnd() + (newline ? '\r\n' : ''), 1).samples).toEqual([]);
    expect(parseChunks(header + row.trimEnd() + (newline ? '\r\n' : ''), 1).samples).toEqual([first]);
  });

  it.each([
    ['', 'Missing or duplicate CSV columns'],
    [header.replace('cadenceRpm', 'humanPowerW'), 'Missing or duplicate CSV columns'],
    [
      header.replace('cadenceRpm', 'other'),
      'Unrecognized CSV column set; open telemetry.csv from a Power Log ride-data ZIP',
    ],
    [header.replace(',cyc.speedRaw', ''), 'Unrecognized CSV column set'],
    [
      'timestamp,elapsedSeconds,sequence,humanPowerW,cadenceRpm,motorInputPowerW,batteryVoltageV,batteryCurrentA,' +
        'motorCurrentA,motorRpm,pedalTorqueNm,controllerTempC,motorTempC,consumedAh,consumedWh,throttleVoltageV,' +
        'faultCode,assistLevel,raceMode,speedRaw,controllerSpeedMps,controllerModel,firmwareLabel,' +
        'controllerProtocol,connectionEpoch,interruptionIndex\n',
      'Unrecognized CSV column set; open telemetry.csv from a Power Log ride-data ZIP',
    ],
    [header + '"2026', 'Unterminated CSV quoted field'],
    [header + '20"26', 'Malformed CSV quoting'],
    [header + '"2026"oops', 'Malformed CSV quoting'],
    [header + row + 'short\r\n', 'CSV row 3 has an unexpected column count'],
    [header + row + '\n', 'CSV row 3 has an unexpected column count'],
    [header + 'x'.repeat(257), 'CSV field or column count exceeds supported limits'],
    [header + ','.repeat(names.length + 1), 'CSV field or column count exceeds supported limits'],
    [withCell('timestamp', '2026-02-30T00:00:00Z'), 'Invalid calendar timestamp'],
    [withCell('timestamp', ''), 'Timestamp must be an ISO UTC timestamp ending in Z'],
    [withCell('elapsedSeconds', ''), 'Invalid CSV numeric field elapsedSeconds'],
    [withCell('elapsedSeconds', String(MAX_RECORDING_DURATION_SECONDS + 1)), 'outside the supported recording'],
    [withCell('cadenceRpm', 'NaN'), 'Invalid CSV numeric field cadenceRpm'],
    [withCell('cadenceRpm', '=1+1'), 'Invalid CSV numeric field cadenceRpm'],
    [withCell('cadenceRpm', '0x10'), 'Invalid CSV numeric field cadenceRpm'],
    [withCell('cadenceRpm', '1e999'), 'Non-finite CSV numeric field cadenceRpm'],
    [withCell('faultCode', '1.5'), 'Invalid CSV integer field faultCode'],
    [withCell('cyc.raceMode', '0.5'), 'Invalid CSV integer field cyc.raceMode'],
    [withCell('run', ''), 'CSV row 2 has no run'],
    [withCell('run', '0'), 'Invalid CSV run number'],
    [withCell('activeInterval', '1.5'), 'Invalid CSV activeInterval number'],
    [withCell('connection', '-1'), 'Invalid CSV connection number'],
    [withCell('connection', '2147483648'), 'Invalid CSV connection number'],
  ])('rejects malformed input %#', (text, error) => {
    expect(() => parseCsv(text)).toThrow(error);
    expect(() => parseChunks(text, 1)).toThrow(error);
    expect(() => parseChunks(text, 17)).toThrow(error);
  });

  it('imports values as exported, without controller-profile checks', () => {
    const odd = { ...first, motorInputPowerW: 1, controllerSpeedMps: 99, humanPowerW: 1.5, faultCode: 4096 };
    expect(parseCsv(header + syntheticCsvRow(odd) + '\n').samples).toEqual([odd]);
  });

  it.each(['\u0000', '\u007f', 'é', '﻿'])('checks unsupported characters in each chunk: %j', character => {
    const parser = createCsvParser();
    parser.write(header + row);
    expect(() => parser.write(character)).toThrow('CSV contains unsupported characters');
  });

  it('rejects a row earlier than the one before as soon as it arrives', () => {
    const parser = createCsvParser();
    parser.write(header + syntheticCsvRow({ ...first, elapsedSeconds: 1 }) + '\n');
    expect(() => parser.write(syntheticCsvRow(syntheticCsvSample(1)) + '\n')).toThrow(
      'CSV row 3 is earlier than the row before',
    );
  });

  it('rejects a row that goes back to an earlier run and accepts equal elapsed times', () => {
    const later = { ...syntheticCsvSample(1), elapsedSeconds: 0, run: 2, interruptionIndex: 2 };
    expect(parseCsv(header + row + syntheticCsvRow(later) + '\n').samples).toEqual([first, later]);
    expect(() => parseCsv(header + syntheticCsvRow(later) + '\n' + row)).toThrow(
      'CSV row 3 goes back to an earlier run',
    );
  });

  it('counts logical rows when quoted fields contain newlines', () => {
    const text = withCell('cadenceRpm', '"\r\n80.5\r\n"');
    expect(parseChunks(text, 1).samples).toEqual([first]);
    expect(() => parseChunks(text + 'short\r\n', 1)).toThrow('CSV row 3 has an unexpected column count');
  });

  it('accepts 700,000 rows and rejects the next row with the CSV limit error', () => {
    expect(MAX_RECORDING_SAMPLES).toBe(700_000);
    const parser = createCsvParser();
    for (const chunk of syntheticCsvChunks(MAX_RECORDING_SAMPLES)) parser.write(chunk);
    expect(() => parser.write(syntheticCsvRow(syntheticCsvSample(MAX_RECORDING_SAMPLES)) + '\n')).toThrow(
      'CSV exceeds the sample count limit',
    );
  }, 60_000);

  it('enforces the cumulative byte limit across chunks', () => {
    expect(MAX_CSV_BYTES).toBe(256 * 1024 * 1024);
    const parser = createCsvParser();
    parser.write(header);
    expect(() => parser.write(' '.repeat(MAX_CSV_BYTES - header.length + 1))).toThrow(
      'CSV exceeds the 256 MiB import limit',
    );
  });
});
