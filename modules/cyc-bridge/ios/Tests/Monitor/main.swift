import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
func rejected(_ body: () throws -> Void, _ message: String) { do { try body(); fatalError(message) } catch { assertions += 1 } }
let fm = FileManager.default
let root = fm.temporaryDirectory.appendingPathComponent("powerlog-monitor-\(UUID().uuidString)/PowerLog")
try fm.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? fm.removeItem(at: root.deletingLastPathComponent()) }
let canonical = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: canonical)
let reader = MonitorDataStore(root: root)
let start = Date(timeIntervalSince1970: 1_780_000_000)
func stamp(_ seconds: Double) -> String { WorkoutCoding.timestamp(start.addingTimeInterval(seconds)) }
let workout = try archive.create(startedAt: start, indoor: false, watchEnabled: true)
try archive.update(id: workout.id, phase: "running")
func event(_ time: Double, _ payload: [String: WorkoutJSON], source: String = "cyc", kind: String = "telemetry", id: String = UUID().uuidString) throws -> WorkoutEvent {
  try WorkoutEvent(workoutId: workout.id, kind: kind, source: source, timestamp: start.addingTimeInterval(time), elapsedSeconds: time, payload: payload, eventId: id)
}
func append(_ time: Double, _ payload: [String: WorkoutJSON], source: String = "cyc", kind: String = "telemetry", id: String = UUID().uuidString) throws {
  try archive.append(event(time, payload, source: source, kind: kind, id: id))
}
func request(metrics: [String] = ["humanPowerW"], start: Double? = nil, end: Double? = nil, seconds: Double? = nil, buckets: Int = 16) throws -> MonitorRequest {
  var r = MonitorRequest(source: "workout", id: workout.id, generation: 1, startSeconds: start, endSeconds: end, seconds: seconds, metrics: metrics, buckets: buckets)
  r.expectedRevision = try reader.describeSource(r)["revision"] as? String
  return r
}
func points(_ result: [String: Any], metric: String = "humanPowerW") -> [[String: Any]] { (result["series"] as? [String: [[String: Any]]])?[metric] ?? [] }
func selected(_ result: [String: Any], metric: String = "humanPowerW") -> [String: Any]? { (result["points"] as? [String: Any])?[metric] as? [String: Any] }
func statistics(_ result: [String: Any], metric: String = "humanPowerW") -> [String: Any]? { (result["statistics"] as? [String: Any])?[metric] as? [String: Any] }

for batch in 0..<4 {
  var values: [WorkoutEvent] = []
  for i in (batch * 250)..<((batch + 1) * 250) {
    let watts = i == 501 ? 701.125 : Double(i % 100)
    let payload: [String: WorkoutJSON] = ["humanPowerW": .number(watts), "cadenceRpm": .number(72), "batteryVoltageV": .number(i == 501 ? 38.125 : 52)]
    values.append(try event(Double(i) / 8, payload))
  }
  _ = try canonical.appendBatch(values)
}
let descriptor = try reader.describeSource(MonitorRequest(source: "workout", id: workout.id))
check((descriptor["availableMetrics"] as? [String] ?? []).contains("humanPowerW"), "descriptor reads persisted available columns")
let plot = try reader.readPlot(request(metrics: ["humanPowerW", "batteryVoltageV"], buckets: 8))
check(points(plot).count <= 32, "geometry size bounded by selected pixel buckets")
check(points(plot).contains { $0["value"] as? Double == 701.125 }, "single original spike survives M4")
check(points(plot, metric: "batteryVoltageV").contains { $0["value"] as? Double == 38.125 }, "single original sag survives M4")
let inspect = try reader.inspectAt(request(seconds: 501.0 / 8))
check(selected(inspect)?["timestamp"] as? String == stamp(501.0 / 8), "inspection returns exact original UTC")
check(selected(inspect)?["value"] as? Double == 701.125, "inspection independent original value")
let stats = try reader.rangeStats(request(start: 0, end: 125))
check(statistics(stats)?["count"] as? Int == 1_000, "statistics count every original")
check((statistics(stats)?["max"] as? [String: Any])?["elapsedSeconds"] as? Double == 501.0 / 8, "exact max timestamp")
let clipped = try reader.rangeStats(request(start: 1.14, end: 2.49))
check(statistics(clipped)?["count"] as? Int == 10, "fractional boundaries neither double count nor include outside observations")
let neighbors = try reader.readPlot(request(start: 1.14, end: 2.49, buckets: 3))
check(points(neighbors).first?["elapsedSeconds"] as? Double == 1.125 && points(neighbors).last?["elapsedSeconds"] as? Double == 2.5, "original neighbors clip viewport edges")
let stale = try request()
try append(126, ["humanPowerW": .number(250), "cadenceRpm": .number(78)])
check(try reader.readPlot(stale)["status"] as? String == "retry", "old revision cannot mix a new snapshot")
var changesRequest = MonitorRequest(source: "workout", id: workout.id, generation: 9)
changesRequest.sinceRevision = stale.expectedRevision
let changes = try reader.changesSince(changesRequest)
check(changes["resetRequired"] as? Bool == false && (changes["changes"] as? [[String: Any]])?.count == 1, "incremental mutation ranges survive request boundary")

