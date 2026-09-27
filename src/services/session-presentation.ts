import {
  MAX_SAMPLE_GAP_SECONDS,
  sampleAcquisition,
  samplePresentationTime,
  type NativeState,
  type SampleDelivery,
  type TelemetrySample,
} from '../core/types';
import { telemetryDisplay, TELEMETRY_DISPLAY_HOLD_SECONDS, type TelemetryDisplay } from '../core/telemetry-display';
import { idleState } from './adapter';

type Snapshot = { state: NativeState; display: TelemetryDisplay; faultCode: number | null };
const sameState = (a: NativeState, b: NativeState) =>
  Object.keys({ ...a, ...b }).every(key => a[key as keyof NativeState] === b[key as keyof NativeState]);

/** Capture owns every frame. Shared UI state owns only connection freshness and faults. */
export class SessionPresentation {
  private snapshot: Snapshot = { state: idleState(), display: 'unavailable', faultCode: null };
  private state = this.snapshot.state;
  private latest: TelemetrySample | null = null;
  private delivery: SampleDelivery | null = null;
  private unsubscribeClock?: () => void;
  private active = false;
  private lastPublication = -Infinity;
  private expiry?: ReturnType<typeof setTimeout>;
  private expiryAt: number | null = null;
  private pending?: ReturnType<typeof setTimeout>;
  private listeners = new Set<() => void>();
  constructor(private readonly now = () => performance.now() / 1000) {}
  getSnapshot = () => this.snapshot;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  setActive(active: boolean) {
    if (this.active === active) return;
    this.active = active;
    if (this.expiry) clearTimeout(this.expiry);
    if (this.pending) clearTimeout(this.pending);
    this.expiry = undefined;
    this.expiryAt = null;
    this.pending = undefined;
    this.observeClock();
    if (active) this.publish();
  }
  private observeClock() {
    this.unsubscribeClock?.();
    this.unsubscribeClock =
      this.active && this.delivery && 'clock' in this.delivery
        ? this.delivery.clock.subscribe(() => this.publish())
        : undefined;
  }
  receiveState(state: NativeState) {
    if (sameState(state, this.state)) return;
    this.state = state;
    if (state.status !== 'connected' && state.status !== 'reconnecting') {
      this.latest = null;
      this.delivery = null;
      this.observeClock();
    }
    if (this.active) this.publish();
  }
  receiveSample(sample: TelemetrySample, delivery: SampleDelivery) {
    const acquiredAt = sampleAcquisition(delivery);
    const receivedAtSeconds = samplePresentationTime(delivery);
    const previous = this.delivery && sampleAcquisition(this.delivery);
    if (
      acquiredAt === null ||
      (receivedAtSeconds !== null && receivedAtSeconds > this.now()) ||
      (previous !== null && acquiredAt < previous) ||
      (this.latest?.connectionEpoch === sample.connectionEpoch &&
        this.latest &&
        sample.sequence <= this.latest.sequence)
    )
      return;
    this.latest = sample;
    const previousClock = this.delivery && 'clock' in this.delivery ? this.delivery.clock : undefined;
    this.delivery = delivery;
    if (previousClock !== ('clock' in delivery ? delivery.clock : undefined)) this.observeClock();
    if (!this.active || this.pending) return;
    const delay = Math.max(0, 250 - (this.now() - this.lastPublication) * 1000);
    if (!delay) this.publish();
    else
      this.pending = setTimeout(() => {
        this.pending = undefined;
        this.publish();
      }, delay);
  }
  private publish() {
    if (!this.active) return;
    this.lastPublication = this.now();
    const receivedAt = this.delivery && samplePresentationTime(this.delivery);
    const display = telemetryDisplay(this.state.status, receivedAt, this.lastPublication);
    const faultCode = display === 'unavailable' ? null : (this.latest?.faultCode ?? null);
    this.scheduleExpiry();
    if (
      this.snapshot.state === this.state &&
      this.snapshot.display === display &&
      this.snapshot.faultCode === faultCode
    )
      return;
    this.snapshot = { state: this.state, display, faultCode };
    for (const listener of this.listeners) listener();
  }
  private scheduleExpiry() {
    const receivedAt = this.delivery && samplePresentationTime(this.delivery);
    const age = receivedAt === null ? Infinity : this.now() - receivedAt;
    const connected = this.state.status === 'connected';
    const holdsSample = connected || this.state.status === 'reconnecting';
    const deadline =
      holdsSample && receivedAt !== null && age >= 0 && age < TELEMETRY_DISPLAY_HOLD_SECONDS
        ? receivedAt +
          (connected && age <= MAX_SAMPLE_GAP_SECONDS ? MAX_SAMPLE_GAP_SECONDS + 0.001 : TELEMETRY_DISPLAY_HOLD_SECONDS)
        : null;
    if (deadline !== null && this.expiryAt !== null && this.expiryAt <= deadline) return;
    if (this.expiry) clearTimeout(this.expiry);
    this.expiry = undefined;
    this.expiryAt = deadline;
    if (deadline === null) return;
    this.expiry = setTimeout(
      () => {
        this.expiry = undefined;
        this.expiryAt = null;
        this.publish();
      },
      Math.max(1, (deadline - this.now()) * 1000),
    );
  }
}
