import { describe, expect, it, vi } from 'vitest';
import { WorkoutActions } from '../../src/services/workout-actions';
function deferred() { let resolve!: () => void; const promise = new Promise<void>(yes => { resolve = yes; }); return { promise, resolve }; }
describe('workout action lifecycle', () => {
  it('keeps check/stop available during a held start, but does not overlap recovery controls or release the start early', async () => {
    const actions = new WorkoutActions(() => {}), start = deferred(), recovery = deferred(), refresh = vi.fn(async () => {});
    const first = actions.run(() => start.promise, refresh, 'start');
    expect(actions.snapshot()).toMatchObject({ busy: true, recoveryAvailable: true });
    const second = actions.run(() => recovery.promise, refresh);
    expect(actions.snapshot()).toMatchObject({ busy: true, recoveryAvailable: false });
    const duplicate = vi.fn(async () => {}); await actions.run(duplicate, refresh); expect(duplicate).not.toHaveBeenCalled();
    recovery.resolve(); await second;
    expect(actions.snapshot()).toMatchObject({ busy: true, recoveryAvailable: true });
    start.resolve(); await first;
    expect(actions.snapshot()).toMatchObject({ busy: false, recoveryAvailable: false });
    expect(refresh).toHaveBeenCalledTimes(2);
  });
  it('refreshes state after failure and preserves the original operation error if refresh also fails', async () => {
    const actions = new WorkoutActions(() => {}), refresh = vi.fn(async () => { throw new Error('Catalog or state failure'); });
    await actions.run(async () => { throw new Error('Original owner unavailable'); }, refresh);
    expect(refresh).toHaveBeenCalledOnce();
    expect(actions.snapshot()).toEqual({ busy: false, recoveryAvailable: false, error: 'Original owner unavailable' });
  });
});
