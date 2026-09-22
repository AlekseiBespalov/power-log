# Storage, lifecycle and Watch synchronization

## Originals and queries

Each native device uses one SQLite database through [PowerLogStore](../modules/cyc-bridge/ios/PowerLogStore.swift), with one executor per connection, STRICT tables, WAL, `synchronous=FULL` and foreign keys. Normal capture writes typed rows, not whole-ride JSON. A checkpoint reclaims WAL space; durability follows committed transactions ([SQLite reference](https://sqlite.org/pragma.html#pragma_synchronous)). Incompatible schema versions require reinstalling; there are no in-place migrations.

One physical observation can belong to live and ride collections without duplication. Original UTC, acquisition clock, collection elapsed time and exact integers remain distinct. Corrections and metadata are revisioned; summaries, chart tiles and distance profiles are disposable derivatives.

| Data | Tables |
| --- | --- |
| Identity and typed originals | `observations`, `telemetry_frames`, `locations`, `health_samples`, `lifecycle_records` |
| Timelines, source progress and revisions | `collections`, `collection_memberships`, `collection_sources`, `collection_channels`, `collection_versions`, `collection_corrections`, `health_deletions`, `collection_changes` |
| Ownership, commands, receipts and deletion fences | `durable_records`, `counters` |
| Derived analysis | `derived_cache`, `distance_generations`, `distance_snapshots`, `distance_points`, `distance_health_inputs` |

Phone capture batches live/ride memberships together, targeting eight frames or one second of available execution. Destinations and timeline mapping stay fixed across retries; failures retain buffered frames. Lifecycle barriers drain admitted frames before changing boundaries or sealing a source. Abrupt termination can lose the uncommitted RAM tail; a suspended timer cannot promise a wall-clock flush.

Indexes serve catalog order, elapsed/UTC ranges, source/kind, membership, export order and revision. History loads metadata pages; summaries and exports read typed fields in 256-row pages at a fixed revision, releasing the executor between pages. Distance profiles advance bounded checkpoints or rebuild after corrections; exact boundaries use indexes. [Measurement rules](measurements.md) define the calculation, and [chart architecture](architecture.md#chart-queries-and-rendering) defines display reads. Admission constants live in `PowerLogStorageLimits`; queue limits are not total-memory guarantees.

## Ownership and finalization

`WorkoutEngine` chooses one owner: Watch when selected, phone otherwise. Durable command intent precedes native effects; receipts follow observed completion. Recovery reconciles uncertain operations with the existing session/workout before retrying, never creating a second Health workout to resolve an uncertain save.

Health/GPS choices freeze at Start. Absent Health choice defaults to saving; absent GPS choice follows outdoor mode. Explicit GPS choice is independent of indoor/outdoor mode. Source requirements reflect these choices: Watch off requires no Watch stream, GPS off no route.

With Watch and Health saving off, the phone uses `WorkoutLocalOwner` without Health authorization or an `HKWorkoutSession`. A selected Watch still uses HealthKit for sensors/background execution, with permissions requested there. Health saving off prevents app-added Health quantities, routes and workout creation; sensor reads drain before discarding the builder. Apple's independent Health records are unaffected. Saving locally with Health off ends as `notRequested`, never as Discard or a later Health save.

Original timestamps and paused observations remain intact. Active timing, Health insertion, distance and FIT respect pause intervals and stop cutoff. Interrupted phone rides close at the retained checkpoint; a durable stopped cutoff outranks older recovery state. Unobserved downtime cannot extend active time.

**Local retention, Health outcome and complete iPhone archive are separate.** Final export readiness requires owner completion and verification of the required sources. Explicitly unavailable sources can produce a partial ride. History reads have no lifecycle effects; recovery targets the chosen ride, including older history.

## Watch transfer

The phone produces CYC originals; the Watch produces Health/GPS/lifecycle records. CYC travels to Watch for Health insertion but is not echoed back. Watch originals transfer progressively during recording. Final Health extraction and corrections can arrive after Finish, so progressive transfer does not guarantee instant completion.

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

IndexedDB retains controller originals, lifecycle, chart summaries and distance profiles. The origin-wide `power-log-recording` Web Lock and a persisted owner token prevent competing writers. Other tabs can read history but cannot control a live writer. After its page exits, recovery marks the committed prefix interrupted rather than restarting capture. Storage failures stop admission and preserve that prefix.

Writes are bounded; elapsed indexes and multiresolution summaries serve queries without CSV round trips or whole-ride reads. Paused originals remain stored; active timing excludes pauses. Eight hours at 8 Hz is 230,400 controller frames, supported by this path subject to browser quota/eviction; import limits are not recording limits.

| Export | Contract |
| --- | --- |
| CSV | Browser controller originals, paged from storage, with a 128 MiB export cap that never truncates the recording. Imported CSV is temporary, unverified analysis and can be re-exported on either platform; chooser limit is 25 MiB, parser limits are in [protocol](protocol.md). |
| Original ZIP | iPhone metadata, JSONL originals and `CYCtelemetry.csv`; a portable export, not the active storage format. |
| FIT | iPhone bounded processing at a fixed revision: rider power, cadence, available Health/GPS, timing/laps and mapped summaries. Missing data is never fabricated; motor watts/temperature never become human power/ambient temperature. |

Browser rides export CSV only; Watch, Health, phone GPS, FIT and original ZIP are native capabilities. Backups/fixtures must include a consistent WAL state: stop the app before preloading a database and retain user-selected rides before resetting. [Testing](testing.md) covers software and physical acceptance separately.
