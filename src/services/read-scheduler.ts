/** Admission belongs to the application, not to one mounted chart. */
export type ReadExecutionLane = 'plot' | 'statistics' | 'fast' | 'summary' | 'catalog';
export class ReadCancelled extends Error { constructor() { super('Read replaced or deactivated.'); this.name = 'ReadCancelled'; } }
export class ReadDeferred extends Error { constructor() { super('Read capacity is temporarily occupied.'); this.name = 'ReadDeferred'; } }
export function readPressure(error: unknown): boolean {
  return error instanceof ReadDeferred || (error instanceof Error && /\bMONITOR_(?:ADMISSION|CACHE)_[A-Z_]+\b|\bSTORAGE_ADMISSION\b|\[(?:monitor|storage|cache)[-_ ](?:busy|admission|contention)\]|Storage work queue is full/i.test(error.message));
}
type Job = { key: string; consumer: string; lane: ReadExecutionLane; execute: () => Promise<unknown>; resolve: (value: unknown) => void; reject: (error: unknown) => void; settled: boolean };
let consumerSequence = 0;
export const readConsumer = (purpose: string) => `${purpose}:${++consumerSequence}`;

/** One executing operation per lane, one newest pending request per logical key.
 * Canceling a running request settles its caller but retains its execution slot
 * until the native promise completes. Source changes cannot over-admit native work.
 */
export class ReadScheduler {
  private readonly active = new Map<ReadExecutionLane, Job>();
  private readonly pending = new Map<string, Job>();
  constructor(private readonly maximumPending = 32) {}
  schedule<T>(lane: ReadExecutionLane, consumer: string, operation: string, execute: () => Promise<T>): Promise<T> {
    const key = `${consumer}/${operation}`;
    this.cancel(consumer, operation);
    return new Promise<T>((resolve, reject) => {
      if (this.pending.size >= this.maximumPending) { reject(new ReadDeferred()); return; }
      const job: Job = { key, consumer, lane, execute, resolve: value => resolve(value as T), reject, settled: false };
      this.pending.set(key, job); this.drain(lane);
    });
  }
  cancel(consumer: string, operation?: string) {
    const matches = (job: Job) => job.consumer === consumer && (operation === undefined || job.key === `${consumer}/${operation}`);
    for (const [key, job] of this.pending) if (matches(job)) { this.pending.delete(key); this.settle(job, false, new ReadCancelled()); }
    for (const job of this.active.values()) if (matches(job)) this.settle(job, false, new ReadCancelled());
  }
  getSnapshot() { return { active: this.active.size, pending: this.pending.size, lanes: [...this.active.keys()] }; }
  private settle(job: Job, success: boolean, value: unknown) {
    if (job.settled) return;
    job.settled = true; if (success) job.resolve(value); else job.reject(value);
  }
  private drain(lane: ReadExecutionLane) {
    if (this.active.has(lane)) return;
    const job = [...this.pending.values()].find(value => value.lane === lane);
    if (!job) return;
    this.pending.delete(job.key); this.active.set(lane, job);
    void (async () => {
      try { this.settle(job, true, await job.execute()); }
      catch (error) { this.settle(job, false, error); }
      finally { this.active.delete(lane); this.drain(lane); }
    })();
  }
}
export const reads = new ReadScheduler();
