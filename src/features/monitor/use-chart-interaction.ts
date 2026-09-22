import type { MonitorSnap } from '../../core/monitor-hit-test';
import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import { useSharedValue } from 'react-native-reanimated';
import { scheduleOnUI } from 'react-native-worklets';
import { ChartInteractionGate, chartInteractionState, synchronizeChartInteraction, type ChartInteractionEvent, type ChartPresentation } from '../../core/monitor-interaction';
import type { ChartViewport } from '../../core/chart-viewport';
import { useForegroundActivity } from '../../services/use-foreground-activity';

export function useChartInteraction(presentation: ChartPresentation, scope: string,
  inspect: (seconds: number | null, interacting?: boolean, snap?: MonitorSnap | null) => void,
  changeViewport: (view: ChartViewport, interacting?: boolean) => void) {
  const gateRef = useRef(new ChartInteractionGate());
  const synchronizedEpoch = useRef(0);
  const [epoch, setEpoch] = useState(1);
  // Snapshot the imperative gate at render time: later React commits must carry
  // the sequence they actually rendered, not a newer event accepted meanwhile.
  /* eslint-disable react-hooks/refs */
  const gate = gateRef.current;
  const acknowledgedSequence = gate.sequence;
  const shared = useSharedValue(chartInteractionState(presentation, gate.epoch));
  const foreground = useForegroundActivity();
  const { view: { start, end }, domain: { start: domainStart, end: domainEnd }, cursor, reference } = presentation;
  useLayoutEffect(() => {
    const view = { start, end }, domain = { start: domainStart, end: domainEnd };
    gate.enabled = foreground;
    const nextScope = `${scope}:${foreground}`;
    if (gate.scope !== nextScope) {
      gate.scope = nextScope; gate.invalidate();
      // Synchronize native packet ownership with the imperative UI gate on lifecycle changes.
      setEpoch(gate.epoch);
    }
    if (synchronizedEpoch.current !== gate.epoch) {
      synchronizedEpoch.current = gate.epoch;
      scheduleOnUI((epoch: number, initial: ChartPresentation) => { 'worklet'; shared.value = chartInteractionState(initial, epoch); }, gate.epoch, { view, domain, cursor, reference });
    }
    scheduleOnUI((epoch: number, acknowledged: number, incoming: ChartPresentation) => {
      'worklet'; shared.value = synchronizeChartInteraction(shared.value, incoming, epoch, acknowledged);
    }, gate.epoch, acknowledgedSequence, { view, domain, cursor, reference });
  }, [gate, shared, epoch, foreground, scope, acknowledgedSequence, start, end, domainStart, domainEnd, cursor, reference]);
  useLayoutEffect(() => () => { gate.enabled = false; gate.invalidate(); }, [gate]);
  const receive = useCallback((event: ChartInteractionEvent) => {
    if (!gate.accept(event)) return;
    if (event.kind === 'cursor') { gate.navigating = false; inspect(event.cursor, !event.final, event.snap); }
    else {
      if (!gate.navigating) inspect(null);
      gate.navigating = !event.final;
      changeViewport(event.view, !event.final);
    }
  }, [gate, inspect, changeViewport]);
  const cancel = useCallback(() => {
    gate.invalidate(); setEpoch(gate.epoch);
    // The next layout commit resets UI ownership using the resulting control
    // state, never the old null cursor followed by a same-sequence selection.
  }, [gate]);
  return { shared, receive, cancel, epoch, sequence: acknowledgedSequence };
  /* eslint-enable react-hooks/refs */
}
export type ChartInteraction = ReturnType<typeof useChartInteraction>;
