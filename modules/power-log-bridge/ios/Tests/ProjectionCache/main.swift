import Foundation
import PowerLogCore

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-projection-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: db)
let reader = MonitorDataStore(root: root)
let origin = Date(timeIntervalSince1970: 1_780_000_000)
let ride = try archive.create(
  startedAt: origin, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true, sampleHz: 4)
func event(_ time: Double, value: WorkoutJSON, id: String = UUID().uuidString, extra: [String: WorkoutJSON] = [:])
  throws -> WorkoutEvent
{
  var payload: [String: WorkoutJSON] = ["humanPowerW": value, "cadenceRpm": .number(75)]
  for (key, value) in extra { payload[key] = value }
  return try WorkoutEvent(
    workoutId: ride.id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
    elapsedSeconds: time, payload: payload, eventId: id)
}
func health(_ time: Double, value: Double, source: String, representation: String) throws {
  try archive.append(
    WorkoutEvent(
      workoutId: ride.id, kind: "health", source: source, timestamp: origin.addingTimeInterval(time),
      elapsedSeconds: time,
      payload: ["heartRateBpm": .number(value), "representation": .string(representation)]))
}
func query(
  _ metrics: [String] = ["humanPowerW", "cadenceRpm"], start: Double? = nil, end: Double? = nil, buckets: Int = 64
) -> MonitorRequest {
  MonitorRequest(
    source: "workout", id: ride.id, startSeconds: start, endSeconds: end, metrics: metrics, buckets: buckets)
}
func points(_ result: [String: Any], metric: String = "humanPowerW") -> [[String: Any]] {
  (result["series"] as? [String: [[String: Any]]])?[metric] ?? []
}
func clearPersistedPlots() throws { _ = try db.transaction { try $0.execute("DELETE FROM derived_cache") } }
func counter(_ name: String, _ reader: MonitorDataStore = reader) -> Int { reader.projectionDiagnostics()[name] ?? 0 }
for offset in stride(from: 0, to: 4_096, by: 256) {
  _ = try db.appendBatch((offset..<(offset + 256)).map { try event(Double($0) / 8, value: .number(Double($0 % 200))) })
}
var scalarBridges = 0
MonitorDataStore.compactPointObserverForTesting = { scalarBridges += 1 }
let cold = try reader.readPlot(query())
MonitorDataStore.compactPointObserverForTesting = nil
check(scalarBridges == 0, "Stored plot reads batch numeric inputs without per-original compact bridge objects")
let coldHydration = counter("hydratedPoints")
_ = try reader.readPlot(query())
check(
  counter("hydratedPoints") == coldHydration, "Repeated stored plots memoize retained hydration at the admitted pin")
let hydrationRevision = try archive.revision(id: ride.id)
let hydrationPlan = try db.read { sql in
  let ids = try sql.rows(
    "SELECT observation_id FROM collection_memberships WHERE collection_id=? LIMIT 3", [.text(ride.id)], limit: 3)
  return try sql.rows(
    "EXPLAIN QUERY PLAN " + MonitorDataStore.retainedObservationSQL(ids.count),
    [.text(ride.id), .integer(hydrationRevision), .integer(hydrationRevision), .integer(hydrationRevision)]
      + ids.map { .integer($0.int("observation_id")!) }, limit: 32)
}
check(
  hydrationPlan.contains {
    $0.string("detail")?.hasPrefix("SEARCH m ") == true
      && $0.string("detail")?.contains("observation_id=?") == true
  },
  "Retained hydration performs observation lookups instead of a full pinned-history scan")
check(points(cold).count <= 256, "plot geometry remains pixel bounded")
check(
  counter("scannedPoints") == 8_192 && counter("points") == 8_192, "cold read stores only selected numeric projections")
check(counter("hydratedPoints") < 1_024, "cold projection hydrates retained originals only")
var windowCountScans = 0
try db.read { sql in
  sql.queryObserverForTesting = { query, _ in
    if query.hasPrefix("SELECT count(*) FROM collection_memberships m") && query.contains("JOIN observations o") {
      windowCountScans += 1
    }
  }
}
_ = try MonitorDataStore(root: root).readPlot(query(start: 100.01, end: 130.99, buckets: 17))
try db.read { $0.queryObserverForTesting = nil }
check(windowCountScans == 0, "Stored narrow projection admission does not count the visible window before reading it")
var scanned = counter("scannedPoints")
let hydratedBeforeStats = counter("hydratedPoints")
_ = try reader.rangeStats(query(start: 10.01, end: 11.99))
check(
  counter("scannedPoints") == scanned + 30,
  "statistics create an isolated numeric projection without locking plot cache")
