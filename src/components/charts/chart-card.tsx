import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Platform, StyleSheet, Text, View, useWindowDimensions, type LayoutRectangle } from 'react-native';
import { Gesture, GestureDetector, GestureHandlerRootView } from 'react-native-gesture-handler';
import { useSharedValue } from 'react-native-reanimated';
import { kit, type MetricId, type TemperatureUnit, type UnitSystem } from '../../../modules/power-log';
import type { EnergySmoothingSeconds } from '../energy-smoothing-menu';
import type { ChartFeedState, ChartSeries, LatestCell } from '../../features/charts/chart-feed-model';
import { EnergySmoothingMenu } from '../energy-smoothing-menu';
import { colors, lineHeight, Pressable, type } from '../ui';
import { isVoltage, type ChartGroup, type ChartMetric } from './chart-groups';
import type { ChartMarks, ChartTail } from './chart-renderer.types';
import {
  chartScale,
  hasSamples as anySamples,
  latestPoint,
  originalPoint,
  seriesFrame,
  stepCursor,
  type ChartPoint,
  type StatisticsMap,
} from './chart-series';
import { beginChartTransform, moveChartTransform, type ChartTransform } from './chart-touch';
import {
  CHART_INSET,
  clampChartViewport,
  isChartZoomed,
  panChartViewport,
  zoomChartViewport,
  type ChartViewport,
} from './chart-viewport';
import type { HitScene } from './hit-test';
import { NativeRasterChart } from './native-raster-chart';
import { SvgChart } from './svg-chart';
import type { ChartInteraction } from './use-chart-interaction';
import { useNativeChartGestures } from './use-native-chart-gestures';

const leftInset = CHART_INSET.left;
const rightInset = CHART_INSET.right;

const preciseElapsed = (seconds: number) => kit.formatElapsedPrecise(seconds * 1e6);
const duration = (seconds: number) => kit.formatDuration(seconds * 1e6);
const timestamp = (point: ChartPoint) => (point.utcUs === null ? '' : kit.formatIsoTimestamp(point.utcUs));

function followingPoint(cell: LatestCell | undefined, series: ChartSeries | undefined): ChartPoint | null {
  if (!cell) return latestPoint(series);
  return cell.value === null ? null : { atS: (cell.elapsedUs ?? 0) / 1e6, value: cell.value, utcUs: null };
}

const equal = (a: unknown, b: unknown): boolean =>
  a === b ||
  (!!a &&
    !!b &&
    typeof a === 'object' &&
    typeof b === 'object' &&
    Object.keys(a).length === Object.keys(b).length &&
    Object.entries(a).every(([key, value]) => (b as Record<string, unknown>)[key] === value));

/** Whether the feed changed for these metrics; other cards' updates leave this card as it is. */
function sameFeed(a: ChartFeedState, b: ChartFeedState, ids: readonly MetricId[]): boolean {
  if (a === b) return true;
  const values = <T extends { values: Readonly<Partial<Record<MetricId, unknown>>> }>(x: T | null, y: T | null) =>
    x === y ||
    (!!x &&
      !!y &&
      equal({ ...x, values: null }, { ...y, values: null }) &&
      ids.every(id => x.values[id] === y.values[id]));
  return (
    a.sourceKey === b.sourceKey &&
    a.following === b.following &&
    values(a.statistics, b.statistics) &&
    values(a.cursor, b.cursor) &&
    values(a.reference, b.reference) &&
    ids.every(
      id =>
        a.series[id] === b.series[id] &&
        equal(a.drawings[id], b.drawings[id]) &&
        equal(a.latest[id], b.latest[id]) &&
        equal(a.tails[id], b.tails[id]),
    )
  );
}

export function rangeReadout(
  statistics: ChartFeedState['statistics'],
  metric: ChartMetric,
  view: ChartViewport,
): { value: string; detail: string } {
  const matches =
    !!statistics &&
    !statistics.pending &&
    Math.abs(statistics.range.startS - view.start) < 1e-6 &&
    Math.abs(statistics.range.endS - view.end) < 1e-6;
  const stats = matches ? statistics.values[metric.id] : undefined;
  if (metric.semantics === 'ratio')
    return { value: `avg ${metric.format(stats?.ratio)}`, detail: stats?.partial ? 'Partial data' : '' };
  if (metric.semantics === 'cumulative' || metric.semantics === 'distance')
    return { value: metric.format(stats?.total), detail: '' };
  const mean = metric.allowsMean ? stats?.average : null;
  return {
    value:
      mean != null && Number.isFinite(mean) ? `avg ${metric.format(mean)}` : `min ${metric.exact(stats?.min?.value)}`,
    detail: matches ? `max ${metric.exact(stats?.max?.value)} ${metric.unit}` : '',
  };
}

