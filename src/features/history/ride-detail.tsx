import type { ReactNode } from 'react';
import { Text, View } from 'react-native';
import { RoutePreview } from '../../components/route-preview';
import { ControlButton } from '../../components/control-button';
import { NoticeBanner } from '../../components/notice-banner';
import { Body, colors, Heading, Metric, Pressable, styles } from '../../components/ui';
import { useScreenModel, useUiEnv } from '../../components/use-screen-model';
import { useHistoryList } from './history-stack';
import { RideDetailModel, type SummaryItem } from './ride-detail-model';

const tones: Record<NonNullable<SummaryItem['tone']>, string> = {
  accent: colors.accent,
  cadence: colors.cadence,
  red: colors.red,
};

export function RideDetail({
  rideId,
  chart,
  actions,
  menu,
}: {
  rideId: string;
  chart: ReactNode;
  actions?: ReactNode;
  menu?: ReactNode;
}) {
  const { ui } = useUiEnv();
  const [, list] = useHistoryList();
  const [state, model] = useScreenModel(env => new RideDetailModel(env, rideId, list), [rideId, list]);
  return (
    <View style={{ gap: 12 }}>
      <View
        testID="history-toolbar"
        style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}
      >
        <Pressable
          accessibilityRole="button"
          onPress={() => ui.dismissTo('/sessions')}
          style={{ minHeight: 44, justifyContent: 'center' }}
        >
          <Text style={{ color: colors.accent }}>‹ History</Text>
        </Pressable>
        {actions}
      </View>
      {menu}
      {state.notice && (
        <NoticeBanner
          notice={state.notice}
          onAction={action => (action === 'retry' ? model.retry() : model.dismissNotice())}
        />
      )}
      {state.ready && (
        <View style={{ gap: 4 }}>
          <Heading>{state.title}</Heading>
          <Body muted>{state.subtitle}</Body>
        </View>
      )}
      <View style={styles.row}>
        <ControlButton control={state.exports.fit} onPress={() => model.exportRide('fit')} />
        <ControlButton secondary control={state.exports.csv} onPress={() => model.exportRide('csv')} />
        <ControlButton secondary control={state.exports.zip} onPress={() => model.exportRide('zip')} />
      </View>
      {state.ready && (
        <View style={{ gap: 8 }}>
          <Heading level={2}>Ride summary</Heading>
          {state.summary.length > 0 ? (
            <View testID="ride-summary" style={styles.row}>
              {state.summary.map(item => (
                <Metric
                  key={item.label}
                  label={item.label}
                  value={item.value}
                  unit={item.unit}
                  color={item.tone ? tones[item.tone] : undefined}
                />
              ))}
            </View>
          ) : (
            <Body muted>
              {state.summaryState === 'unavailable'
                ? 'Ride summary unavailable.'
                : 'Preparing summary… Charts are available below.'}
            </Body>
          )}
        </View>
      )}
      {state.route && (
        <RoutePreview
          points={state.route.flatMap((segment, index) =>
            segment.map(point => ({ latitude: point.latitude, longitude: point.longitude, segment: index })),
          )}
        />
      )}
      <Heading level={2}>Chart inspection</Heading>
      {chart}
      {state.health && (
        <View testID="ride-health-sync" style={{ gap: 6 }}>
          <Body muted>{state.health.label}</Body>
          {state.health.onWatch && <Body muted>Detailed Health results are on the Watch.</Body>}
          {state.health.reason && <Body muted>{state.health.reason}</Body>}
          {state.health.components.map((line, index) => (
            <Body key={index} muted>
              {line}
            </Body>
          ))}
        </View>
      )}
      {(state.finish || state.discard) && (
        <View style={styles.row}>
          {state.finish && <ControlButton control={state.finish} onPress={() => model.finishSavedPortion()} />}
          {state.discard && <ControlButton danger control={state.discard} onPress={() => model.askToDelete()} />}
        </View>
      )}
      {state.healthRepair && (
        <ControlButton secondary control={state.healthRepair} onPress={() => model.repairHealth()} />
      )}
      {state.strava && (
        <View style={{ gap: 8 }}>
          <Body muted>Export FIT, then select the file on Strava.</Body>
          <ControlButton secondary control={state.strava} onPress={() => model.openStrava()} />
        </View>
      )}
      {state.remove && <ControlButton danger control={state.remove} onPress={() => model.askToDelete()} />}
    </View>
  );
}