scanned = counter("scannedPoints")
_ = try reader.rangeStats(query(start: 10.01, end: 11.99))
check(counter("scannedPoints") == scanned, "warm statistics reuse their own numeric projection")
check(counter("hydratedPoints") - hydratedBeforeStats <= 8, "statistics hydrate only final original extrema")
_ = try reader.readPlot(query(start: 100.01, end: 130.99, buckets: 17))
check(
  counter("scannedPoints") == scanned && counter("projectionHits") == 6,
  "pan seeks existing numeric projection without rereading history")
_ = try db.appendBatch((4_096..<4_112).map { try event(Double($0) / 8, value: .number(Double($0 % 200))) })
_ = try reader.readPlot(query(buckets: 65))
check(
  counter("scannedPoints") - scanned == 32 && counter("appendedPoints") == 32,
  "tail append reads only new projected values for both metrics")
let beforeLate = counter("invalidations")
try archive.append(event(100.00000004, value: .number(-500)))
let repaired = try reader.readPlot(query(buckets: 66))
check(
  counter("invalidations") > beforeLate && points(repaired).contains { $0["value"] as? Double == -500 },
  "late submicrosecond point invalidates affected projection and preserves exact extremum")
check(
  points(repaired).contains { $0["elapsedSeconds"] as? Double == 100.00000004 },
  "numeric cache retains unquantized source time")

let tiedHigh = "ffffffff-ffff-ffff-ffff-ffffffffffff"
let tiedLow = "00000000-0000-0000-0000-000000000001"
try archive.append(event(200, value: .number(-700), id: tiedHigh))
try archive.append(event(200, value: .number(-700), id: tiedLow))
let ties = try reader.readPlot(query(start: 199, end: 201, buckets: 1))
check(
  points(ties).contains { $0["observationId"] as? String == "event:\(tiedLow)" },
  "stable identity resolves tied projected minima")
let replacement = try event(200, value: .number(15), extra: ["supersedesEventId": .string(tiedLow)])
try archive.append(replacement)
let corrected = try reader.readPlot(query(start: 199, end: 201, buckets: 2))
check(
  !points(corrected).contains { $0["observationId"] as? String == "event:\(tiedLow)" },
  "immutable correction removes superseded projection original")

try health(1, value: 101, source: "phone", representation: "builderMostRecent")
_ = try reader.readPlot(query(["heartRateBpm"], buckets: 7))
try health(2, value: 111, source: "watch", representation: "builderMostRecent")
let preferred = try reader.readPlot(query(["heartRateBpm"], buckets: 8))
check(
  points(preferred, metric: "heartRateBpm").count == 1
    && points(preferred, metric: "heartRateBpm")[0]["value"] as? Double == 111,
  "preferred Watch arrival replaces entire fallback source")
try health(0.5, value: 121, source: "watch", representation: "rawQuantity")
let raw = try reader.readPlot(query(["heartRateBpm"], buckets: 9))
check(
  points(raw, metric: "heartRateBpm").count == 1 && points(raw, metric: "heartRateBpm")[0]["value"] as? Double == 121,
  "first raw quantity replaces builder projection")
let beforeHeart = counter("scannedPoints")
try health(1.5, value: 122, source: "watch", representation: "rawQuantity")
_ = try reader.readPlot(query(["heartRateBpm"], buckets: 10))
check(counter("scannedPoints") == beforeHeart + 1, "ordinary asynchronous HR appends only its numeric tail")

let big: Int64 = 9_007_199_254_740_980
let safeMaximum: Int64 = 9_007_199_254_740_991
for invalid in [safeMaximum + 1, Int64.max, Int64.min] {
  do {
    _ = try event(600, value: .integer(invalid))
    fatalError("oversized numeric payload was accepted")
  } catch { assertions += 1 }
}
try archive.append(event(600, value: .integer(big + 1), extra: ["faultCode": .integer(big + 1)]))
try archive.append(event(601, value: .integer(big), extra: ["faultCode": .integer(big)]))
try archive.append(event(602, value: .integer(big), extra: ["faultCode": .integer(big)]))
try archive.append(event(603, value: .integer(safeMaximum), extra: ["faultCode": .integer(safeMaximum)]))
try archive.append(event(604, value: .integer(safeMaximum - 1), extra: ["faultCode": .integer(safeMaximum - 1)]))
try archive.append(event(605, value: .integer(-safeMaximum), extra: ["faultCode": .integer(-safeMaximum)]))
try archive.append(event(606, value: .integer(-safeMaximum), extra: ["faultCode": .integer(-safeMaximum)]))
let integerPlot = try reader.readPlot(query(["humanPowerW"], start: 600, end: 602, buckets: 1))
check(
  points(integerPlot).contains { $0["exactValue"] as? String == String(big + 1) },
  "projection retains exact integer metadata")
