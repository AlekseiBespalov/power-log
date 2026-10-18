import { completionIdentity, CompletionUpdateCursor, type CompletionSnapshotFields } from '../../shared/js';
import { workoutCompletion } from '../core/workout-payload';
import type { WorkoutState } from '../core/workouts';

export function workoutHistoryRevision(state: WorkoutState): string {
  return stateKey([completionIdentity(workoutCompletion(state)), state.historyRevision, state.exportEligible]);
}

export class WorkoutStateDelivery {
  private readonly cursor = new CompletionUpdateCursor();
  private active = true;
  private pending?: WorkoutState;
  private deliveredControl?: string;
  private pendingControl?: string;
  private lastPublication = -Infinity;
  private timer?: ReturnType<typeof setTimeout>;
  constructor(private readonly update: (state: WorkoutState) => void) {}
  setActive(active: boolean): void {
    this.active = active;
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    if (active) this.publish();
  }
  beginRefresh(): number {
    return this.cursor.beginRefresh();
  }
  receive(state: WorkoutState): void {
    const completion = workoutCompletion(state);
    if (this.cursor.receive(completion)) this.admit(state, completion);
  }
  finishRefresh(request: number, state: WorkoutState): void {
    const completion = workoutCompletion(state);
    if (this.cursor.finishRefresh(request, completion)) this.admit(state, completion);
  }
  private admit(state: WorkoutState, completion: CompletionSnapshotFields) {
    this.pending = state;
    this.pendingControl = stateKey([
      completionIdentity(completion, true),
      state.pendingAction,
      state.healthBusy,
      state.sessionBusy,
      state.endingOwner,
      state.readWorkoutIds?.join(','),
      state.startBusy,
      state.finalizationBusy,
      state.failure?.code,
      state.historyRevision,
      state.lastDeletedWorkoutId,
      state.exportEligible,
      state.recoveryState,
      state.supported,
      state.watch.installed,
    ]);
    if (!this.active) return;
    const delay = Math.max(0, 1000 - (performance.now() - this.lastPublication));
    if (this.deliveredControl !== this.pendingControl || !delay) this.publish();
    else if (!this.timer)
      this.timer = setTimeout(() => {
        this.timer = undefined;
        this.publish();
      }, delay);
  }
  private publish() {
    if (!this.active || !this.pending) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    const state = this.pending;
    this.pending = undefined;
    this.deliveredControl = this.pendingControl;
    this.pendingControl = undefined;
    this.lastPublication = performance.now();
    this.update(state);
  }
}

function stateKey(values: (string | boolean | null | undefined)[]): string {
  return values.map(value => (value == null ? 'n' : `${typeof value}:${String(value).length}:${value}`)).join('');
}
