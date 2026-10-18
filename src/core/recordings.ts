import {
  AnalyticsItem,
  AnalyticsMetadata,
  AnalyticsPin,
  AnalyticsPreparation,
  AnalyticsComposition,
  AnalyticsSource,
  AnalyticsMetricReducer,
  analyticsTimeUs,
} from '../../shared/js';
import { appendStatistic } from './analytics';
import { TABLES } from './export/catalog';
import { createSlicer } from './export/pages';
import { TELEMETRY_LAYOUT } from './export/tables/telemetry';
import { TextChunks, csvHeader, layoutColumns, writeRows, type ByteOutput, type Texts } from './export/tables/text';
import { MAX_SAMPLE_GAP_SECONDS, type TelemetryMeasurements, type TelemetrySample } from './types';
import type { WorkoutSummary } from './workouts';
import { MAX_CSV_BYTES, MAX_RECORDING_DURATION_SECONDS, MAX_RECORDING_SAMPLES, validateTimestamp } from './validation';

type ChannelKey = keyof TelemetryMeasurements | 'controllerSpeedMps';

/** A telemetry.csv row; `interruptionIndex` holds the row's run, and missing channels are NaN. */
export interface ImportedSample extends TelemetrySample {
  activeInterval: number | null;
  run: number;
  connection: number | null;
  controllerSpeedMps: number;
}

export interface ParsedRecording {
  samples: ImportedSample[];
}

const FIELDS = TABLES[0].fields;
const FIELD_NAMES: readonly string[] = FIELDS.map(field => field.name);
const ENVELOPE = new Set(['timestamp', 'elapsedSeconds', 'activeInterval', 'run', 'connection']);
const CHANNELS = FIELDS.filter(field => !ENVELOPE.has(field.name)).map(field => ({
  name: field.name,
  key: field.reads[0] as ChannelKey,
  integer: field.type === 'integer',
}));
const CSV_SAMPLE_SHAPE = Object.fromEntries(CHANNELS.map(channel => [channel.key, 0])) as Pick<
  ImportedSample,
  ChannelKey
>;
const FIELD = Object.fromEntries(FIELD_NAMES.map((name, index) => [name, index])) as Record<string, number>;
const BATCH_ROWS = 4096;
const HEADER_COLUMNS = 64;

export function createCsvParser() {
  const samples: ImportedSample[] = [];
  let positions: number[] | undefined;
  let bytes = 0;
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  let closedQuote = false;
  let skipLF = false;
  let finished = false;
  const finishCell = (): void => {
    row.push(cell);
    cell = '';
    closedQuote = false;
  };
  const finishRow = (): void => {
    finishCell();
    if (!positions) {
      if (new Set(row).size !== row.length) throw new Error('Missing or duplicate CSV columns');
      if (row.length !== FIELD_NAMES.length || !FIELD_NAMES.every(name => row.includes(name)))
        throw new Error('Unrecognized CSV column set; open telemetry.csv from a Power Log ride-data ZIP');
      positions = FIELD_NAMES.map(name => row.indexOf(name));
    } else {
      if (samples.length >= MAX_RECORDING_SAMPLES) throw new Error('CSV exceeds the sample count limit');
      if (row.length !== positions.length)
        throw new Error(`CSV row ${samples.length + 2} has an unexpected column count`);
      samples.push(readSample(row, positions, samples[samples.length - 1], samples.length));
    }
    row = [];
  };
  return {
    write(chunk: string): void {
      if (finished) throw new Error('CSV parser is already finished');
      const bom = bytes === 0 && chunk.startsWith('﻿');
      bytes += chunk.length + (bom ? 2 : 0);
      if (bytes > MAX_CSV_BYTES) throw new Error('CSV exceeds the 256 MiB import limit');
      if (bom) chunk = chunk.slice(1);
      for (let offset = 0; offset < chunk.length; offset += 1024 * 1024) {
        if (/[^\x09\x0a\x0d\x20-\x7e]/.test(chunk.slice(offset, offset + 1024 * 1024)))
          throw new Error('CSV contains unsupported characters');
      }
      for (let index = 0; index < chunk.length; index += 1) {
        const character = chunk[index]!;
        if (skipLF) {
          skipLF = false;
          if (character === '\n') continue;
        }
        if (quoted) {
          if (character === '"') {
            quoted = false;
            closedQuote = true;
          } else cell += character;
        } else if (closedQuote && character === '"') {
          cell += '"';
          quoted = true;
          closedQuote = false;
        } else if (character === ',') finishCell();
        else if (character === '\n' || character === '\r') {
          finishRow();
          skipLF = character === '\r';
        } else if (character === '"' && cell === '' && !closedQuote) quoted = true;
        else {
          if (closedQuote || character === '"') throw new Error('Malformed CSV quoting');
          cell += character;
        }
        if (cell.length > 256 || row.length > (positions ? FIELD_NAMES.length : HEADER_COLUMNS))
          throw new Error('CSV field or column count exceeds supported limits');
      }
    },
    finish(): ParsedRecording {
      if (finished) throw new Error('CSV parser is already finished');
      if (quoted) throw new Error('Unterminated CSV quoted field');
      if (cell !== '' || row.length > 0 || closedQuote) finishRow();
      if (!positions) throw new Error('Missing or duplicate CSV columns');
      finished = true;
      return { samples };
    },
  };
}

