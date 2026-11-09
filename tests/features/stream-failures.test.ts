import { describe, expect, it, vi } from 'vitest';
import type { StreamName } from '../../modules/power-log';
import type { Notice, ScreenModel, UiEnv } from '../../src/components/screen-model';
import { ChartsPanelModel } from '../../src/features/charts/charts-panel-model';
import { ConnectionPanelModel } from '../../src/features/connection/connection-panel-model';
import { HistoryListModel } from '../../src/features/history/history-list-model';
import { RideDetailModel } from '../../src/features/history/ride-detail-model';
import { RideControlsModel } from '../../src/features/recording/ride-controls-model';
import { SettingsModel } from '../../src/features/settings/settings-model';
import { failure, holderEnv, settle } from './holder-env';
import { connectionView, recordingView } from './views';

vi.mock('../../modules/power-log/src/native-module', () => ({ default: null }));

type Holder = ScreenModel<{ notice: Notice | null }> & { retry(): void };

const cases: {
  name: string;
  stream: StreamName;
  params: object;
  value: unknown;
  create: (env: UiEnv) => Holder;
}[] = [
  {
    name: 'ConnectionPanelModel',
    stream: 'connection.state',
    params: {},
    value: connectionView(),
    create: env => new ConnectionPanelModel(env),
  },
  {
    name: 'RideControlsModel',
    stream: 'recording.state',
    params: {},
    value: recordingView(),
    create: env => new RideControlsModel(env),
  },
  {
    name: 'HistoryListModel',
    stream: 'history.list',
    params: {},
    value: { revision: 1 },
    create: env => new HistoryListModel(env),
  },
  {
    name: 'SettingsModel',
    stream: 'settings.state',
    params: {},
    value: null,
    create: env => new SettingsModel(env),
  },
  {
    name: 'ChartsPanelModel',
    stream: 'settings.state',
    params: {},
    value: null,
    create: env => new ChartsPanelModel(env, { source: { kind: 'preview' }, scope: 'live' }),
  },
  {
    name: 'ChartsPanelModel (charts.head)',
    stream: 'charts.head',
    params: { source: { kind: 'ride', rideId: 'r1' } },
    value: {
      revision: 1,
      rangeUs: { startUs: 0, endUs: 10_000_000 },
      live: false,
      originMonoUs: null,
      metrics: [],
      catchingUp: false,
    },
    create: env => new ChartsPanelModel(env, { source: { kind: 'ride', rideId: 'r1' }, scope: 'history' }),
  },
  {
    name: 'RideDetailModel',
    stream: 'history.ride',
    params: { rideId: 'r1' },
    value: null,
    create: env => new RideDetailModel(env, 'r1', new HistoryListModel(env)),
  },
];

async function holder(create: (env: UiEnv) => Holder) {
  const env = await holderEnv('web');
  env.transport.answer('history.page', () => ({ ok: true, value: { rows: [], next: null } }));
  const model = create(env.env);
  return { ...env, model };
}

describe.each(cases)('$name', ({ stream, params, value, create }) => {
  const opened = (calls: readonly { op: string }[]) => calls.filter(call => call.op === stream).length;

  it('offers Retry when its stream ends with a failure, and Retry subscribes again', async () => {
    const { transport, model } = await holder(create);
    model.activate();
    await settle();
    transport.fail(stream, params as never, failure('readFailed', `${stream}-1`));
    await settle();
    expect(model.getSnapshot().notice).toMatchObject({ id: `${stream}-1`, actions: ['retry'] });
    const before = opened(transport.calls);

    model.retry();
    await settle();

    expect(opened(transport.calls)).toBeGreaterThan(before);
    expect(model.getSnapshot().notice).toBeNull();
    model.deactivate();
  });

  it('shows a retryable failure with Dismiss until its next event, and nothing for storageBusy', async () => {
    const { transport, model } = await holder(create);
    model.activate();
    await settle();
    transport.fail(stream, params as never, failure('storageBusy', `${stream}-busy`, true));
    await settle();
    expect(model.getSnapshot().notice).toBeNull();
    model.deactivate();

    const retried = await holder(create);
    retried.model.activate();
    await settle();
    retried.transport.fail(stream, params as never, failure('unknown', `${stream}-2`, true));
    await settle();
    expect(retried.model.getSnapshot().notice).toMatchObject({ id: `${stream}-2`, actions: ['dismiss'] });
    if (value !== null) {
      retried.transport.emit(stream, params as never, value as never);
      await vi.waitFor(() => expect(retried.model.getSnapshot().notice).toBeNull(), { timeout: 3000 });
    }
    retried.model.deactivate();
  });
});

it.each([
  { name: 'RideControlsModel', create: (env: UiEnv) => new RideControlsModel(env) },
  { name: 'ConnectionPanelModel', create: (env: UiEnv) => new ConnectionPanelModel(env) },
])(
  '$name keeps one stream’s Retry through another stream’s event, and Retry opens the ended stream',
  async ({ create }) => {
    const { transport, model } = await holder(create);
    model.activate();
    await settle();
    transport.fail('recording.state', {}, failure('readFailed', 'recording-ended'));
    await settle();
    transport.emit('connection.state', {}, connectionView());
    await settle();
    expect(model.getSnapshot().notice).toMatchObject({ id: 'recording-ended', actions: ['retry'] });
    const before = transport.calls.filter(call => call.op === 'recording.state').length;

    model.retry();
    await settle();

    expect(transport.calls.filter(call => call.op === 'recording.state').length).toBeGreaterThan(before);
    expect(model.getSnapshot().notice).toBeNull();
    model.deactivate();
  },
);
