import { PresentationCapabilities, PresentationOptions } from '../../shared/js';
import type { WorkoutOptions, WorkoutState } from './workouts';

export function presentationCapabilities(value: WorkoutState['capabilities']): PresentationCapabilities {
  return new PresentationCapabilities(
    value.phoneWorkout,
    value.watchWorkout,
    value.phoneHealth,
    value.watchHealth,
    value.gps,
    value.healthProvider,
    value.foregroundOnly,
  );
}

export function presentationOptions(value: { [K in keyof WorkoutOptions]: unknown }): PresentationOptions {
  return new PresentationOptions(
    value.indoor as boolean,
    (value.useWatch ?? null) as boolean | null,
    (value.saveToHealth ?? null) as boolean | null,
    (value.recordGPS ?? null) as boolean | null,
    (value.sampleHz ?? null) as number | null,
    (value.distanceSource ?? 'auto') as string,
    (value.batteryConsumptionSource ?? 'controller') as string,
  );
}
