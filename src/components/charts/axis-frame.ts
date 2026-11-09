import { CHART_INSET } from './chart-viewport';

export const AXIS = {
  textHeight: 14,
  inset: 4,
  horizontalPadding: 4,
  verticalPadding: 2,
  gridOffset: 2,
} as const;

export function axisFrame(axis: 'x' | 'y', position: number, textWidth: number, width: number, height: number) {
  const plotWidth = width - CHART_INSET.left - CHART_INSET.right;
  const labelWidth = Math.min(
    Math.ceil(textWidth) + AXIS.horizontalPadding * 2,
    Math.max(0, plotWidth - AXIS.inset * 2),
  );
  const labelHeight = AXIS.textHeight + AXIS.verticalPadding * 2;
  const left = CHART_INSET.left + AXIS.inset;
  const top = 8 + AXIS.inset;
  const right = width - CHART_INSET.right - AXIS.inset - labelWidth;
  const bottom = Math.max(top, height - 24 - AXIS.inset - labelHeight);
  return {
    x: axis === 'y' ? left : Math.max(left, Math.min(right, CHART_INSET.left + plotWidth * position - labelWidth / 2)),
    y: axis === 'y' ? Math.min(bottom, Math.max(top, position + AXIS.gridOffset)) : height - AXIS.inset - labelHeight,
    width: labelWidth,
    height: labelHeight,
  };
}
