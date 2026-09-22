# Testing

Use Node 24 and `npm ci`. Browser checks need Playwright Chromium (`npx playwright install chromium`); native checks need macOS and full Xcode with iOS/watchOS SDKs.

| Command | Coverage |
| --- | --- |
| `npm run check` | Types, lint, unit tests, native target generation, dependency regressions, notice generation and web build |
| `npm run test:e2e` | Browser UI, layouts, gestures, history and exports |
| `npm run test:browser` | IndexedDB/Web Locks, recording/recovery and production-provider integration |
| `npm run test:swift` | Native protocol, storage, capture, chart, ownership and sync regressions; Expo module parse and both SDK typechecks |
| `npm run test:workout` | Native FIT export decoded independently by Garmin's Python SDK |
| `npm run test:public-web` | Production Pages routes, notice files, parser safety and unexpected network requests |

Install `garmin-fit-sdk` in a Python environment and set `FIT_PYTHON` to that interpreter for FIT checks. Tests use disposable stores and synthetic/sanitized inputs; see [fixture provenance](../tests/fixtures/README.md). ZIP tests need macOS file-coordination services: rerun a sandbox-denied operation with that access rather than treating it as an app defect. Swift typechecks are not linked app builds.

## Native build and installation

```sh
npm run prebuild:ios
(cd ios && bundle exec pod install)
# Build Debug or Release in ios/PowerLog.xcworkspace.
npm run test:ios-bundle -- /absolute/path/to/PowerLog.app
codesign --verify --deep --strict /absolute/path/to/PowerLog.app
```

Prebuild regenerates native targets, including the configured UIScene lifecycle and Live Activity extension. Before installation, confirm neither device is recording and preserve user-selected rides. Install matching phone/Watch versions, confirm each process stays alive and inspect the UI. Release must launch with Metro stopped. An install/launch command succeeding does not prove startup or background reliability.

## Benchmarks

Use `npm run benchmark:native` or a focused mode: `-- --capture`, `-- --transfer-profile`, `-- --projection`, `-- --exports`, `-- --chart-navigation`, `-- --distance`. Browser scale checks use `npm run benchmark:browser`. Watch transfer workloads use `sh scripts/benchmark-watch-sync.sh` (add `--smoke` for a short run).

These runners use production storage/query/transfer code with synthetic 57-minute/eight-hour workloads. The Watch stress model adds five Watch records per CYC sample plus heart-rate/location observations; it tests exact digests, retries and verification. Mac timings and highly compressible fixtures do not establish radio latency, real-ride size, phone flash writes or battery use. Capture comparisons report matched durability paths; WAL growth is not total disk-write volume.

Development builds expose fictional rides in Settings → Developer. They carry generated provenance, export like other examples and hide the Strava shortcut; [screenshot instructions](../artifacts/app-store/README.md) describe reproduction. [MonitorRasterFrames](../modules/cyc-bridge/ios/MonitorRasterFrames.swift) documents the opt-in physical frame sampler: callback cadence is a main-thread proxy, not touch-to-photon latency.

Formatting is optional: `npm run format` / `npm run format:check`. Keep broad formatting separate from behavior changes.

## Physical acceptance

Use signed Release builds launched normally without a debugger, on battery. Record device/OS/controller, requested rate, workload and actual results. Xcode and Mirroring are diagnostic supplements; real fingers are required for gesture checks.

1. **Capture:** test X6/X12 at 2/4/8 Hz, distinct identity/names, nonzero pedal values, reconnects and a moving speed comparison against CYC Ride Control in both unit settings. Compare foreground with at least 30 minutes locked. Exercise controller/radio outages, app switches, termination/restoration and history inspection while recording.
2. **Ownership:** cover Watch, Health and GPS choices on/off, including indoor GPS on, outdoor GPS off, Watch with Health off and phone-only with both off. Exercise pause/resume/lap and finish while unreachable. Verify one owner, correct stop cutoff, one Health workout when requested and no app-created workout when saving is off.
3. **Synchronization:** record a 60-minute Watch ride with repeated locks/switches, then a two-hour soak. Restart either app; compare final original counts/digests, missing tails, staging cleanup and archive receipt. Health outcome and archive completeness must stay distinct.
4. **Controls:** test Watch start while the phone app is hidden and Lock Screen Pause/Resume/Finish, with Live Activities enabled, disabled and dismissed. Include repeated taps and unreachable Watch. A moving system timer does not prove telemetry freshness.
5. **Charts/UI:** inspect short, 57-minute and eight-hour rides; test peaks/exact readouts, pan/pinch, full screen, repeated reorder/scroll, narrow/desktop layouts and larger text. Refreshes, missing data, settings and tab/modal transitions must not shift surrounding layout.
6. **Removal:** discard from both owners, including offline/restart, then start another ride. Delete a completed ride with an offline Watch copy; reconnect and confirm it stays deleted. Reject deletion of active rides; retain previously saved Apple Health workouts.
7. **Exports:** compare browser CSV and iPhone ZIP/FIT timestamps, timing, coverage and provenance. Check the iPhone share sheet/Save to Files and the manual Strava page link. Do not submit a file without explicit user authorization.

Repeat critical background cases three times. Eight-hour generated fixtures establish storage/query scale, not eight-hour unattended recording.

| Measurement | Acceptance target, not a measured guarantee |
| --- | --- |
| Available-link delivery | ≥95% of requested rate per 60-second window; background within 5% of matched foreground |
| Unexplained gaps | None beyond `max(0.5 seconds, 3 / requestedHz)`; classify controlled outages separately |
| Background CPU / writes | Aim ≤3 CPU seconds/minute at 8 Hz and <100 KB/s sustained; report spikes, bytes/sample, WAL/checkpoints and DB growth |
| Admission | BLE enqueue p95 <5 ms; report oldest buffered sample and worst commit delay |
| Hidden UI | No periodic chart/catalog/display work after cancellation drains; no growing event backlog |
| Interaction | Real gesture-to-presentation p95 <50 ms; no stalls >100 ms |
| Durability | No lost acknowledged originals, resource termination or growing queues; bounded cost with ride length |

Keep run-specific logs outside Git. Report source tests, linked builds, startup and physical acceptance separately. Apple references: [background Bluetooth execution](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html), [CPU profiling](https://developer.apple.com/documentation/xcode/addressing-cpu-bottlenecks).
