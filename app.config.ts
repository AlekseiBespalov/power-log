import type { ExpoConfig } from 'expo/config';
import { version } from './package.json';

const [major, minor, patch] = version.split('.').map(Number) as [number, number, number];
const iosBuildNumber = process.env.POWER_LOG_IOS_BUILD_NUMBER ?? String(major * 10000 + minor * 100 + patch);
if (!/^[1-9]\d*$/.test(iosBuildNumber) || !Number.isSafeInteger(Number(iosBuildNumber)))
  throw new Error('Invalid iOS build number');
const androidVersionCode = Number(process.env.POWER_LOG_ANDROID_VERSION_CODE ?? 1);
if (!Number.isSafeInteger(androidVersionCode) || androidVersionCode < 1 || androidVersionCode > 2100000000)
  throw new Error('Invalid Android version code');

const config: ExpoConfig = {
  name: 'Power Log',
  slug: 'power-log',
  version,
  scheme: 'power-log',
  icon: './assets/Assets.xcassets/AppIcon.appiconset/AppIcon.png',
  orientation: 'default',
  userInterfaceStyle: 'dark',
  platforms: ['ios', 'android', 'web'],
  android: {
    allowBackup: false,
    package: 'app.powerlog.mobile',
    versionCode: androidVersionCode,
    permissions: [
      'BLUETOOTH_SCAN',
      'BLUETOOTH_CONNECT',
      'ACCESS_COARSE_LOCATION',
      'ACCESS_FINE_LOCATION',
      'POST_NOTIFICATIONS',
    ],
    blockedPermissions: [
      'android.permission.RECORD_AUDIO',
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.WRITE_EXTERNAL_STORAGE',
      'android.permission.SYSTEM_ALERT_WINDOW',
    ],
  },
  ios: {
    deploymentTarget: '26.0',
    buildNumber: iosBuildNumber,
    bundleIdentifier: process.env.POWER_LOG_BUNDLE_ID ?? 'app.powerlog.mobile',
    appleTeamId: process.env.POWER_LOG_APPLE_TEAM_ID,
    supportsTablet: false,
    entitlements: { 'com.apple.developer.healthkit': true },
    privacyManifests: { NSPrivacyTracking: false, NSPrivacyAccessedAPITypes: [] },
    infoPlist: {
      NSBluetoothAlwaysUsageDescription:
        'Power Log connects to the CYC controller on your bike over Bluetooth to show and record your rides.',
      NSHealthShareUsageDescription:
        'Power Log reads the heart rate, heart rate variability, respiratory rate, blood oxygen, active and resting energy, physical effort, workout effort, and cycling distance, speed, power, cadence and functional threshold power recorded during your rides. It stores them with each ride for its charts, summary and exports. It also reads workouts to confirm that your Apple Watch saved a ride.',
      NSHealthUpdateUsageDescription:
        'When Health saving is on, Power Log saves each ride as a cycling workout. The workout includes your GPS route, distance and speed, rider power and cadence from your bike, and the heart rate and active and resting energy measured during it.',
      NSLocationWhenInUseUsageDescription:
        'Power Log records your outdoor ride route, distance, speed and elevation while a workout is active.',
      UIBackgroundModes: ['audio', 'bluetooth-central', 'location'],
      ITSAppUsesNonExemptEncryption: false,
    },
  },
  web: { bundler: 'metro', output: 'static', name: 'Power Log', shortName: 'Power Log' },
  plugins: [
    'expo-router',
    './plugins/with-power-log-dev-client',
    // Precompiled Expo modules require React.framework; building them from source keeps the app launchable
    // when the React Native prebuilt is unavailable and pod install falls back to a source build.
    [
      'expo-build-properties',
      {
        android: { minSdkVersion: 28, kotlinVersion: '2.1.20' },
        ios: { buildReactNativeFromSource: false, usePrecompiledModules: false, enableSceneSupport: true },
      },
    ],
    './plugins/with-power-log-core',
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
