import type { EnergySmoothingSeconds } from '../core/energy-per-distance';
import { EnergySmoothingMenu } from './energy-smoothing-menu';
import type { MonitorSnap, MonitorHitScene } from '../core/monitor-hit-test';
import { useSharedValue } from 'react-native-reanimated';
import {
  memo,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
  type RefObject,
} from 'react';
import { Modal, Platform, StyleSheet, Text, View, useWindowDimensions, type LayoutRectangle } from 'react-native';
import { Gesture, GestureDetector, GestureHandlerRootView, ScrollView } from 'react-native-gesture-handler';
import { SafeAreaProvider, SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Circle, ClipPath, Defs, G, Line, Path, Rect } from 'react-native-svg';
import { beginChartTransform, moveChartTransform, type ChartTransform } from '../core/chart-touch';
import {
  CHART_INSET,
  clampChartViewport,
  isChartZoomed,
  panChartViewport,
  zoomChartViewport,
  type ChartViewport,
} from '../core/chart-viewport';
import {
  formatMetric,
  metricAllowsMean,
  formatMetricPoint,
  formatMetricDifference,
  metricValue,
  type MonitorData,
  type MonitorMetric,
  type MonitorPoint,
  type SpeedUnit,
} from '../core/monitor';
import {
  groupMonitorMetrics,
  isVoltageMetric,
  monitorChartData,
  reorderMonitorCharts,
  monitorDisplayDomain,
  monitorElapsed,
  monitorLineGeometry,
  monitorScale,
  monitorSelection,
  monitorTailSeconds,
  monitorViewportTransform,
  stepMonitorCursor,
  validMonitorPoint,
  type MonitorPlotGroup,
} from '../core/monitor-chart';
import {
  monitorChartEstimate,
  monitorChartVisibility,
  monitorGroupMetrics,
  type MonitorChartBounds,
  type MonitorChartVisibility,
} from '../core/monitor-visibility';
import { Card, colors, formatDuration, Pressable, type, lineHeight } from './ui';
import { useChartInteraction, type ChartInteraction } from './use-chart-interaction';
import { useNativeChartGestures } from './use-native-chart-gestures';
import { MonitorRaster } from './monitor-raster';
import { MonitorRasterAxis } from './monitor-raster-axis';
import { MonitorChartGrid } from './monitor-chart-grid';
import { useFocusedModal } from './modal-dialog';
import { useModalAccessibility } from '../services/modal-accessibility';
import { useForegroundActivity } from '../services/use-foreground-activity';

export interface MonitorChartsProps {
  emptyState?: ReactNode;
  energySmoothingSeconds?: EnergySmoothingSeconds;
  onEnergySmoothingChange?: (seconds: EnergySmoothingSeconds) => void;
  resetKey?: number;
  data: MonitorData;
  metricIds: readonly string[];
  speedUnit?: SpeedUnit;
  viewport: ChartViewport;
  onViewportChange: (next: ChartViewport, interacting?: boolean) => void;
  cursorSeconds: number | null;
  onCursorChange: (seconds: number | null, interacting?: boolean, snap?: MonitorSnap | null) => void;
  referenceSeconds: number | null;
  onReferenceChange: (seconds: number | null) => void;
  live?: boolean;
  following?: boolean;
  holdTail?: boolean;
  displayTimeSeconds?: number;
  desktopColumns?: number;
  availableHeight?: number;
  onReorderCharts?: (ids: string[]) => void;
  onPlotWidthChange?: (width: number) => void;
  onVisibleMetricsChange?: (metrics: string[]) => void;
}

const leftInset = CHART_INSET.left;
const rightInset = CHART_INSET.right;
const isVoltage = (metric: MonitorMetric) => isVoltageMetric(metric.id);

