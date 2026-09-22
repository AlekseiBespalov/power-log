import { Link, usePathname } from 'expo-router';
import { Platform, Text, View, useWindowDimensions } from 'react-native';
import { colors } from './ui';
import { APP_NAVIGATION, appNavigationIndex } from './app-navigation';

export function AppHeader() {
  const selectedIndex = appNavigationIndex(usePathname());
  const { width, fontScale } = useWindowDimensions();
  const desktop = Platform.OS === 'web' && width >= 1100;
  const stacked = width < 420 * fontScale + (width > 700 ? 48 : 24);
  return <View testID="app-header" style={{ paddingHorizontal: width > 700 ? 24 : 12, paddingTop: width > 700 ? 24 : 12, paddingBottom: desktop ? 16 : 12, backgroundColor: colors.bg }}>
    <View key={fontScale} style={{ width: '100%', maxWidth: 1400, alignSelf: 'center', flexDirection: stacked ? 'column' : 'row', flexWrap: 'wrap', alignItems: stacked ? 'stretch' : 'center', justifyContent: 'space-between', gap: 8, ...(desktop && { paddingBottom: 16, borderBottomWidth: 1, borderBottomColor: colors.border }) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 7, maxWidth: '100%' }}>
        <View style={{ width: 28, height: 28, flexShrink: 0, backgroundColor: colors.accent, borderRadius: 8, alignItems: 'center', justifyContent: 'center' }}><Text allowFontScaling={false} style={{ fontSize: 23, fontWeight: '800', color: colors.bg }}>ϟ</Text></View>
        <Text style={{ color: colors.text, fontSize: 15, fontWeight: '600', flexShrink: 1 }}>Power Log</Text>
      </View>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 2, maxWidth: '100%' }}>
        {APP_NAVIGATION.map(({ href, title }, index) => {
          const selected = selectedIndex === index;
          return <Link key={href} href={href} accessibilityState={{ selected }} aria-current={selected ? 'page' : undefined} style={{ color: selected ? colors.text : colors.muted, backgroundColor: selected ? colors.surfaceRaised : 'transparent', paddingHorizontal: stacked ? 8 : 13, paddingVertical: 13, borderRadius: 10, fontSize: 14, fontWeight: '600', minHeight: 44, maxWidth: '100%', textAlign: 'center', flexShrink: 1, ...(stacked && { flexGrow: 1, flexShrink: 0, flexBasis: 'auto', minWidth: 72 * fontScale }) }}>{title}</Link>;
        })}
      </View>
    </View>
  </View>;
}
