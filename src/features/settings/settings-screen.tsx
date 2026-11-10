import { useRef, useState, type ReactNode } from 'react';
import { Link } from 'expo-router';
import { Platform, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppShell } from '../../components/app-shell';
import { ChoiceSheet } from '../../components/choice-sheet';
import { ExternalLink } from '../../components/external-link';
import { ModalDialog } from '../../components/modal-dialog';
import { NoticeBanner } from '../../components/notice-banner';
import { StableLabel } from '../../components/stable-label';
import { Toggle } from '../../components/toggle';
import { Button, colors, headingLevel, Pressable, type, lineHeight, MIN_TOUCH_TARGET } from '../../components/ui';
import { APP_VERSION, ISSUES_URL, RIDE_STORAGE_NOTE, SOURCE_URL, WEB_NOTICES_URL } from '../../components/app-info';
import { useScreenModel } from '../../components/use-screen-model';
import { DiagnosticsSection } from '../diagnostics/diagnostics-section';
import { SettingsModel, type ChoiceField, type SettingsState, type ToggleField } from './settings-model';

type Focusable = { focus?: () => void };

const testIDs: Record<ChoiceField, string> = {
  units: 'settings-units',
  temperature: 'settings-temperature',
  distance: 'settings-distance',
  battery: 'settings-battery',
  fitPower: 'settings-fitPower',
  rideType: 'settings-environment',
  sampleRate: 'settings-sampleHz',
};

function SettingsGroup({
  title,
  caption,
  testID,
  children,
}: {
  title: string;
  caption?: string;
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
          style={{ fontSize: type.title, fontWeight: '600', color: colors.text }}
        >
          {title}
        </Text>
        {caption && <Text style={{ fontSize: type.caption, color: colors.muted }}>{caption}</Text>}
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
      <Text style={{ flex: 1, minWidth: 0, fontSize: type.body, color: colors.text }}>{label}</Text>
      <Toggle label={accessibilityLabel} value={value} onChange={onChange} />
    </View>
  );
}

export function SettingsScreen() {
  const [state, model] = useScreenModel(env => new SettingsModel(env), []);
  const { width, fontScale } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const [selection, setSelection] = useState<ChoiceField | null>(null);
  const [selectionOpen, setSelectionOpen] = useState(false);
  const controls = useRef<Partial<Record<ChoiceField, Focusable | null>>>({});
  const openedControl = useRef<ChoiceField | null>(null);
  const columns = Platform.OS === 'web' && width >= 900 * fontScale;
  const close = () => {
    setSelectionOpen(false);
    if (Platform.OS === 'web')
      requestAnimationFrame(() => {
        if (openedControl.current) controls.current[openedControl.current]?.focus?.();
      });
  };
  const choose = (value: string) => {
    if (selection) model.choose(selection, value as never);
    close();
  };
  const picker = (field: ChoiceField) => {
    const row = state.rows[field];
    return (
      <Pressable
        key={field}
        ref={node => {
          controls.current[field] = node;
        }}
        testID={testIDs[field]}
        accessibilityRole="button"
        accessibilityLabel={`${row.title}, ${row.label}`}
        accessibilityState={{ expanded: selectionOpen && selection === field }}
        onPress={() => {
          openedControl.current = field;
          setSelection(field);
          setSelectionOpen(true);
        }}
        style={({ pressed }) => ({ minHeight: 60, paddingVertical: 8, opacity: pressed ? 0.75 : 1, gap: 6 })}
      >
        <Text style={{ color: colors.text, fontSize: type.body }}>{row.title}</Text>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
          <View style={{ flex: 1, minWidth: 0 }}>
            <StableLabel
              value={row.label}
              variants={row.choices.map(choice => choice.label)}
              style={{ color: colors.muted, fontSize: type.body }}
            />
          </View>
          <Text
            aria-hidden
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{ color: colors.muted, fontSize: type.title, width: 16, textAlign: 'center' }}
          >
            ›
          </Text>
        </View>
      </Pressable>
    );
  };
  const toggle = (field: ToggleField) => {
    const row = state.toggles[field];
    return (
      row && (
        <ToggleRow
          key={field}
          label={row.label}
          accessibilityLabel={row.accessibilityLabel}
          value={row.value}
          onChange={on => model.setToggle(field, on)}
        />
      )
    );
  };

  return (
    <AppShell>
      <View testID="settings-screen" style={{ width: '100%', maxWidth: 1040, alignSelf: 'center', gap: 16 }}>
        <Text
          key={fontScale}
          accessibilityRole="header"
          style={{ color: colors.text, fontSize: type.title, fontWeight: '600', letterSpacing: -0.6 }}
        >
          Settings
        </Text>
        {state.notice && (
          <NoticeBanner
            notice={state.notice}
            onAction={action => (action === 'retry' ? model.retry() : model.dismissNotice())}
          />
        )}
        {state.refused ? null : !state.ready ? (
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
            <Text style={{ color: colors.muted, fontSize: type.body }}>Loading settings…</Text>
          </View>
        ) : (
          <SettingsGroups
            state={state}
            columns={columns}
            fontScale={fontScale}
            picker={picker}
            toggle={toggle}
            model={model}
          />
        )}
        <SettingsGroup title="About" caption={`Power Log ${state.version || APP_VERSION}`} testID="settings-about">
          <Text style={{ color: colors.muted, fontSize: type.body, lineHeight: lineHeight.body }}>
            {RIDE_STORAGE_NOTE}
          </Text>
          <Text style={{ color: colors.muted, fontSize: type.body, lineHeight: lineHeight.body }}>
            Readings come from your controller and sensors and can be inaccurate; battery use can read low. Set up
            before you ride, and don’t use the app while moving.
          </Text>
          <Link
            href="/privacy"
            style={{
              color: colors.accent,
              fontSize: type.body,
              lineHeight: lineHeight.body,
              paddingVertical: 12,
              minHeight: MIN_TOUCH_TARGET,
            }}
          >
            Privacy policy
          </Link>
          <ExternalLink href={ISSUES_URL}>GitHub Issues</ExternalLink>
          <ExternalLink href={SOURCE_URL}>Source repository</ExternalLink>
          <ExternalLink href={WEB_NOTICES_URL}>Third-party notices</ExternalLink>
          <Text style={{ color: colors.muted, fontSize: type.caption }}>Notices for the published website build.</Text>
        </SettingsGroup>
      </View>
      <ChoiceSheet
        visible={selectionOpen && state.dialog === null}
        title={selection ? state.rows[selection].title : ''}
        choices={selection ? state.rows[selection].choices : []}
        selected={selection ? state.rows[selection].value : null}
        onChoose={choose}
        onClose={close}
        testID="settings-options"
      />
      <ModalDialog
        visible={state.dialog !== null}
        onClose={() => model.dismissDialog()}
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
          <Text accessibilityRole="header" style={{ fontSize: type.title, fontWeight: '600', color: colors.text }}>
            {state.dialog?.kind === 'unreadable' ? 'Settings unavailable' : 'Settings not saved'}
          </Text>
          <Text
            accessibilityRole="alert"
            style={{ color: colors.red, fontSize: type.body, lineHeight: lineHeight.body }}
          >
            {state.dialog?.text}
          </Text>
          {state.dialog?.resetOffered && <Button onPress={() => model.resetSettings()}>Reset settings</Button>}
          <Button secondary onPress={() => model.dismissDialog()}>
            Dismiss
          </Button>
        </ScrollView>
      </ModalDialog>
    </AppShell>
  );
}

