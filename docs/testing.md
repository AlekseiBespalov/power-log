# Testing

Use Node 24 and `npm ci`; the scripts pick Homebrew's `node@24` when it is installed. Browser checks need Playwright
Chromium, Apple checks need macOS with full Xcode and the iOS/watchOS SDKs, Android checks need JDK 17 and the Android
SDK, and FIT checks need Garmin's FIT Python SDK from `FIT_PYTHON` or `~/.cache/power-log/fit-venv`. Tests use
disposable stores and synthetic or sanitized inputs.

## Commands

| Command | Purpose |
| --- | --- |
| `sh scripts/verify.sh .` | Everyday tier in the working directory |
| `sh scripts/verify.sh . --full` | Release tier in the working directory |
| `npm run verify` | Release tier on clean `HEAD` in a temporary worktree: the release gate |
| `npm run verify:stress` | Live-ride soak at full workload on a quiet Mac |
| `node scripts/checks.mjs` | The runner: `--full`, `--only`/`--skip`, `--force`, `--jobs n`, `--explain`, `--list` |
| `npm run check` | The `check` group of the everyday tier |
| `npm run test:core` | Release-tier core checks |
| `npm test`, `npm run test:browser`, `npm run test:e2e` | Vitest, browser and Playwright suites |
| `bash scripts/test-swift.sh [--only id[,id]]` | Swift host suites, or chosen ones by id from `scripts/swift-suites.mjs` |
| `npm run test:android [-- --emulator]` | Android JVM suites; with `emulator-5554` also connected suites and crash paths |
| `npm run prepare:android` | Generate the Android project without building; `build:android` already prepares it |
| `npm run build:android -- --preview --bundle` | Preview App Bundle with native symbol tables in `artifacts/builds/android/power-log-preview.aab` |
| `npm run test:crash-paths` | Android interrupted-store checks and browser reload checks |
| `npm run test:lab-apple` | Apple Lab host checks |
| `npm run build:ios-simulator` | Linked iPhone simulator Release build with the final-bundle Lab check |
| `npm run build:core-js` | Regenerates `shared/js` after a shared Kotlin change |
| `sh scripts/format.sh` | Prettier, swift-format and ktfmt (`--check` or `--write`) |

## Tiers

The everyday tier is the development loop: it reruns only the checks whose inputs, toolchain or environment changed
since their last pass. The release tier (`--full`, `npm run verify`) runs every check regardless of earlier passes and
adds simulator, emulator, app-build, generation and soak checks; it requires `emulator-5554`
(`"$ANDROID_HOME/emulator/emulator" -avd PowerLog_API36 -port 5554`), and `POWER_LOG_SKIP_CONNECTED=1` marks a run
as partial. `node scripts/checks.mjs --list [--full]` prints the checks, their tiers and their inputs.

Large-volume variants of Kotlin common and Android unit tests live in `*VolumeTest` classes, which only the release
tier runs; their behaviour stays covered every day by the ordinary classes. `npm run verify:stress` runs the live-ride
soak before every release and during the device pass. Timings are comparable only on an awake Mac on power.

## Qualification evidence

Report host, simulator or emulator, linked-build and physical evidence separately, and name each as such; compilation,
linking and a launch request are not executions or passes. A linked app or a generated eight-hour fixture does not
prove eight hours of unattended recording. Keep run-specific logs, timings and device evidence outside Git.

## Apple Health Lab

`POWER_LOG_LAB=1 npm run build:ios-simulator` builds Debug **Power Log Lab** (`app.powerlog.lab`, with its own
Watch and Live Activity identities). Production Release rejects Lab code. Regenerate `ios/` with
`expo prebuild --clean --platform ios --no-install` when switching between Lab and production.

`sh scripts/test-lab-apple.sh --simulators` uses a booted paired iPhone and Watch selected by
`POWER_LOG_LAB_PHONE_UDID` and `POWER_LOG_LAB_WATCH_UDID`. Authorize the requested Health types on both simulators.
`--no-build` reuses a build only when its source digest matches; `--phone-only` and `--watch-only` are partial runs.
Results, process identities and source digests are retained under `test-results/lab-apple/runtime/<run-id>/`.

The phone cases exercise normal recording and controls, then a real process interruption followed by Finish of the
same retained ride. The Watch cases exercise native-session cleanup, cancellation during Start, Health-off finish,
and `watch-health-save`: an actual Health save with quantity, route and lap readback. These replace the
retired archive-replication and mutation-ledger recovery cases. Neither a simulated provider nor simulator Health
establishes locked-device delivery, real Bluetooth operation or physical Health behavior.

