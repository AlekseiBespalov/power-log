import reference from './contract/analytics/iphone-reference.json';
import deltas from './contract/analytics/approved-deltas.json';
import review from './contract/analytics/review-required.json';
const references: Record<string, unknown> = { ...reference.shared, ...deltas.shared };
function overlay(base: unknown, changes: unknown): unknown {
  if (Array.isArray(base) && Array.isArray(changes) && base.length === changes.length)
    return base.map((value, index) => overlay(value, changes[index]));
  if (
    base &&
    changes &&
    typeof base === 'object' &&
    typeof changes === 'object' &&
    !Array.isArray(base) &&
    !Array.isArray(changes)
  ) {
    const original = base as Record<string, unknown>;
    return {
      ...original,
      ...Object.fromEntries(Object.entries(changes).map(([key, value]) => [key, overlay(original[key], value)])),
    };
  }
  return changes;
}
export function expandAnalytics(value: unknown, index = 0): unknown {
  if (Array.isArray(value)) return value.map(item => expandAnalytics(item, index));
  if (value === null || typeof value !== 'object') return value;
  const node = value as Record<string, unknown>;
  if ('$ref' in node) {
    const key = String(node.$ref);
    if (!(key in references)) throw new Error(`Unknown analytics fixture reference: ${key}`);
    const expanded = expandAnalytics(references[key], index);
    return node.override ? overlay(expanded, expandAnalytics(node.override, index)) : expanded;
  }
  if ('$values' in node) return (node.$values as unknown[])[index];
  if ('$linear' in node) {
    const v = node.$linear as { start: number; step: number };
    return v.start + index * v.step;
  }
  if ('$time' in node) {
    const v = node.$time as { date: string; startMs: number; stepMs: number };
    return new Date(Date.parse(`${v.date}T00:00:00Z`) + v.startMs + index * v.stepMs).toISOString();
  }
  if ('$repeat' in node) {
    const v = node.$repeat as { count: number; template: unknown };
    return Array.from({ length: v.count }, (_, i) => expandAnalytics(v.template, i));
  }
  if ('$concat' in node) return (node.$concat as unknown[]).flatMap(item => expandAnalytics(item) as unknown[]);
  return Object.fromEntries(Object.entries(node).map(([key, item]) => [key, expandAnalytics(item, index)]));
}
export type AnalyticsFixture = {
  name: string;
  input: {
    create: { startedAt: string; indoor: boolean; recordGPS: boolean };
    events: {
      kind: string;
      source: string;
      eventId: string;
      timestamp: string;
      elapsedSeconds: number;
      payload: Record<string, number | string | boolean>;
    }[];
    stopElapsedSeconds: number;
    endedAt: string;
    queries: { startSeconds: number; endSeconds: number; metrics: string[] }[];
  };
  expectedSummary: Record<string, unknown>;
  expectedAbsentSummaryFields: string[];
};
const labels = expandAnalytics(deltas.cases.find(item => item.testId === 'source-labels')!.expectedApproved) as {
  summaries: Record<string, unknown>;
};
const gpsTotals = review.cases.find(item => item.testId === 'gps-fixed-point-summary')!.expectedApproved;
function browserWording(value: unknown): unknown {
  if (typeof value === 'string') return value.replaceAll('GPS · iPhone', 'GPS · Browser');
  if (Array.isArray(value)) return value.map(browserWording);
  if (value && typeof value === 'object')
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, browserWording(item)]));
  return value;
}
export const browserAnalyticsFixtures = (expandAnalytics(reference.cases) as AnalyticsFixture[])
  .filter(fixture => fixture.input.events.every(event => event.kind !== 'health' && event.source !== 'watch'))
  .map(fixture => {
    let summary = overlay(fixture.expectedSummary, labels.summaries[fixture.name]);
    if (fixture.name === 'moving-gps-gap') summary = overlay(summary, gpsTotals);
    // The producer is frozen at Start; this changes selection metadata, never measurement totals.
    summary = overlay(summary, {
      distance: {
        selection: fixture.input.create.recordGPS && !fixture.input.create.indoor ? 'gps:phone' : 'controller',
      },
    });
    return { ...fixture, expectedSummary: browserWording(summary) as Record<string, unknown> };
  });
export const approvedChartOverrides = expandAnalytics(
  deltas.cases.find(item => item.testId === 'chart-reference-targets')!.expectedApproved,
) as Record<string, Record<string, Record<string, unknown>>[]>;
