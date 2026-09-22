import { Link, useIsFocused, useRoute } from 'expo-router';
import { Platform, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import type { ReactNode } from 'react';
import { useSession } from '../services/session-context';
import { useWorkout } from '../services/workout-context';
import { workoutInProgress } from '../core/workouts';
import { Button, colors, formatDuration } from './ui';
import { TabTransition } from './tab-transition';
import { appNavigationIndex } from './app-navigation';

export function AppShell({ children, footer }: { children: ReactNode; footer?: ReactNode }) {
  const route = useRoute(); const { width } = useWindowDimensions();
  const navigationIndex = appNavigationIndex(route.name);
  const focused = useIsFocused();
  const desktop = Platform.OS === 'web' && width >= 1100;
  return <View style={{ flex: 1, display: Platform.OS === 'web' && !focused ? 'none' : 'flex' }}><TabTransition index={navigationIndex}><SafeAreaView edges={Platform.OS === 'ios' ? ['top'] : ['bottom']} style={{ flex: 1, backgroundColor: colors.bg }}>
    <ScrollView showsVerticalScrollIndicator={Platform.OS === 'web'} showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: width > 700 ? 24 : 12, paddingBottom: 24, flexGrow: 1 }}>
      <View testID="app-workspace" style={{ width: '100%', maxWidth: 1400, alignSelf: 'center', gap: desktop ? 16 : 12 }}>
        {navigationIndex !== 0 && <ActiveRideLink />}
        <SessionError />
        {children}
      </View>
    </ScrollView>
    {footer && <View testID="app-footer" style={{ borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.surface, paddingHorizontal: width > 700 ? 24 : 12, paddingVertical: 8 }}>{footer}</View>}
  </SafeAreaView></TabTransition></View>;
}

function ActiveRideLink() {
  const { state } = useWorkout();
  return workoutInProgress(state.phase) ? <Link href="/" style={{ color: colors.accent, backgroundColor: colors.surfaceRaised, borderRadius: 10, padding: 14, fontWeight: '600', minHeight: 44 }}>● {state.phase === 'paused' ? 'Ride paused' : 'Ride in progress'} · {formatDuration(state.timerSeconds)} →</Link> : null;
}
function SessionError() {
  const session = useSession();
  return session.error ? <View accessibilityRole="alert" style={{ padding: 12, borderRadius: 12, backgroundColor: '#412b27', flexDirection: 'row', gap: 12, alignItems: 'center' }}><Text style={{ color: colors.red, flex: 1, lineHeight: 20 }}>{session.error}</Text><Button secondary onPress={session.clearError}>Dismiss</Button></View> : null;
}
