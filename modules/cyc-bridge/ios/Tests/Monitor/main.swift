import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
func rejected(_ body: () throws -> Void, _ message: String) {
  do {
    try body()
    fatalError(message)
  } catch { assertions += 1 }
}
let fm = FileManager.default
let root = fm.temporaryDirectory.appendingPathComponent("powerlog-monitor-\(UUID().uuidString)/PowerLog")
try fm.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? fm.removeItem(at: root.deletingLastPathComponent()) }
let canonical = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: canonical)
let reader = MonitorDataStore(root: root)
let start = Date(timeIntervalSince1970: 1_780_000_000)
func stamp(_ seconds: Double) -> String { WorkoutCoding.timestamp(start.addingTimeInterval(seconds)) }
let workout = try archive.create(
  startedAt: start, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true)
try archive.update(id: workout.id, phase: "running")
func event(
  _ time: Double, _ payload: [String: WorkoutJSON], source: String = "cyc", kind: String = "telemetry",
  id: String = UUID().uuidString
) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: workout.id, kind: kind, source: source, timestamp: start.addingTimeInterval(time), elapsedSeconds: time,
    payload: payload, eventId: id)
}
func append(
  _ time: Double, _ payload: [String: WorkoutJSON], source: String = "cyc", kind: String = "telemetry",
  id: String = UUID().uuidString
) throws {
  try archive.append(event(time, payload, source: source, kind: kind, id: id))
}
func request(
  metrics: [String] = ["humanPowerW"], start: Double? = nil, end: Double? = nil, seconds: Double? = nil,
  buckets: Int = 16
) throws -> MonitorRequest {
  var r = MonitorRequest(
    source: "workout", id: workout.id, generation: 1, startSeconds: start, endSeconds: end, seconds: seconds,
    metrics: metrics, buckets: buckets)
  r.expectedRevision = try reader.describeSource(r)["revision"] as? String
  return r
}
func points(_ result: [String: Any], metric: String = "humanPowerW") -> [[String: Any]] {
  (result["series"] as? [String: [[String: Any]]])?[metric] ?? []
}
func selected(_ result: [String: Any], metric: String = "humanPowerW") -> [String: Any]? {
  (result["points"] as? [String: Any])?[metric] as? [String: Any]
}
func statistics(_ result: [String: Any], metric: String = "humanPowerW") -> [String: Any]? {
  (result["statistics"] as? [String: Any])?[metric] as? [String: Any]
}

for batch in 0..<4 {
  var values: [WorkoutEvent] = []
  for i in (batch * 250)..<((batch + 1) * 250) {
    let watts = i == 501 ? 701.125 : Double(i % 100)
    let payload: [String: WorkoutJSON] = [
      "humanPowerW": .number(watts), "cadenceRpm": .number(72), "batteryVoltageV": .number(i == 501 ? 38.125 : 52),
    ]
    values.append(try event(Double(i) / 8, payload))
  }
  _ = try canonical.appendBatch(values)
}
let descriptor = try reader.describeSource(MonitorRequest(source: "workout", id: workout.id))
check(
  (descriptor["availableMetrics"] as? [String] ?? []).contains("humanPowerW"),
  "descriptor reads persisted available columns")
let plot = try reader.readPlot(request(metrics: ["humanPowerW", "batteryVoltageV"], buckets: 8))
check(points(plot).count <= 32, "geometry size bounded by selected pixel buckets")
check(points(plot).contains { $0["value"] as? Double == 701.125 }, "single original spike survives M4")
check(
  points(plot, metric: "batteryVoltageV").contains { $0["value"] as? Double == 38.125 },
  "single original sag survives M4")
let inspect = try reader.inspectAt(request(seconds: 501.0 / 8))
check(selected(inspect)?["timestamp"] as? String == stamp(501.0 / 8), "inspection returns exact original UTC")
check(selected(inspect)?["value"] as? Double == 701.125, "inspection independent original value")
let stats = try reader.rangeStats(request(start: 0, end: 125))
check(statistics(stats)?["count"] as? Int == 1_000, "statistics count every original")
check((statistics(stats)?["max"] as? [String: Any])?["elapsedSeconds"] as? Double == 501.0 / 8, "exact max timestamp")
let clipped = try reader.rangeStats(request(start: 1.14, end: 2.49))
check(
  statistics(clipped)?["count"] as? Int == 10,
  "fractional boundaries neither double count nor include outside observations")
let neighbors = try reader.readPlot(request(start: 1.14, end: 2.49, buckets: 3))
check(
  points(neighbors).first?["elapsedSeconds"] as? Double == 1.125
    && points(neighbors).last?["elapsedSeconds"] as? Double == 2.5, "original neighbors clip viewport edges")
