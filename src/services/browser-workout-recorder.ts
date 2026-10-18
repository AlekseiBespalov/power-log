import { browserConsumptionSummary } from './browser-distance-store';
import { BrowserGPS, browserGPSAvailable, browserGPSPermission, requestBrowserGPSPermission } from './browser-gps';
import { browserSummary } from './browser-analytics';
import { controllerStreamStatus, gpsStreamStatus } from '../../shared/js';
import { samplePresentationTime, type NativeState, type SampleDelivery, type TelemetrySample } from '../core/types';
import { validateSample } from '../core/validation';
import { RideFailureError, rideFailure } from '../core/failures';
import { browserFailure, browserFailureCode } from './browser-failures';
import {
  effectiveWorkoutOptions,
  workoutInProgress,
  unavailableWorkoutState,
  type WorkoutAdapter,
  type WorkoutOptions,
  type WorkoutState,
  type WorkoutPermissionStatus,
  type WorkoutDetail,
  type RideAction,
} from '../core/workouts';
import type { CatalogRequest } from '../core/catalog';
import type { TelemetryAdapter } from './adapter';
import {
  BROWSER_BATCH_TARGET,
  BROWSER_OWNER_WORK_LOCK,
  browserRideStore,
  browserStoragePersistence,
  metadataOf,
  requestBrowserStoragePersistence,
  type BrowserRideStore,
  type BrowserRide,
  type RideRow,
  type RideLocationRow,
} from './browser-ride-store';
import { registerBrowserWorkoutClock } from './browser-workout-monitor';
import { ensureBrowserDistance, retainBrowserDistance } from './browser-distance-store';
import { int64, secondsUs, timestampUs } from './browser-storage-keys';
import type { DistanceSource } from '../core/distance';

export const BROWSER_RECORDING_LOCK = 'power-log-recording';
const permissions: WorkoutPermissionStatus = {
  health: { available: false, writeAuthorization: {} },
  location: 'unavailable',
  locationServicesEnabled: false,
  locationAccuracyAuthorization: 'unknown',
};
type Clock = { elapsed: number; timer: number; at: string };
type SampleEntry =
  { kind: 'sample'; row: RideRow; clock: Clock } | { kind: 'location'; row: RideLocationRow; clock: Clock };
type Action = Exclude<RideAction, 'start'>;
// At 8 Hz this caps a stalled writer at roughly 32 seconds. Lost observations form a gap, never invented values.
const MAX_BUFFERED_OBSERVATIONS = 256;

/** Hold the Web Lock until explicitly released. A busy tab is never displaced. */
async function acquireLock(name = BROWSER_RECORDING_LOCK, ifAvailable = true): Promise<(() => Promise<void>) | null> {
  if (typeof navigator === 'undefined' || !navigator.locks) throw new RideFailureError('actionUnavailable');
  return new Promise((resolve, reject) => {
    const finished = navigator.locks
      .request(name, { ifAvailable }, async lock => {
        if (!lock) {
          resolve(null);
          return;
        }
        await new Promise<void>(release =>
          resolve(() => {
            release();
            return finished;
          }),
        );
      })
      .catch(reject);
  });
}

export class BrowserWorkoutRecorder implements WorkoutAdapter {
  private source?: TelemetryAdapter;
  private unsubscribe?: () => void;
  private sourceState?: NativeState;
  private deviceID?: string;
  private record: BrowserRide | null = null;
  private token?: string;
  private release?: () => Promise<void>;
  private released?: Promise<void>;
  private releaseClock?: () => void;
  private listeners = new Set<(state: WorkoutState) => void>();
  private entries: SampleEntry[] = [];
  private flushWork?: Promise<void>;
  private flushTimer?: ReturnType<typeof setTimeout>;
  private displayTimer?: ReturnType<typeof setInterval>;
  private checking?: Promise<WorkoutState>;
  private action: RideAction | null = null;
  private failure?: string;
  private foreign = false;
  private accepting = false;
  private captureFailed = false;
  private epoch = 0;
  private activeEpoch = 0;
  private timerAtPhase = 0;
  private phaseAt = 0;
  private interval = 0;
  private nextSequence = 0;
  private nextProducerSequence = 1n;
  private nextBrowserSequence = 2n;
  private gps: BrowserGPS;
  private gpsProviderStatus: string | null = null;
  private latestGPS?: RideLocationRow;
  private visibility?: () => void;
  private interruptionIndex = 0;
  private latestReceived?: number;
  private get admissionPhase() {
    return this.record?.phase ?? 'idle';
  }
  constructor(
    private readonly store: BrowserRideStore = browserRideStore,
    private readonly now = () => performance.now() / 1000,
    private readonly utc = () => new Date().toISOString(),
  ) {
    this.gps = new BrowserGPS(now, utc);
  }

