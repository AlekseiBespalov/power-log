import { useLayoutEffect, useRef, useState } from 'react';
import { G, Rect, Text as SvgText } from 'react-native-svg';
import { MONITOR_AXIS, monitorAxisFrame } from '../core/monitor-raster';
import { colors, systemFontFamily, type } from './ui';

export function MonitorRasterAxis({
  axis,
  position,
  text,
  width,
  height,
}: {
  axis: 'x' | 'y';
  position: number;
  text: string;
  width: number;
  height: number;
}) {
  const label = useRef<SvgText>(null);
  const [textWidth, setTextWidth] = useState(0);
  useLayoutEffect(() => {
    const element = (label.current as unknown as { elementRef?: { current: SVGTextElement } })?.elementRef?.current;
    if (!element) return;
    const measure = () => setTextWidth(element.getComputedTextLength());
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [text]);
  const frame = monitorAxisFrame(axis, position, textWidth, width, height);
  return (
    <G {...{ 'data-axis': axis }}>
      <Rect {...frame} fill={colors.bg} />
      <SvgText
        ref={label}
        x={frame.x + MONITOR_AXIS.horizontalPadding}
        y={frame.y + MONITOR_AXIS.verticalPadding}
        alignmentBaseline="text-before-edge"
        textAnchor="start"
        fill={colors.muted}
        fontFamily={systemFontFamily}
        fontSize={type.axis}
      >
        {text}
      </SvgText>
    </G>
  );
}
