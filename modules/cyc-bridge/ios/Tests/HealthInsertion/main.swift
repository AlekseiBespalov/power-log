import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) { assertions += 1; if !condition { fatalError(message) } }
func rejects(_ operation: () throws -> Void, _ message: String) {
  do { try operation(); fatalError("Expected rejection: " + message) } catch { assertions += 1 }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-health-insertion-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root)
let journal = WorkoutHealthInsertionJournal(archive: archive)
let date = Date(timeIntervalSince1970: 1_780_000_000)
func ride() throws -> WorkoutMetadata { try archive.create(startedAt: date, indoor: true, watchEnabled: false) }
func event(_ id: String, _ time: Double, power: WorkoutJSON?, cadence: WorkoutJSON?) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = [:]; payload["humanPowerW"] = power; payload["cadenceRpm"] = cadence
  let original = try WorkoutEvent(workoutId: id, kind: "telemetry", source: "cyc", timestamp: date.addingTimeInterval(time), elapsedSeconds: time, payload: payload)
  // Exercise the production JSON decoder (which intentionally decodes integral numbers as Int64).
  return try JSONDecoder().decode(WorkoutEvent.self, from: WorkoutCoding.encoder().encode(original))
}
func lifecycle(_ id: String, _ time: Double, _ action: String) throws {
  try archive.append(WorkoutEvent(workoutId: id, kind: "lifecycle", source: "phone", timestamp: date.addingTimeInterval(time), elapsedSeconds: time, payload: ["action": .string(action)]))
}
let first = try ride()
let decoded = try event(first.id, 0, power: .number(0), cadence: .number(80))
check(decoded.payload["cadenceRpm"] == .integer(80), "fixture traverses integral production JSON decode")
let initial = WorkoutHealthTelemetryPlan(events: [decoded]) { _ in true }
check(initial.quantities.count == 2 && initial.quantities.map(\.value) == [0, 80], "zero and integral JSON produce real Health quantities")
let fractional = try event(first.id, 1, power: .number(12.5), cadence: .number(80.25))
check(WorkoutHealthTelemetryPlan(events: [fractional]) { _ in true }.quantities.map(\.value) == [12.5, 80.25], "fractional values retain their precision")
var invalid = decoded; invalid.payload["humanPowerW"] = .integer(-1); invalid.payload.removeValue(forKey: "cadenceRpm")
let invalidPlan = WorkoutHealthTelemetryPlan(events: [invalid]) { _ in true }
check(invalidPlan.quantities.isEmpty && invalidPlan.committed[invalid.eventId]?.values.allSatisfy({ $0 == "invalid" }) == true, "missing and negative values are unavailable, never fabricated zero")
var nonfinite = decoded; nonfinite.payload["humanPowerW"] = .number(.infinity)
check(WorkoutHealthTelemetryPlan(events: [nonfinite]) { _ in true }.results[decoded.eventId]?["humanPowerW"] == "invalid", "nonfinite values cannot enter native samples")
try archive.append(decoded); try journal.prepare([decoded])
let mixed = WorkoutHealthTelemetryPlan(events: [decoded]) { $0 == "humanPowerW" }
try journal.recordMetrics([decoded], results: mixed.committed)
check(try journal.outcome(id: first.id) == "unavailable", "a denied metric yields partial completion")
let repaired = WorkoutHealthTelemetryPlan(events: [decoded], previous: try journal.metricResults([decoded])) { _ in true }
check(repaired.quantities.count == 1 && repaired.quantities.first?.metric == "cadenceRpm", "repair leaves applied power untouched and retries cadence only")
try journal.beginRepair(id: first.id)
check(try journal.outcome(id: first.id) == "pending", "repair marker prevents stale seal claim")
archive.store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected receipt commit failure") }
rejects({ try journal.recordMetrics([decoded], results: repaired.committed) }, "native success followed by failed durable receipt remains retryable")
archive.store.beforeCommitForTesting = nil
check(try journal.metricResults([decoded])[decoded.eventId]?["cadenceRpm"] == "denied", "receipt transaction cannot commit a successful metric prefix")
try journal.recordMetrics([decoded], results: repaired.committed)
try journal.finishRepair(id: first.id)
check(try journal.outcome(id: first.id) == "sealed", "repair recomputes previously sticky partial progress")
check(WorkoutHealthTelemetryPlan(events: [decoded], previous: try journal.metricResults([decoded])) { _ in true }.quantities.isEmpty, "replayed repaired page is idempotent")
let second = try ride(), later = try event(second.id, 0, power: .integer(30), cadence: .integer(80))
try archive.append(later); try journal.prepare([later]); try journal.recordMetrics([later], results: WorkoutHealthTelemetryPlan(events: [later]) { _ in true }.committed)
check(try journal.outcome(id: second.id) == "sealed", "unavailable state never contaminates next ride")
let legacy = try ride()
let legacyEvents = try (0..<145).map { try event(legacy.id, Double($0), power: .integer(50), cadence: .integer(80)) }
_ = try archive.appendBatch(legacyEvents); try journal.prepare(legacyEvents); try journal.record(legacyEvents, outcome: "unavailable")
check(try journal.outcome(id: legacy.id) == "unavailable", "legacy unavailable receipts advance over multiple original pages")
try journal.beginRepair(id: legacy.id)
for pageStart in stride(from: 0, to: legacyEvents.count, by: 16) {
  let page = Array(legacyEvents[pageStart..<min(pageStart + 16, legacyEvents.count)])
  try journal.recordMetrics(page, results: WorkoutHealthTelemetryPlan(events: page, previous: try journal.metricResults(page)) { _ in true }.committed)
}
// A new facade simulates repair restart without requiring a HealthKit device.
let restarted = WorkoutHealthInsertionJournal(archive: archive)
check(try restarted.outcome(id: legacy.id) == "pending", "durable repair marker survives adapter reconstruction")
try restarted.finishRepair(id: legacy.id)
check(try restarted.outcome(id: legacy.id) == "sealed", "bounded completion recomputes all 145 receipts after restart")
let hole = try ride(), holeEvent = try event(hole.id, 1, power: .integer(50), cadence: .integer(80))
_ = try archive.appendBatch([holeEvent], producer: "cyc", firstSequence: 2)
try journal.prepare([holeEvent]); try journal.record([holeEvent], outcome: "applied"); try journal.beginRepair(id: hole.id)
rejects({ try journal.finishRepair(id: hole.id) }, "missing source sequence cannot complete repair")
check(try journal.outcome(id: hole.id) == "pending", "hole retains durable repair marker")
// A legacy batch could contain fractional power already saved at version 1 and skipped integral cadence.
let mixedLegacyRide = try ride(), mixedLegacyEvent = try event(mixedLegacyRide.id, 0, power: .number(12.5), cadence: .number(80))
try archive.append(mixedLegacyEvent); try journal.prepare([mixedLegacyEvent]); try journal.record([mixedLegacyEvent], outcome: "unavailable")
let mixedLegacy = WorkoutHealthTelemetryPlan(events: [mixedLegacyEvent], previous: try journal.metricResults([mixedLegacyEvent])) { _ in true }
check(mixedLegacy.quantities.count == 2, "legacy mixed result does not guess which metric already reached Health")
let versions = try journal.reserveVersions(mixedLegacy.quantities)
check(versions.values.allSatisfy({ $0 == 2 }), "legacy replay uses documented higher-version replacement for both stable metric identities")
let retryVersions = try journal.reserveVersions(mixedLegacy.quantities)
check(retryVersions.values.allSatisfy({ $0 == 3 }), "uncertain native outcome advances durable version on retry after restart")
check(try journal.outcome(id: mixedLegacyRide.id) == "unavailable", "reserving a version does not fabricate native insertion success")
try journal.recordMetrics([mixedLegacyEvent], results: mixedLegacy.committed)
let knownApplied = WorkoutHealthTelemetryPlan(events: [mixedLegacyEvent], previous: try journal.metricResults([mixedLegacyEvent])) { _ in true }
check(try journal.reserveVersions(knownApplied.quantities).isEmpty, "known applied metrics are never replaced on repair replay")
// An interrupted legacy native attempt may have saved version 1 without any receipt.
let pendingRide = try ride(), pendingEvent = try event(pendingRide.id, 0, power: .integer(20), cadence: .integer(80))
try archive.append(pendingEvent); try journal.prepare([pendingEvent])
check(try journal.needsRepair(pendingEvent), "explicit historical repair discovers originals missing a receipt")
let pendingPlan = WorkoutHealthTelemetryPlan(events: [pendingEvent], previous: try journal.metricResults([pendingEvent])) { _ in true }
let pendingVersions = try journal.reserveVersions(pendingPlan.quantities, minimumVersion: 2)
check(pendingVersions.values.allSatisfy({ $0 == 2 }), "missing legacy receipt safely replaces any uncertain native version 1")
try journal.beginRepair(id: pendingRide.id)
check(try journal.outcome(id: pendingRide.id) == "pending", "version reservation leaves missing native completion pending")
try journal.recordMetrics([pendingEvent], results: pendingPlan.committed); try journal.finishRepair(id: pendingRide.id)
check(try journal.outcome(id: pendingRide.id) == "sealed" && !journal.needsRepair(pendingEvent), "confirmed repair settles missing historical receipt without current-session replay")
let untouched = try event(pendingRide.id, 1, power: .integer(20), cadence: .integer(80))
let livePlan = WorkoutHealthTelemetryPlan(events: [untouched]) { _ in true }
check(try journal.reserveVersions(livePlan.quantities).values.allSatisfy({ $0 == 1 }), "ordinary new live insertion retains initial version 1")
let phases = try ride(); try lifecycle(phases.id, 0, "start"); try lifecycle(phases.id, 2, "pause"); try lifecycle(phases.id, 4, "resume"); try lifecycle(phases.id, 6, "stop")
for (time, expected) in [(1.0, true), (2.0, false), (3.0, false), (4.0, true), (6.0, false), (7.0, false)] {
  check(try WorkoutHealthEligibility.permits(event(phases.id, time, power: .integer(1), cadence: .integer(80)), archive: archive) == expected, "repair respects authoritative lifecycle at \(time)")
}
let delayedStop = try ride(); try lifecycle(delayedStop.id, 0, "start")
try archive.update(id: delayedStop.id, stopElapsedSeconds: 2)
check(try !WorkoutHealthEligibility.permits(event(delayedStop.id, 3, power: .integer(1), cadence: .integer(80)), archive: archive), "committed owner cutoff excludes telemetry before delayed stop event arrives")
check(WorkoutHealthFinalizationGate.canFinish(nativePhase: "stopped"), "stopped original Health session can finish without another delegate transition")
check(WorkoutHealthFinalizationGate.canFinish(nativePhase: "ended"), "recovered ended Health session can finish without an impossible stopped callback")
for phase in ["running", "paused", "prepared"] { check(!WorkoutHealthFinalizationGate.canFinish(nativePhase: phase), "active Health phase still requires stop: \(phase)") }
let oldFinish = WorkoutHealthCallbackIdentity(workoutID: first.id, generation: 1, finishAttempt: 4)
let retryFinish = WorkoutHealthCallbackIdentity(workoutID: first.id, generation: 1, finishAttempt: 5)
check(!oldFinish.matches(retryFinish), "late callback cannot settle a newer finish attempt of the same owner generation")
check(retryFinish.matches(retryFinish), "current original finish attempt can complete")
check(!oldFinish.matches(WorkoutHealthCallbackIdentity(workoutID: second.id, generation: 1, finishAttempt: 4)), "another workout cannot receive finalization effects")
check(!oldFinish.matches(WorkoutHealthCallbackIdentity(workoutID: first.id, generation: 2, finishAttempt: 4)), "recovered ownership invalidates prior generation callbacks")
check(WorkoutHealthMirrorAdmission().permitted, "idle or completed phone adapter can accept a Watch mirror")
check(!WorkoutHealthMirrorAdmission(awaitingStopCompletion: true).permitted, "saved-workout lookup owns admission even with no native session or writes")
check(!WorkoutHealthMirrorAdmission(primaryOwner: true).permitted, "active primary phone owner rejects foreign mirror")
check(!WorkoutHealthMirrorAdmission(ownershipInFlight: true).permitted, "pending owner acquisition rejects foreign mirror")
check(!WorkoutHealthMirrorAdmission(repairInFlight: true).permitted, "saved-workout repair rejects foreign mirror")
check(!WorkoutHealthMirrorAdmission(pendingWrites: 1).permitted, "in-flight Health insertion rejects foreign mirror")
check(!WorkoutHealthMirrorAdmission(finishing: true).permitted, "stopping phone owner rejects foreign mirror")
check(!WorkoutHealthMirrorAdmission(finishStarted: true).permitted, "admitted finalization rejects foreign mirror")