export function MonitorCharts(props: MonitorChartsProps) {
  const groups = useMemo(
    () => groupMonitorMetrics(props.metricIds, props.speedUnit),
    [props.metricIds, props.speedUnit],
  );
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

type CollectionProps = MonitorChartsProps & {
  groups: MonitorPlotGroup[];
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
  layouts: ReadonlyMap<string, MonitorChartBounds>;
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
): MonitorChartVisibility & {
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
      const fallback = monitorChartVisibility(ids, new Map(), screenHeight, estimate);
      return {
        onScreen: ids.filter(id => placement.onScreen.get(id) ?? fallback.onScreen.includes(id)),
        read: ids.filter(id => placement.read.get(id) ?? fallback.read.includes(id)),
      };
    }
    const bounds = new Map<string, MonitorChartBounds>();
    if (placement.top !== null)
      for (const [id, layout] of placement.layouts)
        bounds.set(id, { top: placement.top + layout.top, bottom: placement.top + layout.bottom });
    return monitorChartVisibility(ids, bounds, screenHeight, estimate);
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

const chartInputs = new WeakMap<MonitorPlotGroup, MonitorData>();
function chartData(data: MonitorData, group: MonitorPlotGroup) {
  const next = monitorChartData(data, group.metrics, chartInputs.get(group));
  chartInputs.set(group, next);
  return next;
}

function ChartCollection(props: CollectionProps) {
  const size = useWindowDimensions();
  const {
    data,
    viewport,
    onViewportChange,
    cursorSeconds,
    onCursorChange,
    referenceSeconds,
    onReferenceChange,
    groups,
    fullscreen,
    onExpand,
    onClose,
    onVisibleMetricsChange,
  } = props;
  const { start: domainStart, end: domainEnd } = monitorDisplayDomain(data, props.live, props.displayTimeSeconds);
  const domain = useMemo(() => ({ start: domainStart, end: domainEnd }), [domainStart, domainEnd]);
  const { start: viewStart, end: viewEnd } = clampChartViewport(viewport, domain);
  const view = useMemo(() => ({ start: viewStart, end: viewEnd }), [viewStart, viewEnd]);
  const {
    shared,
    receive,
    cancel: cancelGestures,
    epoch,
    sequence,
  } = useChartInteraction(
    { view, domain, cursor: cursorSeconds, reference: referenceSeconds },
    `${data.sourceId}:${props.resetKey ?? 0}:${groups.map(group => group.metrics.map(metric => metric.id).join(',')).join(';')}:${fullscreen ?? false}:${size.width}:${size.height}:${size.fontScale}:${props.height}`,
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
    monitorChartEstimate(props.height),
  );
  const [focusedChart, setFocusedChart] = useState<string | null>(null);
  const onChartFocus = useCallback(
    (id: string, focused: boolean) => setFocusedChart(current => (focused ? id : current === id ? null : current)),
    [],
  );
  const shown = (id: string) => onScreen.includes(id) || id === focusedChart;
  const readKey = ids.filter(id => read.includes(id) || id === focusedChart).join(',');
  const visibleMetrics = useMemo(() => monitorGroupMetrics(groups, readKey.split(',')), [groups, readKey]);
  useEffect(() => {
    onVisibleMetricsChange?.(visibleMetrics);
  }, [onVisibleMetricsChange, visibleMetrics]);
  const zoomed = isChartZoomed(view, domain);
  const anySamples = useMemo(
    () =>
      groups.some(group =>
        group.metrics.some(metric =>
          data.series[metric.id]?.some(
            point => validMonitorPoint(point) && Number.isFinite(metricValue(metric, point.value)),
          ),
        ),
      ),
    [groups, data.series],
  );
  const owner = groups.find(item => item.metrics.some(isVoltage));
  const canReference =
    !data.selectionPending &&
    cursorSeconds !== null &&
    groups.some(item =>
      item.metrics.some(metric => isVoltage(metric) && monitorSelection(data, metric, cursorSeconds) !== null),
    );
  const zoom = (scale: number) => {
    const currentView = Platform.OS === 'web' ? view : interaction.shared.value.view;
    cancel();
    onCursorChange(null);
    onViewportChange(zoomChartViewport(currentView, domain, scale));
  };
  const suffix = fullscreen ? '-fullscreen' : '';
  const plot = (group: MonitorPlotGroup) => (
    <MonitorPlot
      key={group.id}
      chart={chartData(data, group)}
      group={group}
      view={view}
      domain={domain}
      height={props.height}
      laneCount={groups.length}
      interaction={interaction}
      live={props.live}
      following={props.following}
      holdTail={props.holdTail ?? true}
      displayTimeSeconds={props.displayTimeSeconds}
      nowSeconds={data.nowSeconds}
      monotonicAt={data.monotonicAt}
      cursor={cursorSeconds}
      referenceSeconds={referenceSeconds}
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
          {anySamples ? `${formatDuration(view.start)} – ${formatDuration(view.end)}` : ' '}
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
        <MonitorChartGrid
          columns={props.columns ?? 1}
          onMove={(from, to) => props.onReorderCharts?.(reorderMonitorCharts(props.metricIds, from, to))}
          items={groups.map(group => ({ id: group.id, label: group.label, content: plot(group) }))}
        />
      ) : (
        groups.map(plot)
      )}
    </View>
  );
}

function latestMonitorPoint(
  points: readonly MonitorPoint[],
  metric: MonitorMetric,
  view?: ChartViewport,
): MonitorPoint | null {
  for (let index = points.length - 1; index >= 0; index--) {
    const point = points[index]!;
    if (
      validMonitorPoint(point) &&
      Number.isFinite(metricValue(metric, point.value)) &&
      (!view || (point.elapsedSeconds >= view.start && point.elapsedSeconds <= view.end))
    )
      return point;
  }
  return null;
}

