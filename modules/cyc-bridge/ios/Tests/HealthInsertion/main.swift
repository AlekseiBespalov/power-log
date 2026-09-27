import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
func rejects(_ operation: () throws -> Void, _ message: String) {
  do {
    try operation()
    fatalError("Expected rejection: " + message)
  } catch { assertions += 1 }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-health-insertion-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root)
let journal = WorkoutHealthInsertionJournal(archive: archive)
let date = Date(timeIntervalSince1970: 1_780_000_000)
func ride() throws -> WorkoutMetadata {
  try archive.create(startedAt: date, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false)
}
func event(_ id: String, _ time: Double, power: WorkoutJSON?, cadence: WorkoutJSON?) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = [:]
  payload["humanPowerW"] = power
  payload["cadenceRpm"] = cadence
  let original = try WorkoutEvent(
    workoutId: id, kind: "telemetry", source: "cyc", timestamp: date.addingTimeInterval(time), elapsedSeconds: time,
    payload: payload)
  // Exercise the production JSON decoder (which intentionally decodes integral numbers as Int64).
  return try JSONDecoder().decode(WorkoutEvent.self, from: WorkoutCoding.encoder().encode(original))
}
func lifecycle(_ id: String, _ time: Double, _ action: String) throws {
  try archive.append(
    WorkoutEvent(
      workoutId: id, kind: "lifecycle", source: "phone", timestamp: date.addingTimeInterval(time), elapsedSeconds: time,
      payload: ["action": .string(action)]))
}
let first = try ride()
let decoded = try event(first.id, 0, power: .number(0), cadence: .number(80))
check(decoded.payload["cadenceRpm"] == .integer(80), "fixture traverses integral production JSON decode")
let initial = WorkoutHealthTelemetryPlan(events: [decoded]) { _ in true }
check(
  initial.quantities.count == 2 && initial.quantities.map(\.value) == [0, 80],
  "zero and integral JSON produce real Health quantities")
let fractional = try event(first.id, 1, power: .number(12.5), cadence: .number(80.25))
check(
  WorkoutHealthTelemetryPlan(events: [fractional]) { _ in true }.quantities.map(\.value) == [12.5, 80.25],
  "fractional values retain their precision")
var invalid = decoded
invalid.payload["humanPowerW"] = .integer(-1)
invalid.payload.removeValue(forKey: "cadenceRpm")
let invalidPlan = WorkoutHealthTelemetryPlan(events: [invalid]) { _ in true }
check(
  invalidPlan.quantities.isEmpty
    && invalidPlan.committed[invalid.eventId]?.values.allSatisfy({ $0 == "invalid" }) == true,
  "missing and negative values are unavailable, never fabricated zero")
var nonfinite = decoded
nonfinite.payload["humanPowerW"] = .number(.infinity)
check(
  WorkoutHealthTelemetryPlan(events: [nonfinite]) { _ in true }.results[decoded.eventId]?["humanPowerW"] == "invalid",
  "nonfinite values cannot enter native samples")
@discardableResult
func insert(
  _ events: [WorkoutEvent], into ledger: WorkoutHealthInsertionJournal = journal,
  authorized: (String) -> Bool = { _ in true },
  operation: ([WorkoutHealthInsertionJournal.Write]) throws -> Void = { _ in }
) throws -> [WorkoutHealthInsertionJournal.Write] {
  var writes: [WorkoutHealthInsertionJournal.Write] = []
  var result: Result<Void, Error>?
  ledger.insert(
    events, authorized: authorized,
    operation: { reserved, done in
      writes = reserved
      do {
        try operation(reserved)
        done(.success(()))
      } catch { done(.failure(error)) }
    }, completion: { result = $0 })
  try result!.get()
  return writes
}
try archive.append(decoded)
var nativeCalls = 0
archive.store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected intent failure") }
rejects(
  { try insert([decoded], operation: { _ in nativeCalls += 1 }) },
  "intent/reservation failure prevents native execution")