// An admitted native callback must settle with deletion failure without restoring receipts.
let deletedRide = try ride()
let deletedEvent = try event(deletedRide.id, 1, power: .integer(100), cadence: .integer(80))
try archive.append(deletedEvent); try journal.prepare([deletedEvent])
let deletedPlan = WorkoutHealthTelemetryPlan(events: [deletedEvent]) { _ in true }
_ = try journal.reserveVersions(deletedPlan.quantities)
_ = try archive.finish(id: deletedRide.id, endedAt: date.addingTimeInterval(2))
_ = try archive.store.markWorkoutDeleted(id: deletedRide.id)
func rejectsDeleted(_ operation: () throws -> Void, _ message: String) throws {
  do { try operation(); fatalError("Expected deleted target: " + message) }
  catch PowerLogStorageError.deleted { assertions += 1 }
}
func healthRows() throws -> [String] {
  try archive.store.read { db in
    try db.rows("SELECT namespace,key,hex(value) AS bytes FROM durable_records WHERE namespace LIKE 'health-%' ORDER BY namespace,key", limit: 512)
      .map { $0.string("namespace")! + $0.string("key")! + $0.string("bytes")! }
  }
}
let rowsBeforeDeletedCallback = try healthRows()
try rejectsDeleted({ try archive.store.requireWorkoutAvailable(id: deletedRide.id) }, "production Health admission uses the canonical marker")
try rejectsDeleted({ try journal.prepare([deletedEvent]) }, "late insertion intent")
try rejectsDeleted({ try journal.record([deletedEvent], outcome: "applied") }, "late aggregate native receipt")
try rejectsDeleted({ try journal.recordMetrics([deletedEvent], results: deletedPlan.committed) }, "late successful metric receipt")
try rejectsDeleted({ _ = try journal.reserveVersions(deletedPlan.quantities) }, "late native attempt version")
try rejectsDeleted({ try journal.beginRepair(id: deletedRide.id) }, "deleted historical repair admission")
try rejectsDeleted({ try journal.finishRepair(id: deletedRide.id) }, "deleted historical repair completion")
try rejectsDeleted({ _ = try WorkoutHealthEligibility.permits(deletedEvent, archive: archive) }, "deleted telemetry eligibility")
check(try healthRows() == rowsBeforeDeletedCallback, "failed late callbacks leave every Health receipt and intent unchanged")
print("Health numeric, metric receipts, repair and lifecycle: \(assertions) assertions passed")
