require 'cocoapods'
require 'cocoapods/xcode/xcframework'
require 'minitest/autorun'

class CorePodTest < Minitest::Test
  def test_controller_bridge_discovers_static_core_and_both_ios_slices
    root = Pathname.new(__dir__).join('../../modules/power-log-bridge/ios').expand_path
    spec = Pod::Specification.from_file(root + 'PowerLogBridge.podspec')
    accessor = Pod::Sandbox::FileAccessor.new(root, spec.consumer(:ios))
    frameworks = accessor.vendored_xcframeworks
    assert_equal ['PowerLogCore.xcframework'], frameworks.map { |path| path.basename.to_s }
    assert_equal frameworks, accessor.vendored_static_xcframeworks

    core = Pod::Xcode::XCFramework.new(spec.name, frameworks.fetch(0))
    ios = core.slices.select { |slice| slice.platform.name == :ios }
    assert_equal ['ios-arm64', 'ios-arm64-simulator'], ios.map(&:identifier)
    assert_equal [nil, :simulator], ios.map(&:platform_variant)
    ios.each do |slice|
      assert_equal ['arm64'], slice.supported_archs
      assert slice.path.join('Headers/PowerLogCore.h').file?
      assert_match(/framework module "PowerLogCore"/, slice.path.join('Modules/module.modulemap').read)
      assert_match(/umbrella header "PowerLogCore.h"/, slice.path.join('Modules/module.modulemap').read)
      assert slice.build_type.static_framework?
    end
  end
end