// Equal minima arrive late and in reverse order: the original earliest time wins.
try append(140, ["humanPowerW": .number(-2), "cadenceRpm": .number(0)])
try append(130, ["humanPowerW": .number(-2), "cadenceRpm": .number(0)])
let tied = try reader.rangeStats(request(start: 125, end: 150))
check((statistics(tied)?["min"] as? [String: Any])?["elapsedSeconds"] as? Double == 130, "late equal minimum uses original time")
let gap = try reader.inspectAt(request(seconds: 135))
check(selected(gap) == nil, "inspection rejects long gaps")
let gapPlot = try reader.readPlot(request(start: 125, end: 145, buckets: 1))
check(points(gapPlot).last?["startsSegment"] as? Bool == true, "a long gap remains visible even inside one reduced bucket")
try append(134, ["humanPowerW": .number(3), "cadenceRpm": .number(0)])
try append(138, ["humanPowerW": .number(4), "cadenceRpm": .number(0)])
check(selected(try reader.inspectAt(request(seconds: 135))) != nil, "late originals repair gap without stale cache")

try append(20, ["heartRateBpm": .number(120), "representation": .string("builderMostRecent")], source: "watch", kind: "health")
let heartBefore = try reader.readPlot(request(metrics: ["heartRateBpm"]))
check(points(heartBefore, metric: "heartRateBpm").count == 1, "fallback genuine heart snapshot available")
try append(11, ["heartRateBpm": .number(91), "representation": .string("rawSeries")], source: "watch", kind: "health")
try append(12, ["heartRateBpm": .number(200)], source: "phone", kind: "health")
let heart = try reader.readPlot(request(metrics: ["heartRateBpm"]))
check(points(heart, metric: "heartRateBpm").count == 1 && points(heart, metric: "heartRateBpm").first?["value"] as? Double == 91, "first raw Watch series invalidates entire metric and wins source priority")
check((heart["latest"] as? [String: Any])?["heartRateBpm"] is [String: Any], "latest real reading provided separately")
try append(12, ["latitude": .number(0), "longitude": .number(0), "speedMps": .number(4), "altitudeMeters": .number(-2.5), "horizontalAccuracyM": .number(3)], source: "watch", kind: "location")
try append(13, ["latitude": .number(0), "longitude": .number(0), "speedMps": .number(9)], source: "phone", kind: "location")
let route = try reader.readPlot(request(metrics: ["speedMps", "altitudeMeters", "horizontalAccuracyM"]))
try append(12, ["humanPowerW": .number(100), "cadenceRpm": .number(80), "speedRaw": .number(36), "controllerSpeedMps": .number(10), "controllerModel": .string("X12"), "firmwareLabel": .string("20250604"), "controllerProtocol": .string("5.3")])
try append(13, ["humanPowerW": .number(100), "cadenceRpm": .number(80), "speedRaw": .number(100)])
let controllerSpeed = try reader.readPlot(request(metrics: ["speedMps", "controllerSpeedMps", "speedRaw"]))
check(points(controllerSpeed, metric: "controllerSpeedMps").count == 1 && points(controllerSpeed, metric: "controllerSpeedMps").first?["value"] as? Double == 10, "controller speed reads canonical m/s without interpreting old raw rows")
check(points(controllerSpeed, metric: "speedMps").first?["value"] as? Double == 4, "controller speed cannot replace owner GPS speed")
let speedDescription = try reader.describeSource(MonitorRequest(source: "workout", id: workout.id))
check((speedDescription["availableMetrics"] as? [String])?.contains("controllerSpeedMps") == true, "normalized speed availability survives canonical write")
check((speedDescription["availableMetrics"] as? [String])?.contains("controllerModel") == false, "identity strings are not chart metrics")
check(points(route, metric: "speedMps").first?["value"] as? Double == 4 && points(route, metric: "speedMps").count == 1, "route selects expected owner")
check(points(route, metric: "altitudeMeters").first?["value"] as? Double == -2.5, "negative valid altitude preserved")
check(points(route, metric: "horizontalAccuracyM").first?["value"] as? Double == 3, "native location column matches production payload")

