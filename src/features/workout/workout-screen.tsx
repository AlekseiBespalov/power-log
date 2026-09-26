import { createContext, useContext, useState, type ReactNode } from 'react';
import { bikeDisplayName } from '../../core/bikes';
import { ActivityIndicator, Alert, Linking, Platform, Pressable, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Link } from 'expo-router';
import { Body, Button, Chip, colors, formatDuration, Metric, styles } from '../../components/ui';
import { Icon } from '../../components/icon';
import { ModalDialog } from '../../components/modal-dialog';
import { availableWorkoutOptions, workoutInProgress } from '../../core/workouts';
import { workoutPermissionAction } from '../../core/workout-permissions';
import { workoutFinishPresentation, workoutSourcePresentation } from '../../core/workout-presentation';
import { bikeConnectionLabel } from '../../core/telemetry-display';
import { haptics } from '../../services/haptics';
import { useSession } from '../../services/session-context';
import { useWorkout } from '../../services/workout-context';
import { workouts } from '../../services/workouts';
import type { WorkoutActionKind } from '../../services/workout-actions';

const phaseChips: Record<string, string> = { preparing: 'Starting', running: 'Recording', paused: 'Paused', recoverable: 'Needs attention', finishing: 'Finishing', completed: 'Saved', failed: 'Failed' };

function useRideControlState() {
  const workout = useWorkout(), session = useSession();
  const { state, busy } = workout;
  const finish = workoutFinishPresentation(state);
  const options = availableWorkoutOptions({ ...workout.options, sampleHz: session.hz }, state.capabilities);
  const { useWatch, recordGPS } = options;
  const [setup, setSetup] = useState(false);
  const [finishTarget, setFinishTarget] = useState<string | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [showNotices, setShowNotices] = useState(false);
  const [dismissedError, setDismissedError] = useState<string | null>(null);
  const errorMessage = workout.error ?? state.error;
  const active = workoutInProgress(state.phase);
  const bikeDisplay = session.display;
  const bikeName = bikeDisplayName({ id: session.state.deviceId, name: session.state.deviceName, controllerModel: session.state.controllerModel });
  const bikeReady = (session.adapter.kind === 'native' || Platform.OS === 'web') && (session.state.status === 'connected' || bikeDisplay === 'held');
  const sources = workoutSourcePresentation(state, options, bikeReady);
  const bikePresentation = active && bikeDisplay !== 'unavailable' ? { ...sources.bike, label: 'Receiving samples', tone: 'ready' as const } : sources.bike;
  const disabled = busy || Boolean(state.pendingAction);
  const recoveryBusy = busy && !workout.recoveryAvailable;
  const canRequestStop = ['preparing', 'recoverable'].includes(state.phase) || !state.pendingAction;
  const permission = workoutPermissionAction(workout.permissions, options);
  const permissionVisible = (workout.permissions !== null || workout.permissionsError !== null) && permission.action !== 'none';
  const action = (operation: () => Promise<unknown>, kind?: WorkoutActionKind, haptic?: () => void) => () => {
    setDismissedError(null); void workout.run(async () => { try { await operation(); } catch (error) { haptics.warning(); throw error; } haptic?.(); }, kind);
  };
  const start = action(async () => { await workouts.start(options); setSetup(false); }, 'start', haptics.success);
  const sourceReady = Platform.OS === 'web' ? bikeReady : session.adapter.kind === 'native' && (bikeReady || useWatch || recordGPS);
  const needsSetup = !sourceReady || (useWatch && !state.watch.installed) || permissionVisible;
  const connectionLabel = bikeConnectionLabel(session.state.status, bikeDisplay);
  const visibleError = errorMessage && errorMessage !== dismissedError;
  const askToFinish = () => { if (state.id) { workout.clearError(); setConfirmDiscard(false); setFinishTarget(state.id); } };
  const modeSummary = [options.indoor ? 'Indoor' : 'Outdoor', recordGPS ? 'GPS route' : null, useWatch ? 'Apple Watch' : null,
    state.capabilities.healthKit || state.capabilities.healthConnect
      ? (options.saveToHealth !== false ? (state.capabilities.healthConnect ? 'Health Connect' : 'Apple Health') : 'No Health save') : null].filter(Boolean).join(' · ');
  const finishVisible = finishTarget !== null && finishTarget === state.id && ['running', 'paused'].includes(state.phase);
  const finishAvailable = finishVisible && !disabled;
  const finishRide = (discard: boolean) => action(async () => {
    if (!finishTarget) return;
    await (discard ? workouts.discard(finishTarget) : workouts.stop(finishTarget));
    setFinishTarget(null);
  }, undefined, discard ? haptics.warning : haptics.success);
  const pause = action(() => workouts.pause(state.id ?? undefined), undefined, () => haptics.impact());
  const resume = action(() => workouts.resume(state.id ?? undefined), undefined, () => haptics.impact());
  const lap = action(() => workouts.lap(state.id ?? undefined), undefined, () => haptics.impact('light'));
  const recover = action(() => workouts.recover(state.id!));
  const stopUnconfirmed = action(() => workouts.stop(state.id ?? undefined));
  return { workout, session, state, busy, finish, options, useWatch, recordGPS, setup, setSetup, finishTarget, setFinishTarget, confirmDiscard, setConfirmDiscard,
    showNotices, setShowNotices, dismissedError, setDismissedError, errorMessage, active, bikeDisplay, bikeName, sources, bikePresentation, disabled, recoveryBusy,
    canRequestStop, permission, permissionVisible, action, start, sourceReady, needsSetup, connectionLabel, visibleError, askToFinish, modeSummary, finishVisible, finishAvailable,
    finishRide, pause, resume, lap, recover, stopUnconfirmed };
}
type RideControls = ReturnType<typeof useRideControlState>;
const RideControlsContext = createContext<RideControls | null>(null);
export function RideControlsProvider({ children }: { children: ReactNode }) {
  const value = useRideControlState();
  return <RideControlsContext.Provider value={value}>{children}</RideControlsContext.Provider>;
}
function useRideControls() {
  const value = useContext(RideControlsContext);
  if (!value) throw new Error('RideControlsProvider is missing.');
  return value;
}

