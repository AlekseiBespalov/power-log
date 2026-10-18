import { ExportError } from '../core/export/types';
import { MonitorPreferencesError } from '../core/monitor';
import { failureCode, presentFailure } from '../core/failures';
import { waitForRideAction } from './ride-action-deadline';

export type WorkoutActionKind = 'start' | 'control';
export type WorkoutActionFlight = { current: () => boolean; signal: AbortSignal };
type ActionState = { busy: boolean; recoveryAvailable: boolean; error: string | null };
const message = (error: unknown, fallback?: string) => {
  if (error instanceof ExportError || error instanceof MonitorPreferencesError) return error.message;
  return fallback && failureCode(error) === 'unknown' ? fallback : presentFailure(error).text;
};

export class WorkoutActions {
  private pending = new Map<symbol, WorkoutActionKind>();
  private generation = 0;
  private state: ActionState = { busy: false, recoveryAvailable: false, error: null };
  constructor(private readonly publish: (state: ActionState) => void) {}
  snapshot = () => this.state;
  private emit(error = this.state.error) {
    this.state = {
      busy: this.pending.size > 0,
      recoveryAvailable: this.pending.size > 0 && [...this.pending.values()].every(kind => kind === 'start'),
      error,
    };
    this.publish(this.state);
  }
  clearError = () => this.emit(null);
  reportError = (error: unknown) => this.emit(message(error));
  async run(
    operation: (flight: WorkoutActionFlight) => Promise<unknown>,
    refresh: (current: () => boolean) => Promise<unknown>,
    kind: WorkoutActionKind = 'control',
  ) {
    if (this.pending.size && (kind === 'start' || !this.state.recoveryAvailable)) return;
    const token = Symbol(kind);
    const generation = ++this.generation;
    const controller = new AbortController();
    const current = () => this.pending.has(token) && !controller.signal.aborted;
    this.pending.set(token, kind);
    this.emit(null);
    try {
      await waitForRideAction(
        async () => {
          try {
            await operation({ current, signal: controller.signal });
          } catch (error) {
            if (current() && generation === this.generation) this.emit(message(error));
          }
          if (current()) {
            try {
              await refresh(current);
            } catch (error) {
              if (current() && generation === this.generation) this.emit(this.state.error ?? message(error));
            }
          }
        },
        kind === 'start' ? 'start' : 'recovery',
      );
    } catch (error) {
      if (generation === this.generation) this.emit(this.state.error ?? message(error));
    } finally {
      controller.abort();
      this.pending.delete(token);
      this.emit();
    }
  }
}

type ArchiveActionState = { pending: boolean; error: string | null };
export class ArchiveActions {
  private state: ReadonlyMap<string, ArchiveActionState> = new Map();
  constructor(private readonly publish: (state: ReadonlyMap<string, ArchiveActionState>) => void) {}
  snapshot = () => this.state;
  private emit(key: string, value?: ArchiveActionState) {
    const next = new Map(this.state);
    if (value) next.set(key, value);
    else next.delete(key);
    this.state = next;
    this.publish(next);
  }
  clearError = (key: string) => {
    if (this.state.get(key)?.pending) this.emit(key, { pending: true, error: null });
    else this.emit(key);
  };
  async run(key: string, operation: () => Promise<unknown>, fallback?: string) {
    if (this.state.get(key)?.pending) return;
    this.emit(key, { pending: true, error: null });
    try {
      await operation();
      this.emit(key);
    } catch (error) {
      this.emit(key, { pending: false, error: message(error, fallback) });
    }
  }
}
