import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-projection-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: db)
let reader = MonitorDataStore(root: root)
let origin = Date(timeIntervalSince1970: 1_780_000_000)
let ride = try archive.create(startedAt: origin, indoor: false, watchEnabled: true)
func event(_ time: Double, value: WorkoutJSON, id: String = UUID().uuidString, extra: [String: WorkoutJSON] = [:]) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = ["humanPowerW": value, "cadenceRpm": .number(75)]
  for (key, value) in extra { payload[key] = value }
  return try WorkoutEvent(workoutId: ride.id, kind: "telemetry", source: "cyc", timestamp: origin.addingTimeInterval(time), elapsedSeconds: time, payload: payload, eventId: id)
}
func health(_ time: Double, value: Double, source: String, representation: String) throws {
  try archive.append(WorkoutEvent(workoutId: ride.id, kind: "health", source: source, timestamp: origin.addingTimeInterval(time), elapsedSeconds: time,
    payload: ["heartRateBpm": .number(value), "representation": .string(representation)]))
}
func query(_ metrics: [String] = ["humanPowerW", "cadenceRpm"], start: Double? = nil, end: Double? = nil, buckets: Int = 64) -> MonitorRequest {
  MonitorRequest(source: "workout", id: ride.id, startSeconds: start, endSeconds: end, metrics: metrics, buckets: buckets)
}
func points(_ result: [String: Any], metric: String = "humanPowerW") -> [[String: Any]] { (result["series"] as? [String: [[String: Any]]])?[metric] ?? [] }
func clearPersistedPlots() throws { _ = try db.transaction { try $0.execute("DELETE FROM derived_cache") } }
func counter(_ name: String, _ reader: MonitorDataStore = reader) -> Int { reader.projectionDiagnostics()[name] ?? 0 }
for offset in stride(from: 0, to: 4_096, by: 256) {
  _ = try db.appendBatch((offset..<(offset + 256)).map { try event(Double($0) / 8, value: .number(Double($0 % 200))) })
}
let cold = try reader.readPlot(query())
check(points(cold).count <= 256, "plot geometry remains pixel bounded")
check(counter("scannedPoints") == 8_192 && counter("points") == 8_192, "cold read stores only selected numeric projections")
check(counter("hydratedPoints") < 1_024, "cold projection hydrates retained originals only")
var scanned = counter("scannedPoints")
let hydratedBeforeStats = counter("hydratedPoints")
_ = try reader.rangeStats(query(start: 10.01, end: 11.99))
check(counter("scannedPoints") == scanned + 30, "statistics create an isolated numeric projection without locking plot cache")
scanned = counter("scannedPoints")
_ = try reader.rangeStats(query(start: 10.01, end: 11.99))
check(counter("scannedPoints") == scanned, "warm statistics reuse their own numeric projection")
check(counter("hydratedPoints") - hydratedBeforeStats <= 8, "statistics hydrate only final original extrema")
_ = try reader.readPlot(query(start: 100.01, end: 130.99, buckets: 17))
check(counter("scannedPoints") == scanned && counter("projectionHits") == 4, "pan seeks existing numeric projection without rereading history")
_ = try db.appendBatch((4_096..<4_112).map { try event(Double($0) / 8, value: .number(Double($0 % 200))) })
_ = try reader.readPlot(query(buckets: 65))
check(counter("scannedPoints") - scanned == 32 && counter("appendedPoints") == 32, "tail append reads only new projected values for both metrics")
let beforeLate = counter("invalidations")
try archive.append(event(100.00000004, value: .number(-500)))
let repaired = try reader.readPlot(query(buckets: 66))
check(counter("invalidations") > beforeLate && points(repaired).contains { $0["value"] as? Double == -500 }, "late submicrosecond point invalidates affected projection and preserves exact extremum")
check(points(repaired).contains { $0["elapsedSeconds"] as? Double == 100.00000004 }, "numeric cache retains unquantized source time")

