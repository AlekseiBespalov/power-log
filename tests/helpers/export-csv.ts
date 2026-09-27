import { csvRow, SAMPLE_COLUMNS, type TelemetrySample } from '../../src/core';

export function exportCsv(samples: readonly TelemetrySample[]): string {
  return [SAMPLE_COLUMNS.join(','), ...samples.map(csvRow)].join('\n') + '\n';
}
