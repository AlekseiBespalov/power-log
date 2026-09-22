import { Text, View, type TextProps } from 'react-native';

type Props = TextProps & { value: string; variants: readonly string[] };

export function StableLabel({ value, variants, style, ...props }: Props) {
  return <View style={{ flexDirection: 'row', alignItems: 'flex-start' }}>
    {variants.map((variant, index) => <Text key={variant} accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants" aria-hidden
      style={[style, { width: '100%', flexShrink: 0, marginLeft: index ? '-100%' : 0, opacity: 0 }]}>{variant}</Text>)}
    <Text {...props} style={[style, { width: '100%', flexShrink: 0, marginLeft: variants.length ? '-100%' : 0 }]}>{value || ' '}</Text>
  </View>;
}