let invalidPowerStats = try reader.rangeStats(query(["humanPowerW"], start: 600, end: 602))
check(
  ((invalidPowerStats["statistics"] as! [String: [String: Any]])["humanPowerW"]?["count"] as? Int) == 0,
  "Physiologically invalid power remains an original without entering statistics")
let stats = try reader.rangeStats(query(["faultCode"], start: 600, end: 602))
let stat = (stats["statistics"] as? [String: [String: Any]])?["faultCode"]
check(
  (stat?["min"] as? [String: Any])?["elapsedSeconds"] as? Double == 601,
  "integer extrema compare exact values before earliest-time tie")
check(
  (stat?["max"] as? [String: Any])?["exactValue"] as? String == String(big + 1),
  "range extrema carry original integer digits")
let upperStats = try reader.rangeStats(query(["faultCode"], start: 603, end: 604))
let upper = (upperStats["statistics"] as? [String: [String: Any]])?["faultCode"]
check(
  (upper?["max"] as? [String: Any])?["exactValue"] as? String == String(safeMaximum),
  "the largest safe integer compares exactly against its neighbor")
let lowerStats = try reader.rangeStats(query(["faultCode"], start: 605, end: 606))
let lower = (lowerStats["statistics"] as? [String: [String: Any]])?["faultCode"]
check(
  (lower?["max"] as? [String: Any])?["elapsedSeconds"] as? Double == 605,
  "minimum safe integer ties follow original time")
var cursor = query(["humanPowerW"])
cursor.seconds = 600
let inspected = try reader.inspectAt(cursor)
check(
  ((inspected["points"] as? [String: Any])?["humanPowerW"] as? [String: Any])?["exactValue"] as? String
    == String(big + 1), "indexed inspection retains exact integer readout")

func scalarOrder(_ a: Double, _ ai: Int64?, _ b: Double, _ bi: Int64?) -> Int {
  Int(
    AnalysisTypesKt.compareScalars(
      a: MonitorInspection.scalar(a, integer: ai)!, b: MonitorInspection.scalar(b, integer: bi)!))
}
let beyond: Int64 = 9_007_199_254_740_992
check(
  scalarOrder(Double(beyond + 1), beyond + 1, Double(beyond), nil) > 0, "mixed integer/real ordering above 2^53")
check(
  scalarOrder(Double(-beyond - 1), -beyond - 1, -Double(beyond), nil) < 0,
  "mixed negative integer/real ordering below -2^53")
check(
  scalarOrder(Double(Int64.max), Int64.max, 9_223_372_036_854_775_808.0, nil) < 0,
  "Int64.max compares below out-of-range representable real")
check(
  scalarOrder(Double(Int64.min), Int64.min, -9_223_372_036_854_775_808.0, nil) == 0,
  "Int64.min compares equal to exactly representable real")
check(
  scalarOrder(1, 1, 1.5, nil) < 0 && scalarOrder(-1, -1, -1.5, nil) > 0,
  "mixed fractions compare correctly on both sides of zero")
check(
  scalarOrder(2, 2, 1.9, nil) > 0 && scalarOrder(-2, -2, -1.9, nil) < 0,
  "mixed truncated fractions retain order")
_ = try reader.readPlot(query(buckets: 67))
let journalBefore = counter("invalidations")
for offset in stride(from: 0, to: 768, by: 256) {
  _ = try db.appendBatch((offset..<(offset + 256)).map { try event(1_000 + Double($0), value: .number(50)) })
}
_ = try reader.readPlot(query(buckets: 68))
check(
  counter("invalidations") >= journalBefore + 2,
  "journal overflow invalidates cache rather than guessing append-only history")

try clearPersistedPlots()
let bounded = MonitorDataStore(root: root, projectionCacheBytes: 40_000)
let streamed = try bounded.readPlot(query(buckets: 69))
check(
  counter("streamingFallbacks", bounded) == 2 && counter("nativeAdapterBytes", bounded) == 0,
  "oversized channels skip cache admission")
try clearPersistedPlots()
let cachedEquivalent = try reader.readPlot(query(buckets: 69))
check(
  try JSONSerialization.data(withJSONObject: streamed["series"]!, options: [.sortedKeys])
    == JSONSerialization.data(withJSONObject: cachedEquivalent["series"]!, options: [.sortedKeys]),
  "streaming and cached paths preserve identical geometry and gaps")

let smallRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
_ = try db.appendBatch(
  (0..<300).map { i in
    try WorkoutEvent(
      workoutId: smallRide.id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(Double(i)),
      elapsedSeconds: Double(i),
      payload: ["humanPowerW": .number(Double(i)), "cadenceRpm": .number(75), "batteryVoltageV": .number(52)])
  })
let evicting = MonitorDataStore(root: root, projectionCacheBytes: 180_000)
let smallQuery = MonitorRequest(
  source: "workout", id: smallRide.id, metrics: ["humanPowerW", "cadenceRpm", "batteryVoltageV"], buckets: 3)
