import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-monitor-distance-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: store)
let monitor = MonitorDataStore(root: root)
let distance = WorkoutDistanceStore(store: store)
final class PaceClock: @unchecked Sendable {
  private let lock = NSLock()
  private var seconds = 0.0
  private var step = WorkoutDistanceStore.pacedRebuildSeconds
  func now() -> Double {
    lock.lock()
    defer { lock.unlock() }
    seconds += step
    return seconds
  }
  func hold(_ value: Double) {
    lock.lock()
    defer { lock.unlock() }
    seconds = value
    step = 0
  }
}
let paceClock = PaceClock()
WorkoutDistanceStore.clockForTesting = paceClock.now
let start = Date(timeIntervalSince1970: 1_780_000_000)
func timestamp(_ seconds: Double) -> String { WorkoutCoding.timestamp(start.addingTimeInterval(seconds)) }
func create(watch: Bool = false, indoor: Bool = false, health: Bool = false) throws -> String {
  let value = try archive.create(
    startedAt: start, indoor: indoor, watchEnabled: watch, saveToHealth: health, recordGPS: true, sampleHz: 4)
  try archive.update(id: value.id, phase: "running")
  return value.id
}
func append(
  _ id: String, time: Double, kind: String, source: String, payload: [String: WorkoutJSON],
  identity: String = UUID().uuidString.lowercased()
) throws {
  try archive.append(
    WorkoutEvent(
      workoutId: id, kind: kind, source: source, timestamp: start.addingTimeInterval(time), elapsedSeconds: time,
      payload: payload, eventId: identity))
}
func location(
  _ id: String, time: Double, longitude: Double, source: String = "phone", accuracy: Double = 3, speed: Double = 5,
  speedAccuracy: Double? = 1, course: Double? = nil
) throws {
  var payload: [String: WorkoutJSON] = [
    "latitude": .number(0), "longitude": .number(longitude), "horizontalAccuracyM": .number(accuracy),
    "speedMps": .number(speed),
  ]
  if let speedAccuracy { payload["speedAccuracyMps"] = .number(speedAccuracy) }
  if let course { payload["courseDegrees"] = .number(course) }
  try append(id, time: time, kind: "location", source: source, payload: payload)
}
func request(
  _ id: String, metrics: [String] = ["distanceMeters"], from: Double? = nil, to: Double? = nil, at: Double? = nil,
  buckets: Int = 16, anchor: MonitorObservationAnchor? = nil, selection: String = "auto"
) throws -> MonitorRequest {
  let revision = String(try store.collection(id: id).int("revision")!)
  return MonitorRequest(
    source: "workout", id: id, generation: 1,
    expectedRevision: revision, startSeconds: from,
    endSeconds: to, seconds: at, metrics: metrics, buckets: buckets, anchor: anchor, distanceSource: selection)
}
func points(_ value: [String: Any], metric: String = "distanceMeters") -> [[String: Any]] {
  (value["series"] as? [String: [[String: Any]]])?[metric] ?? []
}
func settledPlot(_ request: MonitorRequest) throws -> [String: Any] {
  for _ in 0..<200 {
    let value = try monitor.readPlot(request)
    if value["status"] as? String == "ok" { return value }
    Thread.sleep(forTimeInterval: 0.005)
  }
  fatalError("Synthetic distance profile never completed its pending build")
}
func selected(_ value: [String: Any], metric: String = "distanceMeters") -> [String: Any]? {
  (value["points"] as? [String: Any])?[metric] as? [String: Any]
}
func statistics(_ value: [String: Any], metric: String = "distanceMeters") -> [String: Any]? {
  (value["statistics"] as? [String: [String: Any]])?[metric]
}
func originalFingerprint() throws -> [Data] {
  try store.read { db in
    try db.rows("SELECT content_hash FROM observations ORDER BY id LIMIT 256", limit: 256).compactMap {
      $0.data("content_hash")
    }
  }
}

do {
  var clock = TelemetryCaptureClock(origin: 100, wallOrigin: start)
  monitor.beginLive(startedAt: timestamp(0), monotonic: 100)
  func frame(_ time: Double, speed: Double) throws {
    let sample = try clock.observation(
      testGeneratedTelemetryReading([
        "humanPowerW": 180, "cadenceRpm": 80, "motorInputPowerW": 1800, "controllerSpeedMps": speed,
      ]),
      connectionEpoch: clock.epochID, monotonic: 100 + time, wall: start.addingTimeInterval(time))
    do { try monitor.appendLive(sample, elapsedSeconds: time) } catch { fatalError("Preview append failed: \(error)") }
  }
  func read(_ time: Double, smooth: Int = 0) throws -> [String: Any] {
    var request = MonitorRequest(source: "live", startSeconds: time, endSeconds: time)
    request.energySmoothingSeconds = smooth
    do { return try monitor.readEnergyPerDistance(request) } catch {
      fatalError("Preview energy query failed: \(error)")
    }
  }
  try frame(1, speed: 5)
  let first = try read(1)
  check(selected(first, metric: "batteryWhPerKm")?["value"] as? Double == 100, "preview graphs first battery frame")
  check(selected(first, metric: "riderWhPerKm")?["value"] as? Double == 10, "preview graphs first rider frame")
  try frame(2, speed: 10)
  for smooth in [0, 5, 30, 60] {
    let value = try read(2, smooth: smooth)
    check(selected(value, metric: "batteryWhPerKm") != nil, "preview graph supports smoothing")
    check(
      (value["averages"] as? [String: Any])?["batteryWhPerKm"] is NSNull,
      "preview never manufactures a whole-ride average")
    check(statistics(value, metric: "batteryWhPerKm")?["ratio"] == nil, "preview does not accumulate a ride ratio")
  }
  try frame(3, speed: 0)
  check(try selected(read(3), metric: "batteryWhPerKm") == nil, "stationary preview has no Wh/km sample")
}

func exportedMeters(_ id: String, distanceSource: String = "auto") throws -> Double? {
  let snapshot = try distance.snapshot(id: id, selection: distanceSource)
  guard snapshot.source != nil else { return nil }
  var total = 0.0
  var cursor: WorkoutDistanceCursor?
  while true {
    let job = try distance.intervals(snapshot: snapshot, after: cursor, limit: 128) { interval in
      total += interval.meters
      return true
    }
    guard job.examined == 128, let last = job.last else { return total }
    cursor = last
  }
}

