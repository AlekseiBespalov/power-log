#!/bin/sh
set -eu
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
power_log_tmp=$(mktemp -d "${TMPDIR:-/tmp}/powerlog-watch-sync-benchmark.XXXXXX")
trap 'rm -rf "$power_log_tmp"' EXIT HUP INT TERM
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
/usr/bin/xcrun swiftc -O -swift-version 5 -module-cache-path "$power_log_tmp/modules" \
  modules/cyc-bridge/ios/CycProtocol.swift modules/cyc-bridge/ios/CycCaptureClock.swift \
  modules/cyc-bridge/ios/WorkoutTypes.swift modules/cyc-bridge/ios/PowerLogStore.swift \
  modules/cyc-bridge/ios/WorkoutArchive.swift modules/cyc-bridge/ios/WorkoutControl.swift \
  modules/cyc-bridge/ios/WorkoutTransfer.swift modules/cyc-bridge/ios/WorkoutSync.swift \
  modules/cyc-bridge/ios/Tests/WatchSyncPerformance/main.swift \
  -lsqlite3 -lcompression -o "$power_log_tmp/benchmark"
"$power_log_tmp/benchmark" "$@"
