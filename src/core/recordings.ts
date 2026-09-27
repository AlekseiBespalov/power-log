import {
  MAX_SAMPLE_GAP_SECONDS,
  REQUIRED_SAMPLE_COLUMNS,
  SAMPLE_COLUMNS,
  SAMPLE_IDENTITY_COLUMNS,
  type TelemetrySample,
} from './types';
import { MAX_CSV_BYTES, MAX_RECORDING_SAMPLES, validateSample } from './validation';

export interface ParsedRecording {
  samples: TelemetrySample[];
}

export function createCsvParser() {
  const samples: TelemetrySample[] = [];
  const identities = new Map<string, string>();
  let header: string[] | undefined;
  let columns: Record<string, number> = Object.create(null) as Record<string, number>;
  let bytes = 0;
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  let closedQuote = false;
  let skipLF = false;
  let finished = false;
  const intern = (value: string): string => {
    const existing = identities.get(value);
    if (existing !== undefined) return existing;
    identities.set(value, value);
    return value;
  };
  const finishCell = (): void => {
    row.push(cell);
    cell = '';
    closedQuote = false;
  };
  const finishRow = (): void => {
    finishCell();
    if (!header) {
      if (new Set(row).size !== row.length) throw new Error('Missing or duplicate CSV columns');
      const sameColumns = (keys: readonly string[]): boolean =>
        keys.length === row.length && keys.every(key => row.includes(key));
      if (!sameColumns(SAMPLE_COLUMNS))
        throw new Error('Unrecognized CSV column set; use the 26-column Power Log export');
      header = row;
      columns = Object.fromEntries(header.map((key, index) => [key, index]));
    } else {
      if (samples.length >= MAX_RECORDING_SAMPLES) throw new Error('CSV exceeds the sample count limit');
      if (row.length !== header.length) throw new Error(`CSV row ${samples.length + 2} has an unexpected column count`);
      const field = (key: string): string => row[columns[key]!] ?? '';
      const value: Record<string, unknown> = {};
      for (const key of REQUIRED_SAMPLE_COLUMNS)
        value[key] = key === 'timestamp' ? field(key) : parseNumber(field(key), key);
      if (field('controllerSpeedMps'))
        value.controllerSpeedMps = parseNumber(field('controllerSpeedMps'), 'controllerSpeedMps');
      for (const key of SAMPLE_IDENTITY_COLUMNS) if (field(key)) value[key] = field(key);
      if (field('connectionEpoch')) value.connectionEpoch = field('connectionEpoch');
      value.interruptionIndex = parseNumber(field('interruptionIndex'), 'interruptionIndex');
      const sample = validateSample(value, samples[samples.length - 1]);
      for (const key of [...SAMPLE_IDENTITY_COLUMNS, 'connectionEpoch'] as const) {
        if (sample[key] !== undefined) sample[key] = intern(sample[key]);
      }
      samples.push(sample);
    }
    row = [];
  };
  return {
    write(chunk: string): void {
      if (finished) throw new Error('CSV parser is already finished');
      const bom = bytes === 0 && chunk.startsWith('\uFEFF');
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
        if (cell.length > 256 || row.length > SAMPLE_COLUMNS.length)
          throw new Error('CSV field or column count exceeds supported limits');
      }
    },
    finish(): ParsedRecording {
      if (finished) throw new Error('CSV parser is already finished');
      if (quoted) throw new Error('Unterminated CSV quoted field');
      if (cell !== '' || row.length > 0 || closedQuote) finishRow();
      if (!header) throw new Error('Missing or duplicate CSV columns');
      finished = true;
      identities.clear();
      return { samples };
    },
  };
}

function parseNumber(value: string, name: string): number {
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim()))
    throw new Error(`Invalid CSV numeric field ${name}`);
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`Non-finite CSV numeric field ${name}`);
  return parsed;
}

export const csvRow = (sample: TelemetrySample) => SAMPLE_COLUMNS.map(key => String(sample[key] ?? '')).join(',');

export interface RecordingSummary {
  sampleCount: number;
  durationSeconds: number;
  coveredSeconds: number;
  gapCount: number;
  missingSamples: number;
  clockDiscontinuities: number;
  averageHumanPowerW: number | null;
  peakHumanPowerW: number | null;
  averageCadenceRpm: number | null;
  peakCadenceRpm: number | null;
  humanEnergyWh: number;
  motorInputEnergyWh: number;
}

export function summarizeRecording(samples: readonly TelemetrySample[]): RecordingSummary {
  let coveredSeconds = 0;
  let gapCount = 0;
  let missingSamples = 0;
  let clockDiscontinuities = 0;
  let humanJoules = 0;
  let motorJoules = 0;
  let cadenceSeconds = 0;
  let peakHumanPowerW: number | null = null;
  let peakCadenceRpm: number | null = null;
  samples.forEach((sample, index) => {
    peakHumanPowerW = peakHumanPowerW === null ? sample.humanPowerW : Math.max(peakHumanPowerW, sample.humanPowerW);
    peakCadenceRpm = peakCadenceRpm === null ? sample.cadenceRpm : Math.max(peakCadenceRpm, sample.cadenceRpm);
    const previous = samples[index - 1];
    if (!previous) return;
    missingSamples += sample.sequence - previous.sequence - 1;
    const dt = sample.elapsedSeconds - previous.elapsedSeconds;
    const utcDt = (Date.parse(sample.timestamp) - Date.parse(previous.timestamp)) / 1000;
    if (utcDt <= 0 || Math.abs(utcDt - dt) > 2.5) clockDiscontinuities += 1;
    if (
      dt <= 0 ||
      dt > MAX_SAMPLE_GAP_SECONDS ||
      sample.interruptionIndex !== previous.interruptionIndex ||
      sample.connectionEpoch !== previous.connectionEpoch
    ) {
      gapCount += 1;
      return;
    }
    coveredSeconds += dt;
    humanJoules += ((previous.humanPowerW + sample.humanPowerW) * dt) / 2;
    motorJoules += ((previous.motorInputPowerW + sample.motorInputPowerW) * dt) / 2;
    cadenceSeconds += ((previous.cadenceRpm + sample.cadenceRpm) * dt) / 2;
  });
  return {
    sampleCount: samples.length,
    durationSeconds: samples.length > 1 ? samples[samples.length - 1]!.elapsedSeconds - samples[0]!.elapsedSeconds : 0,
    coveredSeconds,
    gapCount,
    missingSamples,
    clockDiscontinuities,
    peakHumanPowerW,
    peakCadenceRpm,
    averageHumanPowerW: coveredSeconds > 0 ? humanJoules / coveredSeconds : null,
    averageCadenceRpm: coveredSeconds > 0 ? cadenceSeconds / coveredSeconds : null,
    humanEnergyWh: humanJoules / 3600,
    motorInputEnergyWh: motorJoules / 3600,
  };
}
