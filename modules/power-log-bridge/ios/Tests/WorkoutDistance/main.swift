import Foundation
import CryptoKit
import PowerLogCore

try runDistanceContractFixtures()

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
func near(_ a: Double?, _ b: Double, _ message: String, tolerance: Double = 0.00001) {
  check(a != nil && abs(a! - b) <= tolerance, message + " \(String(describing: a)) != \(b)")
}
func rejects(_ message: String, _ body: () throws -> Void) {
  do {
    try body()
    check(false, message)
  } catch { check(true, message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-distance-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("store.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"), store: store)
let service = WorkoutDistanceStore(store: store)
let start = Date(timeIntervalSince1970: 1_780_000_000)
var inputRows = 0
var largestInputPage = 0
WorkoutDistanceStore.inputPageObserverForTesting = { count in
  inputRows += count
  largestInputPage = max(largestInputPage, count)
}
defer { WorkoutDistanceStore.inputPageObserverForTesting = nil }
func ride(indoor: Bool = false, watch: Bool = true, saves: Bool = false) throws -> String {
  try archive.create(
    startedAt: start, indoor: indoor, watchEnabled: watch, saveToHealth: saves, recordGPS: !indoor, sampleHz: 4
  ).id
}
func event(_ id: String, _ kind: String, _ source: String, _ time: Double, _ payload: [String: WorkoutJSON]) throws
  -> WorkoutEvent
{
  try WorkoutEvent(
    workoutId: id, kind: kind, source: source, timestamp: start.addingTimeInterval(time), elapsedSeconds: time,
    payload: payload)
}
func gps(
  _ id: String, _ time: Double, source: String = "watch", lat: Double = 0, meters: Double? = nil, accuracy: Double = 2,
  extra: [String: WorkoutJSON] = [:]
) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = [
    "latitude": .number(lat), "longitude": .number((meters ?? time * 5) / 111195.0802335329),
    "horizontalAccuracyM": .number(accuracy), "clockEpoch": .string("gps-epoch"),
  ]
  payload.merge(extra) { _, value in value }
  return try event(id, "location", source, time, payload)
}
func controller(_ id: String, _ time: Double, speed: Double? = 4, extra: [String: WorkoutJSON] = [:]) throws
  -> WorkoutEvent
{
  var payload: [String: WorkoutJSON] = [
    "humanPowerW": .number(100), "cadenceRpm": .number(70), "controllerModel": .string("X6"),
    "controllerProtocol": .string("5.3"), "captureSessionID": .string(id), "connectionEpoch": .string("connection"),
    "clockEpoch": .string("epoch"),
  ]
  payload["controllerSpeedMps"] = speed.map(WorkoutJSON.number) ?? .null
  payload.merge(extra) { _, value in value }
  return try event(id, "telemetry", "controller", time, payload)
}
func lifecycle(_ id: String, _ time: Double, _ action: String) throws {
  try archive.append(event(id, "lifecycle", "phone", time, ["action": .string(action)]))
}
func health(
  _ id: String, _ a: Double, _ b: Double, _ meters: Double, uuid: String?, count: Double = 1, source: String = "watch",
  extra: [String: WorkoutJSON] = [:]
) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = [
    "value": .number(meters), "unit": .string("m"),
    "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"),
    "sampleUUID": .string(UUID().uuidString.lowercased()), "sampleCount": .number(count),
    "sampleStart": .string(WorkoutCoding.timestamp(start.addingTimeInterval(a))),
    "sampleEnd": .string(WorkoutCoding.timestamp(start.addingTimeInterval(b))),
    "representation": .string("rawQuantity"),
  ]
  if let uuid { payload["associatedWorkoutUUID"] = .string(uuid) }
  payload.merge(extra) { _, value in value }
  return try event(id, "health", source, b, payload)
}
func fingerprint(_ id: String) throws -> String {
  var sha = SHA256()
  try archive.forEachEvent(id: id) { sha.update(data: try WorkoutCoding.encoder().encode($0)) }
  return sha.finalize().map { String(format: "%02x", $0) }.joined()
}

let corpus =
  try JSONSerialization.jsonObject(
    with: Data(contentsOf: URL(fileURLWithPath: "tests/fixtures/distance-controller.json"))) as! [String: Any]
