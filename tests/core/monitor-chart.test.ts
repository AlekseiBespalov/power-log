import { describe, expect, it } from 'vitest';
import { metricById, type MonitorData, type MonitorPoint } from '../../src/core/monitor';
import { groupMonitorMetrics, reorderMonitorCharts, monitorDisplayDomain, monitorElapsed, monitorLineGeometry, monitorScale, monitorSelection, monitorTailSeconds, monitorViewportTransform, nearestMonitorPoint, stepMonitorCursor } from '../../src/core/monitor-chart';
import { monitorRasterScene } from '../../src/core/monitor-raster';

const point = (elapsedSeconds: number, value: number, startsSegment?: boolean): MonitorPoint => ({
  elapsedSeconds, value, timestamp: new Date(Date.UTC(2026, 0, 1) + elapsedSeconds * 1000).toISOString(),
  ...(startsSegment === undefined ? {} : { startsSegment }),
});
const metric = (id: string) => metricById(id)!;
const data = (series: MonitorData['series'] = {}): MonitorData => ({ sourceId: 'synthetic', startedAt: '2026-01-01T00:00:00.000Z', domain: { start: 0, end: 100 }, series, statistics: {} });
const identity = (value: number) => value;

describe('monitor plot geometry', () => {
  it.each(['km/h', 'mph'] as const)('overlays GPS and controller speed in %s without combining their samples or raw fallback', unit => {
    const groups = groupMonitorMetrics(['speedMps', 'controllerSpeedMps', 'speedRaw'], unit);
    expect(groups.map(group => [group.id, group.unit])).toEqual([['speed', unit], ['speedRaw', 'raw']]);
    const speed = groups[0]!;
    expect(speed.label).toBe('Speed');
    expect(speed.metrics.map(metric => metric.shortLabel)).toEqual(['GPS', 'Controller']);
    expect(speed.metrics[0]!.color).not.toBe(speed.metrics[1]!.color);
    expect(speed.metrics.every(metric => metric.unit === unit && metric.scale === speed.metrics[0]!.scale)).toBe(true);
    const source = data({ speedMps: [point(0, 10), point(1, 11)], controllerSpeedMps: [point(0.125, 8), point(0.25, 9)] });
    const scene = monitorRasterScene(source, speed, monitorScale(speed.metrics, source), 1);
    expect(scene.series).toHaveLength(2);
    expect(scene.series[0]!.points[0]!.slice(0, 2)).toEqual([0, 10 * speed.metrics[0]!.scale!]);
    expect(scene.series[1]!.points[0]!.slice(0, 2)).toEqual([0.125, 8 * speed.metrics[1]!.scale!]);
  });
  it('moves both speed sources together and keeps the unknown-unit lane independently reorderable', () => {
    expect(reorderMonitorCharts(['speedMps', 'humanPowerW', 'controllerSpeedMps', 'speedRaw'], 'speed', 'speedRaw'))
      .toEqual(['humanPowerW', 'speedRaw', 'speedMps', 'controllerSpeedMps']);
  });
  it('moves a whole chart without losing overlaid metrics or changing their relative order', () => {
    const ids = Object.freeze(['humanPowerW', 'cadenceRpm', 'motorInputPowerW', 'batteryVoltageV']);
    expect(reorderMonitorCharts(ids, 'power', 'batteryVoltageV')).toEqual(['cadenceRpm', 'batteryVoltageV', 'humanPowerW', 'motorInputPowerW']);
    expect(reorderMonitorCharts(ids, 'batteryVoltageV', 'power')).toEqual(['batteryVoltageV', 'humanPowerW', 'motorInputPowerW', 'cadenceRpm']);
    expect(reorderMonitorCharts(ids, 'unknown', 'power')).toEqual(ids);
    expect(reorderMonitorCharts(ids, 'power', 'power')).toEqual(ids);
  });
  it('keeps retained paths aligned with exact cursors through eight-hour pan/zoom and a detail swap', () => {
    const left = 44, width = 340;
    for (const geometry of [{ start: 0, end: 28800 }, { start: 12500, end: 16000 }]) {
      for (const view of [{ start: 100, end: 28000 }, { start: 13000, end: 14000 }, { start: 13201, end: 13202 }]) {
        const preview = monitorViewportTransform(geometry, view, left, width);
        for (const time of [view.start, (view.start + view.end) / 2, view.end]) {
          const retainedX = left + (time - geometry.start) / (geometry.end - geometry.start) * width;
          const cursorX = left + (time - view.start) / (view.end - view.start) * width;
          expect(retainedX * preview.scale + preview.offset).toBeCloseTo(cursorX, 6);
          const detail = monitorViewportTransform(view, view, left, width);
          expect(cursorX * detail.scale + detail.offset).toBeCloseTo(cursorX, 6);
        }
      }
    }
  });
  it('groups compatible metrics while separating different RPM, voltage, altitude and status semantics', () => {
    const groups = groupMonitorMetrics(['cadenceRpm', 'motorRpm', 'batteryVoltageV', 'throttleVoltageV', 'assistLevel', 'raceMode', 'faultCode', 'motorTempC', 'controllerTempC', 'humanPowerW', 'motorInputPowerW', 'altitudeMeters', 'horizontalAccuracyM', 'verticalAccuracyM', 'humanPowerW', 'unknown']);
    expect(groups.map(group => group.metrics.map(item => item.id))).toEqual([
      ['cadenceRpm'], ['motorRpm'], ['batteryVoltageV'], ['throttleVoltageV'], ['assistLevel'], ['raceMode'], ['faultCode'],
      ['motorTempC', 'controllerTempC'], ['humanPowerW', 'motorInputPowerW'], ['altitudeMeters'], ['horizontalAccuracyM', 'verticalAccuracyM'],
    ]);
  });

  it('trusts explicit continuity across coarse vertices and explicit breaks even at close timestamps', () => {
    const points = [point(0, 40, true), point(40, 45, false), point(40.1, 42, true), point(80, 41, false)];
    expect(monitorLineGeometry(points, metric('batteryVoltageV'), identity, identity).path)
      .toBe('M0.00,40.00 L40.00,45.00 M40.10,42.00 L80.00,41.00');
    expect(nearestMonitorPoint(points, 20, 6)).toBe(points[0]);
    expect(nearestMonitorPoint(points, 40.05, 6)).toBeNull();
    expect(nearestMonitorPoint(points, 40.1, 6)).toBe(points[2]);
  });

  it('infers six-second gaps only when a backend marker is absent', () => {
    const points = [point(0, 0), point(5.999, 1), point(11.999, 2), point(12, 3)];
    expect(monitorLineGeometry(points, metric('humanPowerW'), identity, identity).path)
      .toBe('M0.00,0.00 L6.00,1.00 M12.00,2.00 L12.00,3.00');
    expect(nearestMonitorPoint(points, 8, 6)).toBeNull();
    expect(nearestMonitorPoint(points, 2, 6)).toBe(points[0]);
  });

  it('breaks invalid and reversed input without bridging it; renders isolated valid observations', () => {
    const points = [point(0, -2), point(1, NaN), point(2, 3, false), point(1, 4, false), point(3, 5, false)];
    const geometry = monitorLineGeometry(points, metric('batteryCurrentA'), identity, identity);
    expect(geometry.path).toBe('M0.00,-2.00 M2.00,3.00 M1.00,4.00 L3.00,5.00');
    expect(geometry.isolated).toEqual([points[0], points[2]]);
    expect(monitorLineGeometry([point(0, 2)], metric('humanPowerW'), () => Infinity, identity).path).toBe('');
  });

  it('uses straight steps for categories and preserves signed data and converted speed', () => {
    expect(monitorLineGeometry([point(0, 1), point(1, 3)], metric('assistLevel'), identity, identity).path).toBe('M0.00,1.00 H1.00 V3.00');
    expect(monitorLineGeometry([point(0, 10), point(1, -2)], metric('speedMps'), identity, identity).path).toBe('M0.00,36.00 L1.00,-7.20');
  });

  it('uses authoritative extrema omitted by decimation, independent of cursor/reference values', () => {
    const input = data({ humanPowerW: [point(0, 10), point(10, 20)] });
    input.statistics.humanPowerW = { count: 400, min: point(0, -30), max: point(3, 900) };
    const result = monitorScale([metric('humanPowerW')], input);
    expect(result.min).toBeLessThan(-30); expect(result.max).toBeGreaterThan(900);
    input.selection = { humanPowerW: point(3, 2000) }; input.selectionSeconds = 3;
    expect(monitorScale([metric('humanPowerW')], input)).toEqual(result);
  });

  it('keeps voltage scales tight and nonzero for narrow and completely flat data', () => {
    for (const values of [[48.72, 48.78], [48.75, 48.75]]) {
      const input = data({ batteryVoltageV: values.map((value, index) => point(index, value)) });
      const scale = monitorScale([metric('batteryVoltageV')], input);
      expect(scale.min).toBeGreaterThan(48.7); expect(scale.min).toBeLessThanOrEqual(Math.min(...values));
      expect(scale.max).toBeLessThan(48.8); expect(scale.max).toBeGreaterThan(Math.max(...values));
      expect(scale.max).toBeGreaterThan(scale.min); expect(scale.decimals).toBe(2);
    }
    const signed = monitorScale([metric('batteryCurrentA')], data({ batteryCurrentA: [point(0, -4), point(1, 2)] }));
    expect(signed.min).toBeLessThan(-4); expect(signed.max).toBeGreaterThan(2);
    const zero = monitorScale([metric('humanPowerW')], data({ humanPowerW: [point(0, 0), point(1, 0)] }));
    expect(zero.min).toBe(0); expect(zero.max).toBeGreaterThan(0);
  });

  it('scales overlay values in display units and ignores nonfinite readings', () => {
    const speed = monitorScale([metric('speedMps')], data({ speedMps: [point(0, 10), point(1, Infinity)] }));
    expect(speed.min).toBe(0); expect(speed.max).toBeGreaterThan(36);
    expect(monitorScale([metric('batteryVoltageV')], data()).max).toBe(1);
  });
});

