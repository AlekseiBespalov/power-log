import Foundation
import CryptoKit

try runStoredPyramidPersistenceFixture()

let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-lod-\(UUID().uuidString)/PowerLog")
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
let db = archive.store
let origin = Date(timeIntervalSince1970: 1_780_000_000)
let ride = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
var assertions = 0
func check(_ condition: @autoclosure () -> Bool, _ message: String) {
  precondition(condition(), message)
  assertions += 1
}
func event(_ time: Double, value: Double, id: String = UUID().uuidString.lowercased()) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: ride.id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
    elapsedSeconds: time,
    payload: ["humanPowerW": .number(value), "cadenceRpm": .number(75)], eventId: id)
}
for offset in stride(from: 0, to: 32_000, by: 256) {
  let events = try (offset..<min(32_000, offset + 256)).compactMap { index -> WorkoutEvent? in
    let t = Double(index) / 8
    if t >= 1000 && t < 1008 { return nil }
    return try event(t, value: t == 1008 ? 10_000 : Double(index % 199))
  }
  _ = try db.appendBatch(events)
}
let reader = MonitorDataStore(root: root)
func q(_ start: Double = 0, _ end: Double = 3999.875, _ buckets: Int = 64) -> MonitorRequest {
  MonitorRequest(
    source: "workout", id: ride.id, startSeconds: start, endSeconds: end, metrics: ["humanPowerW"], buckets: buckets)
}
func points(_ request: MonitorRequest) throws -> [[String: Any]] {
  let result = try reader.readPlot(request)
  check(result["status"] as? String == "ok", "successful bounded read")
  return (result["series"] as! [String: [[String: Any]]])["humanPowerW"]!
}
let countBefore = try archive.revision(id: ride.id)
var nodeProjections = 0
MonitorDataStore.aggregateProjectionObserverForTesting = { nodeProjections += 1 }
let first = try points(q())
MonitorDataStore.aggregateProjectionObserverForTesting = nil
check(
  nodeProjections > 0 && nodeProjections <= 500,
  "Stored pyramid parents retain core aggregates without point round trips")
check(first.count <= 4 * 66 + 2, "wide geometry is bounded by display width")
check(
  first.contains { $0["elapsedSeconds"] as? Double == 1008 && $0["startsSegment"] as? Bool == true },
  "coarse geometry preserves the original gap and exact spike")
let initial = reader.projectionDiagnostics()
check(initial["pyramidBuilds"] == 1, "long history builds one hierarchy")
check(initial["pyramidOriginalPoints"] == 31_936, "originals scanned once to build the hierarchy")
for i in 0..<20 {
  let start = 20.03 + Double(i) * 0.37
  let end = 3800.91 + Double(i) * 0.31
  let result = try points(q(start, end, 63 + i % 3))
  let inside = result.filter { ($0["elapsedSeconds"] as! Double) >= start && ($0["elapsedSeconds"] as! Double) <= end }
  check(inside.map { $0["value"] as! Double }.max() == 10_000, "every moved window preserves the exact peak")
}
var aggregateAdmissions = 0
MonitorDataStore.aggregateAdmissionObserverForTesting = { aggregateAdmissions += 1 }
_ = try points(q(20.03, 3800.91, 64))
MonitorDataStore.aggregateAdmissionObserverForTesting = nil
check(aggregateAdmissions == 0, "Wide stored navigation reuses admitted core aggregates without node conversion")
let after = reader.projectionDiagnostics()
check(
  after["pyramidOriginalPoints"] == initial["pyramidOriginalPoints"],
  "changing wide viewports never rescans the full-resolution history")
check(
  (after["scannedPoints"] ?? 0) - (initial["scannedPoints"] ?? 0) <= 21 * 128,
  "only clipped eight-second edge tiles may read originals")
check(
  after["nativeAdapterBytes"] != nil && after["nativeAdapterByteLimit"] != nil
    && after["allocatedBytes"] == nil,
  "diagnostics explicitly name native adapter storage rather than core graph allocation")
check(
  after["nativeAdapterBytes"]! <= after["nativeAdapterByteLimit"]!,
  "hierarchy respects its native adapter storage limit")