// Equal-time extrema use the lowest stable original identity, independent of insertion order.
let tiedHigh = "ffffffff-ffff-ffff-ffff-ffffffffffff", tiedLow = "00000000-0000-0000-0000-000000000001"
try archive.append(event(200, value: .number(-700), id: tiedHigh))
try archive.append(event(200, value: .number(-700), id: tiedLow))
let ties = try reader.readPlot(query(start: 199, end: 201, buckets: 1))
check(points(ties).contains { $0["observationId"] as? String == "event:\(tiedLow)" }, "stable identity resolves tied projected minima")
let replacement = try event(200, value: .number(15), extra: ["supersedesEventId": .string(tiedLow)])
try archive.append(replacement)
let corrected = try reader.readPlot(query(start: 199, end: 201, buckets: 2))
check(!points(corrected).contains { $0["observationId"] as? String == "event:\(tiedLow)" }, "immutable correction removes superseded projection original")

try health(1, value: 101, source: "phone", representation: "builderMostRecent")
_ = try reader.readPlot(query(["heartRateBpm"], buckets: 7))
try health(2, value: 111, source: "watch", representation: "builderMostRecent")
let preferred = try reader.readPlot(query(["heartRateBpm"], buckets: 8))
check(points(preferred, metric: "heartRateBpm").count == 1 && points(preferred, metric: "heartRateBpm")[0]["value"] as? Double == 111, "preferred Watch arrival replaces entire fallback source")
try health(0.5, value: 121, source: "watch", representation: "rawQuantity")
let raw = try reader.readPlot(query(["heartRateBpm"], buckets: 9))
check(points(raw, metric: "heartRateBpm").count == 1 && points(raw, metric: "heartRateBpm")[0]["value"] as? Double == 121, "first raw quantity replaces builder projection")
let beforeHeart = counter("scannedPoints")
try health(1.5, value: 122, source: "watch", representation: "rawQuantity")
_ = try reader.readPlot(query(["heartRateBpm"], buckets: 10))
check(counter("scannedPoints") == beforeHeart + 1, "ordinary asynchronous HR appends only its numeric tail")

// Large original integers keep a rounded plot coordinate and separate exact readout.
let big: Int64 = 9_007_199_254_740_992
try archive.append(event(600, value: .integer(big + 1)))
try archive.append(event(601, value: .integer(big)))
try archive.append(event(602, value: .integer(big)))
try archive.append(event(603, value: .integer(Int64.max)))
try archive.append(event(604, value: .integer(Int64.max - 1)))
try archive.append(event(605, value: .integer(Int64.min)))
try archive.append(event(606, value: .integer(Int64.min)))
let integerPlot = try reader.readPlot(query(["humanPowerW"], start: 600, end: 602, buckets: 1))
check(points(integerPlot).contains { $0["exactValue"] as? String == String(big + 1) }, "projection retains exact integer metadata")
let stats = try reader.rangeStats(query(["humanPowerW"], start: 600, end: 602))
let stat = (stats["statistics"] as? [String: [String: Any]])?["humanPowerW"]
check((stat?["min"] as? [String: Any])?["elapsedSeconds"] as? Double == 601, "Int64 extrema compare exact values above 2^53 before earliest-time tie")
check((stat?["max"] as? [String: Any])?["exactValue"] as? String == String(big + 1), "range extrema carry original integer digits")
let upperStats = try reader.rangeStats(query(["humanPowerW"], start: 603, end: 604))
let upper = (upperStats["statistics"] as? [String: [String: Any]])?["humanPowerW"]
check((upper?["max"] as? [String: Any])?["exactValue"] as? String == String(Int64.max), "Int64.max compares exactly against its neighboring integer")
let lowerStats = try reader.rangeStats(query(["humanPowerW"], start: 605, end: 606))
let lower = (lowerStats["statistics"] as? [String: [String: Any]])?["humanPowerW"]
check((lower?["max"] as? [String: Any])?["elapsedSeconds"] as? Double == 605, "Int64.min ties follow original time")
var cursor = query(["humanPowerW"]); cursor.seconds = 600
let inspected = try reader.inspectAt(cursor)
check(((inspected["points"] as? [String: Any])?["humanPowerW"] as? [String: Any])?["exactValue"] as? String == String(big + 1), "indexed inspection retains exact integer readout")

