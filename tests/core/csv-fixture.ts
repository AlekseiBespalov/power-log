import { csvRow } from '../../src/core/recordings';
import { SAMPLE_COLUMNS, type TelemetrySample } from '../../src/core/types';

export function syntheticCsvSample(sequence = 0): TelemetrySample {
  return {
    timestamp: new Date(Date.UTC(2026, 0, 1) + sequence * 125).toISOString(),
    elapsedSeconds: sequence / 8,
    sequence,
    humanPowerW: 200 + (sequence % 50),
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
    controllerModel: 'X12',
    firmwareLabel: '20250604',
    controllerProtocol: '5.3',
    connectionEpoch: 'synthetic-connection-1',
    interruptionIndex: 0,
  };
}

export function* syntheticCsvChunks(count: number, chunkBytes = 1024 * 1024): Generator<string> {
  let chunk = SAMPLE_COLUMNS.join(',') + '\n';
  for (let sequence = 0; sequence < count; sequence += 1) {
    chunk += csvRow(syntheticCsvSample(sequence)) + '\n';
    while (chunk.length >= chunkBytes) {
      yield chunk.slice(0, chunkBytes);
      chunk = chunk.slice(chunkBytes);
    }
  }
  if (chunk) yield chunk;
}
