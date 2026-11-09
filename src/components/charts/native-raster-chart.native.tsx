import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { PixelRatio, Text, View } from 'react-native';
import Animated, { useAnimatedProps, useSharedValue } from 'react-native-reanimated';
import { scheduleOnUI } from 'react-native-worklets';
import { ChartRasterView, type ChartRasterStatus } from '../../../modules/power-log';
import { colors, type } from '../ui';
import { isVoltage } from './chart-groups';
import type { ChartRendererProps } from './chart-renderer.types';
import { CHART_INSET } from './chart-viewport';
import { buildHitScene, HitAdmission, hitTargets, type HitScene } from './hit-test';
import { rasterScene } from './raster-scene';

const NativePlot = Animated.createAnimatedComponent(ChartRasterView);

export function NativeRasterChart({
  sourceId,
  revision,
  group,
  series,
  frame,
  scale,
  laneCount,
  width,
  height,
  units,
  temperature,
  hitScene,
  interaction,
  cursor,
  reference,
  cursorPending,
  marks,
  tails,
  snappable,
}: ChartRendererProps) {
  const [error, setError] = useState<string | null>(null);
  const displayScale = PixelRatio.get();
  const packet = useMemo(() => {
    const key = JSON.stringify([
      sourceId,
      revision,
      frame.start,
      frame.end,
      group.metrics.map(metric => [metric.id, metric.color, metric.factor, metric.offset]),
      scale.min,
      scale.max,
      scale.decimals,
      laneCount,
      width,
      height,
      displayScale,
    ]);
    const scene = rasterScene({
      key,
      sourceId,
      revision,
      viewport: { startS: frame.start, endS: frame.end },
      series,
      group: group.metrics,
      scale,
      laneCount,
      units,
      temperature,
    });
    return {
      json: JSON.stringify(scene),
      key,
      width,
      height,
      displayScale,
      hits: buildHitScene({ key, sourceId, revision, height, scale, metrics: group.metrics, series }),
    };
  }, [sourceId, revision, series, frame, group, scale, laneCount, width, height, displayScale, units, temperature]);
  const binding = JSON.stringify([
    sourceId,
    group.metrics.map(metric => [metric.id, metric.step, metric.factor, metric.offset, metric.color]),
    width,
    height,
    displayScale,
  ]);
  const expected = useRef({ packet, binding });
  const admission = useRef(new HitAdmission());
  const acceptedGeneration = useSharedValue(-1);
  const currentBinding = useSharedValue('');
  const admittedScene = useSharedValue<HitScene | null>(null);
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
  const selection = useMemo(
    () =>
      JSON.stringify({
        sourceId,
        revision,
        epoch: interaction.epoch,
        sequence: interaction.sequence,
        cursorPending,
        cursorS: cursor,
        referenceS: reference,
        points: marks.flatMap(({ metric, cursor: point }) =>
          point ? [{ id: metric.id, atS: point.atS, value: metric.display(point.value) }] : [],
        ),
        references: marks.flatMap(({ metric, reference: point }) =>
          point && isVoltage(metric.id) ? [{ id: metric.id, atS: point.atS, value: metric.display(point.value) }] : [],
        ),
        tails: tails.map(tail => ({
          id: tail.metric.id,
          startS: tail.startS,
          endS: tail.endS,
          value: tail.metric.display(tail.value),
        })),
      }),
    [sourceId, revision, interaction.epoch, interaction.sequence, cursorPending, cursor, reference, marks, tails],
  );
  const shared = interaction.shared;
  const voltage = group.metrics.some(metric => isVoltage(metric.id));
  const animatedProps = useAnimatedProps(() => {
    const state = shared.value;
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
        points: hitTargets(hitScene.value, state.cursor, state.snap),
      }),
    };
  }, [shared, voltage, hitScene]);
  const onRenderStatus = useCallback(
    (event: { nativeEvent: ChartRasterStatus }) => {
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
        sourceId={sourceId}
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
