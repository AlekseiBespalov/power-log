import {
  AnalyticsItem,
  AnalyticsMetadata,
  AnalyticsPin,
  AnalyticsPreparation,
  AnalyticsComposition,
  AnalyticsSource,
  InspectionMetricQuery,
  InspectionMetricCheckpoint,
  InspectionMetricPolicy,
  type AnalyticsPrepared,
  type AnalyticsDistanceProfile,
} from '../../shared/js';
import { inspectionStatistics } from '../core/monitor-inspection';
import { TABLES } from '../core/export/catalog';
import type { MonitorStatistics } from '../core/monitor';
import type { WorkoutSummary } from '../core/workouts';
import type { DistanceSource } from '../core/distance';
import {
  BROWSER_PAGE,
  TILE_SECONDS,
  browserRideStore,
  browserAnalysisItem,
  type BrowserRide,
  type BrowserRideStore,
  type OriginalCursor,
  type RideRow,
  type RideLocationRow,
  type RideLifecycleRow,
} from './browser-ride-store';
import { secondsUs, int64 } from './browser-storage-keys';

export const browserSources = [
  new AnalyticsSource('controller', 'controller', null, null),
  new AnalyticsSource('phone', 'browser', null, null),
];
type Row = RideRow | RideLocationRow | RideLifecycleRow;
export { browserAnalysisItem, browserInspectionItem } from './browser-ride-store';
export function browserPageReader(store: BrowserRideStore, id: string, revision: string) {
  const pages = new Map<string, RideRow[]>();
  let retainedRows = 0;
  return async (start: number, end: number, after?: [number, number]) => {
    const key = JSON.stringify([start, end, after]);
    const cached = pages.get(key);
    if (cached) return cached;
    const page = await store.page(id, Math.max(0, start), end, after, BROWSER_PAGE, revision);
    pages.set(key, page);
    retainedRows += page.length;
    while (retainedRows > 1024 || pages.size > 16) {
      const first = pages.keys().next().value!;
      retainedRows -= pages.get(first)!.length;
      pages.delete(first);
    }
    return page;
  };
}
export async function scanBrowserAnalysis(
  record: BrowserRide,
  store: BrowserRideStore,
  receive: (item: AnalyticsItem, row: Row) => void | Promise<void>,
  initial: Partial<Record<'samples' | 'ride-locations' | 'ride-lifecycle', OriginalCursor | false>> = {},
): Promise<void> {
  const streams = ['samples', 'ride-locations', 'ride-lifecycle'] as const;
  const cursors: (OriginalCursor | null)[] = streams.map(stream => initial[stream] || null);
  const buffers: Row[][] = [[], [], []];
  const ended = streams.map(stream => initial[stream] === false);
  while (true) {
    for (let i = 0; i < streams.length; i++) {
      if (buffers[i]!.length || ended[i]) continue;
      const rows = await store.analysisPage(streams[i]!, record.id, cursors[i]!, record.revision);
      buffers[i] = rows;
      ended[i] = rows.length < BROWSER_PAGE;
      const tail = rows.at(-1);
      if (tail) cursors[i] = [tail.elapsedUs, tail.producer, tail.producerSequence];
    }
    let selected = -1;
    for (let i = 0; i < buffers.length; i++) {
      const row = buffers[i]![0];
      if (!row) continue;
      if (selected < 0 || compare(row, buffers[selected]![0]!) < 0) selected = i;
    }
    if (selected < 0) return;
    const row = buffers[selected]!.shift()!;
    await receive(browserAnalysisItem(row), row);
  }
}
export async function scanBrowserLocations(
  record: BrowserRide,
  store: BrowserRideStore,
  start: number,
  end: number,
  receive: (item: AnalyticsItem, row: RideLocationRow) => void,
): Promise<void> {
  const startUs = secondsUs(start),
    endUs = secondsUs(end);
  if (int64(startUs) > int64(endUs)) return;
  const before = await store.locationNeighbor(record.id, start, 'prev', record.revision);
  if (before && int64(before.elapsedUs) < int64(startUs)) receive(browserAnalysisItem(before), before);
  let after: OriginalCursor | null = null;
  while (true) {
    const rows = await store.locationPage(record.id, startUs, endUs, after, record.revision);
    for (const row of rows) receive(browserAnalysisItem(row), row);
    if (rows.length < BROWSER_PAGE) break;
    const last = rows.at(-1)!;
    after = [last.elapsedUs, last.producer, last.producerSequence];
  }
  const next = await store.locationNeighbor(record.id, end, 'next', record.revision);
  if (next && int64(next.elapsedUs) > int64(endUs)) receive(browserAnalysisItem(next), next);
}
function compare(a: Row, b: Row): number {
  const time = int64(a.elapsedUs) - int64(b.elapsedUs);
  if (time !== 0n) return time < 0n ? -1 : 1;
  const rank = Number(!('action' in a)) - Number(!('action' in b));
  if (rank) return rank;
  if (a.producer !== b.producer) return a.producer < b.producer ? -1 : 1;
  const sequence = int64(a.producerSequence) - int64(b.producerSequence);
  if (sequence !== 0n) return sequence < 0n ? -1 : 1;
  return a.eventId < b.eventId ? -1 : Number(a.eventId !== b.eventId);
}
export async function prepareBrowserAnalysis(
  record: BrowserRide,
  store = browserRideStore,
  selection: DistanceSource = 'auto',
): Promise<AnalyticsPrepared> {
  const metadata = new AnalyticsMetadata(
    record.id,
    record.startedAt,
    record.elapsedSeconds,
    record.indoor,
    false,
    record.recordGPS,
    false,
    record.sampleHz,
    record.endedAt ?? null,
    record.endedAt ? record.elapsedSeconds : null,
    browserSources,
    JSON.stringify(record),
    record.phase,
    'notRequested',
    false,
  );
  const preparation = new AnalyticsPreparation(new AnalyticsPin(metadata, record.revision, selection));
  await scanBrowserAnalysis(record, store, item => preparation.append(item));
  return preparation.finish(selection, []);
}
export async function browserSummary(
  record: BrowserRide,
  selection: DistanceSource,
  profiles: AnalyticsDistanceProfile[],
  store = browserRideStore,
): Promise<WorkoutSummary> {
  const prepared = await prepareBrowserAnalysis(record, store, selection);
  const composer = new AnalyticsComposition(prepared, selection, profiles, []);
  await scanBrowserAnalysis(record, store, item => composer.append(item));
  const json = composer.summaryJson(String(record.lifecycleCount));
  if (json == null) throw new Error(composer.outcome());
  return JSON.parse(json) as WorkoutSummary;
}
export const distanceProfileInfo = (profile: AnalyticsDistanceProfile) => ({
  source: profile.source as Exclude<DistanceSource, 'auto'>,
  label: profile.label,
  estimated: profile.estimated,
  partial: profile.partial,
  coveredSeconds: profile.coveredSeconds,
  uncoveredSeconds: profile.uncoveredSeconds,
  distanceMeters: profile.distanceMeters,
});
export const distanceInfo = (value: {
  selection: string;
  selected?: AnalyticsDistanceProfile | null;
  available: AnalyticsDistanceProfile[];
}) => ({
  selection: value.selection as DistanceSource,
  selected: value.selected ? distanceProfileInfo(value.selected) : null,
  available: value.available.map(distanceProfileInfo),
});
export async function browserStatistics(
  record: BrowserRide,
  store: BrowserRideStore,
  start: number,
  end: number,
  metrics: string[],
): Promise<Record<string, MonitorStatistics>> {
  const statistics: Record<string, MonitorStatistics> = {};
  for (const metric of metrics) {
    if (record.availableMetrics.includes(metric)) continue;
    const policy = new InspectionMetricPolicy(metric);
    const present =
      (policy.channel === 'telemetry' && record.telemetryCount > 0) ||
      (policy.channel === 'location' && record.locationCount > 0);
    if (present) statistics[metric] = { count: 0, ...(policy.suppressed ? {} : { coveredSeconds: 0, integral: 0 }) };
  }
  metrics = metrics.filter(metric => record.availableMetrics.includes(metric));
  if (!metrics.length) return statistics;
  const lifecycle: AnalyticsItem[] = [];
  await scanBrowserAnalysis(
    record,
    store,
    item => {
      lifecycle.push(item);
    },
    { samples: false, 'ride-locations': false },
  );
  const reducers = Object.fromEntries(
    metrics.map(metric => [
      metric,
      new InspectionMetricQuery(
        metric,
        secondsUs(start),
        secondsUs(end),
        secondsUs(record.elapsedSeconds),
        lifecycle,
        null,
        null,
        false,
      ),
    ]),
  );
  const readPage = browserPageReader(store, record.id, record.revision);
  for (const metric of metrics) {
    const reducer = reducers[metric]!;
    if (browserLocationMetrics.has(metric)) {
      await scanBrowserLocations(record, store, start, end, item => {
        reducer.append(item, true);
      });
      continue;
    }
    const before = await store.neighbor(record.id, start, 'prev', record.revision);
    if (before && before.elapsedSeconds < start) reducer.append(browserAnalysisItem(before), true);
    const appendRaw = async (left: number, right: number) => {
      let after: [number, number] | undefined;
      while (true) {
        const rows = await readPage(Math.max(start, left), Math.min(end, right), after);
        for (const row of rows)
          if (row.elapsedSeconds < right || row.elapsedSeconds === end) reducer.append(browserAnalysisItem(row), true);
        if (rows.length < BROWSER_PAGE) return;
        const last = rows.at(-1)!;
        after = [last.elapsedSeconds, last.sequence];
      }
    };
    const descend = async (level: number, first: number, last: number): Promise<void> => {
      const size = TILE_SECONDS[level]!;
      const tiles = await store.tiles(record.id, level, first, last, record.revision);
      for (let bucket = first; bucket <= last; bucket++) {
        const left = bucket * size,
          right = left + size;
        const tile = tiles.find(tile => tile.bucket === bucket);
        if (left > start && right <= end && tile?.stats[metric]?.checkpoint) {
          let checkpoint: InspectionMetricCheckpoint | undefined;
          try {
            checkpoint = new InspectionMetricCheckpoint(tile.stats[metric]!.checkpoint!);
          } catch {
            checkpoint = undefined;
          }
          if (checkpoint && reducer.canMerge(checkpoint)) {
            reducer.mergeOwned(checkpoint);
            continue;
          }
        }
        if (level === 0) await appendRaw(left, right);
        else {
          const child = TILE_SECONDS[level - 1]!;
          await descend(
            level - 1,
            Math.floor(Math.max(start, left) / child),
            Math.min(Math.ceil(right / child) - 1, Math.floor(end / child)),
          );
        }
      }
    };
    const level = Math.max(
      0,
      TILE_SECONDS.findIndex(size => Math.ceil((end - start) / size) < 128),
    );
    await descend(level, Math.floor(start / TILE_SECONDS[level]!), Math.floor(end / TILE_SECONDS[level]!));
    const after = await store.neighbor(record.id, end, 'next', record.revision);
    if (after && after.elapsedSeconds > end) reducer.append(browserAnalysisItem(after), true);
  }
  for (const [metric, reducer] of Object.entries(reducers)) {
    const value = reducer.result();
    if (value.outcome.startsWith('numericOverflow')) throw new Error(value.outcome);
    if (value.statistics) statistics[metric] = inspectionStatistics(value.statistics);
  }
  return statistics;
}

export const browserLocationMetrics = new Set<string>(
  TABLES[1].fields
    .filter(field => field.type === 'number' && field.name !== 'elapsedSeconds' && !field.name.startsWith('original'))
    .flatMap(field => field.reads),
);

export async function prepareBrowserTimeline(
  record: BrowserRide,
  store: BrowserRideStore = browserRideStore,
): Promise<AnalyticsPrepared> {
  const metadata = new AnalyticsMetadata(
    record.id,
    record.startedAt,
    record.elapsedSeconds,
    record.indoor,
    false,
    record.recordGPS,
    false,
    record.sampleHz,
    record.endedAt ?? null,
    record.endedAt ? record.elapsedSeconds : null,
    browserSources,
    JSON.stringify(record),
    record.phase,
    'notRequested',
    false,
  );
  const preparation = new AnalyticsPreparation(new AnalyticsPin(metadata, record.revision, 'auto'));
  await scanBrowserAnalysis(record, store, item => preparation.append(item), {
    samples: false,
    'ride-locations': false,
  });
  return preparation.finish('auto', []);
}
