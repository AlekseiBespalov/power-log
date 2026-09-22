import { AppShell } from '../../components/app-shell';
import { Heading } from '../../components/ui';
import { SavedWorkouts } from './saved-workouts';

export function SessionsScreen() {
  return <AppShell><Heading>History</Heading><SavedWorkouts /></AppShell>;
}
