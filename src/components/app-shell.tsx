import { Link, useIsFocused, useRoute } from 'expo-router';
import { Platform, View, useWindowDimensions, type RefreshControlProps } from 'react-native';
import { ScrollView } from 'react-native-gesture-handler';
import { SafeAreaView } from 'react-native-safe-area-context';
import type { ReactElement, ReactNode } from 'react';
import { StoreRefusedScreen } from '../features/shell/store-refused-screen';
import { NoticeBanner } from './notice-banner';
import { colors, MIN_TOUCH_TARGET } from './ui';
import { TabTransition } from './tab-transition';
import { appNavigationIndex } from './app-navigation';
import { useModelSnapshot, useUiEnv } from './use-screen-model';

export function AppShell({
  children,
  footer,
  navigationRoute,
  refreshControl,
}: {
  children: ReactNode;
  footer?: ReactNode;
  navigationRoute?: string;
  refreshControl?: ReactElement<RefreshControlProps>;
}) {
  const route = useRoute();
  const { width } = useWindowDimensions();
  const navigationIndex = appNavigationIndex(navigationRoute ?? route.name);
  const focused = useIsFocused();
  const { shell } = useUiEnv();
  const state = useModelSnapshot(shell);
  const desktop = Platform.OS === 'web' && width >= 1100;
  const refused = state.mode === 'refused' && state.message !== null;
  return (
    <View style={{ flex: 1, display: Platform.OS === 'web' && !focused ? 'none' : 'flex' }}>
      <TabTransition index={navigationIndex}>
        <SafeAreaView
          edges={Platform.OS !== 'web' ? ['top'] : ['bottom']}
          style={{ flex: 1, backgroundColor: colors.bg }}
        >
          <ScrollView
            refreshControl={refreshControl}
            showsVerticalScrollIndicator={Platform.OS === 'web'}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={{ paddingHorizontal: width > 700 ? 24 : 12, paddingBottom: 24, flexGrow: 1 }}
          >
            <View
              testID="app-workspace"
              style={{ width: '100%', maxWidth: 1400, alignSelf: 'center', gap: desktop ? 16 : 12 }}
            >
              {navigationIndex !== 0 && state.activeRide && (
                <Link
                  href="/"
                  style={{
                    color: colors.accent,
                    backgroundColor: colors.surfaceRaised,
                    borderRadius: 10,
                    padding: 14,
                    fontWeight: '600',
                    minHeight: MIN_TOUCH_TARGET,
                  }}
                >
                  {state.activeRide.text}
                </Link>
              )}
              {state.notice && <NoticeBanner notice={state.notice} onAction={() => shell.retry()} />}
              {state.bikeNotice && (
                <NoticeBanner notice={state.bikeNotice} onAction={() => shell.dismiss(state.bikeNotice!.id)} />
              )}
              {refused && <StoreRefusedScreen message={state.message!} />}
              {refused && navigationIndex <= 1 ? null : children}
            </View>
          </ScrollView>
          {footer && (
            <View
              testID="app-footer"
              style={{
                borderTopWidth: 1,
                borderTopColor: colors.border,
                backgroundColor: colors.surface,
                paddingHorizontal: width > 700 ? 24 : 12,
                paddingVertical: 8,
              }}
            >
              {footer}
            </View>
          )}
        </SafeAreaView>
      </TabTransition>
    </View>
  );
}
