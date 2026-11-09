import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ChartHeadView, InspectedValue, MetricRangeStatistics, PlotView } from '../../../modules/power-log';
import { ChartFeedModel, chartSeries } from '../../../src/features/charts/chart-feed-model';
import { failure, holderEnv, kitText, sent, settle } from '../holder-env';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

afterEach(() => {
  vi.useRealTimers();
});

const source = { kind: 'ride' as const, rideId: 'r1' };

function head(revision: number, overrides: Partial<ChartHeadView> = {}): ChartHeadView {
  return {
    revision,
    rangeUs: { startUs: 0, endUs: 90_000_000 },
    live: false,
    originMonoUs: null,
    metrics: ['humanPowerW'],
    catchingUp: false,
    ...overrides,
  };
}

function plot(revision: number): PlotView {
  return {
    revision,
    rangeUs: { startUs: 0, endUs: 90_000_000 },
    slotUs: 1_000_000,
    series: [
      {
        metric: 'humanPowerW',
        segment: [1, 1, 2],
        firstUs: [0, 1_000_000, 3_000_000],
        first: [100, 120, 90],
        minUs: [500_000, 1_000_000, 3_000_000],
        min: [80, 120, 90],
        maxUs: [250_000, 1_500_000, 3_000_000],
        max: [150, 130, 90],
        lastUs: [900_000, 1_900_000, 3_000_000],
        last: [110, 125, 90],
      },
    ],
  };
}

async function feed(range: 'all' | 'seconds30' = 'all') {
  const env = await holderEnv('web');
  const model = new ChartFeedModel(env.env, { source, scope: 'history', metrics: ['humanPowerW'], range });
  return { ...env, model };
}

