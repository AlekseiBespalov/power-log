import {
  InspectionPlotQuery,
  inspectionPlotBuckets,
  inspectionPlotPointLimit,
  inspectionPlotSlotUs,
} from '../../shared/js';
import type { BatteryConsumptionSource } from '../../src/core/consumption';
import type { MonitorPoint, MonitorSource } from '../../src/core/monitor';
import { inspectionPoint } from '../../src/core/monitor-inspection';
import { browserInspectionItem } from '../../src/services/browser-analytics';
import { browserEnergyPerDistance, ensureBrowserDistance } from '../../src/services/browser-distance-store';
import {
  BROWSER_PAGE,
  BrowserRideStore,
  browserRideStore as store,
  type OriginalCursor,
  type RideLocationRow,
  type RideRow,
} from '../../src/services/browser-ride-store';
import { secondsUs, timestampUs } from '../../src/services/browser-storage-keys';
import { browserWorkoutMonitorSource } from '../../src/services/browser-workout-monitor';
import { browserRow } from '../support/browser-ride';

const TELEMETRY = ['humanPowerW', 'cadenceRpm', 'controllerSpeedMps', 'batteryVoltageV'];
const LOCATION = ['speedMps', 'courseDegrees', 'altitudeMeters'];
const DERIVED = ['distanceMeters', 'gpsDistanceMeters', 'calculatedEnergyWh'];
const METRICS = [...TELEMETRY, ...LOCATION, ...DERIVED];
const INT64_MIN = '-9223372036854775808',
  INT64_MAX = '9223372036854775807';
const base = Date.UTC(2026, 0, 1);
const stamp = (seconds: number) => new Date(base + seconds * 1000).toISOString();

const reads = new Map<string, number>();
function countReads() {
  if (reads.has('installed')) return;
  reads.set('installed', 1);
  const add = (target: IDBObjectStore | IDBIndex, amount: number) => {
    const name = target instanceof IDBIndex ? target.objectStore.name : target.name;
    reads.set(name, (reads.get(name) ?? 0) + amount);
  };
  for (const prototype of [IDBObjectStore.prototype, IDBIndex.prototype] as unknown as Record<string, unknown>[]) {
    const getAll = prototype.getAll as (this: IDBObjectStore, ...args: unknown[]) => IDBRequest<unknown[]>;
    prototype.getAll = function (this: IDBObjectStore, ...args: unknown[]) {
      const request = getAll.apply(this, args);
      request.addEventListener('success', () => add(this, request.result.length));
      return request;
    };
    const openCursor = prototype.openCursor as (this: IDBObjectStore, ...args: unknown[]) => IDBRequest<unknown>;
    prototype.openCursor = function (this: IDBObjectStore, ...args: unknown[]) {
      const request = openCursor.apply(this, args);
      request.addEventListener('success', () => add(this, request.result ? 1 : 0));
      return request;
    };
  }
}
function readsDuring<T>(work: () => Promise<T>): Promise<[T, Record<string, number>]> {
  const before = new Map(reads);
  return work().then(value => [
    value,
    Object.fromEntries(
      [...reads]
        .filter(([name]) => name !== 'installed')
        .map(([name, count]) => [name, count - (before.get(name) ?? 0)]),
    ),
  ]);
}

function plotted(query: InspectionPlotQuery): MonitorPoint[] {
  let previous: number | undefined;
  return query.result().map(value => {
    const startsSegment = value.segment !== previous;
    previous = value.segment;
    return { ...inspectionPoint(value.original), startsSegment };
  });
}
async function originals<T extends RideRow | RideLocationRow>(
  read: (after: OriginalCursor | null) => Promise<T[]>,
): Promise<T[]> {
  const rows: T[] = [];
  let after: OriginalCursor | null = null;
  while (true) {
    const page = await read(after);
    rows.push(...page);
    if (page.length < BROWSER_PAGE) return rows;
    const tail = page.at(-1)!;
    after = [tail.elapsedUs, tail.producer, tail.producerSequence];
  }
}
async function reference(id: string, revision: string, endSeconds: number, buckets: number, metrics: string[]) {
  const [samples, locations] = await Promise.all([
    originals(after => store.pageExact(id, INT64_MIN, INT64_MAX, after, BROWSER_PAGE, revision)),
    originals(after => store.locationPage(id, INT64_MIN, INT64_MAX, after, revision)),
  ]);
  const endUs = secondsUs(endSeconds),
    count = inspectionPlotBuckets(buckets, metrics.length),
    slot = inspectionPlotSlotUs('0', endUs, count) ?? undefined;
  const series: Record<string, MonitorPoint[]> = {};
  for (const metric of metrics.filter(metric => !DERIVED.includes(metric))) {
    const query = new InspectionPlotQuery(metric, '0', endUs, count, inspectionPlotPointLimit(metrics.length), slot);
    for (const row of TELEMETRY.includes(metric) ? samples : locations)
      query.append(browserInspectionItem(row), true, false);
    series[metric] = plotted(query);
  }
  return series;
}
async function plot(source: MonitorSource, revision: string, endSeconds: number, buckets: number, metrics: string[]) {
  const result = await source.readPlot({
    generation: 1,
    expectedRevision: revision,
    metrics,
    startSeconds: 0,
    endSeconds: endSeconds + 5,
    buckets,
  });
  if (result.status !== 'ok') throw new Error('Unexpected retry');
  return result.series;
}
function compare(label: string, actual: MonitorPoint[] | undefined, expected: MonitorPoint[] | undefined) {
  if (!expected?.length) return [`${label} empty`];
  return JSON.stringify(actual) === JSON.stringify(expected) ? [] : [label];
}

