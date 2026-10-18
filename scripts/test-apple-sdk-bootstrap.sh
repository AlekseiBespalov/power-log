#!/bin/sh
set -eu
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
power_log_sdk_tmp=$(mktemp -d "${TMPDIR:-/tmp}/powerlog-sdk-bootstrap.XXXXXX")
trap 'rm -rf "$power_log_sdk_tmp"' EXIT HUP INT TERM
power_log_core_mac="$power_log_root/shared/build/bin/macosArm64/releaseFramework"
for power_log_sdk_module in UIKit ExpoModulesCore; do
  xcrun swiftc -swift-version 5 -module-cache-path "$power_log_sdk_tmp/modules" -emit-module -emit-library \
    -module-name "$power_log_sdk_module" "tests/lab-apple/sdk-bootstrap/$power_log_sdk_module.swift" \
    -o "$power_log_sdk_tmp/lib$power_log_sdk_module.dylib"
done
xcrun swiftc -swift-version 5 -D POWER_LOG_LAB -module-cache-path "$power_log_sdk_tmp/modules" \
  -I "$power_log_sdk_tmp" -L "$power_log_sdk_tmp" -lUIKit -lExpoModulesCore \
  -F "$power_log_core_mac" -framework PowerLogCore \
  -Xlinker -rpath -Xlinker "$power_log_sdk_tmp" -Xlinker -rpath -Xlinker "$power_log_core_mac" \
  modules/power-log-bridge/ios/WorkoutLabCrash.swift \
  modules/power-log-bridge/ios/PowerLogBridgeAppDelegateSubscriber.swift \
  "${1:-tests/lab-apple/sdk-bootstrap/main.swift}" -o "$power_log_sdk_tmp/sdk-bootstrap"
"$power_log_sdk_tmp/sdk-bootstrap"
