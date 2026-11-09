import { useId, useMemo } from 'react';
import Svg, { Circle, ClipPath, Defs, G, Line, Path, Rect } from 'react-native-svg';
import { kit } from '../../../modules/power-log';
import { colors } from '../ui';
import { ChartAxis } from './chart-axis';
import { isVoltage } from './chart-groups';
import type { ChartRendererProps } from './chart-renderer.types';
import { lineGeometry } from './chart-series';
import { CHART_INSET, viewportTransform } from './chart-viewport';

const leftInset = CHART_INSET.left;
const rightInset = CHART_INSET.right;

export function SvgChart({
  group,
  series,
  frame,
  view,
  scale,
  width,
  height,
  cursor,
  reference,
  marks,
  tails,
  hasSamples,
}: ChartRendererProps) {
  const clipID = `chart-${useId().replace(/[^a-z0-9]/gi, '')}`;
  const plotWidth = Math.max(1, width - leftInset - rightInset);
  const duration = view.end - view.start;
  const geometryDuration = Math.max(1e-9, frame.end - frame.start);
  const y = (value: number) => 8 + ((scale.max - value) / (scale.max - scale.min)) * (height - 34);
  const displayX = (seconds: number) => leftInset + ((seconds - view.start) / duration) * plotWidth;
  const transform = viewportTransform(frame, view, leftInset, plotWidth);
  const paths = useMemo(() => {
    const x = (seconds: number) => leftInset + ((seconds - frame.start) / geometryDuration) * plotWidth;
    const plotY = (value: number) => 8 + ((scale.max - value) / (scale.max - scale.min)) * (height - 34);
    return group.metrics.map(metric => ({ metric, ...lineGeometry(series[metric.id], metric, x, plotY) }));
  }, [series, group.metrics, frame.start, geometryDuration, plotWidth, scale, height]);
  const inside = (seconds: number | null) =>
    seconds !== null && Number.isFinite(seconds) && seconds >= view.start && seconds <= view.end;
  return (
    <Svg pointerEvents="none" width="100%" height={height} viewBox={`0 0 ${width} ${height}`}>
      <Defs>
        <ClipPath id={clipID}>
          <Rect x={leftInset} y={0} width={plotWidth} height={height - 24} />
        </ClipPath>
      </Defs>
      {[0, 0.5, 1].map(fraction => {
        const at = y(scale.min + fraction * (scale.max - scale.min));
        return (
          <G key={fraction}>
            <Line x1={leftInset} x2={width - rightInset} y1={at} y2={at} stroke={colors.border} strokeDasharray="2 5" />
          </G>
        );
      })}
      <G clipPath={`url(#${clipID})`}>
        {paths.map(({ metric, path, isolated }) => (
          <G key={metric.id}>
            <Path
              d={path}
              transform={`matrix(${transform.scale} 0 0 1 ${transform.offset} 0)`}
              vectorEffect="non-scaling-stroke"
              fill="none"
              stroke={metric.color}
              strokeWidth={1.8}
              strokeLinejoin="round"
              strokeLinecap="round"
            />
            {isolated.map((point, index) => (
              <Circle
                key={index}
                cx={displayX(point.atS)}
                cy={y(metric.display(point.value))}
                r={1.8}
                fill={metric.color}
              />
            ))}
          </G>
        ))}
        {tails.map(tail => {
          if (tail.startS > view.end || tail.endS < view.start) return null;
          const at = y(tail.metric.display(tail.value));
          return (
            <Path
              key={`held-${tail.metric.id}`}
              d={`M${displayX(tail.startS)},${at} L${displayX(tail.endS)},${at}`}
              stroke={tail.metric.color}
              strokeWidth={1.8}
            />
          );
        })}
        {inside(reference) && group.metrics.some(metric => isVoltage(metric.id)) && (
          <Line
            x1={displayX(reference!)}
            x2={displayX(reference!)}
            y1={4}
            y2={height - 26}
            stroke={colors.muted}
            strokeWidth={1}
            strokeDasharray="3 4"
          />
        )}
        {inside(cursor) && (
          <Line
            x1={displayX(cursor!)}
            x2={displayX(cursor!)}
            y1={4}
            y2={height - 26}
            stroke={colors.text}
            strokeWidth={1}
          />
        )}
        {marks.map(({ metric, cursor: selected, reference: a }) => (
          <G key={`selected-${metric.id}`}>
            {selected && inside(selected.atS) && (
              <Circle
                cx={displayX(selected.atS)}
                cy={y(metric.display(selected.value))}
                r={4}
                fill={metric.color}
                stroke={colors.surface}
                strokeWidth={2}
              />
            )}
            {isVoltage(metric.id) && a && inside(a.atS) && (
              <Circle
                cx={displayX(a.atS)}
                cy={y(metric.display(a.value))}
                r={4}
                fill={colors.surface}
                stroke={metric.color}
                strokeWidth={1.5}
              />
            )}
          </G>
        ))}
      </G>
      {hasSamples &&
        [0, 0.5, 1].flatMap(fraction => {
          const value = scale.min + fraction * (scale.max - scale.min);
          return [
            <ChartAxis
              key={`y-${fraction}`}
              axis="y"
              position={y(value)}
              text={value.toFixed(scale.decimals)}
              width={width}
              height={height}
            />,
            <ChartAxis
              key={`x-${fraction}`}
              axis="x"
              position={fraction}
              text={kit.formatDuration((view.start + duration * fraction) * 1e6)}
              width={width}
              height={height}
            />,
          ];
        })}
    </Svg>
  );
}
