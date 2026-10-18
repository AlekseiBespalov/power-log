#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
power_log_tmp=$(mktemp -d "${TMPDIR:-/tmp}/powerlog-fixture.XXXXXX")
trap 'rm -rf "$power_log_tmp"' EXIT HUP INT TERM
sh shared/scripts/build-apple.sh --macos
power_log_sources=
for power_log_name in MonitorInspection TelemetryAdapter TelemetryCaptureClock PowerLogCapture TelemetryDiagnostics \
  RideSnapshot WorkoutHealthMembers WorkoutCompanion WorkoutDistance WorkoutDistanceStore WorkoutTypes PowerLogStore WorkoutArchive \
  WorkoutControl WorkoutSync MonitorData WorkoutAnalysis WorkoutEngine; do
  power_log_sources="$power_log_sources modules/power-log-bridge/ios/$power_log_name.swift"
done
/usr/bin/xcrun swiftc -whole-module-optimization -O -swift-version 5 -module-cache-path "$power_log_tmp/modules" \
  $power_log_sources scripts/device-eight-hour/main.swift -F shared/build/bin/macosArm64/releaseFramework \
  -framework PowerLogCore -lsqlite3 -lcompression -o "$power_log_tmp/fixture"
"$power_log_tmp/fixture" "$@"
