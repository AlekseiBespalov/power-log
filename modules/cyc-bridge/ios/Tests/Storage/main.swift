import Foundation
import Darwin
import SQLite3

func check(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
func rejects(_ body: () throws -> Void, _ message: String) {
  do { try body(); fatalError("Expected rejection: " + message) } catch {}
}
let fixedDate = Date(timeIntervalSince1970: 1_788_912_000)
func health(_ id: String, seconds: Double, value: Double = 100, eventID: String = UUID().uuidString.lowercased()) throws -> WorkoutEvent {
  try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: fixedDate.addingTimeInterval(seconds), elapsedSeconds: seconds,
                   payload: ["heartRateBpm": .number(value), "representation": .string("rawQuantity")], eventId: eventID)
}
if CommandLine.arguments.count > 2, CommandLine.arguments[1] == "--crash" {
  let archive = try WorkoutArchive(rootURL: URL(fileURLWithPath: CommandLine.arguments[2]))
  let id = CommandLine.arguments[3]
  archive.store.beforeCommitForTesting = { _exit(77) }
  try archive.append(health(id, seconds: 500))
  fatalError("fault hook not reached")
}
if CommandLine.arguments.count > 2, CommandLine.arguments[1] == "--stale-source" {
  let reopened = try WorkoutArchive(rootURL: URL(fileURLWithPath: CommandLine.arguments[2]))
  let stale = try JSONDecoder().decode(WorkoutSourceSeal.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[4])))
  let transfer = WorkoutTransferJournal(archive: reopened)
  try transfer.saveSource(id: CommandLine.arguments[3], source: stale)
  try transfer.saveSource(id: CommandLine.arguments[3], source: stale)
  check(try transfer.declaredSource(id: CommandLine.arguments[3], producer: stale.producer)?.count == 11, "stale retries after process reopen cannot replace current boundary")
  exit(0)
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-store-tests-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root)
let store = archive.store
let first = try archive.create(startedAt: fixedDate, indoor: true, watchEnabled: false)
let second = try archive.create(startedAt: fixedDate, indoor: true, watchEnabled: false)
let sameStore = try PowerLogStore.shared(databaseURL: store.databaseURL)
check(store === sameStore, "facades share exactly one connection")
let pragmas = try store.read { db in (try db.scalarInt("PRAGMA synchronous"), try db.rows("PRAGMA journal_mode", limit: 1).first?.string("journal_mode")) }
check(pragmas.0 == 2 && pragmas.1 == "wal", "WAL with FULL synchronous makes successful commits durable")
try store.checkpoint()
check(try store.read { try $0.scalarInt("PRAGMA synchronous") } == 2, "checkpoint does not weaken successful-commit durability")
let sample: [String: Any] = ["timestamp": "2026-09-09T00:00:00.123456789Z", "humanPowerW": Int64(9_007_199_254_740_993), "cadenceRpm": 81.25,
  "faultCode": Int64(65_535), "observationId": UUID().uuidString.lowercased(), "captureSessionID": UUID().uuidString.lowercased(),
  "observationSequence": "18446744073709551615", "clockEpoch": UUID().uuidString.lowercased(),
  "sourceElapsedSeconds": 500.125, "acquisitionMonotonic": 1_000.125, "elapsedSeconds": 500.125, "sequence": 20,
  "vendorUnsigned": UInt64.max]