async function syntheticRide(
  distanceSource: 'controller' | 'gps:phone' = 'controller',
  batteryConsumptionSource: BatteryConsumptionSource = 'calculated',
) {
  const token = 'owner';
  let ride = await store.begin(
    token,
    { indoor: false, recordGPS: true, sampleHz: 2, distanceSource, batteryConsumptionSource },
    stamp(0),
  );
  const id = ride.id;
  let sequence = 0,
    controller = 2,
    browser = 2;
  const state = { interruption: 0, newSamples: 0, newLocations: 0 };
  const telemetry = (
    seconds: number,
    options: { active?: boolean; local?: number; producer?: number } = {},
  ): RideRow => {
    const local = options.local ?? (sequence += 2);
    const row = {
      ...browserRow(seconds, local, stamp(seconds)),
      recordingId: id,
      producerSequence: String(options.producer ?? (controller += 2)),
      active: options.active ?? true,
      interruptionIndex: state.interruption,
    };
    if (local % 14 === 6) return row;
    return {
      ...row,
      controllerSpeedMps: 5,
      speedRaw: 18,
      controllerModel: 'X6',
      controllerProtocol: '5.3',
      firmwareLabel: '20260101',
    };
  };
  const location = (seconds: number): RideLocationRow => {
    const altitude = 400 + Math.round(Math.sin(seconds / 30) * 50) / 10;
    const producer = browser++;
    return {
      recordingId: id,
      eventId: crypto.randomUUID(),
      producer: 'browser',
      producerSequence: String(producer),
      elapsedUs: secondsUs(seconds),
      queryUs: timestampUs(stamp(seconds)),
      timestamp: stamp(seconds),
      originalTimestamp: stamp(seconds),
      elapsedSeconds: seconds,
      originalElapsedSeconds: seconds,
      acquiredAtSeconds: seconds,
      clockEpoch: 'gps',
      active: true,
      interval: 0,
      distanceBarrier: false,
      latitude: 47 + seconds * 0.00001,
      longitude: 8,
      horizontalAccuracyM: 5,
      speedMps: producer % 11 === 0 ? -1 : 3 + Math.round(Math.sin(seconds / 9) * 100) / 100,
      courseDegrees: (producer * 37) % 360,
      originalAltitudeDatum: 'msl',
      originalAltitudeMeters: altitude,
      originalVerticalAccuracyM: 3,
      altitudeMeters: altitude,
      verticalAccuracyM: 3,
    };
  };
  return {
    id,
    state,
    record: () => ride,
    nextLocal: () => sequence + 2,
    telemetry,
    location,
    async append(rows: RideRow[]) {
      for (let offset = 0; offset < rows.length; offset += 64) {
        const batch = rows.slice(offset, offset + 64);
        const end = Math.max(ride.elapsedSeconds, ...batch.map(row => row.elapsedSeconds));
        ride = await store.append(id, token, batch, end, end, stamp(end));
      }
      state.newSamples += rows.length;
    },
    async appendLocations(rows: RideLocationRow[]) {
      const end = Math.max(ride.elapsedSeconds, ...rows.map(row => row.elapsedSeconds));
      ride = await store.appendLocations(id, token, rows, end, end, stamp(end));
      state.newLocations += rows.length;
    },
    async transition(action: 'pause' | 'resume' | 'lap', time: number) {
      ride = await store.transition(id, token, action, time, time, stamp(time), String(browser++));
    },
  };
}

