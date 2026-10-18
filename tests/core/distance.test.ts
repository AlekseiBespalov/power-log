import { describe, expect, it } from 'vitest';
import fixture from '../fixtures/distance-controller.json';
import {
  AnalyticsControllerKernel,
  AnalyticsItem,
  AnalyticsDistanceReducer,
  analyticsTimeUs,
  type AnalyticsInterval,
} from '../../shared/js';
import { DISTANCE_SOURCE_LABELS } from '../../src/core/distance';
import { metricAllowsMean, metricById } from '../../src/core/monitor';
import { monitorLineGeometry, groupMonitorMetrics } from '../../src/core/monitor-chart';

describe('controller distance parity corpus', () => {
  for (const test of fixture.cases)
    it(test.name, () => {
      const kernel = new AnalyticsControllerKernel();
      const reducer = new AnalyticsDistanceReducer('0', '1000000000000');
      const intervals: AnalyticsInterval[] = [];
      for (const [index, sample] of test.samples.entries()) {
        const input = { ...fixture.defaults, ...sample };
        const item = new AnalyticsItem(
          analyticsTimeUs(input.time),
          input.time,
          `sample-${index}`,
          `sample-${index}`,
          'controller',
          String(index + 1),
          'telemetry',
          'controller',
          '2026-01-01T00:00:00Z',
          ['controllerSpeedMps'],
          ['real64'],
          [String(input.speed)],
          null,
          null,
          null,
          input.connectionEpoch,
          0,
          null,
          false,
          false,
          null,
          true,
        );
        const interval = kernel.append(
          item,
          input.active ? input.continuity : null,
          input.model,
          input.protocol,
          input.identity,
          input.connectionEpoch ? input.connectionEpoch + ':' + input.continuity : null,
          null,
          null,
        );
        if (interval) {
          intervals.push(interval);
          reducer.append(interval);
        }
      }
      const result = reducer.result('1000000000000');
      expect(result.distanceMeters).toBeCloseTo(test.distance, 9);
      expect(result.coveredSeconds).toBeCloseTo(test.covered, 9);
      expect(intervals.length).toBe(test.intervals);
      if ('range' in test && test.range) {
        const clipped = new AnalyticsDistanceReducer(
          analyticsTimeUs(test.range.start),
          analyticsTimeUs(test.range.end),
        );
        for (const interval of intervals) clipped.append(interval);
        expect(clipped.result('0').distanceMeters).toBeCloseTo(test.range.distance, 9);
        expect(clipped.result('0').coveredSeconds).toBeCloseTo(test.range.covered, 9);
      }
    });
});

it('keeps semantic quantities and course geometry distinct', () => {
  for (const id of [
    'distanceMeters',
    'consumedAh',
    'consumedWh',
    'activeEnergyKcal',
    'basalEnergyKcal',
    'courseDegrees',
    'assistLevel',
    'raceMode',
    'faultCode',
  ])
    expect(metricAllowsMean(metricById(id)!)).toBe(false);
  expect(metricAllowsMean(metricById('humanPowerW')!)).toBe(true);
  expect(groupMonitorMetrics(['cadenceRpm', 'motorRpm'])).toHaveLength(2);
  expect(groupMonitorMetrics(['speedMps', 'controllerSpeedMps'])).toHaveLength(1);
  expect(metricById('controllerSpeedMps', 'mph')!.scale).toBe(1 / 0.44704);
  const geometry = monitorLineGeometry(
    [
      { value: 359, elapsedSeconds: 0, timestamp: 't0' },
      { value: 1, elapsedSeconds: 1, timestamp: 't1' },
    ],
    metricById('courseDegrees')!,
    n => n,
    n => n,
  );
  expect(geometry.path.match(/M/g)).toHaveLength(2);
  expect(geometry.path).not.toContain('L');
});
it('offers no Health distance source or Health speed series', () => {
  expect(Object.keys(DISTANCE_SOURCE_LABELS)).toEqual(['auto', 'gps:watch', 'gps:phone', 'controller']);
  expect(metricById('healthSpeedMps')).toBeUndefined();
  expect(groupMonitorMetrics(['healthSpeedMps'])).toEqual([]);
});
