import {
  SAMPLE_COLUMNS,
  samplePresentationTime,
  type NativeState,
  type SampleDelivery,
  type TelemetrySample,
} from '../core/types';
import { csvRow } from '../core/recordings';
import { validateSample } from '../core/validation';
import {
  effectiveWorkoutOptions,
  unavailableWorkoutState,
  type WorkoutAdapter,
  type WorkoutOptions,
  type WorkoutState,
  type WorkoutPermissionStatus,
  type WorkoutDetail,
} from '../core/workouts';
import type { CatalogRequest } from '../core/catalog';
import type { TelemetryAdapter } from './adapter';
import {
  BROWSER_BATCH,
  BROWSER_PAGE,
  browserRideStore,
  browserStoragePersistence,
  metadataOf,
  requestBrowserStoragePersistence,
  type BrowserRideStore,
  type BrowserRide,
  type RideRow,
} from './browser-ride-store';
import { browserWorkoutMonitorSource, registerBrowserWorkoutClock } from './browser-workout-monitor';
import { browserDistanceRevision, ensureBrowserDistance, browserDistanceCurrent } from './browser-distance-store';
import type { DistanceSource } from '../core/distance';

export const BROWSER_RECORDING_LOCK = 'power-log-recording';
export const BROWSER_PENDING_ROWS = 512;
const CSV_EXPORT_BYTES = 128 * 1024 * 1024;
const message = (error: unknown) => (error instanceof Error ? error.message : String(error));
const permissions: WorkoutPermissionStatus = {
  health: { available: false, writeAuthorization: {} },
  location: 'unavailable',
  locationServicesEnabled: false,
  locationAccuracyAuthorization: 'unknown',
};
type Clock = { elapsed: number; timer: number; at: string };
type SampleEntry = { kind: 'sample'; row: RideRow; clock: Clock };
type Action = 'pause' | 'resume' | 'lap' | 'save' | 'discard';
type Command = {
  kind: 'command';
  action: Action;
  clock: Clock;
  resolve: (state: WorkoutState) => void;
  reject: (error: unknown) => void;
};

/** Hold the Web Lock until explicitly released. A busy tab is never displaced. */
async function acquireLock(): Promise<(() => void) | null> {
  if (typeof navigator === 'undefined' || !navigator.locks)
    throw new Error('Recording requires a browser with Web Locks support. Saved history remains available.');
  return new Promise((resolve, reject) => {
    void navigator.locks
      .request(BROWSER_RECORDING_LOCK, { ifAvailable: true }, async lock => {
        if (!lock) {
          resolve(null);
          return;
        }
        await new Promise<void>(release => resolve(release));
      })
      .catch(reject);
  });
}

/** One bounded writer queue owns originals, lifecycle and chart projections together. */
export class BrowserWorkoutRecorder implements WorkoutAdapter {
  private source?: TelemetryAdapter;
  private unsubscribe?: () => void;
  private sourceState?: NativeState;
  private deviceID?: string;
  private record: BrowserRide | null = null;
  private token?: string;
  private release?: () => void;
  private releaseClock?: () => void;
  private listeners = new Set<(state: WorkoutState) => void>();
  private entries: (SampleEntry | Command)[] = [];
  private pumping = false;
  private flushTimer?: ReturnType<typeof setTimeout>;
  private displayTimer?: ReturnType<typeof setInterval>;
  private checking?: Promise<WorkoutState>;
  private action: string | null = null;
  private failure?: string;
  private foreign = false;
  private accepting = false;
  private admissionPhase: 'running' | 'paused' = 'running';
  private epoch = 0;
  private activeEpoch = 0;
  private activeElapsed = 0;
  private interval = 0;
  private nextSequence = 0;
  private latestReceived?: number;
  constructor(
    private readonly store: BrowserRideStore = browserRideStore,
    private readonly now = () => performance.now() / 1000,
    private readonly utc = () => new Date().toISOString(),
  ) {}

