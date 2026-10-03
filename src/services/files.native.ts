import * as DocumentPicker from 'expo-document-picker';
import { File, FileMode } from 'expo-file-system';
import bridge from '../../modules/cyc-bridge';
import { Platform, Share } from 'react-native';
import { createCsvParser, type ParsedRecording } from '../core/recordings';
import { MAX_CSV_BYTES } from '../core/validation';

export async function importRecording(): Promise<{ name: string; recording: ParsedRecording } | null> {
  const selected = await chooseCsv();
  if (!selected) return null;
  const parser = createCsvParser();
  const handle = selected.file.open(FileMode.ReadOnly);
  try {
    let bom = 0;
    for (;;) {
      const bytes = handle.readBytes(64 * 1024);
      if (bytes.length === 0) break;
      let start = 0;
      while (bom < 3 && start < bytes.length && bytes[start] === UTF8_BOM[bom]) {
        bom++;
        start++;
      }
      if (bom < 3 && start < bytes.length) {
        parser.write(byteText(UTF8_BOM.subarray(0, bom)));
        bom = 3;
      }
      parser.write(byteText(bytes.subarray(start)));
      await new Promise<void>(resolve => setTimeout(resolve, 0));
    }
    if (bom > 0 && bom < 3) parser.write(byteText(UTF8_BOM.subarray(0, bom)));
    return { name: selected.name, recording: parser.finish() };
  } finally {
    handle.close();
  }
}

const UTF8_BOM = Uint8Array.of(0xef, 0xbb, 0xbf);

// Supported CSV is ASCII, so each byte maps to one character; any other byte
// becomes a non-ASCII character that the parser rejects.
function byteText(bytes: Uint8Array): string {
  let text = '';
  for (let offset = 0; offset < bytes.length; offset += 8192)
    text += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
  return text;
}

async function chooseCsv(): Promise<{ name: string; file: File } | null> {
  const result = await DocumentPicker.getDocumentAsync({
    type: ['text/csv', 'text/comma-separated-values', 'public.comma-separated-values-text'],
    copyToCacheDirectory: true,
  });
  if (result.canceled) return null;
  const asset = result.assets[0];
  if (!asset) return null;
  const file = new File(asset.uri);
  if (file.size > MAX_CSV_BYTES) throw new Error('Choose a CSV no larger than 256 MiB.');
  return { name: asset.name, file };
}

export async function shareExport(uri: string): Promise<void> {
  if (Platform.OS === 'android' && bridge?.shareFile) await bridge.shareFile(uri);
  else await Share.share({ url: uri });
}
