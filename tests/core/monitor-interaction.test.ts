import { describe, expect, it } from 'vitest';
import { ChartInteractionGate, chartInteractionState, moveChartInteraction, synchronizeChartInteraction, type ChartInteractionEvent } from '../../src/core/monitor-interaction';

const initial = { view: { start: 0, end: 600 }, domain: { start: 0, end: 28800 }, cursor: null, reference: null };
describe('native chart interaction policy', () => {
  it.each(['cursor', 'viewport'] as const)('keeps 120 Hz %s movement immediate with bounded JS reads and a terminal flush', kind => {
    let state = chartInteractionState(initial, 4);
    const events: ChartInteractionEvent[] = [];
    for (let frame = 0; frame < 120; frame++) {
      const next = moveChartInteraction(state, kind, { start: frame, end: frame + 600 }, frame, frame * 1000 / 120, false);
      state = next.state;
      expect(state.cursor).toBe(frame); expect(state.view.start).toBe(frame);
      if (next.event) events.push(next.event);
    }
    expect(events.length).toBeLessThanOrEqual(kind === 'cursor' ? 20 : 10);
    const terminal = moveChartInteraction(state, kind, state.view, state.cursor, 999, true);
    expect(terminal.event).toMatchObject({ final: true, cursor: 119, sequence: 121 });
    expect(terminal.state.active).toBe(0);
  });
  it('rejects delayed React echoes during movement and after release until the final state is acknowledged', () => {
    let state = chartInteractionState(initial, 4);
    state = moveChartInteraction(state, 'viewport', { start: 100, end: 200 }, null, 0, false).state;
    expect(synchronizeChartInteraction(state, initial, 4, 1).view).toEqual(state.view);
    state = moveChartInteraction(state, 'viewport', state.view, null, 10, true).state;
    expect(synchronizeChartInteraction(state, initial, 4, 1).view).toEqual(state.view);
    expect(synchronizeChartInteraction(state, initial, 3, 99)).toBe(state);
    expect(synchronizeChartInteraction(state, { ...initial, view: state.view }, 4, 2).view).toEqual(state.view);
  });
  it('rejects out-of-order callbacks and callbacks queued before reset, source change or unmount', () => {
    const gate = new ChartInteractionGate();
    const state = chartInteractionState(initial, gate.epoch);
    const first = moveChartInteraction(state, 'cursor', state.view, 12, 0, false).event!;
    const final = { ...first, sequence: 4, final: true, cursor: 15 };
    expect(gate.accept(final)).toBe(true); expect(gate.accept(first)).toBe(false);
    gate.invalidate(); expect(gate.accept(final)).toBe(false);
    const current = { ...final, epoch: gate.epoch, sequence: 1 };
    expect(gate.accept(current)).toBe(true);
    gate.enabled = false; expect(gate.accept({ ...current, sequence: 2 })).toBe(false);
  });
});
