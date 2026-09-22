import { Link } from 'expo-router';
import { AppShell } from '../components/app-shell';
import { Heading, colors } from '../components/ui';
export default function NotFound() { return <AppShell><Heading>This page does not exist.</Heading><Link href="/" style={{ color: colors.accent }}>Return to Ride</Link></AppShell>; }