export function historyRangeReadout(data: MonitorData, metric: MonitorMetric, view: ChartViewport) {
  const matches =
    !data.statisticsPending &&
    (!data.statisticsViewport ||
      (Math.abs(data.statisticsViewport.start - view.start) < 1e-6 &&
        Math.abs(data.statisticsViewport.end - view.end) < 1e-6));
  const stats = matches ? data.statistics[metric.id] : undefined;
  if (metric.semantics === 'ratio')
    return { value: `avg ${formatMetric(metric, stats?.ratio)}`, detail: stats?.partial ? 'Partial data' : '' };
  const cumulative = stats?.amount ?? stats?.distance;
  if (metric.semantics === 'cumulative' || metric.semantics === 'distance')
    return {
      value: formatMetric(metric, cumulative),
      detail: '',
    };
  const mean = metricAllowsMean(metric) && stats && (stats.count ?? 0) > 0 ? stats.sampleMean : undefined;
  const max = stats && (stats.count ?? 0) > 0 && validMonitorPoint(stats.max) ? stats.max : null;
  const min = stats && (stats.count ?? 0) > 0 && validMonitorPoint(stats.min) ? stats.min : null;
  const value =
    mean !== undefined && Number.isFinite(mean)
      ? `avg ${formatMetric(metric, mean)}`
      : `min ${formatMetricPoint(metric, min)}`;

  return {
    value,
    detail: matches ? `max ${formatMetricPoint(metric, max)} ${metric.unit}` : '',
  };
}

type PlotProps = {
  chart: MonitorData;
  group: MonitorPlotGroup;
  view: ChartViewport;
  domain: ChartViewport;
  height: number;
  laneCount: number;
  interaction: ChartInteraction;
  live?: boolean;
  following?: boolean;
  holdTail: boolean;
  displayTimeSeconds?: number;
  nowSeconds?: number;
  monotonicAt?: number;
  cursor: number | null;
  referenceSeconds: number | null;
  ownsComparison: boolean;
  canReference: boolean;
  fullscreen: boolean;
  desktopColumns?: number;
  energySmoothingSeconds?: EnergySmoothingSeconds;
  onEnergySmoothingChange?: (seconds: EnergySmoothingSeconds) => void;
  onCursorChange: (seconds: number | null, interacting?: boolean) => void;
  onViewportChange: (next: ChartViewport, interacting?: boolean) => void;
  onReferenceChange: (seconds: number | null) => void;
  onClose?: () => void;
  onPlotWidthChange?: (width: number) => void;
  onScreen: boolean;
  onChartLayout: (id: string, layout: LayoutRectangle) => void;
  onChartNode: (id: string, node: unknown) => void;
  onChartFocus: (id: string, focused: boolean) => void;
};
const layoutProps = new Set<keyof PlotProps>([
  'group',
  'height',
  'laneCount',
  'ownsComparison',
  'referenceSeconds',
  'fullscreen',
  'desktopColumns',
  'onEnergySmoothingChange',
  'onChartLayout',
  'onChartNode',
  'onChartFocus',
]);
/** Off-screen charts skip every update that cannot change their size. */
function samePlotProps(previous: PlotProps, next: PlotProps) {
  const hidden = !next.onScreen;
  return (Object.keys(next) as (keyof PlotProps)[]).every(
    key => previous[key] === next[key] || (hidden && !layoutProps.has(key)),
  );
}

