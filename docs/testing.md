# Testing

Use Node 24 and `npm ci`; the scripts pick Homebrew's `node@24` when it is installed. Browser checks need Playwright
Chromium, Apple checks need macOS with full Xcode and the iOS and watchOS SDKs, Android checks need JDK 17 and the
Android SDK, and FIT checks need Garmin's FIT Python SDK from `FIT_PYTHON` or `~/.cache/power-log/fit-venv`. The shared
Gradle build needs `JAVA_HOME` and `ANDROID_HOME`, which the scripts default on macOS. Tests use disposable stores and
synthetic or sanitized inputs.

## Commands

| Command | Purpose |
| --- | --- |
| `sh scripts/verify.sh .` | The everyday tier in the working directory |
| `sh scripts/verify.sh . --full` | The release tier in the working directory |
| `npm run verify` | The release tier on a clean copy of `HEAD` in a temporary worktree: the release gate |
| `node scripts/checks.mjs` | The runner: `--full`, `--only`/`--skip`, `--force`, `--jobs n`, `--explain`, `--list` |
| `npm test`, `npm run test:browser`, `npm run test:e2e` | Vitest, the browser suites and Playwright |
| `bash scripts/test-swift.sh [--only id[,id]]` | The Swift host suites, or chosen ones by id from `scripts/swift-suites.mjs` |
| `npm run test:android` | The Android JVM and Robolectric suites |
| `npm run build:generated` | Rebuilds `shared/generated` after a change to a shared module's `commonMain` or `jsMain`, or to the API generator |
| `npm run build:ios-device [-- --development]` | An iPhone device build, with its dSYMs archived for symbolication |
| `sh scripts/format.sh` | Prettier, swift-format and ktfmt (`--check` or `--write`); `--kotlin` for ktfmt alone |

## Tiers

`scripts/checks.mjs` runs the checks of `scripts/check-tasks.mjs`. Each check names its inputs, and a check reruns only
when its inputs, toolchain or environment changed since its last pass. `node scripts/checks.mjs --list [--full]`
prints every check with its tier and inputs, and `test-results/gates/timing.tsv` keeps their timings.

- **Everyday** (`sh scripts/verify.sh .`): types, lint and formatting;
  the generated outputs' fingerprint; the Konsist boundary rules; the Kotlin JVM and Node tests of every module; the
  Vitest, Node and browser suites; the Swift host suites; the Android Robolectric suites and the release compilation;
  the Apple release-surface scan; the encoder goldens.
- **Release** (`--full`, `npm run verify`): every check regardless of earlier passes, plus
  the Kotlin macOS and iPhone-simulator tests, the Kotlin/JS browser suites (`core:browser`), the ride goldens, the
  Chrome long-ride export (`js:browser-long`), Playwright, the release APK with its release-surface scan, the iPhone
  simulator Release build, the Watch typecheck and Release links, the Kotlin metadata pins on the Android release
  classpath (`android:kotlin-metadata`), the dependency audit, a cold build of every generated output, and the
  `*VolumeTest` classes, the large-volume variants of ordinary tests.
- **Demand** (`--only <id>`): checks that need a device the tiers don't start:
  - `thin-slice:phones`: development builds on the booted iPhone simulator (or `POWER_LOG_SLICE_SIMULATOR`) and on
    `ANDROID_SERIAL` (default `emulator-5554`) launched with the demo bike and the slice run; it passes when each prints
    `[power-log] slice ok` within 3 minutes. `node scripts/thin-slice-phones.mjs --ios` or `--android` runs one phone.
  - `android:sqlite-api28`: the repository suite on Android 9's SQLite, on `emulator-5556`
    (`"$ANDROID_HOME/emulator/emulator" -avd PowerLog_API28 -port 5556`, image
    `system-images;android-28;google_apis;arm64-v8a`).

A check that fails alone under load (a Playwright or browser timeout while other builds run) is rerun alone before it
counts as a failure.

## Tests per layer

| Layer | Where | Runs on |
| --- | --- | --- |
| Domain, kit | their `commonTest` | JVM, Node (everyday); macOS, iPhone simulator (release) |
| Application | `shared/application/src/jvmTest`: the scenario tests over the real `SqlArchive` in memory with the port fakes and virtual time, the crash-path cases, `EncoderPagesTest`, `RideGoldenPagesTest` | JVM (everyday; `RideGoldenPagesTest` release) |
| Data | the repository suite | JVM (everyday); iPhone simulator, Chromium (release); Android 9 (demand) |
| Runtime | the bridge, host and client cases, `JsonWatchCodec` against the Watch protocol fixtures, the example-ride generator, the web adapters' suites, the crash hook | JVM, Node (everyday); macOS, iPhone simulator, Chromium (release) |
| Adapters | the `BleCentral` scenarios, the native suites, the smoke tests | Swift host and simulators, Robolectric and emulators, Chromium |
| Client; screen-state holders | Vitest over `fakeTransport()`; Vitest over a client on `fakeTransport()` with a fake UI platform | Node (everyday) |
| End to end | Playwright on the real web runtime with a fake Web Bluetooth; the thin slice | release, demand |

