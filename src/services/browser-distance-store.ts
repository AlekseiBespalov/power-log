import {
  AnalyticsEnergyPerDistance,
  type AnalyticsEnergyPerDistanceCheckpoint,
  AnalyticsEnergyPerDistanceSeries,
  analyticsEnergyCheckpointMarginUs,
  analyticsEnergyContextSeconds,
  AnalyticsConsumptionKernel,
  AnalyticsConsumptionInterval,
  AnalyticsControllerKernel,
  AnalyticsGPSKernel,
  AnalyticsDistanceReducer,
  AnalyticsItem,
  AnalyticsDistanceProfile,
  analyticsStoredInterval,
  analyticsDistanceSelection,
  analyticsProfileTotals,
  analyticsDivide,
  InspectionDistanceBoundary,
  InspectionTargetInput,
  inspectionDistanceOriginal,
  InspectionPlotBucket,
  InspectionPlotCache,
  InspectionPlotQuery,
  InspectionPlotSlotSummary,
  type AnalyticsInterval,
} from '../../shared/js';
import { monitorInspectionItem, inspectionPoint } from '../core/monitor-inspection';
import { type DistanceSource, type WorkoutDistanceInfo } from '../core/distance';
import { browserIndexKey, browserKeyRange, int64, secondsKey, secondsUs } from './browser-storage-keys';
import type { MonitorPoint, MonitorStatistics } from '../core/monitor';
import { TELEMETRY_DISPLAY_HOLD_SECONDS } from '../core/telemetry-display';
import { isChartCapacity, optionalChartCache } from '../core/failures';
import {
  BROWSER_PAGE,
  browserTransaction,
  idbRequest,
  browserRideStore,
  normalize,
  PLOT_SUMMARY_POINTS,
  TILE_SECONDS,
  tileLevelWithin,
  type BrowserRide,
  type RideRow,
  type RideLocationRow,
  type OriginalCursor,
} from './browser-ride-store';
import {
  browserSources,
  prepareBrowserTimeline,
  scanBrowserAnalysis,
  browserAnalysisItem,
  distanceInfo,
} from './browser-analytics';

// One disposable cumulative projection index serves distance and battery quantities.
import {
  CONSUMPTION_METRICS,
  isConsumptionMetric,
  consumptionMetrics,
  type ConsumptionMetric,
  type BatteryConsumptionSummary,
} from '../core/consumption';
import { distanceMetricSource } from '../core/distance';
import type { EnergyPerDistanceResult } from '../core/energy-per-distance';
const PROFILE_SOURCES = ['controller', 'gps:phone', ...CONSUMPTION_METRICS] as const;
type ProfileSource = (typeof PROFILE_SOURCES)[number];
type ConsumptionIntervalData = {
  metric: string;
  startUs: string;
  endUs: string;
  startValue: number;
  endValue: number;
  rate0: number | null;
  rate1: number | null;
  indivisible: boolean;
  segment: number;
  activeInterval: number;
  baseline: boolean;
};
const consumptionInterval = (v: ConsumptionIntervalData) =>
  new AnalyticsConsumptionInterval(
    v.metric,
    v.startUs,
    v.endUs,
    v.startValue,
    v.endValue,
    v.rate0,
    v.rate1,
    v.indivisible,
    v.segment,
    v.activeInterval,
    v.baseline,
  );
interface DistanceRow {
  consumption?: ConsumptionIntervalData;
  recordingId: string;
  generation: string;
  source: ProfileSource;
  generationKey: string;
  revisionKey: string;
  sequence: number;
  total: number;
  covered: number;
  segment: number;
  plotSegment: number;
  timestamp: string;
  startTimestamp: string;
  startReference: string;
  startObservationId: string;
  producer: 'controller' | 'browser';
  producerSequence: string;
  sequenceKey: string;
  start: number;
  end: number;
  startUs: string;
  endUs: string;
  endKey: string;
  distance: number;
  distanceQ: string;
  startSpeed: number | null;
  endSpeed: number | null;
  speed0Q: string | null;
  speed1Q: string | null;
  activeInterval: number;
  indivisible: boolean;
  from: string;
  to: string;
}
interface DistanceTile {
  recordingId: string;
  generation: string;
  source: ProfileSource;
  generationKey: string;
  revisionKey: string;
  level: number;
  bucket: number;
  bucketKey: string;
  plot?: string;
}
interface DistancePrefix {
  generation: string;
  revisionKey: string;
  endUs: string;
  inputSequence: number;
}
interface DistanceSpan extends DistancePrefix {
  startUs: string;
  afterSequence: number;
}
interface Profile {
  recordingId: string;
  generation: string;
  source: ProfileSource;
  generationKey: string;
  revisionKey: string;
  inputSequence: number;
  intervalEndUs: string;
  prefixes: DistancePrefix[];
  state: { intervals: number };
  consumptionCheckpoint?: string;
  checkpoint?: { previous: RideRow | null; segment: number };
  gpsCheckpoint?: { previous: RideLocationRow | null; geometryPrevious: RideLocationRow | null; segment: number };
  cursor?: OriginalCursor;
  telemetryCount: number;
  locationCount: number;
  lifecycleCount: number;
  building: boolean;
  recordRevision: string;
  coveredUs: string;
  distanceQ: string;
  timerUs: string;
}
export interface BrowserDistanceHandle {
  profile: Profile;
  record: BrowserRide;
  info: WorkoutDistanceInfo;
  profiles: AnalyticsDistanceProfile[];
}
const pending = new Map<string, Promise<BrowserDistanceHandle>>();
async function generationAfter(): Promise<string> {
  return browserTransaction(['ride-control'], 'readwrite', async tx => {
    const store = tx.objectStore('ride-control');
    const previous = await idbRequest<{ key: string; value: string } | undefined>(store.get('distance-generation'));
    if (previous && (previous.key !== 'distance-generation' || int64(previous.value) < 0n))
      throw new Error('Invalid distance generation counter');
    const generation = (int64(previous?.value ?? '0') + 1n).toString();
    browserIndexKey(generation);
    await idbRequest(store.put({ key: 'distance-generation', value: generation }));
    return generation;
  });
}
const revision = (record: { revision: string }) => record.revision;
async function profileIn(
  tx: IDBTransaction,
  id: string,
  source: ProfileSource,
  at?: string,
): Promise<Profile | undefined> {
  return new Promise((resolve, reject) => {
    let best: Profile | undefined;
    const request = tx
      .objectStore('distance-profiles')
      .openCursor(IDBKeyRange.bound([id, source], [id, source, []]), 'prev');
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return resolve(best);
      const profile = cursor.value as Profile;
      if (
        (!at || int64(profile.recordRevision) <= int64(at)) &&
        (!best || int64(profile.recordRevision) > int64(best.recordRevision))
      )
        best = profile;
      cursor.continue();
    };
  });
}
function profileSpans(profile: Profile): DistanceSpan[] {
  const prefixes = [...profile.prefixes];
  if (profile.state.intervals && profile.inputSequence > (prefixes.at(-1)?.inputSequence ?? -1))
    prefixes.push({
      generation: profile.generation,
      revisionKey: profile.revisionKey,
      endUs: profile.intervalEndUs,
      inputSequence: profile.inputSequence,
    });
  return prefixes.map((prefix, index) => ({
    ...prefix,
    startUs: prefixes[index - 1]?.endUs ?? '0',
    afterSequence: prefixes[index - 1]?.inputSequence ?? -1,
  }));
}
const spanOwns = (span: DistanceSpan, row: DistanceRow) =>
  row.sequence > span.afterSequence && row.sequence <= span.inputSequence && row.revisionKey <= span.revisionKey;
