import {
  ENERGY_PER_DISTANCE_METRICS,
  energyAverageInterval,
  isEnergyPerDistanceMetric,
  type EnergyPerDistanceResult,
  type EnergySmoothingSeconds,
} from '../core/energy-per-distance';
import type {
  MonitorLatestRequest,
  MonitorPlotRequest,
  MonitorResult,
  MonitorSource,
  MonitorEnvelope,
} from '../core/monitor';
import { inspectionPlotBuckets } from '../../shared/js';
import { readConsumer, reads } from './read-scheduler';

export type EnergyPerDistanceReader = (request: MonitorPlotRequest) => Promise<MonitorResult<EnergyPerDistanceResult>>;

// Class sources keep their methods on the prototype, which object spread does not copy.
function forwarded(source: MonitorSource): MonitorSource {
  const mapMonotonicSeconds = source.mapMonotonicSeconds;
  return {
    ...source,
    ...(mapMonotonicSeconds
      ? { mapMonotonicSeconds: (seconds: number) => mapMonotonicSeconds.call(source, seconds) }
      : {}),
    describeSource: request => source.describeSource(request),
    readLatest: request => source.readLatest(request),
    readPlot: request => source.readPlot(request),
    inspectAt: request => source.inspectAt(request),
    rangeStats: request => source.rangeStats(request),
    changesSince: request => source.changesSince(request),
  };
}