Shared test code lives in two test-only directories of `application`, never compiled into a main source set:
`src/fakes/kotlin`, a fake of every port with one unit test each, and `src/contracts/kotlin`, the repository suite,
the `BleCentral` scenario runner, the encoder-pages suite, the two-tab harness and the golden loaders. The client's
fake transport is `modules/power-log/src/testing/fake-transport.ts`.

Kotlin common tests are discovered by `shared/scripts/discover-common-tests.mjs`, and `check-test-results.mjs` fails a
suite that didn't run. The scanner can't read a Kotlin raw string that opens or closes on a quote; write such strings
with escaped quotes.

## Contract suites

- **The repository suite** runs the same cases over `SqlArchive` on the JVM, the iPhone simulator and Android 9, and
  over `IdbArchive` in Chromium.
- **The Kotlin/JS browser runner.** `data`'s and `runtime`'s `jsTest` each export one `runBrowserSuite` entry; the
  Vitest specs in `tests/browser/kotlin/` load each test executable into Playwright's Chromium, call it and fail on
  every failed case. `js:browser-long` loads `runtime`'s the same way for the long-ride export.
- **`BleCentral` scenarios** are data in `tests/fixtures/contract/ports/ble-central.json`, replayed by every
  implementation: the fake on the JVM, `AndroidBleCentral` under Robolectric, `WebBleCentral` in Chromium and
  `AppleBleCentral` in the Swift `ble-central` suite through its Core Bluetooth seam. Each step drives an OS occurrence
  through the harness's seam and expects the next event or OS call.
- **Every other port** is tested natively per platform against its OS seam, and its Kotlin fake has one unit test with
  the same cases.
- **API fixtures** (`tests/fixtures/contract/api/`), one per wire construct, are decoded and re-encoded through
  `WireCodec` byte for byte on the JVM and as JSON values on the iPhone and JavaScript; Vitest feeds them through the
  client.
- **Watch protocol fixtures** (`tests/fixtures/contract/watch/`) run through `JsonWatchCodec` and the Swift
  `watch-protocol` suite.

## Goldens

