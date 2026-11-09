import { View } from 'react-native';
import { Body, Card, space } from '../../components/ui';

export function StoreRefusedScreen({ message }: { message: string }) {
  return (
    <View testID="store-refused-screen" accessibilityRole="alert">
      <Card style={{ padding: space.lg }}>
        <Body>{message}</Body>
      </Card>
    </View>
  );
}
