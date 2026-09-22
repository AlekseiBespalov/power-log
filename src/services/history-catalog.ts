import { CATALOG_PAGE_SIZE, catalogOrder, type CatalogCursor, type CatalogEntry, type CatalogRequest } from '../core/catalog';
import { ReadCancelled, readConsumer, readPressure, reads } from './read-scheduler';

export type CatalogLoader<T extends CatalogEntry> = (request: CatalogRequest) => Promise<T[]>;
export type HistoryCatalogState<T> = { records: T[]; loading: boolean; hasMore: boolean; initialized: boolean; error: string | null };

export class HistoryCatalog<T extends CatalogEntry> {
  private generation = 0;
  private readonly consumer = readConsumer('catalog');
  private retryTimer?: ReturnType<typeof setTimeout>;
  private cursor?: CatalogCursor;
  private state: HistoryCatalogState<T> = { records: [], loading: false, hasMore: false, initialized: false, error: null };
  private listeners = new Set<(state: HistoryCatalogState<T>) => void>();
  private dirty = true;
  constructor(private readonly sources: readonly CatalogLoader<T>[], private active = true) {}
  getSnapshot() { return this.state; }
  subscribe(listener: (state: HistoryCatalogState<T>) => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; }
  invalidate() { this.generation++; reads.cancel(this.consumer); if (this.retryTimer) clearTimeout(this.retryTimer); this.retryTimer = undefined; this.state = { ...this.state, loading: false }; }
  private emit(patch: Partial<HistoryCatalogState<T>>) { this.state = { ...this.state, ...patch }; for (const listener of this.listeners) listener(this.state); }
  setActive = (active: boolean) => {
    if (this.active === active) return;
    this.active = active;
    if (!active) { if (this.state.loading) this.dirty = true; this.invalidate(); }
    else if (this.dirty) void this.refresh().catch(() => {});
  };
  refresh = async () => { this.dirty = true; if (this.active) await this.load(true); };
  loadMore = async () => { if (this.active && this.state.hasMore && !this.state.loading) await this.load(false); };
  clearError = () => { if (this.state.error !== null) this.emit({ error: null }); };
  private async load(reset: boolean) {
    if (this.retryTimer) clearTimeout(this.retryTimer); this.retryTimer = undefined;
    const generation = ++this.generation;
    const request = { limit: CATALOG_PAGE_SIZE + 1, ...(reset ? {} : this.cursor) };
    this.emit({ loading: true });
    try {
      const pages = await reads.schedule('catalog', this.consumer, 'page', async () => {
        // A failing source must not release admission while its sibling still runs.
        const results = await Promise.allSettled(this.sources.map(async source => source(request)));
        const failure = results.find(result => result.status === 'rejected');
        if (failure?.status === 'rejected') throw failure.reason;
        return results.flatMap(result => result.status === 'fulfilled' ? [result.value] : []);
      });
      if (generation !== this.generation) return;
      // A source's duplicate representation cannot consume another source's page slot.
      const merged = [...new Map(pages.flat().map(record => [record.id, record])).values()].sort(catalogOrder);
      const page = merged.slice(0, CATALOG_PAGE_SIZE), tail = page[page.length - 1];
      if (tail) this.cursor = { beforeStartedAt: tail.startedAt, beforeID: tail.id };
      else if (reset) this.cursor = undefined;
      const records = reset ? page : [...new Map([...this.state.records, ...page].map(record => [record.id, record])).values()].sort(catalogOrder);
      this.dirty = false;
      this.emit({ records, initialized: true, hasMore: merged.length > CATALOG_PAGE_SIZE, loading: false, error: null });
    } catch (cause) {
      if (generation !== this.generation || cause instanceof ReadCancelled) return;
      if (readPressure(cause)) {
        this.retryTimer = setTimeout(() => { this.retryTimer = undefined; if (generation === this.generation) void this.load(reset).catch(() => {}); }, 1000);
        return;
      }
      this.emit({ error: cause instanceof Error ? cause.message : String(cause), loading: false });
      throw cause;
    }
  }
}
