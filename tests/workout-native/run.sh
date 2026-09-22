#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
task_tmp="$(mktemp -d "${TMPDIR:-/tmp}/power-log-workout-native.XXXXXX")"
trap 'rm -rf "$task_tmp"' EXIT
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
xcrun swiftc -O -module-cache-path "$task_tmp/cache" \
  modules/cyc-bridge/ios/WorkoutDistance.swift modules/cyc-bridge/ios/WorkoutDistanceStore.swift modules/cyc-bridge/ios/WorkoutTypes.swift modules/cyc-bridge/ios/PowerLogStore.swift modules/cyc-bridge/ios/WorkoutArchive.swift \
  modules/cyc-bridge/ios/WorkoutFIT.swift tests/workout-native/main.swift -lsqlite3 -o "$task_tmp/tests"
"$task_tmp/tests" "$task_tmp/output"
"${FIT_PYTHON:-python3}" tests/workout-native/verify_fit.py "$task_tmp/output"