// Exercise numeric ordering itself beyond the accepted real payload magnitude limit.
check(monitorValueOrder(Double(big + 1), big + 1, Double(big), nil) > 0, "mixed integer/real ordering above 2^53")
check(monitorValueOrder(Double(-big - 1), -big - 1, -Double(big), nil) < 0, "mixed negative integer/real ordering below -2^53")
check(monitorValueOrder(Double(Int64.max), Int64.max, 9_223_372_036_854_775_808.0, nil) < 0, "Int64.max compares below out-of-range representable real")
check(monitorValueOrder(Double(Int64.min), Int64.min, -9_223_372_036_854_775_808.0, nil) == 0, "Int64.min compares equal to exactly representable real")
check(monitorValueOrder(1, 1, 1.5, nil) < 0 && monitorValueOrder(-1, -1, -1.5, nil) > 0, "mixed fractions compare correctly on both sides of zero")
check(monitorValueOrder(2, 2, 1.9, nil) > 0 && monitorValueOrder(-2, -2, -1.9, nil) < 0, "mixed truncated fractions retain order")
// Missing journal history cannot authorize reuse, even if all missing changes were appends.
_ = try reader.readPlot(query(buckets: 67))
let journalBefore = counter("invalidations")
for offset in stride(from: 0, to: 768, by: 256) {
  _ = try db.appendBatch((offset..<(offset + 256)).map { try event(1_000 + Double($0), value: .number(50)) })
}
_ = try reader.readPlot(query(buckets: 68))
check(counter("invalidations") >= journalBefore + 2, "journal overflow invalidates cache rather than guessing append-only history")

// A deliberately undersized cache takes the same streaming reducer and retains no prefix.
try clearPersistedPlots()
let bounded = MonitorDataStore(root: root, projectionCacheBytes: 40_000)
let streamed = try bounded.readPlot(query(buckets: 69))
check(counter("streamingFallbacks", bounded) == 2 && counter("allocatedBytes", bounded) == 0, "oversized channels skip cache admission")
try clearPersistedPlots()
let cachedEquivalent = try reader.readPlot(query(buckets: 69))
check(try JSONSerialization.data(withJSONObject: streamed["series"]!, options: [.sortedKeys]) == JSONSerialization.data(withJSONObject: cachedEquivalent["series"]!, options: [.sortedKeys]), "streaming and cached paths preserve identical geometry and gaps")

let smallRide = try archive.create(startedAt: origin, indoor: true, watchEnabled: false)
_ = try db.appendBatch((0..<300).map { i in
  try WorkoutEvent(workoutId: smallRide.id, kind: "telemetry", source: "cyc", timestamp: origin.addingTimeInterval(Double(i)), elapsedSeconds: Double(i),
    payload: ["humanPowerW": .number(Double(i)), "cadenceRpm": .number(75), "batteryVoltageV": .number(52)])
})
let evicting = MonitorDataStore(root: root, projectionCacheBytes: 140_000)
let smallQuery = MonitorRequest(source: "workout", id: smallRide.id, metrics: ["humanPowerW", "cadenceRpm", "batteryVoltageV"], buckets: 3)
_ = try evicting.readPlot(smallQuery)
let allocated = evicting.projectionDiagnostics()
check((allocated["peakAllocatedBytes"] ?? 0) <= 70_000 && (allocated["allocatedBytes"] ?? 0) <= 70_000, "native allocation high-water never exceeds hard byte allowance")
check(counter("evictions", evicting) > 0 && counter("entries", evicting) == 2, "bounded LRU evicts compact projections when selected working set exceeds budget")
check(MonitorDataStore.maximumProjectionBytes + MonitorDataStore.plotWorkingBytes == 24 * 1024 * 1024, "projection ceiling leaves explicit bounded-work reserve within shared budget")


