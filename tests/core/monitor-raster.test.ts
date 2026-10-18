import { inspectionFixtures, fixtureSample } from '../support/inspection-fixtures';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { describe, expect, it } from 'vitest';
import { groupMonitorMetrics, monitorDisplayDomain, monitorScale } from '../../src/core/monitor-chart';
import {
  MONITOR_AXIS,
  monitorAxisFrame,
  monitorRasterScene,
  monitorRasterSelection,
} from '../../src/core/monitor-raster';
import type { MonitorData, MonitorPoint } from '../../src/core/monitor';
import { nativeMonitorRasterScene, nativeMonitorRasterSelection } from '../../src/services/native-monitor-raster';
const point = (elapsedSeconds: number, value: number, startsSegment?: boolean): MonitorPoint => ({
  elapsedSeconds,
  value,
  observationId: `point:${elapsedSeconds}`,
  startsSegment,
  timestamp: '2026-01-01T00:00:00Z',
});
const data = (series: MonitorData['series']): MonitorData => ({
  sourceId: 'synthetic',
  revision: '1',
  startedAt: '2026-01-01T00:00:00Z',
  domain: { start: 0, end: 28800 },
  plotViewport: { start: 100, end: 700 },
  statistics: {},
  series,
});
describe('native raster packets', () => {
  it('constructs Apple geometry once and leaves held-tail decisions to the core', () => {
    const input = data({ batteryVoltageV: [point(1, 48, true)] });
    const group = groupMonitorMetrics(['batteryVoltageV'])[0]!;
    const scale = monitorScale(group.metrics, input);
    let reads = 0;
    Object.defineProperty(input.series.batteryVoltageV![0]!, 'value', {
      get: () => {
        reads++;
        return 48;
      },
    });
    expect(nativeMonitorRasterScene(input, group, scale, 1).series[0]!.points).toEqual([[1, 48, true]]);
    expect(reads).toBe(1);
    Object.defineProperty(input, 'liveAcquiredAt', {
      get: () => {
        throw new Error('legacy tail computation');
      },
    });
    expect(nativeMonitorRasterSelection('ios', input, group, null, null, 2)).not.toHaveProperty('tails');
    expect(nativeMonitorRasterSelection('android', data({}), group, null, null, 2)).toHaveProperty('tails', []);
  });
  it('Android selection skips Apple-only acquisition candidates', () => {
    const input = data({ batteryVoltageV: [point(1, 48, true)] });
    let reads = 0;
    input.monotonicAt = 2;
    Object.defineProperty(input, 'liveAcquiredAt', {
      get: () => {
        if (++reads > 1) throw new Error('Apple-only candidate construction');
        return undefined;
      },
    });
    const group = groupMonitorMetrics(['batteryVoltageV'])[0]!;
    const selection = nativeMonitorRasterSelection('android', input, group, null, null, 2);
    expect(selection).toHaveProperty('tails', []);
    expect(selection).not.toHaveProperty('tailCandidates');
    expect(reads).toBe(1);
  });
  it('preserves core segment evidence and original times across normalized-time ties', () => {
    const input = data({
      batteryVoltageV: [point(1.0000008, 48, true), point(1.0000002, 47, false), point(1.0000002, 46, false)],
    });
    const group = groupMonitorMetrics(['batteryVoltageV'])[0]!;
    const scene = nativeMonitorRasterScene(input, group, monitorScale(group.metrics, input), 1);
    expect(scene.series[0]!.points).toEqual([
      [1.0000008, 48, true],
      [1.0000002, 47, false],
      [1.0000002, 46, false],
    ]);
  });
  it('matches the iPhone axis inset, text frame, padding and edge clamping', () => {
    expect(MONITOR_AXIS).toEqual({ textHeight: 14, inset: 4, horizontalPadding: 4, verticalPadding: 2, gridOffset: 2 });
    expect(monitorAxisFrame('y', 8, 22.1, 360, 140)).toEqual({ x: 12, y: 12, width: 31, height: 18 });
    expect(monitorAxisFrame('y', 106, 22.1, 360, 140)).toEqual({ x: 12, y: 94, width: 31, height: 18 });
    expect(monitorAxisFrame('x', 0, 22.1, 360, 140)).toEqual({ x: 12, y: 118, width: 31, height: 18 });
    expect(monitorAxisFrame('x', 0.5, 22.1, 360, 140)).toEqual({ x: 164.5, y: 118, width: 31, height: 18 });
    expect(monitorAxisFrame('x', 1, 22.1, 360, 140)).toEqual({ x: 317, y: 118, width: 31, height: 18 });
  });
  it('keeps empty raster geometry fixed as the live clock advances and emits no held tails', () => {
    const input = data({ batteryVoltageV: [] });
    input.domain = { start: 0, end: 10 };
    delete input.plotViewport;
    const group = groupMonitorMetrics(['batteryVoltageV'])[0]!;
    const scale = monitorScale(group.metrics, input);
    const initial = monitorRasterScene(input, group, scale, 1);
    for (const nowSeconds of [30, 120, 3600]) {
      input.nowSeconds = nowSeconds;
      expect(monitorDisplayDomain(input, true, nowSeconds)).toEqual(input.domain);
      expect(monitorRasterScene(input, group, scale, 1)).toEqual(initial);
      expect(monitorRasterSelection(input, group, null, null, nowSeconds).tails).toEqual([]);
    }
    expect(initial).toMatchObject({ start: 0, end: 10, series: [{ points: [] }] });
  });

  it('never borrows a current Health reading for the plotted tail after a clock rollback', () => {
    const old = point(100, 120);
    const current = point(40, 140);
    const input = data({ heartRateBpm: [current, old] });
    input.monotonicAt = 1001;
    input.liveAcquiredAt = { heartRateBpm: 1000 };
    input.latest = { heartRateBpm: current };
    const group = groupMonitorMetrics(['heartRateBpm'])[0]!;
    expect(monitorRasterSelection(input, group, null, null, 101).tails).toEqual([]);
    input.latest = { heartRateBpm: { ...old } };
    expect(monitorRasterSelection(input, group, null, null, 101).tails).toEqual([
      { id: 'heartRateBpm', start: 100, end: 101, value: 120 },
    ]);
    input.monotonicAt = 1006;
    expect(monitorRasterSelection(input, group, null, null, 101).tails).toEqual([]);
  });

  it('preserves explicit coarse continuity, original gaps, invalid boundaries, step mode and singleton points', () => {
    const input = data({
      assistLevel: [
        point(100, 1, true),
        point(200, 2, false),
        point(201, 3, true),
        point(202, NaN),
        point(203, 2, false),
      ],
    });
    const group = groupMonitorMetrics(['assistLevel'])[0]!;
    const scene = monitorRasterScene(input, group, monitorScale(group.metrics, input), 3);
    expect(scene).toMatchObject({ start: 100, end: 700, laneCount: 3 });
    expect(scene.series[0]).toMatchObject({
      step: true,
      points: [
        [100, 1, true],
        [200, 2, false],
        [201, 3, true],
        [203, 2, true],
      ],
    });
  });
  it('unions delayed exact statistics and newer displayed peaks so neither is clipped', () => {
    const input = data({ batteryVoltageV: [point(100, 48), point(101, 60)] });
    input.statistics.batteryVoltageV = { count: 2, min: point(100, 46), max: point(101, 49) };
    const group = groupMonitorMetrics(['batteryVoltageV'])[0]!;
    const scale = monitorScale(group.metrics, input);
    expect(scale.min).toBeLessThan(46);
    expect(scale.max).toBeGreaterThan(60);
  });
  it('keeps original exact selection times and keeps display-only tails out of base geometry', () => {
    const input = data({ batteryVoltageV: [point(100, 48), point(101, 47)] });
    const group = groupMonitorMetrics(['batteryVoltageV'])[0]!;
    input.latest = { batteryVoltageV: input.series.batteryVoltageV!.at(-1)! };
    input.monotonicAt = 105;
    input.liveAcquiredAt = { batteryVoltageV: 101 };
    input.selectionSeconds = 100.6;
    input.selection = { batteryVoltageV: point(100.625, 47.12) };
    input.referenceSeconds = 100;
    input.reference = { batteryVoltageV: point(100, 48) };
    const before = monitorRasterScene(input, group, monitorScale(group.metrics, input), 1);
    expect(monitorRasterSelection(input, group, 100.6, 100, 105)).toMatchObject({
      cursorSeconds: 100.6,
      points: [{ seconds: 100.625, value: 47.12 }],
      references: [{ seconds: 100, value: 48 }],
      tails: [{ start: 101, end: 105, value: 47 }],
    });
    expect(monitorRasterSelection(input, group, null, null, 107).tails).toEqual([]);
    input.monotonicAt = 107;
    expect(monitorRasterSelection(input, group, null, null, 105).tails).toEqual([]);
    delete input.liveAcquiredAt;
    expect(monitorRasterSelection(input, group, null, null, 105).tails).toEqual([]);
    expect(monitorRasterScene(input, group, monitorScale(group.metrics, input), 1)).toEqual(before);
  });
});

