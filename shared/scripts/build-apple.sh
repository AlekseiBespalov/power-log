#!/bin/sh
set -eu
power_log_shared=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
power_log_usage() {
  echo "Usage: build-apple.sh [--macos | --dev-tools --slice device|simulator]" >&2
  exit 1
}
power_log_options=
case "${1:-}" in
  "")
    [ "$#" -eq 0 ] || power_log_usage
    power_log_task=:runtime:assemblePowerLogCoreReleaseXCFramework
    ;;
  --macos)
    [ "$#" -eq 1 ] || power_log_usage
    power_log_task=:runtime:linkDebugFrameworkMacosArm64
    ;;
  --dev-tools)
    [ "$#" -eq 3 ] && [ "$2" = --slice ] || power_log_usage
    case "$3" in
      device) power_log_slice=iosArm64 ;;
      simulator) power_log_slice=iosSimulatorArm64 ;;
      *) power_log_usage ;;
    esac
    power_log_task=:runtime:assemblePowerLogCoreDebugXCFramework
    power_log_options="-PpowerLog.devTools=true -PpowerLog.appleSlice=$power_log_slice"
    ;;
  *) power_log_usage ;;
esac
power_log_gradle_offline=
[ "${POWER_LOG_GRADLE_OFFLINE:-0}" != 1 ] || power_log_gradle_offline=--offline
"$power_log_shared/gradlew" -p "$power_log_shared" --console=plain $power_log_gradle_offline $power_log_options \
  "$power_log_task"
