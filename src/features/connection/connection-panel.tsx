import { Text, View } from 'react-native';
import { Icon } from '../../components/icon';
import { ControlButton } from '../../components/control-button';
import { NoticeBanner } from '../../components/notice-banner';
import { Body, Button, colors, Heading, type } from '../../components/ui';
import type { ConnectionPanelModel, ConnectionPanelState } from './connection-panel-model';

export function ConnectionPanel({ state, model }: { state: ConnectionPanelState; model: ConnectionPanelModel }) {
  return (
    <View style={{ gap: 12 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
        <Icon name="bike" size={25} color={state.accent ? colors.accent : colors.muted} />
        <View style={{ flex: 1, gap: 3 }}>
          <Heading>{state.heading}</Heading>
          <Text
            testID="bike-connection-status"
            style={{ color: state.accent ? colors.accent : colors.muted, fontSize: type.caption }}
          >
            {state.status}
          </Text>
          {state.speedNote && <Text style={{ color: colors.muted, fontSize: type.caption }}>{state.speedNote}</Text>}
        </View>
        {state.findBike && (
          <ControlButton testID="setup-find-bike" control={state.findBike} onPress={() => model.findBike()} />
        )}
        {state.stopSearch && <ControlButton secondary control={state.stopSearch} onPress={() => model.stopSearch()} />}
      </View>
      {state.notice && (
        <NoticeBanner
          notice={state.notice}
          onAction={action =>
            action === 'openAppSettings'
              ? model.openSettings()
              : action === 'retry'
                ? model.retry()
                : model.dismissNotice()
          }
        />
      )}
      {state.bikes.map(bike => (
        <ControlButton
          key={bike.bikeId}
          testID="setup-connect-bike"
          secondary
          control={bike.control}
          onPress={() => model.connect(bike.bikeId)}
        />
      ))}
      {state.disconnect && <ControlButton secondary control={state.disconnect} onPress={() => model.disconnect()} />}
      {state.cancelConnection && (
        <ControlButton secondary control={state.cancelConnection} onPress={() => model.cancelConnection()} />
      )}
    </View>
  );
}

export function ConnectionEmptyState({ state, onFindBike }: { state: ConnectionPanelState; onFindBike: () => void }) {
  return (
    <View style={{ gap: 12 }}>
      <Text accessibilityRole="header" style={{ color: colors.text, fontSize: type.title, fontWeight: '600' }}>
        No bike connected
      </Text>
      <Body muted>Turn on your bike, then find it to see live readings.</Body>
      <Body muted>Works with CYC X6 and X12 controllers over Bluetooth.</Body>
      {state.findBike ? (
        <ControlButton control={state.findBike} onPress={onFindBike} />
      ) : (
        <Button disabled onPress={() => undefined}>
          Find bike
        </Button>
      )}
    </View>
  );
}
