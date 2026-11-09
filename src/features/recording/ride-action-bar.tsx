import type { ReactNode } from 'react';
import { ActivityIndicator, Text, View, useWindowDimensions } from 'react-native';
import { Icon } from '../../components/icon';
import type { ControlState } from '../../components/screen-model';
import { Button, Chip, colors, Pressable, styles, type } from '../../components/ui';
import type { RideControlsModel, RideControlsState } from './ride-controls-model';

export function RideActionBar({
  state,
  model,
  inline = false,
}: {
  state: RideControlsState;
  model: RideControlsModel;
  inline?: boolean;
}) {
  const { fontScale } = useWindowDimensions();
  const bar = state.bar;
  if (!state.ready) return <View testID="ride-controls" style={{ minHeight: 44 }} />;
  if (!state.supported)
    return (
      <View testID="ride-controls" style={{ minHeight: 44, justifyContent: 'center' }}>
        <Text style={{ color: colors.muted, fontSize: type.caption }}>
          Ride recording is unavailable on this device.
        </Text>
      </View>
    );
  return (
    <View
      testID="ride-controls"
      style={{
        minHeight: 44,
        flexDirection: 'row',
        flexWrap: 'wrap',
        alignItems: 'center',
        justifyContent: inline ? 'flex-start' : 'flex-end',
        gap: 8,
      }}
    >
      {state.phase && (
        <View style={{ flexGrow: 1, flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
          <Text
            testID="ride-timer"
            style={{ color: colors.text, fontSize: type.value, fontWeight: '600', fontVariant: ['tabular-nums'] }}
          >
            {state.timer}
          </Text>
          {state.chip && (
            <View testID="ride-state-badge">
              <Chip tone={state.chip.tone}>{state.chip.text}</Chip>
            </View>
          )}
        </View>
      )}
      {bar && (
        <Button
          testID="start-ride"
          block={!inline}
          secondary={bar.opensSetup}
          disabled={state.start.busy || state.phase === 'preparing'}
          icon={<Icon name="play" size={18} color={bar.opensSetup ? colors.text : colors.bg} />}
          onPress={() => (bar.opensSetup ? model.openSetup() : model.start())}
        >
          {bar.label}
        </Button>
      )}
      {state.finish && (
        <View style={{ flexDirection: 'row', width: inline ? 360 * fontScale : '100%', maxWidth: '100%', gap: 8 }}>
          {state.pauseOrResume && (
            <BarButton
              control={state.pauseOrResume}
              icon={<Icon name={state.phase === 'paused' ? 'play' : 'pause'} size={18} color={colors.text} />}
              onPress={() => (state.phase === 'paused' ? model.resume() : model.pause())}
            />
          )}
          {state.lap && (
            <BarButton
              control={state.lap}
              icon={<Icon name="lap" size={18} color={colors.text} />}
              onPress={() => model.lap()}
            />
          )}
          <BarButton
            testID="finish-ride"
            control={state.finish}
            danger
            icon={<Icon name="stop" size={18} color={colors.bg} />}
            onPress={() => model.askToFinish()}
          />
        </View>
      )}
      {state.reviewRide && (
        <Button secondary onPress={() => model.openSetup()}>
          Review ride
        </Button>
      )}
      {state.waiting && (
        <View style={styles.row}>
          <ActivityIndicator size="small" color={colors.accent} />
          <Text style={{ color: colors.muted, fontSize: type.body }}>{state.waiting}</Text>
        </View>
      )}
    </View>
  );
}

function BarButton({
  control,
  icon,
  danger = false,
  testID,
  onPress,
}: {
  control: ControlState;
  icon: ReactNode;
  danger?: boolean;
  testID?: string;
  onPress: () => void;
}) {
  const disabled = !control.enabled || control.busy;
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={control.label}
      accessibilityState={{ disabled }}
      accessibilityHint={control.reason ?? undefined}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        !danger && styles.secondary,
        danger && { backgroundColor: colors.red },
        { flex: 1, minWidth: 0, paddingHorizontal: 6 },
        disabled && { opacity: 0.38 },
        pressed && { opacity: 0.75 },
      ]}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
        {icon}
        <Text style={[styles.buttonText, { flexShrink: 1, color: danger ? colors.bg : colors.text }]}>
          {control.label}
        </Text>
      </View>
    </Pressable>
  );
}
