import { CONSUMPTION_METRICS } from '../../src/core/consumption';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { browserTransaction } from '../../src/services/browser-ride-store';
import {
  peekBrowserDistance,
  retainBrowserDistance,
  type BrowserDistanceHandle,
} from '../../src/services/browser-distance-store';
import { browserIndexKey } from '../../src/services/browser-storage-keys';
import { browserRide } from '../support/browser-ride';

vi.mock('../../src/services/browser-ride-store', async importOriginal => ({
  ...(await importOriginal<typeof import('../../src/services/browser-ride-store')>()),
  browserTransaction: vi.fn(),
}));

type Row = Record<string, unknown>;
type Stores = Map<string, Map<string, Row>>;
const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const distanceStores = ['distance-profiles', 'distance-intervals', 'distance-tiles'];
let stored: Stores;
let interruptCleanup: boolean;
let interruptedStores: string[];

function request<T>(execute: () => T): IDBRequest<T> {
  const result = {
    result: undefined as T | undefined,
    error: null as Error | null,
    onsuccess: null as (() => void) | null,
    onerror: null as (() => void) | null,
  };
  queueMicrotask(() => {
    try {
      result.result = execute();
      result.onsuccess?.();
    } catch (error) {
      result.error = error as Error;
      result.onerror?.();
    }
  });
  return result as unknown as IDBRequest<T>;
}