// Crossing a cache ceiling during append releases the cached prefix and continues streaming.
let growingRide = try archive.create(startedAt: origin, indoor: true, watchEnabled: false)
func growingEvent(_ i: Int) throws -> WorkoutEvent {
  try WorkoutEvent(workoutId: growingRide.id, kind: "telemetry", source: "cyc", timestamp: origin.addingTimeInterval(Double(i)), elapsedSeconds: Double(i), payload: ["humanPowerW": .number(Double(i)), "cadenceRpm": .number(75)])
}
for offset in stride(from: 0, to: 1_000, by: 250) { _ = try db.appendBatch((offset..<(offset + 250)).map(growingEvent)) }
let growing = MonitorDataStore(root: root, projectionCacheBytes: 80_000)
var growingQuery = MonitorRequest(source: "workout", id: growingRide.id, metrics: ["humanPowerW"], buckets: 5)
_ = try growing.readPlot(growingQuery)
_ = try db.appendBatch((1_000..<1_030).map(growingEvent))
growingQuery.buckets = 6
let grown = try growing.readPlot(growingQuery)
check(counter("allocatedBytes", growing) == 0 && counter("scannedPoints", growing) == 1_030, "append exhaustion streams only suffix and drops retained prefix")
check(counter("peakAllocatedBytes", growing) <= 40_000 && points(grown).last?["value"] as? Double == 1_029, "budget exhaustion preserves final geometry under hard allocation cap")

// A writer advances revision while the production reader scans fixed-revision pages.
final class WriterState: @unchecked Sendable {
  let lock = NSLock()
  var done = false
  var error: Error?
  func shouldStop() -> Bool { lock.lock(); defer { lock.unlock() }; return done }
  func finish(_ error: Error? = nil) { lock.lock(); defer { lock.unlock() }; done = true; if let error { self.error = error } }
}
let state = WriterState(), writerStarted = DispatchSemaphore(value: 0), writerFinished = DispatchSemaphore(value: 0)
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
  } catch { state.finish(error); writerStarted.signal() }
}
writerStarted.wait()
let coherent = try streaming.readPlot(query(buckets: 70))
state.finish()
writerFinished.wait()
if let error = state.error { throw error }
let captured = Int64(coherent["revision"] as! String)!
let finalRevision = try db.collection(id: ride.id).int("revision")!
check(coherent["status"] as? String == "ok" && finalRevision > captured, "continuous writes publish an admitted coherent snapshot without retry starvation")
let allGeometry = (coherent["series"] as? [String: [[String: Any]]])?.values.flatMap { $0 } ?? []
let allLatest = (coherent["latest"] as? [String: [String: Any]])?.values.map { $0 } ?? []
let selectedIDs = (allGeometry + allLatest).compactMap { ($0["observationId"] as? String)?.replacingOccurrences(of: "event:", with: "") }
for offset in stride(from: 0, to: selectedIDs.count, by: 256) {
  let page = Array(selectedIDs[offset..<min(selectedIDs.count, offset + 256)])
  let newer = try db.read { sql in
    try sql.scalarInt("SELECT count(*) FROM collection_memberships WHERE collection_id=? AND revision>? AND event_id IN (\(Array(repeating: "?", count: page.count).joined(separator: ",")))", [.text(ride.id), .integer(captured)] + page.map(PowerLogSQLValue.text)) ?? 0
  }
  check(newer == 0, "published chart includes no observation newer than its admitted revision")
}

