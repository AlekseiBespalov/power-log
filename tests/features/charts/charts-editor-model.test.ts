import { describe, expect, it } from 'vitest';
import { kit } from '../../../modules/power-log';
import { chartMetrics } from '../../../src/components/charts/chart-groups';
import { reorderMetric, searchMetrics, toggleMetric } from '../../../src/features/charts/charts-editor-model';

const metric = chartMetrics('metric', 'celsius');
const ids = (query: string) => searchMetrics(query, metric).map(shown => shown.id);

describe('chart editor choices', () => {
  it('toggles every selectable metric once, in the order chosen', () => {
    const selectable = kit
      .metricAppearances()
      .filter(appearance => appearance.selectable)
      .map(appearance => appearance.id);
    let chosen: string[] = [];
    for (const id of selectable) chosen = toggleMetric(chosen, id);
    expect(chosen).toEqual(selectable);
    expect(toggleMetric(chosen, 'motorTempC')).not.toContain('motorTempC');
  });

  it('reorders only the requested list and handles first/last/unknown boundaries', () => {
    const chosen = Object.freeze(['motorTempC', 'batteryCurrentA', 'cadenceRpm']);
    expect(reorderMetric(chosen, 'batteryCurrentA', 0)).toEqual(['batteryCurrentA', 'motorTempC', 'cadenceRpm']);
    expect(reorderMetric(chosen, 'motorTempC', 1)).toEqual(['batteryCurrentA', 'motorTempC', 'cadenceRpm']);
    expect(reorderMetric(chosen, 'motorTempC', -1)).toEqual(chosen);
    expect(reorderMetric(chosen, 'cadenceRpm', 3)).toEqual(chosen);
    expect(reorderMetric(chosen, 'unknown', 0)).toEqual(chosen);
    expect(reorderMetric(chosen, 'motorTempC', 2)).toEqual(['batteryCurrentA', 'cadenceRpm', 'motorTempC']);
    expect(reorderMetric(chosen, 'cadenceRpm', 0)).toEqual(['cadenceRpm', 'motorTempC', 'batteryCurrentA']);
    expect(reorderMetric(chosen, 'cadenceRpm', NaN)).toEqual(chosen);
  });

  it('searches the selectable catalog by label, group and unit', () => {
    expect(ids('')).toHaveLength(33);
    expect(ids('Wh/km')).toEqual(['batteryWhPerKm', 'riderWhPerKm']);
    expect(ids('Resting energy')).toEqual([]);
    expect(ids('temperature')).toEqual(['motorTempC', 'controllerTempC']);
    expect(ids('  BATTERY current ')).toEqual([
      'batteryCurrentA',
      'motorInputPowerW',
      'calculatedEnergyWh',
      'calculatedChargeAh',
    ]);
    expect(ids('°C')).toEqual(['motorTempC', 'controllerTempC']);
    expect(ids('not-a-metric')).toEqual([]);
    expect(ids('controller speed')).toEqual(['controllerSpeedMps', 'controllerDistanceMeters']);
    expect(searchMetrics('mph', chartMetrics('imperial', 'fahrenheit')).map(shown => shown.id)).toEqual([
      'speedMps',
      'controllerSpeedMps',
    ]);
  });
});
