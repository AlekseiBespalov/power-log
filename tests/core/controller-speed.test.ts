import { describe, expect, it } from 'vitest';
import fixture from '../fixtures/protocol.json';
import { controllerProtocol, controllerRestoreIdentity } from '../../shared/js';
import { telemetrySample } from '../../src/core/telemetry';
import type { ControllerIdentity } from '../../shared/js/power-log-core';
import type { SampleTiming } from '../../src/core/types';
import { framePayload } from '../support/controller-frames';
import { validateSample } from '../../src/core/validation';
import {
  controllerSpeedPreferenceId,
  defaultMonitorPreferences,
  formatMetric,
  metricById,
  monitorQueryMetrics,
  resolveMonitorMetrics,
  monitorPreferencesJson,
  parseMonitorPreferences,
} from '../../src/core/monitor';
import { groupMonitorMetrics } from '../../src/core/monitor-chart';
import { TelemetryMonitor } from '../../src/core/monitor-data';
import { searchMonitorMetrics } from '../../src/features/monitor/monitor-editor-model';
import { hex, sample } from './helpers';
import { syntheticSample } from '../fixtures/synthetic-sample';

const identity = (model = 'X6', major = 5, minor = 3) =>
  controllerRestoreIdentity(model, '20250604', `${major}.${minor}`);
const timing = { timestamp: '2026-01-01T00:00:00.000Z', elapsedSeconds: 0, sequence: 0 };
function decodedSample(speedRaw: number, timing: SampleTiming, profile?: ControllerIdentity) {
  const payload = hex(fixture.telemetry[2]!.payloadHex);
  new DataView(payload.buffer).setInt32(48, Math.round(speedRaw * 100), false);
  const reply = controllerProtocol().feed(new Int8Array(framePayload(payload)), profile ?? null).replies[0]!;
  return telemetrySample(reply.reading!, timing);
}
describe('controller speed provenance and units', () => {
  it.each([{ faultCode: 999 }, { motorInputPowerW: 999 }, { humanPowerW: 1.5 }, { motorRpm: 2147483648 }])(
    'rejects impossible readings without provenance: %j',
    patch => {
      expect(() => validateSample({ ...sample(), ...patch })).toThrow();
    },
  );
  it.each(['X6', 'X12'])('normalizes %s 5.3 without changing the original or GPS channel', model => {
    for (const speedRaw of [0, 24.75, -12.5]) {
      const result = decodedSample(speedRaw, timing, identity(model));
      expect(result).toMatchObject({
        speedRaw,
        controllerSpeedMps: speedRaw / 3.6,
        controllerModel: model,
        firmwareLabel: '20250604',
        controllerProtocol: '5.3',
      });
      expect(result).not.toHaveProperty('speedMps');
      expect(result).not.toHaveProperty('productString');
    }
  });
  it('keeps unknown protocol and model variants raw and does not infer old recordings', () => {
    for (const profile of [undefined, identity('X6', 5, 4), identity('X12', 6, 3), identity('X6_Pro')]) {
      expect(decodedSample(0, timing, profile)).not.toHaveProperty('controllerSpeedMps');
    }
  });
  it('rejects missing, malformed or inconsistent unit provenance in recorded samples', () => {
    const good = decodedSample(36, timing, identity());
    for (const patch of [
      { controllerSpeedMps: 36 },
      { controllerModel: undefined },
      { controllerProtocol: '5.4' },
      { controllerProtocol: '999.3' },
      { firmwareLabel: '=1+1' },
      { controllerModel: 'X6_Pro' },
    ]) {
      expect(() => validateSample({ ...good, ...patch })).toThrow();
    }
    expect(() => validateSample({ ...sample(), controllerSpeedMps: 0 })).toThrow('provenance');
  });
  it.each(['motorTempC', 'motorInputPowerW'] as const)('restores profiled %s through core validation', metric => {
    const original = decodedSample(36, timing, identity());
    expect(validateSample(original)).toEqual(original);
    const malformed = {
      ...original,
      [metric]: metric === 'motorTempC' ? 21.71 : original.motorInputPowerW + 0.0005,
    };
    expect(() => validateSample(malformed)).toThrow();
  });
  it.each([0, 0.125, 1, 123.125])('restores generated profiled originals at %s seconds', seconds => {
    const generated = {
      ...syntheticSample(seconds, 0, timing.timestamp),
      controllerModel: 'X6',
      firmwareLabel: '20250604',
      controllerProtocol: '5.3',
      controllerSpeedMps: 0,
    };
    expect(validateSample(generated)).toEqual(generated);
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
    expect(searchMonitorMetrics('controller speed').map(metric => metric.id)).toEqual([
      'controllerSpeedMps',
      'controllerDistanceMeters',
    ]);
    expect(searchMonitorMetrics('mph', 'mph').map(metric => metric.id)).toEqual(['speedMps', 'controllerSpeedMps']);
  });
  it('keeps raw speed out of saved selections and chooses fallback only when no physical-unit samples exist', () => {
    const prefs = defaultMonitorPreferences();
    prefs.views.ride.charts = ['speedRaw', 'controllerSpeedMps', 'humanPowerW'];
    expect(JSON.parse(monitorPreferencesJson(prefs)).views.ride.charts).toEqual(['controllerSpeedMps', 'humanPowerW']);
    expect(
      parseMonitorPreferences(
        JSON.stringify({
          ...JSON.parse(monitorPreferencesJson(defaultMonitorPreferences())),
          views: {
            ...JSON.parse(monitorPreferencesJson(defaultMonitorPreferences())).views,
            ride: { numbers: [], charts: prefs.views.ride.charts, range: 120, webColumns: 2 },
          },
        }),
      ).views.ride.charts,
    ).toEqual(['controllerSpeedMps', 'humanPowerW']);
    const ids = ['controllerSpeedMps', 'speedMps'];
    expect(monitorQueryMetrics(ids)).toEqual(['controllerSpeedMps', 'speedRaw', 'speedMps']);
    expect(monitorQueryMetrics(ids, ['speedRaw', 'speedMps'])).toEqual(['controllerSpeedMps', 'speedRaw', 'speedMps']);
    expect(monitorQueryMetrics(ids, ['speedRaw', 'controllerSpeedMps'])).toEqual(ids);
    expect(resolveMonitorMetrics(ids, ['speedRaw'])).toEqual(['speedRaw', 'speedMps']);
    expect(resolveMonitorMetrics(ids, ['speedRaw', 'controllerSpeedMps'])).toEqual(ids);
    expect(resolveMonitorMetrics(ids)).toEqual(ids);
    expect(resolveMonitorMetrics(ids, ['speedRaw']).map(controllerSpeedPreferenceId)).toEqual(ids);
  });
  it('reads normalized originals through browser history without interpreting legacy values', async () => {
    const known = decodedSample(36, timing, identity('X12'));
    const monitor = new TelemetryMonitor('units', false, [known, sample(1, { speedRaw: 100 })]);
    const description = await monitor.describeSource({ generation: 1 });
    expect(description.status).toBe('ok');
    const request = {
      generation: 1,
      expectedRevision: description.revision,
      metrics: monitorQueryMetrics(['controllerSpeedMps']),
    };
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
