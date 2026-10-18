import type { TelemetryReading } from '../../shared/js/power-log-core';
import type { SampleTiming, TelemetrySample } from './types';

export function telemetrySample(reading: TelemetryReading, timing: SampleTiming): TelemetrySample {
  const identity = reading.identity;
  return {
    ...Object.fromEntries(reading.metrics.map(value => [value.metric, value.value])),
    ...timing,
    ...(identity
      ? {
          controllerModel: identity.controllerModel,
          firmwareLabel: identity.firmwareLabel,
          controllerProtocol: identity.controllerProtocol,
        }
      : {}),
    interruptionIndex: 0,
  } as TelemetrySample;
}
