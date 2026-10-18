import { ControllerPreviewPresentation } from '../../shared/js';
import {
  sampleAcquisition,
  samplePresentationTime,
  type NativeState,
  type SampleDelivery,
  type TelemetrySample,
} from '../core/types';
import type { TelemetryDisplay } from '../core/telemetry-display';
import { idleState } from './adapter';

type Snapshot = { state: NativeState; display: TelemetryDisplay; faultCode: number | null };
const sameState = (a: NativeState, b: NativeState) =>
  Object.keys({ ...a, ...b }).every(key => a[key as keyof NativeState] === b[key as keyof NativeState]);

export class SessionPresentation {
  private snapshot: Snapshot = { state: idleState(), display: 'unavailable', faultCode: null };
  private state = this.snapshot.state;
  private live = new ControllerPreviewPresentation();
  private delivery: SampleDelivery | null = null;
  private unsubscribeClock?: () => void;
  private active = false;
  private lastPublication = -Infinity;
  private expiry?: ReturnType<typeof setTimeout>;
  private expiryAt: number | null = null;
  private pending?: ReturnType<typeof setTimeout>;
  private listeners = new Set<() => void>();
  constructor(private readonly now = () => performance.now() / 1000) {}
  private observe(sample: TelemetrySample, delivery: SampleDelivery) {
    const acquired = sampleAcquisition(delivery);
    return (
      acquired !== null &&
      this.live.observe(
        sample.connectionEpoch ?? 'session',
        sample.sequence,
        acquired,
        sample.faultCode,
        samplePresentationTime(delivery),
        this.now(),
      )
    );
  }
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
      this.live.clear();
      this.delivery = null;
      this.observeClock();
    }
    if (this.active) this.publish();
  }
  receiveSample(sample: TelemetrySample, delivery: SampleDelivery) {
    if (!this.observe(sample, delivery)) return;
    const previousClock = this.delivery && 'clock' in this.delivery ? this.delivery.clock : undefined;
    this.delivery = Object.freeze({ ...delivery });
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
    const raw = this.live.acquiredAt ?? null;
    const mapped =
      raw === null ? null : this.delivery && 'clock' in this.delivery ? this.delivery.clock.toJS(raw) : raw;
    const view = this.live.view(this.state.status, mapped, this.lastPublication);
    const display = view.display as TelemetryDisplay;
    const faultCode = view.faultCode ?? null;
    this.scheduleExpiry(view.nextDeadline ?? null);
    if (
      this.snapshot.state === this.state &&
      this.snapshot.display === display &&
      this.snapshot.faultCode === faultCode
    )
      return;
    this.snapshot = { state: this.state, display, faultCode };
    for (const listener of this.listeners) listener();
  }
  private scheduleExpiry(deadline: number | null) {
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
