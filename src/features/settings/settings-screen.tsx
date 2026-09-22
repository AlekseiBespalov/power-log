import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Platform, Pressable, ScrollView, Switch, Text, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppShell } from '../../components/app-shell';
import { ModalDialog } from '../../components/modal-dialog';
import { StableLabel } from '../../components/stable-label';
import { Button, colors } from '../../components/ui';
import { DISTANCE_SOURCE_LABELS, type DistanceSource } from '../../core/distance';
import type { SpeedUnit } from '../../core/monitor';
import { useMonitorPreferences } from '../../services/monitor-preferences';
import { useWorkout } from '../../services/workout-context';
import { workouts } from '../../services/workouts';

type Choice = { value: string; label: string };
type Selection = 'speed' | 'distance' | 'environment' | 'sampleHz';
type Focusable = { focus?: () => void };

const choices: Record<Selection, readonly Choice[]> = {
  speed: ['km/h', 'mph', 'm/s'].map(value => ({ value, label: value })),
  distance: (Object.keys(DISTANCE_SOURCE_LABELS) as DistanceSource[]).map(value => ({ value, label: DISTANCE_SOURCE_LABELS[value] })),
  environment: [{ value: 'outdoor', label: 'Outdoor' }, { value: 'indoor', label: 'Indoor' }],
  sampleHz: [2, 4, 8].map(value => ({ value: String(value), label: `${value} Hz` })),
};
const titles: Record<Selection, string> = { speed: 'Speed units', distance: 'Distance source', environment: 'Ride type', sampleHz: 'Bike sample rate' };

function SettingsGroup({ title, caption, testID, children }: { title: string; caption: string; testID: string; children: ReactNode }) {
  return <View testID={testID} style={{ backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: 12, padding: 16, gap: 8 }}>
    <View style={{ gap: 4, marginBottom: 4 }}>
      <Text accessibilityRole="header" style={{ fontSize: 18, fontWeight: '600', color: colors.text }}>{title}</Text>
      <Text style={{ fontSize: 12, color: colors.muted }}>{caption}</Text>
    </View>
    {children}
  </View>;
}

