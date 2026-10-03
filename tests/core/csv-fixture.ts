import { TELEMETRY_LAYOUT } from '../../src/core/export/tables/telemetry';
import { csvHeader } from '../../src/core/export/tables/text';
import type { ImportedSample } from '../../src/core/recordings';

export function syntheticCsvSample(index = 0): ImportedSample {
  return {
    timestamp: new Date(Date.UTC(2026, 0, 1) + index * 125).toISOString(),
    elapsedSeconds: index / 8,
    sequence: index,
    humanPowerW: 200 + (index % 50),
    cadenceRpm: 80.5,
    motorInputPowerW: 504,
    batteryVoltageV: 50.4,
    batteryCurrentA: 10,
    motorCurrentA: 15.25,
    motorRpm: 1200,
    pedalTorqueNm: 24.5,
    controllerTempC: 30.25,
    motorTempC: 40.75,
    consumedAh: 2.1,
    consumedWh: 105.84,
    throttleVoltageV: 1.2,
    faultCode: 0,
    assistLevel: 2,
    raceMode: 0,
    speedRaw: 36,
    controllerSpeedMps: 10,
    interruptionIndex: 1,
    activeInterval: 1,
    run: 1,
    connection: 1,
  };
}

const KEYS = TELEMETRY_LAYOUT.table.fields.map((field, c) => TELEMETRY_LAYOUT.reads[c] ?? field.name);

export const syntheticCsvHeader = csvHeader(TELEMETRY_LAYOUT.table);

export function syntheticCsvRow(sample: ImportedSample): string {
  return KEYS.map(key => {
    const value = sample[key as keyof ImportedSample];
    return value === null || value === undefined || Number.isNaN(value) ? '' : String(value);
  }).join(',');
}

export function* syntheticCsvChunks(count: number, chunkBytes = 1024 * 1024): Generator<string> {
  let chunk = syntheticCsvHeader + '\n';
  for (let index = 0; index < count; index += 1) {
    chunk += syntheticCsvRow(syntheticCsvSample(index)) + '\n';
    while (chunk.length >= chunkBytes) {
      yield chunk.slice(0, chunkBytes);
      chunk = chunk.slice(chunkBytes);
    }
  }
  if (chunk) yield chunk;
}
