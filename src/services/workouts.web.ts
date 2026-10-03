import { cleanBrowserExports } from './browser-export-sink';
import { BrowserWorkoutRecorder } from './browser-workout-recorder';
import { deviceAdapter } from './device';
export const workouts = new BrowserWorkoutRecorder();
workouts.setTelemetrySource(deviceAdapter);
void cleanBrowserExports().catch(() => {});
