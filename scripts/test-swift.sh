#!/bin/sh
set -eu
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
power_log_tmp=$(mktemp -d "${TMPDIR:-/tmp}/powerlog-swift.XXXXXX")
trap 'rm -rf "$power_log_tmp"' EXIT HUP INT TERM
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
if [ "$(uname -s)" != Darwin ]; then
  echo "Swift native checks require macOS with Xcode." >&2
  exit 1
fi
native_compile() {
  /usr/bin/xcrun swiftc -swift-version 5 -module-cache-path "$power_log_tmp/modules" \
    modules/cyc-bridge/ios/CycProtocol.swift \
    modules/cyc-bridge/ios/CycCaptureClock.swift \
    modules/cyc-bridge/ios/PowerLogCapture.swift \
    modules/cyc-bridge/ios/WorkoutDistance.swift modules/cyc-bridge/ios/WorkoutDistanceStore.swift modules/cyc-bridge/ios/WorkoutTypes.swift \
    modules/cyc-bridge/ios/PowerLogStore.swift \
    modules/cyc-bridge/ios/WorkoutArchive.swift \
    modules/cyc-bridge/ios/WorkoutControl.swift \
    modules/cyc-bridge/ios/WorkoutTransfer.swift modules/cyc-bridge/ios/WorkoutSync.swift \
    -lsqlite3 -lcompression "$@"
}
native_compile \
  modules/cyc-bridge/ios/CycDiagnostics.swift \
  modules/cyc-bridge/ios/Tests/main.swift -o "$power_log_tmp/core-tests"
"$power_log_tmp/core-tests"
native_compile modules/cyc-bridge/ios/CycDiagnostics.swift modules/cyc-bridge/ios/Tests/Capture/main.swift -o "$power_log_tmp/capture-tests"
"$power_log_tmp/capture-tests"
native_compile modules/cyc-bridge/ios/Tests/ActivityControl/main.swift -o "$power_log_tmp/activity-control-tests"
"$power_log_tmp/activity-control-tests"
native_compile \
  modules/cyc-bridge/ios/MonitorData.swift modules/cyc-bridge/ios/Tests/Monitor/main.swift \
  -o "$power_log_tmp/monitor-tests"
native_compile modules/cyc-bridge/ios/WorkoutFIT.swift modules/cyc-bridge/ios/MonitorData.swift modules/cyc-bridge/ios/Tests/MonitorDistance/main.swift -o "$power_log_tmp/monitor-distance-tests"
"$power_log_tmp/monitor-distance-tests"
native_compile modules/cyc-bridge/ios/WorkoutFIT.swift modules/cyc-bridge/ios/MonitorData.swift modules/cyc-bridge/ios/WorkoutExampleRides.swift modules/cyc-bridge/ios/Tests/ExampleRides/main.swift -o "$power_log_tmp/example-rides-tests"
"$power_log_tmp/example-rides-tests"
native_compile modules/cyc-bridge/ios/Tests/WorkoutDistance/main.swift -o "$power_log_tmp/distance-tests"
"$power_log_tmp/distance-tests"
"$power_log_tmp/monitor-tests"
native_compile \
  modules/cyc-bridge/ios/MonitorData.swift modules/cyc-bridge/ios/Tests/ProjectionCache/main.swift \
  -o "$power_log_tmp/projection-tests"
"$power_log_tmp/projection-tests"
native_compile \
  modules/cyc-bridge/ios/MonitorData.swift modules/cyc-bridge/ios/Tests/LevelOfDetail/main.swift \
  -o "$power_log_tmp/lod-tests"
"$power_log_tmp/lod-tests"
/usr/bin/xcrun swiftc -swift-version 5 -module-cache-path "$power_log_tmp/modules" \
  modules/cyc-bridge/ios/MonitorRasterScene.swift modules/cyc-bridge/ios/MonitorRasterRenderer.swift \
  modules/cyc-bridge/ios/MonitorRasterWorker.swift modules/cyc-bridge/ios/MonitorRasterFrames.swift \
  modules/cyc-bridge/ios/MonitorRasterMarkers.swift \
  modules/cyc-bridge/ios/Tests/Raster/main.swift \
  -o "$power_log_tmp/raster-tests"
"$power_log_tmp/raster-tests"
native_compile tests/catalog-native/main.swift -o "$power_log_tmp/catalog-tests"
"$power_log_tmp/catalog-tests"
native_compile \
  modules/cyc-bridge/ios/Tests/Storage/main.swift -o "$power_log_tmp/storage-tests"
