import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { View } from 'react-native';
import { Body, Button, Chip, Heading, Label, Metric } from '../../src/components/ui';
import { chartMetrics } from '../../src/components/charts/chart-groups';
import { ChartsEditor } from '../../src/features/charts/charts-editor';
import { TabTransition } from '../../src/components/tab-transition';
export { setPlatform } from './web-ui-parity-platform';

function Harness({ surface }: { surface: 'editor' | 'type' }) {
  const [index, setIndex] = useState(0);
  const view = { id: 'ride', name: 'Ride', numbers: ['humanPowerW'], charts: ['humanPowerW'] };
  if (surface === 'type')
    return (
      <>
        <Label>Label token</Label>
        <Body>Body token</Body>
        <Heading>Title token</Heading>
        <Button onPress={() => {}}>Control token</Button>
        <Chip>Caption token</Chip>
        <View testID="value-token">
          <Metric label="Value token" value="237" unit="W" />
        </View>
        <View testID="hero-token">
          <Metric label="Hero token" value="237" unit="W" large />
        </View>
      </>
    );
  return (
    <>
      <ChartsEditor
        visible
        view={view}
        metric={chartMetrics('metric', 'celsius')}
        onChange={() => {}}
        onReset={() => {}}
        onClose={() => {}}
      />
      <button onClick={() => setIndex(value => 1 - value)}>Switch tab</button>
      <TabTransition index={index}>
        <div data-testid="route-content">Route {index}</div>
      </TabTransition>
    </>
  );
}
let root: ReturnType<typeof createRoot>;
export function mount(surface: 'editor' | 'type' = 'editor') {
  if (root) {
    root.render(<Harness key={surface} surface={surface} />);
    return;
  }
  const container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  root.render(<Harness surface={surface} />);
}
