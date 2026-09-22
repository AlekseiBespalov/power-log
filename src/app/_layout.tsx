import { Tabs } from 'expo-router';
import { useSyncExternalStore } from 'react';
import { StatusBar } from 'expo-status-bar';
import { Platform, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { SessionProvider } from '../services/session-context';
import { colors } from '../components/ui';
import { WorkoutProvider } from '../services/workout-context';
import { MonitorPreferencesProvider } from '../services/monitor-preferences';
import { TabTransitionProvider } from '../components/tab-transition';
import { AppHeader } from '../components/app-header';
import { TabBar } from '../components/tab-bar';

const ios = Platform.OS === 'ios';
const subscribe = () => () => {};
const clientSnapshot = () => true;
const serverSnapshot = () => false;
export default function RootLayout() {
  const hydrated = useSyncExternalStore(subscribe, clientSnapshot, serverSnapshot);
  // Static HTML cannot know the browser viewport or its locally stored rides/settings.
  if (Platform.OS === 'web' && !hydrated) return <View style={{ flex: 1, backgroundColor: colors.bg }} />;
  return <SafeAreaProvider><MonitorPreferencesProvider><SessionProvider><WorkoutProvider><TabTransitionProvider><StatusBar style="light" />
    <SafeAreaView edges={ios ? ['left', 'right'] : ['top', 'left', 'right']} style={{ flex: 1, backgroundColor: colors.bg }}>
      {!ios && <AppHeader />}
      <Tabs tabBar={ios ? props => <TabBar {...props} /> : () => null} backBehavior="fullHistory" screenOptions={{ headerShown: false, animation: 'none', sceneStyle: { backgroundColor: colors.bg } }} />
    </SafeAreaView>
  </TabTransitionProvider></WorkoutProvider></SessionProvider></MonitorPreferencesProvider></SafeAreaProvider>;
}
