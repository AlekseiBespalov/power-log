import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { MonitorEditor } from '../../src/features/monitor/monitor-editor';
import { ConnectionSettings } from '../../src/features/dashboard/connection-settings';
import { TabTransition } from '../../src/components/tab-transition';
import { defaultMonitorPreferences } from '../../src/core/monitor';
export { setPlatform, setConnection, disconnects } from './web-ui-parity-platform';

function Harness() {
  const [index, setIndex] = useState(0);
  const view = defaultMonitorPreferences().views.ride;
  return (
    <>
      <ConnectionSettings />
      <MonitorEditor visible view={view} speedUnit="km/h" onChange={() => {}} onReset={() => {}} onClose={() => {}} />
      <button onClick={() => setIndex(value => 1 - value)}>Switch tab</button>
      <TabTransition index={index}>
        <div data-testid="route-content">Route {index}</div>
      </TabTransition>
    </>
  );
}
export function mount() {
  const container = document.createElement('div');
  document.body.append(container);
  createRoot(container).render(<Harness />);
}
