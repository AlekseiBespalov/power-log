import type { WorkoutMetadata } from '../core/workouts';
import type { CatalogLoader } from './history-catalog';
import { workouts } from './workouts';

export const rideHistorySources: CatalogLoader<WorkoutMetadata>[] = [
  async request => (await workouts.getState()).supported ? workouts.list(request) : [],
];