function SettingsGroups({
  state,
  columns,
  fontScale,
  picker,
  toggle,
  model,
}: {
  state: SettingsState;
  columns: boolean;
  fontScale: number;
  picker: (field: ChoiceField) => ReactNode;
  toggle: (field: ToggleField) => ReactNode;
  model: SettingsModel;
}) {
  return (
    <View style={{ gap: 16 }}>
      <View key={fontScale} style={{ flexDirection: columns ? 'row' : 'column', alignItems: 'stretch', gap: 16 }}>
        <View style={{ flex: columns ? 1 : undefined, minWidth: 0, gap: 16 }}>
          <SettingsGroup
            title="Display & analysis"
            caption="Source choices apply to new ride summaries and exports. All sources remain available in charts."
            testID="settings-display"
          >
            {picker('units')}
            {picker('temperature')}
            {picker('distance')}
            {picker('battery')}
          </SettingsGroup>
          <SettingsGroup title="FIT export" caption="Power reported to other apps" testID="settings-export">
            {picker('fitPower')}
          </SettingsGroup>
        </View>
        <View style={{ flex: columns ? 1 : undefined, minWidth: 0 }}>
          <SettingsGroup title="Recording" caption="Rides started here" testID="settings-recording">
            {picker('rideType')}
            {toggle('useWatch')}
            {toggle('recordGps')}
            {toggle('saveToHealth')}
            {state.healthNote && (
              <Text style={{ color: colors.muted, fontSize: type.caption }}>{state.healthNote}</Text>
            )}
            {picker('sampleRate')}
            {toggle('syncBeeps')}
            {state.toggles.syncBeeps && (
              <Text style={{ color: colors.muted, fontSize: type.caption }}>
                Beeps at Start, Resume and Finish. The ride starts, resumes and ends on the last, higher beep, so video
                tools can line up the ride’s files with the camera’s sound.
              </Text>
            )}
            {state.testBeep && (
              <Button secondary disabled={state.testBeep.play.busy} onPress={() => model.playTestBeep()}>
                {state.testBeep.play.label}
              </Button>
            )}
            {state.testBeep?.result && (
              <Text style={{ color: colors.red, fontSize: type.caption }}>{state.testBeep.result}</Text>
            )}
          </SettingsGroup>
        </View>
      </View>
      {state.storage && (
        <SettingsGroup title="Browser storage" caption="Saved rides on this device" testID="settings-storage">
          <Text style={{ color: colors.text, fontSize: type.body }}>{state.storage}</Text>
        </SettingsGroup>
      )}
      <SettingsGroup title="Bluetooth diagnostics" testID="settings-bluetooth">
        <DiagnosticsSection />
      </SettingsGroup>
      {state.examples && (
        <SettingsGroup title="Developer" caption="Development builds only" testID="settings-developer">
          <Button secondary disabled={state.examples.add.busy} onPress={() => model.addExampleRides()}>
            {state.examples.add.label}
          </Button>
          {state.examples.result && (
            <Text style={{ color: colors.muted, fontSize: type.body }}>{state.examples.result}</Text>
          )}
        </SettingsGroup>
      )}
    </View>
  );
}
