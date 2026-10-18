import { AnalyticsGPSKernel, analyticsValid } from '../../../../shared/js';
import { fitOriginal } from './core';

export interface GpsFix {
  time: number;
  latitude: number;
  longitude: number;
  horizontalAccuracy: number;
  speed: number;
  speedAccuracy: number;
  altitude: number;
  verticalAccuracy: number;
  clockEpoch: string | null;
  interval: number;
  interruption: number;
  barrier: boolean;
}

export function emptyFix(): GpsFix {
  return {
    time: NaN,
    latitude: NaN,
    longitude: NaN,
    horizontalAccuracy: NaN,
    speed: NaN,
    speedAccuracy: NaN,
    altitude: NaN,
    verticalAccuracy: NaN,
    clockEpoch: null,
    interval: 0,
    interruption: 0,
    barrier: false,
  };
}

export function validSpeed(speed: number, accuracy: number): number {
  return analyticsValid('speedMps', speed, Number.isNaN(accuracy) ? null : accuracy) ? speed : NaN;
}

export function validAltitude(altitude: number, accuracy: number): boolean {
  return analyticsValid('altitudeMeters', altitude, null) && analyticsValid('verticalAccuracyM', accuracy, null);
}

export class GpsDistanceAccumulator {
  protected readonly kernel: AnalyticsGPSKernel;
  private identity = 0;

  constructor(fullContinuity = false) {
    this.kernel = new AnalyticsGPSKernel(fullContinuity);
  }

  reset(): void {
    this.kernel.reset();
  }

  append(fix: GpsFix): number {
    if (!Number.isFinite(fix.time)) {
      this.reset();
      return NaN;
    }
    const interval = this.kernel.append(
      fitOriginal({
        elapsed: fix.time,
        kind: 'location',
        sequence: ++this.identity,
        clockEpoch: fix.clockEpoch,
        interruption: fix.interruption,
        barrier: fix.barrier,
        values: {
          latitude: fix.latitude,
          longitude: fix.longitude,
          horizontalAccuracyM: fix.horizontalAccuracy,
          speedMps: fix.speed,
          speedAccuracyMps: fix.speedAccuracy,
          altitudeMeters: fix.altitude,
          verticalAccuracyM: fix.verticalAccuracy,
        },
      }),
      fix.interval === 0 ? null : fix.interval,
    );
    return interval?.distanceQ == null ? NaN : Number(interval.distanceQ) / 1e6;
  }
}

export class FitGeometry extends GpsDistanceAccumulator {
  constructor() {
    super(true);
  }
  get ascent(): number {
    return Number(this.kernel.ascentQ() ?? 0) / 1000;
  }
  get descent(): number {
    return Number(this.kernel.descentQ() ?? 0) / 1000;
  }
  get altitudeSegments(): number {
    return Number(this.kernel.altitudeSegments);
  }

  accept(fix: GpsFix): boolean {
    this.append(fix);
    return Number.isFinite(fix.time) && this.kernel.accepted;
  }
}
