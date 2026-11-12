# Decisions

Product and design choices that the code alone does not mark as deliberate. [Architecture](architecture.md)
describes the structure they shape.

## Requirements

The design meets four requirements, all of them:

1. **The same features on every platform**, with FIT and ride CSV files byte-identical to the reference output
   (OVRLEY and other tools read them) and no metric renames.
2. **Battery use and speed at least as good as 0.7.0's.**
3. **Simplicity:** the fewest moving parts that deliver 1 and 2.
4. **Ease of support for one developer:** few languages, toolchains, build steps and dependencies, and failures that
   are easy to trace.

A design that loses a feature or falls below that performance is out, however simple; one that keeps them with needless
complexity is wrong too.

## Scope and platforms

- **Minimum iOS 26, watchOS 10, Android 9.** Every supported iPhone can run a HealthKit workout session; watchOS 11
  effort scores are read only where the Watch provides them.
- **The phone records every ride.** The Watch is a phone-required sensor, display and control companion with no ride
  archive of its own. For a Watch-assisted ride it runs the HealthKit session and is the only Health writer; otherwise
  the iPhone writes Health. Android uses Health Connect; the web has no Health provider.
- **The iPhone is the reference.** Android and the web match its behaviour, controls, gestures, animations, haptics and
  resets, except for the differences listed here. The phones share one TypeScript layout and branch on native versus
  web, never iOS versus Android; the web changes only responsive placement and interaction handles.
- **The web is foreground-only.** It records Bluetooth and GPS while the page is visible, needs Web Bluetooth for new
  recordings and keeps rides unencrypted in IndexedDB. Browsers without Bluetooth keep History, import and export.
- **Android physiology is Health Connect backfill,** read after the ride, never live.
- **Recording runs without the UI.** Capture and the ride lifecycle run in the shared Kotlin, kept alive by Android's
  foreground service and the iPhone's Bluetooth background mode; JavaScript never drives recording or ride-card
  deadlines.
- **No dead ends.** A ride with no way out is a critical defect: every waiting state offers an explicit rider action
  instead of waiting forever or completing on its own.
- **One app identity,** `app.powerlog.mobile`, in a release and a development flavour. A build for the owner's phones
  installs over the app and keeps its rides: on Android only a release build signed with the release key, since an
  update must carry the installed app's signature. Development builds, signed with the debug key, run on the emulator.

## Architecture

- **Shared Kotlin owns the rules, the use cases and the storage** on every platform. TypeScript owns the screens and the
  FIT, CSV and ZIP byte encoders. Platform code only translates what the OS forces. The Watch stays SwiftUI and links
  no Kotlin: Kotlin's support for the 64-bit Watch target is its lowest tier.
- **A modular monolith.** The layers are Gradle modules, the slices packages inside them. A large feature beside the
  ride workflow, starting with an analytics page, is its own feature module with its own store, API section and
  screens, plugged in at one point in composition.
- **One typed bridge:** `call`, `next`, `exportPage` and, on the phones, the export byte functions, with an API hash
  check and one plain bridge session per JavaScript runtime. Export bytes never cross Kotlin.
- **Kotlin versions are pinned** to what React Native's Android Kotlin can read.
- **Repeated commands are stopped where they start:** busy controls in the app, rotated tokens on the ride card, one
  send per Watch command. Nothing answers from a cache, and commands carry no caller-made ids.

## Storage

- **Phones:** the system SQLite through `androidx.sqlite`, with hand-written prepared statements, one file per
  repository interface; WAL with `synchronous=FULL`, proven by the `launch` line's pragmas on the device. Android 9's
  SQLite 3.22 rules out STRICT tables, UPSERT and RETURNING, so no extra library. No CHECKs or foreign keys: the
  domain types and the row mappers own vocabularies, ranges and timing.
- **Web:** IndexedDB with one record per commit batch and strict durability. One tab holds the recording lease while
  it records; a leftover ride is interrupted only under the lease; other tabs browse History and refresh from commit
  notices; each tab runs its own bike connection.