const spanKey = (profile: Profile, span: DistanceSpan) => [
  profile.recordingId,
  profile.source,
  browserIndexKey(span.generation),
];
const builderLock = (id: string) => `power-log-distance-build:${id}`;
const generationLock = (id: string, generation: string) => `power-log-distance:${id}:${generation}`;
async function holdGeneration(id: string, generation: string): Promise<() => Promise<void>> {
  return new Promise((resolve, reject) => {
    const held = navigator.locks.request(
      generationLock(id, generation),
      { mode: 'shared' },
      () =>
        new Promise<void>(release =>
          resolve(async () => {
            release();
            await held;
          }),
        ),
    );
    void held.catch(reject);
  });
}
async function reclaim(id: string): Promise<void> {
  const readProfiles = (tx: IDBTransaction) =>
    idbRequest<Profile[]>(tx.objectStore('distance-profiles').getAll(IDBKeyRange.bound([id], [id, []])));
  const referenced = (profile: Profile) => [profile.generation, ...profile.prefixes.map(prefix => prefix.generation)];
  const profiles = await browserTransaction(['distance-profiles'], 'readonly', readProfiles);
  const latest = new Map<ProfileSource, Profile>();
  for (const profile of profiles) {
    const previous = latest.get(profile.source);
    if (!previous || previous.revisionKey <= profile.revisionKey) latest.set(profile.source, profile);
  }
  const current = new Set([...latest.values()].map(profile => profile.generation));
  for (const generation of new Set(profiles.map(profile => profile.generation))) {
    if (current.has(generation)) continue;
    await navigator.locks.request(
      generationLock(id, generation),
      { mode: 'exclusive', ifAvailable: true },
      async lock => {
        if (!lock) return;
        await browserTransaction(
          ['distance-profiles', 'distance-intervals', 'distance-tiles'],
          'readwrite',
          async tx => {
            const retained = await readProfiles(tx);
            const live = new Set(retained.filter(profile => profile.generation !== generation).flatMap(referenced));
            const obsolete = new Set(
              retained
                .filter(profile => profile.generation === generation)
                .flatMap(referenced)
                .filter(value => !live.has(value)),
            );
            for (const source of PROFILE_SOURCES) {
              const prefix = [id, source, browserIndexKey(generation)];
              await idbRequest(tx.objectStore('distance-profiles').delete(IDBKeyRange.bound(prefix, [...prefix, []])));
            }
            for (const obsoleteGeneration of obsolete)
              for (const source of PROFILE_SOURCES) {
                const prefix = [id, source, browserIndexKey(obsoleteGeneration)];
                for (const name of ['distance-intervals', 'distance-tiles'])
                  await idbRequest(tx.objectStore(name).delete(IDBKeyRange.bound(prefix, [...prefix, []])));
              }
          },
        );
      },
    );
  }
}
export async function retainBrowserDistance(handle: BrowserDistanceHandle): Promise<() => Promise<void>> {
  const release = await holdGeneration(handle.record.id, handle.profile.generation);
  return async () => {
    await release();
    await navigator.locks.request(builderLock(handle.record.id), () => reclaim(handle.record.id));
  };
}
function validateProfile(profile: Profile): void {
  if (
    !profile.generation ||
    !Array.isArray(profile.prefixes) ||
    !/^(0|[1-9]\d*)$/.test(profile.intervalEndUs) ||
    profile.prefixes.some(
      prefix =>
        !/^[1-9]\d*$/.test(prefix.generation) ||
        !/^(0|[1-9]\d*)$/.test(prefix.endUs) ||
        !Number.isSafeInteger(prefix.inputSequence) ||
        prefix.inputSequence < 0 ||
        !/^\d{20}$/.test(prefix.revisionKey),
    ) ||
    !Number.isSafeInteger(profile.inputSequence) ||
    profile.inputSequence < -1 ||
    !/^(0|[1-9]\d*)$/.test(profile.distanceQ) ||
    !/^(0|[1-9]\d*)$/.test(profile.coveredUs) ||
    !/^(0|[1-9]\d*)$/.test(profile.timerUs) ||
    !Number.isSafeInteger(profile.state.intervals) ||
    profile.state.intervals < 0
  )
    throw new Error('Invalid browser distance profile');
}
function coreProfile(profile: Profile): AnalyticsDistanceProfile | undefined {
  if (profile.state.intervals === 0 || isConsumptionMetric(profile.source)) return;
  return (
    analyticsProfileTotals(profile.source, profile.distanceQ, profile.coveredUs, profile.timerUs, browserSources) ??
    undefined
  );
}
function selectDistance(handle: BrowserDistanceHandle, _selection: DistanceSource): BrowserDistanceHandle {
  const info = distanceInfo(
    analyticsDistanceSelection(handle.record.distanceSource, handle.profiles, handle.record.indoor),
  );
  const selected = {
    ...handle,
    info,
    profile: info.selected
      ? (handleProfiles.get(handle)?.get(info.selected.source as ProfileSource) ?? handle.profile)
      : handle.profile,
  };
  const profiles = handleProfiles.get(handle);
  if (profiles) handleProfiles.set(selected, profiles);
  return selected;
}
const handleProfiles = new WeakMap<BrowserDistanceHandle, Map<ProfileSource, Profile>>();
export function browserDerivedHandle(handle: BrowserDistanceHandle, metric: string): BrowserDistanceHandle {
  const source = isConsumptionMetric(metric) ? metric : distanceMetricSource(metric, handle.record.distanceSource);
  const profile = handleProfiles.get(handle)?.get(source as ProfileSource);
  if (!profile) throw new Error(`Unknown derived metric ${metric}`);
  const selected = { ...handle, profile };
  handleProfiles.set(selected, handleProfiles.get(handle)!);
  return selected;
}
export function browserDerivedSources(
  handle: BrowserDistanceHandle,
): Record<string, import('../core/distance').MetricSourceInfo> {
  const result: Record<string, import('../core/distance').MetricSourceInfo> = {};
  if (handle.info.selected) result.distanceMeters = handle.info.selected;
  for (const profile of handle.info.available) {
    const metric = profile.source === 'controller' ? 'controllerDistanceMeters' : 'gpsDistanceMeters';
    result[metric] = profile;
  }
  for (const metric of CONSUMPTION_METRICS) {
    const profile = browserDerivedHandle(handle, metric).profile;
    if (profile.state.intervals === 0) continue;
    result[metric] = {
      source: metric.startsWith('controller') ? 'controller' : 'calculated',
      label: metric.startsWith('controller') ? 'Controller counter changes' : 'Calculated from battery readings',
      estimated: metric.startsWith('calculated'),
      coveredSeconds: Number(profile.coveredUs) / 1e6,
      partial: BigInt(profile.timerUs) - BigInt(profile.coveredUs) > 1000n,
    };
  }
  return result;
}
export async function browserConsumptionSummary(handle: BrowserDistanceHandle): Promise<BatteryConsumptionSummary> {
  const source = handle.record.batteryConsumptionSource;
  const [energy, charge] = await Promise.all(
    consumptionMetrics(source).map(metric => browserDistanceLatest(browserDerivedHandle(handle, metric))),
  );
  const sources = browserDerivedSources(handle);
  return {
    source,
    energyWh: energy?.value ?? null,
    chargeAh: charge?.value ?? null,
    partial: consumptionMetrics(source).some(metric => sources[metric]?.partial ?? true),
  };
}

