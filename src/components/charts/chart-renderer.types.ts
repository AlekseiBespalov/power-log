import type { SharedValue } from 'react-native-reanimated';
import type { TemperatureUnit, UnitSystem } from '../../../modules/power-log';
import type { ChartGroup, ChartMetric } from './chart-groups';
import type { ChartPoint, ChartScale, SeriesMap } from './chart-series';
import type { ChartViewport } from './chart-viewport';
import type { HitScene } from './hit-test';
import type { ChartInteraction } from './use-chart-interaction';

export interface ChartMarks {
  metric: ChartMetric;
  cursor: ChartPoint | null;
  reference: ChartPoint | null;
}

export interface ChartTail {
  metric: ChartMetric;
  startS: number;
  endS: number;
  value: number;
}

export interface ChartRendererProps {
  sourceId: string;
  revision: string;
  group: ChartGroup;
  series: SeriesMap;
  frame: ChartViewport;
  view: ChartViewport;
  scale: ChartScale;
  laneCount: number;
  width: number;
  height: number;
  units: UnitSystem;
  temperature: TemperatureUnit;
  interaction: ChartInteraction;
  hitScene: SharedValue<HitScene | null>;
  cursor: number | null;
  reference: number | null;
  cursorPending: boolean;
  marks: readonly ChartMarks[];
  tails: readonly ChartTail[];
  snappable: boolean;
  hasSamples: boolean;
}
