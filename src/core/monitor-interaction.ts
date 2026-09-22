import type { ChartViewport } from './chart-viewport';
import type { MonitorSnap } from './monitor-hit-test';

export type ChartPresentation = {
  view: ChartViewport; domain: ChartViewport; cursor: number | null; reference: number | null;
};
export type ChartInteractionState = ChartPresentation & {
  epoch: number; sequence: number; active: 0 | 1 | 2; cursorAt: number; viewportAt: number; snap: MonitorSnap | null;
};
export type ChartInteractionEvent = {
  epoch: number; sequence: number; kind: 'cursor' | 'viewport'; final: boolean;
  view: ChartViewport; cursor: number | null; snap?: MonitorSnap | null;
};
export function chartInteractionState(presentation: ChartPresentation, epoch: number): ChartInteractionState {
  'worklet';
  return { ...presentation, epoch, sequence: 0, active: 0, cursorAt: -Infinity, viewportAt: -Infinity, snap: null };
}

/** UI movement is immediate. Only bounded readout/query notifications cross to JS. */
export function moveChartInteraction(state: ChartInteractionState, kind: ChartInteractionEvent['kind'], view: ChartViewport, cursor: number | null, now: number, final: boolean, snap: MonitorSnap | null = null) {
  'worklet';
  const sequence = state.sequence + 1;
  const last = kind === 'cursor' ? state.cursorAt : state.viewportAt;
  const publish = final || now - last >= (kind === 'cursor' ? 50 : 100);
  const next: ChartInteractionState = { ...state, view, cursor, sequence, snap, active: final ? 0 : kind === 'cursor' ? 1 : 2,
    cursorAt: kind === 'cursor' && publish ? now : state.cursorAt,
    viewportAt: kind === 'viewport' && publish ? now : state.viewportAt };
  const event: ChartInteractionEvent | null = publish ? { epoch: state.epoch, sequence, kind, final, view, cursor, snap } : null;
  return { state: next, event };
}

/** Delayed React commits may update the domain but never rewind a newer gesture. */
export function synchronizeChartInteraction(state: ChartInteractionState, presentation: ChartPresentation, epoch: number, acknowledgedSequence: number): ChartInteractionState {
  'worklet';
  if (epoch !== state.epoch) return state;
  if (state.active !== 0 || acknowledgedSequence < state.sequence) return { ...state, domain: presentation.domain };
  return { ...state, ...presentation, snap: presentation.cursor === state.cursor ? state.snap : null };
}

/** The JS half rejects callbacks queued before clear, configuration changes or unmount. */
export class ChartInteractionGate {
  epoch = 1;
  sequence = 0;
  enabled = true;
  scope = '';
  navigating = false;
  accept(event: ChartInteractionEvent) {
    if (!this.enabled || event.epoch !== this.epoch || event.sequence <= this.sequence) return false;
    this.sequence = event.sequence;
    return true;
  }
  invalidate() { this.epoch++; this.sequence = 0; this.navigating = false; }
}
