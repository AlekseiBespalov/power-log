import { describe, expect, it, vi } from 'vitest';
import {
  catalogOrder,
  catalogLimit,
  type CatalogPage,
  type CatalogEntry,
  type CatalogRequest,
} from '../../src/core/catalog';
import { afterCatalogCursor } from '../support/catalog';
import { HistoryCatalog } from '../../src/services/history-catalog';

const row = (i: number, prefix = 'ride'): CatalogEntry => ({
  id: `${prefix}-${String(i).padStart(4, '0')}`,
  startedAt: new Date(Date.UTC(2026, 0, 1) + Math.floor(i / 4) * 1000).toISOString(),
});
const page = (records: CatalogEntry[]): CatalogPage<CatalogEntry> => ({
  records,
  unreadableCount: 0,
  unindexedCount: 0,
});
const source = (records: CatalogEntry[]) =>
  vi.fn(async (request: CatalogRequest) =>
    page(
      records
        .filter(record => afterCatalogCursor(record, request))
        .sort(catalogOrder)
        .slice(0, catalogLimit(request)),
    ),
  );
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

describe('bounded history catalog pages', () => {
  it('coalesces a burst of revisions behind one active catalog load', async () => {
    const held = deferred<CatalogPage<CatalogEntry>>(),
      rows = [row(1)],
      read = source(rows),
      catalog = new HistoryCatalog(read);
    read.mockImplementationOnce(() => held.promise);
    const first = catalog.refresh();
    await Promise.resolve();
    const burst = Array.from({ length: 100 }, () => catalog.refresh());
    expect(read).toHaveBeenCalledTimes(1);
    rows.push(row(2));
    held.resolve(page([row(0)]));
    await first;
    await Promise.all(burst);
    expect(read).toHaveBeenCalledTimes(2);
    expect(catalog.getSnapshot().records).toEqual([...rows].sort(catalogOrder));
  });
  it('walks more than 100 entries, including identical timestamps, without skips or duplicates', async () => {
    const rows = Array.from({ length: 237 }, (_, i) => row(i));
    const read = source(rows),
      catalog = new HistoryCatalog(read);
    await catalog.refresh();
    expect(catalog.getSnapshot().records).toHaveLength(50);
    while (catalog.getSnapshot().hasMore) await catalog.loadMore();
    expect(catalog.getSnapshot().records).toEqual([...rows].sort(catalogOrder));
    expect(read).toHaveBeenCalledTimes(5);
    expect(read.mock.calls.every(([request]) => request.limit === 51)).toBe(true);
    for (const [request] of read.mock.calls.slice(1))
      expect(request).toMatchObject({ beforeStartedAt: expect.any(String), beforeID: expect.any(String) });
  });
  it('rejects a stale load-more result after refresh changes the source generation', async () => {
    const rows = Array.from({ length: 130 }, (_, i) => row(i)),
      pending = deferred<CatalogPage<CatalogEntry>>();
    const read = source(rows),
      catalog = new HistoryCatalog(read);
    await catalog.refresh();
    read.mockImplementationOnce(() => pending.promise);
    const old = catalog.loadMore();
    rows.push(row(500));
    const replacement = catalog.refresh();
    expect(read).toHaveBeenCalledTimes(2);
    pending.resolve(page(rows.slice(0, 50)));
    await old;
    await replacement;
    expect(catalog.getSnapshot().records).toEqual([...rows].sort(catalogOrder).slice(0, 50));
    expect(catalog.getSnapshot().records[0]!.id).toBe('ride-0500');
    expect(catalog.getSnapshot().loading).toBe(false);
  });
  it('retains a failed page boundary for retry and admits only one load-more at once', async () => {
    const rows = Array.from({ length: 121 }, (_, i) => row(i)),
      pending = deferred<CatalogPage<CatalogEntry>>();
    const read = source(rows),
      catalog = new HistoryCatalog(read);
    await catalog.refresh();
    read.mockImplementationOnce(() => pending.promise);
    const failed = catalog.loadMore();
    await catalog.loadMore();
    expect(read).toHaveBeenCalledTimes(2);
    pending.reject(new Error('Catalog temporarily unavailable'));
    await expect(failed).rejects.toThrow('temporarily unavailable');
    expect(catalog.getSnapshot().records).toHaveLength(50);
    await catalog.loadMore();
    expect(catalog.getSnapshot().records).toHaveLength(100);
    expect(read.mock.calls[2]).toEqual(read.mock.calls[1]);
    expect(catalog.getSnapshot().error).toBeNull();
  });
  it('does not publish stale source errors or stale results after provider cleanup', async () => {
    const old = deferred<CatalogPage<CatalogEntry>>(),
      read = source([row(1)]),
      catalog = new HistoryCatalog(read);
    read.mockImplementationOnce(() => old.promise);
    const previous = catalog.refresh();
    const replacement = catalog.refresh();
    old.reject(new Error('obsolete failure'));
    await previous;
    await replacement;
    expect(catalog.getSnapshot().error).toBeNull();
    const pending = deferred<CatalogPage<CatalogEntry>>();
    read.mockImplementationOnce(() => pending.promise);
    const request = catalog.refresh();
    catalog.invalidate();
    pending.resolve(page([row(2)]));
    await request;
    expect(catalog.getSnapshot().records).toEqual([row(1)]);
  });
});

