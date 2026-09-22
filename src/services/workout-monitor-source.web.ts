import type { DistanceSource } from '../core/distance';
import { browserWorkoutMonitorSource } from './browser-workout-monitor';
export const workoutMonitorSource = (id: string, live = false, distanceSource: DistanceSource = 'auto') => browserWorkoutMonitorSource(id, live, undefined, distanceSource);
