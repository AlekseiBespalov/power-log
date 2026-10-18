import {
  GPSAcquisitionClock,
  GPSLocationInput,
  GPSTimelineInput,
  protocolTimestampMicroseconds,
} from '../../shared/js';
import type { RideLocationRow } from './browser-ride-store';
import { RideFailureError } from '../core/failures';
import { browserFailure } from './browser-failures';

const authorization = new WeakMap<Geolocation, string>();
const permissionDenied = (error: GeolocationPositionError) => {
  if (error.code === 1) authorization.set(navigator.geolocation, 'denied');
};

export const browserGPSAvailable = () => typeof navigator !== 'undefined' && Boolean(navigator.geolocation);
export async function browserGPSPermission(): Promise<string> {
  if (!browserGPSAvailable()) return 'unavailable';
  try {
    const permission = await navigator.permissions?.query({ name: 'geolocation' });
    if (!permission) return authorization.get(navigator.geolocation) ?? 'notDetermined';
    return permission.state === 'granted'
      ? 'authorizedWhenInUse'
      : permission?.state === 'denied'
        ? 'denied'
        : 'notDetermined';
  } catch {
    return authorization.get(navigator.geolocation) ?? 'notDetermined';
  }
}
export async function requestBrowserGPSPermission(): Promise<void> {
  if (!browserGPSAvailable()) throw new RideFailureError('locationDenied');
  await new Promise<void>((resolve, reject) =>
    navigator.geolocation.getCurrentPosition(
      () => {
        authorization.set(navigator.geolocation, 'authorizedWhenInUse');
        resolve();
      },
      error => {
        permissionDenied(error);
        reject(browserFailure(error, 'location'));
      },
      {
        enableHighAccuracy: true,
        maximumAge: 0,
        timeout: 15000,
      },
    ),
  );
}
export class BrowserGPS {
  private watch?: number;
  private generation = 0;
  private acquisition = new GPSAcquisitionClock();
  private barrier = false;
  private clockEpoch = crypto.randomUUID();
  constructor(
    private readonly now: () => number,
    private readonly utc: () => string,
  ) {}
  begin(recovered = false): void {
    this.stop();
    this.clockEpoch = crypto.randomUUID();
    this.acquisition = new GPSAcquisitionClock();
    this.barrier = recovered;
  }
  breakContinuity(): void {
    this.barrier = true;
  }
  start(receive: (position: GeolocationPosition) => void, error: (error: GeolocationPositionError) => void): void {
    if (!browserGPSAvailable() || this.watch !== undefined) return;
    const generation = ++this.generation;
    this.watch = navigator.geolocation.watchPosition(
      position => {
        if (
          generation === this.generation &&
          (typeof document === 'undefined' || document.visibilityState === 'visible')
        ) {
          authorization.set(navigator.geolocation, 'authorizedWhenInUse');
          receive(position);
        }
      },
      failure => {
        if (generation === this.generation) {
          this.barrier = true;
          permissionDenied(failure);
          error(failure);
        }
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 },
    );
  }
  stop(): void {
    ++this.generation;
    if (this.watch !== undefined && browserGPSAvailable()) navigator.geolocation.clearWatch(this.watch);
    this.watch = undefined;
  }
  map(
    position: GeolocationPosition,
    origin: number,
    activeStart: number,
    startedAt: string,
  ):
    | Omit<
        RideLocationRow,
        'recordingId' | 'eventId' | 'producer' | 'producerSequence' | 'queryUs' | 'elapsedUs' | 'active' | 'interval'
      >
    | undefined {
    const uptime = this.now(),
      coords = position.coords;
    if (!Number.isFinite(position.timestamp)) {
      this.barrier = true;
      return;
    }
    const milliseconds = Math.floor(position.timestamp);
    const fraction = Math.floor((position.timestamp - milliseconds) * 1e6);
    let originalTimestamp: string;
    try {
      originalTimestamp = new Date(milliseconds)
        .toISOString()
        .replace('Z', fraction ? `${String(fraction).padStart(6, '0')}Z` : 'Z');
      protocolTimestampMicroseconds(originalTimestamp);
    } catch {
      this.barrier = true;
      return;
    }
    const batch = this.acquisition.mapBatchAt(
      [new GPSLocationInput(originalTimestamp, coords.latitude, coords.longitude, coords.accuracy)],
      this.utc(),
      uptime,
      new GPSTimelineInput(this.clockEpoch, protocolTimestampMicroseconds(startedAt), origin, origin, null, false),
    );
    if (batch.discontinuity) this.barrier = true;
    const fix = batch.fixes[0];
    if (!fix || !this.acquisition.admit(fix)) return;
    if (fix.acquisitionMonotonic < activeStart) return;
    const row = {
      timestamp: originalTimestamp,
      originalTimestamp,
      elapsedSeconds: fix.elapsedSeconds,
      originalElapsedSeconds: fix.elapsedSeconds,
      acquiredAtSeconds: fix.acquisitionMonotonic,
      clockEpoch: this.clockEpoch,
      distanceBarrier: this.barrier || fix.uncertain,
      latitude: coords.latitude,
      longitude: coords.longitude,
      originalAltitudeDatum: 'wgs84Ellipsoid' as const,
      ...(Number.isFinite(coords.accuracy) ? { horizontalAccuracyM: coords.accuracy } : {}),
      ...(coords.speed !== null && Number.isFinite(coords.speed) && coords.speed >= 0
        ? { speedMps: coords.speed }
        : {}),
      ...(coords.heading !== null && Number.isFinite(coords.heading) && coords.heading >= 0
        ? { courseDegrees: coords.heading }
        : {}),
      ...(coords.altitude !== null && Number.isFinite(coords.altitude)
        ? { originalAltitudeMeters: coords.altitude }
        : {}),
      ...(coords.altitudeAccuracy !== null && Number.isFinite(coords.altitudeAccuracy)
        ? { originalVerticalAccuracyM: coords.altitudeAccuracy }
        : {}),
    };
    this.barrier = false;
    return row;
  }
}
