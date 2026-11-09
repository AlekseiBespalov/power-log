#!/bin/sh
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
module="$root/modules/power-log/ios"
slice="$root/shared/runtime/build/XCFrameworks/release/PowerLogCore.xcframework/ios-arm64-simulator"
out="$root/test-results/health-simulator"
name="PowerLog Health Suites"
target=arm64-apple-ios26.0-simulator

[ -d "$slice/PowerLogCore.framework" ] || {
  echo "Missing $slice; core:devices builds the release XCFramework" >&2
  exit 1
}
rm -rf "$out"
mkdir -p "$out/modules"

build() {
  binary=$1
  shift
  xcrun --sdk iphonesimulator swiftc -target "$target" -swift-version 5 -Onone \
    -module-cache-path "$out/modules" -F "$slice" -framework PowerLogCore -lsqlite3 -lcompression -lc++ \
    -o "$out/$binary" "$@"
}

build workout-session \
  "$module/health/HealthTypes.swift" "$module/health/WorkoutSession.swift" "$module/Tests/WorkoutSession/main.swift"
build health-provider \
  "$module/health/HealthTypes.swift" "$module/health/WorkoutSession.swift" "$module/health/AppleHealthProvider.swift" \
  "$module/Tests/HealthProvider/main.swift"

device=$(xcrun simctl list devices available | sed -n "s/^ *$name (\([0-9A-F-]*\)) (.*/\1/p" | head -1)
if [ -z "$device" ]; then
  runtime=$(xcrun simctl list runtimes available | sed -n 's/^iOS .* - \(com\.apple\.CoreSimulator\.SimRuntime\.iOS-[0-9-]*\)$/\1/p' | tail -1)
  device=$(xcrun simctl create "$name" com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro "$runtime")
fi
xcrun simctl list devices | grep -q "($device) (Booted)" || xcrun simctl boot "$device"

xcrun simctl spawn "$device" "$out/workout-session"
xcrun simctl spawn "$device" "$out/health-provider"
