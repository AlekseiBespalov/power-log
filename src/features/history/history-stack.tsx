import { createContext, useContext, type ReactNode } from 'react';
import { useScreenModel } from '../../components/use-screen-model';
import { DeleteRideSheet } from './delete-ride-sheet';
import { HistoryListModel, type HistoryListState } from './history-list-model';

const HistoryListContext = createContext<readonly [HistoryListState, HistoryListModel] | null>(null);

export function HistoryStack({ children }: { children: ReactNode }) {
  const list = useScreenModel(env => new HistoryListModel(env), []);
  return (
    <HistoryListContext.Provider value={list}>
      {children}
      <DeleteRideSheet state={list[0]} model={list[1]} />
    </HistoryListContext.Provider>
  );
}

export function useHistoryList(): readonly [HistoryListState, HistoryListModel] {
  const list = useContext(HistoryListContext);
  if (!list) throw new Error('HistoryStack is missing.');
  return list;
}
