import { describe, expect, it } from 'vitest';
import { rasterScene, type RasterScene } from '../../../src/components/charts/raster-scene';
import type { ChartSeries } from '../../../src/features/charts/chart-feed-model';

const rasterLimits = { series: 32, points: 16_384, keyBytes: 512, sceneBytes: 2 * 1024 * 1024, lanes: 128 };

function rasterSceneProblems(scene: RasterScene): string[] {
  const problems: string[] = [];
  const points = scene.series.reduce((total, series) => total + series.points.length, 0);
  if (new TextEncoder().encode(scene.key).length > rasterLimits.keyBytes) problems.push('key over 512 bytes');
  if (scene.series.length > rasterLimits.series) problems.push(`${scene.series.length} series over 32`);
  if (points > rasterLimits.points) problems.push(`${points} points over 16,384`);
  if (scene.laneCount < 1 || scene.laneCount > rasterLimits.lanes) problems.push('lane count outside 1–128');
  if (!(scene.endS - scene.startS >= 1e-6)) problems.push('an empty time range');
  if (!(scene.max > scene.min)) problems.push('an empty value scale');
  if (!Number.isInteger(scene.decimals) || scene.decimals < 0 || scene.decimals > 8)
    problems.push('decimals outside 0–8');
  for (const series of scene.series) {
    if (!/^#[0-9a-fA-F]{6}$/.test(series.color)) problems.push(`${series.id} colour ${series.color}`);
    for (let index = 1; index < series.points.length; index++)
      if (series.points[index]![0] < series.points[index - 1]![0]) {
        problems.push(`${series.id} points out of time order`);
        break;
      }
  }
  if (new TextEncoder().encode(JSON.stringify(scene)).length > rasterLimits.sceneBytes)
    problems.push('scene over 2 MiB');
  return problems;
}

function series(points: [number, number, number][]): ChartSeries {
  return {
    atUs: Float64Array.from(points.map(([atS]) => atS * 1e6)),
    atS: Float64Array.from(points.map(([atS]) => atS)),
    value: Float64Array.from(points.map(([, value]) => value)),
    starts: Uint8Array.from(points.map(([, , start]) => start)),
  };
}

const base = {
  key: 'ride:speed:320x160@2',
  sourceId: '{"kind":"ride","rideId":"r1"}',
  revision: '5',
  viewport: { startS: 0, endS: 90 },
  scale: { min: 0, max: 40, decimals: 1 },
  laneCount: 1,
  units: 'metric' as const,
  temperature: 'celsius' as const,
};

describe('rasterScene', () => {
  it('converts originals to display units, keeps segment starts and drops non-finite points as breaks', () => {
    const scene = rasterScene({
      ...base,
      series: {
        controllerSpeedMps: series([
          [0, 5, 1],
          [1, Number.NaN, 0],
          [2, 10, 0],
          [3, 6, 1],
        ]),
      },
      group: [{ id: 'controllerSpeedMps', color: '#ff8735', step: false }],
    });

    expect(scene.series).toEqual([
      {
        id: 'controllerSpeedMps',
        color: '#ff8735',
        step: false,
        points: [
          [0, 18, true],
          [2, 36, true],
          [3, 6 * 3.6, true],
        ],
      },
    ]);
    expect(rasterSceneProblems(scene)).toEqual([]);
  });

  it('shows imperial speed and Fahrenheit temperatures', () => {
    const scene = rasterScene({
      ...base,
      units: 'imperial',
      temperature: 'fahrenheit',
      series: { speedMps: series([[0, 0.44704, 1]]), motorTempC: series([[0, 100, 1]]) },
      group: [
        { id: 'speedMps', color: '#00ff00', step: false },
        { id: 'motorTempC', color: '#0000ff', step: true },
      ],
    });

    expect(scene.series.map(row => row.points[0]![1])).toEqual([1, 212]);
    expect(scene.series[1]!.step).toBe(true);
  });

  it('names a scene past its limits, out of order or with a bad colour', () => {
    const many = series(Array.from({ length: 16_385 }, (_, index) => [index, 1, index === 0 ? 1 : 0]));
    const scene = rasterScene({
      ...base,
      scale: { min: 5, max: 5, decimals: 9 },
      series: {
        humanPowerW: many,
        cadenceRpm: series([
          [2, 1, 1],
          [1, 1, 0],
        ]),
      },
      group: [
        { id: 'humanPowerW', color: '#ff8735', step: false },
        { id: 'cadenceRpm', color: 'red', step: false },
      ],
    });

    expect(rasterSceneProblems(scene)).toEqual([
      '16387 points over 16,384',
      'an empty value scale',
      'decimals outside 0–8',
      'cadenceRpm colour red',
      'cadenceRpm points out of time order',
    ]);
  });
});