## Phase 6 checklist

Physical qualification of the simplified implementation is pending. Implementation may proceed before these checks
at the rider's request; that authorization does not count as a device pass. Use the iPhone 17 Pro Max, Watch Ultra 3
and S22 Ultra. Record source/build digest, OS, controller firmware, rate, run IDs and timing/memory evidence outside
Git. A skipped or scaled-down row is not a pass.

Before installing an iOS build, run `npm run test:ios-bundle -- /absolute/path/to/PowerLog.app` and
`codesign --verify --deep --strict` on it. Install matching phone/Watch versions, confirm no device is recording,
preserve personal rides and confirm both processes remain alive. Use signed Release builds without a debugger for
ordinary acceptance; use the separate Debug Lab for controlled termination and provider probes.

### Phone recording and Watch companion

1. Record at least 60 minutes with X6 at 8 Hz, Health on, locked iPhone and wrist-down Watch. Include controlled
   5-minute and 20-minute disconnects. The phone remains the app recorder and GPS source; the Watch remains the
   Health writer. Check Watch Start, Pause, Resume, Lap, Finish and Discard, including duplicate taps and lost replies.
2. Check original controller and Watch measurement times, subsecond chart detail, phone GPS route, History and FIT/ZIP.
   Watch buffering is bounded to 60 seconds, 2,048 observations and 512 KiB; older unavailable data may be lost and
   must appear as gaps. Routine phone-to-Watch messages are capped at one per second; controls bypass that pacing.
3. Finish and Discard with Watch unreachable or Health slow. Local completion and export must remain available within
   the stated UI deadline; no saved Health outcome may be invented. Reconnect and verify bounded terminal delivery
   ends the old session where possible. Exercise another Start with an old native session still active.
4. Cancel during Watch authorization, let authorization complete late, and lose the reply to Watch-originated Finish.
   Neither may leave a new unintended native workout running. End orphaned sessions after app termination; never
   transfer Watch Health publication to the phone or restart the local ride automatically.
   Deliver ride A's delayed Finish/Discard reply and observation acknowledgement after ride B starts; B must keep
   recording, its native session must stay active and its buffered observations must remain intact.
5. Repeat with Health off. The app must not create a saved workout; document any sensor samples or Activity credit
   already recorded by the system. Discard leaves saved Health records alone, stops an available native session,
   requests no new workout save and shows no Health-related text or warning.
6. Select 2, 4 and 8 Hz and controller/GPS distance on the phone, then Start from either device, including native
   phone relaunch without React. Verify actual capture rate and frozen distance. After a failed preference save,
   phone Start must expose the failure rather than silently use older settings. Changing preferences during a ride
   must not change its capture rate, distance or detailed graphs.

Record HR latency, backlog, CPU, heat and memory at 8 Hz. Compare a 30-minute locked Watch run with the prior qualified
baseline and report the difference; do not infer performance from the message cap alone.

### Interrupted recording and storage failures

On each platform, terminate after committed samples and reopen. The ride must be marked Interrupted and offer Finish
saved portion and Discard. Finish preserves committed originals and ends at the last saved in-ride measurement or
meaningful event, excluding restart bookkeeping; no Recover into Paused or automatic recording resumption exists.
Include a retained lap/pause after the last sensor sample and a long delay before reopening: event bounds and active
time must be correct in History and FIT/ZIP, with no restart downtime counted as riding time.
Exercise storage-full/write failures without silently erasing data or claiming uncommitted observations were saved.
A browser reload test, emulator SQLite reopen test and actual process kill are distinct kinds of evidence.

On Apple, separately check native-session cleanup after phone and Watch termination. Uncertain prior Health writes
must not be replayed. A process restart may lose buffered samples and may leave Health incomplete; it must not hide
the retained local ride or block its Finish/Discard actions.

### Health publication, collection and permissions

1. Record ordinary phone-only and Watch-assisted Apple rides and an Android Health Connect ride. Check one requested
   workout, expected time bounds, admitted quantities and route. Compare values and units with local originals and
   decoded FIT. No late correction should replace already published samples.
2. Deny permissions, grant them, and exercise only retry actions actually offered for a known failed save. A request
   whose outcome is uncertain must remain uncertain and must never be blindly repeated. A provider timeout must not
   block local History/export. Watch Health is written only by the Watch, even after a phone restart.