let defaults = corpus["defaults"] as! [String: Any]
for test in corpus["cases"] as! [[String: Any]] {
  var accumulator = WorkoutControllerDistanceAccumulator()
  var intervals: [WorkoutDistanceInterval] = []
  for (index, sample) in (test["samples"] as! [[String: Any]]).enumerated() {
    let values = defaults.merging(sample) { _, value in value }
    let input = WorkoutControllerDistanceSample(
      time: values["time"] as! Double, speed: values["speed"] as? Double, model: values["model"] as? String,
      controllerProtocol: values["protocol"] as? String, identity: values["identity"] as? String,
      continuity: values["connectionEpoch"] as? String,
      activeInterval: values["active"] as? Bool == true ? values["continuity"] as? Int : nil, anchor: String(index),
      timestamp: String(index))
    if let interval = accumulator.append(input) { intervals.append(interval) }
  }
  let name = test["name"] as! String
  near(intervals.reduce(0) { $0 + $1.meters }, test["distance"] as! Double, name)
  near(intervals.reduce(0) { $0 + $1.coveredSeconds }, test["covered"] as! Double, name + " coverage")
  check(intervals.count == test["intervals"] as! Int, name + " interval count")
  if let range = test["range"] as? [String: Double] {
    let parts = intervals.map { $0.clipped(start: range["start"]!, end: range["end"]!) }
    near(parts.reduce(0) { $0 + ($1.distanceMeters ?? 0) }, range["distance"]!, name + " clipping")
  }
}

let id = try ride()
try archive.appendBatch((0...4).map { try gps(id, Double($0)) })
let originalHash = try fingerprint(id)
let originalCount = try store.collection(id: id).int("event_count")!
let coldStart = ProcessInfo.processInfo.systemUptime
let first = try service.snapshot(id: id)
let coldSeconds = ProcessInfo.processInfo.systemUptime - coldStart
check(first.source == "gps:watch" && first.method == "gpsGeometry", "owner GPS profile selected")
near(first.totalMeters, 20, "geometry distance")
near(first.coveredSeconds, 4, "geometry coverage")
let points = try service.page(snapshot: first)
check(
  points.count == 5 && points[0].startSeconds == points[0].endSeconds,
  "segment starts have explicit zero-increment anchors")
near(try service.range(snapshot: first, start: 0.5, end: 2.5).distanceMeters, 10, "indexed clipped GPS range")
let boundPlan = try store.read { db in
  try db.rows(
    "EXPLAIN QUERY PLAN " + WorkoutDistanceStore.endBoundQuery,
    [.integer(first.revision), .text(id), .real(.greatestFiniteMagnitude), .integer(.max)], limit: 10)
}
check(
  boundPlan.contains { $0.string("detail")?.contains("membership_time") == true }
    && !boundPlan.contains { $0.string("detail")?.contains("TEMP B-TREE") == true },
  "production end-bound query uses indexed seek without sorting admitted history")
let inputBeforeInspect = inputRows
let warmStart = ProcessInfo.processInfo.systemUptime
for _ in 0..<100 {
  check(
    try service.anchor(snapshot: first, identity: points[2].identity)?.identity == points[2].identity,
    "stable derived anchor")
  near(try service.range(snapshot: first, start: 0.1, end: 3.9).distanceMeters, 19, "bounded indexed range")
  check(
    try service.cachedSnapshot(id: id, revision: first.revision)?.generation == first.generation, "cached fixed handle")
}
check(inputRows == inputBeforeInspect, "inspection and warm cache read zero original input pages")
let warmSeconds = ProcessInfo.processInfo.systemUptime - warmStart
try archive.append(gps(id, 5))
let beforeAppend = inputRows
let appendStart = ProcessInfo.processInfo.systemUptime
let appended = try service.snapshot(id: id)
check(appended.generation == first.generation, "ordinary append advances generation checkpoint")
near(appended.totalMeters, 25, "append includes only accepted tail")
check(inputRows - beforeAppend == 1, "append processes one relevant input")
near(try service.page(snapshot: first).last?.distanceMeters, 20, "old revision never sees appended suffix")
let appendSeconds = ProcessInfo.processInfo.systemUptime - appendStart
try archive.append(gps(id, 2.5, meters: 12.5))
let corrected = try service.snapshot(id: id)
check(corrected.generation != appended.generation, "late original rebuilds coherent generation")
near(corrected.totalMeters, 25, "late geometry preserves total")
near(try service.page(snapshot: first).last?.distanceMeters, 20, "prior generation stays coherent")
check(
  try fingerprint(id) != originalHash && store.collection(id: id).int("event_count") == originalCount + 2,
  "only explicit appended originals changed archive")