// Cached statistics retain the production pause/stop and clipped-integration policy.
let intervalRide = try archive.create(startedAt: origin, indoor: true, watchEnabled: false)
_ = try db.appendBatch((0..<6).map { i in
  try WorkoutEvent(workoutId: intervalRide.id, kind: "telemetry", source: "cyc", timestamp: origin.addingTimeInterval(Double(i)), elapsedSeconds: Double(i), payload: ["humanPowerW": .number(Double(i * 10)), "cadenceRpm": .number(75)])
})
for (time, action) in [(1.5, "pause"), (3.0, "resume"), (4.5, "stop")] {
  try archive.append(WorkoutEvent(workoutId: intervalRide.id, kind: "lifecycle", source: "phone", timestamp: origin.addingTimeInterval(time), elapsedSeconds: time, payload: ["action": .string(action)]))
}
let intervalsReader = MonitorDataStore(root: root)
var intervalQuery = MonitorRequest(source: "workout", id: intervalRide.id, startSeconds: 0, endSeconds: 5, metrics: ["humanPowerW"], buckets: 2)
_ = try intervalsReader.readPlot(intervalQuery)
var intervalScans = counter("scannedPoints", intervalsReader)
let intervalStats = (try intervalsReader.rangeStats(intervalQuery)["statistics"] as! [String: [String: Any]])["humanPowerW"]!
check(intervalStats["count"] as? Int == 4 && intervalStats["coveredSeconds"] as? Double == 2 && intervalStats["integral"] as? Double == 40, "cached full statistics preserve pause/stop exclusion and valid integration edges")
intervalScans = counter("scannedPoints", intervalsReader)
intervalQuery.startSeconds = 0.5; intervalQuery.endSeconds = 3.5
let clippedStats = (try intervalsReader.rangeStats(intervalQuery)["statistics"] as! [String: [String: Any]])["humanPowerW"]!
check(clippedStats["count"] as? Int == 2 && clippedStats["coveredSeconds"] as? Double == 1 && clippedStats["integral"] as? Double == 20, "cached statistics refine fractional A/B boundaries exactly")
check(counter("scannedPoints", intervalsReader) == intervalScans, "lifecycle-aware A/B reads reuse their statistics projection")