let inserted = try store.transaction { _ in
  try store.appendTelemetry(sample, collectionID: first.id, elapsedSeconds: 0.125) + store.appendTelemetry(sample, collectionID: second.id, elapsedSeconds: 30.125)
}
check(inserted == 2, "two memberships accepted")
let counts = try store.read { db in (try db.scalarInt("SELECT count(*) FROM observations"), try db.scalarInt("SELECT count(*) FROM collection_memberships")) }
check(counts.0 == 1 && counts.1 == 2, "physical original stored once across memberships")
let original = try archive.pageEvents(id: first.id).first!.event
check(original.timestamp == sample["timestamp"] as? String, "submillisecond original timestamp preserved verbatim")
check(original.payload["humanPowerW"] == .integer(9_007_199_254_740_993), "wide numeric integer preserves above 2^53")
check(original.payload["vendorUnsigned"] == .unsigned(UInt64.max), "extension unsigned integer preserves full range")
check(original.elapsedSeconds == 0.125 && original.payload["elapsedSeconds"]?.number == 500.125, "source and collection elapsed remain distinct")
check(try store.read { db in try db.rows("SELECT typeof(humanPowerW) AS t FROM telemetry_frames", limit: 1).first?.string("t") } == "integer", "typed table preserves integer storage class")
let mappedRide = try archive.create(startedAt: fixedDate, indoor: true, watchEnabled: false)
var uncertainSample = sample; uncertainSample["timelineMappingUncertainty"] = "Recovered collection UTC anchor"
_ = try store.appendTelemetry(uncertainSample, collectionID: mappedRide.id, elapsedSeconds: 40.125)
check(try store.read { try $0.scalarInt("SELECT count(*) FROM observations") } == 1, "membership mapping uncertainty never changes physical identity digest")
check(try archive.pageEvents(id: mappedRide.id).first?.event.payload["timelineMappingUncertainty"]?.string == "Recovered collection UTC anchor", "collection mapping uncertainty survives original export decoding")
let replay = try store.appendTelemetry(sample, collectionID: first.id, elapsedSeconds: 0.125)
check(try replay == 0 && archive.metadata(id: first.id).eventCount == 1, "replay does not allocate membership sequence")
var conflict = sample; conflict["humanPowerW"] = 12
rejects({ _ = try store.appendTelemetry(conflict, collectionID: first.id, elapsedSeconds: 0.125) }, "changed physical content")
let secondHealth = try health(first.id, seconds: 2)
var badReplay = original; badReplay.payload["cadenceRpm"] = .number(55)
rejects({ _ = try archive.appendBatch([secondHealth, badReplay]) }, "batch rollback after first insert")
check(!(try archive.hasEvent(id: first.id, eventID: secondHealth.eventId)), "failed batch leaves no accepted prefix")
let countBeforeFault = try archive.metadata(id: first.id).eventCount
store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected disk full") }
rejects({ try archive.append(secondHealth) }, "failed FULL commit")
store.beforeCommitForTesting = nil
check(try archive.metadata(id: first.id).eventCount == countBeforeFault, "commit failure preserves catalog and observations together")
try archive.append(secondHealth)
let progress = try archive.sourceProgress(id: first.id, producer: "watch")
check(progress.count == 1 && progress.lastSequence == 1, "producer committed sequence independent of physical and other source sequences")
let originalRevision = try archive.revision(id: first.id)
var replacement = try health(first.id, seconds: 2, value: 123)
replacement.payload["supersedesEventId"] = .string(secondHealth.eventId)
try archive.append(replacement)
var oldSelected: [WorkoutEvent] = []
try archive.forEachEvent(id: first.id, revision: originalRevision, selectedOnly: true) { oldSelected.append($0) }
check(oldSelected.contains(where: { $0.eventId == secondHealth.eventId }), "historical revision sees original before correction")
var currentSelected: [WorkoutEvent] = []
try archive.forEachEvent(id: first.id, selectedOnly: true) { currentSelected.append($0) }
check(!currentSelected.contains(where: { $0.eventId == secondHealth.eventId }) && currentSelected.contains(where: { $0.eventId == replacement.eventId }), "current selected view applies indexed correction")
var tombstone = try health(first.id, seconds: 2)
tombstone.payload["supersedesEventId"] = .string(replacement.eventId); tombstone.payload["deleted"] = .bool(true)
try archive.append(tombstone)
currentSelected = []; try archive.forEachEvent(id: first.id, selectedOnly: true) { currentSelected.append($0) }
check(currentSelected.filter { $0.kind == "health" }.isEmpty, "tombstone retains original history and excludes selected value")
let beforeMetadata = try archive.revision(id: first.id)
_ = try archive.update(id: first.id, warnings: ["Later warning"], sealRevision: 1, verifiedSealRevision: 1, finalizationState: "complete")
check(try archive.metadata(id: first.id, atRevision: beforeMetadata).warnings.isEmpty, "revision-bound metadata history")
try archive.append(health(first.id, seconds: 4))
check(try archive.metadata(id: first.id).finalizationState == "pending", "late originals invalidate current verified finalization")
let location = try WorkoutEvent(workoutId: first.id, kind: "location", source: "phone", timestamp: fixedDate, payload: ["latitude": .number(41.7), "longitude": .number(44.8), "horizontalAccuracyM": .number(6)])
try archive.append(location)
check(try archive.pageEvents(id: first.id).first(where: { $0.event.eventId == location.eventId })?.event.elapsedSeconds == nil, "absent original elapsed remains absent")
check(try store.read { db in try db.rows("SELECT horizontalAccuracyM FROM locations", limit: 1).first?.double("horizontalAccuracyM") } == 6, "production GPS accuracy uses typed column")
check(try store.read { db in try db.scalarInt("SELECT count(*) FROM collection_memberships WHERE kind='health' AND raw_heart=1") } == 4, "rawQuantity HR has indexed precedence")
// Receipt and immutable original acceptance share one transaction.
let receiptEvent = try health(second.id, seconds: 10)
let maximumSequenceChunk = try WorkoutChunkCodec.encode(workoutID: second.id, producer: "watch", firstSequence: Int64.max, events: [receiptEvent])
check(maximumSequenceChunk.manifest.lastSequence == Int64.max, "one event at maximum sequence has no intermediate overflow")
check(try WorkoutCoding.encoder().encode(WorkoutChunkCodec.decode(maximumSequenceChunk)) == WorkoutCoding.encoder().encode([receiptEvent]), "maximum single-event boundary preserves canonical bytes")
store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected receipt commit failure") }
rejects({ try store.transaction { db in try archive.append(receiptEvent); try db.put(namespace: "test-receipt", key: "one", value: Data([1]), immutable: true) } }, "receipt transaction rollback")
store.beforeCommitForTesting = nil
check(!(try archive.hasEvent(id: second.id, eventID: receiptEvent.eventId)), "receipt cannot acknowledge rolled back event")
check(try store.read { try $0.get(namespace: "test-receipt", key: "one") } == nil, "receipt rolled back with event")
// Greater than one page, duplicate timestamps and lifecycle ordering exercise keyset continuation.
var batch: [WorkoutEvent] = []
for i in 0..<600 {
  batch.append(try health(second.id, seconds: Double(i / 2 + 20), value: Double(i)))
  if batch.count == 100 { try archive.appendBatch(batch); batch.removeAll(keepingCapacity: true) }
}
let chosen = try archive.revision(id: second.id)
var seen = Set<String>(); var lastTime = -Double.infinity
try archive.forEachEvent(id: second.id, revision: chosen, orderByTime: true) { event in
  let time = event.elapsedSeconds ?? 0
  check(time >= lastTime, "ordered page traversal")
  check(seen.insert(event.eventId).inserted, "keyset page has no duplicate")
  lastTime = time
  if seen.count == 100 { try archive.append(health(second.id, seconds: 999)) }
}
check(seen.count == 601, "concurrent append excluded by stable source revision")
rejects({ _ = try archive.pageEvents(id: second.id, limit: 513) }, "page row maximum")
rejects({ _ = try store.read { try $0.rows("SELECT id FROM collection_memberships", limit: 512) } }, "unbounded result fails explicitly")
check(try store.read { try $0.scalarInt("SELECT count(*) FROM collection_changes WHERE collection_id=?", [.text(second.id)]) } == 512, "change journal retention bound")
// Full dense frames invalidate their adjacent interpolation/gap interval; async changes are global.
let changeID = UUID().uuidString.lowercased()
try store.createCollection(id: changeID, kind: "live", startedAt: WorkoutCoding.timestamp(fixedDate))
for t in [0.0, 10.0, 5.0] {
  var wide: [String: Any] = Dictionary(uniqueKeysWithValues: PowerLogStore.telemetryColumns.map { ($0, 1.0 as Any) })
  wide["timestamp"] = WorkoutCoding.timestamp(fixedDate.addingTimeInterval(t))
  _ = try store.appendTelemetry(wide, collectionID: changeID, elapsedSeconds: t)
}
let change = try store.read { db in try db.rows("SELECT * FROM collection_changes WHERE collection_id=? ORDER BY revision DESC LIMIT 1", [.text(changeID)], limit: 1).first! }
check(change.string("kind") == "append" && change.int("min_us") == 0 && change.int("max_us") == 10_000_000, "late dense frame invalidation includes both neighbors")
// Async non-null metric edges are invalidated without rebuilding unrelated projections.
let edgeRide = try archive.create(startedAt: fixedDate, indoor: true, watchEnabled: true)
for t in [0.0, 10.0, 5.0] { try archive.append(health(edgeRide.id, seconds: t)) }
let asyncChange = try store.read { try $0.rows("SELECT * FROM collection_changes WHERE collection_id=? ORDER BY revision DESC LIMIT 1", [.text(edgeRide.id)], limit: 1).first! }
check(asyncChange.string("kind") == "append" && asyncChange.int("min_us") == 0 && asyncChange.int("max_us") == 10_000_000, "late async point invalidates both original neighbors")
rejects({ _ = try archive.appendBatch([health(edgeRide.id, seconds: 11), health(edgeRide.id, seconds: 12)], producer: "watch", firstSequence: Int64.max) }, "source sequence addition overflow rejected before mutation")
let sharedLiveID = UUID().uuidString.lowercased(), activeLiveID = UUID().uuidString.lowercased()
try store.createCollection(id: sharedLiveID, kind: "live", startedAt: WorkoutCoding.timestamp(fixedDate))
try store.createCollection(id: activeLiveID, kind: "live", startedAt: WorkoutCoding.timestamp(fixedDate))
_ = try store.appendTelemetry(sample, collectionID: sharedLiveID, elapsedSeconds: 7)
var cleanupPages = 0
while try store.pruneLivePage(keeping: activeLiveID, limit: 2) { cleanupPages += 1; check(cleanupPages <= 5, "bounded live cleanup makes progress") }
check(try store.collection(id: activeLiveID).string("kind") == "live", "current live collection retained")
check(try archive.pageEvents(id: first.id).first!.event.eventId == original.eventId, "saved workout membership protects shared physical original")
rejects({ _ = try store.collection(id: sharedLiveID) }, "old live catalog reclaimed")
// Kill after BEGIN plus accepted writes, before COMMIT; process reopen must ignore the entire tail.
let countBeforeCrash = try archive.metadata(id: first.id).eventCount
let process = Process(); process.executableURL = URL(fileURLWithPath: CommandLine.arguments[0]); process.arguments = ["--crash", root.path, first.id]
try process.run(); process.waitUntilExit()
check(process.terminationStatus == 77, "subprocess exits at pre-commit crash boundary")
check(try archive.metadata(id: first.id).eventCount == countBeforeCrash, "pre-commit process crash exposes no accepted tail")
// Authoritative start status may follow already committed originals from the same Watch ride.
let preparing = try archive.create(startedAt: fixedDate.addingTimeInterval(-30), indoor: false, watchEnabled: true)
_ = try archive.update(id: preparing.id, phase: "preparing")
let early = try WorkoutEvent(workoutId: preparing.id, kind: "lifecycle", source: "watch", timestamp: fixedDate, elapsedSeconds: 0, payload: ["action": .string("start")])
try archive.append(early)
let earlyWithoutElapsed = try WorkoutEvent(workoutId: preparing.id, kind: "health", source: "watch", timestamp: fixedDate.addingTimeInterval(1), payload: ["heartRateBpm": .number(100)])
try archive.append(earlyWithoutElapsed)
let originalDerivedTime = try store.read { try $0.rows("SELECT elapsed_seconds FROM collection_memberships WHERE collection_id=? AND event_id=?", [.text(preparing.id), .text(earlyWithoutElapsed.eventId)], limit: 1).first!.double("elapsed_seconds") }
let earlyRevision = try archive.revision(id: preparing.id)
let earlyBytes = try WorkoutCoding.encoder().encode(archive.pageEvents(id: preparing.id).first!.event)
_ = try archive.confirmStart(id: preparing.id, startedAt: fixedDate)
check(try archive.metadata(id: preparing.id).startedAt == WorkoutCoding.timestamp(fixedDate), "owner start confirms nonempty preparing collection")
check(try archive.metadata(id: preparing.id, atRevision: earlyRevision).startedAt == WorkoutCoding.timestamp(fixedDate.addingTimeInterval(-30)), "historical metadata remains revision-bound")
check(try WorkoutCoding.encoder().encode(archive.pageEvents(id: preparing.id).first!.event) == earlyBytes, "start confirmation never rebases admitted originals")
try archive.append(earlyWithoutElapsed)
check(try archive.metadata(id: preparing.id).eventCount == 2, "nil elapsed replay remains idempotent after catalog start confirmation")
check(try store.read { try $0.rows("SELECT elapsed_seconds FROM collection_memberships WHERE collection_id=? AND event_id=?", [.text(preparing.id), .text(earlyWithoutElapsed.eventId)], limit: 1).first!.double("elapsed_seconds") } == originalDerivedTime, "retry retains first-admission derived mapping")
var changedMappingPresence = earlyWithoutElapsed; changedMappingPresence.elapsedSeconds = originalDerivedTime
rejects({ try archive.append(changedMappingPresence) }, "replay cannot replace absent original elapsed with supplied elapsed")
_ = try archive.update(id: preparing.id, phase: "running")
rejects({ _ = try archive.confirmStart(id: preparing.id, startedAt: fixedDate.addingTimeInterval(3)) }, "active start cannot be rewritten")
// Two transports can deliver a lower source seal after the newer content is already verified.
let sealRide = try archive.create(startedAt: fixedDate, indoor: false, watchEnabled: true)
let journal = WorkoutTransferJournal(archive: archive)
for i in 1...10 { try archive.append(health(sealRide.id, seconds: Double(i))) }
let source10 = try journal.source(id: sealRide.id, producer: "watch")
try journal.saveSource(id: sealRide.id, source: source10)
try archive.append(health(sealRide.id, seconds: 11))
let source11 = try journal.source(id: sealRide.id, producer: "watch")
try journal.saveSource(id: sealRide.id, source: source11)
_ = try archive.finish(id: sealRide.id, endedAt: fixedDate.addingTimeInterval(12))
let seal = WorkoutSeal(workoutID: sealRide.id, sealRevision: 1, collectionRevision: try archive.revision(id: sealRide.id), ownerRevision: 1,
  stopCutoff: WorkoutCoding.timestamp(fixedDate.addingTimeInterval(12)), healthOutcome: "saved", requirements: [:], sources: [source11])
