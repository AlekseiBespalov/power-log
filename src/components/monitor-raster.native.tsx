import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { CHART_INSET } from '../core/chart-viewport';
import { PixelRatio, Text, View, type ViewProps } from 'react-native';
import { requireNativeViewManager } from 'expo-modules-core';
import Animated, { useAnimatedProps, useSharedValue } from 'react-native-reanimated';
import { scheduleOnUI } from 'react-native-worklets';
import { monitorRasterScene, monitorRasterSelection } from '../core/monitor-raster';
import { MonitorHitAdmission, monitorHitScene, type MonitorRasterReady } from '../core/monitor-hit-test';
import type { MonitorRasterProps } from './monitor-raster.types';
import { colors } from './ui';

type RasterStatus = { nativeEvent: MonitorRasterReady & { status: 'ready' | 'error'; message?: string } };
type NativeProps = ViewProps & { sourceId: string; sceneKey: string; scene: string; selection: string; presentation?: number[]; selectionTarget?: string; onRenderStatus?: (event: RasterStatus) => void };
const NativePlot = Animated.createAnimatedComponent(requireNativeViewManager<NativeProps>('CycBridge'));

export function MonitorRaster({ data, group, scale, laneCount, width, height, hitScene, interaction, cursor, reference, tailTime }: MonitorRasterProps) {
  const [error, setError] = useState<string | null>(null);
  const geometryView = data.plotViewport ?? data.domain;
  const displayScale = PixelRatio.get();
  const packet = useMemo(() => {
    const scene = monitorRasterScene(data, group, scale, laneCount);
    scene.key = JSON.stringify([scene.key, width, height, displayScale]);
    return { json: JSON.stringify(scene), key: scene.key, width, height, displayScale,
      hits: monitorHitScene(data, group, scale, height, scene.key) };
    // Selection/latest updates do not rebuild or transfer geometry or hit tables.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data.sourceId, data.revision, data.plotGeneration, data.series, geometryView.start, geometryView.end, group, scale, laneCount, width, height, displayScale]);
  const binding = JSON.stringify([data.sourceId, group.metrics.map(metric => [metric.id, metric.kind, metric.scale, metric.color]), width, height, displayScale]);
  const expected = useRef({ packet, binding });
  const admission = useRef(new MonitorHitAdmission());
  const acceptedGeneration = useSharedValue(-1);
  const currentBinding = useSharedValue('');
  useLayoutEffect(() => {
    expected.current = { packet, binding };
    admission.current.request(packet, binding);
  }, [packet, binding]);
  useLayoutEffect(() => {
    scheduleOnUI((binding: string) => { 'worklet'; currentBinding.value = binding; hitScene.value = null; }, binding);
  }, [binding, currentBinding, hitScene]);
  const selection = useMemo(() => JSON.stringify({ ...monitorRasterSelection(data, group, cursor, reference, tailTime), epoch: interaction.epoch, sequence: interaction.sequence }), [data, group, cursor, reference, tailTime, interaction.epoch, interaction.sequence]);
  const shared = interaction.shared;
  const animatedProps = useAnimatedProps(() => {
    const state = shared.value;
    const voltage = group.id === 'batteryVoltageV' || group.id === 'throttleVoltageV';
    const metric = group.metrics.find(metric => metric.id === state.snap?.metric);
    const target = state.cursor !== null && state.snap && metric ? { key: state.snap.key, id: metric.id, seconds: state.snap.point.elapsedSeconds, value: state.snap.point.value * (metric.scale ?? 1) } : null;
    return {
      presentation: [state.view.start, state.view.end, state.cursor === null ? 0 : 1, state.cursor ?? 0, voltage && state.reference !== null ? 1 : 0, state.reference ?? 0, state.epoch, state.sequence],
      selectionTarget: JSON.stringify({ epoch: state.epoch, sequence: state.sequence, point: target }),
    };
  }, [shared, group]);
  const onRenderStatus = useCallback((event: RasterStatus) => {
    const ready = event.nativeEvent, { packet: current, binding } = expected.current;
    if (ready.status === 'error') {
      if (ready.key === current.key) setError(ready.message ?? 'Chart drawing unavailable');
      return;
    }
    const accepted = admission.current.accept(ready);
    if (!accepted) return;
    if (ready.key === current.key) setError(null);
    scheduleOnUI((binding: string, next: typeof accepted) => {
      'worklet';
      // JS events and UI tasks may trail requests; admit only monotonic native
      // acceptances for the current configuration, or clear an unbound table.
      if (currentBinding.value === binding && next.generation > acceptedGeneration.value) {
        acceptedGeneration.value = next.generation; hitScene.value = next.scene;
      }
    }, binding, accepted);
  }, [acceptedGeneration, currentBinding, hitScene]);
  return <View pointerEvents="none" style={{ height }}>
    <NativePlot sourceId={data.sourceId} sceneKey={packet.key} scene={packet.json} selection={selection} animatedProps={animatedProps} onRenderStatus={onRenderStatus} style={{ height, width: '100%' }} />
    {error && <Text accessibilityRole="alert" style={{ position: 'absolute', left: CHART_INSET.left, right: CHART_INSET.right, top: 4, color: colors.red, backgroundColor: colors.surface, fontSize: 11 }}>{error}</Text>}
  </View>;
}