// Exercise the exact production scheduler, with the analytical scan held after a SQLite
// page release. Fast queries must finish while that analytical job is still admitted.
final class PageGate: @unchecked Sendable {
  let lock = NSLock(), entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
  var used = false
  func visit() {
    lock.lock(); let shouldWait = !used; used = true; lock.unlock()
    if shouldWait { entered.signal(); release.wait() }
  }
}
final class ReadResultBox: @unchecked Sendable {
  let lock = NSLock()
  private var result: Result<[String: Any], Error>?
  func put(_ value: Result<[String: Any], Error>) { lock.lock(); defer { lock.unlock() }; result = value }
  func get() throws -> [String: Any] { lock.lock(); defer { lock.unlock() }; return try result!.get() }
}
try clearPersistedPlots()
let gate = PageGate(), statsDone = DispatchSemaphore(value: 0), plotDone = DispatchSemaphore(value: 0)
let scheduled = MonitorDataStore(root: root, projectionCacheBytes: 0, projectionPageObserver: { gate.visit() })
let scheduledStats = ReadResultBox(), scheduledPlot = ReadResultBox()
var queuedStats = query(start: 600, end: 602)
queuedStats.expectedRevision = try reader.describeSource(queuedStats)["revision"] as? String
queuedStats.includeEndpoints = true
check(scheduled.submit(.stats, request: queuedStats) { scheduledStats.put($0); statsDone.signal() }, "statistics admitted through production scheduler")
check(gate.entered.wait(timeout: .now() + 5) == .success, "statistics test holds a released SQLite page")
let comparisonDone = DispatchSemaphore(value: 0)
check(scheduled.submit(.stats, request: queuedStats) { _ in comparisonDone.signal() }, "one native defensive pending statistics slot is bounded")
let overflow = ReadResultBox()
check(!scheduled.submit(.stats, request: query()) { overflow.put($0) }, "extra statistics rejected before dispatch")
do { _ = try overflow.get(); fatalError("statistics admission expected") } catch MonitorError.admission(let lane) { check(lane == "statistics", "admission cause identifies statistics lane") }
try archive.append(event(600.5, value: .number(1)))
check(scheduled.submit(.plot, request: query(buckets: 71)) { scheduledPlot.put($0); plotDone.signal() }, "plot admitted independently from held statistics")
check(plotDone.wait(timeout: .now() + 5) == .success, "plot completes while statistics is held between pages")
let fastDone = DispatchSemaphore(value: 0), releaseFastCompletion = DispatchSemaphore(value: 0), fastBox = ReadResultBox()
var exactRequest = query(["humanPowerW"]); exactRequest.seconds = 600
check(scheduled.submit(.inspect, request: exactRequest) { fastBox.put($0); fastDone.signal(); releaseFastCompletion.wait() }, "indexed inspection admitted on independent native lane")
check(fastDone.wait(timeout: .now() + 2) == .success, "exact inspection completes while statistics remains held")
let fastRead = try fastBox.get()
check(((fastRead["points"] as? [String: Any])?["humanPowerW"] as? [String: Any])?["exactValue"] as? String == String(big + 1), "fast lane preserves exact original inspection value")
let fastDrain = DispatchGroup()
for _ in 0..<3 {
  fastDrain.enter()
  check(scheduled.submit(.describe, request: query()) { _ in fastDrain.leave() }, "bounded fast queue admits available slot")
}
let fastOverflow = ReadResultBox()
check(!scheduled.submit(.describe, request: query()) { fastOverflow.put($0) }, "fast queue rejects work beyond current plus three pending jobs")
do { _ = try fastOverflow.get(); fatalError("fast admission expected") } catch MonitorError.admission(let lane) { check(lane == "fast", "admission cause identifies fast lane") }
releaseFastCompletion.signal()
check(fastDrain.wait(timeout: .now() + 5) == .success, "fast queue drains independently of statistics page hold")
let latestBox = ReadResultBox(), latestDone = DispatchSemaphore(value: 0)
try archive.append(event(21_000, value: .integer(big + 7)))
var latestRequest = query(["humanPowerW"]); latestRequest.expectedRevision = "0"
check(scheduled.submit(.latest, request: latestRequest) { latestBox.put($0); latestDone.signal() }, "latest ignores stale plot revision at admission")
check(latestDone.wait(timeout: .now() + 2) == .success, "exact latest completes while statistics remains held")
let latest = try latestBox.get(), latestPoint = (try latestBox.get()["points"] as! [String: [String: Any]])["humanPowerW"]!
check(latest["status"] as? String == "ok" && latestPoint["exactValue"] as? String == String(big + 7), "latest retains exact integer from its own current revision")
check(latestPoint["elapsedSeconds"] as? Double == 21_000 && latestPoint["timestamp"] as? String == WorkoutCoding.timestamp(origin.addingTimeInterval(21_000)), "latest retains original time without held-value synthesis")
gate.release.signal()
check(statsDone.wait(timeout: .now() + 5) == .success && comparisonDone.wait(timeout: .now() + 5) == .success, "bounded statistics jobs resume and settle")
let admittedStats = try scheduledStats.get()
check(admittedStats["revision"] as? String == queuedStats.expectedRevision, "statistics retain admission revision despite later capture")
check(((admittedStats["statistics"] as? [String: [String: Any]])?["humanPowerW"])?["count"] as? Int == 3, "fixed-revision statistics exclude later point inside A/B interval")
let endpoints = admittedStats["endpoints"] as! [String: [String: [String: Any]]]
check(endpoints["start"]?["humanPowerW"]?["exactValue"] as? String == String(big + 1), "comparison endpoint shares statistics admitted revision and exact integer")

// Reverse hold proves the two analytical locks are isolated in both directions.
try clearPersistedPlots()
let reverseGate = PageGate(), reversePlotDone = DispatchSemaphore(value: 0), reverseStatsDone = DispatchSemaphore(value: 0)
let reverse = MonitorDataStore(root: root, projectionCacheBytes: 0, projectionPageObserver: { reverseGate.visit() })
check(reverse.submit(.plot, request: query(buckets: 72)) { _ in reversePlotDone.signal() }, "reverse plot admitted")
check(reverseGate.entered.wait(timeout: .now() + 5) == .success, "plot page held")
check(reverse.submit(.stats, request: query(start: 0, end: 2)) { result in
  if case .failure(let error) = result { fatalError(error.localizedDescription) }; reverseStatsDone.signal()
}, "statistics admitted while plot held")
check(reverseStatsDone.wait(timeout: .now() + 5) == .success, "statistics complete while plot held")
reverseGate.release.signal()
check(reversePlotDone.wait(timeout: .now() + 5) == .success, "held plot settles once released")

