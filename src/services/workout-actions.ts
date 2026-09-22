export type WorkoutActionKind = 'start' | 'control';
type ActionState = { busy: boolean; recoveryAvailable: boolean; error: string | null };
const message = (error: unknown) => error instanceof Error ? error.message : String(error);

/** A pending start may be checked or stopped, without releasing another active action. */
export class WorkoutActions {
  private pending = new Map<symbol, WorkoutActionKind>();
  private state: ActionState = { busy: false, recoveryAvailable: false, error: null };
  constructor(private readonly publish: (state: ActionState) => void) {}
  snapshot = () => this.state;
  private emit(error = this.state.error) {
    this.state = { busy: this.pending.size > 0, recoveryAvailable: this.pending.size > 0 && [...this.pending.values()].every(kind => kind === 'start'), error };
    this.publish(this.state);
  }
  clearError = () => this.emit(null);
  reportError = (error: unknown) => this.emit(message(error));
  async run(operation: () => Promise<unknown>, refresh: () => Promise<unknown>, kind: WorkoutActionKind = 'control') {
    if (this.pending.size && (kind === 'start' || !this.state.recoveryAvailable)) return;
    const token = Symbol(kind); this.pending.set(token, kind); this.emit(null);
    try { await operation(); }
    catch (error) { this.emit(message(error)); }
    finally {
      try { await refresh(); } catch (error) { this.emit(this.state.error ?? message(error)); }
      this.pending.delete(token); this.emit();
    }
  }
}
