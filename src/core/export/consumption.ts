import { AnalyticsConsumptionKernel, analyticsTimeUs } from '../../../shared/js';
import { CONSUMPTION_METRICS, type ConsumptionMetric } from '../consumption';
import type { ProjectionPage } from './pages';
import type { TimelineAssignment } from './timeline';

/** Runs on original telemetry pages, before FIT bins or CSV formatting. */
export class ConsumptionProjection {
  private readonly kernel = new AnalyticsConsumptionKernel();

  append(page: ProjectionPage<'telemetry'>, assignment: TimelineAssignment, runs: Int32Array) {
    const result = Object.fromEntries(
      CONSUMPTION_METRICS.map(metric => [metric, new Float64Array(page.rows).fill(NaN)]),
    ) as Record<ConsumptionMetric, Float64Array>;
    const value = (column: Float64Array | undefined, index: number) => {
      const number = column?.[index];
      return number !== undefined && Number.isFinite(number) ? number : null;
    };
    const c = page.columns;
    for (let index = 0; index < page.rows; index++) {
      const active = assignment.interval[index]!;
      const intervals = this.kernel.append(
        analyticsTimeUs(c.elapsedSeconds![index]!),
        active === 0 ? null : active,
        String(runs[index]!),
        value(c.consumedAh, index),
        value(c.consumedWh, index),
        value(c.batteryCurrentA, index),
        value(c.batteryVoltageV, index),
      );
      for (const interval of intervals) result[interval.metric as ConsumptionMetric][index] = interval.endValue;
    }
    return result;
  }
}
