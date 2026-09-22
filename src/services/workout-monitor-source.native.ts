import { nativeMonitorSource } from './monitor-source.native';
import type { DistanceSource } from '../core/distance';
export const workoutMonitorSource = (id: string, live = false, distanceSource: DistanceSource = 'auto') => nativeMonitorSource('workout', id, live, distanceSource);
