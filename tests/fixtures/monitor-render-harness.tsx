import { createElement, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ScrollView } from 'react-native';
import { MonitorCharts } from '../../src/components/monitor-charts';
import { defaultMonitorPreferences, type MonitorPlotRequest } from '../../src/core/monitor';
import { groupMonitorMetrics } from '../../src/core/monitor-chart';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { monitorChartEstimate, monitorChartVisibility, monitorGroupMetrics } from '../../src/core/monitor-visibility';
import { MonitorPanel } from '../../src/features/monitor/monitor-panel';
import { MonitorReadController, type MonitorReadState } from '../../src/features/monitor/monitor-read-controller';
import { withEnergyPerDistance } from '../../src/services/energy-per-distance-monitor';
import { syntheticSample } from './synthetic-sample';
import { setPlatform } from './monitor-render-native';
import { setPanelPreferences } from './monitor-render-panel';
export { countsSnapshot, resetCounts } from './monitor-render-counts';

const start = Date.UTC(2026, 0, 1);
let root: ReturnType<typeof createRoot> | undefined;
let controller: MonitorReadController | undefined;
export const plotRequests: string[][] = [];

function ride(seconds: number, live: boolean) {
  const monitor = new TelemetryMonitor(live ? 'workout:render-live' : 'workout:render-history', live);
  const anchor = performance.now() / 1000 - seconds;
  for (let i = 0; i <= seconds * 2; i++) {
    const elapsed = i / 2;
    monitor.append(
      syntheticSample(elapsed, i, new Date(start + elapsed * 1000).toISOString()),
      live ? { receivedAtSeconds: anchor + elapsed } : undefined,
    );
  }
  const read = monitor.readPlot.bind(monitor);
  monitor.readPlot = (request: MonitorPlotRequest) => {
    plotRequests.push([...request.metrics]);
    return read(request);
  };
  return monitor;
}

function scroller(child: ReturnType<typeof createElement>) {
  return createElement(ScrollView, { testID: 'scroller', style: { height: window.innerHeight } }, child);
}

function Charts({ live, metrics }: { live: boolean; metrics: string[] }) {
  const [state, setState] = useState<MonitorReadState>(() => controller!.getSnapshot());
  useEffect(() => controller!.subscribe(setState), []);
  if (!state.data) return null;
  return createElement(MonitorCharts, {
    data: state.data,
    metricIds: metrics,
    viewport: state.viewport,
    cursorSeconds: state.cursorSeconds,
    referenceSeconds: state.referenceSeconds,
    onCursorChange: inspect,
    onViewportChange: changeViewport,
    onReferenceChange: setReference,
    live,
    following: state.following,
    displayTimeSeconds: live ? state.nowSeconds : undefined,
    resetKey: state.interactionVersion,
    onVisibleMetricsChange: showMetrics,
  });
}
const inspect = (seconds: number | null, interacting?: boolean) => controller!.inspect(seconds, interacting);
const changeViewport = (view: { start: number; end: number }, interacting?: boolean) =>
  controller!.changeViewport(view, interacting);
const setReference = (seconds: number | null) => controller!.setReference(seconds);
const showMetrics = (metrics: string[]) => controller!.setVisibleMetrics(metrics);

function reset(os: 'ios' | 'web') {
  root?.unmount();
  controller?.dispose();
  controller = undefined;
  plotRequests.length = 0;
  setPlatform(os);
  const container = document.createElement('div');
  document.body.replaceChildren(container);
  root = createRoot(container);
}

export function mountCharts(options: { os: 'ios' | 'web'; live: boolean; metrics: string[]; seconds: number }) {
  reset(options.os);
  controller = new MonitorReadController(ride(options.seconds, options.live));
  const groups = groupMonitorMetrics(options.metrics);
  const first = monitorChartVisibility(
    groups.map(group => group.id),
    new Map(),
    window.innerHeight,
    monitorChartEstimate(140),
  );
  controller.configure(options.metrics, 'all');
  controller.setVisibleMetrics(monitorGroupMetrics(groups, first.read));
  controller.activate();
  controller.refresh();
  root!.render(scroller(createElement(Charts, { live: options.live, metrics: options.metrics })));
}

export function mountPanel(options: { os: 'ios' | 'web'; numbers: string[]; charts: string[]; seconds: number }) {
  reset(options.os);
  const monitor = ride(options.seconds, false);
  const panelSource = withEnergyPerDistance(monitor, request => monitor.readEnergyPerDistance(request));
  const preferences = defaultMonitorPreferences();
  preferences.historyView = 'ride';
  preferences.historyRange = 'all';
  preferences.views.ride = { ...preferences.views.ride, numbers: options.numbers, charts: options.charts };
  setPanelPreferences(preferences);
  root!.render(scroller(createElement(MonitorPanel, { source: panelSource })));
}

export function tick() {
  controller!.tick();
}
export function refresh() {
  controller!.refresh();
}
export function idle() {
  return !!controller && Object.values(controller.getSnapshot().loading).every(loading => !loading);
}
export function snapshot() {
  const state = controller!.getSnapshot();
  return {
    cursorSeconds: state.cursorSeconds,
    interactionVersion: state.interactionVersion,
    series: Object.keys(state.data?.series ?? {}),
    current: Object.entries(state.data?.drawings ?? {})
      .filter(([, drawing]) => drawing.current)
      .map(([metric]) => metric),
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
