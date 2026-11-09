import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Platform } from '../../../modules/power-log';
import { createPowerLogClient } from '../../../modules/power-log/src/client';
import { FakeExportEntries } from '../../../modules/power-log/src/testing/fake-exports';
import { fakeTransport } from '../../../modules/power-log/src/testing/fake-transport';
import { HistoryListModel } from '../../../src/features/history/history-list-model';
import { ConnectionPanelModel } from '../../../src/features/connection/connection-panel-model';
import { AppShellModel } from '../../../src/features/shell/shell-model';
import { device, failure, fakeUi, kitText, settle } from '../holder-env';
import { connectionView, recordingView, runningView } from '../views';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

afterEach(() => vi.useRealTimers());

function shell(platform: Platform = 'ios', options: { compatible?: boolean } = {}) {
  const transport = fakeTransport(options);
  const client = createPowerLogClient(transport);
  const ui = fakeUi(platform === 'web' ? 'web' : 'native');
  const model = new AppShellModel(client, ui, platform);
  const exports = new FakeExportEntries();
  return { transport, client, ui, model, env: { client, ui, shell: model, exports, diagnostics: exports } };
}

describe('ShellModel', () => {
  it('starts, becomes ready on the first device.info and passes its facts and the app settings to the display', async () => {
    const { transport, model } = shell('android');
    model.activate();
    await settle();
    expect(model.getSnapshot().mode).toBe('starting');

    transport.emit('device.info', {}, device('android', { appVersion: '0.8.0', sdkLevel: 35, uses24h: false }));
    await settle();

    expect(model.getSnapshot().mode).toBe('ready');
    expect(model.display.current()).toMatchObject({
      platform: 'android',
      appVersion: '0.8.0',
      sdkLevel: 35,
      uses24h: false,
      units: 'metric',
      temperature: 'celsius',
      fitPower: 'rider',
    });
  });

  it('shows the refusal with the free-space sentence for an unavailable store and keeps the version for About', async () => {
    const { transport, model } = shell('ios');
    model.activate();
    await settle();
    model.report({ ...failure('storageRefused'), detail: 'unavailable' });
    transport.emit(
      'device.info',
      {},
      device('ios', {
        appVersion: '0.8.0',
        capabilities: { ...device('ios').capabilities, recording: false },
      }),
    );
    await settle();

    expect(model.getSnapshot()).toMatchObject({
      mode: 'refused',
      message: "Power Log couldn't open your rides. Free up some storage space, then open Power Log again.",
    });
    expect(model.display.current()).toMatchObject({ appVersion: '0.8.0', capabilities: { recording: false } });
  });

  it('names clearing the site’s data on web and reinstalling on a phone for an unreadable store', () => {
    const web = shell('web').model;
    web.report({ ...failure('storageRefused'), detail: 'unreadable' });
    const phone = shell('android').model;
    phone.report({ ...failure('storageRefused'), detail: 'unreadable' });

    expect(web.getSnapshot().message).toBe(
      "Power Log can't open your saved rides. Clear this site's data to start over.",
    );
    expect(phone.getSnapshot().message).toBe("Power Log can't open your saved rides. Reinstall the app to start over.");
  });

  it('replaces the app with the build mismatch when the client turns incompatible or a call answers apiMismatch', async () => {
    const incompatible = shell('ios', { compatible: false });
    incompatible.model.activate();
    await settle();
    expect(incompatible.model.getSnapshot()).toMatchObject({
      mode: 'mismatch',
      message: 'Install the current Power Log build.',
    });

    const reported = shell('ios');
    reported.model.report(failure('apiMismatch'));
    reported.model.report({ ...failure('storageRefused'), detail: 'unreadable' });
    expect(reported.model.getSnapshot().mode).toBe('mismatch');
  });

  it('shows the refusal when any holder’s call answers storageRefused', async () => {
    const { transport, model, env } = shell('ios');
    transport.answer('history.page', () => ({
      ok: false,
      failure: { ...failure('storageRefused'), detail: 'unreadable' },
    }));
    const list = new HistoryListModel(env);
    list.activate();
    await settle();

    expect(model.getSnapshot().mode).toBe('refused');
    expect(list.getSnapshot().notice).toBeNull();
  });

  it('opens the Ride tab once for a new finish request, and not for one dismissed', async () => {
    const { transport, model, ui } = shell('ios');
    model.activate();
    await settle();
    const request = { requestId: 'q1', rideId: 'r1' };

    transport.emit('recording.state', {}, runningView('r1', { finishRequest: request }));
    await settle();
    transport.emit('recording.state', {}, runningView('r1', { finishRequest: request, pending: 'lap' }));
    await settle();
    expect(ui.navigations).toEqual(['/']);

    model.dismiss('q2');
    transport.emit('recording.state', {}, runningView('r1', { finishRequest: { requestId: 'q2', rideId: 'r1' } }));
    await settle();
    expect(ui.navigations).toEqual(['/']);
  });

  it('offers Retry on every screen while its streams fail storageBusy, and clears it at their next event', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const { transport, model } = shell('ios');
    transport.fail('device.info', {}, failure('storageBusy', 'busy-1', true));
    model.activate();
    await vi.advanceTimersByTimeAsync(0);

    expect(model.getSnapshot().notice).toMatchObject({
      id: 'busy-1',
      code: 'storageBusy',
      text: kitText('storageBusy', 'ios'),
      actions: ['retry'],
    });

    transport.emit('device.info', {}, device('ios'));
    await vi.advanceTimersByTimeAsync(1_000);
    expect(model.getSnapshot().notice).toBeNull();
  });

  it('subscribes its streams again on Retry', async () => {
    const { transport, model } = shell('ios');
    transport.fail('settings.state', {}, failure('readFailed', 'read-1'));
    model.activate();
    await settle();
    expect(model.getSnapshot().notice).toMatchObject({ code: 'readFailed', actions: ['retry'] });
    const before = transport.calls.filter(call => call.op === 'settings.state').length;

    model.retry();
    await settle();

    expect(model.getSnapshot().notice).toBeNull();
    expect(transport.calls.filter(call => call.op === 'settings.state').length).toBeGreaterThan(before);
  });

  it('links to the open ride with its timer, following recording.state, and ticks every second', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'performance'] });
    const { transport, client, model } = shell('ios');
    model.activate();
    await vi.advanceTimersByTimeAsync(0);
    expect(model.getSnapshot().activeRide).toBeNull();

    transport.setNow(1_000_000);
    const origin = client.localTime(0)!;
    vi.setSystemTime(0);
    transport.emit(
      'recording.state',
      {},
      runningView('r1', { timer: { state: 'running', baseUs: 0, originMonoUs: 0 } }),
    );
    await vi.advanceTimersByTimeAsync(0);
    const shown = () => model.getSnapshot().activeRide?.text;
    const startedAt = shown();
    await vi.advanceTimersByTimeAsync(65_000);

    expect(origin).not.toBeNull();
    expect(startedAt).toMatch(/^● Ride in progress · .+ →$/);
    expect(shown()).not.toBe(startedAt);

    transport.emit(
      'recording.state',
      {},
      runningView('r1', { phase: 'paused', timer: { state: 'frozen', baseUs: 125_000_000, originMonoUs: null } }),
    );
    await vi.advanceTimersByTimeAsync(0);
    expect(model.getSnapshot().activeRide).toEqual({ paused: true, text: '● Ride paused · 02:05 →' });

    transport.emit('recording.state', {}, recordingView());
    await vi.advanceTimersByTimeAsync(0);
    expect(model.getSnapshot().activeRide).toBeNull();
  });

  it('shows the bike failure on every screen with Dismiss until the link verifies, hidden everywhere once dismissed', async () => {
    const { transport, model, env } = shell('ios');
    model.activate();
    await settle();
    const lost = failure('bikeReconnecting', 'lost-1');
    transport.emit('connection.state', {}, connectionView({ state: 'reconnecting', display: 'held', failure: lost }));
    await settle();

    expect(model.getSnapshot().bikeNotice).toEqual({
      id: 'lost-1',
      code: 'bikeReconnecting',
      text: kitText('bikeReconnecting', 'ios'),
      actions: ['dismiss'],
    });
    transport.emit('connection.state', {}, connectionView({ state: 'connected', display: 'live' }));
    await settle();
    expect(model.getSnapshot().bikeNotice).toBeNull();

    const denied = failure('bluetoothDenied', 'denied-1');
    const panel = new ConnectionPanelModel(env);
    panel.activate();
    transport.emit('connection.state', {}, connectionView({ failure: denied }));
    await settle();
    expect(model.getSnapshot().bikeNotice?.id).toBe('denied-1');
    expect(panel.getSnapshot().notice?.id).toBe('denied-1');

    model.dismiss('denied-1');
    await settle();
    expect(model.getSnapshot().bikeNotice).toBeNull();
    expect(panel.getSnapshot().notice).toBeNull();
  });

  it('closes its streams and its tick when the app leaves the foreground', async () => {
    const { transport, model } = shell('ios');
    model.activate();
    await settle();
    transport.emit('recording.state', {}, runningView());
    await settle();

    model.deactivate();
    await settle();

    expect(transport.calls.filter(call => call.op === 'cancel').length).toBeGreaterThanOrEqual(4);
  });
});