// Tombstones fence an already admitted read even while cleanup has not removed originals.
// Run both analytic lanes because each owns an independent mutable cache.
for operation in [MonitorReadOperation.plot, .stats] {
  let deletedRide = try archive.create(startedAt: origin, indoor: false, watchEnabled: false)
  _ = try archive.appendBatch((0..<300).map { i in
    try WorkoutEvent(workoutId: deletedRide.id, kind: "telemetry", source: "cyc", timestamp: origin.addingTimeInterval(Double(i)),
      elapsedSeconds: Double(i), payload: ["humanPowerW": .number(Double(i)), "cadenceRpm": .number(75)])
  })
  _ = try archive.finish(id: deletedRide.id, endedAt: origin.addingTimeInterval(300))
  let deletionGate = PageGate(), deletionDone = DispatchSemaphore(value: 0), deletedResult = ReadResultBox()
  let deletedReader = MonitorDataStore(root: root, projectionPageObserver: { deletionGate.visit() })
  let deletedQuery = MonitorRequest(source: "workout", id: deletedRide.id, startSeconds: 0, endSeconds: 300,
    metrics: ["humanPowerW"], buckets: 4)
  check(deletedReader.submit(operation, request: deletedQuery) { deletedResult.put($0); deletionDone.signal() }, "read admitted before deletion")
  check(deletionGate.entered.wait(timeout: .now() + 5) == .success, "deletion holds a released analytic page")
  _ = try db.markWorkoutDeleted(id: deletedRide.id)
  check(!deletedReader.discardWorkout(id: deletedRide.id), "cache cleanup defers without blocking an active lane")
  do { _ = try deletedReader.readLatest(deletedQuery); fatalError("deleted latest must fail") }
  catch PowerLogStorageError.deleted { assertions += 1 }
  deletionGate.release.signal()
  check(deletionDone.wait(timeout: .now() + 5) == .success, "deleted admitted read settles after its held page drains")
  do { _ = try deletedResult.get(); fatalError("deleted admitted geometry must not publish") }
  catch PowerLogStorageError.deleted { assertions += 1 }
  check(deletedReader.discardWorkout(id: deletedRide.id) && counter("allocatedBytes", deletedReader) == 0,
    "deleted lane drops newly built projections and bounded cleanup completes")
  check(try db.read { try $0.scalarInt("SELECT count(*) FROM derived_cache WHERE collection_id=?", [.text(deletedRide.id)]) } == 0,
    "an admitted read cannot recreate a deleted persisted overview")
}

// Eviction removes only the selected workout's warm caches, preserving another source.
let evictedRide = try archive.create(startedAt: origin, indoor: false, watchEnabled: false)
try archive.append(WorkoutEvent(workoutId: evictedRide.id, kind: "telemetry", source: "cyc", timestamp: origin,
  elapsedSeconds: 0, payload: ["humanPowerW": .number(55), "cadenceRpm": .number(75)]))
_ = try archive.finish(id: evictedRide.id, endedAt: origin.addingTimeInterval(10))
let evictedReader = MonitorDataStore(root: root)
let evictedQuery = MonitorRequest(source: "workout", id: evictedRide.id, startSeconds: 0, endSeconds: 10, metrics: ["humanPowerW"])
_ = try evictedReader.readPlot(evictedQuery); _ = try evictedReader.rangeStats(evictedQuery)
_ = try evictedReader.rangeStats(intervalQuery)
let entriesBeforeDelete = counter("entries", evictedReader)
_ = try db.markWorkoutDeleted(id: evictedRide.id)
check(evictedReader.discardWorkout(id: evictedRide.id), "idle warm cache eviction completes immediately")
check(counter("entries", evictedReader) > 0 && counter("entries", evictedReader) < entriesBeforeDelete,
  "target deletion retains another workout's numeric cache")
for read in [evictedReader.describeSource, evictedReader.readPlot, evictedReader.rangeStats, evictedReader.inspectAt,
  evictedReader.readLatest, evictedReader.changesSince] {
  do { _ = try read(evictedQuery); fatalError("deleted source must reject every read boundary") }
  catch PowerLogStorageError.deleted { assertions += 1 }
}
print("Native projection cache, statistics, scheduling and admitted snapshots: \(assertions) assertions passed")
