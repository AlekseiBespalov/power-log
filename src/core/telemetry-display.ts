import { isFreshSample, type ConnectionStatus } from './types';

/** Presentation only. Capture, exports and sensor sharing keep their own freshness rules. */
export const TELEMETRY_DISPLAY_HOLD_SECONDS = 6;
export type TelemetryDisplay = 'live' | 'held' | 'unavailable';

/** Account for native events queued while the UI was suspended. */
export function displaySampleTime(timestamp: string, receivedAt: number, wallTimeMs: number): number | null {
  const measuredAt = Date.parse(timestamp);
  if (![measuredAt, receivedAt, wallTimeMs].every(Number.isFinite) || measuredAt > wallTimeMs + 2000) return null;
  return receivedAt - Math.max(0, (wallTimeMs - measuredAt) / 1000);
}

export function telemetryDisplay(status: ConnectionStatus, receivedAt: number | null, now: number): TelemetryDisplay {
  if (status !== 'connected' && status !== 'reconnecting') return 'unavailable';
  if (status === 'connected' && isFreshSample(receivedAt, now)) return 'live';
  return receivedAt !== null && isFreshSample(receivedAt, now, TELEMETRY_DISPLAY_HOLD_SECONDS)
    && now - receivedAt < TELEMETRY_DISPLAY_HOLD_SECONDS ? 'held' : 'unavailable';
}

export function bikeConnectionLabel(status: ConnectionStatus, display: TelemetryDisplay): string {
  if (display === 'live' || display === 'held') return 'Connected';
  if (status === 'reconnecting') return 'Reconnecting…';
  if (status === 'connected') return 'Waiting for data';
  if (status === 'connecting') return 'Connecting…';
  if (status === 'scanning') return 'Searching…';
  return 'Not connected';
}