- **The archive's name carries its layout version** (`power-log-archive-1`). Until the first public release, every
  change of the archive's layout or of a stored vocabulary raises `ARCHIVE_VERSION`, so a build never meets another
  build's archive: it starts its own, and nothing is refused, migrated or deleted. Reinstalling or clearing site data
  removes older archives; export first. From the first public release, each format change ships an upgrade step.
- **An unreadable archive is refused** with one message and its way out: reinstall on the phones, clear site data on the
  web. A damaged file (`SQLITE_CORRUPT`) is refused as a whole. A row that doesn't parse fails only the call that read
  it, except the open ride's row at launch, which refuses the archive. Nothing is erased or hidden.
- **Slow or failed storage:** a launch that takes more than 30 s offers Retry. A store that can't open, or can't
  interrupt its open ride at launch, for lack of space or an IO error, says to free up storage and open Power Log
  again.
- **The cache** holds ride summaries and chart tiles only, under one rules version, with `synchronous=FULL`, out of
  backups. A distance or consumption tile keeps its reducer's state, so a cursor or range readout resumes from the
  nearest tile instead of the ride start. A whole-ride plot reads cached tiles; a zoomed range reads facts.
- **Settings** are one row in the archive, with Reset settings; no revision files and no "recovered" notice.
- **Disk space:** stores start with incremental auto-vacuum. After History deletion or Discard commits, freed pages
  return to the file system in small background steps that capture, Start and reads run between, and the WAL is
  truncated.
- **Raw controller bytes are not stored.** No feature reads them.

## Controller

- **Only requests 111 (identity) and 50 (selective telemetry).** No drive, configuration, calibration, firmware,
  full-values or keepalive command, and no general Bluetooth write API.
- **A backgrounded app without a ride polls at 1 Hz.** The link stays up for a Watch Start; a preparing, running or
  paused ride keeps its frozen rate in the background, and the foreground uses the selected rate.
- **A missed reply is asked for again, not treated as a lost link.** The controller leaves some requests unanswered,
  most often while the motor draws current. A telemetry request without a reply after 1 s is sent again on the same
  connection; only 3 s of unanswered requests or a system disconnect closes the link. Replies carry no request number,
  so a reply completes the request outstanding when it arrives and keeps its arrival time; a late reply and the reply
  to its re-sent request record one reading. Bytes that arrive with no request outstanding are decoded and counted,
  never recorded, so a late duplicate split across the next request completes it like any late reply. Each reply
  timeout, and each link the bike or the phone ends, logs what arrived since its request was first sent:
  notifications, discarded and pending bytes, unrecognised frames and replies the link did not use. A reconnect after
  the app closes a live link waits at least 2 s, because Android ends the released link a second later. A reconnect
  that keeps delivering telemetry for 3 s restarts the backoff; a bike that is off, or drops again sooner, keeps
  backing off. A failed service lookup retries for a known controller or during a ride.
- **On the iPhone, a ride reconnects just before the bike's own link limit.** The X6 asks for a connection interval
  under 15 ms at 5, 35 and 65 s into every link; iOS never grants it, and the X6 ends the link at 95 s (HCI 0x3B).
  Once the bike has ended three links of at least 30 s at ages within 1 s of each other, a ride closes the link at its
  first reading within 2 s of the shortest of those ages and reconnects as soon as iOS reports the disconnect, without
  Android's link-release wait; each such drop then costs about 0.8 s of readings instead of about 4 s. A link the bike
  ends before its planned reconnect turns planning off until the next connection. Previews, background links without a
  ride, Android and the web keep the bike's own drops. The lasting fix is CYC's firmware.
- **Unknown devices get two identity attempts.** Discovery lists any Nordic UART device. Outside a ride, a device without
  a remembered verified identity that leaves the identity request unanswered on two connection attempts is reported as
  not replying, with the hint to turn on the bike's display.
- **The remembered bike.** Verified controllers and the selected bike are remembered on every platform. The iPhone
  reconnects the bike on every launch, Bluetooth relaunches included, and the Ride screen's charts fill from a fresh
  preview. Android reconnects it when a process first opens its UI, never from background work. A connection that
  fails outside a ride shows its failure once and forgets the bike.
