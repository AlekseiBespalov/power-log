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
let start = Date(timeIntervalSince1970: 1_780_000_000)
func timestamp(_ seconds: Double) -> String { WorkoutCoding.timestamp(start.addingTimeInterval(seconds)) }
func create(watch: Bool = false, indoor: Bool = false, health: Bool = false) throws -> String {
  let value = try archive.create(
    startedAt: start, indoor: indoor, watchEnabled: watch, saveToHealth: health, recordGPS: true)
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
    expectedRevision: selection == "auto" ? revision : "distance:\(selection):\(revision)", startSeconds: from,
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

// Reproduce the production symptom: GPS originals with Health off and no cumulative Health snapshots.
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
  selected(try monitor.inspectAt(request(gps, at: time + 0.0000001))) == nil,
  "Unanchored distance inspection outside the observed extent does not borrow a nearby endpoint")
check(
  selected(try monitor.inspectAt(request(gps, at: time)))?["observationId"] as? String == identity,
  "Unanchored distance inspection retains an exact endpoint hit")
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
check(selected(staleAnchor) == nil, "rebuilt generation cannot accept an old derived identity")

// Accuracy is a separate validity channel; unknown historical accuracy stays usable.
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
let speeds = try monitor.readPlot(
  request(speed, metrics: ["speedMps", "healthSpeedMps", "courseDegrees"], buckets: 16))
check(
  points(speeds, metric: "speedMps").map { $0["value"] as! Double } == [3, 5],
  "negative speed accuracy is excluded while missing historical accuracy remains valid")
check(
  points(speeds, metric: "healthSpeedMps").map { $0["value"] as! Double } == [8],
  "Health speed uses raw series independently and excludes multi-value parents")
check(
  points(speeds, metric: "courseDegrees")[1]["startsSegment"] as? Bool == true,
  "north crossing breaks the plotted course instead of sweeping through180")
let latest = try monitor.readLatest(request(speed, metrics: ["healthSpeedMps"]))
check(
  selected(latest, metric: "healthSpeedMps")?["value"] as? Double == 9,
  "newest Health display snapshot may provide latest while original curve retains raw points")
let courseStats = statistics(
  try monitor.rangeStats(request(speed, metrics: ["courseDegrees"], from: 0, to: 3)), metric: "courseDegrees")!
check(
  courseStats["sampleMean"] == nil && courseStats["integral"] == nil,
  "course has no arithmetic mean or generic integral")
