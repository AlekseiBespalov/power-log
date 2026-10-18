#!/bin/sh
set -eu
power_log_shared=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
power_log_gradle_offline=
[ "${POWER_LOG_GRADLE_OFFLINE:-0}" != 1 ] || power_log_gradle_offline=--offline
"$power_log_shared/gradlew" -p "$power_log_shared" iosSimulatorArm64Test --continue --console=plain \
  $power_log_gradle_offline
node "$power_log_shared/scripts/check-test-results.mjs" iosSimulatorArm64Test
