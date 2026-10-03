import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { downloadFile, importRecording } from '../../src/services/files.web';
import { MAX_CSV_BYTES } from '../../src/core/validation';
import { syntheticCsvHeader, syntheticCsvRow, syntheticCsvSample } from '../core/csv-fixture';

const csv = '\uFEFF' + syntheticCsvHeader + '\r\n' + syntheticCsvRow(syntheticCsvSample()) + '\r\n';
let input: { files: unknown[]; onchange: (() => void) | null; oncancel: (() => void) | null; click: () => void };
let cancelled = false;
let failure: Error | undefined;
let cancel = vi.fn<() => void>();
let file: { name: string; size: number; stream: ReturnType<typeof vi.fn>; text: ReturnType<typeof vi.fn> };

function stream(contents: string): ReadableStream<Uint8Array<ArrayBuffer>> {
  const bytes = new TextEncoder().encode(contents);
  let offset = 0;
  return new ReadableStream({
    pull(controller) {
      if (failure) {
        controller.error(failure);
        return;
      }
      if (offset === bytes.length) {
        controller.close();
        return;
      }
      const length = offset < 3 ? 1 : 19;
      controller.enqueue(bytes.slice(offset, offset + length));
      offset = Math.min(bytes.length, offset + length);
    },
    cancel() {
      cancel();
    },
  });
}

beforeEach(() => {
  cancelled = false;
  failure = undefined;
  cancel = vi.fn();
  file = {
    name: 'ride.csv',
    size: new TextEncoder().encode(csv).length,
    stream: vi.fn(() => stream(csv)),
    text: vi.fn(async () => csv),
  };
  input = {
    files: [file],
    onchange: null,
    oncancel: null,
    click() {
      if (cancelled) this.oncancel?.();
      else this.onchange?.();
    },
  };
  vi.stubGlobal('document', { createElement: vi.fn(() => input) });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('browser CSV import', () => {
  it('streams bytes through UTF-8 decoding with split BOM and field boundaries', async () => {
    expect(await importRecording()).toEqual({ name: 'ride.csv', recording: { samples: [syntheticCsvSample()] } });
    expect(file.stream).toHaveBeenCalledOnce();
    expect(file.text).not.toHaveBeenCalled();
  });

  it('accepts the 256 MiB chooser boundary and rejects larger metadata before reading', async () => {
    file.size = MAX_CSV_BYTES;
    expect(await importRecording()).toMatchObject({ name: 'ride.csv' });
    file.stream.mockClear();
    file.text.mockClear();
    file.size += 1;
    await expect(importRecording()).rejects.toThrow('Choose a CSV no larger than 256 MiB.');
    expect(file.stream).not.toHaveBeenCalled();
    expect(file.text).not.toHaveBeenCalled();
  });

  it('returns null after cancellation or an empty selection', async () => {
    cancelled = true;
    expect(await importRecording()).toBeNull();
    cancelled = false;
    input.files = [];
    expect(await importRecording()).toBeNull();
    expect(file.stream).not.toHaveBeenCalled();
    expect(file.text).not.toHaveBeenCalled();
  });

  it.each(['\u0000', '\uFEFF', 'é'])('cancels the input stream when parsing fails: %j', async character => {
    file.stream.mockImplementation(() => stream(syntheticCsvHeader + '\n' + character + ' '.repeat(1024)));
    await expect(importRecording()).rejects.toThrow('CSV contains unsupported characters');
    expect(cancel).toHaveBeenCalledOnce();
  });

  it('propagates stream failures and incomplete final rows', async () => {
    failure = new Error('Read failed');
    await expect(importRecording()).rejects.toThrow('Read failed');
    failure = undefined;
    file.stream.mockImplementation(() => stream(syntheticCsvHeader + '\nshort'));
    await expect(importRecording()).rejects.toThrow('CSV row 2 has an unexpected column count');
  });
});

describe('browser downloads', () => {
  it('downloads a Blob URL under its name and releases the URL after the download begins', () => {
    vi.useFakeTimers();
    const anchor = { href: '', download: '', click: vi.fn() };
    vi.stubGlobal('document', { createElement: vi.fn(() => anchor) });
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    downloadFile('blob:ride', 'ride.zip');
    expect(anchor).toMatchObject({ href: 'blob:ride', download: 'ride.zip' });
    expect(anchor.click).toHaveBeenCalledOnce();
    expect(revoke).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1000);
    expect(revoke).toHaveBeenCalledExactlyOnceWith('blob:ride');
  });
});