_ = try evicting.readPlot(smallQuery)
let allocated = evicting.projectionDiagnostics()
check(
  (allocated["peakNativeAdapterBytes"] ?? 0) <= 90_000 && (allocated["nativeAdapterBytes"] ?? 0) <= 90_000,
  "native allocation high-water never exceeds hard byte allowance")
check(
  counter("evictions", evicting) > 0 && counter("entries", evicting) == 2,
  "bounded LRU evicts compact projections when selected working set exceeds budget")
check(
  MonitorDataStore.maximumProjectionBytes + MonitorDataStore.plotWorkingBytes == 24 * 1024 * 1024,
  "projection ceiling leaves explicit bounded-work reserve within shared budget")

let growingRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
func growingEvent(_ i: Int) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: growingRide.id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(Double(i)),
    elapsedSeconds: Double(i), payload: ["humanPowerW": .number(Double(i)), "cadenceRpm": .number(75)])
}
for offset in stride(from: 0, to: 1_000, by: 250) {
  _ = try db.appendBatch((offset..<(offset + 250)).map(growingEvent))
}
let growing = MonitorDataStore(root: root, projectionCacheBytes: 100_000)
var growingQuery = MonitorRequest(source: "workout", id: growingRide.id, metrics: ["humanPowerW"], buckets: 5)
_ = try growing.readPlot(growingQuery)
_ = try db.appendBatch((1_000..<1_030).map(growingEvent))
growingQuery.buckets = 6
let grown = try growing.readPlot(growingQuery)
check(
  counter("nativeAdapterBytes", growing) == 0 && counter("scannedPoints", growing) == 1_030,
  "append exhaustion streams only suffix and drops retained prefix")
check(
  counter("peakNativeAdapterBytes", growing) <= 50_000 && points(grown).last?["value"] as? Double == 1_029,
  "budget exhaustion preserves final geometry under hard allocation cap")

// A writer advances revision while the production reader scans fixed-revision pages.
final class WriterState: @unchecked Sendable {
  let lock = NSLock()
  var done = false
  var error: Error?
  func shouldStop() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return done
  }
  func finish(_ error: Error? = nil) {
    lock.lock()
    defer { lock.unlock() }
    done = true
    if let error { self.error = error }
  }
}
let state = WriterState()
let writerStarted = DispatchSemaphore(value: 0)
let writerFinished = DispatchSemaphore(value: 0)
let streaming = MonitorDataStore(root: root, projectionCacheBytes: 0)
try clearPersistedPlots()
DispatchQueue.global(qos: .userInitiated).async {
  defer { writerFinished.signal() }
  do {
    for index in 0..<2_000 {
      if state.shouldStop() { break }
      try archive.append(event(20_000 + Double(index), value: .number(Double(index))))
      if index == 0 { writerStarted.signal() }
    }
  } catch {
    state.finish(error)
    writerStarted.signal()
  }
}
writerStarted.wait()
let coherent = try streaming.readPlot(query(buckets: 70))
state.finish()
writerFinished.wait()
if let error = state.error { throw error }
let captured = Int64((coherent["revision"] as! String).split(separator: ":").last!)!
let finalRevision = try db.collection(id: ride.id).int("revision")!
check(
  coherent["status"] as? String == "ok" && finalRevision > captured,
  "continuous writes publish an admitted coherent snapshot without retry starvation")
let allGeometry = (coherent["series"] as? [String: [[String: Any]]])?.values.flatMap { $0 } ?? []
let selectedIDs = allGeometry.compactMap {
  ($0["observationId"] as? String)?.replacingOccurrences(of: "event:", with: "")
}
for offset in stride(from: 0, to: selectedIDs.count, by: 256) {
  let page = Array(selectedIDs[offset..<min(selectedIDs.count, offset + 256)])
  let newer = try db.read { sql in
    try sql.scalarInt(
      "SELECT count(*) FROM collection_memberships WHERE collection_id=? AND revision>? AND event_id IN (\(Array(repeating: "?", count: page.count).joined(separator: ",")))",
      [.text(ride.id), .integer(captured)] + page.map(PowerLogSQLValue.text)) ?? 0
  }
  check(newer == 0, "published chart includes no observation newer than its admitted revision")
}

let intervalRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
_ = try db.appendBatch(
  (0..<6).map { i in
    try WorkoutEvent(
      workoutId: intervalRide.id, kind: "telemetry", source: "controller",
      timestamp: origin.addingTimeInterval(Double(i)),
      elapsedSeconds: Double(i), payload: ["humanPowerW": .number(Double(i * 10)), "cadenceRpm": .number(75)])
  })
