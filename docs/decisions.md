# Decisions

Product and design choices that the code alone does not mark as deliberate. C and D labels come from the owner's
decision record; N labels are the analytics rules that tests cite.

## Scope and platforms

- **Minimum iOS 26, watchOS 10.** Every supported iPhone can run a HealthKit workout session; watchOS 11 effort scores are read only where the Watch provides them.
- **The phone records every ride.** The Watch is a required-phone sensor, display and control companion, with no
  permanent ride archive. For Watch-assisted rides it runs the native Health session and is the sole Health writer;
  otherwise the iPhone writes Health. Android uses Health Connect and web has no Health writer.
- **The iPhone is the reference.** Android and web match its behaviour, controls, gestures, animations, haptics and
  resets, except for the differences listed here. The phones share one TypeScript layout and branch on native versus
  web, never iOS versus Android; web changes only responsive placement and interaction handles.
- **D6: web is foreground-only and has no Health provider.** It records Bluetooth and GPS while the page is visible,
  requires Web Bluetooth for new recordings and keeps rides unencrypted in IndexedDB. Browsers without Bluetooth
  retain History, import and export.
- **D7: Android physiology is Health Connect backfill,** read after the ride and labelled backfilled, never live.
- **Native background recording.** Capture and the lifecycle run in Swift and in Android's foreground service and
  survive an inactive React UI; JavaScript timers never drive mobile recording or Live Activity deadlines.
- **No dead ends.** A ride with no way out is a critical defect: every waiting state offers an explicit rider action
  instead of waiting forever or completing on its own.
- **Pre-release changes are made in place.** Until the first public release, schemas, storage keys, database names,
  cache formats, bridge fields and Watch messages change directly, with no migrations, upgrade code, compatibility
  readers, dual paths, version-triggered rebuilds or policy versions. Recordings, caches and settings are disposable:
  testers reinstall phone and Watch together and clear browser site data. An unreadable or incompatible store is still
  refused visibly, never erased or hidden. From the first public release, each format change ships its own forward
  migration or cache invalidation.

## Core

- **D1 (C23, C25): shared pure logic.** Kotlin Multiplatform supplies telemetry, numerical analytics, inspection,
  phone presentation and local lifecycle decisions for Android, iPhone and TypeScript. Adapters own native IO,
  transactions, clocks and scheduling. The Watch compiles only its small Swift companion/Health implementation;
  it does not link the analytics framework. TypeScript owns the shared UI and FIT/ZIP byte writers (C14).

## Controller

- **Only requests 111 (identity) and 50 (selective telemetry).** No drive, configuration, calibration, firmware,
  full-values or keepalive command, and no general Bluetooth write API.
- **C33, C35: names follow roles.** CYC appears only in its decoder, identity rules, GATT UUIDs and product labels. A
  new controller adds a decoder, fixtures and capabilities behind `ControllerProtocol`, registered in
  `TelemetryRestoration`; telemetry keeps requiring rider power and cadence until a controller lacks them.
- **Meanings.** Recorded rider power is never motor or battery power; `motorInputPowerW` is battery voltage × current, shown as
  battery input power. CYC cadence is controller-smoothed. Controller and motor temperatures are never ambient.
- **Controller speed** is `speedRaw / 3.6` only for exact X6/X12 identities with protocol 5.3. It never replaces GPS
  speed, and its distance is a **Controller estimate**: Power Log does not calibrate wheels.

## Live display and charts

- **Six-second hold.** A controller interruption shorter than six seconds keeps the last values and, during an
  accepted ride, normal connection appearance; pre-ride setup shows the actual connection state. At six seconds
  current-value tiles become unavailable and the reconnect error shows. Chart headers keep
  the last recorded value; original sample times appear during inspection. Holds and chart continuity are
  rendering only, never samples, Health data or exports; longer gaps stay visible. A metric with no samples is
  unavailable, never zero.
- **Separate timing policies.** GPS age admission (15 s), GPS freshness (10 s), heart-rate display (15 s) and
  integration (10 s), controller integration (2.5 s) and controller display and plot continuity (6 s) stay independent.
- **Live freshness uses monotonic acquisition time;** replays, retries and backfill never renew it, and backfill keeps
  its original time.
- **O6: the Live Activity evaluates no policy.** The app supplies core-computed expiry instants (bike +6 s, heart rate
  +15 s) and the staleDate; the extension only compares the clock with them.
- **Charts.** Plots keep each bucket's original first, minimum, maximum and last observations; cursor, extrema and A–B
  readings are exact originals with their original times. Different units use aligned lanes. Every platform draws
  charts, empty ones included, in dark bordered cards with the same metric colors and labels, and summary readings sit
  directly on their panel. Monitoring presets are independent and keep the whole ride.
  Horizontal inspection works anywhere in the plot; touching a short peak can still select its exact original.
  Metric displays omit repeated source, quality and freshness captions; names identify the source where needed.