let beforeRead = try fingerprint(id)
_ = try service.snapshot(id: id, selection: "gps:phone")
check(
  try service.snapshot(id: id, selection: "gps:phone").totalMeters == nil,
  "pinned unavailable source cannot silently fall back")
check(try fingerprint(id) == beforeRead, "derived reads preserve all original payloads")

for saves in [true, false] {
  let id = try ride(saves: saves)
  try archive.appendBatch([gps(id, 0), gps(id, 1)])
  near(try service.snapshot(id: id).totalMeters, 5, "Health saving does not change GPS")
}
let unjoined = try ride()
try archive.appendBatch([controller(unjoined, 0), controller(unjoined, 1)])
let unjoinedSnapshot = try service.snapshot(id: unjoined)
let joinLimit = String(DistancePolicy.shared.maximumControllerEdgeUs)
try store.transaction { db in
  try db.execute(
    "UPDATE distance_snapshots SET value=CAST(json_remove(CAST(value AS TEXT),'$.controllerEdgeUs') AS BLOB) WHERE collection_id=?",
    [.text(unjoined)])
  try db.execute(
    #"UPDATE distance_generations SET state=CAST(replace(CAST(state AS TEXT),'\"controllerEdgeUs\":\#(joinLimit),','') AS BLOB) WHERE collection_id=?"#,
    [.text(unjoined)])
}
check(
  try service.cachedSnapshot(id: unjoined, revision: unjoinedSnapshot.revision) == nil,
  "a snapshot stored without the controller distance join limit is not current")
try archive.append(controller(unjoined, 2))
check(
  try service.snapshot(id: unjoined).generation != unjoinedSnapshot.generation,
  "a generation built without the controller distance join limit is rebuilt, not extended")
let alternate = try ride()
try archive.appendBatch([gps(alternate, 0), gps(alternate, 0, source: "phone"), gps(alternate, 1, source: "phone")])
check(try service.snapshot(id: alternate).source == "gps:phone", "alternate GPS selected when owner has no interval")
let zero = try ride()
try archive.appendBatch([
  gps(zero, 0, meters: 0, extra: ["speedMps": .number(0)]), gps(zero, 1, meters: 4, extra: ["speedMps": .number(0)]),
])
near(try service.snapshot(id: zero).totalMeters, 0, "stationary jitter yields a usable zero distance")
let negativeAccuracy = try ride()
try archive.appendBatch([
  gps(negativeAccuracy, 0, extra: ["speedMps": .number(0), "speedAccuracyMps": .number(-1)]),
  gps(negativeAccuracy, 1, extra: ["speedMps": .number(0), "speedAccuracyMps": .number(-1)]),
])
near(
  try service.snapshot(id: negativeAccuracy).totalMeters, 5, "invalid speed accuracy cannot suppress valid GPS geometry"
)
let barrier = try ride()
try archive.appendBatch([
  gps(barrier, 0), gps(barrier, 1), gps(barrier, 2, extra: ["distanceBarrier": .bool(true)]), gps(barrier, 3),
  gps(barrier, 4),
])
let broken = try service.snapshot(id: barrier)
near(broken.totalMeters, 10, "retained invalid-location barrier breaks continuity")
check(broken.info.selected!.partial, "gap is partial")
let paused = try ride()
try lifecycle(paused, 0, "start")
try lifecycle(paused, 2, "pause")
try lifecycle(paused, 4, "resume")
try lifecycle(paused, 6, "stop")
try archive.appendBatch((0...7).map { try gps(paused, Double($0)) })
let pauseProfile = try service.snapshot(id: paused)
near(pauseProfile.totalMeters, 20, "no bridge across pause or after stop")
near(pauseProfile.coveredSeconds, 4, "paused time excluded from coverage")
let indoor = try ride(indoor: true)
try archive.appendBatch([gps(indoor, 0), gps(indoor, 1), controller(indoor, 0), controller(indoor, 1)])
check(try service.snapshot(id: indoor).source == "controller", "indoor skips GPS and labels controller estimate")
near(try service.snapshot(id: indoor).totalMeters, 4, "known controller interval")
check(
  try service.snapshot(id: indoor, selection: "gps:watch").source == "gps:watch",
  "explicit saved source remains available")

