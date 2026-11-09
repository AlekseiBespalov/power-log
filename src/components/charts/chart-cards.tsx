import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react';
import { Modal, Platform, Text, View, useWindowDimensions, type LayoutRectangle } from 'react-native';
import { GestureHandlerRootView, ScrollView } from 'react-native-gesture-handler';
import { SafeAreaProvider, SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Path } from 'react-native-svg';
import { kit, type TemperatureUnit, type UnitSystem } from '../../../modules/power-log';
import type { EnergySmoothingSeconds } from '../energy-smoothing-menu';
import type { ChartFeedState } from '../../features/charts/chart-feed-model';
import { useModalAccessibility } from '../modal-accessibility';
import { useForegroundActivity } from '../use-foreground-activity';
import { useFocusedModal } from '../modal-dialog';
import { Card, colors } from '../ui';
import { ChartCard, Control, styles } from './chart-card';
import { ChartGrid } from './chart-grid';
import { chartGroups, isVoltage, reorderCharts, type ChartGroup, type ChartMetric } from './chart-groups';
import { hasSamples, originalPoint } from './chart-series';
import { clampChartViewport, isChartZoomed, zoomChartViewport, type ChartViewport } from './chart-viewport';
import {
  chartEstimate,
  chartVisibility,
  groupMetrics,
  type ChartBounds,
  type ChartVisibility,
} from './chart-visibility';
import type { ChartSnap } from './hit-test';
import { useChartInteraction } from './use-chart-interaction';

export interface ChartCardsProps {
  feed: ChartFeedState;
  metricIds: readonly string[];
  metric: (id: string) => ChartMetric | undefined;
  units: UnitSystem;
  temperature: TemperatureUnit;
  live: boolean;
  holdTail: boolean;
  emptyState?: ReactNode;
  energySmoothingSeconds?: EnergySmoothingSeconds;
  onEnergySmoothingChange?: (seconds: EnergySmoothingSeconds) => void;
  desktopColumns?: number;
  availableHeight?: number;
  onViewportChange: (next: ChartViewport, interacting?: boolean) => void;
  onCursorChange: (seconds: number | null, interacting?: boolean, snap?: ChartSnap | null) => void;
  onReferenceChange: (seconds: number | null) => void;
  onReorderCharts?: (ids: string[]) => void;
  onPlotWidthChange?: (width: number) => void;
  onVisibleMetricsChange?: (metrics: string[]) => void;
}

export function ChartCards(props: ChartCardsProps) {
  const groups = useMemo(() => chartGroups(props.metricIds, props.metric), [props.metricIds, props.metric]);
  const [fullscreen, setFullscreen] = useState(false);
  const [ready, setReady] = useState(false);
  const [inlineHeight, setInlineHeight] = useState(0);
  const [width, setWidth] = useState(360);
  const columns = props.desktopColumns
    ? Math.min(props.desktopColumns, Math.max(1, Math.floor((width + 12) / 372)), Math.max(1, groups.length))
    : 1;
  const rows = Math.max(1, Math.ceil(groups.length / columns));
  const height = props.availableHeight ? Math.max(140, Math.min(480, (props.availableHeight - 56) / rows - 104)) : 140;
  const close = useCallback(() => {
    setFullscreen(false);
    setReady(false);
  }, []);
  const expand = useCallback(() => setFullscreen(true), []);
  const fullscreenVisible = useFocusedModal(fullscreen, close);
  const fullscreenRef = useModalAccessibility(fullscreenVisible);
  if (props.emptyState) return <Card>{props.emptyState}</Card>;
  if (!groups.length) return null;
  return (
    <View testID="monitor-charts" onLayout={event => setWidth(event.nativeEvent.layout.width)}>
      {!fullscreen ? (
        <View onLayout={event => setInlineHeight(event.nativeEvent.layout.height)}>
          <ChartCollection {...props} groups={groups} columns={columns} height={height} onExpand={expand} />
        </View>
      ) : (
        <View style={{ height: inlineHeight }} />
      )}
      {/* Fabric recognizers attach only after the native modal has been presented. */}
      <Modal
        visible={fullscreenVisible}
        animationType="fade"
        onShow={() => setReady(true)}
        onRequestClose={close}
        supportedOrientations={['portrait', 'landscape']}
      >
        <View ref={fullscreenRef} aria-hidden={!fullscreenVisible} style={{ flex: 1 }}>
          <GestureHandlerRootView style={styles.fullscreen}>
            <SafeAreaProvider>
              {fullscreenVisible && ready && <FullscreenCharts {...props} groups={groups} onClose={close} />}
            </SafeAreaProvider>
          </GestureHandlerRootView>
        </View>
      </Modal>
    </View>
  );
}

