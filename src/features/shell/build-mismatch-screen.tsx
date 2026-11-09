import { View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Heading, colors, space } from '../../components/ui';

export function BuildMismatchScreen({ message }: { message: string }) {
  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.bg }}>
      <View testID="build-mismatch-screen" accessibilityRole="alert" style={{ padding: space.lg }}>
        <Heading>{message}</Heading>
      </View>
    </SafeAreaView>
  );
}
