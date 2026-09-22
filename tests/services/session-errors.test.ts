import { describe, expect, it } from 'vitest';
import { initialSessionErrors, sessionErrorMessage, sessionErrorReducer, type SessionErrorAction } from '../../src/services/session-errors';
import { telemetryDisplay } from '../../src/core/telemetry-display';

function replay(...actions: SessionErrorAction[]) {
  return actions.reduce(sessionErrorReducer, initialSessionErrors);
}

describe('session error lifecycle', () => {
  it('defers an ongoing reconnect banner only until the held readings expire', () => {
    const errors = replay({ type: 'native', error: 'Bike disconnected. Reconnecting…' });
    const renderAt = (now: number) => sessionErrorMessage(errors, {
      deferNative: telemetryDisplay('reconnecting', 10, now) === 'held',
    });
    expect(renderAt(10.3)).toBeNull();
    expect(renderAt(15.999)).toBeNull();
    expect(renderAt(16)).toBe('Bike disconnected. Reconnecting…');
    expect(renderAt(25)).toBe('Bike disconnected. Reconnecting…');
  });

  it('does not show a delayed banner after recovery or after dismissal', () => {
    const error = replay({ type: 'native', error: 'Bike disconnected. Reconnecting…' });
    expect(sessionErrorMessage(sessionErrorReducer(error, { type: 'native', error: undefined }))).toBeNull();
    expect(sessionErrorMessage(sessionErrorReducer(error, { type: 'dismiss' }), { deferNative: false })).toBeNull();
  });

  it('keeps action failures visible even during a quiet reconnect', () => {
    const errors = replay(
      { type: 'native', error: 'Bike disconnected. Reconnecting…' },
      { type: 'operation', error: 'Could not export FIT' },
    );
    expect(sessionErrorMessage(errors, { deferNative: true })).toBe('Could not export FIT');
  });

  it('clears a disconnect banner when the native connection recovers', () => {
    const failed = replay({ type: 'native', error: 'X6 disconnected: unknown error.' });
    expect(sessionErrorMessage(failed)).toContain('disconnected');
    const recovered = sessionErrorReducer(failed, { type: 'native', error: undefined });
    expect(sessionErrorMessage(recovered)).toBeNull();
  });

  it('keeps a dismissed occurrence hidden through repeated native state snapshots', () => {
    const dismissed = replay({ type: 'native', error: 'Link lost' }, { type: 'dismiss' });
    let current = dismissed;
    for (let index = 0; index < 100; index++) current = sessionErrorReducer(current, { type: 'native', error: 'Link lost' });
    expect(current).toBe(dismissed);
    expect(sessionErrorMessage(current)).toBeNull();
    expect(current.native).toBe('Link lost');
  });

  it('shows a new error, including the same text after a clean state', () => {
    const dismissed = replay({ type: 'native', error: 'Link lost' }, { type: 'dismiss' });
    expect(sessionErrorMessage(sessionErrorReducer(dismissed, { type: 'native', error: 'Bluetooth unavailable' }))).toBe('Bluetooth unavailable');
    const cleared = sessionErrorReducer(dismissed, { type: 'native', error: null });
    expect(sessionErrorMessage(sessionErrorReducer(cleared, { type: 'native', error: 'Link lost' }))).toBe('Link lost');
  });

  it('preserves an export failure through reconnect and subsequent native snapshots', () => {
    const state = replay(
      { type: 'native', error: 'Link lost' },
      { type: 'operation', error: 'Could not export FIT' },
      { type: 'native', error: undefined },
      { type: 'native', error: 'Cannot open recordings' },
    );
    expect(sessionErrorMessage(state)).toBe('Could not export FIT');
    expect(state.native).toBe('Cannot open recordings');
  });

  it('starting another operation does not hide an ongoing native error or undo dismissal', () => {
    const native = replay({ type: 'native', error: 'Reconnecting' }, { type: 'operation', error: 'Export failed' });
    expect(sessionErrorMessage(sessionErrorReducer(native, { type: 'operation', error: null }))).toBe('Reconnecting');
    const dismissed = sessionErrorReducer(native, { type: 'dismiss' });
    expect(sessionErrorMessage(sessionErrorReducer(dismissed, { type: 'operation', error: null }))).toBeNull();
  });

  it('dismisses both current channels but permits the next operation failure', () => {
    const state = replay(
      { type: 'native', error: 'Link lost' },
      { type: 'operation', error: 'Connection failed' },
      { type: 'dismiss' },
      { type: 'native', error: 'Link lost' },
    );
    expect(sessionErrorMessage(state)).toBeNull();
    expect(sessionErrorMessage(sessionErrorReducer(state, { type: 'operation', error: 'Connection failed' }))).toBe('Connection failed');
  });
});