type CollectionProps = ChartCardsProps & {
  groups: ChartGroup[];
  height: number;
  columns?: number;
  fullscreen?: boolean;
  onExpand?: () => void;
  onClose?: () => void;
};

function FullscreenCharts(props: Omit<CollectionProps, 'height'>) {
  const size = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const available = size.height - insets.top - insets.bottom - 132;
  const columns = props.desktopColumns
    ? Math.min(props.desktopColumns, Math.max(1, Math.floor((size.width - 20) / 372)), props.groups.length)
    : 1;
  const rows = Math.ceil(props.groups.length / columns);
  const height = props.desktopColumns
    ? Math.max(140, Math.min(rows === 1 ? available : 360, available / rows - 104))
    : Math.max(140, Math.min(props.groups.length === 1 ? available : 260, available / props.groups.length - 50));
  return (
    <SafeAreaView style={styles.fullscreen} edges={['top', 'right', 'bottom', 'left']}>
      <ScrollView
        contentContainerStyle={styles.fullscreenContent}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        <ChartCollection {...props} height={height} columns={columns} fullscreen />
      </ScrollView>
    </SafeAreaView>
  );
}

type ChartPlacement = {
  top: number | null;
  layouts: ReadonlyMap<string, ChartBounds>;
  onScreen: ReadonlyMap<string, boolean>;
  read: ReadonlyMap<string, boolean>;
};

function scrollParent(node: HTMLElement | null) {
  for (let parent = node?.parentElement; parent; parent = parent.parentElement) {
    const { overflowY } = getComputedStyle(parent);
    if (overflowY === 'auto' || overflowY === 'scroll') return parent;
  }
  return null;
}

/**
 * Native charts are placed from their layouts inside the collection and the collection's polled window position;
 * web charts use intersection observers rooted at their scroll container.
 */
function useChartVisibility(
  ids: readonly string[],
  estimate: number,
): ChartVisibility & {
  container: RefObject<View | null>;
  onChartLayout: (id: string, layout: LayoutRectangle) => void;
  onChartNode: (id: string, node: unknown) => void;
} {
  const { height: screenHeight } = useWindowDimensions();
  const active = useForegroundActivity();
  const container = useRef<View>(null);
  const nodes = useRef(new Map<string, HTMLElement>());
  const observers = useRef<IntersectionObserver[]>([]);
  const [placement, setPlacement] = useState<ChartPlacement>({
    top: null,
    layouts: new Map(),
    onScreen: new Map(),
    read: new Map(),
  });
  useEffect(() => {
    if (Platform.OS === 'web' || !active) return;
    const measure = () =>
      container.current?.measureInWindow((_x, y) =>
        setPlacement(previous => (previous.top === y ? previous : { ...previous, top: y })),
      );
    measure();
    const timer = setInterval(measure, 200);
    return () => clearInterval(timer);
  }, [active]);
  useEffect(() => {
    if (Platform.OS !== 'web' || !active) return;
    const root = scrollParent(container.current as unknown as HTMLElement | null);
    const observe = (key: 'onScreen' | 'read', rootMargin: string) =>
      new IntersectionObserver(
        entries =>
          setPlacement(previous => {
            const next = new Map(previous[key]);
            for (const entry of entries) {
              const id = (entry.target as HTMLElement).dataset.monitorChart;
              if (id) next.set(id, entry.isIntersecting);
            }
            return { ...previous, [key]: next };
          }),
        { root, rootMargin },
      );
    observers.current = [observe('onScreen', '0px'), observe('read', '50% 0px')];
    for (const node of nodes.current.values()) for (const observer of observers.current) observer.observe(node);
    return () => {
      for (const observer of observers.current) observer.disconnect();
      observers.current = [];
    };
  }, [active]);
  const visibility = useMemo(() => {
    if (Platform.OS === 'web') {
      const fallback = chartVisibility(ids, new Map(), screenHeight, estimate);
      return {
        onScreen: ids.filter(id => placement.onScreen.get(id) ?? fallback.onScreen.includes(id)),
        read: ids.filter(id => placement.read.get(id) ?? fallback.read.includes(id)),
      };
    }
    const bounds = new Map<string, ChartBounds>();
    if (placement.top !== null)
      for (const [id, layout] of placement.layouts)
        bounds.set(id, { top: placement.top + layout.top, bottom: placement.top + layout.bottom });
    return chartVisibility(ids, bounds, screenHeight, estimate);
  }, [ids, screenHeight, estimate, placement]);
  const onChartLayout = useCallback((id: string, layout: LayoutRectangle) => {
    const bounds = { top: layout.y, bottom: layout.y + layout.height };
    setPlacement(previous => {
      const known = previous.layouts.get(id);
      if (known?.top === bounds.top && known.bottom === bounds.bottom) return previous;
      return { ...previous, layouts: new Map(previous.layouts).set(id, bounds) };
    });
  }, []);
  const onChartNode = useCallback((id: string, node: unknown) => {
    const previous = nodes.current.get(id);
    if (previous) for (const observer of observers.current) observer.unobserve(previous);
    if (!node) {
      nodes.current.delete(id);
      return;
    }
    const element = node as HTMLElement;
    element.dataset.monitorChart = id;
    nodes.current.set(id, element);
    for (const observer of observers.current) observer.observe(element);
  }, []);
  return { ...visibility, container, onChartLayout, onChartNode };
}