for (time, action) in [(1.5, "pause"), (3.0, "resume"), (4.5, "stop")] {
  try archive.append(
    WorkoutEvent(
      workoutId: intervalRide.id, kind: "lifecycle", source: "phone", timestamp: origin.addingTimeInterval(time),
      elapsedSeconds: time, payload: ["action": .string(action)]))
}
let intervalsReader = MonitorDataStore(root: root)
var intervalQuery = MonitorRequest(
  source: "workout", id: intervalRide.id, startSeconds: 0, endSeconds: 5, metrics: ["humanPowerW"], buckets: 2)
_ = try intervalsReader.readPlot(intervalQuery)
var intervalScans = counter("scannedPoints", intervalsReader)
let intervalStats = (try intervalsReader.rangeStats(intervalQuery)["statistics"] as! [String: [String: Any]])[
  "humanPowerW"]!
check(
  intervalStats["count"] as? Int == 4 && intervalStats["coveredSeconds"] as? Double == 2
    && intervalStats["integral"] as? Double == 40,
  "cached full statistics preserve pause/stop exclusion and valid integration edges")
intervalScans = counter("scannedPoints", intervalsReader)
intervalQuery.startSeconds = 0.5
intervalQuery.endSeconds = 3.5
let clippedStats = (try intervalsReader.rangeStats(intervalQuery)["statistics"] as! [String: [String: Any]])[
  "humanPowerW"]!
check(
  clippedStats["count"] as? Int == 2 && clippedStats["coveredSeconds"] as? Double == 1
    && clippedStats["integral"] as? Double == 20, "cached statistics refine fractional A/B boundaries exactly")
check(
  counter("scannedPoints", intervalsReader) == intervalScans,
  "lifecycle-aware A/B reads reuse their statistics projection")

// Exercise the exact production scheduler, with the analytical scan held after a SQLite
// page release. Fast queries must finish while that analytical job is still admitted.
final class PageGate: @unchecked Sendable {
  let lock = NSLock(), entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
  var used = false
  func visit() {
    lock.lock()
    let shouldWait = !used
    used = true
    lock.unlock()
    if shouldWait {
      entered.signal()
      release.wait()
    }
  }
}
final class ReadResultBox: @unchecked Sendable {
  let lock = NSLock()
  private var result: Result<[String: Any], Error>?
  func put(_ value: Result<[String: Any], Error>) {
    lock.lock()
    defer { lock.unlock() }
    result = value
  }
  func get() throws -> [String: Any] {
    lock.lock()
    defer { lock.unlock() }
    return try result!.get()
  }
}
try clearPersistedPlots()
let gate = PageGate()
let statsDone = DispatchSemaphore(value: 0)
let plotDone = DispatchSemaphore(value: 0)
let scheduled = MonitorDataStore(root: root, projectionCacheBytes: 0, projectionPageObserver: { gate.visit() })
let scheduledStats = ReadResultBox()
let scheduledPlot = ReadResultBox()
var queuedStats = query(["humanPowerW", "cadenceRpm", "faultCode"], start: 600, end: 602)
queuedStats.expectedRevision = try reader.describeSource(queuedStats)["revision"] as? String
queuedStats.includeEndpoints = true
check(
  scheduled.submit(.stats, request: queuedStats) {
    scheduledStats.put($0)
    statsDone.signal()
  }, "statistics admitted through production scheduler")
check(gate.entered.wait(timeout: .now() + 5) == .success, "statistics test holds a released SQLite page")
do {
  _ = try scheduled.rangeStats(queuedStats)
  fatalError("statistics cache contention expected")
} catch {
  check(readErrorCode(error) == "ERR_READ_CONTENTION", "direct cache contention retains its bridge rejection code")
}
let comparisonDone = DispatchSemaphore(value: 0)
check(
  scheduled.submit(.stats, request: queuedStats) { _ in comparisonDone.signal() },
  "one native defensive pending statistics slot is bounded")
let overflow = ReadResultBox()
check(!scheduled.submit(.stats, request: query()) { overflow.put($0) }, "extra statistics rejected before dispatch")
do {
  _ = try overflow.get()
  fatalError("statistics admission expected")
} catch {
  guard case .admission(let lane)? = error as? MonitorError else { throw error }
  check(lane == "statistics", "admission cause identifies statistics lane")
  check(readErrorCode(error) == "ERR_READ_DEFERRED", "scheduler admission retains its bridge rejection code")
}
try archive.append(event(600.5, value: .number(1)))
check(
  scheduled.submit(.plot, request: query(buckets: 71)) {
    scheduledPlot.put($0)
    plotDone.signal()
  }, "plot admitted independently from held statistics")