- **A controller without write-without-response is refused** on every platform.
- **Names follow roles.** CYC appears only in its decoder, identity rules, GATT UUIDs and product labels. A new
  controller adds a decoder in `ControllerDecoders.all`, its fixtures and capabilities.
- **Meanings.** Recorded rider power is never motor or battery power; `motorInputPowerW` is battery voltage × current,
  shown as battery input power. CYC cadence is controller-smoothed. Controller and motor temperatures are never
  ambient.
- **Controller speed** is `speedRaw / 3.6` only for exact X6/X12 identities with protocol 5.3. When the ride's distance
  source is the controller, controller speed is the FIT and ride CSV record speed and the FIT maximum; GPS-distance rides
  keep GPS speed, and Health never receives controller speed. Its distance is a **Controller estimate**: Power Log does
  not calibrate wheels.

## Live display and charts

- **Six-second hold.** A controller interruption shorter than six seconds keeps the last values and, during an accepted
  ride, the normal connection appearance; setup shows the actual connection state. At six seconds current-value tiles
  become unavailable and the reconnect error shows. Chart headers keep the last recorded value; original sample times
  appear during inspection. Holds and chart continuity are rendering only, never samples, Health data or exports;
  longer gaps stay visible. Energy, charge and Wh/km lines join across the same short controller gaps; totals and
  averages exclude the gap, except the controller energy and charge counters and controller distance, which span it.
  A metric with no samples is unavailable, never zero.
- **Separate timing policies.** GPS age admission (15 s), GPS freshness (10 s), heart-rate display (15 s), plot
  continuity (30 s) and integration (10 s), controller integration (2.5 s), the controller distance join (6 s) and
  controller display and plot continuity (6 s) stay independent. Chart tails are 6 s, 2.5 s for GPS and controller
  distance, from the metric catalog.
- **Live freshness uses monotonic acquisition time;** replays, retries and backfill never renew it, and backfill keeps
  its original time.
- **The ride card evaluates no policy.** The Live Activity and the Android ride notification take new readings at most
  every 15 s; control and phase changes and expiries update them at once. The app supplies the expiry instants (bike
  +21 s, heart rate +30 s: the display holds plus one refresh); the card only compares the clock with them.
- **Charts.** Plots keep each bucket's original first, minimum, maximum and last observations; cursor, extrema and A–B
  readings are exact originals with their original times. A whole-ride plot keeps exact-width buckets while they are
  shorter than one second; from then on its buckets are the smallest power-of-two second slots, aligned to ride time,
  that cover it within the bucket budget. Different units use aligned lanes. Every platform draws charts, empty ones
  included, in dark bordered cards with the same metric colours and labels. Horizontal inspection works anywhere in the
  plot. Reordering on the web and the editor's search over the selectable metrics stay.
- **Only visible charts are read and redrawn:** the charts on screen and within half a screen above or below, as one
  snapshot. A chart that leaves keeps its last drawing; one that enters shows it, or Loading… until its read lands.
  Number tiles, cursor, A–B and comparisons always cover every selected metric.
- **Units:** Metric or Imperial, and °C or °F, defaulting to the region's measurement system. Altitude and GPS accuracy
  show in feet in Imperial; torque stays in Nm; exports and Health stay SI.
- **Dates and durations** are formatted by `kit` everywhere: "Mon, Nov 2 · 14:05" with the device's 12/24-hour
  setting; durations mm:ss under an hour and h:mm:ss above, on the Watch too.
- **Disabled controls and refusals** show the reason and its next step.

## Recording

- **Connect before Start.** Every new ride needs a connected controller, Watch requests included. GPS and the Watch are
  additional sources. Once Start is accepted, normal reconnect behaviour applies.
- **Setup shows actions:** keep the bike connection, one Settings link and Start. Permissions show only when they need
  attention, before the Start attempt.
