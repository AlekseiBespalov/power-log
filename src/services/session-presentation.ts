import { MAX_SAMPLE_GAP_SECONDS, type NativeState, type TelemetrySample } from '../core/types';
import { displaySampleTime, telemetryDisplay, TELEMETRY_DISPLAY_HOLD_SECONDS, type TelemetryDisplay } from '../core/telemetry-display';
import { idleState } from './adapter';

type Snapshot = { state: NativeState; display: TelemetryDisplay; faultCode: number | null };
const sameState = (a: NativeState, b: NativeState) => Object.keys({ ...a, ...b }).every(key => a[key as keyof NativeState] === b[key as keyof NativeState]);

/** Capture owns every frame. Shared UI state owns only connection freshness and faults. */
export class SessionPresentation {
  private snapshot: Snapshot = { state: idleState(), display: 'unavailable', faultCode: null };
  private state = this.snapshot.state;
  private latest: TelemetrySample | null = null;
  private receivedAt: number | null = null;
  private active = false;
  private lastPublication = -Infinity;
  private expiry?: ReturnType<typeof setTimeout>;
  private expiryAt: number | null = null;
  private pending?: ReturnType<typeof setTimeout>;
  private listeners = new Set<() => void>();
  constructor(private readonly now = () => performance.now() / 1000, private readonly utc = () => Date.now()) {}
  getSnapshot = () => this.snapshot;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  setActive(active: boolean) {
    if (this.active === active) return;
    this.active = active;
    if (this.expiry) clearTimeout(this.expiry);
    if (this.pending) clearTimeout(this.pending);
    this.expiry = undefined; this.expiryAt = null; this.pending = undefined;
    if (active) this.publish();
  }
  receiveState(state: NativeState) {
    if (sameState(state, this.state)) return;
    this.state = state;
    if (state.status !== 'connected' && state.status !== 'reconnecting') { this.latest = null; this.receivedAt = null; }
    if (this.active) this.publish();
  }
  receiveSample(sample: TelemetrySample) {
    this.latest = sample;
    this.receivedAt = displaySampleTime(sample.timestamp, this.now(), this.utc());
    if (!this.active || this.pending) return;
    const delay = Math.max(0, 250 - (this.now() - this.lastPublication) * 1000);
    if (!delay) this.publish();
    else this.pending = setTimeout(() => { this.pending = undefined; this.publish(); }, delay);
  }
  private publish() {
    if (!this.active) return;
    this.lastPublication = this.now();
    const display = telemetryDisplay(this.state.status, this.receivedAt, this.lastPublication);
    const faultCode = display === 'unavailable' ? null : this.latest?.faultCode ?? null;
    this.scheduleExpiry();
    if (this.snapshot.state === this.state && this.snapshot.display === display && this.snapshot.faultCode === faultCode) return;
    this.snapshot = { state: this.state, display, faultCode };
    for (const listener of this.listeners) listener();
  }
  private scheduleExpiry() {
    const age = this.receivedAt === null ? Infinity : this.now() - this.receivedAt;
    const connected = this.state.status === 'connected';
    const holdsSample = connected || this.state.status === 'reconnecting';
    const deadline = holdsSample && this.receivedAt !== null && age < TELEMETRY_DISPLAY_HOLD_SECONDS
      ? this.receivedAt + (connected && age <= MAX_SAMPLE_GAP_SECONDS ? MAX_SAMPLE_GAP_SECONDS + 0.001 : TELEMETRY_DISPLAY_HOLD_SECONDS)
      : null;
    if (deadline !== null && this.expiryAt !== null && this.expiryAt <= deadline) return;
    if (this.expiry) clearTimeout(this.expiry);
    this.expiry = undefined; this.expiryAt = deadline;
    if (deadline === null) return;
    this.expiry = setTimeout(() => {
      this.expiry = undefined; this.expiryAt = null; this.publish();
    }, Math.max(1, (deadline - this.now()) * 1000));
  }
}
