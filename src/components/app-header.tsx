import { Link, usePathname, useRootNavigationState, type Href } from 'expo-router';
import { Platform, Text, View, useWindowDimensions } from 'react-native';
import { haptics } from './haptics';
import { colors, type, MIN_TOUCH_TARGET } from './ui';
import { APP_NAVIGATION, appNavigationIndex } from './app-navigation';

export function AppHeader() {
  const selectedIndex = appNavigationIndex(usePathname().split('/').slice(0, 2).join('/'));
  const navigation = useRootNavigationState();
  const tabs =
    navigation.type === 'tab' ? navigation : navigation.routes.find(route => route.state?.type === 'tab')?.state;
  const history = tabs?.routes.find(route => route.name === 'sessions')?.state;
  const historyRoute = history?.routes[history.index ?? history.routes.length - 1];
  const historyId = (historyRoute?.params as { id?: unknown } | undefined)?.id;
  const historyHref: Href =
    historyRoute?.name === 'ride' && typeof historyId === 'string'
      ? { pathname: '/sessions/ride', params: { id: historyId } }
      : '/sessions';
  const { width, fontScale } = useWindowDimensions();
  const desktop = Platform.OS === 'web' && width >= 1100;
  const stacked = width < 420 * fontScale + (width > 700 ? 48 : 24);
  return (
    <View
      testID="app-header"
      style={{
        paddingHorizontal: width > 700 ? 24 : 12,
        paddingTop: width > 700 ? 24 : 12,
        paddingBottom: desktop ? 16 : 12,
        backgroundColor: colors.bg,
      }}
    >
      <View
        key={fontScale}
        style={{
          width: '100%',
          maxWidth: 1400,
          alignSelf: 'center',
          flexDirection: stacked ? 'column' : 'row',
          flexWrap: 'wrap',
          alignItems: stacked ? 'stretch' : 'center',
          justifyContent: 'space-between',
          gap: 8,
          ...(desktop && { paddingBottom: 16, borderBottomWidth: 1, borderBottomColor: colors.border }),
        }}
      >
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 7, maxWidth: '100%' }}>
          <View
            style={{
              width: 28,
              height: 28,
              flexShrink: 0,
              backgroundColor: colors.accent,
              borderRadius: 8,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Text allowFontScaling={false} style={{ fontSize: type.value, fontWeight: '800', color: colors.bg }}>
              ϟ
            </Text>
          </View>
          <Text style={{ color: colors.text, fontSize: type.control, fontWeight: '600', flexShrink: 1 }}>
            Power Log
          </Text>
        </View>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 2, maxWidth: '100%' }}>
          {APP_NAVIGATION.map(({ href, title, route }, index) => {
            const selected = selectedIndex === index;
            return (
              <Link
                key={href}
                href={route === 'sessions' && !selected ? historyHref : href}
                onPress={() => {
                  if (!selected) haptics.selection();
                }}
                accessibilityState={{ selected }}
                aria-current={selected ? 'page' : undefined}
                style={{
                  color: selected ? colors.text : colors.muted,
                  backgroundColor: selected ? colors.surfaceRaised : 'transparent',
                  paddingHorizontal: stacked ? 8 : 13,
                  paddingVertical: 13,
                  borderRadius: 10,
                  fontSize: type.body,
                  fontWeight: '600',
                  minHeight: MIN_TOUCH_TARGET,
                  maxWidth: '100%',
                  textAlign: 'center',
                  flexShrink: 1,
                  ...(stacked && { flexGrow: 1, flexShrink: 0, flexBasis: 'auto', minWidth: 72 * fontScale }),
                }}
              >
                {title}
              </Link>
            );
          })}
        </View>
      </View>
    </View>
  );
}
