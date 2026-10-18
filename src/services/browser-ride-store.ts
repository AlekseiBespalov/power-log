import type { BatteryConsumptionSource } from '../core/consumption';
import {
  AnalyticsItem,
  completionOpenExport,
  InspectionOriginalInput,
  InspectionMetricBucket,
  InspectionMetricCheckpoint,
  InspectionMetricPolicy,
  InspectionPlotBucket,
  InspectionPlotCache,
  InspectionCacheEncoder,
} from '../../shared/js';
import { catalogLimit, type CatalogPage, type CatalogRequest } from '../core/catalog';
import { MONITOR_METRICS, type MonitorPoint } from '../core/monitor';
import type { TelemetrySample } from '../core/types';
import { isRecord, validateTimestamp, validateSample } from '../core/validation';
import { sampleRates, type StoragePersistence, type WorkoutMetadata, type LifecycleAction } from '../core/workouts';
import type { DistanceSource } from '../core/distance';
import { browserIndexKey, browserKeyRange, int64, secondsKey, secondsUs, timestampUs } from './browser-storage-keys';
import { optionalChartCache } from '../core/failures';

export const BROWSER_PAGE = 256;
export const BROWSER_BATCH = 64;
export const BROWSER_BATCH_TARGET = 8;
export const BROWSER_OWNER_WORK_LOCK = 'power-log-owner-work';
// A 16-second base keeps retained projections smaller than the originals at 8 Hz.
export const TILE_SECONDS = [16, 64, 256, 1024, 4096, 16384, 65536] as const;
export { browserIndexKey, browserIndexValue, timestampUs } from './browser-storage-keys';
export type OriginalIdentity = {
  eventId: string;
  producer: 'controller' | 'browser';
  producerSequence: string;
  queryUs: string;
  elapsedUs: string;
};
export type RideRow = TelemetrySample &
  OriginalIdentity & {
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
  telemetryCount: number;
  locationCount: number;
  lifecycleCount: number;
  originalMembershipCount: number;
  sampleHz: 2 | 4 | 8;
  recordGPS: boolean;
  recordingOwner: 'phone';
  ownerDevice: 'browser';
  saveToHealth: false;
  interrupted: boolean;
  phase: 'running' | 'paused' | 'interrupted' | 'completed';
  distanceSource: Exclude<DistanceSource, 'auto' | 'gps:watch'>;
  batteryConsumptionSource: BatteryConsumptionSource;
  indoor: boolean;
  revision: string;
  writer?: string;
  elapsedSeconds: number;
  timerSeconds: number;
  checkpointAt: string;
  availableMetrics: string[];
  lapCount: number;
};
export type Aggregate = { plot?: string; checkpoint?: string };
export type RideTile = { recordingId: string; level: number; bucket: number; stats: Record<string, Aggregate> };
export type RideLifecycleRow = OriginalIdentity & {
  recordingId: string;
  revision: string;
  action: LifecycleAction;
  interrupted: boolean;
  interruption?: RideInterruption;
  timerSeconds: number;
  elapsedSeconds: number;
  timestamp: string;
};
export type RideInterruption = {
  cutoffUs: string;
  producer: OriginalIdentity['producer'];
  producerSequence: string;
  clockEpoch?: string;
  controllerSequence: string;
  eventId: string;
};
export type RideLocationRow = OriginalIdentity & {
  recordingId: string;
  timestamp: string;
  elapsedSeconds: number;
  originalTimestamp: string;
  acquiredAtSeconds: number;
  originalElapsedSeconds: number;
  clockEpoch: string;
  active: boolean;
  interval: number;
  distanceBarrier: boolean;
  latitude: number;
  longitude: number;
  horizontalAccuracyM?: number;
  speedMps?: number;
  speedAccuracyMps?: number;
  courseDegrees?: number;
  courseAccuracyDegrees?: number;
  originalAltitudeDatum: 'msl' | 'wgs84Ellipsoid' | 'unknown';
  originalAltitudeMeters?: number;
  originalVerticalAccuracyM?: number;
  altitudeMeters?: number;
  verticalAccuracyM?: number;
};
export type BrowserRideOptions = Pick<BrowserRide, 'indoor' | 'sampleHz' | 'recordGPS'> & {
  distanceSource?: BrowserRide['distanceSource'];
  batteryConsumptionSource?: BatteryConsumptionSource;
};
export type OriginalCursor = readonly [string, OriginalIdentity['producer'], string];
type IndexedOriginal<T> = T & { revision: string; sequenceKey: string; elapsedKey: string; revisionKey: string };
export type RevisionRead<T> = { record: BrowserRide | undefined; rows: T[] };
type Owner = { key: 'current'; id: string; token: string };
const STORES = [
  'recordings',
  'samples',
  'ride-control',
  'ride-lifecycle',
  'ride-locations',
  'ride-deletions',
  'ride-tiles',
  'distance-profiles',
  'distance-intervals',
  'distance-tiles',
];
let opening: Promise<IDBDatabase> | undefined;
export function browserDatabase(): Promise<IDBDatabase> {
  return (opening ??= new Promise((resolve, reject) => {
    const req = indexedDB.open('power-log', 9);
    let earlierVersion = false;
    req.onupgradeneeded = event => {
      if (event.oldVersion > 0) {
        earlierVersion = true;
        req.transaction!.abort();
        return;
      }
      const db = req.result;
      db.createObjectStore('recordings', { keyPath: 'id' }).createIndex('byStartedAtId', ['startedAt', 'id']);
      for (const name of ['samples', 'ride-lifecycle', 'ride-locations']) {
        const store = db.createObjectStore(name, { keyPath: ['recordingId', 'producer', 'sequenceKey'] });
        if (name === 'samples') store.createIndex('byLocalSequence', ['recordingId', 'sequence'], { unique: true });
        store.createIndex('byEventId', ['recordingId', 'eventId'], { unique: true });
        store.createIndex('byElapsed', ['recordingId', 'elapsedKey', 'producer', 'sequenceKey']);
        store.createIndex('byRevision', ['recordingId', 'revisionKey', 'producer', 'sequenceKey']);
      }
      db.createObjectStore('ride-control', { keyPath: 'key' });
      db.createObjectStore('ride-deletions', { keyPath: 'recordingId' });
      db.createObjectStore('ride-tiles', { keyPath: ['recordingId', 'level', 'bucketKey', 'revisionKey'] });
      db.createObjectStore('distance-profiles', { keyPath: ['recordingId', 'source', 'generationKey', 'revisionKey'] });
      const distances = db.createObjectStore('distance-intervals', {
        keyPath: ['recordingId', 'source', 'generationKey', 'endKey', 'producer', 'sequenceKey', 'revisionKey'],
      });
      distances.createIndex('byRevision', ['recordingId', 'source', 'generationKey', 'revisionKey', 'endKey']);
      db.createObjectStore('distance-tiles', {
        keyPath: ['recordingId', 'source', 'generationKey', 'level', 'bucketKey', 'revisionKey'],
      });
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
    req.onblocked = () => {};
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
    } catch {}
    await done.catch(() => {});
    throw error;
  }
}
export function normalize(value: unknown): BrowserRide {
  if (
    !isRecord(value) ||
    typeof value.id !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value.id) ||
    value.id.includes('..')
  )
    throw new Error('Invalid recording identifier');
  validateTimestamp(value.startedAt);
  validateTimestamp(value.checkpointAt);
  if (value.endedAt !== undefined) validateTimestamp(value.endedAt);
  for (const field of [
    'telemetryCount',
    'locationCount',
    'lifecycleCount',
    'originalMembershipCount',
    'lapCount',
  ] as const) {
    if (!Number.isSafeInteger(value[field]) || (value[field] as number) < 0)
      throw new Error(`Invalid recording ${field}`);
  }
  if (typeof value.revision !== 'string' || int64(value.revision) < 1n || int64(value.revision) > 9007199254740991n)
    throw new Error('Invalid recording revision');
  for (const field of ['elapsedSeconds', 'timerSeconds'] as const) {
    if (typeof value[field] !== 'number' || !Number.isFinite(value[field]) || value[field] < 0)
      throw new Error(`Invalid recording ${field}`);
  }
  if ((value.timerSeconds as number) > (value.elapsedSeconds as number))
    throw new Error('Invalid recording checkpoint');
  if (!sampleRates.includes(value.sampleHz as number)) throw new Error('Invalid recording sampleHz');
  if (
    typeof value.recordGPS !== 'boolean' ||
    value.recordingOwner !== 'phone' ||
    value.ownerDevice !== 'browser' ||
    value.saveToHealth !== false
  )
    throw new Error('Invalid frozen recording options');
  if (
    value.originalMembershipCount !==
    (value.telemetryCount as number) + (value.locationCount as number) + (value.lifecycleCount as number)
  )
    throw new Error('Invalid recording counts');
  if (typeof value.indoor !== 'boolean' || typeof value.interrupted !== 'boolean')
    throw new Error('Invalid recording flags');
  if (!Array.isArray(value.availableMetrics) || !value.availableMetrics.every(metric => typeof metric === 'string'))
    throw new Error('Invalid recording metrics');
  if (!['gps:phone', 'controller'].includes(String(value.distanceSource)))
    throw new Error('Invalid recording distance source');
  if (!['controller', 'calculated'].includes(String(value.batteryConsumptionSource)))
    throw new Error('Invalid recording battery consumption source');
  if (value.phase === 'completed') {
    if (value.endedAt === undefined || value.writer !== undefined) throw new Error('Invalid completed recording');
  } else if (['running', 'paused', 'interrupted'].includes(String(value.phase))) {
    if (
      value.endedAt !== undefined ||
      typeof value.writer !== 'string' ||
      !/^[A-Za-z0-9._:-]{1,128}$/.test(value.writer)
    )
      throw new Error('Invalid recording owner');
  } else throw new Error('Invalid recording phase');
  return {
    ...value,
    availableMetrics: value.availableMetrics.filter(metric => MONITOR_METRICS.some(item => item.id === metric)),
  } as BrowserRide;
}
async function recordIn(tx: IDBTransaction, id: string): Promise<BrowserRide> {
  const value: unknown = await idbRequest(tx.objectStore('recordings').get(id));
  if (!value) throw new Error('Recording not found');
  return normalize(value);
}
async function owned(tx: IDBTransaction, id: string, token: string): Promise<BrowserRide> {
  const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
  const record = await recordIn(tx, id);
  if (owner?.id !== id || owner.token !== token || record.writer !== token)
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
    eventCount: String(record.originalMembershipCount),
    interrupted: record.interrupted,
    healthKitState: 'notRequested',
    collectionRevision: record.revision,
    saveToHealth: false,
    distanceSource: record.distanceSource,
    batteryConsumptionSource: record.batteryConsumptionSource,
    recordGPS: record.recordGPS,
    sampleHz: record.sampleHz,
    storage: 'browser',
  };
}
export function browserAnalysisItem(
  row: RideRow | RideLocationRow | RideLifecycleRow,
  includeInactive = false,
): AnalyticsItem {
  const kind = 'action' in row ? 'lifecycle' : 'latitude' in row ? 'location' : 'telemetry';
  if (kind === 'telemetry') validateSample(row);
  if (kind === 'location') validateLocation(row as RideLocationRow);
  const values = Object.entries(row).filter(
    ([, value]) =>
      typeof value === 'number' && Number.isFinite(value) && (includeInactive || !('active' in row) || row.active),
  );
  return new AnalyticsItem(
    row.elapsedUs,
    row.elapsedSeconds,
    row.eventId,
    kind === 'telemetry' ? `${row.recordingId}:${(row as RideRow).sequence}` : `${row.recordingId}:${row.eventId}`,
    row.producer,
    row.producerSequence,
    kind,
    kind === 'telemetry' ? 'controller' : 'phone',
    row.timestamp,
    values.map(([key]) => key),
    values.map(([, value]) => (Number.isSafeInteger(value) && !Object.is(value, -0) ? 'integer64' : 'real64')),
    values.map(([, value]) => (Object.is(value, -0) ? '-0.0' : String(value))),
    'originalElapsedSeconds' in row ? row.originalElapsedSeconds : null,
    null,
    'clockEpoch' in row ? row.clockEpoch : 'interruption' in row ? (row.interruption?.clockEpoch ?? null) : null,
    'connectionEpoch' in row ? row.connectionEpoch : null,
    'interruptionIndex' in row ? row.interruptionIndex : 0,
    'action' in row ? row.action : null,
    'distanceBarrier' in row ? row.distanceBarrier : false,
    'interrupted' in row ? row.interrupted : false,
    'interruption' in row
      ? (row.interruption?.controllerSequence ?? null)
      : kind === 'telemetry'
        ? row.producerSequence
        : null,
    true,
  );
}
export const browserInspectionItem = (row: RideRow | RideLocationRow | RideLifecycleRow): AnalyticsItem =>
  browserAnalysisItem(row, true);