check(plotDone.wait(timeout: .now() + 5) == .success, "plot completes while statistics is held between pages")
let fastDone = DispatchSemaphore(value: 0)
let releaseFastCompletion = DispatchSemaphore(value: 0)
let fastBox = ReadResultBox()
var exactRequest = query(["humanPowerW"])
exactRequest.seconds = 600
check(
  scheduled.submit(.inspect, request: exactRequest) {
    fastBox.put($0)
    fastDone.signal()
    releaseFastCompletion.wait()
  }, "indexed inspection admitted on independent native lane")
check(fastDone.wait(timeout: .now() + 2) == .success, "exact inspection completes while statistics remains held")
let fastRead = try fastBox.get()
check(
  ((fastRead["points"] as? [String: Any])?["humanPowerW"] as? [String: Any])?["exactValue"] as? String
    == String(big + 1), "fast lane preserves exact original inspection value")
let fastDrain = DispatchGroup()
for _ in 0..<3 {
  fastDrain.enter()
  check(
    scheduled.submit(.describe, request: query()) { _ in fastDrain.leave() }, "bounded fast queue admits available slot"
  )
}
let fastOverflow = ReadResultBox()
check(
  !scheduled.submit(.describe, request: query()) { fastOverflow.put($0) },
  "fast queue rejects work beyond current plus three pending jobs")
do {
  _ = try fastOverflow.get()
  fatalError("fast admission expected")
} catch MonitorError.admission(let lane) { check(lane == "fast", "admission cause identifies fast lane") }
releaseFastCompletion.signal()
check(fastDrain.wait(timeout: .now() + 5) == .success, "fast queue drains independently of statistics page hold")
let latestBox = ReadResultBox()
let latestDone = DispatchSemaphore(value: 0)
try archive.append(event(21_000, value: .integer(big + 7)))
var latestRequest = query(["humanPowerW"])
latestRequest.expectedRevision = "0"
check(
  scheduled.submit(.latest, request: latestRequest) {
    latestBox.put($0)
    latestDone.signal()
  }, "latest ignores stale plot revision at admission")
check(latestDone.wait(timeout: .now() + 2) == .success, "exact latest completes while statistics remains held")
let latest = try latestBox.get()
let latestPoint = (try latestBox.get()["points"] as! [String: [String: Any]])["humanPowerW"]!
check(
  latest["status"] as? String == "ok" && latestPoint["exactValue"] as? String == String(big + 7),
  "latest retains exact integer from its own current revision")
check(
  latestPoint["elapsedSeconds"] as? Double == 21_000
    && latestPoint["timestamp"] as? String == WorkoutCoding.timestamp(origin.addingTimeInterval(21_000)),
  "latest retains original time without held-value synthesis")
gate.release.signal()
check(
  statsDone.wait(timeout: .now() + 5) == .success && comparisonDone.wait(timeout: .now() + 5) == .success,
  "bounded statistics jobs resume and settle")
let admittedStats = try scheduledStats.get()
check(
  admittedStats["revision"] as? String == queuedStats.expectedRevision,
  "statistics retain admission revision despite later capture")
check(
  ((admittedStats["statistics"] as? [String: [String: Any]])?["faultCode"])?["count"] as? Int == 3,
  "fixed-revision statistics exclude later point inside A/B interval")
let endpoints = admittedStats["endpoints"] as! [String: [String: [String: Any]]]
check(
  endpoints["start"]?["humanPowerW"]?["exactValue"] as? String == String(big + 1),
  "comparison endpoint shares statistics admitted revision and exact integer")

// Reverse hold proves the two analytical locks are isolated in both directions.
try clearPersistedPlots()
let reverseGate = PageGate()
let reversePlotDone = DispatchSemaphore(value: 0)
let reverseStatsDone = DispatchSemaphore(value: 0)
let reverse = MonitorDataStore(root: root, projectionCacheBytes: 0, projectionPageObserver: { reverseGate.visit() })
check(reverse.submit(.plot, request: query(buckets: 72)) { _ in reversePlotDone.signal() }, "reverse plot admitted")
check(reverseGate.entered.wait(timeout: .now() + 5) == .success, "plot page held")
check(
  reverse.submit(.stats, request: query(start: 0, end: 2)) { result in
    if case .failure(let error) = result { fatalError(error.localizedDescription) }
    reverseStatsDone.signal()
  }, "statistics admitted while plot held")
check(reverseStatsDone.wait(timeout: .now() + 5) == .success, "statistics complete while plot held")
reverseGate.release.signal()
check(reversePlotDone.wait(timeout: .now() + 5) == .success, "held plot settles once released")

