import { presentationDistanceOptionLabel } from '../../shared/js';
export type DistanceSource = 'auto' | 'gps:watch' | 'gps:phone' | 'controller';
export const distanceMetricSource = (metric: string, selected: DistanceSource): DistanceSource | undefined => {
  if (metric === 'distanceMeters') return selected;
  if (metric === 'gpsDistanceMeters') return 'gps:phone';
  if (metric === 'controllerDistanceMeters') return 'controller';
};
export const isDistanceMetric = (metric: string) => distanceMetricSource(metric, 'auto') !== undefined;
export interface MetricSourceInfo {
  source: string;
  label: string;
  estimated?: boolean;
  partial?: boolean;
  coveredSeconds?: number;
  uncoveredSeconds?: number;
}
export interface DistanceProfileInfo extends MetricSourceInfo {
  source: Exclude<DistanceSource, 'auto'>;
  distanceMeters: number;
}
export interface WorkoutDistanceInfo {
  selection: DistanceSource;
  selected?: DistanceProfileInfo | null;
  available: DistanceProfileInfo[];
}
export const DISTANCE_SOURCE_LABELS: Record<DistanceSource, string> = Object.fromEntries(
  ['auto', 'gps:watch', 'gps:phone', 'controller'].map(source => [
    source,
    presentationDistanceOptionLabel(source, false),
  ]),
) as Record<DistanceSource, string>;
