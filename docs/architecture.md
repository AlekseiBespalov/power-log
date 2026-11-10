# Architecture

Power Log is a modular monolith: one process, one build and one release per platform. The rules, use cases and
storage are shared Kotlin; the screens are React Native for the phones and the web; the operating system is reached
through small adapters, one per capability and platform. [Decisions](decisions.md) records the product and design
choices behind this shape, and [testing](testing.md) the checks that hold it.

## Layers

| Layer | What it does | Where it lives |
| --- | --- | --- |
| Presentation | Screens, screen state and rendering; no rules, storage or device access | `src/` (phones and web), the native chart views, `targets/watch/views/`, `targets/live-activity/`, the Android notification layout |
| Application | Use cases: orders the work, owns transactions, deadlines, retries and executors, publishes state, maps every failure, and owns the port and repository interfaces | `shared/application` |
| Domain | Rules and values, pure; `kit`: the rider texts, units and formats | `shared/domain`, `shared/kit` |
| Data access | Repositories behind the application's interfaces: SQLite on the phones, IndexedDB on the web | `shared/data` |
| Platform adapters | Only what the OS forces, behind the application's ports; the Watch app's own logic | `modules/power-log/ios/`, `modules/power-log/android/…/platform/`, `shared/runtime/src/jsMain/…/web/`, `targets/watch/` |

**Dependencies point inward:** presentation → application → domain. Data access implements the repository interfaces
(`app.powerlog.application.store`) and the adapters implement the ports (`app.powerlog.application.port`); both
interfaces belong to the application. Failures cross every layer as codes. Rider text comes only from `kit`: the UI
uses its JavaScript build, the ride card renders its projection natively, and the Watch keeps Swift copies checked
against shared fixtures.

The import rules are enforced by Konsist's `BoundariesTest` (`shared/runtime/src/jvmTest/`), the
`no-restricted-imports` blocks of `eslint.config.js`, and the Swift scan of `scripts/check-apple-release-surface.mjs`.

## Slices

Each feature area has one name, used for its folders and packages in every layer, always in this order:

| Slice | Covers |
| --- | --- |
| `connection` | search, connect, polling, reconnect, the remembered bike, the status pill's bike part, the fault line, the preview |
| `recording` | setup, Start, capture, controls, Finish and Discard, interruption, permissions, the ride card, the one recording tab |
| `watch` | the phone side of the Watch protocol, and the Watch app |
| `history` | the History list, the ride page, Delete, cleanup and reclaim, example rides |
| `charts` | number tiles, charts, views, the editor, the cursor, A–B, the native and SVG chart views |
| `exports` | FIT, ride CSV, ZIP, Strava, the export byte functions and sinks |
| `imports` | Open CSV and the imported ride |
| `health` | Health saving, outcomes, readback, Retry Health save, Health work |
| `settings` | Settings, About, Privacy, browser storage |
| `diagnostics` | the diagnostic log, Share diagnostics, the Bluetooth trace |
| `shell` | presentation only: the shell, the refusal and build-mismatch screens, Not found, the chrome |

A slice has a folder or package in a layer only where it has code there. A screen belongs to the slice of its route
and may embed other slices' panels: the Ride screen embeds `connection` and `charts`; the ride page embeds `charts`,
`exports` and `health`; the imported ride embeds `charts` and `exports`.

