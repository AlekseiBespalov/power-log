import { TABLES } from '../catalog';
import { readProjection } from '../pages';
import { RunTracker } from '../timeline';
import type { ProducerSet } from '../types';
import {
  CELL_COUNT,
  CELL_ID,
  checkProducer,
  csvHeader,
  layoutColumns,
  tableLayout,
  writeRows,
  type TableInput,
} from './text';

const LAYOUT = tableLayout(TABLES[1], 'a GPS fix', { activeInterval: CELL_ID, run: CELL_COUNT });

export async function writeGps(input: TableInput, producers: ProducerSet): Promise<void> {
  const { source, session, reads, timeline, out } = input;
  out.line(csvHeader(LAYOUT.table));
  const cursor = timeline.cursor('gps');
  const runs = new RunTracker(reads.options.platform, 'gps');
  for await (const page of readProjection(source, session, 'gps', reads)) {
    const sources = page.columns.producer;
    for (let i = 0; i < page.rows; i++) checkProducer(LAYOUT.subject, sources?.[i], producers);
    const assignment = cursor.map(page);
    const run = runs.map(page, assignment);
    const columns = layoutColumns(LAYOUT, page.columns, { activeInterval: assignment.interval, run });
    await writeRows(out, reads.options.slicer, LAYOUT, columns, page.rows);
  }
}
