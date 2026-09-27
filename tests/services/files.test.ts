import { beforeEach, describe, expect, it, vi } from 'vitest';
import { exportText, exportWorkoutFile, importRecording } from '../../src/services/files.native';
import { MAX_CSV_BYTES } from '../../src/core/validation';
import { SAMPLE_COLUMNS } from '../../src/core/types';
import { csvRow } from '../../src/core/recordings';
import { syntheticCsvSample } from '../core/csv-fixture';

const mocks = vi.hoisted(() => ({
  platform: 'ios',
  files: new Map<string, string | Uint8Array>(),
  copyFails: false,
  copyBarrier: Promise.resolve(),
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
    write(value: string) {
      mocks.files.set(this.uri, value);
    }
    delete() {
      mocks.files.delete(this.uri);
    }
    async copy(destination: { uri: string }) {
      await mocks.copyBarrier;
      if (mocks.copyFails) throw new Error('Copy failed');
      if (mocks.files.has(destination.uri)) throw new Error('Destination already exists');
      mocks.files.set(destination.uri, mocks.files.get(this.uri)!);
    }
  },
}));

beforeEach(() => {
  vi.resetAllMocks();
  mocks.files.clear();
  mocks.copyFails = false;
  mocks.copyBarrier = Promise.resolve();
  mocks.platform = 'ios';
  mocks.reportedSize = null;
  mocks.chunkSizes = [];
  mocks.androidShare.mockReset().mockResolvedValue(undefined);
  mocks.share.mockReset().mockResolvedValue({ action: 'sharedAction' });
});

describe('native CSV import', () => {
  const csv = '\uFEFF' + SAMPLE_COLUMNS.join(',') + '\r\n' + csvRow(syntheticCsvSample()) + '\r\n';
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

  it.each([SAMPLE_COLUMNS.join(',') + '\nshort', csv + '\u0000', csv + '\uFEFF', csv + '"unfinished'])(
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
  it.each(['ios', 'android'])('waits for the asynchronous file copy before sharing on %s', async platform => {
    mocks.platform = platform;
    let finish!: () => void;
    mocks.copyBarrier = new Promise<void>(resolve => {
      finish = resolve;
    });
    mocks.files.set('file:///saved.fit', new Uint8Array([1, 2, 3]));
    const exported = exportWorkoutFile('file:///saved.fit', 'ride.fit');
    await Promise.resolve();
    expect(mocks.share).not.toHaveBeenCalled();
    expect(mocks.androidShare).not.toHaveBeenCalled();
    expect(mocks.files.has('file:///cache/ride.fit')).toBe(false);
    finish();
    await exported;
    expect(platform === 'android' ? mocks.androidShare : mocks.share).toHaveBeenCalledOnce();
  });
  it('shares Android exports through the native content-URI provider', async () => {
    mocks.platform = 'android';
    const bytes = new Uint8Array([0, 255, 128]);
    mocks.files.set('file:///saved.fit', bytes);
    await exportWorkoutFile('file:///saved.fit', 'ride.fit');
    expect(mocks.androidShare).toHaveBeenCalledWith('file:///cache/ride.fit');
    expect(mocks.share).not.toHaveBeenCalled();
    expect(mocks.files.get('file:///cache/ride.fit')).toEqual(bytes);
  });

  it.each(['fit', 'zip'])('shares an intact %s cache copy through the iOS system sheet', async extension => {
    const source = `file:///private/workout.${extension}`;
    const bytes = new Uint8Array([0, 255, 128, 10]);
    mocks.files.set(source, bytes);
    await exportWorkoutFile(source, `ride.${extension}`);
    expect(mocks.share).toHaveBeenCalledWith({ url: `file:///cache/ride.${extension}` });
    expect(mocks.files.get(source)).toEqual(bytes);
    expect(mocks.files.get(`file:///cache/ride.${extension}`)).toEqual(bytes);
  });

  it('accepts cancelling the iOS sheet and shares CSV as a file rather than message text', async () => {
    mocks.share.mockResolvedValue({ action: 'dismissedAction' });
    await expect(exportText('My ride.csv', 'time,power\n0,0')).resolves.toBeUndefined();
    expect(mocks.share).toHaveBeenCalledWith({ url: 'file:///cache/My-ride.csv' });
  });

  it('shares CSV through the Android content-URI provider', async () => {
    mocks.platform = 'android';
    await exportText('My ride.csv', 'time,power\n0,10');
    expect(mocks.files.get('file:///cache/My-ride.csv')).toBe('time,power\n0,10');
    expect(mocks.androidShare).toHaveBeenCalledWith('file:///cache/My-ride.csv');
    expect(mocks.share).not.toHaveBeenCalled();
  });

  it('does not open the share sheet when the source is missing or the copy fails', async () => {
    await expect(exportWorkoutFile('file:///missing.fit', 'ride.fit')).rejects.toThrow('missing');
    mocks.files.set('file:///saved.fit', new Uint8Array([1]));
    mocks.copyFails = true;
    await expect(exportWorkoutFile('file:///saved.fit', 'ride.fit')).rejects.toThrow('Copy failed');
    expect(mocks.share).not.toHaveBeenCalled();
  });
});
vi.mock('../../modules/cyc-bridge', () => ({ default: { shareFile: mocks.androidShare } }));
