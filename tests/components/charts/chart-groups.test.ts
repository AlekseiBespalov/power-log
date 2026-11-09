import { describe, expect, it } from 'vitest';
import { chartGroups, chartMetrics, reorderCharts } from '../../../src/components/charts/chart-groups';

const metric = chartMetrics('metric', 'celsius');

describe('chart groups', () => {
  it('overlays compatible metrics while separating different RPM, voltage, altitude and status semantics', () => {
    const groups = chartGroups(
      [
        'cadenceRpm',
        'motorRpm',
        'batteryVoltageV',
        'throttleVoltageV',
        'assistLevel',
        'raceMode',
        'faultCode',
        'motorTempC',
        'controllerTempC',
        'humanPowerW',
        'motorInputPowerW',
        'batteryWhPerKm',
        'riderWhPerKm',
        'altitudeMeters',
        'horizontalAccuracyM',
        'verticalAccuracyM',
        'speedMps',
        'controllerSpeedMps',
        'humanPowerW',
        'unknown',
      ],
      metric,
    );
    expect(groups.map(group => group.metrics.map(item => item.id))).toEqual([
      ['cadenceRpm'],
      ['motorRpm'],
      ['batteryVoltageV'],
      ['throttleVoltageV'],
      ['assistLevel'],
      ['raceMode'],
      ['faultCode'],
      ['motorTempC', 'controllerTempC'],
      ['humanPowerW', 'motorInputPowerW'],
      ['batteryWhPerKm', 'riderWhPerKm'],
      ['altitudeMeters'],
      ['horizontalAccuracyM', 'verticalAccuracyM'],
      ['speedMps', 'controllerSpeedMps'],
    ]);
    expect(groups.at(-1)).toMatchObject({ id: 'speed', label: 'Speed', unit: 'km/h' });
    expect(chartGroups(['speedMps'], chartMetrics('imperial', 'fahrenheit'))[0]?.unit).toBe('mph');
  });

  it('moves both speed sources together and keeps the unknown-unit lane independently reorderable', () => {
    expect(reorderCharts(['speedMps', 'humanPowerW', 'controllerSpeedMps', 'speedRaw'], 'speed', 'speedRaw')).toEqual([
      'humanPowerW',
      'speedRaw',
      'speedMps',
      'controllerSpeedMps',
    ]);
  });

  it('moves a whole chart without losing overlaid metrics or changing their relative order', () => {
    const ids = Object.freeze(['humanPowerW', 'cadenceRpm', 'motorInputPowerW', 'batteryVoltageV']);
    expect(reorderCharts(ids, 'power', 'batteryVoltageV')).toEqual([
      'cadenceRpm',
      'batteryVoltageV',
      'humanPowerW',
      'motorInputPowerW',
    ]);
    expect(reorderCharts(ids, 'batteryVoltageV', 'power')).toEqual([
      'batteryVoltageV',
      'humanPowerW',
      'motorInputPowerW',
      'cadenceRpm',
    ]);
    expect(reorderCharts(ids, 'unknown', 'power')).toEqual(ids);
    expect(reorderCharts(ids, 'power', 'power')).toEqual(ids);
  });
});