  setTelemetrySource(source: TelemetryAdapter): void {
    if (source === this.source) return;
    if (this.release || this.action) throw new RideFailureError('rideActive');
    this.unsubscribe?.();
    this.source = source;
    this.sourceState = undefined;
    this.unsubscribe = source.subscribe({
      device: () => {},
      state: state => {
        this.sourceState = state;
        this.emit();
      },
      sample: (sample, delivery) => this.receive(sample, delivery),
    });
    void source
      .getState()
      .then(state => {
        if (this.source === source) {
          this.sourceState = state;
          this.emit();
        }
      })
      .catch(() => {});
  }
  private clock(now = this.now(), at = this.utc()): Clock {
    if (!this.record) return { elapsed: 0, timer: 0, at };
    if (this.record.phase === 'interrupted' || this.record.phase === 'completed')
      return { elapsed: this.record.elapsedSeconds, timer: this.record.timerSeconds, at: this.record.checkpointAt };
    return {
      elapsed: Math.max(0, now - this.epoch),
      timer: this.timerAtPhase + (this.record.phase === 'running' ? Math.max(0, now - this.phaseAt) : 0),
      at,
    };
  }
  private snapshot(): WorkoutState {
    const supported = typeof indexedDB !== 'undefined' && typeof navigator !== 'undefined' && Boolean(navigator.locks);
    const timer = this.accepting ? this.clock().timer : (this.record?.timerSeconds ?? 0);
    const age = this.latestReceived === undefined ? null : Math.max(0, this.now() - this.latestReceived);
    const phase = (this.record?.phase ?? 'idle') as WorkoutState['phase'];
    const controllerStatus = controllerStreamStatus(
      workoutInProgress(phase),
      phase,
      age,
    ) as WorkoutState['streams']['controller']['status'];
    const gpsStatus = gpsStreamStatus(
      Boolean(this.record?.recordGPS && workoutInProgress(phase)),
      phase,
      this.latestGPS ? Math.max(0, this.now() - this.latestGPS.acquiredAtSeconds) : null,
      this.latestGPS?.horizontalAccuracyM ?? null,
      this.gpsProviderStatus,
    ) as WorkoutState['streams']['gps']['status'];
    return {
      ...unavailableWorkoutState,
      supported,
      capabilities: {
        phoneWorkout: false,
        watchWorkout: false,
        phoneHealth: false,
        watchHealth: false,
        healthProvider: null,
        gps: browserGPSAvailable(),
        foregroundOnly: true,
      },
      id: this.record?.id ?? null,
      phase,
      indoor: this.record?.indoor ?? false,
      useWatch: false,
      saveToHealth: false,
      recordGPS: this.record?.recordGPS ?? false,
      distanceSource: this.record?.distanceSource,
      timerSeconds: timer,
      pendingAction: this.action,
      recoveryState: 'idle',
      foreignOwner: this.foreign ? 'anotherBrowserTab' : null,
      healthKitState: 'notRequested',
      collectionRevision: this.record ? this.record.revision : null,
      streams: {
        ...unavailableWorkoutState.streams,
        controller: { status: controllerStatus },
        gps: {
          status: gpsStatus,
          source: 'phone',
          accuracyMeters: this.latestGPS?.horizontalAccuracyM ?? null,
        },
      },
      failure: rideFailure(this.failure),
    };
  }
  private emit() {
    const state = this.snapshot();
    this.listeners.forEach(listener => listener(state));
  }
  subscribe(listener: (state: WorkoutState) => void) {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
  async getState(): Promise<WorkoutState> {
    if (this.release || this.action || typeof indexedDB === 'undefined') return this.snapshot();
    if (this.checking) return this.checking;
    this.checking = (async () => {
      const release = typeof navigator !== 'undefined' && navigator.locks ? await acquireLock() : null;
      try {
        let current = await this.store.current();
        if (release && current) {
          const retained = current;
          try {
            current = await this.store.interruptCurrent();
            this.failure = undefined;
          } catch (error) {
            const code = browserFailureCode(error, 'storage');
            if (code !== 'storageFull' && code !== 'storageFailed') throw error;
            // Keep the readable ride actionable even when the interruption cannot be written.
            current = { ...retained, phase: 'interrupted', interrupted: true };
            this.failure = code;
          }
        }
        if (current) this.record = current;
        else if (this.foreign || (this.record && !(await this.store.find(this.record.id)))) this.record = null;
        this.foreign = !release && Boolean(current);
        return this.snapshot();
      } finally {
        await release?.();
      }
    })();
    try {
      return await this.checking;
    } finally {
      this.checking = undefined;
    }
  }
  async getPermissions(): Promise<WorkoutPermissionStatus> {
    const location = await browserGPSPermission();
    if (['denied', 'restricted', 'notDetermined', 'unavailable'].includes(location)) this.gpsProviderStatus = location;
    return {
      ...permissions,
      location,
      locationServicesEnabled: browserGPSAvailable(),
      locationAccuracyAuthorization: 'unknown',
    };
  }
  async requestPermissions(options: WorkoutOptions) {
    const effective = effectiveWorkoutOptions(options, this.snapshot().capabilities);
    if (effective.recordGPS) await requestBrowserGPSPermission();
    return this.getPermissions();
  }
  async start(options: WorkoutOptions): Promise<WorkoutState> {
    if (this.release || this.action) throw new RideFailureError(this.action ? 'actionPending' : 'rideActive');
    const frozen = effectiveWorkoutOptions(options, this.snapshot().capabilities);
    this.action = 'start';
    this.emit();
    let releaseAdmission: (() => Promise<void>) | null = null;
    let begun: string | undefined;
    try {
      releaseAdmission = await acquireLock(BROWSER_OWNER_WORK_LOCK, false);
      await this.checking;
      await this.released;
      this.release = (await acquireLock()) ?? undefined;
      if (!this.release) throw new RideFailureError('ownerForeign');
      const source = this.source;
      const state = await source?.getState();
      if (source?.kind !== 'web' || !state?.deviceId || state.status !== 'connected')
        throw new RideFailureError('bikeRequired');
      if (await this.store.interruptCurrent()) throw new RideFailureError('rideActive');
      void requestBrowserStoragePersistence();
      this.deviceID = state.deviceId;
      this.epoch = this.now();
      this.activeEpoch = this.epoch;
      this.phaseAt = this.epoch;
      this.timerAtPhase = 0;
      this.token = crypto.randomUUID();
      this.record = await this.store.begin(
        this.token,
        {
          indoor: frozen.indoor,
          sampleHz: frozen.sampleHz,
          recordGPS: frozen.recordGPS,
          distanceSource: frozen.distanceSource === 'gps:phone' ? 'gps:phone' : 'controller',
          batteryConsumptionSource: frozen.batteryConsumptionSource,
        },
        this.utc(),
      );
      begun = this.record.id;
      try {
        await source?.setSampleRate?.(this.record.sampleHz);
      } catch (error) {
        throw browserFailure(error, 'bluetooth');
      }
      if (this.deviceID) source?.setWorkoutOwner?.(this.deviceID);
      this.sourceState = await source?.getState();
      if (
        this.deviceID &&
        (!this.sourceState ||
          !['connected', 'reconnecting'].includes(this.sourceState.status) ||
          this.sourceState.deviceId !== this.deviceID)
      )
        throw new RideFailureError('startInterrupted');
      this.failure = undefined;
      this.captureFailed = false;
      this.foreign = false;
      this.interval = 0;
      this.nextSequence = 0;
      this.nextProducerSequence = 1n;
      this.nextBrowserSequence = 2n;
      this.latestGPS = undefined;
      this.gpsProviderStatus = null;
      this.gps.begin();
      this.latestReceived = undefined;
      this.entries = [];
      this.accepting = true;
      this.interruptionIndex = 0;
      this.bindClock();
      this.bindGPS();
      this.displayTimer = setInterval(() => this.emit(), 500);
    } catch (error) {
      if (begun && this.token) {
        try {
          await this.store.discard(begun, this.token);
          this.record = null;
        } catch {
          this.record = await this.store.interruptCurrent().catch(() => this.record);
        }
      }
      this.unlock();
      throw browserFailure(error, 'storage');
    } finally {
      await releaseAdmission?.();
      this.action = null;
      this.emit();
    }
    return this.snapshot();
  }
  private bindClock(firstSequence = 0) {
    this.releaseClock?.();
    this.releaseClock = registerBrowserWorkoutClock(
      this.record!.id,
      this.epoch,
      () => {
        if (!this.accepting) return undefined;
        const now = this.now();
        return { nowSeconds: Math.max(0, now - this.epoch), monotonicAt: now };
      },
      this.store,
      firstSequence,
      this.nextBrowserSequence.toString(),
    );
  }
  private unlock() {
    this.accepting = false;
    this.gps.stop();
    this.visibility?.();
    this.visibility = undefined;
    this.releaseClock?.();
    this.releaseClock = undefined;
    clearInterval(this.displayTimer);
    this.displayTimer = undefined;
    clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    this.source?.setWorkoutOwner?.(null);
    if (this.release) this.released = this.release();
    this.release = undefined;
    this.token = undefined;
    this.deviceID = undefined;
  }
  private receive(value: TelemetrySample, delivery: SampleDelivery) {
    if (this.accepting && this.deviceID && this.sourceState?.deviceId !== this.deviceID) {
      this.fail(new RideFailureError('recordingInterrupted'));
      return;
    }
    const acquiredAt = samplePresentationTime(delivery);
    if (
      !this.accepting ||
      !this.record ||
      !this.deviceID ||
      (typeof document !== 'undefined' && document.visibilityState === 'hidden')
    )
      return;
    try {
      if (acquiredAt === null || acquiredAt > this.now())
        throw new Error('Recording requires a valid acquisition time');
      if (acquiredAt < this.epoch) return;
      const sample = validateSample(value),
        clock = this.clock(acquiredAt, sample.timestamp);
      if (!sample.connectionEpoch) throw new Error('Recording requires a connection epoch');
      const row: RideRow = {
        ...sample,
        recordingId: this.record.id,
        eventId: crypto.randomUUID(),
        producer: 'controller',
        producerSequence: (this.nextProducerSequence++).toString(),
        elapsedUs: secondsUs(clock.elapsed),
        queryUs: timestampUs(sample.timestamp),
        sequence: this.nextSequence++,
        elapsedSeconds: clock.elapsed,
        originalSequence: sample.sequence,
        originalElapsedSeconds: sample.elapsedSeconds,
        active: this.admissionPhase === 'running',
        interval: this.interval,
        connectionEpoch: sample.connectionEpoch,
        interruptionIndex: this.interruptionIndex,
      };
      this.buffer({ kind: 'sample', row, clock });
      if (this.entries.length >= BROWSER_BATCH_TARGET) this.kick();
      else
        this.flushTimer ??= setTimeout(() => {
          this.flushTimer = undefined;
          this.kick();
        }, 250);
    } catch (error) {
      this.fail(error);
    }
  }
  private bindGPS(): void {
    this.visibility?.();
    const update = () => {
      this.interruptionIndex++;
      this.gps.breakContinuity();
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') this.gps.stop();
      else this.startGPS();
    };
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', update);
      this.visibility = () => document.removeEventListener('visibilitychange', update);
    }
    this.startGPS();
  }
  private startGPS(): void {
    if (
      !this.accepting ||
      !this.record?.recordGPS ||
      this.admissionPhase !== 'running' ||
      (typeof document !== 'undefined' && document.visibilityState === 'hidden')
    )
      return;
    this.gps.start(
      position => this.receiveLocation(position),
      error => {
        this.gpsProviderStatus = error.code === 1 ? 'denied' : 'unavailable';
        this.emit();
      },
    );
  }
  private receiveLocation(position: GeolocationPosition): void {
    if (!this.accepting || !this.record || this.admissionPhase !== 'running') return;
    try {
      const fix = this.gps.map(position, this.epoch, this.activeEpoch, this.record.startedAt);
      if (!fix) return;
      const row: RideLocationRow = {
        ...fix,
        recordingId: this.record.id,
        eventId: crypto.randomUUID(),
        producer: 'browser',
        producerSequence: (this.nextBrowserSequence++).toString(),
        elapsedUs: secondsUs(fix.elapsedSeconds),
        queryUs: timestampUs(fix.timestamp),
        active: true,
        interval: this.interval,
      };
      this.buffer({ kind: 'location', row, clock: this.clock(fix.acquiredAtSeconds, fix.timestamp) });
      this.gpsProviderStatus = null;
      this.flushTimer ??= setTimeout(() => {
        this.flushTimer = undefined;
        this.kick();
      }, 250);
    } catch (error) {
      this.fail(error);
    }
  }
  private buffer(entry: SampleEntry) {
    if (this.entries.length >= MAX_BUFFERED_OBSERVATIONS) {
      this.entries.shift();
      this.interruptionIndex++;
      this.gps.breakContinuity();
      for (const queued of [...this.entries, entry]) {
        if (queued.kind === 'sample') queued.row.interruptionIndex = this.interruptionIndex;
      }
      const nextGPS = this.entries.find(queued => queued.kind === 'location');
      if (nextGPS?.kind === 'location') nextGPS.row.distanceBarrier = true;
      else if (entry.kind === 'location') entry.row.distanceBarrier = true;
    }
    this.entries.push(entry);
  }
  private fail(error: unknown) {
    if (!this.record) return;
    this.accepting = false;
    this.captureFailed = true;
    this.gps.stop();
    this.entries = [];
    clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    this.failure = browserFailureCode(error, 'storage');
    this.record = { ...this.record, phase: 'interrupted', interrupted: true };
    this.emit();
  }
  private kick() {
    void this.flush().catch(() => {});
  }
  private flush(): Promise<void> {
    if (this.flushWork) return this.flushWork;
    clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    const work = (async () => {
      try {
        while (this.entries.length && this.record && this.token) {
          const first = this.entries[0]!;
          const batch: SampleEntry[] = [];
          for (const entry of this.entries) {
            if (entry.kind !== first.kind || batch.length === BROWSER_BATCH_TARGET) break;
            batch.push(entry);
          }
          // Detach the in-flight batch so bounded buffering never mutates its contents.
          this.entries.splice(0, batch.length);
          const clock = batch.at(-1)!.clock;
          this.record =
            first.kind === 'sample'
              ? await this.store.append(
                  this.record.id,
                  this.token,
                  batch.map(entry => entry.row as RideRow),
                  clock.elapsed,
                  clock.timer,
                  clock.at,
                )
              : await this.store.appendLocations(
                  this.record.id,
                  this.token,
                  batch.map(entry => entry.row as RideLocationRow),
                  clock.elapsed,
                  clock.timer,
                  clock.at,
                );
          if (this.captureFailed) this.record = await this.store.interruptCurrent().catch(() => this.record);
          if (first.kind === 'sample') this.latestReceived = this.epoch + clock.elapsed;
          else this.latestGPS = batch.at(-1)!.row as RideLocationRow;
          this.emit();
        }
      } catch (error) {
        this.fail(error);
        // Best effort only. If storage is unavailable, the next launch marks the last committed record interrupted.
        this.record = await this.store.interruptCurrent().catch(() => this.record);
        throw browserFailure(error, 'storage');
      }
    })();
    this.flushWork = work;
    void work
      .finally(() => {
        if (this.flushWork === work) this.flushWork = undefined;
      })
      .catch(() => {});
    return work;
  }
  private async claimInterrupted(id: string | undefined, discard: boolean) {
    await this.checking;
    await this.released;
    this.release = (await acquireLock()) ?? undefined;
    if (!this.release) throw new RideFailureError('ownerForeign');
    try {
      const current = discard ? await this.store.current() : await this.store.interruptCurrent();
      if (!id || current?.id !== id) throw new RideFailureError('rideChanged');
      // The recording lock excludes a live writer. Discard needs only its existing identity,
      // rechecked by the deletion transaction, rather than more writes to a potentially full store.
      this.token = discard ? current.writer : crypto.randomUUID();
      this.record = discard ? current : await this.store.claim(id, this.token!);
      this.foreign = false;
      if (!discard) this.nextBrowserSequence = int64(await this.store.producerHighWater(id, 'browser')) + 1n;
    } catch (error) {
      this.unlock();
      throw error;
    }
  }
  private async command(action: Action, id = this.record?.id): Promise<WorkoutState> {
    if (this.action) throw new RideFailureError('actionPending');
    if (this.record?.id !== id || this.foreign) throw new RideFailureError('ownerForeign');
    const phase = this.record?.phase;
    if (
      (action === 'pause' && phase !== 'running') ||
      (action === 'resume' && phase !== 'paused') ||
      (action === 'lap' && phase !== 'running') ||
      ((action === 'stop' || action === 'discard') && !['running', 'paused', 'interrupted'].includes(phase ?? ''))
    )
      throw new RideFailureError('actionUnavailable');
    this.action = action;
    const commandAt = this.now();
    const clock = this.clock(commandAt);
    this.accepting = false;
    if (action !== 'lap') this.gps.stop();
    this.emit();
    let releaseAdmission: (() => Promise<void>) | null = null;
    try {
      releaseAdmission = await acquireLock(BROWSER_OWNER_WORK_LOCK, false);
      if (!this.release) await this.claimInterrupted(id, action === 'discard');
      if (!this.record || !this.token || this.foreign || id !== this.record.id)
        throw new RideFailureError('ownerForeign');
      if (action === 'discard') {
        this.entries = [];
        await this.flushWork?.catch(() => {});
        await this.store.discard(id, this.token);
        this.record = null;
        this.failure = undefined;
        this.unlock();
      } else {
        await this.flush();
        if (this.captureFailed) {
          this.record = (await this.store.interruptCurrent()) ?? this.record;
          this.nextBrowserSequence = int64(await this.store.producerHighWater(id, 'browser')) + 1n;
        }
        this.record = await this.store.transition(
          id,
          this.token,
          action,
          clock.elapsed,
          clock.timer,
          clock.at,
          (this.nextBrowserSequence++).toString(),
        );
        if (action !== 'lap') {
          this.timerAtPhase = this.record.timerSeconds;
          this.phaseAt = commandAt;
          this.gps.breakContinuity();
          this.interval++;
        }
        if (action === 'stop') {
          this.failure = undefined;
          this.unlock();
        } else {
          this.accepting = true;
          if (action === 'resume') this.activeEpoch = commandAt;
          this.startGPS();
        }
      }
    } catch (error) {
      if (this.release && this.record) {
        this.fail(error);
        this.record = await this.store.interruptCurrent().catch(() => this.record);
      }
      throw browserFailure(error, 'storage');
    } finally {
      await releaseAdmission?.();
      this.action = null;
      this.emit();
    }
    return this.snapshot();
  }
  pause(id?: string) {
    return this.command('pause', id);
  }
  resume(id?: string) {
    return this.command('resume', id);
  }
  lap(id?: string) {
    return this.command('lap', id);
  }
  stop(id?: string) {
    return this.command('stop', id);
  }
  discard(id: string) {
    return this.command('discard', id);
  }
  async remove(id: string) {
    await this.store.remove(id);
    if (this.record?.id === id) this.record = null;
    this.emit();
    return this.snapshot();
  }
  list(options?: CatalogRequest) {
    return this.store.list(options);
  }
  storagePersistence() {
    return browserStoragePersistence();
  }
  async read(id: string, _distanceSource: DistanceSource = 'auto'): Promise<WorkoutDetail> {
    const record = await this.store.openRead(id);
    const distance = await ensureBrowserDistance(id, record.distanceSource, record);
    const release = await retainBrowserDistance(distance);
    try {
      const summary = await browserSummary(record, record.distanceSource, distance.profiles, this.store);
      summary.batteryConsumption = await browserConsumptionSummary(distance);
      await this.store.checkRead(record);
      return { metadata: metadataOf(record), summary };
    } finally {
      await release();
    }
  }
}