const MonitorPlot = memo(function MonitorPlot({
  chart,
  group,
  view,
  domain,
  height,
  laneCount,
  interaction,
  live,
  following,
  holdTail,
  displayTimeSeconds,
  nowSeconds,
  monotonicAt,
  cursor,
  referenceSeconds,
  ownsComparison,
  canReference,
  fullscreen,
  desktopColumns,
  energySmoothingSeconds,
  onEnergySmoothingChange,
  onCursorChange: changeCursor,
  onViewportChange: commitViewport,
  onReferenceChange: setReference,
  onClose,
  onPlotWidthChange,
  onChartLayout,
  onChartNode,
  onChartFocus,
}: PlotProps) {
  const data = useMemo(() => ({ ...chart, nowSeconds, monotonicAt }), [chart, nowSeconds, monotonicAt]);
  const [width, setWidth] = useState(360);
  const { fontScale } = useWindowDimensions();
  const readingColumns = Math.max(1, Math.min(group.metrics.length, Math.floor((width + 12) / (120 * fontScale + 12))));
  const surface = useRef<View>(null);
  const clipID = `monitor-${useId().replace(/[^a-z0-9]/gi, '')}`;
  const zoomed = isChartZoomed(view, domain);
  const plotWidth = Math.max(1, width - leftInset - rightInset);
  const duration = view.end - view.start;
  const {
    min: scaleMin,
    max: scaleMax,
    decimals,
  } = useMemo(
    () => monitorScale(group.metrics, { series: data.series, statistics: data.statistics }),
    [group.metrics, data.series, data.statistics],
  );
  const scale = useMemo(() => ({ min: scaleMin, max: scaleMax, decimals }), [scaleMin, scaleMax, decimals]);
  const hasSamples = useMemo(
    () =>
      group.metrics.some(metric =>
        data.series[metric.id]?.some(
          point => validMonitorPoint(point) && Number.isFinite(metricValue(metric, point.value)),
        ),
      ),
    [group.metrics, data.series],
  );
  const drawn = !data.drawings || group.metrics.some(metric => data.drawings![metric.id]);
  const snappable = !data.drawings || group.metrics.every(metric => data.drawings![metric.id]?.current);
  const geometryView = data.plotViewport ?? data.domain;
  const geometryDuration = Math.max(1e-9, geometryView.end - geometryView.start);
  const displayX = (seconds: number) => leftInset + ((seconds - view.start) / duration) * plotWidth;
  const transform = monitorViewportTransform(geometryView, view, leftInset, plotWidth);
  const plot = useMemo(() => {
    const x = (seconds: number) => leftInset + ((seconds - geometryView.start) / geometryDuration) * plotWidth;
    const y = (value: number) => 8 + ((scale.max - value) / (scale.max - scale.min)) * (height - 34);
    const paths =
      Platform.OS === 'web'
        ? group.metrics.map(metric => {
            const points = data.series[metric.id] ?? [];
            const geometry = monitorLineGeometry(points, metric, x, y);
            return { metric, ...geometry };
          })
        : [];
    return { x, y, paths };
  }, [data.series, group.metrics, geometryView.start, geometryDuration, plotWidth, scale, height]);
  const elapsed = live ? monitorElapsed : formatDuration;
  const historyRange = !live && cursor === null;
  const historical = !live || (following !== undefined ? !following : view.end < domain.end - 1e-6);
  const readings = group.metrics.map(metric => {
    const selected = monitorSelection(data, metric, cursor);
    const latest = historical
      ? latestMonitorPoint(data.series[metric.id] ?? [], metric, view)
      : data.latest
        ? data.latest[metric.id]
        : latestMonitorPoint(data.series[metric.id] ?? [], metric);
    const shown = cursor !== null ? selected : latest;
    const range = live ? null : historyRangeReadout(data, metric, view);
    const rangeValue = range?.value ?? '';
    const rangeDetail = range?.detail ?? '';
    const pointValue = shown ? formatMetricPoint(metric, shown) : cursor === null ? 'Unavailable' : 'No sample';
    const pointTime = shown && cursor !== null ? elapsed(shown.elapsedSeconds) : '';
    const pointAnnouncement = shown
      ? `${elapsed(shown.elapsedSeconds)} elapsed, ${shown.timestamp}`
      : cursor === null
        ? 'Unavailable'
        : 'No sample';
    return {
      metric,
      selected,
      shown,
      rangeValue,
      rangeDetail,
      valueText: historyRange ? rangeValue : pointValue,
      timeText: historyRange ? rangeDetail : pointTime,
      timeAnnouncement: historyRange ? rangeDetail : pointAnnouncement,
      reference: monitorSelection(data, metric, referenceSeconds, 'reference'),
    };
  });
  const readingRows = Array.from({ length: Math.ceil(readings.length / readingColumns) }, (_, row) =>
    readings.slice(row * readingColumns, (row + 1) * readingColumns),
  );
  const accessibleValue = readings
    .map(({ metric, shown, rangeValue, rangeDetail, timeAnnouncement }) =>
      historyRange
        ? `${metric.label}: ${rangeValue} ${metric.unit}${rangeDetail ? `. ${rangeDetail}` : ''}`
        : shown
          ? `${metric.label} ${formatMetricPoint(metric, shown)} ${metric.unit}, ${timeAnnouncement}`
          : `${metric.label}: ${cursor === null ? 'Unavailable' : 'No sample'}`,
    )
    .join('. ');
  const selectAt = (locationX: number) => {
    if (!Number.isFinite(locationX)) return;
    changeCursor(view.start + Math.max(0, Math.min(1, (locationX - leftInset) / plotWidth)) * duration);
  };
  const stepCursor = (direction: -1 | 1) => {
    const next = stepMonitorCursor(data, group.metrics, cursor, direction, view);
    changeCursor(next);
    if (next !== null && (next < view.start || next > view.end))
      commitViewport(clampChartViewport({ start: next - duration / 2, end: next + duration / 2 }, domain));
  };
  const zoom = (amount: number) => {
    changeCursor(null);
    commitViewport(zoomChartViewport(view, domain, amount));
  };
  const current = useRef({ view, domain, zoomed, plotWidth, cursor, selectAt, changeCursor, commitViewport });
  useLayoutEffect(() => {
    current.current = { view, domain, zoomed, plotWidth, cursor, selectAt, changeCursor, commitViewport };
  });
  const touchSession = useRef<{ multi: boolean; transform: ChartTransform | null; last?: ChartViewport }>({
    multi: false,
    transform: null,
  });
  const hitScene = useSharedValue<MonitorHitScene | null>(null);
  const nativeGestures = useNativeChartGestures(interaction, plotWidth, hitScene);
  /* eslint-disable react-hooks/refs -- RNGH builders retain callbacks; refs are accessed only by gesture events. */
  const gestures = useMemo(() => {
    const navigation = Gesture.Pan()
      .minPointers(2)
      .maxPointers(2)
      .minDistance(0)
      .averageTouches(true)
      .runOnJS(true)
      .onTouchesDown(event => {
        if (event.numberOfTouches === 1) touchSession.current = { multi: false, transform: null };
        else {
          const state = current.current;
          touchSession.current = {
            multi: true,
            transform: beginChartTransform(event.allTouches, state.view, state.domain, leftInset, state.plotWidth),
          };
          state.changeCursor(null);
        }
      })
      .onTouchesMove(event => {
        const session = touchSession.current;
        if (!session.multi || !session.transform || event.numberOfTouches !== 2) return;
        const next = moveChartTransform(session.transform, event.allTouches);
        if (next) {
          session.last = next;
          current.current.commitViewport(next, true);
        }
      })
      .onTouchesUp(() => {
        const session = touchSession.current;
        if (session.last) current.current.commitViewport(session.last);
        session.last = undefined;
        session.transform = null;
      })
      .onTouchesCancelled(() => {
        const session = touchSession.current;
        if (session.last) current.current.commitViewport(session.last);
        session.last = undefined;
        session.transform = null;
      });
    const inspect = Gesture.Pan()
      .maxPointers(1)
      .activeOffsetX([-8, 8])
      .failOffsetY([-8, 8])
      .runOnJS(true)
      .onStart(event => {
        if (!touchSession.current.multi && event.numberOfPointers === 1) current.current.selectAt(event.x);
      })
      .onUpdate(event => {
        if (!touchSession.current.multi && event.numberOfPointers === 1) current.current.selectAt(event.x);
      });
    const tap = Gesture.Tap()
      .maxDistance(8)
      .runOnJS(true)
      .onEnd((event, success) => {
        if (!success || touchSession.current.multi) return;
        if (current.current.cursor !== null) current.current.changeCursor(null);
        else current.current.selectAt(event.x);
      });
    return Gesture.Simultaneous(navigation, Gesture.Race(inspect, tap));
  }, []);
  /* eslint-enable react-hooks/refs */
  useEffect(() => {
    if (Platform.OS !== 'web') return;
    const node = surface.current as unknown as HTMLElement | null;
    if (!node) return;
    const wheel = (event: WheelEvent) => {
      const state = current.current;
      if (event.ctrlKey || event.metaKey) {
        event.preventDefault();
        state.changeCursor(null);
        const anchor = (event.clientX - node.getBoundingClientRect().left - leftInset) / state.plotWidth;
        state.commitViewport(
          zoomChartViewport(
            state.view,
            state.domain,
            Math.exp(-Math.max(-100, Math.min(100, event.deltaY)) / 150),
            anchor,
          ),
          true,
        );
      } else if (state.zoomed && (event.shiftKey || Math.abs(event.deltaX) > Math.abs(event.deltaY))) {
        event.preventDefault();
        state.changeCursor(null);
        state.commitViewport(
          panChartViewport(state.view, state.domain, (event.deltaX || event.deltaY) / state.plotWidth),
          true,
        );
      }
    };
    node.addEventListener('wheel', wheel, { passive: false });
    return () => node.removeEventListener('wheel', wheel);
  }, []);
  const chartNode = useCallback((node: View | null) => onChartNode(group.id, node), [onChartNode, group.id]);
  const webProps =
    Platform.OS === 'web'
      ? {
          'aria-valuetext': `${accessibleValue}. Visible ${elapsed(view.start)} to ${elapsed(view.end)}.`,
          dataSet: { visibleStart: String(view.start), visibleEnd: String(view.end) },
          tabIndex: 0 as const,
          onFocus: () => onChartFocus(group.id, true),
          onBlur: () => onChartFocus(group.id, false),
          onKeyUp: (event: KeyboardEvent<HTMLDivElement>) => {
            if (event.key === 'Escape') event.stopPropagation();
          },
          onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => {
            if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
              event.preventDefault();
              const direction = event.key === 'ArrowRight' ? 1 : -1;
              if (event.shiftKey && zoomed) commitViewport(panChartViewport(view, domain, direction * 0.2));
              else stepCursor(direction);
            } else if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
              event.preventDefault();
              stepCursor(event.key === 'ArrowUp' ? 1 : -1);
            } else if (event.key === '+' || event.key === '=') {
              event.preventDefault();
              zoom(2);
            } else if (event.key === '-') {
              event.preventDefault();
              zoom(0.5);
            } else if (event.key === '0') {
              event.preventDefault();
              commitViewport(domain);
            } else if (event.key === 'Home' || event.key === 'End') {
              event.preventDefault();
              changeCursor(event.key === 'Home' ? data.domain.start : data.domain.end);
              commitViewport(domain);
            } else if (event.key === 'Escape') {
              event.preventDefault();
              if (cursor !== null) changeCursor(null);
              else onClose?.();
            }
          },
        }
      : {};
  const suffix = fullscreen ? '-fullscreen' : '';
  const inside = (seconds: number | null) =>
    seconds !== null && Number.isFinite(seconds) && seconds >= view.start && seconds <= view.end;
  return (
    <View
      ref={Platform.OS === 'web' ? chartNode : undefined}
      onLayout={Platform.OS === 'web' ? undefined : event => onChartLayout(group.id, event.nativeEvent.layout)}
      style={styles.chartRoot}
    >
      <GestureHandlerRootView style={{ flexGrow: 0, flexShrink: 0 }}>
        <View
          onLayout={event => {
            const measured = Math.max(1, event.nativeEvent.layout.width);
            setWidth(measured);
            onPlotWidthChange?.(measured);
          }}
        >
          <View style={[styles.plotHeader, !!desktopColumns && { paddingRight: 32 }]}>
            <View style={{ flex: 1 }}>
              <View style={styles.plotTitle}>
                <Text style={styles.title}>{group.metrics.length === 1 ? group.metrics[0]!.label : group.label}</Text>
                <Text style={styles.unit}>{group.unit}</Text>
              </View>
            </View>
            {group.metrics.every(metric => metric.semantics === 'ratio') && onEnergySmoothingChange && (
              <EnergySmoothingMenu
                value={energySmoothingSeconds ?? 0}
                onChange={onEnergySmoothingChange}
                testID={`monitor-energy-smoothing${suffix}`}
              />
            )}
            {ownsComparison && (
              <View style={styles.controls}>
                <Control
                  label="Set comparison A at selected time"
                  testID={`monitor-reference-set${suffix}`}
                  disabled={!canReference}
                  onPress={() => {
                    if (canReference) setReference(cursor);
                  }}
                >
                  <Text style={styles.controlText}>{referenceSeconds === null ? 'Set A' : 'Replace A'}</Text>
                </Control>
                {referenceSeconds !== null && (
                  <Control
                    label="Clear A and B comparison"
                    testID={`monitor-reference-clear${suffix}`}
                    onPress={() => setReference(null)}
                  >
                    <Text style={styles.controlText}>Clear A/B</Text>
                  </Control>
                )}
              </View>
            )}
          </View>
          <View style={styles.readings}>
            {readingRows.map(row => (
              <View key={row[0]!.metric.id} style={styles.readingRow}>
                {row.map(({ metric, selected, shown, reference, valueText, timeText, timeAnnouncement }) => {
                  return (
                    <View key={metric.id} style={styles.reading} testID={`monitor-readout-${metric.id}${suffix}`}>
                      {group.metrics.length > 1 && (
                        <View style={styles.readingLabel}>
                          <View style={[styles.dot, { backgroundColor: metric.color }]} />
                          <Text style={styles.label}>{metric.shortLabel}</Text>
                        </View>
                      )}
                      <Text
                        numberOfLines={1}
                        adjustsFontSizeToFit
                        minimumFontScale={0.75}
                        style={[styles.value, { color: historyRange || shown ? metric.color : colors.muted }]}
                      >
                        {valueText}
                        <Text style={styles.unit}>{historyRange || shown ? ` ${metric.unit}` : '\u00a0'}</Text>
                      </Text>
                      <Text
                        numberOfLines={1}
                        style={styles.sampleTime}
                        accessible={!!timeText}
                        accessibilityLabel={timeText ? timeAnnouncement : undefined}
                      >
                        {timeText || '\u00a0'}
                      </Text>
                      {isVoltage(metric) && (
                        <VoltageDetails
                          metric={metric}
                          data={data}
                          a={reference}
                          b={selected}
                          comparing={referenceSeconds !== null}
                          suffix={suffix}
                          elapsed={elapsed}
                        />
                      )}
                    </View>
                  );
                })}
                {Array.from({ length: readingColumns - row.length }, (_, index) => (
                  <View key={`space-${index}`} style={{ flex: 1 }} />
                ))}
              </View>
            ))}
          </View>
          <GestureDetector
            gesture={Platform.OS === 'web' ? gestures : nativeGestures}
            touchAction="pan-y"
            userSelect="none"
          >
            <View
              ref={surface}
              {...webProps}
              testID={`monitor-chart-${group.id}${suffix}`}
              collapsable={false}
              style={{ height }}
              accessible
              accessibilityRole="adjustable"
              accessibilityLabel={`${group.label} chart`}
              accessibilityHint="One finger inspects; tap again to clear. Two fingers pan or pinch to zoom. Swipe vertically with one finger to scroll."
              accessibilityValue={{
                text: hasSamples
                  ? `${accessibleValue}. Visible ${elapsed(view.start)} to ${elapsed(view.end)}.`
                  : accessibleValue,
              }}
              accessibilityActions={[
                { name: 'increment', label: 'Next sample' },
                { name: 'decrement', label: 'Previous sample' },
                { name: 'activate', label: 'Clear selection' },
              ]}
              onAccessibilityAction={event => {
                if (event.nativeEvent.actionName === 'increment') stepCursor(1);
                else if (event.nativeEvent.actionName === 'decrement') stepCursor(-1);
                else if (event.nativeEvent.actionName === 'activate') changeCursor(null);
              }}
            >
              {Platform.OS !== 'web' ? (
                <MonitorRaster
                  hitScene={hitScene}
                  width={width}
                  data={data}
                  group={group}
                  scale={scale}
                  laneCount={laneCount}
                  height={height}
                  interaction={interaction}
                  cursor={cursor}
                  reference={referenceSeconds}
                  tailTime={live && holdTail ? (displayTimeSeconds ?? data.nowSeconds) : undefined}
                  snappable={snappable}
                />
              ) : (
                <Svg pointerEvents="none" width="100%" height={height} viewBox={`0 0 ${width} ${height}`}>
                  <Defs>
                    <ClipPath id={clipID}>
                      <Rect x={leftInset} y={0} width={plotWidth} height={height - 24} />
                    </ClipPath>
                  </Defs>
                  {[0, 0.5, 1].map(fraction => {
                    const y = plot.y(scale.min + fraction * (scale.max - scale.min));
                    return (
                      <G key={fraction}>
                        <Line
                          x1={leftInset}
                          x2={width - rightInset}
                          y1={y}
                          y2={y}
                          stroke={colors.border}
                          strokeDasharray="2 5"
                        />
                      </G>
                    );
                  })}
                  <G clipPath={`url(#${clipID})`}>
                    {plot.paths.map(({ metric, path, isolated }) => (
                      <G key={metric.id}>
                        <Path
                          d={path}
                          transform={`matrix(${transform.scale} 0 0 1 ${transform.offset} 0)`}
                          vectorEffect="non-scaling-stroke"
                          fill="none"
                          stroke={metric.color}
                          strokeWidth={1.8}
                          strokeLinejoin="round"
                          strokeLinecap="round"
                        />
                        {isolated.map((point, index) => (
                          <Circle
                            key={index}
                            cx={displayX(point.elapsedSeconds)}
                            cy={plot.y(metricValue(metric, point.value))}
                            r={1.8}
                            fill={metric.color}
                          />
                        ))}
                      </G>
                    ))}
                    {live &&
                      holdTail &&
                      group.metrics.map(metric => {
                        const points = data.series[metric.id] ?? [],
                          last = points[points.length - 1];
                        const tail = monitorTailSeconds(
                          last,
                          displayTimeSeconds ?? data.nowSeconds,
                          metric,
                          data.liveAcquiredAt?.[metric.id],
                          data.monotonicAt,
                          data.latest?.[metric.id],
                          data.mapMonotonicSeconds,
                        );
                        if (
                          tail === null ||
                          !validMonitorPoint(last) ||
                          last.elapsedSeconds > view.end ||
                          tail < view.start
                        )
                          return null;
                        const y = plot.y(metricValue(metric, last.value));
                        return (
                          <Path
                            key={`held-${metric.id}`}
                            d={`M${displayX(last.elapsedSeconds)},${y} L${displayX(tail)},${y}`}
                            stroke={metric.color}
                            strokeWidth={1.8}
                          />
                        );
                      })}
                    {inside(referenceSeconds) && group.metrics.some(isVoltage) && (
                      <Line
                        x1={displayX(referenceSeconds!)}
                        x2={displayX(referenceSeconds!)}
                        y1={4}
                        y2={height - 26}
                        stroke={colors.muted}
                        strokeWidth={1}
                        strokeDasharray="3 4"
                      />
                    )}
                    {inside(cursor) && (
                      <Line
                        x1={displayX(cursor!)}
                        x2={displayX(cursor!)}
                        y1={4}
                        y2={height - 26}
                        stroke={colors.text}
                        strokeWidth={1}
                      />
                    )}
                    {readings.map(({ metric, selected, reference }) => (
                      <G key={`selected-${metric.id}`}>
                        {selected && inside(selected.elapsedSeconds) && (
                          <Circle
                            cx={displayX(selected.elapsedSeconds)}
                            cy={plot.y(metricValue(metric, selected.value))}
                            r={4}
                            fill={metric.color}
                            stroke={colors.surface}
                            strokeWidth={2}
                          />
                        )}
                        {isVoltage(metric) && reference && inside(reference.elapsedSeconds) && (
                          <Circle
                            cx={displayX(reference.elapsedSeconds)}
                            cy={plot.y(metricValue(metric, reference.value))}
                            r={4}
                            fill={colors.surface}
                            stroke={metric.color}
                            strokeWidth={1.5}
                          />
                        )}
                      </G>
                    ))}
                  </G>
                  {hasSamples &&
                    [0, 0.5, 1].flatMap(fraction => {
                      const value = scale.min + fraction * (scale.max - scale.min);
                      return [
                        <MonitorRasterAxis
                          key={`y-${fraction}`}
                          axis="y"
                          position={plot.y(value)}
                          text={value.toFixed(scale.decimals)}
                          width={width}
                          height={height}
                        />,
                        <MonitorRasterAxis
                          key={`x-${fraction}`}
                          axis="x"
                          position={fraction}
                          text={formatDuration(view.start + duration * fraction)}
                          width={width}
                          height={height}
                        />,
                      ];
                    })}
                </Svg>
              )}
              {!hasSamples && (
                <View
                  pointerEvents="none"
                  style={[
                    styles.emptyPlot,
                    { left: leftInset, right: rightInset, top: plot.y(scale.max), bottom: height - plot.y(scale.min) },
                  ]}
                >
                  <Text style={styles.emptyText}>{drawn ? 'No samples' : 'Loading…'}</Text>
                </View>
              )}
            </View>
          </GestureDetector>
        </View>
      </GestureHandlerRootView>
    </View>
  );
}, samePlotProps);