type EnergyCheckpoint = {
  key: string;
  checkpoint: AnalyticsEnergyPerDistanceCheckpoint;
  cutUs: bigint;
  revision: string;
  profiles: Profile[];
};
const energyCheckpoints = new Map<string, EnergyCheckpoint>();
const energyMarginUs = BigInt(analyticsEnergyCheckpointMarginUs());
async function unownedBetween(profile: Profile, fromUs: bigint, toUs: bigint): Promise<boolean> {
  const own = profileSpans(profile).find(span => span.generation === profile.generation);
  if (!own || fromUs >= toUs) return false;
  const prefix = spanKey(profile, own);
  let after: DistanceRow | undefined;
  while (true) {
    const range = browserKeyRange(
      after
        ? [...prefix, after.endKey, after.producer, after.sequenceKey, after.revisionKey]
        : [...prefix, browserIndexKey(fromUs.toString())],
      [...prefix, browserIndexKey(toUs.toString())],
      Boolean(after),
      true,
    );
    if (!range) return false;
    const rows = await browserTransaction(['distance-intervals'], 'readonly', tx =>
      idbRequest<DistanceRow[]>(tx.objectStore('distance-intervals').getAll(range, BROWSER_PAGE)),
    );
    if (rows.some(row => row.revisionKey <= own.revisionKey && row.sequence > own.inputSequence)) return true;
    if (rows.length < BROWSER_PAGE) return false;
    after = rows.at(-1);
  }
}
async function appendedSince(record: BrowserRide, revision: string, cutUs: bigint): Promise<boolean> {
  if (int64(record.revision) < int64(revision)) return false;
  if (record.revision === revision) return true;
  for (const name of record.distanceSource === 'controller'
    ? ['samples' as const]
    : (['samples', 'ride-locations'] as const)) {
    let after: OriginalCursor | null = null;
    while (true) {
      const rows: ((RideRow | RideLocationRow) & { revision: string })[] = await browserRideStore.originalsSince(
        name,
        record.id,
        revision,
        record.revision,
        after,
      );
      if (rows.some(row => BigInt(row.elapsedUs) < cutUs)) return false;
      if (rows.length < BROWSER_PAGE) break;
      const tail = rows.at(-1)!;
      after = [tail.revision, tail.producer, tail.producerSequence];
    }
  }
  let after = Number(revision);
  while (true) {
    const page = await browserRideStore.lifecycleAfter(record.id, after, BROWSER_PAGE);
    if (page.rows.some(row => int64(row.revision) <= int64(record.revision) && BigInt(row.elapsedUs) < cutUs))
      return false;
    if (page.rows.length < BROWSER_PAGE) return true;
    after = Number(page.rows.at(-1)!.revision);
  }
}
async function energyResumes(
  state: EnergyCheckpoint,
  record: BrowserRide,
  profiles: Profile[],
  endUs: string,
): Promise<boolean> {
  if (
    int64(record.revision) < int64(state.revision) ||
    BigInt(endUs) - BigInt(state.checkpoint.throughUs) <= energyMarginUs
  )
    return false;
  for (const [index, profile] of profiles.entries()) {
    const previous = state.profiles[index]!;
    if (profile.revisionKey < previous.revisionKey || profile.inputSequence < previous.inputSequence) return false;
    if ((await generationRowsSince(profile, previous.revisionKey)).some(row => BigInt(row.endUs) < state.cutUs))
      return false;
  }
  return appendedSince(record, state.revision, state.cutUs);
}
type EnergySink = Pick<AnalyticsEnergyPerDistance, 'distance' | 'battery' | 'speed' | 'telemetry'>;
type EnergySeries = { key: string; series: AnalyticsEnergyPerDistanceSeries; cutUs: bigint; revision: string };
const energySeries = new Map<string, EnergySeries | null>();
let energySeriesWork: Promise<unknown> = Promise.resolve();
export async function browserEnergyPerDistance(
  record: BrowserRide,
  handle: BrowserDistanceHandle | null,
  start: number,
  end: number,
  buckets: number | null,
  smoothingSeconds = 0,
  whole?: { slotUs: string | null; incremental: boolean },
): Promise<EnergyPerDistanceResult> {
  const timeline = await prepareBrowserTimeline(record);
  const startUs = secondsUs(start),
    endUs = secondsUs(end),
    timerUs = timeline.rangeTimerUs(startUs, endUs),
    cutUs = BigInt(endUs) - energyMarginUs;
  const from = Math.max(0, start - analyticsEnergyContextSeconds(smoothingSeconds)),
    until = end + 10;
  const controllerSpeed = record.distanceSource === 'controller';
  const battery = `${record.batteryConsumptionSource}EnergyWh`;
  const profiles = handle
    ? [
        ...(handle.info.selected ? [browserDerivedHandle(handle, 'distanceMeters').profile] : []),
        browserDerivedHandle(handle, battery).profile,
      ]
    : [];
  const slot = `${record.id}:${smoothingSeconds}`;
  const key =
    buckets === null && handle && start === 0
      ? JSON.stringify([
          record.distanceSource,
          smoothingSeconds,
          profiles.map(p => [p.source, p.generation, p.prefixes]),
        ])
      : undefined;
  const release = handle ? await retainBrowserDistance(handle) : async () => {};
  type Input = { end: number; timeUs: bigint; append: (sink: EnergySink) => void };
  const feed = async (sink: EnergySink, resumedCutUs: bigint | undefined, throughUs: bigint, cut: () => void) => {
    const fromUs = resumedCutUs === undefined ? secondsUs(from) : resumedCutUs.toString();
    async function* intervals(metric: string, battery: boolean): AsyncGenerator<Input> {
      if (!handle || (!battery && !handle.info.selected)) return;
      const selected = browserDerivedHandle(handle, metric);
      let after: readonly [number, number] = [
        resumedCutUs === undefined ? Number(secondsUs(from)) : Math.max(0, Number(resumedCutUs) - 1),
        0,
      ];
      while (true) {
        const rows = await browserDistanceIntervals(selected, after, BROWSER_PAGE);
        if (!rows) throw new Error('Energy/distance pin changed');
        for (const interval of rows) {
          const row = interval as DistanceRow;
          if (row.end > until) return;
          if (row.end <= row.start || (resumedCutUs !== undefined && BigInt(row.endUs) < resumedCutUs)) continue;
          yield {
            end: row.end,
            timeUs: BigInt(row.endUs),
            append: sink => {
              if (battery && row.consumption) {
                const c = row.consumption;
                sink.battery(
                  c.startUs,
                  c.endUs,
                  c.endValue - c.startValue,
                  c.rate0,
                  c.rate1,
                  c.indivisible,
                  row.timestamp,
                );
              } else if (!battery) {
                sink.distance(row.startUs, row.endUs, row.distance, row.startSpeed, row.endSpeed);
              }
            },
          };
        }
        if (rows.length < BROWSER_PAGE) return;
        const last = rows.at(-1)!;
        after = [Number(last.endUs), last.sequence];
      }
    }
    async function* rider(): AsyncGenerator<Input> {
      let after: OriginalCursor | null = null;
      while (true) {
        const rows = await browserRideStore.pageExact(
          record.id,
          fromUs,
          secondsUs(until),
          after,
          BROWSER_PAGE,
          record.revision,
        );
        for (const row of rows)
          yield {
            end: row.elapsedSeconds,
            timeUs: BigInt(row.elapsedUs),
            append: sink => {
              const item = browserAnalysisItem(row);
              sink.telemetry(
                row.elapsedUs,
                row.humanPowerW ?? null,
                row.motorInputPowerW ?? null,
                row.controllerSpeedMps ?? null,
                row.active ? timeline.activeInterval(item) : null,
                JSON.stringify([row.recordingId, row.connectionEpoch]),
                row.timestamp,
              );
            },
          };
        if (rows.length < BROWSER_PAGE) return;
        const last = rows.at(-1)!;
        after = [last.elapsedUs, last.producer, last.producerSequence];
      }
    }
    async function* gps(): AsyncGenerator<Input> {
      if (controllerSpeed) return;
      let after: OriginalCursor | null = null;
      while (true) {
        const rows = await browserRideStore.locationPage(record.id, fromUs, secondsUs(until), after, record.revision);
        for (const row of rows)
          yield {
            end: row.elapsedSeconds,
            timeUs: BigInt(row.elapsedUs),
            append: sink =>
              sink.speed(
                row.elapsedUs,
                row.distanceBarrier ? null : (row.speedMps ?? null),
                row.speedAccuracyMps ?? null,
                row.active ? timeline.activeInterval(browserAnalysisItem(row)) : null,
              ),
          };
        if (rows.length < BROWSER_PAGE) return;
        const last = rows.at(-1)!;
        after = [last.elapsedUs, last.producer, last.producerSequence];
      }
    }
    let highWater = throughUs,
      saved = false;
    const save = () => {
      if (saved || highWater >= cutUs) return;
      saved = true;
      cut();
    };
    const streams = [intervals('distanceMeters', false), intervals(battery, true), gps(), rider()];
    const heads = await Promise.all(streams.map(stream => stream.next()));
    while (true) {
      let index = -1;
      for (let i = 0; i < heads.length; i++)
        if (!heads[i]!.done && (index < 0 || heads[i]!.value.end < heads[index]!.value.end)) index = i;
      if (index < 0) break;
      const input = heads[index]!.value as Input;
      if (input.timeUs >= cutUs) save();
      input.append(sink);
      if (input.timeUs > highWater) highWater = input.timeUs;
      heads[index] = await streams[index]!.next();
    }
    save();
  };
  const fresh = async (resumed?: EnergyCheckpoint) => {
    const query = new AnalyticsEnergyPerDistance(
      startUs,
      endUs,
      buckets,
      timerUs,
      controllerSpeed,
      smoothingSeconds,
      handle !== null,
      resumed?.checkpoint ?? null,
      whole?.slotUs ?? null,
    );
    let next: EnergyCheckpoint | undefined;
    await feed(query, resumed?.cutUs, resumed ? BigInt(resumed.checkpoint.throughUs) : -1n, () => {
      if (key !== undefined) next = { key, checkpoint: query.checkpoint(), cutUs, revision: record.revision, profiles };
    });
    return { result: JSON.parse(query.resultJson()) as EnergyPerDistanceResult, next };
  };
  const cached = async (state: EnergySeries | undefined, seriesKey: string) => {
    const series =
      state?.series ??
      new AnalyticsEnergyPerDistanceSeries(controllerSpeed, smoothingSeconds, handle !== null, PLOT_SUMMARY_POINTS);
    const read = series.begin(endUs, timerUs, buckets!, whole!.slotUs);
    let committed = state?.cutUs ?? 0n;
    await feed(read, state?.cutUs, BigInt(series.throughUs), () => {
      read.cut();
      committed = cutUs;
    });
    const result = JSON.parse(read.resultJson()) as EnergyPerDistanceResult;
    return { result, next: { key: seriesKey, series, cutUs: committed, revision: record.revision } };
  };
  try {
    if (whole && buckets !== null && start === 0) {
      const ride = `${record.id}:`;
      for (const name of energySeries.keys())
        if (whole.incremental ? !name.startsWith(ride) : record.endedAt && name.startsWith(ride))
          energySeries.delete(name);
      if (!whole.incremental) return (await fresh()).result;
      const seriesKey = JSON.stringify([record.distanceSource, smoothingSeconds, handle !== null]);
      const work = energySeriesWork.then(async () => {
        let state = energySeries.get(slot);
        if (state === null) return (await fresh()).result;
        if (
          state &&
          !(
            state.key === seriesKey &&
            state.series.valid &&
            BigInt(endUs) - BigInt(state.series.throughUs) > energyMarginUs &&
            (await appendedSince(record, state.revision, state.cutUs))
          )
        )
          state = undefined;
        let outcome: Awaited<ReturnType<typeof cached>>;
        try {
          outcome = await cached(state, seriesKey);
        } catch (error) {
          if (!state || isChartCapacity(error)) throw error;
          outcome = await cached(undefined, seriesKey);
        }
        energySeries.set(slot, outcome.next.series.valid ? outcome.next : null);
        return outcome.result;
      });
      energySeriesWork = work.catch(() => {});
      return await work;
    }
    const stored = key === undefined ? undefined : energyCheckpoints.get(slot);
    const resumed =
      stored?.key === key && stored && (await energyResumes(stored, record, profiles, endUs)) ? stored : undefined;
    const { result, next } = await fresh(resumed);
    let reusable = next !== undefined && (!stored || int64(next.revision) >= int64(stored.revision));
    for (const profile of profiles)
      if (reusable && (await unownedBetween(profile, resumed?.cutUs ?? 0n, next!.cutUs))) reusable = false;
    if (reusable) {
      energyCheckpoints.delete(slot);
      energyCheckpoints.set(slot, next!);
      if (energyCheckpoints.size > 8) energyCheckpoints.delete(energyCheckpoints.keys().next().value!);
    }
    return result;
  } finally {
    await release();
  }
}
function makeHandle(record: BrowserRide, profiles: Profile[]): BrowserDistanceHandle {
  const handle: BrowserDistanceHandle = {
    record,
    profile: profiles[0]!,
    info: { selection: 'auto', selected: null, available: [] },
    profiles: profiles.flatMap(profile => {
      const value = coreProfile(profile);
      return value ? [value] : [];
    }),
  };
  handleProfiles.set(handle, new Map(profiles.map(profile => [profile.source, profile])));
  return handle;
}
export function browserDistanceRevision(
  record: Pick<BrowserRide, 'revision' | 'distanceSource'>,
  _selection: DistanceSource = 'auto',
): string {
  return revision(record);
}
export function ensureBrowserDistance(
  id: string,
  selection: DistanceSource = 'auto',
  pin?: BrowserRide,
): Promise<BrowserDistanceHandle> {
  const work = Promise.resolve(pending.get(id))
    .catch(() => {})
    .then(() =>
      navigator.locks.request(builderLock(id), async () => {
        const record = pin ?? (await browserRideStore.get(id));
        const cached = await peekBrowserDistance(id, selection, record);
        return cached ?? build(id, record);
      }),
    )
    .finally(() => {
      if (pending.get(id) === work) pending.delete(id);
    });
  pending.set(id, work);
  return work.then(handle => selectDistance(handle, selection));
}
export async function peekBrowserDistance(
  id: string,
  selection: DistanceSource = 'auto',
  pin?: BrowserRide,
): Promise<BrowserDistanceHandle | undefined> {
  return browserTransaction(['recordings', 'distance-profiles'], 'readonly', async tx => {
    const stored = await idbRequest<BrowserRide | undefined>(tx.objectStore('recordings').get(id));
    if (!stored) throw new Error('Recording not found');
    const record = pin ?? normalize(stored);
    const profiles = await Promise.all(PROFILE_SOURCES.map(source => profileIn(tx, id, source, record.revision)));
    for (const profile of profiles) if (profile) validateProfile(profile);
    if (profiles.some(profile => !profile || profile.building || profile.recordRevision !== record.revision)) return;
    return selectDistance(makeHandle(record, profiles as Profile[]), selection);
  });
}
async function build(id: string, record: BrowserRide): Promise<BrowserDistanceHandle> {
  let oldProfiles = await browserTransaction(['distance-profiles'], 'readonly', async tx =>
    Promise.all(PROFILE_SOURCES.map(source => profileIn(tx, id, source))),
  );
  for (const profile of oldProfiles) if (profile) validateProfile(profile);
  let lifecycleSafe = oldProfiles.every(profile => profile?.lifecycleCount === record.lifecycleCount);
  let dirtyUs: bigint | undefined;
  if (
    !lifecycleSafe &&
    oldProfiles.every(profile => profile && int64(profile.recordRevision) <= int64(record.revision))
  ) {
    lifecycleSafe = true;
    let after = Math.min(...oldProfiles.map(profile => Number(profile!.recordRevision)));
    while (true) {
      const page = await browserRideStore.lifecycleAfter(id, after, BROWSER_PAGE);
      for (const event of page.rows) {
        if (int64(event.revision) > int64(record.revision)) continue;
        const time = int64(event.elapsedUs);
        if (dirtyUs === undefined || time < dirtyUs) dirtyUs = time;
        if (oldProfiles.some(profile => profile?.cursor && int64(profile.cursor[0]) >= int64(event.elapsedUs)))
          lifecycleSafe = false;
      }
      if (page.rows.length < BROWSER_PAGE) break;
      after = Number(page.rows.at(-1)!.revision);
    }
  }
  let fork = !oldProfiles.every(profile => profile?.lifecycleCount === record.lifecycleCount);
  if (!lifecycleSafe && dirtyUs !== undefined) {
    const candidates = await browserTransaction(['distance-profiles'], 'readonly', tx =>
      idbRequest<Profile[]>(tx.objectStore('distance-profiles').getAll(IDBKeyRange.bound([id], [id, []]))),
    );
    const groups = new Map<string, Profile[]>();
    for (const profile of candidates) {
      if (
        profile.building ||
        profile.lifecycleCount !== oldProfiles[0]!.lifecycleCount ||
        int64(profile.recordRevision) > int64(record.revision) ||
        (profile.cursor && int64(profile.cursor[0]) >= dirtyUs)
      )
        continue;
      const key = `${profile.generation}:${profile.recordRevision}`;
      const group = groups.get(key) ?? [];
      group.push(profile);
      groups.set(key, group);
    }
    const preceding = [...groups.values()]
      .filter(group => PROFILE_SOURCES.every(source => group.some(p => p.source === source)))
      .sort((a, b) => b[0]!.revisionKey.localeCompare(a[0]!.revisionKey))[0];
    if (preceding) {
      preceding.forEach(validateProfile);
      oldProfiles = PROFILE_SOURCES.map(source => preceding.find(p => p.source === source)!);
      lifecycleSafe = true;
      fork = true;
    }
  }
  const incremental = oldProfiles.every(
    profile =>
      profile &&
      int64(profile.recordRevision) <= int64(record.revision) &&
      profile.telemetryCount <= record.telemetryCount &&
      profile.locationCount <= record.locationCount &&
      lifecycleSafe &&
      !profile.building,
  );
  let old = incremental ? (oldProfiles as Profile[]) : undefined;
  const generation = old && !fork ? old[0]!.generation : await generationAfter();
  let releaseOld = old && fork ? await holdGeneration(id, old[0]!.generation) : undefined;
  const release = await holdGeneration(id, generation);
  try {
    if (old) {
      const retainedProfiles = old;
      const retained = await browserTransaction(['distance-profiles'], 'readonly', tx =>
        Promise.all(
          retainedProfiles.map(profile =>
            idbRequest(
              tx.objectStore('distance-profiles').get([id, profile.source, profile.generationKey, profile.revisionKey]),
            ),
          ),
        ),
      );
      if (retained.some(profile => !profile)) return await build(id, record);
      if (fork) {
        old = forkCheckpoint(old, generation);
        const checkpoints = old;
        await browserTransaction(['recordings', 'distance-profiles'], 'readwrite', async tx => {
          if (!(await idbRequest(tx.objectStore('recordings').get(id)))) throw new Error('Recording not found');
          for (const profile of checkpoints) await idbRequest(tx.objectStore('distance-profiles').put(profile));
        });
      }
    }
    const result = await buildGeneration(id, record, old, generation);
    await releaseOld?.();
    releaseOld = undefined;
    await reclaim(id);
    return result;
  } finally {
    await release();
    await releaseOld?.();
  }
}
function forkCheckpoint(profiles: Profile[], generation: string): Profile[] {
  return profiles.map(profile => ({
    ...profile,
    generation,
    generationKey: browserIndexKey(generation),
    prefixes: profileSpans(profile).map(({ generation, revisionKey, endUs, inputSequence }) => ({
      generation,
      revisionKey,
      endUs,
      inputSequence,
    })),
  }));
}
async function buildGeneration(
  id: string,
  record: BrowserRide,
  old: Profile[] | undefined,
  generation: string,
): Promise<BrowserDistanceHandle> {
  const prepared = await prepareBrowserTimeline(record);
  const controller = new AnalyticsControllerKernel();
  const gps = new AnalyticsGPSKernel(false);
  const oldFor = (source: ProfileSource) => old?.find(profile => profile.source === source);
  const reducers = new Map(
    ['controller', 'gps:phone'].map(source => {
      const prior = oldFor(source as ProfileSource);
      return [
        source,
        new AnalyticsDistanceReducer('0', prepared.actualEndUs, prior?.distanceQ ?? '0', prior?.coveredUs ?? '0'),
      ];
    }),
  );
  const consumption = new AnalyticsConsumptionKernel(oldFor('controllerEnergyWh')?.consumptionCheckpoint ?? null);
  const profiles: Profile[] = PROFILE_SOURCES.map(source => ({
    recordingId: id,
    generation,
    source,
    generationKey: browserIndexKey(generation),
    revisionKey: browserIndexKey(record.revision),
    inputSequence: -1,
    intervalEndUs: '0',
    prefixes: [],
    state: { intervals: 0 },
    building: false,
    recordRevision: record.revision,
    coveredUs: '0',
    distanceQ: '0',
    timerUs: prepared.timerUs,
    telemetryCount: record.telemetryCount,
    locationCount: record.locationCount,
    lifecycleCount: record.lifecycleCount,
  }));
  if (old)
    for (const [index, profile] of profiles.entries())
      Object.assign(profile, old[index], {
        timerUs: prepared.timerUs,
        recordRevision: record.revision,
        revisionKey: browserIndexKey(record.revision),
        telemetryCount: record.telemetryCount,
        locationCount: record.locationCount,
        lifecycleCount: record.lifecycleCount,
      });
  const checkpoint = oldFor('controller')?.checkpoint;
  const previous = checkpoint?.previous;
  if (checkpoint)
    controller.restore(
      previous ? browserAnalysisItem(previous) : null,
      previous ? (prepared.activeInterval(browserAnalysisItem(previous)) ?? null) : null,
      previous?.controllerModel ?? null,
      previous?.controllerProtocol ?? null,
      previous?.connectionEpoch ?? null,
      previous ? JSON.stringify([previous.connectionEpoch, previous.interval, previous.interruptionIndex]) : null,
      checkpoint.segment,
    );
  const gpsCheckpoint = oldFor('gps:phone')?.gpsCheckpoint;
  if (gpsCheckpoint) {
    const item = gpsCheckpoint.previous ? browserAnalysisItem(gpsCheckpoint.previous) : null;
    const geometry = gpsCheckpoint.geometryPrevious ? browserAnalysisItem(gpsCheckpoint.geometryPrevious) : null;
    gps.restoreDistance(
      item,
      item ? (prepared.activeInterval(item) ?? null) : null,
      geometry,
      geometry ? (prepared.activeInterval(geometry) ?? null) : null,
      gpsCheckpoint.segment,
    );
  }
  let rows: DistanceRow[] = [];
  const last = new Map<ProfileSource, DistanceRow>();
  if (old)
    for (const profile of old) {
      const row = await profileNeighbor(profile, Infinity, 'prev');
      if (row) last.set(profile.source, row);
    }
  const flush = async (building: boolean) => {
    const batch = rows;
    rows = [];
    await browserTransaction(
      ['recordings', 'distance-intervals', 'distance-tiles', 'distance-profiles'],
      'readwrite',
      async tx => {
        if (!(await idbRequest(tx.objectStore('recordings').get(id)))) throw new Error('Recording not found');
        await project(tx, batch);
        for (const profile of profiles) {
          await idbRequest(tx.objectStore('distance-profiles').put({ ...profile, building }));
          if (!building) {
            const prefix = [id, profile.source, profile.generationKey];
            const stored = await idbRequest<Profile[]>(
              tx.objectStore('distance-profiles').getAll(IDBKeyRange.bound(prefix, [...prefix, []])),
            );
            // Handles own their profile; interval revisions remain immutable until generation reclamation.
            const completed = stored
              .filter(row => !row.building)
              .sort((a, b) => a.revisionKey.localeCompare(b.revisionKey));
            for (const redundant of [
              ...completed.slice(0, -2),
              ...stored.filter(row => row.building && row.revisionKey < profile.revisionKey),
            ])
              await idbRequest(tx.objectStore('distance-profiles').delete([...prefix, redundant.revisionKey]));
          }
        }
      },
    );
  };
  await scanBrowserAnalysis(
    record,
    browserRideStore,
    async (item, original) => {
      if ('action' in original) return;
      const source = 'latitude' in original ? 'gps:phone' : 'controller';
      const active = original.active
        ? (prepared.activeIntervalAt(item.collectionUs, item.producer, item.sequence, item.clockEpoch) ?? null)
        : null;
      if (!('latitude' in original)) {
        const calculated = consumption.append(
          item.collectionUs,
          active,
          JSON.stringify([
            original.controllerModel,
            original.controllerProtocol,
            original.connectionEpoch,
            original.interruptionIndex,
          ]),
          original.consumedAh,
          original.consumedWh,
          original.batteryCurrentA,
          original.batteryVoltageV,
        );
        const saved = consumption.checkpoint();
        for (const metric of CONSUMPTION_METRICS) {
          const profile = profiles.find(profile => profile.source === metric)!;
          profile.consumptionCheckpoint = saved;
          profile.cursor = [original.elapsedUs, original.producer, original.producerSequence];
          profile.inputSequence = original.sequence;
        }
        for (const interval of calculated) {
          const metric = interval.metric as ConsumptionMetric;
          const profile = profiles.find(profile => profile.source === metric)!;
          const previous = last.get(metric);
          const start = Number(interval.startUs) / 1e6;
          const end = Number(interval.endUs) / 1e6;
          const connected = previous?.end === start && previous.segment === interval.segment && !interval.baseline;
          const coveredUs =
            BigInt(profile.coveredUs) + (interval.baseline ? 0n : BigInt(interval.endUs) - BigInt(interval.startUs));
          const row: DistanceRow = {
            recordingId: id,
            generation,
            source: metric,
            generationKey: profile.generationKey,
            revisionKey: profile.revisionKey,
            sequence: original.sequence,
            total: interval.endValue,
            covered: Number(coveredUs) / 1e6,
            segment: interval.segment,
            plotSegment: interval.segment,
            timestamp: original.timestamp,
            startTimestamp: connected ? previous.timestamp : original.timestamp,
            startReference: String(connected ? previous.sequence * 2 + 1 : original.sequence * 2),
            startObservationId: connected
              ? point(previous, 'end').observationId!
              : `${id}:distance:${generation}:${metric}:${original.sequence}:start`,
            producer: original.producer,
            producerSequence: original.producerSequence,
            sequenceKey: browserIndexKey(original.producerSequence),
            start,
            end,
            startUs: interval.startUs,
            endUs: interval.endUs,
            endKey: browserIndexKey(interval.endUs),
            distance: interval.endValue - interval.startValue,
            distanceQ: '0',
            startSpeed: null,
            endSpeed: null,
            speed0Q: null,
            speed1Q: null,
            activeInterval: interval.activeInterval,
            indivisible: interval.indivisible,
            from: original.eventId,
            to: original.eventId,
            consumption: {
              metric,
              startUs: interval.startUs,
              endUs: interval.endUs,
              startValue: interval.startValue,
              endValue: interval.endValue,
              rate0: interval.rate0 ?? null,
              rate1: interval.rate1 ?? null,
              indivisible: interval.indivisible,
              segment: interval.segment,
              activeInterval: interval.activeInterval,
              baseline: interval.baseline,
            },
          };
          profile.state = { intervals: profile.state.intervals + 1 };
          profile.intervalEndUs = interval.endUs;
          profile.coveredUs = coveredUs.toString();
          rows.push(row);
          last.set(metric, row);
        }
        if (rows.length >= BROWSER_PAGE) await flush(true);
      }
      const interval =
        'latitude' in original
          ? gps.append(item, active)
          : controller.append(
              item,
              active,
              original.controllerModel ?? null,
              original.controllerProtocol ?? null,
              original.connectionEpoch,
              JSON.stringify([original.connectionEpoch, original.interval, original.interruptionIndex]),
              null,
              null,
            );
      const profile = profiles.find(profile => profile.source === source)!;
      const localSequence = 'latitude' in original ? profile.inputSequence + 1 : original.sequence;
      profile.inputSequence = localSequence;
      if (!('latitude' in original)) {
        const identity = controller.previousObservationId();
        const savedPrevious = profile.checkpoint?.previous;
        let retained: RideRow | null = null;
        if (identity === item.observationId) retained = original;
        else if (savedPrevious && identity === `${id}:${savedPrevious.sequence}`) retained = savedPrevious;
        profile.checkpoint = {
          previous: retained,
          segment: controller.segment(),
        };
      } else {
        const checkpoint = profile.gpsCheckpoint;
        const retained = (identity: string | null) =>
          [original, checkpoint?.previous, checkpoint?.geometryPrevious].find(
            row => row && browserAnalysisItem(row).observationId === identity,
          ) ?? null;
        profile.gpsCheckpoint = {
          previous: retained(gps.previousObservationId() ?? null),
          geometryPrevious: retained(gps.geometryPreviousObservationId() ?? null),
          segment: gps.segment,
        };
      }
      profile.cursor = [original.elapsedUs, original.producer, original.producerSequence];
      if (!interval) return;
      const reducer = reducers.get(source)!;
      reducer.append(interval);
      const total = reducer.result(prepared.timerUs);
      const previous = last.get(source);
      const start = analyticsDivide(interval.startUs, '1000000');
      const end = analyticsDivide(interval.endUs, '1000000');
      const plotSegment =
        (previous?.plotSegment ?? 0) +
        (previous &&
        (start - previous.end >= TELEMETRY_DISPLAY_HOLD_SECONDS ||
          (item.interruptionIndex !== 0 && previous.segment !== interval.segment))
          ? 1
          : 0);
      const row: DistanceRow = {
        recordingId: id,
        generation,
        source,
        generationKey: profile.generationKey,
        revisionKey: profile.revisionKey,
        sequence: localSequence,
        total: total.distanceMeters,
        covered: total.coveredSeconds,
        segment: interval.segment,
        plotSegment,
        timestamp: interval.end.timestamp,
        startTimestamp: interval.start.timestamp,
        startReference:
          previous?.to === interval.start.observationId ? String(previous.sequence * 2 + 1) : String(localSequence * 2),
        startObservationId:
          previous?.to === interval.start.observationId
            ? `${id}:distance:${previous.generation}:${source}:${previous.sequence}:end`
            : `${id}:distance:${generation}:${source}:${localSequence}:start`,
        producer: original.producer,
        producerSequence: original.producerSequence,
        sequenceKey: browserIndexKey(original.producerSequence),
        start,
        end,
        startUs: interval.startUs,
        endUs: interval.endUs,
        endKey: browserIndexKey(interval.endUs),
        distance: analyticsDivide(interval.distanceQ, '1000000'),
        distanceQ: interval.distanceQ,
        startSpeed: interval.speed0Q == null ? null : analyticsDivide(interval.speed0Q, '1000000'),
        endSpeed: interval.speed1Q == null ? null : analyticsDivide(interval.speed1Q, '1000000'),
        speed0Q: interval.speed0Q ?? null,
        speed1Q: interval.speed1Q ?? null,
        activeInterval: interval.activeInterval,
        indivisible: interval.indivisible,
        from: interval.start.observationId,
        to: interval.end.observationId,
      };
      profile.state = { intervals: profile.state.intervals + 1 };
      profile.intervalEndUs = interval.endUs;
      profile.coveredUs = total.coveredUs;
      profile.distanceQ = total.distanceQ;
      rows.push(row);
      last.set(source, row);
      if (rows.length >= BROWSER_PAGE) await flush(true);
    },
    {
      samples: oldFor('controller')?.cursor,
      'ride-locations': oldFor('gps:phone')?.cursor,
      'ride-lifecycle': false,
    },
  );
  await flush(false);
  return makeHandle(record, profiles);
}
function anchorItem(row: DistanceRow, edge: 'start' | 'end'): AnalyticsItem {
  const elapsed = row[edge];
  return new AnalyticsItem(
    edge === 'start' ? row.startUs : row.endUs,
    elapsed,
    edge === 'start' ? row.from : row.to,
    edge === 'start' ? row.from : row.to,
    row.producer,
    row.producerSequence,
    row.source === 'controller' ? 'telemetry' : 'location',
    row.source === 'controller' ? 'controller' : 'phone',
    edge === 'start' ? row.startTimestamp : row.timestamp,
    [],
    [],
    [],
    null,
    null,
    null,
    null,
    0,
    null,
    false,
    false,
    null,
    true,
  );
}
function hydrateInterval(row: DistanceRow): AnalyticsInterval {
  return analyticsStoredInterval(
    row.startUs,
    row.endUs,
    row.distanceQ,
    row.segment,
    row.activeInterval,
    anchorItem(row, 'start'),
    anchorItem(row, 'end'),
    row.speed0Q,
    row.speed1Q,
    row.indivisible,
  );
}
const projectionHandles = new WeakMap<IDBDatabase, Map<string, { payload: string; handle: InspectionPlotCache }>>();
async function ownedDistanceRows(
  tx: IDBTransaction,
  prefix: IDBValidKey[],
  start: number,
  end: number,
): Promise<DistanceRow[]> {
  const range = browserKeyRange([...prefix, secondsKey(start)], [...prefix, secondsKey(end)], false, true);
  if (!range) return [];
  const values = tx.objectStore('distance-intervals');
  const [rows, next] = await Promise.all([
    idbRequest<DistanceRow[]>(values.getAll(range)),
    idbRequest(values.openCursor(IDBKeyRange.bound([...prefix, secondsKey(end)], [...prefix, []]))),
  ]);
  if (next) {
    // Disjoint intervals permit only this end-time group to own a start before the bucket ends.
    const key = [...prefix, (next.value as DistanceRow).endKey];
    rows.push(...(await idbRequest<DistanceRow[]>(values.getAll(IDBKeyRange.bound(key, [...key, []])))));
  }
  return rows;
}

