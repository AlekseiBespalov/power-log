import { REQUIRED_SAMPLE_COLUMNS, SAMPLE_IDENTITY_COLUMNS, type Recording, type RecordingSource, type TelemetrySample } from './types';
import { hasKnownControllerSpeedUnit } from './controller-speed';

export const MAX_CSV_BYTES = 32 * 1024 * 1024;
export const MAX_RECORDING_SAMPLES = 200_000;
export const MAX_RECORDING_DURATION_SECONDS = 7 * 24 * 60 * 60;

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function validateTimestamp(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value))
    throw new Error('Timestamp must be an ISO UTC timestamp ending in Z');
  const date = new Date(value);
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 19) !== value.slice(0, 19))
    throw new Error('Invalid calendar timestamp');
  return value;
}

export function validateSource(value: unknown): RecordingSource {
  if (value !== 'device') throw new Error('Recording source must explicitly be device');
  return value;
}

function finiteNumber(value: unknown, name: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > 1e12)
    throw new Error(`Invalid numeric field ${name}`);
  return value;
}

export function validateSample(value: unknown): TelemetrySample {
  if (!isRecord(value)) throw new Error('Sample must be an object');
  const timestamp = validateTimestamp(value.timestamp);
  const numeric: Record<string, number> = {};
  for (const key of REQUIRED_SAMPLE_COLUMNS) if (key !== 'timestamp') numeric[key] = finiteNumber(value[key], key);
  if (numeric.elapsedSeconds! < 0 || numeric.elapsedSeconds! > MAX_RECORDING_DURATION_SECONDS)
    throw new Error('Elapsed time is outside the supported recording duration');
  if (!Number.isSafeInteger(numeric.sequence) || numeric.sequence! < 0)
    throw new Error('Sequence must be a nonnegative safe integer');
  for (const key of ['faultCode', 'assistLevel', 'raceMode'] as const)
    if (!Number.isInteger(numeric[key]) || numeric[key]! < 0 || numeric[key]! > 255) throw new Error(`Invalid byte field ${key}`);
  for (const key of ['humanPowerW', 'motorRpm'] as const)
    if (!Number.isInteger(numeric[key]) || numeric[key]! < -2147483648 || numeric[key]! > 2147483647) throw new Error(`Invalid integer field ${key}`);
  const electricalWatts = numeric.batteryVoltageV! * numeric.batteryCurrentA!;
  if (!Number.isFinite(electricalWatts) || Math.abs(numeric.motorInputPowerW! - electricalWatts) > Math.max(0.001, Math.abs(electricalWatts) * 1e-7))
    throw new Error('Motor input power must equal battery voltage multiplied by battery current');

  const provenance: Partial<Pick<TelemetrySample, typeof SAMPLE_IDENTITY_COLUMNS[number]>> = {};
  if (SAMPLE_IDENTITY_COLUMNS.some(key => value[key] !== undefined)) {
    if (typeof value.controllerModel !== 'string' || !/^X(?:6|12)(?:[A-Za-z_][A-Za-z0-9_]{0,29})?$/.test(value.controllerModel)
      || typeof value.firmwareLabel !== 'string' || !/^[0-9]{6,8}[A-Z]{0,8}$/.test(value.firmwareLabel)
      || typeof value.controllerProtocol !== 'string' || !/^(?:0|[1-9][0-9]{0,2})\.(?:0|[1-9][0-9]{0,2})$/.test(value.controllerProtocol)
      || value.controllerProtocol.split('.').some(part => Number(part) > 255)) throw new Error('Invalid controller provenance');
    Object.assign(provenance, { controllerModel: value.controllerModel, firmwareLabel: value.firmwareLabel, controllerProtocol: value.controllerProtocol });
  }
  if (value.controllerSpeedMps !== undefined) {
    const speed = finiteNumber(value.controllerSpeedMps, 'controllerSpeedMps');
    if (!hasKnownControllerSpeedUnit(provenance.controllerModel ?? '', provenance.controllerProtocol ?? '')
      || Math.abs(speed - numeric.speedRaw! / 3.6) > Math.max(1e-9, Math.abs(speed) * 1e-12)) throw new Error('Controller speed does not match its unit provenance');
    numeric.controllerSpeedMps = speed;
  }
  if (value.connectionEpoch !== undefined && (typeof value.connectionEpoch !== 'string' || !/^[a-zA-Z0-9._:-]{1,128}$/.test(value.connectionEpoch))) throw new Error('Invalid connection epoch');
  return { timestamp, ...numeric, ...provenance, ...(value.connectionEpoch !== undefined ? { connectionEpoch: value.connectionEpoch } : {}) } as unknown as TelemetrySample;
}

export function validateSamples(values: readonly unknown[], allowEmpty = false): TelemetrySample[] {
  if ((!allowEmpty && values.length === 0) || values.length > MAX_RECORDING_SAMPLES) throw new Error('Unsupported recording sample count');
  const samples: TelemetrySample[] = [];
  let previous: TelemetrySample | undefined;
  for (const value of values) {
    const sample = validateSample(value);
    if (previous) {
      if (sample.elapsedSeconds <= previous.elapsedSeconds || sample.sequence <= previous.sequence)
        throw new Error('Sample elapsed times and sequence numbers must increase');
      // UTC may jump after a clock correction. Monotonic elapsed time owns ordering.
    }
    samples.push(sample); previous = sample;
  }
  return samples;
}

export function validateRecording(value: unknown): Recording {
  if (!isRecord(value) || typeof value.id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value.id) || value.id.includes('..'))
    throw new Error('Invalid recording identifier');
  if (typeof value.uri !== 'string' || value.uri.length === 0 || value.uri.length > 4096) throw new Error('Invalid recording URI');
  const startedAt = validateTimestamp(value.startedAt);
  const samples = finiteNumber(value.samples, 'samples');
  if (!Number.isSafeInteger(samples) || samples < 0) throw new Error('Invalid sample count');
  const result: Recording = { id: value.id, uri: value.uri, source: validateSource(value.source), startedAt, samples };
  if (value.endedAt !== undefined) {
    result.endedAt = validateTimestamp(value.endedAt);
    // Metadata retains actual UTC even if the system clock moved during capture.
  }
  if (value.interrupted !== undefined) {
    if (typeof value.interrupted !== 'boolean') throw new Error('Invalid interrupted state');
    result.interrupted = value.interrupted;
  }
  return result;
}