| Slice | Presentation | Application package; API group | Domain package | Repositories | Ports (adapter folder) |
| --- | --- | --- | --- | --- | --- |
| core and plumbing | `src/app/`, `src/components/`; the client in `modules/power-log/src/` | `api` (group `device`; `cancel`, `apiVersion`), `launch`, `port`, `store`, `Executors` | `core.*`; `kit` | `StoreHost`, `ArchiveWriter`, `RideRepository`, `MeasurementRepository` | `Clock`, `DeviceInfo` (`device/`) |
| `connection` | `src/features/connection/` | `connection`; `connection.*` | `connection` | `ConnectionRepository` | `BleCentral` (`ble/`) |
| `recording` | `src/features/recording/`; the ride card in `targets/live-activity/` and `platform/ridecard/` | `recording`; `recording.*` | `recording` | the core's; `MemoryMeasurementRepository` for the preview | `LocationSource` (`location/`), `PermissionGate` (`permissions/`), `RecordingKeepAlive` (`keepalive/`), `RideCard` (`ridecard/`), `TabLock` (`tablock/`), `TonePlayer` (`tones/`) |
| `watch` | `targets/watch/views/` | `watch`; no group | — | — | `WatchLink` (`watch/`) |
| `history` | `src/features/history/` | `history`; `history.*` | — | `MaintenanceRepository`, `CacheRepository` (summaries) | — |
| `charts` | `src/features/charts/`, `src/components/charts/`, the native chart views | `charts`; `charts.*` | `charts` | `CacheRepository` (tiles), `MemoryMeasurementRepository` | — |
| `exports` | — (its encoders and entries: `modules/power-log/src/exports/`) | `exports`; `exports.*` | `exports` | `MemoryMeasurementRepository` (imported rides) | the export byte functions (`files/`), not a port |
| `imports` | `src/features/imports/` | `imports`; `imports.*` | `imports` | `MemoryMeasurementRepository` | `FilePicker` (`files/`) |
| `health` | — | `health`; `health.*` | `health` | `HealthRepository` | `HealthProvider` (`health/`), `BackgroundScheduler` (`scheduler/`) |
| `settings` | `src/features/settings/` | `settings`; `settings.*` | `settings` | `SettingsRepository` | `StoragePersistence` (`persistence/`) |
| `diagnostics` | `src/features/diagnostics/` | `diagnostics`; `diagnostics.*` | `diagnostics` | `DiagnosticLogRepository` | — |
| `shell` | `src/features/shell/` | — | — | — | — |

### The core

What several slices read lives in the domain's `core` packages and the `kit` module. The core imports no slice.

| Package | Holds |
| --- | --- |
| `core.ids`, `core.time` | ids, `ClockReading`, µs values, time ranges, UTC and seconds parsing |
| `core.metric` | the metric catalog: unit, quantum, range, gaps, holds and tails per metric; the unit enums |
| `core.ride` | the ride, its options, phases, actions and timer, `Platform`, `DeviceCapabilities`, `RideOptionsRules`; the timeline and the Finish boundary |
| `core.controller` (with `.cyc`) | the decoder interface and registry, the CYC decoder, the controller policy, the same-controller rule |
| `core.measurement` | controller sessions, telemetry rows, fixes, Health samples, `Fact` |
| `core.analysis.*`, `core.numeric` | the distance, battery, statistics, summary and Wh/km reducers; fixed-point arithmetic |
| `core.health` | Health providers, writers, outcomes, components, `HealthOutcomeRules` |
| `core.failure`, `core.rules` | `FailureCode`, `RefusalDetail`, `Availability`; `RULES_VERSION` |
| `kit` (`app.powerlog.kit`) | rider texts by slice, units, formats, the ride-card projection, the JavaScript facade |

A few files keep packages from before the slices: `telemetry` (with `.cyc`) holds the CYC frame decoder that
`core.controller.cyc` wraps and the Bluetooth trace; `protocol` its JSON and decimal reading; `analytics` the
`FixedPoint` alias and `Scalar`; the root package `Sha256`. New rules go in the core or a slice.

**Plumbing** belongs to no slice: the routes and components of `src/`, the client, the application's `api`, `launch`,
`port`, `store` and `Executors`, all of `shared/data`, and the runtime.

### Slice dependencies

A slice imports the core, its own packages, and the slices its edges name; the edges are acyclic.

| Slice | May also import |
| --- | --- |
| `recording` | `connection`, `settings` |
| `watch` | `recording`, `health`, `settings` |
| `history` | `health`, `exports` |
| `charts` | `recording`, `connection`, `imports` |
| `health` | `recording` |
| `diagnostics` | `connection` |
| `connection`, `exports`, `imports`, `settings` | — |

`api` and `launch` may import any slice; `port` imports the core and the domain's `recording`, `settings` and
`health`; `store` imports the core and every domain package. A type a second slice needs that no edge allows moves to
the core. A slice reaches another without an edge through an interface its owner publishes:

- **`RideEvents`** (`recording`): ride transitions and committed batches after each commit, and the open ride at
  launch; `health`, `watch` and `charts` subscribe.