let stale = try request()
try append(126, ["humanPowerW": .number(250), "cadenceRpm": .number(78)])
check(try reader.readPlot(stale)["status"] as? String == "retry", "old revision cannot mix a new snapshot")
var changesRequest = MonitorRequest(source: "workout", id: workout.id, generation: 9)
changesRequest.sinceRevision = stale.expectedRevision
let changes = try reader.changesSince(changesRequest)
check(
  changes["resetRequired"] as? Bool == false && (changes["changes"] as? [[String: Any]])?.count == 1,
  "incremental mutation ranges survive request boundary")

// Equal minima arrive late and in reverse order: the original earliest time wins.
try append(140, ["humanPowerW": .number(-2), "cadenceRpm": .number(0)])
try append(130, ["humanPowerW": .number(-2), "cadenceRpm": .number(0)])
let tied = try reader.rangeStats(request(start: 125, end: 150))
check(
  (statistics(tied)?["min"] as? [String: Any])?["elapsedSeconds"] as? Double == 130,
  "late equal minimum uses original time")
let gap = try reader.inspectAt(request(seconds: 135))
check(selected(gap) == nil, "inspection rejects long gaps")
let gapPlot = try reader.readPlot(request(start: 125, end: 145, buckets: 1))
check(
  points(gapPlot).last?["startsSegment"] as? Bool == true, "a long gap remains visible even inside one reduced bucket")
try append(134, ["humanPowerW": .number(3), "cadenceRpm": .number(0)])
try append(138, ["humanPowerW": .number(4), "cadenceRpm": .number(0)])
check(selected(try reader.inspectAt(request(seconds: 135))) != nil, "late originals repair gap without stale cache")

try append(
  20, ["heartRateBpm": .number(120), "representation": .string("builderMostRecent")], source: "watch", kind: "health")
let heartBefore = try reader.readPlot(request(metrics: ["heartRateBpm"]))
check(points(heartBefore, metric: "heartRateBpm").count == 1, "fallback genuine heart snapshot available")
try append(11, ["heartRateBpm": .number(91), "representation": .string("rawSeries")], source: "watch", kind: "health")
try append(12, ["heartRateBpm": .number(200)], source: "phone", kind: "health")
let heart = try reader.readPlot(request(metrics: ["heartRateBpm"]))
check(
  points(heart, metric: "heartRateBpm").count == 1
    && points(heart, metric: "heartRateBpm").first?["value"] as? Double == 91,
  "first raw Watch series invalidates entire metric and wins source priority")
let heartLatest = try reader.readLatest(request(metrics: ["heartRateBpm"]))
check(
  (heartLatest["points"] as? [String: Any])?["heartRateBpm"] is [String: Any], "latest real reading provided separately"
)
try append(
  12,
  [
    "latitude": .number(0), "longitude": .number(0), "speedMps": .number(4), "altitudeMeters": .number(-2.5),
    "horizontalAccuracyM": .number(3),
  ], source: "watch", kind: "location")
try append(
  13, ["latitude": .number(0), "longitude": .number(0), "speedMps": .number(9)], source: "phone", kind: "location")
let route = try reader.readPlot(request(metrics: ["speedMps", "altitudeMeters", "horizontalAccuracyM"]))
try append(
  12,
  [
    "humanPowerW": .number(100), "cadenceRpm": .number(80), "speedRaw": .number(36), "controllerSpeedMps": .number(10),
    "controllerModel": .string("X12"), "firmwareLabel": .string("20250604"), "controllerProtocol": .string("5.3"),
  ])
try append(13, ["humanPowerW": .number(100), "cadenceRpm": .number(80), "speedRaw": .number(100)])
let controllerSpeed = try reader.readPlot(request(metrics: ["speedMps", "controllerSpeedMps", "speedRaw"]))
check(
  points(controllerSpeed, metric: "controllerSpeedMps").count == 1
    && points(controllerSpeed, metric: "controllerSpeedMps").first?["value"] as? Double == 10,
  "controller speed reads canonical m/s without interpreting old raw rows")
check(
  points(controllerSpeed, metric: "speedMps").first?["value"] as? Double == 4,
  "controller speed cannot replace owner GPS speed")
let speedDescription = try reader.describeSource(MonitorRequest(source: "workout", id: workout.id))
check(
  (speedDescription["availableMetrics"] as? [String])?.contains("controllerSpeedMps") == true,
  "normalized speed availability survives canonical write")
check(
  (speedDescription["availableMetrics"] as? [String])?.contains("controllerModel") == false,
  "identity strings are not chart metrics")
check(
  points(route, metric: "speedMps").first?["value"] as? Double == 4 && points(route, metric: "speedMps").count == 1,
  "route selects expected owner")
check(points(route, metric: "altitudeMeters").first?["value"] as? Double == -2.5, "negative valid altitude preserved")
check(
  points(route, metric: "horizontalAccuracyM").first?["value"] as? Double == 3,
  "native location column matches production payload")

