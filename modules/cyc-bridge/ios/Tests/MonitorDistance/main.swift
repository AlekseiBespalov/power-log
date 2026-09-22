import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-monitor-distance-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: store)
let monitor = MonitorDataStore(root: root)
let distance = WorkoutDistanceStore(store: store)
let start = Date(timeIntervalSince1970: 1_780_000_000)
func timestamp(_ seconds: Double) -> String { WorkoutCoding.timestamp(start.addingTimeInterval(seconds)) }
func create(watch: Bool = false, indoor: Bool = false, health: Bool = false) throws -> String {
  let value = try archive.create(startedAt: start, indoor: indoor, watchEnabled: watch, saveToHealth: health, recordGPS: true)
  try archive.update(id: value.id, phase: "running")
  return value.id
}
func append(_ id: String, time: Double, kind: String, source: String, payload: [String: WorkoutJSON], identity: String = UUID().uuidString.lowercased()) throws {
  try archive.append(WorkoutEvent(workoutId: id, kind: kind, source: source, timestamp: start.addingTimeInterval(time), elapsedSeconds: time, payload: payload, eventId: identity))
}
func location(_ id: String, time: Double, longitude: Double, source: String = "phone", accuracy: Double = 3, speed: Double = 5, speedAccuracy: Double? = 1, course: Double? = nil) throws {
  var payload: [String: WorkoutJSON] = ["latitude": .number(0), "longitude": .number(longitude), "horizontalAccuracyM": .number(accuracy), "speedMps": .number(speed)]
  if let speedAccuracy { payload["speedAccuracyMps"] = .number(speedAccuracy) }
  if let course { payload["courseDegrees"] = .number(course) }
  try append(id, time: time, kind: "location", source: source, payload: payload)
}
func request(_ id: String, metrics: [String] = ["distanceMeters"], from: Double? = nil, to: Double? = nil, at: Double? = nil, buckets: Int = 16, anchor: MonitorObservationAnchor? = nil, selection: String = "auto") throws -> MonitorRequest {
  let revision = String(try store.collection(id: id).int("revision")!)
  return MonitorRequest(source: "workout", id: id, generation: 1, expectedRevision: selection == "auto" ? revision : "distance:v1:\(selection):\(revision)", startSeconds: from, endSeconds: to, seconds: at, metrics: metrics, buckets: buckets, anchor: anchor, distanceSource: selection)
}
func points(_ value: [String: Any], metric: String = "distanceMeters") -> [[String: Any]] { (value["series"] as? [String: [[String: Any]]])?[metric] ?? [] }
func settledPlot(_ request: MonitorRequest) throws -> [String: Any] {
  for _ in 0..<200 {
    let value = try monitor.readPlot(request)
    if value["status"] as? String == "ok" { return value }
    Thread.sleep(forTimeInterval: 0.005)
  }
  fatalError("Synthetic distance profile never completed its pending build")
}
func selected(_ value: [String: Any], metric: String = "distanceMeters") -> [String: Any]? { (value["points"] as? [String: Any])?[metric] as? [String: Any] }
func statistics(_ value: [String: Any], metric: String = "distanceMeters") -> [String: Any]? { (value["statistics"] as? [String: [String: Any]])?[metric] }
func originalFingerprint() throws -> [Data] {
  try store.read { db in try db.rows("SELECT content_hash FROM observations ORDER BY id LIMIT 256", limit: 256).compactMap { $0.data("content_hash") } }
}

