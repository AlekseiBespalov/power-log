import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { describe, expect, it, vi } from 'vitest';
import { kit, type MetricId, type MetricRangeStatistics, type Original } from '../../../modules/power-log';
import { rangeReadout } from '../../../src/components/charts/chart-card';
import { ChartCards, type ChartCardsProps } from '../../../src/components/charts/chart-cards';
import { chartMetrics } from '../../../src/components/charts/chart-groups';
import type { ChartFeedState, ChartSeries, LatestCell } from '../../../src/features/charts/chart-feed-model';

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
    ScrollView: wrapper,
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
vi.mock('../../../src/components/charts/use-chart-interaction', () => ({
  useChartInteraction: () => ({ shared: { value: {} } }),
}));
vi.mock('../../../src/components/charts/use-native-chart-gestures', () => ({ useNativeChartGestures: () => ({}) }));
vi.mock('../../../src/components/charts/chart-grid', () => ({ ChartGrid: () => null }));
vi.mock('../../../src/components/modal-dialog', () => ({ useFocusedModal: () => false }));
vi.mock('../../../src/components/use-foreground-activity', () => ({ useForegroundActivity: () => true }));

const metric = chartMetrics('metric', 'celsius');
const startUs = Date.UTC(2026, 0, 1) * 1000 + 123_000;
const original = (value: number, atS: number): Original => ({
  value,
  elapsedUs: atS * 1e6,
  utcUs: startUs + atS * 1e6,
});
const series = (...points: [number, number][]): ChartSeries => ({
  atUs: Float64Array.from(points, ([atS]) => atS * 1e6),
  atS: Float64Array.from(points, ([atS]) => atS),
  value: Float64Array.from(points, ([, value]) => value),
  starts: Uint8Array.from(points, (_, index) => (index === 0 ? 1 : 0)),
});
const cell = (value: number | null, atS: number, stale = false): LatestCell => ({
  value,
  elapsedUs: atS * 1e6,
  rideAverage: null,
  freshUntilMs: null,
  tailUntilMs: null,
  stale,
});
const statistics = (values: Partial<MetricRangeStatistics> & { metric: MetricId }): MetricRangeStatistics => ({
  min: null,
  average: null,
  max: null,
  total: null,
  ratio: null,
  partial: false,
  ...values,
});
const inspected = (id: MetricId, value: Original | null) => ({ [id]: { metric: id, original: value, gap: false } });

function feed(overrides: Partial<ChartFeedState> = {}): ChartFeedState {
  return {
    sourceKey: 'fixture',
    head: { revision: 1, live: true, metrics: ['humanPowerW'] },
    following: true,
    inspecting: false,
    domain: { startS: 0, endS: 17 },
    viewport: { startS: 0, endS: 17 },
    nowS: 17,
    series: { humanPowerW: series([10, 237]) },
    drawings: { humanPowerW: { revision: 1, current: true } },
    latest: { humanPowerW: cell(237, 10) },
    statistics: null,
    cursor: null,
    reference: null,
    comparison: null,
    tails: {},
    catchingUp: false,
    error: null,
    resetKey: 0,
    ...overrides,
  };
}

function render(props: Partial<ChartCardsProps> = {}) {
  return renderToStaticMarkup(
    createElement(ChartCards, {
      feed: feed(),
      metricIds: ['humanPowerW'],
      metric,
      units: 'metric',
      temperature: 'celsius',
      live: true,
      holdTail: true,
      onViewportChange() {},
      onCursorChange() {},
      onReferenceChange() {},
      ...props,
    }),
  );
}

