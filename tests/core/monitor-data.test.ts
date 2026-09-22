import { describe, expect, it } from 'vitest';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { defaultMonitorPreferences, formatMetric, formatMetricPoint, formatMetricDifference, metricById, validateMonitorPreferences } from '../../src/core/monitor';
import type { MonitorData, MonitorPlotRequest } from '../../src/core/monitor';
import { syntheticSample } from '../fixtures/synthetic-sample';

const sample = (seconds: number, value = seconds) => ({ ...syntheticSample(seconds, seconds, new Date(Date.UTC(2026, 0, 1) + seconds * 1000).toISOString()), humanPowerW: value, batteryVoltageV: 50 - value / 1000 });

async function query(monitor: TelemetryMonitor, request: Partial<MonitorPlotRequest> & { cursorSeconds?: number; referenceSeconds?: number }): Promise<MonitorData> {
  const description = await monitor.describeSource({ generation: 1 });
  const common = { generation: 1, expectedRevision: description.revision, metrics: request.metrics ?? [] };
  const plot = await monitor.readPlot({ ...common, ...request });
  const stats = await monitor.rangeStats({ ...common, startSeconds: request.startSeconds ?? description.domain.start, endSeconds: request.endSeconds ?? description.domain.end });
  const selection = request.cursorSeconds === undefined ? null : await monitor.inspectAt({ ...common, seconds: request.cursorSeconds });
  const reference = request.referenceSeconds === undefined ? null : await monitor.inspectAt({ ...common, seconds: request.referenceSeconds });
  if (plot.status !== 'ok' || stats.status !== 'ok') throw new Error('Unexpected revision change');
  return { ...description, ...plot, statistics: stats.statistics, selection: selection?.status === 'ok' ? selection.points : {}, reference: reference?.status === 'ok' ? reference.points : {}, selectionSeconds: request.cursorSeconds, referenceSeconds: request.referenceSeconds };
}
describe('monitor original observations', () => {
  it('queries beyond two minutes, preserves extrema and selects originals independently of buckets', async () => {
    const samples = Array.from({ length: 3601 }, (_, i) => sample(i, i === 1703 ? 913 : 100));
    const monitor = new TelemetryMonitor('ride', false, samples);
    const result = await query(monitor, { metrics: ['humanPowerW', 'batteryVoltageV'], buckets: 8, cursorSeconds: 1702.9, referenceSeconds: 1699 });
    expect(result.domain.end).toBe(3600);
    expect(result.series.humanPowerW!.length).toBeLessThanOrEqual(32);
    expect(result.series.humanPowerW!.some(point => point.value === 913)).toBe(true);
    expect(result.selection!.humanPowerW).toMatchObject({ elapsedSeconds: 1703, value: 913, timestamp: samples[1703]!.timestamp });
    expect(result.selectionSeconds).toBe(1702.9);
    expect(result.referenceSeconds).toBe(1699);
    expect(result.statistics.batteryVoltageV!.min?.value).toBe(49.087);
    expect(result.statistics.humanPowerW!.count).toBe(3601);
  });
  it('retains genuine gaps after decimation and keeps missing selections unavailable', async () => {
    const monitor = new TelemetryMonitor('gaps', false, [sample(0), sample(1), sample(6.2), sample(7), sample(14), sample(15)]);
    const result = await query(monitor, { metrics: ['humanPowerW', 'heartRateBpm'], buckets: 1, cursorSeconds: 10 });
    expect(result.series.humanPowerW!.filter(point => point.startsSegment).map(point => point.elapsedSeconds)).toEqual([0, 14]);
    expect(result.selection!.humanPowerW).toBeNull();
    expect(result.latest!.heartRateBpm).toBeNull();
    expect(result.series.heartRateBpm).toEqual([]);
  });
  it('computes voltage minimum only in the selected interval; latest stays independent', async () => {
    const monitor = new TelemetryMonitor('range', false, [sample(0, 900), sample(1, 300), sample(2, 100), sample(3, 600)]);
    const result = await query(monitor, { metrics: ['batteryVoltageV'], startSeconds: 1, endSeconds: 2 });
    expect(result.statistics.batteryVoltageV!.min?.value).toBe(49.7);
    expect(result.statistics.batteryVoltageV!.count).toBe(2);
    expect(result.latest!.batteryVoltageV?.value).toBe(49.4);
  });
  it('keeps a foreground connection history across recorder elapsed clock resets without modifying samples', async () => {
    const first = sample(50), second = { ...sample(51), elapsedSeconds: 0 };
    const monitor = new TelemetryMonitor('live', true);
    monitor.append(first); monitor.append(second);
    const result = await query(monitor, { metrics: ['humanPowerW'] });
    expect(result.series.humanPowerW!.map(point => point.elapsedSeconds)).toEqual([0, 1]);
    expect([first.elapsedSeconds, second.elapsedSeconds]).toEqual([50, 0]);
    expect(result.series.humanPowerW![1]!.timestamp).toBe(second.timestamp);
  });
});
describe('monitor snapshot contract', () => {
  it('looks up anchored equal-time originals without falling back when an identity or time is missing', async () => {
    const source = new TelemetryMonitor('identity', false, [sample(0), sample(1, 50), sample(1, 900), sample(2)]);
    const common = { generation: 1, expectedRevision: '4', metrics: ['humanPowerW', 'batteryVoltageV'], seconds: 1 };
    const anchor = { metric: 'humanPowerW', observationId: 'identity:0000000000000002' };
    const result = await source.inspectAt({ ...common, anchor });
    expect(result).toMatchObject({ points: { humanPowerW: { value: 900 }, batteryVoltageV: { value: 49.95 } } });
    expect(await source.inspectAt({ ...common, anchor: { ...anchor, observationId: 'missing' } })).toMatchObject({ points: { humanPowerW: null } });
    expect(await source.inspectAt({ ...common, seconds: 2, anchor })).toMatchObject({ points: { humanPowerW: null } });
    expect(await source.rangeStats({ ...common, startSeconds: 1, endSeconds: 1, includeEndpoints: true, startAnchor: anchor, endAnchor: { ...anchor, observationId: 'identity:0000000000000001' } })).toMatchObject({ endpoints: { start: { humanPowerW: { value: 900 } }, end: { humanPowerW: { value: 50 } } } });
  });

  it('keeps sub-microsecond pointer roundoff at genuine gap endpoints on the original observation', async () => {
    const monitor = new TelemetryMonitor('roundoff', false, [sample(0), sample(1), sample(2, 140), sample(8)]);
    const request = { generation: 1, expectedRevision: '4', metrics: ['humanPowerW'] };
    const result = await monitor.inspectAt({ ...request, seconds: 2.0000000001 });
    if (result.status !== 'ok') throw new Error('unexpected retry');
    expect(result.points.humanPowerW).toMatchObject({ elapsedSeconds: 2, value: 140, timestamp: '2026-01-01T00:00:02.000Z' });
    const gap = await monitor.inspectAt({ ...request, seconds: 2.001 });
    if (gap.status !== 'ok') throw new Error('unexpected retry');
    expect(gap.points.humanPowerW).toBeNull(); expect(gap.gaps.humanPowerW).toBe(true);
  });
  it('rejects an expected-revision mismatch on each exact operation', async () => {
    const monitor = new TelemetryMonitor('ride', false, [sample(0)]);
    const description = await monitor.describeSource({ generation: 2 });
    monitor.append(sample(1));
    const request = { generation: 4, expectedRevision: description.revision, metrics: ['humanPowerW'] };
    for (const result of [await monitor.readPlot(request), await monitor.inspectAt({ ...request, seconds: 0 }), await monitor.rangeStats({ ...request, startSeconds: 0, endSeconds: 1 })]) {
      expect(result).toEqual({ status: 'retry', generation: 4, sourceId: 'ride', revision: '2' });
    }
  });
  it('retains equal-time originals and uses the earliest stable identity for inspection ties', async () => {
    const monitor = new TelemetryMonitor('ties', false, [sample(0, 80), sample(1, 50), sample(1, 90), sample(2, 50)]);
    const request = { generation: 1, expectedRevision: '4', metrics: ['humanPowerW'] };
    const selected = await monitor.inspectAt({ ...request, seconds: 1.5 });
    const stats = await monitor.rangeStats({ ...request, startSeconds: 0, endSeconds: 1 });
    expect(selected.status).toBe('ok'); expect(stats.status).toBe('ok');
    if (selected.status !== 'ok' || stats.status !== 'ok') return;
    expect(selected.points.humanPowerW!.value).toBe(50);
    expect(stats.statistics.humanPowerW!.count).toBe(3);
    expect(stats.statistics.humanPowerW!.max?.value).toBe(90);
    expect(stats.statistics.humanPowerW!.min?.observationId).toBe('ties:0000000000000001');
  });
  it('separates sample means from boundary-refined integration and excludes long gaps', async () => {
    const monitor = new TelemetryMonitor('integral', false, [sample(0, 0), sample(2, 100), sample(3, 10), sample(10, 50)]);
    const result = await monitor.rangeStats({ generation: 1, expectedRevision: '4', metrics: ['humanPowerW'], startSeconds: 1, endSeconds: 10 });
    if (result.status !== 'ok') throw new Error('unexpected retry');
    expect(result.statistics.humanPowerW).toMatchObject({ count: 3, sampleMean: 160 / 3, coveredSeconds: 2, integral: 130 });
  });
  it('returns resetRequired when the bounded change journal cannot prove history', async () => {
    const monitor = new TelemetryMonitor('journal', false);
    for (let i = 0; i < 300; i++) monitor.append(sample(i));
    expect((await monitor.changesSince({ generation: 1, sinceRevision: '0' })).resetRequired).toBe(true);
    expect((await monitor.changesSince({ generation: 1, sinceRevision: '299' })).changes).toMatchObject([{ startSeconds: 298, endSeconds: 299, kind: 'append' }]);
    expect((await monitor.changesSince({ generation: 1, sinceRevision: '9007199254740993' })).resetRequired).toBe(true);
  });
  it('changes source identity on explicit connection replacement, independently of recording clock resets', async () => {
    const monitor = new TelemetryMonitor('foreground', true, [sample(0), sample(1)]);
    const before = await monitor.describeSource({ generation: 1 });
    monitor.beginSession(); monitor.append(sample(50));
    const after = await monitor.describeSource({ generation: 2 });
    expect(after.sourceId).not.toBe(before.sourceId); expect(after.revision).toBe('1'); expect(after.domain.start).toBe(0);
  });
});
describe('monitor metric boundaries', () => {
  it('validates persisted view selections without coupling independent views', () => {
    const input = defaultMonitorPreferences();
    input.views.ride.charts.push('motorTempC', 'unknown', 'motorTempC');
    input.views.battery.numbers = [];
    const result = validateMonitorPreferences(input);
    expect(result.views.ride.charts).toEqual(['humanPowerW', 'motorInputPowerW', 'cadenceRpm', 'motorTempC']);
    expect(result.views.battery.charts).toEqual(['batteryVoltageV', 'batteryCurrentA', 'motorInputPowerW']);
    expect(result.views.battery.numbers).toEqual([]);
    expect(result.views.temperature.range).toBe(600);
  });
  it('renders original integer values and A-B differences beyond Number precision exactly', () => {
    const point = { elapsedSeconds: 1, timestamp: '2026-01-01T00:00:01Z', value: 9007199254740992, exactValue: '9007199254740993' };
    expect(formatMetricPoint(metricById('humanPowerW')!, point)).toBe('9007199254740993');
    expect(formatMetricPoint(metricById('batteryVoltageV')!, point)).toBe('9007199254740993.00');
    expect(formatMetricPoint(metricById('distanceMeters')!, point)).toBe('9007199254740.99');
    expect(formatMetricDifference(metricById('batteryVoltageV')!, point, { ...point, exactValue: '9007199254740994' })).toBe('+1.00');
    expect(formatMetricPoint(metricById('humanPowerW')!, { ...point, exactValue: '-9223372036854775808' })).toBe('-9223372036854775808');
  });
  it('converts only known units and never treats raw controller speed as GPS speed', () => {
    expect(formatMetric(metricById('speedMps')!, 10)).toBe('36.0');
    expect(formatMetric(metricById('distanceMeters')!, 1500)).toBe('1.50');
    expect(metricById('speedRaw')!.unit).toBe('raw');
    expect(formatMetric(metricById('heartRateBpm')!, null)).toBe('—');
  });
});