// Decode the actual standard FIT definition/data messages; inspect numeric wire fields.
func fitMessages(_ url: URL) throws -> [(Int, [Int: UInt64])] {
  let bytes = [UInt8](try Data(contentsOf: url))
  func word(_ at: Int, _ count: Int) -> UInt64 { (0..<count).reduce(0) { $0 | UInt64(bytes[at + $1]) << ($1 * 8) } }
  var offset = Int(bytes[0]), definitions: [Int: (Int, [(Int, Int)])] = [:], result: [(Int, [Int: UInt64])] = []
  let end = offset + Int(word(4, 4))
  while offset < end {
    let header = bytes[offset]; offset += 1
    precondition(header & 0x80 == 0, "Generated FIT uses ordinary headers")
    let local = Int(header & 15)
    if header & 0x40 != 0 {
      precondition(bytes[offset + 1] == 0, "Generated FIT uses little endian")
      let global = Int(word(offset + 2, 2)), count = Int(bytes[offset + 4]); offset += 5
      var fields: [(Int, Int)] = []
      for _ in 0..<count { fields.append((Int(bytes[offset]), Int(bytes[offset + 1]))); offset += 3 }
      definitions[local] = (global, fields)
    } else {
      let (global, fields) = definitions[local]!
      var values: [Int: UInt64] = [:]
      for (number, size) in fields { let value = word(offset, size); offset += size; if value != (UInt64(1) << (size * 8)) - 1 { values[number] = value } }
      result.append((global, values))
    }
  }
  return result
}

// Reproduce the production symptom: GPS originals with Health off and no cumulative Health snapshots.
let gps = try create()
for i in 0...4 { try location(gps, time: Double(i), longitude: Double(i) * 0.00005) }
let hashes = try originalFingerprint()
let firstPlot = try monitor.readPlot(request(gps))
let curve = points(firstPlot)
check(firstPlot["status"] as? String == "ok" && curve.count >= 2, "GPS-only local ride produces a distance curve")
check(curve.first?["value"] as? Double == 0 && (curve.last?["value"] as? Double ?? 0) > 20, "curve has a true derived start boundary and cumulative increments")
check(curve.allSatisfy { $0["derived"] as? Bool == true }, "derived points are explicitly distinguished from originals")
let snapshot = try distance.snapshot(id: gps)
check(abs((curve.last?["value"] as? Double ?? 0) - (snapshot.totalMeters ?? 0)) < 1e-9, "Monitor and shared profile use identical total")
let source = ((firstPlot["metricSources"] as? [String: Any])?["distanceMeters"] as? [String: Any])
check(source?["source"] as? String == "gps:phone" && source?["estimated"] as? Bool == false, "GPS provenance survives the response")
let descriptor = try monitor.describeSource(MonitorRequest(source: "workout", id: gps))
check((descriptor["availableMetrics"] as? [String])?.contains("distanceMeters") == true, "distance availability is derived, independent of Health snapshot columns")
let last = curve.last!, identity = last["observationId"] as! String, time = last["elapsedSeconds"] as! Double
let anchor = MonitorObservationAnchor(metric: "distanceMeters", observationId: identity)
let anchored = try monitor.inspectAt(request(gps, at: time, anchor: anchor))
check(selected(anchored)?["observationId"] as? String == identity, "indexed derived identity lookup restores exact curve point")
check(selected(anchored)?["timestamp"] as? String == timestamp(time), "derived boundary retains actual original boundary time")
let profileRowsBefore = try store.read { try $0.scalarInt("SELECT count(*) FROM distance_points")! }
var cursorInputPages = 0
WorkoutDistanceStore.inputPageObserverForTesting = { _ in cursorInputPages += 1 }
for _ in 0..<50 {
  let inspected = try monitor.inspectAt(request(gps, at: time, anchor: anchor))
  check(selected(inspected)?["observationId"] as? String == identity, "repeated cursor lookup retains one fixed generation")
}
WorkoutDistanceStore.inputPageObserverForTesting = nil
check(cursorInputPages == 0, "production cursor path performs zero original input-page reads")
check(try store.read { try $0.scalarInt("SELECT count(*) FROM distance_points")! } == profileRowsBefore, "cursor reads do not rebuild or append projection rows")
let range = statistics(try monitor.rangeStats(request(gps, from: 0.5, to: 2.5)))!
check(abs((range["distance"] as? Double ?? 0) - (snapshot.totalMeters ?? 0) / 2) < 0.00001, "range clips GPS chord contributions explicitly")
check(range["coveredSeconds"] as? Double == 2 && range["sampleMean"] == nil && range["integral"] == nil, "distance statistics contain meters and coverage, never cumulative mean or metre-seconds")
check(try originalFingerprint() == hashes, "plot, cursor, and range reads preserve original content hashes")

