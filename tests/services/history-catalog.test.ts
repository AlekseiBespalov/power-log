import { describe, expect, it, vi } from 'vitest';
import { catalogOrder, catalogLimit, afterCatalogCursor, type CatalogEntry, type CatalogRequest } from '../../src/core/catalog';
import { HistoryCatalog } from '../../src/services/history-catalog';

const row = (i: number, prefix = 'ride'): CatalogEntry => ({ id: `${prefix}-${String(i).padStart(4, '0')}`, startedAt: new Date(Date.UTC(2026, 0, 1) + Math.floor(i / 4) * 1000).toISOString() });
const source = (records: CatalogEntry[]) => vi.fn(async (request: CatalogRequest) => records.filter(record => afterCatalogCursor(record, request)).sort(catalogOrder).slice(0, catalogLimit(request)));
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (error: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }

describe('bounded history catalog pages', () => {
  it('coalesces a burst of revisions behind one active catalog load', async () => {
    const held = deferred<CatalogEntry[]>(), rows = [row(1)], read = source(rows), catalog = new HistoryCatalog([read]);
    read.mockImplementationOnce(() => held.promise);
    const first = catalog.refresh();
    await Promise.resolve();
    const burst = Array.from({ length: 100 }, () => catalog.refresh());
    expect(read).toHaveBeenCalledTimes(1);
    rows.push(row(2)); held.resolve([row(0)]);
    await first; await Promise.all(burst);
    expect(read).toHaveBeenCalledTimes(2);
    expect(catalog.getSnapshot().records).toEqual([...rows].sort(catalogOrder));
  });
  it('retains catalog admission after one source fails until every started sibling settles', async () => {
    const held = deferred<CatalogEntry[]>(), failed = source([]), sibling = source([row(5)]);
    failed.mockRejectedValueOnce(new Error('One catalog is unavailable'));
    sibling.mockImplementationOnce(() => held.promise);
    const catalog = new HistoryCatalog([failed, sibling]);
    const first = catalog.refresh();
    await Promise.resolve(); await Promise.resolve();
    const burst = Array.from({ length: 100 }, () => catalog.refresh());
    for (let i = 0; i < 20; i++) await Promise.resolve();
    expect(failed).toHaveBeenCalledTimes(1); expect(sibling).toHaveBeenCalledTimes(1);
    held.resolve([row(0)]); await first; await Promise.all(burst);
    expect(failed).toHaveBeenCalledTimes(2); expect(sibling).toHaveBeenCalledTimes(2);
    expect(catalog.getSnapshot().records).toEqual([row(5)]);
    expect(catalog.getSnapshot().error).toBeNull();
  });
  it('walks more than 100 entries, including identical timestamps, without skips or duplicates', async () => {
    const rows = Array.from({ length: 237 }, (_, i) => row(i));
    const read = source(rows), catalog = new HistoryCatalog([read]);
    await catalog.refresh();
    expect(catalog.getSnapshot().records).toHaveLength(50);
    while (catalog.getSnapshot().hasMore) await catalog.loadMore();
    expect(catalog.getSnapshot().records).toEqual([...rows].sort(catalogOrder));
    expect(read).toHaveBeenCalledTimes(5);
    expect(read.mock.calls.every(([request]) => request.limit === 51)).toBe(true);
    for (const [request] of read.mock.calls.slice(1)) expect(request).toMatchObject({ beforeStartedAt: expect.any(String), beforeID: expect.any(String) });
  });
  it('merges interleaved native/local sources at a common cursor with duplicate IDs deduplicated', async () => {
    const nativeRows = Array.from({ length: 135 }, (_, i) => row(i * 2));
    const localRows = Array.from({ length: 147 }, (_, i) => row(i * 2 + 1));
    localRows.push(nativeRows[20]!);
    const native = source(nativeRows), local = source(localRows), catalog = new HistoryCatalog([native, local]);
    await catalog.refresh();
    while (catalog.getSnapshot().hasMore) await catalog.loadMore();
    const expected = [...new Map([...nativeRows, ...localRows].map(record => [record.id, record])).values()].sort(catalogOrder);
    expect(catalog.getSnapshot().records).toEqual(expected);
    expect(catalog.getSnapshot().records).toHaveLength(282);
    expect(native.mock.calls).toEqual(local.mock.calls);
    expect(native.mock.calls.every(([request]) => request.limit === 51)).toBe(true);
  });
  it('rejects a stale load-more result after refresh changes the source generation', async () => {
    const rows = Array.from({ length: 130 }, (_, i) => row(i)), pending = deferred<CatalogEntry[]>();
    const read = source(rows), catalog = new HistoryCatalog([read]);
    await catalog.refresh();
    read.mockImplementationOnce(() => pending.promise);
    const old = catalog.loadMore();
    rows.push(row(500)); const replacement = catalog.refresh();
    expect(read).toHaveBeenCalledTimes(2);
    pending.resolve(rows.slice(0, 50)); await old; await replacement;
    expect(catalog.getSnapshot().records).toEqual([...rows].sort(catalogOrder).slice(0, 50));
    expect(catalog.getSnapshot().records[0]!.id).toBe('ride-0500');
    expect(catalog.getSnapshot().loading).toBe(false);
  });
  it('retains a failed page boundary for retry and admits only one load-more at once', async () => {
    const rows = Array.from({ length: 121 }, (_, i) => row(i)), pending = deferred<CatalogEntry[]>();
    const read = source(rows), catalog = new HistoryCatalog([read]);
    await catalog.refresh();
    read.mockImplementationOnce(() => pending.promise);
    const failed = catalog.loadMore(); await catalog.loadMore();
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
    const old = deferred<CatalogEntry[]>(), read = source([row(1)]), catalog = new HistoryCatalog([read]);
    read.mockImplementationOnce(() => old.promise);
    const previous = catalog.refresh(); const replacement = catalog.refresh();
    old.reject(new Error('obsolete failure')); await previous; await replacement;
    expect(catalog.getSnapshot().error).toBeNull();
    const pending = deferred<CatalogEntry[]>(); read.mockImplementationOnce(() => pending.promise);
    const request = catalog.refresh(); catalog.invalidate(); pending.resolve([row(2)]); await request;
    expect(catalog.getSnapshot().records).toEqual([row(1)]);
  });
});

describe('catalog error dismissal', () => {
  it('clears a reported error without reloading and keeps the records', async () => {
    const rows = [row(1)], read = source(rows), catalog = new HistoryCatalog([read]);
    await catalog.refresh();
    read.mockRejectedValueOnce(new Error('Catalog unavailable'));
    await expect(catalog.refresh()).rejects.toThrow('Catalog unavailable');
    expect(catalog.getSnapshot().error).toBe('Catalog unavailable');
    catalog.clearError();
    expect(catalog.getSnapshot()).toMatchObject({ error: null, records: rows });
    expect(read).toHaveBeenCalledTimes(2);
  });
});
