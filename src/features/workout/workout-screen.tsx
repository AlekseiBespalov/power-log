import { presentationRecoveryStopOffered } from '../../../shared/js';
import { createContext, useContext, useState, useMemo, type ReactNode } from 'react';
import { bikeDisplayName } from '../../core/bikes';
import { ActivityIndicator, Alert, Linking, Platform, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Link } from 'expo-router';
import { Body, Button, Chip, colors, formatDuration, Metric, styles, type, Pressable } from '../../components/ui';
import { Icon } from '../../components/icon';
import { ModalDialog } from '../../components/modal-dialog';
import { useMonitorPreferences } from '../../services/monitor-preferences';
import { effectiveWorkoutOptions, workoutInProgress } from '../../core/workouts';
import { failurePresentation } from '../../core/failures';
import { workoutPermissionAction } from '../../services/workout-permission-action';
import {
  workoutFinishPresentation,
  workoutOwnershipPresentation,
  workoutSourcePresentation,
  workoutHealthResultPresentation,
  workoutDistanceSelectionLabel,
  workoutStartReadiness,
} from '../../core/workout-presentation';
import { bikeConnectionLabel } from '../../core/telemetry-display';
import { haptics } from '../../services/haptics';
import { useSession } from '../../services/session-context';
import { useWorkout } from '../../services/workout-context';
import { workouts } from '../../services/workouts';
import { externalRideDecision } from '../../services/workout-external-actions';
import type { WorkoutActionKind } from '../../services/workout-actions';
import {
  RideControlActions,
  browserDiscardAvailable,
  type PresentedRideAction,
  type RideHaptic,
} from '../../services/ride-control-actions';

const phaseChips: Record<string, string> = {
  preparing: 'Starting',
  running: 'Running',
  paused: 'Paused',
  interrupted: 'Needs attention',
  finishing: 'Finishing',
  completed: 'Saved',
  failed: 'Failed',
};

const failureText = (code: string | null | undefined) => (code ? failurePresentation(code).text : null);

function rideHaptic(outcome: RideHaptic) {
  switch (outcome) {
    case 'success':
      haptics.success();
      break;
    case 'warning':
      haptics.warning();
      break;
    case 'impact':
      haptics.impact();
      break;
    case 'lightImpact':
      haptics.impact('light');
      break;
  }
}

