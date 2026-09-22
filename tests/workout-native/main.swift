import Foundation

// All UUIDs, GPS coordinates, telemetry and HealthKit quantities here are synthetic.
var assertions = 0
func check(_ value: Bool, _ message: String) {
  assertions += 1
  if !value { fatalError("Assertion failed: \(message)") }
}
func near(_ actual: Double?, _ expected: Double, _ message: String, tolerance: Double = 0.00001) {
  check(actual != nil && abs(actual! - expected) <= tolerance, "\(message): \(String(describing: actual)) != \(expected)")
}
func rejects(_ message: String, _ body: () throws -> Void) {
  do { try body(); fatalError("Expected rejection: \(message)") } catch { assertions += 1 }
}
if CommandLine.arguments.count == 3, CommandLine.arguments[1] == "--recover" {
  let reopened = try WorkoutArchive(rootURL: URL(fileURLWithPath: CommandLine.arguments[2]))
  let recovered = try reopened.metadata(id: "33333333-3333-4333-8333-333333333333")
  precondition(recovered.phase == "recoverable")
  exit(0)
}
let root = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "/private/tmp/power-log-workout-tests", isDirectory: true)
try? FileManager.default.removeItem(at: root)
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
let start = try WorkoutCoding.date("2026-01-01T00:00:00.000Z")
let id = "11111111-1111-4111-8111-111111111111"
var archive: WorkoutArchive? = try WorkoutArchive(rootURL: root.appendingPathComponent("archives"))
_ = try archive!.create(id: id, startedAt: start, indoor: false, watchEnabled: true)
func event(_ t: Double, _ kind: String, _ source: String, _ payload: [String: WorkoutJSON], wall: Double? = nil, workoutId: String = id) throws -> WorkoutEvent {
  try WorkoutEvent(workoutId: workoutId, kind: kind, source: source, timestamp: start.addingTimeInterval(wall ?? t), elapsedSeconds: t, payload: payload)
}
func lifecycle(_ t: Double, _ action: String) throws -> WorkoutEvent {
  try event(t, "lifecycle", "phone", ["action": .string(action)])
}
var events = [try lifecycle(0, "start"), try lifecycle(3, "pause"), try lifecycle(7, "resume"), try lifecycle(8, "lap"), try lifecycle(14, "stop")]
for (t, watts) in [(0.0,100.0),(0.5,200),(2,100),(7,0),(8,200),(12,400),(13,200)] {
  events.append(try event(t, "telemetry", "cyc", ["humanPowerW": .number(watts), "cadenceRpm": .number(watts == 0 ? 0 : 80),
                                                   "batteryPowerW": .number(9999), "speedRaw": .number(123), "controllerSpeedMps": .number(123 / 3.6),
                                                   "controllerModel": .string("X6"), "firmwareLabel": .string("20250604"), "controllerProtocol": .string("5.3"),
                                                   "temperatureC": .number(85)], wall: t == 13 ? -20 : nil))
}
for (t, hr) in [(0.0,100.0),(2,120),(8,140),(13,160)] {
  events.append(try event(t, "health", "watch", ["heartRateBpm": .number(hr), "activeEnergyKcal": .number(t * 2), "basalEnergyKcal": .number(t / 10),
                                                 "rawQuantities": .array([.object(["type": .string("synthetic.test.metric"), "value": .number(123), "unit": .string("count")])])]))
}
for (t, lon) in [(0.0,0.0),(1,0.000045),(2,0.00009),(8,0.1),(9,0.100045),(12,0.2),(13,0.200045)] {
  events.append(try event(t, "location", "watch", ["latitude": .number(0), "longitude": .number(lon), "horizontalAccuracyM": .number(2),
                                                   "altitudeMeters": .number(100 + t), "verticalAccuracyM": .number(2), "speedMps": .number(5)]))
}
// Canonical uniqueness survives wrapper recreation without a derived dedup cache.
let cachedID = "55555555-5555-4555-8555-555555555555"
var cachedArchive: WorkoutArchive? = try WorkoutArchive(rootURL: root.appendingPathComponent("cache-tests"))
_ = try cachedArchive!.create(id: cachedID, startedAt: start, indoor: true, watchEnabled: true)
var cachedEvents: [WorkoutEvent] = []
for index in 0..<32 {
  var sample = try event(Double(index), "telemetry", "cyc", ["humanPowerW": .number(Double(index)), "cadenceRpm": .number(80)], workoutId: cachedID)
  sample.eventId = String(format: "%02x000000-0000-4000-8000-%012x", index % 8, index)
  try cachedArchive!.append(sample)
  try cachedArchive!.append(sample) // Replay must not allocate another membership.
  cachedEvents.append(sample)
}
for sample in cachedEvents.reversed() { try cachedArchive!.append(sample) }
check(try cachedArchive!.metadata(id: cachedID).eventCount == cachedEvents.count, "canonical identity preserves dedup")
var cachedConflict = cachedEvents[0]; cachedConflict.payload["humanPowerW"] = .number(999)
rejects("conflicting canonical observation") { try cachedArchive!.append(cachedConflict) }
cachedArchive = nil
cachedArchive = try WorkoutArchive(rootURL: root.appendingPathComponent("cache-tests"))
for sample in cachedEvents { try cachedArchive!.append(sample) }
check(try cachedArchive!.metadata(id: cachedID).eventCount == cachedEvents.count, "canonical uniqueness survives reopen")
rejects("conflict after reopen") { try cachedArchive!.append(cachedConflict) }
cachedArchive = nil