try append(10, ["action": .string("pause")], source: "watch", kind: "lifecycle")
try append(20, ["action": .string("resume")], source: "watch", kind: "lifecycle")
try append(30, ["action": .string("stop")], source: "watch", kind: "lifecycle")
let active = try reader.rangeStats(request(start: 0, end: 140))
check((statistics(active)?["count"] as? Int ?? 1_000) < 200, "pause and authoritative stop constrain statistics, not original capture")
check(points(try reader.readPlot(request(start: 125, end: 145))).contains { $0["elapsedSeconds"] as? Double == 140 }, "original evidence after cutoff remains inspectable")

let reopened = MonitorDataStore(root: root)
_ = try reopened.readPlot(request())
check(try canonical.read { try $0.scalarInt("SELECT count(*) FROM derived_cache") ?? 0 } == 0, "live geometry does not write a persistent entry on every update")
_ = try archive.finish(id: workout.id, endedAt: start.addingTimeInterval(145))
let revisionBefore = try canonical.collection(id: workout.id).int("revision")
let snapshot = try request()
_ = try reopened.readPlot(snapshot)
let cacheCount = try canonical.read { try $0.scalarInt("SELECT count(*) FROM derived_cache") ?? 0 }
_ = try reopened.readPlot(request(start: 2, end: 130))
check(try canonical.read { try $0.scalarInt("SELECT count(*) FROM derived_cache") ?? 0 } == cacheCount, "panning does not accumulate one cache entry per viewport")
check(cacheCount > 0, "small plot overview persists in canonical store")
_ = try reopened.readPlot(snapshot)
check(try canonical.collection(id: workout.id).int("revision") == revisionBefore, "reads and derived cache do not mutate originals revision")
check(!fm.fileExists(atPath: root.appendingPathComponent("workouts/\(workout.id)/events.jsonl").path), "no second canonical JSONL representation")
rejected({ _ = try reader.describeSource(MonitorRequest(source: "workout", id: workout.id, startSeconds: .nan)) }, "reject invalid range")
rejected({ _ = try reader.readPlot(request(metrics: ["unknown"])) }, "reject unknown projection")