export type ScenarioStep = {
  label: string;
  aligned: boolean;
  telemetryCount: number;
  locationCount: number;
  newSamples: number;
  newLocations: number;
  reads: Record<string, number>;
  mismatches: string[];
};

export async function wholeRideScenario(): Promise<ScenarioStep[]> {
  countReads();
  const ride = await syntheticRide();
  const live = browserWorkoutMonitorSource(ride.id, true, store);
  const fresh = browserWorkoutMonitorSource(ride.id, false, new BrowserRideStore());
  let time = 0,
    gap = 0,
    previous: RideRow | undefined;
  const steps: ScenarioStep[] = [];
  for (let step = 0; step < 40; step++) {
    let label = `step ${step}`;
    if (step === 11 && previous) {
      await ride.append([ride.telemetry(previous.elapsedSeconds)]);
      label += ' equal-time row after';
    }
    if (step === 13 && previous) {
      await ride.append([
        ride.telemetry(previous.elapsedSeconds, {
          local: previous.sequence - 1,
          producer: Number(previous.producerSequence) - 1,
        }),
      ]);
      label += ' equal-time row before';
    }
    if (step === 2) {
      gap = time;
      time += 12;
      label += ' gap';
    }
    if (step === 6) {
      ride.state.interruption++;
      label += ' interruption';
    }
    if (step === 4) {
      await ride.transition('pause', time);
      label += ' paused';
    }
    if (step === 14) {
      await ride.transition('lap', time);
      label += ' lap';
    }
    const start = time;
    const rows = Array.from({ length: 50 }, (_, index) => ride.telemetry(start + index / 2, { active: step !== 4 }));
    await ride.append(rows);
    time += 25;
    if (step === 4) await ride.transition('resume', time);
    else {
      const fixes = Array.from({ length: 25 }, (_, index) => ride.location(start + index));
      if (step === 9) fixes.splice(5, 0, { ...ride.location(start + 5), courseDegrees: 359 });
      await ride.appendLocations(fixes);
    }
    if (step === 8) {
      await ride.append([ride.telemetry(gap + 3), ride.telemetry(gap + 6)]);
      label += ' late rows';
    }
    previous = rows.at(-1);
    const record = ride.record();
    await ensureBrowserDistance(ride.id);
    const [liveSeries, liveReads] = await readsDuring(() =>
      plot(live, record.revision, record.elapsedSeconds, 161, METRICS),
    );
    const mismatches: string[] = [];
    const expected = await reference(ride.id, record.revision, record.elapsedSeconds, 161, METRICS);
    for (const metric of [...TELEMETRY, ...LOCATION])
      mismatches.push(...compare(`B=161 ${metric} live`, liveSeries[metric], expected[metric]));
    const compared = [5, 8, 13, 23, 31, 39].includes(step);
    if (compared)
      for (const buckets of [4, 33, 161, 512]) {
        const wanted = await reference(ride.id, record.revision, record.elapsedSeconds, buckets, METRICS);
        const liveRead =
          buckets === 161 ? liveSeries : await plot(live, record.revision, record.elapsedSeconds, buckets, METRICS);
        const freshRead = await plot(fresh, record.revision, record.elapsedSeconds, buckets, METRICS);
        for (const metric of METRICS) {
          const target = DERIVED.includes(metric) ? freshRead[metric] : wanted[metric];
          mismatches.push(...compare(`B=${buckets} ${metric} live`, liveRead[metric], target));
          mismatches.push(...compare(`B=${buckets} ${metric} fresh`, freshRead[metric], target));
        }
      }
    if (compared) {
      const single = await reference(ride.id, record.revision, record.elapsedSeconds, 512, ['humanPowerW']);
      mismatches.push(
        ...compare(
          'single metric live',
          (await plot(live, record.revision, record.elapsedSeconds, 512, ['humanPowerW'])).humanPowerW,
          single.humanPowerW,
        ),
      );
    }
    steps.push({
      label,
      aligned:
        inspectionPlotSlotUs(
          '0',
          secondsUs(Math.max(10, record.elapsedSeconds)),
          inspectionPlotBuckets(161, METRICS.length),
        ) !== null,
      telemetryCount: record.telemetryCount,
      locationCount: record.locationCount,
      newSamples: ride.state.newSamples,
      newLocations: ride.state.newLocations,
      reads: liveReads,
      mismatches,
    });
    ride.state.newSamples = 0;
    ride.state.newLocations = 0;
  }
  return steps;
}