_ = try journal.accept(seal: seal)
check(try journal.verify(id: sealRide.id), "latest source verifies")
try journal.saveSource(id: sealRide.id, source: source10)
let sourcePath = root.appendingPathComponent("stale-source.json")
try WorkoutCoding.encoder().encode(source10).write(to: sourcePath)
let retryProcess = Process(); retryProcess.executableURL = URL(fileURLWithPath: CommandLine.arguments[0])
retryProcess.arguments = ["--stale-source", root.path, sealRide.id, sourcePath.path]
try retryProcess.run(); retryProcess.waitUntilExit()
check(retryProcess.terminationStatus == 0, "stale source retries remain idempotent across process restart")
check(try journal.declaredSource(id: sealRide.id, producer: "watch") == source11, "newest source remains the declaration")
check(try journal.verify(id: sealRide.id), "stale declaration cannot regress verified finality")
var changedSource = source11; changedSource.digest = String(repeating: "0", count: 64)
rejects({ try journal.saveSource(id: sealRide.id, source: changedSource) }, "same source boundary with changed digest")
var pendingSource = source11; pendingSource.outcome = "pending"
try journal.saveSource(id: sealRide.id, source: pendingSource)
check(try journal.declaredSource(id: sealRide.id, producer: "watch") == source11, "stale pending outcome cannot regress sealed content")
var malformedSource = source11; malformedSource.lastSequence = 12
rejects({ try journal.saveSource(id: sealRide.id, source: malformedSource) }, "inconsistent source boundary")
try store.checkpoint()
let integrity = try store.read { try $0.rows("PRAGMA integrity_check", limit: 1).first?.string("integrity_check") }
check(integrity == "ok", "SQLite integrity after faults and WAL recovery")
func previousBuildFixture(_ directory: URL) throws {
  try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  var handle: OpaquePointer?
  check(sqlite3_open(PowerLogStore.databaseURL(forRoot: directory).path, &handle) == SQLITE_OK, "previous-build fixture opens")
  check(sqlite3_exec(handle, "PRAGMA user_version=1", nil, nil, nil) == SQLITE_OK, "previous-build fixture carries schema version 1")
  sqlite3_close(handle)
}
let freshRoot = root.appendingPathComponent("fresh-schema")
do {
  let fresh = try WorkoutArchive(rootURL: freshRoot)
  check(try fresh.store.read { try $0.scalarInt("PRAGMA user_version") } == Int64(PowerLogStore.schemaVersion), "a new database opens at the single initial schema version")
  check(try fresh.store.read { try $0.rows("PRAGMA integrity_check", limit: 1).first?.string("integrity_check") } == "ok", "fresh database integrity")
}
let previousRoot = root.appendingPathComponent("previous-build-schema")
try previousBuildFixture(previousRoot)
do { _ = try WorkoutArchive(rootURL: previousRoot); fatalError("the previous build's database must be refused") }
catch { check("\(error)".contains("reinstall"), "a database stamped by the previous build is refused with a reinstall message") }
print("SQLite storage tests passed: identity, exact values, atomic replay/receipt, revision/correction pages, start ordering, monotonic source seals across restart, bounds, crash recovery, single schema version; \(store.runtimeVersion)")