for (const row of inspectionFixtures('plot')) {
  it(`runs reduced original geometry fixture ${row.id}`, async () => {
    const monitor = new TelemetryMonitor(
      row.id,
      false,
      row.points.map((p, i) => fixtureSample(p, row.metric, i)),
    );
    const description = await monitor.describeSource({ generation: 1 });
    const plot = await monitor.readPlot({
      generation: 1,
      expectedRevision: description.revision,
      metrics: [row.metric],
      buckets: 1,
      startSeconds: Number(row.startUs ?? 0) / 1e6,
      endSeconds: Number(row.endUs ?? Math.max(...row.points.map(p => Number(p.timeUs)))) / 1e6,
    });
    if (plot.status !== 'ok') throw new Error('Unexpected retry');
    const input = { ...description, ...plot, statistics: {} },
      group = groupMonitorMetrics([row.metric])[0]!;
    const scene = monitorRasterScene(input, group, monitorScale(group.metrics, input), 1);
    expect(scene.revision).toBe(description.revision);
    expect(scene.series[0]?.points.map(p => p[0])).toEqual(
      (row.expected as string[]).map(id => Number(row.points.find(p => p.id === id)!.timeUs) / 1e6),
    );
    expect(scene.series[0]?.points.map(p => p[2])).toEqual(
      row.segments!.map((segment, i) => i === 0 || segment !== row.segments![i - 1]),
    );
  });
}
