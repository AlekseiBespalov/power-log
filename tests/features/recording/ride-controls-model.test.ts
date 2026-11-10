import { describe, expect, it, vi } from 'vitest';
import type {
  Availability,
  ConnectionView,
  FailureCode,
  PermissionsView,
  RecordingView,
  RideAllowed,
} from '../../../modules/power-log';
import { RideControlsModel } from '../../../src/features/recording/ride-controls-model';
import { checkExits, device, failure, holderEnv, kitText, rideExits, sent, settle } from '../holder-env';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

const yes: Availability = { enabled: true, reason: null };
const no = (reason: FailureCode): Availability => ({ enabled: false, reason });
const text = (code: FailureCode) => kitText(code);
const options = {
  sampleRate: '8' as const,
  indoor: true,
  useWatch: false,
  saveToHealth: false,
  recordGps: false,
  distanceSource: 'controller' as const,
  batteryMethod: 'controller' as const,
};

function allowed(overrides: Partial<RideAllowed> = {}): RideAllowed {
  return { start: yes, pause: yes, resume: yes, lap: yes, finish: yes, discard: yes, ...overrides };
}

function recording(overrides: Partial<RecordingView> = {}): RecordingView {
  return {
    phase: null,
    ride: null,
    pending: null,
    cue: null,
    timer: { state: 'unavailable', baseUs: 0, originMonoUs: null },
    allowed: allowed(),
    finishRequest: null,
    sources: { controller: 'receiving', heartRate: 'off', gps: 'off' },
    health: null,
    otherTab: false,
    failure: null,
    ...overrides,
  };
}

const running = (rideId = 'r1', overrides: Partial<RecordingView> = {}) =>
  recording({
    phase: 'running',
    ride: { rideId, options },
    timer: { state: 'running', baseUs: 0, originMonoUs: 0 },
    ...overrides,
  });

function connection(display: ConnectionView['display']): ConnectionView {
  return {
    state: display === 'unavailable' ? 'idle' : 'connected',
    display,
    heldUntilMonoUs: null,
    bike: null,
    found: [],
    faultCode: null,
    failure: null,
    allowed: { startSearch: yes, stopSearch: yes, connect: yes, disconnect: yes },
  };
}

const noPermissions: PermissionsView = { action: null, actionFor: null, failure: null };

async function controls(platform: 'web' | 'ios' | 'android' = 'web', info = device(platform)) {
  const env = await holderEnv(platform, info);
  const model = new RideControlsModel(env.env);
  checkExits(model, rideExits);
  model.activate();
  await settle();
  env.transport.emit('connection.state', {}, connection('live'));
  env.transport.emit('recording.permissions', {}, noPermissions);
  return { ...env, model };
}

