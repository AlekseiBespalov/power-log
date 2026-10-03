import { catalogLimit, type CatalogPage, type CatalogRequest } from '../core/catalog';
import {
  MONITOR_METRICS,
  metricById,
  metricAllowsMean,
  type MonitorPoint,
  type MonitorStatistics,
} from '../core/monitor';
import { metricIntegrationGapSeconds } from '../core/monitor-data';
import type { TelemetrySample } from '../core/types';
import { isRecord, validateTimestamp, validateSample } from '../core/validation';
import type { StoragePersistence, WorkoutMetadata } from '../core/workouts';

export const BROWSER_PAGE = 256;
export const BROWSER_BATCH = 128;
// A 16-second base keeps retained projections smaller than the originals at 8 Hz.
export const TILE_SECONDS = [16, 64, 256, 1024, 4096, 16384, 65536] as const;
export type RideRow = TelemetrySample & {
  recordingId: string;
  connectionEpoch: string;
  active: boolean;
  interval: number;
  originalSequence: number;
  originalElapsedSeconds: number;
};
export type BrowserRide = {
  id: string;
  startedAt: string;
  endedAt?: string;
  samples: number;
  interrupted: boolean;
  phase: 'running' | 'paused' | 'completed';
  indoor: boolean;
  revision: number;
  writer?: string;
  elapsedSeconds: number;
  timerSeconds: number;
  checkpointAt: string;
  availableMetrics: string[];
  lapCount: number;
};
type PlotRun = { first: MonitorPoint; last: MonitorPoint; min: MonitorPoint; max: MonitorPoint };
export type Aggregate = {
  first: MonitorPoint;
  last: MonitorPoint;
  min?: MonitorPoint;
  max?: MonitorPoint;
  count: number;
  sum: number;
  integral: number;
  covered: number;
  runs: PlotRun[];
  firstActive: boolean;
  lastActive: boolean;
  firstInterval: number;
  lastInterval: number;
  firstConnectionEpoch: string;
  lastConnectionEpoch: string;
  firstInterruptionIndex: number;
  lastInterruptionIndex: number;
};
export type RideTile = { recordingId: string; level: number; bucket: number; stats: Record<string, Aggregate> };
export type RideLifecycleRow = {
  recordingId: string;
  revision: number;
  action: 'start' | 'pause' | 'resume' | 'lap' | 'save' | 'interrupted';
  elapsedSeconds: number;
  timestamp: string;
};
export type RevisionRead<T> = { record: BrowserRide | undefined; rows: T[] };
type Owner = { key: 'current'; id: string; token: string };
const STORES = [
  'recordings',
  'samples',
  'ride-control',
  'ride-lifecycle',
  'ride-tiles',
  'distance-profiles',
  'distance-intervals',
  'distance-tiles',
];
let opening: Promise<IDBDatabase> | undefined;
export function browserDatabase(): Promise<IDBDatabase> {
  return (opening ??= new Promise((resolve, reject) => {
    const req = indexedDB.open('power-log', 5);
    let earlierVersion = false;
    req.onupgradeneeded = event => {
      if (event.oldVersion > 0) {
        earlierVersion = true;
        req.transaction!.abort();
        return;
      }
      const db = req.result;
      db.createObjectStore('recordings', { keyPath: 'id' }).createIndex('byStartedAtId', ['startedAt', 'id']);
      db.createObjectStore('samples', { keyPath: ['recordingId', 'sequence'] }).createIndex('byElapsed', [
        'recordingId',
        'elapsedSeconds',
        'sequence',
      ]);
      db.createObjectStore('ride-control', { keyPath: 'key' });
      db.createObjectStore('ride-lifecycle', { keyPath: ['recordingId', 'revision'] });
      db.createObjectStore('ride-tiles', { keyPath: ['recordingId', 'level', 'bucket'] });
      db.createObjectStore('distance-profiles', { keyPath: 'recordingId' });
      db.createObjectStore('distance-intervals', { keyPath: ['recordingId', 'generation', 'end', 'sequence'] });
      db.createObjectStore('distance-tiles', { keyPath: ['recordingId', 'generation', 'level', 'bucket'] });
    };
    req.onsuccess = () => {
      req.result.onversionchange = () => {
        req.result.close();
        opening = undefined;
      };
      resolve(req.result);
    };
    req.onerror = () => {
      opening = undefined;
      if (earlierVersion)
        reject(
          new Error("Power Log can't open rides saved by an earlier version. Clear this site's data to start over."),
        );
      else if (req.error?.name === 'VersionError') reject(new Error('Update Power Log to open your rides'));
      else reject(req.error ?? new Error('Browser storage is unavailable'));
    };
    req.onblocked = () => {
      /* Existing connections close on versionchange; no destructive reset. */
    };
  }));
}
export function idbRequest<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('Browser storage request failed'));
  });
}
export async function browserTransaction<T>(
  stores: string[],
  mode: IDBTransactionMode,
  work: (tx: IDBTransaction) => Promise<T>,
): Promise<T> {
  const tx = (await browserDatabase()).transaction(stores, mode);
  const done = new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error ?? new Error('Browser storage transaction aborted'));
    tx.onerror = () => reject(tx.error ?? new Error('Browser storage transaction failed'));
  });
  void done.catch(() => {});
  try {
    const result = await work(tx);
    await done;
    return result;
  } catch (error) {
    try {
      tx.abort();
    } catch {
      /* Already settled. */
    }
    await done.catch(() => {});
    throw error;
  }
}
export function normalize(value: unknown): BrowserRide {
  if (
    !isRecord(value) ||
    typeof value.id !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value.id) ||
    value.id.includes('..')
  )
    throw new Error('Invalid recording identifier');
  validateTimestamp(value.startedAt);
  validateTimestamp(value.checkpointAt);
  if (value.endedAt !== undefined) validateTimestamp(value.endedAt);
  for (const field of ['samples', 'revision', 'lapCount'] as const) {
    if (!Number.isSafeInteger(value[field]) || (value[field] as number) < (field === 'revision' ? 1 : 0))
      throw new Error(`Invalid recording ${field}`);
  }
  for (const field of ['elapsedSeconds', 'timerSeconds'] as const) {
    if (typeof value[field] !== 'number' || !Number.isFinite(value[field]) || value[field] < 0)
      throw new Error(`Invalid recording ${field}`);
  }
  if ((value.timerSeconds as number) > (value.elapsedSeconds as number))
    throw new Error('Invalid recording checkpoint');
  if (typeof value.indoor !== 'boolean' || typeof value.interrupted !== 'boolean')
    throw new Error('Invalid recording flags');
  if (
    !Array.isArray(value.availableMetrics) ||
    !value.availableMetrics.every(
      metric => typeof metric === 'string' && MONITOR_METRICS.some(item => item.id === metric),
    )
  )
    throw new Error('Invalid recording metrics');
  if (value.phase === 'completed') {
    if (value.endedAt === undefined || value.writer !== undefined) throw new Error('Invalid completed recording');
  } else if (value.phase === 'running' || value.phase === 'paused') {
    if (
      value.endedAt !== undefined ||
      typeof value.writer !== 'string' ||
      !/^[A-Za-z0-9._:-]{1,128}$/.test(value.writer)
    )
      throw new Error('Invalid recording owner');
  } else throw new Error('Invalid recording phase');
  return value as BrowserRide;
}
async function recordIn(tx: IDBTransaction, id: string): Promise<BrowserRide> {
  const value: unknown = await idbRequest(tx.objectStore('recordings').get(id));
  if (!value) throw new Error('Recording not found');
  return normalize(value);
}
async function owned(tx: IDBTransaction, id: string, token: string): Promise<BrowserRide> {
  const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
  const record = await recordIn(tx, id);
  if (owner?.id !== id || owner.token !== token || record.writer !== token || record.endedAt)
    throw new Error('This browser no longer owns the recording');
  return record;
}
/** The batch and its recording come from one transaction, so the rows belong to the returned revision. */
async function revisionRead<T>(
  id: string,
  store: string,
  limit: number,
  read: (tx: IDBTransaction, count: number) => IDBRequest<T[]>,
): Promise<RevisionRead<T>> {
  // IndexedDB reads a count of 0 as unbounded.
  if (!Number.isSafeInteger(limit) || limit < 1) throw new Error('Invalid browser read bound');
  return browserTransaction(['recordings', store], 'readonly', async tx => {
    const [value, rows] = await Promise.all([
      idbRequest<unknown>(tx.objectStore('recordings').get(id)),
      idbRequest(read(tx, limit)),
    ]);
    return { record: value === undefined ? undefined : normalize(value), rows };
  });
}
export function metadataOf(record: BrowserRide): WorkoutMetadata {
  return {
    schemaVersion: 1,
    id: record.id,
    startedAt: record.startedAt,
    elapsedSeconds: record.elapsedSeconds,
    ...(record.endedAt ? { endedAt: record.endedAt } : {}),
    phase: record.phase,
    indoor: record.indoor,
    watchEnabled: false,
    sport: 'cycling',
    subSport: 'eBiking',
    eventCount: record.samples,
    interrupted: record.interrupted,
    healthKitState: 'notRequested',
    watchSyncState: 'notRequired',
    collectionRevision: record.revision,
    finalizationState: record.endedAt ? 'complete' : 'pending',
    saveToHealth: false,
    recordGPS: false,
    storage: 'browser',
  };
}
export function rowPoint(row: RideRow, metric: string, previous?: RideRow): MonitorPoint | undefined {
  const value = row[metric as keyof TelemetrySample];
  if (typeof value !== 'number' || !Number.isFinite(value)) return;
  return {
    value,
    elapsedSeconds: row.elapsedSeconds,
    timestamp: row.timestamp,
    observationId: `${row.recordingId}:${row.sequence}`,
    startsSegment: previous !== undefined && previous.interruptionIndex !== row.interruptionIndex,
  };
}
export function canIntegrate(a: Aggregate, b: Aggregate, metric: string): boolean {
  const dt = b.first.elapsedSeconds - a.last.elapsedSeconds;
  const limit = metricIntegrationGapSeconds(metricById(metric)!);
  return (
    dt > 0 &&
    dt <= limit &&
    a.lastActive &&
    b.firstActive &&
    a.lastInterval === b.firstInterval &&
    a.lastConnectionEpoch === b.firstConnectionEpoch &&
    a.lastInterruptionIndex === b.firstInterruptionIndex
  );
}
function integrate(a: Aggregate, b: Aggregate, metric: string): { covered: number; integral: number } {
  if (!canIntegrate(a, b, metric)) return { covered: 0, integral: 0 };
  const dt = b.first.elapsedSeconds - a.last.elapsedSeconds;
  return {
    covered: dt,
    integral: (metricById(metric)?.kind === 'step' ? a.last.value : (a.last.value + b.first.value) / 2) * dt,
  };
}
export function mergeAggregate(a: Aggregate | undefined, b: Aggregate, metric: string): Aggregate {
  if (!a) return { ...b };
  const bridge = integrate(a, b, metric);
  const dt = b.first.elapsedSeconds - a.last.elapsedSeconds,
    runs = [...a.runs];
  if (dt > 0 && dt < metricById(metric)!.gapSeconds && a.lastInterruptionIndex === b.firstInterruptionIndex) {
    const left = runs.pop()!,
      right = b.runs[0]!;
    runs.push(
      {
        first: left.first,
        last: right.last,
        min: right.min.value < left.min.value ? right.min : left.min,
        max: right.max.value > left.max.value ? right.max : left.max,
      },
      ...b.runs.slice(1),
    );
  } else {
    const [first, ...rest] = b.runs;
    runs.push({ ...first!, first: { ...first!.first, startsSegment: true } }, ...rest);
  }
  return {
    ...a,
    last: b.last,
    lastActive: b.lastActive,
    lastInterval: b.lastInterval,
    lastConnectionEpoch: b.lastConnectionEpoch,
    lastInterruptionIndex: b.lastInterruptionIndex,
    min: !a.min || (b.min && b.min.value < a.min.value) ? b.min : a.min,
    max: !a.max || (b.max && b.max.value > a.max.value) ? b.max : a.max,
    count: a.count + b.count,
    sum: a.sum + b.sum,
    covered: a.covered + b.covered + bridge.covered,
    integral: a.integral + b.integral + bridge.integral,
    runs,
  };
}
export function rowAggregate(row: RideRow, metric: string): Aggregate | undefined {
  const point = rowPoint(row, metric);
  if (!point) return;
  const active = row.active;
  return {
    first: point,
    last: point,
    ...(active ? { min: point, max: point } : {}),
    count: active ? 1 : 0,
    sum: active ? point.value : 0,
    covered: 0,
    integral: 0,
    runs: [{ first: point, last: point, min: point, max: point }],
    firstActive: active,
    lastActive: active,
    firstInterval: row.interval,
    lastInterval: row.interval,
    firstConnectionEpoch: row.connectionEpoch,
    lastConnectionEpoch: row.connectionEpoch,
    firstInterruptionIndex: row.interruptionIndex,
    lastInterruptionIndex: row.interruptionIndex,
  };
}
export function statisticsOf(aggregate: Aggregate, metric?: string): MonitorStatistics {
  return {
    min: aggregate.min,
    max: aggregate.max,
    count: aggregate.count,
    coveredSeconds: aggregate.covered,
    ...(!metric || metricAllowsMean(metricById(metric)!)
      ? { ...(aggregate.count ? { sampleMean: aggregate.sum / aggregate.count } : {}), integral: aggregate.integral }
      : {}),
  };
}
async function project(tx: IDBTransaction, rows: RideRow[]): Promise<void> {
  const store = tx.objectStore('ride-tiles'),
    updates = new Map<string, RideTile>();
  // Register all reads before awaiting them, keeping the IDB transaction active.
  const keys = new Map<string, [string, number, number]>();
  for (const row of rows)
    TILE_SECONDS.forEach((size, level) => {
      const key: [string, number, number] = [row.recordingId, level, Math.floor(row.elapsedSeconds / size)];
      keys.set(JSON.stringify(key), key);
    });
  await Promise.all(
    [...keys].map(async ([key, values]) => {
      updates.set(
        key,
        (await idbRequest<RideTile | undefined>(store.get(values))) ?? {
          recordingId: values[0],
          level: values[1],
          bucket: values[2],
          stats: {},
        },
      );
    }),
  );
  for (const row of rows)
    for (let level = 0; level < TILE_SECONDS.length; level++) {
      const tile = updates.get(
        JSON.stringify([row.recordingId, level, Math.floor(row.elapsedSeconds / TILE_SECONDS[level]!)]),
      )!;
      for (const metric of MONITOR_METRICS) {
        const next = rowAggregate(row, metric.id);
        if (next) tile.stats[metric.id] = mergeAggregate(tile.stats[metric.id], next, metric.id);
      }
    }
  await Promise.all([...updates.values()].map(tile => idbRequest(store.put(tile))));
}
export class BrowserRideStore {
  async get(id: string) {
    return browserTransaction(['recordings'], 'readonly', tx => recordIn(tx, id));
  }
  async current() {
    return browserTransaction(['recordings', 'ride-control'], 'readonly', async tx => {
      const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
      return owner ? recordIn(tx, owner.id) : null;
    });
  }
  async begin(token: string, indoor: boolean, startedAt: string): Promise<BrowserRide> {
    return browserTransaction(STORES, 'readwrite', async tx => {
      if (await idbRequest(tx.objectStore('ride-control').get('current')))
        throw new Error('Another ride needs recovery first');
      const record = normalize({
        id: crypto.randomUUID(),
        startedAt,
        samples: 0,
        interrupted: false,
        phase: 'running',
        indoor,
        revision: 1,
        writer: token,
        elapsedSeconds: 0,
        timerSeconds: 0,
        checkpointAt: startedAt,
        availableMetrics: [],
        lapCount: 0,
      });
      await idbRequest(tx.objectStore('recordings').add(record));
      await idbRequest(tx.objectStore('ride-control').put({ key: 'current', id: record.id, token }));
      await idbRequest(
        tx
          .objectStore('ride-lifecycle')
          .add({ recordingId: record.id, revision: 1, action: 'start', elapsedSeconds: 0, timestamp: startedAt }),
      );
      return record;
    });
  }
  async append(
    id: string,
    token: string,
    rows: RideRow[],
    elapsed: number,
    timer: number,
    at: string,
  ): Promise<BrowserRide> {
    if (!rows.length || rows.length > BROWSER_BATCH) throw new Error('Invalid browser recording batch');
    return browserTransaction(STORES, 'readwrite', async tx => {
      const record = await owned(tx, id, token);
      let sequence = record.samples - 1,
        previousElapsed = record.elapsedSeconds;
      for (const row of rows) {
        validateSample(row);
        if (
          !row.connectionEpoch ||
          typeof row.active !== 'boolean' ||
          !Number.isSafeInteger(row.interval) ||
          row.interval < 0 ||
          !Number.isSafeInteger(row.originalSequence) ||
          row.originalSequence < 0 ||
          !Number.isFinite(row.originalElapsedSeconds) ||
          row.originalElapsedSeconds < 0
        )
          throw new Error('Invalid recording observation');
        if (row.recordingId !== id || row.sequence !== ++sequence || row.elapsedSeconds < previousElapsed)
          throw new Error('Recording sequence or time changed');
        previousElapsed = row.elapsedSeconds;
        await idbRequest(tx.objectStore('samples').add(row));
      }
      if (elapsed !== previousElapsed || !Number.isFinite(timer) || timer < record.timerSeconds || timer > elapsed)
        throw new Error('Invalid recording checkpoint');
      await project(tx, rows);
      const available = new Set(record.availableMetrics);
      for (const row of rows)
        for (const metric of MONITOR_METRICS) if (rowPoint(row, metric.id)) available.add(metric.id);
      const next = {
        ...record,
        samples: record.samples + rows.length,
        revision: record.revision + 1,
        elapsedSeconds: elapsed,
        timerSeconds: timer,
        checkpointAt: at,
        availableMetrics: [...available],
      };
      normalize(next);
      await idbRequest(tx.objectStore('recordings').put(next));
      return next;
    });
  }
  async transition(
    id: string,
    token: string,
    action: 'pause' | 'resume' | 'lap' | 'save',
    elapsed: number,
    timer: number,
    at: string,
  ): Promise<BrowserRide> {
    return browserTransaction(STORES, 'readwrite', async tx => {
      const record = await owned(tx, id, token);
      if ((action === 'pause' && record.phase !== 'running') || (action === 'resume' && record.phase !== 'paused'))
        throw new Error('Recording phase changed');
      const next: BrowserRide = {
        ...record,
        revision: record.revision + 1,
        elapsedSeconds: elapsed,
        timerSeconds: timer,
        checkpointAt: at,
        lapCount: record.lapCount + (action === 'lap' ? 1 : 0),
        phase:
          action === 'pause'
            ? 'paused'
            : action === 'resume'
              ? 'running'
              : action === 'save'
                ? 'completed'
                : record.phase,
      };
      if (
        !Number.isFinite(elapsed) ||
        elapsed < record.elapsedSeconds ||
        !Number.isFinite(timer) ||
        timer < record.timerSeconds ||
        timer > elapsed
      )
        throw new Error('Invalid recording checkpoint');
      if (action === 'save') {
        next.endedAt = at;
        delete next.writer;
        await idbRequest(tx.objectStore('ride-control').delete('current'));
      }
      normalize(next);
      await idbRequest(tx.objectStore('recordings').put(next));
      await idbRequest(
        tx
          .objectStore('ride-lifecycle')
          .add({ recordingId: id, revision: next.revision, action, elapsedSeconds: elapsed, timestamp: at }),
      );
      return next;
    });
  }
  /** Caller holds the exclusive origin lock, so the old process cannot still own capture. */
  async recoverOrphan(): Promise<BrowserRide | null> {
    return browserTransaction(STORES, 'readwrite', async tx => {
      const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
      if (!owner) return null;
      const record = await recordIn(tx, owner.id);
      const next: BrowserRide = {
        ...record,
        phase: 'completed',
        endedAt: record.checkpointAt,
        interrupted: true,
        revision: record.revision + 1,
      };
      delete next.writer;
      normalize(next);
      await idbRequest(tx.objectStore('recordings').put(next));
      await idbRequest(tx.objectStore('ride-control').delete('current'));
      await idbRequest(
        tx.objectStore('ride-lifecycle').add({
          recordingId: record.id,
          revision: next.revision,
          action: 'interrupted',
          elapsedSeconds: next.elapsedSeconds,
          timestamp: next.endedAt,
        }),
      );
      return next;
    });
  }
  async remove(id: string, token?: string): Promise<void> {
    await browserTransaction(STORES, 'readwrite', async tx => {
      const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
      if (owner?.id === id) {
        if (!token) throw new Error('Stop or discard this ride from the recording tab');
        await owned(tx, id, token);
      }
      for (const name of ['samples', 'ride-lifecycle', 'ride-tiles', 'distance-intervals', 'distance-tiles'])
        await idbRequest(tx.objectStore(name).delete(IDBKeyRange.bound([id], [id, []])));
      await idbRequest(tx.objectStore('distance-profiles').delete(id));
      await idbRequest(tx.objectStore('recordings').delete(id));
      if (owner?.id === id) await idbRequest(tx.objectStore('ride-control').delete('current'));
    });
  }
  async list(options: CatalogRequest = {}): Promise<CatalogPage<WorkoutMetadata>> {
    return browserTransaction(['recordings'], 'readonly', async tx => {
      const store = tx.objectStore('recordings'),
        index = store.index('byStartedAtId');
      const counts =
        options.beforeStartedAt === undefined
          ? Promise.all([idbRequest(store.count()), idbRequest(index.count())])
          : undefined;
      const [page, totals] = await Promise.all([
        new Promise<CatalogPage<WorkoutMetadata>>((resolve, reject) => {
          const range =
            options.beforeStartedAt === undefined
              ? undefined
              : IDBKeyRange.upperBound([options.beforeStartedAt, options.beforeID ?? ''], true);
          const cursor = index.openCursor(range, 'prev'),
            result: CatalogPage<WorkoutMetadata> = { records: [], unreadableCount: 0, unindexedCount: 0 };
          cursor.onerror = () => reject(cursor.error);
          cursor.onsuccess = () => {
            const item = cursor.result;
            if (!item) {
              resolve(result);
              return;
            }
            try {
              result.records.push(metadataOf(normalize(item.value)));
            } catch {
              result.unreadableCount++;
            }
            if (result.records.length >= catalogLimit(options)) resolve(result);
            else item.continue();
          };
        }),
        counts,
      ]);
      if (totals) page.unindexedCount = totals[0] - totals[1];
      return page;
    });
  }
  async page(
    id: string,
    start: number,
    end: number,
    after?: [number, number],
    limit = BROWSER_PAGE,
  ): Promise<RideRow[]> {
    return browserTransaction(['samples'], 'readonly', tx =>
      idbRequest(
        tx
          .objectStore('samples')
          .index('byElapsed')
          .getAll(
            IDBKeyRange.bound(after ? [id, ...after] : [id, start], [id, end, Number.MAX_SAFE_INTEGER], Boolean(after)),
            Math.min(BROWSER_PAGE, limit),
          ),
      ),
    );
  }
  async find(id: string): Promise<BrowserRide | undefined> {
    return browserTransaction(['recordings'], 'readonly', async tx => {
      const value: unknown = await idbRequest(tx.objectStore('recordings').get(id));
      return value === undefined ? undefined : normalize(value);
    });
  }
  async samplesAfter(
    id: string,
    after: readonly [number, number] | null,
    limit: number,
  ): Promise<RevisionRead<RideRow>> {
    return revisionRead(id, 'samples', limit, (tx, count) =>
      tx
        .objectStore('samples')
        .index('byElapsed')
        .getAll(IDBKeyRange.bound(after ? [id, ...after] : [id], [id, []], Boolean(after)), count),
    );
  }
  async lifecycleAfter(id: string, after: number | null, limit: number): Promise<RevisionRead<RideLifecycleRow>> {
    return revisionRead(id, 'ride-lifecycle', limit, (tx, count) =>
      tx
        .objectStore('ride-lifecycle')
        .getAll(IDBKeyRange.bound(after === null ? [id] : [id, after], [id, []], after !== null), count),
    );
  }
  async neighbor(id: string, seconds: number, direction: 'prev' | 'next'): Promise<RideRow | undefined> {
    return browserTransaction(['samples'], 'readonly', async tx => {
      const range =
        direction === 'prev'
          ? IDBKeyRange.bound([id, 0], [id, seconds, Number.MAX_SAFE_INTEGER])
          : IDBKeyRange.bound([id, seconds], [id, Infinity]);
      const cursor = await idbRequest(tx.objectStore('samples').index('byElapsed').openCursor(range, direction));
      return cursor?.value as RideRow | undefined;
    });
  }
  async bySequence(id: string, sequence: number): Promise<RideRow | undefined> {
    return browserTransaction(['samples'], 'readonly', tx => idbRequest(tx.objectStore('samples').get([id, sequence])));
  }
  async tiles(id: string, level: number, first: number, last: number): Promise<RideTile[]> {
    if (last < first) return [];
    if (last - first > 2048) throw new Error('Chart tile request exceeds its bound');
    return browserTransaction(['ride-tiles'], 'readonly', tx =>
      idbRequest(tx.objectStore('ride-tiles').getAll(IDBKeyRange.bound([id, level, first], [id, level, last]), 2049)),
    );
  }
}
export const browserRideStore = new BrowserRideStore();

export async function browserStoragePersistence(): Promise<StoragePersistence> {
  try {
    await persistenceRequest;
    if (typeof navigator === 'undefined' || !navigator.storage?.persisted) return 'unavailable';
    return (await navigator.storage.persisted()) ? 'persisted' : 'not persisted';
  } catch {
    return 'unavailable';
  }
}
let persistenceRequest: Promise<void> | undefined;
export function requestBrowserStoragePersistence(): Promise<void> {
  return (persistenceRequest ??= (async () => {
    try {
      await navigator.storage?.persist?.();
    } catch {}
  })());
}