function VoltageDetails({
  metric,
  data,
  a,
  b,
  comparing,
  suffix,
  elapsed,
}: {
  metric: MonitorMetric;
  data: MonitorData;
  a: MonitorPoint | null;
  b: MonitorPoint | null;
  comparing: boolean;
  suffix: string;
  elapsed: (seconds: number) => string;
}) {
  const { fontScale } = useWindowDimensions();
  const statistics = data.statistics[metric.id];
  const minimum =
    statistics && (statistics.count ?? 0) > 0 && validMonitorPoint(statistics.min) ? statistics.min : null;
  const delta = formatMetricDifference(metric, a, b);
  return (
    <View style={styles.voltageDetails}>
      <Text
        style={styles.sampleTime}
        accessibilityLabel={
          minimum
            ? `Minimum ${formatMetricPoint(metric, minimum)} ${metric.unit}, ${elapsed(minimum.elapsedSeconds)} elapsed, ${minimum.timestamp}${data.statisticsViewport ? `, ${data.statisticsPending ? 'previous interval' : 'interval'} ${elapsed(data.statisticsViewport.start)} to ${elapsed(data.statisticsViewport.end)}` : ''}`
            : 'Minimum unavailable'
        }
      >
        {minimum
          ? `Min ${formatMetricPoint(metric, minimum)} ${metric.unit} · ${elapsed(minimum.elapsedSeconds)}`
          : 'Min —'}
      </Text>
      {data.statisticsViewport && (
        <Text style={styles.sampleTime}>
          {data.statisticsPending ? 'Previous range' : 'Range'} {formatDuration(data.statisticsViewport.start)}–
          {formatDuration(data.statisticsViewport.end)}
        </Text>
      )}
      {comparing && (
        <View testID={`monitor-compare-${metric.id}${suffix}`}>
          <Text
            numberOfLines={1}
            adjustsFontSizeToFit
            minimumFontScale={0.75}
            style={[styles.value, { color: metric.color }]}
            accessibilityLabel={delta === '—' ? 'Comparison unavailable' : `B minus A ${delta} ${metric.unit}`}
          >
            Δ {delta} {metric.unit}
          </Text>
          {(
            [
              ['A', a],
              ['B', b],
            ] as const
          ).map(([label, point]) => (
            <Text
              key={label}
              numberOfLines={2}
              style={[styles.sampleTime, { minHeight: 30 * fontScale }]}
              accessibilityLabel={`${label}: ${point ? `${formatMetricPoint(metric, point)} ${metric.unit}, ${elapsed(point.elapsedSeconds)} elapsed, ${point.timestamp}` : 'No sample'}`}
            >
              {label}{' '}
              {point ? `${formatMetricPoint(metric, point)} ${metric.unit} · ${elapsed(point.elapsedSeconds)}` : '—'}
            </Text>
          ))}
        </View>
      )}
    </View>
  );
}

