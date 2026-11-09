import { ScrollView, Text } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ControlButton } from '../../components/control-button';
import { ModalDialog } from '../../components/modal-dialog';
import { Body, colors, Heading } from '../../components/ui';
import type { HistoryListModel, HistoryListState } from './history-list-model';

export function DeleteRideSheet({ state, model }: { state: HistoryListState; model: HistoryListModel }) {
  const insets = useSafeAreaInsets();
  const sheet = state.deleteSheet;
  return (
    <ModalDialog
      visible={sheet !== null}
      onClose={() => model.keepRide()}
      closeLabel="Cancel ride deletion"
      testID="delete-ride-sheet"
      style={{
        width: '100%',
        maxWidth: 600,
        alignSelf: 'center',
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderTopLeftRadius: 16,
        borderTopRightRadius: 16,
      }}
    >
      {sheet && (
        <ScrollView
          testID="delete-ride-scroll"
          style={{ flexShrink: 1 }}
          contentContainerStyle={{ padding: 20, paddingBottom: Math.max(20, insets.bottom), gap: 12 }}
        >
          <Heading>{sheet.title}</Heading>
          <Body muted>{sheet.date}</Body>
          <Body>{sheet.body}</Body>
          {sheet.error && (
            <Text accessibilityRole="alert" style={{ color: colors.red }}>
              {sheet.error}
            </Text>
          )}
          <ControlButton danger control={sheet.confirm} onPress={() => model.confirmDelete()} />
          <ControlButton secondary control={sheet.keep} onPress={() => model.keepRide()} />
        </ScrollView>
      )}
    </ModalDialog>
  );
}
