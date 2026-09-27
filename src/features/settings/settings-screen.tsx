import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link } from 'expo-router';
import { Platform, Pressable, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppShell } from '../../components/app-shell';
import { ExternalLink } from '../../components/external-link';
import { ModalDialog } from '../../components/modal-dialog';
import { StableLabel } from '../../components/stable-label';
import { Toggle } from '../../components/toggle';
import { Button, colors, headingLevel } from '../../components/ui';
import { APP_VERSION, ISSUES_URL, SOURCE_URL, WEB_NOTICES_URL } from '../../core/app-info';
import { DISTANCE_SOURCE_LABELS, type DistanceSource } from '../../core/distance';
import type { SpeedUnit } from '../../core/monitor';
import { useMonitorPreferences } from '../../services/monitor-preferences';
import { useWorkout } from '../../services/workout-context';
import { effectiveWorkoutOptions, type StoragePersistence } from '../../core/workouts';
import { workoutHealthPresentation } from '../../core/workout-presentation';
import { workouts } from '../../services/workouts';

const persistenceLabels: Record<StoragePersistence, string> = {
  persisted: 'Protected from automatic browser cleanup',
  'not persisted': 'The browser may clear saved rides when storage runs low',
  unavailable: 'Protection status unavailable in this browser',
};
type Choice = { value: string; label: string };
type Selection = 'speed' | 'distance' | 'environment' | 'sampleHz';
type Focusable = { focus?: () => void };

export function distanceSourceChoices(platform: typeof Platform.OS): Choice[] {
  const available =
    platform === 'web'
      ? ['auto', 'controller']
      : platform === 'android'
        ? ['auto', 'gps:phone', 'controller']
        : Object.keys(DISTANCE_SOURCE_LABELS);
  return (available as DistanceSource[]).map(value => ({ value, label: DISTANCE_SOURCE_LABELS[value] }));
}

const choices: Record<Selection, readonly Choice[]> = {
  speed: ['km/h', 'mph', 'm/s'].map(value => ({ value, label: value })),
  distance: distanceSourceChoices(Platform.OS),
  environment: [
    { value: 'outdoor', label: 'Outdoor' },
    { value: 'indoor', label: 'Indoor' },
  ],
  sampleHz: [2, 4, 8].map(value => ({ value: String(value), label: `${value} Hz` })),
};
const choiceLabel = (id: Selection, value: string) =>
  choices[id].find(choice => choice.value === value)?.label ??
  (id === 'distance' ? DISTANCE_SOURCE_LABELS[value as DistanceSource] : undefined) ??
  value;
const titles: Record<Selection, string> = {
  speed: 'Speed units',
  distance: 'Distance source',
  environment: 'Ride type',
  sampleHz: 'Bike sample rate',
};

function SettingsGroup({
  title,
  caption,
  testID,
  children,
}: {
  title: string;
  caption: string;
  testID: string;
  children: ReactNode;
}) {
  return (
    <View
      testID={testID}
      style={{
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: 12,
        padding: 16,
        gap: 8,
      }}
    >
      <View style={{ gap: 4, marginBottom: 4 }}>
        <Text
          accessibilityRole="header"
          {...headingLevel(2)}
          style={{ fontSize: 18, fontWeight: '600', color: colors.text }}
        >
          {title}
        </Text>
        <Text style={{ fontSize: 12, color: colors.muted }}>{caption}</Text>
      </View>
      {children}
    </View>
  );
}

function ToggleRow({
  label,
  accessibilityLabel = label,
  value,
  onChange,
}: {
  label: string;
  accessibilityLabel?: string;
  value: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <View style={{ minHeight: 52, flexDirection: 'row', alignItems: 'center', gap: 12 }}>
      <Text style={{ flex: 1, minWidth: 0, fontSize: 14, color: colors.text }}>{label}</Text>
      <Toggle label={accessibilityLabel} value={value} onChange={onChange} />
    </View>
  );
}

