import { forwardRef, type Ref } from 'react';
import { Platform, View as WebView, type ViewProps } from 'react-native-web/dist/cjs/index.js';
import { count, counts } from './monitor-render-counts';

export * from 'react-native-web/dist/cjs/index.js';

export const View = forwardRef(function View(props: ViewProps, ref: Ref<WebView>) {
  if (props.testID?.startsWith('monitor-chart-')) count(counts.renders, props.testID);
  return <WebView {...props} ref={ref} />;
});

export function setPlatform(os: 'ios' | 'android' | 'web') {
  Object.defineProperty(Platform, 'OS', { configurable: true, value: os });
}
