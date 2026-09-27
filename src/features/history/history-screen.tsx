import { AppShell } from '../../components/app-shell';
import { Heading } from '../../components/ui';
import { SavedRides } from './saved-rides';

export function HistoryScreen() {
  return (
    <AppShell>
      <Heading>History</Heading>
      <SavedRides />
    </AppShell>
  );
}
