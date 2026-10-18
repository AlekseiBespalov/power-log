import { AnalyticsTimeline, analyticsTimeUs, type AnalyticsItem } from '../../../shared/js';
import { readProjection, type ExportReads } from './pages';
import { FIT_MAX_LIFECYCLE_EVENTS, FIT_MAX_RIDE_SECONDS, Timeline, type TimelineTransition } from './timeline';
import { ExportError, type ExportSource } from './types';
import { fitOriginal } from './fit/core';

export class DerivedTimelineUnavailable extends Error {}

export async function readAnalyticsTimeline(
  source: ExportSource,
  session: string,
  end: number,
  reads: ExportReads,
  options = { fitLimits: true },
): Promise<Timeline> {
  if (!Number.isFinite(end) || end < 0) throw new ExportError('gate', 'The ride has no valid elapsed time.');
  if (options.fitLimits && end > FIT_MAX_RIDE_SECONDS)
    throw new ExportError(
      'limit',
      'A FIT file cannot hold a ride longer than 31 days. The ride-data ZIP is still available.',
    );
  const lifecycle: AnalyticsItem[] = [];
  let unavailable: string | undefined;
  let previous = -Infinity;
  for await (const page of readProjection(source, session, 'lifecycle', reads)) {
    if (options.fitLimits && lifecycle.length + page.rows > FIT_MAX_LIFECYCLE_EVENTS)
      throw new ExportError(
        'limit',
        'A FIT file cannot hold more than 10,000 start, pause, resume, lap or stop events. The ride-data ZIP is still available.',
      );
    const c = page.columns;
    for (let i = 0; i < page.rows; i++) {
      const elapsed = c.elapsedSeconds![i]!;
      if (!Number.isFinite(elapsed) || elapsed < 0)
        throw new ExportError('page', 'A lifecycle event has no valid elapsed time.');
      if (elapsed < previous) throw new ExportError('cursor', 'The ride events are out of elapsed order.');
      previous = elapsed;
      const action = c.action?.[i] === 'interruption' ? 'pause' : c.action?.[i];
      const interrupted = c.interrupted?.[i] === 1 || c.action?.[i] === 'interruption';
      if (!options.fitLimits && action === 'lap') continue;
      if (!options.fitLimits && lifecycle.length === FIT_MAX_LIFECYCLE_EVENTS) {
        unavailable = 'More than 10,000 activity-boundary events.';
        continue;
      }
      if (
        action === 'pause' &&
        interrupted &&
        (!Number.isSafeInteger(c.controllerSequence?.[i]) || c.controllerSequence![i]! < 0)
      ) {
        const reason = 'An interruption in this ride has no retained telemetry sequence.';
        if (options.fitLimits) throw new ExportError('page', reason);
        unavailable = reason;
        continue;
      }
      const sequence = c.producerSequence?.[i];
      if (!Number.isSafeInteger(sequence) || sequence! < 0) {
        const reason = 'A lifecycle event has no valid producer sequence.';
        if (options.fitLimits) throw new ExportError('page', reason);
        unavailable = reason;
        continue;
      }
      lifecycle.push(
        fitOriginal({
          elapsed,
          kind: 'lifecycle',
          producer: c.producer?.[i] ?? 'phone',
          sequence,
          clockEpoch: c.clockEpoch?.[i],
          action,
          interrupted,
          ...(Number.isFinite(c.controllerSequence?.[i]) ? { controllerSequence: c.controllerSequence![i]! } : {}),
        }),
      );
    }
  }
  if (unavailable) throw new DerivedTimelineUnavailable(unavailable);
  const core = new AnalyticsTimeline(analyticsTimeUs(end), lifecycle);
  const intervals = core.startsUs.map((start, index) => ({
    id: index + 1,
    open: Number(start) / 1e6,
    close: Number(core.endsUs[index]) / 1e6,
  }));
  const transitions: (TimelineTransition & { readonly elapsedUs: string })[] = [];
  for (const interval of intervals) {
    if (interval.open > 0)
      transitions.push({
        kind: 'open',
        elapsed: interval.open,
        elapsedUs: core.startsUs[interval.id - 1]!,
        interval: interval.id,
      });
    if (interval.close < end || !core.activeThroughEnd)
      transitions.push({
        kind: 'close',
        elapsed: interval.close,
        elapsedUs: core.endsUs[interval.id - 1]!,
        interval: interval.id,
      });
  }
  const lapEnds = core.lapEndsUs.slice(0, -1).map(value => Number(value) / 1e6);
  lapEnds.forEach((elapsed, lap) =>
    transitions.push({ kind: 'lap', elapsed, elapsedUs: core.lapEndsUs[lap]!, lap: lap + 1 }),
  );
  const order = new Map<string, number>();
  lifecycle.forEach((item, index) => {
    const key = `${item.collectionUs}:${item.action}`;
    if (!order.has(key)) order.set(key, index);
  });
  const rank = (item: (typeof transitions)[number]) =>
    order.get(`${item.elapsedUs}:${item.kind === 'open' ? 'resume' : item.kind === 'close' ? 'pause' : 'lap'}`) ?? -1;
  transitions.sort((a, b) => a.elapsed - b.elapsed || rank(a) - rank(b));
  return new Timeline(reads.options.platform, end, intervals, lapEnds, transitions, [], core);
}