archive.store.beforeCommitForTesting = nil
check(nativeCalls == 0, "native execution waits for the reservation transaction")
let powerWrites = try insert([decoded], authorized: { $0 == "humanPowerW" })
check(powerWrites.count == 1 && powerWrites[0].version == 1, "only authorized power is reserved and written")
check(try journal.outcome(id: first.id) == "unavailable", "a denied metric yields partial completion")
check(
  try journal.metricResults([decoded])[decoded.eventId] == ["humanPowerW": "applied", "cadenceRpm": "denied"],
  "canonical receipt preserves each metric outcome")
check(
  try journal.historyPage(id: first.id, afterSequence: 0, repairing: false).events.isEmpty,
  "automatic discovery does not retry denied receipts")
check(try !journal.requiresAutomaticHistory(id: first.id), "Denied-only archive is idle for automatic discovery")
var historyWork = WorkoutBoundedWorkQueue()
check(historyWork.request(first.id), "Automatic history worker starts")
let automaticRepair = try journal.isRepairing(id: first.id)
let automaticPage = try journal.historyPage(id: first.id, afterSequence: 0, repairing: false)
check(automaticPage.events.isEmpty && !automaticRepair, "Automatic worker reaches its empty nonrepair page")
try journal.beginRepair(id: first.id, historicalWork: &historyWork)
check(try journal.isRepairing(id: first.id), "Explicit Retry is recorded while automatic history is suspended")
check(
  try journal.historyPage(id: first.id, afterSequence: 0, repairing: automaticRepair).events.isEmpty,
  "Automatic worker cannot switch to an explicit scan after suspension")
if automaticRepair { try journal.finishRepair(id: first.id) }
check(try journal.isRepairing(id: first.id), "Automatic completion cannot clear the queued explicit scan")
check(historyWork.finish(first.id) == first.id, "Completion hands the explicit Retry back through the work queue")
check(historyWork.request(first.id), "Explicit worker owns the historical queue after handoff")
check(try journal.beginHistory(id: first.id), "Explicit worker starts the requested original scan")
check(try journal.requiresAutomaticHistory(id: first.id), "Explicit repair marker survives admission")

let repairPage = try journal.historyPage(id: first.id, afterSequence: 0, repairing: true)
check(repairPage.events.map(\.eventId) == [decoded.eventId], "explicit Watch history discovery finds a denied metric")
check(try journal.outcome(id: first.id) == "pending", "repair marker prevents stale seal claim")
var uncertainWrites: [WorkoutHealthInsertionJournal.Write] = []
rejects(
  {
    try insert(
      repairPage.events,
      operation: { writes in
        uncertainWrites = writes
        archive.store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected receipt failure") }
      })
  }, "native success followed by failed durable receipt remains retryable")
archive.store.beforeCommitForTesting = nil
check(
  uncertainWrites.count == 1 && uncertainWrites[0].quantity.metric == "cadenceRpm" && uncertainWrites[0].version == 1,
  "repair leaves successful power untouched and reserves the first cadence attempt")
check(
  try journal.metricResults([decoded])[decoded.eventId]?["cadenceRpm"] == "denied",
  "receipt failure retains the previous metric results")
let reopened = try WorkoutArchive(rootURL: root)
let restarted = WorkoutHealthInsertionJournal(archive: reopened)
let retried = try insert(restarted.historyPage(id: first.id, afterSequence: 0, repairing: true).events, into: restarted)
check(
  retried.count == 1 && retried[0].syncIdentifier == uncertainWrites[0].syncIdentifier && retried[0].version == 2,
  "restart preserves metric identity and advances an uncertain reserved version")
try restarted.beginRepair(id: first.id, historicalWork: &historyWork)
try restarted.finishRepair(id: first.id)
check(try restarted.isRepairing(id: first.id), "Old explicit worker cannot clear a newly requested scan")
check(historyWork.finish(first.id) == first.id, "Repeated Retry is handed off after the original scan finishes")
check(historyWork.request(first.id), "The next explicit scan owns the queue")
check(try restarted.beginHistory(id: first.id), "Handoff starts the second explicit original scan")
check(
  try restarted.historyPage(id: first.id, afterSequence: 0, repairing: true).events.isEmpty,
  "Second explicit scan retains already applied receipts")
