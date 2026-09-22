import type { TextStyle } from 'react-native';
import { DISTANCE_SOURCE_LABELS, distanceSourceCaption, type MetricSourceInfo } from '../core/distance';
import { StableLabel } from './stable-label';
import { colors } from './ui';

const variants = Object.values(DISTANCE_SOURCE_LABELS).map(label => `${label} · Partial`);

export function DistanceSourceCaption({ info, unavailable = 'Unavailable', testID, style }: {
  info?: MetricSourceInfo | null; unavailable?: string; testID?: string; style?: TextStyle;
}) {
  return <StableLabel testID={testID} value={info ? distanceSourceCaption(info) : unavailable}
    variants={variants} style={[{ color: colors.muted, fontSize: 11 }, style]} />;
}