async function project(tx: IDBTransaction, rows: DistanceRow[]) {
  let handles = projectionHandles.get(tx.db);
  if (!handles) {
    handles = new Map();
    projectionHandles.set(tx.db, handles);
  }
  const keyFor = (tile: Pick<DistanceTile, 'recordingId' | 'source' | 'generationKey' | 'level' | 'bucket'>) =>
    JSON.stringify([tile.recordingId, tile.source, tile.generationKey, tile.level, tile.bucket]);
  const retain = (key: string, payload: string, handle: InspectionPlotCache) => {
    handles!.delete(key);
    handles!.set(key, { payload, handle });
    if (handles!.size > 1024) handles!.delete(handles!.keys().next().value!);
  };
  const decoded = (tile: DistanceTile) => {
    const key = keyFor(tile),
      cached = handles!.get(key);
    if (tile.plot && cached?.payload === tile.plot) return cached.handle;
    const handle = new InspectionPlotCache(tile.plot!);
    retain(key, tile.plot!, handle);
    return handle;
  };
  const values = tx.objectStore('distance-intervals'),
    tiles = tx.objectStore('distance-tiles');
  await Promise.all(rows.map(row => idbRequest(values.put(row))));
  for (let level = 0; level < TILE_SECONDS.length; level++) {
    const size = TILE_SECONDS[level]!;
    const touched = new Map<string, { row: DistanceRow; bucket: number }>();
    for (const row of rows)
      for (const time of [row.start, row.end]) {
        const bucket = Math.floor(time / size);
        touched.set(JSON.stringify([row.recordingId, row.source, row.generationKey, bucket]), { row, bucket });
      }
    await Promise.all(
      [...touched.values()].map(async ({ row, bucket }) => {
        const prefix = [row.recordingId, row.source, row.generationKey];
        const reducer = new InspectionPlotBucket(
          'distanceMeters',
          secondsUs(bucket * size),
          secondsUs((bucket + 1) * size),
        );
        let aggregate: InspectionPlotCache | undefined;
        if (level === 0) {
          const raw = await ownedDistanceRows(tx, prefix, bucket * size, (bucket + 1) * size);
          aggregate = optionalChartCache(() => {
            raw
              .flatMap(boundaries)
              .filter(
                point =>
                  point.original.elapsedSeconds >= bucket * size && point.original.elapsedSeconds < (bucket + 1) * size,
              )
              .sort((a, b) => a.original.elapsedSeconds - b.original.elapsedSeconds)
              .forEach(p => reducer.appendDistance(p.boundary));
            return reducer.aggregate();
          });
        } else {
          const childSize = TILE_SECONDS[level - 1]!;
          const children = await idbRequest<DistanceTile[]>(
            tiles.getAll(
              IDBKeyRange.bound(
                [...prefix, level - 1, browserIndexKey(String((bucket * size) / childSize))],
                [...prefix, level - 1, browserIndexKey(String(((bucket + 1) * size) / childSize - 1)), []],
              ),
            ),
          );
          if (children.every(child => child.plot))
            aggregate = optionalChartCache(() => {
              children.forEach(child => reducer.merge(decoded(child)));
              return reducer.aggregate();
            });
        }
        const tile: DistanceTile = {
          recordingId: row.recordingId,
          generation: row.generation,
          source: row.source,
          generationKey: row.generationKey,
          revisionKey: row.revisionKey,
          level,
          bucket,
          bucketKey: browserIndexKey(String(bucket)),
          plot: aggregate?.payload,
        };
        if (aggregate) retain(keyFor(tile), aggregate.payload, aggregate);
        const key = [...prefix, level, tile.bucketKey];
        await idbRequest(tiles.delete(IDBKeyRange.bound(key, [...key, []])));
        await idbRequest(tiles.put(tile));
      }),
    );
  }
}

