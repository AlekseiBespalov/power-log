#!/bin/sh
set -eu
project=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
output=${1:?Pass a new absolute output directory ending in PowerLog}
case "$output" in /*/PowerLog) ;; *) echo 'Use an absolute path ending in /PowerLog' >&2; exit 1 ;; esac
test ! -e "$output" || { echo 'Output already exists; choose a new directory.' >&2; exit 1; }
temp=$(mktemp -d "${TMPDIR:-/tmp}/powerlog-app-store.XXXXXX")
trap 'rm -rf "$temp"' EXIT HUP INT TERM
cd "$project"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
xcrun swiftc -O -swift-version 5 -module-cache-path "$temp/modules" \
  modules/cyc-bridge/ios/CycProtocol.swift modules/cyc-bridge/ios/CycCaptureClock.swift \
  modules/cyc-bridge/ios/WorkoutDistance.swift modules/cyc-bridge/ios/WorkoutDistanceStore.swift \
  modules/cyc-bridge/ios/WorkoutTypes.swift modules/cyc-bridge/ios/PowerLogStore.swift \
  modules/cyc-bridge/ios/WorkoutArchive.swift modules/cyc-bridge/ios/WorkoutControl.swift \
  modules/cyc-bridge/ios/WorkoutTransfer.swift modules/cyc-bridge/ios/WorkoutSync.swift modules/cyc-bridge/ios/MonitorData.swift \
  modules/cyc-bridge/ios/WorkoutFIT.swift modules/cyc-bridge/ios/WorkoutExampleRides.swift \
  artifacts/app-store/source/main.swift -lsqlite3 -lcompression -o "$temp/generate"
"$temp/generate" "$output"