3. Add a delayed HR series and run automatic or explicit readback. Supported physiology must appear in graphs, summary
   and exports after refresh, without importing Power Log's own published samples. Check deterministic source choice
   and compatible basal-energy coverage. Provider edits/deletions are not continuously mirrored.
   On Android, refresh a ride with Health saving off: permitted measurements can be imported without provider writes,
   a publication job or an endless worker loop. Check denied reads and an unavailable provider as bounded outcomes.
4. Export during a refresh and delete during an export. The read must retain a coherent revision or fail clearly for
   a retry, never mix revisions. Deleting local History leaves already saved Health records intact.
5. Use the full eight-hour synthetic workload separately from actual capture: 230,400 controller originals at 8 Hz,
   supported Health seconds, missing intervals, original GPS edges and route. Apple route publication is capped at
   30,000 admitted points; Android encoding must fit its 900,000-byte limit. Verify local raw originals remain intact.

Measure save/readback time and peak/post-cleanup memory separately from framework overhead. Phone readback is bounded
at Finish, +15 minutes and +60 minutes while the process remains alive, plus explicit refresh. Do not call provider
probes, generated storage workloads or simulator runs physical acceptance.

### Live Activity and notification controls

1. Start with real controller and HR inputs, then stop new data. Check foreground, locked and suspended states around
   controller +21 seconds and HR +30 seconds. Capture publication, `staleDate`, evaluation and visible-card timestamps.
   No reading may be refreshed merely by delivery of an old sample or by a moving timer.
2. With the phone process cold, invoke Pause/Resume/Finish from an existing Activity or Android notification. Finish
   must open the Save/Discard confirmation; Cancel must not send Stop/Discard. Replay controls from an old ride or phase.
3. Repeat with Live Activities disabled/dismissed and with a slow/unreachable Watch. Verify accessible labels, haptics,
   one admitted action per current intent and an explicit action for every waiting/error state.

### Eight-hour query/export measurements

1. With the iPhone procedure below, measure accepted cold Ride summary, chart first-open and distance build, then the
   same warm requests, also while another ride captures at 8 Hz. Check that finalization warms the summary before the
   first History open. Measure FIT peak resident/footprint and memory after export closes. Record page counts, peaks and
   five-run medians. Latency and memory acceptance stays open until the user sets thresholds from this device pass.
2. On the S22 install the current Android Lab APK, clear only its disposable long-ride store and run
   `adb -s <s22-serial> shell am instrument -w -e class app.powerlog.bridge.LongRideTest#eightHoursRetainsOriginalsAndBoundsInteractiveReads -e powerlogVolume full app.powerlog.bridge.test/androidx.test.runner.AndroidJUnitRunner`.
   Retain the `PowerLogPlotComparison` and `PowerLogBenchmark` logcat lines and test output, and measure cold/warm
   detail, first History open after finalization, complete power plot, full distance plot, cursor, A–B and whole-ride
   statistics with the current build. Pass: geometry, counts, endpoints and every assertion pass, and no cold summary
   work is deferred onto the first History open. Emulator results do not qualify the S22.

#### iPhone eight-hour fixture and collection procedure

Use a freshly installed, signed development app with disposable data on the iPhone 17 Pro Max; never overwrite a
personal recording container. Run the scripts in `scripts/device-eight-hour/` from the checkout and keep everything under
ignored `test-results/device-eight-hour/`. The fixture is a generated phone-recorded eight-hour 8 Hz ride with one GPS and
HR observation per second, a pause, an invalid span and missing speed; it exercises local reads, not capture or Health
publication.

1. Before any read warms a cache, generate it with
   `sh scripts/device-eight-hour/generate.sh --full test-results/device-eight-hour/original/PowerLog`.
2. Before **each cold operation**, `python3 scripts/device-eight-hour/stage.py` stages a copy without derived caches,
   verifies every other table and prints the ride ID, counts and original hash; record them and keep the original for
   every repeat. This is an application-cache cold run; do not purge OS disk caches.
3. Build a signed Debug development app for the iPhone in Xcode with Metro as in the README. With
   `POWER_LOG_DEVICE_APP` and `POWER_LOG_DEVICE_ID` set (and `POWER_LOG_DEVICE_BUNDLE` when the bundle ID is not
   `app.powerlog`), run `sh scripts/device-eight-hour/device.sh install`. Terminate Power Log in Xcode before each
   transfer, run `device.sh push` and `device.sh pull`, and check the copy with
   `python3 scripts/device-eight-hour/stage.py test-results/device-eight-hour/copied/PowerLog` before launching. Start
   the app on Live, confirm its process stays alive and keep History closed until the measurement.