function readSample(
  row: readonly string[],
  positions: readonly number[],
  previous: ImportedSample | undefined,
  index: number,
): ImportedSample {
  const cell = (name: string) => row[positions[FIELD[name]!]!]!;
  const where = `CSV row ${index + 2}`;
  const timestamp = validateTimestamp(cell('timestamp'));
  const elapsedSeconds = parseNumber(cell('elapsedSeconds'), 'elapsedSeconds');
  if (elapsedSeconds < 0 || elapsedSeconds > MAX_RECORDING_DURATION_SECONDS)
    throw new Error('Elapsed time is outside the supported recording duration');
  const run = parseCount(cell('run'), 'run');
  if (run === null) throw new Error(`${where} has no run`);
  if (previous && elapsedSeconds < previous.elapsedSeconds) throw new Error(`${where} is earlier than the row before`);
  if (previous && run < previous.run) throw new Error(`${where} goes back to an earlier run`);
  const sample: ImportedSample = {
    ...CSV_SAMPLE_SHAPE,
    timestamp,
    elapsedSeconds,
    sequence: index,
    interruptionIndex: run,
    activeInterval: parseCount(cell('activeInterval'), 'activeInterval'),
    run,
    connection: parseCount(cell('connection'), 'connection'),
  };
  for (const channel of CHANNELS) {
    const text = cell(channel.name);
    const value = text === '' ? NaN : parseNumber(text, channel.name);
    if (channel.integer && text !== '' && !Number.isSafeInteger(value))
      throw new Error(`Invalid CSV integer field ${channel.name}`);
    sample[channel.key] = value;
  }
  return sample;
}

function parseNumber(value: string, name: string): number {
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim()))
    throw new Error(`Invalid CSV numeric field ${name}`);
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`Non-finite CSV numeric field ${name}`);
  return parsed;
}

function parseCount(value: string, name: string): number | null {
  if (value === '') return null;
  const parsed = /^[1-9]\d{0,9}$/.test(value) ? Number(value) : NaN;
  if (!(parsed <= 2_147_483_647)) throw new Error(`Invalid CSV ${name} number`);
  return parsed;
}

export async function writeRecordingCsv(samples: readonly ImportedSample[], output: ByteOutput): Promise<void> {
  const out = new TextChunks(output);
  out.line(csvHeader(TELEMETRY_LAYOUT.table));
  const slicer = createSlicer();
  const timestamps: (string | null)[] = new Array<string | null>(BATCH_ROWS).fill(null);
  const elapsed = new Float64Array(BATCH_ROWS);
  const activeInterval = new Int32Array(BATCH_ROWS);
  const run = new Int32Array(BATCH_ROWS);
  const connection = new Int32Array(BATCH_ROWS);
  const channels = CHANNELS.map(() => new Float64Array(BATCH_ROWS));
  const stored: Record<string, Float64Array | Texts> = { timestamp: timestamps, elapsedSeconds: elapsed };
  CHANNELS.forEach((channel, c) => (stored[channel.key] = channels[c]!));
  const columns = layoutColumns(TELEMETRY_LAYOUT, stored, { activeInterval, run, connection });
  for (let start = 0; start < samples.length; start += BATCH_ROWS) {
    const rows = Math.min(BATCH_ROWS, samples.length - start);
    for (let i = 0; i < rows; i++) {
      const sample = samples[start + i]!;
      timestamps[i] = sample.timestamp;
      elapsed[i] = sample.elapsedSeconds;
      activeInterval[i] = sample.activeInterval ?? 0;
      run[i] = sample.run;
      connection[i] = sample.connection ?? 0;
      for (let c = 0; c < CHANNELS.length; c++) channels[c]![i] = sample[CHANNELS[c]!.key];
    }
    await writeRows(out, slicer, TELEMETRY_LAYOUT, columns, rows);
  }
  await out.end();
}

export interface RecordingSummary {
  sampleCount: number;
  durationSeconds: number;
  coveredSeconds: number;
  gapCount: number;
  clockDiscontinuities: number;
  averageHumanPowerW: number | null;
  peakHumanPowerW: number | null;
  averageCadenceRpm: number | null;
  peakCadenceRpm: number | null;
  humanEnergyWh: number | null;
  motorInputEnergyWh: number | null;
}