// Native physical identity/timeline remains independent of recording resets and reconnect gaps.
var clock = CycCaptureClock(origin: 100, wallOrigin: start)
let first = clock.observation(["humanPowerW": 120, "cadenceRpm": 80], monotonic: 101, wall: start.addingTimeInterval(1))
let next = clock.observation(["humanPowerW": 120, "cadenceRpm": 80], monotonic: 110, wall: start.addingTimeInterval(310))
check(first["captureSessionID"] as? String == next["captureSessionID"] as? String, "reconnect gap retains acquisition session")
check(next["observationSequence"] as? String == "2", "physical sequence independent of all view counters")
check(next["clockDiscontinuitySeconds"] as? Double == 300, "wall jump preserved as mapping evidence")
check(next["sourceElapsedSeconds"] as? Double == 10, "clock jump does not alter monotonic source domain")
let liveID = UUID().uuidString.lowercased()
reader.beginLive(startedAt: stamp(0), monotonic: 100, id: liveID)
try reader.appendLive(first, elapsedSeconds: 1)
let liveDescriptor = try reader.describeSource(MonitorRequest())
check(liveDescriptor["sourceId"] as? String == "live:\(liveID)", "live membership exposes stable acquisition collection")
let beforeReuse = try canonical.read { try $0.scalarInt("SELECT count(*) FROM observations") ?? 0 }
let physicalWorkout = try WorkoutEvent(dictionary: ["schemaVersion": 1, "eventId": first["observationId"]!, "workoutId": workout.id, "kind": "telemetry", "source": "cyc", "timestamp": first["timestamp"]!, "elapsedSeconds": 1.0, "payload": first])
try archive.append(physicalWorkout)
check(try canonical.read { try $0.scalarInt("SELECT count(*) FROM observations") ?? 0 } == beforeReuse, "one physical observation shared by live and workout membership")
// A failed live commit is sticky only until a later original commits successfully.
var afterFailure = first
let nextObservation = UUID().uuidString.lowercased()
afterFailure["observationId"] = nextObservation; afterFailure["timestamp"] = stamp(2)
afterFailure["observationSequence"] = "2"
// Distance derivation also commits in the background; fail only this observation's transaction.
try canonical.read { db in
  canonical.beforeCommitForTesting = {
    if try db.scalarInt("SELECT count(*) FROM collection_memberships WHERE collection_id=? AND event_id=?", [.text(liveID), .text(nextObservation)]) == 1 {
      throw PowerLogStorageError.sqlite(13, "Injected live append failure")
    }
  }
}
check(try canonical.transaction(priority: .background) { _ in true }, "live fault injection leaves unrelated commits available")
do { try reader.appendLive(afterFailure, elapsedSeconds: 2); fatalError("failed commit expected") } catch { assertions += 1 }
try canonical.read { _ in canonical.beforeCommitForTesting = nil }
do { _ = try reader.describeSource(MonitorRequest()); fatalError("capture error expected") } catch { assertions += 1 }
try reader.appendLive(afterFailure, elapsedSeconds: 2)
check(try reader.describeSource(MonitorRequest())["sourceId"] as? String == "live:\(liveID)", "successful live commit clears prior capture error")
let recoveredLatest = try reader.readLatest(MonitorRequest(metrics: ["humanPowerW"]))
check((recoveredLatest["points"] as? [String: [String: Any]])?["humanPowerW"]?["timestamp"] as? String == stamp(2), "independent latest reads newly committed live original after recovery")
// Native cursor comparisons use original precision, not the microsecond indexing hint.
let preciseRide = try archive.create(startedAt: start, indoor: true, watchEnabled: false)
for (time, watts) in [(1.0000002, 100.0), (1.0000008, 200.0)] {
  try archive.append(WorkoutEvent(workoutId: preciseRide.id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(time), elapsedSeconds: time, payload: ["humanPowerW": .number(watts), "cadenceRpm": .number(80)]))
}
let precise = try reader.inspectAt(MonitorRequest(source: "workout", id: preciseRide.id, seconds: 1.0000008, metrics: ["humanPowerW"]))
check(selected(precise)?["value"] as? Double == 200, "exact observation wins over nearby observation within one microsecond")
let precisionStats = try reader.rangeStats(MonitorRequest(source: "workout", id: preciseRide.id, startSeconds: 1.0000005, endSeconds: 1.0000009, metrics: ["humanPowerW"]))
check(statistics(precisionStats)?["count"] as? Int == 1, "range boundaries retain original submicrosecond precision")

