import { describe, expect, it } from 'vitest';
import { ReadCancelled, ReadDeferred, ReadScheduler, readPressure } from '../../src/services/read-scheduler';
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; };
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

describe('global native read admission', () => {
  it('holds the native slot after cancellation, replaces pending source work, and settles once', async () => {
    const scheduler = new ReadScheduler(), native = deferred<number>(); let calls = 0;
    const old = scheduler.schedule('plot', 'old-source', 'plot', () => { calls++; return native.promise; });
    const oldOutcome = old.catch(error => error);
    scheduler.cancel('old-source');
    const replaced = scheduler.schedule('plot', 'new-source', 'plot', async () => { calls++; return 2; }).catch(error => error);
    const newest = scheduler.schedule('plot', 'new-source', 'plot', async () => { calls++; return 3; });
    expect(await oldOutcome).toBeInstanceOf(ReadCancelled);
    expect(await replaced).toBeInstanceOf(ReadCancelled);
    expect(calls).toBe(1); expect(scheduler.getSnapshot()).toMatchObject({ active: 1, pending: 1 });
    native.resolve(1); expect(await newest).toBe(3); await flush();
    expect(calls).toBe(2); expect(scheduler.getSnapshot()).toMatchObject({ active: 0, pending: 0 });
  });
  it('runs plot and fast latest while statistics are held, and keeps logical stats consumers fair', async () => {
    const scheduler = new ReadScheduler(), held = deferred<number>(), order: string[] = [];
    const first = scheduler.schedule('statistics', 'ride', 'viewport', () => held.promise);
    const comparison = scheduler.schedule('statistics', 'ride', 'comparison', async () => { order.push('comparison'); return 1; });
    const replacement = scheduler.schedule('statistics', 'ride', 'viewport', async () => { order.push('viewport'); return 2; });
    const settledFirst = first.catch(error => error);
    expect(await scheduler.schedule('plot', 'ride', 'plot', async () => 7)).toBe(7);
    expect(await scheduler.schedule('fast', 'ride', 'latest', async () => 9)).toBe(9);
    expect(order).toEqual([]); held.resolve(0);
    expect(await settledFirst).toBeInstanceOf(ReadCancelled); await comparison; await replacement;
    expect(order).toEqual(['comparison', 'viewport']);
  });
  it('bounds independent pending consumers and all five fast operations progress', async () => {
    const scheduler = new ReadScheduler(5), held = deferred<void>(), order: string[] = [];
    const first = scheduler.schedule('fast', 'old', 'description', () => held.promise);
    const pending = ['describe', 'changes', 'latest', 'cursor', 'reference'].map(operation => scheduler.schedule('fast', 'ride', operation, async () => { order.push(operation); }));
    await expect(scheduler.schedule('fast', 'other', 'extra', async () => {})).rejects.toBeInstanceOf(ReadDeferred);
    expect(scheduler.getSnapshot()).toMatchObject({ active: 1, pending: 5 });
    held.resolve(); await first; await Promise.all(pending);
    expect(order).toEqual(['describe', 'changes', 'latest', 'cursor', 'reference']);
  });
  it('recognizes distinct native read-pressure codes without masking unrelated failures', () => {
    for (const message of ['MONITOR_ADMISSION_PLOT', 'MONITOR_CACHE_STATISTICS', 'STORAGE_ADMISSION']) expect(readPressure(new Error(message))).toBe(true);
    expect(readPressure(new Error('Malformed source metadata'))).toBe(false);
  });
});
