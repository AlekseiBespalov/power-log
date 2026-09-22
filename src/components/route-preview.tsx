import { useMemo } from 'react';
import Svg, { Path, Rect } from 'react-native-svg';
import type { RoutePoint } from '../core/workouts';
import { routePaths } from '../core/route';
import { Body, colors } from './ui';

export function RoutePreview({ points }: { points: RoutePoint[] }) {
  const paths = useMemo(() => routePaths(points, 640, 260), [points]);
  if (!paths.length) return <Body muted>No route recorded</Body>;
  return <Svg width="100%" height={200} viewBox="0 0 640 260" accessibilityLabel="Recorded GPS route preview">
    <Rect x={0} y={0} width={640} height={260} rx={8} fill={colors.bg} />
    {paths.map((path, index) => <Path key={index} d={path} fill="none" stroke={colors.route} strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" />)}
  </Svg>;
}
