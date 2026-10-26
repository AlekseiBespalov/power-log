import { describe, expect, it } from 'vitest';
import { DIAGNOSTICS_FOLDER, writeDiagnosticsZip } from '../../src/core/connection-diagnostics';
import { MemorySink, entryText, readZip } from '../fixtures/export/zip/harness';

describe('Share diagnostics ZIP', () => {
  const exportedAt = '2026-10-26T13:08:29.837Z';
  const device = { manufacturer: 'samsung', model: 'SM-S908B', sdkVersion: 35, powerSave: false };
  const connection = { status: 'reconnecting', connectionAttempts: 3 };
  const log =
    Array.from({ length: 8000 }, (_, sampleCount) => JSON.stringify({ event: 'telemetry_summary', sampleCount })).join(
      '\n',
    ) + '\n';

  it('holds the device summary and the whole connection log', async () => {
    const sink = new MemorySink();
    const result = await writeDiagnosticsZip(sink, {
      exportedAt,
      appVersion: '0.4.0',
      platform: 'android',
      connection,
      read: { log, device },
    });
    expect(result.uri).toBe('memory://power-log-diagnostics-20261026-130829Z.zip');
    const [summary, events, ...rest] = readZip(sink.file);
    expect(rest).toEqual([]);
    expect(summary!.name).toBe(`${DIAGNOSTICS_FOLDER}/device.json`);
    expect(JSON.parse(entryText(summary!))).toEqual({
      appVersion: '0.4.0',
      platform: 'android',
      exportedAt,
      device,
      connection,
    });
    expect(events!.name).toBe(`${DIAGNOSTICS_FOLDER}/connection-log.jsonl`);
    expect(entryText(events!)).toBe(log);
    expect(sink.maxInFlight).toBe(1);
    expect(sink.violations).toEqual([]);
  });

  it('records why the log could not be read and still shares the summary', async () => {
    const sink = new MemorySink();
    await writeDiagnosticsZip(sink, {
      exportedAt,
      appVersion: '0.4.0',
      platform: 'ios',
      connection: null,
      read: { error: 'Private diagnostic storage is unavailable.' },
    });
    const [summary, events] = readZip(sink.file);
    expect(JSON.parse(entryText(summary!))).toMatchObject({
      device: null,
      connectionLogError: 'Private diagnostic storage is unavailable.',
    });
    expect(entryText(events!)).toBe('');
  });

  it('discards the unfinished file when a write fails', async () => {
    const sink = new MemorySink({
      beforeWrite: index => {
        if (index === 2) throw new Error('disk full');
      },
    });
    await expect(
      writeDiagnosticsZip(sink, {
        exportedAt,
        appVersion: '0.4.0',
        platform: 'android',
        connection,
        read: { log, device },
      }),
    ).rejects.toThrow('disk full');
    expect(sink.aborted).toBe(true);
    expect(sink.committed).toBeNull();
  });
});