for missing in ["captureSessionID", "connectionEpoch", "clockEpoch"] {
  for absent: WorkoutJSON in [.null, .string("")] {
    let id = try ride(indoor: true)
    try archive.appendBatch([controller(id, 0, extra: [missing: absent]), controller(id, 1, extra: [missing: absent])])
    check(try service.snapshot(id: id).totalMeters == nil, "controller distance requires explicit " + missing)
    try archive.appendBatch([controller(id, 2), controller(id, 3)])
    near(
      try service.snapshot(id: id).totalMeters, 4, "missing continuity evidence cannot bridge to current observations")
  }
}
for (resumed, meters) in [(2.0, 12.0), (7.0, 8.0)] {
  let reconnect = try ride(indoor: true)
  try archive.appendBatch([
    controller(reconnect, 0), controller(reconnect, 1),
    controller(reconnect, resumed, extra: ["connectionEpoch": .string("reconnected")]),
    controller(reconnect, resumed + 1, extra: ["connectionEpoch": .string("reconnected")]),
  ])
  near(
    try service.snapshot(id: reconnect).totalMeters, meters,
    "a reconnect joins controller integration only across a loss shorter than six seconds")
}

let healthID = try ride(saves: true)
let uuid = UUID().uuidString.lowercased()
try archive.update(id: healthID, healthKitUUID: uuid)
try archive.appendBatch([
  controller(healthID, 0), controller(healthID, 1), health(healthID, 0, 1, 8, uuid: uuid),
  health(healthID, 1, 2, 6, uuid: uuid, extra: ["representation": .string("rawSeries")]),
])
let h = try service.snapshot(id: healthID)
check(
  h.sources.map(\.source) == ["controller"] && h.source == "controller",
  "associated Health cycling distance is no distance source")
check(
  (try? service.snapshot(id: healthID, selection: "health:watch")) == nil,
  "a Health distance choice is rejected")
let restart = try ride(indoor: true, watch: false)
try archive.appendBatch([controller(restart, 0), controller(restart, 1)])
let old = try service.snapshot(id: restart)
for from in stride(from: 2, through: 601, by: 100) {
  try archive.appendBatch((from..<min(from + 100, 602)).map { try controller(restart, Double($0)) })
}
let inputsBeforeLongTail = inputRows
let extended = try WorkoutDistanceStore(store: store).snapshot(id: restart)
check(
  extended.generation == old.generation && inputRows - inputsBeforeLongTail == 600,
  "an append-only tail longer than the change journal extends the generation from its new originals")
near(extended.totalMeters, 2404, "the extended generation retains the long controller total")
check(largestInputPage <= 128, "every original input page is bounded")

let legacy = try ride(saves: true)
try lifecycle(legacy, 10, "stop")
try archive.appendBatch([gps(legacy, 0), gps(legacy, 10)])
try archive.update(id: legacy, stopElapsedSeconds: 10)
_ = try archive.finish(id: legacy, endedAt: start.addingTimeInterval(10))
near(try service.snapshot(id: legacy).totalMeters, 50, "legacy stop-only lifecycle starts at ride origin")
for (discontinuity, meters) in [(false, 12.0), (true, 4.0)] {
  let controllerBarrier = try ride(indoor: true, watch: false)
  var reset: [String: WorkoutJSON] = ["clockEpoch": .string("new-epoch")]
  if discontinuity { reset["clockDiscontinuitySeconds"] = .number(1) }
  try archive.appendBatch([
    controller(controllerBarrier, 0), controller(controllerBarrier, 1), controller(controllerBarrier, 2, extra: reset),
    controller(controllerBarrier, 3, extra: ["clockEpoch": .string("new-epoch")]),
  ])
  near(
    try service.snapshot(id: controllerBarrier).totalMeters, meters,
    "only a clock discontinuity, not a new clock epoch, breaks controller continuity on one connection")
}
var counterAccumulator = WorkoutControllerDistanceAccumulator()
func counter(_ time: Double, _ counter: Double) -> WorkoutControllerDistanceSample {
  WorkoutControllerDistanceSample(
    time: time, speed: 4, model: "X6", controllerProtocol: "5.3", identity: "bike", continuity: "connection",
    activeInterval: 0, anchor: String(time), timestamp: String(time), counter: counter)
}
_ = counterAccumulator.append(counter(0, 10))
check(counterAccumulator.append(counter(1, 9)) == nil, "retained counter reset breaks integration")
near(counterAccumulator.append(counter(2, 10))?.meters, 4, "counter reset reanchors following interval")

