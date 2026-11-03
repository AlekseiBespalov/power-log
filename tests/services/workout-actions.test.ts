import { afterEach, describe, expect, it, vi } from 'vitest';
import { presentationFailure } from '../../shared/js';
import failures from '../fixtures/contract/presentation/failures.json';
import { ExportError } from '../../src/core/export/types';
import { RideFailureError } from '../../src/core/failures';
import { ArchiveActions, WorkoutActions } from '../../src/services/workout-actions';
afterEach(() => vi.useRealTimers());
function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>(yes => {
    resolve = yes;
  });
  return { promise, resolve };
}
describe('workout action lifecycle', () => {
  it('bounds a hung state refresh and ignores its late error during a later flight', async () => {
    vi.useFakeTimers();
    const actions = new WorkoutActions(() => {});
    let reject!: (error: Error) => void;
    const old = actions.run(
      async () => {},
      () =>
        new Promise<void>((_, fail) => {
          reject = fail;
        }),
    );
    await vi.advanceTimersByTimeAsync(15000);
    expect(actions.snapshot().busy).toBe(false);
    await old;
    const next = deferred();
    const current = actions.run(
      () => next.promise,
      async () => {},
    );
    reject(new Error('Late refresh'));
    await Promise.resolve();
    expect(actions.snapshot()).toMatchObject({ busy: true, error: null });
    next.resolve();
    await current;
    expect(actions.snapshot().busy).toBe(false);
  });
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
      throw new RideFailureError('watchStartUnconfirmed');
    }, refresh);
    expect(refresh).toHaveBeenCalledOnce();
    expect(actions.snapshot()).toEqual({
      busy: false,
      recoveryAvailable: false,
      error: presentationFailure('watchStartUnconfirmed').text,
    });
  });
});

const coded = (code: string | undefined, message: string) => Object.assign(new Error(message), { code });
const deviceRejections = [
  {
    name: 'locked Watch at Start',
    error: coded(
      'watchLaunchFailed',
      'UnexpectedException: The operation couldn’t be completed. Unable to open URLs while locked or passcode compliance is pending (at ExpoModulesCore/Promise.swift:56)',
    ),
    text: "Power Log couldn't open on your Apple Watch. Unlock your Apple Watch and keep it near your iPhone, then start again.",
  },
  {
    name: 'retained owner timing at Finish',
    error: coded(
      'unknown',
      'BridgeException: Invalid retained owner timing (at PowerLogBridge/PowerLogBridgeModule.swift:11)',
    ),
    text: presentationFailure('unknown').text,
  },
  {
    name: 'an exception without a vocabulary code',
    error: coded(
      'E_POWER_LOG',
      'BridgeException: Invalid retained owner timing (at PowerLogBridge/PowerLogBridgeModule.swift:11)',
    ),
    text: presentationFailure('unknown').text,
  },
  {
    name: 'an uncoded exception',
    error: new Error('UnexpectedException: … (at ExpoModulesCore/Promise.swift:56)'),
    text: presentationFailure('unknown').text,
  },
  {
    name: 'a deleted ride read',
    error: coded('ERR_RIDE_DELETED', 'Ride was deleted'),
    text: presentationFailure('rideDeleted').text,
  },
];

describe('rider-facing action failures', () => {
  it.each(deviceRejections)('renders $name only as shared plain text', async ({ error, text }) => {
    const ride = new WorkoutActions(() => {}),
      archive = new ArchiveActions(() => {});
    await ride.run(
      async () => {
        throw error;
      },
      async () => {},
    );
    await archive.run('saved-ride', async () => {
      throw error;
    });
    for (const shown of [ride.snapshot().error, archive.snapshot().get('saved-ride')?.error]) {
      expect(shown).toBe(text);
      for (const forbidden of failures.forbidden) expect(shown).not.toContain(forbidden);
    }
  });

  it('keeps the original operation failure when the refresh after it also fails', async () => {
    const ride = new WorkoutActions(() => {});
    await ride.run(
      async () => {
        throw coded('watchLaunchFailed', 'UnexpectedException: (at ExpoModulesCore/Promise.swift:56)');
      },
      async () => {
        throw coded('storageBusy', 'BridgeException: busy (at PowerLogBridge/PowerLogBridgeModule.swift:11)');
      },
    );
    expect(ride.snapshot().error).toBe(presentationFailure('watchLaunchFailed').text);
  });

  it('shows an export failure as its export text and a file-import failure as the caller sentence', async () => {
    const archive = new ArchiveActions(() => {});
    await archive.run('export', async () => {
      throw new ExportError('changed', 'This ride changed while it was exporting. Export it again.');
    });
    await archive.run(
      'csv',
      async () => {
        throw new Error('Invalid CSV numeric field controller.speedRaw');
      },
      "Power Log couldn't open this CSV.",
    );
    await archive.run(
      'csv-storage',
      async () => {
        throw coded('storageFull', 'SQLITE_FULL');
      },
      "Power Log couldn't open this CSV.",
    );
    expect(archive.snapshot().get('export')?.error).toBe('This ride changed while it was exporting. Export it again.');
    expect(archive.snapshot().get('csv')?.error).toBe("Power Log couldn't open this CSV.");
    expect(archive.snapshot().get('csv-storage')?.error).toBe(presentationFailure('storageFull').text);
  });

  it('shows an unreadable ride page or a lost export position as a plain sentence, not the internal detail', async () => {
    const archive = new ArchiveActions(() => {});
    await archive.run('page', async () => {
      throw new ExportError(
        'page',
        'The ride data could not be read for export: a telemetry page has no valid cursor.',
      );
    });
    await archive.run('cursor', async () => {
      throw new ExportError('cursor', 'The telemetry cursor changed its shape.');
    });
    expect(archive.snapshot().get('page')?.error).toBe(
      "Power Log couldn't read this ride for export. Export it again, and report it if this keeps happening.",
    );
    expect(archive.snapshot().get('cursor')?.error).toBe('The export lost its place in the ride. Export it again.');
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
      throw new RideFailureError('storageFull');
    });
    expect(archive.snapshot().get('first')).toEqual({ pending: true, error: null });
    expect(archive.snapshot().get('second')).toEqual({
      pending: false,
      error: presentationFailure('storageFull').text,
    });
    archive.clearError('second');
    expect(archive.snapshot().has('second')).toBe(false);
    held.resolve();
    await first;
  });
});
