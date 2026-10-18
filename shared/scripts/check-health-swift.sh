#!/bin/sh
set -eu
power_log_shared=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
power_log_gradle_offline=
[ "${POWER_LOG_GRADLE_OFFLINE:-0}" != 1 ] || power_log_gradle_offline=--offline
"$power_log_shared/gradlew" -p "$power_log_shared" :linkReleaseFrameworkMacosArm64 --console=plain $power_log_gradle_offline
python3 "$power_log_shared/../tests/native-config/health-swift/check-header.py" "$power_log_shared/build/bin/macosArm64/releaseFramework/PowerLogCore.framework/Headers/PowerLogCore.h"
power_log_health_build="$power_log_shared/build/health-swift"
mkdir -p "$power_log_health_build"
xcrun swiftc -warnings-as-errors -F "$power_log_shared/build/bin/macosArm64/releaseFramework" \
  -framework PowerLogCore -module-cache-path "$power_log_health_build/modules" \
  "$power_log_shared/../tests/native-config/health-swift/main.swift" -o "$power_log_health_build/health-swift"
"$power_log_health_build/health-swift"
