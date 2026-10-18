require 'xcodeproj'
require 'fileutils'

source = File.expand_path(__dir__)
output = File.expand_path(ARGV.fetch(0))
FileUtils.mkdir_p(output)
project = Xcodeproj::Project.new(File.join(output, 'ActivityQualification.xcodeproj'))
host = project.new_target(:application, 'QualificationHost', :ios, '26.0')
tests = project.new_target(:ui_test_bundle, 'ActivityQualification', :ios, '26.0')
host.add_file_references([project.main_group.new_file(File.join(source, 'QualificationHost.swift'))])
tests.add_file_references([project.main_group.new_file(File.join(source, 'ActivityQualification.swift'))])
tests.add_dependency(host)
[host, tests].each do |target|
  target.build_configurations.each do |configuration|
    configuration.build_settings.merge!({
      'PRODUCT_BUNDLE_IDENTIFIER' => "app.powerlog.qualification.#{target.name.downcase}",
      'GENERATE_INFOPLIST_FILE' => 'YES',
      'SWIFT_VERSION' => '5.0',
      'CODE_SIGNING_ALLOWED' => 'NO',
      'TARGETED_DEVICE_FAMILY' => '1',
      'SUPPORTED_PLATFORMS' => 'iphonesimulator',
      'SDKROOT' => 'iphonesimulator'
    })
  end
end
host.build_configurations.each { |config| config.build_settings['INFOPLIST_KEY_UILaunchScreen_Generation'] = 'YES' }
tests.build_configurations.each { |config| config.build_settings['TEST_TARGET_NAME'] = host.name }
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(host)
scheme.set_launch_target(host)
scheme.add_test_target(tests)
scheme.save_as(project.path, 'ActivityQualification', true)
