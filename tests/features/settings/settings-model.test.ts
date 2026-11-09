import { describe, expect, it, vi } from 'vitest';
import type { DeviceCapabilities, Platform, SettingsView } from '../../../modules/power-log';
import { SettingsModel } from '../../../src/features/settings/settings-model';
import { device, failure, holderEnv, kitText, sent, settle } from '../holder-env';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

const preset = { numbers: [], charts: [], range: 'seconds120' as const, webColumns: 2 };
const capabilities: DeviceCapabilities = {
  recording: true,
  bluetooth: true,
  gps: true,
  health: 'appleHealth',
  watch: true,
  diagnosticLog: true,
};

function view(
  overrides: Partial<SettingsView['ride']> = {},
  state: SettingsView['state'] = 'ok',
  extra: Partial<SettingsView> = {},
): SettingsView {
  return {
    ride: {
      sampleRate: '2',
      distance: 'auto',
      battery: 'controller',
      indoor: false,
      useWatch: false,
      saveToHealth: false,
      recordGps: null,
      ...overrides,
    },
    app: {
      units: 'metric',
      temperature: 'celsius',
      fitPower: 'rider',
      views: { ride: preset, battery: preset, temperature: preset },
      activeView: 'ride',
      historyView: null,
      historyRange: 'all',
    },
    effective: null,
    state,
    failure: state === 'saveFailed' ? failure('settingsSaveFailed', 'save-1') : null,
    ...extra,
  };
}

async function settings(platform: Platform = 'web', info: Partial<ReturnType<typeof device>> = {}) {
  const env = await holderEnv(platform, device(platform, info));
  const model = new SettingsModel(env.env);
  model.activate();
  await settle();
  return { ...env, model };
}