// Out-of-order arrival across phone/Watch streams and exact replay must be harmless.
for e in events.reversed() { try archive!.append(e) }
try archive!.append(events[0])
check(try archive!.metadata(id: id).eventCount == events.count, "exact replay deduplicated")
var conflict = events[0]; conflict.payload["action"] = .string("stop")
rejects("conflicting ID") { try archive!.append(conflict) }
_ = try archive!.finish(id: id, endedAt: start.addingTimeInterval(14))
let initial = try WorkoutFIT.export(archive: archive!, id: id, to: root.appendingPathComponent("synthetic.fit"))
near(initial.timerSeconds, 10, "pause excluded from timer")
near(initial.elapsedSeconds, 14, "elapsed duration")
near(initial.averageRiderPowerW, 175, "irregular time-weighted power and no gap/paused extrapolation")
near(initial.riderWorkJoules, 700, "rider work only, not motor power")
near(initial.telemetryCoveredSeconds, 4, "power coverage")
near(initial.averageHeartRateBpm, 970 / 7, "HR time-weighted coverage")
near(initial.heartRateCoveredSeconds, 7, "HR excludes pause crossing")
near(initial.maximumRiderPowerW, 400, "real spike maximum")
near(initial.activeEnergyKcal, 26, "cumulative energy not sum")
check(initial.lapCount == 2, "manual plus final lap")
check(initial.routePreview.count == 7, "small route endpoints retained")
check(Set(initial.routePreview.compactMap { $0["segment"] }).count == 3, "route breaks at pause and implausible jump")
near(initial.gpsDistanceMeters, 4 * 0.000045 * .pi / 180 * 6_371_008.8, "GPS does not bridge pauses or jumps")
check(initial.warnings.contains { $0.contains("UTC") }, "UTC discontinuity documented")
check(initial.warnings.contains { $0.contains("Watch synchronization") }, "incomplete Watch transfer visible")
try WorkoutCoding.encoder().encode(initial).write(to: root.appendingPathComponent("synthetic-summary.json"))