function ToggleRow({ label, accessibilityLabel = label, value, onChange }: { label: string; accessibilityLabel?: string; value: boolean; onChange: (value: boolean) => void }) {
  return <View style={{ minHeight: 52, flexDirection: 'row', alignItems: 'center', gap: 12 }}>
    <Text style={{ flex: 1, minWidth: 0, fontSize: 14, color: colors.text }}>{label}</Text>
    <Switch accessibilityLabel={accessibilityLabel} value={value} onValueChange={onChange} trackColor={{ false: colors.border, true: '#9b461d' }} thumbColor={value ? colors.accent : colors.muted} style={{ flexShrink: 0 }} />
  </View>;
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
  const controls = useRef<Partial<Record<Selection, Focusable | null>>>({});
  const optionControls = useRef<Record<string, Focusable | null>>({});
  const openedControl = useRef<Selection | null>(null);
  const { preferences } = settings;
  const options = preferences.workoutOptions;
  const values: Record<Selection, string> = {
    speed: preferences.speedUnit, distance: preferences.distanceSource,
    environment: options.indoor ? 'indoor' : 'outdoor', sampleHz: String(preferences.sampleHz),
  };
  const ready = settings.ready && workout.ready;
  const error = settings.error ?? (!workout.ready ? workout.error : null);
  const showError = Boolean(error && error !== dismissedError);
  if (!error && dismissedError !== null) setDismissedError(null);
  const columns = Platform.OS === 'web' && width >= 900 * fontScale;
  const close = () => {
    setSelectionOpen(false);
    // Web modal dismissal should return keyboard focus to the selected setting.
    if (Platform.OS === 'web') requestAnimationFrame(() => { if (openedControl.current) controls.current[openedControl.current]?.focus?.(); });
  };
  const choose = (value: string) => {
    if (selection === 'speed') settings.setSpeedUnit(value as SpeedUnit);
    else if (selection === 'distance') settings.setDistanceSource(value as DistanceSource);
    else if (selection === 'environment') settings.setWorkoutOptions(previous => ({ ...previous, indoor: value === 'indoor', recordGPS: previous.recordGPS ?? !previous.indoor }));
    else if (selection === 'sampleHz') settings.setSampleHz(Number(value) as 2 | 4 | 8);
    close();
  };
  const optionKeyDown = (event: KeyboardEvent, index: number) => {
    if (!selection) return;
    const options = choices[selection];
    if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); choose(options[index]!.value); }
    else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : options.length - 1)) % options.length;
      optionControls.current[options[next]!.value]?.focus?.();
    }
  };
  const picker = (id: Selection) => <Pressable key={id} ref={node => { controls.current[id] = node; }} testID={`settings-${id}`} accessibilityRole="button" accessibilityLabel={`${titles[id]}, ${choices[id].find(choice => choice.value === values[id])?.label ?? values[id]}`} accessibilityState={{ expanded: selectionOpen && selection === id }} onPress={() => { openedControl.current = id; setSelection(id); setSelectionOpen(true); }} style={({ pressed }) => ({ minHeight: 60, paddingVertical: 8, opacity: pressed ? 0.75 : 1, gap: 6 })}>
    <Text style={{ color: colors.text, fontSize: 14 }}>{titles[id]}</Text>
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
      <View style={{ flex: 1, minWidth: 0 }}><StableLabel value={choices[id].find(choice => choice.value === values[id])?.label ?? values[id]} variants={choices[id].map(choice => choice.label)} style={{ color: colors.muted, fontSize: 13 }} /></View>
      <Text aria-hidden accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ color: colors.muted, fontSize: 18, width: 16, textAlign: 'center' }}>›</Text>
    </View>
  </Pressable>;

  return <AppShell>
    <View testID="settings-screen" style={{ width: '100%', maxWidth: 1040, alignSelf: 'center', gap: 16 }}>
      <Text key={fontScale} accessibilityRole="header" style={{ color: colors.text, fontSize: 18, fontWeight: '600', letterSpacing: -0.6 }}>Settings</Text>
      {!ready ? <View testID="settings-loading" style={{ minHeight: 280, padding: 16, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: 12 }}><Text style={{ color: colors.muted, fontSize: 14 }}>Loading settings…</Text></View> : <View style={{ gap: 16 }}><View key={fontScale} style={{ flexDirection: columns ? 'row' : 'column', alignItems: 'stretch', gap: 16 }}>
        <View style={{ flex: columns ? 1 : undefined, minWidth: 0, gap: 16 }}>
          <SettingsGroup title="Display & analysis" caption="All rides" testID="settings-display">
            {picker('speed')}
            {picker('distance')}
          </SettingsGroup>
        </View>
        <View style={{ flex: columns ? 1 : undefined, minWidth: 0 }}>
          <SettingsGroup title="Recording" caption="Rides started here" testID="settings-recording">
            {picker('environment')}
            {workout.state.capabilities.watchWorkout && <ToggleRow label="Apple Watch" accessibilityLabel="Use Apple Watch" value={options.useWatch} onChange={useWatch => settings.setWorkoutOptions(previous => ({ ...previous, useWatch }))} />}
            {(workout.state.capabilities.gps ?? workout.state.capabilities.phoneWorkout) && <ToggleRow label="GPS route" accessibilityLabel="Record GPS route" value={options.recordGPS ?? !options.indoor} onChange={recordGPS => settings.setWorkoutOptions(previous => ({ ...previous, recordGPS }))} />}
            {workout.state.capabilities.healthKit && <ToggleRow label="Apple Health" accessibilityLabel="Save to Apple Health" value={options.saveToHealth !== false} onChange={saveToHealth => settings.setWorkoutOptions(previous => ({ ...previous, saveToHealth }))} />}
            {picker('sampleHz')}
          </SettingsGroup>
        </View>
      </View>{__DEV__ && workouts.addExampleRides && <SettingsGroup title="Developer" caption="Development builds only" testID="settings-developer">
        <Button secondary disabled={workout.busy} onPress={() => { setExampleNotice(null); void workout.run(async () => {
          try {
            const added = await workouts.addExampleRides!();
            setExampleNotice(added ? `${added} example ${added === 1 ? 'ride' : 'rides'} added to History.` : 'The example rides are already in History.');
          } catch (error) { setExampleNotice(error instanceof Error ? error.message : String(error)); throw error; }
        }); }}>Add example rides</Button>
        {exampleNotice && <Text style={{ color: colors.muted, fontSize: 13 }}>{exampleNotice}</Text>}
      </SettingsGroup>}</View>}
    </View>
    <ModalDialog visible={selectionOpen && !showError} onClose={close} closeLabel="Close setting options" testID="settings-options" style={{ width: '100%', maxWidth: 480, alignSelf: 'center', backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderTopLeftRadius: 16, borderTopRightRadius: 16 }}>
      <View style={{ padding: 16, flexDirection: 'row', alignItems: 'center', gap: 12 }}>
        <Text accessibilityRole="header" style={{ fontSize: 18, fontWeight: '600', color: colors.text, flex: 1, minWidth: 0 }}>{selection ? titles[selection] : ''}</Text>
        <Button secondary onPress={close}>Done</Button>
      </View>
      <ScrollView showsHorizontalScrollIndicator={false} style={{ flexShrink: 1 }} contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: Math.max(16, insets.bottom) }}>
        {selection && <View accessibilityRole="radiogroup" accessibilityLabel={titles[selection]}>
          {choices[selection].map((choice, index) => <Pressable key={choice.value} ref={node => { optionControls.current[choice.value] = node; }} accessibilityRole="radio" accessibilityLabel={choice.label} accessibilityState={{ checked: choice.value === values[selection] }} aria-checked={choice.value === values[selection]} onPress={() => choose(choice.value)} {...(Platform.OS === 'web' ? { onKeyDown: (event: KeyboardEvent) => optionKeyDown(event, index) } : {})} style={({ pressed }) => ({ minHeight: 52, paddingVertical: 12, flexDirection: 'row', alignItems: 'center', gap: 12, opacity: pressed ? 0.75 : 1 })}>
            <Text style={{ color: colors.text, fontSize: 15, flex: 1, minWidth: 0 }}>{choice.label}</Text>
            <Text aria-hidden accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ width: 24 * fontScale, color: colors.accent, fontSize: 18, textAlign: 'center' }}>{choice.value === values[selection] ? '✓' : ''}</Text>
          </Pressable>)}
        </View>}
      </ScrollView>
    </ModalDialog>
    <ModalDialog visible={showError} onClose={() => setDismissedError(error)} closeLabel="Dismiss settings error" testID="settings-error" style={{ width: '100%', maxWidth: 480, alignSelf: 'center', backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderTopLeftRadius: 16, borderTopRightRadius: 16 }}>
      <ScrollView style={{ flexShrink: 1 }} contentContainerStyle={{ padding: 20, paddingBottom: Math.max(20, insets.bottom), gap: 16 }}>
        <Text accessibilityRole="header" style={{ fontSize: 18, fontWeight: '600', color: colors.text }}>{settings.error ? settings.error.startsWith('Could not load') ? 'Settings unavailable' : 'Settings not saved' : 'Recording options unavailable'}</Text>
        <Text accessibilityRole="alert" style={{ color: colors.red, fontSize: 14, lineHeight: 21 }}>{error}</Text>
        {!workout.ready && <Button disabled={workout.busy} onPress={() => { void workout.run(async () => {}); }}>Retry</Button>}
        <Button secondary onPress={() => setDismissedError(error)}>Dismiss</Button>
      </ScrollView>
    </ModalDialog>
  </AppShell>;
}