native_compile modules/cyc-bridge/ios/Tests/TransferRegression/main.swift -o "$power_log_tmp/transfer-regression-tests"
"$power_log_tmp/transfer-regression-tests"
native_compile modules/cyc-bridge/ios/Tests/TransferScheduling/main.swift -o "$power_log_tmp/transfer-scheduling-tests"
"$power_log_tmp/transfer-scheduling-tests"
native_compile modules/cyc-bridge/ios/Tests/WorkoutSync/main.swift -o "$power_log_tmp/workout-sync-tests"
"$power_log_tmp/workout-sync-tests"
native_compile apple/WatchApp/WatchWorkoutJournal.swift modules/cyc-bridge/ios/Tests/SyncFiles/main.swift -o "$power_log_tmp/sync-file-tests"
"$power_log_tmp/sync-file-tests"
native_compile modules/cyc-bridge/ios/Tests/HealthInsertion/main.swift -o "$power_log_tmp/health-insertion-tests"
"$power_log_tmp/health-insertion-tests"
native_compile modules/cyc-bridge/ios/WorkoutFIT.swift apple/WatchApp/WatchWorkoutJournal.swift modules/cyc-bridge/ios/Tests/RecordingOptions/main.swift -o "$power_log_tmp/recording-options-tests"
"$power_log_tmp/recording-options-tests"
native_compile apple/WatchApp/WatchWorkoutJournal.swift modules/cyc-bridge/ios/Tests/Support/WatchJournalRecords.swift modules/cyc-bridge/ios/Tests/WorkoutOwnership/main.swift -o "$power_log_tmp/workout-ownership-tests"
"$power_log_tmp/workout-ownership-tests"
native_compile apple/WatchApp/WatchWorkoutJournal.swift modules/cyc-bridge/ios/Tests/WorkoutDeletion/main.swift -o "$power_log_tmp/workout-deletion-tests"
"$power_log_tmp/workout-deletion-tests"
"$power_log_tmp/storage-tests"
native_compile \
  modules/cyc-bridge/ios/WorkoutPhoneConnectivity.swift modules/cyc-bridge/ios/Tests/WorkoutPhone/main.swift \
  -o "$power_log_tmp/workout-phone-tests"
"$power_log_tmp/workout-phone-tests"
native_compile modules/cyc-bridge/ios/WorkoutEngine.swift modules/cyc-bridge/ios/Tests/WorkoutOriginal/main.swift \
  -o "$power_log_tmp/original-data-tests"
"$power_log_tmp/original-data-tests"
native_compile apple/WatchApp/WatchReadingFreshness.swift apple/WatchApp/WatchQueryDrain.swift apple/WatchApp/WatchWorkoutPresentation.swift \
  apple/WatchApp/WatchWorkoutJournal.swift modules/cyc-bridge/ios/Tests/Support/WatchJournalRecords.swift apple/WatchApp/Tests/main.swift -o "$power_log_tmp/watch-tests"
"$power_log_tmp/watch-tests"
/usr/bin/xcrun swiftc -frontend -parse \
  modules/cyc-bridge/ios/CycBridgeModule.swift modules/cyc-bridge/ios/CycBridgeAppDelegateSubscriber.swift
/usr/bin/plutil -lint modules/cyc-bridge/ios/PrivacyInfo.xcprivacy
power_log_ios_sdk=$(/usr/bin/xcrun --sdk iphoneos --show-sdk-path)
/usr/bin/xcrun --sdk iphoneos swiftc -typecheck -swift-version 5 \
  -module-cache-path "$power_log_tmp/ios-modules" -target arm64-apple-ios16.4 -sdk "$power_log_ios_sdk" \
  modules/cyc-bridge/ios/CycProtocol.swift modules/cyc-bridge/ios/CycCaptureClock.swift \
  modules/cyc-bridge/ios/PowerLogCapture.swift modules/cyc-bridge/ios/PowerLogRideAttributes.swift modules/cyc-bridge/ios/WorkoutLiveActivity.swift \
  modules/cyc-bridge/ios/CycDiagnostics.swift \
  modules/cyc-bridge/ios/MonitorData.swift modules/cyc-bridge/ios/CycEngine.swift \
  modules/cyc-bridge/ios/MonitorRasterScene.swift modules/cyc-bridge/ios/MonitorRasterRenderer.swift \
  modules/cyc-bridge/ios/MonitorRasterWorker.swift modules/cyc-bridge/ios/MonitorRasterView.swift \
  modules/cyc-bridge/ios/MonitorRasterFrames.swift \
  modules/cyc-bridge/ios/MonitorRasterMarkers.swift \
  modules/cyc-bridge/ios/WorkoutDistance.swift modules/cyc-bridge/ios/WorkoutDistanceStore.swift modules/cyc-bridge/ios/WorkoutTypes.swift modules/cyc-bridge/ios/PowerLogStore.swift \
  modules/cyc-bridge/ios/WorkoutArchive.swift modules/cyc-bridge/ios/WorkoutControl.swift \
  modules/cyc-bridge/ios/WorkoutTransfer.swift modules/cyc-bridge/ios/WorkoutSync.swift modules/cyc-bridge/ios/WorkoutFIT.swift \
  modules/cyc-bridge/ios/WorkoutHealth.swift modules/cyc-bridge/ios/WorkoutLocation.swift \
  modules/cyc-bridge/ios/WorkoutPhoneConnectivity.swift modules/cyc-bridge/ios/WorkoutEngine.swift \
  modules/cyc-bridge/ios/WorkoutExampleRides.swift
power_log_watch_sdk=$(/usr/bin/xcrun --sdk watchos --show-sdk-path)
/usr/bin/xcrun --sdk watchos swiftc -typecheck -swift-version 5 \
  -module-cache-path "$power_log_tmp/watch-modules" -target arm64-apple-watchos10.0 -sdk "$power_log_watch_sdk" \
  modules/cyc-bridge/ios/WorkoutDistance.swift modules/cyc-bridge/ios/WorkoutDistanceStore.swift modules/cyc-bridge/ios/WorkoutTypes.swift modules/cyc-bridge/ios/PowerLogStore.swift \
  modules/cyc-bridge/ios/WorkoutArchive.swift modules/cyc-bridge/ios/WorkoutControl.swift \
  modules/cyc-bridge/ios/WorkoutTransfer.swift modules/cyc-bridge/ios/WorkoutSync.swift apple/WatchApp/*.swift

echo "Native software checks and both SDK typechecks passed. Full app builds and physical device checks run separately."
