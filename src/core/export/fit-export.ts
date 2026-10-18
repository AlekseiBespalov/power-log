import type { BatteryConsumptionSource } from '../consumption';
import type { DistanceSource } from '../distance';
import { fitClock, parseStartedAt, type FitClock } from './clock';
import { DistanceRanges } from './fit/distance';
import { analyticsValid } from '../../../shared/js';
import { FitGeometry } from './fit/geometry';
import { HealthDiscovery, type HealthPlan, type HealthOriginSelection } from './fit/health';
import { FIT_RECORD_LIMIT, WitnessSeconds, needsPlanning, noRecords, planRecordInterval } from './fit/planner';
import {
  DistanceStream,
  GpsStream,
  HeartRateStream,
  RecordEmitter,
  TelemetryStream,
  lifecycleGroups,
  mergeWitnesses,
  type WitnessStream,
} from './fit/records';
import {
  RideStatistics,
  lifecycleWriter,
  writeActivity,
  writeFileId,
  writeSession,
  type SummaryInput,
} from './fit/summaries';
import { readAnalyticsTimeline } from './analytics-timeline';
import { FIT_MAX_MESSAGE_INDEX, FitWriter } from './fit/writer';
import { ExportReads, createSlicer, exportCancelled, readProjection, type ProjectionPage } from './pages';
import { RunTracker, type Timeline } from './timeline';
import {
  ExportError,
  type ExportCommitResult,
  type ExportContext,
  type FitPowerSource,
  type ExportDistanceProfile,
  type ExportOpenResult,
  type ExportSink,
  type ExportSource,
  type OpenExportSink,
  type Producer,
  type ProducerSet,
} from './types';

export interface FitExportRequest {
  readonly rideId: string;
  readonly context: ExportContext;
  readonly distanceSource?: DistanceSource;
  readonly powerSource?: FitPowerSource;
  readonly signal?: AbortSignal;
}

export interface FitExportTuning {
  readonly recordLimit?: number;
  readonly recordInterval?: number;
}

export interface FitExportResult extends ExportCommitResult {
  readonly records: number;
  readonly recordInterval: number;
  readonly health: HealthPlan;
}

const PRODUCER_SETS: readonly (readonly string[])[] = [[], ['phone'], ['watch'], ['phone', 'watch']];

const invalidSession = (problem: string) =>
  new ExportError('page', `The ride data could not be read for export: its ${problem} are not valid.`);

function producerSet(value: unknown, stream: string): ProducerSet {
  if (
    Array.isArray(value) &&
    PRODUCER_SETS.some(set => set.length === value.length && set.every((p, i) => value[i] === p))
  )
    return [...value] as ProducerSet;
  throw invalidSession(`${stream} sources`);
}

function distanceProfile(value: unknown): ExportDistanceProfile | null {
  if (value === null) return null;
  const profile = (typeof value === 'object' ? value : {}) as Partial<Record<string, unknown>>;
  const valid =
    (profile.source === 'controller' && profile.kind === 'controller') ||
    (profile.kind === 'gps' &&
      (profile.source === 'gps:phone' || profile.source === 'gps:watch') &&
      profile.producer === profile.source.slice(4));
  if (!valid) throw invalidSession('distance settings');
  return value as ExportDistanceProfile;
}

interface FitSession {
  readonly source: ExportSource;
  readonly session: string;
  readonly reads: ExportReads;
  readonly timeline: Timeline;
}

function countGoodFixes(page: ProjectionPage<'gpsDiscovery'>, good: Record<Producer, number>): void {
  const { producer, horizontalAccuracyM } = page.columns;
  for (let i = 0; i < page.rows; i++) {
    const accuracy = horizontalAccuracyM ? horizontalAccuracyM[i]! : NaN;
    const source = producer?.[i];
    if ((source === 'phone' || source === 'watch') && analyticsValid('horizontalAccuracyM', accuracy, null))
      good[source]++;
  }
}