// Completed workouts remain open for durable late Watch delivery; snapshots are maxima per source.
let late = try event(14, "health", "watch", ["activeEnergyKcal": .number(30), "distanceMeters": .number(100), "heartRateBpm": .number(150)])
try archive!.append(late)
try archive!.append(try event(13.5, "health", "phone", ["activeEnergyKcal": .number(500), "distanceMeters": .number(500)]))
let merged = try WorkoutFIT.summarize(archive: archive!, id: id)
near(merged.activeEnergyKcal, 30, "late energy merged without phone double count")
near(merged.distanceMeters, initial.distanceMeters!, "Health total does not replace canonical GPS")
near(merged.healthDistanceMeters, 100, "Health reported distance remains separate")
check(merged.healthDistanceProvisional == true && merged.healthDistanceSource == "watch" && merged.healthDistanceReportedAt != nil, "builder-only reported total retains provisional source and time")
check(merged.eventCount == events.count + 2, "late events counted")
_ = try archive!.update(id: id, phase: "completed", healthKitState: "saved", healthKitUUID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
try archive!.flush(); archive = nil
archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archives"))
check(try archive!.metadata(id: id).phase == "completed", "completed remains completed after launch")
try archive!.append(late)
check(try archive!.metadata(id: id).eventCount == events.count + 2, "replay dedup survives relaunch")
var rawSeen = false
try archive!.forEachEvent(id: id) { if $0.number("batteryPowerW") == 9999 && $0.number("speedRaw") == 123 { rawSeen = true } }
check(rawSeen, "full CYC readings preserved outside FIT")
try archive!.append(event(0, "health", "watch", ["heartRateBpm": .number(60), "representation": .string("rawQuantity"), "sampleCount": .number(1)]))
try archive!.append(event(2, "health", "watch", ["heartRateBpm": .number(90), "representation": .string("rawSeries")]))
try archive!.append(event(18, "health", "watch", ["activeEnergyKcal": .number(25), "distanceMeters": .number(90), "representation": .string("finalWorkoutTotal")]))
let authoritative = try WorkoutFIT.summarize(archive: archive!, id: id)
near(authoritative.averageHeartRateBpm, 75, "raw HR takes precedence over builder snapshots")
near(authoritative.maximumHeartRateBpm, 90, "builder snapshot maximum excluded after raw delivery")
near(authoritative.activeEnergyKcal, 25, "final saved total may revise provisional maximum downward")
near(authoritative.distanceMeters, initial.distanceMeters!, "late final Health total does not overwrite GPS")
near(authoritative.healthDistanceMeters, 90, "late final Health reported distance corrects downward")
check(authoritative.healthDistanceProvisional == false && authoritative.healthDistanceSource == "watch", "final reported total is distinguished from provisional")
near(authoritative.elapsedSeconds, 14, "late delivery does not extend stopped workout")
check(authoritative.completeness["watchSync"] == "pending", "HealthKit saved does not imply raw Watch archive received")
_ = try archive!.update(id: id, watchSyncState: "received")
_ = try archive!.finish(id: id, endedAt: start.addingTimeInterval(14), finalPhase: "finishing")
check(try archive!.metadata(id: id).phase == "finishing", "stop date and provisional finishing phase saved together")
_ = try archive!.finish(id: id, endedAt: start.addingTimeInterval(14), finalPhase: "completed")
let synchronized = try WorkoutFIT.summarize(archive: archive!, id: id)
check(synchronized.completeness["watchSync"] == "received", "durable raw archive receipt closes synchronization status")
var summaryCache = try archive!.directory(id: id).appendingPathComponent("summary-v4-r\(archive!.revision(id: id))-dauto.json")
let cachedSummary = try WorkoutFIT.summarize(archive: archive!, id: id)
check(try WorkoutCoding.encoder().encode(cachedSummary) == WorkoutCoding.encoder().encode(synchronized), "cached summary preserves every field")
_ = try archive!.update(id: id, warnings: ["Synthetic metadata-only change"])
let changedSummary = try WorkoutFIT.summarize(archive: archive!, id: id)
check(changedSummary.warnings.contains("Synthetic metadata-only change"), "metadata changes invalidate summary cache")
summaryCache = try archive!.directory(id: id).appendingPathComponent("summary-v4-r\(archive!.revision(id: id))-dauto.json")
try Data("invalid cache".utf8).write(to: summaryCache)
check(try WorkoutFIT.summarize(archive: archive!, id: id).eventCount == changedSummary.eventCount, "corrupt disposable summary cache rebuilds")
// A later commit cannot change the originals/metadata selected by an export already in progress.
let frozenRevision = try archive!.revision(id: id)
let frozen = root.appendingPathComponent("frozen-before.fit")
_ = try WorkoutFIT.export(archive: archive!, id: id, to: frozen, revision: frozenRevision)
let frozenBytes = try Data(contentsOf: frozen)
let lateCommitted = try event(14, "health", "watch", ["heartRateBpm": .number(85)])
try archive!.append(lateCommitted)
check(try WorkoutFIT.summarize(archive: archive!, id: id).eventCount == changedSummary.eventCount + 1, "committed data revision invalidates summary cache")
let sameRevision = root.appendingPathComponent("frozen-after.fit")
_ = try WorkoutFIT.export(archive: archive!, id: id, to: sameRevision, revision: frozenRevision)
check(try Data(contentsOf: sameRevision) == frozenBytes, "paged export retains chosen revision despite a later commit")


let emptyId = "22222222-2222-4222-8222-222222222222"
_ = try archive!.create(id: emptyId, startedAt: start, indoor: true, watchEnabled: false)
_ = try archive!.finish(id: emptyId, endedAt: start.addingTimeInterval(2))
check(try archive!.metadata(id: emptyId).watchSyncState == "notRequired", "phone-only workout requires no Watch transfer")
let empty = try WorkoutFIT.export(archive: archive!, id: emptyId, to: root.appendingPathComponent("empty.fit"))
check(empty.maximumRiderPowerW == nil && empty.distanceMeters == nil && empty.maximumHeartRateBpm == nil, "empty does not invent sensor zeros")
check(empty.lapCount == 1 && empty.timerSeconds == 2, "empty activity has required summary structure")

let recoveryId = "33333333-3333-4333-8333-333333333333"
_ = try archive!.create(id: recoveryId, startedAt: start, indoor: false, watchEnabled: false)
let recoverEvent = try event(0, "lifecycle", "phone", ["action": .string("start")], workoutId: recoveryId)
try archive!.append(recoverEvent); try archive!.flush(); archive = nil
let recoveryProcess = Process()
recoveryProcess.executableURL = URL(fileURLWithPath: CommandLine.arguments[0])
recoveryProcess.arguments = ["--recover", root.appendingPathComponent("archives").path]
try recoveryProcess.run(); recoveryProcess.waitUntilExit()
check(recoveryProcess.terminationStatus == 0, "fresh process recovers the committed workout")
archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archives"))
check(try archive!.metadata(id: recoveryId).phase == "recoverable", "unfinished recovery explicit")
try archive!.append(recoverEvent)
check(try archive!.metadata(id: recoveryId).eventCount == 1, "committed original retained after recovery")
let isolated = try event(1, "health", "phone", ["heartRateBpm": .number(99)], workoutId: recoveryId)
archive!.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Synthetic commit failure") }
rejects("failed commit must not publish another sample") { try archive!.append(isolated) }
archive!.store.beforeCommitForTesting = nil
check(try archive!.metadata(id: recoveryId).eventCount == 1, "failed transaction leaves catalog and originals consistent")

// Validation boundaries: no traversal, nonfinite JSON, invalid location/version/time.
rejects("path traversal") { _ = try archive!.directory(id: "../elsewhere") }
rejects("nonfinite payload") { _ = try event(0, "telemetry", "cyc", ["humanPowerW": .number(.nan), "cadenceRpm": .number(80)]) }
rejects("latitude range") { _ = try event(0, "location", "phone", ["latitude": .number(91), "longitude": .number(0)]) }
rejects("elapsed range") { _ = try event(-1, "health", "watch", ["heartRateBpm": .number(100)]) }
var dictionary = events[0].dictionary; dictionary["schemaVersion"] = 2
rejects("schema version") { _ = try WorkoutEvent(dictionary: dictionary) }
dictionary = events[0].dictionary; dictionary["timestamp"] = "2026-01-01T00:00:00"
rejects("timezone missing") { _ = try WorkoutEvent(dictionary: dictionary) }

// Ordered canonical pages handle irregular source arrival without temporary sort files.
let longId = "44444444-4444-4444-8444-444444444444"
_ = try archive!.create(id: longId, startedAt: start, indoor: false, watchEnabled: false)
let longCount = 32_769
for high in stride(from: longCount, to: 0, by: -256) {
  let batch = try (max(0, high - 256)..<high).reversed().map { i in
    try event(Double(i), "location", "phone", ["latitude": .number(0), "longitude": .number(Double(i) * 0.00001), "horizontalAccuracyM": .number(1)], workoutId: longId)
  }
  _ = try archive!.store.appendBatch(batch)
}
_ = try archive!.finish(id: longId, endedAt: start.addingTimeInterval(Double(longCount - 1)))
let long = try WorkoutFIT.summarize(archive: archive!, id: longId)
check(long.locationCount == longCount, "ordered pages retain all points")
check(long.routePreview.count <= 256, "route preview bounded")
check(long.routePreview.first?["elapsedSeconds"] == 0 && long.routePreview.last?["elapsedSeconds"] == Double(longCount - 1), "preview endpoints retained")
near(long.gpsDistanceMeters, Double(longCount - 1) * 0.00001 * .pi / 180 * 6_371_008.8, "ordered page geometry", tolerance: 0.001)

let gapId = "55555555-5555-4555-8555-555555555555"
_ = try archive!.create(id: gapId, startedAt: start.addingTimeInterval(-60), indoor: false, watchEnabled: false)
_ = try archive!.update(id: gapId, phase: "preparing")
_ = try archive!.confirmStart(id: gapId, startedAt: start)
_ = try archive!.update(id: gapId, phase: "running")
check(try archive!.metadata(id: gapId).startedAt == WorkoutCoding.timestamp(start), "confirmed session start replaces permission/preparation time")
rejects("running workout cannot rebase time") { _ = try archive!.confirmStart(id: gapId, startedAt: start.addingTimeInterval(10)) }
for (time, longitude) in [(0.0, 0.0), (5, 0.0001), (20, 0.0002)] {
  try archive!.append(event(time, "location", "phone", ["latitude": .number(0), "longitude": .number(longitude), "horizontalAccuracyM": .number(1)], workoutId: gapId))
  try archive!.append(event(time, "telemetry", "cyc", ["humanPowerW": .number(0), "cadenceRpm": .number(0)], workoutId: gapId))
}
_ = try archive!.finish(id: gapId, endedAt: start.addingTimeInterval(20))
let gaps = try WorkoutFIT.summarize(archive: archive!, id: gapId)
near(gaps.gpsDistanceMeters, 0.0001 * .pi / 180 * 6_371_008.8, "GPS does not bridge a >10 second gap")
check(Set(gaps.routePreview.compactMap { $0["segment"] }).count == 2, "long GPS gap has distinct segment")
near(gaps.maximumRiderPowerW, 0, "measured zero remains valid zero")
check(gaps.averageRiderPowerW == nil && gaps.riderWorkJoules == nil, "isolated zeros do not manufacture covered duration")

// Invalid input is rejected before mutation; other collections remain queryable.
let beforeInvalid = try archive!.metadata(id: recoveryId).eventCount
var malformed = recoverEvent; malformed.kind = "invalid"
rejects("malformed imported record") { try archive!.append(malformed) }
check(try archive!.metadata(id: recoveryId).eventCount == beforeInvalid, "invalid import cannot alter intact history")
// Revision-bound summary/export applies HealthKit parent deletions to every raw-series child.
let deletedRide = try archive!.create(startedAt: start, indoor: true, watchEnabled: true)
let deletedParent = UUID().uuidString.lowercased()
for i in 0..<3 {
  try archive!.append(event(Double(i), "health", "watch", ["sampleUUID": .string(deletedParent), "representation": .string("rawSeries"), "heartRateBpm": .number(Double(100 + i))], workoutId: deletedRide.id))
}
try archive!.finish(id: deletedRide.id, endedAt: start.addingTimeInterval(3))
let beforeDeletion = try archive!.revision(id: deletedRide.id)
let oldSummary = try WorkoutFIT.summarize(archive: archive!, id: deletedRide.id, revision: beforeDeletion)
near(oldSummary.maximumHeartRateBpm, 102, "raw series included before deletion")
try archive!.append(event(5, "health", "watch", ["sampleUUID": .string(deletedParent), "deleted": .bool(true), "representation": .string("deletedSample")], workoutId: deletedRide.id))
let deletedSummary = try WorkoutFIT.summarize(archive: archive!, id: deletedRide.id)
check(deletedSummary.maximumHeartRateBpm == nil, "parent deletion removes every series child from current summary")
near(try WorkoutFIT.summarize(archive: archive!, id: deletedRide.id, revision: beforeDeletion).maximumHeartRateBpm, 102, "historical summary remains bound to old revision")
let beforeDeleteFIT = root.appendingPathComponent("parent-before.fit")
let afterDeleteFIT = root.appendingPathComponent("parent-after.fit")
let oldExport = try WorkoutFIT.export(archive: archive!, id: deletedRide.id, to: beforeDeleteFIT, revision: beforeDeletion)
let newExport = try WorkoutFIT.export(archive: archive!, id: deletedRide.id, to: afterDeleteFIT)
check(oldExport.maximumHeartRateBpm == 102 && newExport.maximumHeartRateBpm == nil, "fixed revision export uses the same deletion selection as chart and summary")
// Typed analysis preserves fractional clocks, lifecycle ties, boolean barriers and old revisions.
let typedRide = try archive!.create(startedAt: start, indoor: false, watchEnabled: false, saveToHealth: false)
for (t, action) in [(0.0, "start"), (0.875, "pause"), (1.875, "resume"), (3.125, "stop")] {
  try archive!.append(event(t, "lifecycle", "phone", ["action": .string(action)], workoutId: typedRide.id))
}
var lastPower: WorkoutEvent?
for (t, watts) in [(0.125, 100.0), (0.875, 999.0), (1.875, 50.0), (2.125, 150.0), (3.125, 150.0)] {
  var sample = try event(t, "telemetry", "cyc", ["humanPowerW": .number(watts), "cadenceRpm": .number(80)], wall: t == 3.125 ? 20 : nil, workoutId: typedRide.id)
  if t == 0.125 { sample.elapsedSeconds = nil }
  try archive!.append(sample); lastPower = sample
}
for (index, t) in [0.125, 0.625, 1.875, 2.125, 2.625, 3.125].enumerated() {
  var sample = try event(t, "location", "phone", ["latitude": .number(0), "longitude": .number(Double(index) * 0.00001), "horizontalAccuracyM": .number(1)], workoutId: typedRide.id)
  if t == 0.125 { sample.elapsedSeconds = nil }
  if t == 2.125 { sample.payload["distanceBarrier"] = .bool(true) }
  if t == 2.625 { sample.payload["distanceBarrier"] = .integer(1) }
  if t == 3.125 { sample.payload["distanceBarrier"] = .bool(false) }
  try archive!.append(sample)
}
try archive!.finish(id: typedRide.id, endedAt: start.addingTimeInterval(3.125))
let typedRevision = try archive!.revision(id: typedRide.id)
let typedSummary = try WorkoutFIT.export(archive: archive!, id: typedRide.id, to: root.appendingPathComponent("typed-analysis.fit"))
near(typedSummary.timerSeconds, 2.125, "fractional lifecycle intervals")
near(typedSummary.riderWorkJoules, 175, "tied pause sample excluded and resume sample included")
near(typedSummary.averageRiderPowerW, 140, "fractional weighted power with original elapsed priority")
near(typedSummary.gpsDistanceMeters, 2 * 0.00001 * .pi / 180 * 6_371_008.8, "true barrier excludes its fix; numeric one and false retain the next segment")
near(typedSummary.distanceMeters, typedSummary.gpsDistanceMeters!, "canonical and projected geometry agree")
check(typedSummary.eventCount == 15 && typedSummary.telemetryCount == 5 && typedSummary.locationCount == 6, "projected pages retain all selected counts")
check(typedSummary.routePreview.first?["elapsedSeconds"] == 0.125, "absent original elapsed uses fractional UTC")
check(typedSummary.warnings.contains { $0.contains("UTC and elapsed clocks differ") }, "projected clock disagreement remains visible")
try archive!.append(event(3.125, "telemetry", "cyc", ["humanPowerW": .number(100), "cadenceRpm": .number(80), "supersedesEventId": .string(lastPower!.eventId)], workoutId: typedRide.id))
let correctedTyped = try WorkoutFIT.export(archive: archive!, id: typedRide.id, to: root.appendingPathComponent("typed-corrected.fit"))
near(correctedTyped.riderWorkJoules, 150, "selected replacement changes derived work")
near(try WorkoutFIT.summarize(archive: archive!, id: typedRide.id, revision: typedRevision).riderWorkJoules, 175, "typed historical query retains original correction revision")
print("Workout native tests: \(assertions) assertions passed. Synthetic FIT files: \(root.path)")
