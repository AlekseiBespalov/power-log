import type { BrowserRide, RideRow } from '../../src/services/browser-ride-store';
import { secondsUs, timestampUs } from '../../src/services/browser-storage-keys';
import { syntheticSample } from '../fixtures/synthetic-sample';

export function browserRow(seconds: number, sequence: number, timestamp?: string): Omit<RideRow, 'recordingId'> {
  const sample = syntheticSample(seconds, sequence, timestamp);
  return {
    eventId: crypto.randomUUID(),
    producer: 'controller',
    producerSequence: String(sequence + 1),
    elapsedUs: secondsUs(seconds),
    queryUs: timestampUs(sample.timestamp),
    ...sample,
    connectionEpoch: 'test-epoch',
    active: true,
    interval: 0,
    originalSequence: sequence,
    originalElapsedSeconds: seconds,
  };
}
export function browserRide(values: Partial<BrowserRide> = {}): BrowserRide {
  const record: BrowserRide = {
    distanceSource: 'controller',
    id: '00000000-0000-0000-0000-000000000001',
    startedAt: '2026-01-01T00:00:00.000Z',
    checkpointAt: '2026-01-01T00:00:00.000Z',
    telemetryCount: 0,
    locationCount: 0,
    lifecycleCount: 1,
    originalMembershipCount: (values.telemetryCount ?? 0) + (values.locationCount ?? 0) + (values.lifecycleCount ?? 1),
    batteryConsumptionSource: 'controller',
    sampleHz: 2,
    recordGPS: false,
    recordingOwner: 'phone',
    ownerDevice: 'browser',
    saveToHealth: false,
    interrupted: false,
    phase: 'running',
    writer: 'owner',
    indoor: true,
    revision: '1',
    elapsedSeconds: 0,
    timerSeconds: 0,
    availableMetrics: [],
    lapCount: 0,
    ...values,
  };
  return record;
}
