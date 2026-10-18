import { Link } from 'expo-router';
import { AppShell } from '../components/app-shell';
import { Heading, colors, MIN_TOUCH_TARGET, type } from '../components/ui';
export default function NotFound() {
  return (
    <AppShell>
      <Heading>This page does not exist.</Heading>
      <Link
        href="/"
        style={{ color: colors.accent, fontSize: type.body, minHeight: MIN_TOUCH_TARGET, justifyContent: 'center' }}
      >
        Return to Ride
      </Link>
    </AppShell>
  );
}