async function discoverGps(
  input: FitSession,
  present: ProducerSet,
  profile: ExportDistanceProfile | null,
  owner: Producer,
): Promise<Producer | null> {
  if (present.length === 0) return null;
  const fixed = profile?.kind === 'gps' ? profile.producer : null;
  const counting = fixed === null && present.length > 1;
  if (!counting) return fixed ?? present[0]!;
  const good: Record<Producer, number> = { phone: 0, watch: 0 };
  for await (const page of readProjection(input.source, input.session, 'gpsDiscovery', input.reads)) {
    if (counting) countGoodFixes(page, good);
  }
  const other: Producer = owner === 'watch' ? 'phone' : 'watch';
  return good[owner] > 0 ? owner : good[other] > 0 ? other : null;
}

async function discoverHealth(
  input: FitSession,
  owner: Producer,
  origins: HealthOriginSelection | null,
): Promise<HealthPlan> {
  const discovery = new HealthDiscovery(owner, input.timeline.end, origins);
  for await (const page of readProjection(input.source, input.session, 'healthFit', input.reads)) discovery.add(page);
  discovery.prepareConsumption();
  for await (const page of readProjection(input.source, input.session, 'healthFit', input.reads))
    discovery.addConsumption(page);
  return discovery.plan();
}

function retainedHealthOrigins(opened: ExportOpenResult): HealthOriginSelection | null {
  const health = opened.metadata.health as typeof opened.metadata.health & {
    readback?: {
      healthOrigins?: { heartRate?: unknown; activeEnergy?: unknown; basalEnergy?: unknown };
      basalUnavailableReason?: unknown;
    };
  };
  const origins = health.readback?.healthOrigins;
  if (origins === undefined) return null;
  const origin = (value: unknown): string | null => {
    if (value === undefined || value === null) return null;
    if (typeof value !== 'string') throw invalidSession('Health origins');
    return value;
  };
  return {
    heartRate: origin(origins.heartRate),
    activeEnergy: origin(origins.activeEnergy),
  };
}

interface Streams {
  readonly list: WitnessStream[];
  readonly geometry: FitGeometry | null;
}

function streams(
  input: FitSession,
  clock: FitClock,
  profile: ExportDistanceProfile | null,
  gps: Producer | null,
  health: HealthPlan,
  statistics: RideStatistics | null,
  ranges: DistanceRanges | null,
  consumptionSource: BatteryConsumptionSource,
  powerSource: FitPowerSource,
): Streams {
  const { source, session, reads, timeline } = input;
  const platform = reads.options.platform;
  const list: WitnessStream[] = [];
  if (profile)
    list.push(
      new DistanceStream(readProjection(source, session, 'distance', reads), clock, timeline, ranges, profile.kind),
    );
  list.push(
    new TelemetryStream(
      readProjection(source, session, 'telemetry', reads),
      clock,
      timeline.cursor('telemetry'),
      new RunTracker(platform, 'telemetry'),
      statistics,
      consumptionSource,
      powerSource,
    ),
  );
  let geometry: FitGeometry | null = null;
  if (gps) {
    geometry = new FitGeometry();
    list.push(
      new GpsStream(
        readProjection(source, session, 'gps', reads),
        clock,
        timeline.cursor('gps'),
        new RunTracker(platform, 'gps'),
        gps,
        geometry,
        statistics,
      ),
    );
  }
  if (health.heartRate)
    list.push(
      new HeartRateStream(
        readProjection(source, session, 'healthFit', reads),
        clock,
        timeline.cursor('healthFit'),
        health.heartRate,
        statistics,
      ),
    );
  return { list, geometry };
}