try restarted.finishRepair(id: first.id)
check(historyWork.finish(first.id) == nil, "Completed explicit repair leaves the queue idle")
check(try restarted.outcome(id: first.id) == "sealed", "repair recomputes previously partial progress")
check(try insert([decoded], into: restarted).isEmpty, "duplicate delivery cannot repeat a successful native write")
let canonicalRows = try archive.store.read { db in
  try db.rows(
    "SELECT namespace FROM durable_records WHERE key=? AND namespace IN ('health-insertion-results','health-insertion-metrics')",
    [.text(decoded.eventId)], limit: 2)
}
check(canonicalRows.count == 1, "one canonical receipt is stored per event")

let second = try ride()
try lifecycle(second.id, 0, "start")
let later = try event(second.id, 1, power: .integer(30), cadence: .integer(80))
try archive.append(later)
var liveBounds = WorkoutHealthWriteBounds()
let liveEvents = try journal.admit([later], bounds: &liveBounds, now: date.addingTimeInterval(2), historical: false)
var liveWrites: [WorkoutHealthInsertionJournal.Write] = []
rejects(
  {
    try insert(
      liveEvents,
      operation: { writes in
        liveWrites = writes
        throw PowerLogStorageError.sqlite(13, "Native result unavailable")
      })
  }, "uncertain live native completion keeps its receipt pending")
liveBounds.settle(writer: "telemetry", id: later.eventId, success: false)
check(try journal.outcome(id: second.id) == "pending", "version reservations never imply successful insertion")
try archive.update(id: second.id, stopElapsedSeconds: 2)
try archive.finish(id: second.id, endedAt: date.addingTimeInterval(2))
var historyBounds = WorkoutHealthWriteBounds()
let historyEvents = try restarted.admit(
  restarted.pending(id: second.id), bounds: &historyBounds, now: date.addingTimeInterval(86400), historical: true)
let historyWrites = try insert(historyEvents, into: restarted)
check(
  historyWrites.map(\.syncIdentifier) == liveWrites.map(\.syncIdentifier)
    && historyWrites.allSatisfy { $0.version == 2 },
  "live-to-history handoff retries stable identifiers with higher durable versions")
check(try restarted.outcome(id: second.id) == "sealed", "historical completion settles uncertain live writes")
check(try reopened.metadata(id: second.id).eventCount == 2, "Health retries never append timeline observations")

let deniedRide = try ride()
let deniedEvent = try event(deniedRide.id, 1, power: .integer(50), cadence: .integer(80))
try archive.append(deniedEvent)
check(try insert([deniedEvent], authorized: { _ in false }).isEmpty, "fully denied events invoke no native write")
check(
  try journal.outcome(id: deniedRide.id) == "unavailable",
  "fully denied receipts resolve without a pending native effect")
check(
  try journal.metricResults([deniedEvent])[deniedEvent.eventId]?.values.allSatisfy { $0 == "denied" } == true,
  "each denied metric has an explicit canonical result")

let pages = try ride()
let pageEvents = try (0..<145).map { try event(pages.id, Double($0), power: .integer(50), cadence: .integer(80)) }
_ = try archive.appendBatch(pageEvents)
for pageStart in stride(from: 0, to: pageEvents.count, by: 16) {
  let page = Array(pageEvents[pageStart..<min(pageStart + 16, pageEvents.count)])
  if pageStart == 0 {
    try journal.prepare(page)
    try journal.recordExcluded(page)
  } else {
    try insert(page, authorized: { $0 == "humanPowerW" })
  }
}
check(try journal.outcome(id: pages.id) == "unavailable", "excluded and denied receipts advance across bounded pages")
check(try insert(Array(pageEvents.prefix(16))).isEmpty, "excluded receipts remain terminal on replay")
try restarted.beginRepair(id: pages.id)
var repairCursor: Int64 = 0
var repairedCount = 0
while true {
  let page = try restarted.historyPage(id: pages.id, afterSequence: repairCursor, repairing: true, limit: 16)
  let writes = try insert(page.events, into: restarted)
  repairedCount += writes.count
  check(
    writes.allSatisfy { $0.quantity.metric == "cadenceRpm" && $0.version == 1 }, "denial reserves no native attempt")
  repairCursor = page.afterSequence
  if !page.hasMore { break }
}
check(repairedCount == 129, "repair pages originals past an excluded prefix and preserves successful power")
check(try restarted.outcome(id: pages.id) == "pending", "durable repair marker survives adapter reconstruction")
try restarted.finishRepair(id: pages.id)
check(try restarted.outcome(id: pages.id) == "sealed", "bounded completion derives all 145 canonical receipts")

