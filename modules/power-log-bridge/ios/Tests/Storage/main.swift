import Foundation
import Darwin
import SQLite3
import PowerLogCore

var assertions = 0
func check(_ value: Bool, _ message: String) {
  assertions += 1
  if !value { fatalError(message) }
}
func rejects(_ body: () throws -> Void, _ message: String) {
  do {
    try body()
    fatalError("Expected rejection: " + message)
  } catch { assertions += 1 }
}
let fixedDate = Date(timeIntervalSince1970: 1_788_912_000)
func health(_ id: String, seconds: Double, value: Double = 100, eventID: String = UUID().uuidString.lowercased()) throws
  -> WorkoutEvent
{
  try WorkoutEvent(
    workoutId: id, kind: "health", source: "watch", timestamp: fixedDate.addingTimeInterval(seconds),
    elapsedSeconds: seconds,
    payload: ["heartRateBpm": .number(value), "representation": .string("rawQuantity")], eventId: eventID)
}
if CommandLine.arguments.count > 2, CommandLine.arguments[1] == "--crash" {
  let archive = try WorkoutArchive(rootURL: URL(fileURLWithPath: CommandLine.arguments[2]))
  let id = CommandLine.arguments[3]
  archive.store.beforeCommitForTesting = { _exit(77) }
  try archive.append(health(id, seconds: 500))
  fatalError("fault hook not reached")
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-store-tests-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root)
let store = archive.store
let first = try archive.create(
  startedAt: fixedDate, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
let second = try archive.create(
  startedAt: fixedDate, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
for indoor in [false, true] {
  let metadata = WorkoutMetadata(
    id: UUID().uuidString.lowercased(), startedAt: WorkoutCoding.timestamp(fixedDate), indoor: indoor,
    watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
  let encoded = try WorkoutCoding.encoder().encode(metadata)
  for flag in ["saveToHealth", "recordGPS"] {
    var fields = try JSONSerialization.jsonObject(with: encoded) as! [String: Any]
    fields.removeValue(forKey: flag)
    rejects(
      { _ = try JSONDecoder().decode(WorkoutMetadata.self, from: JSONSerialization.data(withJSONObject: fields)) },
      "persisted recording options are required")
    fields[flag] = NSNull()
    rejects(
      { _ = try JSONDecoder().decode(WorkoutMetadata.self, from: JSONSerialization.data(withJSONObject: fields)) },
      "persisted recording options cannot be null")
  }
}
let sameStore = try PowerLogStore.shared(databaseURL: store.databaseURL)
check(store === sameStore, "facades share exactly one connection")
let pragmas = try store.read { db in
  (try db.scalarInt("PRAGMA synchronous"), try db.rows("PRAGMA journal_mode", limit: 1).first?.string("journal_mode"))
}
check(pragmas.0 == 2 && pragmas.1 == "wal", "WAL with FULL synchronous makes successful commits durable")
try store.checkpoint()
check(
  try store.read { try $0.scalarInt("PRAGMA synchronous") } == 2,
  "checkpoint does not weaken successful-commit durability")
let sample: [String: Any] = [
  "timestamp": "2026-09-09T00:00:00.123456789Z", "humanPowerW": Int64(9_007_199_254_740_991), "cadenceRpm": 81.25,
  "faultCode": Int64(65_535), "observationId": UUID().uuidString.lowercased(),
  "captureSessionID": UUID().uuidString.lowercased(),
  "observationSequence": "18446744073709551615", "clockEpoch": UUID().uuidString.lowercased(),
  "sourceElapsedSeconds": 500.125, "acquisitionMonotonic": 1_000.125, "elapsedSeconds": 500.125, "sequence": "20",
  "vendorUnsigned": UInt64(9_007_199_254_740_991),
]
let inserted = try store.transaction { _ in
  try store.appendTelemetry(sample, collectionID: first.id, elapsedSeconds: 0.125)
    + store.appendTelemetry(sample, collectionID: second.id, elapsedSeconds: 30.125)
}
check(inserted == 2, "two memberships accepted")
let counts = try store.read { db in
  (
    try db.scalarInt("SELECT count(*) FROM observations"),
    try db.scalarInt("SELECT count(*) FROM collection_memberships")
  )
}
check(counts.0 == 1 && counts.1 == 2, "physical original stored once across memberships")
let original = try archive.pageEvents(id: first.id).first!.event
check(original.timestamp == sample["timestamp"] as? String, "submillisecond original timestamp preserved verbatim")
check(original.payload["humanPowerW"] == .integer(9_007_199_254_740_991), "safe numeric integer preserves exact value")
check(
  original.payload["vendorUnsigned"]?.integer == 9_007_199_254_740_991, "safe extension integer preserves exact value")
check(original.payload["observationSequence"]?.string == String(UInt64.max), "declared counter preserves full range")
for (field, value) in [("humanPowerW", Int64(9_007_199_254_740_993) as Any), ("vendorUnsigned", UInt64.max as Any)] {
  var invalid = sample
  invalid[field] = value
  rejects(
    { _ = try store.appendTelemetry(invalid, collectionID: first.id, elapsedSeconds: 1) },
    "oversized numeric extension is rejected: " + field)
}
check(
  original.elapsedSeconds == 0.125 && original.payload["elapsedSeconds"]?.number == 500.125,
  "source and collection elapsed remain distinct")
check(
  try store.read { db in
    try db.rows("SELECT typeof(humanPowerW) AS t FROM telemetry_frames", limit: 1).first?.string("t")
  } == "integer", "typed table preserves integer storage class")
let mappedRide = try archive.create(
  startedAt: fixedDate, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
var remappedSample = sample
remappedSample["sequence"] = 21
_ = try store.appendTelemetry(remappedSample, collectionID: mappedRide.id, elapsedSeconds: 40.125)
check(
  try store.read { try $0.scalarInt("SELECT count(*) FROM observations") } == 1,
  "membership mapping never changes physical identity digest")
check(
  try archive.pageEvents(id: mappedRide.id).first?.event.payload["sequence"]?.string == "21",
  "collection mapping survives original export decoding")
let replay = try store.appendTelemetry(sample, collectionID: first.id, elapsedSeconds: 0.125)
check(try replay == 0 && archive.metadata(id: first.id).eventCount == 1, "replay does not allocate membership sequence")
var conflict = sample
conflict["humanPowerW"] = 12
rejects(
  { _ = try store.appendTelemetry(conflict, collectionID: first.id, elapsedSeconds: 0.125) }, "changed physical content"
)
let secondHealth = try health(first.id, seconds: 2)
var badReplay = original
badReplay.payload["cadenceRpm"] = .number(55)
rejects({ _ = try archive.appendBatch([secondHealth, badReplay]) }, "batch rollback after first insert")
check(!(try archive.hasEvent(id: first.id, eventID: secondHealth.eventId)), "failed batch leaves no accepted prefix")
let countBeforeFault = try archive.metadata(id: first.id).eventCount
store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected disk full") }
rejects({ try archive.append(secondHealth) }, "failed FULL commit")
store.beforeCommitForTesting = nil
check(
  try archive.metadata(id: first.id).eventCount == countBeforeFault,
  "commit failure preserves catalog and observations together")
rejects(
  {
    try store.transaction { db in
      try db.put(namespace: "nested-failure", key: "outer", value: Data([1]))
      do {
        try store.transaction { nested in
          try nested.put(namespace: "nested-failure", key: "inner", value: Data([2]))
          throw PowerLogStorageError.invalid("injected nested failure")
        }
      } catch {}
    }
  }, "caught nested failure still rolls back the outer transaction")
check(
  try store.read { try $0.get(namespace: "nested-failure", key: "outer") } == nil,
  "nested failure preserves outer atomicity")
check(
  try store.read { try $0.get(namespace: "nested-failure", key: "inner") } == nil,
  "nested failure leaves no inner write")
rejects({ _ = try archive.update(id: first.id, phase: "awaitingWatchSync") }, "unsupported archive phase is rejected")
rejects(
  { _ = try archive.finish(id: first.id, endedAt: fixedDate, finalPhase: "awaitingWatchSync") },
  "unsupported final archive phase is rejected")
try archive.append(secondHealth)
let progress = try archive.sourceProgress(id: first.id, producer: "watch")
check(
  progress.count == 1 && progress.lastSequence == 1,
  "producer committed sequence independent of physical and other source sequences")
let originalRevision = try archive.revision(id: first.id)
var replacement = try health(first.id, seconds: 2, value: 123)
replacement.payload["supersedesEventId"] = .string(secondHealth.eventId)
try archive.append(replacement)
var oldSelected: [WorkoutEvent] = []
try archive.forEachEvent(id: first.id, revision: originalRevision, selectedOnly: true) { oldSelected.append($0) }
check(
  oldSelected.contains(where: { $0.eventId == secondHealth.eventId }),
  "historical revision sees original before correction")
var currentSelected: [WorkoutEvent] = []
try archive.forEachEvent(id: first.id, selectedOnly: true) { currentSelected.append($0) }
check(
  !currentSelected.contains(where: { $0.eventId == secondHealth.eventId })
    && currentSelected.contains(where: { $0.eventId == replacement.eventId }),
  "current selected view applies indexed correction")
var tombstone = try health(first.id, seconds: 2)
tombstone.payload["supersedesEventId"] = .string(replacement.eventId)
tombstone.payload["deleted"] = .bool(true)
try archive.append(tombstone)
currentSelected = []
try archive.forEachEvent(id: first.id, selectedOnly: true) { currentSelected.append($0) }
check(
  currentSelected.filter { $0.kind == "health" }.isEmpty,
  "tombstone retains original history and excludes selected value")
let beforeMetadata = try archive.revision(id: first.id)
try archive.update(id: first.id, healthKitState: "unknown")
check(
  try archive.metadata(id: first.id, atRevision: beforeMetadata).healthKitState != "unknown"
    && archive.metadata(id: first.id).healthKitState == "unknown", "revision-bound Health metadata")
try archive.append(health(first.id, seconds: 4))
let location = try WorkoutEvent(
  workoutId: first.id, kind: "location", source: "phone", timestamp: fixedDate, elapsedSeconds: 0,
  payload: ["latitude": .number(41.7), "longitude": .number(44.8), "horizontalAccuracyM": .number(6)])
var missingLocationTiming = location
missingLocationTiming.elapsedSeconds = nil
rejects({ try archive.append(missingLocationTiming) }, "GPS without measured elapsed cannot enter UTC fallback")
try archive.append(location)
check(
  try archive.pageEvents(id: first.id).first(where: { $0.event.eventId == location.eventId })?.event.elapsedSeconds
    == 0, "GPS original elapsed survives storage")
check(
  try store.read { db in
    try db.rows("SELECT horizontalAccuracyM FROM locations", limit: 1).first?.double("horizontalAccuracyM")
  } == 6, "production GPS accuracy uses typed column")
check(
  try store.read { db in
    try db.scalarInt("SELECT count(*) FROM collection_memberships WHERE kind='health' AND raw_heart=1")
  } == 4, "rawQuantity HR has indexed precedence")
let receiptEvent = try health(second.id, seconds: 10)
store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected receipt commit failure") }
rejects(
  {
    try store.transaction { db in
      try archive.append(receiptEvent)
      try db.put(namespace: "test-receipt", key: "one", value: Data([1]), immutable: true)
    }
  }, "receipt transaction rollback")
store.beforeCommitForTesting = nil
check(
  !(try archive.hasEvent(id: second.id, eventID: receiptEvent.eventId)), "receipt cannot acknowledge rolled back event")
check(try store.read { try $0.get(namespace: "test-receipt", key: "one") } == nil, "receipt rolled back with event")
var batch: [WorkoutEvent] = []
for i in 0..<600 {
  batch.append(try health(second.id, seconds: Double(i / 2 + 20), value: Double(i)))
  if batch.count == 100 {
    try archive.appendBatch(batch)
    batch.removeAll(keepingCapacity: true)
  }
}
let chosen = try archive.revision(id: second.id)
var seen = Set<String>()
var lastTime = -Double.infinity
try archive.forEachEvent(id: second.id, revision: chosen, orderByTime: true) { event in
  let time = event.elapsedSeconds ?? 0
  check(time >= lastTime, "ordered page traversal")
  check(seen.insert(event.eventId).inserted, "keyset page has no duplicate")
  lastTime = time
  if seen.count == 100 { try archive.append(health(second.id, seconds: 999)) }
}
check(seen.count == 601, "concurrent append excluded by stable source revision")
rejects({ _ = try archive.pageEvents(id: second.id, limit: 513) }, "page row maximum")
rejects(
  { _ = try store.read { try $0.rows("SELECT id FROM collection_memberships", limit: 512) } },
  "unbounded result fails explicitly")
check(
  try store.read {
    try $0.scalarInt("SELECT count(*) FROM collection_changes WHERE collection_id=?", [.text(second.id)])
  } == 512, "change journal retention bound")
let changeID = UUID().uuidString.lowercased()
try store.createCollection(id: changeID, kind: "live", startedAt: WorkoutCoding.timestamp(fixedDate))
for t in [0.0, 10.0, 5.0] {
  var wide: [String: Any] = Dictionary(uniqueKeysWithValues: PowerLogStore.telemetryColumns.map { ($0, 1.0 as Any) })
  wide["timestamp"] = WorkoutCoding.timestamp(fixedDate.addingTimeInterval(t))
  _ = try store.appendTelemetry(wide, collectionID: changeID, elapsedSeconds: t)
}
let change = try store.read { db in
  try db.rows(
    "SELECT * FROM collection_changes WHERE collection_id=? ORDER BY revision DESC LIMIT 1", [.text(changeID)], limit: 1
  ).first!
}
check(
  change.string("kind") == "append" && change.int("min_us") == 0 && change.int("max_us") == 10_000_000,
  "late dense frame invalidation includes both neighbors")
let edgeRide = try archive.create(
  startedAt: fixedDate, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false, sampleHz: 4)
for t in [0.0, 10.0, 5.0] { try archive.append(health(edgeRide.id, seconds: t)) }
let asyncChange = try store.read {
  try $0.rows(
    "SELECT * FROM collection_changes WHERE collection_id=? ORDER BY revision DESC LIMIT 1", [.text(edgeRide.id)],
    limit: 1
  ).first!
}
check(
  asyncChange.string("kind") == "append" && asyncChange.int("min_us") == 0 && asyncChange.int("max_us") == 10_000_000,
  "late async point invalidates both original neighbors")
func changeMarks(_ id: String) throws -> [String: Int64] {
  try store.read { db in
    Dictionary(
      uniqueKeysWithValues: try db.rows(
        "SELECT metric,kind,revision FROM collection_change_marks WHERE collection_id=?", [.text(id)], limit: 512
      ).map { ($0.string("kind")! + ":" + $0.string("metric")!, $0.int("revision")!) })
  }
}
func lifecycle(_ id: String, _ action: String, seconds: Double) throws {
  try archive.append(
    WorkoutEvent(
      workoutId: id, kind: "lifecycle", source: "phone", timestamp: fixedDate.addingTimeInterval(seconds),
      elapsedSeconds: seconds, payload: ["action": .string(action)]))
}
let markedRide = try archive.create(
  startedAt: fixedDate, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false, sampleHz: 4)
try archive.append(health(markedRide.id, seconds: 1))
let introducedRevision = try archive.revision(id: markedRide.id)
try archive.append(health(markedRide.id, seconds: 2))
check(
  try changeMarks(markedRide.id) == ["semantics:heartRateBpm": introducedRevision],
  "a new channel marks its metric and an ordinary append leaves no mark")
try lifecycle(markedRide.id, "start", seconds: 0)
let startRevision = try archive.revision(id: markedRide.id)
_ = try archive.update(id: markedRide.id, phase: "running")
let metadataRevision = try archive.revision(id: markedRide.id)
_ = try archive.update(
  id: markedRide.id,
  ownerTiming: WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(fixedDate.addingTimeInterval(5)), elapsedSeconds: 5, timerSeconds: 5),
  elapsedSeconds: 5, onlyIfChanged: true)
let timingRevision = try archive.revision(id: markedRide.id)
check(
  try changeMarks(markedRide.id) == [
    "semantics:heartRateBpm": introducedRevision, "semantics:": startRevision, "metadata:": metadataRevision,
    "timing:": timingRevision,
  ], "changes that list no metric mark the all-metrics sentinel of their kind")
for offset in stride(from: 0, to: 600, by: 200) {
  try archive.appendBatch((offset..<(offset + 200)).map { try health(markedRide.id, seconds: 10 + Double($0) / 4) })
}
try lifecycle(markedRide.id, "pause", seconds: 200)
let pauseRevision = try archive.revision(id: markedRide.id)
check(
  try store.read {
    try $0.scalarInt("SELECT min(revision) FROM collection_changes WHERE collection_id=?", [.text(markedRide.id)])
  }! > timingRevision, "the change journal no longer holds the earlier changes")
check(
  try changeMarks(markedRide.id) == [
    "semantics:heartRateBpm": introducedRevision, "semantics:": pauseRevision, "metadata:": metadataRevision,
    "timing:": timingRevision,
  ], "marks keep the newest revision of each metric and kind beyond the change journal")
let prunedLiveID = UUID().uuidString.lowercased()
try store.createCollection(id: prunedLiveID, kind: "live", startedAt: WorkoutCoding.timestamp(fixedDate))
for t in [0.0, 1.0, 2.0] {
  var wide: [String: Any] = Dictionary(uniqueKeysWithValues: PowerLogStore.telemetryColumns.map { ($0, 2.0 as Any) })
  wide["timestamp"] = WorkoutCoding.timestamp(fixedDate.addingTimeInterval(t))
  _ = try store.appendTelemetry(wide, collectionID: prunedLiveID, elapsedSeconds: t)
}
try store.prunePreview(id: prunedLiveID, retaining: 1)
let pruneRevision = try store.collection(id: prunedLiveID).int("revision")!
let pruneMarks = try changeMarks(prunedLiveID)
check(
  PowerLogStore.telemetryColumns.allSatisfy { pruneMarks["telemetry:" + $0] == pruneRevision },
  "a preview prune marks every telemetry column")
rejects(
  {
    _ = try archive.appendBatch(
      [health(edgeRide.id, seconds: 11), health(edgeRide.id, seconds: 12)], producer: "watch", firstSequence: Int64.max)
  }, "source sequence addition overflow rejected before mutation")
let sharedLiveID = UUID().uuidString.lowercased()
let activeLiveID = UUID().uuidString.lowercased()
try store.createCollection(id: sharedLiveID, kind: "live", startedAt: WorkoutCoding.timestamp(fixedDate))
try store.createCollection(id: activeLiveID, kind: "live", startedAt: WorkoutCoding.timestamp(fixedDate))
_ = try store.appendTelemetry(sample, collectionID: sharedLiveID, elapsedSeconds: 7)
check(
  try [changeID, sharedLiveID, prunedLiveID].allSatisfy { try !changeMarks($0).isEmpty },
  "stale live collections hold change marks before cleanup")
var cleanupPages = 0
while try store.pruneLivePage(keeping: activeLiveID, limit: 2) {
  cleanupPages += 1
  check(cleanupPages <= 5, "bounded live cleanup makes progress")
}
check(try store.collection(id: activeLiveID).string("kind") == "live", "current live collection retained")
check(
  try archive.pageEvents(id: first.id).first!.event.eventId == original.eventId,
  "saved workout membership protects shared physical original")
rejects({ _ = try store.collection(id: sharedLiveID) }, "old live catalog reclaimed")
check(
  try [changeID, sharedLiveID, prunedLiveID].allSatisfy { try changeMarks($0).isEmpty },
  "live cleanup removes the change marks of every reclaimed collection")
_ = try store.markWorkoutDeleted(id: markedRide.id)
var deletionPages = 0
while try !archive.cleanupDeletedWorkoutPage(id: markedRide.id) {
  deletionPages += 1
  check(deletionPages < 1_000, "bounded ride deletion makes progress")
}
check(
  try store.read {
    (
      try $0.scalarInt("SELECT count(*) FROM collections WHERE id=?", [.text(markedRide.id)]),
      try $0.scalarInt("SELECT count(*) FROM collection_change_marks WHERE collection_id=?", [.text(markedRide.id)])
    )
  } == (0, 0), "ride deletion removes the change marks with the collection")
let correctedRide = try archive.create(
  startedAt: fixedDate, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false, sampleHz: 4)
func frame(_ seconds: Double, _ extra: [String: WorkoutJSON] = [:]) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = ["humanPowerW": .number(200), "cadenceRpm": .number(80)]
  payload.merge(extra) { _, new in new }
  return try WorkoutEvent(
    workoutId: correctedRide.id, kind: "telemetry", source: "controller",
    timestamp: fixedDate.addingTimeInterval(seconds), elapsedSeconds: seconds, payload: payload)
}
func latestChange(_ id: String) throws -> (revision: Int64, metrics: [String]) {
  try store.read { db in
    let row = try db.rows(
      "SELECT revision,metrics FROM collection_changes WHERE collection_id=? ORDER BY revision DESC LIMIT 1",
      [.text(id)], limit: 1
    ).first!
    return (row.int("revision")!, try JSONDecoder().decode([String].self, from: row.data("metrics")!))
  }
}
let withVoltage = try frame(1, ["batteryVoltageV": .number(52)])
try archive.append(withVoltage)
try archive.append(frame(2, ["supersedesEventId": .string(withVoltage.eventId)]))
var correction = try latestChange(correctedRide.id)
var correctionMarks = try changeMarks(correctedRide.id)
check(
  correction.metrics == ["batteryVoltageV", "cadenceRpm", "humanPowerW"]
    && correctionMarks["semantics:batteryVoltageV"] == correction.revision && correctionMarks["semantics:"] == nil,
  "a correction marks the metrics of the original it replaces as well as its own")
let deletedVoltage = try frame(3, ["batteryVoltageV": .number(53)])
try archive.append(deletedVoltage)
try archive.append(frame(4, ["supersedesEventId": .string(deletedVoltage.eventId), "deleted": .bool(true)]))
correction = try latestChange(correctedRide.id)
check(
  try correction.metrics == ["batteryVoltageV", "cadenceRpm", "humanPowerW"]
    && changeMarks(correctedRide.id)["semantics:batteryVoltageV"] == correction.revision,
  "a deletion marks the metrics of the original it deletes")
let deletedHeart = try health(correctedRide.id, seconds: 5)
try archive.append(deletedHeart)
let marksBeforeTombstone = try changeMarks(correctedRide.id)
try archive.append(
  WorkoutHealthObservation.tombstone(
    id: correctedRide.id, source: "watch", parentID: deletedHeart.eventId,
    observedAt: fixedDate.addingTimeInterval(6), elapsed: 6))
correction = try latestChange(correctedRide.id)
correctionMarks = try changeMarks(correctedRide.id)
check(
  correction.metrics == ["heartRateBpm"] && correctionMarks["semantics:heartRateBpm"] == correction.revision
    && correctionMarks.filter { $0.key != "semantics:heartRateBpm" }
      == marksBeforeTombstone.filter { $0.key != "semantics:heartRateBpm" },
  "a Health tombstone marks only the metric of the sample it deletes")
let deletedParent = UUID().uuidString.lowercased()
var seriesMember = try health(correctedRide.id, seconds: 7)
seriesMember.payload["sampleUUID"] = .string(deletedParent)
try archive.append(seriesMember)
try archive.append(
  WorkoutEvent(
    workoutId: correctedRide.id, kind: "health", source: "watch", timestamp: fixedDate.addingTimeInterval(8),
    elapsedSeconds: 8,
    payload: [
      "sampleUUID": .string(deletedParent), "deleted": .bool(true), "representation": .string("deletedSample"),
    ]))
check(
  try latestChange(correctedRide.id).metrics == ["heartRateBpm"],
  "a Health deletion by sample identifier marks the metric of the samples it deletes")
try archive.append(frame(9, ["supersedesEventId": .string(UUID().uuidString.lowercased())]))
correction = try latestChange(correctedRide.id)
check(
  try correction.metrics.isEmpty && changeMarks(correctedRide.id)["semantics:"] == correction.revision,
  "a correction whose original is absent marks every metric")
let replacedPause = try WorkoutEvent(
  workoutId: correctedRide.id, kind: "lifecycle", source: "phone", timestamp: fixedDate.addingTimeInterval(10),
  elapsedSeconds: 10, payload: ["action": .string("pause")])
try archive.append(replacedPause)
try archive.append(frame(11, ["supersedesEventId": .string(replacedPause.eventId)]))
correction = try latestChange(correctedRide.id)
check(
  try correction.metrics.isEmpty && changeMarks(correctedRide.id)["semantics:"] == correction.revision,
  "a correction that replaces a lifecycle record marks every metric")
let countBeforeCrash = try archive.metadata(id: first.id).eventCount
let process = Process()
process.executableURL = URL(fileURLWithPath: CommandLine.arguments[0])
process.arguments = ["--crash", root.path, first.id]
try process.run()
process.waitUntilExit()
check(process.terminationStatus == 77, "subprocess exits at pre-commit crash boundary")
check(
  try archive.metadata(id: first.id).eventCount == countBeforeCrash, "pre-commit process crash exposes no accepted tail"
)
let preparing = try archive.create(
  startedAt: fixedDate.addingTimeInterval(-30), indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true,
  sampleHz: 4)
_ = try archive.update(id: preparing.id, phase: "preparing")
let early = try WorkoutEvent(
  workoutId: preparing.id, kind: "lifecycle", source: "watch", timestamp: fixedDate, elapsedSeconds: 0,
  payload: ["action": .string("start")])
try archive.append(early)
let earlyWithoutElapsed = try WorkoutEvent(
  workoutId: preparing.id, kind: "health", source: "watch", timestamp: fixedDate.addingTimeInterval(1),
  payload: ["heartRateBpm": .number(100)])
try archive.append(earlyWithoutElapsed)
let originalDerivedTime = try store.read {
  try $0.rows(
    "SELECT elapsed_seconds FROM collection_memberships WHERE collection_id=? AND event_id=?",
    [.text(preparing.id), .text(earlyWithoutElapsed.eventId)], limit: 1
  ).first!.double("elapsed_seconds")
}
let earlyRevision = try archive.revision(id: preparing.id)
let earlyBytes = try WorkoutCoding.encoder().encode(archive.pageEvents(id: preparing.id).first!.event)
_ = try archive.confirmStart(id: preparing.id, startedAt: fixedDate)
check(
  try archive.metadata(id: preparing.id).startedAt == WorkoutCoding.timestamp(fixedDate),
  "owner start confirms nonempty preparing collection")
check(
  try archive.metadata(id: preparing.id, atRevision: earlyRevision).startedAt
    == WorkoutCoding.timestamp(fixedDate.addingTimeInterval(-30)), "historical metadata remains revision-bound")
check(
  try WorkoutCoding.encoder().encode(archive.pageEvents(id: preparing.id).first!.event) == earlyBytes,
  "start confirmation never rebases admitted originals")
try archive.append(earlyWithoutElapsed)
check(
  try archive.metadata(id: preparing.id).eventCount == 2,
  "nil elapsed replay remains idempotent after catalog start confirmation")
check(
  try store.read {
    try $0.rows(
      "SELECT elapsed_seconds FROM collection_memberships WHERE collection_id=? AND event_id=?",
      [.text(preparing.id), .text(earlyWithoutElapsed.eventId)], limit: 1
    ).first!.double("elapsed_seconds")
  } == originalDerivedTime, "retry retains first-admission derived mapping")
var changedMappingPresence = earlyWithoutElapsed
changedMappingPresence.elapsedSeconds = originalDerivedTime
rejects(
  { try archive.append(changedMappingPresence) }, "replay cannot replace absent original elapsed with supplied elapsed")
_ = try archive.update(id: preparing.id, phase: "running")
rejects(
  { _ = try archive.confirmStart(id: preparing.id, startedAt: fixedDate.addingTimeInterval(3)) },
  "active start cannot be rewritten")
try store.checkpoint()
let integrity = try store.read { try $0.rows("PRAGMA integrity_check", limit: 1).first?.string("integrity_check") }
check(integrity == "ok", "SQLite integrity after faults and WAL recovery")
func unsupportedVersionFixture(_ directory: URL, version: Int) throws {
  try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  var handle: OpaquePointer?
  check(
    sqlite3_open(PowerLogStore.databaseURL(forRoot: directory).path, &handle) == SQLITE_OK,
    "unsupported-version fixture opens")
  defer { sqlite3_close(handle) }
  check(
    sqlite3_exec(
      handle,
      "PRAGMA user_version=\(version); CREATE TABLE retained(value TEXT); INSERT INTO retained VALUES('ride data')",
      nil, nil, nil) == SQLITE_OK, "fixture contains unsupported-version data")
}
let freshRoot = root.appendingPathComponent("fresh-schema")
do {
  let fresh = try WorkoutArchive(rootURL: freshRoot)
  check(
    try fresh.store.read { try $0.scalarInt("PRAGMA user_version") } == Int64(PowerLogStore.schemaVersion),
    "a new database opens at the single initial schema version")
  check(
    try fresh.store.read { try $0.rows("PRAGMA integrity_check", limit: 1).first?.string("integrity_check") } == "ok",
    "fresh database integrity")
}
for version in [PowerLogStore.schemaVersion - 1, PowerLogStore.schemaVersion + 1] {
  let incompatibleRoot = root.appendingPathComponent("unsupported-schema-\(version)")
  try unsupportedVersionFixture(incompatibleRoot, version: version)
  let database = PowerLogStore.databaseURL(forRoot: incompatibleRoot)
  let original = try Data(contentsOf: database)
  do {
    _ = try WorkoutArchive(rootURL: incompatibleRoot)
    fatalError("unsupported databases must be refused")
  } catch {
    check(
      error.localizedDescription
        == (version < PowerLogStore.schemaVersion
          ? "Power Log can't open rides saved by an earlier version. Reinstall the app to start over."
          : "Update Power Log to open your rides"),
      "unsupported database message matches the version direction")
  }
  check(try Data(contentsOf: database) == original, "refusal leaves database contents and journal mode unchanged")
  for suffix in ["-wal", "-shm"] {
    check(
      !FileManager.default.fileExists(atPath: database.path + suffix),
      "refusal never enables WAL on an unsupported database")
  }
}
func rejectsRevision(_ body: () throws -> Void, _ message: String) {
  do {
    try body()
    fatalError("Expected revision rejection: " + message)
  } catch {
    check(error is PowerLogStorageError, message + " uses the storage failure path")
  }
}
let boundedArchive = try WorkoutArchive(rootURL: root.appendingPathComponent("revision-bound"))
let boundedStore = boundedArchive.store
let maximumRevision = PowerLogStorageLimits.maximumRevision
check(maximumRevision == 9_007_199_254_740_991, "revision bound matches the exact JavaScript integer range")
for append in [false, true] {
  let ride = try boundedArchive.create(
    startedAt: fixedDate, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 4)
  _ = try boundedStore.transaction { db in
    try db.execute("UPDATE collections SET revision=? WHERE id=?", [.integer(maximumRevision - 1), .text(ride.id)])
  }
  if append {
    try boundedArchive.append(health(ride.id, seconds: 1))
  } else {
    try boundedArchive.update(id: ride.id, phase: "running")
  }
  check(try boundedArchive.revision(id: ride.id) == maximumRevision, "last exact collection revision commits")
  let before = try boundedArchive.metadata(id: ride.id)
  let originals = try boundedStore.read { try $0.scalarInt("SELECT count(*) FROM observations") }
  rejectsRevision(
    {
      if append {
        try boundedArchive.append(health(ride.id, seconds: 2))
      } else {
        try boundedArchive.update(id: ride.id, phase: "paused")
      }
    }, "collection revision cannot advance beyond the bound")
  let after = try boundedArchive.metadata(id: ride.id)
  check(
    after.collectionRevision == before.collectionRevision && after.eventCount == before.eventCount
      && after.phase == before.phase, "failed collection write preserves prior metadata")
  check(
    try boundedStore.read { try $0.scalarInt("SELECT count(*) FROM observations") } == originals,
    "failed collection write leaves no orphan original")
}
for namespace in ["roster-revision", "command-origins"] {
  try boundedStore.transaction { db in
    try db.execute(
      "INSERT INTO counters(namespace,key,value) VALUES(?,?,?)",
      [.text(namespace), .text("bound"), .integer(maximumRevision - 1)])
    check(try db.nextSequence(namespace: namespace, key: "bound") == maximumRevision, "last exact counter commits")
  }
  rejectsRevision(
    {
      try boundedStore.transaction { db in
        try db.put(namespace: "revision-rollback", key: namespace, value: Data([1]))
        _ = try db.nextSequence(namespace: namespace, key: "bound")
      }
    }, "durable counter refuses overflow")
  check(
    try boundedStore.read {
      try $0.scalarInt("SELECT value FROM counters WHERE namespace=? AND key='bound'", [.text(namespace)])
    } == maximumRevision, "failed counter retains its last exact value")
  check(
    try boundedStore.read { try $0.get(namespace: "revision-rollback", key: namespace) } == nil,
    "counter overflow rolls back the enclosing write")
}
let replanned = try archive.create(
  startedAt: fixedDate, indoor: false, watchEnabled: false, saveToHealth: false, recordGPS: true, sampleHz: 4)
func capture(_ seconds: Double) throws -> [WorkoutEvent] {
  let at = fixedDate.addingTimeInterval(seconds)
  return [
    try WorkoutEvent(
      workoutId: replanned.id, kind: "telemetry", source: "controller", timestamp: at, elapsedSeconds: seconds,
      payload: ["humanPowerW": .number(150), "cadenceRpm": .number(80)]),
    try WorkoutEvent(
      workoutId: replanned.id, kind: "location", source: "phone", timestamp: at, elapsedSeconds: seconds,
      payload: ["latitude": .number(0), "longitude": .number(seconds * 0.00001), "horizontalAccuracyM": .number(3)]),
    try health(replanned.id, seconds: seconds),
  ]
}
for event in try capture(0) { try archive.append(event) }
let preparedBefore = try store.read { $0.workForTesting.reprepares }
for second in 1...20 { for event in try capture(Double(second)) { try archive.append(event) } }
check(
  try store.read { $0.workForTesting.reprepares } == preparedBefore,
  "capture inserts of every stream reuse their statement plans")
print(
  "SQLite storage: \(assertions) assertions passed; identity, exact values, atomic replay/receipt, revision/correction pages, start ordering, bounds, crash integrity, single schema version; \(store.runtimeVersion)"
)

let atomicRide = try archive.create(
  startedAt: fixedDate, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false, sampleHz: 8)
let atomicRevision = try archive.revision(id: atomicRide.id)
store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected Finish disk error") }
rejects(
  {
    try store.transaction { _ in
      try archive.append(
        WorkoutEvent(
          workoutId: atomicRide.id, kind: "lifecycle", source: "phone", timestamp: fixedDate, elapsedSeconds: 0,
          payload: ["action": .string("stop")]))
      _ = try archive.update(id: atomicRide.id, phase: "completed", stopElapsedSeconds: 0)
      _ = try archive.finish(id: atomicRide.id, endedAt: fixedDate)
    }
  }, "A failed local Finish leaves no partial lifecycle or completion")
store.beforeCommitForTesting = nil
let rolledBackFinish = try archive.metadata(id: atomicRide.id)
check(
  rolledBackFinish.endedAt == nil && rolledBackFinish.phase != "completed"
    && rolledBackFinish.collectionRevision == atomicRevision, "Failed Finish remains retryable at its original revision"
)
try archive.update(id: atomicRide.id, healthKitState: "saved")
try archive.update(id: atomicRide.id, healthKitState: "pending")
check(
  try archive.metadata(id: atomicRide.id).healthKitState == "saved",
  "A delayed pending Health message cannot replace a saved result")
for (name, value) in try reclaimTests(root: root).sorted(by: { $0.key < $1.key }) {
  print(
    "Reclaim \(name): deleted ride \(value.rideBytes) bytes; database and WAL \(value.before) to \(value.after) bytes; \(value.steps) steps in \(Int(value.milliseconds)) ms"
  )
}
