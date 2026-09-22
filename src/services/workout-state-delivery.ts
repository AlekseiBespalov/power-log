import type { WorkoutState } from '../core/workouts';

export function workoutHistoryRevision(state: WorkoutState): string {
  return JSON.stringify([state.id, state.phase, state.historyRevision,
    state.phase === 'completed' ? [state.collectionRevision, state.sealRevision, state.verifiedSealRevision, state.finalizationState] : null]);
}

/** A delayed refresh must never replace a newer native lifecycle event. */
export class WorkoutStateDelivery {
  private revision = 0;
  private active = true;
  private pending?: WorkoutState;
  private delivered?: WorkoutState;
  private lastPublication = -Infinity;
  private timer?: ReturnType<typeof setTimeout>;
  constructor(private readonly update: (state: WorkoutState) => void) {}
  setActive(active: boolean): void {
    this.active = active;
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    if (active) this.publish();
  }
  beginRefresh(): number { return ++this.revision; }
  receive(state: WorkoutState): void {
    this.revision += 1;
    this.admit(state);
  }
  finishRefresh(request: number, state: WorkoutState): void {
    if (request === this.revision) this.admit(state);
  }
  private admit(state: WorkoutState) {
    this.pending = state;
    if (!this.active) return;
    const control = (value: WorkoutState) => JSON.stringify([value.id, value.phase, value.pendingAction, value.error, value.historyRevision,
      value.lastDeletedWorkoutId, value.healthKitState, value.recoveryState, value.supported, value.watch.paired, value.watch.installed,
      value.watch.reachable, value.watch.error, value.finalizationState, value.warnings]);
    const delay = Math.max(0, 1000 - (performance.now() - this.lastPublication));
    if (!this.delivered || control(this.delivered) !== control(state) || !delay) this.publish();
    else if (!this.timer) this.timer = setTimeout(() => { this.timer = undefined; this.publish(); }, delay);
  }
  private publish() {
    if (!this.active || !this.pending) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    this.delivered = this.pending; this.pending = undefined;
    this.lastPublication = performance.now();
    this.update(this.delivered);
  }
}
