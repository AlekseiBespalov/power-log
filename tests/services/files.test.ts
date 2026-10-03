import { beforeEach, describe, expect, it, vi } from 'vitest';
import { importRecording, shareExport } from '../../src/services/files.native';
import { MAX_CSV_BYTES } from '../../src/core/validation';
import { syntheticCsvHeader, syntheticCsvRow, syntheticCsvSample } from '../core/csv-fixture';

const mocks = vi.hoisted(() => ({
  platform: 'ios',
  files: new Map<string, string | Uint8Array>(),
  share: vi.fn(),
  androidShare: vi.fn(),
  pick: vi.fn(),
  open: vi.fn(),
  read: vi.fn(),
  close: vi.fn(),
  text: vi.fn(),
  reportedSize: null as number | null,
  chunkSizes: [] as number[],
}));
vi.mock('react-native', () => ({
  Platform: {
    get OS() {
      return mocks.platform;
    },
  },
  Share: { share: mocks.share },
}));
vi.mock('expo-document-picker', () => ({ getDocumentAsync: mocks.pick }));
vi.mock('expo-file-system', () => ({
  Paths: { cache: 'file:///cache' },
  FileMode: { ReadOnly: 'r' },
  File: class {
    uri: string;
    constructor(...parts: string[]) {
      this.uri = parts.join('/');
    }
    get exists() {
      return mocks.files.has(this.uri);
    }
    get size() {
      return mocks.reportedSize ?? this.bytes().length;
    }
    bytes() {
      const value = mocks.files.get(this.uri)!;
      return typeof value === 'string' ? new TextEncoder().encode(value) : value;
    }
    async text() {
      mocks.text();
      return new TextDecoder().decode(this.bytes());
    }
    open(mode: string) {
      mocks.open(mode);
      const bytes = this.bytes();
      let offset = 0;
      return {
        readBytes(length: number) {
          mocks.read(length);
          const result = bytes.slice(offset, offset + Math.min(length, mocks.chunkSizes.shift() ?? length));
          offset += result.length;
          return result;
        },
        close: mocks.close,
      };
    }
  },
}));

beforeEach(() => {
  vi.resetAllMocks();
  mocks.files.clear();
  mocks.platform = 'ios';
  mocks.reportedSize = null;
  mocks.chunkSizes = [];
  mocks.androidShare.mockReset().mockResolvedValue(undefined);
  mocks.share.mockReset().mockResolvedValue({ action: 'sharedAction' });
});

describe('native CSV import', () => {
  const csv = '\uFEFF' + syntheticCsvHeader + '\r\n' + syntheticCsvRow(syntheticCsvSample()) + '\r\n';
  beforeEach(() => {
    mocks.files.set('file:///selected.csv', csv);
    mocks.pick.mockResolvedValue({ canceled: false, assets: [{ uri: 'file:///selected.csv', name: 'ride.csv' }] });
  });

  it('reads bounded slices through a read-only handle and decodes split BOM bytes', async () => {
    mocks.chunkSizes = [1, 1, 1, 13, 31, 29];
    expect(await importRecording()).toEqual({ name: 'ride.csv', recording: { samples: [syntheticCsvSample()] } });
    expect(mocks.open).toHaveBeenCalledExactlyOnceWith('r');
    expect(mocks.read.mock.calls.length).toBeGreaterThan(6);
    expect(mocks.read.mock.calls.every(([length]) => length <= 64 * 1024)).toBe(true);
    expect(mocks.text).not.toHaveBeenCalled();
    expect(mocks.close).toHaveBeenCalledOnce();
  });

  it('accepts the 256 MiB chooser boundary and rejects larger metadata before reading', async () => {
    mocks.reportedSize = MAX_CSV_BYTES;
    expect(await importRecording()).toMatchObject({ name: 'ride.csv' });
    mocks.open.mockClear();
    mocks.text.mockClear();
    mocks.reportedSize += 1;
    await expect(importRecording()).rejects.toThrow('Choose a CSV no larger than 256 MiB.');
    expect(mocks.open).not.toHaveBeenCalled();
    expect(mocks.text).not.toHaveBeenCalled();
  });

  it('returns null after cancellation or an empty selection', async () => {
    mocks.pick.mockResolvedValue({ canceled: true });
    expect(await importRecording()).toBeNull();
    mocks.pick.mockResolvedValue({ canceled: false, assets: [] });
    expect(await importRecording()).toBeNull();
    expect(mocks.open).not.toHaveBeenCalled();
    expect(mocks.text).not.toHaveBeenCalled();
  });

  it.each([syntheticCsvHeader + '\nshort', csv + '\u0000', csv + '\uFEFF', csv + '"unfinished'])(
    'closes the handle after parsing fails: %#',
    async contents => {
      mocks.files.set('file:///selected.csv', contents);
      await expect(importRecording()).rejects.toThrow();
      expect(mocks.close).toHaveBeenCalledOnce();
    },
  );

  it('closes the handle after a read fails', async () => {
    mocks.read.mockImplementation(() => {
      throw new Error('Read failed');
    });
    await expect(importRecording()).rejects.toThrow('Read failed');
    expect(mocks.close).toHaveBeenCalledOnce();
  });
});

describe('native file export', () => {
  it.each([
    ['ios', 'file:///app/Exports/0f0e/power-log-ride.fit'],
    ['android', 'content://app.powerlog.powerlog.files/ride-exports/0f0e/power-log-ride.fit'],
  ])('shares a committed %s export in place', async (platform, uri) => {
    mocks.platform = platform;
    await shareExport(uri);
    if (platform === 'android') expect(mocks.androidShare).toHaveBeenCalledExactlyOnceWith(uri);
    else expect(mocks.share).toHaveBeenCalledExactlyOnceWith({ url: uri });
    expect(mocks.files.size).toBe(0);
  });

  it('accepts cancelling the iOS sheet', async () => {
    mocks.share.mockResolvedValue({ action: 'dismissedAction' });
    await expect(shareExport('file:///app/Exports/0f0e/ride.csv')).resolves.toBeUndefined();
  });
});
vi.mock('../../modules/cyc-bridge', () => ({ default: { shareFile: mocks.androidShare } }));
