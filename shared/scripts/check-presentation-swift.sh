#!/bin/sh
set -eu
power_log_shared=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
power_log_gradle_offline=
[ "${POWER_LOG_GRADLE_OFFLINE:-0}" != 1 ] || power_log_gradle_offline=--offline
"$power_log_shared/gradlew" -p "$power_log_shared" :linkReleaseFrameworkMacosArm64 --console=plain $power_log_gradle_offline
power_log_presentation_build="$power_log_shared/build/presentation-swift"
mkdir -p "$power_log_presentation_build"
xcrun swiftc -warnings-as-errors -F "$power_log_shared/build/bin/macosArm64/releaseFramework" \
  -framework PowerLogCore -module-cache-path "$power_log_presentation_build/modules" \
  "$power_log_shared/../tests/native-config/presentation-swift/main.swift" -o "$power_log_presentation_build/presentation-swift"
"$power_log_presentation_build/presentation-swift"