  setTelemetrySource(source: TelemetryAdapter): void {
    if (source === this.source) return;
    if (this.release || this.action) throw new Error('Finish the ride before changing its data source');
    this.unsubscribe?.();
    this.source = source;
    this.sourceState = undefined;
    this.latestReceived = undefined;
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
    const elapsed = Math.max(0, now - this.epoch);
    return {
      elapsed,
      timer: Math.min(
        elapsed,
        this.activeElapsed + (this.admissionPhase === 'running' ? Math.max(0, now - this.activeEpoch) : 0),
      ),
      at,
    };
  }
  private snapshot(): WorkoutState {
    const supported = typeof indexedDB !== 'undefined' && typeof navigator !== 'undefined' && Boolean(navigator.locks);
    const timer = this.accepting ? this.clock().timer : (this.record?.timerSeconds ?? 0);
    const age = this.latestReceived === undefined ? null : Math.max(0, this.now() - this.latestReceived);
    const fresh = age !== null && age < 6;
    return {
      ...unavailableWorkoutState,
      supported,
      capabilities: {
        phoneWorkout: false,
        watchWorkout: false,
        phoneHealth: false,
        watchHealth: false,
        healthProvider: null,
        gps: false,
        foregroundOnly: true,
      },
      id: this.record?.id ?? null,
      phase: this.failure && this.release ? 'recoverable' : (this.record?.phase ?? 'idle'),
      indoor: this.record?.indoor ?? false,
      useWatch: false,
      saveToHealth: false,
      recordGPS: false,
      timerSeconds: timer,
      pendingAction: this.foreign ? 'anotherBrowserTab' : this.action,
      healthKitState: 'notRequested',
      collectionRevision: this.record?.revision ?? null,
      finalizationState: this.record ? (this.record.endedAt ? 'complete' : 'pending') : null,
      streams: {
        ...unavailableWorkoutState.streams,
        cyc: { status: fresh ? 'receiving' : 'waiting' },
      },
      warnings: [
        ...(this.record?.warnings ?? []),
        ...(this.foreign ? ['This ride is recording in another browser tab. Use that tab to finish it.'] : []),
      ],
      error: this.failure ?? null,
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
        if (release) {
          const recovered = await this.store.recoverOrphan();
          if (recovered) this.record = recovered;
          else if (this.foreign) this.record = null;
          this.foreign = false;
        } else {
          const record = await this.store.current();
          if (record) {
            this.record = record;
            this.foreign = true;
          }
        }
        return this.snapshot();
      } finally {
        release?.();
      }
    })();
    try {
      return await this.checking;
    } finally {
      this.checking = undefined;
    }
  }
  async getPermissions() {
    return permissions;
  }
  async requestPermissions(options: WorkoutOptions) {
    effectiveWorkoutOptions(options, this.snapshot().capabilities);
    return permissions;
  }
  async start(options: WorkoutOptions): Promise<WorkoutState> {
    const frozenOptions = effectiveWorkoutOptions(options, this.snapshot().capabilities);
    if (this.release || this.action) throw new Error('A ride is already active or changing state');
    this.action = 'start';
    this.emit();
    try {
      await this.checking;
      const source = this.source;
      if (!source || source.kind !== 'web') throw new Error('Connect a browser data source first');
      const state = await source.getState();
      if (state.status !== 'connected' || !state.deviceId) throw new Error('Connect a data source first');
      const release = await acquireLock();
      if (!release) throw new Error('Another browser tab owns the recording');
      this.release = release;
      void requestBrowserStoragePersistence();
      await this.store.recoverOrphan();
      await source.setSampleRate?.(frozenOptions.sampleHz);
      this.deviceID = state.deviceId;
      source.setWorkoutOwner?.(this.deviceID);
      this.epoch = this.now();
      this.activeEpoch = this.epoch;
      this.activeElapsed = 0;
      this.token = crypto.randomUUID();
      this.record = await this.store.begin(this.token, frozenOptions.indoor, this.utc());
      this.sourceState = await source.getState();
      if (this.sourceState.status !== 'connected' || this.sourceState.deviceId !== this.deviceID) {
        this.record = await this.store.recoverOrphan();
        throw new Error('Data source changed while the ride was starting');
      }
      this.failure = undefined;
      this.foreign = false;
      this.interval = 0;
      this.nextSequence = 0;
      this.admissionPhase = 'running';
      this.accepting = true;
      this.releaseClock = registerBrowserWorkoutClock(
        this.record.id,
        this.epoch,
        () => {
          if (!this.accepting) return undefined;
          const now = this.now();
          return {
            nowSeconds: Math.max(0, now - this.epoch),
            monotonicAt: now,
          };
        },
        this.store,
      );
      this.displayTimer = setInterval(() => this.emit(), 500);
    } catch (error) {
      this.unlock();
      throw error;
    } finally {
      this.action = null;
      this.emit();
    }
    return this.snapshot();
  }
  private unlock() {
    this.accepting = false;
    this.releaseClock?.();
    this.releaseClock = undefined;
    clearInterval(this.displayTimer);
    this.displayTimer = undefined;
    this.source?.setWorkoutOwner?.(null);
    this.release?.();
    this.release = undefined;
    this.token = undefined;
    this.deviceID = undefined;
  }
  private receive(value: TelemetrySample, delivery: SampleDelivery) {
    if (this.accepting && this.sourceState?.deviceId !== this.deviceID) {
      this.fail(new Error('The connected bike changed. Recording stopped; committed data remains available.'));
      return;
    }
    const acquiredAt = samplePresentationTime(delivery);
    this.latestReceived = acquiredAt ?? undefined;
    if (!this.accepting || !this.record) return;
    try {
      if (acquiredAt === null || acquiredAt > this.now())
        throw new Error('Recording requires a valid acquisition time');
      if (acquiredAt < this.epoch) return;
      const sample = validateSample(value),
        clock = this.clock(acquiredAt, sample.timestamp);
      if (!sample.connectionEpoch) throw new Error('Recording requires a connection epoch');
      if (this.entries.length >= BROWSER_PENDING_ROWS)
        throw new Error('Browser storage could not keep up. Recording stopped at its last committed checkpoint.');
      const row: RideRow = {
        ...sample,
        recordingId: this.record.id,
        sequence: this.nextSequence++,
        elapsedSeconds: clock.elapsed,
        originalSequence: sample.sequence,
        originalElapsedSeconds: sample.elapsedSeconds,
        active: this.admissionPhase === 'running',
        interval: this.interval,
        connectionEpoch: sample.connectionEpoch,
        interruptionIndex: 0,
      };
      this.entries.push({ kind: 'sample', row, clock });
      if (this.entries.length >= BROWSER_BATCH) this.kick();
      else
        this.flushTimer ??= setTimeout(() => {
          this.flushTimer = undefined;
          this.kick();
        }, 250);
    } catch (error) {
      this.fail(error);
    }
  }
  private fail(error: unknown) {
    this.accepting = false;
    this.failure = `Recording interrupted: ${message(error)}`;
    if (this.flushTimer) clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    for (const entry of this.entries.splice(0)) if (entry.kind === 'command') entry.reject(error);
    this.action = null;
    this.emit();
  }
  private kick() {
    if (this.pumping || !this.entries.length || this.failure) return;
    if (this.flushTimer) clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    this.pumping = true;
    void this.pump().finally(() => {
      this.pumping = false;
      if (this.entries.length && !this.failure) this.kick();
    });
  }
  private async pump() {
    while (this.entries.length && !this.failure && this.record && this.token) {
      const first = this.entries.shift()!;
      try {
        if (first.kind === 'sample') {
          const batch = [first];
          while (batch.length < BROWSER_BATCH && this.entries[0]?.kind === 'sample')
            batch.push(this.entries.shift() as SampleEntry);
          const clock = batch[batch.length - 1]!.clock;
          this.record = await this.store.append(
            this.record.id,
            this.token,
            batch.map(entry => entry.row),
            clock.elapsed,
            clock.timer,
            clock.at,
          );
        } else {
          if (first.action === 'discard') {
            await this.store.remove(this.record.id, this.token);
            this.record = null;
            this.unlock();
          } else {
            this.record = await this.store.transition(
              this.record.id,
              this.token,
              first.action,
              first.clock.elapsed,
              first.clock.timer,
              first.clock.at,
            );
            if (first.action === 'save') this.unlock();
          }
          this.action = null;
          first.resolve(this.snapshot());
        }
        this.emit();
      } catch (error) {
        if (first.kind === 'command') first.reject(error);
        this.fail(error);
      }
    }
  }
  private command(action: Action, id?: string): Promise<WorkoutState> {
    if (!this.record || !this.token || !this.release || this.foreign || (id && id !== this.record.id))
      return Promise.reject(new Error('This tab does not own that ride'));
    if (this.action) return Promise.reject(new Error('A ride action is already in progress'));
    if (this.failure)
      return action === 'save'
        ? this.recover(this.record.id)
        : action === 'discard'
          ? this.discardFailed()
          : Promise.reject(new Error('Recover or discard the interrupted recording first'));
    if (
      (action === 'pause' && this.admissionPhase !== 'running') ||
      (action === 'resume' && this.admissionPhase !== 'paused')
    )
      return Promise.reject(new Error('Recording phase changed'));
    const clock = this.clock();
    if (action === 'pause') {
      this.activeElapsed = clock.timer;
      this.admissionPhase = 'paused';
    }
    if (action === 'resume') {
      this.activeEpoch = this.now();
      this.admissionPhase = 'running';
      this.interval++;
    }
    if (action === 'save' || action === 'discard') this.accepting = false;
    this.action = action;
    this.emit();
    return new Promise((resolve, reject) => {
      this.entries.push({ kind: 'command', action, clock, resolve, reject });
      this.kick();
    });
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
    return this.command('save', id);
  }
  discard(id: string) {
    return this.command('discard', id);
  }
  private async discardFailed() {
    if (this.pumping) throw new Error('Wait for the current storage transaction to finish');
    await this.store.remove(this.record!.id, this.token);
    this.record = null;
    this.failure = undefined;
    this.unlock();
    this.emit();
    return this.snapshot();
  }
  async recover(id: string) {
    if (this.action || this.pumping) throw new Error('Wait for the current recording action');
    if (this.record?.id !== id || !this.release || !this.failure)
      throw new Error('Recover the interrupted ride from its recording tab');
    this.record = await this.store.recoverOrphan();
    this.failure = undefined;
    this.unlock();
    this.emit();
    return this.snapshot();
  }
  async remove(id: string) {
    if (this.record?.id === id && this.release) throw new Error('Finish or discard the active ride first');
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
  async read(id: string, distanceSource: DistanceSource = 'auto'): Promise<WorkoutDetail> {
    const record = await this.store.get(id),
      source = browserWorkoutMonitorSource(id, false, this.store, distanceSource);
    const elapsed = record.elapsedSeconds;
    const result = await source.rangeStats({
      generation: 1,
      expectedRevision: browserDistanceRevision(record, distanceSource),
      startSeconds: 0,
      endSeconds: elapsed,
      metrics: ['humanPowerW', 'cadenceRpm'],
    });
    if (result.status !== 'ok') throw new Error('Ride changed while reading its summary; retry');
    const power = result.statistics.humanPowerW,
      cadence = result.statistics.cadenceRpm,
      distance = await ensureBrowserDistance(id, distanceSource);
    if (!(await browserDistanceCurrent(distance)) || distance.record.revision !== record.revision)
      throw new Error('Ride changed while reading its summary; retry');
    const selectedDistance = distance.info.selected;
    return {
      metadata: metadataOf(record),
      summary: {
        schemaVersion: 1,
        id,
        startedAt: record.startedAt,
        endedAt: record.endedAt ?? record.checkpointAt,
        elapsedSeconds: elapsed,
        timerSeconds: record.timerSeconds,
        distance: distance.info,
        ...(selectedDistance
          ? {
              distanceMeters: selectedDistance.distanceMeters,
              ...((selectedDistance.coveredSeconds ?? 0) > 0
                ? { averageSpeedMps: selectedDistance.distanceMeters / selectedDistance.coveredSeconds! }
                : {}),
            }
          : {}),
        ...(power
          ? {
              ...(power.max ? { maximumRiderPowerW: power.max.value } : {}),
              riderWorkJoules: power.integral ?? 0,
              ...((power.coveredSeconds ?? 0) > 0
                ? { averageRiderPowerW: power.integral! / power.coveredSeconds! }
                : {}),
            }
          : {}),
        ...(cadence
          ? {
              ...(cadence.max ? { maximumCadenceRpm: cadence.max.value } : {}),
              ...((cadence.coveredSeconds ?? 0) > 0
                ? { averageCadenceRpm: cadence.integral! / cadence.coveredSeconds! }
                : {}),
            }
          : {}),
        telemetryCoveredSeconds: power?.coveredSeconds ?? 0,
        heartRateCoveredSeconds: 0,
        eventCount: record.samples,
        telemetryCount: record.samples,
        locationCount: 0,
        healthCount: 0,
        lapCount: record.lapCount,
        routePreview: [],
        warnings: record.warnings,
        provenance: { capture: 'browser CYC', storage: 'IndexedDB', health: 'not requested' },
      },
    };
  }
  async export(id: string, _distanceSource: DistanceSource = 'auto'): Promise<string> {
    const record = await this.store.get(id);
    if (!record.endedAt) throw new Error('Save the ride before exporting');
    const parts = [SAMPLE_COLUMNS.join(',') + '\n'];
    let bytes = parts[0]!.length,
      after: [number, number] | undefined;
    while (true) {
      const rows = await this.store.page(id, 0, Infinity, after);
      const text = rows.map(row => csvRow(validateSample(row))).join('\n') + (rows.length ? '\n' : '');
      bytes += text.length;
      if (bytes > CSV_EXPORT_BYTES)
        throw new Error('CSV export exceeds the 128 MiB browser export limit. Your complete recording remains saved.');
      parts.push(text);
      if (rows.length < BROWSER_PAGE) break;
      const last = rows[rows.length - 1]!;
      after = [last.elapsedSeconds, last.sequence];
      await new Promise<void>(resolve => setTimeout(resolve, 0));
    }
    return URL.createObjectURL(new Blob(parts, { type: 'text/csv;charset=utf-8' }));
  }
  async exportOriginal(_id: string): Promise<string> {
    throw new Error('Browser rides support CSV export');
  }
}
