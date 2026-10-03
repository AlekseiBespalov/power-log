import { TABLES } from '../catalog';
import { readProjection } from '../pages';
import { RunTracker } from '../timeline';
import { CELL_COUNT, CELL_ID, csvHeader, layoutColumns, tableLayout, writeRows, type TableInput } from './text';

export const TELEMETRY_LAYOUT = tableLayout(TABLES[0], 'a telemetry reading', {
  activeInterval: CELL_ID,
  run: CELL_COUNT,
  connection: CELL_ID,
});

export async function writeTelemetry(input: TableInput): Promise<void> {
  const { source, session, reads, timeline, out } = input;
  out.line(csvHeader(TELEMETRY_LAYOUT.table));
  const cursor = timeline.cursor('telemetry');
  const runs = new RunTracker(reads.options.platform, 'telemetry');
  for await (const page of readProjection(source, session, 'telemetry', reads)) {
    const assignment = cursor.map(page);
    const run = runs.map(page, assignment);
    const columns = layoutColumns(TELEMETRY_LAYOUT, page.columns, {
      activeInterval: assignment.interval,
      run,
      connection: page.connection,
    });
    await writeRows(out, reads.options.slicer, TELEMETRY_LAYOUT, columns, page.rows);
  }
}