- **Start settles within one 15 s window.** While a ride is preparing, every action except Discard answers "action
  pending". A Start with a GPS distance source and GPS recording off refuses with a message that says how to fix it.
  On Android a foreground service refused at Start fails the Start, and the failed ride stays in History until the
  rider deletes it.
- **Choices freeze at Start:** GPS, Health, indoor, the 2, 4 or 8 Hz rate, the concrete distance source, the battery
  method, the Health writer and video sync beeps. The phone owns the rate, distance and battery preferences; a Watch Start uses the
  saved phone settings. Settings that can't be read block Start until Reset settings saves the defaults.
- **Checkpoints:** the ride's checkpoint commits with every capture batch, or alone after 5 s without one while the ride
  runs; a paused ride keeps its Pause's.
- **Bounded capture.** Commits are atomic and queues bounded. A full queue drops the newest reading and marks a gap.
  Losing uncommitted readings is acceptable; corrupted committed data, fabricated zeros and silent erasure are not.
  Only storage failures interrupt a ride: a busy commit is retried silently, and any other recording error is shown
  without interrupting the ride. After a storage interruption, readings not yet saved are dropped. Readings during a
  pause are not recorded.
- **Local completion is independent.** Finish commits the local ride and enables History and export without waiting for
  Health or a Watch reply. Health has its own outcome.
- **Interrupted means Finish or Discard.** At the next launch committed readings stay, the ride is marked Interrupted
  and offers Finish or Discard, from the phone or from the Watch. Finish ends at the last retained reading or event,
  else at the ride's start, never at its checkpoint. There is no resumption into Paused. A leftover Health session is
  only ended, never used for a new ride.
- **The preview is temporary:** before Start the connected bike's newest 28,800 readings stay in memory only, with no
  distance or consumption values.
- **Lap while paused** is the Watch's alone.
- **Video sync beeps** (a setting, off by default) let video tools line the ride's files up with a camera's sound.
  - **The beeps.** Start plays three pips one second apart, then a shorter, higher sync beep. Resume from Paused and
    Finish from Running play the sync beep alone.
  - **On the beep.** The ride starts, resumes or ends at the sync beep's onset, on a whole second. Start uses a whole
    UTC second; Resume and Finish use a whole second of ride time. So FIT's start time and first and last records, the
    ride CSV's first and last rows and the events fall on beeps, with no change to the exports.
  - **Timing.** The ride is created at the beep, after a 3.5–4.5 s countdown with Cancel. Resume and Finish wait 1–2 s.
    Pause, Lap and Discard don't beep.
  - **A reading in the beep's second.** A controller request is timed into the beep's half second, and the iPhone's
    planned reconnect waits for it.
  - **A tone never moves the ride.** A beep that fails or is cut short is shown on the recording failure line and
    logged, and the ride moves on time. The iPhone declares the audio background mode, so beeps play with the phone
    locked.
  - **Storage.** The choice is kept in memory for the ride, and the archive stores no beep.
- **Android holds a wake lock for the whole ride,** so reconnect and checkpoint timers keep running.
- **Deleting a ride** is allowed while its Health work runs; the work is cancelled.

## Watch

- **The phone creates the ride first.** A Watch-assisted Start expires after 15 s; Cancel Start on the Watch leaves no
  ride. After a failed Watch Start, the way out is to turn off Apple Watch in Settings and start again. Starting another
  Watch session first requires the previous HealthKit session to end.
- **Commands need the phone's reply within 5 s.** Pause, Resume and Lap need the phone; with the phone out of reach,
  Finish ends on the Watch. Local Finish never waits for Watch delivery.
- **Phone state reaches the Watch every 2 s, controls at once.** Watch readings keep their original timestamps.
- **Health items reach the Watch best effort:** each `ride` message carries the items not yet sent, and a message the
  Watch misses loses them as an honest gap. The Watch reports one Health outcome with its workout id. The Watch keeps its
  Start tombstones and its replies to `start`; the phone keeps neither.
- **Phone GPS supplies the route,** Watch-assisted rides included; the phone sends route points on the shared
  reduction's stride.