describe('ChartFeedModel', () => {
  it('turns a plot series into time-ordered originals with each bucket’s equal times once and a start per segment', () => {
    const series = chartSeries(plot(1).series[0]!);

    expect(Array.from(series.atUs)).toEqual([0, 250_000, 500_000, 900_000, 1_000_000, 1_500_000, 1_900_000, 3_000_000]);
    expect(Array.from(series.value)).toEqual([100, 150, 80, 110, 120, 130, 125, 90]);
    expect(Array.from(series.atS)).toEqual([0, 0.25, 0.5, 0.9, 1, 1.5, 1.9, 3]);
    expect(Array.from(series.starts)).toEqual([1, 0, 0, 0, 0, 0, 0, 1]);
  });

  it('plots the whole stored ride for each new head revision and not again for the drawn one', async () => {
    const { transport, model } = await feed();
    transport.answer('charts.plot', input => ({ ok: true, value: plot(input.source.kind === 'ride' ? 5 : 0) }));
    model.setPlotWidth(300.4);
    model.activate();
    await settle();

    transport.emit('charts.head', { source }, head(5));
    await settle();
    transport.emit('charts.head', { source }, head(5, { catchingUp: true }));
    await settle();

    expect(sent(transport, 'charts.plot')).toEqual([
      {
        source,
        metrics: ['humanPowerW'],
        key: expect.stringMatching(/^chart-feed:\d+:plot$/),
        rangeUs: null,
        buckets: 300,
        smoothingUs: 0,
      },
    ]);
    const state = model.getSnapshot();
    expect(state.sourceKey).toBe('{"kind":"ride","rideId":"r1"}');
    expect(state.head).toEqual({ revision: 5, live: false, metrics: ['humanPowerW'] });
    expect(state.domain).toEqual({ startS: 0, endS: 90 });
    expect(state.viewport).toEqual({ startS: 0, endS: 90 });
    expect(state.drawings).toEqual({ humanPowerW: { revision: 5, current: true } });
    expect(Array.from(state.series.humanPowerW!.value)).toHaveLength(8);
    expect(state.following).toBe(false);
    expect(state.nowS).toBeNull();
  });

  it('marks a stored ride inspecting while a cursor or a manual viewport is set, until the view resets', async () => {
    const { transport, model } = await feed();
    transport.answer('charts.plot', () => ({ ok: true, value: plot(5) }));
    model.setPlotWidth(300);
    model.activate();
    await settle();
    transport.emit('charts.head', { source }, head(5));
    await settle();
    expect(model.getSnapshot().inspecting).toBe(false);

    model.setCursor(30, null);
    expect(model.getSnapshot().inspecting).toBe(true);
    model.resetView();
    expect(model.getSnapshot().inspecting).toBe(false);

    model.setViewport({ startS: 0, endS: 45 }, false);
    expect(model.getSnapshot()).toMatchObject({ inspecting: true, following: false });
  });

  it('asks for the last range of a stored ride and caps buckets at 512', async () => {
    const { transport, model } = await feed('seconds30');
    transport.answer('charts.plot', () => ({ ok: true, value: plot(6) }));
    model.setPlotWidth(2000);
    model.activate();
    await settle();

    transport.emit('charts.head', { source }, head(6));
    await settle();

    expect(sent(transport, 'charts.plot')).toEqual([
      expect.objectContaining({ rangeUs: { startUs: 60_000_000, endUs: 90_000_000 }, buckets: 512 }),
    ]);
    expect(model.getSnapshot().viewport).toEqual({ startS: 60, endS: 90 });
  });

  it('keeps the last drawing while busy and asks again after 500 ms', async () => {
    const { transport, model } = await feed();
    vi.useFakeTimers();
    let attempt = 0;
    transport.answer('charts.plot', () =>
      ++attempt === 2 ? { ok: false, failure: failure('storageBusy', null, true) } : { ok: true, value: plot(attempt) },
    );
    model.activate();
    await vi.advanceTimersByTimeAsync(0);
    transport.emit('charts.head', { source }, head(1));
    await vi.advanceTimersByTimeAsync(0);
    transport.emit('charts.head', { source }, head(2));
    await vi.advanceTimersByTimeAsync(0);
    expect(model.getSnapshot().catchingUp).toBe(true);
    expect(model.getSnapshot().drawings.humanPowerW?.revision).toBe(1);

    await vi.advanceTimersByTimeAsync(500);

    expect(sent(transport, 'charts.plot')).toHaveLength(3);
    expect(model.getSnapshot().catchingUp).toBe(false);
    expect(model.getSnapshot().drawings.humanPowerW).toEqual({ revision: 3, current: false });
  });

  it('shows a chart error for capacity and a read failure and stops on a deleted ride', async () => {
    const { transport, model } = await feed();
    transport.answer('charts.plot', () => ({ ok: false, failure: failure('chartCapacity') }));
    model.activate();
    await settle();
    transport.emit('charts.head', { source }, head(1));
    await settle();
    expect(model.getSnapshot().error).toBe(kitText('chartCapacity'));

    transport.fail('charts.head', { source }, failure('rideDeleted'));
    await settle();
    transport.emit('charts.head', { source }, head(2));
    await settle();

    expect(sent(transport, 'charts.plot')).toHaveLength(1);
  });

  it('waits for a preview that doesn’t exist yet and follows it once the bike is available', async () => {
    const preview = { kind: 'preview' as const };
    const env = await holderEnv('web');
    const model = new ChartFeedModel(env.env, {
      source: preview,
      scope: 'live',
      metrics: ['humanPowerW'],
      range: 'seconds30',
      bikeAvailable: false,
    });
    model.activate();
    await settle();
    env.transport.fail('charts.head', { source: preview }, failure('rideDeleted'));
    await settle();
    const subscriptions = () => env.transport.calls.filter(call => call.op === 'charts.head').length;
    const before = subscriptions();
    expect(model.getSnapshot().head).toBeNull();

    model.configure({ metrics: ['humanPowerW'], range: 'seconds30', bikeAvailable: true });
    await settle();
    expect(subscriptions()).toBeGreaterThan(before);
    env.transport.emit('charts.head', { source: preview }, head(1, { live: true, originMonoUs: 0 }));
    await settle();
    expect(model.getSnapshot().head).toMatchObject({ revision: 1, live: true });
    model.deactivate();
  });

  it('follows a live panel before its head answers, so no Live button or Custom range shows', async () => {
    const env = await holderEnv('web');
    const live = new ChartFeedModel(env.env, {
      source: { kind: 'preview' },
      scope: 'live',
      metrics: ['humanPowerW'],
      range: 'seconds120',
    });
    live.activate();
    await settle();
    expect(live.getSnapshot()).toMatchObject({ head: null, following: true });
    live.deactivate();
  });

  it('marks a live panel inspecting only after a cursor, even when its ride is no longer live', async () => {
    const env = await holderEnv('web');
    const source = { kind: 'ride' as const, rideId: 'interrupted' };
    env.transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    const live = new ChartFeedModel(env.env, { source, scope: 'live', metrics: ['humanPowerW'], range: 'seconds120' });
    live.setPlotWidth(300);
    live.activate();
    await settle();
    env.transport.emit('charts.head', { source }, head(1, { live: false }));
    await settle();
    expect(live.getSnapshot()).toMatchObject({ following: false, inspecting: false });

    live.setCursor(30, null);
    expect(live.getSnapshot().inspecting).toBe(true);
    live.follow();
    expect(live.getSnapshot().inspecting).toBe(false);
    live.deactivate();
  });

  it('follows a live head with its clock', async () => {
    const env = await holderEnv('web');
    const model = new ChartFeedModel(env.env, {
      source: { kind: 'preview' },
      scope: 'live',
      metrics: ['humanPowerW'],
      range: 'seconds30',
    });
    env.transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    env.transport.setNow(50_000_000);
    model.activate();
    await settle();

    env.transport.emit(
      'charts.head',
      { source: { kind: 'preview' } },
      head(1, { live: true, originMonoUs: 50_000_000 - 42_000_000, rangeUs: { startUs: 0, endUs: 42_000_000 } }),
    );
    await settle();

    const state = model.getSnapshot();
    expect(state.following).toBe(true);
    expect(state.nowS).toBeGreaterThanOrEqual(42);
    expect(state.viewport.endS - state.viewport.startS).toBeCloseTo(30, 0);
    model.deactivate();
  });
  it('asks for latest readings of the tiles and the live readout on activate and each head answer, with their instants mapped', async () => {
    const env = await holderEnv('web');
    env.transport.setNow(10_000_000);
    const model = new ChartFeedModel(env.env, {
      source: { kind: 'preview' },
      scope: 'live',
      metrics: ['humanPowerW'],
      range: 'seconds30',
      tiles: ['cadenceRpm'],
      readouts: true,
    });
    env.transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    env.transport.answer('charts.latest', () => ({
      ok: true,
      value: {
        revision: 1,
        readings: [
          {
            metric: 'cadenceRpm',
            value: 82,
            elapsedUs: 5_000_000,
            freshUntilMonoUs: 12_000_000,
            tailUntilMonoUs: null,
            rideAverage: null,
          },
        ],
      },
    }));
    model.activate();
    await settle();
    env.transport.emit('charts.head', { source: { kind: 'preview' } }, head(1, { live: true, originMonoUs: 0 }));
    await settle();

    expect(sent(env.transport, 'charts.latest')).toEqual([
      expect.objectContaining({ metrics: ['cadenceRpm', 'humanPowerW'], key: expect.stringMatching(/:latest$/) }),
      expect.objectContaining({ metrics: ['cadenceRpm', 'humanPowerW'] }),
    ]);
    const cell = model.getSnapshot().latest.cadenceRpm!;
    expect(cell).toMatchObject({ value: 82, elapsedUs: 5_000_000, tailUntilMs: null });
    expect(cell.freshUntilMs).toBe(env.env.client.localTime(12_000_000));
    model.deactivate();
  });

  it('asks for statistics of the viewport once it settles, and of voltage metrics only while following live', async () => {
    const stats: MetricRangeStatistics = {
      metric: 'humanPowerW',
      min: null,
      average: 150,
      max: null,
      total: null,
      ratio: null,
      partial: false,
    };
    const { transport, model } = await feed();
    model.activate();
    model.configure({ metrics: ['humanPowerW'], range: 'all', readouts: true });
    transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    transport.answer('charts.statistics', () => ({
      ok: true,
      value: { revision: 1, statistics: [stats], nextUs: null },
    }));
    transport.emit('charts.head', { source }, head(1));
    await settle();

    expect(sent(transport, 'charts.statistics')).toEqual([
      expect.objectContaining({
        metrics: ['humanPowerW'],
        rangeUs: { startUs: 0, endUs: 90_000_000 },
        whPerKmCharted: false,
      }),
    ]);
    expect(model.getSnapshot().statistics).toMatchObject({ range: { startS: 0, endS: 90 }, pending: false });
    expect(model.getSnapshot().statistics?.values.humanPowerW?.average).toBe(150);

    const env = await holderEnv('web');
    const live = new ChartFeedModel(env.env, {
      source: { kind: 'preview' },
      scope: 'live',
      metrics: ['humanPowerW', 'batteryVoltageV'],
      range: 'seconds30',
      readouts: true,
      whPerKmCharted: true,
    });
    env.transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    env.transport.answer('charts.latest', () => ({ ok: true, value: { revision: 1, readings: [] } }));
    env.transport.answer('charts.statistics', () => ({
      ok: true,
      value: { revision: 1, statistics: [], nextUs: null },
    }));
    live.activate();
    env.transport.emit('charts.head', { source: { kind: 'preview' } }, head(1, { live: true, originMonoUs: 0 }));
    await settle();
    expect(sent(env.transport, 'charts.statistics')).toEqual([
      expect.objectContaining({ metrics: ['batteryVoltageV'], whPerKmCharted: true }),
    ]);
    live.deactivate();
  });

  it('asks live statistics again on a head answer only once nextUs has elapsed', async () => {
    const env = await holderEnv('web');
    const live = new ChartFeedModel(env.env, {
      source: { kind: 'preview' },
      scope: 'live',
      metrics: ['humanPowerW', 'batteryVoltageV'],
      range: 'seconds30',
      readouts: true,
      whPerKmCharted: true,
    });
    env.transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    env.transport.answer('charts.latest', () => ({ ok: true, value: { revision: 1, readings: [] } }));
    env.transport.answer('charts.statistics', () => ({
      ok: true,
      value: { revision: 1, statistics: [], nextUs: 1_000_000 },
    }));
    vi.useFakeTimers({ toFake: ['performance'] });
    live.activate();
    const preview = { source: { kind: 'preview' } } as const;
    env.transport.emit('charts.head', preview, head(1, { live: true, originMonoUs: 0 }));
    await settle();
    env.transport.emit('charts.head', preview, head(2, { live: true, originMonoUs: 0 }));
    await settle();
    expect(sent(env.transport, 'charts.statistics')).toHaveLength(1);

    vi.advanceTimersByTime(1_000);
    env.transport.emit('charts.head', preview, head(3, { live: true, originMonoUs: 0 }));
    await settle();

    expect(sent(env.transport, 'charts.statistics')).toEqual([
      expect.objectContaining({ metrics: ['batteryVoltageV'], whPerKmCharted: true }),
      expect.objectContaining({ metrics: ['batteryVoltageV'], whPerKmCharted: true }),
    ]);
    live.deactivate();
  });

  it('compares A with an anchored cursor B over [min, max + 1), 160 ms after the drag’s last move', async () => {
    const { transport, model } = await feed();
    model.activate();
    model.configure({ metrics: ['batteryVoltageV'], range: 'all' });
    transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    transport.answer('charts.inspect', input => ({ ok: true, value: { revision: 1, atUs: input.atUs, values: [] } }));
    transport.answer('charts.statistics', () => ({ ok: true, value: { revision: 1, statistics: [], nextUs: null } }));
    transport.emit('charts.head', { source }, head(1));
    await settle();
    model.setReference(30, { metric: 'batteryVoltageV', elapsedUs: 30_250_000 });
    await settle();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const before = sent(transport, 'charts.statistics').length;

    model.setCursor(11, { metric: 'batteryVoltageV', elapsedUs: 11_000_000 }, true);
    await vi.advanceTimersByTimeAsync(100);
    model.setCursor(12, { metric: 'batteryVoltageV', elapsedUs: 12_500_000 }, true);
    await vi.advanceTimersByTimeAsync(159);
    expect(sent(transport, 'charts.statistics')).toHaveLength(before);
    await vi.advanceTimersByTimeAsync(1);

    expect(sent(transport, 'charts.statistics').slice(before)).toEqual([
      expect.objectContaining({ rangeUs: { startUs: 12_500_000, endUs: 30_250_001 } }),
    ]);
  });

  it('plots at most every 120 ms during a gesture and once 200 ms after it, then asks for statistics', async () => {
    const { transport, model } = await feed();
    model.activate();
    model.configure({ metrics: ['humanPowerW'], range: 'all', readouts: true });
    transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    transport.answer('charts.statistics', () => ({ ok: true, value: { revision: 1, statistics: [], nextUs: null } }));
    transport.emit('charts.head', { source }, head(1));
    await settle();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] });
    const plots = () => sent(transport, 'charts.plot').length;
    const statistics = () => sent(transport, 'charts.statistics').length;
    const before = { plots: plots(), statistics: statistics() };

    model.setViewport({ startS: 10, endS: 40 }, true);
    await vi.advanceTimersByTimeAsync(50);
    model.setViewport({ startS: 12, endS: 42 }, true);
    await vi.advanceTimersByTimeAsync(80);
    model.setViewport({ startS: 14, endS: 44 }, true);
    await vi.advanceTimersByTimeAsync(0);
    expect(plots() - before.plots).toBe(2);
    expect(statistics()).toBe(before.statistics);
    expect(model.getSnapshot().statistics?.pending).toBe(true);

    await vi.advanceTimersByTimeAsync(200);
    expect(plots() - before.plots).toBe(3);
    expect(statistics() - before.statistics).toBe(1);
    expect(sent(transport, 'charts.plot').at(-1)).toMatchObject({
      rangeUs: { startUs: 14_000_000, endUs: 44_000_000 },
    });
    expect(model.getSnapshot().viewport).toEqual({ startS: 14, endS: 44 });
    expect(model.getSnapshot().statistics?.pending).toBe(false);
  });

  it('plots 100 ms after the on-screen charts change', async () => {
    const { transport, model } = await feed();
    model.activate();
    model.configure({ metrics: ['humanPowerW', 'cadenceRpm'], range: 'all' });
    transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    transport.emit('charts.head', { source }, head(1));
    await settle();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const before = sent(transport, 'charts.plot').length;

    model.setOnScreen(['cadenceRpm']);
    await vi.advanceTimersByTimeAsync(99);
    expect(sent(transport, 'charts.plot')).toHaveLength(before);
    await vi.advanceTimersByTimeAsync(1);

    expect(sent(transport, 'charts.plot').at(-1)).toMatchObject({ metrics: ['cadenceRpm'] });
  });

  it('flags each kept series with the revision of the plot that drew it', async () => {
    const { transport, model } = await feed();
    model.activate();
    transport.answer('charts.plot', input => {
      const drawn = plot(input.metrics.includes('humanPowerW') ? 1 : 2);
      return {
        ok: true,
        value: { ...drawn, series: input.metrics.map(metric => ({ ...drawn.series[0]!, metric })) },
      };
    });
    transport.emit('charts.head', { source }, head(1));
    await settle();
    transport.emit('charts.head', { source }, head(2));
    model.configure({ metrics: ['humanPowerW', 'cadenceRpm'], range: 'all' });
    model.setOnScreen(['cadenceRpm']);
    await new Promise(resolve => setTimeout(resolve, 120));
    await settle();

    expect(sent(transport, 'charts.plot').at(-1)).toMatchObject({ metrics: ['cadenceRpm'] });
    expect(model.getSnapshot().drawings).toEqual({
      humanPowerW: { revision: 1, current: false },
      cadenceRpm: { revision: 2, current: true },
    });
  });

  it('keeps a metric’s series when a new plot answer draws it the same', async () => {
    const { transport, model } = await feed();
    model.activate();
    transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    transport.emit('charts.head', { source }, head(1));
    await settle();
    const drawn = model.getSnapshot().series.humanPowerW;

    model.setPlotWidth(400);
    await settle();
    expect(sent(transport, 'charts.plot')).toHaveLength(2);
    expect(model.getSnapshot().series.humanPowerW).toBe(drawn);
  });

  it('asks statistics only for charts entering the screen, without marking the shown values pending', async () => {
    const stats = (metric: 'humanPowerW' | 'cadenceRpm', average: number) => ({
      metric,
      min: null,
      average,
      max: null,
      total: null,
      ratio: null,
      partial: false,
    });
    const { transport, model } = await feed();
    model.activate();
    model.configure({ metrics: ['humanPowerW', 'cadenceRpm'], range: 'all', readouts: true });
    model.setOnScreen(['humanPowerW']);
    transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    let answer: (() => void) | undefined;
    transport.answer('charts.statistics', input => {
      const value = {
        revision: 1,
        statistics: input.metrics.map(metric => stats(metric as 'humanPowerW', metric === 'cadenceRpm' ? 80 : 150)),
        nextUs: null,
      };
      return input.metrics.includes('cadenceRpm')
        ? new Promise(resolve => (answer = () => resolve({ ok: true, value })))
        : { ok: true, value };
    });
    transport.emit('charts.head', { source }, head(1));
    await settle();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });

    model.setOnScreen(['humanPowerW']);
    model.setOnScreen(['humanPowerW', 'cadenceRpm']);
    await vi.advanceTimersByTimeAsync(100);
    expect(sent(transport, 'charts.statistics').at(-1)).toMatchObject({ metrics: ['cadenceRpm'] });
    expect(model.getSnapshot().statistics).toMatchObject({ pending: false });
    answer!();
    await vi.advanceTimersByTimeAsync(0);

    expect(model.getSnapshot().statistics?.values).toMatchObject({
      humanPowerW: { average: 150 },
      cadenceRpm: { average: 80 },
    });
  });

  it('inspects the cursor at its snapped original, and compares A and B over the range between them', async () => {
    const value = (metric: 'batteryVoltageV', original: number): InspectedValue => ({
      metric,
      original: { value: original, utcUs: 0, elapsedUs: 0 },
      gap: false,
    });
    const { transport, model } = await feed();
    model.activate();
    model.configure({ metrics: ['batteryVoltageV'], range: 'all' });
    transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    transport.answer('charts.inspect', input => ({
      ok: true,
      value: { revision: 1, atUs: input.atUs, values: [value('batteryVoltageV', input.atUs / 1e6)] },
    }));
    transport.answer('charts.statistics', () => ({ ok: true, value: { revision: 1, statistics: [], nextUs: null } }));
    transport.emit('charts.head', { source }, head(1));
    await settle();

    model.setReference(20, { metric: 'batteryVoltageV', elapsedUs: 20_500_000 });
    model.setCursor(10, null);
    await settle();

    expect(sent(transport, 'charts.inspect')).toEqual([
      expect.objectContaining({ atUs: 20_500_000, anchor: { metric: 'batteryVoltageV', elapsedUs: 20_500_000 } }),
      expect.objectContaining({ atUs: 10_000_000, anchor: null }),
    ]);
    expect(sent(transport, 'charts.statistics').at(-1)).toMatchObject({
      rangeUs: { startUs: 10_000_000, endUs: 20_500_001 },
    });
    expect(model.getSnapshot().cursor).toMatchObject({ atS: 10, pending: false });
    expect(model.getSnapshot().reference?.values.batteryVoltageV?.original?.value).toBe(20.5);
    expect(model.getSnapshot().comparison).toMatchObject({ loading: false });

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const compared = sent(transport, 'charts.statistics').length;
    model.setCursor(11, null, true);
    model.setCursor(12, null, true);
    await vi.advanceTimersByTimeAsync(159);
    expect(sent(transport, 'charts.statistics')).toHaveLength(compared);
    await vi.advanceTimersByTimeAsync(1);
    expect(sent(transport, 'charts.statistics')).toHaveLength(compared + 1);
  });

  it('inspects a stored ride’s domain end at its last stored instant', async () => {
    const { transport, model } = await feed();
    model.activate();
    transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    transport.answer('charts.inspect', input => ({ ok: true, value: { revision: 1, atUs: input.atUs, values: [] } }));
    transport.emit('charts.head', { source }, head(1));
    await settle();

    model.setCursor(model.getSnapshot().domain.endS, null);
    await settle();

    expect(sent(transport, 'charts.inspect')).toEqual([expect.objectContaining({ atUs: 89_999_999 })]);
  });

  it('keeps other kinds working when one fails, and clears the error once none fails', async () => {
    const { transport, model } = await feed();
    model.activate();
    model.configure({ metrics: ['humanPowerW'], range: 'all', readouts: true });
    let failing = true;
    transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    transport.answer('charts.statistics', () =>
      failing
        ? { ok: false, failure: failure('readFailed') }
        : { ok: true, value: { revision: 2, statistics: [], nextUs: null } },
    );
    transport.emit('charts.head', { source }, head(1));
    await settle();
    expect(model.getSnapshot().error).toBe(kitText('readFailed'));
    expect(model.getSnapshot().drawings.humanPowerW).toEqual({ revision: 1, current: true });

    failing = false;
    model.setViewport({ startS: 0, endS: 45 }, false);
    await settle();
    expect(model.getSnapshot().error).toBeNull();
  });

  it('draws a tail from the newest plotted original to now while the reading holds it, at most its tail long', async () => {
    const env = await holderEnv('web');
    vi.useFakeTimers({ toFake: ['performance'] });
    env.transport.setNow(0);
    const model = new ChartFeedModel(env.env, {
      source: { kind: 'preview' },
      scope: 'live',
      metrics: ['humanPowerW'],
      range: 'seconds30',
      readouts: true,
      bikeAvailable: true,
    });
    env.transport.answer('charts.plot', () => ({ ok: true, value: plot(1) }));
    env.transport.answer('charts.latest', () => ({
      ok: true,
      value: {
        revision: 1,
        readings: [
          {
            metric: 'humanPowerW',
            value: 90,
            elapsedUs: 3_000_000,
            freshUntilMonoUs: 60_000_000,
            tailUntilMonoUs: 60_000_000,
            rideAverage: null,
          },
        ],
      },
    }));
    model.activate();
    await settle();
    env.transport.emit('charts.head', { source: { kind: 'preview' } }, head(1, { live: true, originMonoUs: 0 }));
    await settle();
    vi.advanceTimersByTime(20_000);
    env.transport.setNow(20_000_000);
    env.transport.emit(
      'charts.head',
      { source: { kind: 'preview' } },
      head(1, { live: true, originMonoUs: 0, metrics: ['humanPowerW', 'cadenceRpm'] }),
    );
    await settle();

    const tail = model.getSnapshot().tails.humanPowerW!;
    expect(tail).toMatchObject({ startS: 3, value: 90 });
    expect(tail.endS).toBeCloseTo(9, 5);
    model.deactivate();
  });
});
