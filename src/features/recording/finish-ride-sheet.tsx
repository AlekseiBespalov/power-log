import { ScrollView, Text } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ControlButton } from '../../components/control-button';
import { ModalDialog } from '../../components/modal-dialog';
import { Body, Button, colors, type } from '../../components/ui';
import type { RideControlsModel, RideControlsState } from './ride-controls-model';

export function FinishRideSheet({ state, model }: { state: RideControlsState; model: RideControlsModel }) {
  const insets = useSafeAreaInsets();
  const sheet = state.finishSheet;
  return (
    <ModalDialog
      visible={sheet !== null}
      onClose={() => model.keepRecording()}
      closeLabel="Keep recording"
      testID="finish-ride-sheet"
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
          testID="finish-ride-scroll"
          style={{ flexShrink: 1 }}
          contentContainerStyle={{ padding: 20, paddingBottom: Math.max(20, insets.bottom), gap: 12 }}
        >
          <Text accessibilityRole="header" style={{ color: colors.text, fontSize: type.title, fontWeight: '600' }}>
            Finish this ride?
          </Text>
          <Body>Save the ride, or permanently discard it from Power Log.</Body>
          {sheet.error && (
            <Text accessibilityRole="alert" style={{ color: colors.red }}>
              {sheet.error}
            </Text>
          )}
          {sheet.step === 'confirm' ? (
            <>
              {!sheet.save.enabled && <Body muted>{sheet.save.reason}</Body>}
              <ControlButton testID="save-ride" control={sheet.save} onPress={() => model.saveRide()} />
              <ControlButton secondary control={sheet.discard} onPress={() => model.discardRide()} />
              <ControlButton secondary control={sheet.keepRecording} onPress={() => model.keepRecording()} />
            </>
          ) : (
            <>
              <Body>Discarding permanently removes this ride from Power Log and ends recording.</Body>
              {!sheet.discard.enabled && <Body muted>{sheet.discard.reason}</Body>}
              <ControlButton danger control={sheet.discard} onPress={() => model.confirmDiscard()} />
              <Button secondary disabled={!sheet.keepRecording.enabled} onPress={() => model.backFromDiscard()}>
                Back
              </Button>
            </>
          )}
        </ScrollView>
      )}
    </ModalDialog>
  );
}
