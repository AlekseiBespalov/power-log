import { createElement, useSyncExternalStore } from 'react';
import { createRoot } from 'react-dom/client';
import { ScrollView } from 'react-native';
import type { MetricId, PlotView, SettingsView } from '../../modules/power-log';
import { createPowerLogClient } from '../../modules/power-log/src/client';
import { fakeTransport, type FakeTransport } from '../../modules/power-log/src/testing/fake-transport';
import { ChartCards } from '../../src/components/charts/chart-cards';
import { chartGroups, chartMetrics } from '../../src/components/charts/chart-groups';
import { chartEstimate, chartVisibility, groupMetrics } from '../../src/components/charts/chart-visibility';
import type { UiEnv } from '../../src/components/screen-model';
import { ChartFeedModel } from '../../src/features/charts/chart-feed-model';
import { setPlatform } from './monitor-render-native';
export { countsSnapshot, resetCounts } from './monitor-render-counts';

const source = { kind: 'ride', rideId: 'render' } as const;
const metric = chartMetrics('metric', 'celsius');
let root: ReturnType<typeof createRoot> | undefined;
let feed: ChartFeedModel | undefined;
let bump: (() => void) | undefined;
export const plotRequests: string[][] = [];

const level = (id: string) => 100 + ([...id].reduce((sum, char) => sum + char.charCodeAt(0), 0) % 50);

function plot(transport: FakeTransport, seconds: number) {
  transport.answer('charts.plot', input => {
    plotRequests.push([...input.metrics]);
    const startUs = input.rangeUs?.startUs ?? 0,
      endUs = Math.min(input.rangeUs?.endUs ?? seconds * 1e6, seconds * 1e6);
    const count = Math.max(1, Math.min(input.buckets, seconds));
    const slotUs = Math.max(1, Math.floor((endUs - startUs) / count));
    const times = Array.from({ length: count }, (_, index) => startUs + index * slotUs);
    const view: PlotView = {
      revision: 1,
      rangeUs: { startUs, endUs },
      slotUs,
      series: input.metrics.map(id => {
        const values = times.map(atUs => level(id) + 50 * Math.sin(atUs / 30e6));
        return {
          metric: id,
          segment: times.map(() => 1),
          firstUs: times,
          first: values,
          minUs: times,
          min: values,
          maxUs: times,
          max: values,
          lastUs: times,
          last: values,
        };
      }),
    };
    return { ok: true, value: view };
  });
}

function scroller(child: ReturnType<typeof createElement>) {
  return createElement(ScrollView, { testID: 'scroller', style: { height: window.innerHeight } }, child);
}

function Charts({ model, live, metrics }: { model: ChartFeedModel; live: boolean; metrics: MetricId[] }) {
  const state = useSyncExternalStore(model.subscribe, model.getSnapshot);
  if (!state.head) return null;
  return createElement(ChartCards, {
    feed: state,
    metricIds: metrics,
    metric,
    units: 'metric',
    temperature: 'celsius',
    live,
    holdTail: true,
    onViewportChange,
    onCursorChange,
    onReferenceChange,
    onPlotWidthChange,
    onVisibleMetricsChange,
  });
}
const onViewportChange = (next: { start: number; end: number }, interacting?: boolean) =>
  feed!.setViewport({ startS: next.start, endS: next.end }, !!interacting);
const onCursorChange = (seconds: number | null) =>
  seconds === null ? feed!.clearCursor() : feed!.setCursor(seconds, null);
const onReferenceChange = (seconds: number | null) =>
  seconds === null ? feed!.clearReference() : feed!.setReference(seconds, null);
const onPlotWidthChange = (width: number) => feed!.setPlotWidth(width);
const onVisibleMetricsChange = (shown: string[]) => feed!.setOnScreen(shown as MetricId[]);

function reset(os: 'ios' | 'web') {
  root?.unmount();
  feed?.deactivate();
  feed = undefined;
  plotRequests.length = 0;
  setPlatform(os);
  const container = document.createElement('div');
  document.body.replaceChildren(container);
  root = createRoot(container);
}

export function mountCharts(options: { os: 'ios' | 'web'; live: boolean; metrics: MetricId[]; seconds: number }) {
  reset(options.os);
  const transport = fakeTransport();
  const client = createPowerLogClient(transport);
  const display = { units: 'metric', temperature: 'celsius' } as SettingsView['app'];
  const env = {
    client,
    shell: { display: { current: () => display, subscribe: () => () => undefined }, report: () => undefined },
  } as unknown as UiEnv;
  plot(transport, options.seconds);
  transport.answer('charts.latest', () => ({ ok: true, value: { revision: 1, readings: [] } }));
  transport.answer('charts.statistics', () => ({ ok: true, value: { revision: 1, statistics: [], nextUs: null } }));
  transport.answer('charts.inspect', input => {
    const atUs = input.atUs;
    const values = input.metrics.map((id, offset) => ({
      metric: id,
      original: {
        value: 100 + offset + 50 * Math.sin(atUs / 30e6),
        elapsedUs: atUs,
        utcUs: Date.UTC(2026, 0, 1) * 1000 + atUs,
      },
      gap: false,
    }));
    return { ok: true, value: { revision: 1, atUs: input.atUs, values } };
  });
  const model = new ChartFeedModel(env, {
    source,
    scope: options.live ? 'live' : 'history',
    metrics: options.metrics,
    range: 'all',
    readouts: true,
  });
  feed = model;
  const groups = chartGroups(options.metrics, metric);
  const first = chartVisibility(
    groups.map(group => group.id),
    new Map(),
    window.innerHeight,
    chartEstimate(140),
  );
  model.setOnScreen(groupMetrics(groups, first.read) as MetricId[]);
  model.activate();
  let revision = 1;
  bump = () =>
    transport.emit(
      'charts.head',
      { source },
      {
        revision: revision++,
        rangeUs: { startUs: 0, endUs: options.seconds * 1e6 },
        live: options.live,
        originMonoUs: options.live ? -options.seconds * 1e6 : null,
        metrics: options.metrics,
        catchingUp: false,
      },
    );
  bump();
  root!.render(scroller(createElement(Charts, { model, live: options.live, metrics: options.metrics })));
}

export function tick() {
  return new Promise(resolve => setTimeout(resolve, 260));
}
export function bumpHead() {
  bump!();
}
export function idle() {
  return !!feed;
}
export function snapshot() {
  const state = feed!.getSnapshot();
  return {
    cursorSeconds: state.cursor?.atS ?? null,
    interactionVersion: state.resetKey,
    series: Object.keys(state.series),
    current: Object.entries(state.drawings)
      .filter(([, drawing]) => drawing?.current)
      .map(([id]) => id),
  };
}
export function scrollBy(pixels: number) {
  document.querySelector('[data-testid="scroller"]')!.scrollBy(0, pixels);
}
export function charts() {
  const frame = document.querySelector('[data-testid="scroller"]')!.getBoundingClientRect();
  return [...document.querySelectorAll('[data-testid^="monitor-chart-"]')].map(node => {
    const card = node.closest('[data-monitor-chart]') ?? node.parentElement!.parentElement!.parentElement!;
    const rect = card.getBoundingClientRect();
    return {
      id: node.getAttribute('data-testid')!.slice('monitor-chart-'.length),
      onScreen: rect.bottom > frame.top && rect.top < frame.bottom,
      read: rect.bottom > frame.top - frame.height / 2 && rect.top < frame.bottom + frame.height / 2,
      text: node.textContent ?? '',
    };
  });
}
