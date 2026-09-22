import { describe, expect, it } from 'vitest';
import { beginChartTransform, moveChartTransform, type ChartTouch } from '../../src/core/chart-touch';

const domain = { start: 0, end: 100 };
const view = { start: 20, end: 80 };
const pair: readonly ChartTouch[] = [{ id: 4, x: 50, y: 40 }, { id: 9, x: 150, y: 40 }];
const begin = () => beginChartTransform(pair, view, domain, 10, 200)!;

describe('two-finger chart geometry', () => {
  it('pans at constant finger separation without changing the visible span', () => {
    expect(moveChartTransform(begin(), [{ id: 4, x: 70, y: 70 }, { id: 9, x: 170, y: 70 }])).toEqual({ start: 14, end: 74 });
  });

  it('zooms around the original time under an off-center pinch', () => {
    // The centroid is at 45% of the plot: time 47 must remain at 45%.
    const result = moveChartTransform(begin(), [{ id: 4, x: 25, y: 40 }, { id: 9, x: 175, y: 40 }])!;
    expect(result.start).toBeCloseTo(29);
    expect(result.end).toBeCloseTo(69);
    expect(result.start + (result.end - result.start) * 0.45).toBeCloseTo(47);
  });

  it('combines centroid movement and changing separation in one update', () => {
    // Separation 100 -> 120 gives a 50-second window; centroid moves to 60%.
    const result = moveChartTransform(begin(), [{ id: 4, x: 70, y: 40 }, { id: 9, x: 190, y: 40 }])!;
    expect(result.start).toBeCloseTo(17);
    expect(result.end).toBeCloseTo(67);
    expect(result.start + (result.end - result.start) * 0.6).toBeCloseTo(47);
  });

  it('follows pointer IDs rather than the incoming array order', () => {
    const moved = [{ id: 9, x: 190, y: 40 }, { id: 4, x: 70, y: 40 }];
    expect(moveChartTransform(begin(), moved)).toEqual(moveChartTransform(begin(), [...moved].reverse()));
  });

  it('uses actual two-dimensional separation and ignores pure vertical translation', () => {
    expect(moveChartTransform(begin(), [{ id: 4, x: 50, y: 80 }, { id: 9, x: 150, y: 80 }])).toEqual(view);
    expect(moveChartTransform(begin(), [{ id: 4, x: 70, y: 20 }, { id: 9, x: 130, y: 100 }])).toEqual(view);
  });

  it('does not accumulate updates or mutate the starting viewport', () => {
    const initialView = { ...view };
    const initialDomain = { ...domain };
    const start = beginChartTransform(pair, initialView, initialDomain, 10, 200)!;
    initialView.start = -100;
    initialDomain.end = 200;
    const moved = [{ id: 4, x: 70, y: 40 }, { id: 9, x: 170, y: 40 }];
    expect(moveChartTransform(start, moved)).toEqual({ start: 14, end: 74 });
    expect(moveChartTransform(start, moved)).toEqual({ start: 14, end: 74 });
    expect(start.view).toEqual(view);
    expect(start.domain).toEqual(domain);
  });

  it('clamps navigation at the domain edge without shrinking the window', () => {
    expect(moveChartTransform(begin(), [{ id: 4, x: 1050, y: 40 }, { id: 9, x: 1150, y: 40 }])).toEqual({ start: 0, end: 60 });
    expect(moveChartTransform(begin(), [{ id: 4, x: -950, y: 40 }, { id: 9, x: -850, y: 40 }])).toEqual({ start: 40, end: 100 });
  });

  it('invalidates the old transform on dropped, added or replaced pointers', () => {
    const start = begin();
    for (const touches of [[], [pair[0]!], [...pair, { id: 12, x: 200, y: 40 }], [pair[0]!, { ...pair[1]!, id: 12 }]]) {
      expect(moveChartTransform(start, touches)).toBeNull();
    }
    const replacement = [pair[0]!, { ...pair[1]!, id: 12 }];
    const restarted = beginChartTransform(replacement, view, domain, 10, 200)!;
    expect(moveChartTransform(restarted, replacement)).toEqual(view);
  });

  it('requires exactly two distinct, separated pointers to start', () => {
    for (const touches of [[], [pair[0]!], [...pair, { id: 12, x: 200, y: 40 }], [{ ...pair[0]! }, { ...pair[1]!, id: 4 }], [{ ...pair[0]! }, { ...pair[0]!, id: 9 }]]) {
      expect(beginChartTransform(touches, view, domain, 10, 200)).toBeNull();
    }
  });

  it.each([NaN, Infinity, -Infinity])('rejects nonfinite coordinates %s at both gesture boundaries', invalid => {
    for (const key of ['x', 'y'] as const) {
      const touches = [{ ...pair[0]!, [key]: invalid }, pair[1]!];
      expect(beginChartTransform(touches, view, domain, 10, 200)).toBeNull();
      expect(moveChartTransform(begin(), touches)).toBeNull();
    }
    expect(beginChartTransform(pair, view, domain, invalid, 200)).toBeNull();
    expect(beginChartTransform(pair, view, domain, 10, invalid)).toBeNull();
  });

  it('rejects a missing plot width and a collapsed moving pinch', () => {
    for (const width of [0, -1]) expect(beginChartTransform(pair, view, domain, 10, width)).toBeNull();
    expect(moveChartTransform(begin(), [{ id: 4, x: 100, y: 40 }, { id: 9, x: 100.1, y: 40 }])).toBeNull();
  });
});
