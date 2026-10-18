import { describe, expect, it } from 'vitest';
import { HistorySelection } from '../../src/features/history/history-selection';
import { ArchiveActions } from '../../src/services/workout-actions';

describe('History selection generations', () => {
  it('rejects a chooser result after navigating away and back to the same route', () => {
    const selection = new HistorySelection();
    selection.observeRoute('/sessions', null);
    const chooser = selection.token();
    selection.observeRoute('/sessions/ride?id=A', 'A');
    selection.observeRoute('/sessions', null);
    expect(selection.accepts(chooser)).toBe(false);
  });
  it('retains a chooser generation through catalog refresh but invalidates a transient selection', () => {
    const selection = new HistorySelection();
    selection.observeRoute('/sessions', null);
    const chooser = selection.token();
    selection.observeRoute('/sessions', null);
    expect(selection.accepts(chooser)).toBe(true);
    selection.choose(null);
    expect(selection.accepts(chooser)).toBe(false);
  });
  it('admits deletion navigation only while the deleted record is still selected', () => {
    const selection = new HistorySelection();
    selection.observeRoute('/sessions/ride?id=A', 'A');
    expect(selection.selects('A')).toBe(true);
    selection.observeRoute('/sessions/ride?id=B', 'B');
    expect(selection.selects('A')).toBe(false);
    expect(selection.selects('B')).toBe(true);
    selection.observeRoute('/sessions', null);
    expect(selection.selects('B')).toBe(false);
  });
  it('protects the new selection before its router transition commits', () => {
    const selection = new HistorySelection();
    selection.observeRoute('/sessions/ride?id=A', 'A');
    const chooser = selection.token();
    selection.choose('B');
    expect(selection.selects('A')).toBe(false);
    expect(selection.selects('B')).toBe(true);
    expect(selection.accepts(chooser)).toBe(false);
  });
  it('retains A’s archive lock while B runs and until A settles', async () => {
    const actions = new ArchiveActions(() => {});
    let complete!: () => void;
    const first = actions.run(
      'record:A',
      () =>
        new Promise<void>(resolve => {
          complete = resolve;
        }),
    );
    await actions.run('record:B', async () => {});
    expect(actions.snapshot().get('record:A')?.pending).toBe(true);
    let duplicateRuns = 0;
    await actions.run('record:A', async () => {
      duplicateRuns++;
    });
    expect(duplicateRuns).toBe(0);
    complete();
    await first;
    expect(actions.snapshot().has('record:A')).toBe(false);
  });
});