// A quality-rejected fix inside ten seconds must remain a visible, uninspectable gap.
try location(gps, time: 5, longitude: 0.00025, accuracy: 100)
try location(gps, time: 6, longitude: 0.00030)
try location(gps, time: 7, longitude: 0.00035)
let gapPlot = try monitor.readPlot(request(gps, from: 0, to: 7, buckets: 1))
check(points(gapPlot).count <= 6, "derived plot work/output remains bounded by pixel buckets")
check(points(gapPlot).last?["startsSegment"] as? Bool == true, "M4 preserves rejected-fix discontinuity inside a single bucket")
check(selected(try monitor.inspectAt(request(gps, at: 5))) == nil, "short explicit gap is not filled by nearest distance boundary")
try location(gps, time: 2.5, longitude: 0.000125)
_ = try monitor.readPlot(request(gps))
let staleAnchor = try monitor.inspectAt(request(gps, at: time, anchor: anchor))
check(selected(staleAnchor) == nil, "rebuilt generation cannot accept an old derived identity")

// Accuracy is a separate validity channel; unknown historical accuracy stays usable.
let speed = try create()
try location(speed, time: 0, longitude: 0, speed: 3, speedAccuracy: nil, course: 359)
try location(speed, time: 1, longitude: 0.00005, speed: 4, speedAccuracy: -1, course: 1)
try location(speed, time: 2, longitude: 0.00010, speed: 5, speedAccuracy: 0, course: 2)
try append(speed, time: 1, kind: "health", source: "watch", payload: ["healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingSpeed"), "value": .number(8), "unit": .string("m/s"), "representation": .string("rawSeries")])
try append(speed, time: 2, kind: "health", source: "watch", payload: ["healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingSpeed"), "value": .number(99), "sampleCount": .number(5), "unit": .string("m/s"), "representation": .string("rawQuantity")])
try append(speed, time: 3, kind: "health", source: "watch", payload: ["healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingSpeed"), "speedMps": .number(9), "unit": .string("m/s"), "representation": .string("builderMostRecent")])
let speeds = try monitor.readPlot(request(speed, metrics: ["speedMps", "healthSpeedMps", "courseDegrees"], buckets: 16))
check(points(speeds, metric: "speedMps").map { $0["value"] as! Double } == [3, 5], "negative speed accuracy is excluded while missing historical accuracy remains valid")
check(points(speeds, metric: "healthSpeedMps").map { $0["value"] as! Double } == [8], "Health speed uses raw series independently and excludes multi-value parents")
check(points(speeds, metric: "courseDegrees")[1]["startsSegment"] as? Bool == true, "north crossing breaks the plotted course instead of sweeping through180")
let latest = try monitor.readLatest(request(speed, metrics: ["healthSpeedMps"]))
check(selected(latest, metric: "healthSpeedMps")?["value"] as? Double == 9, "newest Health display snapshot may provide latest while original curve retains raw points")
let courseStats = statistics(try monitor.rangeStats(request(speed, metrics: ["courseDegrees"], from: 0, to: 3)), metric: "courseDegrees")!
check(courseStats["sampleMean"] == nil && courseStats["integral"] == nil, "course has no arithmetic mean or generic integral")
for i in 0...2 {
  try append(speed, time: Double(i), kind: "telemetry", source: "cyc", payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80), "consumedWh": .number(Double(i)), "assistLevel": .number(Double(i))])
  try append(speed, time: Double(i), kind: "health", source: "phone", payload: ["activeEnergyKcal": .number(Double(i)), "representation": .string("cumulativeWorkoutTotal")])
}
let semantics = try monitor.rangeStats(request(speed, metrics: ["consumedWh", "assistLevel", "activeEnergyKcal", "humanPowerW"], from: 0, to: 2))
for metric in ["consumedWh", "assistLevel", "activeEnergyKcal"] {
  let stats = statistics(semantics, metric: metric)!
  check(stats["sampleMean"] == nil && stats["integral"] == nil, "counter/cumulative/category aggregate suppression")
}
check(statistics(semantics, metric: "humanPowerW")?["integral"] as? Double == 200, "meaningful rider work integration remains unchanged")

