import { useLayoutEffect, useRef, useState } from 'react';
import { G, Rect, Text as SvgText } from 'react-native-svg';
import { colors, systemFontFamily, type } from '../ui';
import { AXIS, axisFrame } from './axis-frame';

export function ChartAxis({
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
  const frame = axisFrame(axis, position, textWidth, width, height);
  return (
    <G {...{ 'data-axis': axis }}>
      <Rect {...frame} fill={colors.bg} />
      <SvgText
        ref={label}
        x={frame.x + AXIS.horizontalPadding}
        y={frame.y + AXIS.verticalPadding}
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
