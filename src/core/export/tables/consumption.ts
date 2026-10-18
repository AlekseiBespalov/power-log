import { DerivedTimelineUnavailable, readAnalyticsTimeline } from '../analytics-timeline';
import { CONSUMPTION_METRICS } from '../../consumption';
import { metricById } from '../../monitor';
import { ConsumptionProjection } from '../consumption';
import { readProjection } from '../pages';
import { RunTracker, type TimelineCursor } from '../timeline';
import { csvNumber, csvText, type TableInput } from './text';

export const CONSUMPTION_TABLE = {
  name: 'battery_consumption',
  path: 'battery-consumption.csv',
  fields: [
    { name: 'timestamp', type: 'datetime', description: 'Original controller timestamp.' },
    { name: 'elapsedSeconds', type: 'number', description: 'Time on the ride timeline.', 'powerLog:unit': 's' },
    ...CONSUMPTION_METRICS.map(id => ({
      name: id,
      type: 'number',
      description: `${metricById(id)!.label}. ${metricById(id)!.description}`,
      'powerLog:unit': metricById(id)!.unit,
    })),
  ],
};

export type ConsumptionTableStatus = { status: 'available' } | { status: 'unavailable'; reason: string };

export async function writeConsumption({
  source,
  session,
  reads,
  timeline,
  out,
}: TableInput): Promise<ConsumptionTableStatus> {
  out.line(CONSUMPTION_TABLE.fields.map(field => field.name).join(','));
  let cursor: TimelineCursor<'telemetry'> | undefined;
  let status: ConsumptionTableStatus = { status: 'unavailable', reason: 'No controller telemetry.' };
  let attempted = false;
  const runs = new RunTracker(reads.options.platform, 'telemetry');
  const projection = new ConsumptionProjection();
  for await (const page of readProjection(source, session, 'telemetry', reads)) {
    if (!page.rows) continue;
    if (!attempted) {
      attempted = true;
      try {
        const analytics = await readAnalyticsTimeline(source, session, timeline.end, reads, { fitLimits: false });
        cursor = analytics.cursor('telemetry');
        status = { status: 'available' };
      } catch (error) {
        if (!(error instanceof DerivedTimelineUnavailable)) throw error;
        status = { status: 'unavailable', reason: error.message };
      }
    }
    if (!cursor) continue;
    const assignment = cursor.map(page);
    const values = projection.append(page, assignment, runs.map(page, assignment));
    for (let index = 0; index < page.rows; index++) {
      out.line(
        [
          csvText(page.columns.timestamp?.[index]),
          csvNumber(page.columns.elapsedSeconds![index]!, 'derived battery reading', 'elapsedSeconds'),
          ...CONSUMPTION_METRICS.map(metric => csvNumber(values[metric][index]!, 'derived battery reading', metric)),
        ].join(','),
      );
      if (index % 64 === 0) await reads.options.slicer.tick();
    }
  }
  return status;
}