- **Phone and Watch run the same build.**

## Health

- **Append once.** Accepted per-second rider power, cadence and GPS speed are published with a short admission buffer
  (3 s, the iPhone's) and a fixed provider version, and never revised after late data. Positive GPS distance intervals
  publish once. Original phone readings stay available locally.
- **One id format on every platform.** Per-second ids count from the ride's start. Any sample whose id starts with
  `powerlog:` is Power Log's own and is never read back.
- **Routes:** Apple Health gets every point up to 30,000 and an even reduction beyond; Health Connect gets a
  deterministic thinning within 900,000 bytes that keeps the first and last points.
- **Published meanings.** A workout or session with laps, pauses and route, rider power, cadence, GPS speed and GPS
  distance. Never motor input power, controller speed or distance, or derived work as human output. Automatic cycling
  power, cadence, speed and distance collection is disabled where the writer supplies those quantities.
- **Provider rules remain.** Permissions, units, range checks, route limits and provider call budgets are real adapter
  constraints. Health admission uses 0–5,000 W and 0–300 rpm. Health Connect's rate limit is handled by its own error
  and a retry at the next run, without quota bookkeeping.
- **One writer and small outcomes:** submitted, saved, failed or uncertain. An uncertain operation is never replayed
  after process death. An explicit Android retry exists only for known safe failures. A lost Watch session never gets
  a replacement workout. Without Health Connect's exercise write grant nothing is written, and Start shows disabled with
  the Health reason.
- **Health off:** no Power Log workout is saved. The Watch still needs a HealthKit session for live measurements and
  discards its builder when done; sensor samples and ring credit HealthKit made may remain.
- **Readback** runs after every Finish, at Finish, +15 and +60 min and on Refresh, whenever Health read access is
  granted, whether or not the ride saved to Health. A failed readback waits for the next instant. Only samples inside
  the ride are kept. Apple includes heart rate, active and basal energy, cycling quantities, respiratory rate, oxygen
  saturation, HRV SDNN, physical effort, cycling FTP and available effort scores; Android heart rate, active energy,
  respiratory rate, oxygen saturation, HRV RMSSD, power, cadence, speed, distance and exercise-session information.
  The types that are only read back stay in the ZIP; no screen shows them.
- **One Health source per metric.** Health Connect selects the origin with the greatest clipped coverage, ties by package
  name, and never sums competing origins. Android reads no total calories and derives no resting energy. Android asks for
  no Health Connect history access: a ride more than 30 days before the first grant is not read.
- **Health Connect unavailable at readback:** the readback rows end `unavailable`, and publish rows not yet saved fail.
- **Discard and History deletion leave Health alone.** Pending local Health work is cancelled; a provider call already in
  flight may finish. Saved Health records are never looked up or deleted, and Discard shows no Health text.

## Analytics

- **Two time domains.** Controller, GPS and lifecycle events sit on the monotonic capture timeline; Health samples are
  placed by UTC from the ride start. Original UTC never changes, so after a clock change Health and FIT placement may
  differ.
- **Integer microseconds.** Every analytical and stored time is `floor(seconds × 1,000,000)`; original strings and
  values stay for display and export.
- **Fixed point.** Readings are quantized once at admission (half to even), summed as exact Int64 and averaged with one
  IEEE division, so raw, paged and tiled results agree on every target.
- **Integration.** An invalid or missing selected reading breaks every integrated channel; ordinary pause endpoints are
  excluded; an interruption closes the active interval.
- **Energy.** Within the selected source the latest final total wins, then the clipped sum of overlapping intervals,
  then the largest cumulative snapshot; representations and devices never add. An interval counts at its end.
- **Altitude is above mean sea level.** Android converts at capture or reports none; Core Location altitude already is
  MSL. Originals keep their datum, and export never converts.
- **Distance freezes at Start.** Auto selects phone GPS for outdoor GPS recording and the controller otherwise; an
  explicit choice never falls back. **Ride distance** uses that source for summaries and FIT; **Distance (GPS)** and
  **Distance (controller)** charts keep both. Health always uses GPS. Controller distance joins readings less than 6 s
  apart from the same controller (same identity and active interval) across reconnects, using the speeds on either
  side; a controller swap starts a new segment. In an opened file, two sessions without an identity count as one
  controller. FIT average speed is ride distance over covered time.
- **Battery consumption is ride-relative.** **Energy used (controller)** and **Charge used (controller)** accumulate
  positive counter deltas; **Energy used (calc)** and **Charge used (calc)** integrate positive V × A and current. Each
  starts at the first valid reading and keeps supported totals across resets, pauses, reconnects and gaps without
  counting the unknown interval. A counter increase across a loss shorter than the six-second hold, within one active
  interval, without a reset and within what a supported controller could draw (200 A, 25 kW), counts as one indivisible
  delta. Calculated intervals use the controller integration limit and clip zero crossings: this measures consumption,
  not net regeneration. No metric is made from missing inputs.
- **One battery-consumption preference** chooses the Ah/Wh pair for ride summaries and FIT, defaulting to Controller,
  and freezes at Start. Both methods stay selectable in charts. Electrical Wh/Ah never enter Health as human energy.
- **Energy per distance.** Number tiles show whole-ride matched Wh/km; graphs overlay battery and rider on one Wh/km
  axis from current non-negative power over the selected source's speed. Zero or missing speed is unavailable.
  Optional 5, 30 or 60 s smoothing affects only the plot and its cursor, resets across gaps and defaults off.
  Summaries and A–B are matched energy over distance, never means of graph values. Live tiles refresh their whole-ride
  averages every 2 s in the first minute, 5 s until 10 minutes, 15 s until an hour, then 30 s.
- **Derived data is disposable.** An unreadable original is an error; an unreadable cache is recomputed.

## Exports and sharing

- **One timeline, in Kotlin.** Kotlin's projections and the one per-second timeline feed FIT and the ride CSV; the
  TypeScript writers only encode bytes. Pages cross the bridge in binary, and the bytes leave through the platform's
  sink: the Expo module's byte functions on the phones, the OPFS sink on the web. An export stops when the rider leaves
  the app, not the screen; a diagnostics share runs to its outcome. An export during which the ride changes, such as a
  Health readback storing samples, fails with "This ride changed while it was exporting. Export it again."
- **The files.** Every platform writes one FIT activity, one ride CSV and one ride-data ZIP. The ZIP is the complete
  original: a Data Package (`datapackage.json`) with telemetry, derived battery totals, GPS, Health and events CSV, each
  source at its own rate. The ride CSV is FIT's per-second records as one table, every value a named column, with the
  ride totals of the frozen consumption source; a second without a reading stays empty, nothing is interpolated,
  except that seconds inside a joined controller loss carry the joined distance and speed. FIT has a record per second,
  or the smallest wider interval within Garmin Connect's point limit, and writes battery power as `motor_power`.
- **FIT power source** (an export setting, default Rider power): Battery input power instead puts that measurement in
  standard `power`, average and maximum power and total work, for apps that need it. It applies to existing rides too,
  and never changes recordings, charts, ZIP originals or Health.
- **Times print from integer µs.** The ZIP's elapsed and timer seconds, its descriptor's `timerSeconds` and the iPhone's
  Health timestamps print from µs. `health.csv`'s provenance cells `logicalKey`, `targetEventId`, `originRevision`,
  `valueBits` and `evidence` are empty, and `events.csv`'s `timerSeconds` stays empty. An interrupted ride is only marked
  Interrupted, with no extra pause event.
- **ZIP compression** is each platform's own, so ZIPs compare entry by entry.
- **Refusals.** A FIT or ride CSV refused by one of FIT's limits (31 days, 10,000 events, 4,096 laps, 99,998 records,
  FIT's time range) says "This ride is too large for this file.", and one with no readings "This ride has no power,
  cadence, heart rate, GPS or distance readings for this file.", each adding "The ride-data ZIP is still available."
- **Open CSV** reads a ride-data ZIP's `telemetry.csv` up to 256 MiB, 700,000 samples and 7 days, ignores unknown
  columns, and can export the opened file as CSV again.
- **Manual sharing.** Nothing leaves the device without a rider action. Strava gets FIT only through export and its
  official upload page, with no credentials, integration or relay. Deleting a ride keeps its Health records and
  uploads.
- **Example rides** come from one shared generator in phone development builds, labelled, without the Strava shortcut;
  the web has none. The App Store screenshots use them, with Health saving off.
- **History rows show one status line;** the ride page shows the full outcomes.

## Diagnostics

- **The diagnostic log** has one closed vocabulary and field whitelist
  (`tests/fixtures/contract/telemetry/diagnostic-log.json`): events, reasons, timings, counters, platform error codes
  and the command code of an unrecognised controller frame, never names, identifiers, payloads or ride measurements.
  Beyond Bluetooth and storage it records each ride phase, each Health outcome and every failure from Health, the
  Watch, the keep-alive, permissions, files and exports. Two 4 MiB files hold at least ten hours of riding. Durations
  are whole milliseconds; timestamps are µs.
- **Release builds write only warnings and errors to the OS log.**
- **No crash service and no MetricKit.** The Kotlin crash hook keeps the stack on both phones, and an exception inside a
  task logs its frames; Android logs why the previous process ended, once per launch; iPhone exit causes come from the
  OS's Analytics Data and Xcode's device logs.
- **The Bluetooth trace** keeps rejected packets before decoding in bounded, temporary memory, apart from rides and the
  log, and only while the rider captures it. Exporting it is a rider action with notice that raw bytes can include
  serials. The trace never bypasses ride admission or adds controller commands.
- **Share diagnostics** zips the log with the phone model, system version and app version. Nothing leaves the phone
  unless the rider shares it.

## Performance

- **At least 0.7.0's battery use and speed.** Capture commits in Kotlin through the native SQLite driver on both phones,
  with no Swift or Java writer. Its cost rules: only small work on the Bluetooth callback queue, one hop to the writer and
  one back per commit, persistent due and checkpoint timers, no log line per batch, and only immutable data crossing
  executors.
- **The bars** (testing's [stress test](testing.md#the-stress-test)): at 8 Hz, ≥ 95 % delivered in every linked 60 s
  window, every reading saved within 1.5 s, memory flat; CPU, cold launch and export times no worse than the starting
  build's, judged by bracketing runs on one day and device.
- **No benchmark machinery is kept:** no benchmark builds, harnesses, kept baselines or in-app autopilot. Chart
  gestures are checked by hand.

## Not built

Designs that were considered and left out. Don't add them back without a decision.

- A custom bridge framework (operation tables, acknowledgement credit, digests), export bytes through Kotlin, extra UI
  API operations (a live-ride feed, change feeds, session progress, a ride-card Finish call), and command ids with
  repeat caches.
- SQLDelight, SQLite in the browser, schema CHECKs, foreign keys and the `foreign_keys` pragma, read pins, leases and
  writer priorities, cache families beyond summaries and tiles, an in-memory archive for tests.
- Kotlin FIT, CSV and ZIP encoders, and a Kotlin ZIP compressor.
- Stored raw controller frames and decoder versions; stored values only the starting build wrote (Health provenance
  cells, a separate controller speed column, interruption cuts, connection times, Watch GPS fixes).
- Health Connect quota bookkeeping; an acknowledged Watch Health backlog and per-item de-duplication on the Watch; a
  route reducer on the Watch; a generated Swift Watch contract.
- MetricKit and de-duplicated exit reports.
- A second app identity (a Lab build, an Android preview flavour), web development tools and a store-check switch.
- Settings revision files, a "recovered" notice, per-ride "couldn't be read" counts.
- "Start without Watch" and an Android wake lock released between polls.
- Sync beeps on a timer during the ride, a manual beep button, beeps from the Watch's speaker, timecode in audio
  (LTC), FIT marker events, beep rows in exports, and stored beep outcomes.
