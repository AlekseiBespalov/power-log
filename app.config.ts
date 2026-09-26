import type { ExpoConfig } from 'expo/config';
import { version } from './package.json';

const androidVersionCode = Number(process.env.POWER_LOG_ANDROID_VERSION_CODE ?? 1);
if (!Number.isSafeInteger(androidVersionCode) || androidVersionCode < 1 || androidVersionCode > 2100000000) throw new Error('Invalid Android version code');

const config: ExpoConfig = {
  name: process.env.POWER_LOG_ANDROID_PREVIEW === '1' ? 'Power Log Preview' : 'Power Log', slug: 'power-log', version, scheme: 'power-log',
  icon: './assets/Assets.xcassets/AppIcon.appiconset/AppIcon.png',
  orientation: 'default', userInterfaceStyle: 'dark',
  platforms: ['ios', 'android', 'web'],
  android: {
    allowBackup: false,
    package: process.env.POWER_LOG_ANDROID_PREVIEW === '1' ? 'app.powerlog.mobile.preview' : 'app.powerlog.mobile',
    versionCode: androidVersionCode,
    permissions: ['BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'POST_NOTIFICATIONS'],
    blockedPermissions: ['android.permission.RECORD_AUDIO', 'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE'],
  },
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
    ['expo-build-properties', { android: { minSdkVersion: 28 }, ios: { buildReactNativeFromSource: true, usePrecompiledModules: false, enableSceneSupport: true } }],
    './plugins/with-power-log-watch',
    './plugins/with-power-log-live-activity',
    './plugins/with-power-log-android',
  ],
  experiments: {
    typedRoutes: true,
    ...(process.env.POWER_LOG_WEB_BASE_URL ? { baseUrl: process.env.POWER_LOG_WEB_BASE_URL } : {}),
  },
};
export default config;
