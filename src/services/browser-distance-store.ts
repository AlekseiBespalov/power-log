import {
  appendControllerDistance,
  clipControllerDistance,
  DISTANCE_SOURCE_LABELS,
  emptyControllerDistance,
  type ControllerDistanceInterval,
  type ControllerDistanceState,
  type DistanceSource,
  type DistanceProfileInfo,
  type WorkoutDistanceInfo,
} from '../core/distance';
import { isRecord, validateSample } from '../core/validation';
import type { MonitorPoint, MonitorStatistics } from '../core/monitor';
import { TELEMETRY_DISPLAY_HOLD_SECONDS } from '../core/telemetry-display';
import {
  BROWSER_PAGE,
  browserTransaction,
  idbRequest,
  normalize,
  TILE_SECONDS,
  type BrowserRide,
  type RideRow,
} from './browser-ride-store';

interface DistanceRow extends Omit<ControllerDistanceInterval, 'from' | 'to'> {
  recordingId: string;
  generation: string;
  sequence: number;
  total: number;
  covered: number;
  segment: number;
  plotSegment: number;
  timestamp: string;
  startTimestamp: string;
}
interface DistanceTile {
  recordingId: string;
  generation: string;
  level: number;
  bucket: number;
  first: DistanceRow;
  last: DistanceRow;
  gap: boolean;
}
interface Profile {
  recordingId: string;
  generation: string;
  inputSequence: number;
  state: ControllerDistanceState;
  segment: number;
  plotSegment: number;
  lastPlotEnd?: number;
  lastInterruptionIndex?: number;
  lastEnd?: number;
  previousTimestamp?: string;
  building: boolean;
  recordRevision: string;
  lifecycleRevision: number;
  lifecycleCount: number;
  activeIntervals: [number, number][];
  activeOpen: number | null;
}
type LifecycleRow = { recordingId: string; revision: number; action: string; elapsedSeconds: number };
export interface BrowserDistanceHandle {
  profile: Profile;
  record: BrowserRide;
  info: WorkoutDistanceInfo;
}
const stores = ['recordings', 'samples', 'ride-lifecycle', 'distance-profiles', 'distance-intervals', 'distance-tiles'];
const pending = new Map<string, Promise<BrowserDistanceHandle>>();
const yieldPage = () => new Promise<void>(resolve => setTimeout(resolve, 0));
const revision = (record: { revision: number }) => String(record.revision);
const scoped = (id: string) => IDBKeyRange.bound([id], [id, []]);
function validateProfile(profile: Profile): void {
  const nonnegative = (value: unknown): value is number =>
    typeof value === 'number' && Number.isFinite(value) && value >= 0;
  if (
    !isRecord(profile) ||
    typeof profile.generation !== 'string' ||
    !profile.generation ||
    !Number.isSafeInteger(profile.inputSequence) ||
    profile.inputSequence < -1 ||
    typeof profile.building !== 'boolean' ||
    typeof profile.recordRevision !== 'string' ||
    !/^\d+$/.test(profile.recordRevision) ||
    !Number.isSafeInteger(profile.segment) ||
    profile.segment < -1 ||
    !Number.isSafeInteger(profile.plotSegment) ||
    profile.plotSegment < 0 ||
    !Number.isSafeInteger(profile.lifecycleRevision) ||
    profile.lifecycleRevision < 0 ||
    !Number.isSafeInteger(profile.lifecycleCount) ||
    profile.lifecycleCount < 0 ||
    profile.lifecycleCount > 10_000 ||
    !isRecord(profile.state) ||
    !nonnegative(profile.state.distance) ||
    !nonnegative(profile.state.covered) ||
    !Number.isSafeInteger(profile.state.intervals) ||
    profile.state.intervals < 0 ||
    !Array.isArray(profile.activeIntervals) ||
    profile.activeIntervals.some(
      interval =>
        !Array.isArray(interval) ||
        interval.length !== 2 ||
        !nonnegative(interval[0]) ||
        !nonnegative(interval[1]) ||
        interval[1] <= interval[0],
    ) ||
    (profile.activeOpen !== null && !nonnegative(profile.activeOpen))
  )
    throw new Error('Invalid browser distance profile');
}
function information(profile: Profile, record: BrowserRide): WorkoutDistanceInfo {
  const uncovered = Math.max(0, record.timerSeconds - profile.state.covered);
  const selected: DistanceProfileInfo | undefined =
    profile.state.intervals > 0
      ? {
          source: 'controller',
          label: 'Controller estimate',
          estimated: true,
          distanceMeters: profile.state.distance,
          coveredSeconds: profile.state.covered,
          uncoveredSeconds: uncovered,
          partial: uncovered > 0.001,
        }
      : undefined;
  return { selection: 'auto', selected: selected ?? null, available: selected ? [selected] : [] };
}
/** Selection changes only this read handle; the profile and original ride stay reusable. */
function selectDistance(handle: BrowserDistanceHandle, selection: DistanceSource): BrowserDistanceHandle {
  if (!Object.hasOwn(DISTANCE_SOURCE_LABELS, selection)) throw new Error('Invalid distance source');
  const available = handle.info.available;
  return {
    ...handle,
    info: {
      selection,
      available,
      selected:
        selection === 'auto' ? (available[0] ?? null) : (available.find(source => source.source === selection) ?? null),
    },
  };
}
export function browserDistanceRevision(record: { revision: number }, selection: DistanceSource = 'auto'): string {
  if (!Object.hasOwn(DISTANCE_SOURCE_LABELS, selection)) throw new Error('Invalid distance source');
  return selection === 'auto' ? revision(record) : `distance:${selection}:${revision(record)}`;
}
/** Originals are read once into disposable, indexed intervals. Publication is guarded during each paged update. */
export function ensureBrowserDistance(id: string, selection: DistanceSource = 'auto'): Promise<BrowserDistanceHandle> {
  let work = pending.get(id);
  if (!work) {
    work = peekBrowserDistance(id)
      .then(cached => cached ?? build(id))
      .finally(() => {
        pending.delete(id);
      });
    pending.set(id, work);
  }
  return work.then(handle => selectDistance(handle, selection));
}
/** Catalog/description reads never decode originals or block on a backfill. */
export async function peekBrowserDistance(
  id: string,
  selection: DistanceSource = 'auto',
): Promise<BrowserDistanceHandle | undefined> {
  return browserTransaction(['recordings', 'distance-profiles'], 'readonly', async tx => {
    const [record, profile] = await Promise.all([
      idbRequest<BrowserRide | undefined>(tx.objectStore('recordings').get(id)),
      idbRequest<Profile | undefined>(tx.objectStore('distance-profiles').get(id)),
    ]);
    if (!record) throw new Error('Recording not found');
    normalize(record);
    if (profile) validateProfile(profile);
    if (
      !profile ||
      profile.building ||
      profile.inputSequence !== record.samples - 1 ||
      profile.recordRevision !== revision(record)
    )
      return;
    return selectDistance({ profile, record, info: information(profile, record) }, selection);
  });
}
async function build(id: string): Promise<BrowserDistanceHandle> {
  while (true) {
    const result = await browserTransaction(stores, 'readwrite', async tx => {
      const record = await idbRequest<BrowserRide | undefined>(tx.objectStore('recordings').get(id));
      if (!record) throw new Error('Recording not found');
      normalize(record);
      const sampleStore = tx.objectStore('samples'),
        profiles = tx.objectStore('distance-profiles');
      const sequence = record.samples - 1;
      let profile = await idbRequest<Profile | undefined>(profiles.get(id));
      if (profile) validateProfile(profile);
      if (!profile || profile.inputSequence > sequence) {
        await idbRequest(tx.objectStore('distance-intervals').delete(scoped(id)));
        await idbRequest(tx.objectStore('distance-tiles').delete(scoped(id)));
        profile = {
          recordingId: id,
          generation: crypto.randomUUID(),
          inputSequence: -1,
          state: emptyControllerDistance(),
          segment: -1,
          plotSegment: 0,
          building: true,
          recordRevision: revision(record),
          lifecycleRevision: 0,
          lifecycleCount: 0,
          activeIntervals: [],
          activeOpen: 0,
        };
      }
      const rows =
        profile.inputSequence < sequence
          ? await idbRequest<RideRow[]>(
              sampleStore.getAll(IDBKeyRange.bound([id, profile.inputSequence], [id, sequence], true), BROWSER_PAGE),
            )
          : [];
      if (profile.inputSequence < sequence && !rows.length) throw new Error('Missing recording observations');
      const lifecycle = await idbRequest<LifecycleRow[]>(
        tx
          .objectStore('ride-lifecycle')
          .getAll(IDBKeyRange.bound([id, profile.lifecycleRevision], [id, Infinity], true), BROWSER_PAGE),
      );
      for (const event of lifecycle) {
        profile.lifecycleCount = profile.lifecycleCount + 1;
        if (profile.lifecycleCount > 10_000) throw new Error('Too many ride lifecycle boundaries');
        if (event.action === 'start' || event.action === 'resume') profile.activeOpen = event.elapsedSeconds;
        else if (['pause', 'save', 'interrupted'].includes(event.action) && profile.activeOpen != null) {
          if (event.elapsedSeconds > profile.activeOpen)
            profile.activeIntervals.push([profile.activeOpen, event.elapsedSeconds]);
          profile.activeOpen = null;
        }
        profile.lifecycleRevision = event.revision;
      }
      const intervals: DistanceRow[] = [];
      for (const row of rows) {
        validateSample(row);
        if (
          row.sequence !== profile.inputSequence + 1 ||
          !row.connectionEpoch ||
          typeof row.active !== 'boolean' ||
          !Number.isSafeInteger(row.interval) ||
          row.interval < 0
        )
          throw new Error('Invalid distance observation continuity');
        const interval = appendControllerDistance(profile.state, {
          id: `${id}:${row.sequence}`,
          time: row.elapsedSeconds,
          speed: row.controllerSpeedMps,
          model: row.controllerModel,
          protocol: row.controllerProtocol,
          identity: row.connectionEpoch,
          continuity: row.interval,
          connectionEpoch: row.connectionEpoch,
          active: row.active,
        });
        if (interval) {
          if (profile.lastEnd !== interval.start) profile.segment += 1;
          if (
            profile.lastPlotEnd !== undefined &&
            (interval.start - profile.lastPlotEnd >= TELEMETRY_DISPLAY_HOLD_SECONDS ||
              profile.lastInterruptionIndex !== row.interruptionIndex)
          )
            profile.plotSegment += 1;
          intervals.push({
            start: interval.start,
            end: interval.end,
            startSpeed: interval.startSpeed,
            endSpeed: interval.endSpeed,
            distance: interval.distance,
            recordingId: id,
            generation: profile.generation,
            sequence: row.sequence,
            total: profile.state.distance,
            covered: profile.state.covered,
            segment: profile.segment,
            plotSegment: profile.plotSegment,
            timestamp: row.timestamp,
            startTimestamp: profile.previousTimestamp ?? row.timestamp,
          });
          profile.lastEnd = interval.end;
          profile.lastPlotEnd = interval.end;
          profile.lastInterruptionIndex = row.interruptionIndex;
        } else {
          profile.lastEnd = undefined;
        }
        profile.previousTimestamp = row.timestamp;
        profile.inputSequence = row.sequence;
      }
      await project(tx, intervals);
      profile.building = profile.inputSequence < sequence || lifecycle.length === BROWSER_PAGE;
      profile.recordRevision = revision(record);
      await idbRequest(profiles.put(profile));
      return { done: !profile.building, handle: { profile, record, info: information(profile, record) } };
    });
    if (result.done) return result.handle;
    await yieldPage();
  }
}
async function project(tx: IDBTransaction, rows: DistanceRow[]) {
  const values = tx.objectStore('distance-intervals'),
    tiles = tx.objectStore('distance-tiles');
  const touched = new Map<string, [string, string, number, number]>(),
    loaded = new Map<string, DistanceTile>();
  for (const row of rows)
    for (let level = 0; level < TILE_SECONDS.length; level++) {
      const key: [string, string, number, number] = [
        row.recordingId,
        row.generation,
        level,
        Math.floor(row.end / TILE_SECONDS[level]!),
      ];
      touched.set(JSON.stringify(key), key);
    }
  await Promise.all(
    [...touched].map(async ([key, value]) => {
      const tile = await idbRequest<DistanceTile | undefined>(tiles.get(value));
      if (tile) loaded.set(key, tile);
    }),
  );
  for (const row of rows) {
    await idbRequest(values.put(row));
    for (let level = 0; level < TILE_SECONDS.length; level++) {
      const bucket = Math.floor(row.end / TILE_SECONDS[level]!),
        key = JSON.stringify([row.recordingId, row.generation, level, bucket]),
        old = loaded.get(key);
      loaded.set(
        key,
        old
          ? { ...old, last: row, gap: old.gap || old.last.segment !== row.segment }
          : {
              recordingId: row.recordingId,
              generation: row.generation,
              level,
              bucket,
              first: row,
              last: row,
              gap: false,
            },
      );
    }
  }
  await Promise.all([...loaded.values()].map(tile => idbRequest(tiles.put(tile))));
}
export async function browserDistanceCurrent(handle: BrowserDistanceHandle): Promise<boolean> {
  return browserTransaction(['recordings', 'distance-profiles'], 'readonly', async tx => {
    const [record, profile] = await Promise.all([
      idbRequest<BrowserRide | undefined>(tx.objectStore('recordings').get(handle.record.id)),
      idbRequest<Profile | undefined>(tx.objectStore('distance-profiles').get(handle.record.id)),
    ]);
    return Boolean(
      record &&
      profile &&
      !profile.building &&
      revision(record) === revision(handle.record) &&
      profile.generation === handle.profile.generation &&
      profile.inputSequence === handle.profile.inputSequence,
    );
  });
}
function point(row: DistanceRow, edge: 'start' | 'end', startsSegment = false): MonitorPoint {
  return {
    value: edge === 'end' ? row.total : row.total - row.distance,
    elapsedSeconds: row[edge],
    timestamp: edge === 'end' ? row.timestamp : row.startTimestamp,
    observationId: `${row.recordingId}:distance:${row.generation}:${row.sequence}:${edge}`,
    startsSegment,
    derived: true,
  };
}
async function neighbor(
  handle: BrowserDistanceHandle,
  time: number,
  direction: 'prev' | 'next',
  strict = false,
): Promise<DistanceRow | undefined> {
  const prefix = [handle.record.id, handle.profile.generation];
  const range =
    direction === 'prev'
      ? IDBKeyRange.bound(prefix, [...prefix, time, Number.MAX_SAFE_INTEGER])
      : IDBKeyRange.bound([...prefix, time, strict ? Number.MAX_SAFE_INTEGER : -1], [...prefix, Infinity]);
  return browserTransaction(
    ['distance-intervals'],
    'readonly',
    async tx =>
      (await idbRequest(tx.objectStore('distance-intervals').openCursor(range, direction)))?.value as
        DistanceRow | undefined,
  );
}
export async function browserDistanceLatest(handle: BrowserDistanceHandle): Promise<MonitorPoint | null> {
  if (!handle.info.selected) return null;
  const row = await neighbor(handle, Infinity, 'prev');
  return row ? point(row, 'end') : null;
}
export async function inspectBrowserDistance(
  handle: BrowserDistanceHandle,
  time: number,
  anchor?: string,
): Promise<MonitorPoint | null> {
  if (!handle.info.selected) return null;
  const [before, after, following] = await Promise.all([
    neighbor(handle, time, 'prev'),
    neighbor(handle, time, 'next'),
    anchor ? neighbor(handle, time, 'next', true) : undefined,
  ]);
  const candidates = [before, after, following].filter((row): row is DistanceRow => Boolean(row));
  const points = candidates.flatMap(row => [point(row, 'start'), point(row, 'end')]);
  if (anchor) return points.find(p => p.observationId === anchor && Math.abs(p.elapsedSeconds - time) <= 1e-6) ?? null;
  const exact = points.find(p => Math.abs(p.elapsedSeconds - time) <= 1e-6);
  if (exact) return exact;
  const interval = candidates.find(row => row.start <= time && row.end >= time);
  if (!interval) return null;
  // Inspection names a contributing boundary observation, not an interpolated original.
  return point(interval, time - interval.start <= interval.end - time ? 'start' : 'end');
}
async function prefix(handle: BrowserDistanceHandle, time: number) {
  const [before, after] = await Promise.all([neighbor(handle, time, 'prev'), neighbor(handle, time, 'next', true)]);
  let distance = before?.total ?? 0,
    covered = before?.covered ?? 0;
  if (after && after.start < time) {
    const clipped = clipControllerDistance(after, after.start, time);
    distance = after.total - after.distance + clipped.distance;
    covered = after.covered - (after.end - after.start) + clipped.covered;
  }
  return { distance, covered };
}
export async function browserDistanceStats(
  handle: BrowserDistanceHandle,
  start: number,
  end: number,
): Promise<MonitorStatistics | undefined> {
  if (!handle.info.selected) return;
  const [a, b, first] = await Promise.all([
    prefix(handle, start),
    prefix(handle, end),
    neighbor(handle, start, 'next', true),
  ]);
  if (!first || (first.start >= end && first.end > end)) return;
  const covered = Math.max(0, b.covered - a.covered);
  if (covered <= 0) return;
  const active = [
    ...handle.profile.activeIntervals,
    ...(handle.profile.activeOpen == null ? [] : [[handle.profile.activeOpen, handle.record.elapsedSeconds]]),
  ];
  const activeSeconds = active.reduce((sum, [a, b]) => sum + Math.max(0, Math.min(end, b!) - Math.max(start, a!)), 0);
  return {
    distance: Math.max(0, b.distance - a.distance),
    coveredSeconds: covered,
    unresolvedBoundary: false,
    partial: covered + 0.001 < activeSeconds,
  };
}
export async function plotBrowserDistance(
  handle: BrowserDistanceHandle,
  start: number,
  end: number,
  budget: number,
): Promise<MonitorPoint[]> {
  if (!handle.info.selected) return [];
  const prefixKey = [handle.record.id, handle.profile.generation];
  // Narrow windows keep all derived boundaries up to a fixed output bound.
  if (end - start <= 128) {
    const rows = await browserTransaction(['distance-intervals'], 'readonly', tx =>
      idbRequest<DistanceRow[]>(
        tx
          .objectStore('distance-intervals')
          .getAll(
            IDBKeyRange.bound([...prefixKey, start], [...prefixKey, end, Number.MAX_SAFE_INTEGER]),
            Math.min(1024, budget * 2) + 1,
          ),
      ),
    );
    if (rows.length <= Math.min(1024, budget * 2)) {
      const crossing = await neighbor(handle, end, 'next', true);
      if (crossing && crossing.start < end && rows.at(-1)?.sequence !== crossing.sequence) rows.push(crossing);
      const points: MonitorPoint[] = [];
      let segment = -1;
      let plotSegment = -1;
      for (const row of rows) {
        if (segment !== row.segment) points.push(point(row, 'start', plotSegment !== row.plotSegment));
        points.push(point(row, 'end', segment === row.segment && plotSegment !== row.plotSegment));
        segment = row.segment;
        plotSegment = row.plotSegment;
      }
      return points;
    }
  }
  const candidate = TILE_SECONDS.findIndex(size => (end - start) / size <= budget / 4),
    level = candidate < 0 ? TILE_SECONDS.length - 1 : candidate;
  const size = TILE_SECONDS[level]!,
    first = Math.floor(start / size),
    last = Math.floor(end / size);
  if (last - first > 2048) throw new Error('Distance chart tile request exceeds its bound');
  const tiles = await browserTransaction(['distance-tiles'], 'readonly', tx =>
    idbRequest<DistanceTile[]>(
      tx
        .objectStore('distance-tiles')
        .getAll(IDBKeyRange.bound([...prefixKey, level, first], [...prefixKey, level, last]), 2049),
    ),
  );
  const [left, rightBefore, rightAfter] = await Promise.all([
    neighbor(handle, start, 'next'),
    neighbor(handle, end, 'prev'),
    neighbor(handle, end, 'next', true),
  ]);
  const rows = new Map<number, DistanceRow>();
  for (const row of [left, rightBefore, rightAfter])
    if (row && row.end >= start && row.start <= end) rows.set(row.sequence, row);
  for (const tile of tiles) {
    for (const row of [tile.first, tile.last]) if (row.end >= start && row.start <= end) rows.set(row.sequence, row);
  }
  const points: MonitorPoint[] = [];
  let segment = -1;
  let plotSegment = -1;
  for (const row of [...rows.values()].sort((a, b) => a.end - b.end || a.sequence - b.sequence)) {
    if (segment !== row.segment) points.push(point(row, 'start', plotSegment !== row.plotSegment));
    points.push(point(row, 'end', segment === row.segment && plotSegment !== row.plotSegment));
    segment = row.segment;
    plotSegment = row.plotSegment;
  }
  return points;
}
