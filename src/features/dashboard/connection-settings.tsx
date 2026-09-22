import { useState } from 'react';
import { bikeDetails, bikeDisplayName } from '../../core/bikes';
import { Text, View } from 'react-native';
import { Button, colors, Heading } from '../../components/ui';
import { Icon } from '../../components/icon';
import { bikeConnectionLabel } from '../../core/telemetry-display';
import { workoutInProgress } from '../../core/workouts';
import { useSession } from '../../services/session-context';
import { useWorkoutIdentity } from '../../services/workout-context';
import { ConnectionHealth } from './connection-health';

export function ConnectionSettings() {
  const session = useSession(), workout = useWorkoutIdentity();
  const { state, adapter, busy } = session;
  const [details, setDetails] = useState(false);
  const display = session.display;
  const available = display !== 'unavailable';
  const connected = state.status === 'connected';
  const connecting = state.status === 'connecting' || state.status === 'reconnecting';
  const active = workoutInProgress(workout.phase);
  const action = (operation: () => Promise<unknown>) => () => { void session.run(operation); };
  return <View style={{ gap: 12 }}>
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
      <Icon name="bike" size={25} color={available ? colors.accent : colors.muted} />
      <View style={{ flex: 1, gap: 3 }}><Heading>{state.deviceName ? bikeDisplayName({ id: state.deviceId, name: state.deviceName, controllerModel: state.controllerModel }) : 'Your bike'}</Heading><Text testID="bike-connection-status" style={{ color: available ? colors.accent : colors.muted, fontSize: 12 }}>{bikeConnectionLabel(state.status, display)}</Text></View>
      {!connected && !connecting && state.status !== 'scanning' && <Button disabled={busy || adapter.kind === 'unavailable' || (active && Boolean(state.deviceId))} onPress={action(session.scan)}>Find bike</Button>}
      {state.status === 'scanning' && <Button secondary onPress={action(() => adapter.stopScan())}>Stop search</Button>}
    </View>
    {session.error && <View accessibilityRole="alert" style={{ gap: 8 }}><Text style={{ color: colors.red }}>{session.error}</Text><Button secondary onPress={session.clearError}>Dismiss</Button></View>}
    {!connected && !connecting && session.devices.map(device => <View key={device.id} style={{ gap: 5 }}><Button secondary disabled={busy || (active && Boolean(state.deviceId) && state.deviceId !== device.id)} onPress={action(() => adapter.connect({ deviceId: device.id, hz: session.hz }))}>Connect {bikeDisplayName(device)}</Button><Text style={{ color: colors.muted, fontSize: 12 }}>{bikeDetails(device)}</Text></View>)}
    {(connected || connecting) && <Button secondary disabled={busy || active} onPress={action(() => adapter.disconnect())}>Disconnect</Button>}
    <Button secondary onPress={() => setDetails(value => !value)}>{details ? 'Hide connection details' : 'Connection details'}</Button>
    {details && <ConnectionHealth adapter={adapter} readingsAvailable={available} />}
  </View>;
}