let gps = try create()
for i in 0...4 { try location(gps, time: Double(i), longitude: Double(i) * 0.00005) }
let hashes = try originalFingerprint()
let firstPlot = try monitor.readPlot(request(gps))
let curve = points(firstPlot)
check(firstPlot["status"] as? String == "ok" && curve.count >= 2, "GPS-only local ride produces a distance curve")
check(
  curve.first?["value"] as? Double == 0 && (curve.last?["value"] as? Double ?? 0) > 20,
  "curve has a true derived start boundary and cumulative increments")
check(curve.allSatisfy { $0["derived"] as? Bool == true }, "derived points are explicitly distinguished from originals")
let snapshot = try distance.snapshot(id: gps)
check(
  abs((curve.last?["value"] as? Double ?? 0) - (snapshot.totalMeters ?? 0)) < 1e-9,
  "Monitor and shared profile use identical total")
let source = ((firstPlot["metricSources"] as? [String: Any])?["distanceMeters"] as? [String: Any])
check(
  source?["source"] as? String == "gps:phone" && source?["estimated"] as? Bool == false,
  "GPS provenance survives the response")
let descriptor = try monitor.describeSource(MonitorRequest(source: "workout", id: gps))
check(
  (descriptor["availableMetrics"] as? [String])?.contains("distanceMeters") == true,
  "distance availability is derived, independent of Health snapshot columns")
check(descriptor["warnings"] == nil, "Monitor source description carries no ride notices")
let last = curve.last!
let identity = last["observationId"] as! String
let time = last["elapsedSeconds"] as! Double
check(
  selected(try monitor.inspectAt(request(gps, at: time + 0.0000001)))?["observationId"] as? String == identity,
  "N1 collapses a submicrosecond target onto the observed distance endpoint")
check(
  selected(try monitor.inspectAt(request(gps, at: time)))?["observationId"] as? String == identity,
  "Unanchored distance inspection retains an exact endpoint hit")
check(
  selected(try monitor.inspectAt(request(gps, at: time + 0.0000011))) == nil,
  "Distance inspection beyond the normalized observed extent never extrapolates")
let anchor = MonitorObservationAnchor(metric: "distanceMeters", observationId: identity)
let anchored = try monitor.inspectAt(request(gps, at: time, anchor: anchor))
check(
  selected(anchored)?["observationId"] as? String == identity,
  "indexed derived identity lookup restores exact curve point")
check(
  selected(anchored)?["timestamp"] as? String == timestamp(time),
  "derived boundary retains actual original boundary time")
let profileRowsBefore = try store.read { try $0.scalarInt("SELECT count(*) FROM distance_points")! }
var cursorInputPages = 0
WorkoutDistanceStore.inputPageObserverForTesting = { _ in cursorInputPages += 1 }
for _ in 0..<50 {
  let inspected = try monitor.inspectAt(request(gps, at: time, anchor: anchor))
  check(
    selected(inspected)?["observationId"] as? String == identity, "repeated cursor lookup retains one fixed generation")
}
WorkoutDistanceStore.inputPageObserverForTesting = nil
check(cursorInputPages == 0, "production cursor path performs zero original input-page reads")
check(
  try store.read { try $0.scalarInt("SELECT count(*) FROM distance_points")! } == profileRowsBefore,
  "cursor reads do not rebuild or append projection rows")
let range = statistics(try monitor.rangeStats(request(gps, from: 0.5, to: 2.5)))!
check(
  abs((range["distance"] as? Double ?? 0) - (snapshot.totalMeters ?? 0) / 2) < 0.00001,
  "range clips GPS chord contributions explicitly")
check(
  range["coveredSeconds"] as? Double == 2 && range["sampleMean"] == nil && range["integral"] == nil,
  "distance statistics contain meters and coverage, never cumulative mean or metre-seconds")
check(try originalFingerprint() == hashes, "plot, cursor, and range reads preserve original content hashes")

try location(gps, time: 5, longitude: 0.00025, accuracy: 100)
try location(gps, time: 6, longitude: 0.00030)
try location(gps, time: 7, longitude: 0.00035)
let gapPlot = try monitor.readPlot(request(gps, from: 0, to: 7, buckets: 1))
check(points(gapPlot).count <= 6, "derived plot work/output remains bounded by pixel buckets")
check(
  points(gapPlot).last?["startsSegment"] as? Bool == false,
  "M4 retains rendering continuity across a short rejected-fix gap")
check(
  selected(try monitor.inspectAt(request(gps, at: 5))) == nil,
  "short explicit gap is not filled by nearest distance boundary")
try location(gps, time: 2.5, longitude: 0.000125)
_ = try monitor.readPlot(request(gps))
let staleAnchor = try monitor.inspectAt(request(gps, at: time, anchor: anchor))
check(
  selected(staleAnchor)?["observationId"] as? String != identity
    && selected(staleAnchor)?["elapsedSeconds"] as? Double == time,
  "Unmatched distance anchor falls back to the valid nearest point in the rebuilt generation")

let speed = try create()
try location(speed, time: 0, longitude: 0, speed: 3, speedAccuracy: nil, course: 359)
try location(speed, time: 1, longitude: 0.00005, speed: 4, speedAccuracy: -1, course: 1)
try location(speed, time: 2, longitude: 0.00010, speed: 5, speedAccuracy: 0, course: 2)
try append(
  speed, time: 1, kind: "health", source: "watch",
  payload: [
    "healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingSpeed"), "value": .number(8), "unit": .string("m/s"),
    "representation": .string("rawSeries"),
  ])
try append(
  speed, time: 2, kind: "health", source: "watch",
  payload: [
    "healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingSpeed"), "value": .number(99),
    "sampleCount": .number(5), "unit": .string("m/s"), "representation": .string("rawQuantity"),
  ])
try append(
  speed, time: 3, kind: "health", source: "watch",
  payload: [
    "healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingSpeed"), "speedMps": .number(9),
    "unit": .string("m/s"), "representation": .string("builderMostRecent"),
  ])
let speeds = try monitor.readPlot(request(speed, metrics: ["speedMps", "courseDegrees"], buckets: 16))
check(
  points(speeds, metric: "speedMps").map { $0["value"] as! Double } == [3, 5],
  "negative speed accuracy is excluded while missing historical accuracy remains valid")
check(
  points(speeds, metric: "courseDegrees")[1]["startsSegment"] as? Bool == true,
  "north crossing breaks the plotted course instead of sweeping through180")
check(!MonitorDataStore.metrics.contains("healthSpeedMps"), "Health cycling speed is no monitor metric")
check(
  (try? monitor.readLatest(request(speed, metrics: ["healthSpeedMps"]))) == nil,
  "a Health cycling speed request is rejected")
