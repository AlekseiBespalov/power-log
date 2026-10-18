import { AnalyticsItem } from '../../shared/js';
import { readFileSync } from 'node:fs';
import { syntheticSample } from '../fixtures/synthetic-sample';
import type { MonitorPoint } from '../../src/core/monitor';
export interface InspectionFixturePoint {
  id: string;
  timeUs: string;
  value?: number | string | null;
  kind?: string;
  clock?: string;
  connection?: string;
  interruptionIndex?: string;
  sequence?: string;
  boundary?: boolean;
  elapsedSeconds?: number;
  accuracy?: number;
  selected?: boolean;
  declared?: string[];
}
export interface InspectionFixtureCase {
  id: string;
  metric: string;
  points: InspectionFixturePoint[];
  timeUs?: string;
  startUs?: string;
  endUs?: string;
  rideEndUs?: string;
  anchor?: string;
  allowTrailing?: boolean;
  lifecycle?: { timeUs: string; action: string; interrupted?: boolean; controllerSequence?: string }[];
  expected: string | string[] | null;
  segments?: number[];
  count?: string;
  minimum?: string;
  maximum?: string;
  mean?: number | null;
  coveredUs?: string;
  integral?: number | null;
  suppressed?: boolean;
  absent?: boolean;
}
export function inspectionFixtures(name: string): InspectionFixtureCase[] {
  return JSON.parse(readFileSync(new URL(`../fixtures/contract/inspection/${name}.json`, import.meta.url), 'utf8'))
    .cases;
}
export function fixturePoint(row: InspectionFixturePoint): MonitorPoint {
  return {
    observationId: row.id,
    timestamp: '2026-01-01T00:00:00.123456789Z',
    elapsedSeconds: row.elapsedSeconds ?? Number(row.timeUs) / 1e6,
    value: row.value == null ? NaN : Number(row.value),
    ...(typeof row.value === 'string' && row.value !== 'NaN' ? { exactValue: row.value } : {}),
  };
}
export function fixtureSample(row: InspectionFixturePoint, metric: string, index: number) {
  const p = fixturePoint(row);
  return {
    ...syntheticSample(p.elapsedSeconds, index, p.timestamp),
    [metric]: p.value,
    observationId: row.id,
    inspection: {
      kind: row.kind,
      declared: row.declared,
      selected: row.selected,
      accuracy: row.accuracy,
      exactValue: p.exactValue,
    },
    clockEpoch: row.clock,
    connectionEpoch: row.connection ?? 'fixture',
    interruptionIndex: Number(row.interruptionIndex ?? 0),
  };
}

export function fixtureEvidence(row: InspectionFixtureCase) {
  return {
    rideEndSeconds: Number(row.rideEndUs ?? row.endUs) / 1e6,
    ...(row.lifecycle
      ? {
          lifecycle: row.lifecycle.map(
            (event, index) =>
              new AnalyticsItem(
                event.timeUs,
                Number(event.timeUs) / 1e6,
                `event-${index}`,
                `event-${index}`,
                'phone',
                String(index + 1),
                'lifecycle',
                'phone',
                '2026-01-01T00:00:00Z',
                [],
                [],
                [],
                null,
                null,
                null,
                null,
                0,
                event.action,
                false,
                event.interrupted ?? false,
                event.controllerSequence ?? null,
                true,
              ),
          ),
        }
      : {}),
  };
}
