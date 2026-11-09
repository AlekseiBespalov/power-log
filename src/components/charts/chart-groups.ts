import { kit, type MetricId, type TemperatureUnit, type UnitSystem } from '../../../modules/power-log';

export interface ChartMetric {
  id: MetricId;
  label: string;
  shortLabel: string;
  unit: string;
  group: string;
  color: string;
  description: string | null;
  decimals: number;
  factor: number;
  offset: number;
  step: boolean;
  zero: boolean;
  semantics: string;
  allowsMean: boolean;
  origin: string;
  display(value: number): number;
  format(value: number | null | undefined): string;
  exact(value: number | null | undefined): string;
  difference(a: number | null | undefined, b: number | null | undefined): string;
}

export interface ChartGroup {
  id: string;
  label: string;
  unit: string;
  metrics: ChartMetric[];
}

export function chartMetric(id: string, units: UnitSystem, temperature: TemperatureUnit): ChartMetric | undefined {
  const appearance = kit.metricAppearance(id);
  const unit = kit.unitsDisplay(id, units, temperature);
  if (!appearance || !unit) return undefined;
  const metric = id as MetricId;
  return {
    id: metric,
    label: appearance.label,
    shortLabel: appearance.shortLabel,
    unit: unit.symbol,
    group: appearance.group,
    color: appearance.color,
    description: appearance.description ?? null,
    decimals: unit.decimals,
    factor: unit.factor,
    offset: unit.offset,
    step: appearance.step,
    zero: appearance.zero,
    semantics: appearance.semantics,
    allowsMean: appearance.allowsMean,
    origin: appearance.origin,
    display: value => value * unit.factor + unit.offset,
    format: value => kit.formatMetric(id, value ?? null, units, temperature),
    exact: value =>
      value == null ? kit.formatMetric(id, null, units, temperature) : kit.formatExact(id, value, units, temperature),
    difference: (a, b) => kit.formatDifference(id, a ?? null, b ?? null, units, temperature),
  };
}

export function chartMetrics(units: UnitSystem, temperature: TemperatureUnit) {
  const cache = new Map<string, ChartMetric | undefined>();
  return (id: string) => {
    if (!cache.has(id)) cache.set(id, chartMetric(id, units, temperature));
    return cache.get(id);
  };
}

// Matching units alone are insufficient: crank and motor RPM, for example, have unrelated scales.
const overlays: Partial<Record<MetricId, readonly [string, string]>> = {
  batteryWhPerKm: ['energy-per-distance', 'Consumption'],
  riderWhPerKm: ['energy-per-distance', 'Consumption'],
  humanPowerW: ['power', 'Power'],
  motorInputPowerW: ['power', 'Power'],
  motorTempC: ['temperature', 'Temperature'],
  controllerTempC: ['temperature', 'Temperature'],
  batteryCurrentA: ['current', 'Current'],
  motorCurrentA: ['current', 'Current'],
  speedMps: ['speed', 'Speed'],
  gpsDistanceMeters: ['distance-comparison', 'Distance'],
  controllerDistanceMeters: ['distance-comparison', 'Distance'],
  controllerEnergyWh: ['battery-energy', 'Energy used'],
  calculatedEnergyWh: ['battery-energy', 'Energy used'],
  controllerChargeAh: ['battery-charge', 'Charge used'],
  calculatedChargeAh: ['battery-charge', 'Charge used'],
  controllerSpeedMps: ['speed', 'Speed'],
  activeEnergyKcal: ['energy', 'Energy'],
  basalEnergyKcal: ['energy', 'Energy'],
  horizontalAccuracyM: ['accuracy', 'GPS accuracy'],
  verticalAccuracyM: ['accuracy', 'GPS accuracy'],
};

const groupKey = (id: string) => overlays[id as MetricId]?.[0] ?? id;

export function chartGroups(ids: readonly string[], metric: (id: string) => ChartMetric | undefined): ChartGroup[] {
  const groups = new Map<string, ChartGroup>();
  for (const id of new Set(ids)) {
    const shown = metric(id);
    if (!shown) continue;
    const [key, label] = overlays[shown.id] ?? [id, shown.label];
    const group = groups.get(key);
    if (group) group.metrics.push(shown);
    else groups.set(key, { id: key, label, unit: shown.unit, metrics: [shown] });
  }
  return [...groups.values()];
}

export function reorderCharts(ids: readonly string[], from: string, to: string): string[] {
  const groups = new Map<string, string[]>();
  for (const id of new Set(ids)) groups.set(groupKey(id), [...(groups.get(groupKey(id)) ?? []), id]);
  const order = [...groups.keys()];
  const start = order.indexOf(from),
    end = order.indexOf(to);
  if (start < 0 || end < 0 || start === end) return [...ids];
  order.splice(end, 0, order.splice(start, 1)[0]!);
  return order.flatMap(key => groups.get(key)!);
}

export const isVoltage = (id: string) => id === 'batteryVoltageV' || id === 'throttleVoltageV';