/** Start, Pause, Resume, Lap and Finish. Rendered in the shell footer on phones and inline on desktop. */
export function RideActionBar({ inline = false }: { inline?: boolean }) {
  const c = useRideControls();
  const { state, busy, workout } = c;
  const iconColor = (primary: boolean) => primary ? colors.bg : colors.text;
  return <View testID="ride-controls" style={{ minHeight: 44, flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: inline ? 'flex-start' : 'flex-end', gap: 8 }}>
    {c.active && <Text style={{ flex: 1, minWidth: 72, color: colors.text, fontSize: 22, fontWeight: '600', fontVariant: ['tabular-nums'], letterSpacing: -0.6 }}>{formatDuration(state.timerSeconds)}</Text>}
    {state.supported && !c.active && <Button testID="start-ride" block={!inline} disabled={busy || !workout.optionsReady} icon={<Icon name="play" size={18} color={colors.bg} />} onPress={c.needsSetup ? () => c.setSetup(true) : c.start}>{c.needsSetup ? 'Set up ride' : 'Start ride'}</Button>}
    {state.supported && c.active && <>
      {state.phase === 'running' && <Button secondary disabled={c.disabled} accessibilityLabel="Pause" icon={<Icon name="pause" size={18} color={iconColor(false)} />} onPress={c.pause}>{inline ? 'Pause' : undefined}</Button>}
      {state.phase === 'paused' && <Button disabled={c.disabled} accessibilityLabel="Resume" icon={<Icon name="play" size={18} color={iconColor(true)} />} onPress={c.resume}>{inline ? 'Resume' : undefined}</Button>}
      {state.phase === 'running' && <Button secondary disabled={c.disabled} accessibilityLabel="Lap" icon={<Icon name="lap" size={18} color={iconColor(false)} />} onPress={c.lap}>{inline ? 'Lap' : undefined}</Button>}
      {['running', 'paused'].includes(state.phase) && <Button danger disabled={c.disabled} icon={<Icon name="stop" size={18} color={colors.bg} />} onPress={c.askToFinish}>Finish</Button>}
      {state.phase === 'recoverable' && <Button secondary onPress={() => c.setSetup(true)}>Review ride</Button>}
      {['preparing', 'finishing'].includes(state.phase) && <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}><ActivityIndicator size="small" color={colors.accent} /><Text style={{ color: colors.muted, fontSize: 14 }}>{state.phase === 'preparing' ? 'Starting…' : c.finish.label}</Text></View>}
    </>}
    {!state.supported && Platform.OS === 'ios' && <Text style={{ color: colors.muted, fontSize: 12 }}>Ride recording requires iOS 26.</Text>}
  </View>;
}