describe('monitor inspection and comparison', () => {
  it('prefers matching exact source observations and authoritative null over coarse fallback', () => {
    const input = data({ batteryVoltageV: [point(0, 48, true), point(10, 47, false)] });
    const exact = point(4.875, 47.82);
    input.selectionSeconds = 5; input.selection = { batteryVoltageV: exact };
    expect(monitorSelection(input, metric('batteryVoltageV'), 5)).toBe(exact);
    input.selection.batteryVoltageV = null;
    expect(monitorSelection(input, metric('batteryVoltageV'), 5)).toBeNull();
    expect(monitorSelection(input, metric('batteryVoltageV'), 4)).toBe(input.series.batteryVoltageV![0]);
  });

  it('ignores stale or untagged exact results and resolves reference with its independent tag', () => {
    const points = [point(0, 48), point(2, 47.8)]; const input = data({ batteryVoltageV: points });
    input.selectionSeconds = 1; input.selection = { batteryVoltageV: point(1, 47.9) };
    input.referenceSeconds = 0.5; input.reference = { batteryVoltageV: point(0.49, 47.99) };
    expect(monitorSelection(input, metric('batteryVoltageV'), 0.5, 'reference')).toBe(input.reference.batteryVoltageV);
    expect(monitorSelection(input, metric('batteryVoltageV'), 0.5)).toBe(points[0]);
    delete input.selectionSeconds;
    expect(monitorSelection(input, metric('batteryVoltageV'), 1)).toBe(points[0]);
  });

  it('does not borrow exact or neighboring observations from outside the recording domain', () => {
    const input = data({ batteryVoltageV: [point(-0.1, 48, true), point(1, 47, false), point(101, 46, false)] });
    expect(monitorSelection(input, metric('batteryVoltageV'), 0)).toBeNull();
    input.selectionSeconds = -0.1; input.selection = { batteryVoltageV: input.series.batteryVoltageV![0]! };
    expect(monitorSelection(input, metric('batteryVoltageV'), -0.1)).toBeNull();
    input.selectionSeconds = 0;
    expect(monitorSelection(input, metric('batteryVoltageV'), 0)).toBeNull();
  });

  it('formats original observation times without losing their precision', () => {
    const a = point(1.125, 48.83); const b = point(3.875, 47.21);
    expect(monitorElapsed(a.elapsedSeconds)).toBe('00:01.125');
    expect(monitorElapsed(b.elapsedSeconds)).toBe('00:03.875');
    expect(monitorElapsed(3599.9996)).toBe('1:00:00.000');
    expect(a.timestamp).toBe('2026-01-01T00:00:01.125Z');
  });

  it('never selects outside recorded time or in a gap, including a live display-only tail', () => {
    const points = [point(1, 20), point(2, 30), point(10, 80, true)];
    expect(nearestMonitorPoint(points, 0, 6)).toBeNull();
    expect(nearestMonitorPoint(points, 2.1, 6)).toBeNull();
    expect(nearestMonitorPoint(points, 10.1, 6)).toBeNull();
    expect(monitorTailSeconds(points[2], 15.999, 15)).toBe(15.999);
    expect(monitorTailSeconds(points[2], 16, 15)).toBeNull();
    expect(monitorTailSeconds(points[2], 9, 6)).toBeNull();
    expect(monitorTailSeconds(points[2], Infinity, 6)).toBeNull();
    expect(nearestMonitorPoint(points, 15, 6)).toBeNull();
    expect(points).toHaveLength(3);
  });

  it('absorbs sub-microsecond pointer roundoff at a gap edge without filling the gap', () => {
    const points = [point(0, 0), point(2, 40), point(8, 160, true)];
    expect(nearestMonitorPoint(points, 2 + 1e-12, 6)).toBe(points[1]);
    expect(nearestMonitorPoint(points, 8 - 1e-12, 6)).toBe(points[2]);
    expect(nearestMonitorPoint(points, 2.00001, 6)).toBeNull();
    expect(nearestMonitorPoint(points, 7.99999, 6)).toBeNull();
  });

  it('advances only the live time axis without extending historical selection or source data', () => {
    const input = data({ humanPowerW: [point(0, 10), point(100, 20)] });
    expect(monitorDisplayDomain(input, true, 110)).toEqual({ start: 0, end: 110 });
    expect(monitorDisplayDomain(input, false, 110)).toEqual(input.domain);
    expect(monitorDisplayDomain(input, true, NaN)).toEqual(input.domain);
    expect(input.domain.end).toBe(100);
    expect(monitorSelection(input, metric('humanPowerW'), 105)).toBeNull();
    expect(monitorTailSeconds(input.series.humanPowerW![1], 110, 6)).toBeNull();
  });

  it('keyboard inspection starts within the viewport and can advance to available points beyond it', () => {
    const input = data({ humanPowerW: [point(0, 0), point(1, 10), point(2, 20)], motorInputPowerW: [point(0.5, 50), point(1.5, 60)] });
    const metrics = [metric('humanPowerW'), metric('motorInputPowerW')]; const view = { start: 0.5, end: 1.5 };
    expect(stepMonitorCursor(input, metrics, null, 1, view)).toBe(0.5);
    expect(stepMonitorCursor(input, metrics, null, -1, view)).toBe(1.5);
    expect(stepMonitorCursor(input, metrics, 0.5, 1, view)).toBe(1);
    expect(stepMonitorCursor(input, metrics, 1.5, 1, view)).toBe(2);
    expect(stepMonitorCursor(input, metrics, 2, 1, view)).toBe(2);
    expect(stepMonitorCursor(input, metrics, 1, -1, view)).toBe(0.5);
  });

  it('keyboard inspection can escape a viewport containing only a recording gap', () => {
    const input = data({ humanPowerW: [point(0, 0), point(2, 40), point(8, 160, true), point(10, 200)] });
    const view = { start: 2.5, end: 7.5 };
    expect(stepMonitorCursor(input, [metric('humanPowerW')], null, 1, view)).toBe(8);
    expect(stepMonitorCursor(input, [metric('humanPowerW')], null, -1, view)).toBe(2);
  });
});
