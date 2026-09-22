import { useMemo } from 'react';
import { View } from 'react-native';
import { Body, Chip, Heading, Metric, colors, formatDuration } from '../../components/ui';
import type { TelemetrySample } from '../../core/types';
import { TelemetryMonitor } from '../../core/monitor-data';
import { summarizeRecording } from '../../core/recordings';
import { readConsumer } from '../../services/read-scheduler';
import { MonitorPanel } from '../monitor/monitor-panel';

export type CsvRide = { title: string; samples: TelemetrySample[]; csv: string };
export function CsvRideDetails({ ride }: { ride: CsvRide }) {
  const source = useMemo(() => new TelemetryMonitor(readConsumer('csv-source'), false, ride.samples), [ride]);
  const summary = useMemo(() => summarizeRecording(ride.samples), [ride]);
  return <View style={{ gap: 14 }}>
    <Heading>{ride.title}</Heading><Chip tone="warning">Imported · source unverified</Chip>
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 18 }}>
      <Metric label="Duration" value={formatDuration(summary.durationSeconds)} unit="" />
      <Metric label="Avg power" value={summary.averageHumanPowerW?.toFixed(0) ?? '—'} unit="W" color={colors.accent} />
      <Metric label="Peak power" value={summary.peakHumanPowerW?.toFixed(0) ?? '—'} unit="W" />
      <Metric label="Avg cadence" value={summary.averageCadenceRpm?.toFixed(0) ?? '—'} unit="rpm" color={colors.cadence} />
      <Metric label="Rider work" value={summary.humanEnergyWh.toFixed(1)} unit="Wh" />
    </View>
    <MonitorPanel source={source} embedded />
    <Body muted>{summary.sampleCount.toLocaleString()} original samples{summary.gapCount > 0 ? ` · ${summary.gapCount} gaps` : ''}{summary.clockDiscontinuities > 0 ? ` · ${summary.clockDiscontinuities} clock adjustments` : ''}</Body>
  </View>;
}