// Health interval boundaries are indivisible; a final-only total never manufactures a curve.
let health = try create(watch: true, health: true)
let healthID = UUID().uuidString.lowercased()
try archive.update(id: health, healthKitUUID: healthID)
try append(health, time: 10, kind: "health", source: "watch", payload: ["healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "value": .number(100), "unit": .string("m"), "representation": .string("rawQuantity"), "sampleCount": .integer(1), "sampleStart": .string(timestamp(0)), "sampleEnd": .string(timestamp(10)), "associatedWorkoutUUID": .string(healthID)])
let healthPlot = try monitor.readPlot(request(health))
check(points(healthPlot).last?["value"] as? Double == 100, "eligible Health interval produces canonical boundary points")
let healthRange = statistics(try monitor.rangeStats(request(health, from: 0, to: 5)))!
check(healthRange["unresolvedBoundary"] as? Bool == true && healthRange["coveredSeconds"] as? Double == 0, "partial Health interval is unresolved rather than split into50m")
let healthWhole = statistics(try monitor.rangeStats(request(health, from: 0, to: 10)))!
check(healthWhole["distance"] as? Double == 100 && healthWhole["coveredSeconds"] as? Double == 10, "full interval amount remains available")
let finalOnly = try create(watch: true, health: true)
try append(finalOnly, time: 10, kind: "health", source: "watch", payload: ["distanceMeters": .number(1_000), "representation": .string("finalWorkoutTotal")])
check(points(try monitor.readPlot(request(finalOnly))).isEmpty, "Health final-only total is not a fabricated distance curve")
check(try distance.snapshot(id: finalOnly).healthReportedMeters == 1_000, "alternative Health reported total is retained")

// Adjacent Health amounts remain a connected coarse curve across input pages and appends.
let adjacentHealth = try create(watch: true, health: true)
let adjacentHealthID = UUID().uuidString.lowercased()
try archive.update(id: adjacentHealth, healthKitUUID: adjacentHealthID)
func healthAmount(_ from: Double, _ to: Double) throws {
  try append(adjacentHealth, time: to, kind: "health", source: "watch", payload: [
    "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "value": .number((to - from) * 10),
    "unit": .string("m"), "representation": .string("rawQuantity"), "sampleCount": .integer(1),
    "sampleStart": .string(timestamp(from)), "sampleEnd": .string(timestamp(to)),
    "associatedWorkoutUUID": .string(adjacentHealthID)])
}
for index in 0..<128 { try healthAmount(Double(index), Double(index + 1)) }
check(try distance.snapshot(id: adjacentHealth).totalMeters == 1280, "first adjacent Health page is published")
for index in 128..<192 { try healthAmount(Double(index), Double(index + 1)) }
let adjacentCurve = points(try monitor.readPlot(request(adjacentHealth, from: 0, to: 192, buckets: 4)))
check(adjacentCurve.count >= 4 && adjacentCurve.count <= 10, "long Health coverage reduces to a bounded visible curve")
check(adjacentCurve.filter { $0["startsSegment"] as? Bool == true }.count == 1,
  "adjacent Health amounts share one plot segment after checkpoint append")
check(adjacentCurve.first?["elapsedSeconds"] as? Double == 0 && adjacentCurve.last?["value"] as? Double == 1920,
  "coarse Health curve retains its first boundary and final cumulative distance")
for point in adjacentCurve {
  let anchor = MonitorObservationAnchor(metric: "distanceMeters", observationId: point["observationId"] as! String)
  let exact = selected(try monitor.inspectAt(request(adjacentHealth, at: point["elapsedSeconds"] as? Double, anchor: anchor)))
  check(exact?["observationId"] as? String == point["observationId"] as? String,
    "every reduced Health boundary remains exactly inspectable")
}
let adjacentCut = statistics(try monitor.rangeStats(request(adjacentHealth, from: 0.25, to: 1.75)))!
check(adjacentCut["unresolvedBoundary"] as? Bool == true && adjacentCut["coveredSeconds"] as? Double == 0,
  "connecting adjacent Health boundaries does not divide their unknown interval amounts")
try healthAmount(194, 195); try healthAmount(195, 196)
try append(adjacentHealth, time: 196, kind: "lifecycle", source: "phone", payload: ["action": .string("pause")])
try append(adjacentHealth, time: 198, kind: "lifecycle", source: "phone", payload: ["action": .string("resume")])
try healthAmount(198, 199); try healthAmount(199, 200)
try healthAmount(200, 201.5); try healthAmount(201, 202)
try healthAmount(202, 203); try healthAmount(203, 204)
let separatedHealth = points(try monitor.readPlot(request(adjacentHealth, from: 190, to: 204, buckets: 16)))
check(separatedHealth.filter { $0["startsSegment"] as? Bool == true }.count == 4,
  "missing Health time, pause and rejected overlapping amounts each retain a visible break")
check(separatedHealth.last?["value"] as? Double == 1980,
  "only accepted nonoverlapping Health intervals contribute to the cumulative curve")

// Compare real Monitor, summary, range and encoded FIT endpoints, including opt-in equivalence.
for saves in [false, true] {
  let id = try create(health: saves)
  for i in 0...4 { try location(id, time: Double(i), longitude: Double(i) * 0.00005) }
  try append(id, time: 2, kind: "lifecycle", source: "phone", payload: ["action": .string("lap")])
  _ = try archive.finish(id: id, endedAt: start.addingTimeInterval(4))
  let plot = try monitor.readPlot(request(id)), meters = points(plot).last!["value"] as! Double
  let summary = try WorkoutFIT.summarize(archive: archive, id: id)
  let output = root.appendingPathComponent(id + ".fit")
  let exported = try WorkoutFIT.export(archive: archive, id: id, to: output)
  let decoded = try fitMessages(output)
  let session = decoded.first { $0.0 == 18 }!.1
  let records = decoded.filter { $0.0 == 20 }.compactMap { $0.1[5] }
  let laps = decoded.filter { $0.0 == 19 }.compactMap { $0.1[9] }
  check(abs(meters - snapshot.totalMeters!) < 1e-9, "Health opt-in does not change GPS chart distance")
  check(summary.distanceMeters == meters && exported.distanceMeters == meters, "summary and FIT share Monitor's revisioned distance total")
  check(abs(Double(session[9]!) / 100 - meters) <= 0.0051 && abs(Double(records.last!) / 100 - meters) <= 0.0051, "parsed FIT session and final record match Monitor within centimeter encoding")
  check(laps.count == 2 && abs(Double(laps.reduce(0,+)) / 100 - meters) <= 0.011, "parsed GPS laps allocate the same covered distance")
}
try append(health, time: 5, kind: "lifecycle", source: "phone", payload: ["action": .string("lap")])
_ = try archive.finish(id: health, endedAt: start.addingTimeInterval(10))
let healthFIT = root.appendingPathComponent("health.fit")
_ = try WorkoutFIT.export(archive: archive, id: health, to: healthFIT)
let decodedHealth = try fitMessages(healthFIT)
check(decodedHealth.first { $0.0 == 18 }!.1[9] == 10_000, "Health full ride retains exact interval amount in FIT")
check(decodedHealth.filter { $0.0 == 19 }.allSatisfy { $0.1[9] == nil }, "parsed FIT omits lap distance at unresolved Health interval cuts")
let controller = try create(indoor: true)
for i in 0...2 {
  try append(controller, time: Double(i), kind: "telemetry", source: "cyc", payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80), "controllerSpeedMps": .number(4), "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(controller), "connectionEpoch": .string("connection"), "clockEpoch": .string("clock")])
}
let controllerPlot = try monitor.readPlot(request(controller))
check(points(controllerPlot).last?["value"] as? Double == 8, "indoor controller-only ride receives the known-unit integral")
let controllerSource = (controllerPlot["metricSources"] as! [String: [String: Any]])["distanceMeters"]!
check(controllerSource["estimated"] as? Bool == true && controllerSource["label"] as? String == "Controller estimate", "controller provenance is explicitly estimated")

_ = try archive.finish(id: gps, endedAt: start.addingTimeInterval(7))
let changedSelection = try request(gps, selection: "health:phone")
let whileChanging = try monitor.readLatest(changedSelection)
check(whileChanging["status"] as? String == "retry" || (whileChanging["revision"] as? String)?.hasPrefix("distance:v1:health:phone:") == true,
  "latest may retain an older original snapshot but never another requested interpretation")
let pinned = try settledPlot(request(gps, selection: "health:phone"))
check(points(pinned).isEmpty, "unavailable explicitly requested source does not fall back to GPS")
check(!points(try settledPlot(request(gps))).isEmpty, "Auto still selects GPS")

// One original revision can serve concurrent interpretations; caches and exact anchors remain isolated.
let mixed = try create()
for i in 0...4 {
  try location(mixed, time: Double(i), longitude: Double(i) * 0.00005)
  try append(mixed, time: Double(i), kind: "telemetry", source: "cyc", payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80), "controllerSpeedMps": .number(4), "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(mixed), "connectionEpoch": .string("connection"), "clockEpoch": .string("clock")])
}
_ = try archive.finish(id: mixed, endedAt: start.addingTimeInterval(4))
let mixedMetadata = try WorkoutCoding.encoder().encode(archive.metadata(id: mixed))
let mixedRevision = try archive.revision(id: mixed), originalHashes = try originalFingerprint()
let mixedAuto = try distance.snapshot(id: mixed), initialRows = try store.read { try $0.scalarInt("SELECT count(*) FROM distance_points")! }
var interpretationPages = 0
WorkoutDistanceStore.inputPageObserverForTesting = { _ in interpretationPages += 1 }
var controllerAnchor: MonitorObservationAnchor?
for selection in ["controller", "gps:phone", "auto", "controller", "gps:phone", "health:watch", "auto"] {
  let query = try request(mixed, selection: selection)
  let value = try monitor.readPlot(query), plot = points(value)
  let expected = selection == "health:watch" ? nil : selection == "controller" ? 16.0 : mixedAuto.totalMeters
  check(value["revision"] as? String == query.expectedRevision, "response revision includes its requested interpretation")
  check((plot.last?["value"] as? Double) == expected, "assembled plot cache cannot cross-contaminate source selections")
  let info = (value["metricSources"] as? [String: [String: Any]])?["distanceMeters"]
  if expected != nil {
    check(info?["source"] as? String == (selection == "auto" ? "gps:phone" : selection), "caption uses the same request as curve")
  } else {
    check(info == nil, "unavailable profile never claims the requested source as measured provenance")
  }
  let summary = try WorkoutFIT.summarize(archive: archive, id: mixed, distanceSource: selection)
  check(summary.distanceMeters == expected, "summary cache respects request choice at the same original revision")
  let file = try root.appendingPathComponent(WorkoutFIT.filename(revision: mixedRevision, seal: 1, distanceSource: selection))
  let exported = try WorkoutFIT.export(archive: archive, id: mixed, to: file, distanceSource: selection)
  let messages = try fitMessages(file), wire = messages.first { $0.0 == 18 }!.1[9]
  check(exported.distanceMeters == expected, "FIT preparation captures explicit distance source")
  if let expected { check(wire != nil && abs(Double(wire!) / 100 - expected) <= 0.0051, "wire session distance agrees with the selected summary") }
  else { check(wire == nil, "unavailable source stays absent in standard FIT totals") }
  if let point = plot.last {
    let anchor = MonitorObservationAnchor(metric: "distanceMeters", observationId: point["observationId"] as! String)
    let inspected = try monitor.inspectAt(request(mixed, at: point["elapsedSeconds"] as? Double, anchor: anchor, selection: selection))
    check(selected(inspected)?["observationId"] as? String == anchor.observationId, "cursor resolves exact point within the same explicit selection")
    if selection == "controller" { controllerAnchor = anchor }
  }
}
WorkoutDistanceStore.inputPageObserverForTesting = nil
check(interpretationPages == 0, "switching existing source profiles replays no original input pages")
check(try store.read { try $0.scalarInt("SELECT count(*) FROM distance_points")! } == initialRows, "source switches reuse the same derived profile rows")
check(try archive.revision(id: mixed) == mixedRevision && WorkoutCoding.encoder().encode(archive.metadata(id: mixed)) == mixedMetadata,
  "read, summary and export never rewrite original collection or historical pin")
check(try originalFingerprint() == originalHashes, "source-choice requests leave original hashes unchanged")
let otherAnchor = try monitor.inspectAt(request(mixed, at: 4, anchor: controllerAnchor, selection: "gps:phone"))
check(selected(otherAnchor) == nil, "a Controller point identity cannot select a GPS observation")
var mismatched = try request(mixed, selection: "controller")
mismatched.expectedRevision = String(mixedRevision)
check(try monitor.readPlot(mismatched)["status"] as? String == "retry", "Auto revision cannot admit explicit Controller geometry")
var semanticChanges = MonitorRequest(source: "workout", id: mixed, sinceRevision: String(mixedRevision), distanceSource: "controller")
check(try monitor.changesSince(semanticChanges)["resetRequired"] as? Bool == true, "same original revision with different interpretation requires semantic reset")
semanticChanges.sinceRevision = "distance:v1:controller:\(mixedRevision)"
check(try monitor.changesSince(semanticChanges)["resetRequired"] as? Bool == false, "matching interpretation uses ordinary original change tracking")
check(try WorkoutFIT.filename(revision: mixedRevision, seal: 1, distanceSource: "controller") != WorkoutFIT.filename(revision: mixedRevision, seal: 1, distanceSource: "gps:phone"), "concurrent source exports cannot overwrite each other's upload input")
let beforeAppend = try store.collection(id: speed).int("revision")!
try location(speed, time: 4, longitude: 0.0002)
var changes = MonitorRequest(source: "workout", id: speed)
changes.sinceRevision = String(beforeAppend)
let changed = try monitor.changesSince(changes)["changes"] as! [[String: Any]]
check(changed.contains { ($0["metrics"] as? [String])?.contains("distanceMeters") == true }, "GPS source changes advertise derived distance invalidation")
let beforeMetadata = try store.collection(id: speed).int("revision")!
try archive.update(id: speed, phase: "paused")
changes.sinceRevision = String(beforeMetadata)
let broad = try monitor.changesSince(changes)["changes"] as! [[String: Any]]
check(broad.contains { ($0["metrics"] as? [String])?.isEmpty == true }, "metadata changes retain the all-metrics invalidation sentinel")
print("Native Monitor distance checks passed: \(assertions) assertions")
