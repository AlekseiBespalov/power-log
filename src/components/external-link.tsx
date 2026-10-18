import { Link } from 'expo-router';
import { useState, type ReactNode } from 'react';
import { Linking, Platform, Text, View } from 'react-native';
import { colors, type, lineHeight, MIN_TOUCH_TARGET } from './ui';

export function ExternalLink({ href, children }: { href: `https://${string}`; children: ReactNode }) {
  const [error, setError] = useState<string | null>(null);
  return (
    <View style={{ gap: 4 }}>
      <Link
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        onPress={
          Platform.OS === 'web'
            ? undefined
            : event => {
                event.preventDefault();
                setError(null);
                void Linking.openURL(href).catch(() => setError('Could not open this link. Try again.'));
              }
        }
        style={{
          color: colors.accent,
          fontSize: type.body,
          lineHeight: lineHeight.body,
          paddingVertical: 12,
          minHeight: MIN_TOUCH_TARGET,
        }}
      >
        {children}
      </Link>
      {error && (
        <Text accessibilityRole="alert" style={{ color: colors.red, fontSize: type.body, lineHeight: lineHeight.body }}>
          {error}
        </Text>
      )}
    </View>
  );
}
