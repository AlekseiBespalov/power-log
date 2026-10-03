import { TABLES } from './export/catalog';
import { createSlicer } from './export/pages';
import { TELEMETRY_LAYOUT } from './export/tables/telemetry';
import { TextChunks, csvHeader, layoutColumns, writeRows, type ByteOutput, type Texts } from './export/tables/text';
import { MAX_SAMPLE_GAP_SECONDS, type TelemetryMeasurements, type TelemetrySample } from './types';
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
  // Every field exists from the start, so all rows share one compact object shape.
  const sample: ImportedSample = {
    timestamp,
    elapsedSeconds,
    sequence: index,
    humanPowerW: 0,
    cadenceRpm: 0,
    motorInputPowerW: 0,
    batteryVoltageV: 0,
    batteryCurrentA: 0,
    motorCurrentA: 0,
    motorRpm: 0,
    pedalTorqueNm: 0,
    controllerTempC: 0,
    motorTempC: 0,
    consumedAh: 0,
    consumedWh: 0,
    throttleVoltageV: 0,
    faultCode: 0,
    assistLevel: 0,
    raceMode: 0,
    speedRaw: 0,
    controllerSpeedMps: 0,
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
  humanEnergyWh: number;
  motorInputEnergyWh: number;
}

class Integral {
  seconds = 0;
  total = 0;
  add(a: number, b: number, dt: number): void {
    if (!Number.isFinite(a) || !Number.isFinite(b)) return;
    this.seconds += dt;
    this.total += ((a + b) * dt) / 2;
  }
  get mean(): number | null {
    return this.seconds > 0 ? this.total / this.seconds : null;
  }
}

const peak = (current: number | null, value: number) =>
  !Number.isFinite(value) ? current : current === null ? value : Math.max(current, value);

export function summarizeRecording(samples: readonly ImportedSample[]): RecordingSummary {
  let coveredSeconds = 0;
  let gapCount = 0;
  let clockDiscontinuities = 0;
  let peakHumanPowerW: number | null = null;
  let peakCadenceRpm: number | null = null;
  const human = new Integral();
  const motor = new Integral();
  const cadence = new Integral();
  for (let index = 0; index < samples.length; index++) {
    const sample = samples[index]!;
    if (sample.activeInterval !== null) {
      peakHumanPowerW = peak(peakHumanPowerW, sample.humanPowerW);
      peakCadenceRpm = peak(peakCadenceRpm, sample.cadenceRpm);
    }
    const previous = samples[index - 1];
    if (!previous) continue;
    const dt = sample.elapsedSeconds - previous.elapsedSeconds;
    const utcDt = (Date.parse(sample.timestamp) - Date.parse(previous.timestamp)) / 1000;
    if (utcDt <= 0 || Math.abs(utcDt - dt) > 2.5) clockDiscontinuities += 1;
    if (sample.activeInterval === null || sample.activeInterval !== previous.activeInterval || dt === 0) continue;
    if (dt > MAX_SAMPLE_GAP_SECONDS || sample.run !== previous.run) {
      gapCount += 1;
      continue;
    }
    coveredSeconds += dt;
    human.add(previous.humanPowerW, sample.humanPowerW, dt);
    motor.add(previous.motorInputPowerW, sample.motorInputPowerW, dt);
    cadence.add(previous.cadenceRpm, sample.cadenceRpm, dt);
  }
  return {
    sampleCount: samples.length,
    durationSeconds: samples.length > 1 ? samples[samples.length - 1]!.elapsedSeconds - samples[0]!.elapsedSeconds : 0,
    coveredSeconds,
    gapCount,
    clockDiscontinuities,
    peakHumanPowerW,
    peakCadenceRpm,
    averageHumanPowerW: human.mean,
    averageCadenceRpm: cadence.mean,
    humanEnergyWh: human.total / 3600,
    motorInputEnergyWh: motor.total / 3600,
  };
}