describe('chart card readouts', () => {
  it('shows metric names and values without source captions, and the sample time only with a cursor', () => {
    const ids: MetricId[] = ['gpsDistanceMeters', 'controllerEnergyWh', 'calculatedEnergyWh'];
    const html = render({
      metricIds: ids,
      feed: feed({
        series: Object.fromEntries(ids.map(id => [id, series([10, 237])])),
        latest: Object.fromEntries(ids.map(id => [id, cell(237, 10)])),
      }),
    });
    expect(html).toContain('Energy used (controller)');
    expect(html).toContain('Energy used (calc)');
    expect(html).not.toMatch(/Partial|Estimated|Last sample|Last in range/);
    expect(html).not.toContain('>00:10.000<');
    const cursor = { atS: 10, values: inspected('humanPowerW', original(237, 10)), pending: false };
    expect(render({ feed: feed({ following: false, cursor }) })).toContain('>00:10.000<');
  });

  it('renders every selectable metric while a cursor waits for its exact readouts', () => {
    const ids = kit
      .metricAppearances()
      .filter(appearance => appearance.selectable)
      .map(appearance => appearance.id as MetricId);
    const all = feed({
      following: false,
      series: Object.fromEntries(ids.map(id => [id, series([10, 1], [11, 2])])),
      cursor: { atS: 10.25, values: {}, pending: true },
      reference: { atS: 10, values: {} },
    });
    for (const viewport of [all.domain, { startS: 9, endS: 12 }]) {
      const html = render({ metricIds: ids, feed: { ...all, viewport } });
      expect(html).toContain('Distance (GPS)');
      expect(html).toContain('Energy used (controller)');
      expect(html).toContain('Charge used (calc)');
    }
  });

  it('keeps an empty live chart free of axis numbers and a growing accessible range', () => {
    for (const nowS of [10, 30, 120]) {
      const html = render({
        feed: feed({ series: {}, latest: {}, domain: { startS: 0, endS: nowS }, viewport: { startS: 0, endS: nowS } }),
      });
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

  it('draws a held tail only from the feed’s tails, and only while the bike is available', () => {
    const tails = { humanPowerW: { startS: 10, endS: 15, value: 237 } };
    expect(render({ feed: feed({ tails }) })).toContain('data-held-tail=');
    expect(render({ feed: feed({ tails }), holdTail: false })).not.toContain('data-held-tail=');
    expect(render()).not.toContain('data-held-tail=');
  });

  it('keeps a stale latest value in the chart readout', () => {
    const html = render({ feed: feed({ latest: { humanPowerW: cell(237, 10, true) } }) });
    expect(html).toContain('237');
    expect(html).toContain('00:10.000 elapsed');
    expect(html).not.toContain('Last sample');
  });

  it('shows the newest plotted original before the first latest answer, then the latest value alone', () => {
    expect(render({ feed: feed({ latest: {} }) })).toContain('00:10.000 elapsed');
    expect(render({ feed: feed({ latest: { humanPowerW: cell(251, 17) } }) })).toContain('251');
    expect(render({ feed: feed({ latest: { humanPowerW: cell(null, 0) } }) })).not.toContain('237');
  });

  it('labels historical range statistics instead of the last plotted value when there is no cursor', () => {
    const html = render({
      live: false,
      feed: feed({
        statistics: {
          range: { startS: 0, endS: 17 },
          values: {
            humanPowerW: statistics({
              metric: 'humanPowerW',
              average: 123,
              min: original(50, 8),
              max: original(604, 9),
            }),
          },
          pending: false,
        },
      }),
    });
    expect(html).toContain('avg 123');
    expect(html).toContain('max 604 W');
    expect(html).not.toContain('237');
    expect(html).not.toContain('Last in range');
  });

  it('shows a consumption amount without extra source or coverage descriptions', () => {
    const html = render({
      live: false,
      metricIds: ['controllerEnergyWh'],
      feed: feed({
        series: { controllerEnergyWh: series([10, 100]) },
        statistics: {
          range: { startS: 0, endS: 17 },
          values: { controllerEnergyWh: statistics({ metric: 'controllerEnergyWh', total: 1.25, partial: true }) },
          pending: false,
        },
      }),
    });
    expect(html).toContain('>1.25<');
    expect(html).not.toContain('Partial');
    expect(html).not.toContain(metric('controllerEnergyWh')!.description!);
    expect(html).not.toContain('avg ');
  });

  it.each(['pending', 'other range'] as const)('shows a pending historical readout for %s statistics', reason => {
    const html = render({
      live: false,
      feed: feed({
        viewport: { startS: 11, endS: 17 },
        statistics: {
          range: reason === 'pending' ? { startS: 11, endS: 17 } : { startS: 0, endS: 12 },
          values: { humanPowerW: statistics({ metric: 'humanPowerW', average: 237, min: original(237, 10) }) },
          pending: reason === 'pending',
        },
      }),
    });
    expect(html).toContain('min —');
    expect(html).not.toContain('avg 237');
    expect(html).not.toContain('max 237');
  });

  it('shows an explicit historical cursor value at its original observation time', () => {
    const html = render({
      live: false,
      feed: feed({ cursor: { atS: 10.75, values: inspected('humanPowerW', original(237, 10)), pending: false } }),
    });
    expect(html).toContain('237');
    expect(html).toContain('00:10 elapsed, 2026-01-01T00:00:10.123Z');
    expect(html).not.toContain('00:10.000');
    expect(html).not.toContain('avg');
  });

  it('labels a manually held live window from the plotted originals in view', () => {
    expect(render({ feed: feed({ following: false, latest: {} }) })).toContain('00:10.000 elapsed');
    expect(render({ feed: feed({ following: false, viewport: { startS: 11, endS: 17 } }) })).not.toContain('237');
  });

  it('shows cumulative distance in display units', () => {
    expect(
      render({
        metricIds: ['distanceMeters'],
        feed: feed({ series: { distanceMeters: series([10, 237000]) }, latest: { distanceMeters: cell(237000, 10) } }),
      }),
    ).toContain('237.00');
  });
});

describe('history range readouts', () => {
  const range = { startS: 0, endS: 10 };
  const view = { start: 0, end: 10 };
  const of = (values: MetricRangeStatistics, pending = false, at = range) => ({
    range: at,
    values: { [values.metric]: values },
    pending,
  });

  it('shows energy divided by distance, not the mean of instantaneous chart readings', () => {
    const whPerKm = metric('batteryWhPerKm')!;
    const stats = statistics({ metric: 'batteryWhPerKm', ratio: 66.666666, average: 75 });
    expect(rangeReadout(of(stats), whPerKm, view)).toEqual({ value: 'avg 66.7', detail: '' });
    expect(rangeReadout(of({ ...stats, partial: true }), whPerKm, view).detail).toBe('Partial data');
    expect(rangeReadout(null, whPerKm, view)).toEqual({ value: 'avg —', detail: '' });
  });

  it('uses original range statistics rather than the last plotted point', () => {
    const stats = statistics({ metric: 'humanPowerW', average: 123, min: original(0, 0.5), max: original(604, 1) });
    expect(rangeReadout(of(stats), metric('humanPowerW')!, view)).toEqual({ value: 'avg 123', detail: 'max 604 W' });
  });

  it('converts statistics into display units', () => {
    const at = { startS: 1.2, endS: 5.8 };
    const stats = statistics({ metric: 'speedMps', average: 10, min: original(5, 1.2), max: original(15, 4.7) });
    expect(rangeReadout(of(stats, false, at), metric('speedMps')!, { start: 1.2, end: 5.8 })).toEqual({
      value: 'avg 36.0',
      detail: 'max 54.0 km/h',
    });
  });

  it.each(['pending', 'otherRange'] as const)('does not present %s statistics as the current visible range', reason => {
    const stats = statistics({ metric: 'humanPowerW', average: 123, max: original(604, 1) });
    const shown = reason === 'pending' ? of(stats, true) : of(stats, false, { startS: 1, endS: 5 });
    expect(rangeReadout(shown, metric('humanPowerW')!, view)).toEqual({ value: 'min —', detail: '' });
  });

  it('does not average counter values or convert missing values to zero', () => {
    const stats = statistics({ metric: 'consumedWh', average: 99, min: original(1, 1), max: original(2, 2) });
    expect(rangeReadout(of(stats), metric('consumedWh')!, view)).toEqual({ value: 'min 1.0', detail: 'max 2.0 Wh' });
    expect(rangeReadout(of(statistics({ metric: 'humanPowerW' })), metric('humanPowerW')!, view)).toEqual({
      value: 'min —',
      detail: 'max — W',
    });
  });
});