let courseStats = statistics(
  try monitor.rangeStats(request(speed, metrics: ["courseDegrees"], from: 0, to: 3)), metric: "courseDegrees")!
check(
  courseStats["sampleMean"] == nil && courseStats["integral"] == nil,
  "course has no arithmetic mean or generic integral")
for i in 0...2 {
  try append(
    speed, time: Double(i), kind: "telemetry", source: "controller",
    payload: [
      "humanPowerW": .number(100), "cadenceRpm": .number(80), "consumedWh": .number(Double(i)),
      "assistLevel": .number(Double(i)),
    ])
  try append(
    speed, time: Double(i), kind: "health", source: "phone",
    payload: ["activeEnergyKcal": .number(Double(i)), "representation": .string("cumulativeWorkoutTotal")])
}
let semantics = try monitor.rangeStats(
  request(speed, metrics: ["consumedWh", "assistLevel", "activeEnergyKcal", "humanPowerW"], from: 0, to: 2))
for metric in ["consumedWh", "assistLevel", "activeEnergyKcal"] {
  let stats = statistics(semantics, metric: metric)!
  check(stats["sampleMean"] == nil && stats["integral"] == nil, "counter/cumulative/category aggregate suppression")
}
check(
  statistics(semantics, metric: "humanPowerW")?["integral"] as? Double == 200,
  "meaningful rider work integration remains unchanged")

let health = try create(watch: true, health: true)
let healthID = UUID().uuidString.lowercased()
try archive.update(id: health, healthKitUUID: healthID)
try append(
  health, time: 10, kind: "health", source: "watch",
  payload: [
    "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "value": .number(100),
    "unit": .string("m"), "representation": .string("rawQuantity"), "sampleCount": .integer(1),
    "sampleStart": .string(timestamp(0)), "sampleEnd": .string(timestamp(10)),
    "associatedWorkoutUUID": .string(healthID),
  ])
let healthPlot = try monitor.readPlot(request(health))
check(
  points(healthPlot).isEmpty && (healthPlot["metricSources"] as? [String: Any])?["distanceMeters"] == nil,
  "associated Health cycling distance draws no distance curve")
let finalOnly = try create(watch: true, health: true)
try append(
  finalOnly, time: 10, kind: "health", source: "watch",
  payload: ["distanceMeters": .number(1_000), "representation": .string("finalWorkoutTotal")])
check(
  points(try monitor.readPlot(request(finalOnly))).isEmpty, "Health final-only total is not a fabricated distance curve"
)

for saves in [false, true] {
  let id = try create(health: saves)
  for i in 0...4 { try location(id, time: Double(i), longitude: Double(i) * 0.00005) }
  try append(id, time: 2, kind: "lifecycle", source: "phone", payload: ["action": .string("lap")])
  _ = try archive.finish(id: id, endedAt: start.addingTimeInterval(4))
  let plot = try monitor.readPlot(request(id))
  let meters = points(plot).last!["value"] as! Double
  let summary = try WorkoutAnalysis.summarize(archive: archive, id: id)
  let exported = try exportedMeters(id)!
  check(abs(meters - snapshot.totalMeters!) < 1e-9, "Health opt-in does not change GPS chart distance")
  check(
    summary.distanceMeters == meters && abs(exported - meters) < 1e-9,
    "summary and FIT distance intervals share Monitor's revisioned distance total")
}
try append(health, time: 5, kind: "lifecycle", source: "phone", payload: ["action": .string("lap")])
_ = try archive.finish(id: health, endedAt: start.addingTimeInterval(10))
check(try exportedMeters(health) == nil, "Health cycling distance gives FIT no distance intervals")
let controller = try create(indoor: true)
for i in 0...2 {
  try append(
    controller, time: Double(i), kind: "telemetry", source: "controller",
    payload: [
      "humanPowerW": .number(100), "cadenceRpm": .number(80), "controllerSpeedMps": .number(4),
      "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(controller),
      "connectionEpoch": .string("connection"), "clockEpoch": .string("clock"),
    ])
}
let controllerPlot = try monitor.readPlot(request(controller))
check(
  points(controllerPlot).last?["value"] as? Double == 8, "indoor controller-only ride receives the known-unit integral")
let controllerSource = (controllerPlot["metricSources"] as! [String: [String: Any]])["distanceMeters"]!
check(
  controllerSource["estimated"] as? Bool == true && controllerSource["label"] as? String == "Controller estimate",
  "controller provenance is explicitly estimated")

_ = try archive.finish(id: gps, endedAt: start.addingTimeInterval(7))
for selection in ["health:phone", "health:watch"] {
  check((try? monitor.readPlot(request(gps, selection: selection))) == nil, "a Health distance selection is rejected")
}
let frozen = try settledPlot(request(gps, selection: "gps:watch"))
check(!points(frozen).isEmpty, "A later request cannot replace the source frozen at Start")
check(
  (frozen["metricSources"] as? [String: [String: Any]])?["distanceMeters"]?["source"] as? String == "gps:phone",
  "Distance provenance stays phone GPS")
let immutableRevision = try archive.revision(id: gps)
_ = try settledPlot(request(gps, selection: "controller"))
check(try archive.revision(id: gps) == immutableRevision, "Reading another requested source leaves the ride unchanged")
let beforeAppend = try store.collection(id: speed).int("revision")!
try location(speed, time: 4, longitude: 0.0002)
var changes = MonitorRequest(source: "workout", id: speed)
changes.sinceRevision = String(beforeAppend)
let changed = try monitor.changesSince(changes)["changes"] as! [[String: Any]]
check(
  changed.contains { ($0["metrics"] as? [String])?.contains("distanceMeters") == true },
  "GPS source changes advertise derived distance invalidation")
let beforeMetadata = try store.collection(id: speed).int("revision")!
try archive.update(id: speed, phase: "paused")
changes.sinceRevision = String(beforeMetadata)
let broad = try monitor.changesSince(changes)["changes"] as! [[String: Any]]
check(
  broad.contains { ($0["metrics"] as? [String])?.isEmpty == true },
  "metadata changes retain the all-metrics invalidation sentinel")
