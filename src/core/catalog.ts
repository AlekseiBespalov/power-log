/** Stable keyset order shared by SQLite, IndexedDB and the merged history list. */
export type CatalogEntry = { id: string; startedAt: string };
export type CatalogCursor = { beforeStartedAt: string; beforeID: string };
export type CatalogRequest = Partial<CatalogCursor> & { limit?: number };
export const CATALOG_PAGE_SIZE = 50;
export function catalogLimit(request: CatalogRequest = {}) { return Math.max(1, Math.min(100, Math.floor(Number.isFinite(request.limit) ? request.limit! : 100))); }
export function catalogOrder(a: CatalogEntry, b: CatalogEntry) {
  if (a.startedAt !== b.startedAt) return a.startedAt > b.startedAt ? -1 : 1;
  return a.id === b.id ? 0 : a.id > b.id ? -1 : 1;
}
export function afterCatalogCursor(record: CatalogEntry, request: CatalogRequest) {
  return request.beforeStartedAt === undefined || record.startedAt < request.beforeStartedAt || (record.startedAt === request.beforeStartedAt && record.id < (request.beforeID ?? ''));
}
