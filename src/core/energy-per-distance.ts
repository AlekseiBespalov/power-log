import type { MonitorPoint, MonitorStatistics } from './monitor';

export const ENERGY_SMOOTHING_SECONDS = [0, 5, 30, 60] as const;
export type EnergySmoothingSeconds = (typeof ENERGY_SMOOTHING_SECONDS)[number];

export const ENERGY_PER_DISTANCE_METRICS = ['batteryWhPerKm', 'riderWhPerKm'] as const;
export const isEnergyPerDistanceMetric = (metric: string) =>
  (ENERGY_PER_DISTANCE_METRICS as readonly string[]).includes(metric);

/** Seconds between passive total updates; explicit range inspection stays immediate. */
export function energyAverageInterval(seconds: number): number {
  if (seconds < 60) return 2;
  if (seconds < 600) return 5;
  if (seconds < 3600) return 15;
  return 30;
}

export interface EnergyPerDistanceResult {
  averages: Record<string, MonitorPoint | null>;
  series: Record<string, MonitorPoint[]>;
  points: Record<string, MonitorPoint | null>;
  statistics: Record<string, MonitorStatistics>;
  endpoints: { start: Record<string, MonitorPoint | null>; end: Record<string, MonitorPoint | null> };
}
