import { describe, expect, it } from 'vitest';
import type { MetricId } from '../../../modules/power-log';
import {
  admitHitScene,
  buildHitScene,
  hitChart,
  hitTargets,
  type HitPoint,
} from '../../../src/components/charts/hit-test';
import type { ChartSeries } from '../../../src/features/charts/chart-feed-model';

type Point = HitPoint;
const point = (seconds: number, value: number, id = String(value)): Point => ({
  elapsedSeconds: seconds,
  value,
  observationId: id,
});
const domain = { start: 0, end: 28800 };
const chartSeries = (points: Point[]): ChartSeries => ({
  atUs: Float64Array.from(points, item => Number(item.observationId)),
  atS: Float64Array.from(points, item => item.elapsedSeconds),
  value: Float64Array.from(points, item => item.value),
  starts: Uint8Array.from(points, item => (item.startsSegment ? 1 : 0)),
});
const metric = (id: MetricId, factor = 1) => ({ id, factor, offset: 0 });
const build = (
  series: Partial<Record<MetricId, Point[]>>,
  metrics: { id: MetricId; factor: number; offset: number }[],
  max: number,
  key: string,
) =>
  buildHitScene({
    key,
    sourceId: 'synthetic',
    revision: '1',
    height: 134,
    scale: { min: 0, max },
    metrics,
    series: Object.fromEntries(Object.entries(series).map(([id, points]) => [id, chartSeries(points!)])),
  });
const scene = (points: Point[], key = 'accepted:1') =>
  build({ humanPowerW: points }, [metric('humanPowerW')], 100, key);
const samePoint = (actual: Point | undefined, expected: Point) =>
  expect(actual && { ...actual, observationId: undefined }).toEqual({ ...expected, observationId: undefined });
describe('original vertex acquisition', () => {
  it('moves every metric and every chart at the shared cursor while preserving a touched original peak', () => {
    const power = build(
      {
        humanPowerW: [point(10, 0, '1'), point(20, 0, '2'), point(30, 0, '3')],
        motorInputPowerW: [point(10, 100), point(20, 200), point(20, 900), point(30, 300)],
      },
      [metric('humanPowerW'), metric('motorInputPowerW')],
      1000,
      'power',
    );
    const snap = hitChart(power, { start: 0, end: 40 }, 400, 208, 18, null)!;
    expect(snap.point.value).toBe(900);
    expect(hitTargets(power, 20, snap).map(point => [point.id, point.atS, point.value])).toEqual([
      ['humanPowerW', 20, 0],
      ['motorInputPowerW', 20, 900],
    ]);
    expect(hitTargets(power, 29, snap).map(point => point.atS)).toEqual([30, 30]);
    const other = scene([point(10, 10), point(20, 20), point(30, 30)], 'another-chart');
    expect(hitTargets(other, 29, snap).map(point => [point.atS, point.value])).toEqual([[30, 30]]);
  });
  it('clears only the missing metric across a gap and never extends an endpoint or a cleared cursor', () => {
    const hits = build(
      {
        humanPowerW: [point(10, 0, '1'), { ...point(30, 0, '3'), startsSegment: true }],
        motorInputPowerW: [point(10, 100), point(20, 200), point(30, 300)],
      },
      [metric('humanPowerW'), metric('motorInputPowerW')],
      1000,
      'power',
    );
    expect(hitTargets(hits, 20, null).map(point => point.id)).toEqual(['motorInputPowerW']);
    expect(hitTargets(hits, 31, null)).toEqual([]);
    expect(hitTargets(hits, null, null)).toEqual([]);
  });
  it('inspects by time away from a line but never jumps across a segment gap or beyond its samples', () => {
    const points = [point(10, 70), point(20, 75), { ...point(30, 80), startsSegment: true }, point(40, 85)];
    const hits = scene(points);
    const view = { start: 0, end: 50 };
    const at = (seconds: number) => hitChart(hits, view, 300, 8 + seconds * 6, 130, null);
    samePoint(at(16)?.point, points[1]!);
    samePoint(at(36)?.point, points[3]!);
    expect(at(25)).toBeNull();
    expect(at(5)).toBeNull();
    expect(at(45)).toBeNull();
  });
  it('selects a subpixel peak or valley by its visible position without always preferring the maximum', () => {
    const original = point(14400.125, 100),
      valley = point(14400.25, 0);
    const input = scene([point(14400, 50), original, valley]);
    samePoint(hitChart(input, domain, 300, 158, 8, null)?.point, original);
    samePoint(hitChart(input, domain, 300, 158, 108, null)?.point, valley);
    expect(hitChart(input, domain, 300, 158, 58, null)?.point.value).toBe(50);
    expect(hitChart(input, domain, 300, 158, 8, null)?.point.observationId).toBe('100');
  });
  it('preserves different originals at the same time and releases hysteresis for a clearly closer value', () => {
    const input = scene([point(14400, 30), point(14400, 50)]);
    const low = hitChart(input, domain, 300, 158, 78, null)!;
    expect(low.point.value).toBe(30);
    expect(hitChart(input, domain, 300, 158, 65, low)?.point.value).toBe(30);
    expect(hitChart(input, domain, 300, 158, 58, low)?.point.value).toBe(50);
    expect(hitChart(input, domain, 300, 204, 78, low)).toBeNull();
    expect(hitChart(scene([point(14400, 50)], 'accepted:2'), domain, 300, 158, 78, low)?.point.value).toBe(50);
  });
  it('does not synthesize candidates inside gaps, display tails, or outside the viewport', () => {
    const input = scene([point(100, 50), { ...point(28700, 50), startsSegment: true }]);
    expect(hitChart(input, domain, 300, 158, 58, null)).toBeNull();
    expect(hitChart(input, { start: 500, end: 28800 }, 300, 8, 58, null)).toBeNull();
    expect(hitChart(input, domain, 300, NaN, 58, null)).toBeNull();
  });
  it('converts display units once while retaining raw original value and timestamp', () => {
    const hits = build({ speedMps: [point(14400, 10)] }, [metric('speedMps', 3.6)], 72, 'speed');
    expect(hitChart(hits, domain, 300, 158, 58, null)?.point.value).toBe(10);
    expect(hitTargets(hits, 14400, null)[0]?.value).toBe(36);
  });
  it('requires a current native acceptance identity, layout, source and scene key', () => {
    const expected = { key: 'logical', width: 354, height: 134, displayScale: 3, hits: scene([point(14400, 100)]) };
    const ready = {
      key: 'logical',
      width: 354,
      height: 134,
      displayScale: 3,
      sourceId: 'synthetic',
      acceptanceId: 'viewA:2',
    };
    expect(admitHitScene(expected, ready)?.key).toBe('viewA:2');
    expect(admitHitScene({ ...expected, width: 354.125 }, ready)?.key).toBe('viewA:2');
    expect(admitHitScene({ ...expected, width: 354.3 }, ready)).toBeNull();
    for (const change of [
      { key: 'old' },
      { sourceId: 'other' },
      { width: 355 },
      { height: 135 },
      { displayScale: 2 },
      { acceptanceId: undefined },
    ])
      expect(admitHitScene(expected, { ...ready, ...change })).toBeNull();
  });
});

