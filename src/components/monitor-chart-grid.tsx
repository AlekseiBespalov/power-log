import { Fragment } from 'react';
import type { MonitorChartGridProps } from './monitor-chart-grid.types';

export function MonitorChartGrid({ items }: MonitorChartGridProps) {
  return <>{items.map(item => <Fragment key={item.id}>{item.content}</Fragment>)}</>;
}
