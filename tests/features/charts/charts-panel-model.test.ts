import { describe, expect, it, vi } from 'vitest';
import type { ChartHeadView, ChartSource, MetricId, SettingsView } from '../../../modules/power-log';
import { ChartsPanelModel } from '../../../src/features/charts/charts-panel-model';
import { failure, holderEnv, kitText, sent, settle } from '../holder-env';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

const ride: ChartSource = { kind: 'ride', rideId: 'r1' };
const preview: ChartSource = { kind: 'preview' };

function settings(overrides: Partial<SettingsView['app']> = {}, state: SettingsView['state'] = 'ok'): SettingsView {
  return {
    ride: {
      sampleRate: '2',
      distance: 'auto',
      battery: 'controller',
      indoor: false,
      useWatch: false,
      saveToHealth: false,
      recordGps: null,
      syncBeeps: false,
    },
    app: {
      units: 'metric',
      temperature: 'celsius',
      fitPower: 'rider',
      views: {
        ride: {
          numbers: ['humanPowerW', 'cadenceRpm', 'controllerSpeedMps'],
          charts: ['humanPowerW', 'controllerSpeedMps'],
          range: 'seconds120',
          webColumns: 2,
        },
        battery: { numbers: ['batteryVoltageV'], charts: ['batteryVoltageV'], range: 'seconds600', webColumns: 1 },
        temperature: { numbers: ['motorTempC'], charts: ['motorTempC'], range: 'all', webColumns: 3 },
      },
      activeView: 'ride',
      historyView: null,
      historyRange: 'all',
      ...overrides,
    },
    effective: null,
    state,
    failure: state === 'saveFailed' ? failure('settingsSaveFailed', 'save-1') : null,
  };
}

function head(metrics: MetricId[], overrides: Partial<ChartHeadView> = {}): ChartHeadView {
  return {
    revision: 1,
    rangeUs: { startUs: 0, endUs: 90_000_000 },
    live: false,
    originMonoUs: null,
    metrics,
    catchingUp: false,
    ...overrides,
  };
}

async function panel(source: ChartSource = ride, scope: 'live' | 'history' = 'history') {
  const env = await holderEnv('web');
  env.transport.answer('settings.change', () => ({ ok: true, value: null }));
  env.transport.answer('charts.plot', () => ({
    ok: true,
    value: { revision: 1, rangeUs: { startUs: 0, endUs: 90_000_000 }, slotUs: null, series: [] },
  }));
  env.transport.answer('charts.latest', () => ({ ok: true, value: { revision: 1, readings: [] } }));
  env.transport.answer('charts.statistics', () => ({
    ok: true,
    value: { revision: 1, statistics: [], nextUs: null },
  }));
  const model = new ChartsPanelModel(env.env, { source, scope });
  model.activate();
  await settle();
  return { ...env, model };
}