try append(10, ["action": .string("pause")], source: "watch", kind: "lifecycle")
try append(20, ["action": .string("resume")], source: "watch", kind: "lifecycle")
try append(30, ["action": .string("stop")], source: "watch", kind: "lifecycle")
let active = try reader.rangeStats(request(start: 0, end: 140))
check(
  (statistics(active)?["count"] as? Int ?? 1_000) < 200,
  "pause and authoritative stop constrain statistics, not original capture")
check(
  points(try reader.readPlot(request(start: 125, end: 145))).contains { $0["elapsedSeconds"] as? Double == 140 },
  "original evidence after cutoff remains inspectable")

let reopened = MonitorDataStore(root: root)
_ = try reopened.readPlot(request())
check(
  try canonical.read { try $0.scalarInt("SELECT count(*) FROM derived_cache") ?? 0 } == 0,
  "live geometry does not write a persistent entry on every update")
_ = try archive.finish(id: workout.id, endedAt: start.addingTimeInterval(145))
let revisionBefore = try canonical.collection(id: workout.id).int("revision")
let snapshot = try request()
_ = try reopened.readPlot(snapshot)
let cacheCount = try canonical.read { try $0.scalarInt("SELECT count(*) FROM derived_cache") ?? 0 }
_ = try reopened.readPlot(request(start: 2, end: 130))
check(
  try canonical.read { try $0.scalarInt("SELECT count(*) FROM derived_cache") ?? 0 } == cacheCount,
  "panning does not accumulate one cache entry per viewport")
check(cacheCount > 0, "small plot overview persists in canonical store")
_ = try reopened.readPlot(snapshot)
check(
  try canonical.collection(id: workout.id).int("revision") == revisionBefore,
  "reads and derived cache do not mutate originals revision")
check(
  !fm.fileExists(atPath: root.appendingPathComponent("workouts/\(workout.id)/events.jsonl").path),
  "no second canonical JSONL representation")
rejected(
  { _ = try reader.describeSource(MonitorRequest(source: "workout", id: workout.id, startSeconds: .nan)) },
  "reject invalid range")
rejected({ _ = try reader.readPlot(request(metrics: ["unknown"])) }, "reject unknown projection")

// Native physical identity/timeline remains independent of recording resets and reconnect gaps.
var clock = CycCaptureClock(origin: 100, wallOrigin: start)
let first = clock.observation(
  ["humanPowerW": 120, "cadenceRpm": 80], monotonic: 101, wall: start.addingTimeInterval(1))
let next = clock.observation(
  ["humanPowerW": 120, "cadenceRpm": 80], monotonic: 110, wall: start.addingTimeInterval(310))
check(
  first["captureSessionID"] as? String == next["captureSessionID"] as? String,
  "reconnect gap retains acquisition session")
check(next["observationSequence"] as? String == "2", "physical sequence independent of all view counters")
check(next["clockDiscontinuitySeconds"] as? Double == 300, "wall jump preserved as mapping evidence")
check(next["sourceElapsedSeconds"] as? Double == 10, "clock jump does not alter monotonic source domain")
let liveID = UUID().uuidString.lowercased()
reader.beginLive(startedAt: stamp(0), monotonic: 100, id: liveID)
try reader.appendLive(first, elapsedSeconds: 1)
let liveDescriptor = try reader.describeSource(MonitorRequest())
check(
  liveDescriptor["sourceId"] as? String == "live:\(liveID)", "live membership exposes stable acquisition collection")
let beforeReuse = try canonical.read { try $0.scalarInt("SELECT count(*) FROM observations") ?? 0 }
let physicalWorkout = try WorkoutEvent(dictionary: [
  "schemaVersion": 1, "eventId": first["observationId"]!, "workoutId": workout.id, "kind": "telemetry", "source": "cyc",
  "timestamp": first["timestamp"]!, "elapsedSeconds": 1.0, "payload": first,
])
try archive.append(physicalWorkout)
check(
  try canonical.read { try $0.scalarInt("SELECT count(*) FROM observations") ?? 0 } == beforeReuse,
  "one physical observation shared by live and workout membership")
// A failed live commit is sticky only until a later original commits successfully.
var afterFailure = first
let nextObservation = UUID().uuidString.lowercased()
afterFailure["observationId"] = nextObservation
afterFailure["timestamp"] = stamp(2)
afterFailure["observationSequence"] = "2"
afterFailure["acquisitionMonotonic"] = 102.0
afterFailure["sourceElapsedSeconds"] = 2.0
// Distance derivation also commits in the background; fail only this observation's transaction.
try canonical.read { db in
  canonical.beforeCommitForTesting = {
    if try db.scalarInt(
      "SELECT count(*) FROM collection_memberships WHERE collection_id=? AND event_id=?",
      [.text(liveID), .text(nextObservation)]) == 1
    {
      throw PowerLogStorageError.sqlite(13, "Injected live append failure")
    }
  }
}
check(
  try canonical.transaction(priority: .background) { _ in true },
  "live fault injection leaves unrelated commits available")
