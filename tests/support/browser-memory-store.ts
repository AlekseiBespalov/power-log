import { vi } from 'vitest';
import { BrowserRideStore, type BrowserRide } from '../../src/services/browser-ride-store';
import { browserRide } from './browser-ride';

export function browserMemoryStore(store = new BrowserRideStore(), initial: BrowserRide = browserRide()) {
  let record = initial;
  let current: BrowserRide | null = null;
  vi.spyOn(store, 'interruptCurrent').mockImplementation(async () => {
    if (current) record = current = { ...record, phase: 'interrupted', interrupted: true };
    return current;
  });
  vi.spyOn(store, 'current').mockImplementation(async () => current);
  vi.spyOn(store, 'claim').mockImplementation(async (_id, token) => (record = current = { ...record, writer: token }));
  vi.spyOn(store, 'get').mockImplementation(async () => record);
  vi.spyOn(store, 'find').mockImplementation(async () => record);
  vi.spyOn(store, 'producerHighWater').mockResolvedValue('1');
  const begin = vi.spyOn(store, 'begin').mockImplementation(async (token, options, at) => {
    record = current = browserRide({ ...options, id: initial.id, startedAt: at, checkpointAt: at, writer: token });
    return record;
  });
  const checkpoint = (
    elapsed: number,
    timer: number,
    at: string,
    kind: 'telemetryCount' | 'locationCount',
    count: number,
  ) => {
    record = current = {
      ...record,
      [kind]: record[kind] + count,
      originalMembershipCount: record.originalMembershipCount + count,
      revision: String(Number(record.revision) + 1),
      elapsedSeconds: elapsed,
      timerSeconds: timer,
      checkpointAt: at,
    };
    return record;
  };
  const append = vi
    .spyOn(store, 'append')
    .mockImplementation(async (_id, _token, rows, elapsed, timer, at) =>
      checkpoint(elapsed, timer, at, 'telemetryCount', rows.length),
    );
  const appendLocations = vi
    .spyOn(store, 'appendLocations')
    .mockImplementation(async (_id, _token, rows, elapsed, timer, at) =>
      checkpoint(elapsed, timer, at, 'locationCount', rows.length),
    );
  const transition = vi
    .spyOn(store, 'transition')
    .mockImplementation(async (_id, _token, action, elapsed, timer, at) => {
      const interrupted = record.phase === 'interrupted';
      record = {
        ...record,
        phase:
          action === 'stop'
            ? 'completed'
            : action === 'pause'
              ? 'paused'
              : action === 'resume'
                ? 'running'
                : record.phase,
        elapsedSeconds: interrupted ? record.elapsedSeconds : elapsed,
        timerSeconds: interrupted ? record.timerSeconds : timer,
        checkpointAt: interrupted ? record.checkpointAt : at,
      };
      if (action === 'stop') {
        record.endedAt = record.checkpointAt;
        delete record.writer;
        current = null;
      } else current = record;
      return record;
    });
  vi.spyOn(store, 'discard').mockImplementation(async () => {
    current = null;
  });
  vi.spyOn(store, 'remove').mockResolvedValue();
  return { store, begin, append, appendLocations, transition, record: () => record };
}
