#!/bin/sh
set -eu
power_log_shared=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
case "${1:-}" in
  "") power_log_task=assemblePowerLogCoreReleaseXCFramework ;;
  --macos) power_log_task=:linkReleaseFrameworkMacosArm64 ;;
  *) echo "Usage: build-apple.sh [--macos]" >&2; exit 1 ;;
esac
[ "$#" -le 1 ] || { echo "Usage: build-apple.sh [--macos]" >&2; exit 1; }
power_log_gradle_offline=
[ "${POWER_LOG_GRADLE_OFFLINE:-0}" != 1 ] || power_log_gradle_offline=--offline
"$power_log_shared/gradlew" -p "$power_log_shared" --console=plain $power_log_gradle_offline \
  "$power_log_task"
