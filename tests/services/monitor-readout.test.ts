import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { describe, expect, it, vi } from 'vitest';
import { MonitorCharts, type MonitorChartsProps } from '../../src/components/monitor-charts';
import { Heading } from '../../src/components/ui';
import {
  currentMonitorPoint,
  formatMetricPoint,
  metricById,
  MONITOR_METRICS,
  type MonitorData,
  type MonitorPoint,
} from '../../src/core/monitor';
import { groupMonitorMetrics, monitorScale } from '../../src/core/monitor-chart';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { MonitorReadController } from '../../src/features/monitor/monitor-read-controller';
import freshness from '../fixtures/contract/presentation/freshness.json';
import { syntheticSample } from '../fixtures/synthetic-sample';
const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};

vi.mock('react-native', () => {
  const element = (props: {
    children?: ReactNode;
    testID?: string;
    accessibilityRole?: string;
    accessibilityLabel?: string;
    accessibilityValue?: { text: string };
  }) =>
    createElement(
      'div',
      {
        'data-testid': props.testID,
        role: props.accessibilityRole,
        'aria-label': props.accessibilityLabel,
        'aria-valuetext': props.accessibilityValue?.text,
      },
      props.children,
    );
  return {
    View: element,
    Text: element,
    Pressable: element,
    ScrollView: element,
    Modal: () => null,
    Platform: { OS: 'web' },
    StyleSheet: { create: (value: unknown) => value },
    useWindowDimensions: () => ({ width: 390, height: 900, fontScale: 1 }),
  };
});
vi.mock('react-native-safe-area-context', () => ({
  SafeAreaProvider: 'div',
  SafeAreaView: 'div',
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));
vi.mock('react-native-reanimated', () => ({ useSharedValue: (value: unknown) => ({ value }) }));
vi.mock('react-native-gesture-handler', () => {
  const gesture: object = new Proxy({}, { get: () => () => gesture });
  const wrapper = ({ children }: { children: ReactNode }) => children;
  return {
    Gesture: { Pan: () => gesture, Simultaneous: () => gesture, Race: () => gesture, Tap: () => gesture },
    GestureDetector: wrapper,
    GestureHandlerRootView: wrapper,
  };
});
vi.mock('react-native-svg', () => {
  const shape = ({ children }: { children?: ReactNode }) => createElement('span', null, children);
  return {
    default: shape,
    Circle: shape,
    ClipPath: shape,
    Defs: shape,
    G: shape,
    Line: shape,
    Path: ({ d, transform, strokeWidth }: { d?: string; transform?: string; strokeWidth?: number }) =>
      createElement('span', { 'data-held-tail': strokeWidth === 1.8 && !transform ? d : undefined }),
    Rect: ({ fill }: { fill?: string }) => createElement('span', { 'data-axis-backing': fill }),
    Text: ({ children, fontFamily }: { children?: ReactNode; fontFamily?: string }) =>
      createElement('span', { 'data-axis-font': fontFamily }, children),
  };
});
vi.mock('../../src/components/use-chart-interaction', () => ({
  useChartInteraction: () => ({ shared: { value: {} } }),
}));
vi.mock('../../src/components/use-native-chart-gestures', () => ({ useNativeChartGestures: () => ({}) }));
vi.mock('../../src/components/monitor-raster', () => ({ MonitorRaster: () => null }));
vi.mock('../../src/components/monitor-chart-grid', () => ({ MonitorChartGrid: () => null }));
vi.mock('../../src/components/modal-dialog', () => ({ useFocusedModal: () => false }));
vi.mock('../../src/services/use-foreground-activity', () => ({ useForegroundActivity: () => true }));

const point: MonitorPoint = {
  elapsedSeconds: 10,
  timestamp: '2026-01-01T00:00:10.123Z',
  value: 237,
  observationId: 'original-1',
};
const data: MonitorData = {
  sourceId: 'fixture',
  startedAt: '2026-01-01T00:00:00.123Z',
  domain: { start: 0, end: 17 },
  nowSeconds: 17,
  monotonicAt: 17,
  liveAcquiredAt: { humanPowerW: 10, heartRateBpm: 10 },
  series: { humanPowerW: [point] },
  latest: { humanPowerW: point },
  statistics: {},
};

it.each(freshness.heart)('expires the real phone heart reading at $age seconds in $phase', row => {
  const metric = metricById('heartRateBpm')!;
  const original = { ...point, value: 150 };
  const shown = currentMonitorPoint(original, true, 100 + row.age, metric.gapSeconds, 1100, seconds => seconds - 1000);
  expect(formatMetricPoint(metric, shown)).toBe(row.text);
  const markup = render({
    data: {
      ...data,
      monotonicAt: 100 + row.age,
      liveAcquiredAt: { heartRateBpm: 1100 },
      mapMonotonicSeconds: seconds => seconds - 1000,
      latest: { heartRateBpm: original },
      series: { heartRateBpm: [original] },
    },
    metricIds: ['heartRateBpm'],
    live: true,
    following: true,
    viewport: data.domain,
  });
  expect(markup).toContain('>150<');
  expect(markup).not.toContain('Last sample');
});

it.each(freshness.gps.filter(row => row.status === 'receiving' || row.status === 'stale'))(
  'expires the real phone GPS reading at $age seconds',
  row => {
    const metric = metricById('speedMps')!;
    const shown = currentMonitorPoint(point, true, 100 + row.age!, metric.gapSeconds, 1100, seconds => seconds - 1000);
    expect(shown).toBe(row.status === 'stale' ? null : point);
    if (shown) expect(shown.timestamp).toBe(point.timestamp);
  },
);
function render(props: Partial<MonitorChartsProps> = {}) {
  return renderToStaticMarkup(
    createElement(MonitorCharts, {
      data,
      metricIds: ['humanPowerW'],
      viewport: data.domain,
      cursorSeconds: null,
      referenceSeconds: null,
      live: true,
      onViewportChange() {},
      onCursorChange() {},
      onReferenceChange() {},
      ...props,
    }),
  );
}

describe('chart no-cursor readouts', () => {
  it('keeps metric names and values without repeating source, quality, or idle sample-time captions', () => {
    const ids = ['gpsDistanceMeters', 'controllerEnergyWh', 'calculatedEnergyWh'];
    const html = render({
      metricIds: ids,
      data: {
        ...data,
        series: Object.fromEntries(ids.map(id => [id, [point]])),
        latest: Object.fromEntries(ids.map(id => [id, point])),
        metricSources: Object.fromEntries(
          ids.map(id => [
            id,
            {
              source: id,
              label: 'Controller counters',
              partial: true,
              estimated: true,
            },
          ]),
        ),
      },
    });
    expect(html).toContain('Energy used (controller)');
    expect(html).toContain('Energy used (calc)');
    expect(html).not.toMatch(/Controller counters|Partial|Estimated|Last sample|Last in range/);
    expect(html).not.toContain('monitor-source-');
    expect(html).not.toContain('>00:10.000<');
    expect(render({ cursorSeconds: 10 })).toContain('>00:10.000<');
  });
  it('renders a complete dashboard during cursor inspection and viewport changes before exact readouts arrive', () => {
    const metricIds = MONITOR_METRICS.map(metric => metric.id);
    const input: MonitorData = {
      ...data,
      series: Object.fromEntries(
        metricIds.map(id => [
          id,
          [
            { ...point, value: 1, startsSegment: true },
            { ...point, elapsedSeconds: 11, value: 2, observationId: 'next', startsSegment: false },
          ],
        ]),
      ),
    };
    for (const viewport of [data.domain, { start: 9, end: 12 }]) {
      const html = render({
        data: input,
        metricIds,
        viewport,
        cursorSeconds: 10.25,
        referenceSeconds: 10,
        following: false,
      });
      expect(html).toContain('Distance (GPS)');
      expect(html).toContain('Energy used (controller)');
      expect(html).toContain('Charge used (calc)');
    }
  });
  it('keeps an empty live chart free of axis numbers and a growing accessible range', () => {
    const empty = { ...data, series: {}, latest: {}, domain: { start: 0, end: 10 } };
    for (const nowSeconds of [10, 30, 120]) {
      const html = render({ data: empty, viewport: { start: 0, end: nowSeconds }, displayTimeSeconds: nowSeconds });
      expect(html).toContain('No samples');
      expect(html).not.toContain('data-axis-font');
      expect(html).not.toContain('Visible ');
    }
  });
  it('backs all six web axis labels with the opaque chart colour and the app font stack', () => {
    const html = render();
    expect(html.match(/data-axis-backing="#0b0d10"/g)).toHaveLength(6);
    expect(html).toContain('data-axis-font="-apple-system, BlinkMacSystemFont,');
    expect(html).not.toContain('data-axis-font="Arial"');
  });
  it('omits an SVG tail when a current Health reading precedes the plotted tail after a UTC rollback', () => {
    const old = { ...point, elapsedSeconds: 100, value: 120, observationId: 'pre-rollback' };
    const current = { ...point, elapsedSeconds: 40, value: 140, observationId: 'post-rollback' };
    const input: MonitorData = {
      ...data,
      domain: { start: 0, end: 101 },
      nowSeconds: 101,
      monotonicAt: 1001,
      liveAcquiredAt: { heartRateBpm: 1000 },
      series: { heartRateBpm: [current, old] },
      latest: { heartRateBpm: current },
    };
    const props = { data: input, viewport: input.domain, metricIds: ['heartRateBpm'], following: true };
    const html = render(props);
    expect(html).toContain('140');
    expect(html).not.toContain('data-held-tail=');
    expect(render({ ...props, data: { ...input, latest: { heartRateBpm: { ...old } } } })).toContain('data-held-tail=');
  });

  it.each([
    ['humanPowerW', 6],
    ['speedMps', 10],
    ['heartRateBpm', 15],
  ] as const)('keeps the last %s sample after %s seconds while tails never exceed six', (metric, expiry) => {
    const input: MonitorData = {
      ...data,
      domain: { start: 0, end: 11 },
      nowSeconds: 11,
      liveAcquiredAt: { [metric]: 1000 },
      series: { [metric]: [point] },
      latest: { [metric]: point },
    };
    const props = { viewport: input.domain, metricIds: [metric], following: true };
    for (const age of [5.999, 6, expiry - 0.001, expiry]) {
      const html = render({ ...props, data: { ...input, monotonicAt: 1000 + age } });
      expect(html).not.toContain('Unavailable');
      expect(html).not.toContain('Last sample');
      expect(html.includes('data-held-tail=')).toBe(age < 6);
    }
  });

  it('reevaluates retained native chart evidence with the current mapper', () => {
    let offset: number | null = 1000;
    const input: MonitorData = {
      ...data,
      nowSeconds: 11,
      monotonicAt: 11,
      liveAcquiredAt: { humanPowerW: 1010 },
      mapMonotonicSeconds: seconds => (offset === null ? null : seconds - offset),
    };
    expect(render({ data: input })).toContain('237');
    offset = null;
    expect(render({ data: input })).toContain('00:10.000 elapsed');
    offset = 4600;
    expect(render({ data: input })).toContain('00:10.000 elapsed');
  });

  it.each([false, true])('shows current GPS and heart rate with no fresh bike stream: bike=%s', bike => {
    const points = {
      humanPowerW: bike ? point : null,
      speedMps: { ...point, elapsedSeconds: 18, value: 7 },
      heartRateBpm: { ...point, elapsedSeconds: 3618, value: 123 },
    };
    const input: MonitorData = {
      ...data,
      nowSeconds: 18,
      monotonicAt: 1018,
      liveAcquiredAt: { humanPowerW: bike ? 1010 : null, speedMps: 1018, heartRateBpm: 1017 },
      latest: points,
    };
    const html = render({ data: input, following: true, metricIds: ['humanPowerW', 'speedMps', 'heartRateBpm'] });
    expect(html).toContain('25.2');
    expect(html).toContain('123');
    expect(html.includes('237')).toBe(bike);
    expect(
      render({
        data: { ...input, liveAcquiredAt: { speedMps: 1018, heartRateBpm: null } },
        metricIds: ['heartRateBpm'],
        following: true,
      }),
    ).toContain('1:00:18.000 elapsed');
  });
  it('expires current-value tiles but retains labeled chart samples when replay changes point time', () => {
    for (const age of [5.999, 6]) {
      const replayed = { ...point, elapsedSeconds: 15 };
      const input = {
        ...data,
        nowSeconds: 16,
        monotonicAt: 16,
        liveAcquiredAt: { humanPowerW: 16 - age },
        latest: { humanPowerW: replayed },
      };
      const numeric = currentMonitorPoint(replayed, true, 16, 6, 16 - age);
      expect(numeric !== null).toBe(age < 6);
      expect(render({ data: input })).toContain('237');
      expect(render({ data: input })).not.toContain('Last sample');
    }
    for (const acquiredAt of [undefined, null, 12, NaN, Infinity]) {
      expect(
        render({
          data: { ...data, nowSeconds: 11, monotonicAt: 11, liveAcquiredAt: { humanPowerW: acquiredAt ?? null } },
        }),
      ).toContain('00:10.000 elapsed');
    }
    expect(render({ data: { ...data, nowSeconds: 9, monotonicAt: 9 } })).toContain('00:10.000 elapsed');
  });
  it('retains a stale value without adding a visible explanation or extending the series', () => {
    const before = structuredClone(data);
    const html = render();
    expect(html).toContain('237');
    expect(html).toContain('00:10.000 elapsed');
    expect(html).toContain('00:10.000 elapsed');
    expect(html).not.toContain('data-held-tail=');
    expect(data).toEqual(before);
  });
  it('keeps an expired live point at the exact six-second boundary', () => {
    expect(render({ data: { ...data, monotonicAt: 15.999 }, displayTimeSeconds: 15.999 })).toContain('237');
    expect(render({ data: { ...data, monotonicAt: 16 }, displayTimeSeconds: 16 })).not.toContain('Last sample');
  });
  it('uses latest observations independently of retained plotting geometry', () => {
    expect(
      render({
        data: {
          ...data,
          liveAcquiredAt: { humanPowerW: 17 },
          latest: { humanPowerW: { ...point, elapsedSeconds: 17, value: 251 } },
        },
      }),
    ).toContain('251');
    expect(render({ data: { ...data, latest: { humanPowerW: null } }, displayTimeSeconds: 11 })).not.toContain('237');
  });
  it('retains a recorded Health sample without live evidence or visible plot geometry', () => {
    const html = render({
      metricIds: ['heartRateBpm'],
      following: true,
      viewport: { start: 900, end: 1000 },
      data: {
        ...data,
        domain: { start: 0, end: 1000 },
        series: {},
        latest: { heartRateBpm: { ...point, value: 123 } },
        liveAcquiredAt: { heartRateBpm: null },
      },
    });
    expect(html).toContain('123');
    expect(html).toContain('00:10.000 elapsed');
    expect(html).not.toContain('data-held-tail=');
  });
  it('labels historical range statistics instead of the last plotted value when there is no cursor', () => {
    const html = render({
      live: false,
      data: {
        ...data,
        statistics: {
          humanPowerW: {
            count: 3,
            sampleMean: 123,
            min: { ...point, elapsedSeconds: 8, value: 50 },
            max: { ...point, elapsedSeconds: 9, value: 604 },
          },
        },
        statisticsViewport: data.domain,
      },
    });
    expect(html).toContain('avg 123');
    expect(html).toContain('max 604 W');
    expect(html).not.toContain('237');
    expect(html).not.toContain('Last in range');
    expect(html).not.toContain(point.timestamp);
  });
  it('shows a consumption amount without extra source or coverage descriptions', () => {
    const html = render({
      live: false,
      metricIds: ['controllerEnergyWh'],
      data: {
        ...data,
        series: { controllerEnergyWh: [{ ...point, value: 100 }] },
        statistics: {
          controllerEnergyWh: { amount: 1.25, coveredSeconds: 12, partial: true, unresolvedBoundary: true },
        },
        statisticsViewport: data.domain,
      },
    });
    expect(html).toContain('>1.25<');
    expect(html).not.toContain('Boundary amount');
    expect(html).not.toContain('Partial');
    expect(html).not.toContain(metricById('controllerEnergyWh')!.description!);
    expect(html).not.toContain('avg ');
  });
  it('limits historical range statistics to the visible range, excluding query padding', () => {
    const viewport = { start: 0, end: 12 };
    const input: MonitorData = {
      ...data,
      series: { humanPowerW: [point, { ...point, elapsedSeconds: 14, value: 999 }] },
      statistics: { humanPowerW: { count: 1, sampleMean: 237, min: point, max: point } },
      statisticsViewport: viewport,
    };
    const html = render({
      live: false,
      viewport,
      data: input,
    });
    expect(html).toContain('avg 237');
    expect(html).toContain('max 237 W');
    expect(html).not.toContain('999');
    expect(html).not.toContain('Last in range');
    expect(html).not.toContain(point.timestamp);
  });
  it.each(['pending', 'other range'] as const)('shows a pending historical readout for %s statistics', reason => {
    const html = render({
      live: false,
      viewport: { start: 11, end: 17 },
      data: {
        ...data,
        statistics: { humanPowerW: { count: 1, sampleMean: 237, min: point, max: point } },
        statisticsViewport: reason === 'pending' ? { start: 11, end: 17 } : { start: 0, end: 12 },
        statisticsPending: reason === 'pending',
      },
    });
    expect(html).toContain('min —');
    expect(html).not.toContain('Updating range…');
    expect(html).not.toContain('avg 237');
    expect(html).not.toContain('max 237');
    expect(html).not.toContain('Last in range');
  });
  it('shows an explicit historical cursor value at its original observation time', () => {
    const html = render({
      live: false,
      cursorSeconds: 10.75,
      data: { ...data, selectionSeconds: 10.75, selection: { humanPowerW: point } },
    });
    expect(html).toContain('237');
    expect(html).toContain(`00:10 elapsed, ${point.timestamp}`);
    expect(html).not.toContain('00:10.000');
    expect(html).not.toContain('Last in range');
    expect(html).not.toContain('avg');
  });
  it('labels a manually held live window as historical even at the live edge', () => {
    expect(render({ following: false })).toContain('00:10.000 elapsed');
    expect(render({ viewport: { start: 0, end: 12 } })).toContain('00:10.000 elapsed');
    expect(render({ following: true, viewport: { start: 0, end: 12 } })).toContain('00:10.000 elapsed');
  });
  it('retains metric-specific freshness and cumulative distance semantics', () => {
    expect(
      render({
        metricIds: ['heartRateBpm'],
        data: { ...data, series: { heartRateBpm: [point] }, latest: { heartRateBpm: point } },
      }),
    ).toContain('237');
    const distance = { ...point, value: 237000 };
    expect(
      render({
        metricIds: ['distanceMeters'],
        following: true,
        displayTimeSeconds: 1000,
        data: { ...data, series: { distanceMeters: [distance] }, latest: { distanceMeters: distance } },
      }),
    ).toContain('237.00');
  });
  it('preserves an explicit historical cursor on a live source after freshness expires', () => {
    const html = render({
      cursorSeconds: 10,
      data: { ...data, selectionSeconds: 10, selection: { humanPowerW: point } },
    });
    expect(html).toContain('237');
    expect(html).toContain(point.timestamp);
    expect(html).not.toContain('Last in range');
  });
});

it('draws identical live scales and readouts from voltage-only statistics as from every metric', async () => {
  const metrics = ['humanPowerW', 'cadenceRpm', 'batteryVoltageV', 'motorTempC', 'batteryCurrentA'];
  const source = new TelemetryMonitor(
    'workout:plotted-extrema',
    true,
    Array.from({ length: 2400 }, (_, i) => {
      const seconds = i / 4;
      const value = syntheticSample(seconds, i, new Date(Date.UTC(2026, 0, 1) + i * 250).toISOString());
      if (i === 1337) return { ...value, humanPowerW: 911, batteryVoltageV: 47.3, motorTempC: 81 };
      if (i === 2011) return { ...value, cadenceRpm: 3, batteryCurrentA: 29.5 };
      return value;
    }),
  );
  const controller = new MonitorReadController(source);
  try {
    controller.configure(metrics, 'all', 120);
    controller.refresh();
    for (let i = 0; i < 100; i++) await Promise.resolve();
    const state = controller.getSnapshot(),
      data = state.data!;
    expect(Object.keys(data.statistics)).toEqual(['batteryVoltageV']);
    expect(data.series.humanPowerW!.length).toBeLessThan(2400);
    const all = await source.rangeStats({
      generation: 0,
      expectedRevision: data.revision!,
      metrics,
      startSeconds: data.statisticsViewport!.start,
      endSeconds: data.statisticsViewport!.end,
    });
    if (all.status !== 'ok') throw new Error('Unexpected retry');
    expect(all.statistics.humanPowerW?.max?.value).toBe(911);
    const full = { ...data, statistics: all.statistics };
    for (const group of groupMonitorMetrics(metrics))
      expect(monitorScale(group.metrics, data)).toEqual(monitorScale(group.metrics, full));
    const props = {
      metricIds: metrics,
      live: true,
      following: true,
      viewport: state.viewport,
      displayTimeSeconds: state.nowSeconds,
    };
    expect(render({ ...props, data })).toBe(render({ ...props, data: full }));
  } finally {
    controller.dispose();
  }
});

it('marks shared headings for native and web accessibility semantics', () => {
  expect(renderToStaticMarkup(createElement(Heading, null, 'History'))).toContain('role="header"');
});
