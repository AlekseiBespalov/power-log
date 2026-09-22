import { describe, expect, it } from 'vitest';
import { MONITOR_METRICS, defaultMonitorPreferences } from '../../src/core/monitor';
import { reorderMonitorMetric, searchMonitorMetrics, toggleMonitorMetric } from '../../src/features/monitor/monitor-editor-model';

describe('monitor editor choices', () => {
  it('permits every metric in each view without duplicates or mutating defaults', () => {
    const defaults = defaultMonitorPreferences();
    expect(new Set(MONITOR_METRICS.map(metric => metric.id)).size).toBe(MONITOR_METRICS.length);
    for (const view of Object.values(defaults.views)) for (const list of ['numbers', 'charts'] as const) {
      let chosen: string[] = [];
      for (const metric of MONITOR_METRICS) chosen = toggleMonitorMetric(chosen, metric.id);
      expect(chosen).toEqual(MONITOR_METRICS.map(metric => metric.id));
      chosen = toggleMonitorMetric(chosen, 'motorTempC'); expect(chosen).not.toContain('motorTempC');
      expect(view[list]).toEqual(defaultMonitorPreferences().views[view.id][list]);
    }
  });
  it('reorders only the requested list and handles first/last/unknown boundaries', () => {
    const chosen = Object.freeze(['motorTempC', 'batteryCurrentA', 'cadenceRpm']);
    expect(reorderMonitorMetric(chosen, 'batteryCurrentA', 0)).toEqual(['batteryCurrentA', 'motorTempC', 'cadenceRpm']);
    expect(reorderMonitorMetric(chosen, 'motorTempC', 1)).toEqual(['batteryCurrentA', 'motorTempC', 'cadenceRpm']);
    expect(reorderMonitorMetric(chosen, 'motorTempC', -1)).toEqual(chosen);
    expect(reorderMonitorMetric(chosen, 'cadenceRpm', 3)).toEqual(chosen);
    expect(reorderMonitorMetric(chosen, 'unknown', 0)).toEqual(chosen);
    expect(reorderMonitorMetric(chosen, 'motorTempC', 2)).toEqual(['batteryCurrentA', 'cadenceRpm', 'motorTempC']);
    expect(reorderMonitorMetric(chosen, 'cadenceRpm', 0)).toEqual(['cadenceRpm', 'motorTempC', 'batteryCurrentA']);
    expect(reorderMonitorMetric(chosen, 'cadenceRpm', NaN)).toEqual(chosen);
    expect(toggleMonitorMetric(chosen, 'unknown')).toEqual(chosen);
  });
  it('searches the complete catalog by label, group and unit', () => {
    expect(searchMonitorMetrics('')).toHaveLength(27);
    expect(searchMonitorMetrics('temperature').map(metric => metric.id)).toEqual(['motorTempC', 'controllerTempC']);
    expect(searchMonitorMetrics('  BATTERY current ').map(metric => metric.id)).toEqual(['batteryCurrentA']);
    expect(searchMonitorMetrics('°C').map(metric => metric.id)).toEqual(['motorTempC', 'controllerTempC']);
    expect(searchMonitorMetrics('not-a-metric')).toEqual([]);
  });
});