for boundary in ["interruption", "epoch", "connection"] {
  let id = try create(watch: true)
  for (index, time) in [0.1, 0.4, 0.5, 0.9].enumerated() {
    let resumed = index >= 2
    let epoch = boundary == "epoch" && resumed ? "resumed" : "initial"
    let connection = boundary == "connection" && resumed ? "reconnected" : "connection"
    try append(
      id, time: time, kind: "telemetry", source: "controller",
      payload: [
        "humanPowerW": .number(resumed ? 300 : 100), "cadenceRpm": .number(resumed ? 90 : 70),
        "clockEpoch": .string(epoch), "connectionEpoch": .string(connection),
      ])
    try append(
      id, time: time, kind: "health", source: "watch",
      payload: [
        "heartRateBpm": .number(resumed ? 160 : 100), "representation": .string("rawSeries"),
        "clockEpoch": .string(epoch),
      ])
    try append(
      id, time: time, kind: "location", source: "phone",
      payload: [
        "latitude": .number(0), "longitude": .number(time * 0.00005), "horizontalAccuracyM": .number(3),
        "altitudeMeters": .number([0, 10, 1000, 1010][index]), "verticalAccuracyM": .number(0),
        "speedMps": .number(5), "speedAccuracyMps": .number(1), "clockEpoch": .string(epoch),
      ])
  }
  if boundary == "interruption" {
    let resumed = try WorkoutEvent(
      workoutId: id, kind: "lifecycle", source: "watch", timestamp: start.addingTimeInterval(900),
      elapsedSeconds: 0.4, payload: ["action": .string("resume")],
      eventId: "00000000-0000-4000-8000-000000000001")
    let interrupted = try WorkoutEvent(
      workoutId: id, kind: "lifecycle", source: "watch", timestamp: start.addingTimeInterval(-900),
      elapsedSeconds: 0.4,
      payload: ["action": .string("pause"), "interrupted": .bool(true), "controllerSequence": .string("2")],
      eventId: "ffffffff-ffff-4fff-8fff-ffffffffffff")
    _ = try store.appendBatch([resumed], producer: "owner", firstSequence: 2)
    _ = try store.appendBatch([interrupted], producer: "owner", firstSequence: 1)
  }
  _ = try archive.update(id: id, stopElapsedSeconds: 1)
  _ = try archive.finish(id: id, endedAt: start.addingTimeInterval(1))
  let summary = try WorkoutAnalysis.summarize(archive: archive, id: id)
  check(
    summary.elapsedSeconds == 1 && summary.timerSeconds == 1, "\(boundary) retains measured elapsed and active timing")
  check(
    abs(summary.telemetryCoveredSeconds - 0.7) < 1e-9 && abs((summary.riderWorkJoules ?? 0) - 150) < 1e-9,
    "\(boundary) never integrates rider power across the boundary")
  check(
    abs((summary.averageCadenceRpm ?? 0) - (70 * 0.3 + 90 * 0.4) / 0.7) < 1e-9,
    "\(boundary) cadence uses only continuous observed edges")
  let expectedHealthCoverage = boundary == "connection" ? 0.8 : 0.7
  check(
    abs(summary.heartRateCoveredSeconds - expectedHealthCoverage) < 1e-9,
    "\(boundary) applies process interruption evidence to heart rate independently of BLE")
  let profile = try distance.snapshot(id: id)
  check(
    abs(profile.coveredSeconds - expectedHealthCoverage) < 1e-9,
    "\(boundary) distance follows producer lifecycle ordering and process epochs")
  check(
    abs((summary.gpsDistanceMeters ?? 0) - (profile.totalMeters ?? 0)) < 1e-9,
    "\(boundary) FIT geometry agrees with persisted distance intervals")
  if boundary != "connection" {
    check(summary.ascentMeters == 20, "\(boundary) excludes the altitude jump across process downtime")
    check(
      summary.routePreview.filter { $0["startsSegment"] == 1 }.count == 2,
      "\(boundary) route preview preserves separate runs")
  }
}
for sameEpoch in [false, true] {
  let id = try create(indoor: true)
  for (index, time) in [0.0, 1.0, 1.0, 1.1].enumerated() {
    let epoch = index < 2 || sameEpoch ? "before-restart" : "after-restart"
    try append(
      id, time: time, kind: "telemetry", source: "controller",
      payload: [
        "humanPowerW": .number(index < 2 ? 100 : 200), "cadenceRpm": .number(80),
        "controllerSpeedMps": .number(10), "controllerModel": .string("X12"), "controllerProtocol": .string("5.3"),
        "captureSessionID": .string("bike"), "connectionEpoch": .string("connection"), "clockEpoch": .string(epoch),
      ], identity: String(id.prefix(24)) + String(format: "%012d", 9 - index))
    try append(
      id, time: time, kind: "location", source: "phone",
      payload: [
        "latitude": .number(0), "longitude": .number(time * 0.00001), "horizontalAccuracyM": .number(3),
        "speedMps": .number(1), "clockEpoch": .string(epoch),
      ])
    if index == 1 {
      try phoneFixtureInterruption(
        archive: archive, id: id, epoch: "before-restart",
        timing: WorkoutOwnerTiming(timestamp: timestamp(1), elapsedSeconds: 1, timerSeconds: 1))
      try append(
        id, time: 1, kind: "lifecycle", source: "phone",
        payload: ["action": .string("resume"), "clockEpoch": .string("after-restart")])
    }
  }
  try archive.update(id: id, stopElapsedSeconds: 1.1)
  try archive.finish(id: id, endedAt: start.addingTimeInterval(1000))
  do {
    let plot = try monitor.readPlot(request(id, metrics: ["humanPowerW"], from: 0, to: 1.1, buckets: 1))
    check(
      points(plot, metric: "humanPowerW").map { $0["startsSegment"] as! Bool } == [true, false, true, false],
      "a resumed observation exactly at the interruption starts one new run")
    let stats = try monitor.rangeStats(request(id, metrics: ["humanPowerW"], from: 0, to: 1.1))
    let power = (stats["statistics"] as? [String: [String: Any]])?["humanPowerW"]
    check(
      abs((power?["integral"] as? Double ?? -1) - 120) < 1e-9,
      "monitor integrates the equal-time resumed edge without crossing the interruption")
    let beforeBoundary = try monitor.inspectAt(request(id, metrics: ["humanPowerW"], at: 0.75))
    check(
      (beforeBoundary["points"] as? [String: [String: Any]])?["humanPowerW"]?["value"] as? Double == 100,
      "inspection before the boundary selects its predecessor run despite reversed UUIDs")
    let clipped = try monitor.rangeStats(request(id, metrics: ["humanPowerW"], from: 0, to: 0.75))
    check(
      abs((statistics(clipped, metric: "humanPowerW")?["integral"] as? Double ?? -1) - 75) < 1e-9,
      "clipped statistics retain the valid edge into the earlier equal-time observation")
    let inspected = try monitor.inspectAt(request(id, metrics: ["humanPowerW"], at: 1.04))
    check(
      (inspected["points"] as? [String: [String: Any]])?["humanPowerW"]?["value"] as? Double == 200,
      "inspection between resumed cutoff and its next observation stays on the resumed side")
    let summary = try WorkoutAnalysis.summarize(archive: archive, id: id, distanceSource: "controller")
    check(
      abs(summary.telemetryCoveredSeconds - 1.1) < 1e-9 && abs((summary.riderWorkJoules ?? 0) - 120) < 1e-9,
      "FIT retains the valid integration edge after an equal-time restart")
    let controller = try distance.snapshot(id: id, selection: "controller")
    check(
      abs(controller.coveredSeconds - 1.1) < 1e-9 && abs((controller.totalMeters ?? 0) - 11) < 1e-9,
      "controller distance assigns equal-time endpoints to their retained epochs")
  }
  for selection in ["controller", "gps:phone"] {
    let curve = points(try settledPlot(request(id, from: 0, to: 1.1, selection: selection)))
    check(
      curve.map { $0["startsSegment"] as! Bool } == [true, false, true, false],
      "\(selection) hard interruption breaks distance at zero elapsed gap, same epoch: \(sameEpoch)")
  }
  let gps = try distance.snapshot(id: id, selection: "gps:phone")
  check(
    abs(gps.coveredSeconds - 1.1) < 1e-9,
    "GPS distance resolves equal-time membership by producer sequence even without an epoch change")
}
let transferred = try create(watch: true)
var watchEvents: [WorkoutEvent] = []
for (index, time) in [0.0, 1.0, 1.0, 1.1].enumerated() {
  let epoch = index < 2 ? "before" : "after"
  watchEvents.append(
    try WorkoutEvent(
      workoutId: transferred, kind: "location", source: "watch", timestamp: start.addingTimeInterval(time),
      elapsedSeconds: time,
      payload: [
        "latitude": .number(0), "longitude": .number(time * 0.00001), "horizontalAccuracyM": .number(3),
        "speedMps": .number(index < 2 ? 1 : 2), "clockEpoch": .string(epoch),
      ]))
  if index == 1 {
    for action in ["pause", "resume"] {
      var payload: [String: WorkoutJSON] = ["action": .string(action), "clockEpoch": .string(epoch)]
      if action == "pause" {
        payload["interrupted"] = .bool(true)
        payload["controllerSequence"] = .string("0")
      }
      watchEvents.append(
        try WorkoutEvent(
          workoutId: transferred, kind: "lifecycle", source: "watch", timestamp: start.addingTimeInterval(1),
          elapsedSeconds: 1, payload: payload))
    }
  }
}
for index in watchEvents.indices.reversed() {
  _ = try archive.appendBatch([watchEvents[index]], producer: "watch", firstSequence: Int64(index + 1))
}
try archive.update(id: transferred, stopElapsedSeconds: 1.1)
try archive.finish(id: transferred, endedAt: start.addingTimeInterval(1000))
let transferredStats = try monitor.rangeStats(request(transferred, metrics: ["speedMps"], from: 0, to: 1.1))
check(
  abs((statistics(transferredStats, metric: "speedMps")?["integral"] as? Double ?? -1) - 1.2) < 1e-9,
  "monitor orders equal-time Watch originals by producer sequence across reversed transfer arrival")
