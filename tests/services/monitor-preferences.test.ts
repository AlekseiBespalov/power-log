import { describe, expect, it, vi } from 'vitest';
import {
  defaultMonitorPreferences,
  monitorPreferencesJson,
  parseMonitorPreferences,
  type MonitorViewId,
} from '../../src/core/monitor';
import { MonitorPreferencesController } from '../../src/services/monitor-preferences-controller';
import preferenceFixture from '../fixtures/contract/presentation/preferences.json';
import type { MonitorPreferencesStore } from '../../src/services/monitor-preferences-store';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
const store = () => ({
  load: vi.fn<MonitorPreferencesStore['load']>(async () => ({ preferences: defaultMonitorPreferences() })),
  save: vi.fn<MonitorPreferencesStore['save']>(async () => {}),
});

describe('persistent monitor preferences', () => {
  it('persists the export power choice independently of recording and display edits', async () => {
    const storage = store();
    const controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    expect(controller.snapshot().preferences.fitPowerSource).toBe('rider');
    controller.setFitPowerSource('battery');
    controller.setSpeedUnit('mph');
    controller.setBatteryConsumptionSource('calculated');
    controller.setSampleHz(8);
    controller.selectView('battery');
    controller.updateView('ride', { range: 600 });
    await controller.flush();
    const saved = storage.save.mock.calls.at(-1)![0];
    const reloaded = parseMonitorPreferences(monitorPreferencesJson(saved));
    expect(reloaded.fitPowerSource).toBe('battery');
    expect(reloaded.workoutOptions).toEqual(defaultMonitorPreferences().workoutOptions);
    const { fitPowerSource: _source, ...unset } = JSON.parse(monitorPreferencesJson(saved));
    expect(parseMonitorPreferences(JSON.stringify(unset)).fitPowerSource).toBe('rider');
    expect(() => parseMonitorPreferences(JSON.stringify({ ...unset, fitPowerSource: 'unknown' }))).toThrow();
  });

  it('applies session edits after an unreadable load without saving or clearing its error', async () => {
    const fixture = preferenceFixture.loadFailure;
    const storage = store();
    storage.load.mockRejectedValue(new Error(fixture.error));
    const controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    const error = controller.snapshot().error;
    controller.selectView(fixture.edit.activeView as MonitorViewId);
    controller.selectView(fixture.edit.historyView as MonitorViewId, 'history');
    controller.setHistoryRange(600);
    controller.setSpeedUnit('mph');
    controller.setDistanceSource('controller');
    controller.setSampleHz(8);
    controller.setWorkoutOptions({ indoor: true, useWatch: false, saveToHealth: false, recordGPS: true });
    controller.updateView('ride', { numbers: ['faultCode'], charts: ['motorTempC'], range: 30, webColumns: 3 });
    await controller.flush();
    expect(controller.snapshot().preferences).toMatchObject({
      ...fixture.edit,
      speedUnit: 'mph',
      distanceSource: 'controller',
      sampleHz: 8,
      workoutOptions: { indoor: true, useWatch: false, saveToHealth: false, recordGPS: true },
      views: { ride: { numbers: ['faultCode'], charts: ['motorTempC'], range: 30, webColumns: 3 } },
    });
    controller.resetView('ride');
    expect(controller.snapshot().preferences.views.ride).toEqual(defaultMonitorPreferences().views.ride);
    expect(controller.snapshot().error).toBe(error);
    expect(error).toContain(fixture.notice);
    expect(storage.save).not.toHaveBeenCalled();
  });
  it('waits for hydration and pending saves before admitting a recording', async () => {
    const storage = store(),
      pending = deferred<Awaited<ReturnType<MonitorPreferencesStore['load']>>>(),
      saved = deferred<void>();
    storage.load.mockReturnValue(pending.promise);
    storage.save.mockReturnValue(saved.promise);
    const controller = new MonitorPreferencesController(storage);
    const ready = vi.fn();
    const admission = controller.prepareRecording().then(ready);
    controller.setSampleHz(4);
    pending.resolve({ preferences: { ...defaultMonitorPreferences(), sampleHz: 8 } });
    await controller.hydrate();
    expect(ready).not.toHaveBeenCalled();
    saved.resolve();
    await admission;
    expect(ready.mock.calls[0]?.[0].sampleHz).toBe(4);
  });

  it('refuses recording after an unreadable load but accepts a recovered committed revision', async () => {
    const storage = store();
    storage.load.mockRejectedValueOnce(new Error('Read denied'));
    await expect(new MonitorPreferencesController(storage).prepareRecording()).rejects.toThrow(
      "Power Log couldn't read your saved settings. Reset settings to use the defaults.",
    );
    storage.load.mockResolvedValue({
      preferences: defaultMonitorPreferences(),
      warning: 'Recovered previous settings.',
    });
    await expect(new MonitorPreferencesController(storage).prepareRecording()).resolves.toMatchObject({ sampleHz: 2 });
  });

  it('rejects failed persistence instead of admitting the previously committed rate', async () => {
    const storage = store(),
      controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    storage.save.mockRejectedValueOnce(new Error('Disk full'));
    controller.setSampleHz(8);
    await expect(controller.prepareRecording()).rejects.toThrow('Your settings weren’t saved. Disk full');
    expect(controller.snapshot().preferences.sampleHz).toBe(8);
    controller.setDistanceSource('controller');
    await expect(controller.prepareRecording()).resolves.toMatchObject({ sampleHz: 8, distanceSource: 'controller' });
  });

  it('persists shared analysis and next-ride settings without changing a captured start snapshot', async () => {
    const storage = store(),
      controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    controller.setSampleHz(8);
    controller.setDistanceSource('controller');
    controller.setBatteryConsumptionSource('calculated');
    controller.setSpeedUnit('m/s');
    controller.setWorkoutOptions({ indoor: false, useWatch: false, saveToHealth: false, recordGPS: true });
    const captured = {
      ...controller.snapshot().preferences.workoutOptions,
      sampleHz: controller.snapshot().preferences.sampleHz,
      batteryConsumptionSource: controller.snapshot().preferences.batteryConsumptionSource,
    };
    controller.setSampleHz(2);
    controller.setBatteryConsumptionSource('controller');
    controller.setWorkoutOptions(previous => ({ ...previous, useWatch: true }));
    controller.resetView('ride');
    await controller.flush();
    expect(captured).toEqual({
      indoor: false,
      useWatch: false,
      saveToHealth: false,
      recordGPS: true,
      sampleHz: 8,
      batteryConsumptionSource: 'calculated',
    });
    storage.load.mockResolvedValue({ preferences: storage.save.mock.calls.at(-1)![0] });
    const next = new MonitorPreferencesController(storage);
    await next.hydrate();
    expect(next.snapshot().preferences).toMatchObject({
      sampleHz: 2,
      batteryConsumptionSource: 'controller',
      distanceSource: 'controller',
      speedUnit: 'm/s',
      workoutOptions: { useWatch: true },
    });
    for (const sampleHz of [0, 3, 8.1, '8', NaN])
      expect(() => parseMonitorPreferences(JSON.stringify({ ...preferenceFixture.defaults, sampleHz }))).toThrow(
        'Invalid saved monitor settings',
      );
    for (const distanceSource of ['unknown', '__proto__', 'constructor'])
      expect(
        parseMonitorPreferences(JSON.stringify({ ...preferenceFixture.defaults, distanceSource })).distanceSource,
      ).toBe('auto');
    for (const distanceSource of [null, 7])
      expect(() => parseMonitorPreferences(JSON.stringify({ ...preferenceFixture.defaults, distanceSource }))).toThrow(
        'Invalid saved monitor settings',
      );
  });
  it('persists speed units across recreation and view resets', async () => {
    const storage = store(),
      controller = new MonitorPreferencesController(storage);
    controller.setSpeedUnit('mph');
    await controller.hydrate();
    controller.resetView('ride');
    await controller.flush();
    storage.load.mockResolvedValue({ preferences: storage.save.mock.calls.at(-1)![0] });
    const next = new MonitorPreferencesController(storage);
    await next.hydrate();
    expect(next.snapshot().preferences.speedUnit).toBe('mph');
    expect(() =>
      parseMonitorPreferences(JSON.stringify({ ...preferenceFixture.defaults, speedUnit: 'knots' })),
    ).toThrow('Invalid saved monitor settings');
    expect(() =>
      parseMonitorPreferences(JSON.stringify({ ...preferenceFixture.defaults, speedUnit: undefined })),
    ).toThrow('Invalid saved monitor settings');
  });
  it('persists each web layout independently and rejects invalid column counts', async () => {
    const storage = store(),
      controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    controller.updateView('ride', { webColumns: 3 });
    controller.updateView('battery', { webColumns: 1 });
    await controller.flush();
    storage.load.mockResolvedValue({ preferences: storage.save.mock.calls.at(-1)![0] });
    const next = new MonitorPreferencesController(storage);
    await next.hydrate();
    expect(next.snapshot().preferences.views.ride.webColumns).toBe(3);
    expect(next.snapshot().preferences.views.battery.webColumns).toBe(1);
    const saved = preferenceFixture.defaults;
    for (const invalid of [0, 4, 2.5, '3', null])
      expect(() =>
        parseMonitorPreferences(
          JSON.stringify({
            ...saved,
            views: { ...saved.views, ride: { ...saved.views.ride, webColumns: invalid } },
          }),
        ),
      ).toThrow('Invalid saved monitor settings');
  });
  it('preserves Watch-off across recreation and a late settings load without blocking on save failure', async () => {
    const storage = store(),
      pending = deferred<Awaited<ReturnType<MonitorPreferencesStore['load']>>>();
    storage.load.mockReturnValueOnce(pending.promise);
    const controller = new MonitorPreferencesController(storage),
      hydration = controller.hydrate();
    controller.setWorkoutOptions({ indoor: true, useWatch: false });
    pending.resolve({ preferences: defaultMonitorPreferences() });
    await hydration;
    await controller.flush();
    const saved = storage.save.mock.calls.at(-1)![0];
    storage.load.mockResolvedValue({ preferences: saved });
    const recreated = new MonitorPreferencesController(storage);
    await recreated.hydrate();
    expect(recreated.snapshot().preferences.workoutOptions).toEqual({
      indoor: true,
      useWatch: false,
      saveToHealth: true,
    });
    storage.save.mockRejectedValueOnce(new Error('Settings temporarily unavailable'));
    recreated.setWorkoutOptions(previous => ({ ...previous, indoor: false }));
    await recreated.flush();
    expect(recreated.snapshot().preferences.workoutOptions.useWatch).toBe(false);
  });
  it('hydrates once without saving defaults, then merges edits made while loading', async () => {
    const storage = store(),
      pending = deferred<Awaited<ReturnType<MonitorPreferencesStore['load']>>>();
    const saved = defaultMonitorPreferences();
    saved.activeView = 'battery';
    saved.views.battery.charts = ['consumedAh'];
    storage.load.mockReturnValue(pending.promise);
    const controller = new MonitorPreferencesController(storage);
    const first = controller.hydrate();
    expect(controller.hydrate()).toBe(first);
    controller.updateView('ride', { numbers: ['motorTempC', 'batteryCurrentA'] });
    expect(storage.save).not.toHaveBeenCalled();
    expect(controller.snapshot().ready).toBe(false);
    pending.resolve({ preferences: saved });
    await first;
    await controller.flush();
    expect(storage.load).toHaveBeenCalledTimes(1);
    expect(controller.snapshot().preferences.activeView).toBe('battery');
    expect(controller.snapshot().preferences.views.battery.charts).toEqual(['consumedAh']);
    expect(controller.snapshot().preferences.views.ride.numbers).toEqual(['motorTempC', 'batteryCurrentA']);
    expect(storage.save).toHaveBeenCalledTimes(1);
    const clean = store(),
      untouched = new MonitorPreferencesController(clean);
    await untouched.hydrate();
    expect(clean.save).not.toHaveBeenCalled();
  });

  it('submits immutable snapshots immediately and flushes the last save', async () => {
    const storage = store(),
      first = deferred<void>(),
      last = deferred<void>(),
      flushed = vi.fn();
    storage.save.mockReturnValueOnce(first.promise).mockReturnValueOnce(last.promise);
    const controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    const numbers = ['controllerTempC'];
    controller.updateView('ride', { numbers });
    numbers.push('faultCode');
    controller.updateView('ride', { numbers: ['motorCurrentA', 'controllerTempC'] });
    expect(storage.save).toHaveBeenCalledTimes(2);
    expect(storage.save.mock.calls.map(([preferences]) => preferences.views.ride.numbers)).toEqual([
      ['controllerTempC'],
      ['motorCurrentA', 'controllerTempC'],
    ]);
    const flush = controller.flush().then(flushed);
    first.resolve();
    await Promise.resolve();
    expect(flushed).not.toHaveBeenCalled();
    last.resolve();
    await flush;
    expect(flushed).toHaveBeenCalledOnce();
  });

  it.each([true, false])(
    'keeps the latest save outcome when an older save finishes last: failure=%s',
    async failure => {
      const storage = store(),
        first = deferred<void>(),
        last = deferred<void>();
      storage.save.mockReturnValueOnce(first.promise).mockReturnValueOnce(last.promise);
      const controller = new MonitorPreferencesController(storage);
      await controller.hydrate();
      controller.setSpeedUnit('mph');
      const firstFlush = controller.flush();
      controller.setSampleHz(8);
      if (failure) last.reject(new Error('Latest save failed'));
      else last.resolve();
      await controller.flush();
      if (failure) first.resolve();
      else first.reject(new Error('Older save failed'));
      await firstFlush;
      expect(controller.snapshot().error).toBe(failure ? 'Your settings weren’t saved. Latest save failed' : null);
    },
  );

  it('keeps views, chart order and ranges independent when resetting one view', async () => {
    const storage = store(),
      controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    controller.updateView('ride', { numbers: [], charts: ['controllerTempC'], range: 'all' });
    controller.updateView('temperature', { numbers: ['batteryCurrentA'], range: 30 });
    controller.selectView('temperature');
    controller.resetView('temperature');
    await controller.flush();
    expect(controller.snapshot().preferences.views.temperature).toEqual(defaultMonitorPreferences().views.temperature);
    expect(controller.snapshot().preferences.views.ride).toMatchObject({
      numbers: [],
      charts: ['controllerTempC'],
      range: 'all',
    });
    expect(controller.snapshot().preferences.activeView).toBe('temperature');
  });

  it('rejects malformed loaded and edited metric selections without replacing them', async () => {
    const storage = store(),
      saved = defaultMonitorPreferences();
    saved.views.ride.charts = ['fake', 'motorTempC', 'motorTempC'];
    storage.load.mockResolvedValue({ preferences: saved });
    const controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    expect(controller.snapshot().error).toBe(
      "Power Log couldn't read your saved settings. Reset settings to use the defaults.",
    );
    controller.updateView('temperature', { numbers: ['batteryCurrentA'] });
    expect(storage.save).not.toHaveBeenCalled();
    const clean = new MonitorPreferencesController(store());
    await clean.hydrate();
    const previous = clean.snapshot().preferences;
    expect(() =>
      clean.updateView('temperature', { numbers: ['batteryCurrentA', 'unknown', 'batteryCurrentA'] }),
    ).toThrow();
    expect(clean.snapshot().preferences).toBe(previous);
  });

  it('retains visible choices on save failure and recovers on the next save', async () => {
    const storage = store();
    storage.save.mockRejectedValueOnce(new Error('Disk full'));
    const controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    controller.updateView('ride', { numbers: ['motorTempC'] });
    await controller.flush();
    expect(controller.snapshot().preferences.views.ride.numbers).toEqual(['motorTempC']);
    expect(controller.snapshot().error).toContain('Disk full');
    controller.selectView('battery');
    await controller.flush();
    expect(controller.snapshot().error).toBeNull();
    expect(controller.snapshot().preferences.views.ride.numbers).toEqual(['motorTempC']);
  });

  it('reports read failures and never replaces unread data with default settings automatically', async () => {
    const storage = store();
    storage.load.mockRejectedValue(new Error('Read denied'));
    const controller = new MonitorPreferencesController(storage);
    controller.updateView('ride', { charts: ['motorCurrentA'] });
    await controller.hydrate();
    await controller.flush();
    expect(controller.snapshot()).toMatchObject({
      ready: true,
      error: "Power Log couldn't read your saved settings. Reset settings to use the defaults.",
    });
    expect(controller.snapshot().preferences.views.ride.charts).toEqual(['motorCurrentA']);
    expect(storage.save).not.toHaveBeenCalled();
  });
});

describe('History and live selections', () => {
  it('keeps the History preset and range apart from the live view and validates stored values', async () => {
    const storage = store(),
      controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    controller.selectView('battery', 'history');
    controller.setHistoryRange(600);
    expect(controller.snapshot().preferences).toMatchObject({
      activeView: 'ride',
      historyView: 'battery',
      historyRange: 600,
    });
    controller.selectView('temperature');
    expect(controller.snapshot().preferences).toMatchObject({ activeView: 'temperature', historyView: 'battery' });
    expect(() =>
      parseMonitorPreferences(JSON.stringify({ ...preferenceFixture.defaults, historyView: 'nope', historyRange: 7 })),
    ).toThrow('Invalid saved monitor settings');
    expect(() =>
      parseMonitorPreferences(JSON.stringify({ ...preferenceFixture.defaults, historyView: 'nope' })),
    ).toThrow('Invalid saved monitor settings');
  });
});