for i in 0...2 {
  try append(
    speed, time: Double(i), kind: "telemetry", source: "cyc",
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

// Health interval boundaries are indivisible; a final-only total never manufactures a curve.
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
  points(healthPlot).last?["value"] as? Double == 100, "eligible Health interval produces canonical boundary points")
let healthRange = statistics(try monitor.rangeStats(request(health, from: 0, to: 5)))!
check(
  healthRange["unresolvedBoundary"] as? Bool == true && healthRange["coveredSeconds"] as? Double == 0,
  "partial Health interval is unresolved rather than split into50m")
let healthWhole = statistics(try monitor.rangeStats(request(health, from: 0, to: 10)))!
check(
  healthWhole["distance"] as? Double == 100 && healthWhole["coveredSeconds"] as? Double == 10,
  "full interval amount remains available")
let finalOnly = try create(watch: true, health: true)
try append(
  finalOnly, time: 10, kind: "health", source: "watch",
  payload: ["distanceMeters": .number(1_000), "representation": .string("finalWorkoutTotal")])
check(
  points(try monitor.readPlot(request(finalOnly))).isEmpty, "Health final-only total is not a fabricated distance curve"
)
check(
  try distance.snapshot(id: finalOnly).healthReportedMeters == 1_000, "alternative Health reported total is retained")

// Adjacent Health amounts remain a connected coarse curve across input pages and appends.
let adjacentHealth = try create(watch: true, health: true)
let adjacentHealthID = UUID().uuidString.lowercased()
try archive.update(id: adjacentHealth, healthKitUUID: adjacentHealthID)
func healthAmount(_ from: Double, _ to: Double) throws {
  try append(
    adjacentHealth, time: to, kind: "health", source: "watch",
    payload: [
      "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "value": .number((to - from) * 10),
      "unit": .string("m"), "representation": .string("rawQuantity"), "sampleCount": .integer(1),
      "sampleStart": .string(timestamp(from)), "sampleEnd": .string(timestamp(to)),
      "associatedWorkoutUUID": .string(adjacentHealthID),
    ])
}
for index in 0..<128 { try healthAmount(Double(index), Double(index + 1)) }
check(try distance.snapshot(id: adjacentHealth).totalMeters == 1280, "first adjacent Health page is published")
for index in 128..<192 { try healthAmount(Double(index), Double(index + 1)) }
let adjacentCurve = points(try monitor.readPlot(request(adjacentHealth, from: 0, to: 192, buckets: 4)))
check(adjacentCurve.count >= 4 && adjacentCurve.count <= 10, "long Health coverage reduces to a bounded visible curve")
check(
  adjacentCurve.filter { $0["startsSegment"] as? Bool == true }.count == 1,
  "adjacent Health amounts share one plot segment after checkpoint append")
check(
  adjacentCurve.first?["elapsedSeconds"] as? Double == 0 && adjacentCurve.last?["value"] as? Double == 1920,
  "coarse Health curve retains its first boundary and final cumulative distance")
for point in adjacentCurve {
  let anchor = MonitorObservationAnchor(metric: "distanceMeters", observationId: point["observationId"] as! String)
  let exact = selected(
    try monitor.inspectAt(request(adjacentHealth, at: point["elapsedSeconds"] as? Double, anchor: anchor)))
  check(
    exact?["observationId"] as? String == point["observationId"] as? String,
    "every reduced Health boundary remains exactly inspectable")
}
let adjacentCut = statistics(try monitor.rangeStats(request(adjacentHealth, from: 0.25, to: 1.75)))!
check(
  adjacentCut["unresolvedBoundary"] as? Bool == true && adjacentCut["coveredSeconds"] as? Double == 0,
  "connecting adjacent Health boundaries does not divide their unknown interval amounts")
try healthAmount(194, 195)
try healthAmount(195, 196)
try append(adjacentHealth, time: 196, kind: "lifecycle", source: "phone", payload: ["action": .string("pause")])
try append(adjacentHealth, time: 198, kind: "lifecycle", source: "phone", payload: ["action": .string("resume")])
try healthAmount(198, 199)
try healthAmount(199, 200)
try healthAmount(200, 201.5)
try healthAmount(201, 202)
try healthAmount(202, 203)
try healthAmount(203, 204)
let separatedHealth = points(try monitor.readPlot(request(adjacentHealth, from: 190, to: 204, buckets: 16)))
check(
  separatedHealth.filter { $0["startsSegment"] as? Bool == true }.count == 1,
  "short Health coverage gaps retain rendering continuity")
check(
  separatedHealth.last?["value"] as? Double == 1980,
  "only accepted nonoverlapping Health intervals contribute to the cumulative curve")

// Compare real Monitor, summary and FIT distance intervals, including opt-in equivalence.
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
check(try exportedMeters(health) == 100, "Health full ride retains exact interval amount for FIT")
for (from, to) in [(0.0, 5.0), (5.0, 10.0)] {
  let lap = statistics(try monitor.rangeStats(request(health, from: from, to: to)))!
  check(
    lap["unresolvedBoundary"] as? Bool == true && lap["coveredSeconds"] as? Double == 0,
    "a Health interval cut by a lap leaves that lap distance unavailable")
}
let controller = try create(indoor: true)
for i in 0...2 {
  try append(
    controller, time: Double(i), kind: "telemetry", source: "cyc",
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
let changedSelection = try request(gps, selection: "health:phone")
let whileChanging = try monitor.readLatest(changedSelection)
check(
  whileChanging["status"] as? String == "retry"
    || (whileChanging["revision"] as? String)?.hasPrefix("distance:health:phone:") == true,
  "latest may retain an older original snapshot but never another requested interpretation")
let pinned = try settledPlot(request(gps, selection: "health:phone"))
check(points(pinned).isEmpty, "unavailable explicitly requested source does not fall back to GPS")
check(!points(try settledPlot(request(gps))).isEmpty, "Auto still selects GPS")

// One original revision can serve concurrent interpretations; caches and exact anchors remain isolated.
let mixed = try create()
for i in 0...4 {
  try location(mixed, time: Double(i), longitude: Double(i) * 0.00005)
  try append(
    mixed, time: Double(i), kind: "telemetry", source: "cyc",
    payload: [
      "humanPowerW": .number(100), "cadenceRpm": .number(80), "controllerSpeedMps": .number(4),
      "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(mixed),
      "connectionEpoch": .string("connection"), "clockEpoch": .string("clock"),
    ])
}
_ = try archive.finish(id: mixed, endedAt: start.addingTimeInterval(4))
let mixedMetadata = try WorkoutCoding.encoder().encode(archive.metadata(id: mixed))
let mixedRevision = try archive.revision(id: mixed)
let originalHashes = try originalFingerprint()
let mixedAuto = try distance.snapshot(id: mixed)
let initialRows = try store.read { try $0.scalarInt("SELECT count(*) FROM distance_points")! }
var interpretationPages = 0
WorkoutDistanceStore.inputPageObserverForTesting = { _ in interpretationPages += 1 }
var controllerAnchor: MonitorObservationAnchor?
for selection in ["controller", "gps:phone", "auto", "controller", "gps:phone", "health:watch", "auto"] {
  let query = try request(mixed, selection: selection)
  let value = try monitor.readPlot(query)
  let plot = points(value)
  let expected = selection == "health:watch" ? nil : selection == "controller" ? 16.0 : mixedAuto.totalMeters
  check(
    value["revision"] as? String == query.expectedRevision, "response revision includes its requested interpretation")
  check((plot.last?["value"] as? Double) == expected, "assembled plot cache cannot cross-contaminate source selections")
  let info = (value["metricSources"] as? [String: [String: Any]])?["distanceMeters"]
  if expected != nil {
    check(
      info?["source"] as? String == (selection == "auto" ? "gps:phone" : selection),
      "caption uses the same request as curve")
  } else {
    check(info == nil, "unavailable profile never claims the requested source as measured provenance")
  }
  let summary = try WorkoutAnalysis.summarize(archive: archive, id: mixed, distanceSource: selection)
  check(summary.distanceMeters == expected, "summary cache respects request choice at the same original revision")
  let exported = try exportedMeters(mixed, distanceSource: selection)
  if let expected {
    check(exported.map { abs($0 - expected) < 1e-9 } == true, "FIT distance intervals follow the selected source")
  } else {
    check(exported == nil, "an unavailable source gives FIT no distance profile")
  }
  if let point = plot.last {
    let anchor = MonitorObservationAnchor(metric: "distanceMeters", observationId: point["observationId"] as! String)
    let inspected = try monitor.inspectAt(
      request(mixed, at: point["elapsedSeconds"] as? Double, anchor: anchor, selection: selection))
    check(
      selected(inspected)?["observationId"] as? String == anchor.observationId,
      "cursor resolves exact point within the same explicit selection")
    if selection == "controller" { controllerAnchor = anchor }
  }
}
WorkoutDistanceStore.inputPageObserverForTesting = nil
check(interpretationPages == 0, "switching existing source profiles replays no original input pages")
check(
  try store.read { try $0.scalarInt("SELECT count(*) FROM distance_points")! } == initialRows,
  "source switches reuse the same derived profile rows")
check(
  try archive.revision(id: mixed) == mixedRevision
    && WorkoutCoding.encoder().encode(archive.metadata(id: mixed)) == mixedMetadata,
  "read, summary and export never rewrite original collection or historical pin")
check(try originalFingerprint() == originalHashes, "source-choice requests leave original hashes unchanged")
let otherAnchor = try monitor.inspectAt(request(mixed, at: 4, anchor: controllerAnchor, selection: "gps:phone"))
check(selected(otherAnchor) == nil, "a Controller point identity cannot select a GPS observation")
var mismatched = try request(mixed, selection: "controller")
mismatched.expectedRevision = String(mixedRevision)
check(
  try monitor.readPlot(mismatched)["status"] as? String == "retry",
  "Auto revision cannot admit explicit Controller geometry")
var semanticChanges = MonitorRequest(
  source: "workout", id: mixed, sinceRevision: String(mixedRevision), distanceSource: "controller")
check(
  try monitor.changesSince(semanticChanges)["resetRequired"] as? Bool == true,
  "same original revision with different interpretation requires semantic reset")
semanticChanges.sinceRevision = "distance:controller:\(mixedRevision)"
check(
  try monitor.changesSince(semanticChanges)["resetRequired"] as? Bool == false,
  "matching interpretation uses ordinary original change tracking")
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
      id, time: time, kind: "telemetry", source: "cyc",
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
      payload: ["action": .string("pause"), "interrupted": .bool(true), "cycSequence": .string("2")],
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
      id, time: time, kind: "telemetry", source: "cyc",
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
      try WorkoutLocalOwner.interrupt(
        id: id, epoch: "before-restart",
        timing: WorkoutOwnerTiming(timestamp: timestamp(1), elapsedSeconds: 1, timerSeconds: 1), archive: archive)
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
        payload["cycSequence"] = .string("0")
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
    let id = try create()
    for (index, time) in [0.0, 1.0, 1 + gap, 2 + gap].enumerated() {
      let epoch = index < 2 ? "first" : "second"
      if selection == "controller" {
        try append(
          id, time: time, kind: "telemetry", source: "cyc",
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
    for buckets in [1, 16] {
      let curve = points(try settledPlot(request(id, from: 0, to: 2 + gap, buckets: buckets, selection: selection)))
      check(
        curve.filter { $0["startsSegment"] as? Bool == true }.count == (gap < 6 ? 1 : 2),
        "\(selection) distance display gap \(gap) at \(buckets) buckets")
      if buckets == 16 {
        check(
          curve[1]["value"] as? Double == curve[2]["value"] as? Double,
          "\(selection) uncovered distance span is flat")
      }
    }
    let stats = statistics(try monitor.rangeStats(request(id, from: 0, to: 2 + gap, selection: selection)))!
    check(
      profile.coveredSeconds == 2 && profile.info.selected?.partial == true
        && stats["coveredSeconds"] as? Double == 2 && stats["integral"] == nil,
      "\(selection) reconnect display continuity adds no coverage or integral")
    let expected = selection == "controller" ? 20 : 2 * 6_371_008.8 * .pi / 180 * 0.00001
    check(abs((profile.totalMeters ?? -1) - expected) < 1e-8, "\(selection) reconnect adds no distance")
    check(
      selected(try monitor.inspectAt(request(id, at: 1 + gap / 2, selection: selection))) == nil,
      "\(selection) reconnect remains unsupported for inspection")
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
let longHealth = try create(watch: true, health: true)
let longHealthID = UUID().uuidString.lowercased()
try archive.update(id: longHealth, healthKitUUID: longHealthID)
for (from, to) in [(0.0, 10.0), (10.0, 30.0)] {
  try append(
    longHealth, time: to, kind: "health", source: "watch",
    payload: [
      "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "value": .number((to - from) * 10),
      "unit": .string("m"), "representation": .string("rawQuantity"), "sampleCount": .integer(1),
      "sampleStart": .string(timestamp(from)), "sampleEnd": .string(timestamp(to)),
      "associatedWorkoutUUID": .string(longHealthID),
    ])
}
let longHealthCurve = points(try settledPlot(request(longHealth, from: 0, to: 30)))
check(
  longHealthCurve.map { $0["elapsedSeconds"] as? Double } == [0, 10, 30]
    && longHealthCurve.filter { $0["startsSegment"] as? Bool == true }.count == 1,
  "long adjacent Health amounts stay one connected curve")
print("Native Monitor distance checks passed: \(assertions) assertions")
