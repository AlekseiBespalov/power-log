import { beforeEach, describe, expect, it, vi } from 'vitest';
import { exportText, exportWorkoutFile } from '../../src/services/files.native';

const mocks = vi.hoisted(() => ({
  platform: 'ios',
  files: new Map<string, string | Uint8Array>(),
  copyFails: false,
  copyBarrier: Promise.resolve(),
  share: vi.fn(),
  androidShare: vi.fn(),
}));
vi.mock('react-native', () => ({ Platform: { get OS() { return mocks.platform; } }, Share: { share: mocks.share } }));
vi.mock('expo-document-picker', () => ({ getDocumentAsync: vi.fn() }));
vi.mock('expo-file-system', () => ({
  Paths: { cache: 'file:///cache' },
  File: class {
    uri: string;
    constructor(...parts: string[]) { this.uri = parts.join('/'); }
    get exists() { return mocks.files.has(this.uri); }
    write(value: string) { mocks.files.set(this.uri, value); }
    delete() { mocks.files.delete(this.uri); }
    async copy(destination: { uri: string }) {
      await mocks.copyBarrier;
      if (mocks.copyFails) throw new Error('Copy failed');
      if (mocks.files.has(destination.uri)) throw new Error('Destination already exists');
      mocks.files.set(destination.uri, mocks.files.get(this.uri)!);
    }
  },
}));

beforeEach(() => {
  mocks.files.clear(); mocks.copyFails = false; mocks.copyBarrier = Promise.resolve(); mocks.platform = 'ios';
  mocks.androidShare.mockReset().mockResolvedValue(undefined);
  mocks.share.mockReset().mockResolvedValue({ action: 'sharedAction' });
});

describe('native file export', () => {
  it.each(['ios', 'android'])('waits for the asynchronous file copy before sharing on %s', async platform => {
    mocks.platform = platform;
    let finish!: () => void;
    mocks.copyBarrier = new Promise<void>(resolve => { finish = resolve; });
    mocks.files.set('file:///saved.fit', new Uint8Array([1, 2, 3]));
    const exported = exportWorkoutFile('file:///saved.fit', 'ride.fit');
    await Promise.resolve();
    expect(mocks.share).not.toHaveBeenCalled();
    expect(mocks.androidShare).not.toHaveBeenCalled();
    expect(mocks.files.has('file:///cache/ride.fit')).toBe(false);
    finish(); await exported;
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

  it('does not open the share sheet when the source is missing or the copy fails', async () => {
    await expect(exportWorkoutFile('file:///missing.fit', 'ride.fit')).rejects.toThrow('missing');
    mocks.files.set('file:///saved.fit', new Uint8Array([1])); mocks.copyFails = true;
    await expect(exportWorkoutFile('file:///saved.fit', 'ride.fit')).rejects.toThrow('Copy failed');
    expect(mocks.share).not.toHaveBeenCalled();
  });

});
vi.mock('../../modules/cyc-bridge', () => ({ default: { shareFile: mocks.androidShare } }));
