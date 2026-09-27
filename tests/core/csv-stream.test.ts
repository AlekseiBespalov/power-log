import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { createCsvParser, csvRow } from '../../src/core/recordings';
import { parseCsv } from '../support/csv';
import { REQUIRED_SAMPLE_COLUMNS, SAMPLE_COLUMNS } from '../../src/core/types';
import { MAX_CSV_BYTES, MAX_RECORDING_DURATION_SECONDS, MAX_RECORDING_SAMPLES } from '../../src/core/validation';
import { syntheticCsvChunks, syntheticCsvSample } from './csv-fixture';

const first = syntheticCsvSample();
const header = SAMPLE_COLUMNS.join(',') + '\n';
const row = csvRow(first) + '\n';
const nativeCsv = (patch: Record<string, unknown>): string => {
  const value = { ...first, ...patch };
  return header + SAMPLE_COLUMNS.map(key => value[key] ?? '').join(',') + '\n';
};

function parseChunks(text: string, size: number) {
  const parser = createCsvParser();
  parser.write('');
  for (let start = 0; start < text.length; start += size) parser.write(text.slice(start, start + size));
  return parser.finish();
}

describe('incremental CSV import', () => {
  it('reads the exact header the iPhone exporter writes', () => {
    const swift = readFileSync('modules/cyc-bridge/ios/CycProtocol.swift', 'utf8').match(
      /static let columns = \[([^\]]+)\]/,
    )![1]!;
    expect([...swift.matchAll(/"([^"]+)"/g)].map(match => match[1])).toEqual([...SAMPLE_COLUMNS]);
  });
  it.each([false, true])('accepts every split point in the 26-column set with quoted=%s', quoted => {
    const columns = SAMPLE_COLUMNS;
    const samples = [first, syntheticCsvSample(1)];
    const keys = [...columns].reverse();
    const text =
      '\uFEFF' +
      [
        keys.join(','),
        ...samples.map(sample => keys.map(key => (quoted ? `"${sample[key]}"` : String(sample[key]))).join(',')),
      ].join('\r\n') +
      '\r\n';
    const expected = samples.map(sample => Object.fromEntries(columns.map(key => [key, sample[key]])));
    for (let split = 0; split <= text.length; split += 1) {
      const parser = createCsvParser();
      parser.write(text.slice(0, split));
      parser.write('');
      parser.write(text.slice(split));
      expect(parser.finish()).toEqual({ samples: expected });
    }
    expect(parseChunks(text, 1).samples).toEqual(expected);
  });

  it.each([false, true])('accepts header-only and unterminated final rows with trailing newline %s', newline => {
    expect(parseChunks(header.trimEnd() + (newline ? '\r\n' : ''), 1).samples).toEqual([]);
    expect(parseChunks(header + row.trimEnd() + (newline ? '\r\n' : ''), 1).samples).toEqual([first]);
  });

  it.each(
    [SAMPLE_COLUMNS.filter(key => key !== 'connectionEpoch'), SAMPLE_COLUMNS.slice(0, -1), REQUIRED_SAMPLE_COLUMNS].map(
      columns => ({ columns }),
    ),
  )('rejects incomplete column sets %#', ({ columns }) => {
    expect(() => parseChunks(columns.join(',') + '\n', 1)).toThrow('Unrecognized CSV column set');
  });

  it.each([
    ['', 'Missing or duplicate CSV columns'],
    [header.replace('sequence', 'timestamp'), 'Missing or duplicate CSV columns'],
    [header.replace('sequence', 'other'), 'Unrecognized CSV column set; use the 26-column Power Log export'],
    [
      'utc,elapsed_s,sample,response_command,field_mask,human_power_w\n',
      'Unrecognized CSV column set; use the 26-column Power Log export',
    ],
    [header + '"2026', 'Unterminated CSV quoted field'],
    [header + '20"26', 'Malformed CSV quoting'],
    [header + '"2026"oops', 'Malformed CSV quoting'],
    [header + '"""2026",' + row.split(',').slice(1).join(','), 'Timestamp must be an ISO UTC timestamp ending in Z'],
    [header + row + 'short\r\n', 'CSV row 3 has an unexpected column count'],
    [header + row + '\n', 'CSV row 3 has an unexpected column count'],
    [header + 'x'.repeat(257), 'CSV field or column count exceeds supported limits'],
    [header + ','.repeat(SAMPLE_COLUMNS.length + 1), 'CSV field or column count exceeds supported limits'],
    [nativeCsv({ cadenceRpm: '' }), 'Invalid CSV numeric field cadenceRpm'],
    [nativeCsv({ cadenceRpm: '1e999' }), 'Non-finite CSV numeric field cadenceRpm'],
    [nativeCsv({ timestamp: '2026-02-30T00:00:00Z' }), 'Invalid calendar timestamp'],
    [nativeCsv({ motorInputPowerW: 0 }), 'Motor input power must equal battery voltage multiplied by battery current'],
    [nativeCsv({ faultCode: 256 }), 'Invalid byte field faultCode'],
    [nativeCsv({ humanPowerW: 1.5 }), 'Invalid integer field humanPowerW'],
    [
      nativeCsv({ elapsedSeconds: MAX_RECORDING_DURATION_SECONDS + 1 }),
      'Elapsed time is outside the supported recording duration',
    ],
    [nativeCsv({ sequence: -1 }), 'Sequence must be a nonnegative safe integer'],
    [nativeCsv({ controllerSpeedMps: 36 }), 'Controller speed does not match its unit provenance'],
    [nativeCsv({ firmwareLabel: '' }), 'Invalid controller provenance'],
    [nativeCsv({ connectionEpoch: 'a/b' }), 'Invalid connection epoch'],
    [nativeCsv({ interruptionIndex: '' }), 'Invalid CSV numeric field interruptionIndex'],
    [nativeCsv({ interruptionIndex: -1 }), 'Interruption index must be a nonnegative safe integer'],
    [nativeCsv({ interruptionIndex: 0.5 }), 'Interruption index must be a nonnegative safe integer'],
  ])('retains the error for malformed input %#', (text, error) => {
    expect(() => parseCsv(text)).toThrow(error);
    expect(() => parseChunks(text, 1)).toThrow(error);
    expect(() => parseChunks(text, 17)).toThrow(error);
  });

  it.each(['\u0000', '\u007f', 'é', '\uFEFF'])('checks unsupported characters in each chunk: %j', character => {
    const parser = createCsvParser();
    parser.write(header + row);
    expect(() => parser.write(character)).toThrow('CSV contains unsupported characters');
  });

  it.each([{ elapsedSeconds: 0 }, { sequence: 0 }])(
    'rejects recording ordering as soon as the next row arrives: %j',
    patch => {
      const parser = createCsvParser();
      parser.write(header + row);
      const next = { ...syntheticCsvSample(1), ...patch };
      expect(() => parser.write(csvRow(next) + '\n')).toThrow(
        'Sample elapsed times and sequence numbers must increase',
      );
    },
  );

  it('preserves clock corrections and changing or repeated identity values', () => {
    const samples = [
      first,
      {
        ...syntheticCsvSample(1),
        timestamp: '2025-12-31T23:59:59Z',
        controllerModel: 'X6',
        connectionEpoch: 'synthetic-connection-2',
      },
      syntheticCsvSample(2),
    ];
    expect(parseChunks(header + samples.map(csvRow).join('\n'), 19).samples).toEqual(samples);
  });

  it('counts logical rows when quoted numeric fields contain newlines', () => {
    const text = nativeCsv({ cadenceRpm: '"\r\n80.5\r\n"' });
    expect(parseChunks(text, 1).samples).toEqual([first]);
    expect(() => parseChunks(text + 'short\r\n', 1)).toThrow('CSV row 3 has an unexpected column count');
  });

  it('accepts more than 200,000 rows without retaining a complete CSV', () => {
    const parser = createCsvParser();
    for (const chunk of syntheticCsvChunks(200_001)) parser.write(chunk);
    const recording = parser.finish();
    expect(recording.samples).toHaveLength(200_001);
    expect(recording.samples[200_000]).toEqual(syntheticCsvSample(200_000));
  }, 30_000);

  it('accepts 700,000 rows and rejects the next sample with the CSV limit error', () => {
    expect(MAX_RECORDING_SAMPLES).toBeGreaterThanOrEqual(700_000);
    const parser = createCsvParser();
    for (const chunk of syntheticCsvChunks(MAX_RECORDING_SAMPLES)) parser.write(chunk);
    expect(() => parser.write(csvRow(syntheticCsvSample(MAX_RECORDING_SAMPLES)) + '\n')).toThrow(
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
