import { describe, expect, it, vi } from 'vitest';
import type { ImportedRideView } from '../../../modules/power-log';
import { HistoryListModel } from '../../../src/features/history/history-list-model';
import { ImportedRideModel } from '../../../src/features/imports/imported-ride-model';
import { failure, holderEnv, sent, settle } from '../holder-env';

vi.mock('../../../modules/power-log/src/native-module', () => ({ default: null }));

const imported: ImportedRideView = {
  importId: 'i1',
  name: 'ride.csv',
  summary: {
    samples: 12_345,
    durationUs: 125_000_000,
    gaps: 2,
    clockAdjustments: 0,
    averagePowerW: 151.6,
    peakPowerW: 604,
    averageCadenceRpm: null,
    riderWorkWh: 5.27,
  },
};

async function ride() {
  const env = await holderEnv('web');
  env.transport.answer('history.page', () => ({ ok: true, value: { rows: [], next: null } }));
  env.transport.answer('imports.open', () => ({ ok: true, value: imported }));
  env.transport.answer('imports.close', () => ({ ok: true, value: null }));
  const list = new HistoryListModel(env.env);
  list.activate();
  list.openCsv();
  await settle();
  const model = new ImportedRideModel(env.env, list.getSnapshot().imported!, list);
  model.activate();
  return { ...env, list, model };
}

describe('ImportedRideModel', () => {
  it('shows the imported ride’s summary and samples line', async () => {
    const { model } = await ride();

    expect(model.getSnapshot()).toMatchObject({
      name: 'ride.csv',
      samples: '12,345 original samples · 2 gaps',
      exportCsv: { label: 'Export CSV', enabled: true },
    });
    expect(model.getSnapshot().summary.map(item => [item.label, item.value])).toEqual([
      ['Duration', '02:05'],
      ['Avg power', '152'],
      ['Peak power', '604'],
      ['Avg cadence', '—'],
      ['Rider work', '5.3'],
    ]);
  });

  it('exports the CSV through the History list once at a time, showing a failure with Dismiss', async () => {
    const { model, exports } = await ride();
    let finish!: (answer: unknown) => void;
    exports.answer('shareImported', () => new Promise(resolve => (finish = resolve as (answer: unknown) => void)));

    model.exportCsv();
    model.exportCsv();
    expect(exports.calls.map(call => [call.entry, call.args])).toEqual([['shareImported', ['i1', 'ride.csv']]]);
    expect(model.getSnapshot().exportCsv.busy).toBe(true);

    finish({ ok: false, failure: failure('exportSinkFailed', 'sink-1') });
    await settle();
    expect(model.getSnapshot().notice).toMatchObject({ id: 'sink-1', actions: ['dismiss'] });
  });

  it('closes the import on ‹ History', async () => {
    const { transport, list, model } = await ride();

    model.backToHistory();
    await settle();

    expect(sent(transport, 'imports.close')).toEqual([{ importId: 'i1' }]);
    expect(list.getSnapshot().imported).toBeNull();
  });
});
