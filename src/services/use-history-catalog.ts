import { useEffect, useState } from 'react';
import type { CatalogEntry } from '../core/catalog';
import { HistoryCatalog, type CatalogLoader } from './history-catalog';

/** The source function is fixed for the lifetime of the provider. */
export function useHistoryCatalog<T extends CatalogEntry>(source: CatalogLoader<T>) {
  const [catalog] = useState(() => new HistoryCatalog(source, false));
  const [state, setState] = useState(() => catalog.getSnapshot());
  useEffect(() => {
    const unsubscribe = catalog.subscribe(setState);
    return () => {
      unsubscribe();
      catalog.invalidate();
    };
  }, [catalog]);
  return {
    ...state,
    refresh: catalog.refresh,
    loadMore: catalog.loadMore,
    clearError: catalog.clearError,
    setActive: catalog.setActive,
  };
}
