import type { BrowserRide, RideRow } from '../../src/services/browser-ride-store';
import { syntheticSample } from '../fixtures/synthetic-sample';

export function browserRow(seconds: number, sequence: number, timestamp?: string): Omit<RideRow, 'recordingId'> {
  return {
    ...syntheticSample(seconds, sequence, timestamp),
    connectionEpoch: 'test-epoch',
    active: true,
    interval: 0,
    originalSequence: sequence,
    originalElapsedSeconds: seconds,
  };
}
export function browserRide(values: Partial<BrowserRide> = {}): BrowserRide {
  return {
    id: 'test-ride',
    startedAt: '2026-01-01T00:00:00.000Z',
    checkpointAt: '2026-01-01T00:00:00.000Z',
    samples: 0,
    interrupted: false,
    phase: 'running',
    writer: 'owner',
    indoor: true,
    revision: 1,
    elapsedSeconds: 0,
    timerSeconds: 0,
    availableMetrics: [],
    warnings: [],
    lapCount: 0,
    ...values,
  };
}
