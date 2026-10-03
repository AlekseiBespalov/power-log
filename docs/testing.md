# Testing

Use Node 24 and `npm ci`; `npm run verify` and `npm run release:android` pick Homebrew's `node@24` when it is installed. Browser checks need Playwright Chromium (`npx playwright install chromium`); Apple checks need macOS and full Xcode with iOS/watchOS SDKs. Android checks need JDK 17 and the Android SDK.

| Command | Coverage |
| --- | --- |
| `npm run check` | Types, lint, unit tests, native target generation, dependency regressions, notice generation and web build |
| `npm run test:native-config` | Native target generation, Watch privacy resources, shared Apple versions/build numbers and publishing prerequisites |
| `npm run test:e2e` | Browser UI, layouts, gestures, history and exports |
| `npm run test:browser` | IndexedDB/Web Locks, recording/recovery and production-provider integration |
| `npm run test:swift` | Native protocol, storage, capture, chart, ownership and sync regressions, the shared bridge-contract fixtures; Expo module parse and both SDK typechecks |
| `npm run build:ios-simulator` | Linked simulator build of the generated iPhone app, its Expo module, app-delegate subscriber and embedded Watch app (`bundle install` first) |
| `npm run test:public-web` | Production Pages routes, notice files, parser safety and unexpected network requests |

The FIT family and iPhone cross-check suites decode their FIT files with Garmin's FIT Python SDK when `garmin-fit-sdk` 21.217.0 is installed; the other FIT tests read files with the tests' own decoder. Set `FIT_PYTHON` to that interpreter (CI does, and then a missing SDK fails the run), or keep a virtual environment at `~/.cache/power-log/fit-venv`; without either, the Garmin checks are skipped. ZIP tests extract every entry with Python's `zipfile`. Tests use disposable stores and synthetic/sanitized inputs; see [fixture provenance](../tests/fixtures/README.md). Swift typechecks are not linked app builds; `build:ios-simulator` is.

The ride snapshot and ride-option rules are fixed by `tests/fixtures/contract/`: the Swift runner and Android JVM tests serialize production values and compare them structurally, and Vitest feeds the same files through the TypeScript consumers. They prove snapshot serialization and consumption, not Expo argument conversion, which the linked builds and physical acceptance cover. For Android, run `npm run test:android` and `npm run test:android -- --emulator`; the emulator run includes the ride fixture and the corrupt-database test through the real `RideStore` open path. See [Android](android.md) for APK builds and physical acceptance.