export async function exportFit(
  source: ExportSource,
  openSink: OpenExportSink,
  request: FitExportRequest,
  tuning: FitExportTuning = {},
): Promise<FitExportResult> {
  const { rideId, context, distanceSource, powerSource = 'rider', signal } = request;
  if (tuning.recordInterval !== undefined && !(Number.isInteger(tuning.recordInterval) && tuning.recordInterval >= 1))
    throw new RangeError('A FIT record interval is a whole number of seconds');
  if (signal?.aborted) throw exportCancelled();
  const slicer = createSlicer(signal);
  const reads = new ExportReads({ platform: context.platform, kind: 'fit', slicer, signal });
  const opened: ExportOpenResult = await source.open({
    rideId,
    kind: 'fit',
    context,
    ...(distanceSource === undefined ? {} : { distanceSource }),
  });
  const session = opened.session;
  let sink: ExportSink | undefined;
  try {
    if (signal?.aborted) throw exportCancelled();
    const consumptionSource = opened.metadata.batteryConsumptionSource;
    if (consumptionSource !== 'controller' && consumptionSource !== 'calculated')
      throw invalidSession('battery consumption source');
    const profile = distanceProfile(opened.distanceProfile);
    const producers = opened.producers as Partial<Record<string, unknown>> | null;
    const gpsProducers = producerSet(producers?.gps, 'GPS');
    const healthProducers = producerSet(producers?.health, 'Health');
    const timeline = await readAnalyticsTimeline(source, session, opened.elapsedEnd, reads);
    if (timeline.laps.length > FIT_MAX_MESSAGE_INDEX + 1)
      throw new ExportError('limit', 'A FIT file can hold at most 4096 laps, and this ride has more.');
    const start = parseStartedAt(opened.metadata.startedAt);
    const owner: Producer = opened.metadata.watchEnabled ? 'watch' : 'phone';
    const input: FitSession = { source, session, reads, timeline };
    const gps = await discoverGps(input, gpsProducers, profile, owner);
    const health: HealthPlan =
      healthProducers.length > 0
        ? await discoverHealth(input, owner, retainedHealthOrigins(opened))
        : {
            heartRate: null,
            calories: NaN,
            basal: { valueKcal: null, reason: 'missingOperand', selectedOrigin: null },
          };
    const clock = fitClock(start);
    const groups = lifecycleGroups(timeline);
    let interval = tuning.recordInterval ?? 1;
    if (tuning.recordInterval === undefined && needsPlanning(clock, timeline.end, tuning.recordLimit)) {
      const witnesses = new WitnessSeconds();
      const timing = streams(input, clock, profile, gps, health, null, null, consumptionSource, powerSource);
      await mergeWitnesses(timing.list, groups, timeline.intervals, witnesses, slicer);
      interval = (await planRecordInterval(witnesses, clock.second(0), slicer, tuning.recordLimit ?? FIT_RECORD_LIMIT))
        .interval;
    }
    sink = await openSink('fit', context);
    if (signal?.aborted) throw exportCancelled();
    const writer = new FitWriter(sink, slicer);
    await writer.begin();
    const ranges = profile ? new DistanceRanges(profile.kind, timeline) : null;
    const summary: SummaryInput = { writer, clock, timeline, ranges };
    writeFileId(summary);
    writer.developerMetadata(consumptionSource);
    const statistics = new RideStatistics(timeline.endUs);
    const emission = streams(input, clock, profile, gps, health, statistics, ranges, consumptionSource, powerSource);
    const emitter = new RecordEmitter(writer, clock, interval, lifecycleWriter(summary));
    await mergeWitnesses(emission.list, groups, timeline.intervals, emitter, slicer);
    emitter.finish();
    if (emitter.records === 0) throw noRecords();
    writeSession({
      ...summary,
      indoor: opened.metadata.indoor,
      statistics,
      geometry: emission.geometry,
      calories: health.calories,
    });
    writeActivity(summary);
    await writer.finish();
    await source.close(session);
    if (signal?.aborted) throw exportCancelled();
    const committed = await sink.commit(`power-log-${rideId}.fit`);
    return { ...committed, records: emitter.records, recordInterval: interval, health };
  } catch (error) {
    await sink?.abort().catch(() => undefined);
    await source.close(session).catch(() => undefined);
    throw error;
  }
}
