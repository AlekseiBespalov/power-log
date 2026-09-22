import { useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { AccessibilityInfo, Animated, PanResponder, Platform, Pressable, ScrollView, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import { colors, Label } from '../../components/ui';
import { metricById } from '../../core/monitor';
import { reorderMonitorMetric } from './monitor-editor-model';
import { haptics } from '../../services/haptics';

type Props = { selected: string[]; kind: 'numbers' | 'graphs'; showSelected: boolean; onChange: (ids: string[]) => void; header?: ReactNode; children: ReactNode };
type Drag = { id: string; original: string[]; index: number; target: number; startScroll: number; dy: number; fingerY: number; update: () => void };
const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

export function SortableMetrics({ selected, kind, showSelected, onChange, header, children }: Props) {
  const { fontScale } = useWindowDimensions();
  const rowHeight = Math.max(52, Math.ceil(32 * fontScale + 16));
  const scroll = useRef<ScrollView>(null);
  const viewport = useRef({ top: 0, height: 0, content: 0, offset: 0 });
  const drag = useRef<Drag | null>(null);
  const frame = useRef<number | null>(null);
  const [lifted, setLifted] = useState<{ id: string; target: number } | null>(null);
  useEffect(() => () => { if (frame.current !== null) cancelAnimationFrame(frame.current); }, []);

  const measure = (ready?: () => void) => scroll.current?.getNativeScrollRef()?.measureInWindow((_x, top, _width, height) => {
    viewport.current.top = top; viewport.current.height = height;
    ready?.();
  });
  const begin = (id: string, fingerY: number, position: Animated.Value) => {
    if (drag.current || selected.length < 2) return;
    const index = selected.indexOf(id);
    if (index < 0) return;
    haptics.selection();
    const current: Drag = { id, original: [...selected], index, target: index, startScroll: viewport.current.offset, dy: 0, fingerY, update: () => {} };
    current.update = () => {
      const y = clamp(index * rowHeight + current.dy + viewport.current.offset - current.startScroll, 0, (current.original.length - 1) * rowHeight);
      position.setValue(y);
      const target = Math.round(y / rowHeight);
      if (target !== current.target) { current.target = target; setLifted({ id, target }); }
    };
    drag.current = current;
    position.stopAnimation();
    position.setValue(index * rowHeight);
    setLifted({ id, target: index });
    // Keyboard and window changes can move the scroll viewport after its last layout.
    let measured = false;
    measure(() => { measured = true; });
    let lastTime: number | null = null;
    const tick = (time: number) => {
      if (drag.current !== current) return;
      const elapsed = lastTime === null ? 0 : Math.min(32, time - lastTime); lastTime = time;
      const view = viewport.current, edge = Math.min(64, view.height / 4);
      const distanceTop = current.fingerY - view.top, distanceBottom = view.top + view.height - current.fingerY;
      const velocity = !measured || Math.abs(current.dy) < 4 ? 0 : distanceTop < edge ? -clamp(1 - distanceTop / edge, 0, 1) : distanceBottom < edge ? clamp(1 - distanceBottom / edge, 0, 1) : 0;
      const offset = clamp(view.offset + velocity * elapsed * 0.45, 0, Math.max(0, view.content - view.height));
      if (view.height > 0 && offset !== view.offset) {
        view.offset = offset;
        scroll.current?.scrollTo({ y: offset, animated: false });
        current.update();
      }
      frame.current = requestAnimationFrame(tick);
    };
    frame.current = requestAnimationFrame(tick);
  };
  const move = (dy: number, fingerY: number) => {
    const current = drag.current; if (!current) return;
    current.dy = dy; current.fingerY = fingerY; current.update();
  };
  const finish = (commit: boolean) => {
    const current = drag.current; if (!current) return;
    if (commit) haptics.selection();
    if (frame.current !== null) cancelAnimationFrame(frame.current);
    frame.current = null; drag.current = null; setLifted(null);
    // A reset or external selection change must not be overwritten by a stale gesture.
    if (commit && current.original.join('|') === selected.join('|') && current.target !== current.index) {
      onChange(reorderMonitorMetric(selected, current.id, current.target));
      AccessibilityInfo.announceForAccessibility(`${metricById(current.id)?.label}, position ${current.target + 1} of ${selected.length}`);
    }
  };
  const reorder = (id: string, target: number) => {
    if (drag.current || target < 0 || target >= selected.length) return;
    onChange(reorderMonitorMetric(selected, id, target));
    AccessibilityInfo.announceForAccessibility(`${metricById(id)?.label}, position ${target + 1} of ${selected.length}`);
  };
  const preview = lifted ? reorderMonitorMetric(selected, lifted.id, lifted.target) : selected;
  return <ScrollView ref={scroll} testID="monitor-editor-scroll" scrollEnabled={!lifted}
    onLayout={() => measure()} onContentSizeChange={(_width, height) => { viewport.current.content = height; }}
    onScroll={event => { viewport.current.offset = event.nativeEvent.contentOffset.y; drag.current?.update(); }} scrollEventThrottle={16}
    keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag" showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false}
    contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: 12 }}>
    {header}
    {showSelected && <View style={{ marginBottom: 18 }}>
      <View style={styles.selectedHeading}><Label>Selected · {selected.length}</Label>{selected.length > 1 && <Text style={styles.hint}>Drag to reorder</Text>}</View>
      {!selected.length && <Text style={{ color: colors.muted, paddingVertical: 14 }}>No {kind} selected</Text>}
      <View testID="monitor-selected-metrics" style={{ height: selected.length * rowHeight }}>
        {selected.map(id => <SortableMetric key={id} id={id} kind={kind} index={preview.indexOf(id)} count={selected.length} rowHeight={rowHeight}
          lifted={lifted?.id === id} busy={lifted !== null} begin={begin} move={move} finish={finish}
          reorder={target => reorder(id, target)} remove={() => onChange(selected.filter(value => value !== id))} />)}
      </View>
    </View>}
    {children}
  </ScrollView>;
}