function useRideControlState() {
  const workout = useWorkout(),
    session = useSession(),
    settings = useMonitorPreferences();
  const { state, busy } = workout;
  const [controls] = useState(() => new RideControlActions(rideHaptic));
  const finish = useMemo(() => workoutFinishPresentation(state), [state]);
  const options = useMemo(
    () => effectiveWorkoutOptions({ ...workout.options, sampleHz: session.hz }, state.capabilities),
    [workout.options, session.hz, state.capabilities],
  );
  const healthResult = useMemo(
    () => workoutHealthResultPresentation({ ...state, healthProvider: state.capabilities.healthProvider ?? undefined }),
    [state],
  );
  const [setup, setSetup] = useState(false);
  const confirmationIdentity = JSON.stringify([
    state.id,
    ['running', 'paused'].includes(state.phase) ? 'active' : state.phase,
  ]);
  const [localFinish, setLocalFinish] = useState<{ target: string; identity: string } | null>(null);
  const localFinishTarget = localFinish?.identity === confirmationIdentity ? localFinish.target : null;
  const externalFinish = state.externalFinish;
  const finishTarget = externalFinish?.rideId ?? localFinishTarget;
  const setFinishTarget = (target: string | null) => {
    setLocalFinish(target === null ? null : { target, identity: confirmationIdentity });
    if (target === null && externalFinish) workout.cancelFinish();
  };
  const [discardTarget, setDiscardTarget] = useState<string | null>(null);
  if (localFinish && localFinish.identity !== confirmationIdentity) {
    setLocalFinish(null);
    setDiscardTarget(null);
  }
  const finishConfirmationKey = externalFinish?.controlToken ?? localFinish?.identity ?? null;
  const confirmDiscard = discardTarget !== null && discardTarget === finishConfirmationKey;
  const setConfirmDiscard = (confirm: boolean) => setDiscardTarget(confirm ? finishConfirmationKey : null);
  const [dismissedError, setDismissedError] = useState<string | null>(null);
  const errorMessage = workout.error ?? state.failure?.message ?? null;
  const active =
    workoutInProgress(state.phase) ||
    Boolean(state.id && (state.pendingAction === 'discard' || state.pendingAction === 'stop'));
  const bikeDisplay = active ? session.display : session.state.status === 'connected' ? 'live' : 'unavailable';
  const bikeName = bikeDisplayName({
    id: session.state.deviceId,
    name: session.state.deviceName,
    controllerModel: session.state.controllerModel,
  });
  const bikeReady =
    (session.adapter.kind === 'native' || Platform.OS === 'web') && session.state.status === 'connected';
  const bikeDisplayReady = bikeReady || (active && bikeDisplay === 'held');
  const sources = useMemo(
    () => workoutSourcePresentation(state, options, bikeDisplayReady, bikeDisplay),
    [state, options, bikeDisplayReady, bikeDisplay],
  );
  const bikePresentation = sources.bike;
  const ownershipMessage = workoutOwnershipPresentation(state);
  const recoveryBusy = (busy && !workout.recoveryAvailable) || Boolean(state.foreignOwner);
  const recoveryActionBusy = busy && !workout.recoveryAvailable;
  const adapterBusy = (action: PresentedRideAction) => (action === 'stopUnconfirmed' ? recoveryActionBusy : busy);
  const availability = useMemo(
    () => ({
      pause: controls.availability('pause', state, null, busy),
      resume: controls.availability('resume', state, null, busy),
      lap: controls.availability('lap', state, null, busy),
      stop: controls.availability('stop', state, state.id, busy),
      discard: controls.availability('discard', state, state.id, busy),
      stopUnconfirmed: controls.availability('stopUnconfirmed', state, null, recoveryActionBusy),
    }),
    [controls, state, busy, recoveryActionBusy],
  );
  const permission = useMemo(
    () => workoutPermissionAction(workout.permissions, options),
    [workout.permissions, options],
  );
  const permissionVisible =
    workout.permissionsError !== null || (workout.permissions !== null && permission.action !== 'none');
  const action =
    (
      operation: (current: () => boolean) => Promise<unknown>,
      kind?: WorkoutActionKind,
      haptic?: () => void,
      presented?: PresentedRideAction,
      confirmation: string | null = null,
    ) =>
    () => {
      setDismissedError(null);
      void workout.run(async flight => {
        if (presented) {
          await controls.run(
            presented,
            state,
            () => operation(flight.current),
            confirmation,
            state.id,
            adapterBusy(presented),
            flight.signal,
          );
          if (presented === 'start' && flight.current()) setSetup(false);
          return;
        }
        try {
          await operation(flight.current);
        } catch (error) {
          if (flight.current()) haptics.warning();
          throw error;
        }
        if (flight.current()) haptic?.();
      }, kind);
    };
  const start = action(
    async current => {
      const preferences = await settings.prepareRecording();
      if (!current()) return;
      await workouts.start(
        effectiveWorkoutOptions(
          {
            ...preferences.workoutOptions,
            sampleHz: preferences.sampleHz,
            distanceSource: preferences.distanceSource,
            batteryConsumptionSource: preferences.batteryConsumptionSource,
          },
          state.capabilities,
        ),
      );
    },
    'start',
    undefined,
    'start',
  );
  const readiness = useMemo(
    () => workoutStartReadiness(state, options, bikeReady, busy),
    [state, options, bikeReady, busy],
  );
  const startAvailable = readiness.available;
  const startUnavailableReason = failureText(readiness.error);
  const needsSetup = !readiness.available || permissionVisible;
  const connectionLabel = bikeConnectionLabel(session.state.status, bikeDisplay);
  const findBike = () => {
    setSetup(true);
    void session.run(session.scan);
  };
  const findBikeDisabled =
    session.busy ||
    session.adapter.kind === 'unavailable' ||
    ['scanning', 'connecting', 'reconnecting'].includes(session.state.status) ||
    (active && Boolean(session.state.deviceId));
  const visibleError = errorMessage && errorMessage !== dismissedError;
  const askToFinish = () => {
    if (state.id) {
      workout.clearError();
      setConfirmDiscard(false);
      setFinishTarget(state.id);
    }
  };
  const finishVisible =
    finishTarget !== null &&
    finishTarget === state.id &&
    (['running', 'paused', 'interrupted'].includes(state.phase) || browserDiscardAvailable(state)) &&
    (!externalFinish || externalRideDecision(state, externalFinish, false).outcome === 'confirmationRequired');
  const finishAvailable = finishVisible && availability.stop.admitted;
  const discardAvailable = finishVisible && availability.discard.admitted;
  const finishRide = (discard: boolean) =>
    action(
      async current => {
        if (!finishTarget || !finishVisible || (discard && !confirmDiscard)) return;
        if (externalFinish) await workout.finishRide(finishTarget, discard);
        else await (discard ? workouts.discard(finishTarget) : workouts.stop(finishTarget));
        if (current()) setLocalFinish(null);
      },
      undefined,
      undefined,
      discard ? 'discard' : 'stop',
      finishTarget,
    );
  const pause = action(() => workouts.pause(state.id ?? undefined), undefined, undefined, 'pause');
  const resume = action(() => workouts.resume(state.id ?? undefined), undefined, undefined, 'resume');
  const lap = action(() => workouts.lap(state.id ?? undefined), undefined, undefined, 'lap');
  const stopUnconfirmed = action(() => workouts.stop(state.id ?? undefined), undefined, undefined, 'stopUnconfirmed');
  return {
    workout,
    session,
    state,
    busy,
    finish,
    options,
    healthResult,
    setup,
    setSetup,
    finishTarget,
    setFinishTarget,
    confirmDiscard,
    setConfirmDiscard,
    dismissedError,
    setDismissedError,
    errorMessage,
    active,
    bikeDisplay,
    bikeName,
    sources,
    bikePresentation,
    availability,
    ownershipMessage,
    findBike,
    findBikeDisabled,
    recoveryBusy,
    permission,
    permissionVisible,
    action,
    start,
    startAvailable,
    startUnavailableReason,
    needsSetup,
    connectionLabel,
    visibleError,
    askToFinish,
    finishVisible,
    finishAvailable,
    discardAvailable,
    finishRide,
    pause,
    resume,
    lap,
    stopUnconfirmed,
  };
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

export function RideActionBar({ inline = false }: { inline?: boolean }) {
  const c = useRideControls();
  const { state, busy, workout } = c;
  const { fontScale } = useWindowDimensions();
  const showSetup = c.needsSetup || c.bikeDisplay === 'unavailable';
  const stateTone = state.phase === 'running' ? 'accent' : state.phase === 'paused' ? 'warning' : 'neutral';
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
      {c.active && (
        <View style={{ flexGrow: 1, flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: colors.text, fontSize: type.value, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
            {formatDuration(state.timerSeconds)}
          </Text>
          <View testID="ride-state-badge">
            <Chip tone={stateTone}>{phaseChips[state.phase] ?? state.phase}</Chip>
          </View>
        </View>
      )}
      {state.supported && !c.active && (
        <Button
          testID="start-ride"
          block={!inline}
          secondary={c.bikeDisplay === 'unavailable'}
          disabled={busy || !workout.optionsReady}
          icon={<Icon name="play" size={18} color={c.bikeDisplay === 'unavailable' ? colors.text : colors.bg} />}
          onPress={showSetup ? () => c.setSetup(true) : c.start}
        >
          {showSetup ? 'Set up ride' : 'Start ride'}
        </Button>
      )}
      {state.supported && c.active && (
        <>
          {['running', 'paused', 'interrupted'].includes(state.phase) && (
            <View style={{ flexDirection: 'row', width: inline ? 360 * fontScale : '100%', maxWidth: '100%', gap: 8 }}>
              <RideControlButton
                label={state.phase === 'paused' ? 'Resume' : 'Pause'}
                icon={<Icon name={state.phase === 'paused' ? 'play' : 'pause'} size={18} color={colors.text} />}
                disabled={!c.availability[state.phase === 'paused' ? 'resume' : 'pause'].admitted}
                reason={failureText(c.availability[state.phase === 'paused' ? 'resume' : 'pause'].error)}
                onPress={state.phase === 'paused' ? c.resume : c.pause}
              />
              <RideControlButton
                label="Lap"
                disabled={!c.availability.lap.admitted}
                reason={failureText(c.availability.lap.error)}
                icon={<Icon name="lap" size={18} color={colors.text} />}
                onPress={c.lap}
              />
              <RideControlButton
                label="Finish"
                danger
                disabled={!c.availability.stop.admitted}
                reason={failureText(c.availability.stop.error)}
                icon={<Icon name="stop" size={18} color={colors.bg} />}
                onPress={c.askToFinish}
              />
            </View>
          )}
          {state.phase === 'interrupted' && (
            <Button secondary onPress={() => c.setSetup(true)}>
              Review ride
            </Button>
          )}
          {(['preparing', 'finishing'].includes(state.phase) ||
            state.pendingAction === 'stop' ||
            state.pendingAction === 'discard') && (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <ActivityIndicator size="small" color={colors.accent} />
              <Text style={{ color: colors.muted, fontSize: type.body }}>
                {state.phase === 'preparing' ? 'Starting…' : c.finish.label}
              </Text>
            </View>
          )}
        </>
      )}
      {!state.supported && Platform.OS !== 'web' && (
        <Text style={{ color: colors.muted, fontSize: type.caption }}>
          Ride recording is unavailable on this device.
        </Text>
      )}
    </View>
  );
}

function RideControlButton({
  label,
  icon,
  disabled,
  reason,
  danger = false,
  onPress,
}: {
  label: string;
  icon: ReactNode;
  disabled: boolean;
  reason?: string | null;
  danger?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled }}
      accessibilityHint={disabled ? (reason ?? undefined) : undefined}
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
        <Text style={[styles.buttonText, { flexShrink: 1, color: danger ? colors.bg : colors.text }]}>{label}</Text>
      </View>
    </Pressable>
  );
}

