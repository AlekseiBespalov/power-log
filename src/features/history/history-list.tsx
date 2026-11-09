import { Text, View } from 'react-native';
import { ControlButton } from '../../components/control-button';
import { NoticeBanner } from '../../components/notice-banner';
import { Body, Card, colors, Pressable, type } from '../../components/ui';
import { RIDE_STORAGE_NOTE } from '../../components/app-info';
import type { HistoryListModel, HistoryListState } from './history-list-model';

export function HistoryList({ state, model }: { state: HistoryListState; model: HistoryListModel }) {
  return (
    <View style={{ gap: 12 }}>
      <View style={{ gap: 4 }}>
        <Body muted>
          {state.refreshing ? 'Refreshing…' : 'Pull to refresh'} · {state.count}
        </Body>
        <Text style={{ color: colors.muted, fontSize: type.caption }}>{RIDE_STORAGE_NOTE}</Text>
      </View>
      {state.notice && (
        <NoticeBanner
          notice={state.notice}
          onAction={action => (action === 'retry' ? model.retry() : model.dismissNotice())}
        />
      )}
      {state.rows.length ? (
        <View testID="history-rides" style={{ borderTopWidth: 1, borderTopColor: colors.border }}>
          {state.rows.map(row => {
            const disabled = !row.open.enabled;
            return (
              <View key={row.rideId} style={{ borderBottomWidth: 1, borderBottomColor: colors.border }}>
                <Pressable
                  testID="history-ride"
                  accessibilityRole="button"
                  accessibilityLabel={[`Open ride, ${row.title}, ${row.detail}`, row.health].filter(Boolean).join(', ')}
                  accessibilityHint={row.open.reason ?? undefined}
                  accessibilityState={{ disabled }}
                  disabled={disabled}
                  onPress={() => model.openRide(row.rideId)}
                  style={({ pressed }) => ({
                    minHeight: 76,
                    paddingVertical: 14,
                    paddingHorizontal: 2,
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: 12,
                    opacity: disabled ? 0.55 : pressed ? 0.7 : 1,
                  })}
                >
                  <View style={{ flex: 1, gap: 5 }}>
                    <Text style={{ color: colors.text, fontSize: type.control, fontWeight: '500' }}>{row.title}</Text>
                    <Text style={{ color: colors.muted, fontSize: type.caption }}>{row.detail}</Text>
                    {row.health && (
                      <Text
                        testID="ride-health-sync-summary"
                        accessible={false}
                        numberOfLines={1}
                        style={{ color: colors.muted, fontSize: type.caption }}
                      >
                        {row.health}
                      </Text>
                    )}
                  </View>
                  {row.phase && (
                    <Text style={{ color: row.failed ? colors.red : colors.muted, fontSize: type.caption }}>
                      {row.phase}
                    </Text>
                  )}
                  <Text style={{ color: colors.muted, fontSize: type.title }}>›</Text>
                </Pressable>
                {state.editing && (
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={`${row.remove.label}, ${row.title}`}
                    accessibilityHint={row.remove.reason ?? undefined}
                    accessibilityState={{ disabled: !row.remove.enabled }}
                    disabled={!row.remove.enabled}
                    onPress={() => model.askToDelete(row.rideId)}
                    style={{ minHeight: 48, justifyContent: 'center', paddingHorizontal: 2 }}
                  >
                    <Text style={{ color: row.remove.enabled ? colors.red : colors.muted }}>{row.remove.label}</Text>
                  </Pressable>
                )}
              </View>
            );
          })}
        </View>
      ) : (
        <Card style={{ padding: 20 }}>
          <Body muted>{state.loading ? 'Loading saved rides…' : 'No saved rides yet.'}</Body>
        </Card>
      )}
      {state.more && <ControlButton secondary control={state.more} onPress={() => model.loadMore()} />}
    </View>
  );
}