describe('ChartsPanelModel', () => {
  it('shows the view’s numbers and charts through the metric catalog, as selected before the head’s first answer', async () => {
    const { transport, model } = await panel();
    transport.emit('settings.state', {}, settings());
    await settle();
    expect(model.getSnapshot()).toMatchObject({
      ready: true,
      view: 'ride',
      range: 'all',
      numbers: ['humanPowerW', 'cadenceRpm', 'controllerSpeedMps'],
      charts: ['humanPowerW', 'controllerSpeedMps'],
    });

    transport.emit('charts.head', { source: ride }, head(['humanPowerW', 'speedRaw']));
    await settle();

    expect(model.getSnapshot()).toMatchObject({
      numbers: ['humanPowerW', 'cadenceRpm', 'speedRaw'],
      charts: ['humanPowerW', 'speedRaw'],
      columns: 2,
      status: null,
    });
    expect(model.getSnapshot().views.map(view => [view.name, view.selected])).toEqual([
      ['Ride', true],
      ['Battery', false],
      ['Temperature', false],
    ]);
  });

  it('chooses a view and a range for the live panel through the active view and its preset', async () => {
    const { transport, model } = await panel(preview, 'live');
    transport.emit('settings.state', {}, settings());
    await settle();
    expect(model.getSnapshot().range).toBe('seconds120');

    model.chooseView('battery');
    expect(model.getSnapshot()).toMatchObject({ view: 'battery', range: 'seconds600' });
    model.chooseRange('seconds30');
    await settle();

    expect(sent(transport, 'settings.change')).toEqual([
      { kind: 'activeView', value: 'battery' },
      { kind: 'preset', view: 'battery', numbers: null, charts: null, range: 'seconds30', webColumns: null },
    ]);
    expect(model.getSnapshot().range).toBe('seconds30');
  });

  it('chooses a view and a range for a saved ride through History’s own view and range', async () => {
    const { transport, model } = await panel();
    transport.emit('settings.state', {}, settings({ historyView: 'temperature' }));
    await settle();
    expect(model.getSnapshot().view).toBe('temperature');

    model.chooseView('ride');
    model.chooseRange('seconds600');
    await settle();

    expect(sent(transport, 'settings.change')).toEqual([
      { kind: 'historyView', value: 'ride' },
      { kind: 'historyRange', value: 'seconds600' },
    ]);
    expect(model.getSnapshot()).toMatchObject({ view: 'ride', range: 'seconds600' });
  });

  it('adds, removes and reorders the view’s numbers and charts from the editor', async () => {
    const { transport, model } = await panel();
    transport.emit('settings.state', {}, settings());
    await settle();

    model.openEditor('charts');
    model.setEditorQuery('cad');
    expect(model.getSnapshot().editor).toEqual({ tab: 'charts', query: 'cad' });
    model.addMetric('charts', 'cadenceRpm');
    await settle();
    model.removeMetric('numbers', 'cadenceRpm');
    await settle();
    model.moveMetric('charts', 0, 2);
    await settle();
    model.closeEditor();

    expect(sent(transport, 'settings.change')).toEqual([
      {
        kind: 'preset',
        view: 'ride',
        numbers: null,
        charts: ['humanPowerW', 'controllerSpeedMps', 'cadenceRpm'],
        range: null,
        webColumns: null,
      },
      {
        kind: 'preset',
        view: 'ride',
        numbers: ['humanPowerW', 'controllerSpeedMps'],
        charts: null,
        range: null,
        webColumns: null,
      },
      {
        kind: 'preset',
        view: 'ride',
        numbers: null,
        charts: ['controllerSpeedMps', 'cadenceRpm', 'humanPowerW'],
        range: null,
        webColumns: null,
      },
    ]);
    expect(model.getSnapshot().editor).toBeNull();
  });

  it('saves each of quick editor taps, the later one after the earlier answers, and keeps both shown meanwhile', async () => {
    const { transport, model } = await panel();
    const answers: (() => void)[] = [];
    transport.answer(
      'settings.change',
      () => new Promise(resolve => answers.push(() => resolve({ ok: true, value: null }))),
    );
    transport.emit('settings.state', {}, settings());
    await settle();

    model.addMetric('charts', 'cadenceRpm');
    model.addMetric('charts', 'motorTempC');
    await settle();
    expect(sent(transport, 'settings.change')).toHaveLength(1);
    transport.emit('settings.state', {}, settings());
    await settle();
    expect(model.getSnapshot().selected.charts).toEqual([
      'humanPowerW',
      'controllerSpeedMps',
      'cadenceRpm',
      'motorTempC',
    ]);

    answers[0]!();
    await settle();
    expect(sent(transport, 'settings.change').at(-1)).toMatchObject({
      charts: ['humanPowerW', 'controllerSpeedMps', 'cadenceRpm', 'motorTempC'],
    });
    expect(sent(transport, 'settings.change')).toHaveLength(2);
  });

  it('clears the viewport, cursor and A when a view is chosen, and resumes for the same range', async () => {
    const { transport, model } = await panel();
    transport.emit('settings.state', {}, settings());
    transport.emit('charts.head', { source: ride }, head(['humanPowerW']));
    await settle();
    const interact = () => {
      model.feed.setViewport({ startS: 10, endS: 20 }, false);
      model.feed.setCursor(12, null);
      model.feed.setReference(11, null);
    };
    interact();
    const before = model.feed.getSnapshot().resetKey;

    model.chooseView('battery');
    await settle();
    expect(model.feed.getSnapshot()).toMatchObject({
      cursor: null,
      reference: null,
      viewport: { startS: 0, endS: 90 },
    });
    expect(model.feed.getSnapshot().resetKey).toBeGreaterThan(before);

    model.feed.setViewport({ startS: 10, endS: 20 }, false);
    model.chooseRange('all');
    expect(model.feed.getSnapshot().viewport).toEqual({ startS: 0, endS: 90 });
  });

  it('saves a web grid order with each shown id as stored, the raw speed as controller speed', async () => {
    const { transport, model } = await panel();
    transport.emit('settings.state', {}, settings());
    transport.emit('charts.head', { source: ride }, head(['humanPowerW', 'speedRaw']));
    await settle();

    model.reorderCharts(['speedRaw', 'humanPowerW']);
    await settle();

    expect(sent(transport, 'settings.change')).toEqual([
      {
        kind: 'preset',
        view: 'ride',
        numbers: null,
        charts: ['controllerSpeedMps', 'humanPowerW'],
        range: null,
        webColumns: null,
      },
    ]);
  });

  it('starts the editor from the stored selection, controller speed as stored, and keeps unlisted charts through a reorder', async () => {
    const { transport, model } = await panel();
    const view = settings().app.views.ride;
    transport.emit(
      'settings.state',
      {},
      settings({
        views: {
          ...settings().app.views,
          ride: {
            ...view,
            numbers: ['humanPowerW', 'heartRateBpm'],
            charts: ['humanPowerW', 'motorTempC', 'controllerSpeedMps'],
          },
        },
      }),
    );
    transport.emit('charts.head', { source: ride }, head(['humanPowerW', 'speedRaw']));
    await settle();

    expect(model.getSnapshot()).toMatchObject({
      numbers: ['humanPowerW', 'heartRateBpm'],
      charts: ['humanPowerW', 'motorTempC', 'speedRaw'],
      selected: {
        numbers: ['humanPowerW', 'heartRateBpm'],
        charts: ['humanPowerW', 'motorTempC', 'controllerSpeedMps'],
      },
    });

    model.reorderCharts(['speedRaw', 'humanPowerW']);
    await settle();

    expect(sent(transport, 'settings.change').at(-1)).toMatchObject({
      charts: ['controllerSpeedMps', 'motorTempC', 'humanPowerW'],
    });
  });

  it('resets the layout of this view, sets web columns and resets every setting from the menu', async () => {
    const { transport, model } = await panel(preview, 'live');
    transport.emit('settings.state', {}, settings({ activeView: 'battery' }));
    await settle();

    model.resetLayout();
    model.setColumns(3);
    model.openMenu('view');
    model.resetSettings();
    await settle();

    expect(sent(transport, 'settings.change')).toEqual([
      { kind: 'viewReset', view: 'battery' },
      { kind: 'preset', view: 'battery', numbers: null, charts: null, range: null, webColumns: 3 },
      { kind: 'reset' },
    ]);
    expect(model.getSnapshot()).toMatchObject({ columns: 3, menu: null });
  });

  it('plots with the chosen energy smoothing, kept on the screen only', async () => {
    const { transport, model } = await panel();
    transport.emit('settings.state', {}, settings());
    transport.emit('charts.head', { source: ride }, head(['humanPowerW']));
    await settle();

    model.setSmoothing(30);
    await settle();

    expect(model.getSnapshot().smoothingS).toBe(30);
    expect(sent(transport, 'charts.plot').at(-1)).toMatchObject({ smoothingUs: 30_000_000 });
    expect(sent(transport, 'settings.change')).toEqual([]);
  });

  it('says Settings error for unreadable settings or a failed save, after a chart error', async () => {
    const { transport, model } = await panel();
    transport.emit('settings.state', {}, settings({}, 'unreadable'));
    transport.emit('charts.head', { source: ride }, head(['humanPowerW']));
    await settle();
    expect(model.getSnapshot().status).toEqual({
      label: 'Settings error',
      detail: kitText('settingsUnreadable'),
    });

    transport.answer('settings.change', () => ({ ok: false, failure: failure('settingsSaveFailed') }));
    transport.emit('settings.state', {}, settings());
    await settle();
    model.setColumns(1);
    await settle();
    expect(model.getSnapshot().status).toEqual({
      label: 'Settings error',
      detail: kitText('settingsSaveFailed'),
    });

    transport.answer('charts.plot', () => ({ ok: false, failure: failure('chartCapacity') }));
    transport.emit('charts.head', { source: ride }, head(['humanPowerW'], { revision: 2 }));
    await settle();
    expect(model.getSnapshot().status?.label).toBe('Chart error');
  });

  it('awaits data live, and says syncing or no measurements for a saved ride', async () => {
    const live = await panel(preview, 'live');
    live.transport.emit('settings.state', {}, settings());
    await settle();
    expect(live.model.getSnapshot().status).toEqual({ label: 'Awaiting data', detail: 'Waiting for measurements.' });

    const saved = await panel();
    saved.transport.emit('settings.state', {}, settings());
    saved.transport.emit('charts.head', { source: ride }, head([]));
    await settle();
    expect(saved.model.getSnapshot().status).toEqual({
      label: 'No measurements',
      detail: 'No measurements are available.',
    });
  });

  it('opens settings.state and the head while active, and closes both when deactivated', async () => {
    const { transport, model } = await panel();
    transport.emit('settings.state', {}, settings());
    await settle();
    const snapshot = model.getSnapshot();

    model.deactivate();
    transport.emit('settings.state', {}, settings({ activeView: 'battery', historyView: 'battery' }));
    await settle();

    expect(model.getSnapshot()).toBe(snapshot);
    expect(sent(transport, 'cancel')).not.toEqual([]);
  });
});
