import type { ExpoConfig } from 'expo/config';

const config: ExpoConfig = {
  name: 'Power Log', slug: 'power-log', version: '0.1.0', scheme: 'power-log',
  icon: './assets/Assets.xcassets/AppIcon.appiconset/AppIcon.png',
  orientation: 'default', userInterfaceStyle: 'dark',
  platforms: ['ios', 'web'],
  ios: {
    bundleIdentifier: process.env.POWER_LOG_BUNDLE_ID ?? 'app.powerlog.mobile',
    appleTeamId: process.env.POWER_LOG_APPLE_TEAM_ID,
    supportsTablet: true,
    entitlements: { 'com.apple.developer.healthkit': true },
    infoPlist: {
      NSBluetoothAlwaysUsageDescription: 'Power Log reads your CYC bike telemetry.',
      NSHealthShareUsageDescription: 'Power Log reads your workout heart rate, energy and cycling measurements to show and save your ride.',
      NSHealthUpdateUsageDescription: 'Power Log saves your cycling workouts, rider power, cadence and outdoor routes to Apple Health.',
      NSLocationWhenInUseUsageDescription: 'Power Log records your outdoor ride route, distance, speed and elevation while a workout is active.',
      UIBackgroundModes: ['bluetooth-central', 'location'],
      ITSAppUsesNonExemptEncryption: false,
    },
  },
  web: { bundler: 'metro', output: 'static', name: 'Power Log', shortName: 'Power Log' },
  plugins: [
    'expo-router', 'expo-dev-client', 'expo-document-picker',
    // Precompiled Expo modules require React.framework. Keep both on source
    // builds so a missing React prebuilt cannot produce an unlaunchable app.
    ['expo-build-properties', { ios: { buildReactNativeFromSource: true, usePrecompiledModules: false, enableSceneSupport: true } }],
    './plugins/with-power-log-watch',
    './plugins/with-power-log-live-activity',
  ],
  experiments: {
    typedRoutes: true,
    ...(process.env.POWER_LOG_WEB_BASE_URL ? { baseUrl: process.env.POWER_LOG_WEB_BASE_URL } : {}),
  },
};
export default config;
