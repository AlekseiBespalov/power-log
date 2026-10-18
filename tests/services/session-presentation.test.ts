import { afterEach, describe, expect, it, vi } from 'vitest';
import { SessionPresentation } from '../../src/services/session-presentation';
import { syntheticSample } from '../fixtures/synthetic-sample';
import { MonotonicClock } from '../../src/services/monotonic-clock';
import type { SampleDelivery, TelemetrySample } from '../../src/core/types';
import freshness from '../fixtures/contract/presentation/freshness.json';

afterEach(() => vi.useRealTimers());
const delivery = () => ({ receivedAtSeconds: performance.now() / 1000 });
const frame = (sequence: number, faultCode = 0) => ({
  ...syntheticSample(sequence / 8, sequence, new Date().toISOString()),
  faultCode,
});

describe('shared session presentation', () => {
  it.each(freshness.connection.filter(row => row.deadline !== null))(
    'publishes the core connection deadline for $connection at $now',
    async row => {
      vi.useFakeTimers();
      await vi.advanceTimersByTimeAsync(row.now * 1000);
      const presentation = new SessionPresentation();
      presentation.receiveState({ status: row.connection as 'connected' | 'reconnecting' });
      presentation.receiveSample(frame(1, 7), { receivedAtSeconds: row.acquired });
      presentation.setActive(true);
      expect(presentation.getSnapshot().display).toBe(row.display);
      await vi.advanceTimersByTimeAsync(Math.max(1, (row.deadline! - row.now) * 1000));
      expect(presentation.getSnapshot().display).toBe(row.deadline === 102.501 ? 'held' : 'unavailable');
      presentation.setActive(false);
    },
  );
  it.each(freshness.connection)('projects the connection fixture $connection at $now', row => {
    vi.useFakeTimers();
    let now = 100;
    const presentation = new SessionPresentation(() => now);
    presentation.setActive(true);
    presentation.receiveState({ status: row.connection as 'connected' | 'reconnecting' | 'idle' });
    presentation.receiveSample(frame(1, 7), {
      acquiredAtMonotonic: row.acquired ?? 100,
      clock: { toJS: () => row.acquired, subscribe: () => () => {} },
    });
    now = row.now;
    presentation.receiveState({ status: row.connection as 'connected' | 'reconnecting' | 'idle', error: 'fixture' });
    expect(presentation.getSnapshot()).toMatchObject({
      display: row.display,
      faultCode: row.display === 'unavailable' ? null : 7,
    });
    presentation.setActive(false);
  });
  it('makes readings unavailable immediately when the native mapping is invalidated', async () => {
    vi.useFakeTimers();
    let now = 10;
    const clock = new MonotonicClock(
      async () => 100,
      () => now,
    );
    await clock.sync();
    const presentation = new SessionPresentation(() => now);
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    presentation.receiveSample(frame(1, 7), { acquiredAtMonotonic: 100, clock });
    now += 0.25;
    await vi.advanceTimersByTimeAsync(250);
    expect(presentation.getSnapshot()).toMatchObject({ display: 'live', faultCode: 7 });
    clock.invalidate();
    expect(presentation.getSnapshot()).toMatchObject({ display: 'unavailable', faultCode: null });
    presentation.setActive(false);
  });
  it('keeps the newest observation when out-of-order native frames arrive before synchronization', async () => {
    vi.useFakeTimers();
    const clock = new MonotonicClock(
      async () => 101,
      () => 10,
    );
    const presentation = new SessionPresentation(() => 10);
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    presentation.receiveSample({ ...frame(2, 9), connectionEpoch: 'same' }, { acquiredAtMonotonic: 101, clock });
    presentation.receiveSample({ ...frame(1, 7), connectionEpoch: 'same' }, { acquiredAtMonotonic: 100, clock });
    expect(presentation.getSnapshot()).toMatchObject({ display: 'unavailable', faultCode: null });
    await clock.sync();
    expect(presentation.getSnapshot()).toMatchObject({ display: 'live', faultCode: 9 });
    presentation.setActive(false);
  });
  it('admits immutable evidence once and reuses it during publications without a new sample', () => {
    vi.useFakeTimers();
    let now = 10;
    const presentation = new SessionPresentation(() => now);
    const admission = vi.spyOn(
      presentation as unknown as { observe(sample: TelemetrySample, delivery: SampleDelivery): unknown },
      'observe',
    );
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    const sample = frame(1, 7);
    presentation.receiveSample(sample, { receivedAtSeconds: 10 });
    sample.faultCode = 9;
    now += 1;
    presentation.receiveState({ status: 'reconnecting' });
    expect(presentation.getSnapshot()).toMatchObject({ display: 'held', faultCode: 7 });
    now += 1;
    presentation.receiveState({ status: 'connected' });
    expect(presentation.getSnapshot()).toMatchObject({ display: 'live', faultCode: 7 });
    expect(admission).toHaveBeenCalledOnce();
    presentation.setActive(false);
  });
  it('shortens expiry for an earlier duplicate acquisition without replacing its value', () => {
    vi.useFakeTimers();
    let now = 10;
    const presentation = new SessionPresentation(() => now);
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    const sample = { ...frame(1, 7), connectionEpoch: 'same' };
    presentation.receiveSample(sample, { receivedAtSeconds: 10 });
    presentation.receiveSample({ ...sample, faultCode: 9 }, { receivedAtSeconds: 8 });
    now = 13.999999;
    presentation.receiveState({ status: 'reconnecting' });
    expect(presentation.getSnapshot()).toMatchObject({ display: 'held', faultCode: 7 });
    now = 14;
    presentation.receiveState({ status: 'connected' });
    expect(presentation.getSnapshot()).toMatchObject({ display: 'unavailable', faultCode: null });
    presentation.setActive(false);
  });
  it('expires a stopped stream across a backward wall jump and rejects duplicate or aged delivery', async () => {
    vi.useFakeTimers();
    const presentation = new SessionPresentation();
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    const sample = { ...frame(0, 7), connectionEpoch: 'first' };
    presentation.receiveSample(sample, delivery());
    await vi.advanceTimersByTimeAsync(250);
    vi.setSystemTime(Date.now() - 3_600_000);
    await vi.advanceTimersByTimeAsync(5749);
    expect(presentation.getSnapshot()).toMatchObject({ display: 'held', faultCode: 7 });
    presentation.receiveSample(sample, delivery());
    await vi.advanceTimersByTimeAsync(1);
    expect(presentation.getSnapshot()).toMatchObject({ display: 'unavailable', faultCode: null });
    presentation.receiveSample({ ...frame(1), connectionEpoch: 'second' }, { receivedAtSeconds: -10 });
    await vi.advanceTimersByTimeAsync(250);
    expect(presentation.getSnapshot().display).toBe('unavailable');
    presentation.receiveSample({ ...frame(2), connectionEpoch: 'second' }, delivery());
    await vi.advanceTimersByTimeAsync(250);
    expect(presentation.getSnapshot().display).toBe('live');
    presentation.setActive(false);
  });
  it('drops a rejected future acquisition instead of adopting it at a later publication', () => {
    vi.useFakeTimers();
    let now = 10;
    const presentation = new SessionPresentation(() => now);
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    presentation.receiveSample(frame(1, 7), { receivedAtSeconds: 20 });
    now = 20;
    presentation.receiveState({ status: 'reconnecting' });
    expect(presentation.getSnapshot()).toMatchObject({ display: 'unavailable', faultCode: null });
    presentation.setActive(false);
  });
  it('does not broadcast ordinary readings or the display clock through connection consumers', async () => {
    vi.useFakeTimers();
    const presentation = new SessionPresentation();
    const publish = vi.fn();
    presentation.subscribe(publish);
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    presentation.receiveSample(frame(0), delivery());
    await vi.advanceTimersByTimeAsync(250);
    const snapshot = presentation.getSnapshot();
    publish.mockClear();
    for (let i = 1; i <= 80; i++) {
      presentation.receiveSample(frame(i), delivery());
      await vi.advanceTimersByTimeAsync(125);
    }
    expect(presentation.getSnapshot()).toBe(snapshot);
    expect(publish).not.toHaveBeenCalled();
    presentation.setActive(false);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('coalesces changing faults, clears hidden timers and resumes at the latest real timestamp', async () => {
    vi.useFakeTimers();
    const presentation = new SessionPresentation();
    const publish = vi.fn();
    presentation.subscribe(publish);
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    publish.mockClear();
    for (let i = 0; i < 32; i++) {
      presentation.receiveSample(frame(i, i % 3), delivery());
      await vi.advanceTimersByTimeAsync(125);
    }
    expect(publish.mock.calls.length).toBeLessThanOrEqual(17);
    presentation.setActive(false);
    const hidden = presentation.getSnapshot();
    publish.mockClear();
    for (let i = 32; i < 40; i++) {
      presentation.receiveSample(frame(i, 7), delivery());
      await vi.advanceTimersByTimeAsync(125);
    }
    expect(vi.getTimerCount()).toBe(0);
    expect(publish).not.toHaveBeenCalled();
    expect(presentation.getSnapshot()).toBe(hidden);
    await vi.advanceTimersByTimeAsync(7000);
    presentation.setActive(true);
    expect(presentation.getSnapshot()).toMatchObject({ display: 'unavailable', faultCode: null });
    presentation.setActive(false);
  });
  it('preserves the brief reconnect hold and never treats an old queued frame as fresh', async () => {
    vi.useFakeTimers();
    const presentation = new SessionPresentation();
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    const sample = frame(0);
    presentation.receiveSample(sample, delivery());
    await vi.advanceTimersByTimeAsync(250);
    presentation.receiveState({ status: 'reconnecting', recoverableConnectionError: true });
    expect(presentation.getSnapshot().display).toBe('held');
    await vi.advanceTimersByTimeAsync(6000);
    expect(presentation.getSnapshot().display).toBe('unavailable');
    presentation.receiveState({ status: 'connected' });
    presentation.receiveSample(sample, delivery());
    await vi.advanceTimersByTimeAsync(250);
    expect(presentation.getSnapshot().display).toBe('unavailable');
    presentation.setActive(false);
  });
  it('expires the six-second hold at the sample deadline, including fractional timestamps', async () => {
    vi.useFakeTimers();
    const presentation = new SessionPresentation();
    presentation.setActive(true);
    presentation.receiveState({ status: 'connected' });
    await vi.advanceTimersByTimeAsync(200);
    presentation.receiveSample(frame(0), delivery());
    await vi.advanceTimersByTimeAsync(250);
    presentation.receiveState({ status: 'reconnecting' });
    await vi.advanceTimersByTimeAsync(5749);
    expect(presentation.getSnapshot().display).toBe('held');
    await vi.advanceTimersByTimeAsync(1);
    expect(presentation.getSnapshot().display).toBe('unavailable');
    expect(vi.getTimerCount()).toBe(0);
    presentation.setActive(false);
  });
});
