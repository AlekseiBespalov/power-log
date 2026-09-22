import { ALL_MASK, SELECTED_MASK, TELEMETRY_FIELDS } from './protocol';
import { MAX_SAMPLE_GAP_SECONDS, REQUIRED_SAMPLE_COLUMNS, SAMPLE_COLUMNS, SAMPLE_IDENTITY_COLUMNS, type RecordingSource, type TelemetrySample } from './types';
import { MAX_CSV_BYTES, MAX_RECORDING_SAMPLES, validateSamples, validateSource } from './validation';

const PYTHON_FIELD_NAMES = [
  'controller_temp_c', 'motor_temp_c', 'motor_current_a', 'battery_current_a',
  'id_current_a', 'iq_current_a', 'duty_cycle', 'motor_rpm', 'battery_voltage_v',
  'consumed_ah', 'trip_time_raw', 'consumed_wh', 'cadence_rpm', 'throttle_voltage_v',
  'pedal_torque_nm', 'fault_code', 'io_flags', 'controller_id', 'mos1_temp_c',
  'mos2_temp_c', 'mcu_temp_c', 'vd_v', 'vq_v', 'odometer_raw', 'human_power_w',
  'speed_raw', 'race_mode', 'assist_level',
] as const;
export const PYTHON_CSV_COLUMNS = ['utc', 'elapsed_s', 'sample', 'response_command', 'field_mask', ...PYTHON_FIELD_NAMES, 'battery_input_power_w'] as const;

export interface ParsedRecording {
  samples: TelemetrySample[];
  source: RecordingSource;
  format: 'power-log' | 'cyc-python';
}

/** CSV provenance comes from the caller; CSV contents cannot authenticate a device. */
export function parseCsv(text: string, options: { source: RecordingSource }): ParsedRecording {
  const source = validateSource(options?.source);
  if (text.length > MAX_CSV_BYTES) throw new Error('CSV exceeds the 32 MiB import limit');
  const input = text.replace(/^\uFEFF/, '');
  // Numeric, UTC and sanitized controller identity fields are ASCII.
  if (/[^\x09\x0a\x0d\x20-\x7e]/.test(input)) throw new Error('CSV contains unsupported characters');
  const rows = parseRows(input);
  const header = rows.shift();
  if (!header || new Set(header).size !== header.length) throw new Error('Missing or duplicate CSV columns');
  const sameColumns = (columns: readonly string[]): boolean => columns.length === header.length && columns.every(column => header.includes(column));
  const format = sameColumns(SAMPLE_COLUMNS) || sameColumns(SAMPLE_COLUMNS.filter(key => key !== 'connectionEpoch')) || sameColumns(REQUIRED_SAMPLE_COLUMNS) ? 'power-log' : sameColumns(PYTHON_CSV_COLUMNS) ? 'cyc-python' : null;
  if (!format) throw new Error('Unrecognized CSV column set; use Power Log or the CYC Python export');
  const values = rows.map((row, index) => {
    if (row.length !== header.length) throw new Error(`CSV row ${index + 2} has an unexpected column count`);
    const record: Record<string, string> = Object.create(null) as Record<string, string>;
    header.forEach((key, column) => { record[key] = row[column]! });
    const value: Record<string, unknown> = {};
    if (format === 'power-log') {
      for (const key of REQUIRED_SAMPLE_COLUMNS) value[key] = key === 'timestamp' ? record[key] : parseNumber(record[key]!, key);
      if (record.controllerSpeedMps) value.controllerSpeedMps = parseNumber(record.controllerSpeedMps, 'controllerSpeedMps');
      for (const key of SAMPLE_IDENTITY_COLUMNS) if (record[key]) value[key] = record[key];
      if (record.connectionEpoch) value.connectionEpoch = record.connectionEpoch;
    } else {
      value.timestamp = record.utc!.replace(/\+00:00$/, 'Z');
      value.elapsedSeconds = parseNumber(record.elapsed_s!, 'elapsed_s');
      value.sequence = parseNumber(record.sample!, 'sample');
      const command = parseNumber(record.response_command!, 'response_command');
      if (!/^0x[0-9a-f]{1,8}$/i.test(record.field_mask!)) throw new Error('Invalid Python field mask');
      const mask = Number.parseInt(record.field_mask!.slice(2), 16);
      if (!((command === 50 && mask === SELECTED_MASK) || (command === 4 && mask === ALL_MASK))) throw new Error('Unsupported Python telemetry profile');
      let fieldIndex = 0;
      TELEMETRY_FIELDS.forEach((fields, bit) => {
        for (const [key] of fields) {
          const pythonKey = PYTHON_FIELD_NAMES[fieldIndex++]!;
          if (mask & (1 << bit)) value[key] = parseNumber(record[pythonKey]!, pythonKey);
          else if (record[pythonKey] !== '') throw new Error(`Unexpected value for unselected field ${pythonKey}`);
        }
      });
      value.motorInputPowerW = parseNumber(record.battery_input_power_w!, 'battery_input_power_w');
    }
    return value;
  });
  return { samples: validateSamples(values, true), source, format };
}

