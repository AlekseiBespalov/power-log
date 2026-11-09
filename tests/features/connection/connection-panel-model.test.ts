import { describe, expect, it, vi } from 'vitest';
import type { Availability, ConnectionView, FailureCode } from '../../../modules/power-log';
import { ConnectionPanelModel } from '../../../src/features/connection/connection-panel-model';
import { checkExits, connectionExits, device, failure, holderEnv, kitText, sent, settle } from '../holder-env';
import { runningView } from '../views';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

const allowed = (enabled = true, reason: FailureCode | null = null): Availability => ({ enabled, reason });
const text = (code: FailureCode) => kitText(code);

function view(overrides: Partial<ConnectionView> = {}): ConnectionView {
  return {
    state: 'idle',
    display: 'unavailable',
    heldUntilMonoUs: null,
    bike: null,
    found: [],
    faultCode: null,
    failure: null,
    allowed: { startSearch: allowed(), stopSearch: allowed(), connect: allowed(), disconnect: allowed() },
    ...overrides,
  };
}

const found = (bikeId: string, advertisedName: string | null = 'CYCMOTOR') => ({ bikeId, advertisedName, known: null });

async function panel(platform: 'web' | 'ios' = 'web') {
  const env = await holderEnv(platform);
  const model = new ConnectionPanelModel(env.env);
  checkExits(model, connectionExits);
  model.activate();
  await settle();
  return { ...env, model };
}