let gap = try points(q(999.99, 1009.99, 1))
check(
  gap.contains { $0["elapsedSeconds"] as? Double == 1008 && $0["startsSegment"] as? Bool == true },
  "partial edge tiles retain gap semantics")
let revisionAfter = try archive.revision(id: ride.id)
check(revisionAfter == countBefore, "chart reads do not alter recordings")
let constrained = MonitorDataStore(root: root, projectionCacheBytes: 512 * 1024)
_ = try constrained.readPlot(q())
let constrainedBefore = constrained.projectionDiagnostics()
_ = try constrained.rangeStats(q())
_ = try constrained.readPlot(q(10.1, 3900.2))
let constrainedAfter = constrained.projectionDiagnostics()
check(
  constrainedAfter["pyramidOriginalPoints"] == constrainedBefore["pyramidOriginalPoints"],
  "exact statistics never evict navigation summaries under memory pressure")
check(
  constrainedAfter["pyramidEntries"] == 1 && constrainedAfter["nativeAdapterBytes"]! <= 512 * 1024,
  "exact reads stream within the native adapter storage limit")
let beforeAppend = reader.projectionDiagnostics()["pyramidOriginalPoints"]!
_ = try db.appendBatch((0..<8).map { try event(4000 + Double($0) / 8, value: 77) })
_ = try points(q(0, 4000.875, 62))
check(
  reader.projectionDiagnostics()["pyramidOriginalPoints"]! - beforeAppend == 8, "live append extends only the new tail")
try archive.append(event(2222.0125, value: -1000))
let corrected = try points(q(0, 4000.875, 61))
check(
  corrected.contains { $0["value"] as? Double == -1000 && $0["elapsedSeconds"] as? Double == 2222.0125 },
  "late submillisecond extrema rebuild affected hierarchy")
check(reader.projectionDiagnostics()["pyramidInvalidations"] == 1, "late data invalidates the hierarchy")
var inspect = q()
inspect.seconds = 2222.0125
let exact = try reader.inspectAt(inspect)
check(
  ((exact["points"] as? [String: [String: Any]])?["humanPowerW"])?["value"] as? Double == -1000,
  "cursor still reads original values")
_ = try archive.finish(id: ride.id, endedAt: origin.addingTimeInterval(4001))
_ = try points(q(0, 4000.875, 60))
PowerLogStore.evictSharedForTesting(databaseURL: archive.store.databaseURL)
let reopened = MonitorDataStore(root: root)
let restored = try reopened.readPlot(q(0.02, 3999.8, 63))
check(
  restored["status"] as? String == "ok" && reopened.projectionDiagnostics()["pyramidDiskHits"] == 1,
  "reopened completed rides load persisted summaries")
check(
  (reopened.projectionDiagnostics()["pyramidOriginalPoints"] ?? 0) == 0,
  "reopening the hierarchy does not rescan original history")
try db.transaction { sql in
  let row = try sql.rows("SELECT key,value FROM derived_cache WHERE key LIKE 'lod|%'", limit: 1).first!
  var bytes = row.data("value")!
  bytes[100] ^= 1
  try sql.execute("UPDATE derived_cache SET value=? WHERE key=?", [.blob(bytes), .text(row.string("key")!)])
}
let repairing = MonitorDataStore(root: root)
let recovered = try repairing.readPlot(q(0.04, 3999.9, 63))
check(
  recovered["status"] as? String == "ok" && repairing.projectionDiagnostics()["pyramidBuilds"] == 1,
  "damaged derived summaries rebuild from originals")
check(
  (repairing.projectionDiagnostics()["pyramidDiskHits"] ?? 0) == 0, "checksum rejects corrupted cached measurements")
let runsRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
let runTimes = [0.0, 1.0, 8.0, 9.0, 16.0, 17.0]
for (time, value) in zip(runTimes, [0.0, 100.0, 50.0, 51.0, 0.0, 100.0]) {
  try archive.append(
    WorkoutEvent(
      workoutId: runsRide.id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
      elapsedSeconds: time, payload: ["humanPowerW": .number(value), "cadenceRpm": .number(70)]))
}
func padHistory(_ id: String) throws {
  for offset in stride(from: 0, to: 16_384, by: 256) {
    _ = try db.appendBatch(
      (offset..<min(16_384, offset + 256)).map { index in
        let time = 100 + Double(index) / 8
        return try WorkoutEvent(
          workoutId: id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
          elapsedSeconds: time, payload: ["humanPowerW": .number(100), "cadenceRpm": .number(70)])
      })
  }
}
try padHistory(runsRide.id)
let runsQuery = MonitorRequest(
  source: "workout", id: runsRide.id, startSeconds: 0, endSeconds: 17, metrics: ["humanPowerW"], buckets: 1)
func checkRuns(_ monitor: MonitorDataStore, _ message: String) throws {
  let rows = (try monitor.readPlot(runsQuery)["series"] as! [String: [[String: Any]]])["humanPowerW"]!.filter {
    ($0["elapsedSeconds"] as! Double) <= 17
  }
  check(rows.map { $0["elapsedSeconds"] as! Double } == runTimes, message)
  check(
    rows.map { $0["startsSegment"] as! Bool } == [true, false, true, false, true, false],
    "Every retained run has its own explicit start")
  check(rows.count <= MonitorDataStore.maximumPlotPoints, "Run preservation respects the output admission bound")
}
try checkRuns(reader, "One coarse pyramid bucket keeps the short 8-9 second run")
check(reader.projectionDiagnostics()["pyramidBuilds"]! > 1, "Middle-run regression exercises a pyramid")
let streamingRuns = MonitorDataStore(root: root, projectionCacheBytes: 0)
try checkRuns(streamingRuns, "Streaming reduction keeps first/min/max/last independently for every run")
check(
  (streamingRuns.projectionDiagnostics()["pyramidBuilds"] ?? 0) == 0, "Streaming regression bypasses pyramid caching")
_ = try archive.finish(id: runsRide.id, endedAt: origin.addingTimeInterval(2148))
try checkRuns(reader, "Completed overview retains middle runs")
PowerLogStore.evictSharedForTesting(databaseURL: archive.store.databaseURL)
let reopenedRuns = MonitorDataStore(root: root)
var restoredRunsQuery = runsQuery
restoredRunsQuery.endSeconds = 16.5
let restoredRuns = (try reopenedRuns.readPlot(restoredRunsQuery)["series"] as! [String: [[String: Any]]])[
  "humanPowerW"]!
check(
  restoredRuns.contains { $0["elapsedSeconds"] as? Double == 8 }
    && restoredRuns.contains { $0["elapsedSeconds"] as? Double == 9 },
  "Persisted pyramid summaries retain the short middle run after reopening")
check(reopenedRuns.projectionDiagnostics()["pyramidDiskHits"] == 1, "Run regression reads the persisted pyramid")
let leafRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
for (time, value) in [(0.0, 0.0), (0.1, 100.0), (6.2, 50.0), (6.3, 51.0), (12.4, 0.0), (12.5, 100.0)] {
  try archive.append(
    WorkoutEvent(
      workoutId: leafRide.id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
      elapsedSeconds: time, payload: ["humanPowerW": .number(value), "cadenceRpm": .number(70)]))
}
try padHistory(leafRide.id)
let leafQuery = MonitorRequest(
  source: "workout", id: leafRide.id, startSeconds: 0, endSeconds: 12.5, metrics: ["humanPowerW"], buckets: 1)
let leafRuns = (try reader.readPlot(leafQuery)["series"] as! [String: [[String: Any]]])["humanPowerW"]!
check(
  leafRuns.filter { ($0["elapsedSeconds"] as! Double) <= 12.5 }.count == 6
    && leafRuns.contains { $0["elapsedSeconds"] as? Double == 6.2 },
  "A mixed-run leaf descends to originals without dropping its short run")
let fragmentedRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false, sampleHz: 4)
for offset in stride(from: 0, to: 5_500, by: 256) {
  _ = try db.appendBatch(
    (offset..<min(5_500, offset + 256)).map { index in
      try WorkoutEvent(
        workoutId: fragmentedRide.id, kind: "telemetry", source: "controller",
        timestamp: origin.addingTimeInterval(Double(index) * 8),
        elapsedSeconds: Double(index) * 8,
        payload: ["humanPowerW": .number(100), "cadenceRpm": .number(70), "batteryVoltageV": .number(52)])
    })
}
let fragmentedQuery = MonitorRequest(
  source: "workout", id: fragmentedRide.id, startSeconds: 0, endSeconds: 44_000,
  metrics: ["humanPowerW", "cadenceRpm", "batteryVoltageV"], buckets: 1)
func submittedRead(_ operation: MonitorReadOperation, _ request: MonitorRequest) throws -> [String: Any] {
  let done = DispatchSemaphore(value: 0)
  var result: Result<[String: Any], Error>?
  check(
    reader.submit(operation, request: request) {
      result = $0
      done.signal()
    }, "Monitor read enters the bridge-facing scheduler")
  check(done.wait(timeout: .now() + 30) == .success, "Submitted monitor read completes")
  return try result!.get()
}
for read in [{ try submittedRead(.plot, fragmentedQuery) }, { try reader.readPlot(fragmentedQuery) }] {
  do {
    _ = try read()
    fatalError("A plot exceeding the geometry bound must not silently drop runs")
  } catch {
    check(
      readErrorCode(error) == "ERR_CHART_CAPACITY",
      "Chart capacity must reject with ERR_CHART_CAPACITY, got \(readErrorCode(error) ?? "uncoded")")
    check(
      error.localizedDescription == "Choose a smaller range with fewer chart discontinuities.",
      "Chart capacity retains the rider recovery message")
    check(
      (error as? MonitorError)?.bridgeMessage == "Choose a smaller range with fewer chart discontinuities.",
      "Bridge rejection preserves the chart recovery message instead of the unknown failure text")
  }
}
var narrowedFragments = fragmentedQuery
narrowedFragments.endSeconds = 80
let narrowedSeries = try submittedRead(.plot, narrowedFragments)["series"] as! [String: [[String: Any]]]
check(
  narrowedSeries.values.reduce(0) { $0 + $1.count } <= MonitorDataStore.maximumPlotPoints,
  "A narrowed fragmented range remains available within the output bound")
var fragmentInspection = fragmentedQuery
fragmentInspection.seconds = 8
let inspectedFragment = try submittedRead(.inspect, fragmentInspection)["points"] as! [String: [String: Any]]
check(
  inspectedFragment["humanPowerW"]?["elapsedSeconds"] as? Double == 8,
  "Exact inspection remains available after a plot capacity rejection")
let crowdedRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
for offset in stride(from: 0, to: 16_386, by: 256) {
  try archive.appendBatch(
    (offset..<min(16_386, offset + 256)).map { index in
      let time = Double(index) / 4_096
      return try WorkoutEvent(
        workoutId: crowdedRide.id, kind: "telemetry", source: "controller",
        timestamp: origin.addingTimeInterval(time), elapsedSeconds: time,
        payload: ["humanPowerW": .number(100), "cadenceRpm": .number(70), "clockEpoch": .string("epoch-\(index)")])
    })
}
try archive.append(
  WorkoutEvent(
    workoutId: crowdedRide.id, kind: "telemetry", source: "controller",
    timestamp: origin.addingTimeInterval(16), elapsedSeconds: 16,
    payload: ["humanPowerW": .number(125), "cadenceRpm": .number(70)]))
let fallbacksBefore = reader.projectionDiagnostics()["streamingFallbacks"] ?? 0
let crowdedQuery = MonitorRequest(
  source: "workout", id: crowdedRide.id, startSeconds: 5, endSeconds: 16, metrics: ["humanPowerW"], buckets: 1)
let streamed = try submittedRead(.plot, crowdedQuery)["series"] as! [String: [[String: Any]]]
check(
  reader.projectionDiagnostics()["streamingFallbacks"] == fallbacksBefore + 1,
  "A pyramid leaf exceeding discontinuity capacity falls back to streaming")
check(
  streamed["humanPowerW"]?.map { $0["value"] as! Double } == [100, 125],
  "Streaming after pyramid capacity retains the narrow range and its original boundary")
let compressedRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 4)
let compressedTimes = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5]
for (index, time) in compressedTimes.enumerated() {
  try archive.append(
    WorkoutEvent(
      workoutId: compressedRide.id, kind: "telemetry", source: "controller",
      timestamp: origin.addingTimeInterval(time + Double(index / 2) * 600), elapsedSeconds: time,
      payload: [
        "humanPowerW": .number([0.0, 100.0, 50.0, 51.0, 0.0, 100.0][index]),
        "cadenceRpm": .number(70), "clockEpoch": .string("process-\(index / 2)"),
        "connectionEpoch": .string("radio-\(index / 2)"),
      ]))
}
try padHistory(compressedRide.id)
var compressedQuery = MonitorRequest(
  source: "workout", id: compressedRide.id, startSeconds: 0, endSeconds: 9,
  metrics: ["humanPowerW"], buckets: 1)
func compressedPoints(_ monitor: MonitorDataStore) throws -> [[String: Any]] {
  (try monitor.readPlot(compressedQuery)["series"] as! [String: [[String: Any]]])["humanPowerW"]!.filter {
    ($0["elapsedSeconds"] as! Double) <= 0.5
  }
}
let compressed = try compressedPoints(reader)
check(
  compressed.map { $0["elapsedSeconds"] as! Double } == compressedTimes
    && compressed.map { $0["startsSegment"] as! Bool } == [true, false, true, false, true, false],
  "one pyramid leaf preserves compressed process restarts and its otherwise discarded interior run")
let compressedStream = try compressedPoints(MonitorDataStore(root: root, projectionCacheBytes: 0))
check(
  NSArray(array: compressed).isEqual(to: compressedStream),
  "ordinary and pyramid reduction agree on compressed restart geometry")
let invalidationsBeforeInterruption = reader.projectionDiagnostics()["pyramidInvalidations"] ?? 0
try archive.append(
  WorkoutEvent(
    workoutId: compressedRide.id, kind: "lifecycle", source: "phone",
    timestamp: origin.addingTimeInterval(600.2), elapsedSeconds: 0.2,
    payload: ["action": .string("pause"), "interrupted": .bool(true), "controllerSequence": .string("3")]))
let changedCompressed = try compressedPoints(reader)
check(
  changedCompressed.map { $0["startsSegment"] as! Bool } == [true, false, true, true, true, false]
    && (reader.projectionDiagnostics()["pyramidInvalidations"] ?? 0) > invalidationsBeforeInterruption,
  "late interruption evidence invalidates a warm hierarchy and splits its same-epoch run")
try archive.update(id: compressedRide.id, stopElapsedSeconds: 2148)
_ = try archive.finish(id: compressedRide.id, endedAt: origin.addingTimeInterval(3348))
_ = try compressedPoints(reader)
PowerLogStore.evictSharedForTesting(databaseURL: archive.store.databaseURL)
let compressedReopened = MonitorDataStore(root: root)
compressedQuery.startSeconds = 0.05
let restoredCompressed = try compressedPoints(compressedReopened)
check(
  NSArray(array: changedCompressed).isEqual(to: restoredCompressed)
    && compressedReopened.projectionDiagnostics()["pyramidDiskHits"] == 1,
  "persisted continuity evidence survives reopen and mixed-leaf edge rescanning")
compressedQuery.startSeconds = 0
compressedQuery.endSeconds = 0.25
let compressedEdge = try compressedPoints(compressedReopened)
check(
  compressedEdge.map { $0["elapsedSeconds"] as! Double } == [0, 0.1, 0.2, 0.3]
    && compressedEdge.last?["startsSegment"] as? Bool == true,
  "a successor just outside the viewport keeps its persisted interruption boundary")

let barriersRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
for offset in stride(from: 0, to: 16_500, by: 256) {
  _ = try db.appendBatch(
    (offset..<min(16_500, offset + 256)).map { index in
      let time = Double(index) / 8 + (index == 0 ? 0.0000002 : 0)
      var payload: [String: WorkoutJSON] = ["representation": .string("rawSeries")]
      if time < 8 || time >= 16 {
        payload["heartRateBpm"] = .number(time >= 24 && time < 32 ? 0 : 100 + Double(index % 10))
      } else {
        payload["activeEnergyKcal"] = .number(0)
      }
      return try WorkoutEvent(
        workoutId: barriersRide.id, kind: "health", source: "phone",
        timestamp: origin.addingTimeInterval(time), elapsedSeconds: time, payload: payload)
    })
}
_ = try archive.finish(id: barriersRide.id, endedAt: origin.addingTimeInterval(2500))
var barriersQuery = MonitorRequest(
  source: "workout", id: barriersRide.id, startSeconds: 0,
  endSeconds: 40, metrics: ["heartRateBpm"], buckets: 1)
