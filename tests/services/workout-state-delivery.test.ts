import { describe, expect, it, vi } from 'vitest';
import { WorkoutStateDelivery, workoutHistoryRevision } from '../../src/services/workout-state-delivery';
import { HistoryCatalog } from '../../src/services/history-catalog';
import { unavailableWorkoutState, type WorkoutState } from '../../src/core/workouts';

const state = (phase: WorkoutState['phase']): WorkoutState => ({ ...unavailableWorkoutState, phase });

describe('workout state delivery', () => {
  it('bounds ordinary updates, delivers controls immediately, and resumes once after hidden events', async () => {
    vi.useFakeTimers();
    const displayed: WorkoutState[] = [], delivery = new WorkoutStateDelivery(value => displayed.push(value));
    try {
      for (let i = 0; i < 32; i++) { delivery.receive({ ...state('running'), timerSeconds: i / 8 }); await vi.advanceTimersByTimeAsync(125); }
      expect(displayed.length).toBeLessThanOrEqual(5);
      delivery.receive(state('paused')); expect(displayed.at(-1)?.phase).toBe('paused');
      delivery.setActive(false); const count = displayed.length;
      for (let i = 0; i < 80; i++) delivery.receive({ ...state('running'), timerSeconds: i });
      await vi.advanceTimersByTimeAsync(5000); expect(displayed).toHaveLength(count); expect(vi.getTimerCount()).toBe(0);
      delivery.setActive(true); expect(displayed).toHaveLength(count + 1); expect(displayed.at(-1)?.timerSeconds).toBe(79);
    } finally { delivery.setActive(false); vi.useRealTimers(); }
  });
  it('defers hidden catalog refreshes and cancels retries until it becomes visible', async () => {
    const load = vi.fn(async () => [{ id: 'ride', startedAt: '2026-01-01T00:00:00Z' }]);
    const catalog = new HistoryCatalog([load], false);
    for (let i = 0; i < 20; i++) await catalog.refresh();
    expect(load).not.toHaveBeenCalled();
    catalog.setActive(true); for (let i = 0; i < 20; i++) await Promise.resolve();
    expect(load).toHaveBeenCalledTimes(1); expect(catalog.getSnapshot().records).toHaveLength(1);
    catalog.setActive(false); await catalog.refresh(); expect(load).toHaveBeenCalledTimes(1);
    catalog.setActive(true); for (let i = 0; i < 20; i++) await Promise.resolve();
    expect(load).toHaveBeenCalledTimes(2); catalog.invalidate();
  });
  it('keeps completion when an older saving refresh resolves after a native event', async () => {
    const displayed: string[] = [];
    const delivery = new WorkoutStateDelivery(value => displayed.push(value.phase));
    let resolve!: (value: WorkoutState) => void;
    const nativeSnapshot = new Promise<WorkoutState>(done => { resolve = done; });
    const request = delivery.beginRefresh();
    const refresh = nativeSnapshot.then(value => delivery.finishRefresh(request, value));
    delivery.receive(state('completed'));
    resolve(state('finishing'));
    await refresh;
    expect(displayed).toEqual(['completed']);
  });
  it('accepts a refresh with no newer event and ignores a superseded refresh', () => {
    const displayed: string[] = [];
    const delivery = new WorkoutStateDelivery(value => displayed.push(value.phase));
    const old = delivery.beginRefresh(), latest = delivery.beginRefresh();
    delivery.finishRefresh(latest, state('completed'));
    delivery.finishRefresh(old, state('finishing'));
    expect(displayed).toEqual(['completed']);
  });
  it('invalidates a pending finishing catalog when a Watch discard returns the owner to idle', async () => {
    const ride = { id: 'discarded', startedAt: '2026-09-11T13:11:00Z' };
    let resolve!: (rows: typeof ride[]) => void;
    const snapshot = new Promise<typeof ride[]>(done => { resolve = done; });
    let calls = 0;
    const catalog = new HistoryCatalog([async () => ++calls === 1 ? snapshot : []]);
    const finishing = { ...state('finishing'), id: ride.id };
    const first = catalog.refresh();
    const discarded = { ...state('idle'), historyRevision: 'discard-1', lastDeletedWorkoutId: ride.id };
    expect(workoutHistoryRevision(discarded)).not.toBe(workoutHistoryRevision(finishing));
    const refresh = catalog.refresh();
    resolve([ride]);
    await Promise.all([first, refresh]);
    expect(catalog.getSnapshot().records).toEqual([]);
    expect(calls).toBe(2);
  });
  it('invalidates historical deletions without reloading the catalog on every active sample', () => {
    const active = { ...state('running'), id: 'other-ride', collectionRevision: 50, historyRevision: 'deletion-1' };
    expect(workoutHistoryRevision({ ...active, collectionRevision: 51, timerSeconds: 100 })).toBe(workoutHistoryRevision(active));
    expect(workoutHistoryRevision({ ...active, historyRevision: 'deletion-2' })).not.toBe(workoutHistoryRevision(active));
    const completed = { ...active, phase: 'completed' as const };
    expect(workoutHistoryRevision({ ...completed, collectionRevision: 51 })).not.toBe(workoutHistoryRevision(completed));
  });
});