do {
  try reader.appendLive(afterFailure, elapsedSeconds: 2)
  fatalError("failed commit expected")
} catch { assertions += 1 }
try canonical.read { _ in canonical.beforeCommitForTesting = nil }
do {
  _ = try reader.describeSource(MonitorRequest())
  fatalError("capture error expected")
} catch { assertions += 1 }
try reader.appendLive(afterFailure, elapsedSeconds: 2)
check(
  try reader.describeSource(MonitorRequest())["sourceId"] as? String == "live:\(liveID)",
  "successful live commit clears prior capture error")
let recoveredLatest = try reader.readLatest(MonitorRequest(metrics: ["humanPowerW"]))
check(
  (recoveredLatest["points"] as? [String: [String: Any]])?["humanPowerW"]?["timestamp"] as? String == stamp(2),
  "independent latest reads newly committed live original after recovery")
// Native cursor comparisons use original precision, not the microsecond indexing hint.
let preciseRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false)
for (time, watts) in [(1.0000002, 100.0), (1.0000008, 200.0)] {
  try archive.append(
    WorkoutEvent(
      workoutId: preciseRide.id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(time),
      elapsedSeconds: time, payload: ["humanPowerW": .number(watts), "cadenceRpm": .number(80)]))
}
let precise = try reader.inspectAt(
  MonitorRequest(source: "workout", id: preciseRide.id, seconds: 1.0000008, metrics: ["humanPowerW"]))
check(
  selected(precise)?["value"] as? Double == 200, "exact observation wins over nearby observation within one microsecond"
)
let precisionStats = try reader.rangeStats(
  MonitorRequest(
    source: "workout", id: preciseRide.id, startSeconds: 1.0000005, endSeconds: 1.0000009, metrics: ["humanPowerW"]))
check(statistics(precisionStats)?["count"] as? Int == 1, "range boundaries retain original submicrosecond precision")

// A committed owner cutoff applies before its delayed lifecycle stream arrives.
try archive.update(id: preciseRide.id, stopElapsedSeconds: 1.0000005)
try archive.finish(id: preciseRide.id, endedAt: start.addingTimeInterval(20))
let cutoffStats = try reader.rangeStats(
  MonitorRequest(source: "workout", id: preciseRide.id, startSeconds: 0, endSeconds: 30, metrics: ["humanPowerW"]))
check(
  statistics(cutoffStats)?["count"] as? Int == 1,
  "monotonic owner cutoff excludes later originals even before stop event")

// HealthKit deletes a parent sample UUID; every series point for that parent follows it.
let seriesRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
let parentID = UUID().uuidString.lowercased()
for i in 0..<3 {
  try archive.append(
    WorkoutEvent(
      workoutId: seriesRide.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(Double(i)),
      elapsedSeconds: Double(i),
      payload: [
        "sampleUUID": .string(parentID.uppercased()), "representation": .string(i == 0 ? "rawQuantity" : "rawSeries"),
        "heartRateBpm": .number(Double(100 + i)),
      ]))
}
let seriesRevision = try archive.revision(id: seriesRide.id)
let seriesQuery = MonitorRequest(source: "workout", id: seriesRide.id, metrics: ["heartRateBpm"])
check(
  points(try reader.readPlot(seriesQuery), metric: "heartRateBpm").count == 3,
  "parent and raw series visible before deletion")
try archive.append(
  WorkoutEvent(
    workoutId: seriesRide.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(5),
    elapsedSeconds: 5,
    payload: ["sampleUUID": .string(parentID), "deleted": .bool(true), "representation": .string("deletedSample")]))
check(
  points(try reader.readPlot(seriesQuery), metric: "heartRateBpm").isEmpty,
  "parent deletion invalidates all derived series points and cache")
var historicalSeries = 0
var selectedSeries = 0
var allSeries = 0
try archive.forEachEvent(id: seriesRide.id, revision: seriesRevision, selectedOnly: true) { _ in historicalSeries += 1 }
try archive.forEachEvent(id: seriesRide.id, selectedOnly: true) { _ in selectedSeries += 1 }
try archive.forEachEvent(id: seriesRide.id) { _ in allSeries += 1 }
check(
  historicalSeries == 3 && selectedSeries == 0 && allSeries == 4,
  "immutable historical snapshot keeps originals while current selection applies group deletion")
try archive.append(
  WorkoutEvent(
    workoutId: seriesRide.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(1.5),
    elapsedSeconds: 1.5,
    payload: ["sampleUUID": .string(parentID), "representation": .string("rawSeries"), "heartRateBpm": .number(250)]))
check(
  selected(
    try reader.inspectAt(MonitorRequest(source: "workout", id: seriesRide.id, seconds: 1.5, metrics: ["heartRateBpm"])),
    metric: "heartRateBpm") == nil, "late child after parent tombstone is absent from exact inspection")
let deletedStats = try reader.rangeStats(
  MonitorRequest(source: "workout", id: seriesRide.id, startSeconds: 0, endSeconds: 10, metrics: ["heartRateBpm"]))
