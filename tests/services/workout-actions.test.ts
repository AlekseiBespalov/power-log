import { describe, expect, it, vi } from 'vitest';
import { ArchiveActions, WorkoutActions } from '../../src/services/workout-actions';
function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>(yes => {
    resolve = yes;
  });
  return { promise, resolve };
}
describe('workout action lifecycle', () => {
  it('keeps check/stop available during a held start, but does not overlap recovery controls or release the start early', async () => {
    const actions = new WorkoutActions(() => {}),
      start = deferred(),
      recovery = deferred(),
      refresh = vi.fn(async () => {});
    const first = actions.run(() => start.promise, refresh, 'start');
    expect(actions.snapshot()).toMatchObject({ busy: true, recoveryAvailable: true });
    const second = actions.run(() => recovery.promise, refresh);
    expect(actions.snapshot()).toMatchObject({ busy: true, recoveryAvailable: false });
    const duplicate = vi.fn(async () => {});
    await actions.run(duplicate, refresh);
    expect(duplicate).not.toHaveBeenCalled();
    recovery.resolve();
    await second;
    expect(actions.snapshot()).toMatchObject({ busy: true, recoveryAvailable: true });
    start.resolve();
    await first;
    expect(actions.snapshot()).toMatchObject({ busy: false, recoveryAvailable: false });
    expect(refresh).toHaveBeenCalledTimes(2);
  });
  it('refreshes state after failure and preserves the original operation error if refresh also fails', async () => {
    const actions = new WorkoutActions(() => {}),
      refresh = vi.fn(async () => {
        throw new Error('Catalog or state failure');
      });
    await actions.run(async () => {
      throw new Error('Original owner unavailable');
    }, refresh);
    expect(refresh).toHaveBeenCalledOnce();
    expect(actions.snapshot()).toEqual({ busy: false, recoveryAvailable: false, error: 'Original owner unavailable' });
  });
});

describe('independent archive actions', () => {
  it('leaves ride controls idle during an archive export and runs pause without waiting for it', async () => {
    const archive = new ArchiveActions(() => {}),
      ride = new WorkoutActions(() => {}),
      held = deferred();
    const exporting = archive.run('saved-ride', () => held.promise);
    expect(archive.snapshot().get('saved-ride')?.pending).toBe(true);
    expect(ride.snapshot().busy).toBe(false);
    const pause = vi.fn(async () => {});
    await ride.run(pause, async () => {});
    expect(pause).toHaveBeenCalledOnce();
    expect(ride.snapshot().busy).toBe(false);
    expect(archive.snapshot().get('saved-ride')?.pending).toBe(true);
    held.resolve();
    await exporting;
    expect(archive.snapshot().has('saved-ride')).toBe(false);
  });
  it('does not overlap two ride commands and accepts the second after the first completes', async () => {
    const ride = new WorkoutActions(() => {}),
      held = deferred(),
      resume = vi.fn(async () => {});
    const pausing = ride.run(
      () => held.promise,
      async () => {},
    );
    await ride.run(resume, async () => {});
    expect(resume).not.toHaveBeenCalled();
    held.resolve();
    await pausing;
    await ride.run(resume, async () => {});
    expect(resume).toHaveBeenCalledOnce();
  });
  it('isolates pending and error state by record and prevents duplicate operations on the same record', async () => {
    const archive = new ArchiveActions(() => {}),
      held = deferred(),
      duplicate = vi.fn(async () => {});
    const first = archive.run('first', () => held.promise);
    await archive.run('first', duplicate);
    expect(duplicate).not.toHaveBeenCalled();
    await archive.run('second', async () => {
      throw new Error('Export failed');
    });
    expect(archive.snapshot().get('first')).toEqual({ pending: true, error: null });
    expect(archive.snapshot().get('second')).toEqual({ pending: false, error: 'Export failed' });
    archive.clearError('second');
    expect(archive.snapshot().has('second')).toBe(false);
    held.resolve();
    await first;
  });
});
