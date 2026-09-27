import { describe, expect, it } from 'vitest';
import { telemetryDisplay } from '../../src/core/telemetry-display';
import { MonotonicClock } from '../../src/services/monotonic-clock';

describe('monotonic clock mapping', () => {
  it('does not rejuvenate acquisition when the first native answer waits eight seconds in JS', async () => {
    let now = 0;
    const clock = new MonotonicClock(
      async () => 1000,
      () => now,
    );
    const sync = clock.sync();
    now = 8;
    await sync;
    expect(clock.toJS(1000)).toBe(0);
    expect(telemetryDisplay('connected', clock.toJS(1000), now)).toBe('unavailable');
  });

  it('invalidates an in-flight sync and waits for the foreground sync before becoming ready', async () => {
    const completions: ((value: number) => void)[] = [];
    const clock = new MonotonicClock(
      () => new Promise(resolve => completions.push(resolve)),
      () => 10,
    );
    const original = clock.sync();
    completions[0]!(1000);
    await original;
    const background = clock.sync();
    clock.invalidate();
    const foreground = clock.sync();
    let ready = false;
    const waiting = clock.ready().then(() => {
      ready = true;
    });
    completions[1]!(1001);
    await background;
    expect(ready).toBe(false);
    expect(clock.toJS(1000)).toBeNull();
    completions[2]!(4600);
    await foreground;
    await waiting;
    expect(ready).toBe(true);
    expect(clock.toJS(1000)).toBe(-3590);
  });

  it('keeps the narrower of overlapping offset bounds and ignores malformed clocks', async () => {
    let now = 10;
    let complete!: (value: number) => void;
    const clock = new MonotonicClock(
      () =>
        new Promise(resolve => {
          complete = resolve;
        }),
      () => now,
    );
    expect(clock.toJS(100)).toBeNull();
    const first = clock.sync();
    expect(clock.sync()).toBe(first);
    now = 12;
    complete(111);
    await first;
    expect(clock.toJS(105)).toBe(4);
    const faster = clock.sync();
    now = 12.5;
    complete(113.25);
    await faster;
    expect(clock.toJS(105)).toBe(3.75);
    const slower = clock.sync();
    now = 14.5;
    complete(114.5);
    await slower;
    expect(clock.toJS(105)).toBe(3.75);
    for (const value of [NaN, Infinity, -1]) {
      const invalid = clock.sync();
      complete(value);
      await invalid;
      expect(clock.toJS(105)).toBe(3.75);
      expect(clock.toJS(value)).toBeNull();
    }
  });

  it('replaces the offset when a looser estimate shows the clocks moved apart', async () => {
    let now = 10;
    let complete!: (value: number) => void;
    const clock = new MonotonicClock(
      () =>
        new Promise(resolve => {
          complete = resolve;
        }),
      () => now,
    );
    const first = clock.sync();
    now = 10.2;
    complete(110);
    await first;
    expect(clock.toJS(105)).toBe(5);
    now = 20;
    const afterSleep = clock.sync();
    now = 21;
    complete(3720.5);
    await afterSleep;
    expect(clock.toJS(3705)).toBe(4.5);
  });

  it('leaves freshness unavailable after a failed sync and permits a later retry', async () => {
    let failed = true;
    const clock = new MonotonicClock(
      async () => {
        if (failed) throw new Error('Bridge failure');
        return 100;
      },
      () => 10,
    );
    await expect(clock.ready()).rejects.toThrow('Bridge failure');
    expect(clock.toJS(100)).toBeNull();
    failed = false;
    await clock.ready();
    expect(clock.toJS(100)).toBe(10);
  });
});