check(
  statistics(deletedStats, metric: "heartRateBpm")?["count"] as? Int == 0
    || statistics(deletedStats, metric: "heartRateBpm") == nil,
  "deleted raw series never contributes to extrema/statistics")
let nearestBefore = try reader.inspectAt(
  MonitorRequest(source: "workout", id: preciseRide.id, seconds: 1.0000009, metrics: ["humanPowerW"]))
check(selected(nearestBefore) == nil, "Inspection just after a completed recording's observed extent is unavailable")
let nearestAfter = try reader.inspectAt(
  MonitorRequest(source: "workout", id: preciseRide.id, seconds: 1.0000001, metrics: ["humanPowerW"]))
check(selected(nearestAfter) == nil, "Inspection just before the observed extent is unavailable")

// Screen-space selection carries the original physical identity, including multiple vertices at one time.
let anchorRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false)
var anchorEvents: [WorkoutEvent] = []
for (index, reading) in [(5.0, 100.0), (5.0, 900.0), (10.0, 110.0), (10.0, 800.0)].enumerated() {
  let event = try WorkoutEvent(
    workoutId: anchorRide.id, kind: "telemetry", source: "cyc",
    timestamp: start.addingTimeInterval(reading.0), elapsedSeconds: reading.0,
    payload: ["humanPowerW": .number(reading.1), "cadenceRpm": .number(Double(70 + index))],
    eventId: String(format: "00000000-0000-4000-8000-%012d", index + 1))
  anchorEvents.append(event)
  try archive.append(event)
}
let anchorIdentities = try anchorEvents.map { try PowerLogStore.physicalIdentity($0) }
func anchoredRequest(_ identity: String, at seconds: Double = 5, metric: String = "humanPowerW") -> MonitorRequest {
  MonitorRequest(
    source: "workout", id: anchorRide.id, seconds: seconds, metrics: ["humanPowerW", "cadenceRpm"],
    anchor: MonitorObservationAnchor(metric: metric, observationId: identity))
}
let unanchored = try reader.inspectAt(
  MonitorRequest(source: "workout", id: anchorRide.id, seconds: 5, metrics: ["humanPowerW", "cadenceRpm"]))
let anchoredPeak = try reader.inspectAt(anchoredRequest(anchorIdentities[1]))
check(
  selected(unanchored)?["value"] as? Double == 100,
  "Unanchored tied-time lookup retains its established deterministic choice")
check(
  selected(anchoredPeak)?["value"] as? Double == 900
    && selected(anchoredPeak)?["observationId"] as? String == anchorIdentities[1],
  "Anchored inspect returns the actual visible spike, not another original sharing its timestamp")
check(
  selected(anchoredPeak, metric: "cadenceRpm")?["observationId"] as? String == selected(
    unanchored, metric: "cadenceRpm")?["observationId"] as? String,
  "Other metrics keep nearest-time lookup independently of the anchored metric")
check(
  selected(try reader.inspectAt(anchoredRequest(anchorIdentities[1], at: 5.0000005)))?["value"] as? Double == 900,
  "Anchors tolerate the existing one-microsecond boundary roundoff")
check(
  selected(try reader.inspectAt(anchoredRequest(anchorIdentities[1], at: 5.01))) == nil,
  "Mismatched-time anchor is unavailable and never falls back to a nearby original")
check(
  selected(try reader.inspectAt(anchoredRequest("missing-observation"))) == nil,
  "Missing identity does not silently choose a timestamp tie")
check(
  selected(try reader.inspectAt(anchoredRequest("' OR 1=1 --"))) == nil,
  "Opaque identity is bound as SQL data, never an executable predicate")
let unchangedCount = try canonical.read { try $0.scalarInt("SELECT count(*) FROM observations") }
_ = try reader.inspectAt(anchoredRequest("'; DELETE FROM observations; --"))
check(
  try canonical.read { try $0.scalarInt("SELECT count(*) FROM observations") } == unchangedCount,
  "SQL-looking identity cannot change or expand the original-data query")
var wrongCollection = anchoredRequest(anchorIdentities[1])
wrongCollection.id = preciseRide.id
check(
  selected(try reader.inspectAt(wrongCollection)) == nil,
  "Physical identity from another source collection is unavailable")
var otherChannelID = ""
for (source, value) in [("phone", 120.0), ("watch", 160.0)] {
  let sample = try WorkoutEvent(
    workoutId: anchorRide.id, kind: "health", source: source,
    timestamp: start.addingTimeInterval(5), elapsedSeconds: 5, payload: ["heartRateBpm": .number(value)])
  try archive.append(sample)
  if source == "watch" { otherChannelID = try PowerLogStore.physicalIdentity(sample) }
}
let selectedOwner = try reader.inspectAt(
  MonitorRequest(source: "workout", id: anchorRide.id, seconds: 5, metrics: ["heartRateBpm"]))
check(
  selected(selectedOwner, metric: "heartRateBpm")?["value"] as? Double == 120,
  "Fixture selects the configured phone health channel")