export function RideEmptyState() {
  const c = useRideControls();
  return (
    <View style={{ gap: 12 }}>
      <Text accessibilityRole="header" style={{ color: colors.text, fontSize: type.title, fontWeight: '600' }}>
        No bike connected
      </Text>
      <Body muted>Turn on your bike, then find it to see live readings.</Body>
      <Button disabled={c.findBikeDisabled} onPress={c.findBike}>
        Find bike
      </Button>
    </View>
  );
}

export function RideStatus({ connection }: { connection: ReactNode }) {
  const c = useRideControls();
  const { workout, state, busy } = c;
  const { fontScale } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const { preferences } = useMonitorPreferences();
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
          accessibilityLabel={`Bike and ride setup. ${c.connectionLabel}${c.bikeDisplay !== 'unavailable' ? `. ${c.bikeName}` : ''}${c.active ? `. ${state.phase}, ${formatDuration(state.timerSeconds)}` : ''}`}
          accessibilityHint="Opens bike connection and ride options"
          accessibilityState={{ expanded: c.setup }}
          onPress={() => c.setSetup(true)}
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
          <Icon name="bike" color={c.bikeDisplay !== 'unavailable' ? colors.accent : colors.muted} />
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
            {c.active
              ? formatDuration(state.timerSeconds)
              : c.bikeDisplay !== 'unavailable'
                ? c.bikeName
                : 'Connect bike'}
          </Text>
          <View
            style={{
              width: 5,
              height: 5,
              borderRadius: 3,
              backgroundColor: c.bikeDisplay !== 'unavailable' ? colors.accent : colors.muted,
            }}
          />
          <Icon name="chevron" size={14} />
        </Pressable>
      </View>
      {!c.setup && c.visibleError && (
        <View accessibilityRole="alert" style={{ gap: 6 }}>
          <Text style={{ color: colors.red, fontSize: type.caption }}>{c.errorMessage}</Text>
          <Button secondary onPress={() => c.setSetup(true)}>
            Review ride
          </Button>
        </View>
      )}
      <ModalDialog
        visible={c.setup}
        onClose={() => c.setSetup(false)}
        closeLabel="Close ride setup"
        testID="ride-setup-sheet"
        style={{
          maxHeight: '90%',
          width: '100%',
          maxWidth: 600,
          alignSelf: 'center',
          backgroundColor: colors.surface,
          borderWidth: 1,
          borderColor: colors.border,
          borderTopLeftRadius: 16,
          borderTopRightRadius: 16,
          paddingBottom: Math.max(12, insets.bottom),
        }}
      >
        <View
          style={{
            paddingHorizontal: 16,
            paddingVertical: 10,
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 10,
          }}
        >
          <Text accessibilityRole="header" style={{ color: colors.text, fontSize: type.title, fontWeight: '600' }}>
            Ride setup
          </Text>
          <Pressable
            accessibilityRole="button"
            onPress={() => c.setSetup(false)}
            style={{ minHeight: 44, paddingHorizontal: 8, justifyContent: 'center' }}
          >
            <Text style={{ color: colors.accent, fontSize: type.control }}>Done</Text>
          </Pressable>
        </View>
        <ScrollView
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: 8, gap: 16 }}
        >
          {connection}
          {state.supported && (
            <View style={{ borderTopWidth: 1, borderColor: colors.border, paddingTop: 14, gap: 12 }}>
              {c.errorMessage && c.errorMessage !== c.dismissedError && (
                <View accessibilityRole="alert" style={{ gap: 8 }}>
                  <Text style={{ color: colors.red }}>{c.errorMessage}</Text>
                  <Button
                    secondary
                    onPress={() => {
                      c.setDismissedError(c.errorMessage);
                      workout.clearError();
                    }}
                  >
                    Dismiss
                  </Button>
                </View>
              )}
              {!c.active ? (
                <>
                  {state.capabilities.foregroundOnly && (
                    <Body muted>Keep this browser tab visible while recording.</Body>
                  )}
                  {c.permissionVisible && (
                    <View style={{ gap: 6 }}>
                      <Text
                        accessibilityRole="header"
                        style={{ color: colors.text, fontSize: type.body, fontWeight: '600' }}
                      >
                        Permissions
                      </Text>
                      {workout.permissionsError && (
                        <Text style={{ color: colors.red, fontSize: type.caption }}>{workout.permissionsError}</Text>
                      )}
                      {c.permission.action === 'settings' && (
                        <Text style={{ color: colors.muted, fontSize: type.caption }}>{c.permission.detail}</Text>
                      )}
                      {c.permission.action === 'unavailable' && !workout.permissionsError ? (
                        <Text style={{ color: colors.red }}>{c.permission.label}</Text>
                      ) : (
                        <Button
                          secondary
                          disabled={busy}
                          onPress={c.action(async current => {
                            if (workout.permissionsError) await workout.refreshPermissions(current);
                            else if (c.permission.action === 'request') await workouts.requestPermissions(c.options);
                            else if (c.permission.action === 'check' || Platform.OS === 'web')
                              await workout.refreshPermissions(current);
                            else if (c.permission.settingsTarget === 'health')
                              Alert.alert(
                                'Health access',
                                'Open Health → your profile → Apps → Power Log to review access.',
                              );
                            else await Linking.openSettings();
                          })}
                        >
                          {workout.permissionsError ? 'Check permissions' : c.permission.label}
                        </Button>
                      )}
                    </View>
                  )}
                  <Link href="/settings" asChild>
                    <Pressable
                      accessibilityRole="link"
                      accessibilityLabel="Ride settings"
                      onPress={() => c.setSetup(false)}
                      style={{ minHeight: 48, justifyContent: 'center' }}
                    >
                      <Text style={{ color: colors.accent, fontSize: type.body }}>Ride settings</Text>
                    </Pressable>
                  </Link>
                  <Button
                    testID="setup-start-ride"
                    secondary={!c.startAvailable}
                    disabled={busy || !workout.optionsReady || !c.startAvailable}
                    onPress={c.start}
                  >
                    {busy ? 'Starting…' : 'Start ride'}
                  </Button>
                  {c.startUnavailableReason && <Body muted>{c.startUnavailableReason}</Body>}
                </>
              ) : (
                <>
                  {c.ownershipMessage && <Body muted>{c.ownershipMessage}</Body>}
                  <View style={styles.row}>
                    <Chip
                      tone={
                        state.phase === 'running'
                          ? 'accent'
                          : state.phase === 'interrupted' || state.phase === 'failed'
                            ? 'warning'
                            : 'neutral'
                      }
                    >
                      {phaseChips[state.phase] ?? state.phase}
                    </Chip>
                    <Chip>{state.indoor ? 'Indoor' : 'Outdoor'}</Chip>
                    {state.useWatch && <Icon name="watch" color={colors.accent} />}
                  </View>
                  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12 }}>
                    <Metric label="Time" value={formatDuration(state.timerSeconds)} unit="" />
                  </View>
                  <View style={styles.row}>
                    <Body muted>
                      Distance source:{' '}
                      {workoutDistanceSelectionLabel(
                        state.distanceSource ?? preferences.distanceSource,
                        state.capabilities.foregroundOnly,
                      )}
                    </Body>
                    <Body muted>Fixed for this ride</Body>
                  </View>
                  <View style={styles.row}>
                    {state.phase === 'interrupted' && (
                      <Button danger disabled={!c.availability.stop.admitted} onPress={c.askToFinish}>
                        Finish or discard ride
                      </Button>
                    )}
                    {state.recoveryState !== 'absent' &&
                      presentationRecoveryStopOffered(state.phase, state.pendingAction ?? null) && (
                        <Button danger disabled={!c.availability.stopUnconfirmed.admitted} onPress={c.stopUnconfirmed}>
                          Stop
                        </Button>
                      )}
                  </View>
                  {state.phase === 'preparing' && !state.pendingAction && !c.availability.stopUnconfirmed.admitted && (
                    <Body muted>{failureText(c.availability.stopUnconfirmed.error)}</Body>
                  )}
                  {(state.phase === 'preparing' || state.phase === 'finishing' || state.pendingAction) && (
                    <View style={styles.row}>
                      <ActivityIndicator size="small" color={colors.accent} />
                      <Body muted>
                        {state.phase === 'preparing'
                          ? 'Starting ride…'
                          : state.phase === 'finishing' ||
                              state.pendingAction === 'stop' ||
                              state.pendingAction === 'discard'
                            ? c.finish.detail
                            : state.recoveryState === 'checking'
                              ? 'Checking ride recovery…'
                              : state.pendingAction
                                ? 'Waiting for the ride to respond…'
                                : 'Updating ride…'}
                      </Body>
                    </View>
                  )}
                  <View testID="workout-health-sync" style={{ gap: 6 }}>
                    <Body muted>{c.healthResult.label}</Body>
                    {c.healthResult.details.map((detail, index) => (
                      <Body key={index} muted>
                        {detail}
                      </Body>
                    ))}
                  </View>
                  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12 }}>
                    {(
                      [
                        ['Bike', c.bikePresentation],
                        ['Heart rate', c.sources.heartRate],
                        ['GPS', c.sources.gps],
                      ] as const
                    ).map(([title, presentation]) => {
                      if (typeof presentation === 'string') return null;
                      return (
                        <Text
                          key={String(title)}
                          style={{
                            fontSize: type.caption,
                            color:
                              presentation.tone === 'ready'
                                ? colors.accent
                                : presentation.tone === 'warning'
                                  ? colors.warning
                                  : colors.muted,
                          }}
                        >
                          {String(title)} · {presentation.label}
                        </Text>
                      );
                    })}
                  </View>
                </>
              )}
            </View>
          )}
        </ScrollView>
      </ModalDialog>
      <ModalDialog
        visible={c.finishVisible}
        onClose={() => {
          if (!busy) c.setFinishTarget(null);
        }}
        closeLabel="Keep recording"
        testID="finish-ride-sheet"
        style={{
          width: '100%',
          maxWidth: 600,
          alignSelf: 'center',
          backgroundColor: colors.surface,
          borderWidth: 1,
          borderColor: colors.border,
          borderTopLeftRadius: 16,
          borderTopRightRadius: 16,
        }}
      >
        <ScrollView
          testID="finish-ride-scroll"
          style={{ flexShrink: 1 }}
          contentContainerStyle={{ padding: 20, paddingBottom: Math.max(20, insets.bottom), gap: 12 }}
        >
          <Text accessibilityRole="header" style={{ color: colors.text, fontSize: type.title, fontWeight: '600' }}>
            Finish this ride?
          </Text>
          <Body>Save the ride, or permanently discard it from Power Log.</Body>
          {workout.error && (
            <Text accessibilityRole="alert" style={{ color: colors.red }}>
              {workout.error}
            </Text>
          )}
          {(!c.finishAvailable || !c.discardAvailable) && (
            <Body muted>
              {failureText(!c.finishAvailable ? c.availability.stop.error : c.availability.discard.error)}
            </Body>
          )}
          {!c.confirmDiscard ? (
            <>
              <Button disabled={!c.finishAvailable} onPress={c.finishRide(false)}>
                Save ride
              </Button>
              <Button secondary disabled={!c.discardAvailable} onPress={() => c.setConfirmDiscard(true)}>
                Discard ride
              </Button>
              <Button secondary disabled={busy} onPress={() => c.setFinishTarget(null)}>
                Keep recording
              </Button>
            </>
          ) : (
            <>
              <Body>Discarding permanently removes this ride from Power Log and ends recording.</Body>
              <Button danger disabled={!c.discardAvailable} onPress={c.finishRide(true)}>
                Discard for good
              </Button>
              <Button secondary disabled={busy} onPress={() => c.setConfirmDiscard(false)}>
                Back
              </Button>
            </>
          )}
        </ScrollView>
      </ModalDialog>
    </View>
  );
}
