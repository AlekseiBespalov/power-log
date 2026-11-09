import type { ReactNode } from 'react';

export type ChartGridProps = {
  items: { id: string; label: string; content: ReactNode }[];
  columns: number;
  onMove: (from: string, to: string) => void;
};