function ChartCollection(props: CollectionProps) {
  const size = useWindowDimensions();
  const {
    feed,
    onViewportChange,
    onCursorChange,
    onReferenceChange,
    groups,
    fullscreen,
    onExpand,
    onClose,
    onVisibleMetricsChange,
  } = props;
  const cursorSeconds = feed.cursor?.atS ?? null;
  const referenceSeconds = feed.reference?.atS ?? null;
  const { startS: domainStart, endS: domainEnd } = feed.domain;
  const domain = useMemo(() => ({ start: domainStart, end: domainEnd }), [domainStart, domainEnd]);
  const { start: viewStart, end: viewEnd } = clampChartViewport(
    { start: feed.viewport.startS, end: feed.viewport.endS },
    domain,
  );
  const view = useMemo(() => ({ start: viewStart, end: viewEnd }), [viewStart, viewEnd]);
  const {
    shared,
    receive,
    cancel: cancelGestures,
    epoch,
    sequence,
  } = useChartInteraction(
    { view, domain, cursor: cursorSeconds, reference: referenceSeconds },
    `${feed.sourceKey}:${feed.resetKey}:${groups.map(group => group.metrics.map(metric => metric.id).join(',')).join(';')}:${fullscreen ?? false}:${size.width}:${size.height}:${size.fontScale}:${props.height}`,
    onCursorChange,
    onViewportChange,
  );
  const interaction = useMemo(
    () => ({ shared, receive, cancel: cancelGestures, epoch, sequence }),
    [shared, receive, cancelGestures, epoch, sequence],
  );
  const cancel = useCallback(() => {
    if (Platform.OS !== 'web') cancelGestures();
  }, [cancelGestures]);
  const setCursor = useCallback(
    (seconds: number | null, interacting = false) => {
      cancel();
      onCursorChange(seconds, interacting);
    },
    [cancel, onCursorChange],
  );
  const setViewport = useCallback(
    (next: ChartViewport, interacting = false) => {
      cancel();
      onViewportChange(next, interacting);
    },
    [cancel, onViewportChange],
  );
  const setReference = useCallback(
    (seconds: number | null) => {
      cancel();
      onReferenceChange(seconds);
    },
    [cancel, onReferenceChange],
  );
  const ids = useMemo(() => groups.map(group => group.id), [groups]);
  const { onScreen, read, container, onChartLayout, onChartNode } = useChartVisibility(
    ids,
    chartEstimate(props.height),
  );
  const [focusedChart, setFocusedChart] = useState<string | null>(null);
  const onChartFocus = useCallback(
    (id: string, focused: boolean) => setFocusedChart(current => (focused ? id : current === id ? null : current)),
    [],
  );
  const shown = (id: string) => onScreen.includes(id) || id === focusedChart;
  const readKey = ids.filter(id => read.includes(id) || id === focusedChart).join(',');
  const visibleMetrics = useMemo(() => groupMetrics(groups, readKey.split(',')), [groups, readKey]);
  useEffect(() => {
    onVisibleMetricsChange?.(visibleMetrics);
  }, [onVisibleMetricsChange, visibleMetrics]);
  const zoomed = isChartZoomed(view, domain);
  const anySamples = useMemo(() => groups.some(group => hasSamples(group.metrics, feed.series)), [groups, feed.series]);
  const owner = groups.find(group => group.metrics.some(metric => isVoltage(metric.id)));
  const canReference =
    !!feed.cursor &&
    !feed.cursor.pending &&
    groups.some(group =>
      group.metrics.some(metric => isVoltage(metric.id) && originalPoint(feed.cursor!.values[metric.id]?.original)),
    );
  const zoom = (scale: number) => {
    const currentView = Platform.OS === 'web' ? view : interaction.shared.value.view;
    cancel();
    onCursorChange(null);
    onViewportChange(zoomChartViewport(currentView, domain, scale));
  };
  const suffix = fullscreen ? '-fullscreen' : '';
  const card = (group: ChartGroup) => (
    <ChartCard
      key={group.id}
      feed={feed}
      group={group}
      view={view}
      domain={domain}
      height={props.height}
      laneCount={groups.length}
      units={props.units}
      temperature={props.temperature}
      interaction={interaction}
      live={props.live}
      holdTail={props.holdTail}
      ownsComparison={group === owner}
      canReference={group === owner && canReference}
      fullscreen={fullscreen ?? false}
      desktopColumns={props.desktopColumns}
      energySmoothingSeconds={props.energySmoothingSeconds}
      onEnergySmoothingChange={props.onEnergySmoothingChange}
      onCursorChange={setCursor}
      onViewportChange={setViewport}
      onReferenceChange={setReference}
      onClose={onClose}
      onPlotWidthChange={props.onPlotWidthChange}
      onScreen={shown(group.id)}
      onChartLayout={onChartLayout}
      onChartNode={onChartNode}
      onChartFocus={onChartFocus}
    />
  );
  // Fabric can retain old text bounds when Dynamic Type changes in an open modal.
  return (
    <View key={size.fontScale} ref={container} style={styles.collection}>
      <View style={styles.toolbar}>
        <Text style={styles.range}>
          {anySamples ? `${kit.formatDuration(view.start * 1e6)} – ${kit.formatDuration(view.end * 1e6)}` : ' '}
        </Text>
        <View style={styles.controls}>
          <Control
            label="Reset chart zoom"
            testID={`monitor-reset${suffix}`}
            disabled={!zoomed}
            onPress={() => setViewport(domain)}
          >
            <Svg width={18} height={18} viewBox="0 0 24 24">
              <Path
                d="M4 10a8 8 0 1 1 1 8M4 4v6h6"
                fill="none"
                stroke={colors.text}
                strokeWidth={1.7}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </Svg>
          </Control>
          <Control
            label="Zoom out charts"
            testID={`monitor-zoom-out${suffix}`}
            disabled={!zoomed}
            onPress={() => zoom(0.5)}
          >
            <Text style={styles.zoomText}>−</Text>
          </Control>
          <Control
            label="Zoom in charts"
            testID={`monitor-zoom-in${suffix}`}
            disabled={view.end - view.start <= 1.000001}
            onPress={() => zoom(2)}
          >
            <Text style={styles.zoomText}>+</Text>
          </Control>
          {fullscreen ? (
            <Control
              label="Close fullscreen charts"
              testID="monitor-close"
              onPress={() => {
                cancel();
                onClose?.();
              }}
            >
              <Text style={styles.controlText}>Done</Text>
            </Control>
          ) : (
            <Control
              label="Expand charts"
              testID="monitor-expand"
              onPress={() => {
                cancel();
                onExpand?.();
              }}
            >
              <Svg width={18} height={18} viewBox="0 0 24 24">
                <Path d="M9 3H3v6M15 3h6v6M3 15v6h6M21 15v6h-6" fill="none" stroke={colors.text} strokeWidth={1.7} />
              </Svg>
            </Control>
          )}
        </View>
      </View>
      {props.desktopColumns ? (
        <ChartGrid
          columns={props.columns ?? 1}
          onMove={(from, to) => props.onReorderCharts?.(reorderCharts(props.metricIds, from, to))}
          items={groups.map(group => ({ id: group.id, label: group.label, content: card(group) }))}
        />
      ) : (
        groups.map(card)
      )}
    </View>
  );
}
