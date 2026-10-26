import * as chart from '../../src/core/monitor-chart.ts';
import { count, counts } from './monitor-render-counts';

export const {
  groupMonitorMetrics,
  isVoltageMetric,
  monitorChartData,
  monitorDisplayDomain,
  monitorElapsed,
  monitorScale,
  monitorSelection,
  monitorTailSeconds,
  monitorViewportTransform,
  nearestMonitorPoint,
  reorderMonitorCharts,
  reuseShallow,
  stepMonitorCursor,
  validMonitorPoint,
} = chart;

export function monitorLineGeometry(...args: Parameters<typeof chart.monitorLineGeometry>) {
  count(counts.scenes, args[1].id);
  return chart.monitorLineGeometry(...args);
}
