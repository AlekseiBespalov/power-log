import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { CHART_INSET } from '../core/chart-viewport';
import { PixelRatio, Platform, Text, View, type ViewProps } from 'react-native';
import { requireNativeViewManager } from 'expo-modules-core';
import Animated, { useAnimatedProps, useSharedValue } from 'react-native-reanimated';
import { scheduleOnUI } from 'react-native-worklets';
import { monitorRasterScene } from '../core/monitor-raster';
import { nativeMonitorRasterScene, nativeMonitorRasterSelection } from '../services/native-monitor-raster';
import {
  MonitorHitAdmission,
  monitorHitScene,
  monitorHitTargets,
  type MonitorHitScene,
  type MonitorRasterReady,
} from '../core/monitor-hit-test';
import type { MonitorRasterProps } from './monitor-raster.types';
import { colors, type } from './ui';

type RasterStatus = { nativeEvent: MonitorRasterReady & { status: 'ready' | 'error' } };
type NativeProps = ViewProps & {
  sourceId: string;
  sceneKey: string;
  scene: string;
  selection: string;
  presentation?: number[];
  selectionTarget?: string;
  onRenderStatus?: (event: RasterStatus) => void;
};
const NativePlot = Animated.createAnimatedComponent(requireNativeViewManager<NativeProps>('PowerLogBridge'));

export function MonitorRaster({
  data,
  group,
  scale,
  laneCount,
  width,
  height,
  hitScene,
  interaction,
  cursor,
  reference,
  tailTime,
  snappable,
}: MonitorRasterProps) {
  const [error, setError] = useState<string | null>(null);
  const geometryView = data.plotViewport ?? data.domain;
  const displayScale = PixelRatio.get();
  const revision = data.revision ?? 'unavailable';
  const distanceSelection = revision.startsWith('distance:') ? revision.slice(9, revision.lastIndexOf(':')) : 'auto';
  const queryContext = JSON.stringify({ revision, distanceSelection, generation: data.plotGeneration ?? 0 });
  const packet = useMemo(() => {
    const scene = (Platform.OS === 'ios' ? nativeMonitorRasterScene : monitorRasterScene)(
      data,
      group,
      scale,
      laneCount,
    );
    scene.key = JSON.stringify([scene.key, width, height, displayScale, JSON.parse(queryContext) as unknown]);
    return {
      json: JSON.stringify({ ...scene, queryContext: JSON.parse(queryContext) as unknown }),
      key: scene.key,
      width,
      height,
      displayScale,
      hits: monitorHitScene(data, group, scale, height, scene.key),
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    queryContext,
    data.sourceId,
    data.revision,
    data.plotGeneration,
    data.series,
    geometryView.start,
    geometryView.end,
    group,
    scale,
    laneCount,
    width,
    height,
    displayScale,
  ]);
  const binding = JSON.stringify([
    data.sourceId,
    group.metrics.map(metric => [metric.id, metric.kind, metric.scale, metric.color]),
    width,
    height,
    displayScale,
  ]);
  const expected = useRef({ packet, binding });
  const admission = useRef(new MonitorHitAdmission());
  const acceptedGeneration = useSharedValue(-1);
  const currentBinding = useSharedValue('');
  const admittedScene = useSharedValue<MonitorHitScene | null>(null);
  const snapping = useSharedValue(snappable);
  useLayoutEffect(() => {
    expected.current = { packet, binding };
    admission.current.request(packet, binding);
  }, [packet, binding]);
  useLayoutEffect(() => {
    scheduleOnUI((binding: string) => {
      'worklet';
      currentBinding.value = binding;
      admittedScene.value = null;
      hitScene.value = null;
    }, binding);
  }, [binding, currentBinding, admittedScene, hitScene]);
  useLayoutEffect(() => {
    scheduleOnUI((value: boolean) => {
      'worklet';
      snapping.value = value;
      hitScene.value = value ? admittedScene.value : null;
    }, snappable);
  }, [snappable, snapping, admittedScene, hitScene]);
  const selection = useMemo(() => {
    return JSON.stringify({
      ...nativeMonitorRasterSelection(Platform.OS, data, group, cursor, reference, tailTime),
      revision,
      distanceSelection,
      generation: data.plotGeneration ?? 0,
      epoch: interaction.epoch,
      sequence: interaction.sequence,
    });
  }, [data, group, cursor, reference, tailTime, interaction.epoch, interaction.sequence, revision, distanceSelection]);
  const shared = interaction.shared;
  const animatedProps = useAnimatedProps(() => {
    const state = shared.value;
    const voltage = group.id === 'batteryVoltageV' || group.id === 'throttleVoltageV';
    return {
      presentation: [
        state.view.start,
        state.view.end,
        state.cursor === null ? 0 : 1,
        state.cursor ?? 0,
        voltage && state.reference !== null ? 1 : 0,
        state.reference ?? 0,
        state.epoch,
        state.sequence,
      ],
      selectionTarget: JSON.stringify({
        epoch: state.epoch,
        sequence: state.sequence,
        points: monitorHitTargets(hitScene.value, state.cursor, state.snap),
      }),
    };
  }, [shared, group, hitScene]);
  const onRenderStatus = useCallback(
    (event: RasterStatus) => {
      const ready = event.nativeEvent,
        { packet: current, binding } = expected.current;
      if (ready.status === 'error') {
        if (ready.key === current.key) setError('Chart drawing unavailable');
        return;
      }
      const accepted = admission.current.accept(ready);
      if (!accepted) return;
      if (ready.key === current.key) setError(null);
      scheduleOnUI(
        (binding: string, next: typeof accepted) => {
          'worklet';
          // JS events and UI tasks may trail requests; admit only monotonic native
          // acceptances for the current configuration, or clear an unbound table.
          if (currentBinding.value === binding && next.generation > acceptedGeneration.value) {
            acceptedGeneration.value = next.generation;
            admittedScene.value = next.scene;
            hitScene.value = snapping.value ? next.scene : null;
          }
        },
        binding,
        accepted,
      );
    },
    [acceptedGeneration, currentBinding, admittedScene, snapping, hitScene],
  );
  return (
    <View pointerEvents="none" style={{ height }}>
      <NativePlot
        sourceId={data.sourceId}
        sceneKey={packet.key}
        scene={packet.json}
        selection={selection}
        animatedProps={animatedProps}
        onRenderStatus={onRenderStatus}
        style={{ height, width: '100%' }}
      />
      {error && (
        <Text
          accessibilityRole="alert"
          style={{
            position: 'absolute',
            left: CHART_INSET.left,
            right: CHART_INSET.right,
            top: 4,
            color: colors.red,
            backgroundColor: colors.surface,
            fontSize: type.label,
          }}
        >
          {error}
        </Text>
      )}
    </View>
  );
}
