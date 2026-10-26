import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemorySink, entryText, readZip } from '../fixtures/export/zip/harness';

const native = vi.hoisted(() => ({
  read: vi.fn(),
  status: vi.fn(),
  shared: [] as string[],
  sinks: [] as unknown[],
}));
vi.mock('../../src/services/device', () => ({
  deviceAdapter: { readConnectionDiagnostics: native.read, getDiagnostics: native.status },
}));
vi.mock('../../src/services/export-runtime', () => ({
  exportPlatform: () => 'android',
  openExportSink: async () => {
    const { MemorySink: Sink } = await import('../fixtures/export/zip/harness');
    const sink = new Sink();
    native.sinks.push(sink);
    return sink;
  },
}));
vi.mock('../../src/services/files', () => ({ shareExport: async (uri: string) => void native.shared.push(uri) }));

const { shareConnectionDiagnostics } = await import('../../src/services/diagnostics-export');

const zipText = () => {
  const [summary, events] = readZip((native.sinks.at(-1) as MemorySink).file);
  return { summary: JSON.parse(entryText(summary!)), events: entryText(events!) };
};

describe('Share diagnostics', () => {
  beforeEach(() => {
    native.read.mockReset();
    native.status.mockReset();
    native.shared.length = 0;
    native.sinks.length = 0;
  });

  it('shares one ZIP with the native log, device details and the current connection', async () => {
    native.read.mockResolvedValue({ log: '{"event":"engine_started"}\n', device: { model: 'SM-S908B' } });
    native.status.mockResolvedValue({ status: 'connected', connectionAttempts: 1 });
    await shareConnectionDiagnostics();
    expect(native.shared).toEqual([expect.stringMatching(/^memory:\/\/power-log-diagnostics-\d{8}-\d{6}Z\.zip$/)]);
    const { summary, events } = zipText();
    expect(summary).toMatchObject({
      platform: 'android',
      device: { model: 'SM-S908B' },
      connection: { status: 'connected', connectionAttempts: 1 },
    });
    expect(events).toBe('{"event":"engine_started"}\n');
  });

  it('still shares the summary with the reason when the native log cannot be read', async () => {
    native.read.mockRejectedValue(new Error('Private diagnostic storage is unavailable.'));
    native.status.mockRejectedValue(new Error('busy'));
    await shareConnectionDiagnostics();
    expect(native.shared).toHaveLength(1);
    expect(zipText().summary).toMatchObject({
      device: null,
      connection: null,
      connectionLogError: 'Private diagnostic storage is unavailable.',
    });
  });
});
