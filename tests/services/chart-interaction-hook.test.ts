import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useChartInteraction } from '../../src/features/monitor/use-chart-interaction';
import type { ChartPresentation } from '../../src/core/monitor-interaction';

// Execute the production hook's commit/effect ordering and UI task payloads.
// Native marker watermark behavior is separately tested by the Swift policy suite.
const harness = vi.hoisted(() => ({ cursor: 0, slots: [] as unknown[], effects: [] as (() => void)[], dirty: false, ui: [] as (() => void)[] }));
vi.mock('react', () => ({
  useRef: (value: unknown) => { const index = harness.cursor++; return harness.slots[index] ?? (harness.slots[index] = { current: value }); },
  useState: (value: unknown) => {
    const index = harness.cursor++; if (!(index in harness.slots)) harness.slots[index] = value;
    return [harness.slots[index], (next: unknown) => { if (next !== harness.slots[index]) { harness.slots[index] = next; harness.dirty = true; } }];
  },
  useCallback: (callback: unknown) => callback,
  useLayoutEffect: (effect: () => void, dependencies: unknown[]) => {
    const index = harness.cursor++, previous = harness.slots[index] as unknown[] | undefined;
    if (!previous || dependencies.some((value, i) => !Object.is(previous[i], value))) { harness.slots[index] = dependencies; harness.effects.push(effect); }
  },
}));
vi.mock('react-native-reanimated', () => ({ useSharedValue: (value: unknown) => { const index = harness.cursor++; return harness.slots[index] ?? (harness.slots[index] = { value }); } }));
vi.mock('react-native-worklets', () => ({ scheduleOnUI: (callback: (...args: unknown[]) => void, ...args: unknown[]) => harness.ui.push(() => callback(...args)) }));
vi.mock('../../src/services/use-foreground-activity', () => ({ useForegroundActivity: () => true }));
const inspect = vi.fn(), viewport = vi.fn();
const initial: ChartPresentation = { domain: { start: 0, end: 100 }, view: { start: 0, end: 100 }, cursor: null, reference: null };
function render(presentation: ChartPresentation) {
  let result!: ReturnType<typeof useChartInteraction>;
  do {
    harness.dirty = false; harness.cursor = 0;
    // eslint-disable-next-line react-hooks/rules-of-hooks
    result = useChartInteraction(presentation, 'same-source', inspect, viewport);
    for (const effect of harness.effects.splice(0)) effect();
  } while (harness.dirty);
  return result;
}
const flushUI = () => { for (const task of harness.ui.splice(0)) task(); };
describe('programmatic native interaction ownership', () => {
  it('publishes cancellation ownership even when fullscreen/control state retains the same cursor and viewport', () => {
    const first = render(initial); flushUI(); first.cancel();
    const next = render(initial); flushUI();
    expect(next.shared.value.epoch).toBe(next.epoch);
    expect(next.epoch).toBeGreaterThan(first.epoch);
  });

  beforeEach(() => { harness.cursor = 0; harness.slots = []; harness.effects = []; harness.ui = []; harness.dirty = false; inspect.mockClear(); viewport.mockClear(); });
  it('initializes a new epoch with the resulting selected cursor, never a prior clear at the same sequence', () => {
    const first = render(initial); flushUI(); const previousEpoch = first.epoch;
    first.cancel(); expect(harness.ui).toHaveLength(0);
    const selected = render({ ...initial, cursor: 42 });
    const presentations = [];
    for (const task of harness.ui.splice(0)) { task(); presentations.push({ ...selected.shared.value }); }
    expect(presentations.length).toBeGreaterThan(0);
    expect(presentations.every(value => value.epoch > previousEpoch && value.cursor === 42 && value.sequence === 0)).toBe(true);
    expect(selected.epoch).toBe(selected.shared.value.epoch);
    first.receive({ epoch: previousEpoch, sequence: 9, kind: 'cursor', final: true, view: initial.view, cursor: 80 });
    expect(inspect).not.toHaveBeenCalled();
  });
  it('clears a selected epoch and accepts a later programmatic retap without stale queued callbacks', () => {
    let interaction = render({ ...initial, cursor: 42 }); flushUI();
    interaction.cancel(); interaction = render(initial); flushUI(); const clearedEpoch = interaction.epoch;
    expect(interaction.shared.value.cursor).toBeNull();
    interaction.cancel(); interaction = render({ ...initial, cursor: 90 }); flushUI();
    expect(interaction.shared.value.epoch).toBeGreaterThan(clearedEpoch);
    expect(interaction.shared.value.cursor).toBe(90);
  });
});
