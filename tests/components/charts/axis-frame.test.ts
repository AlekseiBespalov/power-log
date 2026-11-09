import { expect, it } from 'vitest';
import { AXIS, axisFrame } from '../../../src/components/charts/axis-frame';

it('matches the iPhone axis inset, text frame, padding and edge clamping', () => {
  expect(AXIS).toEqual({ textHeight: 14, inset: 4, horizontalPadding: 4, verticalPadding: 2, gridOffset: 2 });
  expect(axisFrame('y', 8, 22.1, 360, 140)).toEqual({ x: 12, y: 12, width: 31, height: 18 });
  expect(axisFrame('y', 106, 22.1, 360, 140)).toEqual({ x: 12, y: 94, width: 31, height: 18 });
  expect(axisFrame('x', 0, 22.1, 360, 140)).toEqual({ x: 12, y: 118, width: 31, height: 18 });
  expect(axisFrame('x', 0.5, 22.1, 360, 140)).toEqual({ x: 164.5, y: 118, width: 31, height: 18 });
  expect(axisFrame('x', 1, 22.1, 360, 140)).toEqual({ x: 317, y: 118, width: 31, height: 18 });
});
