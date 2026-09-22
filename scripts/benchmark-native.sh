#!/bin/sh
set -eu
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
power_log_tmp=$(mktemp -d "${TMPDIR:-/tmp}/powerlog-benchmark.XXXXXX")
trap 'rm -rf "$power_log_tmp"' EXIT HUP INT TERM
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
power_log_benchmark_source=modules/cyc-bridge/ios/Tests/Performance/main.swift
case "${1:-}" in
  --capture) power_log_benchmark_source=modules/cyc-bridge/ios/Tests/Capture/main.swift; shift ;;
  --distance) power_log_benchmark_source=modules/cyc-bridge/ios/Tests/DistanceBenchmark/main.swift; shift ;;
  --transfer-profile) power_log_benchmark_source=modules/cyc-bridge/ios/Tests/TransferPerformance/main.swift; shift ;;
  --projection) power_log_benchmark_source=modules/cyc-bridge/ios/Tests/ProjectionPerformance/main.swift; shift ;;
  --exports) power_log_benchmark_source=modules/cyc-bridge/ios/Tests/ExportPerformance/main.swift; shift ;;
  --chart-navigation) power_log_benchmark_source=modules/cyc-bridge/ios/Tests/ChartNavigationPerformance/main.swift; shift ;;
esac
/usr/bin/xcrun swiftc -O -swift-version 5 -module-cache-path "$power_log_tmp/modules" \
  modules/cyc-bridge/ios/CycProtocol.swift modules/cyc-bridge/ios/CycCaptureClock.swift \
  modules/cyc-bridge/ios/PowerLogCapture.swift modules/cyc-bridge/ios/CycDiagnostics.swift \
  modules/cyc-bridge/ios/WorkoutDistance.swift modules/cyc-bridge/ios/WorkoutDistanceStore.swift modules/cyc-bridge/ios/WorkoutTypes.swift modules/cyc-bridge/ios/PowerLogStore.swift \
  modules/cyc-bridge/ios/WorkoutArchive.swift modules/cyc-bridge/ios/WorkoutControl.swift \
  modules/cyc-bridge/ios/WorkoutTransfer.swift modules/cyc-bridge/ios/WorkoutSync.swift modules/cyc-bridge/ios/MonitorData.swift \
  modules/cyc-bridge/ios/WorkoutFIT.swift modules/cyc-bridge/ios/WorkoutEngine.swift \
  "$power_log_benchmark_source" -lsqlite3 -lcompression -o "$power_log_tmp/benchmark"
"$power_log_tmp/benchmark" "$@"
