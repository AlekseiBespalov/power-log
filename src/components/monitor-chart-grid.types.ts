import type { ReactNode } from 'react';

export type MonitorChartGridProps = {
  items: { id: string; label: string; content: ReactNode }[];
  columns: number;
  onMove: (from: string, to: string) => void;
};