// A committed owner cutoff applies before its delayed lifecycle stream arrives.
try archive.update(id: preciseRide.id, stopElapsedSeconds: 1.0000005)
try archive.finish(id: preciseRide.id, endedAt: start.addingTimeInterval(20))
let cutoffStats = try reader.rangeStats(MonitorRequest(source: "workout", id: preciseRide.id, startSeconds: 0, endSeconds: 30, metrics: ["humanPowerW"]))
check(statistics(cutoffStats)?["count"] as? Int == 1, "monotonic owner cutoff excludes later originals even before stop event")

// HealthKit deletes a parent sample UUID; every series point for that parent follows it.
let seriesRide = try archive.create(startedAt: start, indoor: true, watchEnabled: true)
let parentID = UUID().uuidString.lowercased()
for i in 0..<3 {
  try archive.append(WorkoutEvent(workoutId: seriesRide.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(Double(i)), elapsedSeconds: Double(i), payload: ["sampleUUID": .string(parentID.uppercased()), "representation": .string(i == 0 ? "rawQuantity" : "rawSeries"), "heartRateBpm": .number(Double(100+i))]))
}
let seriesRevision = try archive.revision(id: seriesRide.id)
let seriesQuery = MonitorRequest(source: "workout", id: seriesRide.id, metrics: ["heartRateBpm"])
check(points(try reader.readPlot(seriesQuery), metric: "heartRateBpm").count == 3, "parent and raw series visible before deletion")
try archive.append(WorkoutEvent(workoutId: seriesRide.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(5), elapsedSeconds: 5, payload: ["sampleUUID": .string(parentID), "deleted": .bool(true), "representation": .string("deletedSample")]))
check(points(try reader.readPlot(seriesQuery), metric: "heartRateBpm").isEmpty, "parent deletion invalidates all derived series points and cache")
var historicalSeries = 0, selectedSeries = 0, allSeries = 0
try archive.forEachEvent(id: seriesRide.id, revision: seriesRevision, selectedOnly: true) { _ in historicalSeries += 1 }
try archive.forEachEvent(id: seriesRide.id, selectedOnly: true) { _ in selectedSeries += 1 }
try archive.forEachEvent(id: seriesRide.id) { _ in allSeries += 1 }
check(historicalSeries == 3 && selectedSeries == 0 && allSeries == 4, "immutable historical snapshot keeps originals while current selection applies group deletion")
try archive.append(WorkoutEvent(workoutId: seriesRide.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(1.5), elapsedSeconds: 1.5, payload: ["sampleUUID": .string(parentID), "representation": .string("rawSeries"), "heartRateBpm": .number(250)]))
check(selected(try reader.inspectAt(MonitorRequest(source: "workout", id: seriesRide.id, seconds: 1.5, metrics: ["heartRateBpm"])), metric: "heartRateBpm") == nil, "late child after parent tombstone is absent from exact inspection")
let deletedStats = try reader.rangeStats(MonitorRequest(source: "workout", id: seriesRide.id, startSeconds: 0, endSeconds: 10, metrics: ["heartRateBpm"]))
check(statistics(deletedStats, metric: "heartRateBpm")?["count"] as? Int == 0 || statistics(deletedStats, metric: "heartRateBpm") == nil, "deleted raw series never contributes to extrema/statistics")
let nearestBefore = try reader.inspectAt(MonitorRequest(source: "workout", id: preciseRide.id, seconds: 1.0000009, metrics: ["humanPowerW"]))
check(selected(nearestBefore)?["value"] as? Double == 200, "backward seek orders originals before stable identity")
let nearestAfter = try reader.inspectAt(MonitorRequest(source: "workout", id: preciseRide.id, seconds: 1.0000001, metrics: ["humanPowerW"]))
check(selected(nearestAfter)?["value"] as? Double == 100, "forward seek orders originals before stable identity")