for operation in [MonitorReadOperation.plot, .stats] {
  let deletedRide = try archive.create(
    startedAt: origin, indoor: false, watchEnabled: false, saveToHealth: true, recordGPS: true, sampleHz: 4)
  _ = try archive.appendBatch(
    (0..<300).map { i in
      try WorkoutEvent(
        workoutId: deletedRide.id, kind: "telemetry", source: "controller",
        timestamp: origin.addingTimeInterval(Double(i)),
        elapsedSeconds: Double(i), payload: ["humanPowerW": .number(Double(i)), "cadenceRpm": .number(75)])
    })
  _ = try archive.finish(id: deletedRide.id, endedAt: origin.addingTimeInterval(300))
  let deletionGate = PageGate()
  let deletionDone = DispatchSemaphore(value: 0)
  let deletedResult = ReadResultBox()
  let deletedReader = MonitorDataStore(root: root, projectionPageObserver: { deletionGate.visit() })
  let deletedQuery = MonitorRequest(
    source: "workout", id: deletedRide.id, startSeconds: 0, endSeconds: 300,
    metrics: ["humanPowerW"], buckets: 4)
  check(
    deletedReader.submit(operation, request: deletedQuery) {
      deletedResult.put($0)
      deletionDone.signal()
    }, "read admitted before deletion")
  check(deletionGate.entered.wait(timeout: .now() + 5) == .success, "deletion holds a released analytic page")
  _ = try db.markWorkoutDeleted(id: deletedRide.id)
  check(!deletedReader.discardWorkout(id: deletedRide.id), "cache cleanup defers without blocking an active lane")
  do {
    _ = try deletedReader.readLatest(deletedQuery)
    fatalError("deleted latest must fail")
  } catch PowerLogStorageError.deleted { assertions += 1 }
  deletionGate.release.signal()
  check(deletionDone.wait(timeout: .now() + 5) == .success, "deleted admitted read settles after its held page drains")
  do {
    _ = try deletedResult.get()
    fatalError("deleted admitted geometry must not publish")
  } catch PowerLogStorageError.deleted { assertions += 1 }
  check(
    deletedReader.discardWorkout(id: deletedRide.id) && counter("nativeAdapterBytes", deletedReader) == 0,
    "deleted lane drops newly built projections and bounded cleanup completes")
  check(
    try db.read {
      try $0.scalarInt("SELECT count(*) FROM derived_cache WHERE collection_id=?", [.text(deletedRide.id)])
    } == 0,
    "an admitted read cannot recreate a deleted persisted overview")
}

let evictedRide = try archive.create(
  startedAt: origin, indoor: false, watchEnabled: false, saveToHealth: true, recordGPS: true, sampleHz: 4)
try archive.append(
  WorkoutEvent(
    workoutId: evictedRide.id, kind: "telemetry", source: "controller", timestamp: origin,
    elapsedSeconds: 0, payload: ["humanPowerW": .number(55), "cadenceRpm": .number(75)]))
_ = try archive.finish(id: evictedRide.id, endedAt: origin.addingTimeInterval(10))
let evictedReader = MonitorDataStore(root: root)
let evictedQuery = MonitorRequest(
  source: "workout", id: evictedRide.id, startSeconds: 0, endSeconds: 10, metrics: ["humanPowerW"])
_ = try evictedReader.readPlot(evictedQuery)
_ = try evictedReader.rangeStats(evictedQuery)
_ = try evictedReader.rangeStats(intervalQuery)
let entriesBeforeDelete = counter("entries", evictedReader)
_ = try db.markWorkoutDeleted(id: evictedRide.id)
check(evictedReader.discardWorkout(id: evictedRide.id), "idle warm cache eviction completes immediately")
check(
  counter("entries", evictedReader) > 0 && counter("entries", evictedReader) < entriesBeforeDelete,
  "target deletion retains another workout's numeric cache")
for read in [
  evictedReader.describeSource, evictedReader.readPlot, evictedReader.rangeStats, evictedReader.inspectAt,
  evictedReader.readLatest, evictedReader.changesSince,
] {
  do {
    _ = try read(evictedQuery)
    fatalError("deleted source must reject every read boundary")
  } catch PowerLogStorageError.deleted { assertions += 1 }
}
let boundaryRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false,
  saveToHealth: false, recordGPS: false, sampleHz: 8)
for (time, value) in [(0.0, 100.0), (0.5, nil), (1.0, 110.0), (1.5, nil), (2.0, 120.0)] as [(Double, Double?)] {
  var payload: [String: WorkoutJSON] = ["representation": .string("rawSeries")]
  if let value { payload["heartRateBpm"] = .number(value) } else { payload["activeEnergyKcal"] = .number(0) }
  try archive.append(
    WorkoutEvent(
      workoutId: boundaryRide.id, kind: "health", source: "phone",
      timestamp: origin.addingTimeInterval(time), elapsedSeconds: time, payload: payload))
}
let boundaryReader = MonitorDataStore(root: root)
var boundaryQuery = MonitorRequest(source: "workout", id: boundaryRide.id, metrics: ["heartRateBpm"], buckets: 1)
_ = try boundaryReader.readPlot(boundaryQuery)
let boundaryScans = boundaryReader.projectionDiagnostics()["scannedPoints"]
boundaryQuery.startSeconds = 0.75
boundaryQuery.endSeconds = 1.25
let boundaryPlot = points(try boundaryReader.readPlot(boundaryQuery), metric: "heartRateBpm")
check(
  boundaryReader.projectionDiagnostics()["scannedPoints"] == boundaryScans,
  "Viewport brackets reuse cached raw barriers without reading canonical history")