describe('native accepted scene ordering', () => {
  it('binds an older requested but newly accepted bitmap and rejects delayed acceptance rollback', async () => {
    const { HitAdmission } = await import('../../../src/components/charts/hit-test');
    const admission = new HitAdmission();
    const packet = (key: string) => ({
      key,
      width: 354,
      height: 134,
      displayScale: 3,
      hits: scene([point(14400, 100)], key),
    });
    const ready = (key: string, generation: number) => ({
      key,
      width: 354,
      height: 134,
      displayScale: 3,
      sourceId: 'synthetic',
      acceptanceId: `view:${generation}`,
      viewId: 'view',
      acceptanceGeneration: generation,
    });
    admission.request(packet('P0'), 'layout');
    expect(admission.accept(ready('P0', 1))?.scene?.key).toBe('view:1');
    admission.request(packet('P1'), 'layout');
    admission.request(packet('P2'), 'layout');
    expect(admission.accept(ready('P1', 2))?.scene?.key).toBe('view:2');
    expect(admission.accept(ready('P0', 1))).toBeNull();
    expect(admission.accept(ready('P2', 3))?.scene?.key).toBe('view:3');
    admission.request(packet('P3'), 'layout');
    admission.request(packet('P4'), 'layout');
    expect(admission.accept(ready('P2', 4))?.scene).toBeNull(); // evicted packet never leaves stale targets
    admission.request(packet('P5'), 'new-layout');
    expect(admission.accept(ready('P4', 5))?.scene).toBeNull();
  });

  it('drops a ready from another view instance, leaving the hit scene unadmitted', async () => {
    const { HitAdmission } = await import('../../../src/components/charts/hit-test');
    const admission = new HitAdmission();
    const packet = { key: 'P0', width: 354, height: 134, displayScale: 3, hits: scene([point(14400, 100)], 'P0') };
    const ready = (viewId: string, generation: number) => ({
      key: 'P0',
      width: 354,
      height: 134,
      displayScale: 3,
      sourceId: 'synthetic',
      acceptanceId: `${viewId}:${generation}`,
      viewId,
      acceptanceGeneration: generation,
    });
    admission.request(packet, 'layout');
    expect(admission.accept(ready('view-a', 1))?.scene?.key).toBe('view-a:1');
    expect(admission.accept(ready('view-b', 2))).toBeNull();
    expect(admission.accept(ready('view-a', 1))).toBeNull();
  });
});
