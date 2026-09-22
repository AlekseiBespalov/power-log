import type { WorkoutMetadata } from '../core/workouts';
import type { CatalogLoader } from './history-catalog';
import { workouts } from './workouts.web';
export const rideHistorySources: CatalogLoader<WorkoutMetadata>[] = [request => workouts.list(request)];