let transferredDistance = try distance.snapshot(id: transferred, selection: "gps:watch")
check(
  abs(transferredDistance.coveredSeconds - 1.1) < 1e-9,
  "distance keyset order preserves the resumed Watch edge across reversed transfer arrival")
for selection in ["controller", "gps:phone"] {
  for gap in [2.0, 6.0, 8.0] {
    let id = try create(indoor: selection == "controller")
    for (index, time) in [0.0, 1.0, 1 + gap, 2 + gap].enumerated() {
      let epoch = index < 2 ? "first" : "second"
      if selection == "controller" {
        try append(
          id, time: time, kind: "telemetry", source: "controller",
          payload: [
            "humanPowerW": .number(100), "cadenceRpm": .number(80),
            "controllerSpeedMps": .number(10), "controllerModel": .string("X12"),
            "controllerProtocol": .string("5.3"), "captureSessionID": .string("bike"),
            "connectionEpoch": .string(epoch), "clockEpoch": .string("clock"),
          ])
      } else {
        try append(
          id, time: time, kind: "location", source: "phone",
          payload: [
            "latitude": .number(0), "longitude": .number(Double(index) * 0.00001),
            "horizontalAccuracyM": .number(3), "speedMps": .number(1), "clockEpoch": .string(epoch),
          ])
      }
    }
    try archive.update(id: id, stopElapsedSeconds: 2 + gap)
    try archive.finish(id: id, endedAt: start.addingTimeInterval(2 + gap))
    let profile = try distance.snapshot(id: id, selection: selection)
    let joined = selection == "controller" && gap < 6
    for buckets in [1, 16] {
      let curve = points(try settledPlot(request(id, from: 0, to: 2 + gap, buckets: buckets, selection: selection)))
      check(
        curve.filter { $0["startsSegment"] as? Bool == true }.count == (gap < 6 ? 1 : 2),
        "\(selection) distance display gap \(gap) at \(buckets) buckets")
      if buckets == 16 {
        check(
          (curve[1]["value"] as? Double == curve[2]["value"] as? Double) != joined,
          "\(selection) distance across a \(gap) s reconnect rises only when joined")
      }
    }
    let stats = statistics(try monitor.rangeStats(request(id, from: 0, to: 2 + gap, selection: selection)))!
    let covered = joined ? 2 + gap : 2
    check(
      profile.coveredSeconds == covered && profile.info.selected?.partial == !joined
        && stats["coveredSeconds"] as? Double == covered && stats["integral"] == nil,
      "\(selection) reconnect display continuity adds coverage only when joined")
    let expected = selection == "controller" ? (joined ? 40.0 : 20.0) : 2.223902
    check(profile.totalMeters == expected, "\(selection) reconnect adds distance only when joined")
    check(
      (selected(try monitor.inspectAt(request(id, at: 1 + gap / 2, selection: selection))) == nil) != joined,
      "\(selection) reconnect is inspectable only when joined")
    let summary = try WorkoutAnalysis.summarize(archive: archive, id: id, distanceSource: selection)
    check(summary.distanceMeters == profile.totalMeters, "\(selection) export summary retains measured distance")
  }
}
let sparseGPS = try create()
for (time, longitude) in [(0.0, 0.0), (1.0, 0.00001), (9.0, 0.00005)] {
  try location(sparseGPS, time: time, longitude: longitude)
}
try archive.update(id: sparseGPS, stopElapsedSeconds: 9)
try archive.finish(id: sparseGPS, endedAt: start.addingTimeInterval(9))
check(
  points(try settledPlot(request(sparseGPS, from: 0, to: 9, selection: "gps:phone"))).filter {
    $0["startsSegment"] as? Bool == true
  }.count == 1,
  "a covered GPS interval longer than the display gap stays connected")
