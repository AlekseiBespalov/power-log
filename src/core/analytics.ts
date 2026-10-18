import { AnalyticsMetricReducer } from '../../shared/js';

export type StatisticalReading = {
  timeUs: string;
  observationId: string;
  eventId: string;
  timestamp: string;
  elapsedSeconds: number;
  value?: number;
  exactValue?: string;
  activeInterval: number | null;
  clockEpoch?: string;
  connectionEpoch?: string;
  interruptionIndex?: number;
  accuracy?: number;
};
export function appendStatistic(reducer: AnalyticsMetricReducer, row: StatisticalReading): void {
  reducer.append(
    row.timeUs,
    row.observationId,
    row.eventId,
    row.timestamp,
    row.elapsedSeconds,
    row.exactValue === undefined ? 'real64' : 'integer64',
    row.exactValue ?? (row.value === undefined ? null : Object.is(row.value, -0) ? '-0.0' : String(row.value)),
    row.activeInterval,
    row.clockEpoch ?? null,
    row.connectionEpoch ?? null,
    row.interruptionIndex ?? 0,
    true,
    row.accuracy ?? null,
  );
}
