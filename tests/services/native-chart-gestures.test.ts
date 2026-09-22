import { beforeEach, describe, expect, it, vi } from 'vitest';
import { chartInteractionState } from '../../src/core/monitor-interaction';
import { useNativeChartGestures } from '../../src/features/monitor/use-native-chart-gestures';
import type { MonitorHitScene } from '../../src/core/monitor-hit-test';
import type { SharedValue } from 'react-native-reanimated';
import type { ChartInteraction } from '../../src/features/monitor/use-chart-interaction';

// Execute the real recognizer callbacks with deterministic UI shared values.
// This tests callback wiring; native recognition/thread placement is checked on device.
const harness = vi.hoisted(() => {
  type Callback = (event: Record<string, unknown>, success?: boolean) => void;
  const builders: { kind: string; callbacks: Record<string, Callback> }[] = [];
  const make = (kind: string) => {
    const callbacks: Record<string, Callback> = {};
    const builder: object = new Proxy({}, { get: (_target, property: string) => (argument: Callback) => {
      if (property.startsWith('on')) callbacks[property] = argument;
      return builder;
    } });
    builders.push({ kind, callbacks }); return builder;
  };
  return { builders, make };
});
vi.mock('react', () => ({ useMemo: (factory: () => unknown) => factory() }));
vi.mock('react-native-reanimated', () => ({ useSharedValue: (value: unknown) => ({ value }) }));
vi.mock('react-native-worklets', () => ({ scheduleOnRN: (callback: (event: unknown) => void, event: unknown) => callback(event) }));
vi.mock('react-native-gesture-handler', () => ({ Gesture: { Pan: () => harness.make('pan'), Tap: () => harness.make('tap'), Simultaneous: (...items: unknown[]) => items, Race: (...items: unknown[]) => items } }));

function setup(hitScene?: MonitorHitScene) {
  const shared = { value: chartInteractionState({ view: { start: 20, end: 80 }, domain: { start: 0, end: 100 }, cursor: null, reference: null }, 7) };
  const receive = vi.fn();
  // The hook primitives above are mocked to execute the real callback builder.
  // eslint-disable-next-line react-hooks/rules-of-hooks
  useNativeChartGestures({ shared, receive, cancel: vi.fn() } as unknown as ChartInteraction, 200, hitScene ? { value: hitScene } as SharedValue<MonitorHitScene | null> : undefined);
  return { shared, receive, nav: harness.builders[0]!.callbacks, inspect: harness.builders[1]!.callbacks, tap: harness.builders[2]!.callbacks };
}
const pair = [{ id: 4, x: 14, y: 40 }, { id: 9, x: 114, y: 40 }];
describe('production native gesture callbacks', () => {
  beforeEach(() => harness.builders.splice(0));
  it('uses finger y and the terminal event to select a subpixel original peak with identity', () => {
    const point = { elapsedSeconds: 50.125, value: 900, observationId: 'peak', timestamp: '2026-01-01T00:00:50.125Z', exactValue: '900' };
    const { shared, receive, nav, inspect } = setup({ key: 'view:1', sourceId: 'ride', revision: '7', height: 134, min: 0, max: 1000,
      series: [{ metric: 'humanPowerW', scale: 1, points: [{ ...point, elapsedSeconds: 50, value: 100, observationId: 'low' }, point] }] });
    nav.onTouchesDown!({ numberOfTouches: 1 }); inspect.onStart!({ numberOfPointers: 1, x: 68, y: 98 });
    expect(shared.value.snap).toBeNull();
    inspect.onFinalize!({ x: 108, y: 18 });
    expect(shared.value.cursor).toBe(50.125); expect(shared.value.snap!.point).toBe(point);
    expect(receive).toHaveBeenLastCalledWith(expect.objectContaining({ final: true, cursor: 50.125, snap: expect.objectContaining({ metric: 'humanPowerW', point }) }));
  });
  it('flushes a newer cursor position carried only by finger release', () => {
    const { shared, receive, nav, inspect } = setup();
    nav.onTouchesDown!({ numberOfTouches: 1 });
    inspect.onStart!({ numberOfPointers: 1, x: 18 });
    expect(shared.value.cursor).toBe(23);
    inspect.onFinalize!({ x: 108 });
    expect(shared.value.cursor).toBe(50);
    expect(receive).toHaveBeenLastCalledWith(expect.objectContaining({ final: true, cursor: 50 }));
  });
  it('uses terminal touch coordinates for pan settlement and suppresses the remaining finger', () => {
    const { shared, receive, nav, inspect, tap } = setup();
    nav.onTouchesDown!({ numberOfTouches: 1 }); nav.onTouchesDown!({ numberOfTouches: 2, allTouches: pair });
    nav.onTouchesUp!({ allTouches: pair.map(point => ({ ...point, x: point.x + 20 })) });
    expect(shared.value.view.start).toBeCloseTo(14); expect(shared.value.view.end).toBeCloseTo(74);
    expect(receive).toHaveBeenLastCalledWith(expect.objectContaining({ final: true, view: shared.value.view }));
    const calls = receive.mock.calls.length;
    inspect.onUpdate!({ numberOfPointers: 1, x: 124 }); tap.onEnd!({ x: 124 }, true); nav.onFinalize!({});
    expect(receive).toHaveBeenCalledTimes(calls);
    nav.onTouchesDown!({ numberOfTouches: 1 }); tap.onEnd!({ x: 108 }, true);
    expect(receive.mock.calls.length).toBe(calls + 1);
  });
  it('settles cancellation once and rejects all callbacks after a scope/foreground reset', () => {
    const { shared, receive, nav, inspect, tap } = setup();
    nav.onTouchesDown!({ numberOfTouches: 1 }); nav.onTouchesDown!({ numberOfTouches: 2, allTouches: pair });
    nav.onTouchesCancelled!({ allTouches: pair }); const settled = receive.mock.calls.length;
    nav.onFinalize!({}); expect(receive).toHaveBeenCalledTimes(settled);
    nav.onTouchesDown!({ numberOfTouches: 1 }); inspect.onStart!({ numberOfPointers: 1, x: 64 });
    shared.value = chartInteractionState(shared.value, 8); const before = receive.mock.calls.length;
    inspect.onUpdate!({ numberOfPointers: 1, x: 114 }); inspect.onFinalize!({ x: 124 }); tap.onEnd!({ x: 134 }, true);
    expect(receive).toHaveBeenCalledTimes(before);
  });
});
