import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExportError } from '../../src/core/export/types';
import { exportRecording, exportRide } from '../../src/services/ride-export';
import { syntheticCsvHeader, syntheticCsvRow, syntheticCsvSample } from '../core/csv-fixture';

const mocks = vi.hoisted(() => ({
  listeners: [] as ((state: string) => void)[],
  removed: 0,
  exportFit: vi.fn(),
  exportZip: vi.fn(),
  share: vi.fn(),
  openSink: vi.fn(),
}));
vi.mock('react-native', () => ({
  Platform: { OS: 'ios' },
  AppState: {
    currentState: 'active',
    addEventListener: (_event: string, listener: (state: string) => void) => {
      mocks.listeners.push(listener);
      return { remove: () => void mocks.removed++ };
    },
  },
}));
vi.mock('../../src/core/export/fit-export', () => ({ exportFit: mocks.exportFit }));
vi.mock('../../src/core/export/zip-export', () => ({ exportZip: mocks.exportZip }));
vi.mock('../../src/services/export-runtime', () => ({
  exportPlatform: () => 'ios',
  exportSource: () => 'source',
  openExportSink: mocks.openSink,
}));
vi.mock('../../src/services/files', () => ({ shareExport: mocks.share }));

const until = (signal: AbortSignal) =>
  new Promise<never>((_, reject) =>
    signal.addEventListener('abort', () => reject(new ExportError('cancelled', 'The export was cancelled.'))),
  );

beforeEach(() => {
  vi.resetAllMocks();
  mocks.listeners = [];
  mocks.removed = 0;
  mocks.exportFit.mockResolvedValue({ uri: 'file:///exports/a/ride.fit', records: 1, recordInterval: 1 });
  mocks.exportZip.mockResolvedValue({ uri: 'file:///exports/b/ride.zip' });
});

describe('History ride export', () => {
  it('exports FIT with the chosen distance source and shares the committed file', async () => {
    await exportRide('fit', 'ride', 'gps:watch');
    const [source, openSink, request] = mocks.exportFit.mock.calls[0]!;
    expect([source, openSink]).toEqual(['source', mocks.openSink]);
    expect(request).toMatchObject({ rideId: 'ride', distanceSource: 'gps:watch', context: { platform: 'ios' } });
    expect(new Date(request.context.exportedAt).toISOString()).toBe(request.context.exportedAt);
    expect(mocks.share).toHaveBeenCalledExactlyOnceWith('file:///exports/a/ride.fit');
    expect(mocks.removed).toBe(1);
  });

  it('exports the ride-data ZIP without a distance source', async () => {
    await exportRide('zip', 'ride', 'controller');
    expect(mocks.exportZip.mock.calls[0]![2]).not.toHaveProperty('distanceSource');
    expect(mocks.share).toHaveBeenCalledExactlyOnceWith('file:///exports/b/ride.zip');
  });

  it('cancels when the app moves to the background and shares nothing', async () => {
    mocks.exportZip.mockImplementation((_source, _sink, request: { signal: AbortSignal }) => until(request.signal));
    const exported = exportRide('zip', 'ride');
    for (const listener of mocks.listeners) listener('inactive');
    await Promise.resolve();
    expect(mocks.exportZip.mock.calls[0]![2].signal.aborted).toBe(false);
    for (const listener of mocks.listeners) listener('background');
    await expect(exported).rejects.toThrow('Power Log left the screen');
    expect(mocks.share).not.toHaveBeenCalled();
    expect(mocks.removed).toBe(1);
  });

  it('keeps other failures as they are', async () => {
    mocks.exportFit.mockRejectedValue(new ExportError('noRecords', 'This ride has no records for a FIT file.'));
    await expect(exportRide('fit', 'ride')).rejects.toThrow('This ride has no records for a FIT file.');
    expect(mocks.share).not.toHaveBeenCalled();
  });
});

describe('imported CSV re-export', () => {
  const sink = (failing = false) => {
    const written: Uint8Array[] = [];
    const value = {
      written,
      write: vi.fn(async (bytes: Uint8Array) => {
        if (failing) throw new Error('Disk full');
        written.push(bytes.slice());
      }),
      writeAt: vi.fn(),
      beginDeflate: vi.fn(),
      endDeflate: vi.fn(),
      commit: vi.fn(async (name: string) => ({ uri: `file:///app/Exports/0f0e/${name}` })),
      abort: vi.fn(async () => {}),
    };
    mocks.openSink.mockResolvedValue(value);
    return value;
  };

  it('publishes the formatted rows as a CSV export and shares the committed file', async () => {
    const opened = sink();
    await exportRecording('My ride.csv', [syntheticCsvSample()]);
    expect(mocks.openSink.mock.calls[0]![0]).toBe('csv');
    expect(mocks.openSink.mock.calls[0]![1]).toMatchObject({ platform: 'ios' });
    expect(Buffer.concat(opened.written).toString('utf8')).toBe(
      `${syntheticCsvHeader}\n${syntheticCsvRow(syntheticCsvSample())}\n`,
    );
    expect(opened.commit).toHaveBeenCalledExactlyOnceWith('My-ride.csv');
    expect(mocks.share).toHaveBeenCalledExactlyOnceWith('file:///app/Exports/0f0e/My-ride.csv');
  });

  it('aborts the staged file and shares nothing when writing fails', async () => {
    const opened = sink(true);
    await expect(exportRecording('ride.csv', [syntheticCsvSample()])).rejects.toThrow('Disk full');
    expect(opened.abort).toHaveBeenCalledOnce();
    expect(opened.commit).not.toHaveBeenCalled();
    expect(mocks.share).not.toHaveBeenCalled();
  });

  it('names the file so the sink accepts it and keeps its CSV extension', async () => {
    const opened = sink();
    for (const name of ['.csv', '', 'ride.CSV', 'notes', `${'a'.repeat(200)}.csv`]) await exportRecording(name, []);
    expect(opened.commit.mock.calls).toEqual([
      ['telemetry.csv'],
      ['telemetry.csv'],
      ['ride.csv'],
      ['notes.csv'],
      [`${'a'.repeat(120)}.csv`],
    ]);
  });

  it('stops and publishes nothing when the app moves to the background while writing', async () => {
    const opened = sink();
    opened.write.mockImplementation(async () => {
      for (const listener of mocks.listeners) listener('background');
    });
    await expect(exportRecording('ride.csv', [syntheticCsvSample()])).rejects.toThrow('Power Log left the screen');
    expect(opened.abort).toHaveBeenCalled();
    expect(opened.commit).not.toHaveBeenCalled();
    expect(mocks.share).not.toHaveBeenCalled();
  });
});
