import { kit, type MetricId, type TemperatureUnit, type UnitSystem } from '../../../modules/power-log';
import type { ChartSeries, Viewport } from '../../features/charts/chart-feed-model';

export interface RasterScene {
  key: string;
  sourceId: string;
  revision: string;
  startS: number;
  endS: number;
  min: number;
  max: number;
  decimals: number;
  laneCount: number;
  series: { id: MetricId; color: `#${string}`; step: boolean; points: [number, number, boolean][] }[];
}

export interface RasterSceneInput {
  key: string;
  sourceId: string;
  revision: string;
  viewport: Viewport;
  series: Readonly<Partial<Record<MetricId, ChartSeries>>>;
  group: readonly { id: MetricId; color: string; step: boolean }[];
  scale: { min: number; max: number; decimals: number };
  laneCount: number;
  units: UnitSystem;
  temperature: TemperatureUnit;
}

export function rasterScene(input: RasterSceneInput): RasterScene {
  const series = input.group.map(metric => {
    const drawn = input.series[metric.id];
    const unit = kit.unitsDisplay(metric.id, input.units, input.temperature);
    const factor = unit?.factor ?? 1;
    const offset = unit?.offset ?? 0;
    const points: [number, number, boolean][] = [];
    let start = true;
    if (drawn)
      for (let index = 0; index < drawn.atS.length; index++) {
        const atS = drawn.atS[index]!;
        const value = drawn.value[index]! * factor + offset;
        start ||= drawn.starts[index] === 1;
        if (!Number.isFinite(atS) || !Number.isFinite(value)) {
          start = true;
          continue;
        }
        points.push([atS, value, start]);
        start = false;
      }
    return { id: metric.id, color: metric.color as `#${string}`, step: metric.step, points };
  });
  return {
    key: input.key,
    sourceId: input.sourceId,
    revision: input.revision,
    startS: input.viewport.startS,
    endS: Math.max(input.viewport.endS, input.viewport.startS + 1e-6),
    min: input.scale.min,
    max: input.scale.max,
    decimals: input.scale.decimals,
    laneCount: input.laneCount,
    series,
  };
}