describe('ConnectionPanelModel', () => {
  it('starts a web search inside the tap, with nothing awaited first', async () => {
    const { transport, model } = await panel();
    transport.answer('connection.startSearch', () => ({ ok: true, value: null }));
    transport.emit('connection.state', {}, view());
    await settle();
    expect(model.getSnapshot().findBike).toMatchObject({ label: 'Find bike', enabled: true });

    model.findBike();

    expect(sent(transport, 'connection.startSearch')).toEqual([{}]);
    expect(model.getSnapshot().findBike).toMatchObject({ busy: true });
  });

  it('runs one search per control however often Find bike is tapped', async () => {
    const { transport, model } = await panel();
    transport.answer('connection.startSearch', () => new Promise(() => undefined));
    transport.emit('connection.state', {}, view());
    await settle();

    model.findBike();
    model.findBike();

    expect(sent(transport, 'connection.startSearch')).toHaveLength(1);
  });

  it('connects a found bike, disabling the other rows with actionPending while Cancel connection stays enabled', async () => {
    const { transport, model } = await panel();
    transport.answer('connection.connect', () => new Promise(() => undefined));
    transport.emit('connection.state', {}, view({ state: 'searching', found: [found('D1'), found('D2', 'My bike')] }));
    await settle();
    expect(model.getSnapshot().stopSearch).toMatchObject({ label: 'Stop search', enabled: true });
    expect(model.getSnapshot().bikes.map(row => row.control.label)).toEqual([
      expect.stringMatching(/^Connect CYC bike · [0-9A-F]{6}$/),
      expect.stringMatching(/^Connect My bike · [0-9A-F]{6}$/),
    ]);

    model.connect('D1');

    expect(sent(transport, 'connection.connect')).toEqual([{ bikeId: 'D1' }]);
    expect(model.getSnapshot().bikes[1]!.control).toMatchObject({ enabled: false, reason: text('actionPending') });
    expect(model.getSnapshot().stopSearch).toMatchObject({ enabled: false, reason: text('actionPending') });
    expect(model.getSnapshot().cancelConnection).toMatchObject({ label: 'Cancel connection', enabled: true });
    transport.emit('connection.state', {}, view({ state: 'connecting' }));
    await settle();
    expect(model.getSnapshot().cancelConnection).toMatchObject({ label: 'Cancel connection', enabled: true });
    expect(model.getSnapshot().status).toBe('Connecting…');
  });

  it('cancels a connection with disconnect and drops the connect answer it cancelled', async () => {
    const { transport, model } = await panel();
    let answerConnect!: (value: { cancelled: true }) => void;
    transport.answer('connection.connect', () => new Promise(resolve => (answerConnect = resolve)));
    transport.answer('connection.disconnect', () => ({ ok: true, value: null }));
    transport.emit('connection.state', {}, view({ state: 'searching', found: [found('D1')] }));
    await settle();
    model.connect('D1');
    transport.emit('connection.state', {}, view({ state: 'connecting' }));
    await settle();

    model.cancelConnection();
    await settle();
    answerConnect({ cancelled: true });
    await settle();

    expect(sent(transport, 'connection.disconnect')).toEqual([{}]);
    expect(model.getSnapshot().notice).toBeNull();
  });

  it('shows the stream failure with Open Settings first for bluetoothDenied on a phone', async () => {
    const { transport, model, ui, shell } = await panel('ios');
    transport.emit('connection.state', {}, view({ failure: failure('bluetoothDenied', 'f-1') }));
    await settle();

    expect(model.getSnapshot().notice).toEqual({
      id: 'f-1',
      code: 'bluetoothDenied',
      text: kitText('bluetoothDenied', 'ios'),
      actions: ['openAppSettings', 'dismiss'],
    });
    model.openSettings();
    expect(ui.settingsOpened).toBe(1);
    model.dismissNotice();
    expect(model.getSnapshot().notice).toBeNull();
    expect(shell.dismissed('f-1')).toBe(true);
  });

  it('offers only Dismiss for bluetoothDenied on web and keeps a dismissed failure hidden while it stands', async () => {
    const { transport, model } = await panel();
    transport.emit('connection.state', {}, view({ failure: failure('bluetoothDenied') }));
    await settle();
    expect(model.getSnapshot().notice?.actions).toEqual(['dismiss']);

    model.dismissNotice();
    transport.emit('connection.state', {}, view({ failure: failure('bluetoothDenied'), found: [found('D9')] }));
    await settle();

    expect(model.getSnapshot().notice).toBeNull();
  });

  it('names the connected bike, its controller speed note and its fault', async () => {
    const { transport, model } = await panel();
    transport.emit(
      'connection.state',
      {},
      view({
        state: 'connected',
        display: 'live',
        faultCode: 7,
        bike: {
          bikeId: 'D1',
          advertisedName: 'CYCMOTOR',
          identity: { decoder: 'cyc', model: 'X12', firmware: '1.2', protocol: '3' },
          controllerSpeed: false,
        },
      }),
    );
    await settle();

    expect(model.getSnapshot()).toMatchObject({
      heading: expect.stringMatching(/^CYC X12 · [0-9A-F]{6}$/),
      status: 'Connected',
      accent: true,
      shownDisplay: 'live',
      speedNote: 'Controller speed needs CYC X6 or X12 firmware with protocol 5.3.',
      fault: "Your controller reports fault 7. Check the bike's display.",
      disconnect: { label: 'Disconnect', enabled: true },
      findBike: null,
    });
  });

  it('disables Disconnect with rideActive during a ride and shows its failure in the panel', async () => {
    const { transport, model } = await panel();
    transport.answer('connection.disconnect', () => ({ ok: false, failure: failure('rideActive', 'd-1') }));
    transport.emit('connection.state', {}, view({ state: 'connected', display: 'live' }));
    await settle();
    model.disconnect();
    await settle();
    expect(model.getSnapshot().notice).toMatchObject({ id: 'd-1', code: 'rideActive', actions: ['dismiss'] });

    transport.emit(
      'connection.state',
      {},
      view({
        state: 'connected',
        display: 'held',
        allowed: { ...view().allowed, disconnect: allowed(false, 'rideActive') },
      }),
    );
    await settle();
    expect(model.getSnapshot().disconnect).toMatchObject({ enabled: false, reason: text('rideActive') });
    expect(model.getSnapshot().shownDisplay).toBe('live');

    transport.emit('recording.state', {}, runningView());
    await settle();
    expect(model.getSnapshot().shownDisplay).toBe('held');
  });

  it('reports a refused store and a mismatch to the shell', async () => {
    const { transport, model, shell } = await panel();
    transport.answer('connection.startSearch', () => ({ ok: false, failure: failure('storageRefused') }));
    transport.emit('connection.state', {}, view());
    await settle();

    model.findBike();
    await settle();

    expect(shell.getSnapshot().mode).toBe('refused');
    expect(model.getSnapshot().notice).toBeNull();
  });

  it('shows nothing for a busy stream and takes its next event', async () => {
    const { transport, model } = await panel();
    transport.fail('connection.state', {}, failure('storageBusy', null, true));
    await settle();
    expect(model.getSnapshot().ready).toBe(false);

    transport.emit('connection.state', {}, view({ state: 'searching' }));
    await vi.waitFor(() => expect(model.getSnapshot().status).toBe('Searching…'), { timeout: 3000 });
    expect(model.getSnapshot().notice).toBeNull();
  });

  it('stops following the stream and the shell when deactivated, and keeps the snapshot', async () => {
    const { transport, model, shell } = await panel();
    transport.emit('connection.state', {}, view());
    await settle();
    const snapshot = model.getSnapshot();

    model.deactivate();
    transport.emit('connection.state', {}, view({ state: 'searching' }));
    shell.dismiss('other');
    await settle();

    expect(model.getSnapshot()).toBe(snapshot);
  });

  it('keeps Find bike enabled without a Bluetooth adapter, so it can answer bluetoothUnavailable', async () => {
    const { transport, model } = await holderEnv('web', device('web')).then(async env => {
      const created = new ConnectionPanelModel(env.env);
      created.activate();
      await settle();
      return { ...env, model: created };
    });
    transport.emit('connection.state', {}, view({ state: 'unavailable' }));
    await settle();

    expect(model.getSnapshot().findBike).toMatchObject({ enabled: true });
    expect(model.getSnapshot().status).toBe('Not connected');
  });
});