let pagedGPS = try create()
for offset in stride(from: 0, to: 2048, by: 256) {
  _ = try store.appendBatch(
    (offset..<(offset + 256)).map { index in
      let seconds = Double(index) / 8
      return try WorkoutEvent(
        workoutId: pagedGPS, kind: "location", source: "phone", timestamp: start.addingTimeInterval(seconds),
        elapsedSeconds: seconds,
        payload: [
          "latitude": .number(0), "longitude": .number(Double(index) * 0.000001),
          "horizontalAccuracyM": .number(3), "speedMps": .number(5), "speedAccuracyMps": .number(1),
        ])
    })
}
_ = try distance.snapshot(id: pagedGPS)
let pagedMonitor = MonitorDataStore(root: root)
let pagedPlot = try pagedMonitor.readPlot(request(pagedGPS, from: 0, to: 255))
check(pagedPlot["status"] as? String == "ok", "Stored paged GPS chart reads the admitted distance projection")
check(
  pagedMonitor.projectionDiagnostics()["distanceScannedPoints", default: 0] >= 2048,
  "Stored distance page cost covers the original GPS profile")
check(
  pagedMonitor.projectionDiagnostics()["distanceReadVMMaxSteps", default: 0] <= 8192,
  "Stored distance chart pages seek from the cursor without rescanning earlier pages")
let distanceScansBeforeNavigation = pagedMonitor.projectionDiagnostics()["distanceScannedPoints", default: 0]
for i in 0..<20 {
  let window = try request(pagedGPS, from: 0.071 + Double(i) * 0.83, to: 256 - 4.013 + Double(i) * 0.031)
  check(try pagedMonitor.readPlot(window)["status"] as? String == "ok", "Wide distance navigation reads the profile")
}
check(
  pagedMonitor.projectionDiagnostics()["distanceScannedPoints", default: 0] - distanceScansBeforeNavigation <= 20 * 128,
  "Wide distance navigation reads clipped edges instead of full visible profiles")
check(
  pagedMonitor.discardWorkout(id: pagedGPS)
    && pagedMonitor.projectionDiagnostics()["nativeAdapterBytes", default: 0] == 0,
  "Chart cleanup releases distance aggregates and both retained hydration caches")

final class BuildLog: @unchecked Sendable {
  private let lock = NSLock()
  private var counts = (builds: 0, rebuilds: 0)
  func observe(_ sql: String) {
    lock.lock()
    defer { lock.unlock() }
    if sql.hasPrefix("INSERT OR REPLACE INTO distance_snapshots") { counts.builds += 1 }
    if sql.hasPrefix("INSERT INTO distance_generations"), sql.hasSuffix("VALUES(?,?,-1,?)") { counts.rebuilds += 1 }
  }
  var value: (builds: Int, rebuilds: Int) {
    lock.lock()
    defer { lock.unlock() }
    return counts
  }
}
let liveRide = try create(watch: true, health: true)
let liveWorkout = UUID().uuidString.lowercased()
try archive.update(id: liveRide, healthKitUUID: liveWorkout)
func liveDistance(from: Double, to: Double) throws {
  try append(
    liveRide, time: to, kind: "health", source: "watch",
    payload: [
      "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "value": .number((to - from) * 5),
      "unit": .string("m"), "representation": .string("rawQuantity"), "sampleCount": .integer(1),
      "sampleStart": .string(timestamp(from)), "sampleEnd": .string(timestamp(to)),
      "associatedWorkoutUUID": .string(liveWorkout),
    ])
}
let builds = BuildLog()
try store.read { db in db.queryObserverForTesting = { sql, _ in builds.observe(sql) } }
var liveFrame = 0
var unavailableLatest = 0
var receivedDistance = false
for tick in 0..<240 {
  let now = Double(tick) / 4
  paceClock.hold(1_000 + now)
  for _ in 0..<2 {
    try append(
      liveRide, time: Double(liveFrame) / 8, kind: "telemetry", source: "controller",
      payload: [
        "humanPowerW": .number(180), "cadenceRpm": .number(85), "controllerSpeedMps": .number(6),
        "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(liveRide),
        "connectionEpoch": .string("connection"), "clockEpoch": .string("clock"),
      ])
    liveFrame += 1
  }
  if tick % 4 == 0 {
    let second = Double(tick / 4)
    try location(liveRide, time: second, longitude: second * 0.00005, source: "phone")
    if second >= 3 {
      try append(
        liveRide, time: second - 3, kind: "health", source: "watch",
        payload: ["heartRateBpm": .number(140), "representation": .string("rawQuantity")])
    }
    if second >= 9 {
      try append(
        liveRide, time: second - 9, kind: "health", source: "watch",
        payload: [
          "healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingPower"), "value": .number(180),
          "unit": .string("W"), "representation": .string("builderMostRecent"),
        ])
    }
    if second >= 5, Int(second) % 5 == 0 { try liveDistance(from: second - 5, to: second) }
    if Int(second) % 10 == 7 { try liveDistance(from: second - 5, to: second - 3) }
  }
  let latest = try monitor.readLatest(request(liveRide, metrics: ["distanceMeters", "humanPowerW"]))
  if tick % 3 == 0 {
    _ = try monitor.describeSource(MonitorRequest(source: "workout", id: liveRide))
    _ = try monitor.readPlot(request(liveRide, metrics: ["distanceMeters", "humanPowerW"]))
    _ = try monitor.rangeStats(request(liveRide, from: 0, to: now))
  }
  monitor.drainDistanceForTesting()
  if selected(latest) != nil { receivedDistance = true } else if receivedDistance { unavailableLatest += 1 }
}
try store.read { db in db.queryObserverForTesting = nil }
let paced = builds.value
print("Running ride at the live cadence for one minute: \(paced.builds) builds, \(paced.rebuilds) from scratch")
check(paced.builds <= 61, "a running ride polled at the live cadence builds at most once a second: \(paced.builds)")
check(paced.rebuilds <= 7, "and rebuilds from scratch at most once every ten seconds: \(paced.rebuilds)")
check(
  receivedDistance && unavailableLatest == 0, "After the first GPS edge, cached distance stays available between builds"
)
paceClock.hold(1_000 + 60 + WorkoutDistanceStore.pacedRebuildSeconds)
let caughtUp = points(try monitor.readPlot(request(liveRide))).last?["value"] as? Double
let current = try distance.snapshot(id: liveRide).totalMeters
check(caughtUp != nil && caughtUp == current, "once the pace allows a build, the chart reaches the newest originals")
print("Native Monitor distance checks passed: \(assertions) assertions")