export function SettingsScreen() {
  const settings = useMonitorPreferences();
  const workout = useWorkout();
  const { width, fontScale } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const [selection, setSelection] = useState<Selection | null>(null);
  const [selectionOpen, setSelectionOpen] = useState(false);
  const [dismissedError, setDismissedError] = useState<string | null>(null);
  const [exampleNotice, setExampleNotice] = useState<string | null>(null);
  const [persistence, setPersistence] = useState<StoragePersistence>('unavailable');
  useEffect(() => {
    if (!workouts.storagePersistence) return;
    let active = true;
    void workouts.storagePersistence().then(status => {
      if (active) setPersistence(status);
    });
    return () => {
      active = false;
    };
  }, [workout.state.id]);
  const controls = useRef<Partial<Record<Selection, Focusable | null>>>({});
  const optionControls = useRef<Record<string, Focusable | null>>({});
  const openedControl = useRef<Selection | null>(null);
  const { preferences } = settings;
  const options = preferences.workoutOptions;
  const { capabilities } = workout.state;
  const effective = effectiveWorkoutOptions(options, capabilities);
  const health = workoutHealthPresentation(options, capabilities);
  const values: Record<Selection, string> = {
    speed: preferences.speedUnit,
    distance: preferences.distanceSource,
    environment: options.indoor ? 'indoor' : 'outdoor',
    sampleHz: String(preferences.sampleHz),
  };
  const ready = settings.ready && workout.ready;
  const error = settings.error ?? (!workout.ready ? workout.error : null);
  const showError = Boolean(error && error !== dismissedError);
  if (!error && dismissedError !== null) setDismissedError(null);
  const columns = Platform.OS === 'web' && width >= 900 * fontScale;
  const close = () => {
    setSelectionOpen(false);
    // Web modal dismissal should return keyboard focus to the selected setting.
    if (Platform.OS === 'web')
      requestAnimationFrame(() => {
        if (openedControl.current) controls.current[openedControl.current]?.focus?.();
      });
  };
  const choose = (value: string) => {
    if (selection === 'speed') settings.setSpeedUnit(value as SpeedUnit);
    else if (selection === 'distance') settings.setDistanceSource(value as DistanceSource);
    else if (selection === 'environment')
      settings.setWorkoutOptions(previous => ({
        ...previous,
        indoor: value === 'indoor',
        recordGPS: previous.recordGPS ?? !previous.indoor,
      }));
    else if (selection === 'sampleHz') settings.setSampleHz(Number(value) as 2 | 4 | 8);
    close();
  };
  const optionKeyDown = (event: KeyboardEvent, index: number) => {
    if (!selection) return;
    const options = choices[selection];
    if (event.key === ' ' || event.key === 'Enter') {
      event.preventDefault();
      choose(options[index]!.value);
    } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const next =
        event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? options.length - 1
            : (index + (event.key === 'ArrowDown' ? 1 : options.length - 1)) % options.length;
      optionControls.current[options[next]!.value]?.focus?.();
    }
  };
  const picker = (id: Selection) => (
    <Pressable
      key={id}
      ref={node => {
        controls.current[id] = node;
      }}
      testID={`settings-${id}`}
      accessibilityRole="button"
      accessibilityLabel={`${titles[id]}, ${choiceLabel(id, values[id])}`}
      accessibilityState={{ expanded: selectionOpen && selection === id }}
      onPress={() => {
        openedControl.current = id;
        setSelection(id);
        setSelectionOpen(true);
      }}
      style={({ pressed }) => ({ minHeight: 60, paddingVertical: 8, opacity: pressed ? 0.75 : 1, gap: 6 })}
    >
      <Text style={{ color: colors.text, fontSize: 14 }}>{titles[id]}</Text>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
        <View style={{ flex: 1, minWidth: 0 }}>
          <StableLabel
            value={choiceLabel(id, values[id])}
            variants={choices[id].map(choice => choice.label)}
            style={{ color: colors.muted, fontSize: 13 }}
          />
        </View>
        <Text
          aria-hidden
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
          style={{ color: colors.muted, fontSize: 18, width: 16, textAlign: 'center' }}
        >
          ›
        </Text>
      </View>
    </Pressable>
  );

  return (
    <AppShell>
      <View testID="settings-screen" style={{ width: '100%', maxWidth: 1040, alignSelf: 'center', gap: 16 }}>
        <Text
          key={fontScale}
          accessibilityRole="header"
          style={{ color: colors.text, fontSize: 18, fontWeight: '600', letterSpacing: -0.6 }}
        >
          Settings
        </Text>
        {!ready ? (
          <View
            testID="settings-loading"
            style={{
              minHeight: 280,
              padding: 16,
              backgroundColor: colors.surface,
              borderWidth: 1,
              borderColor: colors.border,
              borderRadius: 12,
            }}
          >
            <Text style={{ color: colors.muted, fontSize: 14 }}>Loading settings…</Text>
          </View>
        ) : (
          <View style={{ gap: 16 }}>
            <View key={fontScale} style={{ flexDirection: columns ? 'row' : 'column', alignItems: 'stretch', gap: 16 }}>
              <View style={{ flex: columns ? 1 : undefined, minWidth: 0, gap: 16 }}>
                <SettingsGroup title="Display & analysis" caption="All rides" testID="settings-display">
                  {picker('speed')}
                  {picker('distance')}
                </SettingsGroup>
              </View>
              <View style={{ flex: columns ? 1 : undefined, minWidth: 0 }}>
                <SettingsGroup title="Recording" caption="Rides started here" testID="settings-recording">
                  {picker('environment')}
                  {capabilities.watchWorkout && (
                    <ToggleRow
                      label="Apple Watch"
                      accessibilityLabel="Use Apple Watch"
                      value={effective.useWatch}
                      onChange={useWatch => settings.setWorkoutOptions(previous => ({ ...previous, useWatch }))}
                    />
                  )}
                  {capabilities.gps && (
                    <ToggleRow
                      label="GPS route"
                      accessibilityLabel="Record GPS route"
                      value={effective.recordGPS}
                      onChange={recordGPS => settings.setWorkoutOptions(previous => ({ ...previous, recordGPS }))}
                    />
                  )}
                  {(capabilities.phoneHealth || capabilities.watchHealth) && (
                    <ToggleRow
                      label={health.label}
                      accessibilityLabel={`Save to ${health.label}`}
                      value={options.saveToHealth !== false}
                      onChange={saveToHealth => settings.setWorkoutOptions(previous => ({ ...previous, saveToHealth }))}
                    />
                  )}
                  {health.detail && <Text style={{ color: colors.muted, fontSize: 12 }}>{health.detail}</Text>}
                  {picker('sampleHz')}
                </SettingsGroup>
              </View>
            </View>
            {workouts.storagePersistence && (
              <SettingsGroup title="Browser storage" caption="Saved rides on this device" testID="settings-storage">
                <Text style={{ color: colors.text, fontSize: 14 }}>{persistenceLabels[persistence]}</Text>
              </SettingsGroup>
            )}
            {__DEV__ && workouts.addExampleRides && (
              <SettingsGroup title="Developer" caption="Development builds only" testID="settings-developer">
                <Button
                  secondary
                  disabled={workout.busy}
                  onPress={() => {
                    setExampleNotice(null);
                    void workout.run(async () => {
                      try {
                        const added = await workouts.addExampleRides!();
                        setExampleNotice(
                          added
                            ? `${added} example ${added === 1 ? 'ride' : 'rides'} added to History.`
                            : 'The example rides are already in History.',
                        );
                      } catch (error) {
                        setExampleNotice(error instanceof Error ? error.message : String(error));
                        throw error;
                      }
                    });
                  }}
                >
                  Add example rides
                </Button>
                {exampleNotice && <Text style={{ color: colors.muted, fontSize: 13 }}>{exampleNotice}</Text>}
              </SettingsGroup>
            )}
          </View>
        )}
        <SettingsGroup title="About" caption={`Power Log ${APP_VERSION}`} testID="settings-about">
          <Link
            href="/privacy"
            style={{ color: colors.accent, fontSize: 14, lineHeight: 20, paddingVertical: 12, minHeight: 44 }}
          >
            Privacy policy
          </Link>
          <ExternalLink href={ISSUES_URL}>GitHub Issues</ExternalLink>
          <ExternalLink href={SOURCE_URL}>Source repository</ExternalLink>
          <ExternalLink href={WEB_NOTICES_URL}>Third-party notices</ExternalLink>
          <Text style={{ color: colors.muted, fontSize: 12 }}>Notices for the published website build.</Text>
        </SettingsGroup>
      </View>
      <ModalDialog
        visible={selectionOpen && !showError}
        onClose={close}
        closeLabel="Close setting options"
        testID="settings-options"
        style={{
          width: '100%',
          maxWidth: 480,
          alignSelf: 'center',
          backgroundColor: colors.surface,
          borderWidth: 1,
          borderColor: colors.border,
          borderTopLeftRadius: 16,
          borderTopRightRadius: 16,
        }}
      >
        <View style={{ padding: 16, flexDirection: 'row', alignItems: 'center', gap: 12 }}>
          <Text
            accessibilityRole="header"
            style={{ fontSize: 18, fontWeight: '600', color: colors.text, flex: 1, minWidth: 0 }}
          >
            {selection ? titles[selection] : ''}
          </Text>
          <Button secondary onPress={close}>
            Done
          </Button>
        </View>
        <ScrollView
          showsHorizontalScrollIndicator={false}
          style={{ flexShrink: 1 }}
          contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: Math.max(16, insets.bottom) }}
        >
          {selection && (
            <View accessibilityRole="radiogroup" accessibilityLabel={titles[selection]}>
              {choices[selection].map((choice, index) => (
                <Pressable
                  key={choice.value}
                  ref={node => {
                    optionControls.current[choice.value] = node;
                  }}
                  accessibilityRole="radio"
                  accessibilityLabel={choice.label}
                  accessibilityState={{ checked: choice.value === values[selection] }}
                  aria-checked={choice.value === values[selection]}
                  onPress={() => choose(choice.value)}
                  {...(Platform.OS === 'web'
                    ? { onKeyDown: (event: KeyboardEvent) => optionKeyDown(event, index) }
                    : {})}
                  style={({ pressed }) => ({
                    minHeight: 52,
                    paddingVertical: 12,
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: 12,
                    opacity: pressed ? 0.75 : 1,
                  })}
                >
                  <Text style={{ color: colors.text, fontSize: 15, flex: 1, minWidth: 0 }}>{choice.label}</Text>
                  <Text
                    aria-hidden
                    accessibilityElementsHidden
                    importantForAccessibility="no-hide-descendants"
                    style={{ width: 24 * fontScale, color: colors.accent, fontSize: 18, textAlign: 'center' }}
                  >
                    {choice.value === values[selection] ? '✓' : ''}
                  </Text>
                </Pressable>
              ))}
            </View>
          )}
        </ScrollView>
      </ModalDialog>
      <ModalDialog
        visible={showError}
        onClose={() => setDismissedError(error)}
        closeLabel="Dismiss settings error"
        testID="settings-error"
        style={{
          width: '100%',
          maxWidth: 480,
          alignSelf: 'center',
          backgroundColor: colors.surface,
          borderWidth: 1,
          borderColor: colors.border,
          borderTopLeftRadius: 16,
          borderTopRightRadius: 16,
        }}
      >
        <ScrollView
          style={{ flexShrink: 1 }}
          contentContainerStyle={{ padding: 20, paddingBottom: Math.max(20, insets.bottom), gap: 16 }}
        >
          <Text accessibilityRole="header" style={{ fontSize: 18, fontWeight: '600', color: colors.text }}>
            {settings.error
              ? settings.error.startsWith('Could not load')
                ? 'Settings unavailable'
                : 'Settings not saved'
              : 'Recording options unavailable'}
          </Text>
          <Text accessibilityRole="alert" style={{ color: colors.red, fontSize: 14, lineHeight: 21 }}>
            {error}
          </Text>
          {!workout.ready && (
            <Button
              disabled={workout.busy}
              onPress={() => {
                void workout.run(async () => {});
              }}
            >
              Retry
            </Button>
          )}
          <Button secondary onPress={() => setDismissedError(error)}>
            Dismiss
          </Button>
        </ScrollView>
      </ModalDialog>
    </AppShell>
  );
}