function Control({
  label,
  testID,
  onPress,
  disabled = false,
  children,
}: {
  label: string;
  testID: string;
  onPress: () => void;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled }}
      testID={testID}
      disabled={disabled}
      onPress={onPress}
      style={[styles.control, disabled && styles.disabled]}
    >
      {children}
    </Pressable>
  );
}
const styles = StyleSheet.create({
  collection: { gap: 10 },
  toolbar: {
    minHeight: 44,
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    columnGap: 4,
  },
  range: {
    color: colors.muted,
    fontSize: type.label,
    flexGrow: 1,
    flexBasis: 112,
    paddingVertical: 8,
    fontVariant: ['tabular-nums'],
  },
  controls: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'flex-end' },
  control: { minHeight: 44, minWidth: 44, paddingHorizontal: 8, justifyContent: 'center', alignItems: 'center' },
  disabled: { opacity: 0.3 },
  controlText: { color: colors.text, fontSize: type.caption },
  zoomText: { color: colors.text, fontSize: type.title },
  chartRoot: {
    flexGrow: 0,
    flexShrink: 0,
    flexBasis: 'auto',
    backgroundColor: colors.bg,
    borderColor: colors.border,
    borderWidth: 1,
    borderRadius: 10,
    padding: 12,
  },
  emptyPlot: { position: 'absolute', alignItems: 'center', justifyContent: 'center' },
  emptyText: {
    color: colors.muted,
    backgroundColor: colors.bg,
    paddingHorizontal: 8,
    fontSize: type.caption,
    textAlign: 'center',
  },
  plotHeader: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    columnGap: 8,
    marginBottom: 8,
  },
  plotTitle: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'baseline', gap: 6, paddingVertical: 4 },
  title: { color: colors.text, fontWeight: '600', fontSize: type.caption },
  unit: { color: colors.muted, fontSize: type.label, fontWeight: '400' },
  readings: { gap: 12, marginBottom: 8 },
  readingRow: { flexDirection: 'row', gap: 12 },
  reading: { flex: 1, minWidth: 0, gap: 2 },
  readingLabel: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  dot: { width: 6, height: 6, borderRadius: 3, alignSelf: 'center' },
  label: { color: colors.muted, fontSize: type.label, lineHeight: lineHeight.label, flexShrink: 1 },
  value: { fontSize: type.body, lineHeight: lineHeight.body, fontWeight: '600', fontVariant: ['tabular-nums'] },
  sampleTime: {
    color: colors.muted,
    fontSize: type.label,
    lineHeight: lineHeight.label,
    fontVariant: ['tabular-nums'],
  },
  voltageDetails: { gap: 2 },
  fullscreen: { flex: 1, backgroundColor: colors.bg },
  fullscreenContent: { paddingHorizontal: 16, paddingVertical: 8 },
});
