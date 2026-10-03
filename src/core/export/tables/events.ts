import { TABLES } from '../catalog';
import { readProjection } from '../pages';
import type { ExportPlatform } from '../types';
import {
  ROW_BATCH,
  checkProducer,
  csvBoolean,
  csvHeader,
  csvNumber,
  csvText,
  tableError,
  type TableInput,
  type Texts,
} from './text';

interface EventAction {
  readonly action: 'start' | 'pause' | 'resume' | 'lap' | 'stop';
  readonly interrupted: boolean;
}

const TABLE = TABLES[3];
const SUBJECT = 'a ride event';
const SOURCES: Record<ExportPlatform, readonly string[]> = {
  ios: ['phone', 'watch'],
  android: ['phone'],
  web: ['browser'],
};
const plain = (action: EventAction['action']): [string, EventAction] => [action, { action, interrupted: false }];
const COMMON = [plain('start'), plain('pause'), plain('resume'), plain('lap')];
const ACTIONS: Record<ExportPlatform, ReadonlyMap<string, EventAction>> = {
  ios: new Map([...COMMON, plain('stop'), ['interruption', { action: 'pause', interrupted: true }]]),
  android: new Map([...COMMON, plain('stop')]),
  web: new Map([
    ...COMMON,
    ['save', { action: 'stop', interrupted: false }],
    ['interrupted', { action: 'stop', interrupted: true }],
  ]),
};

export async function writeEvents(input: Omit<TableInput, 'timeline'>): Promise<void> {
  const { source, session, reads, out } = input;
  const { platform, slicer } = reads.options;
  const known = ACTIONS[platform];
  out.line(csvHeader(TABLE));
  for await (const page of readProjection(source, session, 'lifecycle', reads)) {
    const columns = page.columns;
    const elapsed = columns.elapsedSeconds;
    const timestamps: Texts | undefined = columns.timestamp;
    const timers = columns.timerSeconds;
    const actions: Texts | undefined = columns.action;
    const flags = columns.interrupted;
    const sources: Texts | undefined = columns.producer;
    for (let i = 0; i < page.rows; i++) {
      const stored = actions?.[i] ?? null;
      const mapped = stored === null ? undefined : known.get(stored);
      if (!mapped) throw tableError(SUBJECT, `has the action ${stored ?? 'none'}, which this export does not know`);
      const interrupted = mapped.interrupted || (flags !== undefined && flags[i] === 1);
      const producer = checkProducer(SUBJECT, sources?.[i], SOURCES[platform]);
      const time = csvNumber(elapsed ? elapsed[i]! : NaN, SUBJECT, 'elapsedSeconds');
      const timer = csvNumber(timers ? timers[i]! : NaN, SUBJECT, 'timerSeconds');
      out.line(`${csvText(timestamps?.[i])},${time},${timer},${mapped.action},${csvBoolean(interrupted)},${producer}`);
      if (i % ROW_BATCH === ROW_BATCH - 1) {
        if (out.due) await out.drain();
        await slicer.tick();
      }
    }
    if (out.due) await out.drain();
  }
}
