import { useState } from 'react';
import { Text, View } from 'react-native';
import { Link } from 'expo-router';
import { ControlButton } from '../../components/control-button';
import { NoticeBanner } from '../../components/notice-banner';
import { colors, lineHeight, MIN_TOUCH_TARGET, Pressable, type } from '../../components/ui';
import { useScreenModel } from '../../components/use-screen-model';
import { DiagnosticsModel } from './diagnostics-model';

export function DiagnosticsSection() {
  const [state, model] = useScreenModel(env => new DiagnosticsModel(env), []);
  const [advanced, setAdvanced] = useState(false);
  if (!state.available) return <Text style={{ color: colors.muted }}>Bluetooth diagnostics are unavailable here.</Text>;
  return (
    <View style={{ gap: 12 }} testID="bluetooth-diagnostics">
      {state.share && (
        <>
          <Text style={{ color: colors.muted, fontSize: type.body }}>
            If your bike keeps disconnecting, share diagnostics when you report it.
          </Text>
          <Text style={{ color: colors.muted, fontSize: type.caption }}>
            A ZIP of connection events from at least the last 10 hours of riding, with phone model and app version. No
            locations, ride measurements or device names.
          </Text>
          <ControlButton control={state.share} onPress={() => model.share()} />
        </>
      )}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Advanced"
        aria-expanded={advanced}
        onPress={() => setAdvanced(value => !value)}
        style={({ pressed }) => ({ flexDirection: 'row', alignItems: 'center', gap: 12, opacity: pressed ? 0.75 : 1 })}
      >
        <Text style={{ flex: 1, minWidth: 0, color: colors.text, fontSize: type.body }}>Advanced</Text>
        <Text
          aria-hidden
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
          style={{
            color: colors.muted,
            fontSize: type.title,
            width: 16,
            textAlign: 'center',
            transform: [{ rotate: advanced ? '90deg' : '0deg' }],
          }}
        >
          ›
        </Text>
      </Pressable>
      {advanced && (
        <View style={{ gap: 12 }}>
          <Text style={{ color: colors.muted, fontSize: type.body }}>
            Start capture, reconnect your bike, then export the log.
          </Text>
          <Text style={{ color: colors.muted, fontSize: type.caption }}>
            May include device names and serials. Shared only when you export.
          </Text>
          <Text accessibilityLiveRegion="polite" style={{ color: colors.text, fontSize: type.body }}>
            {state.status}
          </Text>
          {state.stop ? (
            <ControlButton control={state.stop} onPress={() => model.stopCapture()} />
          ) : (
            <ControlButton control={state.capture} onPress={() => model.startCapture()} />
          )}
          <Link
            href="/"
            style={{
              color: colors.accent,
              fontSize: type.body,
              lineHeight: lineHeight.body,
              paddingVertical: 12,
              minHeight: MIN_TOUCH_TARGET,
            }}
          >
            Back to ride
          </Link>
          <View style={{ flexDirection: 'row', gap: 12 }}>
            <View style={{ flex: 1 }}>
              <ControlButton secondary control={state.exportLog} onPress={() => model.exportLog()} />
            </View>
            <View style={{ flex: 1 }}>
              <ControlButton secondary control={state.clear} onPress={() => model.clear()} />
            </View>
          </View>
        </View>
      )}
      {state.notice && <NoticeBanner notice={state.notice} onAction={() => model.refreshStatus()} />}
    </View>
  );
}