- **`ReadingSink`** (`connection`, implemented by `recording`'s `Capture`): a fresh preview, a verified controller,
  every reading.
- **`TraceSink`** (`connection`, implemented by `diagnostics`): the Bluetooth trace's events.
- **`WatchHealthItems`** (`health`, implemented by `watch`): the Health items planned for a Watch ride.
- **The archive's history notices** (`ArchiveWriter.historyCommits`): `history` warms a completed ride's summary and
  cleans up a discarded ride without importing `recording`.

Every slice appends `LogLine`s to `DiagnosticLogRepository` under the vocabulary `diagnostics` owns.

## Shared modules

One Gradle build under `shared/`, configured by the `powerlog.kmp` plugin in `shared/build-logic/`, with the version
pins in `shared/gradle/libs.versions.toml`:

- `domain`: the core and the slices' rules and values.
- `application`: the use cases by slice, the UI API (`PowerLogApi`), the port and repository interfaces, and the Watch
  protocol's Kotlin types. Its test-only `src/fakes` holds a fake of every port; `src/contracts` the repository
  suite, the `BleCentral` scenario runner, the encoder-pages suite and the golden loaders.
- `data`: the repositories. The SQL sits in `sqliteMain`, which only the Android, Apple and JVM targets compile, so
  the web build carries no SQLite library.
- `kit`: rider texts, units and formats, with its JavaScript facade.
- `runtime`: composition, the bridge, the hosts, the clocks, the Watch codec, interop, the crash hook and log mirror,
  the web host and adapters, and the phones' dev tools in `src/debugMain`.

Only `data` and `runtime` target Android; Android uses the JVM builds of the other three. The iPhone links
`PowerLogCore.xcframework`, built from `runtime`, which exports `kit` and the API. The web gets one JavaScript build
of everything it needs; the phones' JavaScript bundle loads kit's own build. Both JavaScript builds, the generated
TypeScript API types, the API hash and the Kotlin dispatch are checked in under `shared/generated/` and written only by
`npm run build:generated`.

## Storage

- **The archive** holds the rides: one logical schema of nine tables, defined once. On the phones it is the SQLite file
  `ARCHIVE_NAME.sqlite3` through `androidx.sqlite` with hand-written prepared statements, one `Sql<Interface>.kt` per
  repository interface; on the web it is the IndexedDB database `ARCHIVE_NAME`, behind the same interfaces, one record
  per commit batch with strict durability. `ARCHIVE_NAME` carries `ARCHIVE_VERSION`
  (`shared/data/…/LayoutVersions.kt`), today `power-log-archive-1`.
- **The cache** (`power-log-cache.sqlite3`; the web's `derived` object store) holds ride summaries and plot tiles keyed
  by ride under one `RULES_VERSION`. It is rebuilt, never migrated, and kept out of backups. A recording ride's tiles
  are built only while a chart of it is on screen.
- Both SQLite files run WAL with `synchronous=FULL` and incremental auto-vacuum; the `launch` log line shows the
  pragmas.
- **Settings** are one JSON row in the archive. **The diagnostic log** is two rotating 4 MiB JSONL files on the phones.
- **One writer.** The application commits through `ArchiveWriter`, one FIFO writer; reads re-check revisions instead
  of holding locks. On the web one tab records at a time, under the web lock; other tabs
  browse History and refresh from commit notices.
- **One repository suite** runs on the JVM, the iPhone simulator, Android 9 and Chromium, so every platform stores
  data the same way.

## Ports

Fourteen ports, owned by the application:

| Slice | Ports |
| --- | --- |
| `connection` | `BleCentral` |
| `recording` | `LocationSource`, `PermissionGate`, `RecordingKeepAlive`, `RideCard`, `TabLock`, `TonePlayer` |
| `watch` | `WatchLink` |
| `imports` | `FilePicker` |
| `health` | `HealthProvider` (Health access, readback, the Apple workout session the Watch also runs, the Health Connect writer), `BackgroundScheduler` |
| `settings` | `StoragePersistence` |
| the core | `Clock`, `DeviceInfo` |

Every port has one shape: non-blocking commands, `Reply<T>` requests and one event sink; no `suspend`, `Flow` or
exception crosses it. Adapters translate and decide nothing. A capability is a present port, and the application
derives `DeviceCapabilities` once from the ports a platform has. Each adapter is named `<Platform><Port>` (`Apple`,
`Android`, `Web`) and sits in a folder named after its port, the same on every platform.

## The bridge

One typed API joins React Native and the shared Kotlin, the same on every platform:

- `call(op, request)` for commands and queries, and `next(stream, request)` for state, with JSON in and out. `next`
  answers once the stream's revision passes the caller's `after`, with the newest value only, at the stream's capped
  rate; a newer request with the same key cancels the older one.
- `exportPage(request)` for export pages, whose numeric columns cross as bytes.
- On the phones, beside them, the export byte functions: the export sinks and sharing.

On the phones these are functions of one Expo module, `PowerLog` (`modules/power-log/`; its iPhone pod and Swift
module are `PowerLogPlatform`). On the web the Kotlin/JS `WebSession` offers the same three in the page. The
TypeScript client (`PowerLogClient`, `modules/power-log/src/client.ts`) checks the API hash first, and a mismatch shows
the build-mismatch screen. The bridge holds no rules, and nothing suspends, streams or throws across a language
boundary. Nothing at the controller rate crosses into the UI while recording; exports read page by page.

A bridge session dispatches by API section; `PowerLogApi` is the one section today.

## Composition, hosts and executors

- **Composition roots** build the runtime once per process: `AppleRuntime`, `AndroidRuntime` and the web host's
  `startWebRuntime`. Each wires the stores, the platform's adapters, the executors and, in development builds, the dev
  tools.
- **Hosts** are the per-platform code the OS starts. They build the runtime and forward OS events to the application
  through `HostFacade`: on the iPhone the app-delegate subscriber (`PowerLogHost`), the Expo module and the ride-card
  intent; on Android the application listener (`PowerLogAndroid`), the Expo module, the foreground service and the
  Health worker; on the web `startWebRuntime`. Launch, the foreground and background, termination, ride-card intents
  and Health work all enter through `HostFacade`.
- **Executors**: the single-thread `ride`, `health` and `maintenance` executors, and a read pool of four on the phones
  with a `scans` view of two. A task never blocks; port events and replies arrive on their executor. Only small work
  runs on the Bluetooth callback queue.

## Diagnostics

- The runtime's crash hook saves the Kotlin stack of a crash on both phones, and the next launch moves it into the
  diagnostic log; an exception inside a task logs its frames.
- Android logs why the previous process ended, once per launch. iPhone exit causes come from the OS: the phone's
  Analytics Data and Xcode's device logs ([testing](testing.md#iphone-exit-causes-and-crash-symbols)). Device builds
  archive their dSYMs, and `scripts/symbolicate-ios.sh` turns crash frames back into code.
- The diagnostic log has one closed vocabulary and field whitelist (`tests/fixtures/contract/telemetry/diagnostic-log.json`).
  Release builds also mirror warnings and errors to the OS log; development builds mirror every line.
- Share diagnostics (phones) carries the diagnostic log with its crash, task-failure and exit lines, and the device
  report. Nothing leaves the phone unless the rider shares it.

## Exports

Kotlin owns the export use case: export sessions and their revision checks, the projections, `ExportCatalog`, and the
one timeline whose per-second records and totals FIT and the ride CSV share. The TypeScript FIT, ride CSV and ZIP
writers in `modules/power-log/src/exports/` only encode bytes. Pages cross the bridge in binary, and the encoded bytes
go straight to the platform's sink: on the phones through the Expo module's byte functions, on the web through the
OPFS sink. An export stops when the rider leaves the app. FIT and ride CSV must match the reference output byte for
byte, because OVRLEY and other tools read them; ZIPs compare entry by entry, since each platform keeps its own
compressor.

## The Watch

The Watch app is SwiftUI and links no Kotlin. It compiles two phone files, the Health adapter's
`WorkoutSession.swift` and `HealthTypes.swift`. Phone and Watch exchange the Watch protocol's JSON messages, each
with a version `v` and a `kind`:

- Watch → phone: `startRequest`, `cancelStart`, `command`, `healthSamples`, `healthOutcome`;
- phone → Watch: `start`, and `ride`, which carries the ride's state, readings, Health quantities and route points;
- every message gets exactly one reply: `ack`, or a `ride` answering `startRequest` and `command`.

The phone's message types are Kotlin, in `app.powerlog.application.watch`; the Watch's Swift copies are hand-written
and checked against the fixtures in `tests/fixtures/contract/watch/`. Health items reach the Watch best effort. Phone
and Watch run the same build.

## Development builds

Phone development builds carry the dev tools in `shared/runtime/src/debugMain`: the demo bike (a fake `BleCentral`),
example rides, the thin-slice run, and crash and kill switches. They answer every permission without a prompt. Release
builds and the web carry none; `apple:release-surface` and the Android release scan check that.

## Repository layout

```
src/                         presentation: app/ (routes), features/<slice>/, components/ (primitives, chrome, charts/)
modules/power-log/           the Expo module PowerLog
  index.ts, src/             the client, transports, kit wrappers, the chart view; testing/ (fakes); exports/ (encoders, sinks)
  ios/host/, ios/charts/     the module, host and UI context; the chart raster views
  ios/<port>/, ios/files/    iPhone adapters, one folder per port; export sinks, sharing and the document picker
  android/…/platform/        host/, charts/, one folder per port, files/; adapter suites in android/src/test/
shared/                      the Gradle build: domain/, application/, data/, kit/, runtime/, generated/, scripts/
targets/watch/               the SwiftUI Watch app: ride/, link/, protocol/, text/, views/, Tests/
targets/live-activity/       the Live Activity widget and its App Intent
plugins/                     Expo config plugins, build integration only
tests/                       TypeScript suites, Playwright (e2e/), the Kotlin/JS browser runner (browser/kotlin/), fixtures/
scripts/                     build, verification, release, symbolication
```

`ios/` and `android/` at the root are generated by Expo prebuild and git-ignored.

## Adding a feature

1. Name the feature once, as a slice: one lowercase word, plural where it names a collection. Declare any edge to
   another slice in the boundary rules.
2. Domain rules go in `app.powerlog.domain.<slice>`. A type a second slice needs moves to the core.
3. Use cases, the `<Slice>Api` interface and its DTOs go in `app.powerlog.application.<slice>`. Operation names are
   `<slice>.<member>`; streams and queries are nouns, commands verbs. Run `npm run build:generated` afterwards.
4. A new stored kind gets a `Mutation`, a method on its repository interface, and its implementation in that
   interface's `Sql<Interface>.kt` and `Idb<Interface>.kt`. A change to the DDL, the IndexedDB layout or a stored
   vocabulary raises `ARCHIVE_VERSION` before the first public release, or adds an upgrade step after it; a change to
   a cached value's rule raises `RULES_VERSION`.
5. A new OS capability gets a port named after the capability and one adapter `<Platform><Port>` per platform, in a
   folder named after the port, with its fake in `shared/application/src/fakes`.
6. Screens and holders go in `src/features/<slice>/`: a holder `<Name>Model` in `<name>-model.ts`, its snapshot
   `<Name>State`. Texts go in kit, in its file for the slice.
7. **A controller or protocol variant:** a decoder in `ControllerDecoders.all` with its transport and trace contract,
   and its frame fixtures.
8. **A telemetry metric:** its `MetricCatalog` entry, the decoder's field, a DDL column and an IndexedDB position
   appended last (which raise `ARCHIVE_VERSION`), its `ExportCatalog` columns and its kit appearance.
9. **An export column:** `ExportCatalog` and the projection that fills it; the goldens it changes are recorded again.
10. **A Health type:** `HealthMetrics`, each Health adapter's type table, `ExportCatalog`'s Health metric table and the
    stored vocabulary, which raises `ARCHIVE_VERSION`.

## Feature modules

A large feature that sits beside the ride workflow rather than inside it, such as an analytics page over many rides,
is a **feature module** instead of a slice. A feature that changes connecting, recording, History, charts or exports
is a slice. A feature that mainly reads what the core records, keeps its own records and derived values, and has its
own screens is a feature module. Nothing outside it imports it except one registration in composition.

| Part | Where |
| --- | --- |
| Rules, use cases, its API section and its store | its own Gradle module, `shared/features/<name>`, packages `app.powerlog.<name>.{domain,application,data}`, built for every target |
| Screens and holders | `src/features/<name>/`, its route in `src/app/` |
| Texts | its own texts and JavaScript facade, on kit's units and formats |
| Registration | one entry in `runtime`'s composition |

- It reads the core through a `FeatureContext`: read-only views of rides, measurements, summaries, the remembered bike
  and settings; `RideEvents` and the history notices; `Executors`, `Clock`, the failure codes, the diagnostic log and
  `BackgroundScheduler`; the core domain. It never writes the core's tables and imports no slice package.
- Its store is its own: a separate SQLite file on the phones and a separate IndexedDB database on the web, named for
  the module and its layout version, opened through the same store host as the archive. Records the rider enters are
  kept and upgraded like the archive; derived values are rebuilt like the cache.
- Its API is its own section, `<name>.*`, generated from the module's own interface; the bridge session dispatches by
  section and the client exposes `client.<name>`. `PowerLogApi` doesn't change.

The bridge session and the client already dispatch by section. The first feature module adds `FeatureContext`, the
registration list, its section's hash in the client's compatibility check, and `cancel` and a session's `close()`
reaching every section.
