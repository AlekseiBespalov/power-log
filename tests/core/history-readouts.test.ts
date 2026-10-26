import { describe, expect, it, vi } from 'vitest';
import { historyRangeReadout } from '../../src/components/monitor-charts';
import { metricById, type MonitorData, type MonitorPoint } from '../../src/core/monitor';

vi.mock('react-native', () => ({ Platform: { OS: 'web' }, StyleSheet: { create: (value: unknown) => value } }));
vi.mock('react-native-reanimated', () => ({}));
vi.mock('react-native-gesture-handler', () => ({}));
vi.mock('react-native-safe-area-context', () => ({}));
vi.mock('react-native-svg', () => ({}));
vi.mock('../../src/components/use-chart-interaction', () => ({}));
vi.mock('../../src/components/use-native-chart-gestures', () => ({}));
vi.mock('../../src/components/monitor-raster', () => ({}));
vi.mock('../../src/components/monitor-chart-grid', () => ({}));
vi.mock('../../src/components/modal-dialog', () => ({}));
vi.mock('../../src/services/use-foreground-activity', () => ({ useForegroundActivity: () => true }));

const point = (value: number, elapsedSeconds: number): MonitorPoint => ({
  value,
  elapsedSeconds,
  timestamp: new Date(Date.UTC(2026, 0, 1) + elapsedSeconds * 1000).toISOString(),
});
function data(): MonitorData {
  return {
    sourceId: 'history-test',
    startedAt: '2026-01-01T00:00:00Z',
    domain: { start: 0, end: 10 },
    series: { humanPowerW: [point(100, 0), point(0, 0.5), point(604, 1), point(200, 2)] },
    statistics: { humanPowerW: { count: 9, sampleMean: 123, min: point(0, 0.5), max: point(604, 1) } },
    statisticsViewport: { start: 0, end: 10 },
  };
}
describe('History range readouts', () => {
  it('shows energy divided by distance, not the mean of instantaneous chart readings', () => {
    const value = data();
    value.series.batteryWhPerKm = [point(100, 0), point(50, 10)];
    value.statistics.batteryWhPerKm = { ratio: 66.666666, sampleMean: 75, count: 2, coveredSeconds: 10 };
    expect(historyRangeReadout(value, metricById('batteryWhPerKm')!, { start: 0, end: 10 })).toEqual({
      value: 'avg 66.7',
      detail: '',
    });
    value.statistics.batteryWhPerKm!.partial = true;
    expect(historyRangeReadout(value, metricById('batteryWhPerKm')!, { start: 0, end: 10 }).detail).toBe(
      'Partial data',
    );
    value.statistics = {};
    expect(historyRangeReadout(value, metricById('batteryWhPerKm')!, { start: 0, end: 10 })).toEqual({
      value: 'avg —',
      detail: '',
    });
  });
  it('uses original range statistics rather than the last plotted point', () => {
    expect(historyRangeReadout(data(), metricById('humanPowerW')!, { start: 0, end: 10 })).toEqual({
      value: 'avg 123',
      detail: 'max 604 W',
    });
  });
  it('uses statistics from the clipped range and converts their units', () => {
    const value = data();
    value.statisticsViewport = { start: 1.2, end: 5.8 };
    value.statistics.speedMps = { count: 2, sampleMean: 10, min: point(5, 1.2), max: point(15, 4.7) };
    expect(historyRangeReadout(value, metricById('speedMps')!, value.statisticsViewport)).toEqual({
      value: 'avg 36.0',
      detail: 'max 54.0 km/h',
    });
  });
  it.each(['pending', 'otherRange'] as const)('does not present %s statistics as the current visible range', reason => {
    const value = data();
    if (reason === 'pending') value.statisticsPending = true;
    else value.statisticsViewport = { start: 1, end: 5 };
    expect(historyRangeReadout(value, metricById('humanPowerW')!, { start: 0, end: 10 })).toEqual({
      value: 'min —',
      detail: '',
    });
  });
  it('does not average counter values or convert missing values to zero', () => {
    const value = data();
    value.statistics.consumedWh = {
      count: 2,
      sampleMean: 99,
      min: { ...point(1, 1), exactValue: '18446744073709551610' },
      max: { ...point(2, 2), exactValue: '18446744073709551611' },
    };
    expect(historyRangeReadout(value, metricById('consumedWh')!, { start: 0, end: 10 })).toEqual({
      value: 'min 18446744073709551610.0',
      detail: 'max 18446744073709551611.0 Wh',
    });
    value.statistics = {};
    expect(historyRangeReadout(value, metricById('humanPowerW')!, { start: 0, end: 10 })).toEqual({
      value: 'min —',
      detail: 'max — W',
    });
  });
});
