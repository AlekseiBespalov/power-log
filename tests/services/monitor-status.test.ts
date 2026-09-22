import { describe, expect, it } from 'vitest';
import type { MonitorDescription } from '../../src/core/monitor';
import { monitorSourceStatus } from '../../src/features/monitor/monitor-status';

const source = (changes: Partial<MonitorDescription> = {}): MonitorDescription => ({
  startedAt: '2026-09-16T12:00:00Z', domain: { start: 0, end: 37 },
  availableMetrics: [], outcome: 'pending', warnings: [], ...changes,
});

describe('monitor source status', () => {
  it('waits for the first measurement, not for the active ride to be finalized', () => {
    expect(monitorSourceStatus(undefined, true)).toBeNull();
    expect(monitorSourceStatus(source(), true)).toEqual({ label: 'Awaiting data', detail: 'Waiting for measurements.' });
    expect(monitorSourceStatus(source({ availableMetrics: ['humanPowerW', 'heartRateBpm'] }), true)).toBeNull();
  });

  it.each(['humanPowerW', 'heartRateBpm', 'speedMps', 'distanceMeters'])('recognizes %s without requiring all sources or a completed ride', metric => {
    expect(monitorSourceStatus(source({ availableMetrics: [metric] }), true)).toBeNull();
  });

  it('does not mistake a pending distance capability for a recorded measurement', () => {
    const distance = source({ availableMetrics: ['distanceMeters'], metricSources: { distanceMeters: { source: 'pending', label: 'Calculating distance' } } });
    expect(monitorSourceStatus(distance, true)?.label).toBe('Awaiting data');
    expect(monitorSourceStatus({ ...distance, availableMetrics: ['distanceMeters', 'heartRateBpm'] }, true)).toBeNull();
    expect(monitorSourceStatus({ ...distance, metricSources: { distanceMeters: { source: 'gps:watch', label: 'GPS · Watch' } } }, true)).toBeNull();
  });

  it.each([{ availableMetrics: [] }, { availableMetrics: ['humanPowerW', 'heartRateBpm'] }])('keeps saved-ride synchronization visible regardless of available measurements: $availableMetrics', ({ availableMetrics }) => {
    expect(monitorSourceStatus(source({ availableMetrics }), false)).toEqual({ label: 'Syncing ride…', detail: 'Syncing remaining ride data.' });
  });

  it.each([true, false])('retains partial and unavailable status with live=%s', live => {
    expect(monitorSourceStatus(source({ outcome: 'partial', availableMetrics: ['humanPowerW'] }), live)?.label).toBe(live ? 'Missing readings' : 'Incomplete ride');
    expect(monitorSourceStatus(source({ outcome: 'unavailable' }), live)?.label).toBe('No measurements');
    expect(monitorSourceStatus(source({ outcome: 'available', availableMetrics: ['humanPowerW'] }), live)).toBeNull();
  });
});