check(
  boundaryPlot.map { $0["startsSegment"] as! Bool } == [true, true, true],
  "Missing bracket values break both sides of a cached viewport")
let rawBoundary = points(
  try MonitorDataStore(root: root, projectionCacheBytes: 0).readPlot(boundaryQuery), metric: "heartRateBpm")
check(
  NSArray(array: boundaryPlot).isEqual(to: rawBoundary),
  "Cached and raw boundary reduction agree on original points and segments")
let windowRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
for offset in stride(from: 0, to: 20_000, by: 256) {
  _ = try db.appendBatch(
    (offset..<min(20_000, offset + 256)).map { index in
      let time = Double(index) / 8
      return try WorkoutEvent(
        workoutId: windowRide.id, kind: "telemetry", source: "controller",
        timestamp: origin.addingTimeInterval(time), elapsedSeconds: time,
        payload: [
          "humanPowerW": .number(Double(index % 199)), "cadenceRpm": .number(75),
          "motorInputPowerW": .number(Double(300 + index % 100)), "batteryVoltageV": .number(Double(50 + index % 3)),
        ])
    })
}
let windows = MonitorDataStore(root: root, projectionCacheBytes: 2 * 1024 * 1024)
var windowQuery = MonitorRequest(
  source: "workout", id: windowRide.id,
  metrics: ["humanPowerW", "cadenceRpm", "motorInputPowerW", "batteryVoltageV"], buckets: 64)
_ = try windows.readPlot(windowQuery)
check(windows.projectionDiagnostics()["pyramidEntries"] == 4, "Four long channels fit in the reduced cache budget")
windowQuery.startSeconds = 100
windowQuery.endSeconds = 130
_ = try windows.readPlot(windowQuery)
let warmedWindows = windows.projectionDiagnostics()
windowQuery.startSeconds = 101
windowQuery.endSeconds = 121
_ = try windows.readPlot(windowQuery)
check(
  windows.projectionDiagnostics()["scannedPoints"] == warmedWindows["scannedPoints"],
  "Narrow projections fit beside retained pyramids without reading canonical originals again")
check(
  windows.projectionDiagnostics()["pyramidEntries"] == 4,
  "Short-window admission preserves the long-history pyramids")
var wideEdges = windowQuery
wideEdges.buckets = 1
let scansBeforeWideEdges = windows.projectionDiagnostics()["scannedPoints"]
_ = try windows.readPlot(wideEdges)
check(
  windows.projectionDiagnostics()["scannedPoints"] == scansBeforeWideEdges,
  "Pyramid boundary leaves reuse covering stored projections")
let textRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 4)
let textEpoch = UUID().uuidString.lowercased()
let textConnection = UUID().uuidString.lowercased()
var textClock = TelemetryCaptureClock(origin: 1000, wallOrigin: origin, epochID: textEpoch)
let textReading = try testTelemetryReading(["humanPowerW": 100, "cadenceRpm": 75])
var textEvents: [WorkoutEvent] = []
for i in 0..<300 {
  let sample = try textClock.observation(
    textReading, connectionEpoch: textConnection, monotonic: 1000 + Double(i),
    wall: origin.addingTimeInterval(Double(i)))
  textEvents.append(
    try WorkoutEvent(dictionary: [
      "schemaVersion": 1, "eventId": sample["observationId"]!, "workoutId": textRide.id,
      "kind": "telemetry", "source": "controller", "timestamp": sample["timestamp"]!,
      "elapsedSeconds": Double(i), "payload": sample,
    ]))
}
_ = try db.appendBatch(textEvents)
let textPlot = try MonitorDataStore(root: root).readPlot(
  MonitorRequest(source: "workout", id: textRide.id, metrics: ["humanPowerW"], buckets: 1))
check(
  points(textPlot).last?["startsSegment"] as? Bool == false,
  "Compact projected pages preserve admitted connection continuity across page boundaries")
do {
  _ = try textClock.observation(
    textReading, connectionEpoch: textConnection + "\u{0}suffix", monotonic: 1300,
    wall: origin.addingTimeInterval(300))
  check(false, "An embedded NUL cannot enter a chart through capture admission")
} catch {
  check(error.localizedDescription == "invalidEvidence", "An embedded NUL remains rejected by capture admission")
}
print("Native projection cache, statistics, scheduling and admitted snapshots: \(assertions) assertions passed")
