import { kit } from '../../../modules/power-log';
import type { ChartMetric } from '../../components/charts/chart-groups';

export const editorGroups = ['rider', 'battery', 'motor', 'route', 'status'] as const;

export function toggleMetric(selected: readonly string[], id: string): string[] {
  return selected.includes(id) ? selected.filter(value => value !== id) : [...selected, id];
}

export function reorderMetric(selected: readonly string[], id: string, target: number): string[] {
  const result = [...selected],
    index = result.indexOf(id);
  if (index < 0 || !Number.isInteger(target) || target < 0 || target >= result.length) return result;
  result.splice(index, 1);
  result.splice(target, 0, id);
  return result;
}

export function searchMetrics(query: string, metric: (id: string) => ChartMetric | undefined): ChartMetric[] {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  return kit
    .metricAppearances()
    .filter(appearance => appearance.selectable)
    .flatMap(appearance => metric(appearance.id) ?? [])
    .filter(shown => {
      const searchable =
        `${shown.label} ${shown.shortLabel} ${shown.unit} ${shown.group} ${shown.description ?? ''}`.toLocaleLowerCase();
      return words.every(word => searchable.includes(word));
    });
}
