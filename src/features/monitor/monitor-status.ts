import type { MonitorDescription } from '../../core/monitor';

export function monitorSourceStatus(description: MonitorDescription | undefined, live: boolean): { label: string; detail: string } | null {
  if (!description) return null;
  switch (description.outcome) {
    case 'pending': {
      if (!live) return { label: 'Syncing ride…', detail: 'Syncing remaining ride data.' };
      // Pending also covers finalization and distance calculation, even after capture has begun.
      const hasMeasurements = description.availableMetrics.some(id => description.metricSources?.[id]?.source !== 'pending');
      return hasMeasurements ? null : { label: 'Awaiting data', detail: 'Waiting for measurements.' };
    }
    case 'partial':
      return { label: live ? 'Missing readings' : 'Incomplete ride', detail: 'Some ride measurements are missing. Available data is shown.' };
    case 'unavailable':
      return { label: 'No measurements', detail: 'No measurements are available.' };
    case 'available':
      return null;
  }
}