function* importedAnalysis(
  samples: readonly ImportedSample[],
): Generator<{ item: AnalyticsItem; sample?: ImportedSample }> {
  let lifecycleSequence = 0;
  let previousInterval: number | null = null;
  const event = (sample: ImportedSample, action: string) =>
    new AnalyticsItem(
      analyticsTimeUs(sample.elapsedSeconds),
      sample.elapsedSeconds,
      `import:lifecycle:${++lifecycleSequence}`,
      `import:lifecycle:${lifecycleSequence}`,
      'browser',
      String(lifecycleSequence),
      'lifecycle',
      'phone',
      sample.timestamp,
      [],
      [],
      [],
      null,
      null,
      null,
      null,
      0,
      action,
      false,
      false,
      null,
      true,
    );
  for (const [index, sample] of samples.entries()) {
    if (index === 0 && sample.activeInterval === null) yield { item: event(sample, 'pause') };
    if (sample.activeInterval !== previousInterval) {
      if (previousInterval !== null) yield { item: event(sample, 'pause') };
      if (sample.activeInterval !== null) yield { item: event(sample, 'resume') };
      previousInterval = sample.activeInterval;
    }
    const keys = ['humanPowerW', 'cadenceRpm', 'motorInputPowerW'] as const;
    const values = keys.filter(key => sample.activeInterval !== null && Number.isFinite(sample[key]));
    yield {
      sample,
      item: new AnalyticsItem(
        analyticsTimeUs(sample.elapsedSeconds),
        sample.elapsedSeconds,
        `import:${index}`,
        `import:${index}`,
        'controller',
        String(index + 1),
        'telemetry',
        'controller',
        sample.timestamp,
        values,
        values.map(() => 'real64'),
        values.map(key => (Object.is(sample[key], -0) ? '-0.0' : String(sample[key]))),
        sample.elapsedSeconds,
        null,
        null,
        JSON.stringify([sample.connection, sample.activeInterval]),
        sample.run,
        null,
        false,
        false,
        String(index + 1),
        true,
      ),
    };
  }
}
export function summarizeRecording(samples: readonly ImportedSample[]): RecordingSummary {
  const first = samples[0],
    last = samples.at(-1);
  const end = last?.elapsedSeconds ?? 0;
  const metadata = new AnalyticsMetadata(
    'import',
    first?.timestamp ?? '1970-01-01T00:00:00Z',
    end,
    true,
    false,
    false,
    false,
    8,
    last?.timestamp ?? '1970-01-01T00:00:00Z',
    end,
    [new AnalyticsSource('controller', 'controller', null, null)],
    '{}',
  );
  const preparation = new AnalyticsPreparation(new AnalyticsPin(metadata, '1', 'auto'));
  for (const { item } of importedAnalysis(samples)) preparation.append(item);
  const prepared = preparation.finish('auto', []);
  const composer = new AnalyticsComposition(prepared, 'auto', [], []);
  const motor = new AnalyticsMetricReducer('motorInputPowerW', '0', analyticsTimeUs(end));
  for (const { item, sample } of importedAnalysis(samples)) {
    composer.append(item);
    if (sample)
      appendStatistic(motor, {
        timeUs: item.collectionUs,
        observationId: item.observationId,
        eventId: item.eventId,
        timestamp: item.timestamp,
        elapsedSeconds: item.collectionElapsedSeconds,
        value: Number.isFinite(sample.motorInputPowerW) ? sample.motorInputPowerW : undefined,
        activeInterval: sample.activeInterval === null ? null : (prepared.activeInterval(item) ?? null),
        connectionEpoch: JSON.stringify([sample.connection, sample.activeInterval]),
        interruptionIndex: sample.run,
      });
  }
  const summaryJson = composer.summaryJson('0'),
    battery = motor.result();
  if (summaryJson == null) throw new Error(composer.outcome());
  const summary = JSON.parse(summaryJson) as WorkoutSummary;
  if (!battery) throw new Error(motor.outcome());
  let gapCount = 0,
    clockDiscontinuities = 0;
  for (let index = 1; index < samples.length; index++) {
    const sample = samples[index]!,
      previous = samples[index - 1]!;
    const dt = sample.elapsedSeconds - previous.elapsedSeconds;
    const utcDt = (Date.parse(sample.timestamp) - Date.parse(previous.timestamp)) / 1000;
    if (utcDt <= 0 || Math.abs(utcDt - dt) > 2.5) clockDiscontinuities++;
    if (
      sample.activeInterval !== null &&
      sample.activeInterval === previous.activeInterval &&
      dt > 0 &&
      (dt > MAX_SAMPLE_GAP_SECONDS || sample.run !== previous.run)
    )
      gapCount++;
  }
  return {
    sampleCount: samples.length,
    durationSeconds: first && last ? last.elapsedSeconds - first.elapsedSeconds : 0,
    coveredSeconds: summary.telemetryCoveredSeconds,
    gapCount,
    clockDiscontinuities,
    peakHumanPowerW: summary.maximumRiderPowerW ?? null,
    peakCadenceRpm: summary.maximumCadenceRpm ?? null,
    averageHumanPowerW: summary.averageRiderPowerW ?? null,
    averageCadenceRpm: summary.averageCadenceRpm ?? null,
    humanEnergyWh: summary.riderWorkJoules == null ? null : summary.riderWorkJoules / 3600,
    motorInputEnergyWh: battery.coveredUs === '0' ? null : (battery.integral ?? 0) / 3600,
  };
}