let faultID = try ride()
try archive.appendBatch([gps(faultID, 0), gps(faultID, 1)])
let committed = try service.snapshot(id: faultID)
try archive.append(gps(faultID, 2))
let targetRevision = try store.collection(id: faultID).int("revision")!
store.beforeCommitForTesting = {
  let publishing = try store.read { db in
    try db.scalarInt(
      "SELECT 1 FROM distance_snapshots WHERE collection_id=? AND revision=?",
      [.text(faultID), .integer(targetRevision)]) != nil
  }
  if publishing { throw WorkoutDistanceError.invalid("Injected publication failure") }
}
rejects("publication fault propagates") { _ = try service.snapshot(id: faultID) }
store.beforeCommitForTesting = nil
check(try service.cachedSnapshot(id: faultID, revision: targetRevision) == nil, "failed header is never visible")
near(try service.page(snapshot: committed).last?.distanceMeters, 5, "committed handle excludes failed suffix")
near(
  try WorkoutDistanceStore(store: store).snapshot(id: faultID).totalMeters, 10,
  "restart recomputes uncommitted suffix once")
let doomed = try ride()
try archive.appendBatch([gps(doomed, 0), gps(doomed, 1)])
var deletedDuringBuild = false
WorkoutDistanceStore.inputPageObserverForTesting = { _ in
  if !deletedDuringBuild {
    deletedDuringBuild = true
    _ = try! store.markWorkoutDeleted(id: doomed)
  }
}
rejects("deletion during rebuild prevents publication") { _ = try service.snapshot(id: doomed) }
check(
  try store.read { try $0.scalarInt("SELECT count(*) FROM distance_snapshots WHERE collection_id=?", [.text(doomed)]) }
    == 0, "deleted build publishes no header")
WorkoutDistanceStore.inputPageObserverForTesting = nil
for _ in 0..<100 {
  let record = try store.cleanupWorkoutPage(id: doomed)
  if record.cleanupPhase > 0 { break }
}
check(
  try store.read {
    try $0.scalarInt("SELECT count(*) FROM distance_generations WHERE collection_id=?", [.text(doomed)])
  } == 0, "deletion cleanup removes derived state in bounded pages")

let reuseID = try ride(watch: false)
let reuseController = try (0..<160).map { try controller(reuseID, Double($0) / 8) }
try archive.appendBatch(reuseController)
try archive.appendBatch((0..<20).map { try gps(reuseID, Double($0), source: "phone") })
let heldReuse = try service.leasedSnapshot(id: reuseID, revision: archive.revision(id: reuseID))
let beforeReuse = heldReuse.snapshot
let beforeGPS = beforeReuse.sources.first { $0.source == "gps:phone" }!
defer { heldReuse.lease.release() }
try archive.append(
  controller(reuseID, 0.125, speed: 2, extra: ["supersedesEventId": .string(reuseController[1].eventId)]))
var reuseJobs: [WorkoutDistanceStore.Job] = []
WorkoutDistanceStore.jobObserverForTesting = { reuseJobs.append($0) }
let afterReuse = try service.snapshot(id: reuseID)
check(
  afterReuse.maximumPointID == heldReuse.snapshot.maximumPointID,
  "correction reuse retains the rebuilt profile point count")
WorkoutDistanceStore.jobObserverForTesting = nil
check(afterReuse.generation != beforeReuse.generation, "correction has its own coherent generation")
check(
  try WorkoutCoding.encoder().encode(afterReuse.sources.first { $0.source == "gps:phone" })
    == WorkoutCoding.encoder().encode(beforeGPS), "unchanged GPS profile retains every exact field")
