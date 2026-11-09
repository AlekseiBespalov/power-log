import { Text, View, useWindowDimensions } from 'react-native';
import { Icon } from '../../components/icon';
import { NoticeBanner } from '../../components/notice-banner';
import { colors, Pressable, type } from '../../components/ui';
import type { ConnectionPanelState } from '../connection/connection-panel-model';
import type { RideControlsModel, RideControlsState } from './ride-controls-model';

export function RideStatus({
  state,
  connection,
  model,
}: {
  state: RideControlsState;
  connection: ConnectionPanelState;
  model: RideControlsModel;
}) {
  const { fontScale } = useWindowDimensions();
  const shown = connection.shownDisplay !== 'unavailable';
  const name = connection.bikeName ?? connection.heading;
  const label = [
    'Bike and ride setup',
    connection.status,
    shown ? name : null,
    state.chip ? `${state.chip.text}, ${state.timer}` : null,
  ]
    .filter(Boolean)
    .join('. ');
  return (
    <View style={{ gap: 8 }}>
      <View
        key={fontScale}
        testID="ride-status"
        style={{ minHeight: 44, flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}
      >
        <Pressable
          testID="ride-setup"
          accessibilityRole="button"
          accessibilityLabel={label}
          accessibilityHint="Opens bike connection and ride options"
          accessibilityState={{ expanded: state.setupOpen }}
          onPress={() => model.openSetup()}
          style={({ pressed }) => ({
            flexGrow: 1,
            flexShrink: 1,
            minWidth: 0,
            minHeight: 44,
            flexDirection: 'row',
            alignItems: 'center',
            gap: 8,
            paddingHorizontal: 12,
            borderRadius: 10,
            borderWidth: 1,
            borderColor: colors.border,
            backgroundColor: colors.surface,
            opacity: pressed ? 0.75 : 1,
          })}
        >
          <Icon name="bike" color={shown ? colors.accent : colors.muted} />
          <Text
            numberOfLines={1}
            style={{
              flexShrink: 1,
              color: colors.text,
              fontSize: type.body,
              fontWeight: '600',
              fontVariant: ['tabular-nums'],
            }}
          >
            {state.phase ? state.timer : shown ? name : 'Connect bike'}
          </Text>
          <View
            style={{
              width: 5,
              height: 5,
              borderRadius: 3,
              backgroundColor: shown ? colors.accent : colors.muted,
            }}
          />
          <Icon name="chevron" size={14} />
        </Pressable>
      </View>
      {!state.setupOpen && state.notice && (
        <NoticeBanner
          notice={state.notice}
          onAction={action =>
            action === 'reviewRide' ? model.openSetup() : action === 'retry' ? model.retry() : model.dismissNotice()
          }
        />
      )}
    </View>
  );
}
