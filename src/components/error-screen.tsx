import type { ErrorBoundaryProps } from 'expo-router';
import { View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { Body, Button, Heading, colors, space } from './ui';

export function ErrorScreen({ error, retry }: ErrorBoundaryProps) {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={{ flex: 1, backgroundColor: colors.bg }}>
        <View testID="error-screen" style={{ padding: space.lg, gap: space.md }}>
          <Heading>Something went wrong on this screen.</Heading>
          {__DEV__ && <Body muted>{error.message}</Body>}
          <Body>Try again, or close and reopen Power Log if it happens again.</Body>
          <Button onPress={() => void retry()}>Try again</Button>
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
