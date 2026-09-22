import type { SharedValue } from 'react-native-reanimated';
import type { MonitorHitScene } from '../core/monitor-hit-test';
import type { MonitorData } from '../core/monitor';
import type { MonitorPlotGroup, MonitorScale } from '../core/monitor-chart';
import type { ChartInteraction } from '../features/monitor/use-chart-interaction';
export type MonitorRasterProps = {
  data: MonitorData; group: MonitorPlotGroup; scale: MonitorScale; laneCount: number;
  width: number; height: number; hitScene: SharedValue<MonitorHitScene | null>; interaction: ChartInteraction; cursor: number | null; reference: number | null; tailTime?: number;
};