function matches(handle: BrowserDistanceHandle, record: BrowserRide | undefined, profile: Profile | undefined) {
  return Boolean(
    record &&
    profile &&
    !profile.building &&
    revision(record) === revision(handle.record) &&
    profile.generation === handle.profile.generation &&
    profile.inputSequence === handle.profile.inputSequence,
  );
}
export async function browserDistanceCurrent(handle: BrowserDistanceHandle): Promise<boolean> {
  return browserTransaction(['recordings', 'distance-profiles'], 'readonly', async tx => {
    const [record, profile] = await Promise.all([
      idbRequest<BrowserRide | undefined>(tx.objectStore('recordings').get(handle.record.id)),
      profileIn(tx, handle.record.id, handle.profile.source),
    ]);
    return matches(handle, record, profile);
  });
}
export interface BrowserDistanceInterval {
  startUs: string;
  endUs: string;
  start: number;
  end: number;
  distance: number;
  segment: number;
  startSpeed: number | null;
  endSpeed: number | null;
  sequence: number;
}
export async function browserDistanceIntervals(
  handle: BrowserDistanceHandle,
  after: readonly [number, number] | null,
  limit: number,
): Promise<BrowserDistanceInterval[] | undefined> {
  // IndexedDB reads a count of 0 as unbounded.
  if (!Number.isSafeInteger(limit) || limit < 1) throw new Error('Invalid browser read bound');
  if (after && !after.every(value => Number.isSafeInteger(value) && value >= 0))
    throw new Error('Invalid distance cursor');
  const id = handle.record.id;
  return browserTransaction(['recordings', 'distance-profiles', 'distance-intervals'], 'readonly', async tx => {
    const [record, profile] = await Promise.all([
      idbRequest<BrowserRide | undefined>(tx.objectStore('recordings').get(id)),
      idbRequest(
        tx
          .objectStore('distance-profiles')
          .openCursor(
            IDBKeyRange.bound(
              [id, handle.profile.source, handle.profile.generationKey],
              [id, handle.profile.source, handle.profile.generationKey, []],
            ),
            'prev',
          ),
      ),
    ]);
    if (
      !record ||
      !profile ||
      record.telemetryCount < handle.record.telemetryCount ||
      record.locationCount < handle.record.locationCount
    )
      return;
    const rows: DistanceRow[] = [];
    for (const span of profileSpans(handle.profile)) {
      if (after && (int64(span.endUs) < BigInt(after[0]) || span.inputSequence <= after[1])) continue;
      const prefix = spanKey(handle.profile, span);
      const lower = after && BigInt(after[0]) > int64(span.startUs) ? String(after[0]) : span.startUs;
      const range = browserKeyRange([...prefix, browserIndexKey(lower)], [...prefix, browserIndexKey(span.endUs), []]);
      if (!range) continue;
      await new Promise<void>((resolve, reject) => {
        const request = tx.objectStore('distance-intervals').openCursor(range);
        request.onerror = () => reject(request.error);
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) return resolve();
          const row = cursor.value as DistanceRow;
          if (spanOwns(span, row) && (!after || int64(row.endUs) > BigInt(after[0]) || row.sequence > after[1]))
            rows.push(row);
          if (rows.length === limit) resolve();
          else cursor.continue();
        };
      });
      if (rows.length === limit) break;
    }
    return rows;
  });
}
function point(row: DistanceRow, edge: 'start' | 'end', startsSegment = false): MonitorPoint {
  return {
    value: edge === 'end' ? row.total : row.total - row.distance,
    elapsedSeconds: row[edge],
    timestamp: edge === 'end' ? row.timestamp : row.startTimestamp,
    observationId:
      edge === 'start'
        ? row.startObservationId
        : `${row.recordingId}:distance:${row.generation}:${row.source}:${row.sequence}:end`,
    startsSegment,
    derived: true,
  };
}
async function profileNeighbor(
  profile: Profile,
  time: number,
  direction: 'prev' | 'next',
  strict = false,
): Promise<DistanceRow | undefined> {
  const spans = profileSpans(profile);
  if (direction === 'prev') spans.reverse();
  return browserTransaction(['distance-intervals'], 'readonly', async tx => {
    for (const span of spans) {
      const prefix = spanKey(profile, span);
      const bound = time === Infinity ? int64(span.endUs) : int64(secondsUs(time));
      if (direction === 'prev' && bound < int64(span.startUs)) continue;
      if (direction === 'next' && (bound > int64(span.endUs) || (strict && bound === int64(span.endUs)))) continue;
      const lower = direction === 'next' && bound > int64(span.startUs) ? bound.toString() : span.startUs;
      const upper = direction === 'prev' && bound < int64(span.endUs) ? bound.toString() : span.endUs;
      const range = browserKeyRange([...prefix, browserIndexKey(lower)], [...prefix, browserIndexKey(upper), []]);
      if (!range) continue;
      const row = await new Promise<DistanceRow | undefined>((resolve, reject) => {
        const request = tx.objectStore('distance-intervals').openCursor(range, direction);
        request.onerror = () => reject(request.error);
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) return resolve(undefined);
          const row = cursor.value as DistanceRow;
          if (spanOwns(span, row) && (!strict || int64(row.endUs) !== bound)) resolve(row);
          else cursor.continue();
        };
      });
      if (row) return row;
    }
  });
}
const neighbor = (handle: BrowserDistanceHandle, time: number, direction: 'prev' | 'next', strict = false) =>
  profileNeighbor(handle.profile, time, direction, strict);
