#!/bin/sh
set -eu
cd "$(dirname "$0")/../../../../.."
task_tmp=$(mktemp -d "${TMPDIR:-/tmp}/powerlog-transfer-regression.XXXXXX")
trap 'rm -rf "$task_tmp"' EXIT HUP INT TERM
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
/usr/bin/xcrun swiftc -O -swift-version 5 -module-cache-path "$task_tmp/cache" \
  modules/cyc-bridge/ios/WorkoutTypes.swift modules/cyc-bridge/ios/PowerLogStore.swift \
  modules/cyc-bridge/ios/WorkoutArchive.swift modules/cyc-bridge/ios/WorkoutControl.swift modules/cyc-bridge/ios/WorkoutTransfer.swift \
  modules/cyc-bridge/ios/Tests/TransferRegression/main.swift \
  -lsqlite3 -lcompression -o "$task_tmp/tests"
"$task_tmp/tests"
