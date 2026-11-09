import { Fragment } from 'react';
import type { ChartGridProps } from './chart-grid.types';

export function ChartGrid({ items }: ChartGridProps) {
  return (
    <>
      {items.map(item => (
        <Fragment key={item.id}>{item.content}</Fragment>
      ))}
    </>
  );
}
