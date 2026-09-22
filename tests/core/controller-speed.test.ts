import { describe, expect, it } from 'vitest';
import fixture from '../fixtures/protocol.json';
import { decodeIdentity, decodeSelectiveValues, toTelemetrySample } from '../../src/core/protocol';
import { parseCsv } from '../../src/core/recordings';
import { exportCsv } from '../helpers/export-csv';
import { REQUIRED_SAMPLE_COLUMNS } from '../../src/core/types';
import { validateSample } from '../../src/core/validation';
import { controllerSpeedPreferenceId, defaultMonitorPreferences, formatMetric, metricById, monitorQueryMetrics, resolveMonitorMetrics, validateMonitorPreferences } from '../../src/core/monitor';
import { groupMonitorMetrics } from '../../src/core/monitor-chart';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { searchMonitorMetrics } from '../../src/features/monitor/monitor-editor-model';
import { hex, sample } from './helpers';

const identity = (model = 'X6', major = 5, minor = 3) => decodeIdentity(Uint8Array.of(111, major, minor, ...Array.from(`${model} 20250604 `, char => char.charCodeAt(0)), 0x80, 0xff, 0));
const values = decodeSelectiveValues(hex(fixture.telemetry[2]!.payloadHex));
const timing = { timestamp: '2026-01-01T00:00:00.000Z', elapsedSeconds: 0, sequence: 0 };

describe('controller speed provenance and units', () => {
  it.each(['X6', 'X12'])('normalizes %s 5.3 without changing the original or GPS channel', model => {
    for (const speedRaw of [0, 24.75, -12.5]) {
      const result = toTelemetrySample({ ...values, speedRaw }, timing, identity(model));
      expect(result).toMatchObject({ speedRaw, controllerSpeedMps: speedRaw / 3.6, controllerModel: model, firmwareLabel: '20250604', controllerProtocol: '5.3' });
      expect(result).not.toHaveProperty('speedMps');
      expect(result).not.toHaveProperty('productString');
      expect(parseCsv(exportCsv([result]), { source: 'device' }).samples).toEqual([result]);
    }
  });
  it('keeps unknown protocol and model variants raw and does not infer old recordings', () => {
    for (const profile of [undefined, identity('X6', 5, 4), identity('X12', 6, 3), identity('X6_Pro')]) {
      expect(toTelemetrySample(values, timing, profile)).not.toHaveProperty('controllerSpeedMps');
    }
    const old = sample(0, { speedRaw: 24.75 });
    const legacy = REQUIRED_SAMPLE_COLUMNS.join(',') + '\n' + REQUIRED_SAMPLE_COLUMNS.map(key => old[key]).join(',') + '\n';
    expect(parseCsv(legacy, { source: 'device' }).samples).toEqual([old]);
    expect(parseCsv(exportCsv([old]), { source: 'device' }).samples).toEqual([old]);
  });
  it('rejects missing, malformed or inconsistent unit provenance on import', () => {
    const good = toTelemetrySample({ ...values, speedRaw: 36 }, timing, identity());
    for (const patch of [{ controllerSpeedMps: 36 }, { controllerModel: undefined }, { controllerProtocol: '5.4' }, { controllerProtocol: '999.3' }, { firmwareLabel: '=1+1' }, { controllerModel: 'X6_Pro' }]) {
      expect(() => validateSample({ ...good, ...patch })).toThrow();
    }
    expect(() => validateSample({ ...sample(), controllerSpeedMps: 0 })).toThrow('provenance');
  });
  it('converts readouts and chart scales together while keeping raw fallback unscaled', () => {
    for (const id of ['speedMps', 'controllerSpeedMps']) {
      expect(formatMetric(metricById(id)!, 10)).toBe('36.0');
      expect(formatMetric(metricById(id, 'mph')!, 10)).toBe('22.4');
      const [group] = groupMonitorMetrics([id], 'mph');
      expect(group?.unit).toBe('mph');
      expect(group?.metrics[0]?.scale).toBeCloseTo(1 / 0.44704);
    }
    expect(formatMetric(metricById('speedRaw', 'mph')!, 36)).toBe('36.00');
    expect(metricById('speedRaw', 'mph')!.unit).toBe('raw');
    expect(searchMonitorMetrics('controller speed').map(metric => metric.id)).toEqual(['controllerSpeedMps']);
    expect(searchMonitorMetrics('mph', 'mph').map(metric => metric.id)).toEqual(['speedMps', 'healthSpeedMps', 'controllerSpeedMps']);
  });
  it('migrates saved raw selections and chooses fallback only when no physical-unit samples exist', () => {
    const prefs = defaultMonitorPreferences(); prefs.views.ride.charts = ['speedRaw', 'controllerSpeedMps', 'humanPowerW'];
    expect(validateMonitorPreferences(prefs).views.ride.charts).toEqual(['controllerSpeedMps', 'humanPowerW']);
    const ids = ['controllerSpeedMps', 'speedMps'];
    expect(monitorQueryMetrics(ids)).toEqual(['controllerSpeedMps', 'speedRaw', 'speedMps']);
    expect(resolveMonitorMetrics(ids, ['speedRaw'])).toEqual(['speedRaw', 'speedMps']);
    expect(resolveMonitorMetrics(ids, ['speedRaw', 'controllerSpeedMps'])).toEqual(ids);
    expect(resolveMonitorMetrics(ids)).toEqual(ids);
    expect(resolveMonitorMetrics(ids, ['speedRaw']).map(controllerSpeedPreferenceId)).toEqual(ids);
  });
  it('reads normalized originals through browser history without interpreting legacy values', async () => {
    const known = toTelemetrySample({ ...values, speedRaw: 36 }, timing, identity('X12'));
    const monitor = new TelemetryMonitor('units', false, [known, sample(1, { speedRaw: 100 })]);
    const description = await monitor.describeSource({ generation: 1 });
    expect(description.status).toBe('ok');
    const request = { generation: 1, expectedRevision: description.revision, metrics: monitorQueryMetrics(['controllerSpeedMps']) };
    const plot = await monitor.readPlot(request);
    expect(plot.status).toBe('ok');
    if (plot.status !== 'ok') throw new Error('Unexpected retry');
    expect(plot.series.controllerSpeedMps?.map(point => point.value)).toEqual([10]);
    expect(plot.series.speedRaw?.map(point => point.value)).toEqual([36, 100]);
    const stats = await monitor.rangeStats({ ...request, startSeconds: 0, endSeconds: 1 });
    if (stats.status !== 'ok') throw new Error('Unexpected retry');
    expect(stats.statistics.controllerSpeedMps?.max?.value).toBe(10);
  });
});
