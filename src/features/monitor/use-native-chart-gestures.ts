import { useMemo } from 'react';
import { CHART_INSET } from '../../core/chart-viewport';
import { Gesture } from 'react-native-gesture-handler';
import { useSharedValue, type SharedValue } from 'react-native-reanimated';
import { scheduleOnRN } from 'react-native-worklets';
import { beginChartTransform, moveChartTransform, type ChartTransform, type ChartTouch } from '../../core/chart-touch';
import { moveChartInteraction } from '../../core/monitor-interaction';
import { hitMonitorScene, type MonitorHitScene } from '../../core/monitor-hit-test';
import type { ChartInteraction } from './use-chart-interaction';

export function useNativeChartGestures(interaction: ChartInteraction, plotWidth: number, hitScene?: SharedValue<MonitorHitScene | null>) {
  const touches = useSharedValue<{ multi: boolean; transform: ChartTransform | null; epoch: number; navigation: boolean }>({ multi: false, transform: null, epoch: 0, navigation: false });
  const { shared, receive } = interaction;
  // RNGH stores these worklet callbacks. They mutate UI shared values and read
  // the clock only in touch events; none of these callbacks runs during render.
  /* eslint-disable react-hooks/immutability, react-hooks/purity */
  return useMemo(() => {
    const cursor = (x: number, y: number, final: boolean) => {
      'worklet';
      const state = shared.value;
      if (touches.value.multi || touches.value.epoch !== state.epoch || !Number.isFinite(x)) return;
      const seconds = state.view.start + Math.max(0, Math.min(1, (x - CHART_INSET.left) / plotWidth)) * (state.view.end - state.view.start);
      const snap = hitMonitorScene(hitScene?.value ?? null, state.view, plotWidth, x, y, state.snap);
      const update = moveChartInteraction(state, 'cursor', state.view, snap?.point.elapsedSeconds ?? seconds, Date.now(), final, snap);
      shared.value = update.state;
      if (update.event) scheduleOnRN(receive, update.event);
    };
    const finishNavigation = (terminalTouches?: readonly ChartTouch[]) => {
      'worklet';
      const session = touches.value;
      if (!session.navigation || session.epoch !== shared.value.epoch) return;
      touches.value = { ...session, navigation: false, transform: null };
      const state = shared.value;
      const view = session.transform && terminalTouches ? moveChartTransform(session.transform, terminalTouches) ?? state.view : state.view;
      const update = moveChartInteraction(state, 'viewport', view, null, Date.now(), true);
      shared.value = update.state;
      if (update.event) scheduleOnRN(receive, update.event);
    };
    const navigation = Gesture.Pan().minPointers(2).maxPointers(2).minDistance(0).averageTouches(true)
      .onTouchesDown(event => {
        const state = shared.value;
        if (event.numberOfTouches === 1) touches.value = { multi: false, transform: null, epoch: state.epoch, navigation: false };
        else if (event.numberOfTouches === 2) {
          const transform = beginChartTransform(event.allTouches, state.view, state.domain, CHART_INSET.left, plotWidth);
          touches.value = { multi: true, transform, epoch: state.epoch, navigation: transform !== null };
          if (transform) {
            const update = moveChartInteraction(state, 'viewport', state.view, null, Date.now(), false);
            shared.value = update.state;
            if (update.event) scheduleOnRN(receive, update.event);
          }
        } else finishNavigation();
      })
      .onTouchesMove(event => {
        const session = touches.value;
        if (!session.navigation || !session.transform || event.numberOfTouches !== 2 || session.epoch !== shared.value.epoch) return;
        const next = moveChartTransform(session.transform, event.allTouches);
        if (!next) return;
        const update = moveChartInteraction(shared.value, 'viewport', next, null, Date.now(), false);
        shared.value = update.state;
        if (update.event) scheduleOnRN(receive, update.event);
      })
      .onTouchesUp(event => finishNavigation(event.allTouches))
      .onTouchesCancelled(event => finishNavigation(event.allTouches))
      .onFinalize(() => finishNavigation());
    const inspect = Gesture.Pan().maxPointers(1).activeOffsetX([-8, 8]).failOffsetY([-8, 8])
      .onStart(event => { if (event.numberOfPointers === 1) cursor(event.x, event.y, false); })
      .onUpdate(event => { if (event.numberOfPointers === 1) cursor(event.x, event.y, false); })
      .onFinalize(event => {
        const state = shared.value;
        if (state.active !== 1 || touches.value.epoch !== state.epoch || touches.value.multi) return;
        if (Number.isFinite(event.x)) { cursor(event.x, event.y, true); return; }
        const update = moveChartInteraction(state, 'cursor', state.view, state.cursor, Date.now(), true, state.snap);
        shared.value = update.state;
        if (update.event) scheduleOnRN(receive, update.event);
      });
    const tap = Gesture.Tap().maxDistance(8).onEnd((event, success) => {
      if (!success || touches.value.multi || touches.value.epoch !== shared.value.epoch) return;
      if (shared.value.cursor === null) cursor(event.x, event.y, true);
      else {
        const state = shared.value, update = moveChartInteraction(state, 'cursor', state.view, null, Date.now(), true);
        shared.value = update.state;
        if (update.event) scheduleOnRN(receive, update.event);
      }
    });
    return Gesture.Simultaneous(navigation, Gesture.Race(inspect, tap));
  }, [plotWidth, hitScene, shared, touches, receive]);
  /* eslint-enable react-hooks/immutability, react-hooks/purity */
}
