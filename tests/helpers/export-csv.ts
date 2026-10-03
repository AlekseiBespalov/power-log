import { TELEMETRY_LAYOUT } from '../../src/core/export/tables/telemetry';
import { csvHeader } from '../../src/core/export/tables/text';
import type { TelemetrySample } from '../../src/core';

/** telemetry.csv for live-shaped samples: each interruption opens an interval, and runs follow interruption and connection changes. */
export function exportCsv(samples: readonly TelemetrySample[]): string {
  const connections: string[] = [];
  const lines = [csvHeader(TELEMETRY_LAYOUT.table)];
  let run = 0;
  samples.forEach((sample, index) => {
    const previous = samples[index - 1];
    if (
      !previous ||
      previous.interruptionIndex !== sample.interruptionIndex ||
      previous.connectionEpoch !== sample.connectionEpoch
    )
      run += 1;
    const epoch = sample.connectionEpoch;
    if (epoch !== undefined && !connections.includes(epoch)) connections.push(epoch);
    const computed: Record<string, string> = {
      activeInterval: String(sample.interruptionIndex + 1),
      run: String(run),
      connection: epoch === undefined ? '' : String(connections.indexOf(epoch) + 1),
    };
    lines.push(
      TELEMETRY_LAYOUT.table.fields
        .map((field, c) => {
          const key = TELEMETRY_LAYOUT.reads[c];
          return key === null ? computed[field.name]! : String(sample[key as keyof TelemetrySample] ?? '');
        })
        .join(','),
    );
  });
  return lines.join('\n') + '\n';
}