let wrongChannel = try reader.inspectAt(
  MonitorRequest(
    source: "workout", id: anchorRide.id, seconds: 5, metrics: ["heartRateBpm"],
    anchor: MonitorObservationAnchor(metric: "heartRateBpm", observationId: otherChannelID)))
check(
  selected(wrongChannel, metric: "heartRateBpm") == nil,
  "Identity anchor cannot bypass the selected metric's source channel")

var intervalAnchors = MonitorRequest(
  source: "workout", id: anchorRide.id, startSeconds: 5, endSeconds: 10,
  metrics: ["humanPowerW", "cadenceRpm"], includeEndpoints: true,
  startAnchor: MonitorObservationAnchor(metric: "humanPowerW", observationId: anchorIdentities[1]),
  endAnchor: MonitorObservationAnchor(metric: "humanPowerW", observationId: anchorIdentities[3]))
let intervalResult = try reader.rangeStats(intervalAnchors)
let endpoints = intervalResult["endpoints"] as! [String: [String: Any]]
check(
  (endpoints["start"]?["humanPowerW"] as? [String: Any])?["observationId"] as? String == anchorIdentities[1]
    && (endpoints["end"]?["humanPowerW"] as? [String: Any])?["observationId"] as? String == anchorIdentities[3],
  "Range included A/B endpoints retain their independently anchored original identities")
check(
  statistics(intervalResult)?["count"] as? Int == 4, "Endpoint anchors do not alter exact range membership or counts")
intervalAnchors.endSeconds = 5
intervalAnchors.endAnchor = MonitorObservationAnchor(metric: "humanPowerW", observationId: anchorIdentities[0])
let tiedEndpoints = try reader.rangeStats(intervalAnchors)["endpoints"] as! [String: [String: Any]]
check(
  (tiedEndpoints["start"]?["humanPowerW"] as? [String: Any])?["value"] as? Double == 900
    && (tiedEndpoints["end"]?["humanPowerW"] as? [String: Any])?["value"] as? Double == 100,
  "A/B may name distinct genuine originals at exactly the same elapsed time")
for invalid in ["", String(repeating: "x", count: 513), "invalid\u{0000}identity", String(repeating: "é", count: 257)] {
  rejected(
    { _ = try reader.inspectAt(anchoredRequest(invalid)) },
    "Reject empty, oversized or control-character observation identity")
}
rejected(
  { _ = try reader.inspectAt(anchoredRequest(anchorIdentities[1], metric: "unknown")) },
  "Reject anchor metric outside native allowlist")
rejected(
  { _ = try reader.inspectAt(anchoredRequest(anchorIdentities[1], metric: "batteryVoltageV")) },
  "Reject anchor metric not requested")
var missingAnchorTime = anchoredRequest(anchorIdentities[1])
missingAnchorTime.seconds = nil
rejected({ _ = try reader.inspectAt(missingAnchorTime) }, "Reject identity anchor without requested time")

var deletedPeak = try WorkoutEvent(
  workoutId: anchorRide.id, kind: "telemetry", source: "cyc",
  timestamp: start.addingTimeInterval(5), elapsedSeconds: 5,
  payload: ["humanPowerW": .number(900), "cadenceRpm": .number(71)])
deletedPeak.payload["supersedesEventId"] = .string(anchorEvents[1].eventId)
deletedPeak.payload["deleted"] = .bool(true)
try archive.append(deletedPeak)
check(
  selected(try reader.inspectAt(anchoredRequest(anchorIdentities[1]))) == nil,
  "Deleted or corrected original cannot be resurrected by its retained anchor")
check(
  selected(try reader.inspectAt(anchoredRequest(anchorIdentities[0])))?["value"] as? Double == 100,
  "An independently selected original at the deleted spike's timestamp remains available")
_ = try canonical.markWorkoutDeleted(id: anchorRide.id)
rejected(
  { _ = try reader.inspectAt(anchoredRequest(anchorIdentities[0])) }, "Deleted workout rejects anchored original lookup"
)
let integrationRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false)
let continuousTelemetry = PowerLogStore.telemetryColumns.filter {
  !["assistLevel", "raceMode", "faultCode", "consumedAh", "consumedWh"].contains($0)
}
for (time, value) in [(0.0, 100.0), (2.0, 200.0), (5.0, 100.0), (7.5, 200.0)] {
  try archive.append(
    WorkoutEvent(
      workoutId: integrationRide.id, kind: "telemetry", source: "cyc",
      timestamp: start.addingTimeInterval(time), elapsedSeconds: time,
      payload: Dictionary(uniqueKeysWithValues: continuousTelemetry.map { ($0, .number(value)) })))
}
for (time, expected) in [(0.0, 100.0), (1.0, 100.0), (1.0000001, 200.0), (2.0, 200.0)] {
  let inspected = try reader.inspectAt(
    MonitorRequest(source: "workout", id: integrationRide.id, seconds: time, metrics: ["humanPowerW"]))
  check(
    selected(inspected)?["value"] as? Double == expected,
    "Inspection uses exact hits or the nearest bracketed point, with earlier ties")
}
let integrationQuery = MonitorRequest(
  source: "workout", id: integrationRide.id, startSeconds: 0, endSeconds: 8, metrics: continuousTelemetry)