describe('RideControlsModel', () => {
  it('starts a ride from the bar, shows Starting… while it runs and closes the sheet with a success haptic', async () => {
    const { transport, model, ui } = await controls();
    let answerStart!: (value: { ok: true; value: null }) => void;
    transport.answer('recording.start', () => new Promise(resolve => (answerStart = resolve)));
    transport.emit('recording.state', {}, recording());
    await settle();
    expect(model.getSnapshot().bar).toEqual({ label: 'Start ride', opensSetup: false });
    model.openSetup();

    model.start();
    model.start();

    expect(sent(transport, 'recording.start')).toEqual([{}]);
    expect(model.getSnapshot().bar).toEqual({ label: 'Starting…', opensSetup: false });
    expect(model.getSnapshot().start).toMatchObject({ label: 'Starting…', busy: true, enabled: false });
    await settle();
    answerStart({ ok: true, value: null });
    await settle();
    expect(model.getSnapshot().setupOpen).toBe(false);
    expect(ui.haptics).toEqual(['success']);
  });

  it('counts a sync-beep start down with a Cancel, and waits on the beep for Resume and Finish', async () => {
    const { transport, model } = await controls();
    transport.answer('recording.cancelStart', () => ({ ok: true, value: null }));
    const pending = allowed({ start: no('actionPending') });
    transport.emit(
      'recording.state',
      {},
      recording({ cue: { action: 'start', atMonoUs: 2_500_000 }, allowed: pending }),
    );
    await settle();
    expect(model.getSnapshot().bar).toEqual({ label: 'Starting in 3', opensSetup: false });
    expect(model.getSnapshot().cancelStart).toMatchObject({ label: 'Cancel', enabled: true, busy: false });

    model.cancelStart();
    await settle();
    expect(sent(transport, 'recording.cancelStart')).toEqual([{}]);

    transport.emit('recording.state', {}, running('r1', { cue: { action: 'resume', atMonoUs: 9_000_000 } }));
    await settle();
    expect(model.getSnapshot().cancelStart).toBeNull();
    expect(model.getSnapshot().waiting).toBe('Resuming on the beep…');
    transport.emit('recording.state', {}, running('r1', { cue: { action: 'finish', atMonoUs: 9_000_000 } }));
    await settle();
    expect(model.getSnapshot().waiting).toBe('Finishing on the beep…');
  });

  it('offers Set up ride when Start is disabled, the permission row is due or the bike is unavailable', async () => {
    const { transport, model } = await controls();
    transport.emit('recording.state', {}, recording({ allowed: allowed({ start: no('bikeRequired') }) }));
    await settle();
    expect(model.getSnapshot().bar).toEqual({ label: 'Set up ride', opensSetup: true });
    expect(model.getSnapshot().startReason).toBe(text('bikeRequired'));

    transport.emit('recording.state', {}, recording());
    transport.emit('recording.permissions', {}, { action: 'request', actionFor: 'location', failure: null });
    await settle();
    expect(model.getSnapshot().bar).toEqual({ label: 'Set up ride', opensSetup: true });

    transport.emit('recording.permissions', {}, noPermissions);
    transport.emit('connection.state', {}, connection('unavailable'));
    await settle();
    expect(model.getSnapshot().bar).toEqual({ label: 'Set up ride', opensSetup: true });
  });

  it('pauses with an impact, laps with a light impact and disables Lap while paused', async () => {
    const { transport, model, ui } = await controls();
    transport.answer('recording.control', () => ({ ok: true, value: null }));
    transport.emit('recording.state', {}, running());
    await settle();

    model.pause();
    await settle();
    model.lap();
    await settle();
    expect(sent(transport, 'recording.control')).toEqual([
      { rideId: 'r1', action: 'pause' },
      { rideId: 'r1', action: 'lap' },
    ]);
    expect(ui.haptics).toEqual(['impact', 'lightImpact']);

    transport.emit(
      'recording.state',
      {},
      running('r1', {
        phase: 'paused',
        allowed: allowed({ pause: no('actionUnavailable'), lap: no('actionUnavailable') }),
      }),
    );
    await settle();
    expect(model.getSnapshot().pauseOrResume).toMatchObject({ label: 'Resume', enabled: true });
    expect(model.getSnapshot().lap).toMatchObject({ enabled: false, reason: text('actionUnavailable') });
    expect(model.getSnapshot().chip).toEqual({ text: 'Paused', tone: 'warning' });
  });

  it('saves the ride from the Finish sheet with a success haptic, and keeps the sheet on a failure', async () => {
    const { transport, model, ui } = await controls();
    let attempt = 0;
    transport.answer('recording.control', () =>
      ++attempt === 1 ? { ok: false, failure: failure('storageFailed', 'save-1') } : { ok: true, value: null },
    );
    transport.emit('recording.state', {}, running());
    await settle();

    model.askToFinish();
    expect(model.getSnapshot().finishSheet).toMatchObject({
      step: 'confirm',
      save: { label: 'Save ride', enabled: true },
    });
    model.saveRide();
    await settle();
    expect(model.getSnapshot().finishSheet).not.toBeNull();
    expect(ui.haptics).toEqual(['warning']);

    model.saveRide();
    await settle();
    expect(sent(transport, 'recording.control')).toEqual([
      { rideId: 'r1', action: 'finish' },
      { rideId: 'r1', action: 'finish' },
    ]);
    expect(model.getSnapshot().finishSheet).toBeNull();
    expect(ui.haptics).toEqual(['warning', 'success']);
  });

  it('discards for good only after the discard step, with a warning haptic', async () => {
    const { transport, model, ui } = await controls();
    transport.answer('recording.control', () => ({ ok: true, value: null }));
    transport.emit('recording.state', {}, running());
    await settle();
    model.askToFinish();

    model.discardRide();
    expect(model.getSnapshot().finishSheet).toMatchObject({ step: 'discard', discard: { label: 'Discard for good' } });
    expect(sent(transport, 'recording.control')).toEqual([]);
    model.confirmDiscard();
    await settle();

    expect(sent(transport, 'recording.control')).toEqual([{ rideId: 'r1', action: 'discard' }]);
    expect(model.getSnapshot().finishSheet).toBeNull();
    expect(ui.haptics).toEqual(['warning']);
  });

  it('closes the Finish sheet and resets its discard step when the ride turns interrupted or another ride opens', async () => {
    const { transport, model } = await controls();
    transport.emit('recording.state', {}, running());
    await settle();
    model.askToFinish();
    model.discardRide();

    transport.emit('recording.state', {}, running('r1', { phase: 'interrupted' }));
    await settle();
    expect(model.getSnapshot().finishSheet).toBeNull();
    expect(model.getSnapshot().reviewRide).toBe(true);

    model.askToFinish();
    transport.emit('recording.state', {}, running('r2', { phase: 'interrupted' }));
    await settle();
    expect(model.getSnapshot().finishSheet).toBeNull();
  });

  it('opens the Finish sheet for a ride-card Finish of the open ride, ignores one for another ride and dismisses on Keep recording', async () => {
    const { transport, model, shell } = await controls();
    transport.emit('recording.state', {}, running('r1', { finishRequest: { requestId: 'q-other', rideId: 'r9' } }));
    await settle();
    expect(model.getSnapshot().finishSheet).toBeNull();

    transport.emit('recording.state', {}, running('r1', { finishRequest: { requestId: 'q-1', rideId: 'r1' } }));
    await settle();
    expect(model.getSnapshot().finishSheet).not.toBeNull();
    model.keepRecording();

    expect(model.getSnapshot().finishSheet).toBeNull();
    expect(shell.dismissed('q-1')).toBe(true);
  });

  it('shows the ride failure outside the sheets and in the Finish sheet until it is dismissed everywhere', async () => {
    const { transport, model, shell } = await controls();
    transport.emit('recording.state', {}, running('r1', { failure: failure('storageFull', 'occ-1') }));
    await settle();
    model.askToFinish();
    expect(model.getSnapshot().notice).toMatchObject({ id: 'occ-1', code: 'storageFull', actions: ['reviewRide'] });
    expect(model.getSnapshot().finishSheet?.error).toBe(text('storageFull'));

    model.dismissNotice();

    expect(shell.dismissed('occ-1')).toBe(true);
    expect(model.getSnapshot().notice).toBeNull();
    expect(model.getSnapshot().finishSheet?.error).toBeNull();
  });

  it('stops a preparing ride with a discard and no haptic, Starting ride… being its waiting text', async () => {
    const { transport, model, ui } = await controls();
    transport.answer('recording.control', () => ({ ok: true, value: null }));
    transport.emit(
      'recording.state',
      {},
      recording({
        phase: 'preparing',
        ride: { rideId: 'r1', options },
        timer: { state: 'frozen', baseUs: 0, originMonoUs: null },
        allowed: allowed({ pause: no('actionPending'), finish: no('actionPending') }),
      }),
    );
    await settle();
    expect(model.getSnapshot().waiting).toBe('Starting ride…');
    expect(model.getSnapshot().bar).toBeNull();
    expect(model.getSnapshot().stop).toMatchObject({ label: 'Stop', enabled: true });

    model.stop();
    await settle();

    expect(sent(transport, 'recording.control')).toEqual([{ rideId: 'r1', action: 'discard' }]);
    expect(ui.haptics).toEqual([]);
  });

  it.each(['ios', 'android', 'web'] as const)('says recording is unavailable when %s cannot record', async platform => {
    const info = device(platform, {
      capabilities: {
        recording: false,
        bluetooth: false,
        gps: false,
        health: null,
        watch: false,
        diagnosticLog: false,
      },
    });
    const { transport, model } = await controls(platform, info);
    transport.emit('recording.state', {}, recording());
    await settle();

    expect(model.getSnapshot().supported).toBe(false);
  });

  it('closes the setup sheet before opening Ride settings, and not while a ride is open', async () => {
    const { transport, model, ui } = await controls();
    transport.emit('recording.state', {}, recording());
    await settle();
    model.openSetup();

    model.openRideSettings();

    expect(model.getSnapshot().setupOpen).toBe(false);
    expect(ui.navigations).toEqual(['/settings']);

    transport.emit('recording.state', {}, running());
    await settle();
    model.openRideSettings();
    expect(ui.navigations).toEqual(['/settings']);
  });

  it('runs the permission row: request, the Health access alert, a refresh after a failed read, and no button when unavailable', async () => {
    const { transport, model, ui } = await controls('ios', device('ios'));
    transport.answer('recording.requestPermissions', () => ({ ok: true, value: null }));
    transport.answer('recording.refreshPermissions', () => ({ ok: true, value: null }));
    transport.emit('recording.state', {}, recording());
    transport.emit('recording.permissions', {}, { action: 'request', actionFor: 'location', failure: null });
    await settle();
    expect(model.getSnapshot().permission).toEqual({
      label: 'Allow location',
      detail: null,
      error: null,
      button: 'request',
    });
    model.permissionAction();
    await settle();
    expect(sent(transport, 'recording.requestPermissions')).toEqual([{}]);

    transport.emit('recording.permissions', {}, { action: 'healthHelp', actionFor: 'health', failure: null });
    await settle();
    model.permissionAction();
    expect(ui.alerts).toEqual([['Health access', 'Open Health → your profile → Apps → Power Log to review access.']]);

    transport.emit('recording.permissions', {}, { action: null, actionFor: null, failure: failure('readFailed') });
    await settle();
    expect(model.getSnapshot().permission).toEqual({
      label: 'Check permissions',
      detail: null,
      error: kitText('readFailed', 'ios'),
      button: 'refresh',
    });
    model.permissionAction();
    await settle();
    expect(sent(transport, 'recording.refreshPermissions')).toEqual([{}]);

    transport.emit('recording.permissions', {}, { action: 'unavailable', actionFor: 'health', failure: null });
    await settle();
    expect(model.getSnapshot().permission).toEqual({
      label: 'Health unavailable',
      detail: null,
      error: null,
      button: null,
    });
  });

  it('shows the running timer from the mapped origin and a frozen one from its base', async () => {
    const { transport, model } = await controls();
    transport.setNow(100_000_000);
    transport.emit(
      'recording.state',
      {},
      running('r1', { timer: { state: 'running', baseUs: 0, originMonoUs: 100_000_000 - 65_000_000 } }),
    );
    await settle();
    expect(model.getSnapshot().timer).toBe('01:05');
    expect(model.getSnapshot().chip).toEqual({ text: 'Running', tone: 'accent' });

    transport.emit(
      'recording.state',
      {},
      running('r1', { phase: 'paused', timer: { state: 'frozen', baseUs: 3_725_000_000, originMonoUs: null } }),
    );
    await settle();
    expect(model.getSnapshot().timer).toBe('1:02:05');
  });

  it('describes the open ride: place, Watch, distance source, Health and the Bike, Heart rate and GPS lines', async () => {
    const { transport, model } = await controls('ios', device('ios'));
    transport.emit(
      'recording.state',
      {},
      running('r1', {
        ride: {
          rideId: 'r1',
          options: {
            ...options,
            indoor: false,
            useWatch: true,
            saveToHealth: true,
            recordGps: true,
            distanceSource: 'gps:phone',
          },
        },
        sources: { controller: 'receiving', heartRate: 'waiting', gps: 'weak' },
        health: {
          provider: 'appleHealth',
          writer: 'phone',
          outcome: 'failed',
          reason: 'permission',
          components: [{ component: 'route', state: 'failed', reason: 'permission' }],
        },
      }),
    );
    await settle();

    expect(model.getSnapshot().details).toEqual({
      indoor: false,
      watch: true,
      distanceSource: 'GPS · Phone',
      health: {
        label: 'Apple Health not saved',
        reason: 'Allow Power Log to save workouts in Apple Health permissions.',
        components: ['Route not saved: Allow Power Log to save workouts in Apple Health permissions.'],
      },
      sources: [
        { title: 'Bike', text: 'Receiving samples', tone: 'ready' },
        { title: 'Heart rate', text: 'Waiting for heart rate', tone: 'neutral' },
        { title: 'GPS', text: 'Weak GPS signal', tone: 'warning' },
      ],
    });

    transport.emit('recording.state', {}, recording());
    await settle();
    expect(model.getSnapshot().details).toBeNull();
  });

  it('reports a mismatch from Start to the shell', async () => {
    const { transport, model, shell } = await controls();
    transport.answer('recording.start', () => ({ ok: false, failure: failure('apiMismatch') }));
    transport.emit('recording.state', {}, recording());
    await settle();

    model.start();
    await settle();

    expect(shell.getSnapshot().mode).toBe('mismatch');
  });
});
