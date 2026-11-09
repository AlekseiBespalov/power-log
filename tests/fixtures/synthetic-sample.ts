import type { TelemetrySample } from '../support/telemetry-sample';
import { controllerProtocol } from '../../shared/generated/js/kit';
import { controllerReadingFrame } from '../support/controller-frames';

export function syntheticSample(
  seconds: number,
  sequence: number,
  timestamp = new Date().toISOString(),
): TelemetrySample {
  const effort = Math.max(0, Math.sin(seconds / 23) * 0.45 + 0.55);
  const cadence = 69 + effort * 22 + Math.sin(seconds * 1.4) * 2;
  const watts = 75 + effort * 135 + Math.sin(seconds * 0.7) * 9;
  const voltage = 53.6 - seconds * 0.0002;
  const current = 4 + effort * 7;
  const frame = controllerReadingFrame({
    humanPowerW: Math.round(watts),
    cadenceRpm: cadence,
    batteryVoltageV: voltage,
    batteryCurrentA: current,
    motorCurrentA: current * 1.7,
    motorRpm: Math.round(1700 + effort * 300),
    pedalTorqueNm: watts / ((cadence * 2 * Math.PI) / 60),
    controllerTempC: 30 + effort * 4,
    motorTempC: 35 + effort * 7,
    consumedAh: seconds * 0.002,
    consumedWh: seconds * 0.11,
    throttleVoltageV: 0.83,
    faultCode: 0,
    assistLevel: 2,
    raceMode: 0,
    speedRaw: 0,
  });
  const reading = controllerProtocol().feed(new Int8Array(frame), null).replies[0]!.reading!;
  const identity = reading.identity;
  return {
    ...Object.fromEntries(reading.metrics.map(value => [value.metric, value.value])),
    timestamp,
    elapsedSeconds: seconds,
    sequence,
    ...(identity
      ? {
          controllerModel: identity.controllerModel,
          firmwareLabel: identity.firmwareLabel,
          controllerProtocol: identity.controllerProtocol,
        }
      : {}),
    interruptionIndex: 0,
    connectionEpoch: 'synthetic-connection',
  } as TelemetrySample;
}