let integrated = try reader.rangeStats(integrationQuery)
for metric in continuousTelemetry {
  let value = statistics(integrated, metric: metric)
  check(value?["count"] as? Int == 4, "\(metric) counts active originals across a telemetry gap")
  check(
    value?["coveredSeconds"] as? Double == 4.5 && value?["integral"] as? Double == 675,
    "\(metric) includes 2.5-second edges and excludes three-second edges from integration")
}
var clippedQuery = integrationQuery
clippedQuery.startSeconds = 0.5
clippedQuery.endSeconds = 1.5
let clippedIntegration = try reader.rangeStats(clippedQuery)
for metric in continuousTelemetry {
  let value = statistics(clippedIntegration, metric: metric)
  check(
    value?["count"] as? Int == 0 && value?["min"] == nil && value?["max"] == nil && value?["sampleMean"] == nil,
    "\(metric) clipped interpolation does not invent observations or extrema")
  check(
    value?["coveredSeconds"] as? Double == 1 && value?["integral"] as? Double == 150,
    "\(metric) retains the clipped integral without interior observations")
}
clippedQuery.startSeconds = 3
clippedQuery.endSeconds = 4
let unsupportedInterval = try reader.rangeStats(clippedQuery)
check(
  statistics(unsupportedInterval)?["coveredSeconds"] as? Double == 0
    && statistics(unsupportedInterval)?["integral"] as? Double == 0,
  "An empty range inside an unsupported telemetry gap has no coverage or energy")
for (time, action) in [(5.5, "pause"), (7.0, "resume")] {
  try archive.append(
    WorkoutEvent(
      workoutId: integrationRide.id, kind: "lifecycle", source: "phone",
      timestamp: start.addingTimeInterval(time), elapsedSeconds: time, payload: ["action": .string(action)]))
}
try archive.append(
  WorkoutEvent(
    workoutId: integrationRide.id, kind: "telemetry", source: "cyc",
    timestamp: start.addingTimeInterval(6), elapsedSeconds: 6,
    payload: ["humanPowerW": .number(999), "cadenceRpm": .number(70)]))
let activeIntegration = try reader.rangeStats(integrationQuery)
check(
  statistics(activeIntegration)?["count"] as? Int == 4
    && statistics(activeIntegration)?["sampleMean"] as? Double == 150,
  "Paused originals cannot affect active count or mean")
check(
  (statistics(activeIntegration)?["max"] as? [String: Any])?["value"] as? Double == 200
    && statistics(activeIntegration)?["coveredSeconds"] as? Double == 2
    && statistics(activeIntegration)?["integral"] as? Double == 300,
  "Paused originals and edges across pauses cannot affect extrema, coverage or integration")
check(
  points(try reader.readPlot(integrationQuery)).contains { $0["value"] as? Double == 999 },
  "Paused originals remain plotted")
let healthGapRide = try archive.create(
  startedAt: start, indoor: false, watchEnabled: false, saveToHealth: true, recordGPS: true)
for time in [0.0, 5.0] {
  try archive.append(
    WorkoutEvent(
      workoutId: healthGapRide.id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(time),
      elapsedSeconds: time, payload: ["heartRateBpm": .number(100)]))
  try archive.append(
    WorkoutEvent(
      workoutId: healthGapRide.id, kind: "location", source: "phone", timestamp: start.addingTimeInterval(time),
      elapsedSeconds: time,
      payload: [
        "latitude": .number(0), "longitude": .number(0), "horizontalAccuracyM": .number(5), "speedMps": .number(4),
      ]))
}
let otherGaps = try reader.rangeStats(
  MonitorRequest(
    source: "workout", id: healthGapRide.id, startSeconds: 0, endSeconds: 5, metrics: ["heartRateBpm", "speedMps"]))
for metric in ["heartRateBpm", "speedMps"] {
  check(
    statistics(otherGaps, metric: metric)?["coveredSeconds"] as? Double == 5,
    "\(metric) keeps its non-controller integration gap")
}
let interruptedRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
func interruptedEvent(
  _ time: Double, kind: String = "telemetry", payload: [String: WorkoutJSON], id: String = UUID().uuidString
) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: interruptedRide.id, kind: kind, source: kind == "lifecycle" ? "phone" : "cyc",
    timestamp: start.addingTimeInterval(time < 1.2 ? time : time + 600), elapsedSeconds: time,
    payload: payload, eventId: id)
}
for time in [1.0, 1.1, 1.2, 1.3] {
  try archive.append(interruptedEvent(time, payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80)]))
  try archive.append(
    WorkoutEvent(
      workoutId: interruptedRide.id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(time),
      elapsedSeconds: time, payload: ["heartRateBpm": .number(120)]))
}
var interruptedQuery = MonitorRequest(
  source: "workout", id: interruptedRide.id, startSeconds: 1, endSeconds: 1.3,
  metrics: ["humanPowerW", "heartRateBpm"], buckets: 1)
