import { afterEach, describe, expect, it, vi } from 'vitest';
import { SessionPresentation } from '../../src/services/session-presentation';
import { syntheticSample } from '../fixtures/synthetic-sample';

afterEach(() => vi.useRealTimers());
const delivery = () => ({ receivedAtSeconds: performance.now() / 1000 });
const frame = (sequence: number, faultCode = 0) => ({
  ...syntheticSample(sequence / 8, sequence, new Date().toISOString()),
  faultCode,
});

describe('shared session presentation', () => {
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
