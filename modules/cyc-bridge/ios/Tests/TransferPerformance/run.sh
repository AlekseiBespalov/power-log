#!/bin/sh
set -eu
cd "$(dirname "$0")/../../../../.."
task_tmp=$(mktemp -d "${TMPDIR:-/tmp}/powerlog-transfer-profile.XXXXXX")
trap 'rm -rf "$task_tmp"' EXIT HUP INT TERM
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
# Freeze compiler inputs so another native review can edit an unrelated source.
for source in CycProtocol CycCaptureClock WorkoutTypes PowerLogStore WorkoutArchive WorkoutControl WorkoutTransfer; do
  cp "modules/cyc-bridge/ios/$source.swift" "$task_tmp/$source.swift"
done
/usr/bin/xcrun swiftc -O -swift-version 5 -module-cache-path "$task_tmp/cache" \
  "$task_tmp/CycProtocol.swift" "$task_tmp/CycCaptureClock.swift" \
  "$task_tmp/WorkoutTypes.swift" "$task_tmp/PowerLogStore.swift" \
  "$task_tmp/WorkoutArchive.swift" "$task_tmp/WorkoutControl.swift" "$task_tmp/WorkoutTransfer.swift" \
  modules/cyc-bridge/ios/Tests/TransferPerformance/main.swift \
  -lsqlite3 -lcompression -o "$task_tmp/benchmark"
"$task_tmp/benchmark" "$@"
