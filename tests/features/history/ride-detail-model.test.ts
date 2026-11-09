import { describe, expect, it, vi } from 'vitest';
import type { RideDetailAllowed, RideDetailView } from '../../../modules/power-log';
import { HistoryListModel } from '../../../src/features/history/history-list-model';
import { RideDetailModel } from '../../../src/features/history/ride-detail-model';
import { device, failure, holderEnv, kitText, sent, settle } from '../holder-env';
import { indoorOptions, no, yes } from '../views';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

const startedUtcUs = Date.UTC(2026, 10, 2, 14, 5) * 1000;

function allowed(overrides: Partial<RideDetailAllowed> = {}): RideDetailAllowed {
  return {
    finish: no('actionUnavailable'),
    discard: no('actionUnavailable'),
    delete: yes,
    export: yes,
    healthRefresh: no('actionUnavailable'),
    healthRetry: no('actionUnavailable'),
    ...overrides,
  };
}

function detail(overrides: Partial<RideDetailView> = {}, ride: Partial<RideDetailView['ride']> = {}): RideDetailView {
  return {
    ride: {
      rideId: 'r1',
      startedUtcUs,
      timerUs: 65_000_000,
      phase: 'completed',
      interrupted: false,
      example: false,
      recordedBy: 'browser',
      options: indoorOptions,
      ...ride,
    },
    laps: 1,
    summary: {
      timerUs: 65_000_000,
      distanceM: 1500,
      averageSpeedMps: 5,
      batteryMethod: 'controller',
      energyWh: 12.34,
      chargeAh: 0.5,
      averagePowerW: 180.4,
      averageCadenceRpm: 82,
      averageHeartRateBpm: null,
      ascentM: null,
      activeEnergyKcal: null,
      physiologyBackfilled: false,
    },
    summaryState: 'ready',
    route: null,
    health: null,
    allowed: allowed(),
    ...overrides,
  };
}

async function page(platform: 'web' | 'ios' = 'web', info = device(platform)) {
  const env = await holderEnv(platform, info);
  env.transport.answer('history.page', () => ({ ok: true, value: { rows: [], next: null } }));
  const list = new HistoryListModel(env.env);
  const model = new RideDetailModel(env.env, 'r1', list);
  model.activate();
  await settle();
  return { ...env, list, model };
}

