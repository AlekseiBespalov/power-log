import { useId, useState } from 'react';
import type { MonitorChartGridProps } from './monitor-chart-grid.types';
import { colors } from './ui';

export function MonitorChartGrid({ items, columns, onMove }: MonitorChartGridProps) {
  const [dragged, setDragged] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const helpId = useId();
  const clear = () => { setDragged(null); setTarget(null); };
  const move = (from: string, to: string) => {
    const index = items.findIndex(item => item.id === to), item = items.find(item => item.id === from);
    if (!item || index < 0 || from === to) return;
    onMove(from, to);
    setAnnouncement(`${item.label} moved to position ${index + 1} of ${items.length}.`);
  };
  return <div data-testid="monitor-chart-grid" style={{ display: 'grid', gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`, gap: 12 }}>
    {items.map((item, index) => <div key={item.id} data-testid={`monitor-card-${item.id}`}
      onDragOver={event => { if (!dragged) return; event.preventDefault(); event.dataTransfer.dropEffect = 'move'; setTarget(item.id); }}
      onDragLeave={event => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setTarget(null); }}
      onDrop={event => { if (!dragged) return; event.preventDefault(); move(dragged, item.id); clear(); }}
      style={{ position: 'relative', minWidth: 0, display: 'grid', outline: target === item.id && dragged !== item.id ? `1px solid ${colors.accent}` : 'none', outlineOffset: -1, borderRadius: 10, opacity: dragged === item.id ? 0.45 : 1 }}>
      <button type="button" draggable aria-label={`Move ${item.label} chart`} aria-describedby={helpId}
        data-testid={`monitor-drag-${item.id}`} title="Drag to move · arrow keys to reorder"
        onDragStart={event => {
          event.dataTransfer.effectAllowed = 'move';
          event.dataTransfer.setData('text/plain', item.id);
          event.dataTransfer.setDragImage(event.currentTarget.parentElement!, 24, 20);
          setDragged(item.id);
        }}
        onDragEnd={clear}
        onKeyDown={event => {
          const offset = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -columns, ArrowDown: columns }[event.key];
          if (offset !== undefined) {
            event.preventDefault(); event.stopPropagation();
            const next = items[index + offset]; if (next) move(item.id, next.id);
          } else if (event.key === 'Escape') clear();
        }}
        style={{ position: 'absolute', top: 4, right: 4, zIndex: 1, width: 36, height: 36, border: 0, borderRadius: 6, background: 'transparent', color: colors.muted, cursor: dragged ? 'grabbing' : 'grab', display: 'grid', placeItems: 'center' }}>
        <svg width="16" height="18" viewBox="0 0 16 18" aria-hidden="true">{[4, 9, 14].flatMap(y => [5, 11].map(x => <circle key={`${x}-${y}`} cx={x} cy={y} r="1.5" fill="currentColor" />))}</svg>
      </button>
      {item.content}
    </div>)}
    <span id={helpId} style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clipPath: 'inset(50%)' }}>Drag a chart handle onto another chart, or focus the handle and use the arrow keys.</span>
    <span role="status" aria-live="polite" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clipPath: 'inset(50%)' }}>{announcement}</span>
  </div>;
}
