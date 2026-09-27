import { describe, expect, it } from 'vitest';
import { MonotonicClock } from '../../src/services/monotonic-clock';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import {
  defaultMonitorPreferences,
  formatMetric,
  formatMetricPoint,
  formatMetricDifference,
  metricById,
  MONITOR_METRICS,
  validateMonitorPreferences,
} from '../../src/core/monitor';
import { SAMPLE_COLUMNS } from '../../src/core/types';
import type { MonitorData, MonitorPlotRequest } from '../../src/core/monitor';
import { syntheticSample } from '../fixtures/synthetic-sample';
import { exportCsv } from '../helpers/export-csv';
import { parseCsv } from '../support/csv';

const sample = (seconds: number, value = seconds) => ({
  ...syntheticSample(seconds, seconds, new Date(Date.UTC(2026, 0, 1) + seconds * 1000).toISOString()),
  humanPowerW: value,
  batteryVoltageV: 50 - value / 1000,
});

async function query(
  monitor: TelemetryMonitor,
  request: Partial<MonitorPlotRequest> & { cursorSeconds?: number; referenceSeconds?: number },
): Promise<MonitorData> {
  const description = await monitor.describeSource({ generation: 1 });
  const common = { generation: 1, expectedRevision: description.revision, metrics: request.metrics ?? [] };
  const plot = await monitor.readPlot({ ...common, ...request });
  const latest = await monitor.readLatest(common);
  const stats = await monitor.rangeStats({
    ...common,
    startSeconds: request.startSeconds ?? description.domain.start,
    endSeconds: request.endSeconds ?? description.domain.end,
  });
  const selection =
    request.cursorSeconds === undefined ? null : await monitor.inspectAt({ ...common, seconds: request.cursorSeconds });
  const reference =
    request.referenceSeconds === undefined
      ? null
      : await monitor.inspectAt({ ...common, seconds: request.referenceSeconds });
  if (plot.status !== 'ok' || stats.status !== 'ok' || latest.status !== 'ok')
    throw new Error('Unexpected revision change');
  return {
    ...description,
    ...plot,
    latest: latest.points,
    statistics: stats.statistics,
    selection: selection?.status === 'ok' ? selection.points : {},
    reference: reference?.status === 'ok' ? reference.points : {},
    selectionSeconds: request.cursorSeconds,
    referenceSeconds: request.referenceSeconds,
  };
}
describe('monitor original observations', () => {
  it('reevaluates retained native acquisition after sleep and during foreground resynchronization', async () => {
    let native = 1000;
    let now = 0;
    const clock = new MonotonicClock(
      async () => native,
      () => now,
    );
    await clock.sync();
    const monitor = new TelemetryMonitor('native-clock', true, [], () => now);
    monitor.append(sample(0, 237), { acquiredAtMonotonic: native, clock });
    now = 1;
    const request = { generation: 1, expectedRevision: '1', metrics: ['humanPowerW'], seconds: 1 };
    expect(await monitor.inspectAt(request)).toMatchObject({ points: { humanPowerW: { value: 237 } } });
    clock.invalidate();
    expect(await monitor.inspectAt(request)).toMatchObject({ points: { humanPowerW: null } });
    native = 4601;
    await clock.sync();
    expect(await monitor.inspectAt(request)).toMatchObject({ points: { humanPowerW: null } });
    const retained = await monitor.readLatest(request);
    expect(retained).toMatchObject({ liveAcquiredAt: { humanPowerW: 1000 }, nowSeconds: 3601, monotonicAt: 4601 });
    expect(monitor.mapMonotonicSeconds(1000)).toBe(-3600);
  });

  it('advances live time and age monotonically without letting replay renew the observation', async () => {
    let now = 100;
    const monitor = new TelemetryMonitor('live-clock', true, [], () => now);
    monitor.append(sample(50), { receivedAtSeconds: now });
    now++;
    const next = { ...sample(51), timestamp: '2025-12-31T23:00:00Z' };
    monitor.append(next, { receivedAtSeconds: now });
    expect(await monitor.describeSource({ generation: 1 })).toMatchObject({ nowSeconds: 1, monotonicAt: 101 });
    now += 6;
    monitor.append(next, { receivedAtSeconds: now });
    expect(await monitor.describeSource({ generation: 2 })).toMatchObject({
      revision: '2',
      nowSeconds: 7,
      monotonicAt: 107,
    });
    const latest = await monitor.readLatest({ generation: 3, metrics: ['humanPowerW'] });
    expect(latest).toMatchObject({
      nowSeconds: 7,
      liveAcquiredAt: { humanPowerW: 101 },
      monotonicAt: 107,
      points: { humanPowerW: { elapsedSeconds: 1, timestamp: next.timestamp } },
    });
    const history = new TelemetryMonitor('csv', false, [next]);
    expect(await history.describeSource({ generation: 1 })).not.toHaveProperty('liveAcquiredAt');
    expect(await history.readLatest({ generation: 2, metrics: ['humanPowerW'] })).not.toHaveProperty('liveAcquiredAt');
    monitor.beginSession();
    expect(await monitor.describeSource({ generation: 4 })).toMatchObject({ monotonicAt: 107, nowSeconds: 0 });
  });
  it('preserves CSV interruptions through cached originals, reduction, exact anchors and range endpoints', async () => {
    const samples = [
      sample(0, 100),
      sample(1, 100),
      { ...sample(2, 200), interruptionIndex: 1, timestamp: '2025-12-31T23:00:00Z' },
      { ...sample(3, 200), interruptionIndex: 1 },
    ];
    const imported = parseCsv(
      exportCsv(samples.map(value => ({ ...value, motorInputPowerW: value.batteryVoltageV * value.batteryCurrentA }))),
    ).samples;
    const monitor = new TelemetryMonitor('interrupted', false, imported.slice(0, 2));
    await query(monitor, { metrics: ['humanPowerW'], buckets: 1 });
    imported.slice(2).forEach(value => monitor.append(value));
    for (let repeat = 0; repeat < 2; repeat++) {
      const result = await query(monitor, { metrics: ['humanPowerW'], buckets: 1, cursorSeconds: 1.5 });
      expect(
        result.series.humanPowerW!.filter(point => point.startsSegment).map(point => point.elapsedSeconds),
      ).toEqual([0, 2]);
      expect(result.selection!.humanPowerW).toBeNull();
      expect(result.statistics.humanPowerW).toMatchObject({ count: 4, coveredSeconds: 2, integral: 300 });
      const boundary = result.series.humanPowerW!.find(point => point.elapsedSeconds === 2)!;
      expect(
        await monitor.inspectAt({
          generation: 1,
          expectedRevision: '4',
          metrics: ['humanPowerW'],
          seconds: 2,
          anchor: { metric: 'humanPowerW', observationId: boundary.observationId! },
        }),
      ).toMatchObject({ points: { humanPowerW: { value: 200, timestamp: samples[2]!.timestamp } } });
    }
    expect(
      await monitor.rangeStats({
        generation: 1,
        expectedRevision: '4',
        metrics: ['humanPowerW'],
        startSeconds: 1.25,
        endSeconds: 1.75,
        includeEndpoints: true,
      }),
    ).toMatchObject({ statistics: {}, endpoints: { start: { humanPowerW: null }, end: { humanPowerW: null } } });
  });
  it.each(['next-epoch', undefined])(
    'keeps a short reconnect visible but excludes integration with epoch %s',
    async connectionEpoch => {
      const monitor = new TelemetryMonitor('reconnect', false, [sample(0), { ...sample(1), connectionEpoch }]);
      const result = await query(monitor, { metrics: ['humanPowerW'], buckets: 1, cursorSeconds: 0.5 });
      expect(result.series.humanPowerW!.filter(point => point.startsSegment)).toHaveLength(1);
      expect(result.selection!.humanPowerW).not.toBeNull();
      expect(result.statistics.humanPowerW).toMatchObject({ count: 2, coveredSeconds: 0, integral: 0 });
    },
  );
  it('does not borrow a metric tail past a hard interruption without a new value for that metric', async () => {
    const monitor = new TelemetryMonitor('tail', true, [
      { ...sample(0), controllerSpeedMps: 5 },
      { ...sample(1), interruptionIndex: 1 },
    ]);
    expect(
      await monitor.inspectAt({ generation: 1, expectedRevision: '2', metrics: ['controllerSpeedMps'], seconds: 1.5 }),
    ).toMatchObject({ points: { controllerSpeedMps: null } });
    monitor.append({ ...sample(2), interruptionIndex: 1, controllerSpeedMps: 6 });
    const result = await query(monitor, { metrics: ['controllerSpeedMps'], buckets: 1, cursorSeconds: 1.5 });
    expect(result.series.controllerSpeedMps!.filter(point => point.startsSegment)).toHaveLength(2);
    expect(result.selection!.controllerSpeedMps).toBeNull();
  });
  it('queries beyond two minutes, preserves extrema and selects originals independently of buckets', async () => {
    const samples = Array.from({ length: 3601 }, (_, i) => sample(i, i === 1703 ? 913 : 100));
    const monitor = new TelemetryMonitor('ride', false, samples);
    const result = await query(monitor, {
      metrics: ['humanPowerW', 'batteryVoltageV'],
      buckets: 8,
      cursorSeconds: 1702.9,
      referenceSeconds: 1699,
    });
    expect(result.domain.end).toBe(3600);
    expect(result.series.humanPowerW!.length).toBeLessThanOrEqual(32);
    expect(result.series.humanPowerW!.some(point => point.value === 913)).toBe(true);
    expect(result.selection!.humanPowerW).toMatchObject({
      elapsedSeconds: 1703,
      value: 913,
      timestamp: samples[1703]!.timestamp,
    });
    expect(result.selectionSeconds).toBe(1702.9);
    expect(result.referenceSeconds).toBe(1699);
    expect(result.statistics.batteryVoltageV!.min?.value).toBe(49.087);
    expect(result.statistics.humanPowerW!.count).toBe(3601);
  });
  it('retains genuine gaps after decimation and keeps missing selections unavailable', async () => {
    const monitor = new TelemetryMonitor('gaps', false, [
      sample(0),
      sample(1),
      sample(6.2),
      sample(7),
      sample(14),
      sample(15),
    ]);
    const result = await query(monitor, { metrics: ['humanPowerW', 'heartRateBpm'], buckets: 1, cursorSeconds: 10 });
    expect(result.series.humanPowerW!.filter(point => point.startsSegment).map(point => point.elapsedSeconds)).toEqual([
      0, 14,
    ]);
    expect(result.selection!.humanPowerW).toBeNull();
    expect(result.latest!.heartRateBpm).toBeNull();
    expect(result.series.heartRateBpm).toEqual([]);
  });
  it('computes voltage minimum only in the selected interval; latest stays independent', async () => {
    const monitor = new TelemetryMonitor('range', false, [
      sample(0, 900),
      sample(1, 300),
      sample(2, 100),
      sample(3, 600),
    ]);
    const result = await query(monitor, { metrics: ['batteryVoltageV'], startSeconds: 1, endSeconds: 2 });
    expect(result.statistics.batteryVoltageV!.min?.value).toBe(49.7);
    expect(result.statistics.batteryVoltageV!.count).toBe(2);
    expect(result.latest!.batteryVoltageV?.value).toBe(49.4);
  });
  it('keeps foreground history ordered by source elapsed across a backward UTC jump without modifying samples', async () => {
    const first = sample(50),
      second = { ...sample(51), timestamp: '2025-12-31T23:00:00Z' };
    const monitor = new TelemetryMonitor('live', true);
    monitor.append(first);
    monitor.append(second);
    const result = await query(monitor, { metrics: ['humanPowerW'] });
    expect(result.series.humanPowerW!.map(point => point.elapsedSeconds)).toEqual([0, 1]);
    expect([first.elapsedSeconds, second.elapsedSeconds]).toEqual([50, 51]);
    expect(result.series.humanPowerW![1]!.timestamp).toBe(second.timestamp);
  });
});
describe.each([
  { interruptionIndex: 0, selectedIndex: 1, selectedWatts: 100, integral: 200 },
  { interruptionIndex: 1, selectedIndex: 2, selectedWatts: 200, integral: 250 },
])(
  'equal-time boundary with interruption index $interruptionIndex',
  ({ interruptionIndex, selectedIndex, selectedWatts, integral }) => {
    const samples = [
      sample(0, 100),
      sample(1, 100),
      { ...sample(1, 200), interruptionIndex },
      { ...sample(2, 300), interruptionIndex },
    ].map((value, sequence) => ({
      ...value,
      sequence,
      timestamp: new Date(Date.UTC(2026, 0, 1) + sequence * 1000).toISOString(),
      motorInputPowerW: value.batteryVoltageV * value.batteryCurrentA,
    }));
    const originals = interruptionIndex ? parseCsv(exportCsv(samples)).samples : samples;
    const common = { generation: 1, expectedRevision: '4', metrics: ['humanPowerW'] };

    it('inspects the selected run and retains exact anchored identities on both sides', async () => {
      const monitor = new TelemetryMonitor('boundary', false, originals);
      for (const seconds of [1.25, 1.5]) {
        expect(await monitor.inspectAt({ ...common, seconds })).toMatchObject({
          points: {
            humanPowerW: {
              elapsedSeconds: 1,
              value: selectedWatts,
              timestamp: originals[selectedIndex]!.timestamp,
              observationId: `boundary:${String(selectedIndex).padStart(16, '0')}`,
            },
          },
        });
      }
      for (const seconds of [0.75, 1]) {
        expect(await monitor.inspectAt({ ...common, seconds })).toMatchObject({
          points: { humanPowerW: { value: 100, observationId: 'boundary:0000000000000001' } },
        });
      }
      for (const index of [1, 2]) {
        const observationId = `boundary:${String(index).padStart(16, '0')}`;
        expect(
          await monitor.inspectAt({
            ...common,
            seconds: 1,
            anchor: { metric: 'humanPowerW', observationId },
          }),
        ).toMatchObject({
          points: { humanPowerW: { value: originals[index]!.humanPowerW, observationId } },
        });
      }
    });

    it('integrates the resumed interval without collapsing different interruption runs', async () => {
      const monitor = new TelemetryMonitor('boundary', false, originals);
      for (const range of [
        { startSeconds: 1, endSeconds: 2, count: 3, coveredSeconds: 1, integral },
        { startSeconds: 0, endSeconds: 2, count: 4, coveredSeconds: 2, integral: integral + 100 },
        { startSeconds: 1.25, endSeconds: 1.75, count: 0, coveredSeconds: 0.5, integral: integral / 2 },
        { startSeconds: 1, endSeconds: 1, count: 2, coveredSeconds: 0, integral: 0 },
      ]) {
        const { startSeconds, endSeconds, ...statistics } = range;
        expect(await monitor.rangeStats({ ...common, startSeconds, endSeconds })).toMatchObject({
          statistics: { humanPowerW: statistics },
        });
      }
    });

    it('keeps both boundary originals and the resumed segment through cached reduction', async () => {
      const monitor = new TelemetryMonitor('boundary', false, originals.slice(0, 2));
      await monitor.readPlot({ ...common, expectedRevision: '2', buckets: 1 });
      originals.slice(2).forEach(value => monitor.append(value));
      for (let repeat = 0; repeat < 2; repeat++) {
        const result = await monitor.readPlot({ ...common, startSeconds: 0, endSeconds: 2, buckets: 1 });
        if (result.status !== 'ok') throw new Error('unexpected retry');
        expect(result.series.humanPowerW).toEqual(
          originals.map((value, index) => ({
            elapsedSeconds: value.elapsedSeconds,
            timestamp: value.timestamp,
            value: value.humanPowerW,
            observationId: `boundary:${String(index).padStart(16, '0')}`,
            startsSegment: index === 0 || index === 2,
          })),
        );
      }
    });
  },
);
describe('monitor snapshot contract', () => {
  it('looks up anchored equal-time originals without falling back when an identity or time is missing', async () => {
    const source = new TelemetryMonitor('identity', false, [sample(0), sample(1, 50), sample(1, 900), sample(2)]);
    const common = { generation: 1, expectedRevision: '4', metrics: ['humanPowerW', 'batteryVoltageV'], seconds: 1 };
    const anchor = { metric: 'humanPowerW', observationId: 'identity:0000000000000002' };
    const result = await source.inspectAt({ ...common, anchor });
    expect(result).toMatchObject({ points: { humanPowerW: { value: 900 }, batteryVoltageV: { value: 49.95 } } });
    expect(await source.inspectAt({ ...common, anchor: { ...anchor, observationId: 'missing' } })).toMatchObject({
      points: { humanPowerW: null },
    });
    expect(await source.inspectAt({ ...common, seconds: 2, anchor })).toMatchObject({ points: { humanPowerW: null } });
    expect(
      await source.rangeStats({
        ...common,
        startSeconds: 1,
        endSeconds: 1,
        includeEndpoints: true,
        startAnchor: anchor,
        endAnchor: { ...anchor, observationId: 'identity:0000000000000001' },
      }),
    ).toMatchObject({ endpoints: { start: { humanPowerW: { value: 900 } }, end: { humanPowerW: { value: 50 } } } });
  });

  it('keeps sub-microsecond pointer roundoff at genuine gap endpoints on the original observation', async () => {
    const monitor = new TelemetryMonitor('roundoff', false, [sample(0), sample(1), sample(2, 140), sample(8)]);
    const request = { generation: 1, expectedRevision: '4', metrics: ['humanPowerW'] };
    const result = await monitor.inspectAt({ ...request, seconds: 2.0000000001 });
    if (result.status !== 'ok') throw new Error('unexpected retry');
    expect(result.points.humanPowerW).toMatchObject({
      elapsedSeconds: 2,
      value: 140,
      timestamp: '2026-01-01T00:00:02.000Z',
    });
    const gap = await monitor.inspectAt({ ...request, seconds: 2.001 });
    if (gap.status !== 'ok') throw new Error('unexpected retry');
    expect(gap.points.humanPowerW).toBeNull();
    expect(gap.gaps.humanPowerW).toBe(true);
  });
  it('rejects an expected-revision mismatch on each exact operation', async () => {
    const monitor = new TelemetryMonitor('ride', false, [sample(0)]);
    const description = await monitor.describeSource({ generation: 2 });
    monitor.append(sample(1));
    const request = { generation: 4, expectedRevision: description.revision, metrics: ['humanPowerW'] };
    for (const result of [
      await monitor.readPlot(request),
      await monitor.inspectAt({ ...request, seconds: 0 }),
      await monitor.rangeStats({ ...request, startSeconds: 0, endSeconds: 1 }),
    ]) {
      expect(result).toEqual({ status: 'retry', generation: 4, sourceId: 'ride', revision: '2' });
    }
  });
  it('retains equal-time originals and uses the earliest stable identity for inspection ties', async () => {
    const monitor = new TelemetryMonitor('ties', false, [sample(0, 80), sample(1, 50), sample(1, 90), sample(2, 50)]);
    const request = { generation: 1, expectedRevision: '4', metrics: ['humanPowerW'] };
    const selected = await monitor.inspectAt({ ...request, seconds: 1.5 });
    const stats = await monitor.rangeStats({ ...request, startSeconds: 0, endSeconds: 1 });
    expect(selected.status).toBe('ok');
    expect(stats.status).toBe('ok');
    if (selected.status !== 'ok' || stats.status !== 'ok') return;
    expect(selected.points.humanPowerW!.value).toBe(50);
    expect(stats.statistics.humanPowerW!.count).toBe(3);
    expect(stats.statistics.humanPowerW!.max?.value).toBe(90);
    expect(stats.statistics.humanPowerW!.min?.observationId).toBe('ties:0000000000000001');
  });
  it('separates sample means from boundary-refined integration and excludes long gaps', async () => {
    const monitor = new TelemetryMonitor('integral', false, [
      sample(0, 0),
      sample(2, 100),
      sample(3, 10),
      sample(10, 50),
    ]);
    const result = await monitor.rangeStats({
      generation: 1,
      expectedRevision: '4',
      metrics: ['humanPowerW'],
      startSeconds: 1,
      endSeconds: 10,
    });
    if (result.status !== 'ok') throw new Error('unexpected retry');
    expect(result.statistics.humanPowerW).toMatchObject({
      count: 3,
      sampleMean: 160 / 3,
      coveredSeconds: 2,
      integral: 130,
    });
  });
  it.each([
    { times: [0, 2], values: [100, 200], start: 0.5, end: 1.5, covered: 1, integral: 150 },
    { times: [0, 1], values: [100, 100], start: 0.2, end: 0.8, covered: 0.6, integral: 60 },
  ])(
    'integrates a clipped span without interior observations: $start to $end',
    async ({ times, values, start, end, covered, integral }) => {
      const monitor = new TelemetryMonitor(
        'between',
        false,
        times.map((time, index) => sample(time, values[index])),
      );
      const result = await monitor.rangeStats({
        generation: 1,
        expectedRevision: '2',
        metrics: ['humanPowerW'],
        startSeconds: start,
        endSeconds: end,
      });
      if (result.status !== 'ok') throw new Error('unexpected retry');
      const stats = result.statistics.humanPowerW!;
      expect(stats.count).toBe(0);
      expect(stats.coveredSeconds).toBeCloseTo(covered);
      expect(stats.integral).toBeCloseTo(integral);
      expect(stats.min).toBeUndefined();
      expect(stats.max).toBeUndefined();
      expect(stats.sampleMean).toBeUndefined();
    },
  );
  it('uses the 2.5-second integration limit for every controller metric independently of display continuity', async () => {
    const metrics = MONITOR_METRICS.filter(metric => (SAMPLE_COLUMNS as readonly string[]).includes(metric.id)).map(
      metric => metric.id,
    );
    const monitor = new TelemetryMonitor(
      'telemetry-gaps',
      false,
      [0, 2.5, 6].map(time => ({ ...sample(time, 100), controllerSpeedMps: 3 })),
    );
    const common = { generation: 1, expectedRevision: '3', metrics };
    const result = await monitor.rangeStats({ ...common, startSeconds: 0, endSeconds: 6 });
    const empty = await monitor.rangeStats({ ...common, startSeconds: 3, endSeconds: 5 });
    const plot = await monitor.readPlot(common);
    if (result.status !== 'ok' || empty.status !== 'ok' || plot.status !== 'ok') throw new Error('unexpected retry');
    for (const id of metrics) {
      expect(result.statistics[id]?.coveredSeconds, id).toBe(2.5);
      expect(empty.statistics[id], id).toBeUndefined();
      expect(
        plot.series[id]?.filter(point => point.startsSegment),
        id,
      ).toHaveLength(1);
    }
  });
  it.each(['heartRateBpm', 'speedMps', 'healthSpeedMps', 'activeEnergyKcal', 'basalEnergyKcal'])(
    'keeps the catalog integration gap for %s',
    async id => {
      const gap = metricById(id)!.gapSeconds;
      const monitor = new TelemetryMonitor(
        'other-streams',
        false,
        [0, gap, gap * 2 + 0.01].map(time => ({ ...sample(time), [id]: 100 })),
      );
      const result = await monitor.rangeStats({
        generation: 1,
        expectedRevision: '3',
        metrics: [id],
        startSeconds: 0,
        endSeconds: gap * 2 + 0.01,
      });
      if (result.status !== 'ok') throw new Error('unexpected retry');
      expect(result.statistics[id]?.coveredSeconds).toBe(gap);
    },
  );
  it('returns resetRequired when the bounded change journal cannot prove history', async () => {
    const monitor = new TelemetryMonitor('journal', false);
    for (let i = 0; i < 300; i++) monitor.append(sample(i));
    expect((await monitor.changesSince({ generation: 1, sinceRevision: '0' })).resetRequired).toBe(true);
    expect((await monitor.changesSince({ generation: 1, sinceRevision: '299' })).changes).toMatchObject([
      { startSeconds: 298, endSeconds: 299, kind: 'append' },
    ]);
    expect((await monitor.changesSince({ generation: 1, sinceRevision: '9007199254740993' })).resetRequired).toBe(true);
  });
  it('changes source identity on explicit connection replacement, independently of recording clock resets', async () => {
    const monitor = new TelemetryMonitor('foreground', true, [sample(0), sample(1)]);
    const before = await monitor.describeSource({ generation: 1 });
    monitor.beginSession();
    monitor.append(sample(50));
    const after = await monitor.describeSource({ generation: 2 });
    expect(after.sourceId).not.toBe(before.sourceId);
    expect(after.revision).toBe('1');
    expect(after.domain.start).toBe(0);
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
    const point = {
      elapsedSeconds: 1,
      timestamp: '2026-01-01T00:00:01Z',
      value: 9007199254740992,
      exactValue: '9007199254740993',
    };
    expect(formatMetricPoint(metricById('humanPowerW')!, point)).toBe('9007199254740993');
    expect(formatMetricPoint(metricById('batteryVoltageV')!, point)).toBe('9007199254740993.00');
    expect(formatMetricPoint(metricById('distanceMeters')!, point)).toBe('9007199254740.99');
    expect(
      formatMetricDifference(metricById('batteryVoltageV')!, point, { ...point, exactValue: '9007199254740994' }),
    ).toBe('+1.00');
    expect(formatMetricPoint(metricById('humanPowerW')!, { ...point, exactValue: '-9223372036854775808' })).toBe(
      '-9223372036854775808',
    );
  });
  it('converts only known units and never treats raw controller speed as GPS speed', () => {
    expect(formatMetric(metricById('speedMps')!, 10)).toBe('36.0');
    expect(formatMetric(metricById('distanceMeters')!, 1500)).toBe('1.50');
    expect(metricById('speedRaw')!.unit).toBe('raw');
    expect(formatMetric(metricById('heartRateBpm')!, null)).toBe('—');
  });
});