// Screen-space selection carries the original physical identity, including multiple vertices at one time.
let anchorRide = try archive.create(startedAt: start, indoor: true, watchEnabled: false)
var anchorEvents: [WorkoutEvent] = []
for (index, reading) in [(5.0, 100.0), (5.0, 900.0), (10.0, 110.0), (10.0, 800.0)].enumerated() {
  let event = try WorkoutEvent(workoutId: anchorRide.id, kind: "telemetry", source: "cyc",
    timestamp: start.addingTimeInterval(reading.0), elapsedSeconds: reading.0,
    payload: ["humanPowerW": .number(reading.1), "cadenceRpm": .number(Double(70 + index))],
    eventId: String(format: "00000000-0000-4000-8000-%012d", index + 1))
  anchorEvents.append(event); try archive.append(event)
}
let anchorIdentities = try anchorEvents.map { try PowerLogStore.physicalIdentity($0) }
func anchoredRequest(_ identity: String, at seconds: Double = 5, metric: String = "humanPowerW") -> MonitorRequest {
  MonitorRequest(source: "workout", id: anchorRide.id, seconds: seconds, metrics: ["humanPowerW", "cadenceRpm"],
    anchor: MonitorObservationAnchor(metric: metric, observationId: identity))
}
let unanchored = try reader.inspectAt(MonitorRequest(source: "workout", id: anchorRide.id, seconds: 5, metrics: ["humanPowerW", "cadenceRpm"]))
let anchoredPeak = try reader.inspectAt(anchoredRequest(anchorIdentities[1]))
check(selected(unanchored)?["value"] as? Double == 100, "Unanchored tied-time lookup retains its established deterministic choice")
check(selected(anchoredPeak)?["value"] as? Double == 900 && selected(anchoredPeak)?["observationId"] as? String == anchorIdentities[1],
      "Anchored inspect returns the actual visible spike, not another original sharing its timestamp")
check(selected(anchoredPeak, metric: "cadenceRpm")?["observationId"] as? String == selected(unanchored, metric: "cadenceRpm")?["observationId"] as? String,
      "Other metrics keep nearest-time lookup independently of the anchored metric")
check(selected(try reader.inspectAt(anchoredRequest(anchorIdentities[1], at: 5.0000005)))?["value"] as? Double == 900,
      "Anchors tolerate the existing one-microsecond boundary roundoff")
check(selected(try reader.inspectAt(anchoredRequest(anchorIdentities[1], at: 5.01))) == nil,
      "Mismatched-time anchor is unavailable and never falls back to a nearby original")
check(selected(try reader.inspectAt(anchoredRequest("missing-observation"))) == nil, "Missing identity does not silently choose a timestamp tie")
check(selected(try reader.inspectAt(anchoredRequest("' OR 1=1 --"))) == nil, "Opaque identity is bound as SQL data, never an executable predicate")
let unchangedCount = try canonical.read { try $0.scalarInt("SELECT count(*) FROM observations") }
_ = try reader.inspectAt(anchoredRequest("'; DELETE FROM observations; --"))
check(try canonical.read { try $0.scalarInt("SELECT count(*) FROM observations") } == unchangedCount,
      "SQL-looking identity cannot change or expand the original-data query")
var wrongCollection = anchoredRequest(anchorIdentities[1]); wrongCollection.id = preciseRide.id
check(selected(try reader.inspectAt(wrongCollection)) == nil, "Physical identity from another source collection is unavailable")
var otherChannelID = ""
for (source, value) in [("phone", 120.0), ("watch", 160.0)] {
  let sample = try WorkoutEvent(workoutId: anchorRide.id, kind: "health", source: source,
    timestamp: start.addingTimeInterval(5), elapsedSeconds: 5, payload: ["heartRateBpm": .number(value)])
  try archive.append(sample)
  if source == "watch" { otherChannelID = try PowerLogStore.physicalIdentity(sample) }
}
let selectedOwner = try reader.inspectAt(MonitorRequest(source: "workout", id: anchorRide.id, seconds: 5, metrics: ["heartRateBpm"]))
check(selected(selectedOwner, metric: "heartRateBpm")?["value"] as? Double == 120, "Fixture selects the configured phone health channel")
let wrongChannel = try reader.inspectAt(MonitorRequest(source: "workout", id: anchorRide.id, seconds: 5, metrics: ["heartRateBpm"],
  anchor: MonitorObservationAnchor(metric: "heartRateBpm", observationId: otherChannelID)))