let bounded = try ride()
let boundedEvents = try (0..<145).map { try event(bounded.id, Double($0), power: .integer(50), cadence: .integer(80)) }
_ = try archive.appendBatch(boundedEvents)
check(try journal.pending(id: bounded.id, limit: 16).count == 16, "pending discovery remains page bounded")
try insert([boundedEvents[15]])
check(try journal.pending(id: bounded.id, limit: 16).count == 15, "later success never hides an earlier pending event")
try journal.prepare(Array(boundedEvents.prefix(15)))
try journal.recordExcluded(Array(boundedEvents.prefix(15)))
check(try restarted.pending(id: bounded.id, limit: 16).isEmpty, "restart advances the contiguous excluded prefix")
check(
  try restarted.pending(id: bounded.id, limit: 16).first?.eventId == boundedEvents[16].eventId,
  "next page follows the retained prefix")
try insert(Array(boundedEvents.dropFirst(16)), into: restarted)
check(try restarted.outcome(id: bounded.id) == "sealed", "bounded pending discovery reaches a resolved final outcome")

let hole = try ride()
let holeEvent = try event(hole.id, 1, power: .integer(50), cadence: .integer(80))
_ = try archive.appendBatch([holeEvent], producer: "cyc", firstSequence: 2)
try insert([holeEvent])
try journal.beginRepair(id: hole.id)
rejects({ try journal.finishRepair(id: hole.id) }, "missing source sequence cannot complete repair")
check(try journal.outcome(id: hole.id) == "pending", "hole retains durable repair marker")

let corruptRide = try ride()
let corruptEvent = try event(corruptRide.id, 1, power: .integer(50), cadence: .integer(80))
try archive.append(corruptEvent)
try journal.prepare([corruptEvent])
try archive.store.transaction { db in
  try db.put(
    namespace: "health-insertion-versions", key: corruptEvent.eventId + ".humanPowerW", value: Data("broken".utf8))
}
rejects({ try insert([corruptEvent]) }, "unreadable reservation cannot reset an uncertain native version")
check(try journal.outcome(id: corruptRide.id) == "pending", "invalid reservation cannot fabricate a receipt")
let phases = try ride()
try lifecycle(phases.id, 0, "start")
try lifecycle(phases.id, 2, "pause")
try lifecycle(phases.id, 4, "resume")
try lifecycle(phases.id, 6, "stop")
for (time, expected) in [(1.0, true), (2.0, false), (3.0, false), (4.0, true), (6.0, false), (7.0, false)] {
  check(
    try WorkoutHealthEligibility.permits(
      event(phases.id, time, power: .integer(1), cadence: .integer(80)), archive: archive) == expected,
    "repair respects authoritative lifecycle at \(time)")
}
let delayedStop = try ride()
try lifecycle(delayedStop.id, 0, "start")
try archive.update(id: delayedStop.id, stopElapsedSeconds: 2)
check(
  try !WorkoutHealthEligibility.permits(
    event(delayedStop.id, 3, power: .integer(1), cadence: .integer(80)), archive: archive),
  "committed owner cutoff excludes telemetry before delayed stop event arrives")
check(
  WorkoutHealthFinalizationGate.canFinish(nativePhase: "stopped"),
  "stopped original Health session can finish without another delegate transition")
check(
  WorkoutHealthFinalizationGate.canFinish(nativePhase: "ended"),
  "recovered ended Health session can finish without an impossible stopped callback")
for phase in ["running", "paused", "prepared"] {
  check(
    !WorkoutHealthFinalizationGate.canFinish(nativePhase: phase), "active Health phase still requires stop: \(phase)")
}
let oldFinish = WorkoutHealthCallbackIdentity(workoutID: first.id, generation: 1, finishAttempt: 4)
let retryFinish = WorkoutHealthCallbackIdentity(workoutID: first.id, generation: 1, finishAttempt: 5)
check(
  !oldFinish.matches(retryFinish), "late callback cannot settle a newer finish attempt of the same owner generation")
