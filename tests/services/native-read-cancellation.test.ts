import { afterEach, expect, it, vi } from 'vitest';
import { workouts } from '../../src/services/workouts.native';
import { nativeMonitorSource } from '../../src/services/monitor-source.native';
import { MonitorReadController } from '../../src/features/monitor/monitor-read-controller';
import { useReadResource } from '../../src/services/use-read-resource';
import { ReadCancelled, ReadScheduler } from '../../src/services/read-scheduler';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { failureCode } from '../../src/core/failures';
import { syntheticSample } from '../fixtures/synthetic-sample';

const native = vi.hoisted(() => ({
  readWorkout: vi.fn(),
  cancelWorkoutRead: vi.fn(),
  describeMonitorSource: vi.fn(),
  readMonitorLatest: vi.fn(),
  readMonitorPlot: vi.fn(),
  readMonitorRangeStats: vi.fn(),
  inspectMonitorAt: vi.fn(),
  cancelMonitorRead: vi.fn(),
}));
const hooks = vi.hoisted(() => ({ cleanups: [] as (() => void)[] }));
vi.mock('react', () => ({
  useState: (initial: unknown) => [typeof initial === 'function' ? initial() : initial, () => {}],
  useRef: (current: unknown) => ({ current }),
  useCallback: (callback: unknown) => callback,
  useEffect: (effect: () => (() => void) | undefined) => {
    const cleanup = effect();
    if (cleanup) hooks.cleanups.push(cleanup);
  },
}));
vi.mock('../../src/services/use-foreground-activity', () => ({ useForegroundActivity: () => true }));
vi.mock('../../modules/power-log-bridge', () => ({ default: native }));
vi.mock('../../src/services/native-monotonic-clock', () => ({
  nativeMonotonicClock: { ready: async () => {}, toJS: (value: number) => value },
}));
afterEach(() => {
  hooks.cleanups.splice(0).forEach(cleanup => cleanup());
  vi.clearAllMocks();
  vi.useRealTimers();
});
const flush = async () => {
  for (let i = 0; i < 30; i++) await Promise.resolve();
};

it.each([
  Object.assign(new Error('Choose a smaller range with fewer chart discontinuities.'), { code: 'ERR_CHART_CAPACITY' }),
  { code: 'ERR_CHART_CAPACITY', message: 'Choose a smaller range with fewer chart discontinuities.' },
])('presents native chart capacity through the monitor controller and recovers after narrowing: %j', async error => {
  const source = new TelemetryMonitor(
    'workout:a',
    false,
    [0, 1, 2].map(time => syntheticSample(time, time, new Date(Date.UTC(2026, 0, 1) + time * 1000).toISOString())),
  );
  native.describeMonitorSource.mockImplementation(request => source.describeSource(request));
  native.readMonitorLatest.mockImplementation(request => source.readLatest(request));
  native.readMonitorPlot.mockImplementation(request => source.readPlot(request));
  native.readMonitorRangeStats.mockImplementation(request => source.rangeStats(request));
  const controller = new MonitorReadController(nativeMonitorSource('workout', 'a', false));
  try {
    controller.configure(['humanPowerW'], 'all');
    controller.refresh();
    await flush();
    const series = controller.getSnapshot().data!.series;
    native.readMonitorPlot.mockRejectedValueOnce(error);
    controller.changeViewport({ start: 0, end: 1.5 });
    await flush();
    expect(controller.getSnapshot()).toMatchObject({
      errors: { plot: 'Choose a smaller range with fewer chart discontinuities.' },
      loading: { plot: false },
      deferred: { plot: false },
    });
    expect(failureCode(error)).toBe('chartCapacity');
    expect(controller.getSnapshot().data!.series).toBe(series);
    controller.changeViewport({ start: 0, end: 1 });
    await flush();
    expect(controller.getSnapshot().errors.plot).toBeUndefined();
    expect(controller.getSnapshot().data!.plotViewport).toEqual({ start: 0, end: 1 });
  } finally {
    controller.dispose();
  }
});