type CardProps = {
  feed: ChartFeedState;
  group: ChartGroup;
  view: ChartViewport;
  domain: ChartViewport;
  height: number;
  laneCount: number;
  units: UnitSystem;
  temperature: TemperatureUnit;
  interaction: ChartInteraction;
  live: boolean;
  holdTail: boolean;
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
const layoutProps = new Set<keyof CardProps>([
  'group',
  'height',
  'laneCount',
  'ownsComparison',
  'fullscreen',
  'desktopColumns',
  'onEnergySmoothingChange',
  'onChartLayout',
  'onChartNode',
  'onChartFocus',
]);
/** Off-screen charts skip every update that cannot change their size. */
function sameCardProps(previous: CardProps, next: CardProps) {
  const hidden = !next.onScreen;
  return (Object.keys(next) as (keyof CardProps)[]).every(
    key =>
      previous[key] === next[key] ||
      (hidden && !layoutProps.has(key)) ||
      (key === 'feed' &&
        sameFeed(
          previous.feed,
          next.feed,
          next.group.metrics.map(metric => metric.id),
        )),
  );
}

export const ChartCard = memo(function ChartCard({
  feed,
  group,
  view,
  domain,
  height,
  laneCount,
  units,
  temperature,
  interaction,
  live,
  holdTail,
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
}: CardProps) {
  const [width, setWidth] = useState(360);
  const { fontScale } = useWindowDimensions();
  const readingColumns = Math.max(1, Math.min(group.metrics.length, Math.floor((width + 12) / (120 * fontScale + 12))));
  const surface = useRef<View>(null);
  const zoomed = isChartZoomed(view, domain);
  const plotWidth = Math.max(1, width - leftInset - rightInset);
  const span = view.end - view.start;
  const cursor = feed.cursor?.atS ?? null;
  const referenceSeconds = feed.reference?.atS ?? null;
  const statistics: StatisticsMap | undefined = feed.statistics?.values;
  const {
    min: scaleMin,
    max: scaleMax,
    decimals,
  } = useMemo(() => chartScale(group.metrics, feed.series, statistics), [group.metrics, feed.series, statistics]);
  const scale = useMemo(() => ({ min: scaleMin, max: scaleMax, decimals }), [scaleMin, scaleMax, decimals]);
  const hasSamples = useMemo(() => anySamples(group.metrics, feed.series), [group.metrics, feed.series]);
  const frame = useMemo(() => seriesFrame(group.metrics, feed.series), [group.metrics, feed.series]);
  const drawn = group.metrics.some(metric => feed.drawings[metric.id]);
  const snappable = group.metrics.every(metric => feed.drawings[metric.id]?.current);
  const elapsed = live ? preciseElapsed : duration;
  const historyRange = !live && cursor === null;
  const historical = !live || !feed.following;
  const readings = group.metrics.map(metric => {
    const selected = originalPoint(feed.cursor?.values[metric.id]?.original);
    const latest = historical
      ? latestPoint(feed.series[metric.id], view)
      : followingPoint(feed.latest[metric.id], feed.series[metric.id]);
    const shown = cursor !== null ? selected : latest;
    const range = live ? null : rangeReadout(feed.statistics, metric, view);
    const rangeValue = range?.value ?? '';
    const rangeDetail = range?.detail ?? '';
    const pointValue = shown ? metric.exact(shown.value) : cursor === null ? 'Unavailable' : 'No sample';
    const pointTime = shown && cursor !== null ? elapsed(shown.atS) : '';
    const pointAnnouncement = shown
      ? `${elapsed(shown.atS)} elapsed${timestamp(shown) ? `, ${timestamp(shown)}` : ''}`
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
      reference: originalPoint(feed.reference?.values[metric.id]?.original),
    };
  });
  const marks = useMemo<ChartMarks[]>(
    () =>
      group.metrics.map(metric => ({
        metric,
        cursor: originalPoint(feed.cursor?.values[metric.id]?.original),
        reference: originalPoint(feed.reference?.values[metric.id]?.original),
      })),
    [group.metrics, feed.cursor, feed.reference],
  );
  const tails = useMemo<ChartTail[]>(
    () =>
      live && holdTail
        ? group.metrics.flatMap(metric => {
            const tail = feed.tails[metric.id];
            return tail ? [{ metric, ...tail }] : [];
          })
        : [],
    [live, holdTail, group.metrics, feed.tails],
  );
  const readingRows = Array.from({ length: Math.ceil(readings.length / readingColumns) }, (_, row) =>
    readings.slice(row * readingColumns, (row + 1) * readingColumns),
  );
  const accessibleValue = readings
    .map(({ metric, shown, rangeValue, rangeDetail, timeAnnouncement }) =>
      historyRange
        ? `${metric.label}: ${rangeValue} ${metric.unit}${rangeDetail ? `. ${rangeDetail}` : ''}`
        : shown
          ? `${metric.label} ${metric.exact(shown.value)} ${metric.unit}, ${timeAnnouncement}`
          : `${metric.label}: ${cursor === null ? 'Unavailable' : 'No sample'}`,
    )
    .join('. ');
  const selectAt = (locationX: number) => {
    if (!Number.isFinite(locationX)) return;
    changeCursor(view.start + Math.max(0, Math.min(1, (locationX - leftInset) / plotWidth)) * span);
  };
  const step = (direction: -1 | 1) => {
    const next = stepCursor(group.metrics, feed.series, domain, cursor, direction, view);
    changeCursor(next);
    if (next !== null && (next < view.start || next > view.end))
      commitViewport(clampChartViewport({ start: next - span / 2, end: next + span / 2 }, domain));
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
  const hitScene = useSharedValue<HitScene | null>(null);
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
              else step(direction);
            } else if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
              event.preventDefault();
              step(event.key === 'ArrowUp' ? 1 : -1);
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
              changeCursor(event.key === 'Home' ? domain.start : domain.end);
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
  const plotY = (value: number) => 8 + ((scale.max - value) / (scale.max - scale.min)) * (height - 34);
  const rendererProps = {
    sourceId: feed.sourceKey,
    revision: String(
      group.metrics.map(metric => feed.drawings[metric.id]?.revision).find(revision => revision !== undefined) ?? '',
    ),
    group,
    series: feed.series,
    frame,
    view,
    scale,
    laneCount,
    width,
    height,
    units,
    temperature,
    interaction,
    hitScene,
    cursor,
    reference: referenceSeconds,
    cursorPending: feed.cursor?.pending ?? false,
    marks,
    tails,
    snappable,
    hasSamples,
  };
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
                {row.map(({ metric, selected, shown, reference, valueText, timeText, timeAnnouncement }) => (
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
                      <Text style={styles.unit}>{historyRange || shown ? ` ${metric.unit}` : ' '}</Text>
                    </Text>
                    <Text
                      numberOfLines={1}
                      style={styles.sampleTime}
                      accessible={!!timeText}
                      accessibilityLabel={timeText ? timeAnnouncement : undefined}
                    >
                      {timeText || ' '}
                    </Text>
                    {isVoltage(metric.id) && (
                      <VoltageDetails
                        metric={metric}
                        statistics={feed.statistics}
                        a={reference}
                        b={selected}
                        comparing={referenceSeconds !== null}
                        suffix={suffix}
                        elapsed={elapsed}
                      />
                    )}
                  </View>
                ))}
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
                if (event.nativeEvent.actionName === 'increment') step(1);
                else if (event.nativeEvent.actionName === 'decrement') step(-1);
                else if (event.nativeEvent.actionName === 'activate') changeCursor(null);
              }}
            >
              {Platform.OS === 'web' ? <SvgChart {...rendererProps} /> : <NativeRasterChart {...rendererProps} />}
              {!hasSamples && (
                <View
                  pointerEvents="none"
                  style={[
                    styles.emptyPlot,
                    { left: leftInset, right: rightInset, top: plotY(scale.max), bottom: height - plotY(scale.min) },
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
}, sameCardProps);

function VoltageDetails({
  metric,
  statistics,
  a,
  b,
  comparing,
  suffix,
  elapsed,
}: {
  metric: ChartMetric;
  statistics: ChartFeedState['statistics'];
  a: ChartPoint | null;
  b: ChartPoint | null;
  comparing: boolean;
  suffix: string;
  elapsed: (seconds: number) => string;
}) {
  const { fontScale } = useWindowDimensions();
  const minimum = originalPoint(statistics?.values[metric.id]?.min);
  const range = statistics?.range;
  const delta = metric.difference(a?.value, b?.value);
  const described = (point: ChartPoint) =>
    `${elapsed(point.atS)} elapsed${timestamp(point) ? `, ${timestamp(point)}` : ''}`;
  return (
    <View style={styles.voltageDetails}>
      <Text
        style={styles.sampleTime}
        accessibilityLabel={
          minimum
            ? `Minimum ${metric.exact(minimum.value)} ${metric.unit}, ${described(minimum)}${range ? `, ${statistics.pending ? 'previous interval' : 'interval'} ${elapsed(range.startS)} to ${elapsed(range.endS)}` : ''}`
            : 'Minimum unavailable'
        }
      >
        {minimum ? `Min ${metric.exact(minimum.value)} ${metric.unit} · ${elapsed(minimum.atS)}` : 'Min —'}
      </Text>
      {range && (
        <Text style={styles.sampleTime}>
          {statistics.pending ? 'Previous range' : 'Range'} {duration(range.startS)}–{duration(range.endS)}
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
              accessibilityLabel={`${label}: ${point ? `${metric.exact(point.value)} ${metric.unit}, ${described(point)}` : 'No sample'}`}
            >
              {label} {point ? `${metric.exact(point.value)} ${metric.unit} · ${elapsed(point.atS)}` : '—'}
            </Text>
          ))}
        </View>
      )}
    </View>
  );
}

export function Control({
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
  children: React.ReactNode;
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

export const styles = StyleSheet.create({
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
