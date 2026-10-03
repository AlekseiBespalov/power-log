export const GPS_MAX_HORIZONTAL_ACCURACY_M = 50;
export const GPS_MAX_GAP_S = 10;
export const GPS_MAX_SPEED_MPS = 40;
export const GPS_STATIONARY_MPS = 0.5;
export const ALTITUDE_MIN_M = -500;
export const ALTITUDE_MAX_M = 20000;
export const ALTITUDE_MAX_ACCURACY_M = 20;
export const ASCENT_MIN_M = 3;
const EARTH_RADIUS_M = 6_371_008.8;

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
  if (!Number.isFinite(speed) || speed < 0 || speed > GPS_MAX_SPEED_MPS) return NaN;
  if (!Number.isNaN(accuracy) && !(Number.isFinite(accuracy) && accuracy >= 0)) return NaN;
  return speed;
}

export function validVerticalAccuracy(accuracy: number): boolean {
  return accuracy > 0 && accuracy <= ALTITUDE_MAX_ACCURACY_M;
}

export function validAltitude(altitude: number, accuracy: number): boolean {
  return (
    Number.isFinite(altitude) &&
    altitude >= ALTITUDE_MIN_M &&
    altitude <= ALTITUDE_MAX_M &&
    validVerticalAccuracy(accuracy)
  );
}

export function validFix(fix: GpsFix): boolean {
  return (
    !fix.barrier &&
    Number.isFinite(fix.time) &&
    fix.time >= 0 &&
    Number.isFinite(fix.latitude) &&
    Number.isFinite(fix.longitude) &&
    fix.latitude >= -90 &&
    fix.latitude <= 90 &&
    fix.longitude >= -180 &&
    fix.longitude <= 180 &&
    Number.isFinite(fix.horizontalAccuracy) &&
    fix.horizontalAccuracy >= 0 &&
    fix.horizontalAccuracy <= GPS_MAX_HORIZONTAL_ACCURACY_M &&
    fix.interval !== 0
  );
}

export function gpsMeters(a: GpsFix, b: GpsFix): number {
  const p1 = (a.latitude * Math.PI) / 180;
  const p2 = (b.latitude * Math.PI) / 180;
  const dp = p2 - p1;
  const dl = ((b.longitude - a.longitude) * Math.PI) / 180;
  const h = Math.pow(Math.sin(dp / 2), 2) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2);
  return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(Math.max(0, Math.min(1, h))), Math.sqrt(Math.max(0, 1 - h)));
}

// Keeps copies of the latest fixes in two owned objects, so the copy before the latest stays intact.
class FixCopies {
  private readonly fixes = [emptyFix(), emptyFix()];
  private next = 0;

  copy(fix: GpsFix): GpsFix {
    const copy = this.fixes[this.next]!;
    this.next = 1 - this.next;
    copy.time = fix.time;
    copy.latitude = fix.latitude;
    copy.longitude = fix.longitude;
    copy.horizontalAccuracy = fix.horizontalAccuracy;
    copy.speed = fix.speed;
    copy.speedAccuracy = fix.speedAccuracy;
    copy.altitude = fix.altitude;
    copy.verticalAccuracy = fix.verticalAccuracy;
    copy.clockEpoch = fix.clockEpoch;
    copy.interval = fix.interval;
    copy.interruption = fix.interruption;
    copy.barrier = fix.barrier;
    return copy;
  }
}

export class GpsDistanceAccumulator {
  private previous: GpsFix | null = null;
  private readonly copies = new FixCopies();

  reset(): void {
    this.previous = null;
  }

  append(fix: GpsFix): number {
    if (!validFix(fix)) {
      this.reset();
      return NaN;
    }
    return this.appendValid(fix);
  }

  appendValid(fix: GpsFix): number {
    const old = this.previous;
    this.previous = this.copies.copy(fix);
    if (!old) return NaN;
    const dt = fix.time - old.time;
    if (dt <= 0) {
      this.reset();
      if (dt === 0 && (old.clockEpoch !== fix.clockEpoch || old.interval !== fix.interval))
        this.previous = this.copies.copy(fix);
      return NaN;
    }
    if (!(dt <= GPS_MAX_GAP_S && old.interval === fix.interval && old.clockEpoch === fix.clockEpoch)) return NaN;
    const chord = gpsMeters(old, fix);
    if (!(chord / dt <= GPS_MAX_SPEED_MPS)) return NaN;
    const stationary =
      validSpeed(old.speed, old.speedAccuracy) < GPS_STATIONARY_MPS &&
      validSpeed(fix.speed, fix.speedAccuracy) < GPS_STATIONARY_MPS;
    return stationary ? 0 : chord;
  }
}

export class FitGeometry {
  ascent = 0;
  descent = 0;
  altitudeSegments = 0;
  private previous: GpsFix | null = null;
  private anchor = NaN;
  private readonly accumulator = new GpsDistanceAccumulator();
  private readonly copies = new FixCopies();

  reset(): void {
    this.previous = null;
    this.anchor = NaN;
    this.accumulator.reset();
  }

  accept(fix: GpsFix): boolean {
    if (!validFix(fix)) {
      this.reset();
      return false;
    }
    const meters = this.accumulator.appendValid(fix);
    let startsSegment = true;
    const old = this.previous;
    if (old) {
      if (!Number.isNaN(meters) && continuous(old, fix)) {
        startsSegment = false;
        if (validAltitude(fix.altitude, fix.verticalAccuracy) && validVerticalAccuracy(old.verticalAccuracy)) {
          if (Number.isNaN(this.anchor)) this.anchor = fix.altitude;
          else {
            this.altitudeSegments++;
            const delta = fix.altitude - this.anchor;
            if (Math.abs(delta) >= Math.max(ASCENT_MIN_M, Math.max(fix.verticalAccuracy, old.verticalAccuracy))) {
              if (delta > 0) this.ascent += delta;
              else this.descent -= delta;
              this.anchor = fix.altitude;
            }
          }
        } else this.anchor = NaN;
      } else this.anchor = NaN;
    }
    if (startsSegment && validAltitude(fix.altitude, fix.verticalAccuracy)) this.anchor = fix.altitude;
    this.previous = this.copies.copy(fix);
    return true;
  }
}

function continuous(a: GpsFix, b: GpsFix): boolean {
  return (
    a.interval !== 0 &&
    a.interval === b.interval &&
    b.time >= a.time &&
    a.clockEpoch === b.clockEpoch &&
    a.interruption === b.interruption
  );
}
