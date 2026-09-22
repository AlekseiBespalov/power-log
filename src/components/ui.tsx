import { Pressable, StyleSheet, Text, View, useWindowDimensions, type ViewStyle } from 'react-native';
import type { ReactNode } from 'react';

export const colors = { bg: '#0b0d10', surface: '#15191f', surfaceRaised: '#202630', border: '#303844', text: '#f4f6fa', muted: '#9ba6b6', accent: '#ff873c', route: '#47d6d0', motor: '#59caff', cadence: '#b4a0ff', red: '#ff8994', warning: '#ffbe55' };
export const type = { label: 11, caption: 12, body: 14, control: 15, title: 18, value: 26, hero: 38 } as const;
export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24 } as const;
export const radius = { sm: 6, md: 10, lg: 12 } as const;

export function Label({ children, color = colors.muted }: { children: ReactNode; color?: string }) {
  return <Text style={[styles.label, { color }]}>{children}</Text>;
}
export function Heading({ children }: { children: ReactNode }) { return <Text style={styles.heading}>{children}</Text>; }
export function Body({ children, muted = false }: { children: ReactNode; muted?: boolean }) { return <Text style={[styles.body, muted && { color: colors.muted }]}>{children}</Text>; }
export function Card({ children, style }: { children: ReactNode; style?: ViewStyle }) { return <View style={[styles.card, style]}>{children}</View>; }
export function Button({ children, onPress, disabled = false, secondary = false, danger = false, block = false, testID, icon, accessibilityLabel }: { children?: ReactNode; onPress: () => void; disabled?: boolean; secondary?: boolean; danger?: boolean; block?: boolean; testID?: string; icon?: ReactNode; accessibilityLabel?: string }) {
  const { fontScale } = useWindowDimensions();
  return <Pressable accessibilityRole="button" accessibilityLabel={accessibilityLabel} accessibilityState={{ disabled }} testID={testID} onPress={onPress} disabled={disabled} style={({ pressed }) => [styles.button, secondary && styles.secondary, danger && { backgroundColor: colors.red }, block && { flex: 1 }, children === undefined && { paddingHorizontal: 13 }, disabled && { opacity: 0.38 }, pressed && { opacity: 0.75 }]}>
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>{icon}{children !== undefined && <Text key={fontScale} style={[styles.buttonText, secondary && { color: colors.text }]}>{children}</Text>}</View>
  </Pressable>;
}
const chipTones = { neutral: colors.muted, accent: colors.accent, warning: colors.warning, danger: colors.red } as const;
export function Chip({ children, tone = 'neutral' }: { children: ReactNode; tone?: keyof typeof chipTones }) {
  return <View style={styles.chip}><View style={[styles.chipDot, { backgroundColor: chipTones[tone] }]} /><Text style={[styles.chipText, tone !== 'neutral' && { color: colors.text }]}>{children}</Text></View>;
}
export function Metric({ label, value, unit, color = colors.text, large = false, style }: { label: string; value: string; unit: string; color?: string; large?: boolean; style?: ViewStyle }) {
  const { fontScale } = useWindowDimensions();
  const missing = value === '—';
  return <View style={[{ gap: 2, flexBasis: (large ? 125 : 110) * fontScale, flexGrow: 1, flexShrink: 1, minWidth: 0, maxWidth: '100%' }, style]}>
    <View style={{ minHeight: 28 * fontScale, justifyContent: 'flex-end' }}><Text numberOfLines={2} accessibilityLabel={label} style={[styles.label, { color: colors.muted, lineHeight: 14 }]}>{label}</Text></View>
    <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 6 }}>
      {/* iOS Fabric auto-fit can shrink to 4pt despite minimumFontScale; preserve readable exact text. */}
      <Text selectable style={{ flexShrink: 1, minWidth: 0, fontSize: large ? type.hero : type.value, lineHeight: large ? 46 : 32, fontWeight: '600', color: missing ? colors.muted : color, fontVariant: ['tabular-nums'], letterSpacing: -0.8 }}>{value}</Text>
      {!!unit && <Text style={{ color: colors.muted, fontSize: type.body, flexShrink: 0 }}>{unit}</Text>}
    </View>
  </View>;
}
export function formatDuration(seconds: number) {
  const s = Math.max(0, Math.floor(seconds)); return `${Math.floor(s / 3600) ? `${Math.floor(s / 3600)}:` : ''}${Math.floor(s / 60 % 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}`;
}
export const styles = StyleSheet.create({
  card: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, padding: 14, gap: space.md },
  label: { fontSize: type.label, fontWeight: '600', letterSpacing: 0.6, textTransform: 'uppercase' },
  heading: { fontSize: type.title, fontWeight: '600', letterSpacing: -0.4, color: colors.text },
  body: { color: colors.text, fontSize: type.body, lineHeight: 20 },
  button: { paddingVertical: 11, paddingHorizontal: 16, borderRadius: radius.md, backgroundColor: colors.accent, alignItems: 'center', justifyContent: 'center', minHeight: 44, flexShrink: 1, maxWidth: '100%' },
  secondary: { backgroundColor: colors.surfaceRaised, borderWidth: 1, borderColor: colors.border },
  buttonText: { color: colors.bg, fontSize: type.control, fontWeight: '600', textAlign: 'center' },
  chip: { alignSelf: 'flex-start', flexDirection: 'row', alignItems: 'center', gap: 6, borderRadius: 999, backgroundColor: colors.surfaceRaised, paddingVertical: 4, paddingHorizontal: 10 },
  chipDot: { width: 6, height: 6, borderRadius: 3 },
  chipText: { color: colors.muted, fontSize: type.caption, fontWeight: '600' },
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.md },
});