check(selected(wrongChannel, metric: "heartRateBpm") == nil, "Identity anchor cannot bypass the selected metric's source channel")

var intervalAnchors = MonitorRequest(source: "workout", id: anchorRide.id, startSeconds: 5, endSeconds: 10,
  metrics: ["humanPowerW", "cadenceRpm"], includeEndpoints: true,
  startAnchor: MonitorObservationAnchor(metric: "humanPowerW", observationId: anchorIdentities[1]),
  endAnchor: MonitorObservationAnchor(metric: "humanPowerW", observationId: anchorIdentities[3]))
let intervalResult = try reader.rangeStats(intervalAnchors)
let endpoints = intervalResult["endpoints"] as! [String: [String: Any]]
check((endpoints["start"]?["humanPowerW"] as? [String: Any])?["observationId"] as? String == anchorIdentities[1]
      && (endpoints["end"]?["humanPowerW"] as? [String: Any])?["observationId"] as? String == anchorIdentities[3],
      "Range included A/B endpoints retain their independently anchored original identities")
check(statistics(intervalResult)?["count"] as? Int == 4, "Endpoint anchors do not alter exact range membership or counts")
intervalAnchors.endSeconds = 5; intervalAnchors.endAnchor = MonitorObservationAnchor(metric: "humanPowerW", observationId: anchorIdentities[0])
let tiedEndpoints = try reader.rangeStats(intervalAnchors)["endpoints"] as! [String: [String: Any]]
check((tiedEndpoints["start"]?["humanPowerW"] as? [String: Any])?["value"] as? Double == 900
      && (tiedEndpoints["end"]?["humanPowerW"] as? [String: Any])?["value"] as? Double == 100,
      "A/B may name distinct genuine originals at exactly the same elapsed time")
for invalid in ["", String(repeating: "x", count: 513), "invalid\u{0000}identity", String(repeating: "é", count: 257)] {
  rejected({ _ = try reader.inspectAt(anchoredRequest(invalid)) }, "Reject empty, oversized or control-character observation identity")
}
rejected({ _ = try reader.inspectAt(anchoredRequest(anchorIdentities[1], metric: "unknown")) }, "Reject anchor metric outside native allowlist")
rejected({ _ = try reader.inspectAt(anchoredRequest(anchorIdentities[1], metric: "batteryVoltageV")) }, "Reject anchor metric not requested")
var missingAnchorTime = anchoredRequest(anchorIdentities[1]); missingAnchorTime.seconds = nil
rejected({ _ = try reader.inspectAt(missingAnchorTime) }, "Reject identity anchor without requested time")

var deletedPeak = try WorkoutEvent(workoutId: anchorRide.id, kind: "telemetry", source: "cyc",
  timestamp: start.addingTimeInterval(5), elapsedSeconds: 5, payload: ["humanPowerW": .number(900), "cadenceRpm": .number(71)])
deletedPeak.payload["supersedesEventId"] = .string(anchorEvents[1].eventId)
deletedPeak.payload["deleted"] = .bool(true)
try archive.append(deletedPeak)
check(selected(try reader.inspectAt(anchoredRequest(anchorIdentities[1]))) == nil,
      "Deleted or corrected original cannot be resurrected by its retained anchor")
check(selected(try reader.inspectAt(anchoredRequest(anchorIdentities[0])))?["value"] as? Double == 100,
      "An independently selected original at the deleted spike's timestamp remains available")
_ = try canonical.markWorkoutDeleted(id: anchorRide.id)
rejected({ _ = try reader.inspectAt(anchoredRequest(anchorIdentities[0])) }, "Deleted workout rejects anchored original lookup")
print("Canonical native monitor: \(assertions) assertions passed")
