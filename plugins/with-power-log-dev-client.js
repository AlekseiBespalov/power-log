const {
  AndroidConfig,
  IOSConfig,
  createRunOncePlugin,
  withAndroidManifest,
  withInfoPlist,
  withPlugins,
} = require('expo/config-plugins');
const getDefaultScheme = require('expo-dev-client/getDefaultScheme');

function withDevelopmentClient(config) {
  config = withInfoPlist(config, mod => {
    mod.modResults.NSAppTransportSecurity = { ...mod.modResults.NSAppTransportSecurity, NSAllowsLocalNetworking: true };
    return mod;
  });
  return withPlugins(config, ['expo-dev-client']);
}

function withoutDevelopmentClient(config) {
  // Prebuild applies the installed development client's plugins even when the app config omits them
  // (legacyExpoPlugins in @expo/prebuild-config/build/plugins/withDefaultPlugins.js) unless a plugin of the same
  // run-once name has already run.
  for (const name of ['expo-dev-client', 'expo-dev-launcher'])
    config = createRunOncePlugin(config => config, name)(config);
  const scheme = getDefaultScheme(config);
  config = withInfoPlist(config, mod => {
    mod.modResults = IOSConfig.Scheme.removeScheme(scheme, mod.modResults);
    delete mod.modResults.NSBonjourServices;
    delete mod.modResults.NSLocalNetworkUsageDescription;
    delete mod.modResults.NSAppTransportSecurity?.NSAllowsLocalNetworking;
    return mod;
  });
  return withAndroidManifest(config, mod => {
    for (const filter of AndroidConfig.Manifest.getMainActivityOrThrow(mod.modResults)['intent-filter'] ?? [])
      if (filter.data) filter.data = filter.data.filter(data => data.$['android:scheme'] !== scheme);
    return mod;
  });
}

module.exports = config =>
  process.env.POWER_LOG_DEV_CLIENT === '1' ? withDevelopmentClient(config) : withoutDevelopmentClient(config);
