export const APP_NAVIGATION = [
  { href: '/', route: 'index', title: 'Ride' },
  { href: '/sessions', route: 'sessions', title: 'History' },
  { href: '/settings', route: 'settings', title: 'Settings' },
] as const;

export function appNavigationIndex(pathOrRoute: string) {
  return Math.max(0, APP_NAVIGATION.findIndex(item => item.href === pathOrRoute || item.route === pathOrRoute));
}