## Lifecycle

- **Connect before Start.** Every new ride requires a connected controller, including Watch requests. GPS and Watch
  are additional sources. Once Start is accepted, normal reconnect behavior applies; a temporary BLE loss introduces
  no additional Start cancellation. Historical rides with missing telemetry remain readable and exportable.
- **Setup shows actions.** Keep bike connection, one Settings link and Start. Show permissions only when they need
  attention; recording preferences and diagnostic details do not belong in the setup sheet.
- **Choices freeze at Start.** GPS, Health, indoor, the 2, 4 or 8 Hz capture rate, the concrete distance producer, battery-consumption method and
  Health writer stay fixed for the ride. The phone owns the capture-rate, distance and battery-consumption preferences; Watch Start
  uses those saved phone settings, with no separate Watch rate preference (C22). Phone Start requires its settings
  save to succeed. The Watch retains its indoor/GPS/Health controls.
- **Local completion is independent.** Finish commits the local ride and enables History/export without waiting for
  Health or a Watch reply. Health has its own outcome. A pending old Watch save does not block a new phone-only ride;
  starting another Watch session first requires its previous native session to end.
- **Interrupted means Finish or Discard.** On restart retain committed measurements, mark the ride Interrupted and
  offer Finish at the last retained reading/event or Discard. There is no resumption into Paused and no reconstruction
  of unfinished commands. Existing native Health sessions are recovered only for cleanup, never to invent a new ride.
- **Bounded capture.** Keep atomic commits and bounded queues, with honest storage failures. Losing uncommitted
  observations is acceptable; corrupted committed data, fabricated zeros and silent erasure are not. Queue limits
  are not a guarantee of a one-second crash-loss bound.
- **Preview is temporary.** Before Start, live data stays in bounded memory. Once recording, dashboards and History
  query the same ride archive. Previous recorded rides remain in History.

## Watch

- **C24: routine phone-to-Watch messages are capped at one per second.** They carry display readings and bounded
  Health/route data; controls bypass pacing. Every admitted 2/4/8 Hz controller original stays in the phone archive.
- **Timestamped observations.** Watch measurements retain original timestamps and identities. The acknowledgement
  buffer drops oldest entries at its age/count/byte limits; long disconnections can leave gaps. There are no permanent
  Watch archives, full-archive transfers, seals or reconstruction proofs.
- **Bounded Start and controls.** The phone establishes the ride and Health writer before the Watch starts. Failed
  Start offers cancellation or phone-only recording. Expired attempts cannot start a late replacement workout.
  Pause/Resume/Lap require phone acknowledgement. Watch-local End remains available when the phone is unreachable.
- **Phone GPS supplies the route**, including Watch-assisted rides. Missing measurements stay missing. Local Finish
  is always independent of Watch delivery, so a separate Finish-without-Watch action is unnecessary.

## Health

- **C16: append once.** Publish accepted per-second rider power, cadence and GPS speed with a short admission buffer
  and fixed provider version. Do not revise a published value after late data. Detailed original phone observations
  stay available locally. Positive GPS distance intervals also publish once.
- **C16: routes.** Apple Health gets every point up to 30,000 and an even reduction beyond; Health Connect gets a
  deterministic thinning within 900,000 bytes that keeps the first and last points.
- **O7: published meanings.** Save a workout/session with laps, pauses and route, rider power, cadence, GPS speed and
  GPS distance. Never publish motor electrical input, controller speed/distance or derived work as human output.
  Disable automatic cycling power/cadence/speed/distance collection where the native writer supplies those quantities.
- **Provider rules remain.** Permissions, units, range checks, route limits and provider call budgets are real adapter
  constraints. Health admission uses 0–5,000 W and 0–300 rpm; analytical summaries retain their own range policies.
- **One writer and small outcomes.** Keep deterministic provider identities where supported, fixed versions and
  submitted/saved/failed/uncertain outcomes. Do not blindly replay an uncertain operation after process death.
  Explicit Android retry is available only for known safe failures; refreshing Health does not promise repair of
  missing Apple publication. Never create a replacement workout for a lost Watch session.
- **Health off.** Do not save a Power Log workout. The Watch still needs a native session to obtain live measurements;
  discard its builder when done. Sensor samples and ring credit created by HealthKit may remain.
- **Readback keeps the supported ride metrics.** Apple includes heart rate, active/basal energy, cycling quantities,
  respiratory rate, oxygen saturation, HRV SDNN, physical effort, cycling FTP and available effort scores. Android
  includes heart rate, active energy, respiratory rate, oxygen saturation, HRV RMSSD, power, cadence, speed,
  distance and exercise-session information. Availability and permissions determine actual data.
