import type { EnergySmoothingSeconds } from '../../core/energy-per-distance';
import { withEnergySmoothing } from '../../services/energy-per-distance-monitor';
import { isConsumptionMetric } from '../../core/consumption';
import { memo, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Platform, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { ModalDialog } from '../../components/modal-dialog';
import { MonitorCharts } from '../../components/monitor-charts';
import { Icon } from '../../components/icon';
import { Body, Card, colors, Label, Metric, Pressable, type, lineHeight } from '../../components/ui';
import {
  controllerSpeedPreferenceId,
  resolveMonitorMetrics,
  currentMonitorPoint,
  formatMetric,
  formatMetricPoint,
  metricAllowsMean,
  metricById,
  type MonitorRange,
  type MonitorSource,
  type MonitorViewId,
} from '../../core/monitor';
import { groupMonitorMetrics } from '../../core/monitor-chart';
import { monitorChartEstimate, monitorChartVisibility, monitorGroupMetrics } from '../../core/monitor-visibility';
import { useMonitorPreferences } from '../../services/monitor-preferences';
import { MonitorEditor } from './monitor-editor';
import { useMonitorData } from './use-monitor-data';
import { StableLabel } from '../../components/stable-label';
import { monitorSourceStatus } from './monitor-status';

const ranges: { value: MonitorRange; label: string }[] = [
  { value: 30, label: '30 sec' },
  { value: 120, label: '2 min' },
  { value: 600, label: '10 min' },
  { value: 'all', label: 'Whole ride' },
];
const viewIds: MonitorViewId[] = ['ride', 'battery', 'temperature'];
const statusLabels = ['Chart error', 'Settings error', 'Awaiting data', 'Syncing ride…', 'No measurements'];

export const MonitorPanel = memo(function MonitorPanel(props: {
  source: MonitorSource;
  bikeAvailable?: boolean;
  embedded?: boolean;
  emptyState?: ReactNode;
}) {
  return <MonitorPanelContent key={props.source.key} {...props} />;
});
function MonitorPanelContent({
  source,
  bikeAvailable = true,
  embedded = false,
  emptyState,
}: {
  source: MonitorSource;
  bikeAvailable?: boolean;
  embedded?: boolean;
  emptyState?: ReactNode;
}) {
  void embedded;
  const {
    preferences,
    updateView,
    selectView,
    resetView,
    resetSettings,
    resetOffered,
    setHistoryRange,
    error: preferencesError,
  } = useMonitorPreferences();
  const view =
    preferences.views[source.live ? preferences.activeView : (preferences.historyView ?? preferences.activeView)];
  const [editor, setEditor] = useState(false),
    [menu, setMenu] = useState<'view' | 'range'>('view');
  const [menuOpen, setMenuOpen] = useState(false);
  const openMenu = (value: 'view' | 'range') => {
    setMenu(value);
    setMenuOpen(true);
  };
  const [numbersWidth, setNumbersWidth] = useState(330);
  const [plotWidth, setPlotWidth] = useState(360);
  const size = useWindowDimensions();
  const desktop = Platform.OS === 'web' && size.width >= 760;
  const sidebar = desktop && size.width >= 1100 && view.charts.length > 0 && view.numbers.length > 0;
  const range = source.live ? view.range : preferences.historyRange;
  const metrics = useMemo(() => [...new Set([...view.numbers, ...view.charts])], [view.numbers, view.charts]);
  const [energySmoothingSeconds, setEnergySmoothingSeconds] = useState<EnergySmoothingSeconds>(0);
  const displaySource = useMemo(
    () => withEnergySmoothing(source, energySmoothingSeconds),
    [source, energySmoothingSeconds],
  );
  const monitor = useMonitorData(displaySource, metrics, range, plotWidth);
  const rawSpeedFallback =
    !!monitor.description?.availableMetrics.includes('speedRaw') &&
    !monitor.description.availableMetrics.includes('controllerSpeedMps');
  const numberIds = useMemo(
    () => resolveMonitorMetrics(view.numbers, rawSpeedFallback ? ['speedRaw'] : []),
    [view.numbers, rawSpeedFallback],
  );
  const chartIds = useMemo(
    () => resolveMonitorMetrics(view.charts, rawSpeedFallback ? ['speedRaw'] : []),
    [view.charts, rawSpeedFallback],
  );
  const chartKey = chartIds.join(',');
  const firstScreen = useMemo(() => {
    const groups = groupMonitorMetrics(chartIds, preferences.speedUnit);
    const ids = groups.map(group => group.id);
    return monitorGroupMetrics(
      groups,
      monitorChartVisibility(ids, new Map(), size.height, monitorChartEstimate(140)).read,
    );
  }, [chartIds, preferences.speedUnit, size.height]);
  const [reportedMetrics, setReportedMetrics] = useState<{ charts: string; metrics: string[] }>();
  const visibleMetrics = reportedMetrics?.charts === chartKey ? reportedMetrics.metrics : firstScreen;
  const { setVisibleMetrics } = monitor;
  useEffect(() => {
    setVisibleMetrics(visibleMetrics);
  }, [setVisibleMetrics, visibleMetrics]);
  const reportVisibleMetrics = useCallback(
    (visible: string[]) => setReportedMetrics({ charts: chartKey, metrics: visible }),
    [chartKey],
  );
  const columns = sidebar
    ? 1
    : Math.max(1, Math.min(numbersWidth >= 600 ? 4 : 2, Math.floor((numbersWidth + 14) / (126 * size.fontScale + 14))));
  const sourceStatus = monitorSourceStatus(monitor.description, source.live);
  const status = [
    monitor.error,
    preferencesError,
    sourceStatus?.detail,
    Object.values(monitor.deferred).some(Boolean) ? 'Charts catching up…' : null,
  ]
    .filter(Boolean)
    .join('\n');
  const statusLabel = monitor.error ? 'Chart error' : preferencesError ? 'Settings error' : (sourceStatus?.label ?? '');
  const chooseRange = (value: MonitorRange) => {
    if (source.live) updateView(view.id, { range: value });
    else setHistoryRange(value);
    monitor.resume();
    setMenuOpen(false);
  };
  const header = (
    <View
      key={size.fontScale}
      testID="monitor-header"
      style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 6, minHeight: 44 }}
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Choose monitoring view, ${view.name}${status ? `. Monitoring status: ${status}` : ''}`}
        accessibilityHint={status ? 'Opens views and the full status message' : undefined}
        testID="monitor-view-picker"
        onPress={() => openMenu('view')}
        style={{
          flex: 1,
          minWidth: Math.min(120 * size.fontScale, size.width - 48),
          minHeight: 44,
          gap: 2,
          justifyContent: 'center',
        }}
      >
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
          <Text style={{ color: colors.text, fontSize: type.title, fontWeight: '600' }}>{view.name}</Text>
          <Icon name="chevron" size={14} />
        </View>
        <StableLabel
          value={statusLabel}
          variants={statusLabels}
          testID={statusLabel ? 'monitor-status' : undefined}
          accessibilityRole={monitor.error || preferencesError ? 'alert' : undefined}
          style={{
            lineHeight: lineHeight.label,
            fontSize: type.label,
            color: monitor.error || preferencesError ? colors.red : colors.muted,
          }}
        />
      </Pressable>
      {!monitor.following && source.live && (
        <Pressable
          accessibilityRole="button"
          onPress={monitor.resume}
          testID="monitor-live"
          style={{ minHeight: 44, paddingHorizontal: 8, justifyContent: 'center' }}
        >
          <Text style={{ color: colors.accent, fontSize: type.caption, fontWeight: '600' }}>● Live</Text>
        </Pressable>
      )}
      {desktop && !!view.charts.length && (
        <View
          accessibilityRole="radiogroup"
          accessibilityLabel="Maximum chart columns, automatically reduced to fit the window"
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            borderRadius: 7,
            padding: 3,
            backgroundColor: colors.bg,
            marginRight: 12,
          }}
        >
          <Text style={{ color: colors.muted, fontSize: type.label, paddingHorizontal: 8 }}>Columns</Text>
          {([1, 2, 3] as const).map(count => (
            <Pressable
              key={count}
              accessibilityRole="radio"
              accessibilityLabel={`${count} ${count === 1 ? 'column' : 'columns'}`}
              accessibilityState={{ checked: view.webColumns === count }}
              aria-checked={view.webColumns === count}
              onPress={() => updateView(view.id, { webColumns: count })}
              style={{
                minWidth: 38,
                minHeight: 32,
                paddingHorizontal: 10,
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 3,
                borderRadius: 5,
                backgroundColor: view.webColumns === count ? colors.surfaceRaised : 'transparent',
              }}
            >
              {Array.from({ length: count }, (_, index) => (
                <View
                  key={index}
                  style={{
                    width: count === 1 ? 17 : count === 2 ? 8 : 5,
                    height: 14,
                    borderWidth: 1,
                    borderRadius: 2,
                    borderColor: view.webColumns === count ? colors.accent : colors.muted,
                  }}
                />
              ))}
            </Pressable>
          ))}
        </View>
      )}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Chart time range, ${ranges.find(item => item.value === range)?.label}`}
        testID="monitor-range-picker"
        onPress={() => openMenu('range')}
        style={{ minHeight: 44, paddingHorizontal: 8, flexDirection: 'row', alignItems: 'center', gap: 4 }}
      >
        <Text style={{ color: colors.muted, fontSize: type.caption }}>
          {monitor.following ? ranges.find(item => item.value === range)?.label : 'Custom'}
        </Text>
        <Icon name="chevron" size={14} />
      </Pressable>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Edit ${view.name} view`}
        testID="monitor-edit"
        onPress={() => setEditor(true)}
        style={{ width: 44, height: 44, alignItems: 'center', justifyContent: 'center' }}
      >
        <Icon name="layout" />
      </Pressable>
    </View>
  );
  const numbers = !!view.numbers.length && (
    <View
      testID="monitor-numbers"
      onLayout={event => setNumbersWidth(event.nativeEvent.layout.width)}
      style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 14, paddingBottom: view.charts.length && !sidebar ? 6 : 0 }}
    >
      {numberIds.map(id => {
        const metric = metricById(id, preferences.speedUnit)!;
        const point = currentMonitorPoint(
          metric.semantics === 'ratio' ? monitor.averages?.[id] : monitor.latest?.[id],
          source.live && metric.semantics !== 'distance' && metric.semantics !== 'ratio' && !isConsumptionMetric(id),
          monitor.monotonicAt,
          metric.gapSeconds,
          monitor.liveAcquiredAt[id],
          monitor.mapMonotonicSeconds,
        );
        const bike =
          metric.semantics !== 'distance' &&
          metric.semantics !== 'ratio' &&
          !isConsumptionMetric(id) &&
          ![
            'heartRateBpm',
            'speedMps',
            'distanceMeters',
            'altitudeMeters',
            'horizontalAccuracyM',
            'verticalAccuracyM',
            'courseDegrees',
            'activeEnergyKcal',
            'basalEnergyKcal',
          ].includes(id);
        const unavailable = !source.live && !!monitor.description && !monitor.description.availableMetrics.includes(id);
        return (
          <View
            key={id}
            testID={`monitor-number-${id}`}
            style={{
              width: Math.max(0, (numbersWidth - 14 * (columns - 1)) / columns),
              opacity: unavailable ? 0.55 : 1,
              ...(sidebar && { paddingVertical: 12 }),
            }}
          >
            <Metric
              label={metric.label}
              value={formatMetricPoint(metric, source.live && bike && !bikeAvailable ? null : point)}
              unit={metric.semantics === 'ratio' ? `${metric.unit} · ride avg` : metric.unit}
              color={metric.color}
              style={{ flexBasis: 'auto' }}
            />
          </View>
        );
      })}
    </View>
  );
  const charts = (
    <>
      {monitor.data && (!!view.charts.length || emptyState) && (
        <MonitorCharts
          energySmoothingSeconds={energySmoothingSeconds}
          onEnergySmoothingChange={setEnergySmoothingSeconds}
          emptyState={emptyState}
          resetKey={monitor.interactionVersion}
          data={monitor.data}
          metricIds={chartIds}
          speedUnit={preferences.speedUnit}
          viewport={monitor.viewport}
          onViewportChange={monitor.changeViewport}
          cursorSeconds={monitor.cursorSeconds}
          onCursorChange={monitor.inspect}
          referenceSeconds={monitor.referenceSeconds}
          onReferenceChange={monitor.setReference}
          live={source.live}
          following={monitor.following}
          holdTail={bikeAvailable}
          displayTimeSeconds={source.live ? monitor.nowSeconds : undefined}
          desktopColumns={desktop ? view.webColumns : undefined}
          availableHeight={desktop ? size.height - (embedded ? 420 : sidebar ? 246 : 340) : undefined}
          onReorderCharts={ids => updateView(view.id, { charts: ids.map(controllerSpeedPreferenceId) })}
          onPlotWidthChange={setPlotWidth}
          onVisibleMetricsChange={reportVisibleMetrics}
        />
      )}
      {!monitor.data && emptyState && <Card>{emptyState}</Card>}
      {!monitor.data && !emptyState && !monitor.error && monitor.description?.outcome !== 'unavailable' && (
        <Body muted>Loading charts…</Body>
      )}
      {monitor.referenceSeconds !== null && monitor.cursorSeconds !== null && (
        <View style={{ gap: 4 }}>
          {monitor.loading.comparison ? (
            <Body muted>Loading comparison…</Body>
          ) : (
            chartIds
              .filter(id => monitor.intervalStatistics[id])
              .map(id => {
                const metric = metricById(id, preferences.speedUnit)!,
                  stats = monitor.intervalStatistics[id]!;
                if (
                  metric.semantics === 'distance' ||
                  metric.semantics === 'cumulative' ||
                  metric.semantics === 'ratio'
                )
                  return (
                    <Text key={id} style={{ color: colors.muted, fontSize: type.caption }}>
                      {metric.label} A–B · {formatMetric(metric, stats.ratio ?? stats.amount ?? stats.distance)}{' '}
                      {metric.unit}
                      {metric.semantics === 'ratio' && stats.partial ? ' · partial' : ''}
                    </Text>
                  );
                if (!metricAllowsMean(metric)) return null;
                return (
                  <Text key={id} style={{ color: colors.muted, fontSize: type.caption }}>
                    {metric.label} A–B · min {formatMetricPoint(metric, stats.min)} {metric.unit} · avg{' '}
                    {formatMetric(metric, stats.sampleMean)} {metric.unit}
                  </Text>
                );
              })
          )}
        </View>
      )}
      {!view.numbers.length && !view.charts.length && (
        <Pressable
          accessibilityRole="button"
          onPress={() => setEditor(true)}
          style={{ minHeight: 44, justifyContent: 'center' }}
        >
          <Text style={{ color: colors.accent }}>Add measurements</Text>
        </Pressable>
      )}
    </>
  );
  const content = (
    <>
      {header}
      {sidebar ? (
        <View
          testID="monitor-desktop"
          style={{ flexDirection: 'row', gap: 20, borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 16 }}
        >
          {!!view.numbers.length && (
            <View style={{ width: sidebar ? 208 : '100%', gap: 12 }}>
              <Label>Readings</Label>
              {numbers}
            </View>
          )}
          <View style={{ flex: 1, minWidth: 0, gap: 12 }}>{charts}</View>
        </View>
      ) : (
        <>
          {numbers}
          {charts}
        </>
      )}
      <MonitorEditor
        speedUnit={preferences.speedUnit}
        visible={editor}
        view={view}
        onChange={changes => updateView(view.id, changes)}
        onReset={() => resetView(view.id)}
        onClose={() => setEditor(false)}
      />
      <ModalDialog
        visible={menuOpen}
        onClose={() => setMenuOpen(false)}
        closeLabel="Close menu"
        testID="monitor-menu-dialog"
        placement="center"
        style={{
          backgroundColor: colors.surface,
          borderWidth: 1,
          borderColor: colors.border,
          borderRadius: 12,
          padding: 8,
          width: '100%',
          maxWidth: 380,
          alignSelf: 'center',
        }}
      >
        <ScrollView style={{ flexShrink: 1 }} keyboardShouldPersistTaps="handled">
          {!!status && (
            <Text
              testID="monitor-status-details"
              style={{
                padding: 14,
                color: monitor.error || preferencesError ? colors.red : colors.muted,
                fontSize: type.body,
                lineHeight: lineHeight.body,
              }}
            >
              {status}
            </Text>
          )}
          {resetOffered && (
            <Pressable
              accessibilityRole="button"
              testID="monitor-reset-settings"
              onPress={() => {
                resetSettings();
                setMenuOpen(false);
              }}
              style={{ minHeight: 50, paddingHorizontal: 14, justifyContent: 'center' }}
            >
              <Text style={{ color: colors.accent }}>Reset settings</Text>
            </Pressable>
          )}
          {menu === 'view'
            ? viewIds.map(id => (
                <Pressable
                  key={id}
                  accessibilityRole="button"
                  accessibilityState={{ selected: view.id === id }}
                  onPress={() => {
                    selectView(id, source.live ? 'live' : 'history');
                    monitor.resume();
                    setMenuOpen(false);
                  }}
                  style={{ minHeight: 50, paddingHorizontal: 14, justifyContent: 'center' }}
                >
                  <Text style={{ color: view.id === id ? colors.accent : colors.text }}>
                    {preferences.views[id].name}
                    {view.id === id ? '  ✓' : ''}
                  </Text>
                </Pressable>
              ))
            : ranges.map(item => (
                <Pressable
                  key={item.value}
                  accessibilityRole="button"
                  accessibilityState={{ selected: range === item.value }}
                  onPress={() => chooseRange(item.value)}
                  style={{ minHeight: 50, paddingHorizontal: 14, justifyContent: 'center' }}
                >
                  <Text style={{ color: range === item.value ? colors.accent : colors.text }}>
                    {item.label}
                    {range === item.value ? '  ✓' : ''}
                  </Text>
                </Pressable>
              ))}
        </ScrollView>
      </ModalDialog>
    </>
  );
  return <View style={{ gap: 12 }}>{content}</View>;
}
