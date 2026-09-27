export class MonotonicClock {
  private bounds?: { lower: number; upper: number };
  private pending?: Promise<void>;
  private generation = 0;
  private listeners = new Set<() => void>();
  constructor(
    private readonly read: () => Promise<number>,
    private readonly now = () => performance.now() / 1000,
  ) {}

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  invalidate() {
    this.generation++;
    this.bounds = undefined;
    this.pending = undefined;
    for (const listener of this.listeners) listener();
  }

  sync(): Promise<void> {
    if (this.pending) return this.pending;
    const before = this.now();
    const generation = this.generation;
    const pending = this.read()
      .then(native => {
        const after = this.now();
        const roundTrip = after - before;
        if (
          generation !== this.generation ||
          !Number.isFinite(native) ||
          native < 0 ||
          !Number.isFinite(roundTrip) ||
          roundTrip < 0
        )
          return;
        const bounds = { lower: native - after, upper: native - before };
        if (
          this.bounds &&
          roundTrip >= this.bounds.upper - this.bounds.lower &&
          bounds.lower <= this.bounds.upper &&
          bounds.upper >= this.bounds.lower
        )
          return;
        this.bounds = bounds;
        for (const listener of this.listeners) listener();
      })
      .finally(() => {
        if (this.pending === pending) this.pending = undefined;
      });
    this.pending = pending;
    return pending;
  }

  async ready(): Promise<void> {
    if (this.pending) await this.pending;
    else if (!this.bounds) await this.sync();
  }

  toJS(seconds: number): number | null {
    // The upper offset bound maps acquisition to its earliest possible JS time.
    return this.bounds && Number.isFinite(seconds) && seconds >= 0 ? seconds - this.bounds.upper : null;
  }
}
