import type { CatalogEntry, CatalogRequest } from '../../src/core/catalog';
export function afterCatalogCursor(record: CatalogEntry, request: CatalogRequest) {
  return (
    request.beforeStartedAt === undefined ||
    record.startedAt < request.beforeStartedAt ||
    (record.startedAt === request.beforeStartedAt && record.id < (request.beforeID ?? ''))
  );
}
