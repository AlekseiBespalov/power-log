import { describe, expect, it, vi } from 'vitest';
import { kit, type HistoryRow } from '../../../modules/power-log';
import { HistoryListModel } from '../../../src/features/history/history-list-model';
import { failure, holderEnv, kitText, sent, settle } from '../holder-env';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

const startedUtcUs = Date.UTC(2026, 10, 2, 14, 5) * 1000;

function row(rideId: string, overrides: Partial<HistoryRow> = {}): HistoryRow {
  return {
    rideId,
    startedUtcUs,
    elapsedUs: 65_000_000,
    phase: 'completed',
    interrupted: false,
    indoor: true,
    example: false,
    health: null,
    open: { enabled: true, reason: null },
    delete: { enabled: true, reason: null },
    ...overrides,
  };
}

async function list() {
  const env = await holderEnv('web');
  const model = new HistoryListModel(env.env);
  return { ...env, model };
}

describe('HistoryListModel', () => {
  it('asks for the first page on activate and shows its rows through today’s formats', async () => {
    const { transport, model } = await list();
    transport.answer('history.page', () => ({ ok: true, value: { rows: [row('r1')], next: null } }));
    expect(model.getSnapshot().loading).toBe(true);

    model.activate();
    await settle();

    expect(sent(transport, 'history.page')).toEqual([{ after: null, key: 'history:list:page' }]);
    expect(model.getSnapshot()).toMatchObject({
      loading: false,
      refreshing: false,
      count: '1 ride',
      rows: [{ rideId: 'r1', detail: 'Recorded · Indoor · 01:05', health: null, phase: null, open: { enabled: true } }],
      notice: null,
    });
    expect(model.getSnapshot().rows[0]!.title).toBe(kit.rideDate(startedUtcUs, Date.now() * 1000, true, 'en-GB'));
  });

  it('shows a row’s Health line, and its phase with Interrupted when the ride was', async () => {
    const { transport, model } = await list();
    transport.answer('history.page', () => ({
      ok: true,
      value: {
        rows: [
          row('r1', {
            phase: 'interrupted',
            interrupted: true,
            health: {
              provider: 'appleHealth',
              writer: 'phone',
              outcome: 'pending',
              reason: null,
              components: [{ component: 'workout', state: 'pending', reason: null }],
            },
          }),
        ],
        next: null,
      },
    }));
    model.activate();
    await settle();

    expect(model.getSnapshot().rows[0]).toMatchObject({
      detail: 'Recorded · Indoor · 01:05',
      health: 'Apple Health save pending · 0 of 1 saved',
      phase: 'Needs attention · Interrupted',
    });
  });

  it('asks for the first page again when history.list moves, and not for its first answer', async () => {
    const { transport, model } = await list();
    let rows = [row('r1')];
    transport.answer('history.page', () => ({ ok: true, value: { rows, next: null } }));
    model.activate();
    await settle();
    transport.emit('history.list', {}, { revision: 4 });
    await settle();
    expect(sent(transport, 'history.page')).toHaveLength(1);

    rows = [row('r2'), row('r1')];
    transport.emit('history.list', {}, { revision: 5 });
    await settle();

    expect(sent(transport, 'history.page')).toHaveLength(2);
    expect(model.getSnapshot().rows.map(item => item.rideId)).toEqual(['r2', 'r1']);
    expect(model.getSnapshot().count).toBe('2 rides');
  });

  it('asks for the first page again on Refresh, refreshing until it answers', async () => {
    const { transport, model } = await list();
    const answers: ((rows: HistoryRow[]) => void)[] = [];
    transport.answer(
      'history.page',
      () => new Promise(resolve => answers.push(rows => resolve({ ok: true, value: { rows, next: null } }))),
    );
    model.activate();
    await settle();
    answers[0]!([row('r1')]);
    await settle();

    model.refresh();
    await settle();

    expect(sent(transport, 'history.page')).toEqual([
      { after: null, key: 'history:list:page' },
      { after: null, key: 'history:list:page' },
    ]);
    expect(model.getSnapshot()).toMatchObject({ refreshing: true, rows: [{ rideId: 'r1' }] });

    answers[1]!([row('r2'), row('r1')]);
    await settle();

    expect(model.getSnapshot()).toMatchObject({ refreshing: false, count: '2 rides' });
  });

  it('opens a ride page, and not a ride that is still recording', async () => {
    const { transport, model, ui } = await list();
    transport.answer('history.page', () => ({
      ok: true,
      value: {
        rows: [row('r1', { phase: 'running', open: { enabled: false, reason: 'rideActive' } }), row('r0')],
        next: null,
      },
    }));
    model.activate();
    await settle();
    expect(model.getSnapshot().rows[0]).toMatchObject({
      phase: 'Recording',
      open: { enabled: false, reason: kitText('rideActive') },
    });

    model.openRide('r1');
    model.openRide('r0');

    expect(ui.navigations).toEqual([{ pathname: '/sessions/ride', params: { id: 'r0' } }]);
  });

  it('shows a failed page with Retry and Dismiss, and retries', async () => {
    const { transport, model } = await list();
    let attempt = 0;
    transport.answer('history.page', () =>
      ++attempt === 1
        ? { ok: false, failure: failure('readFailed', 'p-1') }
        : { ok: true, value: { rows: [row('r1')], next: null } },
    );
    model.activate();
    await settle();
    expect(model.getSnapshot().notice).toMatchObject({ id: 'p-1', code: 'readFailed', actions: ['retry', 'dismiss'] });

    model.retry();
    await settle();

    expect(model.getSnapshot().notice).toBeNull();
    expect(model.getSnapshot().rows).toHaveLength(1);
  });

  it('stops loading once a failed first page is dismissed', async () => {
    const { transport, model } = await list();
    transport.answer('history.page', () => ({ ok: false, failure: failure('readFailed', 'p-2') }));
    model.activate();
    await settle();
    expect(model.getSnapshot()).toMatchObject({ loading: false, notice: { id: 'p-2' } });

    model.dismissNotice();

    expect(model.getSnapshot()).toMatchObject({ loading: false, notice: null });
  });

  it('cancels its page query when deactivated and drops the answer', async () => {
    const { transport, model } = await list();
    transport.answer('history.page', () => new Promise(() => undefined));
    model.activate();
    await settle();

    model.deactivate();
    await settle();

    expect(sent(transport, 'cancel')).toEqual(
      expect.arrayContaining([{ key: 'history:list:page' }, { key: expect.stringMatching(/^w:\d+$/) }]),
    );
    expect(model.getSnapshot().loading).toBe(true);
  });

  it('drops an older page answer once a newer query with the same key runs', async () => {
    const { transport, model } = await list();
    const answers: ((rows: HistoryRow[]) => void)[] = [];
    transport.answer(
      'history.page',
      () => new Promise(resolve => answers.push(rows => resolve({ ok: true, value: { rows, next: null } }))),
    );
    model.activate();
    await settle();
    transport.emit('history.list', {}, { revision: 1 });
    await settle();
    transport.emit('history.list', {}, { revision: 2 });
    await settle();

    answers[1]!([row('new')]);
    answers[0]!([row('old')]);
    await settle();

    expect(model.getSnapshot().rows.map(item => item.rideId)).toEqual(['new']);
  });
  it('loads more rides after the cursor and offers Load more until the last page', async () => {
    const { transport, model } = await list();
    transport.answer('history.page', input =>
      input.after === null
        ? { ok: true, value: { rows: [row('r3'), row('r2')], next: 'c1' } }
        : { ok: true, value: { rows: [row('r1')], next: null } },
    );
    model.activate();
    await settle();
    expect(model.getSnapshot().more).toMatchObject({ label: 'Load more rides', enabled: true });

    model.loadMore();
    await settle();

    expect(sent(transport, 'history.page')).toEqual([
      { after: null, key: 'history:list:page' },
      { after: 'c1', key: 'history:list:more' },
    ]);
    expect(model.getSnapshot().rows.map(item => item.rideId)).toEqual(['r3', 'r2', 'r1']);
    expect(model.getSnapshot().more).toBeNull();
  });

  it('loads more without refreshing, and loads the page a reload interrupted once the reload lands', async () => {
    const { transport, model } = await list();
    const held: (() => void)[] = [];
    transport.answer('history.page', input => {
      const value =
        input.after === null
          ? { rows: [row('r3'), row('r2')], next: 'c1' }
          : { rows: [row('r1')], next: null as string | null };
      return input.key === 'history:list:more' && held.length === 0
        ? new Promise(resolve => held.push(() => resolve({ ok: true, value })))
        : { ok: true, value };
    });
    model.activate();
    await settle();

    model.loadMore();
    await settle();
    expect(model.getSnapshot()).toMatchObject({ refreshing: false, more: { busy: true } });
    transport.emit('history.list', {}, { revision: 1 });
    await settle();
    transport.emit('history.list', {}, { revision: 2 });
    await settle();

    expect(sent(transport, 'history.page').at(-1)).toEqual({ after: 'c1', key: 'history:list:more' });
    expect(model.getSnapshot().rows.map(item => item.rideId)).toEqual(['r3', 'r2', 'r1']);
  });

  it('reloads from the first page until as many rows as were loaded, replacing them at the end', async () => {
    const { transport, model } = await list();
    const pages: Record<string, { rows: HistoryRow[]; next: string | null }> = {
      first: { rows: [row('r5'), row('r4')], next: 'c1' },
      c1: { rows: [row('r3'), row('r2')], next: 'c2' },
      c2: { rows: [row('r1')], next: null },
    };
    transport.answer('history.page', input => ({ ok: true, value: pages[input.after ?? 'first']! }));
    model.activate();
    await settle();
    model.loadMore();
    await settle();
    expect(model.getSnapshot().rows).toHaveLength(4);
    pages.first = { rows: [row('r6'), row('r5')], next: 'c1' };

    transport.emit('history.list', {}, { revision: 1 });
    await settle();
    transport.emit('history.list', {}, { revision: 2 });
    await settle();

    expect(sent(transport, 'history.page').slice(2)).toEqual([
      { after: null, key: 'history:list:page' },
      { after: 'c1', key: 'history:list:page' },
    ]);
    expect(model.getSnapshot().rows.map(item => item.rideId)).toEqual(['r6', 'r5', 'r3', 'r2']);
    expect(model.getSnapshot().more).toMatchObject({ enabled: true });
  });

  it('deletes a ride from the sheet, removing its row at once and ending edit mode with the last row', async () => {
    const { transport, model } = await list();
    transport.answer('history.page', () => ({ ok: true, value: { rows: [row('r1')], next: null } }));
    transport.answer('history.delete', () => ({ ok: true, value: null }));
    model.activate();
    await settle();
    model.toggleEdit();
    expect(model.getSnapshot()).toMatchObject({
      editing: true,
      rows: [{ remove: { label: 'Delete ride', enabled: true } }],
    });

    model.askToDelete('r1');
    expect(model.getSnapshot().deleteSheet).toMatchObject({ rideId: 'r1', mode: 'delete', error: null });
    model.confirmDelete();
    await settle();

    expect(sent(transport, 'history.delete')).toEqual([{ rideId: 'r1' }]);
    expect(model.getSnapshot()).toMatchObject({ rows: [], deleteSheet: null, editing: false });
  });

  it('keeps the sheet with the failure’s text and Keep ride when a delete fails, and discards an interrupted ride', async () => {
    const { transport, model } = await list();
    transport.answer('history.page', () => ({
      ok: true,
      value: { rows: [row('r2', { phase: 'interrupted', interrupted: true }), row('r1')], next: null },
    }));
    transport.answer('history.delete', () => ({ ok: false, failure: failure('exportInProgress') }));
    transport.answer('recording.control', () => ({ ok: true, value: null }));
    model.activate();
    await settle();

    model.askToDelete('r1');
    model.confirmDelete();
    await settle();
    expect(model.getSnapshot().deleteSheet).toMatchObject({
      rideId: 'r1',
      error: kitText('exportInProgress'),
      keep: { label: 'Keep ride', enabled: true },
    });
    model.keepRide();
    expect(model.getSnapshot().deleteSheet).toBeNull();

    model.askToDelete('r2');
    expect(model.getSnapshot().deleteSheet).toMatchObject({ mode: 'discard', title: 'Discard ride?' });
    model.confirmDelete();
    await settle();
    expect(sent(transport, 'recording.control')).toEqual([{ rideId: 'r2', action: 'discard' }]);
    expect(model.getSnapshot().rows.map(item => item.rideId)).toEqual(['r1']);
  });

  it('opens a CSV through a deactivation, shows the imported ride and closes it on ‹ History', async () => {
    const { transport, model } = await list();
    let answer!: () => void;
    const imported = {
      importId: 'i1',
      name: 'ride.csv',
      summary: {
        samples: 3,
        durationUs: 2_000_000,
        gaps: 0,
        clockAdjustments: 0,
        averagePowerW: null,
        peakPowerW: null,
        averageCadenceRpm: null,
        riderWorkWh: null,
      },
    };
    transport.answer('history.page', () => ({ ok: true, value: { rows: [], next: null } }));
    transport.answer(
      'imports.open',
      () => new Promise(resolve => (answer = () => resolve({ ok: true, value: imported }))),
    );
    transport.answer('imports.close', () => ({ ok: true, value: null }));
    model.activate();
    await settle();

    model.openCsv();
    model.deactivate();
    await settle();
    answer();
    await settle();

    expect(sent(transport, 'imports.open')).toEqual([{ key: 'history:list:csv' }]);
    expect(model.getSnapshot().imported?.importId).toBe('i1');
    model.closeImport();
    await settle();
    expect(sent(transport, 'imports.close')).toEqual([{ importId: 'i1' }]);
    expect(model.getSnapshot().imported).toBeNull();
  });

  it('closes the open import when the History layout unmounts', async () => {
    const { transport, model } = await list();
    transport.answer('history.page', () => ({ ok: true, value: { rows: [], next: null } }));
    transport.answer('imports.open', () => ({
      ok: true,
      value: {
        importId: 'i2',
        name: 'kept.csv',
        summary: {
          samples: 1,
          durationUs: 0,
          gaps: 0,
          clockAdjustments: 0,
          averagePowerW: null,
          peakPowerW: null,
          averageCadenceRpm: null,
          riderWorkWh: null,
        },
      },
    }));
    transport.answer('imports.close', () => ({ ok: true, value: null }));
    model.activate();
    model.openCsv();
    await settle();
    expect(model.getSnapshot().imported?.importId).toBe('i2');

    model.dispose();
    await settle();

    expect(sent(transport, 'imports.close')).toEqual([{ importId: 'i2' }]);
  });

  it('closes a late import at once when the selection changed or the holder was disposed', async () => {
    const { transport, model } = await list();
    const answers: (() => void)[] = [];
    transport.answer('history.page', () => ({ ok: true, value: { rows: [row('r1')], next: null } }));
    transport.answer(
      'imports.open',
      () =>
        new Promise(resolve =>
          answers.push(() =>
            resolve({
              ok: true,
              value: {
                importId: `i${answers.length}`,
                name: 'late.csv',
                summary: {
                  samples: 0,
                  durationUs: 0,
                  gaps: 0,
                  clockAdjustments: 0,
                  averagePowerW: null,
                  peakPowerW: null,
                  averageCadenceRpm: null,
                  riderWorkWh: null,
                },
              },
            }),
          ),
        ),
    );
    transport.answer('imports.close', () => ({ ok: true, value: null }));
    model.activate();
    await settle();

    model.openCsv();
    model.openRide('r1');
    await settle();
    answers[0]!();
    await settle();
    model.openCsv();
    model.rideSelected();
    await settle();
    answers[1]!();
    await settle();

    expect(model.getSnapshot().imported).toBeNull();
    expect(sent(transport, 'imports.close')).toHaveLength(2);
    expect(model.getSnapshot().openCsv.busy).toBe(false);
  });

  it('keeps a ride export through Back and focus changes, aborts it when the app leaves, and reports it running', async () => {
    const env = await holderEnv('web');
    const background = { value: false, listeners: new Set<(value: boolean) => void>() };
    Object.assign(env.ui, {
      background: {
        current: () => background.value,
        subscribe: (listener: (value: boolean) => void) => {
          background.listeners.add(listener);
          return () => background.listeners.delete(listener);
        },
      },
    });
    const model = new HistoryListModel(env.env);
    env.transport.answer('history.page', () => ({ ok: true, value: { rows: [row('r1')], next: null } }));
    let finish!: (answer: { ok: false; failure: ReturnType<typeof failure> }) => void;
    env.exports.answer('shareRide', () => new Promise(resolve => (finish = resolve as typeof finish)));
    model.activate();
    await settle();
    const answers: unknown[] = [];

    model.exportRide('r1', 'fit', answer => answers.push(answer));
    model.deactivate();
    model.activate();
    expect(model.exporting('r1')).toBe(true);
    expect(env.exports.calls[0]).toMatchObject({ entry: 'shareRide', args: ['r1', 'fit', 'rider'] });
    expect(env.exports.calls[0]!.signal!.aborted).toBe(false);

    background.value = true;
    for (const listener of background.listeners) listener(true);
    expect(env.exports.calls[0]!.signal!.aborted).toBe(true);
    finish({ ok: false, failure: failure('exportLeftScreen') });
    await settle();

    expect(model.exporting('r1')).toBe(false);
    expect(answers).toEqual([{ ok: false, failure: failure('exportLeftScreen') }]);
  });
});