describe('SettingsModel', () => {
  it('shows every choice row with the values the settings hold', async () => {
    const { transport, model } = await settings();
    transport.emit('settings.state', {}, view({ indoor: true, sampleRate: '4', distance: 'controller' }));
    await settle();

    const { rows, ready, dialog } = model.getSnapshot();
    expect({ ready, dialog }).toEqual({ ready: true, dialog: null });
    expect(
      Object.fromEntries(Object.entries(rows).map(([field, row]) => [field, [row.title, row.value, row.label]])),
    ).toEqual({
      units: ['Units', 'metric', 'Metric'],
      temperature: ['Temperature', 'celsius', '°C'],
      distance: ['Distance source', 'controller', 'Controller estimate'],
      battery: ['Battery consumption source', 'controller', 'Controller'],
      fitPower: ['FIT power source', 'rider', 'Rider power'],
      rideType: ['Ride type', 'indoor', 'Indoor'],
      sampleRate: ['Bike sample rate', '4', '4 Hz'],
    });
    expect(rows.units.choices.map(choice => choice.label)).toEqual(['Metric', 'Imperial']);
    expect(rows.temperature.choices.map(choice => choice.label)).toEqual(['°C', '°F']);
    expect(rows.sampleRate.choices.map(choice => choice.label)).toEqual(['2 Hz', '4 Hz', '8 Hz']);
  });

  it('offers browser GPS alongside Auto and controller distance on web, and phone GPS on the phones', async () => {
    const web = await settings('web');
    web.transport.emit('settings.state', {}, view());
    await settle();
    expect(web.model.getSnapshot().rows.distance.choices).toEqual([
      { value: 'auto', label: 'Auto' },
      { value: 'gps:phone', label: 'GPS · Browser' },
      { value: 'controller', label: 'Controller estimate' },
    ]);

    for (const platform of ['ios', 'android'] as const) {
      const phone = await settings(platform);
      phone.transport.emit('settings.state', {}, view());
      await settle();
      expect(phone.model.getSnapshot().rows.distance.choices).toEqual([
        { value: 'auto', label: 'Auto' },
        { value: 'gps:phone', label: 'GPS · Phone' },
        { value: 'controller', label: 'Controller estimate' },
      ]);
    }
  });

  it('sends each choice as its settings change, one call per row at a time', async () => {
    const { transport, model } = await settings();
    const answers: (() => void)[] = [];
    transport.answer(
      'settings.change',
      () => new Promise(resolve => answers.push(() => resolve({ ok: true, value: null }))),
    );
    transport.emit('settings.state', {}, view());
    await settle();

    model.choose('units', 'imperial');
    model.choose('units', 'metric');
    model.choose('temperature', 'fahrenheit');
    model.choose('distance', 'gps:phone');
    model.choose('battery', 'calculated');
    model.choose('fitPower', 'battery');
    model.choose('rideType', 'indoor');
    model.choose('sampleRate', '8');
    await settle();

    expect(sent(transport, 'settings.change')).toEqual([
      { kind: 'units', value: 'imperial' },
      { kind: 'temperature', value: 'fahrenheit' },
      { kind: 'distance', value: 'gps:phone' },
      { kind: 'battery', value: 'calculated' },
      { kind: 'fitPower', value: 'battery' },
      { kind: 'toggle', field: 'indoor', on: true },
      { kind: 'sampleRate', value: '8' },
    ]);
    expect(model.getSnapshot().rows.units.busy).toBe(true);
    answers.forEach(answer => answer());
    await settle();
    expect(model.getSnapshot().rows.units.busy).toBe(false);
  });

  it('shows Apple Watch, GPS route and Save to Health only with their capability, from effective and saved values', async () => {
    const { transport, model } = await settings('ios', { capabilities });
    transport.answer('settings.change', () => ({ ok: true, value: null }));
    transport.emit(
      'settings.state',
      {},
      view({ useWatch: true, saveToHealth: true, recordGps: null }, 'ok', {
        effective: {
          sampleRate: '2',
          indoor: false,
          useWatch: false,
          saveToHealth: true,
          recordGps: true,
          distanceSource: 'controller',
          batteryMethod: 'controller',
        },
      }),
    );
    await settle();

    expect(model.getSnapshot().toggles).toEqual({
      useWatch: { label: 'Apple Watch', accessibilityLabel: 'Use Apple Watch', value: false, busy: false },
      recordGps: { label: 'GPS route', accessibilityLabel: 'Record GPS route', value: true, busy: false },
      saveToHealth: { label: 'Apple Health', accessibilityLabel: 'Save to Apple Health', value: true, busy: false },
    });

    model.setToggle('useWatch', true);
    model.setToggle('recordGps', false);
    model.setToggle('saveToHealth', false);
    await settle();
    expect(sent(transport, 'settings.change')).toEqual([
      { kind: 'toggle', field: 'useWatch', on: true },
      { kind: 'recordGps', value: false },
      { kind: 'toggle', field: 'saveToHealth', on: false },
    ]);
  });

  it('notes when the ride asks for Health but this device cannot save it', async () => {
    const { transport, model } = await settings('ios', { capabilities });
    const effective = {
      sampleRate: '2' as const,
      indoor: false,
      useWatch: true,
      saveToHealth: false,
      recordGps: false,
      distanceSource: 'controller' as const,
      batteryMethod: 'controller' as const,
    };
    transport.emit('settings.state', {}, view({ useWatch: true, saveToHealth: true }, 'ok', { effective }));
    await settle();
    expect(model.getSnapshot().healthNote).toBe(
      'Watch-recorded rides cannot be saved to Apple Health on this device. This ride will stay in Power Log.',
    );

    transport.emit(
      'settings.state',
      {},
      view({ useWatch: true, saveToHealth: true }, 'ok', { effective: { ...effective, saveToHealth: true } }),
    );
    await settle();
    expect(model.getSnapshot().healthNote).toBeNull();
  });

  it('shows no Health note on the web, which has no Save to Health', async () => {
    const { transport, model } = await settings('web', { capabilities: { ...capabilities, health: null } });
    const effective = {
      sampleRate: '2' as const,
      indoor: false,
      useWatch: false,
      saveToHealth: false,
      recordGps: true,
      distanceSource: 'gps:phone' as const,
      batteryMethod: 'controller' as const,
    };
    transport.emit('settings.state', {}, view({ saveToHealth: true }, 'ok', { effective }));
    await settle();
    expect(model.getSnapshot().toggles.saveToHealth).toBeNull();
    expect(model.getSnapshot().healthNote).toBeNull();
  });

  it('labels the Health row by provider and hides the rows a device lacks', async () => {
    const android = await settings('android', {
      capabilities: { ...capabilities, health: 'healthConnect', watch: false },
    });
    android.transport.emit('settings.state', {}, view());
    await settle();
    expect(android.model.getSnapshot().toggles).toMatchObject({
      useWatch: null,
      saveToHealth: { label: 'Health Connect', accessibilityLabel: 'Save to Health Connect' },
    });

    const web = await settings('web');
    web.transport.emit('settings.state', {}, view());
    await settle();
    expect(web.model.getSnapshot().toggles).toEqual({ useWatch: null, recordGps: null, saveToHealth: null });
  });

  it('offers Reset settings for unreadable settings and keeps the dialog dismissed until the state changes', async () => {
    const { transport, model } = await settings();
    transport.answer('settings.change', () => ({ ok: true, value: null }));
    transport.emit('settings.state', {}, view({}, 'unreadable'));
    await settle();
    expect(model.getSnapshot().dialog).toEqual({
      kind: 'unreadable',
      text: kitText('settingsUnreadable'),
      resetOffered: true,
    });

    model.resetSettings();
    await settle();
    expect(sent(transport, 'settings.change')).toEqual([{ kind: 'reset' }]);

    model.dismissDialog();
    transport.emit('settings.state', {}, view({ sampleRate: '4' }, 'unreadable'));
    await settle();
    expect(model.getSnapshot().dialog).toBeNull();
    transport.emit('settings.state', {}, view());
    transport.emit('settings.state', {}, view({}, 'unreadable'));
    await settle();
    expect(model.getSnapshot().dialog?.kind).toBe('unreadable');
  });

  it('shows a failed save without Reset, dismissed by its occurrence until the next failure', async () => {
    const { transport, model, shell } = await settings();
    transport.emit('settings.state', {}, view({}, 'saveFailed'));
    await settle();
    expect(model.getSnapshot().dialog).toEqual({
      kind: 'saveFailed',
      text: kitText('settingsSaveFailed'),
      resetOffered: false,
    });

    model.dismissDialog();
    await settle();
    expect(model.getSnapshot().dialog).toBeNull();
    expect(shell.dismissed('save-1')).toBe(true);
  });

  it('shows the browser storage state on web only', async () => {
    const web = await settings('web', { storage: 'notPersisted' });
    web.transport.emit('settings.state', {}, view());
    await settle();
    expect(web.model.getSnapshot().storage).toBe('The browser may clear saved rides when storage runs low');

    const phone = await settings('ios', { storage: 'persisted' });
    phone.transport.emit('settings.state', {}, view());
    await settle();
    expect(phone.model.getSnapshot().storage).toBeNull();
  });

  it('adds example rides in developer builds, with the count, the already-added text or the failure', async () => {
    const plain = await settings('ios');
    plain.transport.emit('settings.state', {}, view());
    await settle();
    expect(plain.model.getSnapshot().examples).toBeNull();

    const { transport, model } = await settings('ios', { developerTools: true });
    transport.emit('settings.state', {}, view());
    let added = 3;
    transport.answer('history.addExampleRides', () =>
      added < 0 ? { ok: false, failure: failure('rideActive') } : { ok: true, value: { added } },
    );
    await settle();
    expect(model.getSnapshot().examples).toEqual({
      add: { label: 'Add example rides', busy: false, enabled: true, reason: null },
      result: null,
    });

    model.addExampleRides();
    model.addExampleRides();
    expect(model.getSnapshot().examples?.add).toMatchObject({ busy: true });
    await settle();
    expect(sent(transport, 'history.addExampleRides')).toHaveLength(1);
    expect(model.getSnapshot().examples?.result).toBe('3 example rides added to History.');
    added = 0;
    model.addExampleRides();
    await settle();
    expect(model.getSnapshot().examples?.result).toBe('The example rides are already in History.');
    added = -1;
    model.addExampleRides();
    await settle();
    expect(model.getSnapshot().examples?.result).toBe(kitText('rideActive', 'ios'));
  });

  it('keeps About’s version from device.info and hides the groups while the store is refused', async () => {
    const { transport, model, shell } = await settings('ios', { appVersion: '0.8.1' });
    transport.emit('settings.state', {}, view());
    await settle();
    expect(model.getSnapshot()).toMatchObject({ version: '0.8.1', refused: false });

    shell.report({ ...failure('storageRefused'), detail: 'unavailable' });
    await settle();
    expect(model.getSnapshot()).toMatchObject({ version: '0.8.1', refused: true });
  });

  it('reports a refused store from a change to the shell', async () => {
    const { transport, model, shell } = await settings();
    transport.answer('settings.change', () => ({
      ok: false,
      failure: { ...failure('storageRefused'), detail: 'unreadable' },
    }));
    transport.emit('settings.state', {}, view());
    await settle();

    model.choose('sampleRate', '8');
    await settle();

    expect(shell.getSnapshot().mode).toBe('refused');
  });
});
