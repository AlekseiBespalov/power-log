import { createElement, useLayoutEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { useChartInteraction, type ChartInteraction } from '../../src/components/use-chart-interaction';
import {
  moveChartInteraction,
  type ChartInteractionEvent,
  type ChartInteractionState,
  type ChartPresentation,
} from '../../src/core/monitor-interaction';
import { rnTasks, scheduleOnRN, uiTasks } from '../helpers/chart-interaction-platform';
import { useForegroundActivity } from '../../src/services/use-foreground-activity';

export const initial: ChartPresentation = {
  domain: { start: 0, end: 100 },
  view: { start: 0, end: 100 },
  cursor: null,
  reference: null,
};
const inspectCalls: Parameters<Parameters<typeof useChartInteraction>[2]>[] = [];
const viewportCalls: Parameters<Parameters<typeof useChartInteraction>[3]>[] = [];
const inspect: Parameters<typeof useChartInteraction>[2] = (...args) => inspectCalls.push(args);
const viewport: Parameters<typeof useChartInteraction>[3] = (...args) => viewportCalls.push(args);
let committed: { interaction: ChartInteraction; presentation: ChartPresentation; scope: string; foreground: boolean };
let root: ReturnType<typeof createRoot>;

function Chart({ presentation, scope }: { presentation: ChartPresentation; scope: string }) {
  const interaction = useChartInteraction(presentation, scope, inspect, viewport);
  const foreground = useForegroundActivity();
  useLayoutEffect(() => {
    committed = { interaction, presentation, scope, foreground };
  });
  return createElement('output', null, interaction.epoch);
}
export function mount(presentation = initial) {
  const container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  render(presentation);
}
export function render(presentation: ChartPresentation, scope = 'same-source') {
  root.render(createElement(Chart, { presentation, scope }));
}
export function cancel(presentation = committed.presentation) {
  committed.interaction.cancel();
  render(presentation, committed.scope);
  return uiTasks.length;
}
export function flushUI() {
  const states: ChartInteractionState[] = [];
  for (const task of uiTasks.splice(0)) {
    task();
    states.push({ ...committed.interaction.shared.value });
  }
  return states;
}
export function queueEvent(kind: ChartInteractionEvent['kind'], cursor = 80) {
  const { shared, receive } = committed.interaction;
  const update = moveChartInteraction(
    shared.value,
    kind,
    kind === 'viewport' ? { start: 20, end: 80 } : shared.value.view,
    kind === 'cursor' ? cursor : null,
    performance.now(),
    true,
  );
  shared.value = update.state;
  if (update.event) scheduleOnRN(receive, update.event);
}
export function flushRN() {
  for (const task of rnTasks.splice(0)) task();
}
export function setForeground(active: boolean) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: active ? 'visible' : 'hidden' });
  document.dispatchEvent(new Event('visibilitychange'));
}
export function unmount() {
  root.unmount();
}
export function snapshot() {
  return committed
    ? {
        epoch: committed.interaction.epoch,
        shared: committed.interaction.shared.value,
        presentation: committed.presentation,
        scope: committed.scope,
        foreground: committed.foreground,
        pendingUI: uiTasks.length,
        pendingRN: rnTasks.length,
        inspectCalls,
        viewportCalls,
      }
    : null;
}
