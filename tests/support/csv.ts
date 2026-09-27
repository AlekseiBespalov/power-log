import { createCsvParser, type ParsedRecording } from '../../src/core/recordings';

export function parseCsv(text: string): ParsedRecording {
  const parser = createCsvParser();
  parser.write(text);
  return parser.finish();
}
