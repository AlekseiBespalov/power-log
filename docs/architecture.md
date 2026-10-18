# Architecture

Power Log records CYC X6/X12 controller telemetry, phone GPS and ride-related Health measurements. The phone keeps
one archive per ride, including raw controller observations at the selected 2/4/8 Hz rate. Live and History charts
query that archive; FIT and raw ZIP exports read it through bounded, revision-checked pages. Product choices are in
[decisions](decisions.md) and validation procedures in [testing](testing.md).

## Runtime structure

- **Phone recording:** Swift owns iPhone capture and SQLite; Kotlin owns Android capture, SQLite and its foreground
  service. Recording continues independently of React. Before Start, a bounded in-memory preview serves live charts.
- **Watch companion:** the phone is required. The Watch runs the native Health session and writes Health for an
  assisted ride. It streams timestamped observations through a bounded acknowledgement buffer and receives paced
  telemetry/route batches and immediate controls. It has no permanent ride archive or shared Kotlin dependency.
- **Web:** foreground Bluetooth/GPS recording uses IndexedDB and Web Locks for tab exclusion. Reload marks the last
  committed ride Interrupted. The rider can finish the saved portion or discard it.
- **Local lifecycle:** atomic capture and control writes retain committed data. Start freezes options and the distance
  producer. Finish completes the local archive before external Health work. Interrupted recordings are not resumed;
  native-session recovery exists only to clean up a session that the OS still retains.
- **Start preferences:** native iPhone Start reads the same committed monitor settings as the UI, including for
  Watch requests while React is inactive. The Watch has no independent capture-rate preference.
- **Health:** append-once publication uses one writer, fixed provider identities where supported, bounded calls and
  small outcomes. Unknown effects are not blindly repeated. Readback adds supported measurements without exhaustive
  provider edit/deletion synchronization. Late data can change local graphs without revising published Health values.
  Reading and publication are independently eligible. Discard stops local work and native collection without
  deleting saved provider records.
- **Shared core:** pure Kotlin logic supplies telemetry decoding, numerical analytics, exact inspection, local
  lifecycle and phone presentation. iPhone consumes `PowerLogCore.xcframework`, Android the JVM artifact and
  TypeScript generated `shared/js/`. Adapters own IO, permissions, transactions, clocks and scheduling.
- **UI:** Expo/React Native shares phone/web features. SwiftUI supplies Watch controls; Live Activity and Android
  notifications expose native controls. Custom charts preserve extrema, exact cursor/A–B inspection and subsecond data.

## Code map

| Responsibility | Maintained files |
| --- | --- |
| Shared telemetry and numerical rules | `shared/src/commonMain/kotlin/app/powerlog/core/{telemetry,gps,analytics,inspection}/` |
| Local lifecycle, completion and phone presentation | `shared/src/commonMain/kotlin/app/powerlog/core/{lifecycle,completion,presentation}/` |
| Provider bounds and source selection | `shared/src/commonMain/kotlin/app/powerlog/core/health/` |
| iPhone recording and archive | `modules/power-log-bridge/ios/WorkoutEngine.swift`, `WorkoutControl.swift`, `PowerLogCapture.swift`, `PowerLogStore.swift`, `WorkoutArchive.swift` |
| Phone Start preferences | `src/services/monitor-preferences-controller.ts`, `monitor-preferences-store.native.ts`, `modules/power-log-bridge/ios/WorkoutPreferences.swift` |
| Apple companion transport and native Health | `modules/power-log-bridge/ios/WorkoutCompanion.swift`, `WorkoutCompanionTransport.swift`, `WorkoutNativeHealth.swift`, `WorkoutHealthMetrics.swift` |
| Watch UI and session | `apple/WatchApp/WatchWorkoutEngine.swift`, `WatchWorkoutPresentation.swift`, `WatchWorkoutView.swift`, `PowerLogWatchApp.swift` |
| Apple queries and rendering | `modules/power-log-bridge/ios/WorkoutAnalysis.swift`, `WorkoutDistanceStore.swift`, `MonitorData.swift`, `MonitorInspection.swift`, `MonitorRaster*.swift` |
| Android recording and storage | `modules/power-log-bridge/android/src/main/java/app/powerlog/bridge/RecordingEngine.kt`, `RecordingService.kt`, `TelemetryCapture.kt`, `RideStore.kt` |
| Android Health | `modules/power-log-bridge/android/src/main/java/app/powerlog/bridge/HealthExport.kt`, `HealthPublicationWorker.kt`, `HealthReadback.kt`, `HealthReadings.kt`, `HealthRecords.kt`, `HealthConnectAccess.kt` |
| Android queries and rendering | `modules/power-log-bridge/android/src/main/java/app/powerlog/bridge/RideAnalysis.kt`, `RideDistance.kt`, `MetricCache.kt`, `RideSummaryCache.kt`, `RideMonitor.kt`, `MonitorRasterView.kt` |
| Browser recording and queries | `src/services/browser-workout-recorder.ts`, `browser-ride-store.ts`, `browser-gps.ts`, `browser-distance-store.ts`, `browser-analytics.ts`, `browser-workout-monitor.ts` |
| Charts and read scheduling | `src/components/monitor-charts.tsx`, `src/features/monitor/`, `src/core/monitor*.ts`, `src/services/read-scheduler.ts` |
| History and controls | `src/features/history/`, `src/features/workout/`, `src/services/workout-context.tsx`, `ride-control-actions.ts`, `workout-external-actions.ts` |
| Export | `src/core/export/`, `src/services/ride-export.ts`, `browser-export-source.ts`, native `ExportSource` and `ExportSink` adapters |
| Live Activity and notifications | `apple/LiveActivity/`, `modules/power-log-bridge/ios/WorkoutActivity*.swift`, Android `RideNotificationAdapter.kt` |
| Native configuration | `app.config.ts`, `plugins/`, `modules/power-log-bridge/` |
| Checks and synthetic workloads | `scripts/`, `tests/`, shared common tests, native `Tests`/`src/test`/`src/androidTest`, separate `lab/apple/` |

## Boundaries

Routes stay thin in `src/app/`; domain logic lives in `src/core/`, feature UI in `src/features/`, primitives in
`src/components/`, and platform access in `src/services/` and the native bridge. Charts share the metric catalog,
MonitorSource query interface and multi-lane chart component. Derived caches remain rebuildable; unreadable original
stores fail visibly. Export revision checks and deletion protection remain independent of Health saving.

Expo prebuild regenerates `ios/` and `android/`; maintained sources stay in `modules/` and `apple/`. Tests never enter
application source phases. Metro selects native/web implementations, and native delegates/autolinking are entry
points, so absence of a direct TypeScript caller does not prove a module is unused.