describe('catalog error dismissal', () => {
  it('clears a reported error without reloading and keeps the records', async () => {
    const rows = [row(1)],
      read = source(rows),
      catalog = new HistoryCatalog(read);
    await catalog.refresh();
    read.mockRejectedValueOnce(new Error('Catalog unavailable'));
    await expect(catalog.refresh()).rejects.toThrow('Catalog unavailable');
    expect(catalog.getSnapshot().error).toBe('Catalog unavailable');
    catalog.clearError();
    expect(catalog.getSnapshot()).toMatchObject({ error: null, records: rows });
    expect(read).toHaveBeenCalledTimes(2);
  });
});

describe('partial catalog results', () => {
  it('keeps healthy rows, reports skipped counts, and resets warnings on refresh', async () => {
    const read = source([row(1)]),
      catalog = new HistoryCatalog(read);
    read.mockResolvedValueOnce({ records: [row(1)], unreadableCount: 7, unindexedCount: 2 });
    await catalog.refresh();
    expect(catalog.getSnapshot()).toMatchObject({
      records: [row(1)],
      unreadableCount: 7,
      unindexedCount: 2,
      error: null,
    });
    await catalog.refresh();
    expect(catalog.getSnapshot()).toMatchObject({ unreadableCount: 0, unindexedCount: 0, error: null });
  });
  it('keeps the last displayed row as the sole cursor and retains refresh index warnings', async () => {
    const records = Array.from({ length: 52 }, (_, i) => row(i)).sort(catalogOrder),
      read = source(records),
      catalog = new HistoryCatalog(read);
    read.mockResolvedValueOnce({ records: records.slice(0, 51), unreadableCount: 4, unindexedCount: 2 });
    await catalog.refresh();
    await catalog.loadMore();
    expect(read.mock.calls[1]![0]).toEqual({
      limit: 51,
      beforeStartedAt: records[49]!.startedAt,
      beforeID: records[49]!.id,
    });
    expect(catalog.getSnapshot()).toMatchObject({ records, hasMore: false, unreadableCount: 0, unindexedCount: 2 });
  });
  it('reports an entirely unreadable page without claiming more healthy records', async () => {
    const read = source([]),
      catalog = new HistoryCatalog(read);
    read.mockResolvedValueOnce({ records: [], unreadableCount: 75, unindexedCount: 1 });
    await catalog.refresh();
    expect(catalog.getSnapshot()).toMatchObject({
      records: [],
      initialized: true,
      hasMore: false,
      unreadableCount: 75,
      unindexedCount: 1,
      error: null,
    });
  });
});