/** One query adapter supplies the same chart, cursor and A–B behavior on every platform. */
export function withEnergyPerDistance(
  source: MonitorSource,
  read: EnergyPerDistanceReader,
  options: { preview?: boolean; now?: () => number } = {},
): MonitorSource {
  const now = options.now ?? (() => performance.now() / 1000);
  const consumer = readConsumer('energy-averages');
  let average:
    | { sourceId: string; revision: string; end: number; readAt: number; values: EnergyPerDistanceResult['averages'] }
    | undefined;
  let refresh: { sourceId: string; end: number } | undefined;
  const readDerived = (request: MonitorPlotRequest) =>
    read({
      generation: request.generation,
      expectedRevision: request.expectedRevision,
      signal: request.signal,
      metrics: [],
      startSeconds: request.startSeconds,
      endSeconds: request.endSeconds,
      buckets: request.buckets,
      pixelWidth: request.pixelWidth,
      energySmoothingSeconds: request.energySmoothingSeconds,
      energyTotals: request.energyTotals,
      energyCurrent: request.energyCurrent,
    });
  const raw = (metrics: string[]) => metrics.filter(metric => !isEnergyPerDistanceMetric(metric));
  const selected = <T>(values: Record<string, T>, metrics: string[]) =>
    Object.fromEntries(metrics.filter(isEnergyPerDistanceMetric).map(metric => [metric, values[metric]!]));
  const retry = (base: MonitorEnvelope) => ({ ...base, status: 'retry' as const });
  const matches = (base: MonitorEnvelope, result: MonitorEnvelope) =>
    base.revision === result.revision && base.sourceId === result.sourceId;
  const refreshAverages = (
    request: MonitorLatestRequest,
    base: MonitorEnvelope & { nowSeconds?: number },
    end: number,
  ) => {
    const job = { sourceId: base.sourceId, end };
    refresh = job;
    reads
      .schedule('statistics', consumer, 'averages', signal =>
        readDerived({
          ...request,
          signal,
          metrics: [],
          expectedRevision: base.revision,
          startSeconds: 0,
          endSeconds: base.nowSeconds,
          energySmoothingSeconds: 0,
          energyTotals: true,
        }),
      )
      .then(
        totals => {
          if (refresh !== job) return;
          refresh = undefined;
          if (totals.status === 'ok' && matches(base, totals))
            average = { sourceId: base.sourceId, revision: base.revision, end, readAt: now(), values: totals.averages };
        },
        () => {
          if (refresh === job) refresh = undefined;
        },
      );
  };
  return {
    ...forwarded(source),
    async describeSource(request) {
      const result = await source.describeSource(request);
      if (result.availableMetrics.includes('distanceMeters') || result.availableMetrics.includes('controllerSpeedMps'))
        return {
          ...result,
          availableMetrics: [...new Set([...result.availableMetrics, ...ENERGY_PER_DISTANCE_METRICS])],
        };
      return result;
    },
    async readLatest(request) {
      const has = request.metrics.some(isEnergyPerDistanceMetric);
      const metrics = has
        ? [...new Set([...raw(request.metrics), 'humanPowerW', 'motorInputPowerW'])]
        : request.metrics;
      const base = await source.readLatest({ ...request, metrics });
      if (!has || base.status !== 'ok') return base;
      let end = base.nowSeconds;
      if (end === undefined) {
        const description = await source.describeSource({ generation: request.generation, signal: request.signal });
        if (description.status !== 'ok' || !matches(base, description)) return retry(base);
        end = description.domain.end;
      }
      // Current readings only need bounded context; they never integrate the entire recording.
      const value = await readDerived({
        ...request,
        metrics: [],
        expectedRevision: base.revision,
        startSeconds: base.nowSeconds,
        endSeconds: base.nowSeconds,
        energyCurrent: true,
        energyTotals: false,
      });
      if (value.status !== 'ok' || !matches(base, value)) return retry(base);
      if (average && (average.sourceId !== base.sourceId || end < average.end)) average = undefined;
      if (refresh && (refresh.sourceId !== base.sourceId || end < refresh.end)) {
        refresh = undefined;
        reads.cancel(consumer);
      }
      const due =
        !average ||
        (average.revision !== base.revision && (!source.live || now() - average.readAt >= energyAverageInterval(end)));
      if (!options.preview && due && !refresh) refreshAverages(request, base, end);
      const acquired = Object.fromEntries(
        request.metrics.filter(isEnergyPerDistanceMetric).map(metric => {
          const power = metric === 'batteryWhPerKm' ? 'motorInputPowerW' : 'humanPowerW';
          const point = value.points[metric],
            original = base.points[power],
            time = base.liveAcquiredAt?.[power];
          return [
            metric,
            point && original && time != null
              ? time - Math.max(0, original.elapsedSeconds - point.elapsedSeconds)
              : null,
          ];
        }),
      );
      return {
        ...base,
        points: { ...base.points, ...selected(value.points, request.metrics) },
        averages: selected(average?.values ?? { batteryWhPerKm: null, riderWhPerKm: null }, request.metrics),
        averagesRevision: average?.revision,
        ...(refresh ? { averagesPending: true } : {}),
        liveAcquiredAt: { ...base.liveAcquiredAt, ...acquired },
      };
    },
    async readPlot(request) {
      const base = await source.readPlot({ ...request, metrics: raw(request.metrics) });
      if (!request.metrics.some(isEnergyPerDistanceMetric) || base.status !== 'ok') return base;
      const value = await readDerived({
        ...request,
        metrics: [],
        startSeconds: request.startSeconds ?? 0,
        buckets: inspectionPlotBuckets(
          Math.max(1, Math.min(512, Math.floor(request.buckets ?? 128))),
          Math.max(1, raw(request.metrics).length),
        ),
        energyTotals: false,
      });
      if (value.status !== 'ok' || !matches(base, value)) return retry(base);
      return { ...base, series: { ...base.series, ...selected(value.series, request.metrics) } };
    },
    async inspectAt(request) {
      const base = await source.inspectAt({
        ...request,
        metrics: raw(request.metrics),
        anchor: request.anchor && !isEnergyPerDistanceMetric(request.anchor.metric) ? request.anchor : undefined,
      });
      if (!request.metrics.some(isEnergyPerDistanceMetric) || base.status !== 'ok') return base;
      const value = await readDerived({
        ...request,
        metrics: [],
        startSeconds: request.seconds,
        endSeconds: request.seconds,
        energyTotals: false,
      });
      if (value.status !== 'ok' || !matches(base, value)) return retry(base);
      const points = { ...base.points, ...selected(value.endpoints.start, request.metrics) };
      return {
        ...base,
        points,
        gaps: Object.fromEntries(Object.entries(points).map(([metric, point]) => [metric, !point])),
      };
    },
    async rangeStats(request) {
      const base = await source.rangeStats({
        ...request,
        metrics: raw(request.metrics),
        startAnchor:
          request.startAnchor && !isEnergyPerDistanceMetric(request.startAnchor.metric)
            ? request.startAnchor
            : undefined,
        endAnchor:
          request.endAnchor && !isEnergyPerDistanceMetric(request.endAnchor.metric) ? request.endAnchor : undefined,
      });
      if (!request.metrics.some(isEnergyPerDistanceMetric) || base.status !== 'ok') return base;
      const value = await readDerived({ ...request, metrics: [], energyTotals: true });
      if (value.status !== 'ok' || !matches(base, value)) return retry(base);
      return {
        ...base,
        statistics: { ...base.statistics, ...selected(value.statistics, request.metrics) },
        ...(request.includeEndpoints
          ? {
              endpoints: {
                start: { ...base.endpoints?.start, ...selected(value.endpoints.start, request.metrics) },
                end: { ...base.endpoints?.end, ...selected(value.endpoints.end, request.metrics) },
              },
            }
          : {}),
      };
    },
    async changesSince(request) {
      const result = await source.changesSince(request);
      return {
        ...result,
        changes: result.changes.map(change => ({
          ...change,
          startSeconds: Math.max(0, change.startSeconds),
          endSeconds: change.endSeconds + 70,
          metrics: change.metrics ? [...new Set([...change.metrics, ...ENERGY_PER_DISTANCE_METRICS])] : undefined,
        })),
      };
    },
  };
}

/** Apply the display choice to plots and inspection, leaving whole-ride readings unchanged. */
export function withEnergySmoothing(source: MonitorSource, seconds: EnergySmoothingSeconds): MonitorSource {
  return {
    ...forwarded(source),
    semanticKey: `${source.semanticKey ?? ''}:energy-smoothing:${seconds}`,
    readLatest: request => source.readLatest({ ...request, energySmoothingSeconds: seconds }),
    readPlot: request => source.readPlot({ ...request, energySmoothingSeconds: seconds }),
    inspectAt: request => source.inspectAt({ ...request, energySmoothingSeconds: seconds }),
    rangeStats: request => source.rangeStats({ ...request, energySmoothingSeconds: seconds }),
  };
}