export async function browserDistanceLatest(handle: BrowserDistanceHandle): Promise<MonitorPoint | null> {
  if (handle.profile.state.intervals === 0) return null;
  const row = await neighbor(handle, Infinity, 'prev');
  return row ? point(row, 'end') : null;
}
function boundaries(row: DistanceRow) {
  return (['start', 'end'] as const).map(edge => {
    const original = point(row, edge);
    return {
      original,
      boundary: new InspectionDistanceBoundary(
        monitorInspectionItem('distanceMeters', original),
        row.segment,
        secondsUs(row.start),
        secondsUs(row.end),
        edge === 'start' ? row.startReference : String(row.sequence * 2 + 1),
        row.plotSegment,
      ),
    };
  });
}
export async function inspectBrowserDistance(
  handle: BrowserDistanceHandle,
  time: number,
  anchor?: string,
): Promise<MonitorPoint | null> {
  if (handle.profile.state.intervals === 0) return null;
  const rows = await Promise.all([
    neighbor(handle, time, 'prev'),
    neighbor(handle, time, 'next'),
    neighbor(handle, time, 'next', true),
  ]);
  const points = [...new Map(rows.filter((row): row is DistanceRow => !!row).map(row => [row.sequence, row])).values()]
    .flatMap(boundaries)
    .sort((a, b) => a.original.elapsedSeconds - b.original.elapsedSeconds);
  const before = points.filter(p => p.original.elapsedSeconds <= time).at(-1);
  const after = points.find(p => p.original.elapsedSeconds >= time);
  const anchored = points.find(p => p.original.observationId === anchor);
  const selected = inspectionDistanceOriginal(
    new InspectionTargetInput(secondsUs(time), anchor ?? null, anchor ? secondsUs(time) : null),
    before?.boundary ?? null,
    after?.boundary ?? null,
    anchored?.boundary ?? null,
  );
  return selected ? { ...inspectionPoint(selected), derived: true } : null;
}
export async function browserDistanceStats(
  handle: BrowserDistanceHandle,
  start: number,
  end: number,
): Promise<MonitorStatistics | undefined> {
  if (handle.profile.state.intervals === 0) return;
  if (isConsumptionMetric(handle.profile.source)) return consumptionStats(handle, start, end);
  const release = await retainBrowserDistance(handle);
  try {
    const reducer = new AnalyticsDistanceReducer(secondsUs(start), secondsUs(end));
    let after: readonly [number, number] | null = null;
    while (true) {
      const rows = await browserDistanceIntervals(handle, after, BROWSER_PAGE);
      if (!rows) throw new Error('Distance pin changed');
      for (const row of rows) reducer.append(hydrateInterval(row as DistanceRow));
      if (rows.length < BROWSER_PAGE) break;
      const tail = rows.at(-1)!;
      after = [Number(tail.endUs), tail.sequence];
    }
    const timer = await prepareBrowserTimeline(handle.record);
    const value = reducer.result(timer.rangeTimerUs(secondsUs(start), secondsUs(end)));
    if (value.coveredUs === '0') return;
    return {
      distance: value.distanceMeters,
      coveredSeconds: value.coveredSeconds,
      unresolvedBoundary: value.unresolvedBoundary,
      partial: value.partial,
    };
  } finally {
    await release();
  }
}
async function consumptionStats(handle: BrowserDistanceHandle, start: number, end: number): Promise<MonitorStatistics> {
  const release = await retainBrowserDistance(handle);
  try {
    const [left, right, firstAfter, lastAfter] = await Promise.all([
      neighbor(handle, start, 'prev'),
      neighbor(handle, end, 'prev'),
      neighbor(handle, start, 'next', true),
      neighbor(handle, end, 'next', true),
    ]);
    // Cumulative prefixes cover all full edges without scanning the ride. Only the two boundary edges need clipping.
    let amount = (right?.total ?? 0) - (left?.total ?? 0);
    let coveredSeconds = (right?.covered ?? 0) - (left?.covered ?? 0);
    let unresolvedBoundary = false;
    let available = right?.consumption
      ? consumptionInterval(right.consumption).range(secondsUs(start), secondsUs(end)).available
      : false;
    if (firstAfter?.consumption && firstAfter.start < start && firstAfter.end <= end) {
      amount -= firstAfter.distance;
      coveredSeconds -= firstAfter.end - firstAfter.start;
      const clipped = consumptionInterval(firstAfter.consumption).range(secondsUs(start), secondsUs(end));
      amount += clipped.amount;
      coveredSeconds += clipped.coveredSeconds;
      unresolvedBoundary ||= clipped.unresolvedBoundary;
      available ||= clipped.available;
    }
    if (lastAfter?.consumption && lastAfter.start < end) {
      const clipped = consumptionInterval(lastAfter.consumption).range(secondsUs(start), secondsUs(end));
      amount += clipped.amount;
      coveredSeconds += clipped.coveredSeconds;
      unresolvedBoundary ||= clipped.unresolvedBoundary;
      available ||= clipped.available;
    }
    const timeline = await prepareBrowserTimeline(handle.record);
    const timer = Number(timeline.rangeTimerUs(secondsUs(start), secondsUs(end))) / 1e6;
    return {
      ...(available ? { amount: Math.max(0, amount) } : {}),
      coveredSeconds: Math.max(0, coveredSeconds),
      unresolvedBoundary,
      partial: !available || unresolvedBoundary || timer - coveredSeconds > 0.001,
    };
  } finally {
    await release();
  }
}
type DistanceSummary = {
  key: string;
  summary?: InspectionPlotSlotSummary;
  revisionKey: string;
  inputSequence: number;
  last?: DistanceRow;
  unowned: boolean;
};
const distanceSummaries = new Map<string, DistanceSummary>();
let distanceSummaryWork: Promise<unknown> = Promise.resolve();
function compareDistanceRows(a: DistanceRow, b: DistanceRow): number {
  for (const field of ['endKey', 'producer', 'sequenceKey', 'revisionKey'] as const)
    if (a[field] !== b[field]) return a[field] < b[field] ? -1 : 1;
  return 0;
}
async function spanRows(profile: Profile, span: DistanceSpan, after?: DistanceRow): Promise<DistanceRow[]> {
  const prefix = spanKey(profile, span);
  const range = browserKeyRange(
    after
      ? [...prefix, after.endKey, after.producer, after.sequenceKey, after.revisionKey]
      : [...prefix, browserIndexKey(span.startUs)],
    [...prefix, browserIndexKey(span.endUs), []],
    Boolean(after),
  );
  if (!range) return [];
  return browserTransaction(['distance-intervals'], 'readonly', tx =>
    idbRequest<DistanceRow[]>(tx.objectStore('distance-intervals').getAll(range, BROWSER_PAGE)),
  );
}
async function generationRowsSince(profile: Profile, revisionKey: string): Promise<DistanceRow[]> {
  const prefix = [profile.recordingId, profile.source, profile.generationKey];
  const range = browserKeyRange([...prefix, revisionKey, []], [...prefix, profile.revisionKey, []], true);
  if (!range) return [];
  return browserTransaction(['distance-intervals'], 'readonly', tx =>
    idbRequest<DistanceRow[]>(tx.objectStore('distance-intervals').index('byRevision').getAll(range)),
  );
}
function feedDistance(state: DistanceSummary, span: DistanceSpan, rows: DistanceRow[], own: boolean): void {
  const owned = rows.filter(row => spanOwns(span, row));
  // A later profile owns rows written past this input sequence without writing them again.
  if (own) state.unowned ||= rows.some(row => row.sequence > span.inputSequence && row.revisionKey <= span.revisionKey);
  try {
    for (const row of owned) for (const edge of boundaries(row)) state.summary?.appendDistance(edge.boundary);
    state.last = owned.at(-1) ?? state.last;
  } catch {
    state.summary = undefined;
  }
}
async function distanceSummary(handle: BrowserDistanceHandle): Promise<InspectionPlotSlotSummary | undefined> {
  const profile = handle.profile,
    spans = profileSpans(profile),
    slot = `${profile.recordingId}:${profile.source}`,
    key = JSON.stringify([profile.generation, profile.prefixes]);
  for (const name of distanceSummaries.keys())
    if (!name.startsWith(`${profile.recordingId}:`)) distanceSummaries.delete(name);
  let state = distanceSummaries.get(slot);
  if (state?.key !== key) state = undefined;
  if (state && (state.revisionKey > profile.revisionKey || state.inputSequence > profile.inputSequence)) return;
  if (state?.summary && state.revisionKey !== profile.revisionKey) {
    const own = spans.find(span => span.generation === profile.generation);
    if (state.unowned && profile.inputSequence > state.inputSequence) state = undefined;
    else if (own) {
      const rows = (await generationRowsSince(profile, state.revisionKey)).sort(compareDistanceRows);
      const first = rows.find(row => spanOwns(own, row));
      if (first && state.last?.generation === profile.generation && compareDistanceRows(first, state.last) <= 0)
        state = undefined;
      else feedDistance(state, own, rows, true);
    }
  }
  if (!state) {
    state = {
      key,
      summary: new InspectionPlotSlotSummary('distanceMeters', 512, PLOT_SUMMARY_POINTS),
      revisionKey: profile.revisionKey,
      inputSequence: profile.inputSequence,
      unowned: false,
    };
    for (const span of spans) {
      let after: DistanceRow | undefined;
      while (state.summary) {
        const rows = await spanRows(profile, span, after);
        feedDistance(state, span, rows, span.generation === profile.generation);
        if (rows.length < BROWSER_PAGE) break;
        after = rows.at(-1);
      }
    }
  }
  state.revisionKey = profile.revisionKey;
  state.inputSequence = profile.inputSequence;
  distanceSummaries.set(slot, state);
  return state.summary;
}
function mergeDistanceSummary(handle: BrowserDistanceHandle, reducer: InspectionPlotQuery): Promise<boolean> {
  const work = distanceSummaryWork.then(async () => {
    const summary = await distanceSummary(handle);
    if (!summary?.canMergeInto(reducer)) return false;
    summary.mergeInto(reducer);
    return true;
  });
  distanceSummaryWork = work.catch(() => {});
  return work;
}
export async function plotBrowserDistance(
  handle: BrowserDistanceHandle,
  start: number,
  end: number,
  budget: number,
  slotUs?: string,
  summarized = false,
): Promise<MonitorPoint[]> {
  if (handle.profile.state.intervals === 0) return [];
  const reducer = new InspectionPlotQuery('distanceMeters', secondsUs(start), secondsUs(end), budget, 16384, slotUs);
  const spans = profileSpans(handle.profile);
  const candidate = TILE_SECONDS.findIndex(size => (end - start) / size <= budget / 4);
  const level = slotUs === undefined ? (candidate < 0 ? TILE_SECONDS.length - 1 : candidate) : tileLevelWithin(slotUs);
  const [before, after] = await Promise.all([neighbor(handle, start, 'next'), neighbor(handle, end, 'next', true)]);
  if (before)
    boundaries(before)
      .filter(p => p.original.elapsedSeconds < start)
      .forEach(p => reducer.appendDistance(p.boundary));
  const descend = async (level: number, first: number, last: number): Promise<void> => {
    if (last < first) return;
    if (last - first > 2048) throw new Error('Distance chart tile request exceeds its bound');
    const size = TILE_SECONDS[Math.max(0, level)]!;
    const tiles =
      level < 0
        ? []
        : await browserTransaction(['distance-tiles'], 'readonly', tx =>
            Promise.all(
              spans.map(span => {
                const prefix = spanKey(handle.profile, span);
                return idbRequest<DistanceTile[]>(
                  tx
                    .objectStore('distance-tiles')
                    .getAll(
                      IDBKeyRange.bound(
                        [...prefix, level, browserIndexKey(String(first))],
                        [...prefix, level, browserIndexKey(String(last)), []],
                      ),
                      2049,
                    ),
                );
              }),
            ),
          );
    for (let bucket = first; bucket <= last; bucket++) {
      const index = spans.findIndex(
        (span, index) =>
          int64(span.startUs) <= BigInt(secondsUs(bucket * size)) &&
          (index === spans.length - 1 || int64(span.endUs) >= BigInt(secondsUs((bucket + 1) * size))),
      );
      const tile =
        index < 0
          ? undefined
          : tiles[index]?.find(tile => tile.bucket === bucket && tile.revisionKey <= spans[index]!.revisionKey);
      let aggregate: InspectionPlotCache | undefined;
      if (tile?.plot && tile.revisionKey <= handle.profile.revisionKey) {
        try {
          aggregate = new InspectionPlotCache(tile.plot!);
        } catch {
          aggregate = undefined;
        }
      }
      if (aggregate && reducer.canMerge(aggregate)) reducer.merge(aggregate);
      else if (level > 0) {
        const child = TILE_SECONDS[level - 1]!;
        await descend(
          level - 1,
          Math.floor(Math.max(start, bucket * size) / child),
          Math.floor(Math.min(end, (bucket + 1) * size - 0.000001) / child),
        );
      } else {
        const rows = await browserTransaction(['distance-intervals'], 'readonly', async tx =>
          (
            await Promise.all(
              spans.map(async span => {
                if (int64(span.endUs) < BigInt(secondsUs(bucket * size))) return [];
                const rows = await ownedDistanceRows(
                  tx,
                  spanKey(handle.profile, span),
                  bucket * size,
                  (bucket + 1) * size,
                );
                return rows.filter(row => spanOwns(span, row));
              }),
            )
          ).flat(),
        );
        rows
          .flatMap(boundaries)
          .filter(
            p =>
              p.original.elapsedSeconds >= Math.max(start, bucket * size) &&
              p.original.elapsedSeconds <= end &&
              p.original.elapsedSeconds < (bucket + 1) * size,
          )
          .sort((a, b) => a.original.elapsedSeconds - b.original.elapsedSeconds)
          .forEach(p => reducer.appendDistance(p.boundary));
      }
    }
  };
  const size = TILE_SECONDS[Math.max(0, level)]!;
  if (!(summarized && slotUs !== undefined && (await mergeDistanceSummary(handle, reducer))))
    await descend(level, Math.floor(start / size), Math.floor(end / size));
  if (after)
    boundaries(after)
      .filter(p => p.original.elapsedSeconds > end)
      .forEach(p => reducer.appendDistance(p.boundary));
  let previous: number | undefined;
  return reducer.result().map(value => {
    const startsSegment = value.segment !== previous;
    previous = value.segment;
    return { ...inspectionPoint(value.original), startsSegment, derived: true };
  });
}
