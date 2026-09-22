import { describe, expect, it } from 'vitest';
import fixture from '../fixtures/distance-controller.json';
import { appendControllerDistance, clipControllerDistance, emptyControllerDistance, type ControllerDistanceInput } from '../../src/core/distance';
import { metricAllowsMean, metricById } from '../../src/core/monitor';
import { monitorLineGeometry, groupMonitorMetrics } from '../../src/core/monitor-chart';

describe('controller distance parity corpus', () => {
  for (const test of fixture.cases) it(test.name, () => {
    const state = emptyControllerDistance();
    const intervals = test.samples.map((input, index) => appendControllerDistance(state, { ...fixture.defaults, ...input, id: `sample-${index}` } as ControllerDistanceInput)).filter(value => value !== undefined);
    expect(state.distance).toBeCloseTo(test.distance, 9); expect(state.covered).toBeCloseTo(test.covered, 9); expect(state.intervals).toBe(test.intervals);
    if ('range' in test && test.range) {
      const clipped = intervals.map(value => clipControllerDistance(value, test.range!.start, test.range!.end));
      expect(clipped.reduce((sum, value) => sum + value.distance, 0)).toBeCloseTo(test.range.distance, 9);
      expect(clipped.reduce((sum, value) => sum + value.covered, 0)).toBeCloseTo(test.range.covered, 9);
    }
  });
});

it('keeps semantic quantities and course geometry distinct', () => {
  for (const id of ['distanceMeters', 'consumedAh', 'consumedWh', 'activeEnergyKcal', 'basalEnergyKcal', 'courseDegrees', 'assistLevel', 'raceMode', 'faultCode']) expect(metricAllowsMean(metricById(id)!)).toBe(false);
  expect(metricAllowsMean(metricById('humanPowerW')!)).toBe(true);
  expect(groupMonitorMetrics(['cadenceRpm', 'motorRpm'])).toHaveLength(2);
  expect(groupMonitorMetrics(['speedMps', 'healthSpeedMps', 'controllerSpeedMps'])).toHaveLength(1);
  expect(metricById('healthSpeedMps', 'mph')!.scale).toBe(1 / 0.44704);
  const geometry = monitorLineGeometry([{ value: 359, elapsedSeconds: 0, timestamp: 't0' }, { value: 1, elapsedSeconds: 1, timestamp: 't1' }], metricById('courseDegrees')!, n => n, n => n);
  expect(geometry.path.match(/M/g)).toHaveLength(2); expect(geometry.path).not.toContain('L');
});
