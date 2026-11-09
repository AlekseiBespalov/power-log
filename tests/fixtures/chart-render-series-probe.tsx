import * as series from '../../src/components/charts/chart-series.ts';
import { count, counts } from './monitor-render-counts';

export const { chartScale, hasSamples, latestPoint, originalPoint, seriesFrame, stepCursor } = series;

export function lineGeometry(...args: Parameters<typeof series.lineGeometry>) {
  count(counts.scenes, args[1].id);
  return series.lineGeometry(...args);
}
