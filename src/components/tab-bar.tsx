import type { ComponentProps } from 'react';
import { Pressable, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { Tabs } from 'expo-router';
import { APP_NAVIGATION } from './app-navigation';
import { Icon, type IconName } from './icon';
import { colors, type } from './ui';
import { haptics } from '../services/haptics';

type TabBarProps = Parameters<NonNullable<ComponentProps<typeof Tabs>['tabBar']>>[0];
const icons: Record<string, IconName> = { index: 'bike', sessions: 'history', settings: 'settings' };

export function TabBar({ state, navigation }: TabBarProps) {
  const insets = useSafeAreaInsets();
  return <View accessibilityRole="tablist" testID="tab-bar" style={{ flexDirection: 'row', backgroundColor: colors.surface, borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 6, paddingBottom: Math.max(insets.bottom, 8) }}>
    {APP_NAVIGATION.map(item => {
      const index = state.routes.findIndex(route => route.name === item.route);
      const route = state.routes[index];
      if (!route) return null;
      const selected = state.index === index;
      const color = selected ? colors.accent : colors.muted;
      return <Pressable key={item.route} accessibilityRole="tab" accessibilityState={{ selected }} accessibilityLabel={item.title} testID={`tab-${item.route}`}
        onPress={() => {
          const event = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
          if (!selected && !event.defaultPrevented) { haptics.selection(); navigation.navigate(route.name); }
        }}
        style={({ pressed }) => ({ flex: 1, minHeight: 49, alignItems: 'center', justifyContent: 'center', gap: 3, opacity: pressed ? 0.7 : 1 })}>
        <Icon name={icons[item.route] ?? 'bike'} color={color} size={24} />
        <Text style={{ color, fontSize: type.label, fontWeight: '600' }}>{item.title}</Text>
      </Pressable>;
    })}
  </View>;
}