// Every ride-derived series shares one pinned projection and remains independently selectable.
do {
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false,
    recordGPS: false, sampleHz: 4, distanceSource: "controller", batteryConsumptionSource: "calculated")
  for t in 0...120 {
    try append(
      ride.id, time: Double(t), kind: "telemetry", source: "controller",
      payload: [
        "humanPowerW": .number(180), "cadenceRpm": .number(80),
        "controllerSpeedMps": .number(t <= 60 ? 5 : 10), "batteryVoltageV": .number(50),
        "batteryCurrentA": .number(36), "motorInputPowerW": .number(1800), "consumedWh": .number(100 + Double(t) * 2),
        "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"),
        "captureSessionID": .string(ride.id), "connectionEpoch": .string("connection"),
        "clockEpoch": .string("clock"), "observationSequence": .string(String(t + 1)),
        "monotonicSeconds": .number(Double(t)),
      ])
  }
  let value = try monitor.readEnergyPerDistance(request(ride.id, metrics: [], from: 0, to: 120))
  let statistics = value["statistics"] as! [String: [String: Any]]
  check(
    abs((statistics["batteryWhPerKm"]?["ratio"] as? Double ?? -1) - 60 / 0.8975) < 1e-8,
    "energy/distance uses selected calculated energy rather than the controller counter")
  check(
    abs((statistics["riderWhPerKm"]?["ratio"] as? Double ?? -1) - 6 / 0.8975) < 1e-8,
    "human work shares the selected distance")
  check((value["series"] as? [String: Any])?.isEmpty == true, "a totals read builds no graph series")
  var graphRequest = try request(ride.id, metrics: [], from: 0, to: 120)
  graphRequest.energyTotals = false
  let series = try monitor.readEnergyPerDistance(graphRequest)["series"] as! [String: [[String: Any]]]
  check(
    series["batteryWhPerKm"]!.contains { ($0["value"] as! Double) > 99.9 },
    "instantaneous graph retains early consumption")
  check(
    series["batteryWhPerKm"]!.contains { ($0["value"] as! Double) < 50.3 },
    "instantaneous graph reveals lower consumption")
  var instantRequest = try request(ride.id, metrics: [], from: 61, to: 61)
  let instant = try monitor.readEnergyPerDistance(instantRequest)
  let instantPoints = instant["points"] as! [String: [String: Any]]
  check(instantPoints["batteryWhPerKm"]?["value"] as? Double == 50, "speed transition immediately changes Wh/km")
  instantRequest.energySmoothingSeconds = 5
  let smooth = try monitor.readEnergyPerDistance(instantRequest)
  let smoothPoints = smooth["points"] as! [String: [String: Any]]
  check(smoothPoints["batteryWhPerKm"]?["value"] as? Double == 95, "optional smoothing uses five seconds")
  for seconds in [30, 60] {
    instantRequest.energySmoothingSeconds = seconds
    instantRequest.energyTotals = false
    let smoothed = try monitor.readEnergyPerDistance(instantRequest)
    let points = smoothed["points"] as! [String: [String: Any]]
    check(
      abs((points["batteryWhPerKm"]!["value"] as! Double) - (100 - 25 / Double(seconds))) < 0.000001,
      "single-point smoothing loads full context")
    check((smoothed["averages"] as! [String: Any])["batteryWhPerKm"] is NSNull, "graph query skips totals")
  }

  let selected = try monitor.readEnergyPerDistance(request(ride.id, metrics: [], from: 60, to: 120))
  let selectedStats = selected["statistics"] as! [String: [String: Any]]
  check(
    abs((selectedStats["batteryWhPerKm"]?["ratio"] as? Double ?? -1) - 30 / 0.5975) < 1e-8,
    "A-B recomputes the ratio of totals")
}

