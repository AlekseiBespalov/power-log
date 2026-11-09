import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { Platform, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { kit, type ChartRange, type MetricId, type ViewId } from '../../../modules/power-log';
import { ChartCards } from '../../components/charts/chart-cards';
import { chartGroups, chartMetrics } from '../../components/charts/chart-groups';
import { chartEstimate, chartVisibility, groupMetrics } from '../../components/charts/chart-visibility';
import type { ChartSnap } from '../../components/charts/hit-test';
import { Icon } from '../../components/icon';
import { ModalDialog } from '../../components/modal-dialog';
import { NoticeBanner } from '../../components/notice-banner';
import { StableLabel } from '../../components/stable-label';
import { Body, Card, colors, Label, lineHeight, Metric, Pressable, type } from '../../components/ui';
import { useModelSnapshot, useScreenModel, useUiEnv } from '../../components/use-screen-model';
import { ChartsEditor } from './charts-editor';
import { isTotal, tileValue } from './tile-value';
import { ChartsPanelModel, type ChartsPanelInput, type Smoothing } from './charts-panel-model';

const statusLabels = ['Chart error', 'Settings error', 'Awaiting data', 'Syncing ride…', 'No measurements'];
export function ChartsPanel(props: { input: ChartsPanelInput; embedded?: boolean; emptyState?: ReactNode }) {
  const { source, scope } = props.input;
  return <ChartsPanelContent key={`${scope}:${JSON.stringify(source)}`} {...props} />;
}

const snapAnchor = (snap: ChartSnap | null | undefined) =>
  snap ? { metric: snap.metric as MetricId, elapsedUs: Number(snap.point.observationId) } : null;

function ChartsPanelContent({
  input,
  embedded = false,
  emptyState,
}: {
  input: ChartsPanelInput;
  embedded?: boolean;
  emptyState?: ReactNode;
}) {
  const [panel, model] = useScreenModel(env => new ChartsPanelModel(env, input), []);
  const feed = useModelSnapshot(model.feed);
  const { display } = useUiEnv().shell;
  const { units, temperature } = useSyncExternalStore(display.subscribe, display.current, display.current);
  const metric = useMemo(() => chartMetrics(units, temperature), [units, temperature]);
  const live = input.scope === 'live';
  const bikeAvailable = input.bikeAvailable ?? true;
  useEffect(() => model.setBikeAvailable(bikeAvailable), [model, bikeAvailable]);
  const [editor, setEditor] = useState(false);
  const [menu, setMenu] = useState<'view' | 'range' | null>(null);
  const [numbersWidth, setNumbersWidth] = useState(330);
  const size = useWindowDimensions();
  const desktop = Platform.OS === 'web' && size.width >= 760;
  const sidebar = desktop && size.width >= 1100 && panel.charts.length > 0 && panel.numbers.length > 0;
  const view = panel.views.find(item => item.selected);
  const rangeLabel = panel.ranges.find(item => item.selected)?.label ?? '';
  const chartKey = panel.charts.join(',');
  const firstScreen = useMemo(() => {
    const groups = chartGroups(panel.charts, metric);
    return groupMetrics(
      groups,
      chartVisibility(
        groups.map(group => group.id),
        new Map(),
        size.height,
        chartEstimate(140),
      ).read,
    );
  }, [panel.charts, metric, size.height]);
  const [reported, setReported] = useState<{ charts: string; metrics: string[] }>();
  const onScreen = reported?.charts === chartKey ? reported.metrics : firstScreen;
  useEffect(() => model.feed.setOnScreen(onScreen as MetricId[]), [model, onScreen]);
  const reportOnScreen = useCallback((metrics: string[]) => setReported({ charts: chartKey, metrics }), [chartKey]);
  const lastSnap = useRef<ChartSnap | null>(null);
  const setSmoothing = useCallback((seconds: number) => model.setSmoothing(seconds as Smoothing), [model]);
  const changeViewport = useCallback(
    (next: { start: number; end: number }, interacting?: boolean) =>
      model.feed.setViewport({ startS: next.start, endS: next.end }, !!interacting),
    [model],
  );
  const changeCursor = useCallback(
    (seconds: number | null, interacting?: boolean, snap?: ChartSnap | null) => {
      lastSnap.current = snap ?? null;
      if (seconds === null) model.feed.clearCursor();
      else model.feed.setCursor(seconds, snapAnchor(snap), !!interacting);
    },
    [model],
  );
  const changeReference = useCallback(
    (seconds: number | null) => {
      if (seconds === null) model.feed.clearReference();
      else {
        const snap = lastSnap.current;
        model.feed.setReference(seconds, snap?.point.elapsedSeconds === seconds ? snapAnchor(snap) : null);
      }
    },
    [model],
  );
  const reorderCharts = useCallback((ids: string[]) => model.reorderCharts(ids as MetricId[]), [model]);
  const setPlotWidth = useCallback((width: number) => model.feed.setPlotWidth(width), [model]);
  const columns = sidebar
    ? 1
    : Math.max(1, Math.min(numbersWidth >= 600 ? 4 : 2, Math.floor((numbersWidth + 14) / (126 * size.fontScale + 14))));
  const status = panel.status;
  const statusError = status?.label === 'Chart error' || status?.label === 'Settings error';
  const details = [status?.detail, feed.catchingUp ? kit.textsChartStatus('catchingUp')?.detail : null]
    .filter(Boolean)
    .join('\n');
  const chooseRange = (value: ChartRange) => {
    model.chooseRange(value);
    setMenu(null);
  };
  const chooseView = (id: ViewId) => {
    model.chooseView(id);
    setMenu(null);
  };
  const header = (
    <View
      key={size.fontScale}
      testID="monitor-header"
      style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 6, minHeight: 44 }}
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Choose monitoring view, ${view?.name ?? ''}${details ? `. Monitoring status: ${details}` : ''}`}
        accessibilityHint={details ? 'Opens views and the full status message' : undefined}
        testID="monitor-view-picker"
        onPress={() => setMenu('view')}
        style={{
          flex: 1,
          minWidth: Math.min(120 * size.fontScale, size.width - 48),
          minHeight: 44,
          gap: 2,
          justifyContent: 'center',
        }}
      >
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
          <Text style={{ color: colors.text, fontSize: type.title, fontWeight: '600' }}>{view?.name}</Text>
          <Icon name="chevron" size={14} />
        </View>
        <StableLabel
          value={status?.label ?? ''}
          variants={statusLabels}
          testID={status ? 'monitor-status' : undefined}
          accessibilityRole={statusError ? 'alert' : undefined}
          style={{
            lineHeight: lineHeight.label,
            fontSize: type.label,
            color: statusError ? colors.red : colors.muted,
          }}
        />
      </Pressable>
      {live && feed.inspecting && (
        <Pressable
          accessibilityRole="button"
          onPress={() => model.feed.follow()}
          testID="monitor-live"
          style={{ minHeight: 44, paddingHorizontal: 8, justifyContent: 'center' }}
        >
          <Text style={{ color: colors.accent, fontSize: type.caption, fontWeight: '600' }}>● Live</Text>
        </Pressable>
      )}
      {desktop && panel.charts.length > 0 && (
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
              accessibilityState={{ checked: panel.columns === count }}
              aria-checked={panel.columns === count}
              onPress={() => model.setColumns(count)}
              style={{
                minWidth: 38,
                minHeight: 32,
                paddingHorizontal: 10,
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 3,
                borderRadius: 5,
                backgroundColor: panel.columns === count ? colors.surfaceRaised : 'transparent',
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
                    borderColor: panel.columns === count ? colors.accent : colors.muted,
                  }}
                />
              ))}
            </Pressable>
          ))}
        </View>
      )}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Chart time range, ${rangeLabel}`}
        testID="monitor-range-picker"
        onPress={() => setMenu('range')}
        style={{ minHeight: 44, paddingHorizontal: 8, flexDirection: 'row', alignItems: 'center', gap: 4 }}
      >
        <Text style={{ color: colors.muted, fontSize: type.caption }}>{feed.inspecting ? 'Custom' : rangeLabel}</Text>
        <Icon name="chevron" size={14} />
      </Pressable>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Edit ${view?.name ?? ''} view`}
        testID="monitor-edit"
        onPress={() => setEditor(true)}
        style={{ width: 44, height: 44, alignItems: 'center', justifyContent: 'center' }}
      >
        <Icon name="layout" />
      </Pressable>
    </View>
  );
  const numbers = panel.numbers.length > 0 && (
    <View
      testID="monitor-numbers"
      onLayout={event => setNumbersWidth(event.nativeEvent.layout.width)}
      style={{
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 14,
        paddingBottom: panel.charts.length && !sidebar ? 6 : 0,
      }}
    >
      {panel.numbers.map(id => {
        const shown = metric(id);
        if (!shown) return null;
        const value = tileValue(shown, feed.latest[id], { live, headLive: feed.head?.live ?? null, bikeAvailable });
        const unavailable = !live && !!feed.head && !feed.head.metrics.includes(id);
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
              label={shown.label}
              value={shown.format(value)}
              unit={shown.semantics === 'ratio' ? `${shown.unit} · ride avg` : shown.unit}
              color={shown.color}
              style={{ flexBasis: 'auto' }}
            />
          </View>
        );
      })}
    </View>
  );
  const comparison = feed.comparison;
  const charts = (
    <>
      {feed.head && (panel.charts.length > 0 || emptyState) && (
        <ChartCards
          energySmoothingSeconds={panel.smoothingS}
          onEnergySmoothingChange={setSmoothing}
          emptyState={emptyState}
          feed={feed}
          metricIds={panel.charts}
          metric={metric}
          units={units}
          temperature={temperature}
          onViewportChange={changeViewport}
          onCursorChange={changeCursor}
          onReferenceChange={changeReference}
          live={live}
          holdTail={bikeAvailable}
          desktopColumns={desktop ? panel.columns : undefined}
          availableHeight={desktop ? size.height - (embedded ? 420 : sidebar ? 246 : 340) : undefined}
          onReorderCharts={reorderCharts}
          onPlotWidthChange={setPlotWidth}
          onVisibleMetricsChange={reportOnScreen}
        />
      )}
      {!feed.head && emptyState && <Card>{emptyState}</Card>}
      {!feed.head && !emptyState && !feed.error && <Body muted>Loading charts…</Body>}
      {comparison && feed.cursor && (
        <View style={{ gap: 4 }}>
          {comparison.loading ? (
            <Body muted>Loading comparison…</Body>
          ) : (
            panel.charts.map(id => {
              const shown = metric(id);
              const stats = comparison.values[id];
              if (!shown || !stats) return null;
              if (isTotal(shown))
                return (
                  <Text key={id} style={{ color: colors.muted, fontSize: type.caption }}>
                    {shown.label} A–B · {shown.format(stats.ratio ?? stats.total)} {shown.unit}
                    {shown.semantics === 'ratio' && stats.partial ? ' · partial' : ''}
                  </Text>
                );
              if (!shown.allowsMean) return null;
              return (
                <Text key={id} style={{ color: colors.muted, fontSize: type.caption }}>
                  {shown.label} A–B · min {shown.exact(stats.min?.value)} {shown.unit} · avg{' '}
                  {shown.format(stats.average)} {shown.unit}
                </Text>
              );
            })
          )}
        </View>
      )}
      {panel.ready && !panel.numbers.length && !panel.charts.length && feed.head && (
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
  const editedView = view
    ? {
        id: view.id,
        name: view.name,
        numbers: panel.selected.numbers,
        charts: panel.selected.charts,
      }
    : null;
  return (
    <View style={{ gap: 12 }}>
      {header}
      {panel.notice && (
        <NoticeBanner
          notice={panel.notice}
          onAction={action => (action === 'retry' ? model.retry() : model.dismissNotice())}
        />
      )}
      {sidebar ? (
        <View
          testID="monitor-desktop"
          style={{ flexDirection: 'row', gap: 20, borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 16 }}
        >
          {panel.numbers.length > 0 && (
            <View style={{ width: 208, gap: 12 }}>
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
      {editedView && (
        <ChartsEditor
          metric={metric}
          visible={editor}
          view={editedView}
          onChange={changes => {
            if (changes.numbers) model.setMetrics('numbers', changes.numbers as MetricId[]);
            if (changes.charts) model.setMetrics('charts', changes.charts as MetricId[]);
          }}
          onReset={() => model.resetLayout()}
          onClose={() => setEditor(false)}
        />
      )}
      <ModalDialog
        visible={menu !== null}
        onClose={() => setMenu(null)}
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
          {!!details && (
            <Text
              testID="monitor-status-details"
              style={{
                padding: 14,
                color: statusError ? colors.red : colors.muted,
                fontSize: type.body,
                lineHeight: lineHeight.body,
              }}
            >
              {details}
            </Text>
          )}
          {panel.resetOffered && (
            <Pressable
              accessibilityRole="button"
              testID="monitor-reset-settings"
              onPress={() => {
                model.resetSettings();
                setMenu(null);
              }}
              style={{ minHeight: 50, paddingHorizontal: 14, justifyContent: 'center' }}
            >
              <Text style={{ color: colors.accent }}>Reset settings</Text>
            </Pressable>
          )}
          {menu === 'view'
            ? panel.views.map(item => (
                <Pressable
                  key={item.id}
                  accessibilityRole="button"
                  accessibilityState={{ selected: item.selected }}
                  onPress={() => chooseView(item.id)}
                  style={{ minHeight: 50, paddingHorizontal: 14, justifyContent: 'center' }}
                >
                  <Text style={{ color: item.selected ? colors.accent : colors.text }}>
                    {item.name}
                    {item.selected ? '  ✓' : ''}
                  </Text>
                </Pressable>
              ))
            : panel.ranges.map(item => (
                <Pressable
                  key={item.value}
                  accessibilityRole="button"
                  accessibilityState={{ selected: item.selected }}
                  onPress={() => chooseRange(item.value)}
                  style={{ minHeight: 50, paddingHorizontal: 14, justifyContent: 'center' }}
                >
                  <Text style={{ color: item.selected ? colors.accent : colors.text }}>
                    {item.label}
                    {item.selected ? '  ✓' : ''}
                  </Text>
                </Pressable>
              ))}
        </ScrollView>
      </ModalDialog>
    </View>
  );
}
