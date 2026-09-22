Pod::Spec.new do |s|
  s.name           = 'CycBridge'
  s.version        = '1.0.0'
  s.summary        = 'Local native CYC telemetry and ride recording for Power Log'
  s.description    = 'Expo native module for allowlisted CYC X6/X12 telemetry and durable private ride capture.'
  s.author         = 'Power Log'
  s.homepage       = 'https://github.com/AlekseiBespalov/power-log'
  s.license        = { :type => 'Apache-2.0', :file => '../../../LICENSE' }
  s.platform       = :ios, '16.4'
  s.source         = { :path => '.' }
  s.static_framework = true
  s.dependency 'ExpoModulesCore'
  s.frameworks     = 'CoreBluetooth', 'Foundation', 'UIKit', 'HealthKit', 'CoreLocation', 'WatchConnectivity', 'ActivityKit'
  s.libraries      = 'sqlite3'
  s.swift_version  = '5.9'
  s.source_files   = '*.swift'
  s.resource_bundles = { 'CycBridge_privacy' => ['PrivacyInfo.xcprivacy'] }
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
end