/** Bike status pill with the ride setup and finish sheets. */
export function RideStatus({ connection }: { connection: ReactNode }) {
  const c = useRideControls();
  const { workout, state, busy } = c;
  const { fontScale } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  return <View style={{ gap: 8 }}>
    <View key={fontScale} testID="ride-status" style={{ minHeight: 44, flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
      <Pressable testID="ride-setup" accessibilityRole="button" accessibilityLabel={`Bike and ride setup. ${c.connectionLabel}${c.bikeDisplay !== 'unavailable' ? `. ${c.bikeName}` : ''}${c.active ? `. ${state.phase}, ${formatDuration(state.timerSeconds)}` : ''}`} accessibilityHint="Opens bike connection and ride options" accessibilityState={{ expanded: c.setup }} onPress={() => c.setSetup(true)} style={({ pressed }) => ({ flexGrow: 1, flexShrink: 1, minWidth: 0, minHeight: 44, flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 12, borderRadius: 10, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface, opacity: pressed ? 0.75 : 1 })}>
        <Icon name="bike" color={c.bikeDisplay !== 'unavailable' ? colors.accent : colors.muted} />
        <Text numberOfLines={1} style={{ flexShrink: 1, color: colors.text, fontSize: 14, fontWeight: '600', fontVariant: ['tabular-nums'] }}>{c.active ? formatDuration(state.timerSeconds) : c.bikeDisplay !== 'unavailable' ? c.bikeName : 'Connect bike'}</Text>
        <View style={{ width: 5, height: 5, borderRadius: 3, backgroundColor: c.bikeDisplay !== 'unavailable' ? colors.accent : colors.muted }} />
        <Icon name="chevron" size={14} />
      </Pressable>
    </View>
    {!c.setup && c.visibleError && <View accessibilityRole="alert" style={{ gap: 6 }}><Text style={{ color: colors.red, fontSize: 12 }}>{c.errorMessage}</Text><Button secondary onPress={() => c.setSetup(true)}>Review ride</Button></View>}
    <ModalDialog visible={c.setup} onClose={() => c.setSetup(false)} closeLabel="Close ride setup" testID="ride-setup-sheet" style={{ maxHeight: '90%', width: '100%', maxWidth: 600, alignSelf: 'center', backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderTopLeftRadius: 16, borderTopRightRadius: 16, paddingBottom: Math.max(12, insets.bottom) }}>
      <View style={{ paddingHorizontal: 16, paddingVertical: 10, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}><Text accessibilityRole="header" style={{ color: colors.text, fontSize: 18, fontWeight: '600' }}>Ride setup</Text><Button onPress={() => c.setSetup(false)}>Done</Button></View>
      <ScrollView showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: 8, gap: 16 }}>
        {connection}
        {state.supported && <View style={{ borderTopWidth: 1, borderColor: colors.border, paddingTop: 14, gap: 12 }}>
          {c.errorMessage && c.errorMessage !== c.dismissedError && <View accessibilityRole="alert" style={{ gap: 8 }}><Text style={{ color: colors.red }}>{c.errorMessage}</Text><Button secondary onPress={() => { c.setDismissedError(c.errorMessage); workout.clearError(); }}>Dismiss</Button></View>}
          {!c.active ? <>
            {state.capabilities.foregroundOnly && <Body muted>Keep this browser tab visible while recording.</Body>}
            {c.useWatch && !state.watch.installed && <Text style={{ color: colors.warning, fontSize: 12 }}>Install Power Log on your Watch.</Text>}
            {c.permissionVisible && <View style={{ gap: 6 }}>
              {workout.permissionsError && <Text style={{ color: colors.red, fontSize: 12 }}>{workout.permissionsError}</Text>}
              {c.permission.action === 'settings' && <Text style={{ color: colors.muted, fontSize: 12 }}>{c.permission.detail}</Text>}
              {c.permission.action === 'unavailable' ? <Text style={{ color: colors.red }}>{c.permission.label}</Text> : <Button secondary disabled={busy} onPress={c.action(async () => {
                if (c.permission.action === 'request') await workouts.requestPermissions(c.options);
                else if (c.permission.action === 'check') await workout.refreshPermissions();
                else if (c.permission.settingsTarget === 'health') Alert.alert('Health access', 'Open Health → your profile → Apps → Power Log to review access.');
                else await Linking.openSettings();
              })}>{c.permission.label}</Button>}
            </View>}
            <Body muted>{c.modeSummary} · <Link href="/settings" style={{ color: colors.accent }}>Change</Link></Body>
            <Button disabled={busy || !workout.optionsReady || !c.sourceReady || (c.useWatch && !state.watch.installed)} onPress={c.start}>{busy ? 'Starting…' : 'Start ride'}</Button>
          </> : <>
            <View style={styles.row}>
              <Chip tone={state.phase === 'running' ? 'accent' : state.phase === 'recoverable' || state.phase === 'failed' ? 'warning' : 'neutral'}>{phaseChips[state.phase] ?? state.phase}</Chip>
              <Chip>{state.indoor ? 'Indoor' : 'Outdoor'}</Chip>
              {state.useWatch && <Icon name="watch" color={colors.accent} />}
            </View>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12 }}>
              <Metric label="Time" value={formatDuration(state.timerSeconds)} unit="" />
            </View>
            <View style={styles.row}>
              {(state.phase === 'recoverable' || state.phase === 'finishing' || state.pendingAction) && state.id && <Button secondary disabled={c.recoveryBusy} onPress={c.recover}>Retry</Button>}
              {['preparing', 'recoverable'].includes(state.phase) && <Button danger disabled={c.recoveryBusy || !c.canRequestStop} onPress={c.stopUnconfirmed}>Stop</Button>}
            </View>
            {(state.phase === 'preparing' || state.phase === 'finishing' || state.pendingAction) && <View style={styles.row}><ActivityIndicator size="small" color={colors.accent} /><Body muted>{state.phase === 'preparing' ? 'Starting ride…' : state.phase === 'finishing' ? c.finish.detail : state.recoveryState === 'checking' ? 'Checking ride recovery…' : state.pendingAction ? 'Waiting for the ride to respond…' : 'Updating ride…'}</Body></View>}
            {state.recoveryMessage && <Body muted>{state.recoveryMessage}</Body>}
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12 }}>{([['Bike', c.bikePresentation], ['Heart rate', c.sources.heartRate], ['GPS', c.sources.gps]] as const).map(([title, presentation]) => {
              if (typeof presentation === 'string') return null;
              return <Text key={String(title)} style={{ fontSize: 12, color: presentation.tone === 'ready' ? colors.accent : presentation.tone === 'warning' ? colors.warning : colors.muted }}>{String(title)} · {presentation.label}</Text>;
            })}</View>
          </>}
          {state.warnings.length > 0 && c.active && <View style={{ gap: 6 }}><Button secondary onPress={() => c.setShowNotices(value => !value)}>{c.showNotices ? 'Hide notices' : `Ride notices · ${state.warnings.length}`}</Button>{c.showNotices && state.warnings.map(warning => <Body key={warning} muted>{warning}</Body>)}</View>}
        </View>}
      </ScrollView>
    </ModalDialog>
    <ModalDialog visible={c.finishVisible} onClose={() => { if (!busy) c.setFinishTarget(null); }} closeLabel="Keep recording" testID="finish-ride-sheet" style={{ width: '100%', maxWidth: 600, alignSelf: 'center', backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderTopLeftRadius: 16, borderTopRightRadius: 16 }}>
      <ScrollView testID="finish-ride-scroll" style={{ flexShrink: 1 }} contentContainerStyle={{ padding: 20, paddingBottom: Math.max(20, insets.bottom), gap: 12 }}>
        <Text accessibilityRole="header" style={{ color: colors.text, fontSize: 20, fontWeight: '600' }}>Finish this ride?</Text>
        <Body>Save the ride, or permanently discard it from Power Log.</Body>
        {workout.error && <Text accessibilityRole="alert" style={{ color: colors.red }}>{workout.error}</Text>}
        {!c.confirmDiscard ? <>
          <Button disabled={!c.finishAvailable} onPress={c.finishRide(false)}>Save ride</Button>
          <Button secondary disabled={!c.finishAvailable} onPress={() => c.setConfirmDiscard(true)}>Discard ride</Button>
          <Button secondary disabled={busy} onPress={() => c.setFinishTarget(null)}>Keep recording</Button>
        </> : <>
          <Body>Discarding removes this ride for good. Nothing will be saved.</Body>
          <Button danger disabled={!c.finishAvailable} onPress={c.finishRide(true)}>Discard for good</Button>
          <Button secondary disabled={busy} onPress={() => c.setConfirmDiscard(false)}>Back</Button>
        </>}
      </ScrollView>
    </ModalDialog>
  </View>;
}