export async function unownedIntervalScenario(): Promise<string[]> {
  const ride = await syntheticRide();
  const live = browserWorkoutMonitorSource(ride.id, true, store);
  const fresh = browserWorkoutMonitorSource(ride.id, false, new BrowserRideStore());
  const metrics = ['distanceMeters', 'calculatedEnergyWh'];
  const mismatches: string[] = [];
  for (let step = 0; step < 4; step++) {
    const rows = Array.from({ length: 20 }, (_, index) => ride.telemetry(step * 10 + index / 2));
    await ride.append(rows);
    const last = rows.at(-1)!;
    if (step === 1)
      await ride.append([ride.telemetry(last.elapsedSeconds, { producer: Number(last.producerSequence) - 1 })]);
    const record = ride.record();
    await ensureBrowserDistance(ride.id);
    const liveRead = await plot(live, record.revision, record.elapsedSeconds, 8, metrics);
    const freshRead = await plot(fresh, record.revision, record.elapsedSeconds, 8, metrics);
    for (const metric of metrics)
      mismatches.push(...compare(`step ${step} ${metric}`, liveRead[metric], freshRead[metric]));
  }
  return mismatches;
}

export type EnergyStep = {
  label: string;
  telemetryCount: number;
  newSamples: number;
  newLocations: number;
  reads: Record<string, number>;
  mismatches: string[];
};

export async function energyScenario(
  distanceSource: 'controller' | 'gps:phone',
  batteryConsumptionSource: BatteryConsumptionSource,
): Promise<EnergyStep[]> {
  countReads();
  const ride = await syntheticRide(distanceSource, batteryConsumptionSource);
  const source = browserWorkoutMonitorSource(ride.id, true, store);
  let time = 0,
    gap = 0,
    previous: RideRow | undefined;
  const steps: EnergyStep[] = [];
  for (let step = 0; step < 20; step++) {
    let label = `step ${step}`;
    if (step === 11 && previous) {
      await ride.append([
        ride.telemetry(previous.elapsedSeconds, {
          local: previous.sequence - 1,
          producer: Number(previous.producerSequence) - 1,
        }),
      ]);
      label += ' equal-time row before';
    }
    if (step === 2) {
      gap = time;
      time += 12;
      label += ' gap';
    }
    if (step === 6) {
      ride.state.interruption++;
      label += ' interruption';
    }
    if (step === 4) {
      await ride.transition('pause', time);
      label += ' paused';
    }
    if (step === 14) {
      await ride.transition('lap', time);
      label += ' lap';
    }
    const start = time;
    const ahead = step === 16 ? ride.telemetry(start + 5.25, { local: ride.nextLocal() + 101 }) : undefined;
    const rows = Array.from({ length: 50 }, (_, index) => ride.telemetry(start + index / 2, { active: step !== 4 }));
    if (ahead) {
      rows.splice(11, 0, ahead);
      label += ' out-of-order sample';
    }
    if (step === 17) label += ' after out-of-order sample';
    await ride.append(rows);
    time += 25;
    if (step === 4) await ride.transition('resume', time);
    else await ride.appendLocations(Array.from({ length: 25 }, (_, index) => ride.location(start + index)));
    if (step === 8) {
      await ride.append([ride.telemetry(gap + 3), ride.telemetry(gap + 6)]);
      label += ' late rows';
    }
    previous = rows.at(-1);
    const record = ride.record();
    const end = record.elapsedSeconds + 0.7;
    const handle = await ensureBrowserDistance(ride.id, 'auto', record);
    const mismatches: string[] = [];
    let reads: Record<string, number> = {};
    for (const smoothing of [0, 5]) {
      const [resumed, counted] = await readsDuring(() =>
        browserEnergyPerDistance(record, handle, 0, end, null, smoothing),
      );
      if (smoothing === 0) reads = counted;
      const full = await browserEnergyPerDistance(record, handle, 0, end, 128, smoothing);
      const strip = ({ series: _series, ...rest }: typeof full) => JSON.stringify(rest);
      if (strip(resumed) !== strip(full)) mismatches.push(`smoothing ${smoothing}`);
      if (smoothing === 0) {
        const stats = await source.rangeStats({
          generation: 1,
          expectedRevision: record.revision,
          metrics: ['batteryWhPerKm', 'riderWhPerKm'],
          startSeconds: 0,
          endSeconds: end,
        });
        if (stats.status !== 'ok') throw new Error('Unexpected retry');
        if (JSON.stringify(stats.statistics) !== JSON.stringify(full.statistics)) mismatches.push('range statistics');
      }
    }
    steps.push({
      label,
      telemetryCount: record.telemetryCount,
      newSamples: ride.state.newSamples,
      newLocations: ride.state.newLocations,
      reads,
      mismatches,
    });
    ride.state.newSamples = 0;
    ride.state.newLocations = 0;
  }
  return steps;
}