check(!reuseJobs.contains { $0.name == "gps:phone" }, "correction copies unchanged GPS without replaying originals")
check(
  reuseJobs.contains { $0.name == "reuse:gps:phone" && $0.examined > 0 },
  "unchanged profile copies bounded derived pages")
check(
  reuseJobs.filter { $0.name.hasPrefix("reuse:") }.allSatisfy { $0.examined <= 128 },
  "profile reuse yields after every page")
near(
  afterReuse.sources.first { $0.source == "controller" }?.distanceMeters, 79.25,
  "only corrected controller edges change")
near(
  try service.range(snapshot: beforeReuse, start: 0, end: 19).distanceMeters, beforeGPS.distanceMeters,
  "old lease remains exact after reuse")
try lifecycle(reuseID, 10, "pause")
reuseJobs = []
WorkoutDistanceStore.jobObserverForTesting = { reuseJobs.append($0) }
let afterPauseReuse = try service.snapshot(id: reuseID)
WorkoutDistanceStore.jobObserverForTesting = nil
check(!reuseJobs.contains { $0.name.hasPrefix("reuse:") }, "changed active timeline rebuilds every profile")
check(
  afterPauseReuse.sources.first { $0.source == "gps:phone" }?.distanceMeters != beforeGPS.distanceMeters,
  "pause invalidates the formerly reusable profile")

let longReuseID = try ride(watch: false)
let longReuseController = try (0..<160).map { try controller(longReuseID, Double($0) / 8) }
try archive.appendBatch(longReuseController)
try archive.appendBatch((0..<20).map { try gps(longReuseID, Double($0), source: "phone") })
let beforeLongReuse = try service.snapshot(id: longReuseID)
try archive.append(
  controller(longReuseID, 0.125, speed: 2, extra: ["supersedesEventId": .string(longReuseController[1].eventId)]))
for from in stride(from: 0, to: 600, by: 200) {
  try archive.appendBatch(
    (from..<(from + 200)).map {
      try event(
        longReuseID, "health", "watch", Double($0) / 32,
        ["heartRateBpm": .number(120), "representation": .string("rawQuantity")])
    })
}
reuseJobs = []
WorkoutDistanceStore.jobObserverForTesting = { reuseJobs.append($0) }
let afterLongReuse = try service.snapshot(id: longReuseID)
WorkoutDistanceStore.jobObserverForTesting = nil
check(
  afterLongReuse.generation != beforeLongReuse.generation && reuseJobs.contains { $0.name == "reuse:gps:phone" }
    && !reuseJobs.contains { $0.name == "gps:phone" },
  "a correction followed by more revisions than the change journal keeps still copies the unchanged GPS profile")
try store.transaction { db in
  try db.execute(
    "DELETE FROM distance_points WHERE generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=?)",
    [.text(longReuseID)])
  try db.execute("DELETE FROM distance_snapshots WHERE collection_id=?", [.text(longReuseID)])
  try db.execute("DELETE FROM distance_generations WHERE collection_id=?", [.text(longReuseID)])
}
let longRebuild = try service.snapshot(id: longReuseID)
check(
  try WorkoutCoding.encoder().encode(afterLongReuse.sources) == WorkoutCoding.encoder().encode(longRebuild.sources),
  "profiles reused across the change journal equal a full rebuild")
try archive.append(
  controller(longReuseID, 0.25, speed: 3, extra: ["supersedesEventId": .string(longReuseController[2].eventId)]))
_ = try archive.update(
  id: longReuseID,
  ownerTiming: WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(10)), elapsedSeconds: 10, timerSeconds: 10),
  elapsedSeconds: 10, onlyIfChanged: true)
reuseJobs = []
WorkoutDistanceStore.jobObserverForTesting = { reuseJobs.append($0) }
let afterTimedCorrection = try service.snapshot(id: longReuseID)
WorkoutDistanceStore.jobObserverForTesting = nil
check(
  afterTimedCorrection.endSeconds == longRebuild.endSeconds && !reuseJobs.contains { $0.name.hasPrefix("reuse:") },
  "a timing revision since the previous build still prevents profile reuse")

print(
  "Workout distance: \(assertions) assertions passed. cold=\(coldSeconds)s warm100=\(warmSeconds)s append=\(appendSeconds)s maxInputPage=\(largestInputPage)"
)
