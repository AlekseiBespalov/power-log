import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { describe, expect, it, vi } from 'vitest';
import { MonitorCharts, type MonitorChartsProps } from '../../src/components/monitor-charts';
import { Heading } from '../../src/components/ui';
import { currentMonitorPoint, type MonitorData, type MonitorPoint } from '../../src/core/monitor';
const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};

vi.mock('react-native', () => {
  const element = (props: {
    children?: ReactNode;
    testID?: string;
    accessibilityRole?: string;
    accessibilityLabel?: string;
  }) =>
    createElement(
      'div',
      {
        'data-testid': props.testID,
        role: props.accessibilityRole,
        'aria-label': props.accessibilityLabel,
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
    Rect: shape,
    Text: shape,
  };
});
vi.mock('../../src/components/use-chart-interaction', () => ({
  useChartInteraction: () => ({ shared: { value: {} } }),
}));
vi.mock('../../src/components/use-native-chart-gestures', () => ({ useNativeChartGestures: () => ({}) }));
vi.mock('../../src/components/monitor-raster', () => ({ MonitorRaster: () => null }));
vi.mock('../../src/components/monitor-chart-grid', () => ({ MonitorChartGrid: () => null }));
vi.mock('../../src/components/modal-dialog', () => ({ useFocusedModal: () => false }));

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
  ] as const)('expires %s at its catalog boundary of %s seconds while tails never exceed six', (metric, expiry) => {
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
      expect(html.includes('Unavailable')).toBe(age >= expiry);
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
    expect(render({ data: input })).not.toContain('237');
    offset = 4600;
    expect(render({ data: input })).not.toContain('237');
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
    expect(html).not.toContain('237');
    expect(
      render({
        data: { ...input, liveAcquiredAt: { speedMps: 1018, heartRateBpm: null } },
        metricIds: ['heartRateBpm'],
        following: true,
      }),
    ).not.toContain('123');
  });
  it('expires number and chart readings together using live age even when replay changes the point time', () => {
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
      expect(render({ data: input }).includes('237')).toBe(age < 6);
    }
    for (const acquiredAt of [undefined, null, 12, NaN, Infinity]) {
      expect(
        render({
          data: { ...data, nowSeconds: 11, monotonicAt: 11, liveAcquiredAt: { humanPowerW: acquiredAt ?? null } },
        }),
      ).not.toContain('237');
    }
    expect(render({ data: { ...data, nowSeconds: 9, monotonicAt: 9 } })).not.toContain('237');
  });
  it('expires a live point seven seconds old without changing the original series', () => {
    const before = structuredClone(data);
    const html = render();
    expect(html).toContain('Unavailable');
    expect(html).not.toContain('237');
    expect(data).toEqual(before);
  });
  it('holds a live point only until the exact six-second boundary', () => {
    expect(render({ data: { ...data, monotonicAt: 15.999 }, displayTimeSeconds: 15.999 })).toContain('237');
    expect(render({ data: { ...data, monotonicAt: 16 }, displayTimeSeconds: 16 })).not.toContain('237');
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
  it('labels the last historical value and preserves its original observation time', () => {
    const html = render({ live: false });
    expect(html).toContain('237');
    expect(html).toContain('Last in range · 00:10.000');
    expect(html).toContain(point.timestamp);
  });
  it('limits historical readouts to the visible range, excluding query padding', () => {
    const html = render({
      live: false,
      viewport: { start: 0, end: 12 },
      data: { ...data, series: { humanPowerW: [point, { ...point, elapsedSeconds: 14, value: 999 }] } },
    });
    expect(html).toContain('Last in range · 00:10.000');
    expect(html).toContain('237');
    expect(html).not.toContain('999');
    expect(render({ live: false, viewport: { start: 11, end: 17 } })).toContain('Unavailable');
  });
  it('labels a manually held live window as historical even at the live edge', () => {
    expect(render({ following: false })).toContain('Last in range · 00:10.000');
    expect(render({ viewport: { start: 0, end: 12 } })).toContain('Last in range · 00:10.000');
    expect(render({ following: true, viewport: { start: 0, end: 12 } })).toContain('Unavailable');
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

it('marks shared headings for native and web accessibility semantics', () => {
  expect(renderToStaticMarkup(createElement(Heading, null, 'History'))).toContain('role="header"');
});
