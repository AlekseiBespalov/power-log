import { csvRow, SAMPLE_COLUMNS, validateSamples, type TelemetrySample } from '../../src/core';

export function exportCsv(samples: readonly TelemetrySample[]): string {
  return [SAMPLE_COLUMNS.join(','), ...validateSamples(samples, true).map(csvRow)].join('\n') + '\n';
}
