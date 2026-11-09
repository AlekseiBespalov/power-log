import { Text, View } from 'react-native';
import type { Notice, NoticeAction } from './screen-model';
import { Button, colors, styles } from './ui';

const actionLabels: Record<NoticeAction, string> = {
  dismiss: 'Dismiss',
  retry: 'Retry',
  openAppSettings: 'Open Settings',
  reviewRide: 'Review ride',
  resetSettings: 'Reset settings',
  refreshStatus: 'Refresh status',
};

export function NoticeBanner({
  notice,
  onAction,
  testID,
}: {
  notice: Notice;
  onAction: (action: NoticeAction) => void;
  testID?: string;
}) {
  return (
    <View accessibilityRole="alert" testID={testID} style={{ gap: 8 }}>
      <Text style={{ color: colors.red }}>{notice.text}</Text>
      <View style={styles.row}>
        {notice.actions.map(action => (
          <Button key={action} secondary onPress={() => onAction(action)}>
            {actionLabels[action]}
          </Button>
        ))}
      </View>
    </View>
  );
}
