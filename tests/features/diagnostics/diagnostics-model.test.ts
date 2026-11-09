import { describe, expect, it, vi } from 'vitest';
import type { TraceView } from '../../../modules/power-log';
import { DiagnosticsModel } from '../../../src/features/diagnostics/diagnostics-model';
import { device, failure, holderEnv, kitText, sent, settle } from '../holder-env';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

const trace = (overrides: Partial<TraceView> = {}): TraceView => ({
  state: 'idle',
  events: 0,
  stopReason: null,
  ...overrides,
});

async function diagnostics(diagnosticLog = true) {
  const base = device('ios');
  const env = await holderEnv('ios', device('ios', { capabilities: { ...base.capabilities, diagnosticLog } }));
  const model = new DiagnosticsModel(env.env);
  model.activate();
  await settle();
  return { ...env, model };
}

describe('DiagnosticsModel', () => {
  it('captures, stops, exports and clears the trace, with today’s status line', async () => {
    const { transport, model, exports } = await diagnostics();
    for (const op of ['diagnostics.startTrace', 'diagnostics.stopTrace', 'diagnostics.clearTrace'] as const)
      transport.answer(op, () => ({ ok: true, value: null }));
    transport.emit('diagnostics.state', {}, { trace: trace() });
    await settle();
    expect(model.getSnapshot()).toMatchObject({
      status: 'No capture',
      capture: { label: 'Start capture', enabled: true },
      stop: null,
      exportLog: { enabled: false },
      clear: { enabled: true },
    });

    model.startCapture();
    await settle();
    transport.emit('diagnostics.state', {}, { trace: trace({ state: 'capturing', events: 4 }) });
    await settle();
    expect(model.getSnapshot()).toMatchObject({ status: 'Capturing · 4 events', stop: { enabled: true } });
    model.stopCapture();
    await settle();
    transport.emit('diagnostics.state', {}, { trace: trace({ state: 'stopped', events: 9, stopReason: 'timeLimit' }) });
    await settle();
    expect(model.getSnapshot()).toMatchObject({
      status: 'Capture stopped · 9 events · Time limit reached',
      capture: { label: 'New capture' },
      exportLog: { enabled: true },
    });
    model.exportLog();
    await settle();
    model.clear();
    await settle();

    expect(exports.calls.map(call => call.entry)).toEqual(['purgeTrace', 'shareTrace', 'purgeTrace']);
    expect(sent(transport, 'diagnostics.startTrace')).toHaveLength(1);
    expect(sent(transport, 'diagnostics.stopTrace')).toHaveLength(1);
    expect(sent(transport, 'diagnostics.clearTrace')).toHaveLength(1);
  });

  it('offers Share diagnostics only with the diagnostic log, and disables the others while a call runs', async () => {
    const plain = await diagnostics(false);
    expect(plain.model.getSnapshot().share).toBeNull();

    const { transport, model, exports } = await diagnostics();
    let finish!: (answer: unknown) => void;
    exports.answer('share', () => new Promise(resolve => (finish = resolve as (answer: unknown) => void)));
    transport.emit('diagnostics.state', {}, { trace: trace({ events: 2 }) });
    await settle();

    model.share();
    model.exportLog();
    const pending = kitText('actionPending', 'ios');
    expect(model.getSnapshot()).toMatchObject({
      share: { busy: true },
      exportLog: { enabled: false, reason: pending },
      capture: { enabled: false, reason: pending },
    });
    expect(exports.calls.map(call => call.entry)).toEqual(['share']);
    finish({ ok: true, value: 'shared' });
    await settle();
    expect(model.getSnapshot().exportLog).toMatchObject({ enabled: true });
  });

  it('runs a share to its outcome when the screen goes away', async () => {
    const { model, exports } = await diagnostics();
    let finish!: (answer: unknown) => void;
    exports.answer('share', () => new Promise(resolve => (finish = resolve as (answer: unknown) => void)));

    model.share();
    model.deactivate();
    finish({ ok: true, value: 'shared' });
    await settle();

    expect(exports.calls[0]).toMatchObject({ entry: 'share', signal: null });
  });

  it('shows the fixed text with Refresh status for unknown, and kit’s for the others', async () => {
    const { transport, model, exports } = await diagnostics();
    exports.answer('shareTrace', () => ({ ok: false, failure: failure('unknown', 'u-1') }));
    transport.answer('diagnostics.stopTrace', () => ({ ok: false, failure: failure('storageFull', 'f-1') }));
    transport.emit('diagnostics.state', {}, { trace: trace({ state: 'capturing', events: 1 }) });
    await settle();

    model.exportLog();
    await settle();
    expect(model.getSnapshot().notice).toEqual({
      id: 'u-1',
      code: 'unknown',
      text: 'Bluetooth diagnostics could not complete. Try again.',
      actions: ['refreshStatus'],
    });

    model.refreshStatus();
    model.stopCapture();
    await settle();
    expect(model.getSnapshot().notice).toMatchObject({
      code: 'storageFull',
      text: kitText('storageFull', 'ios'),
    });
  });

  it('shows nothing when its stream answers storageBusy', async () => {
    const { transport, model } = await diagnostics();
    transport.fail('diagnostics.state', {}, failure('storageBusy', 'busy-1', true));
    await settle();
    expect(model.getSnapshot().notice).toBeNull();
  });
});