check(retryFinish.matches(retryFinish), "current original finish attempt can complete")
check(
  !oldFinish.matches(WorkoutHealthCallbackIdentity(workoutID: second.id, generation: 1, finishAttempt: 4)),
  "another workout cannot receive finalization effects")
check(
  !oldFinish.matches(WorkoutHealthCallbackIdentity(workoutID: first.id, generation: 2, finishAttempt: 4)),
  "recovered ownership invalidates prior generation callbacks")
check(WorkoutHealthMirrorAdmission().permitted, "idle or completed phone adapter can accept a Watch mirror")
check(
  !WorkoutHealthMirrorAdmission(awaitingStopCompletion: true).permitted,
  "saved-workout lookup owns admission even with no native session or writes")
check(!WorkoutHealthMirrorAdmission(primaryOwner: true).permitted, "active primary phone owner rejects foreign mirror")
check(
  !WorkoutHealthMirrorAdmission(ownershipInFlight: true).permitted, "pending owner acquisition rejects foreign mirror")
check(!WorkoutHealthMirrorAdmission(repairInFlight: true).permitted, "saved-workout repair rejects foreign mirror")
check(!WorkoutHealthMirrorAdmission(pendingWrites: 1).permitted, "in-flight Health insertion rejects foreign mirror")
check(!WorkoutHealthMirrorAdmission(finishing: true).permitted, "stopping phone owner rejects foreign mirror")
check(!WorkoutHealthMirrorAdmission(finishStarted: true).permitted, "admitted finalization rejects foreign mirror")

// An admitted native callback must settle with deletion failure without restoring receipts.
let deletedRide = try ride()
let deletedEvent = try event(deletedRide.id, 1, power: .integer(100), cadence: .integer(80))
try archive.append(deletedEvent)
try journal.prepare([deletedEvent])
let deletedPlan = WorkoutHealthTelemetryPlan(events: [deletedEvent]) { _ in true }
_ = try journal.reserveVersions(deletedPlan.quantities)
_ = try archive.finish(id: deletedRide.id, endedAt: date.addingTimeInterval(2))
_ = try archive.store.markWorkoutDeleted(id: deletedRide.id)
func rejectsDeleted(_ operation: () throws -> Void, _ message: String) throws {
  do {
    try operation()
    fatalError("Expected deleted target: " + message)
  } catch PowerLogStorageError.deleted { assertions += 1 }
}
func healthRows() throws -> [String] {
  try archive.store.read { db in
    try db.rows(
      "SELECT namespace,key,hex(value) AS bytes FROM durable_records WHERE namespace LIKE 'health-%' AND (key=? OR key=? OR key LIKE ?) ORDER BY namespace,key",
      [.text(deletedRide.id), .text(deletedEvent.eventId), .text(deletedEvent.eventId + ".%")], limit: 16
    )
    .map { $0.string("namespace")! + $0.string("key")! + $0.string("bytes")! }
  }
}
let rowsBeforeDeletedCallback = try healthRows()
try rejectsDeleted(
  { try archive.store.requireWorkoutAvailable(id: deletedRide.id) },
  "production Health admission uses the canonical marker")
try rejectsDeleted({ try journal.prepare([deletedEvent]) }, "late insertion intent")
try rejectsDeleted(
  { try journal.recordMetrics([deletedEvent], results: deletedPlan.committed) }, "late successful metric receipt")
try rejectsDeleted({ _ = try journal.reserveVersions(deletedPlan.quantities) }, "late native attempt version")
try rejectsDeleted({ try journal.beginRepair(id: deletedRide.id) }, "deleted historical repair admission")
try rejectsDeleted({ try journal.finishRepair(id: deletedRide.id) }, "deleted historical repair completion")
try rejectsDeleted(
  { _ = try WorkoutHealthEligibility.permits(deletedEvent, archive: archive) }, "deleted telemetry eligibility")
check(
  try healthRows() == rowsBeforeDeletedCallback,
  "failed late callbacks leave the deleted ride Health receipts and intent unchanged"
)
print("Health numeric, metric receipts, repair and lifecycle: \(assertions) assertions passed")