do {
  let ride = try archive.create(
    startedAt: start, indoor: false, watchEnabled: true, saveToHealth: false,
    recordGPS: true, sampleHz: 8, distanceSource: "gps:phone", batteryConsumptionSource: "calculated")
  let id = ride.id
  func batteryFrame(_ time: Double, _ ah: Double, _ wh: Double, connection: String = "first") throws {
    try append(
      id, time: time, kind: "telemetry", source: "controller",
      payload: [
        "humanPowerW": .number(100), "cadenceRpm": .number(80),
        "consumedAh": .number(ah), "consumedWh": .number(wh), "batteryCurrentA": .number(3.6),
        "batteryVoltageV": .number(50), "motorInputPowerW": .number(180), "controllerSpeedMps": .number(5),
        "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"),
        "captureSessionID": .string(id), "connectionEpoch": .string(connection),
        "clockEpoch": .string("clock"), "observationSequence": .string(String(Int(time * 8) + 1)),
        "monotonicSeconds": .number(time),
      ])
  }
  try batteryFrame(0, 25, 1000)
  let initial = try distance.snapshot(id: id)
  for metric in WorkoutDistanceStore.batteryMetrics {
    check(initial.battery[metric]?.total == 0, "first valid input supplies zero for " + metric)
  }
  for index in 0...2 { try location(id, time: Double(index), longitude: Double(index) * 0.00005, speed: 2) }
  try batteryFrame(1, 25.1, 1002)
  try batteryFrame(2, 25.2, 1004)
  let consumption = try monitor.readEnergyPerDistance(request(id, metrics: [], from: 1, to: 1))
  let consumptionPoints = consumption["points"] as! [String: [String: Any]]
  check(
    consumptionPoints["batteryWhPerKm"]?["value"] as? Double == 25,
    "instantaneous consumption uses selected GPS speed rather than controller speed or chord speed")
  let all = ["distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters"] + WorkoutDistanceStore.batteryMetrics
  let chart = try settledPlot(request(id, metrics: all, from: 0, to: 2))
  for metric in all { check(!points(chart, metric: metric).isEmpty, "independent derived plot " + metric) }
  check(
    points(chart, metric: "controllerDistanceMeters").last?["value"] as? Double == 10,
    "controller distance remains available with GPS selected")
  let latest = try monitor.readLatest(request(id, metrics: all))
  check(
    abs((selected(latest, metric: "controllerEnergyWh")?["value"] as? Double ?? -1) - 4) < 1e-9,
    "controller consumption removes the starting balance")
  check(
    abs((selected(latest, metric: "calculatedEnergyWh")?["value"] as? Double ?? -1) - 0.1) < 1e-9,
    "calculated consumption integrates original electrical power")
  let details = try monitor.describeSource(request(id, metrics: all))
  check(Set(details["availableMetrics"] as! [String]).isSuperset(of: all), "derived availability includes every method")
  let inspection = try monitor.inspectAt(request(id, metrics: all, at: 1))
  for metric in all { check(selected(inspection, metric: metric) != nil, "independent exact inspection " + metric) }
  let clipped = try monitor.rangeStats(request(id, metrics: all, from: 0.5, to: 1.5))
  check(
    statistics(clipped, metric: "controllerEnergyWh")?["amount"] as? Double == 0,
    "clipped counter edges are excluded")
  check(
    statistics(clipped, metric: "controllerEnergyWh")?["unresolvedBoundary"] as? Bool == true,
    "clipped counter edges report unresolved boundaries")
  check(
    abs((statistics(clipped, metric: "calculatedEnergyWh")?["amount"] as? Double ?? -1) - 0.05) < 1e-9,
    "integration edges clip by their actual rates")
  try batteryFrame(3, 30, 1500, connection: "second")
  try batteryFrame(4, 30.1, 1502, connection: "second")
  _ = try archive.finish(id: id, endedAt: start.addingTimeInterval(4))
  let summary = try WorkoutAnalysis.summarize(archive: archive, id: id)
  check(summary.batteryConsumption?["source"] == .string("calculated"), "Watch ride preserves phone source choice")
  check(
    abs((summary.batteryConsumption?["energyWh"]?.number ?? -1) - 0.15) < 1e-9,
    "summary uses selected calculated pair and excludes reconnect gap")
  check(summary.batteryConsumption?["partial"] == .bool(true), "reconnect coverage remains partial")
  let full = try distance.snapshot(id: id)
  check(
    abs((full.battery["controllerEnergyWh"]?.total ?? -1) - 6) < 1e-9,
    "an impossible counter jump at a reconnect rebases without catch-up")
  try store.transaction { db in
    try db.execute(
      "DELETE FROM distance_points WHERE generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=?)",
      [.text(id)])
    try db.execute("DELETE FROM distance_snapshots WHERE collection_id=?", [.text(id)])
    try db.execute("DELETE FROM distance_generations WHERE collection_id=?", [.text(id)])
  }
  let rebuilt = try distance.snapshot(id: id)
  for metric in WorkoutDistanceStore.batteryMetrics {
    check(rebuilt.battery[metric]?.total == full.battery[metric]?.total, "disposable cache rebuild " + metric)
  }
  let next = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false,
    recordGPS: false, sampleHz: 8)
  check(try distance.snapshot(id: next.id).battery.isEmpty, "new ride never inherits old consumption")
}
do {
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false,
    recordGPS: false, sampleHz: 8)
  func frame(_ time: Double, _ ah: Double, _ wh: Double, connection: String) throws {
    try append(
      ride.id, time: time, kind: "telemetry", source: "controller",
      payload: [
        "humanPowerW": .number(100), "cadenceRpm": .number(80),
        "consumedAh": .number(ah), "consumedWh": .number(wh), "batteryCurrentA": .number(3.6),
        "batteryVoltageV": .number(50), "motorInputPowerW": .number(180), "controllerSpeedMps": .number(5),
        "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"),
        "captureSessionID": .string(ride.id), "connectionEpoch": .string(connection),
        "clockEpoch": .string("clock"), "observationSequence": .string(String(Int(time * 8) + 1)),
        "monotonicSeconds": .number(time),
      ])
  }
  try frame(0, 25, 1000, connection: "first")
  try frame(1, 25.02, 1001, connection: "first")
  try frame(6, 25.08, 1004, connection: "second")
  try frame(7, 25.1, 1005, connection: "second")
  _ = try archive.finish(id: ride.id, endedAt: start.addingTimeInterval(7))
  let bridged = try distance.snapshot(id: ride.id)
  check(
    abs((bridged.battery["controllerEnergyWh"]?.total ?? -1) - 5) < 1e-9,
    "a short link loss keeps the energy counter's own increase")
  check(
    abs((bridged.battery["controllerChargeAh"]?.total ?? -1) - 0.1) < 1e-9,
    "a short link loss keeps the charge counter's own increase")
  check(
    abs((bridged.battery["calculatedEnergyWh"]?.total ?? -1) - 0.1) < 1e-9,
    "calculated energy still excludes the link loss")
  let crossing = try monitor.rangeStats(request(ride.id, metrics: ["controllerEnergyWh"], from: 3, to: 7))
  check(
    statistics(crossing, metric: "controllerEnergyWh")?["unresolvedBoundary"] as? Bool == true,
    "a range starting inside the link loss cannot split the counter increase")
}
print("Swift derived distance/consumption regressions passed")

do {
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false,
    recordGPS: false, sampleHz: 8)
  try append(
    ride.id, time: 10, kind: "telemetry", source: "controller",
    payload: [
      "humanPowerW": .number(100), "cadenceRpm": .number(80),
      "consumedAh": .number(25), "consumedWh": .number(1000), "batteryCurrentA": .number(3.6),
      "batteryVoltageV": .number(50), "captureSessionID": .string(ride.id),
      "connectionEpoch": .string("connection"), "clockEpoch": .string("clock"),
    ])
  _ = try archive.finish(id: ride.id, endedAt: start.addingTimeInterval(15))
  for (from, to, expected) in [(0.0, 5.0, false), (12.0, 15.0, false), (10.0, 10.0, true), (9.0, 11.0, true)] {
    let result = try monitor.rangeStats(
      request(ride.id, metrics: WorkoutDistanceStore.batteryMetrics, from: from, to: to))
    for metric in WorkoutDistanceStore.batteryMetrics {
      let amount = statistics(result, metric: metric)?["amount"] as? Double
      check(
        expected ? amount == 0 : amount == nil,
        "a baseline is available only in its observed range: \(metric) [\(from), \(to)]")
    }
  }
}
print("Swift battery baseline range availability passed")
