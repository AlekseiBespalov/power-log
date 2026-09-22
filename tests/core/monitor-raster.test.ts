import { describe, expect, it } from 'vitest';
import { groupMonitorMetrics, monitorScale } from '../../src/core/monitor-chart';
import { monitorRasterScene, monitorRasterSelection } from '../../src/core/monitor-raster';
import type { MonitorData, MonitorPoint } from '../../src/core/monitor';
const point = (elapsedSeconds: number, value: number, startsSegment?: boolean): MonitorPoint => ({ elapsedSeconds, value, startsSegment, timestamp: '2026-01-01T00:00:00Z' });
const data = (series: MonitorData['series']): MonitorData => ({ sourceId: 'synthetic', revision: '1', startedAt: '2026-01-01T00:00:00Z', domain: { start: 0, end: 28800 }, plotViewport: { start: 100, end: 700 }, statistics: {}, series });
describe('native raster packets', () => {
  it('preserves explicit coarse continuity, original gaps, invalid boundaries, step mode and singleton points', () => {
    const input = data({ assistLevel: [point(100, 1, true), point(200, 2, false), point(201, 3, true), point(202, NaN), point(203, 2, false)] });
    const group = groupMonitorMetrics(['assistLevel'])[0]!;
    const scene = monitorRasterScene(input, group, monitorScale(group.metrics, input), 3);
    expect(scene).toMatchObject({ start: 100, end: 700, laneCount: 3 });
    expect(scene.series[0]).toMatchObject({ step: true, points: [[100, 1, true], [200, 2, false], [201, 3, true], [203, 2, true]] });
  });
  it('unions delayed exact statistics and newer displayed peaks so neither is clipped', () => {
    const input = data({ batteryVoltageV: [point(100, 48), point(101, 60)] });
    input.statistics.batteryVoltageV = { count: 2, min: point(100, 46), max: point(101, 49) };
    const group = groupMonitorMetrics(['batteryVoltageV'])[0]!;
    const scale = monitorScale(group.metrics, input);
    expect(scale.min).toBeLessThan(46); expect(scale.max).toBeGreaterThan(60);
  });
  it('keeps original exact selection times and keeps display-only tails out of base geometry', () => {
    const input = data({ batteryVoltageV: [point(100, 48), point(101, 47)] });
    const group = groupMonitorMetrics(['batteryVoltageV'])[0]!;
    input.selectionSeconds = 100.6; input.selection = { batteryVoltageV: point(100.625, 47.12) };
    input.referenceSeconds = 100; input.reference = { batteryVoltageV: point(100, 48) };
    const before = monitorRasterScene(input, group, monitorScale(group.metrics, input), 1);
    expect(monitorRasterSelection(input, group, 100.6, 100, 105)).toMatchObject({ cursorSeconds: 100.6, points: [{ seconds: 100.625, value: 47.12 }], references: [{ seconds: 100, value: 48 }], tails: [{ start: 101, end: 105, value: 47 }] });
    expect(monitorRasterSelection(input, group, null, null, 107).tails).toEqual([]);
    expect(monitorRasterScene(input, group, monitorScale(group.metrics, input), 1)).toEqual(before);
  });
});
