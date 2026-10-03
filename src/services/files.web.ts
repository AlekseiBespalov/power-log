import { createCsvParser, type ParsedRecording } from '../core/recordings';
import { MAX_CSV_BYTES } from '../core/validation';

export async function importRecording(): Promise<{ name: string; recording: ParsedRecording } | null> {
  const file = await chooseCsv();
  if (!file) return null;
  const parser = createCsvParser();
  const reader = file
    .stream()
    .pipeThrough(new TextDecoderStream('utf-8', { ignoreBOM: true }))
    .getReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.write(value);
    }
    return { name: file.name, recording: parser.finish() };
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

function chooseCsv(): Promise<File | null> {
  return new Promise((resolve, reject) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.csv,text/csv';
    input.oncancel = () => resolve(null);
    input.onchange = () => {
      const file = input.files?.[0];
      if (!file) {
        resolve(null);
        return;
      }
      if (file.size > MAX_CSV_BYTES) {
        reject(new Error('Choose a CSV no larger than 256 MiB.'));
        return;
      }
      resolve(file);
    };
    input.click();
  });
}

export function downloadFile(url: string, name: string): void {
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// The browser export sink starts the download when it commits.
export async function shareExport(_uri: string): Promise<void> {}