- **Read once, then bounded refresh.** Retain original timestamps and enough source identity for deduplication. Do not
  reimport Power Log's published samples or republish imported data. Later readback can add local measurements and
  invalidate views; it does not revise already published Health data. There is no exhaustive provider deletion/history
  synchronization. Android reads at Finish, +15 minutes and +60 minutes; explicit refresh remains available.
  Refresh can read permitted measurements with saving off; it does not enable publication or require write permission.
- **O5: one Health source per metric.** Health Connect selects the origin with greatest clipped coverage, ties by
  package name, and never sums competing origins. Android does not read total calories or derive resting energy;
  Apple-provided resting energy remains available.
- **Discard and History deletion leave Health alone.** Discard removes the local ride and ends recording, including
  an available native session, without requesting a new workout save. Cancel pending local Health work; a provider
  operation already in flight may finish. Never look up or delete saved Health records. Discard has no Health-related
  text or warnings. Normal Finish still saves automatically on a best-effort basis.

## Analytics

- **Two time domains.** Controller, GPS and lifecycle events sit on the monotonic capture timeline; Health samples are placed by UTC from the ride start. Original UTC never changes,
  so after a clock change Health and FIT placement may differ.
- **N1, C38: integer microseconds.** Every analytical time, charts included, is `floor(seconds × 1,000,000)`; original
  strings and values stay for display and export.
- **C15: fixed point.** Readings are quantized once at admission (half to even), summed as exact Int64 and averaged
  with one IEEE division, so raw, paged, checkpoint and tile results agree on every target.
- **N2–N4.** An invalid or missing selected reading breaks every integrated channel; ordinary pause endpoints are
  excluded; an interruption closes the active interval.
- **N5, C34: energy.** Within the selected source the latest final total wins, then the clipped sum of overlapping
  intervals, then the largest cumulative snapshot; representations and devices never add. An interval counts at its
  end.
- **N6: altitude is above mean sea level.** Android converts at capture or reports none; Core Location altitude
  already is MSL. Originals keep their datum, and export never converts.
- **Distance freezes at Start.** Auto selects phone GPS for outdoor GPS recording and the controller otherwise.
  Explicit source choice never falls back. **Ride distance** uses that source for summaries and FIT; independently
  selectable **Distance (GPS)** and **Distance (controller)** charts retain both calculations. Health always uses GPS.
- **Battery consumption is ride-relative.** **Energy used (controller)** and **Charge used (controller)** accumulate
  positive counter deltas; **Energy used (calc)** and **Charge used (calc)** independently integrate positive V × A
  and current. Each starts at the first valid reading, retaining supported totals across resets, pauses, reconnects
  and gaps without counting the unknown interval. Calculated intervals use the controller integration limit and
  clip zero crossings; this measures consumption, not net regeneration. Range queries exclude clipped counter deltas
  and mark them partial. Counter increments use the controller's 1e-4 precision; electrical integration uses double
  precision for clipped areas. No metric is manufactured from missing inputs.
- **One battery-consumption preference** chooses the Ah/Wh pair for ride summaries and FIT, defaulting to Controller,
  and freezes at Start. Both methods remain selectable in charts. Raw **Energy counter (controller)** and **Charge
  counter (controller)** stay unchanged in recordings and the ZIP; a separate ZIP table contains the ride totals.
  Electrical Wh/Ah never enter Health as human energy.
- **Derived data is disposable.** An unreadable original is an error; an unreadable cache is recomputed.

## Sharing

- **Exports.** Every platform writes one FIT activity and one ride-data ZIP with the shared TypeScript exporter. The
  ZIP is a vendor-neutral Data Package: `datapackage.json` with telemetry, derived battery totals, GPS, Health and events CSV.
  Unsupported derived analysis never blocks raw ZIP export; its metadata records why the derived table is unavailable. FIT has a record
  per second, or the smallest wider interval within Garmin Connect's point limit, and writes battery power as
  `motor_power`. The export-time **FIT power source** setting defaults to Rider power; Battery input power
  instead puts that measurement in standard `power`, average/maximum power and total work for app compatibility.
  It applies to existing rides too, without changing recordings, charts, ZIP originals or Health publication.
  Missing or negative battery power is omitted from standard power, never replaced with rider power.
- **Manual sharing.** Nothing leaves the device without a rider action; Strava gets FIT only through export and its
  official upload page, with no credentials, integration or relay. Deleting a ride keeps its Health records and
  uploads, including when the ride is discarded.
- **Example rides** are generated, labelled and omit the Strava shortcut; App Store examples use the phone archive and Watch companion
  with Health saving off (C39).
- **C40: History rows show one status line;** the ride page shows the full outcomes.