The Watch journal and `ClockHealth` Swift suites guard the [two time domains](measurements.md#evidence-and-limits): monotonic capture elapsed excludes recovery downtime, while original UTC stays unchanged through forward and backward wall-clock jumps. A Watch recovery fixture retains elapsed 60 at 10:01, resumes at 10:11 and stores a GPS fix at elapsed 61 alongside a raw Health sample at UTC 10:11:01 with UTC-derived placement 661; valid historical Health data remains stored even beyond the measured stop. Clock contracts retain measured terminal timing, original Health write dates and UTC-based omission rules. FIT's start-plus-elapsed placement can differ from Health after recovery or clock changes; these tests do not assert Health/FIT equality or validate native HealthKit execution.

GitHub runs only the website deploy. Before merging or releasing, `npm run verify` checks the commit on this Mac in a fresh temporary worktree of `HEAD`, so ignored local files and generated native folders cannot hide a missing file. With `CI=1` it installs dependencies with `npm ci`, then runs the audit (`scripts/audit.mjs` fails on any moderate or worse advisory it does not accept by name), `npm run check`, the browser, end-to-end and public-web suites, the Swift and Kotlin format checks, the Swift suite, the linked iPhone simulator build and the Android preview build with its unit tests; the worktree is removed when everything passes and kept for inspection when something fails. FIT decoding there is mandatory and uses the Garmin SDK from `FIT_PYTHON` (default `~/.cache/power-log/fit-venv`).

## iOS build and installation

```sh
npm run prebuild:ios
(cd ios && bundle exec pod install)
# Build Debug or Release in ios/PowerLog.xcworkspace.
npm run test:ios-bundle -- /absolute/path/to/PowerLog.app
codesign --verify --deep --strict /absolute/path/to/PowerLog.app
```

Prebuild regenerates native targets, including the configured UIScene lifecycle, Live Activity extension and Watch privacy manifest resource. The phone, Watch and Live Activity use the version from `package.json` and the positive integer `POWER_LOG_IOS_BUILD_NUMBER` (default `1`). Before installation, confirm neither device is recording and preserve user-selected rides. Install matching phone/Watch versions, confirm each process stays alive and inspect the UI. Release must launch with Metro stopped. An install/launch command succeeding does not prove startup or background reliability.

## Benchmarks

Use `npm run benchmark:native` or a focused mode: `-- --capture`, `-- --transfer-profile`, `-- --projection`, `-- --chart-navigation`, `-- --distance`, `-- --watch-sync` (Watch transfer workloads; add `--smoke` for a short run). Browser scale checks use `npm run benchmark:browser`; `npm run benchmark:csv` streams 700,000 synthetic CSV rows and reports parse time and peak heap.

These runners use production storage/query/transfer code with synthetic 57-minute/eight-hour workloads. The Watch stress model adds four Watch records per CYC sample plus heart-rate/location observations; it tests exact digests, retries and verification. Mac timings and highly compressible fixtures do not establish radio latency, real-ride size, phone flash writes or battery use. Capture comparisons report matched durability paths; WAL growth is not total disk-write volume.

Development builds expose fictional rides in Settings → Developer. They carry generated provenance, export like other examples and hide the Strava shortcut; [screenshot instructions](../artifacts/app-store/README.md) describe reproduction. [MonitorRasterFrames](../modules/cyc-bridge/ios/MonitorRasterFrames.swift) documents the opt-in physical frame sampler: callback cadence is a main-thread proxy, not touch-to-photon latency.

Formatting is enforced per language: `sh scripts/format.sh --ts|--swift|--kotlin --check` (or `--write`); `npm run check` runs the TypeScript check and `npm run verify` all three. `npm run format` writes all three. Kotlin uses a pinned standalone ktfmt, downloaded to `~/.cache/power-log` on first use.

## Physical acceptance

Use signed Release builds launched normally without a debugger, on battery. Record device/OS/controller, requested rate, workload and actual results. Xcode and Mirroring are diagnostic supplements; real fingers are required for gesture checks.

1. **Capture:** test X6/X12 at 2/4/8 Hz, distinct identity/names, nonzero pedal values, reconnects and a moving speed comparison against CYC Ride Control in both unit settings. Compare foreground with at least 30 minutes locked. Exercise controller/radio outages, app switches, termination/restoration and history inspection while recording.
2. **Ownership:** cover Watch, Health and GPS choices on/off, including indoor GPS on, outdoor GPS off, Watch with Health off and phone-only with both off. Exercise pause/resume/lap and finish while unreachable. Verify one owner, correct stop cutoff, one Health workout when requested (none, with Health `unavailable` and a clock reason, when the clock put the cutoff before the start) and no app-created workout when saving is off. Include forward and backward clock changes while running, across Pause/Resume and with delayed GPS fixes, a backward clock change before Finish, a restart afterwards, another Start, late Health callbacks, a Finish while the phone is unreachable followed by reconnection, and verified export on both Apple owners. Delay a Watch ride's Health save after Stop and confirm the phone refuses another Start until the Watch confirms its session ended; deny cadence, grant it, then Retry Health save (also after a restart) and confirm the missing samples arrive. Repeat on each iPhone tier: iOS 16.x (phone-owned rides only, no Health), iOS 17–25 (phone-owned rides without Health, Watch-owned rides with Health) and iOS 26+; include a locked-screen ride with GPS off and one with GPS on below iOS 26.
3. **Synchronization:** record a 60-minute Watch ride with repeated locks/switches, then a two-hour soak. Restart either app; compare final original counts/digests, missing tails, staging cleanup and archive receipt. Health outcome and archive completeness must stay distinct.
4. **Controls:** test Watch start while the phone app is hidden and Lock Screen Pause/Resume/Finish, with Live Activities enabled, disabled and dismissed. Include repeated taps and unreachable Watch. A moving system timer does not prove telemetry freshness. With the controller disconnected, GPS and Watch heart-rate readings stay live; a reading delivered late (Watch fallback transport, busy JavaScript thread, a phone that slept) is never shown as live past its hold after it was measured: six seconds for controller readings, ten for GPS and fifteen for heart rate, with held chart tails capped at six seconds.
5. **Charts/UI:** inspect short, 57-minute and eight-hour rides, including an eight-hour ride where GPS speed is absent for hours and then resumes (cursor inspection, A–B statistics, whole-ride statistics and live refresh must stay within the interaction target); test peaks/exact readouts, pan/pinch, full screen, repeated reorder/scroll, narrow/desktop layouts and larger text. Refreshes, missing data, settings and tab/modal transitions must not shift surrounding layout.
6. **Removal:** discard from both owners, including offline/restart, then start another ride. Delete a completed ride with an offline Watch copy; reconnect and confirm it stays deleted. Reject deletion of active rides; retain previously saved Apple Health workouts.
7. **Exports:** export FIT and ZIP from iPhone, Android and the browser for matching rides; compare the extracted files and decoded FIT timing, coverage and provenance. Check the share sheet, Save to Files, a share that completes after Power Log restarts and the manual Strava page link. Do not submit a file without explicit user authorization.

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
