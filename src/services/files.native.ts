import * as DocumentPicker from 'expo-document-picker';
import { File, Paths } from 'expo-file-system';
import { Share } from 'react-native';

const safeName = (name: string) => name.replace(/[^a-zA-Z0-9_.-]/g, '-');

export async function exportText(name: string, contents: string) {
  const file = new File(Paths.cache, safeName(name)); file.write(contents);
  await Share.share({ url: file.uri });
}
export async function importText(): Promise<{ name: string; text: string } | null> {
  const result = await DocumentPicker.getDocumentAsync({ type: ['text/csv', 'text/comma-separated-values', 'public.comma-separated-values-text'], copyToCacheDirectory: true });
  if (result.canceled) return null;
  const asset = result.assets[0]; if (!asset) return null;
  const file = new File(asset.uri); if (file.size > 25 * 1024 * 1024) throw new Error('Choose a CSV smaller than 25 MB.');
  return { name: asset.name, text: await file.text() };
}

export async function exportWorkoutFile(uri: string, name: string) {
  const source = new File(uri);
  if (!source.exists) throw new Error('The workout export is missing.');
  const copy = new File(Paths.cache, safeName(name));
  if (copy.exists) copy.delete();
  source.copy(copy);
  await Share.share({ url: copy.uri });
}
