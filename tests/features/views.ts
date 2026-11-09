import type {
  Availability,
  ConnectionView,
  FailureCode,
  RecordingView,
  RideAllowed,
  RideOptions,
} from '../../modules/power-log';

export const yes: Availability = { enabled: true, reason: null };
export const no = (reason: FailureCode): Availability => ({ enabled: false, reason });

export const indoorOptions: RideOptions = {
  sampleRate: '8',
  indoor: true,
  useWatch: false,
  saveToHealth: false,
  recordGps: false,
  distanceSource: 'controller',
  batteryMethod: 'controller',
};

export function rideAllowed(overrides: Partial<RideAllowed> = {}): RideAllowed {
  return { start: yes, pause: yes, resume: yes, lap: yes, finish: yes, discard: yes, ...overrides };
}

export function recordingView(overrides: Partial<RecordingView> = {}): RecordingView {
  return {
    phase: null,
    ride: null,
    pending: null,
    timer: { state: 'unavailable', baseUs: 0, originMonoUs: null },
    allowed: rideAllowed(),
    finishRequest: null,
    sources: { controller: 'receiving', heartRate: 'off', gps: 'off' },
    health: null,
    otherTab: false,
    failure: null,
    ...overrides,
  };
}

export const runningView = (rideId = 'r1', overrides: Partial<RecordingView> = {}) =>
  recordingView({
    phase: 'running',
    ride: { rideId, options: indoorOptions },
    timer: { state: 'running', baseUs: 0, originMonoUs: 0 },
    ...overrides,
  });

export function connectionView(overrides: Partial<ConnectionView> = {}): ConnectionView {
  return {
    state: 'idle',
    display: 'unavailable',
    heldUntilMonoUs: null,
    bike: null,
    found: [],
    faultCode: null,
    failure: null,
    allowed: { startSearch: yes, stopSearch: yes, connect: yes, disconnect: yes },
    ...overrides,
  };
}
