import type { MonitorDescription } from '../../core/monitor';

export function monitorSourceStatus(
  description: MonitorDescription | undefined,
  live: boolean,
): { label: string; detail: string } | null {
  if (!description) return null;
  if (live) {
    const hasMeasurements = description.availableMetrics.some(
      id => description.metricSources?.[id]?.source !== 'pending',
    );
    return hasMeasurements ? null : { label: 'Awaiting data', detail: 'Waiting for measurements.' };
  }
  switch (description.outcome) {
    case 'pending':
      return { label: 'Syncing ride…', detail: 'Syncing remaining ride data.' };
    case 'unavailable':
      return { label: 'No measurements', detail: 'No measurements are available.' };
    case 'available':
    case 'partial':
      return null;
  }
}
