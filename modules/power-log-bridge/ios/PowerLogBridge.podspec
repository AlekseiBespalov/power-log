Pod::Spec.new do |s|
  s.name           = 'PowerLogBridge'
  s.version        = '1.0.0'
  s.summary        = 'Local native Controller telemetry and ride recording for Power Log'
  s.description    = 'Expo native module for allowlisted CYC X6/X12 telemetry and durable private ride capture.'
  s.author         = 'Power Log'
  s.homepage       = 'https://github.com/AlekseiBespalov/power-log'
  s.license        = { :type => 'Apache-2.0', :file => '../../../LICENSE' }
  s.platform       = :ios, '26.0'
  s.source         = { :path => '.' }
  s.static_framework = true
  s.vendored_frameworks = 'Frameworks/PowerLogCore.xcframework'
  s.dependency 'ExpoModulesCore'
  s.frameworks     = 'CoreBluetooth', 'Foundation', 'UIKit', 'HealthKit', 'CoreLocation', 'WatchConnectivity', 'ActivityKit'
  s.libraries      = 'sqlite3'
  s.swift_version  = '5.9'
  s.source_files   = '*.swift'
  s.resource_bundles = { 'PowerLogBridge_privacy' => ['PrivacyInfo.xcprivacy'] }
  xcconfig = { 'DEFINES_MODULE' => 'YES' }
  if ENV['POWER_LOG_LAB'] == '1'
    xcconfig['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] = '$(inherited) POWER_LOG_LAB'
  end
  s.pod_target_xcconfig = xcconfig
end
