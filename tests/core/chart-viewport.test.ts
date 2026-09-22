import { describe, expect, it } from 'vitest';
import { chartVisibleRange, clampChartViewport, isChartZoomed, panChartViewport, zoomChartViewport } from '../../src/core/chart-viewport';

describe('chart viewport interaction', () => {
  const domain = { start: 10, end: 110 };
  it('anchors zoom at the finger and follows movement of the pinch centroid', () => {
    expect(zoomChartViewport(domain, domain, 2, 0.25)).toEqual({ start: 22.5, end: 72.5 });
    expect(zoomChartViewport(domain, domain, 2, 0.25, 0.5)).toEqual({ start: 10, end: 60 });
    const zoomed = zoomChartViewport(domain, domain, 2);
    expect(zoomChartViewport(zoomed, domain, 0.5)).toEqual(domain);
  });
  it('bounds zoom and pan without shrinking at a boundary', () => {
    expect(zoomChartViewport(domain, domain, 1000)).toEqual({ start: 59.5, end: 60.5 });
    expect(panChartViewport({ start: 30, end: 50 }, domain, -100)).toEqual({ start: 10, end: 30 });
    expect(panChartViewport({ start: 30, end: 50 }, domain, 100)).toEqual({ start: 90, end: 110 });
    expect(clampChartViewport({ start: 0, end: 20 }, domain)).toEqual({ start: 10, end: 30 });
    expect(isChartZoomed(domain, domain)).toBe(false);
    expect(isChartZoomed({ start: 10, end: 60 }, domain)).toBe(true);
  });
  it('rejects invalid gesture values and keeps a visible viewport as live history rolls off', () => {
    for (const scale of [0, -1, Number.NaN, Infinity]) expect(zoomChartViewport(domain, domain, scale)).toEqual(domain);
    expect(clampChartViewport({ start: NaN, end: 0 }, domain)).toEqual(domain);
    expect(clampChartViewport({ start: 10, end: 30 }, { start: 20, end: 120 })).toEqual({ start: 20, end: 40 });
  });
  it('includes adjacent original observations when clipping, without inventing endpoints', () => {
    const input = [0, 1, 2, 3, 4, 5].map(elapsedSeconds => ({ elapsedSeconds }));
    const range = chartVisibleRange(input, { start: 1.5, end: 3.5 });
    const visible = input.slice(range.start, range.end);
    expect(visible.map(sample => sample.elapsedSeconds)).toEqual([1, 2, 3, 4]);
    expect(visible[0]).toBe(input[1]);
  });
});