beforeEach(() => {
  stored = new Map(['recordings', ...distanceStores].map(name => [name, new Map()]));
  interruptCleanup = false;
  interruptedStores = [];
  vi.stubGlobal('IDBKeyRange', { bound: (lower: unknown, upper: unknown) => ({ lower, upper }) });
  const held = new Map<string, number>();
  vi.stubGlobal('navigator', {
    locks: {
      request: async (
        name: string,
        options: LockOptions | ((lock: object | null) => Promise<unknown>),
        body?: (lock: object | null) => Promise<unknown>,
      ) => {
        const work = typeof options === 'function' ? options : body!;
        if (typeof options !== 'function' && options.ifAvailable && held.has(name)) return work(null);
        held.set(name, (held.get(name) ?? 0) + 1);
        try {
          return await work({ name });
        } finally {
          const count = held.get(name)! - 1;
          if (count) held.set(name, count);
          else held.delete(name);
        }
      },
    },
  });
  vi.mocked(browserTransaction).mockImplementation(async (names, mode, work) => {
    const working = structuredClone(stored);
    const tx = {
      objectStore(name: string) {
        if (!names.includes(name)) throw new Error(`Store outside transaction: ${name}`);
        const rows = working.get(name)!;
        const matching = (range: { lower: unknown[] }) =>
          [...rows.keys()].filter(key => {
            const parts = JSON.parse(key) as unknown[];
            return range.lower.every((part, index) => parts[index] === part);
          });
        return {
          get: (key: unknown) => request(() => rows.get(JSON.stringify(key))),
          getAll: (range: { lower: unknown[] }) => request(() => matching(range).map(key => rows.get(key))),
          openCursor: (range: { lower: unknown[] }) => {
            const keys = matching(range);
            let index = 0;
            const cursor = {
              result: null as { value: Row; continue: () => void } | null,
              onsuccess: null as (() => void) | null,
            };
            const advance = () =>
              queueMicrotask(() => {
                const key = keys[index++];
                cursor.result = key ? { value: rows.get(key)!, continue: advance } : null;
                cursor.onsuccess?.();
              });
            advance();
            return cursor;
          },
          delete: (range: { lower: unknown[] }) =>
            request(() => {
              if (mode !== 'readwrite') throw new Error('Read-only transaction');
              if (interruptCleanup && name === 'distance-intervals') {
                interruptedStores = names;
                throw new Error('Tab closed during distance reclamation');
              }
              for (const key of matching(range)) rows.delete(key);
            }),
        };
      },
    };
    const result = await work(tx as unknown as IDBTransaction);
    if (mode === 'readwrite') for (const name of names) stored.set(name, working.get(name)!);
    return result;
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

function seed(prefix = false) {
  const record = browserRide({ id, revision: '2' });
  stored.get('recordings')!.set(JSON.stringify(id), record);
  for (const generation of ['1', '2'])
    for (const source of ['controller', 'gps:phone', ...CONSUMPTION_METRICS] as const) {
      const profile: BrowserDistanceHandle['profile'] = {
        recordingId: id,
        generation,
        source,
        generationKey: browserIndexKey(generation),
        revisionKey: browserIndexKey(generation),
        recordRevision: generation,
        inputSequence: -1,
        intervalEndUs: '0',
        prefixes:
          prefix && generation === '2'
            ? [{ generation: '1', revisionKey: browserIndexKey('1'), endUs: '1000000', inputSequence: 0 }]
            : [],
        state: { intervals: 0 },
        building: false,
        coveredUs: '0',
        distanceQ: '0',
        timerUs: '0',
        telemetryCount: 0,
        locationCount: 0,
        lifecycleCount: 1,
      };
      const key = [id, source, profile.generationKey, profile.revisionKey];
      stored.get('distance-profiles')!.set(JSON.stringify(key), { ...profile });
      for (const name of ['distance-intervals', 'distance-tiles'])
        stored.get(name)!.set(JSON.stringify(key), { recordingId: id, source, generation });
    }
  return record;
}
const snapshot = () => distanceStores.map(name => [...stored.get(name)!.entries()]);

it('rolls back profile removal when interval cleanup is interrupted and reclaims after reopening', async () => {
  seed();
  const before = snapshot();
  const handle = (await peekBrowserDistance(id))!;
  const release = await retainBrowserDistance(handle);
  interruptCleanup = true;
  await expect(release()).rejects.toThrow('Tab closed during distance reclamation');
  expect(snapshot()).toEqual(before);
  expect(interruptedStores).toEqual(expect.arrayContaining(distanceStores));
  interruptCleanup = false;
  vi.resetModules();
  const reopened = await import('../../src/services/browser-distance-store');
  const current = (await reopened.peekBrowserDistance(id))!;
  await (
    await reopened.retainBrowserDistance(current)
  )();
  for (const name of distanceStores)
    expect([...stored.get(name)!.values()].map(row => row.generation)).toEqual(Array(6).fill('2'));
});

it('retains leased generations and referenced prefixes until their last profile is removed', async () => {
  const record = seed(true);
  const current = (await peekBrowserDistance(id))!;
  const older = (await peekBrowserDistance(id, 'auto', { ...record, revision: '1' }))!;
  const releaseOlder = await retainBrowserDistance(older);
  await (
    await retainBrowserDistance(current)
  )();
  expect(stored.get('distance-profiles')!.size).toBe(12);
  await releaseOlder();
  expect(stored.get('distance-profiles')!.size).toBe(6);
  for (const name of ['distance-intervals', 'distance-tiles']) expect(stored.get(name)!.size).toBe(12);
  const latest = browserRide({ id, revision: '3' });
  stored.get('recordings')!.set(JSON.stringify(id), latest);
  for (const profile of [...stored.get('distance-profiles')!.values()]) {
    const next = {
      ...profile,
      generation: '3',
      generationKey: browserIndexKey('3'),
      revisionKey: browserIndexKey('3'),
      recordRevision: '3',
      prefixes: [],
    };
    stored
      .get('distance-profiles')!
      .set(JSON.stringify([id, profile.source, next.generationKey, next.revisionKey]), next);
  }
  const replacement = (await peekBrowserDistance(id))!;
  await (
    await retainBrowserDistance(replacement)
  )();
  expect(stored.get('distance-profiles')!.size).toBe(6);
  for (const name of ['distance-intervals', 'distance-tiles']) expect(stored.get(name)!.size).toBe(0);
});
