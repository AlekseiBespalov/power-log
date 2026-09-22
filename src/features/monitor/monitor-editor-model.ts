import { MONITOR_METRICS, metricById, type SpeedUnit } from '../../core/monitor';

export function toggleMonitorMetric(selected: readonly string[], id: string): string[] {
  if (!metricById(id)) return [...selected];
  return selected.includes(id) ? selected.filter(value => value !== id) : [...selected, id];
}
export function reorderMonitorMetric(selected: readonly string[], id: string, target: number): string[] {
  const result = [...selected], index = result.indexOf(id);
  if (index < 0 || !Number.isInteger(target) || target < 0 || target >= result.length) return result;
  result.splice(index, 1);
  result.splice(target, 0, id);
  return result;
}
export function searchMonitorMetrics(query: string, speedUnit: SpeedUnit = 'km/h') {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  return MONITOR_METRICS.filter(metric => metric.id !== 'speedRaw').map(metric => metricById(metric.id, speedUnit)!).filter(metric => {
    const searchable = `${metric.label} ${metric.shortLabel} ${metric.unit} ${metric.group}`.toLocaleLowerCase();
    return words.every(word => searchable.includes(word));
  });
}