export function rowPoint(row: RideRow, metric: string): MonitorPoint | undefined {
  const value = row[metric as keyof TelemetrySample];
  if (typeof value !== 'number' || !Number.isFinite(value)) return;
  return {
    value,
    elapsedSeconds: row.elapsedSeconds,
    timestamp: row.timestamp,
    observationId: `${row.recordingId}:${row.sequence}`,
  };
}
type AggregateHandle = { payload: Aggregate; plot?: InspectionPlotCache; checkpoint?: InspectionMetricCheckpoint };
const captureHandles = new WeakMap<IDBDatabase, Map<string, AggregateHandle>>();
const captureEncoders = new WeakMap<IDBDatabase, InspectionCacheEncoder>();
async function project(
  tx: IDBTransaction,
  rows: RideRow[],
  revision: string,
  available: Set<string>,
  rideEnd: number,
): Promise<void> {
  const tiles = tx.objectStore('ride-tiles');
  const samples = tx.objectStore('samples').index('byElapsed');
  const affected = [...rows];
  const leaves = new Map<string, { id: string; bucket: number }>();
  for (const row of rows) {
    const bucket = Math.floor(row.elapsedSeconds / TILE_SECONDS[0]);
    leaves.set(JSON.stringify([row.recordingId, bucket]), { id: row.recordingId, bucket });
  }
  const successors = await Promise.all(
    [...leaves.values()].map(async ({ id, bucket }) => {
      const cursor = await idbRequest(
        samples.openCursor(IDBKeyRange.bound([id, secondsKey((bucket + 1) * TILE_SECONDS[0])], [id, []])),
      );
      return cursor?.value as RideRow | undefined;
    }),
  );
  const retained = new Set(rows.map(row => row.eventId));
  for (const row of successors)
    if (row && !retained.has(row.eventId)) {
      affected.push(row);
      retained.add(row.eventId);
    }
  const id = rows[0]?.recordingId;
  const lifecycle = id
    ? (
        await idbRequest<RideLifecycleRow[]>(
          tx
            .objectStore('ride-lifecycle')
            .index('byElapsed')
            .getAll(IDBKeyRange.bound([id], [id, []])),
        )
      ).map(row => browserAnalysisItem(row))
    : [];
  let encoder = captureEncoders.get(tx.db);
  if (!encoder) {
    encoder = new InspectionCacheEncoder();
    captureEncoders.set(tx.db, encoder);
  }
  let handles = captureHandles.get(tx.db);
  if (!handles) {
    handles = new Map();
    captureHandles.set(tx.db, handles);
  }
  const handleKey = (id: string, level: number, bucket: number, metric: string) =>
    JSON.stringify([id, level, bucket, metric]);
  const retain = (key: string, handle: AggregateHandle) => {
    handles!.delete(key);
    handles!.set(key, handle);
    if (handles!.size > 1024) handles!.delete(handles!.keys().next().value!);
    return handle;
  };
  const decoded = (id: string, level: number, bucket: number, metric: string, payload: Aggregate): AggregateHandle => {
    const key = handleKey(id, level, bucket, metric),
      cached = handles!.get(key);
    if (cached && cached.payload.plot === payload.plot && cached.payload.checkpoint === payload.checkpoint)
      return cached;
    return retain(key, {
      payload,
      plot: payload.plot ? new InspectionPlotCache(payload.plot) : undefined,
      checkpoint: payload.checkpoint ? new InspectionMetricCheckpoint(payload.checkpoint) : undefined,
    });
  };
  const updates = new Map<string, RideTile>();
  const key = (id: string, level: number, bucket: number) => JSON.stringify([id, level, bucket]);
  const read = async (id: string, level: number, bucket: number): Promise<RideTile | undefined> => {
    const updated = updates.get(key(id, level, bucket));
    if (updated) return updated;
    return (
      await idbRequest(
        tiles.openCursor(
          IDBKeyRange.bound(
            [id, level, browserIndexKey(String(bucket))],
            [id, level, browserIndexKey(String(bucket)), []],
          ),
          'prev',
        ),
      )
    )?.value as RideTile | undefined;
  };
  for (let level = 0; level < TILE_SECONDS.length; level++) {
    const size = TILE_SECONDS[level]!;
    const buckets = new Map<string, { id: string; bucket: number }>();
    for (const row of affected)
      buckets.set(key(row.recordingId, level, Math.floor(row.elapsedSeconds / size)), {
        id: row.recordingId,
        bucket: Math.floor(row.elapsedSeconds / size),
      });
    await Promise.all(
      [...buckets.values()].map(async ({ id, bucket }) => {
        const start = bucket * size,
          end = start + size;
        const stats: Record<string, Aggregate> = {};
        const previous =
          level === 0 && start > 0
            ? ((
                await idbRequest(
                  samples.openCursor(IDBKeyRange.bound([id], [id, secondsKey(start)], false, true), 'prev'),
                )
              )?.value as RideRow | undefined)
            : undefined;
        const predecessor = previous ? browserAnalysisItem(previous) : null;
        if (level === 0) {
          const prior = await read(id, level, bucket);
          const raw: RideRow[] = [];
          if (prior) {
            let lower: IDBValidKey[] = [id, secondsKey(start)];
            let exclusive = false;
            while (true) {
              const range = browserKeyRange(lower, [id, secondsKey(end)], exclusive, true);
              if (!range) break;
              const page = await idbRequest<RideRow[]>(samples.getAll(range, 256));
              raw.push(...page);
              if (page.length < 256) break;
              const last = page.at(-1)!;
              lower = [id, secondsKey(last.elapsedSeconds), last.producer, browserIndexKey(last.producerSequence)];
              exclusive = true;
            }
          } else
            raw.push(
              ...affected
                .filter(row => row.recordingId === id && row.elapsedSeconds >= start && row.elapsedSeconds < end)
                .sort(
                  (a, b) =>
                    Number(BigInt(a.elapsedUs) - BigInt(b.elapsedUs)) ||
                    Number(BigInt(a.producerSequence) - BigInt(b.producerSequence)),
                ),
            );
          const items = raw.map(row => new InspectionOriginalInput(browserInspectionItem(row)));
          const statisticsItems = raw.map((row, index) =>
            row.active ? items[index]! : new InspectionOriginalInput(browserAnalysisItem(row)),
          );
          for (const metric of MONITOR_METRICS) {
            if (
              !available.has(metric.id) ||
              metric.id === 'distanceMeters' ||
              new InspectionMetricPolicy(metric.id).channel !== 'telemetry'
            )
              continue;
            const reducer = new InspectionPlotBucket(metric.id, secondsUs(start), secondsUs(end));
            const statistics = new InspectionMetricBucket(
              metric.id,
              secondsUs(start),
              secondsUs(end),
              secondsUs(rideEnd),
              lifecycle,
              predecessor,
            );
            statisticsItems.forEach(item => statistics.appendPrepared(item, true));
            const plot = optionalChartCache(() => {
                items.forEach(item => reducer.appendOriginal(item, true));
                return reducer.aggregate();
              }),
              checkpoint = statistics.checkpoint() ?? undefined;
            const payload = {
              plot: plot ? encoder.plot(plot) : undefined,
              checkpoint: checkpoint ? encoder.statistics(checkpoint) : undefined,
            };
            stats[metric.id] = payload;
            retain(handleKey(id, level, bucket, metric.id), { payload, plot, checkpoint });
          }
        } else {
          const childSize = TILE_SECONDS[level - 1]!;
          const children = await Promise.all(
            Array.from({ length: size / childSize }, (_, index) =>
              read(id, level - 1, (bucket * size) / childSize + index),
            ),
          );
          const metrics = new Set(
            children
              .flatMap(child => Object.keys(child?.stats ?? {}))
              .filter(metric => MONITOR_METRICS.some(item => item.id === metric)),
          );
          for (const metric of metrics) {
            const reducer = new InspectionPlotBucket(metric, secondsUs(start), secondsUs(end));
            const statistics = new InspectionMetricBucket(
              metric,
              secondsUs(start),
              secondsUs(end),
              secondsUs(rideEnd),
              lifecycle,
              predecessor,
            );
            const leading = children[0]?.stats[metric]?.checkpoint;
            if (leading)
              statistics.inheritPredecessor(
                decoded(id, level - 1, (bucket * size) / childSize, metric, children[0]!.stats[metric]!).checkpoint!,
              );
            const plots: (InspectionPlotCache | undefined)[] = [];
            for (const child of children) {
              const value = child?.stats[metric];
              if (value) {
                const handle = decoded(id, level - 1, child!.bucket, metric, value);
                plots.push(handle.plot);
                if (handle.checkpoint) statistics.mergeOwned(handle.checkpoint);
              }
            }
            const plot = plots.every(plot => plot !== undefined)
                ? optionalChartCache(() => {
                    plots.forEach(plot => reducer.merge(plot!));
                    return reducer.aggregate();
                  })
                : undefined,
              checkpoint = statistics.checkpoint() ?? undefined;
            const payload = {
              plot: plot ? encoder.plot(plot) : undefined,
              checkpoint: checkpoint ? encoder.statistics(checkpoint) : undefined,
            };
            stats[metric] = payload;
            retain(handleKey(id, level, bucket, metric), { payload, plot, checkpoint });
          }
        }
        const tile = { recordingId: id, level, bucket, stats };
        updates.set(key(id, level, bucket), tile);
        await idbRequest(
          tiles.put({
            ...tile,
            revision,
            bucketKey: browserIndexKey(String(bucket)),
            revisionKey: browserIndexKey(revision),
          }),
        );
        await idbRequest(
          tiles.delete(
            IDBKeyRange.bound(
              [id, level, browserIndexKey(String(bucket))],
              [id, level, browserIndexKey(String(bucket)), browserIndexKey(revision)],
              false,
              true,
            ),
          ),
        );
      }),
    );
  }
}
async function refreshInspection(tx: IDBTransaction, record: BrowserRide): Promise<void> {
  const start = Math.max(0, Math.floor(record.elapsedSeconds / 16) * 16 - 16);
  const rows = await idbRequest<RideRow[]>(
    tx
      .objectStore('samples')
      .index('byElapsed')
      .getAll(
        IDBKeyRange.bound([record.id, secondsKey(start)], [record.id, secondsKey(record.elapsedSeconds), []]),
        256,
      ),
  );
  if (rows.length) await project(tx, rows, record.revision, new Set(record.availableMetrics), record.elapsedSeconds);
}
const nextRevision = (revision: string) => (int64(revision) + 1n).toString();
const ORIGINAL_STORES = ['samples', 'ride-lifecycle', 'ride-locations'] as const;
function validateOriginal(row: OriginalIdentity & { timestamp: string; elapsedSeconds: number }): void {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(row.eventId))
    throw new Error('Invalid original event identity');
  if (!['controller', 'browser'].includes(row.producer) || int64(row.producerSequence) <= 0n)
    throw new Error('Invalid original producer position');
  if (row.queryUs !== timestampUs(row.timestamp) || row.elapsedUs !== secondsUs(row.elapsedSeconds))
    throw new Error('Original comparison time changed');
}
function indexOriginal<T extends OriginalIdentity>(row: T, revision: string): IndexedOriginal<T> {
  return {
    ...row,
    revision,
    sequenceKey: browserIndexKey(row.producerSequence),
    elapsedKey: browserIndexKey(row.elapsedUs),
    revisionKey: browserIndexKey(String(revision)),
  };
}
async function highWater(tx: IDBTransaction, id: string, producer: OriginalIdentity['producer']): Promise<bigint> {
  const rows = await Promise.all(
    ORIGINAL_STORES.map(async name => {
      const cursor = await idbRequest(
        tx.objectStore(name).openCursor(IDBKeyRange.bound([id, producer], [id, producer, []]), 'prev'),
      );
      return cursor ? int64((cursor.value as OriginalIdentity).producerSequence) : 0n;
    }),
  );
  return rows.reduce((a, b) => (a > b ? a : b), 0n);
}
type OriginalLookup<T extends OriginalIdentity> = {
  kind: (typeof ORIGINAL_STORES)[number];
  byId?: IndexedOriginal<T>;
  byPosition?: IndexedOriginal<T>;
}[];
async function lookupOriginal<T extends OriginalIdentity>(
  tx: IDBTransaction,
  row: T & { recordingId: string },
): Promise<OriginalLookup<T>> {
  const position = [row.recordingId, row.producer, browserIndexKey(row.producerSequence)];
  return Promise.all(
    ORIGINAL_STORES.map(async kind => {
      const store = tx.objectStore(kind);
      const [byId, byPosition] = await Promise.all([
        idbRequest<IndexedOriginal<T> | undefined>(store.index('byEventId').get([row.recordingId, row.eventId])),
        idbRequest<IndexedOriginal<T> | undefined>(store.get(position)),
      ]);
      return { kind, byId, byPosition };
    }),
  );
}
async function admitOriginal<T extends OriginalIdentity>(
  tx: IDBTransaction,
  name: string,
  row: T & { recordingId: string },
  revision: string,
  lookup?: OriginalLookup<T>,
): Promise<boolean> {
  let replay = false;
  for (const { kind, byId, byPosition } of lookup ?? (await lookupOriginal(tx, row))) {
    if (byId || byPosition) {
      if (
        kind !== name ||
        byId?.eventId !== byPosition?.eventId ||
        byId?.producerSequence !== row.producerSequence ||
        byId?.producer !== row.producer
      )
        throw new Error('Conflicting original identity or producer position');
      const indexed = indexOriginal(row, byId.revision);
      if (JSON.stringify(Object.entries(indexed).sort()) !== JSON.stringify(Object.entries(byId).sort()))
        throw new Error('Original content changed on replay');
      replay = true;
    }
  }
  if (!replay) await idbRequest(tx.objectStore(name).add(indexOriginal(row, revision)));
  return !replay;
}
async function composedLapCount(tx: IDBTransaction, record: BrowserRide): Promise<number> {
  if (record.elapsedSeconds <= 0) return 0;
  return new Promise((resolve, reject) => {
    let count = 1;
    let last: string | undefined;
    const req = tx
      .objectStore('ride-lifecycle')
      .index('byElapsed')
      .openCursor(
        IDBKeyRange.bound(
          [record.id, browserIndexKey('0')],
          [record.id, secondsKey(record.elapsedSeconds)],
          true,
          true,
        ),
      );
    req.onerror = () => reject(req.error);
    req.onsuccess = () => {
      const cursor = req.result;
      if (!cursor) {
        resolve(count);
        return;
      }
      const event = cursor.value as RideLifecycleRow;
      if (event.action === 'lap' && int64(event.elapsedUs) > 0n && event.elapsedUs !== last) {
        count++;
        last = event.elapsedUs;
      }
      cursor.continue();
    };
  });
}
async function saveRecord(tx: IDBTransaction, record: BrowserRide): Promise<void> {
  record.lapCount = await composedLapCount(tx, record);
  normalize(record);
  await idbRequest(tx.objectStore('recordings').put(record));
}
async function observeLifecycle(
  tx: IDBTransaction,
  record: BrowserRide,
  action: LifecycleAction,
  producerSequence?: string,
): Promise<void> {
  const sequence = producerSequence ?? ((await highWater(tx, record.id, 'browser')) + 1n).toString();
  const eventId = crypto.randomUUID();
  const row: RideLifecycleRow = {
    recordingId: record.id,
    revision: record.revision,
    eventId,
    producer: 'browser',
    producerSequence: sequence,
    queryUs: timestampUs(record.checkpointAt),
    elapsedUs: secondsUs(record.elapsedSeconds),
    action,
    timestamp: record.checkpointAt,
    elapsedSeconds: record.elapsedSeconds,
    timerSeconds: record.timerSeconds,
    interrupted: record.phase === 'interrupted' && action === 'pause',
  };
  if (row.interrupted)
    row.interruption = {
      cutoffUs: row.elapsedUs,
      producer: 'browser',
      producerSequence: sequence,
      controllerSequence: (await highWater(tx, record.id, 'controller')).toString(),
      eventId,
    };
  await admitOriginal(tx, 'ride-lifecycle', row, record.revision);
  record.lifecycleCount++;
  record.originalMembershipCount++;
}
export function validateLocation(row: RideLocationRow): RideLocationRow {
  validateOriginal(row);
  timestampUs(row.originalTimestamp);
  if (
    row.producer !== 'browser' ||
    typeof row.active !== 'boolean' ||
    typeof row.distanceBarrier !== 'boolean' ||
    !row.clockEpoch ||
    !Number.isSafeInteger(row.interval) ||
    row.interval < 0 ||
    !Number.isFinite(row.acquiredAtSeconds) ||
    !Number.isFinite(row.originalElapsedSeconds)
  )
    throw new Error('Invalid location acquisition evidence');
  if (
    !Number.isFinite(row.latitude) ||
    Math.abs(row.latitude) > 90 ||
    !Number.isFinite(row.longitude) ||
    Math.abs(row.longitude) > 180
  )
    throw new Error('Invalid location coordinates');
  if (!['msl', 'wgs84Ellipsoid', 'unknown'].includes(row.originalAltitudeDatum))
    throw new Error('Invalid altitude datum');
  for (const field of [
    'horizontalAccuracyM',
    'speedMps',
    'speedAccuracyMps',
    'courseAccuracyDegrees',
    'courseDegrees',
    'originalAltitudeMeters',
    'originalVerticalAccuracyM',
    'altitudeMeters',
    'verticalAccuracyM',
  ] as const)
    if (row[field] !== undefined && !Number.isFinite(row[field])) throw new Error(`Invalid location ${field}`);
  if ((row.altitudeMeters === undefined) !== (row.verticalAccuracyM === undefined))
    throw new Error('Incomplete canonical MSL altitude');
  return row;
}
async function originalNeighbor<T extends OriginalIdentity>(
  name: string,
  id: string,
  us: string,
  direction: 'prev' | 'next',
  at?: string,
): Promise<T | undefined> {
  const key = browserIndexKey(us);
  return browserTransaction(
    [name],
    'readonly',
    tx =>
      new Promise<T | undefined>((resolve, reject) => {
        const range =
          direction === 'prev' ? IDBKeyRange.bound([id], [id, key, []]) : IDBKeyRange.bound([id, key], [id, []]);
        const request = tx.objectStore(name).index('byElapsed').openCursor(range, direction);
        request.onerror = () => reject(request.error);
        request.onsuccess = () => {
          try {
            const cursor = request.result;
            if (!cursor) return resolve(undefined);
            const row = cursor.value as IndexedOriginal<T>;
            if (!at || int64(row.revision) <= int64(at)) resolve(row);
            else cursor.continue();
          } catch (error) {
            reject(error);
          }
        };
      }),
  );
}
async function eraseRide(tx: IDBTransaction, id: string): Promise<void> {
  for (const name of [
    'samples',
    'ride-lifecycle',
    'ride-locations',
    'ride-tiles',
    'distance-profiles',
    'distance-intervals',
    'distance-tiles',
  ])
    await idbRequest(tx.objectStore(name).delete(IDBKeyRange.bound([id], [id, []])));
  await idbRequest(
    tx
      .objectStore('ride-deletions')
      .put({ recordingId: id, actionId: crypto.randomUUID(), requestedAt: new Date().toISOString() }),
  );
  await idbRequest(tx.objectStore('recordings').delete(id));
}
export class BrowserRideStore {
  async claim(id: string, token: string): Promise<BrowserRide> {
    return browserTransaction(['recordings', 'ride-control'], 'readwrite', async tx => {
      const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
      if (owner?.id !== id) throw new Error('The selected owner changed.');
      const record = { ...(await recordIn(tx, id)), writer: token };
      await idbRequest(tx.objectStore('recordings').put(normalize(record)));
      await idbRequest(tx.objectStore('ride-control').put({ key: 'current', id, token }));
      return record;
    });
  }
  async get(id: string) {
    return browserTransaction(['recordings'], 'readonly', tx => recordIn(tx, id));
  }
  async current() {
    return browserTransaction(['recordings', 'ride-control'], 'readonly', async tx => {
      const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
      return owner ? recordIn(tx, owner.id) : null;
    });
  }
  async begin(token: string, options: BrowserRideOptions, startedAt: string): Promise<BrowserRide> {
    return browserTransaction(STORES, 'readwrite', async tx => {
      if (await idbRequest(tx.objectStore('ride-control').get('current')))
        throw new Error('Finish or discard the interrupted ride first.');
      const record: BrowserRide = {
        id: crypto.randomUUID(),
        startedAt,
        ...options,
        distanceSource: options.distanceSource ?? (options.recordGPS && !options.indoor ? 'gps:phone' : 'controller'),
        batteryConsumptionSource: options.batteryConsumptionSource ?? 'controller',
        telemetryCount: 0,
        locationCount: 0,
        lifecycleCount: 0,
        originalMembershipCount: 0,
        recordingOwner: 'phone',
        ownerDevice: 'browser',
        saveToHealth: false,
        interrupted: false,
        phase: 'running',
        revision: '1',
        writer: token,
        elapsedSeconds: 0,
        timerSeconds: 0,
        checkpointAt: startedAt,
        availableMetrics: [],
        lapCount: 0,
      };
      await observeLifecycle(tx, record, 'start', '1');
      await saveRecord(tx, record);
      await idbRequest(tx.objectStore('ride-control').put({ key: 'current', id: record.id, token }));
      return record;
    });
  }
  async producerHighWater(id: string, producer: OriginalIdentity['producer']): Promise<string> {
    return browserTransaction([...ORIGINAL_STORES], 'readonly', async tx =>
      (await highWater(tx, id, producer)).toString(),
    );
  }
  async append(
    id: string,
    token: string,
    rows: RideRow[],
    elapsed: number,
    timer: number,
    at: string,
  ): Promise<BrowserRide> {
    return this.appendOriginals(id, token, 'samples', rows, elapsed, timer, at);
  }
  async appendLocations(
    id: string,
    token: string,
    rows: RideLocationRow[],
    elapsed: number,
    timer: number,
    at: string,
  ): Promise<BrowserRide> {
    return this.appendOriginals(id, token, 'ride-locations', rows, elapsed, timer, at);
  }
  private async appendOriginals(
    id: string,
    token: string,
    kind: 'samples' | 'ride-locations',
    rows: (RideRow | RideLocationRow)[],
    elapsed: number,
    timer: number,
    at: string,
  ): Promise<BrowserRide> {
    if (!rows.length || rows.length > BROWSER_BATCH) throw new Error('Invalid browser recording batch');
    return browserTransaction(STORES, 'readwrite', async tx => {
      const record = await owned(tx, id, token);
      if (!['running', 'paused'].includes(record.phase)) throw new Error('Recording is no longer active');
      if (kind === 'ride-locations' && !record.recordGPS) throw new Error('GPS was not enabled at Start');
      const accepted: (RideRow | RideLocationRow)[] = [];
      const unique: (RideRow | RideLocationRow)[] = [];
      const positions = new Map<string, RideRow | RideLocationRow>();
      const eventIds = new Map<string, RideRow | RideLocationRow>();
      for (const row of rows) {
        const position = `${row.producer}:${row.producerSequence}`;
        const duplicate = positions.get(position) ?? eventIds.get(row.eventId);
        if (duplicate) {
          if (JSON.stringify(Object.entries(duplicate).sort()) !== JSON.stringify(Object.entries(row).sort()))
            throw new Error('Conflicting original identity or producer position');
          continue;
        }
        positions.set(position, row);
        eventIds.set(row.eventId, row);
        unique.push(row);
      }
      const lookups = await Promise.all(unique.map(row => lookupOriginal(tx, row)));
      for (const row of unique) {
        validateOriginal(row);
        if (row.recordingId !== id) throw new Error('Recording destination changed');
        if (kind === 'samples') {
          const sample = row as RideRow;
          validateSample(sample);
          if (
            sample.producer !== 'controller' ||
            !sample.connectionEpoch ||
            typeof sample.active !== 'boolean' ||
            !Number.isSafeInteger(sample.interval) ||
            sample.interval < 0 ||
            !Number.isSafeInteger(sample.originalSequence) ||
            sample.originalSequence < 0 ||
            !Number.isFinite(sample.originalElapsedSeconds) ||
            sample.originalElapsedSeconds < 0
          )
            throw new Error('Invalid recording observation');
        } else validateLocation(row as RideLocationRow);
      }
      const admitted = await Promise.all(
        unique.map((row, index) => admitOriginal(tx, kind, row, nextRevision(record.revision), lookups[index])),
      );
      let previousElapsed = -Infinity;
      for (const [index, row] of unique.entries()) {
        if (admitted[index]) {
          if (row.elapsedSeconds < previousElapsed) throw new Error('Recording time changed');
          previousElapsed = row.elapsedSeconds;
          accepted.push(row);
        }
      }
      if (!accepted.length) return record;
      if (elapsed < previousElapsed || !Number.isFinite(timer) || timer > elapsed)
        throw new Error('Invalid recording checkpoint');
      const available = new Set(record.availableMetrics);
      if (kind === 'ride-locations')
        for (const row of accepted as RideLocationRow[])
          for (const metric of MONITOR_METRICS)
            if (typeof row[metric.id as keyof RideLocationRow] === 'number') available.add(metric.id);
      if (kind === 'samples')
        for (const row of accepted as RideRow[])
          for (const metric of MONITOR_METRICS) if (rowPoint(row, metric.id)) available.add(metric.id);
      if (kind === 'samples')
        await project(tx, accepted as RideRow[], nextRevision(record.revision), available, elapsed);
      const next: BrowserRide = {
        ...record,
        telemetryCount: record.telemetryCount + (kind === 'samples' ? accepted.length : 0),
        locationCount: record.locationCount + (kind === 'ride-locations' ? accepted.length : 0),
        originalMembershipCount: record.originalMembershipCount + accepted.length,
        revision: nextRevision(record.revision),
        elapsedSeconds: Math.max(record.elapsedSeconds, elapsed),
        timerSeconds: Math.max(record.timerSeconds, timer),
        checkpointAt: elapsed >= record.elapsedSeconds ? at : record.checkpointAt,
        availableMetrics: [...available],
      };
      await saveRecord(tx, next);
      return next;
    });
  }
  async transition(
    id: string,
    token: string,
    action: Exclude<LifecycleAction, 'start'>,
    elapsed: number,
    timer: number,
    at: string,
    producerSequence?: string,
  ): Promise<BrowserRide> {
    return browserTransaction(STORES, 'readwrite', async tx => {
      const record = await owned(tx, id, token);
      const allowed =
        action === 'stop'
          ? ['running', 'paused', 'interrupted']
          : action === 'pause'
            ? ['running']
            : action === 'resume'
              ? ['paused']
              : ['running'];
      if (!allowed.includes(record.phase) || !['stop', 'pause', 'resume', 'lap'].includes(action))
        throw new Error('This action is unavailable for the current ride.');
      const interrupted = record.phase === 'interrupted';
      if (
        !interrupted &&
        (!Number.isFinite(elapsed) ||
          !Number.isFinite(timer) ||
          elapsed < record.elapsedSeconds ||
          timer < record.timerSeconds ||
          timer > elapsed)
      )
        throw new Error('Invalid ride timing');
      const next: BrowserRide = {
        ...record,
        revision: nextRevision(record.revision),
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
        next.endedAt = next.checkpointAt;
        delete next.writer;
      }
      await observeLifecycle(tx, next, action, producerSequence);
      await refreshInspection(tx, next);
      await saveRecord(tx, next);
      if (!next.writer) await idbRequest(tx.objectStore('ride-control').delete('current'));
      return next;
    });
  }
  /** Called only while holding the recording Web Lock; no recording is resumed after process loss. */
  async interruptCurrent(): Promise<BrowserRide | null> {
    return browserTransaction(STORES, 'readwrite', async tx => {
      const owner = await idbRequest<Owner | undefined>(tx.objectStore('ride-control').get('current'));
      if (!owner) return null;
      const record = await recordIn(tx, owner.id);
      if (record.phase === 'interrupted') return record;
      const next: BrowserRide = {
        ...record,
        phase: 'interrupted',
        interrupted: true,
        revision: nextRevision(record.revision),
      };
      await observeLifecycle(tx, next, 'pause');
      await refreshInspection(tx, next);
      await saveRecord(tx, next);
      return next;
    });
  }
  async discard(id: string, token: string): Promise<void> {
    await browserTransaction(STORES, 'readwrite', async tx => {
      await owned(tx, id, token);
      await eraseRide(tx, id);
      await idbRequest(tx.objectStore('ride-control').delete('current'));
    });
  }
  async remove(id: string): Promise<void> {
    if (typeof navigator === 'undefined' || !navigator.locks)
      throw new Error('Deleting recordings requires a browser with Web Locks support.');
    await navigator.locks.request(BROWSER_OWNER_WORK_LOCK, { ifAvailable: true }, async lock => {
      if (!lock) throw new Error('A recording action is in progress.');
      await browserTransaction(STORES, 'readwrite', async tx => {
        const record = await recordIn(tx, id);
        if (record.phase !== 'completed') throw new Error('Finish or discard the active ride first.');
        await eraseRide(tx, id);
      });
    });
  }
  async openRead(id: string, exportRead = false): Promise<BrowserRide> {
    return browserTransaction(['recordings', 'ride-deletions'], 'readonly', async tx => {
      const record = await recordIn(tx, id);
      if (await idbRequest(tx.objectStore('ride-deletions').get(id))) throw new Error('Recording was deleted');
      if (exportRead && !completionOpenExport(JSON.stringify(metadataOf(record)), id, record.revision))
        throw new Error('Save the ride before exporting it.');
      return record;
    });
  }
  async checkRead(record: BrowserRide, exportRead = false): Promise<void> {
    const current = await this.openRead(record.id, exportRead);
    if (exportRead && current.revision !== record.revision) throw new Error('Recording changed during the read');
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
    revision?: string,
  ): Promise<RideRow[]> {
    const anchor = after ? await this.bySequence(id, after[1]) : undefined;
    if (after && !anchor) throw new Error('Original cursor no longer exists');
    return this.pageExact(
      id,
      secondsUs(start),
      end === Infinity ? '9223372036854775807' : secondsUs(end),
      anchor ? [anchor.elapsedUs, anchor.producer, anchor.producerSequence] : null,
      limit,
      revision,
    );
  }
  async pageExact(
    id: string,
    startUs: string,
    endUs: string,
    after: OriginalCursor | null = null,
    limit = BROWSER_PAGE,
    revision?: string,
  ): Promise<RideRow[]> {
    return this.originalPage<RideRow>('samples', id, startUs, endUs, after, limit, revision);
  }
  async analysisPage(
    name: 'samples' | 'ride-locations' | 'ride-lifecycle',
    id: string,
    after: OriginalCursor | null,
    revision: string,
  ): Promise<(RideRow | RideLocationRow | RideLifecycleRow)[]> {
    return this.originalPage(name, id, '-9223372036854775808', '9223372036854775807', after, BROWSER_PAGE, revision);
  }
  async locationPage(
    id: string,
    startUs: string,
    endUs: string,
    after: OriginalCursor | null,
    revision: string,
  ): Promise<RideLocationRow[]> {
    return this.originalPage('ride-locations', id, startUs, endUs, after, BROWSER_PAGE, revision);
  }
  private async originalPage<T>(
    name: string,
    id: string,
    startUs: string,
    endUs: string,
    after: OriginalCursor | null,
    limit: number,
    revision?: string,
  ): Promise<T[]> {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 1025) throw new Error('Invalid browser read bound');
    const lower = after
      ? [id, browserIndexKey(after[0]), after[1], browserIndexKey(after[2])]
      : [id, browserIndexKey(startUs)];
    const upper = [id, browserIndexKey(endUs), []];
    const range = browserKeyRange(lower, upper, Boolean(after));
    if (!range) return [];
    return browserTransaction(
      [name],
      'readonly',
      tx =>
        new Promise<T[]>((resolve, reject) => {
          const result: T[] = [];
          const request = tx.objectStore(name).index('byElapsed').openCursor(range);
          request.onerror = () => reject(request.error);
          request.onsuccess = () => {
            const cursor = request.result;
            if (!cursor) {
              resolve(result);
              return;
            }
            const row = cursor.value as T & { revision: string };
            if (revision === undefined || int64(row.revision) <= int64(revision)) result.push(row);
            if (result.length === limit) resolve(result);
            else cursor.continue();
          };
        }),
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
    const anchor = after ? await this.bySequence(id, after[1]) : undefined;
    if (after && !anchor) throw new Error('Original cursor no longer exists');
    return revisionRead(id, 'samples', limit, (tx, count) =>
      tx
        .objectStore('samples')
        .index('byElapsed')
        .getAll(
          IDBKeyRange.bound(
            anchor
              ? [id, browserIndexKey(anchor.elapsedUs), anchor.producer, browserIndexKey(anchor.producerSequence)]
              : [id],
            [id, []],
            Boolean(after),
          ),
          count,
        ),
    );
  }
  async locationsAfter(
    id: string,
    after: OriginalCursor | null,
    limit: number,
  ): Promise<RevisionRead<RideLocationRow>> {
    return revisionRead(id, 'ride-locations', limit, (tx, count) =>
      tx
        .objectStore('ride-locations')
        .index('byElapsed')
        .getAll(
          IDBKeyRange.bound(
            after ? [id, browserIndexKey(after[0]), after[1], browserIndexKey(after[2])] : [id],
            [id, []],
            Boolean(after),
          ),
          count,
        ),
    );
  }
  async lifecycleAfter(id: string, after: number | null, limit: number): Promise<RevisionRead<RideLifecycleRow>> {
    return revisionRead(id, 'ride-lifecycle', limit, (tx, count) =>
      tx
        .objectStore('ride-lifecycle')
        .index('byRevision')
        .getAll(
          IDBKeyRange.bound(after === null ? [id] : [id, browserIndexKey(String(after)), []], [id, []], after !== null),
          count,
        ),
    );
  }
  async inspectionGroups(
    id: string,
    seconds: number,
    at: string,
    locations: boolean,
    metrics: string[],
    anchor?: string,
  ): Promise<(RideRow | RideLocationRow)[]> {
    const name = locations ? 'ride-locations' : 'samples';
    return browserTransaction([name], 'readonly', async tx => {
      const store = tx.objectStore(name),
        index = store.index('byElapsed');
      const selected = new Map<string, RideRow | RideLocationRow>();
      const read = async (direction: 'prev' | 'next', metric: string) => {
        const target = int64(secondsUs(seconds));
        const gap = int64(new InspectionMetricPolicy(metric).plotGapUs);
        const start = browserIndexKey((target - gap).toString());
        const end = browserIndexKey((target + gap).toString());
        let time = secondsUs(seconds),
          exclusive = false;
        while (true) {
          const range =
            direction === 'prev'
              ? browserKeyRange(
                  [id, start],
                  exclusive ? [id, browserIndexKey(time)] : [id, browserIndexKey(time), []],
                  false,
                  exclusive,
                )
              : browserKeyRange(
                  exclusive ? [id, browserIndexKey(time), []] : [id, browserIndexKey(time)],
                  [id, end, []],
                  exclusive,
                );
          if (!range) return;
          const cursor = await idbRequest(index.openCursor(range, direction));
          if (!cursor) return;
          const neighbour = cursor.value as RideRow | RideLocationRow;
          const group = (
            await idbRequest<(RideRow | RideLocationRow)[]>(
              index.getAll(
                IDBKeyRange.bound(
                  [id, browserIndexKey(neighbour.elapsedUs)],
                  [id, browserIndexKey(neighbour.elapsedUs), []],
                ),
              ),
            )
          ).filter(row => int64((row as unknown as IndexedOriginal<OriginalIdentity>).revision) <= int64(at));
          group.forEach(row => selected.set(row.eventId, row));
          if (group.some(row => new InspectionOriginalInput(browserInspectionItem(row)).plotValid(metric))) return;
          time = neighbour.elapsedUs;
          exclusive = true;
        }
      };
      await Promise.all(metrics.flatMap(metric => [read('prev', metric), read('next', metric)]));
      if (anchor) {
        const prefix = `${id}:`;
        const identity = anchor.startsWith(prefix) ? anchor.slice(prefix.length) : '';
        const row = await idbRequest<RideRow | RideLocationRow | undefined>(
          locations
            ? store.index('byEventId').get([id, identity])
            : store.index('byLocalSequence').get([id, Number(identity)]),
        );
        if (row && int64((row as unknown as IndexedOriginal<OriginalIdentity>).revision) <= int64(at))
          selected.set(row.eventId, row);
      }
      return [...selected.values()].sort(
        (a, b) =>
          Number(int64(a.elapsedUs) - int64(b.elapsedUs)) ||
          Number(int64(a.producerSequence) - int64(b.producerSequence)),
      );
    });
  }