it('cancels the native summary through the production consumer scheduler and releases replacement work', async () => {
  const scheduler = new ReadScheduler();
  let reject!: (error: unknown) => void;
  native.readWorkout.mockImplementationOnce(
    () =>
      new Promise((_, fail) => {
        reject = fail;
      }),
  );
  native.cancelWorkoutRead.mockImplementation(() => reject({ code: 'ERR_READ_CANCELLED' }));
  const first = scheduler.schedule('summary', 'history', 'summary', signal => workouts.read('a', 'auto', signal));
  const outcome = first.catch(error => error);
  scheduler.cancel('history');
  expect(await outcome).toBeInstanceOf(ReadCancelled);
  expect(native.readWorkout.mock.calls[0]).toEqual(['a', 'auto', expect.any(String)]);
  expect(native.cancelWorkoutRead).toHaveBeenCalledExactlyOnceWith(native.readWorkout.mock.calls[0]![2]);
  await flush();
  expect(await scheduler.schedule('summary', 'history', 'summary', async () => 'replacement')).toBe('replacement');
});

it('source replacement and deactivation cancel actual native monitor calls', async () => {
  vi.useFakeTimers();
  const pending = new Map<string, (reason: unknown) => void>();
  const hold = (request: { cancellationId: string }) =>
    new Promise((_, reject) => pending.set(request.cancellationId, reject));
  native.describeMonitorSource.mockImplementation(request =>
    Promise.resolve({
      status: 'ok',
      sourceId: 'workout:a',
      revision: '1',
      generation: request.generation,
      startedAt: '2026-01-01T00:00:00Z',
      domain: { start: 0, end: 10 },
      availableMetrics: ['humanPowerW'],
    }),
  );
  native.readMonitorLatest.mockImplementation(hold);
  native.readMonitorPlot.mockImplementation(hold);
  native.readMonitorRangeStats.mockImplementation(hold);
  native.cancelMonitorRead.mockImplementation(id => pending.get(id)?.({ code: 'ERR_READ_CANCELLED' }));
  const controller = new MonitorReadController(nativeMonitorSource('workout', 'a', false));
  try {
    controller.configure(['humanPowerW'], 'all');
    controller.refresh();
    await flush();
    expect(native.readMonitorPlot).toHaveBeenCalled();
    const ids = [...pending.keys()];
    expect(ids.length).toBeGreaterThanOrEqual(2);
    expect(ids.every(id => typeof id === 'string')).toBe(true);
    controller.updateSource(nativeMonitorSource('workout', 'b', false));
    await flush();
    for (const id of ids) expect(native.cancelMonitorRead).toHaveBeenCalledWith(id);
    native.describeMonitorSource.mockImplementation(hold);
    controller.refresh();
    await flush();
    const id = native.describeMonitorSource.mock.calls.at(-1)![0].cancellationId;
    controller.deactivate();
    await flush();
    expect(native.cancelMonitorRead).toHaveBeenCalledWith(id);
  } finally {
    controller.dispose();
  }
});

it('the History resource cleanup forwards cancellation into the native summary adapter', async () => {
  let reject!: (reason: unknown) => void;
  native.readWorkout.mockImplementationOnce(
    () =>
      new Promise((_, fail) => {
        reject = fail;
      }),
  );
  native.cancelWorkoutRead.mockImplementation(() => reject({ code: 'ERR_READ_CANCELLED' }));
  useReadResource('history-ride', '1', (_consumer, _current, signal) => workouts.read('history-ride', 'auto', signal));
  await flush();
  const id = native.readWorkout.mock.calls[0]![2];
  expect(id).toEqual(expect.any(String));
  hooks.cleanups.pop()!();
  await flush();
  expect(native.cancelWorkoutRead).toHaveBeenCalledExactlyOnceWith(id);
});

it('backs off busy History reads and resets after a revision change', async () => {
  vi.useFakeTimers();
  native.readWorkout.mockRejectedValue({ code: 'ERR_STORAGE_BUSY' });
  const load = (_consumer: string, _current: () => boolean, signal?: AbortSignal) =>
    workouts.read('history-ride', 'auto', signal);
  useReadResource('history-ride', '1', load);
  await flush();
  expect(native.readWorkout).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1000);
  expect(native.readWorkout).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(1999);
  expect(native.readWorkout).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(1);
  expect(native.readWorkout).toHaveBeenCalledTimes(3);
  hooks.cleanups.pop()!();
  useReadResource('history-ride', '2', load);
  await flush();
  expect(native.readWorkout).toHaveBeenCalledTimes(4);
  await vi.advanceTimersByTimeAsync(999);
  expect(native.readWorkout).toHaveBeenCalledTimes(4);
  await vi.advanceTimersByTimeAsync(1);
  expect(native.readWorkout).toHaveBeenCalledTimes(5);
});
