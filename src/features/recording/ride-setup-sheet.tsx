import type { ReactNode } from 'react';
import { ActivityIndicator, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ControlButton } from '../../components/control-button';
import { Icon } from '../../components/icon';
import { ModalDialog } from '../../components/modal-dialog';
import { NoticeBanner } from '../../components/notice-banner';
import { Body, Button, Chip, colors, Pressable, styles, type } from '../../components/ui';
import type { RideControlsModel, RideControlsState } from './ride-controls-model';

export function RideSetupSheet({
  state,
  model,
  connection,
  web,
}: {
  state: RideControlsState;
  model: RideControlsModel;
  connection: ReactNode;
  web: boolean;
}) {
  const insets = useSafeAreaInsets();
  const permission = state.permission;
  const details = state.details;
  return (
    <ModalDialog
      visible={state.setupOpen}
      onClose={() => model.closeSetup()}
      closeLabel="Close ride setup"
      testID="ride-setup-sheet"
      style={{
        maxHeight: '90%',
        width: '100%',
        maxWidth: 600,
        alignSelf: 'center',
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderTopLeftRadius: 16,
        borderTopRightRadius: 16,
        paddingBottom: Math.max(12, insets.bottom),
      }}
    >
      <View
        style={{
          paddingHorizontal: 16,
          paddingVertical: 10,
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 10,
        }}
      >
        <Text accessibilityRole="header" style={{ color: colors.text, fontSize: type.title, fontWeight: '600' }}>
          Ride setup
        </Text>
        <Pressable
          accessibilityRole="button"
          onPress={() => model.closeSetup()}
          style={{ minHeight: 44, paddingHorizontal: 8, justifyContent: 'center' }}
        >
          <Text style={{ color: colors.accent, fontSize: type.control }}>Done</Text>
        </Pressable>
      </View>
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: 8, gap: 16 }}
      >
        {connection}
        {state.supported && (
          <View style={{ borderTopWidth: 1, borderColor: colors.border, paddingTop: 14, gap: 12 }}>
            {state.notice && (
              <NoticeBanner
                notice={
                  state.notice.actions.includes('reviewRide') ? { ...state.notice, actions: ['dismiss'] } : state.notice
                }
                onAction={action => (action === 'retry' ? model.retry() : model.dismissNotice())}
              />
            )}
            {state.phase === null ? (
              <>
                {state.ownerForeign && <Body muted>{state.ownerForeign}</Body>}
                {web && <Body muted>Keep this browser tab visible while recording.</Body>}
                {permission && (
                  <View style={{ gap: 6 }}>
                    <Text
                      accessibilityRole="header"
                      style={{ color: colors.text, fontSize: type.body, fontWeight: '600' }}
                    >
                      Permissions
                    </Text>
                    {permission.error && (
                      <Text style={{ color: colors.red, fontSize: type.caption }}>{permission.error}</Text>
                    )}
                    {permission.detail && (
                      <Text style={{ color: colors.muted, fontSize: type.caption }}>{permission.detail}</Text>
                    )}
                    {permission.button ? (
                      <Button secondary onPress={() => model.permissionAction()}>
                        {permission.label}
                      </Button>
                    ) : (
                      <Text style={{ color: colors.red }}>{permission.label}</Text>
                    )}
                  </View>
                )}
                <Pressable
                  accessibilityRole="link"
                  accessibilityLabel="Ride settings"
                  onPress={() => model.openRideSettings()}
                  style={{ minHeight: 48, justifyContent: 'center' }}
                >
                  <Text style={{ color: colors.accent, fontSize: type.body }}>Ride settings</Text>
                </Pressable>
                <ControlButton
                  testID="setup-start-ride"
                  secondary={!state.start.enabled}
                  control={state.start}
                  onPress={() => model.start()}
                />
                {state.startReason && !state.start.busy && <Body muted>{state.startReason}</Body>}
                {state.cancelStart && (
                  <ControlButton
                    testID="setup-cancel-start"
                    secondary
                    control={state.cancelStart}
                    onPress={() => model.cancelStart()}
                  />
                )}
              </>
            ) : (
              <>
                <View style={styles.row}>
                  {state.chip && <Chip tone={state.chip.tone}>{state.chip.text}</Chip>}
                  {details && <Chip>{details.indoor ? 'Indoor' : 'Outdoor'}</Chip>}
                  {details?.watch && <Icon name="watch" color={colors.accent} />}
                  <Text style={{ color: colors.text, fontSize: type.value, fontVariant: ['tabular-nums'] }}>
                    {state.timer}
                  </Text>
                </View>
                {details && (
                  <View style={styles.row}>
                    <Body muted>Distance source: {details.distanceSource}</Body>
                    <Body muted>Fixed for this ride</Body>
                  </View>
                )}
                {state.reviewRide && state.finish && (
                  <ControlButton
                    danger
                    control={{ ...state.finish, label: 'Finish or discard ride' }}
                    onPress={() => model.askToFinish()}
                  />
                )}
                {state.stop && <ControlButton danger control={state.stop} onPress={() => model.stop()} />}
                {state.waiting && (
                  <View style={styles.row}>
                    <ActivityIndicator size="small" color={colors.accent} />
                    <Body muted>{state.waiting}</Body>
                  </View>
                )}
                {details?.health && (
                  <View testID="ride-health" style={{ gap: 6 }}>
                    <Body muted>{details.health.label}</Body>
                    {details.health.reason && <Body muted>{details.health.reason}</Body>}
                    {details.health.components.map((line, index) => (
                      <Body key={index} muted>
                        {line}
                      </Body>
                    ))}
                  </View>
                )}
                {details && (
                  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12 }}>
                    {details.sources.map(line => (
                      <Text
                        key={line.title}
                        style={{
                          fontSize: type.caption,
                          color:
                            line.tone === 'ready'
                              ? colors.accent
                              : line.tone === 'warning'
                                ? colors.warning
                                : colors.muted,
                        }}
                      >
                        {line.title} · {line.text}
                      </Text>
                    ))}
                  </View>
                )}
              </>
            )}
          </View>
        )}
      </ScrollView>
    </ModalDialog>
  );
}
