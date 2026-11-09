import { Platform, Text, useWindowDimensions } from 'react-native';
import { AppShell } from '../../components/app-shell';
import { colors } from '../../components/ui';
import { useScreenModel } from '../../components/use-screen-model';
import { ConnectionEmptyState, ConnectionPanel } from '../connection/connection-panel';
import { ConnectionPanelModel } from '../connection/connection-panel-model';
import { ChartsPanel } from '../charts/charts-panel';
import { FinishRideSheet } from './finish-ride-sheet';
import { RideActionBar } from './ride-action-bar';
import { RideControlsModel } from './ride-controls-model';
import { RideSetupSheet } from './ride-setup-sheet';
import { RideStatus } from './ride-status';

export function RecordingScreen() {
  const [connection, panel] = useScreenModel(env => new ConnectionPanelModel(env), []);
  const [controls, rideControls] = useScreenModel(env => new RideControlsModel(env), []);
  const { width } = useWindowDimensions();
  const web = Platform.OS === 'web';
  const bottomBar = !(web && width >= 1100);
  const bikeAvailable = connection.shownDisplay !== 'unavailable';
  const actionBar = <RideActionBar state={controls} model={rideControls} inline={!bottomBar} />;
  return (
    <AppShell footer={bottomBar ? actionBar : undefined}>
      <RideStatus state={controls} connection={connection} model={rideControls} />
      <RideSetupSheet
        state={controls}
        model={rideControls}
        web={web}
        connection={<ConnectionPanel state={connection} model={panel} />}
      />
      <FinishRideSheet state={controls} model={rideControls} />
      {!bottomBar && actionBar}
      <ChartsPanel
        input={{
          source: controls.rideId ? { kind: 'ride', rideId: controls.rideId } : { kind: 'preview' },
          scope: 'live',
          bikeAvailable,
        }}
        emptyState={
          !bikeAvailable && controls.phase === null ? (
            <ConnectionEmptyState
              state={connection}
              onFindBike={() => {
                rideControls.openSetup();
                panel.findBike();
              }}
            />
          ) : undefined
        }
      />
      {connection.fault && (
        <Text accessibilityRole="alert" style={{ color: colors.red }}>
          {connection.fault}
        </Text>
      )}
    </AppShell>
  );
}