type RowProps = {
  id: string; kind: string; index: number; count: number; rowHeight: number; lifted: boolean; busy: boolean;
  begin: (id: string, y: number, position: Animated.Value) => void; move: (dy: number, y: number) => void; finish: (commit: boolean) => void;
  reorder: (target: number) => void; remove: () => void;
};
function SortableMetric(props: RowProps) {
  const { id, kind, index, count, rowHeight, lifted, busy, reorder, remove } = props;
  const metric = metricById(id)!;
  const latest = useRef(props);
  useLayoutEffect(() => { latest.current = props; });
  const [position] = useState(() => new Animated.Value(index * rowHeight));
  useLayoutEffect(() => {
    // Keep this row bound to the same native animation node across every drag.
    // While lifted, only the gesture owns position; preview indexes must not snap it.
    if (lifted) return;
    if (!busy) { position.setValue(index * rowHeight); return; }
    const animation = Animated.timing(position, { toValue: index * rowHeight, duration: 130, useNativeDriver: Platform.OS !== 'web' });
    animation.start(); return () => animation.stop();
  }, [busy, index, lifted, position, rowHeight]);
  /* eslint-disable react-hooks/refs -- PanResponder stores these callbacks; only input events access the ref. */
  const responder = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => latest.current.count > 1,
    onPanResponderGrant: event => latest.current.begin(latest.current.id, event.nativeEvent.pageY, position),
    onPanResponderMove: (_event, gesture) => latest.current.move(gesture.dy, gesture.moveY),
    onPanResponderRelease: () => latest.current.finish(true),
    onPanResponderTerminate: () => latest.current.finish(false),
    onPanResponderTerminationRequest: () => false,
  }), [position]);
  /* eslint-enable react-hooks/refs */
  const webProps = Platform.OS === 'web' ? {
    tabIndex: count > 1 ? 0 as const : -1 as const,
    'aria-valuemin': 1, 'aria-valuemax': count, 'aria-valuenow': index + 1, 'aria-valuetext': `Position ${index + 1} of ${count}`,
    onKeyUp: (event: KeyboardEvent<HTMLDivElement>) => { if (event.key === 'Escape') event.stopPropagation(); },
    onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => {
      if (event.key === 'Escape' && busy) { event.preventDefault(); event.stopPropagation(); props.finish(false); }
      else if (!busy && ['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) {
        event.preventDefault();
        reorder(event.key === 'Home' ? 0 : event.key === 'End' ? count - 1 : index + (event.key === 'ArrowUp' ? -1 : 1));
      }
    },
  } : {};
  return <Animated.View testID={`monitor-selected-${id}`} style={[styles.row, { height: rowHeight, zIndex: lifted ? 1 : 0, backgroundColor: lifted ? colors.surfaceRaised : colors.surface, borderRadius: lifted ? 8 : 0, transform: [{ translateY: position }] }]}>
    <Text numberOfLines={2} style={styles.name}>{metric.label}</Text>
    <View {...responder.panHandlers} {...webProps} testID={`monitor-drag-${id}`} accessible accessibilityRole="adjustable"
      accessibilityLabel={`Reorder ${metric.label}`} accessibilityHint="Drag up or down to reorder. Use accessibility actions to move one position."
      accessibilityState={{ disabled: count < 2 }} accessibilityValue={{ min: 1, max: count, now: index + 1, text: `Position ${index + 1} of ${count}` }}
      accessibilityActions={[...(index > 0 ? [{ name: 'decrement', label: 'Move up' }] : []), ...(index < count - 1 ? [{ name: 'increment', label: 'Move down' }] : [])]}
      onAccessibilityAction={event => { if (event.nativeEvent.actionName === 'decrement') reorder(index - 1); else if (event.nativeEvent.actionName === 'increment') reorder(index + 1); }}
      style={[styles.control, Platform.OS === 'web' && { cursor: lifted ? 'grabbing' : 'grab', touchAction: 'none', userSelect: 'none' } as object, { opacity: count < 2 ? 0.3 : 1 }]}>
      <Svg width={20} height={20} viewBox="0 0 24 24"><Path d="M5 7h14M5 12h14M5 17h14" stroke={lifted ? colors.accent : colors.muted} strokeWidth={1.8} strokeLinecap="round" /></Svg>
    </View>
    <Pressable accessibilityRole="button" accessibilityLabel={`Remove ${metric.label} from ${kind}`} disabled={busy} onPress={remove} style={styles.control}><Text style={{ color: colors.red, fontSize: 24 }}>×</Text></Pressable>
  </Animated.View>;
}

const styles = StyleSheet.create({
  selectedHeading: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8, paddingBottom: 4 },
  hint: { color: colors.muted, fontSize: 11 },
  row: { position: 'absolute', left: 0, right: 0, top: 0, flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: colors.border },
  name: { flex: 1, color: colors.text, fontSize: 13, paddingVertical: 8 },
  control: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
});