The goldens hold the reference output every export must reproduce: the starting build `0094792` (0.7.0 with the
iPhone's planned reconnect).

- **Encoder goldens** (`tests/fixtures/export/**`, everyday): each fixture's export pages, dumped from the reference
  code, replay through the export adapter into the TypeScript encoders, which must reproduce the fixture bytes. No
  store is involved, so edge inputs the archive can't hold still prove the encoders. `EncoderPagesTest` then loads
  each fixture with a `ride.json` into `SqlArchive`, runs every projection and compares the pages with the dumped ones;
  its browser twin does the same over `IdbArchive`. `goldens:imports` replays the four ZIP fixtures' `telemetry.csv`
  through Open CSV and the imported-CSV encoder, which must give back the file byte for byte.
- **Ride goldens** (release): rides the reference build recorded on each platform, exported as ZIP, FIT and ride CSV,
  each with a `sidecar.json` holding what the ZIP doesn't carry. `goldens:ride-pages` loads each into `SqlArchive` on a
  file and writes its pages to `shared/build/ride-golden-pages/`; `goldens:rides` replays them through the encoders and
  compares by the rules below.
- **The long-ride golden:** the 8-hour 8 Hz fixture (one GPS and heart-rate observation per second, a pause, an invalid
  span, missing speed), exported the same way. It runs with `goldens:rides`; nothing in the repository regenerates it,
  so the stored copy is the only one.
- **Storage.** Synthetic, harness and simulator goldens are in `tests/fixtures/rides/<platform>/<name>/`. Real rides
  hold the owner's routes, and the long ride and the encoder cases over 1 MiB are too large, so they live in
  `~/.cache/power-log/goldens/`, listed with each file's SHA-256 in `tests/fixtures/rides/manifest.json`; a changed or
  missing one fails. Goldens outside Git run when their directory exists, and must before a release. Only the owner
  replaces a golden; a new export column means recording the goldens it changes again.

**Comparison rules**

1. FIT and ride CSV: byte for byte.
2. ZIP: the same entry count and central-directory order; per index the same name, DOS time and date, flags, version
   needed, compression method, extra-field ids and lengths, and identical uncompressed contents. Compressed bytes,
   sizes and CRCs aren't compared, since each platform keeps its compressor.
3. Times the reference printed from floating-point clocks compare as µs: CSV `elapsedSeconds` cells and
   `datapackage.json`'s `elapsedSeconds`, `ownerTiming.elapsedSeconds` and `ownerTiming.timerSeconds` within 1 µs; the
   iPhone's `health.csv` timestamps as parsed UTC µs; the descriptor's top-level `timerSeconds` within 1 µs per active
   interval boundary.
4. Cells only the reference wrote compare as empty: `health.csv`'s `logicalKey`, `targetEventId`, `originRevision`,
   `valueBits`, `evidence`; the descriptor's `health.export` compares as null.
5. Android goldens drop the `health.csv` lines whose sample falls outside the ride.
6. A duplicate timer `stop` just before a phone ride's final lap compares as absent.
7. The web's encoder pages leave out Health, which `IdbArchive` doesn't store.

Every other cell and byte compares exactly. Each golden's `datapackage.json` `created` sets the export instant, so ZIP
entry times match.

## The thin slice

The thin slice runs connect → indoor 8 Hz Start → at least 60 s of frames → Finish → the ride in History → its rider
power chart from stored facts, through every real layer:

- on Chrome, `tests/e2e/thin-slice.spec.ts` over `npm run web` with a fake `navigator.bluetooth`, in `js:e2e`;
- on the phones, `thin-slice:phones`: development builds with the demo bike, whose dev tools answer every permission
  without a prompt, so the run is unattended.

## Qualification evidence

Report host, simulator or emulator, linked-build and physical evidence separately, and name each as such; compiling,
linking and a launch request are not executions or passes. A linked app or a generated eight-hour fixture does not prove
eight hours of unattended recording. Keep run-specific logs, timings and device evidence outside Git.

## Installing on the owner's devices

- **Never uninstall Power Log to make room for a build:** that deletes the rides. A build installs over the app.
- **iPhone:** any build the team signs. Before installing, run `npm run test:ios-bundle -- /absolute/path/to/PowerLog.app`
  and `codesign --verify --deep --strict` on it. Install matching phone and Watch builds.
- **Android:** only a release build signed with the release key (`POWER_LOG_ANDROID_*`) and a version code no lower than
  the installed app's; development builds, signed with the debug key, run on the emulator.
- After installing, confirm no device is recording, that the process stays alive, and inspect the app where possible;
  a successful launch request is not proof of a successful start.

## Simulator smoke tests

Ports whose OS feature has no harness are checked by hand on the iPhone simulator with a development build:

| Port | Check |
| --- | --- |
| `PermissionGate` | A fresh install: Find bike asks for Bluetooth, a GPS ride's Start asks for location, Start with Save to Health asks for Health; a denial shows its reason and the Settings link |
| `RideCard` | `tests/native-live-activity/`: the Live Activity shows the timer, power and heart rate; Pause and Resume act from the card; Finish opens its sheet |
| `RecordingKeepAlive` | A ride keeps capturing with the app in the background, and the card stays until Finish or Discard |
| `WatchLink` | With a paired Watch simulator, a Watch Start creates the phone's ride first, and Pause, Resume and Finish reach the phone |
| `FilePicker` | Open CSV picks a ride-data ZIP's `telemetry.csv` from Files and shows the imported ride |

On the web, Playwright covers `TabLock` with two pages and the picker with `setInputFiles`.

## Device checks

Run on the finished build, with the iPhone 17 Pro Max, the Watch Ultra 3, the S22 Ultra and Chrome:

| Check | Where | Pass |
| --- | --- | --- |
| Runtime | iPhone, S22, Chrome | `apiVersion` matches; a reload closes and reopens a session without stuck requests; a development build's `crashAfterSeconds` crash appears symbolicated after the next launch: on the iPhone through `scripts/symbolicate-ios.sh`, on the Android emulator with readable frames |
| Pragmas | iPhone, S22 | a release build's `launch` line shows `synchronous` 2, `journalMode` `wal`, `autoVacuum` 2 and `walAutocheckpoint` 1000 for both files |
| Bluetooth relaunch | iPhone | a development build launched with `killAfterSeconds` dies while locked during a ride (never from the app switcher, which stops iOS from relaunching); within 3 minutes Core Bluetooth relaunches it (a `launch` line with `bluetoothRelaunch`), the ride is Interrupted with Finish and Discard, and the remembered bike reconnects |
| GPS ride | iPhone, S22, Chrome | a 10-minute outdoor 8 Hz ride with GPS: ≥ 95 % delivered in every linked 60 s window, a fix stored in every 10 s window |
| Locked ride | iPhone, S22 | a 10-minute locked 8 Hz ride after 2 minutes on the Ride screen with its charts, using the ride card's Pause and Resume: ≥ 95 % delivered in every linked 60 s window by the build's `captureSummary` lines |
| Health | iPhone, S22 | a workout written and read back |
| Watch | iPhone with the Watch | a Watch-assisted Start and Finish |
| X6 link limit | iPhone with the X6 on its stand | a 10-minute ride: after the bike's third own drop, every link change is planned at about 93 s and costs under 1.5 s of readings |
| Acceptance rides | iPhone with the Watch, S22, Chrome | the checklist below holds; at 8 Hz, with at least 60 locked minutes after 10 on screen (Chrome: visible): ≥ 95 % delivered in every linked 60 s window, the background windows' mean within 5 % of the on-screen windows', every reading saved within 1.5 s and memory flat (phones; Chrome is held to delivery only); CPU reported only |

Development builds take their switches as launch arguments on the iPhone (`-PowerLogDevBike YES`,
`-PowerLogDevCrashAfterSeconds 30`) and from `files/dev-switches.json` on Android.

**Measuring** reads each build's own log and store: delivery per 60 s window of the stored readings' elapsed time,
windows with a link loss excluded, background against on-screen segments from `foreground` lines; save latency from
`captureSummary.commitMaxMs`; memory from `footprintBytes` over the last 30 minutes, spread under 5 MB; CPU from
`processCpuMs` per segment, for the record. The web has no log, so Chrome is held to delivery only.

### The device checklist

One row per feature that only a device can prove; tests hold every other feature.

| Feature | Device | Action and pass |
| --- | --- | --- |
| Remembered bike | iPhone, S22 | iPhone: the relaunch check, and a cold launch the rider starts, reconnect the bike, and the Ride screen's charts fill from the preview; away from the bike, a launch shows Bike unavailable once and forgets the bike. S22: the first time a process opens its UI the remembered bike reconnects; a WorkManager run doesn't; away from the bike, one failure, then the bike forgotten |
| Android permissions | `emulator-5554` (API 36) and `emulator-5556` (API 28), fresh development installs | Find bike shows the Nearby devices prompt (API 28: location) before any search; Start shows the notification prompt (API 36), and the location prompt for a GPS ride |
| Recording out of view | iPhone, S22 | the acceptance ride's locked minutes meet the delivery bar at 8 Hz |
| Cursor and zoom | iPhone, S22 | scrub, pinch and pan a long ride's whole-ride chart: it follows the finger |
| Web hidden time | Chrome | hide the tab for 2 minutes during a ride: that time is a gap, and recording goes on once the tab is visible |
| Ride card | iPhone (Live Activity), S22 (notification) | during a locked ride the card shows the timer, power and heart rate, at most every 15 s; Pause and Resume act without opening the app; Finish opens its sheet; a control from an earlier ride or phase does nothing |
| Watch-assisted Start | iPhone with the Watch | Start from the phone and from the Watch; the phone creates the ride first; a Start expires after 15 s; Cancel Start on the Watch leaves no ride |
| Watch screens | Watch | the controls and readings pages; a command needs the phone's reply within 5 s; with the phone out of reach, Finish ends on the Watch |
| Phone–Watch messages | iPhone with the Watch | the Watch's readings reach the ride with their timestamps; phone state every 2 s, controls at once |
| Watch Health saving | Watch | with Health on, the Watch alone writes one workout, never replaced |
| Session after a crash | iPhone, Watch | after a kill during a Health session, the next launch only ends the leftover session; no ride is made |
| Health saving | iPhone (live), S22 (after Finish) | one workout with laps, pauses, route, power, cadence, GPS speed and distance, each published once |
| Health outcomes | iPhone, S22, Watch | History rows show one label; the ride page shows the outcome and each unsaved part |
| Readback | iPhone, S22 | readback at Finish, +15 and +60 min and on Refresh, with Health saving on and off; Power Log's own samples are never read back |
| Browser storage | Chrome | Settings says whether the browser may clear rides |
| One recording tab | Chrome | one tab records; a second tab browses History without the ride controls |

## The stress test

A one-off comparison of a candidate build with a baseline release, such as a new build against
0.7.0. Its tools, a script that reads the two builds' logs and stores, live outside the repository and go once its
report is written.

- **The rule.** Two runs of the baseline bracket the candidate: A1, B, A2, on one day and device, on release builds,
  10 minutes apart. Each measure is a median over its 30 s windows, and passes when B's median is no worse than the
  worse of A1's and A2's: no higher for CPU or time, no lower for readings delivered.
- **The stand.** The phone, locked, by a window next to the X6 on its stand; windows with a link change are excluded.
  The phone at least 90 % charged and unplugged, Low Power Mode off, each run starting at a nominal thermal state; a run
  disturbed by a call or handling repeats the day's runs. Both builds outdoor, phone GPS on, distance Auto, with Save to
  Health, Apple Watch (worn) and Live Activities on. On the iPhone the script reads each build's app container
  (`xcrun devicectl device copy from --domain-type appDataContainer`); on the S22, whose release builds `run-as` can't
  read, each run's Share diagnostics ZIP and the stand rides' ZIP exports. Afterwards, delete the stand rides in each
  build's History and their workouts in Health, since Power Log never deletes provider records.

| Segment | What runs | Length |
| --- | --- | --- |
| 8 Hz on screen | Start at 8 Hz; the Ride screen on | 5 minutes |
| 8 Hz locked | the same ride, the phone locked | 30 minutes, then Finish |
| 2 Hz locked | Start at 2 Hz; the phone locked | 15 minutes, then Finish |

- **Measures:** process CPU every 30 s (`captureSummary.processCpuMs`), link losses from the diagnostic log and the
  stored readings, and the footprint from `captureSummary`. Absolute bars in the 8 Hz segments: ≥ 95 % delivered in
  every linked 60 s window, every reading saved within 1.5 s, the 30 locked minutes' footprint spread under 5 MB, each
  link loss reconnected within 6 s.
- **Launch and exports**, on each phone by the same rule: three cold launches to the Ride screen's first frame
  (`adb shell am start -W -S` on the S22, a screen recording on the iPhone), and three FIT and three ZIP exports of the
  8 Hz ride, tap to share sheet, timed from a screen recording. In Chrome, without the bike: three cold loads of each
  build to the Ride screen's first frame, and three FIT and three ZIP exports of the first two hours of the example
  long ride, tap to download.
- **A bar that fails** gets one tuning round by the owner of the failing measure, using the test's evidence (CPU per
  segment, delivery and save latency per window); then A1, B and A2 run again. A second failure goes to the owner with
  the evidence.