function parseNumber(value: string, name: string): number {
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim())) throw new Error(`Invalid CSV numeric field ${name}`);
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`Non-finite CSV numeric field ${name}`);
  return parsed;
}

function parseRows(input: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  let closedQuote = false;
  const finishCell = (): void => { row.push(cell); cell = ''; closedQuote = false };
  const finishRow = (): void => {
    finishCell(); rows.push(row); row = [];
    if (rows.length > MAX_RECORDING_SAMPLES + 1) throw new Error('CSV exceeds the sample count limit');
  };
  for (let index = 0; index < input.length; index += 1) {
    const character = input[index]!;
    if (quoted) {
      if (character === '"') {
        if (input[index + 1] === '"') { cell += '"'; index += 1 }
        else { quoted = false; closedQuote = true }
      } else cell += character;
    } else if (character === ',') finishCell();
    else if (character === '\n' || character === '\r') {
      finishRow(); if (character === '\r' && input[index + 1] === '\n') index += 1;
    } else if (character === '"' && cell === '' && !closedQuote) quoted = true;
    else {
      if (closedQuote || character === '"') throw new Error('Malformed CSV quoting');
      cell += character;
    }
    if (cell.length > 256 || row.length > PYTHON_CSV_COLUMNS.length) throw new Error('CSV field or column count exceeds supported limits');
  }
  if (quoted) throw new Error('Unterminated CSV quoted field');
  if (cell !== '' || row.length > 0 || closedQuote) finishRow();
  return rows;
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

/** Time-weighted trapezoids only across fresh adjacent samples; outages add no energy. */
export function summarizeRecording(input: readonly TelemetrySample[], maxGapSeconds = MAX_SAMPLE_GAP_SECONDS): RecordingSummary {
  if (!Number.isFinite(maxGapSeconds) || maxGapSeconds <= 0) throw new Error('Invalid maximum sample gap');
  const samples = validateSamples(input, true);
  let coveredSeconds = 0; let gapCount = 0; let missingSamples = 0; let clockDiscontinuities = 0;
  let humanJoules = 0; let motorJoules = 0; let cadenceSeconds = 0;
  let peakHumanPowerW: number | null = null; let peakCadenceRpm: number | null = null;
  samples.forEach((sample, index) => {
    peakHumanPowerW = peakHumanPowerW === null ? sample.humanPowerW : Math.max(peakHumanPowerW, sample.humanPowerW);
    peakCadenceRpm = peakCadenceRpm === null ? sample.cadenceRpm : Math.max(peakCadenceRpm, sample.cadenceRpm);
    const previous = samples[index - 1];
    if (!previous) return;
    missingSamples += sample.sequence - previous.sequence - 1;
    const dt = sample.elapsedSeconds - previous.elapsedSeconds;
    const utcDt = (Date.parse(sample.timestamp) - Date.parse(previous.timestamp)) / 1000;
    if (utcDt <= 0 || Math.abs(utcDt - dt) > 2.5) clockDiscontinuities += 1;
    if (dt > maxGapSeconds) { gapCount += 1; return }
    coveredSeconds += dt;
    humanJoules += (previous.humanPowerW + sample.humanPowerW) * dt / 2;
    motorJoules += (previous.motorInputPowerW + sample.motorInputPowerW) * dt / 2;
    cadenceSeconds += (previous.cadenceRpm + sample.cadenceRpm) * dt / 2;
  });
  return {
    sampleCount: samples.length,
    durationSeconds: samples.length > 1 ? samples[samples.length - 1]!.elapsedSeconds - samples[0]!.elapsedSeconds : 0,
    coveredSeconds, gapCount, missingSamples, clockDiscontinuities, peakHumanPowerW, peakCadenceRpm,
    averageHumanPowerW: coveredSeconds > 0 ? humanJoules / coveredSeconds : null,
    averageCadenceRpm: coveredSeconds > 0 ? cadenceSeconds / coveredSeconds : null,
    humanEnergyWh: humanJoules / 3600, motorInputEnergyWh: motorJoules / 3600,
  };
}