  async locationNeighbor(
    id: string,
    seconds: number,
    direction: 'prev' | 'next',
    at?: string,
  ): Promise<RideLocationRow | undefined> {
    const row = await originalNeighbor<RideLocationRow>(
      'ride-locations',
      id,
      seconds === Infinity ? '9223372036854775807' : secondsUs(seconds),
      direction,
      at,
    );
    return row ? validateLocation(row) : undefined;
  }
  async neighborExact(id: string, us: string, direction: 'prev' | 'next', at?: string): Promise<RideRow | undefined> {
    return originalNeighbor<RideRow>('samples', id, us, direction, at);
  }
  async neighbor(id: string, seconds: number, direction: 'prev' | 'next', at?: string): Promise<RideRow | undefined> {
    return this.neighborExact(id, seconds === Infinity ? '9223372036854775807' : secondsUs(seconds), direction, at);
  }
  async bySequence(id: string, sequence: number, at?: string): Promise<RideRow | undefined> {
    return browserTransaction(['samples'], 'readonly', async tx => {
      const row = await idbRequest<IndexedOriginal<RideRow> | undefined>(
        tx.objectStore('samples').index('byLocalSequence').get([id, sequence]),
      );
      return row && (!at || int64(row.revision) <= int64(at)) ? row : undefined;
    });
  }

  async tiles(id: string, level: number, first: number, last: number, at?: string): Promise<RideTile[]> {
    if (last < first) return [];
    if (last - first > 2048) throw new Error('Chart tile request exceeds its bound');
    return browserTransaction(['ride-tiles'], 'readonly', async tx => {
      const rows = await idbRequest<(RideTile & { revision: string })[]>(
        tx
          .objectStore('ride-tiles')
          .getAll(
            IDBKeyRange.bound(
              [id, level, browserIndexKey(String(first))],
              [id, level, browserIndexKey(String(last)), []],
            ),
          ),
      );
      const latest = new Map<number, RideTile>();
      for (const row of rows) if (!at || int64(row.revision) <= int64(at)) latest.set(row.bucket, row);
      return [...latest.values()].map(row => ({
        ...row,
        stats: Object.fromEntries(
          Object.entries(row.stats).filter(([metric]) => MONITOR_METRICS.some(item => item.id === metric)),
        ),
      }));
    });
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