export type SeriesStep = EnergyStep & { aligned: boolean; gridMatters: boolean };

export async function energySeriesScenario(distanceSource: 'controller' | 'gps:phone'): Promise<SeriesStep[]> {
  countReads();
  const ride = await syntheticRide(distanceSource, 'controller');
  const source = browserWorkoutMonitorSource(ride.id, true, store);
  let time = 0,
    gap = 0,
    previous: RideRow | undefined;
  const steps: SeriesStep[] = [];
  for (let step = 0; step < 24; step++) {
    let label = `step ${step}`;
    if (step === 11 && previous) {
      await ride.append([ride.telemetry(previous.elapsedSeconds)]);
      label += ' equal-time row after';
    }
    if (step === 13 && previous) {
      await ride.append([
        ride.telemetry(previous.elapsedSeconds, {
          local: previous.sequence - 1,
          producer: Number(previous.producerSequence) - 1,
        }),
      ]);
      label += ' equal-time row before';
    }
    if (step === 2) {
      gap = time;
      time += 12;
      label += ' gap';
    }
    if (step === 6) {
      ride.state.interruption++;
      label += ' interruption';
    }
    if (step === 4) {
      await ride.transition('pause', time);
      label += ' paused';
    }
    if (step === 14) {
      await ride.transition('lap', time);
      label += ' lap';
    }
    const start = time;
    const rows = Array.from({ length: 50 }, (_, index) => ride.telemetry(start + index / 2, { active: step !== 4 }));
    await ride.append(rows);
    time += 25;
    if (step === 4) await ride.transition('resume', time);
    else await ride.appendLocations(Array.from({ length: 25 }, (_, index) => ride.location(start + index)));
    if (step === 8) {
      await ride.append([ride.telemetry(gap + 3), ride.telemetry(gap + 6)]);
      label += ' late rows';
    }
    previous = rows.at(-1);
    const record = ride.record();
    const end = Math.max(10, record.elapsedSeconds);
    const slotUs = (buckets: number) => inspectionPlotSlotUs('0', secondsUs(end), buckets) ?? null;
    const mismatches: string[] = [];
    let reads: Record<string, number> = {},
      fresh: Awaited<ReturnType<typeof browserEnergyPerDistance>> | undefined;
    for (const smoothing of [0, 5]) {
      for (const buckets of step % 6 === 5 ? [161, 33, 512] : [161]) {
        const whole = (incremental: boolean) => ({ slotUs: slotUs(buckets), incremental });
        const [cached, counted] = await readsDuring(() =>
          browserEnergyPerDistance(record, null, 0, end, buckets, smoothing, whole(true)),
        );
        const full = await browserEnergyPerDistance(record, null, 0, end, buckets, smoothing, whole(false));
        if (smoothing === 0 && buckets === 161) {
          reads = counted;
          fresh = full;
        }
        if (JSON.stringify(cached) !== JSON.stringify(full)) mismatches.push(`B=${buckets} smoothing ${smoothing}`);
      }
    }
    const plot = await source.readPlot({
      generation: 1,
      expectedRevision: record.revision,
      metrics: ['batteryWhPerKm', 'riderWhPerKm'],
      startSeconds: 0,
      endSeconds: record.elapsedSeconds + 0.7,
      buckets: 161,
    });
    if (plot.status !== 'ok') throw new Error('Unexpected retry');
    for (const metric of ['batteryWhPerKm', 'riderWhPerKm'] as const)
      if (JSON.stringify(plot.series[metric]) !== JSON.stringify(fresh!.series[metric]))
        mismatches.push(`chart ${metric}`);
    const exact = step % 6 === 5 ? await browserEnergyPerDistance(record, null, 0, end, 161, 0) : fresh;
    steps.push({
      label,
      aligned: slotUs(161) !== null,
      gridMatters: JSON.stringify(exact!.series) !== JSON.stringify(fresh!.series),
      telemetryCount: record.telemetryCount,
      newSamples: ride.state.newSamples,
      newLocations: ride.state.newLocations,
      reads,
      mismatches,
    });
    ride.state.newSamples = 0;
    ride.state.newLocations = 0;
  }
  return steps;
}