_ = try reader.readPlot(interruptedQuery)
_ = try reader.rangeStats(interruptedQuery)
let resumed = try interruptedEvent(
  1.1, kind: "lifecycle", payload: ["action": .string("resume")], id: "00000000-0000-0000-0000-000000000031")
let interruption = try interruptedEvent(
  1.1, kind: "lifecycle",
  payload: ["action": .string("pause"), "interrupted": .bool(true), "cycSequence": .string("2")],
  id: "ffffffff-ffff-ffff-ffff-fffffffffff1")
_ = try archive.appendBatch([resumed], producer: "phone-owner", firstSequence: 2)
_ = try archive.appendBatch([interruption], producer: "phone-owner", firstSequence: 1)
let interruptedPlot = try reader.readPlot(interruptedQuery)
check(
  points(interruptedPlot).map { $0["elapsedSeconds"] as! Double } == [1, 1.1, 1.2, 1.3]
    && points(interruptedPlot).map { $0["startsSegment"] as! Bool } == [true, false, true, false],
  "a delayed persisted interruption invalidates warm reduction and preserves both short runs")
let interruptedStats = try reader.rangeStats(interruptedQuery)
check(
  abs((statistics(interruptedStats)?["coveredSeconds"] as? Double ?? -1) - 0.2) < 1e-9
    && abs((statistics(interruptedStats)?["integral"] as? Double ?? -1) - 20) < 1e-9,
  "interruption rejects its edge while source sequence orders equal-time pause and resume")
check(
  abs((statistics(interruptedStats, metric: "heartRateBpm")?["coveredSeconds"] as? Double ?? -1) - 0.2) < 1e-9,
  "persisted interruption also breaks Health observations without acquisition epochs")
let coldInterrupted = MonitorDataStore(root: root)
let coldInterruptedPlot = try coldInterrupted.readPlot(interruptedQuery)
check(
  NSDictionary(dictionary: interruptedPlot).isEqual(to: coldInterruptedPlot),
  "warm and cold interruption geometry are identical")
for (lower, upper) in [(1.11, 1.29), (1.0, 1.15), (1.15, 1.3)] {
  interruptedQuery.startSeconds = lower
  interruptedQuery.endSeconds = upper
  check(
    points(try reader.readPlot(interruptedQuery)).contains {
      $0["elapsedSeconds"] as? Double == 1.2 && $0["startsSegment"] as? Bool == true
    }, "viewport predecessor and successor retain hard boundaries")
}
interruptedQuery.seconds = 1.15
check(selected(try reader.inspectAt(interruptedQuery)) == nil, "cursor refuses compressed process downtime")
interruptedQuery.seconds = 1.1
check(
  selected(try reader.inspectAt(interruptedQuery))?["timestamp"] as? String == stamp(1.1),
  "interruption endpoint remains an exact original observation")
interruptedQuery.seconds = 1.2
check(
  selected(try reader.inspectAt(interruptedQuery))?["timestamp"] as? String == stamp(601.2),
  "resumed endpoint retains original UTC rather than compressed elapsed UTC")
let epochRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
for (time, epoch, connection) in [
  (1.0, "process-a", "radio-a"), (1.1, "process-a", "radio-b"), (1.2, "process-b", "radio-b"),
  (1.3, "process-b", "radio-b"),
] {
  try archive.append(
    WorkoutEvent(
      workoutId: epochRide.id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(time),
      elapsedSeconds: time,
      payload: [
        "humanPowerW": .number(100), "cadenceRpm": .number(80),
        "clockEpoch": .string(epoch), "connectionEpoch": .string(connection),
      ]))
}
var epochQuery = MonitorRequest(
  source: "workout", id: epochRide.id, startSeconds: 1, endSeconds: 1.3,
  metrics: ["humanPowerW"], buckets: 8)
check(
  points(try reader.readPlot(epochQuery)).map { $0["startsSegment"] as! Bool } == [true, false, true, false],
  "process epoch breaks plotting while a short radio reconnect remains visually continuous")
check(
  abs((statistics(try reader.rangeStats(epochQuery))?["coveredSeconds"] as? Double ?? -1) - 0.1) < 1e-9,
  "both process and radio epochs break integration")
epochQuery.seconds = 1.05
check(selected(try reader.inspectAt(epochQuery)) != nil, "short radio reconnect keeps original-neighbor inspection")
epochQuery.seconds = 1.15
check(selected(try reader.inspectAt(epochQuery)) == nil, "process epoch alone exposes a compressed interruption")

print("Canonical native monitor: \(assertions) assertions passed")