describe('RideDetailModel', () => {
  it('shows the ride’s title, subtitle and summary in the chosen units', async () => {
    const { transport, model } = await page();
    transport.emit('history.ride', { rideId: 'r1' }, detail());
    await settle();

    const state = model.getSnapshot();
    expect(state).toMatchObject({
      ready: true,
      subtitle: 'Recorded · Indoor · Browser · 1 lap',
      summaryState: 'ready',
    });
    expect(state.summary.map(item => [item.label, item.value, item.unit])).toEqual([
      ['Ride time', '01:05', ''],
      ['Distance', '1.50', 'km'],
      ['Avg speed', '18.0', 'km/h'],
      ['Energy used (controller)', '12.34', 'Wh'],
      ['Charge used (controller)', '0.50', 'Ah'],
      ['Avg power', '180', 'W'],
      ['Avg cadence', '82', 'rpm'],
    ]);
    expect(state.exports.fit).toMatchObject({ label: 'Export FIT', enabled: true });
    expect(state.strava).toMatchObject({ label: 'Open Strava upload', enabled: true });
  });

  it('says no route was recorded for a GPS ride without fixes, once its summary is ready', async () => {
    const { transport, model } = await page();
    const gps = { ...indoorOptions, indoor: false, recordGps: true };
    transport.emit(
      'history.ride',
      { rideId: 'r1' },
      detail({ summaryState: 'pending', route: null }, { options: gps }),
    );
    await settle();
    expect(model.getSnapshot().route).toBeNull();

    transport.emit('history.ride', { rideId: 'r1' }, detail({ route: null }, { options: gps }));
    await settle();
    expect(model.getSnapshot().route).toEqual([]);

    transport.emit('history.ride', { rideId: 'r1' }, detail({ route: null }));
    await settle();
    expect(model.getSnapshot().route).toBeNull();
  });

  it('shows the ride’s Health result on the phones, noting Watch rides, and none on web', async () => {
    const health: RideDetailView['health'] = {
      provider: 'appleHealth',
      writer: 'watch',
      outcome: 'pending',
      reason: null,
      components: [{ component: 'route', state: 'failed', reason: 'permission' }],
    };
    const watchRide = detail(
      { health },
      { recordedBy: 'iphone', options: { ...indoorOptions, useWatch: true, saveToHealth: true } },
    );
    const phone = await page('ios');
    phone.transport.emit('history.ride', { rideId: 'r1' }, watchRide);
    await settle();
    expect(phone.model.getSnapshot()).toMatchObject({
      subtitle: 'Recorded · Indoor · Watch + iPhone · 1 lap',
      health: {
        label: 'Apple Health save pending',
        onWatch: true,
        reason: null,
        components: ['Route not saved: Allow Power Log to save workouts in Apple Health permissions.'],
      },
    });

    phone.transport.emit(
      'history.ride',
      { rideId: 'r1' },
      detail({ health: { ...health, outcome: 'failed', reason: 'locked' } }, { recordedBy: 'iphone' }),
    );
    await settle();
    expect(phone.model.getSnapshot().health).toMatchObject({
      label: 'Apple Health not saved',
      onWatch: false,
      reason: 'Apple Health is locked. Unlock the device to allow Health access.',
    });

    const web = await page('web');
    web.transport.emit('history.ride', { rideId: 'r1' }, watchRide);
    await settle();
    expect(web.model.getSnapshot().health).toBeNull();
  });

  it('exports through the History list, disabling the ride’s other commands until it ends', async () => {
    const { transport, model, exports } = await page();
    let finish!: (answer: { ok: true; value: 'shared' }) => void;
    exports.answer('shareRide', () => new Promise(resolve => (finish = resolve as typeof finish)));
    transport.emit('history.ride', { rideId: 'r1' }, detail({ allowed: allowed({ healthRefresh: yes }) }));
    await settle();

    model.exportRide('fit');
    model.exportRide('zip');
    await settle();

    expect(exports.calls.map(call => call.args)).toEqual([['r1', 'fit', 'rider']]);
    const busy = kitText('exportInProgress');
    expect(model.getSnapshot().exports.zip).toMatchObject({ enabled: false, reason: busy });
    expect(model.getSnapshot().remove).toMatchObject({ enabled: false, reason: busy });
    expect(model.getSnapshot().healthRepair).toMatchObject({ enabled: false, reason: busy });

    finish({ ok: true, value: 'shared' });
    await settle();
    expect(model.getSnapshot().exports.zip).toMatchObject({ enabled: true });
  });

  it('disables Export until history.ride answers, and passes on a pending or unavailable summary', async () => {
    const { transport, model } = await page();
    expect(model.getSnapshot().exports.fit).toMatchObject({ busy: true });

    transport.emit('history.ride', { rideId: 'r1' }, detail({ summary: null, summaryState: 'pending' }));
    await settle();
    expect(model.getSnapshot()).toMatchObject({
      summaryState: 'pending',
      summary: [],
      exports: { fit: { enabled: true, busy: false } },
    });
    transport.emit('history.ride', { rideId: 'r1' }, detail({ summary: null, summaryState: 'unavailable' }));
    await settle();
    expect(model.getSnapshot().summaryState).toBe('unavailable');
  });

  it('keeps Export disabled on a reopened page while the earlier page’s export runs, with one finished export', async () => {
    const { env, transport, list, model, exports } = await page();
    let finish!: (answer: { ok: true; value: 'shared' }) => void;
    exports.answer('shareRide', () => new Promise(resolve => (finish = resolve as typeof finish)));
    transport.emit('history.ride', { rideId: 'r1' }, detail());
    await settle();
    model.exportRide('csv');
    await settle();
    model.deactivate();

    const reopened = new RideDetailModel(env, 'r1', list);
    reopened.activate();
    transport.emit('history.ride', { rideId: 'r1' }, detail());
    await settle();
    expect(reopened.getSnapshot().exports.fit).toMatchObject({ enabled: false, reason: kitText('exportInProgress') });

    finish({ ok: true, value: 'shared' });
    await settle();
    expect(reopened.getSnapshot().exports.fit).toMatchObject({ enabled: true });
    expect(exports.calls).toHaveLength(1);
    reopened.deactivate();
  });

  it('shows a ride left behind by an export when the app went to the background', async () => {
    const { transport, model, exports } = await page();
    exports.answer('shareRide', () => ({ ok: false, failure: failure('exportLeftScreen', 'left-1') }));
    transport.emit('history.ride', { rideId: 'r1' }, detail());
    await settle();

    model.exportRide('csv');
    await settle();

    expect(model.getSnapshot().notice).toMatchObject({ id: 'left-1', code: 'exportLeftScreen', actions: ['dismiss'] });
  });

  it('repairs Health with the retry when allowed, else a refresh, and finishes an interrupted ride’s saved portion', async () => {
    const { transport, model } = await page();
    transport.answer('health.retrySave', () => ({ ok: true, value: null }));
    transport.answer('recording.control', () => ({ ok: true, value: null }));
    transport.emit(
      'history.ride',
      { rideId: 'r1' },
      detail(
        { allowed: allowed({ healthRetry: yes, finish: yes, discard: yes }) },
        { phase: 'interrupted', interrupted: true },
      ),
    );
    await settle();
    expect(model.getSnapshot()).toMatchObject({
      healthRepair: { label: 'Retry Health save', enabled: true },
      finish: { label: 'Finish saved portion', enabled: true },
      discard: { label: 'Discard ride', enabled: true },
      remove: null,
    });

    model.repairHealth();
    model.finishSavedPortion();
    await settle();

    expect(sent(transport, 'health.retrySave')).toEqual([{ rideId: 'r1' }]);
    expect(sent(transport, 'recording.control')).toEqual([]);
    model.finishSavedPortion();
    await settle();
    expect(sent(transport, 'recording.control')).toEqual([{ rideId: 'r1', action: 'finish' }]);
  });

  it('leaves the page when its Delete answers ok or history.ride fails rideDeleted', async () => {
    const deleted = await page();
    deleted.transport.answer('history.delete', () => ({ ok: true, value: null }));
    deleted.transport.emit('history.ride', { rideId: 'r1' }, detail());
    await settle();
    deleted.model.askToDelete();
    deleted.list.confirmDelete();
    await settle();
    expect(sent(deleted.transport, 'history.delete')).toEqual([{ rideId: 'r1' }]);
    expect(deleted.ui.dismissals).toEqual(['/sessions']);

    const gone = await page();
    gone.transport.fail('history.ride', { rideId: 'r1' }, failure('rideDeleted'));
    await settle();
    expect(gone.ui.dismissals).toEqual(['/sessions']);
  });

  it('opens Strava’s upload page, and offers it only for rides that are not examples', async () => {
    const { transport, model, ui } = await page();
    const opened: string[] = [];
    Object.assign(ui, { openUrl: (url: string) => opened.push(url) });
    transport.emit('history.ride', { rideId: 'r1' }, detail());
    await settle();
    model.openStrava();
    expect(opened).toEqual(['https://www.strava.com/upload/select']);

    transport.emit('history.ride', { rideId: 'r1' }, detail({}, { example: true }));
    await settle();
    expect(model.getSnapshot().strava).toBeNull();
  });

  it('shows a failed read with Dismiss, cleared at the next event', async () => {
    const { transport, model } = await page();
    transport.fail('history.ride', { rideId: 'r1' }, failure('readFailed', 'read-1', true));
    await settle();
    expect(model.getSnapshot().notice).toMatchObject({ id: 'read-1', code: 'readFailed' });

    transport.emit('history.ride', { rideId: 'r1' }, detail());
    await vi.waitFor(() => expect(model.getSnapshot().ready).toBe(true), { timeout: 3000 });
    expect(model.getSnapshot().notice).toBeNull();
  });
});