4. Collect accepted bridge timings in the device's React Native DevTools console: after each restart put the ride ID
   into `console.js` and paste it. Cold summary: restore the staging store, restart, run `await measureSummary()`.
   Chart first-open: restore and restart, prebuild distance outside the timer with `distance.lldb`, read no plot or
   statistics, run `await measureChart()`. A timing ends when the result is accepted and excludes rendering; a retry,
   stale identity, error or cancellation is a failed request, not a sample. Save console output with
   build/configuration, OS, fixture hash, run and operation, and retain screen recordings of visible first-open and
   gestures separately.
5. Collect distance build, page counts and memory in a **separate diagnostic repeat** with the same staged inputs. In
   Xcode, pause in a Swift bridge frame and `command source` `diagnostics.lldb` once, `reset.lldb` before each
   operation, `collect.lldb` after it and `stop.lldb` at the end. For a **cold distance build** restore and restart, put
   the ride ID into `distance.lldb` and source it; repeat for warm. Summary pages hold at most 256 entries and distance
   pages at most 128; retain page, row and byte totals, scan counts and idle, sampled-peak (100 ms resolution) and
   post-request `phys_footprint`. Summary page callbacks count both passes; projection diagnostics count scans and
   adapter bytes, not process memory. Observe FIT separately through sharing and after the exporter closes. Keep this
   instrumented Debug run separate from uninstrumented bridge timings and Release UI acceptance.
6. Run five cold/warm repeats per operation, then repeat bridge reads while a separate phone-recorded ride captures at
   8 Hz. Retain raw values and five-run medians. Copy the store back after each read and compare the fixture's original
   rows, memberships and metadata with the immutable input, isolating the live ride for capture runs. On a real
   completed ride check that finalization warmed the summary before History opens.

Pass: exact summary/inspection/export values, unchanged fixture originals, bounded pages, accepted current request
identities and cancellation drain. Record device summary/chart/distance measurements beside the quiet-Mac references
for the same cold operations, **8.1 s / 5.1 s / 1.4 s**, with idle/peak/post-cleanup footprint and
build/instrumentation context; they are host references, not phone thresholds, so derive no limits from them. The
setup and collection commands are not a device pass.

### Capture, charts and sharing

Use the real X6/X12 with both phones and Watch-assisted Apple rides at 2/4/8 Hz. Compare matched foreground and at least
30-minute locked runs; repeat radio outages, manual disconnect/reconnect, app switches and History inspection during
capture. Check continuous crank counters, original acquisition times, no overlapping controller requests and honest
gaps. Compare speed with Controller Ride Control in both unit settings. Phone GPS stays enabled on Watch rides when
chosen; distance source freezes at Start and does not change when later preferences change.

Exercise short, 57-minute and eight-hour rides, including hours without GPS speed followed by resumed fixes. Preserve
exact cursor values, A–B statistics, rapid power/current/speed changes, peaks, pan/pinch, full screen, chart customization,
metric ordering, larger text, narrow/desktop layouts and platform Back gestures. Settings, missing data, refreshes and
modal transitions must not unexpectedly move surrounding UI. Check graphs while another ride captures at 8 Hz.

Export FIT and optional full raw ZIP on iPhone, Android and web, compare decoded timing/coverage/provenance, and check
share sheet/Save to Files and the manual Strava page link. Do not upload a file without explicit rider action. Local
History must retain multiple independent rides; Discard affects only its selected ride. Include Android permission
prompts, screen-off GPS, notification controls and vendor background limits. An actual unattended long ride remains
separate evidence from generated eight-hour fixtures.

| Measurement | Acceptance target, not a measured guarantee |
| --- | --- |
| Available-link delivery | ≥95% of requested rate per 60-second window; background within 5% of matched foreground |
| Gaps | Record and explain gaps; controlled outages and bounded-buffer losses must not become zeros or fabricated edges |
| Background CPU / writes | Aim ≤3 CPU seconds/minute at 8 Hz and <100 KB/s sustained; report spikes, bytes/sample, WAL/checkpoints and DB growth |
| Admission | BLE enqueue p95 <5 ms; report oldest buffered sample and worst commit delay |
| Hidden UI | No periodic chart/catalog/display work after cancellation drains; no growing event backlog |
| Interaction | Real gesture-to-presentation p95 <50 ms; no stalls >100 ms |
| Storage | Committed originals remain readable, atomic errors remain visible, and memory/queue cost remains bounded |

Apple references: [background Bluetooth execution](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html),
[CPU profiling](https://developer.apple.com/documentation/xcode/addressing-cpu-bottlenecks).
