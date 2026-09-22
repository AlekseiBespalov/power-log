import { useState } from 'react';
import { Pressable, Text, TextInput, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button, colors, Label } from '../../components/ui';
import { type MonitorView, type SpeedUnit } from '../../core/monitor';
import { searchMonitorMetrics, toggleMonitorMetric } from './monitor-editor-model';
import { SortableMetrics } from './sortable-metrics';
import { ModalDialog } from '../../components/modal-dialog';

type Props = {
  visible: boolean; view: MonitorView;
  speedUnit: SpeedUnit;
  onChange: (changes: Partial<Pick<MonitorView, 'numbers' | 'charts'>>) => void;
  onReset: () => void; onClose: () => void;
};
export function MonitorEditor(props: Props) {
  const insets = useSafeAreaInsets();
  return <ModalDialog visible={props.visible} onClose={props.onClose} closeLabel="Close monitor editor" testID="monitor-editor-dialog" style={{ maxHeight: '92%', width: '100%', maxWidth: 640, alignSelf: 'center', backgroundColor: colors.surface, borderTopLeftRadius: 16, borderTopRightRadius: 16, borderWidth: 1, borderColor: colors.border, paddingBottom: Math.max(12, insets.bottom) }}>
    <EditorContent key={props.view.id} {...props} />
  </ModalDialog>;
}
function EditorContent({ view, speedUnit, onChange, onReset, onClose }: Props) {
  const [tab, setTab] = useState<'numbers' | 'charts'>('numbers');
  const [query, setQuery] = useState('');
  const { height, fontScale } = useWindowDimensions();
  const compact = height < 520 * fontScale;
  const selected = view[tab], results = searchMonitorMetrics(query, speedUnit);
  const change = (ids: string[]) => onChange({ [tab]: ids });
  const controls = <>
      <View accessibilityRole="tablist" style={{ marginHorizontal: compact ? 0 : 16, padding: 3, backgroundColor: colors.bg, borderRadius: 9, flexDirection: 'row' }}>
        {(['numbers', 'charts'] as const).map(value => <Pressable key={value} accessibilityRole="tab" accessibilityState={{ selected: tab === value }} onPress={() => { setTab(value); setQuery(''); }} style={{ flex: 1, minHeight: 44, alignItems: 'center', justifyContent: 'center', borderRadius: 7, backgroundColor: tab === value ? colors.surfaceRaised : 'transparent' }}><Text style={{ color: tab === value ? colors.text : colors.muted, fontWeight: '600' }}>{value === 'numbers' ? 'Numbers' : 'Graphs'}</Text></Pressable>)}
      </View>
      <View style={{ marginHorizontal: compact ? 0 : 16, marginVertical: 10, flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <TextInput accessibilityLabel="Search metrics" placeholder="Search metrics" value={query} onChangeText={setQuery} autoCorrect={false} autoCapitalize="none" clearButtonMode="while-editing" placeholderTextColor={colors.muted} style={{ flex: 1, minHeight: 44, borderRadius: 8, backgroundColor: colors.bg, color: colors.text, paddingHorizontal: 12, fontSize: 14 }} />
      </View>
    </>;
  return <>
      <View style={{ paddingHorizontal: 16, paddingVertical: 10, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 10 }}>
        <Text accessibilityRole="header" style={{ color: colors.text, fontSize: 18, fontWeight: '600', flex: 1, minWidth: 0 }}>{view.name} layout</Text>
        <Button onPress={onClose}>Done</Button>
      </View>
      {!compact && controls}
      <SortableMetrics key={tab} selected={selected} kind={tab === 'numbers' ? 'numbers' : 'graphs'} showSelected={!query.trim()} onChange={change} header={compact ? controls : undefined}>
        {(['Rider', 'Battery', 'Motor', 'Route', 'Status'] as const).map(group => {
          const metrics = results.filter(metric => metric.group === group);
          if (!metrics.length) return null;
          return <View key={group} style={{ marginBottom: 12 }}><Label>{group}</Label>{metrics.map(metric => {
            const checked = selected.includes(metric.id);
            return <Pressable key={metric.id} accessibilityRole="checkbox" accessibilityLabel={metric.label} accessibilityState={{ checked }} aria-checked={checked} onPress={() => change(toggleMonitorMetric(selected, metric.id))} style={({ pressed }) => ({ minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: 10, borderBottomWidth: 1, borderBottomColor: colors.border, opacity: pressed ? 0.65 : 1 })}>
              <View style={{ width: 6, height: 6, borderRadius: 3, backgroundColor: metric.color }} />
              <Text style={{ flex: 1, paddingVertical: 8, color: colors.text, fontSize: 13 }}>{metric.label}</Text>
              <Text style={{ color: colors.muted, fontSize: 12 }}>{metric.unit}</Text>
              <Text style={{ color: checked ? colors.accent : colors.muted, fontSize: 21, width: 24, textAlign: 'center' }}>{checked ? '✓' : '+'}</Text>
            </Pressable>;
          })}</View>;
        })}
        {!results.length && <Text style={{ color: colors.muted, paddingVertical: 16 }}>No matching metrics</Text>}
      </SortableMetrics>
      <View style={{ paddingHorizontal: 16, paddingTop: 10, borderTopWidth: 1, borderTopColor: colors.border }}><Button secondary onPress={onReset}>Reset layout</Button></View>
  </>;
}
