import type { ReactNode } from 'react';
import { Platform, View } from 'react-native';

export function TabTransition({ index, children }: { index: number; children: ReactNode }) {
  return Platform.OS === 'web' ? (
    <View testID={`tab-transition-${index}`} style={{ flex: 1 }}>
      {children}
    </View>
  ) : (
    <>{children}</>
  );
}