Time Profiler can't record a locked, unplugged iPhone, so the test reads the app's own 30 s summaries.

## iPhone exit causes and crash symbols

The iPhone logs no exit cause of its own. The OS keeps them:

- **On the phone:** Settings → Privacy & Security → Analytics & Improvements → Analytics Data lists the reports. A
  `PowerLog-<date>.ips` is a crash (its exception type and the termination reason, such as `0x8badf00d` for a watchdog
  kill); `JetsamEvent-<date>.ips` lists the processes ended for memory, with Power Log's entry and its `reason`;
  `PowerLog.cpu_resource-<date>.ips` and `PowerLog.wakeups_resource-<date>.ips` report CPU and wake-up limits. Share a
  report from there with AirDrop.
- **On the Mac:** Xcode's Devices and Simulators window (Window → Devices and Simulators → the phone → Open Recent
  Logs) shows the same reports, symbolicated when Xcode has the build's dSYMs. Console.app streams the phone's OS log
  while it is connected: filter on the `PowerLog` process for the mirrored warnings and errors, and on `bluetoothd` for
  the link's own events.
- **A Kotlin crash** is kept by the crash hook and appears in the diagnostic log after the next launch as a `crash` line
  with its image UUID, load address and frames.

**Symbols.** `npm run build:ios-device` copies every product's dSYM (the app, the Watch app, the Live Activity extension)
to `~/.cache/power-log/releases/<version>-<build>-<sha>[-dirty]-<flavor>/ios/dSYMs/`, with `uuids.txt`. The Kotlin
framework is linked into the app, so the app's dSYM covers it. `scripts/symbolicate-ios.sh <diagnostics.zip |
connection-log.jsonl> ~/.cache/power-log/releases` finds the dSYM whose UUID matches each `crash` and `unexpected` line
and prints its frames through `xcrun atos`. The archive folder can move: binaries resolve beside each `uuids.txt`.

**Android** logs why the previous process ended as an `exit` line at every launch (`reason`, `status`, `importance`,
`atUtcUs`); `adb shell dumpsys activity exit-info app.powerlog.mobile` shows the OS's own record on a debuggable or
emulator build. Kotlin stacks need no mapping. Android releases keep `native-debug-symbols.zip` and the JavaScript
source map in `~/.cache/power-log/releases/<version>/android/`.
