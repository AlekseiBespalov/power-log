import type { TelemetryAdapter } from './adapter';
import { BrowserWorkoutRecorder } from './browser-workout-recorder';
export const workouts = new BrowserWorkoutRecorder();
export const setWorkoutTelemetrySource = (adapter: TelemetryAdapter) => workouts.setTelemetrySource(adapter);
export { browserWorkoutMonitorSource } from './browser-workout-monitor';
