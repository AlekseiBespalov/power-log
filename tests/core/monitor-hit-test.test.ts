import { describe, expect, it } from 'vitest';
import { admitMonitorHitScene, hitMonitorScene, monitorHitScene } from '../../src/core/monitor-hit-test';
import { groupMonitorMetrics } from '../../src/core/monitor-chart';
import type { MonitorData, MonitorPoint } from '../../src/core/monitor';
const point = (seconds: number, value: number, id = String(value)): MonitorPoint => ({ elapsedSeconds: seconds, value, observationId: id, timestamp: '2026-01-01T00:00:01.000001Z', exactValue: String(value) });
const group = groupMonitorMetrics(['humanPowerW'])[0]!;
const domain = { start: 0, end: 28800 };
const data = (points: MonitorPoint[]): MonitorData => ({ sourceId: 'synthetic', revision: '1', domain, startedAt: '2026-01-01T00:00:00Z', series: { humanPowerW: points }, statistics: {} });
const scene = (points: MonitorPoint[], key = 'accepted:1') => monitorHitScene(data(points), group, { min: 0, max: 100, decimals: 0 }, 134, key);
describe('original vertex acquisition', () => {
  it('cannot snap to a clipped predecessor exposed only by preview pan before a new bitmap arrives', () => {
    const input = data([point(99, 90), point(110, 30), point(190, 40), point(201, 95)]);
    input.plotViewport = { start: 100, end: 200 };
    const hits = monitorHitScene(input, group, { min: 0, max: 100, decimals: 0 }, 134, 'coverage');
    expect(hits.series[0]!.points.map(point => point.elapsedSeconds)).toEqual([110, 190]);
    expect(hitMonitorScene(hits, { start: 90, end: 190 }, 300, 35, 18, null)).toBeNull();
  });

  it('selects a subpixel peak or valley by its visible position without always preferring the maximum', () => {
    const original = point(14400.125, 100), valley = point(14400.25, 0);
    const input = scene([point(14400, 50), original, valley]);
    expect(hitMonitorScene(input, domain, 300, 158, 8, null)?.point).toBe(original);
    expect(hitMonitorScene(input, domain, 300, 158, 108, null)?.point).toBe(valley);
    expect(hitMonitorScene(input, domain, 300, 158, 58, null)?.point.value).toBe(50);
    expect(hitMonitorScene(input, domain, 300, 158, 8, null)?.point.timestamp).toBe(original.timestamp);
    expect(hitMonitorScene(input, domain, 300, 158, 8, null)?.point.exactValue).toBe('100');
  });
  it('preserves different originals at the same time and releases hysteresis for a clearly closer value', () => {
    const input = scene([point(14400, 30), point(14400, 50)]);
    const low = hitMonitorScene(input, domain, 300, 158, 78, null)!;
    expect(low.point.value).toBe(30);
    expect(hitMonitorScene(input, domain, 300, 158, 65, low)?.point.value).toBe(30);
    expect(hitMonitorScene(input, domain, 300, 158, 58, low)?.point.value).toBe(50);
    expect(hitMonitorScene(input, domain, 300, 204, 78, low)).toBeNull();
    expect(hitMonitorScene(scene([point(14400, 50)], 'accepted:2'), domain, 300, 158, 78, low)?.point.value).toBe(50);
  });
  it('does not synthesize candidates inside gaps, display tails, or outside the viewport', () => {
    const input = scene([point(100, 50), { ...point(28700, 50), startsSegment: true }]);
    expect(hitMonitorScene(input, domain, 300, 158, 58, null)).toBeNull();
    expect(hitMonitorScene(input, { start: 500, end: 28800 }, 300, 8, 58, null)).toBeNull();
    expect(hitMonitorScene(input, domain, 300, NaN, 58, null)).toBeNull();
  });
  it('converts display units once while retaining raw original value and timestamp', () => {
    const speedGroup = groupMonitorMetrics(['speedMps'])[0]!;
    const input = data([]); input.series.speedMps = [point(14400, 10)];
    const hits = monitorHitScene(input, speedGroup, { min: 0, max: 72, decimals: 0 }, 134, 'speed');
    expect(hitMonitorScene(hits, domain, 300, 158, 58, null)?.point.value).toBe(10);
  });
  it('requires a current native acceptance identity, layout, source and scene key', () => {
    const expected = { key: 'logical', width: 354, height: 134, displayScale: 3, hits: scene([point(14400, 100)]) };
    const ready = { key: 'logical', width: 354, height: 134, displayScale: 3, sourceId: 'synthetic', acceptanceId: 'viewA:2' };
    expect(admitMonitorHitScene(expected, ready)?.key).toBe('viewA:2');
    expect(admitMonitorHitScene({ ...expected, width: 354.125 }, ready)?.key).toBe('viewA:2');
    expect(admitMonitorHitScene({ ...expected, width: 354.3 }, ready)).toBeNull();
    for (const change of [{ key: 'old' }, { sourceId: 'other' }, { width: 355 }, { height: 135 }, { displayScale: 2 }, { acceptanceId: undefined }]) expect(admitMonitorHitScene(expected, { ...ready, ...change })).toBeNull();
  });
});

describe('native accepted scene ordering', () => {
  it('binds an older requested but newly accepted bitmap and rejects delayed acceptance rollback', async () => {
    const { MonitorHitAdmission } = await import('../../src/core/monitor-hit-test');
    const admission = new MonitorHitAdmission();
    const packet = (key: string) => ({ key, width: 354, height: 134, displayScale: 3, hits: scene([point(14400, 100)], key) });
    const ready = (key: string, generation: number) => ({ key, width: 354, height: 134, displayScale: 3, sourceId: 'synthetic', acceptanceId: `view:${generation}`, viewId: 'view', acceptanceGeneration: generation });
    admission.request(packet('P0'), 'layout');
    expect(admission.accept(ready('P0', 1))?.scene?.key).toBe('view:1');
    admission.request(packet('P1'), 'layout'); admission.request(packet('P2'), 'layout');
    expect(admission.accept(ready('P1', 2))?.scene?.key).toBe('view:2');
    expect(admission.accept(ready('P0', 1))).toBeNull();
    expect(admission.accept(ready('P2', 3))?.scene?.key).toBe('view:3');
    admission.request(packet('P3'), 'layout'); admission.request(packet('P4'), 'layout');
    expect(admission.accept(ready('P2', 4))?.scene).toBeNull(); // evicted packet never leaves stale targets
    admission.request(packet('P5'), 'new-layout');
    expect(admission.accept(ready('P4', 5))?.scene).toBeNull();
  });
});
