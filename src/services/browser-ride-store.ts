import { catalogLimit, type CatalogRequest } from '../core/catalog';
import { MONITOR_METRICS, metricById, metricAllowsMean, type MonitorPoint, type MonitorStatistics } from '../core/monitor';
import type { Recording, RecordingSource, TelemetrySample } from '../core/types';
import { validateRecording, validateSample } from '../core/validation';
import type { WorkoutMetadata, WorkoutPhase } from '../core/workouts';

export const BROWSER_PAGE = 256;
export const BROWSER_BATCH = 128;
// A 16-second base keeps retained projections smaller than the originals at 8 Hz.
export const TILE_SECONDS = [16, 64, 256, 1024, 4096, 16384, 65536] as const;
export type RideRow = TelemetrySample & { recordingId: string; active?: boolean; interval?: number; connection?: number; deviceIdentity?: string; originalSequence?: number; originalElapsedSeconds?: number };
export type BrowserRide = Recording & {
  rideVersion?: 1; phase?: WorkoutPhase; indoor?: boolean; revision?: number; writer?: string;
  elapsedSeconds?: number; timerSeconds?: number; checkpointAt?: string; interval?: number;
  lastSequence?: number; projectionSequence?: number; availableMetrics?: string[]; warnings?: string[]; lapCount?: number;
  activeMaximumPower?: number; activeMaximumCadence?: number;
};
export type Aggregate = { first: MonitorPoint; last: MonitorPoint; min: MonitorPoint; max: MonitorPoint; count: number; sum: number; integral: number; covered: number; gap: boolean; firstActive: boolean; lastActive: boolean; firstInterval: number; lastInterval: number };
export type RideTile = { recordingId: string; level: number; bucket: number; stats: Record<string, Aggregate> };
type Owner = { key: 'current'; id: string; token: string };
const STORES = ['recordings', 'samples', 'ride-control', 'ride-lifecycle', 'ride-tiles', 'distance-profiles', 'distance-intervals', 'distance-tiles'];
let opening: Promise<IDBDatabase> | undefined;
export function browserDatabase(): Promise<IDBDatabase> {
  return opening ??= new Promise((resolve, reject) => {
    const req = indexedDB.open('power-log', 4);
    req.onupgradeneeded = () => {
      const db = req.result, tx = req.transaction!;
      const records = db.objectStoreNames.contains('recordings') ? tx.objectStore('recordings') : db.createObjectStore('recordings', { keyPath: 'id' });
      if (!records.indexNames.contains('byStartedAtId')) records.createIndex('byStartedAtId', ['startedAt', 'id']);
      const samples = db.objectStoreNames.contains('samples') ? tx.objectStore('samples') : db.createObjectStore('samples', { keyPath: ['recordingId', 'sequence'] });
      if (!samples.indexNames.contains('byElapsed')) samples.createIndex('byElapsed', ['recordingId', 'elapsedSeconds', 'sequence']);
      if (!db.objectStoreNames.contains('ride-control')) db.createObjectStore('ride-control', { keyPath: 'key' });
      if (!db.objectStoreNames.contains('ride-lifecycle')) db.createObjectStore('ride-lifecycle', { keyPath: ['recordingId', 'revision'] });
      if (!db.objectStoreNames.contains('ride-tiles')) db.createObjectStore('ride-tiles', { keyPath: ['recordingId', 'level', 'bucket'] });
      if (!db.objectStoreNames.contains('distance-profiles')) db.createObjectStore('distance-profiles', { keyPath: 'recordingId' });
      if (!db.objectStoreNames.contains('distance-intervals')) db.createObjectStore('distance-intervals', { keyPath: ['recordingId', 'generation', 'end', 'sequence'] });
      if (!db.objectStoreNames.contains('distance-tiles')) db.createObjectStore('distance-tiles', { keyPath: ['recordingId', 'generation', 'level', 'bucket'] });
    };
    req.onsuccess = () => {
      req.result.onversionchange = () => { req.result.close(); opening = undefined; };
      resolve(req.result);
    };
    req.onerror = () => { opening = undefined; reject(req.error ?? new Error('Browser storage is unavailable')); };
    req.onblocked = () => { /* Existing connections close on versionchange; no destructive reset. */ };
  });
}
export function idbRequest<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error ?? new Error('Browser storage request failed')); });
}
export async function browserTransaction<T>(stores: string[], mode: IDBTransactionMode, work: (tx: IDBTransaction) => Promise<T>): Promise<T> {
  const tx = (await browserDatabase()).transaction(stores, mode);
  const done = new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve(); tx.onabort = () => reject(tx.error ?? new Error('Browser storage transaction aborted'));
    tx.onerror = () => reject(tx.error ?? new Error('Browser storage transaction failed'));
  });
  void done.catch(() => {});
  try { const result = await work(tx); await done; return result; }
  catch (error) { try { tx.abort(); } catch { /* Already settled. */ } await done.catch(() => {}); throw error; }
}
function normalize(value: unknown): BrowserRide {
  const base = validateRecording(value);
  if (base.uri !== 'indexeddb') throw new Error('Recording belongs to another storage backend');
  return { ...(value as BrowserRide), ...base };
}
async function recordIn(tx: IDBTransaction, id: string): Promise<BrowserRide> {
  const value: unknown = await idbRequest(tx.objectStore('recordings').get(id));
  if (!value) throw new Error('Recording not found');
  return normalize(value);
}
async function owned(tx: IDBTransaction, id: string, token: string): Promise<BrowserRide> {
  const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
  const record = await recordIn(tx, id);
  if (owner?.id !== id || owner.token !== token || record.writer !== token || record.endedAt) throw new Error('This browser no longer owns the recording');
  return record;
}
export function metadataOf(record: BrowserRide): WorkoutMetadata {
  return { schemaVersion: 1, id: record.id, startedAt: record.startedAt, ...(record.endedAt ? { endedAt: record.endedAt } : {}),
    phase: record.phase ?? (record.endedAt ? 'completed' : 'recoverable'), indoor: record.indoor ?? false,
    watchEnabled: false, sport: 'cycling', subSport: 'eBiking', eventCount: record.samples,
    interrupted: record.interrupted ?? false, healthKitState: 'notRequested', warnings: record.warnings ?? [], watchSyncState: 'notRequired',
    collectionRevision: record.revision ?? record.samples, finalizationState: record.endedAt ? 'complete' : 'pending',
    saveToHealth: false, recordGPS: false, storage: 'browser' };
}
export function rowPoint(row: RideRow, metric: string): MonitorPoint | undefined {
  const value = row[metric as keyof TelemetrySample];
  if (typeof value !== 'number' || !Number.isFinite(value)) return;
  return { value, elapsedSeconds: row.elapsedSeconds, timestamp: row.timestamp, observationId: `${row.recordingId}:${row.sequence}` };
}
function integrate(a: Aggregate, b: Aggregate, metric: string): { covered: number; integral: number } {
  const dt = b.first.elapsedSeconds - a.last.elapsedSeconds;
  const limit = ['humanPowerW', 'cadenceRpm'].includes(metric) ? 2.5 : metricById(metric)?.gapSeconds ?? 6;
  if (dt <= 0 || dt > limit || !a.lastActive || !b.firstActive || a.lastInterval !== b.firstInterval) return { covered: 0, integral: 0 };
  return { covered: dt, integral: (metricById(metric)?.kind === 'step' ? a.last.value : (a.last.value + b.first.value) / 2) * dt };
}
export function mergeAggregate(a: Aggregate | undefined, b: Aggregate, metric: string): Aggregate {
  if (!a) return { ...b };
  const bridge = integrate(a, b, metric);
  return { ...a, last: b.last, lastActive: b.lastActive, lastInterval: b.lastInterval,
    min: b.min.value < a.min.value ? b.min : a.min, max: b.max.value > a.max.value ? b.max : a.max,
    count: a.count + b.count, sum: a.sum + b.sum, covered: a.covered + b.covered + bridge.covered,
    integral: a.integral + b.integral + bridge.integral,
    gap: a.gap || b.gap || b.first.elapsedSeconds - a.last.elapsedSeconds >= (metricById(metric)?.gapSeconds ?? 6) };
}
export function rowAggregate(row: RideRow, metric: string): Aggregate | undefined {
  const point = rowPoint(row, metric); if (!point) return;
  return { first: point, last: point, min: point, max: point, count: 1, sum: point.value, covered: 0, integral: 0,
    gap: false, firstActive: row.active !== false, lastActive: row.active !== false, firstInterval: row.interval ?? 0, lastInterval: row.interval ?? 0 };
}
export function statisticsOf(aggregate: Aggregate, metric?: string): MonitorStatistics {
  return { min: aggregate.min, max: aggregate.max, count: aggregate.count, coveredSeconds: aggregate.covered,
    ...(!metric || metricAllowsMean(metricById(metric)!) ? { sampleMean: aggregate.sum / aggregate.count, integral: aggregate.integral } : {}) };
}
async function project(tx: IDBTransaction, rows: RideRow[]): Promise<void> {
  const store = tx.objectStore('ride-tiles'), updates = new Map<string, RideTile>();
  // Register all reads before awaiting them, keeping the IDB transaction active.
  const keys = new Map<string, [string, number, number]>();
  for (const row of rows) TILE_SECONDS.forEach((size, level) => { const key: [string, number, number] = [row.recordingId, level, Math.floor(row.elapsedSeconds / size)]; keys.set(JSON.stringify(key), key); });
  await Promise.all([...keys].map(async ([key, values]) => { updates.set(key, await idbRequest<RideTile | undefined>(store.get(values)) ?? { recordingId: values[0], level: values[1], bucket: values[2], stats: {} }); }));
  for (const row of rows) for (let level = 0; level < TILE_SECONDS.length; level++) {
    const tile = updates.get(JSON.stringify([row.recordingId, level, Math.floor(row.elapsedSeconds / TILE_SECONDS[level]!)]))!;
    for (const metric of MONITOR_METRICS) { const next = rowAggregate(row, metric.id); if (next) tile.stats[metric.id] = mergeAggregate(tile.stats[metric.id], next, metric.id); }
  }
  await Promise.all([...updates.values()].map(tile => idbRequest(store.put(tile))));
}
export class BrowserRideStore {
  async get(id: string) { return browserTransaction(['recordings'], 'readonly', tx => recordIn(tx, id)); }
  async current() { return browserTransaction(['recordings', 'ride-control'], 'readonly', async tx => { const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current')); return owner ? recordIn(tx, owner.id) : null; }); }
  async begin(token: string, source: RecordingSource, indoor: boolean, startedAt: string): Promise<BrowserRide> {
    return browserTransaction(STORES, 'readwrite', async tx => {
      if (await idbRequest(tx.objectStore('ride-control').get('current'))) throw new Error('Another ride needs recovery first');
      const record: BrowserRide = { id: crypto.randomUUID(), startedAt, samples: 0, uri: 'indexeddb', source, rideVersion: 1,
        phase: 'running', indoor, revision: 1, writer: token, elapsedSeconds: 0, timerSeconds: 0, checkpointAt: startedAt, interval: 0, lastSequence: -1, projectionSequence: -1, availableMetrics: [] };
      await idbRequest(tx.objectStore('recordings').add(record));
      await idbRequest(tx.objectStore('ride-control').put({ key: 'current', id: record.id, token }));
      await idbRequest(tx.objectStore('ride-lifecycle').add({ recordingId: record.id, revision: 1, action: 'start', elapsedSeconds: 0, timestamp: startedAt }));
      return record;
    });
  }
  async append(id: string, token: string, rows: RideRow[], elapsed: number, timer: number, at: string): Promise<BrowserRide> {
    if (!rows.length || rows.length > BROWSER_BATCH) throw new Error('Invalid browser recording batch');
    return browserTransaction(STORES, 'readwrite', async tx => {
      const record = await owned(tx, id, token);
      let sequence = record.lastSequence ?? -1, previousElapsed = record.elapsedSeconds ?? 0;
      for (const row of rows) {
        validateSample(row);
        if (row.recordingId !== id || row.sequence !== ++sequence || row.elapsedSeconds < previousElapsed) throw new Error('Recording sequence or time changed');
        previousElapsed = row.elapsedSeconds;
        await idbRequest(tx.objectStore('samples').add(row));
      }
      if (elapsed !== previousElapsed || !Number.isFinite(timer) || timer < (record.timerSeconds ?? 0) || timer > elapsed) throw new Error('Invalid recording checkpoint');
      await project(tx, rows);
      const active = rows.filter(row => row.active !== false);
      const available = new Set(record.availableMetrics ?? []);
      for (const row of rows) for (const metric of MONITOR_METRICS) if (rowPoint(row, metric.id)) available.add(metric.id);
      const next = { ...record, samples: record.samples + rows.length, lastSequence: sequence, projectionSequence: sequence, revision: (record.revision ?? 0) + 1,
        ...(active.length ? { activeMaximumPower: Math.max(record.activeMaximumPower ?? -Infinity, ...active.map(row => row.humanPowerW)), activeMaximumCadence: Math.max(record.activeMaximumCadence ?? -Infinity, ...active.map(row => row.cadenceRpm)) } : {}),
        elapsedSeconds: elapsed, timerSeconds: timer, checkpointAt: at, availableMetrics: [...available] };
      await idbRequest(tx.objectStore('recordings').put(next)); return next;
    });
  }
  async transition(id: string, token: string, action: 'pause' | 'resume' | 'lap' | 'save', elapsed: number, timer: number, at: string): Promise<BrowserRide> {
    return browserTransaction(STORES, 'readwrite', async tx => {
      const record = await owned(tx, id, token);
      if ((action === 'pause' && record.phase !== 'running') || (action === 'resume' && record.phase !== 'paused')) throw new Error('Recording phase changed');
      const next: BrowserRide = { ...record, revision: (record.revision ?? 0) + 1, elapsedSeconds: elapsed, timerSeconds: timer, checkpointAt: at,
        lapCount: (record.lapCount ?? 0) + (action === 'lap' ? 1 : 0),
        interval: (record.interval ?? 0) + (action === 'resume' ? 1 : 0), phase: action === 'pause' ? 'paused' : action === 'resume' ? 'running' : action === 'save' ? 'completed' : record.phase };
      if (!Number.isFinite(elapsed) || elapsed < (record.elapsedSeconds ?? 0) || !Number.isFinite(timer) || timer < (record.timerSeconds ?? 0) || timer > elapsed) throw new Error('Invalid recording checkpoint');
      if (action === 'save') { next.endedAt = at; delete next.writer; await idbRequest(tx.objectStore('ride-control').delete('current')); }
      await idbRequest(tx.objectStore('recordings').put(next));
      await idbRequest(tx.objectStore('ride-lifecycle').add({ recordingId: id, revision: next.revision, action, elapsedSeconds: elapsed, timestamp: at }));
      return next;
    });
  }
  /** Caller holds the exclusive origin lock, so the old process cannot still own capture. */
  async recoverOrphan(): Promise<BrowserRide | null> {
    return browserTransaction(STORES, 'readwrite', async tx => {
      const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current')); if (!owner) return null;
      const record = await recordIn(tx, owner.id);
      const next: BrowserRide = { ...record, phase: 'completed', endedAt: record.checkpointAt ?? record.startedAt, interrupted: true,
        revision: (record.revision ?? 0) + 1, warnings: [...(record.warnings ?? []), 'Browser recording was interrupted. The retained cutoff is the last committed checkpoint; unobserved time was not counted.'] };
      delete next.writer;
      await idbRequest(tx.objectStore('recordings').put(next)); await idbRequest(tx.objectStore('ride-control').delete('current'));
      await idbRequest(
        tx.objectStore('ride-lifecycle').add({
          recordingId: record.id,
          revision: next.revision,
          action: 'interrupted',
          elapsedSeconds: next.elapsedSeconds ?? 0,
          timestamp: next.endedAt,
        }),
      );
      return next;
    });
  }
  async remove(id: string, token?: string): Promise<void> {
    await browserTransaction(STORES, 'readwrite', async tx => {
      const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
      if (owner?.id === id) { if (!token) throw new Error('Stop or discard this ride from the recording tab'); await owned(tx, id, token); }
      for (const name of ['samples', 'ride-lifecycle', 'ride-tiles', 'distance-intervals', 'distance-tiles']) await idbRequest(tx.objectStore(name).delete(IDBKeyRange.bound([id], [id, []])));
      await idbRequest(tx.objectStore('distance-profiles').delete(id));
      await idbRequest(tx.objectStore('recordings').delete(id));
      if (owner?.id === id) await idbRequest(tx.objectStore('ride-control').delete('current'));
    });
  }
  async list(options: CatalogRequest = {}): Promise<WorkoutMetadata[]> {
    return browserTransaction(['recordings'], 'readonly', tx => new Promise((resolve, reject) => {
      const range = options.beforeStartedAt === undefined ? undefined : IDBKeyRange.upperBound([options.beforeStartedAt, options.beforeID ?? ''], true);
      const cursor = tx.objectStore('recordings').index('byStartedAtId').openCursor(range, 'prev'), result: WorkoutMetadata[] = [];
      cursor.onerror = () => reject(cursor.error);
      cursor.onsuccess = () => { const item = cursor.result; if (!item) { resolve(result); return; }
        try { result.push(metadataOf(normalize(item.value))); } catch { /* Invalid entries cannot hide intact history. */ }
        if (result.length >= catalogLimit(options)) resolve(result); else item.continue(); };
    }));
  }
  async page(id: string, start: number, end: number, after?: [number, number], limit = BROWSER_PAGE): Promise<RideRow[]> {
    return browserTransaction(['samples'], 'readonly', tx => idbRequest(tx.objectStore('samples').index('byElapsed').getAll(
      IDBKeyRange.bound(after ? [id, ...after] : [id, start], [id, end, Number.MAX_SAFE_INTEGER], Boolean(after)), Math.min(BROWSER_PAGE, limit))));
  }
  async neighbor(id: string, seconds: number, direction: 'prev' | 'next'): Promise<RideRow | undefined> {
    return browserTransaction(['samples'], 'readonly', async tx => {
      const range = direction === 'prev' ? IDBKeyRange.bound([id, 0], [id, seconds, Number.MAX_SAFE_INTEGER]) : IDBKeyRange.bound([id, seconds], [id, Infinity]);
      const cursor = await idbRequest(tx.objectStore('samples').index('byElapsed').openCursor(range, direction)); return cursor?.value as RideRow | undefined;
    });
  }
  async bySequence(id: string, sequence: number): Promise<RideRow | undefined> {
    return browserTransaction(['samples'], 'readonly', tx => idbRequest(tx.objectStore('samples').get([id, sequence])));
  }
  async tiles(id: string, level: number, first: number, last: number): Promise<RideTile[]> {
    if (last < first) return [];
    if (last - first > 2048) throw new Error('Chart tile request exceeds its bound');
    return browserTransaction(['ride-tiles'], 'readonly', tx => idbRequest(tx.objectStore('ride-tiles').getAll(IDBKeyRange.bound([id, level, first], [id, level, last]), 2049)));
  }
  /** Legacy originals are indexed without loading them all or modifying their payloads. */
  async ensureProjection(id: string): Promise<void> {
    while (true) {
      const done = await browserTransaction(['recordings', 'samples', 'ride-tiles'], 'readwrite', async tx => {
        const record = await recordIn(tx, id);
        if (record.rideVersion === 1) return true;
        const after = record.projectionSequence ?? -1;
        const rows = await idbRequest<RideRow[]>(tx.objectStore('samples').getAll(IDBKeyRange.bound([id, after], [id, Number.MAX_SAFE_INTEGER], true), BROWSER_PAGE));
        if (!rows.length) return true;
        await project(tx, rows);
        await idbRequest(tx.objectStore('recordings').put({ ...record, projectionSequence: rows[rows.length - 1]!.sequence }));
        return rows.length < BROWSER_PAGE;
      });
      if (done) return;
      await new Promise<void>(resolve => setTimeout(resolve, 0));
    }
  }
}
export const browserRideStore = new BrowserRideStore();
