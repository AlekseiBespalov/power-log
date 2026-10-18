import { useRef, type ReactNode } from 'react';
import { Platform, View, type GestureResponderEvent } from 'react-native';

const pullDistance = 80;
function scrollersAtTop(target: unknown) {
  if (!(target instanceof Element) || window.scrollY > 0) return false;
  for (let node: Element | null = target; node; node = node.parentElement) {
    if (node.scrollTop > 0) return false;
  }
  return true;
}
export function HistoryRefresh({
  children,
  enabled,
  refreshing,
  onRefresh,
}: {
  children: ReactNode;
  enabled: boolean;
  refreshing: boolean;
  onRefresh: () => void;
}) {
  const pull = useRef<{ x: number; y: number; distance: number } | null>(null);
  const start = (event: GestureResponderEvent) => {
    const touches = event.nativeEvent.touches;
    const touch = touches[0];
    pull.current =
      enabled && !refreshing && touches.length === 1 && touch && scrollersAtTop(event.target)
        ? { x: touch.pageX, y: touch.pageY, distance: 0 }
        : null;
  };
  const move = (event: GestureResponderEvent) => {
    const gesture = pull.current;
    if (!gesture) return;
    const touches = event.nativeEvent.touches;
    const touch = touches[0];
    if (!touch || touches.length !== 1 || Math.abs(touch.pageX - gesture.x) > 20) {
      pull.current = null;
      return;
    }
    gesture.distance = touch.pageY - gesture.y;
    if (gesture.distance > 10) event.preventDefault();
  };
  const end = () => {
    const gesture = pull.current;
    pull.current = null;
    if (enabled && !refreshing && gesture && gesture.distance >= pullDistance) onRefresh();
  };
  const web = Platform.OS === 'web';
  return (
    <View
      style={{ flex: 1 }}
      onTouchStart={web ? start : undefined}
      onTouchMove={web ? move : undefined}
      onTouchEnd={web ? end : undefined}
      onTouchCancel={
        web
          ? () => {
              pull.current = null;
            }
          : undefined
      }
    >
      {children}
    </View>
  );
}
