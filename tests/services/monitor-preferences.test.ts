import { describe, expect, it, vi } from 'vitest';
import { defaultMonitorPreferences, validateMonitorPreferences } from '../../src/core/monitor';
import { MonitorPreferencesController } from '../../src/services/monitor-preferences-controller';
import type { MonitorPreferencesStore } from '../../src/services/monitor-preferences-store';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
const store = () => ({ load: vi.fn<MonitorPreferencesStore['load']>(async () => ({ preferences: defaultMonitorPreferences() })), save: vi.fn<MonitorPreferencesStore['save']>(async () => {}) });

describe('persistent monitor preferences', () => {
  it('persists shared analysis and next-ride settings without changing a captured start snapshot', async () => {
    const storage = store(), controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    controller.setSampleHz(8); controller.setDistanceSource('controller'); controller.setSpeedUnit('m/s');
    controller.setWorkoutOptions({ indoor: false, useWatch: false, saveToHealth: false, recordGPS: true });
    const captured = { ...controller.snapshot().preferences.workoutOptions, sampleHz: controller.snapshot().preferences.sampleHz };
    controller.setSampleHz(2); controller.setWorkoutOptions(previous => ({ ...previous, useWatch: true }));
    controller.resetView('ride'); await controller.flush();
    expect(captured).toEqual({ indoor: false, useWatch: false, saveToHealth: false, recordGPS: true, sampleHz: 8 });
    storage.load.mockResolvedValue({ preferences: storage.save.mock.calls.at(-1)![0] });
    const next = new MonitorPreferencesController(storage); await next.hydrate();
    expect(next.snapshot().preferences).toMatchObject({ sampleHz: 2, distanceSource: 'controller', speedUnit: 'm/s', workoutOptions: { useWatch: true } });
    for (const sampleHz of [0, 3, 8.1, '8', NaN]) expect(validateMonitorPreferences({ ...defaultMonitorPreferences(), sampleHz }).sampleHz).toBe(2);
    for (const distanceSource of ['unknown', '__proto__', 'constructor', null]) expect(validateMonitorPreferences({ ...defaultMonitorPreferences(), distanceSource }).distanceSource).toBe('auto');
  });
  it('persists speed units across recreation and view resets', async () => {
    const storage = store(), controller = new MonitorPreferencesController(storage);
    controller.setSpeedUnit('mph');
    await controller.hydrate();
    controller.resetView('ride');
    await controller.flush();
    storage.load.mockResolvedValue({ preferences: storage.save.mock.calls.at(-1)![0] });
    const next = new MonitorPreferencesController(storage); await next.hydrate();
    expect(next.snapshot().preferences.speedUnit).toBe('mph');
    expect(validateMonitorPreferences({ ...defaultMonitorPreferences(), speedUnit: 'knots' }).speedUnit).toBe('km/h');
    expect(validateMonitorPreferences({ ...defaultMonitorPreferences(), speedUnit: undefined }).speedUnit).toBe('km/h');
  });
  it('persists each web layout independently and rejects invalid column counts', async () => {
    const storage = store(), controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    controller.updateView('ride', { webColumns: 3 });
    controller.updateView('battery', { webColumns: 1 });
    await controller.flush();
    storage.load.mockResolvedValue({ preferences: storage.save.mock.calls.at(-1)![0] });
    const next = new MonitorPreferencesController(storage); await next.hydrate();
    expect(next.snapshot().preferences.views.ride.webColumns).toBe(3);
    expect(next.snapshot().preferences.views.battery.webColumns).toBe(1);
    const saved = next.snapshot().preferences;
    for (const invalid of [0, 4, 2.5, '3', null]) expect(validateMonitorPreferences({ ...saved, views: { ...saved.views, ride: { ...saved.views.ride, webColumns: invalid } } }).views.ride.webColumns).toBe(2);
  });
  it('preserves Watch-off across recreation and a late settings load without blocking on save failure', async () => {
    const storage = store(), pending = deferred<Awaited<ReturnType<MonitorPreferencesStore['load']>>>();
    storage.load.mockReturnValueOnce(pending.promise);
    const controller = new MonitorPreferencesController(storage), hydration = controller.hydrate();
    controller.setWorkoutOptions({ indoor: true, useWatch: false });
    pending.resolve({ preferences: defaultMonitorPreferences() }); await hydration; await controller.flush();
    const saved = storage.save.mock.calls.at(-1)![0];
    storage.load.mockResolvedValue({ preferences: saved });
    const recreated = new MonitorPreferencesController(storage); await recreated.hydrate();
    expect(recreated.snapshot().preferences.workoutOptions).toEqual({ indoor: true, useWatch: false, saveToHealth: true });
    storage.save.mockRejectedValueOnce(new Error('Settings temporarily unavailable'));
    recreated.setWorkoutOptions(previous => ({ ...previous, indoor: false })); await recreated.flush();
    expect(recreated.snapshot().preferences.workoutOptions.useWatch).toBe(false);
  });
  it('hydrates once without saving defaults, then merges edits made while loading', async () => {
    const storage = store(), pending = deferred<Awaited<ReturnType<MonitorPreferencesStore['load']>>>();
    const saved = defaultMonitorPreferences(); saved.activeView = 'battery'; saved.views.battery.charts = ['consumedAh'];
    storage.load.mockReturnValue(pending.promise);
    const controller = new MonitorPreferencesController(storage);
    const first = controller.hydrate(); expect(controller.hydrate()).toBe(first);
    controller.updateView('ride', { numbers: ['motorTempC', 'batteryCurrentA'] });
    expect(storage.save).not.toHaveBeenCalled(); expect(controller.snapshot().ready).toBe(false);
    pending.resolve({ preferences: saved }); await first; await controller.flush();
    expect(storage.load).toHaveBeenCalledTimes(1);
    expect(controller.snapshot().preferences.activeView).toBe('battery');
    expect(controller.snapshot().preferences.views.battery.charts).toEqual(['consumedAh']);
    expect(controller.snapshot().preferences.views.ride.numbers).toEqual(['motorTempC', 'batteryCurrentA']);
    expect(storage.save).toHaveBeenCalledTimes(1);
    const clean = store(), untouched = new MonitorPreferencesController(clean);
    await untouched.hydrate(); expect(clean.save).not.toHaveBeenCalled();
  });

  it('serializes rapid edits and writes immutable snapshots in order', async () => {
    const storage = store(), pending = deferred<void>(), writes: string[][] = [];
    storage.save.mockImplementationOnce(async preferences => { writes.push([...preferences.views.ride.numbers]); await pending.promise; })
      .mockImplementationOnce(async preferences => { writes.push([...preferences.views.ride.numbers]); });
    const controller = new MonitorPreferencesController(storage); await controller.hydrate();
    const numbers = ['controllerTempC']; controller.updateView('ride', { numbers }); numbers.push('faultCode');
    controller.updateView('ride', { numbers: ['motorCurrentA', 'controllerTempC'] });
    await Promise.resolve(); expect(storage.save).toHaveBeenCalledTimes(1);
    pending.resolve(); await controller.flush();
    expect(writes).toEqual([['controllerTempC'], ['motorCurrentA', 'controllerTempC']]);
  });

  it('keeps views, chart order and ranges independent when resetting one view', async () => {
    const storage = store(), controller = new MonitorPreferencesController(storage); await controller.hydrate();
    controller.updateView('ride', { numbers: [], charts: ['controllerTempC'], range: 'all' });
    controller.updateView('temperature', { numbers: ['batteryCurrentA'], range: 30 });
    controller.selectView('temperature'); controller.resetView('temperature'); await controller.flush();
    expect(controller.snapshot().preferences.views.temperature).toEqual(defaultMonitorPreferences().views.temperature);
    expect(controller.snapshot().preferences.views.ride).toMatchObject({ numbers: [], charts: ['controllerTempC'], range: 'all' });
    expect(controller.snapshot().preferences.activeView).toBe('temperature');
  });

  it('validates loaded and edited metric IDs without accepting duplicates', async () => {
    const storage = store(), saved = defaultMonitorPreferences(); saved.views.ride.charts = ['fake', 'motorTempC', 'motorTempC'];
    storage.load.mockResolvedValue({ preferences: saved });
    const controller = new MonitorPreferencesController(storage); await controller.hydrate();
    expect(controller.snapshot().preferences.views.ride.charts).toEqual(['motorTempC']);
    controller.updateView('temperature', { numbers: ['batteryCurrentA', 'unknown', 'batteryCurrentA'] }); await controller.flush();
    expect(controller.snapshot().preferences.views.temperature.numbers).toEqual(['batteryCurrentA']);
  });

  it('retains visible choices on save failure and recovers on the next save', async () => {
    const storage = store(); storage.save.mockRejectedValueOnce(new Error('Disk full'));
    const controller = new MonitorPreferencesController(storage); await controller.hydrate();
    controller.updateView('ride', { numbers: ['motorTempC'] }); await controller.flush();
    expect(controller.snapshot().preferences.views.ride.numbers).toEqual(['motorTempC']);
    expect(controller.snapshot().error).toContain('Disk full');
    controller.selectView('battery'); await controller.flush();
    expect(controller.snapshot().error).toBeNull();
    expect(controller.snapshot().preferences.views.ride.numbers).toEqual(['motorTempC']);
  });

  it('reports read failures and never replaces unread data with default settings automatically', async () => {
    const storage = store(); storage.load.mockRejectedValue(new Error('Read denied'));
    const controller = new MonitorPreferencesController(storage);
    controller.updateView('ride', { charts: ['motorCurrentA'] });
    await controller.hydrate(); await controller.flush();
    expect(controller.snapshot()).toMatchObject({ ready: true, error: expect.stringContaining('Read denied') });
    expect(controller.snapshot().preferences.views.ride.charts).toEqual(['motorCurrentA']);
    expect(storage.save).not.toHaveBeenCalled();
  });
});

describe('History and live selections', () => {
  it('keeps the History preset and range apart from the live view and validates stored values', async () => {
    const storage = store(), controller = new MonitorPreferencesController(storage); await controller.hydrate();
    controller.selectView('battery', 'history'); controller.setHistoryRange(600);
    expect(controller.snapshot().preferences).toMatchObject({ activeView: 'ride', historyView: 'battery', historyRange: 600 });
    controller.selectView('temperature');
    expect(controller.snapshot().preferences).toMatchObject({ activeView: 'temperature', historyView: 'battery' });
    expect(validateMonitorPreferences({ ...defaultMonitorPreferences(), historyView: 'nope', historyRange: 7 })).toMatchObject({ historyRange: 'all' });
    expect(validateMonitorPreferences({ ...defaultMonitorPreferences(), historyView: 'nope' }).historyView).toBeUndefined();
  });
});
