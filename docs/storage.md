# Storage, lifecycle and Watch synchronization

Android uses a separate SQLite implementation of the same public contracts; [Android storage and exports](android.md) describes its lifecycle and schema.

## Originals and queries

Each native device uses one SQLite database through [PowerLogStore](../modules/cyc-bridge/ios/PowerLogStore.swift), with one executor per connection, STRICT tables, WAL, `synchronous=FULL` and foreign keys. Normal capture writes typed rows, not whole-ride JSON. A checkpoint reclaims WAL space; durability follows committed transactions ([SQLite reference](https://sqlite.org/pragma.html#pragma_synchronous)). Before the first public release, schemas change in place without migrations; testers reinstall. A database from an earlier schema version is refused with a message to reinstall (on the web, to clear site data); one from a later version is refused with "Update Power Log to open your rides". The version is read before WAL is enabled, and the file is never erased. Android opens its database with a handler that fails closed on corruption instead of deleting the file; SQLite may still discard a write-ahead log that holds no valid frames. Ride metadata always carries the Health and GPS choices frozen at Start; a record without them is invalid.

One physical observation can belong to live and ride collections without duplication. Original UTC, acquisition clock, collection elapsed time and exact integers remain distinct. Corrections and metadata are revisioned, and a write that would advance a revision counter past 2^53−1 fails instead of losing precision in JavaScript; summaries, chart tiles and distance profiles are disposable derivatives.

| Data | Tables |
| --- | --- |
| Identity and typed originals | `observations`, `telemetry_frames`, `locations`, `health_samples`, `lifecycle_records` |
| Timelines, source progress and revisions | `collections`, `collection_memberships`, `collection_sources`, `collection_channels`, `collection_versions`, `collection_corrections`, `health_deletions`, `collection_changes` |
| Ownership, commands, receipts and deletion fences | `durable_records`, `counters` |
| Derived analysis | `derived_cache`, `distance_generations`, `distance_snapshots`, `distance_points`, `distance_health_inputs` |

Phone capture batches live/ride memberships together, targeting eight frames or one second of available execution. Destinations and timeline mapping stay fixed across retries; failures retain buffered frames. Lifecycle barriers drain admitted frames before changing boundaries or sealing a source. Abrupt termination can lose the uncommitted RAM tail; a suspended timer cannot promise a wall-clock flush.

Indexes serve catalog order, elapsed/UTC ranges, source/kind, membership, export order and revision. History loads metadata pages, each carrying the ride's measured `elapsedSeconds`, taken from retained owner timing and app-timed observations (History never subtracts timestamps, and Health placement can widen a chart but never the duration); summaries and exports read typed fields in 256-row pages at a fixed revision, releasing the executor between pages. Distance profiles advance bounded checkpoints or rebuild after corrections; exact boundaries use indexes. Derived caches carry no format or policy version. [Measurement rules](measurements.md) define the calculation, and [chart architecture](architecture.md#chart-queries-and-rendering) defines display reads. Admission constants live in `PowerLogStorageLimits`; queue limits are not total-memory guarantees.

iPhone live freshness is a lock-protected, process-local map per collection and monitor channel. Only newly admitted live observations update it after commit, and acquisition advances monotonically; retries, corrections and Health history never renew it. A live latest read picks, per metric, the channel with the newest evidence and fetches that observation once through the physical-identity and membership indexes, against the collection's current revision; a distance snapshot that lags capture reports pending on its own and never removes sensor readings. Without evidence, both the point and acquisition are null. Channel availability, including Health speed, comes from per-collection channel metadata, so live descriptions do no work proportional to Health history. Saved latest reads keep ordinary channel discovery and elapsed-neighbor queries. The partial `membership_app_elapsed` index serves measured elapsed queries without scanning Health history. An explicit Retry Health save requested while an automatic pass runs for the same ride is queued behind it, and only the pass that scans the ride's originals clears the repair marker.

Native chart reduction retains first/minimum/maximum/last observations per continuous run within each bucket. Mixed-run cache nodes descend to finer summaries or originals. Ranges with too many discontinuities for the bounded output require a smaller range; no run is silently dropped.

## Ownership and finalization

`WorkoutEngine` chooses one owner: Watch when selected, phone otherwise. Durable command intent precedes native effects; receipts follow observed completion. Recovery reconciles uncertain operations with the existing session/workout before retrying, never creating a second Health workout to resolve an uncertain save.

Health/GPS choices freeze at Start as the effective choices for the selected owner: Health saving is frozen on only when that owner can save (phone-owned rides need iOS 26 on iPhone or Health Connect on Android; Watch-owned rides need iOS 17). Absent GPS choice follows outdoor mode. Explicit GPS choice is independent of indoor/outdoor mode. Source requirements reflect these choices: Watch off requires no Watch stream, GPS off no route.

With Watch and Health saving off, the phone uses `WorkoutLocalOwner` without Health authorization or an `HKWorkoutSession`. A selected Watch still uses HealthKit for sensors/background execution, with permissions requested there. Health saving off prevents app-added Health quantities, routes and workout creation; sensor reads drain before discarding the builder. Apple's independent Health records are unaffected. Saving locally with Health off ends as `notRequested`, never as Discard or a later Health save.

Original timestamps and paused observations remain intact. Active timing, Health insertion, distance and FIT respect pause intervals and stop cutoff. Every checkpoint, interruption and seal retains a tuple of UTC, measured elapsed and active time; recovery never reconstructs a cutoff from start plus elapsed or from the current time. After a restart the resume boundary covers the latest committed observation, and elapsed and active time exclude the unobserved downtime, which is recorded as an uncertainty and a hard interruption that charts never join across. A durable stopped cutoff outranks older recovery state, and a seal's terminal timing is immutable. When recovery lacks retained evidence the ride stays unresolved rather than inventing timing.

Chart range statistics count only active observations. A supported interval clipped between observations can retain coverage and an integral with zero observations and no extrema or sample mean. Every controller-telemetry metric integrates only across adjacent observations at most 2.5 seconds apart; the six-second display hold affects rendering only.

**Local retention, Health outcome and complete iPhone archive are separate.** Health insertion keeps one canonical receipt per event (excluded, or per-metric results) with durable version reservations, so an uncertain retry uses a higher version and never duplicates a write; phone and Watch writers share that orchestration. An explicit Retry Health save pages the ride's originals and resubmits every metric whose receipt still needs repair (for example one denied at the time), resuming from a durable repair marker after a restart. Each app-inserted Health writer keeps an in-memory bound and drops samples dated before the workout start or before its previous sample; a permanently dropped telemetry sample settles an `excluded` receipt so insertion progress advances, and dropped route points and laps are omitted and reported. When the retained cutoff is not after the start, no Health finalization is attempted: the outcome settles to terminal `unavailable` with a clock reason (kept in metadata, owner snapshot and seal), pending effects are released, and a Health-off Watch ride skips its final sensor extraction with a terminal result. Native ownership is released only once the session's end is confirmed; until then a new Start is refused with an explanation. Original Watch records keep transferring, so the ride completes, verifies and exports. Final export readiness requires owner completion and verification of the required sources. Explicitly unavailable sources can produce a partial ride. History reads have no lifecycle effects; recovery targets the chosen ride, including older history.

## Watch transfer

The phone produces CYC originals; the Watch produces Health/GPS/lifecycle records. CYC travels to Watch for Health insertion but is not echoed back. Watch originals transfer progressively during recording. Final Health extraction and corrections can arrive after Finish, so progressive transfer does not guarantee instant completion.

Watch status packets carry semantic `gpsStatus` (`off`, `waiting`, `receiving`, `weak`, `stale`, `paused`, `denied`, `restricted`, `notDetermined` or `unavailable`) and optional numeric `gpsAccuracyM`. Accuracy above 50 meters is weak; paused rides report paused, and unselected GPS reports off. The phone normalizes unknown status values to waiting. Watch display labels stay local to its UI.

[WorkoutSync](../modules/cyc-bridge/ios/WorkoutSync.swift) manages the durable outgoing cursor; [WorkoutTransfer](../modules/cyc-bridge/ios/WorkoutTransfer.swift) handles compression, admission, receipts and verification:

1. Freeze one pending Watch range per ride, identifying producer, sequences, record count, encoded/decoded sizes and SHA-256 digest. Retries cannot enlarge or change it.
2. Deliver the zlib-compressed JSON batch through a reachable WatchConnectivity message or a background file. Both use the same inbox and receipt protocol.
3. Validate bounds, source and digest. Commit originals, progress and the immutable receipt atomically before acknowledging. Duplicates reuse that receipt; conflicting content is rejected.
4. Advance the sender cursor only after its matching receipt, atomically clearing the pending manifest. A successful radio callback is insufficient.
5. Deliver the revision-specific final seal independently, even if every batch arrived before Finish. Verify its cutoff, source roster, contiguous counts and digests against committed originals. Late batches trigger re-verification; newer revisions invalidate older completion proofs. Seal state and visible metadata update atomically.

Chunks allow 128 records and 512 KiB encoded/decoded payloads. Immediate envelopes allow 60,000 bytes including metadata/base64; sender staging and receiver inbox each allow eight files/2 MiB, reserving capacity for the current ride. Other bounds and retry policy live in the transfer/sync code. Compression/hashing run outside long SQLite transactions.

Activation, native heartbeats, reachability and receipts schedule coalesced work. Durable discovery keeps older pending rides recoverable without changing the active owner. Sparse CYC replication cannot block the independent Watch stream. Phone-only collections reject Watch archives; Watch-first provisional collections do not invent a finish time or claim phone control.

Saturation may evict an unclaimed historical transport copy without acknowledging it; canonical originals and the pending range remain for retry. Claimed imports cannot be evicted, and historical work receives turns. Staged sender files stay until native transfer references are gone, even when an immediate acknowledgement arrives first.

Apple's [WatchConnectivity](https://developer.apple.com/documentation/watchconnectivity/transferring-data-with-watch-connectivity) schedules background delivery without a latency guarantee. A [received file URL](https://developer.apple.com/documentation/watchconnectivity/wcsessionfile/fileurl) must be copied/moved before its delegate returns. [HealthKit mirroring](https://developer.apple.com/documentation/healthkit/building-a-multidevice-workout-app) ends with the session and does not replace archive receipts; Power Log budgets mirrored data below the SDK's rolling limit. Paired/reachable, committed chunk and verified archive are different states.

The Watch exposes ride controls rather than routine sync warnings/buttons. Transport retries run automatically; actionable capture, storage, integrity and Health failures stay visible. Unidentified launches may show temporary preparation but cannot create an active ride or overwrite a terminal outcome. Diagnostics exclude recording payloads and locations.

## Delete and discard

Deletion commits a fence before hiding/reclaiming a ride. Active/admitted operations prevent deletion. Bounded cleanup removes memberships, unreferenced originals, receipts, caches and exports; shared observations remain referenced. Watch deletion retries until acknowledged, and fences reject delayed traffic. Existing Apple Health workouts and external uploads remain untouched.

Discard is a durable owner command. It calls `discardWorkout()` instead of `finishWorkout()` and commits a terminal discarded outcome; a stopped callback alone cannot settle it or turn it into Save. It prevents workout creation, not independent Health measurements. Watch discard transfers its terminal state independently of full archive completion, retaining data until the deletion handshake finishes. Use matching phone/Watch builds.

## Browser and exports

IndexedDB retains controller originals, lifecycle, chart summaries and distance profiles. The origin-wide `power-log-recording` Web Lock and a persisted owner token prevent competing writers. Other tabs can read history but cannot control a live writer. After its page exits, recovery marks the committed prefix interrupted rather than restarting capture. Storage failures stop admission and preserve that prefix. Ride records carry a required set of fields and every row a connection epoch; an unreadable record is skipped in History with a warning that counts it, and records missing from the History index are reported separately. The first recording asks the browser to persist storage, and Settings shows whether saved rides are protected from automatic cleanup.

Writes are bounded; elapsed indexes and multiresolution summaries serve queries without CSV round trips or whole-ride reads. Summaries keep first/minimum/maximum/last per continuous run. Live elapsed time comes from the page's monotonic clock, so a wall-clock correction does not move the live chart or freshness. Paused originals remain stored; active timing excludes pauses. Eight hours at 8 Hz is 230,400 controller frames, supported by this path subject to browser quota/eviction; import limits are not recording limits.

| Export | Contract |
| --- | --- |
| CSV | Browser controller originals, paged from storage, with a 128 MiB export cap that never truncates the recording. Imported CSV is temporary, unverified analysis and can be re-exported on either platform; chooser and parser limits are 256 MiB and 700,000 samples (over 24 hours at 8 Hz), with at most seven days of elapsed time and 256 characters per cell. |
| Original ZIP | iPhone metadata, JSONL originals and `CYCtelemetry.csv`; a portable export, not the active storage format. |
| FIT | iPhone bounded processing at a fixed revision: rider power, cadence, available Health/GPS, timing/laps and mapped summaries. Missing data is never fabricated; motor watts/temperature never become human power/ambient temperature. |

Browser rides export CSV only; Watch, Health, phone GPS, FIT and original ZIP are native capabilities. Backups/fixtures must include a consistent WAL state: stop the app before preloading a database and retain user-selected rides before resetting. [Testing](testing.md) covers software and physical acceptance separately.
