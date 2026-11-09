import { View } from 'react-native';
import type { ImportedRideView } from '../../../modules/power-log';
import { ControlButton } from '../../components/control-button';
import { NoticeBanner } from '../../components/notice-banner';
import { Body, Chip, colors, Heading, Metric } from '../../components/ui';
import { useScreenModel } from '../../components/use-screen-model';
import { ChartsPanel } from '../charts/charts-panel';
import type { HistoryListModel } from '../history/history-list-model';
import { ImportedRideModel } from './imported-ride-model';

const tones = { accent: colors.accent, cadence: colors.cadence, red: colors.red };

export function ImportedRide({ imported, list }: { imported: ImportedRideView; list: HistoryListModel }) {
  const [state, model] = useScreenModel(env => new ImportedRideModel(env, imported, list), [imported.importId]);
  return (
    <View style={{ gap: 14 }}>
      {state.notice && <NoticeBanner notice={state.notice} onAction={() => model.dismissNotice()} />}
      <Heading>{state.name}</Heading>
      <Chip tone="warning">Imported · source unverified</Chip>
      <Heading level={2}>Ride summary</Heading>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 18 }}>
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
      <Heading level={2}>Chart inspection</Heading>
      <ChartsPanel input={{ source: { kind: 'imported', importId: state.importId }, scope: 'history' }} embedded />
      <Body muted>{state.samples}</Body>
      <ControlButton secondary control={state.exportCsv} onPress={() => model.exportCsv()} />
    </View>
  );
}