func checkBarriers(_ monitor: MonitorDataStore, _ message: String) throws {
  let rows = (try monitor.readPlot(barriersQuery)["series"] as! [String: [[String: Any]]])["heartRateBpm"]!
  for time in [16.0, 32.0] {
    check(rows.contains { $0["elapsedSeconds"] as? Double == time && $0["startsSegment"] as? Bool == true }, message)
  }
  check(!rows.contains { ($0["value"] as! Double) == 0 }, "Invalid heart readings are barriers without plotted zeros")
}
try checkBarriers(reader, "Pyramid keeps missing-only and invalid-only leaf barriers")
let barrierKey = "lod|workout:" + barriersRide.id + "|heartRateBpm|phone|raw"
let barrierBytes = try db.read { sql in
  try sql.rows("SELECT value FROM derived_cache WHERE key=?", [.text(barrierKey)], limit: 1).first!.data("value")!
}
func word(_ bytes: Data, _ offset: Int) -> UInt64 {
  bytes.withUnsafeBytes { UInt64(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self)) }
}
check(word(barrierBytes, 0) == 0x504c4f474c4f4400, "Production pyramid persists numeric references")
check(
  Double(bitPattern: word(barrierBytes, 152)) == 0.0000002,
  "Persisted retained references preserve original submicrosecond times")
var leafOffset = 80
var barrierOffsets: [Int] = []
for index in 0..<4 {
  barrierOffsets.append(leafOffset)
  let pointCount = Int(word(barrierBytes, leafOffset))
  if [1, 3].contains(index) {
    check(word(barrierBytes, leafOffset + 24) & 3 == 3, "Persisted empty leaves retain both raw barriers")
    check(
      word(barrierBytes, leafOffset + 8) == UInt64(index) * 8_000_000
        && word(barrierBytes, leafOffset + 16) == UInt64(index + 1) * 8_000_000 - 125_000,
      "Persisted empty leaves retain their original raw extent")
  }
  leafOffset += 32 + pointCount * 48
}
PowerLogStore.evictSharedForTesting(databaseURL: db.databaseURL)
let restoredBarriers = MonitorDataStore(root: root)
barriersQuery.endSeconds = 40.01
try checkBarriers(restoredBarriers, "Reopened pyramid preserves missing and invalid barriers")
check(
  restoredBarriers.projectionDiagnostics()["pyramidDiskHits"] == 1,
  "Barrier evidence is consumed from persisted leaves")
let rawBarriers = MonitorDataStore(root: root, projectionCacheBytes: 0)
try checkBarriers(rawBarriers, "Raw compact reduction agrees with persisted barrier segmentation")
var invalidPayload = Data(barrierBytes.dropLast(32))
var badFlags = (UInt64(1) << 40).littleEndian
withUnsafeBytes(of: &badFlags) { invalidPayload.replaceSubrange(136..<144, with: $0) }
invalidPayload.append(contentsOf: SHA256.hash(data: invalidPayload))
_ = try db.transaction {
  try $0.execute("UPDATE derived_cache SET value=? WHERE key=?", [.blob(invalidPayload), .text(barrierKey)])
}
let rejectingShape = MonitorDataStore(root: root)
barriersQuery.endSeconds = 40.02
try checkBarriers(rejectingShape, "Invalid compact evidence rebuilds from original observations")
check(
  (rejectingShape.projectionDiagnostics()["pyramidDiskHits"] ?? 0) == 0,
  "A matching checksum cannot admit malformed retained-reference flags")
print(
  "Native level-of-detail: \(assertions) assertions passed; exact extrema, gaps, late arrivals, append-only updates and bounded wide-window work"
)
