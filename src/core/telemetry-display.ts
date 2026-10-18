import { presentationTransportDisplay, presentationConnectionLabel, telemetryTimingPolicy } from '../../shared/js';
import type { ConnectionStatus } from './types';

export const TELEMETRY_DISPLAY_HOLD_SECONDS = telemetryTimingPolicy().displayHoldSeconds;
export type TelemetryDisplay = 'live' | 'held' | 'unavailable';

export function telemetryDisplay(status: ConnectionStatus, receivedAt: number | null, now: number): TelemetryDisplay {
  return presentationTransportDisplay(status, receivedAt, now) as TelemetryDisplay;
}

export function bikeConnectionLabel(status: ConnectionStatus, display: TelemetryDisplay): string {
  return presentationConnectionLabel(status, display);
}
