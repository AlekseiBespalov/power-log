import type { ChartMetric } from '../../components/charts/chart-groups';
import type { LatestCell } from './chart-feed-model';

const totals = new Set(['distance', 'ratio', 'cumulative', 'counter']);

export function tileValue(
  metric: ChartMetric,
  cell: LatestCell | undefined,
  tile: { live: boolean; headLive: boolean | null; bikeAvailable: boolean },
): number | null {
  if (!cell) return null;
  if (metric.semantics === 'ratio') return cell.rideAverage;
  if (tile.live && !totals.has(metric.semantics)) {
    if (tile.headLive === false || cell.stale) return null;
    if (!tile.bikeAvailable && metric.origin === 'telemetry') return null;
  }
  return cell.value;
}

export const isTotal = (metric: ChartMetric) => totals.has(metric.semantics);
