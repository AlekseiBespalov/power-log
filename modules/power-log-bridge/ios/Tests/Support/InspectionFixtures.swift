import Foundation
import PowerLogCore

private func inspectionFixture(_ name: String) throws -> [String: Any] {
  let root = ProcessInfo.processInfo.environment["POWER_LOG_CONTRACT_FIXTURES"] ?? "tests/fixtures/contract"
  return try JSONSerialization.jsonObject(
    with: Data(
      contentsOf: URL(fileURLWithPath: root)
        .appendingPathComponent("inspection/\(name).json"))) as! [String: Any]
}

private func fixtureLong(_ value: Any?) -> Int64 {
  if let text = value as? String { return Int64(text)! }
  return (value as! NSNumber).int64Value
}

private func fixtureScalar(_ value: Any?) -> Scalar? {
  guard let value, !(value is NSNull) else { return nil }
  if let text = value as? String {
    return text == "NaN" ? Scalar.Real64(value: .nan) : Scalar.Integer64(value: Int64(text)!)
  }
  return Scalar.Real64(value: (value as! NSNumber).doubleValue)
}

func runStoredInspectionRegressionFixtures() throws {
  let fm = FileManager.default
  let root = fm.temporaryDirectory.appendingPathComponent("monitor-barriers-" + UUID().uuidString)
  defer { try? fm.removeItem(at: root) }
  let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
  let start = Date(timeIntervalSince1970: 1_780_000_000)
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false,
    recordGPS: false, sampleHz: 8)
  let row = (try inspectionFixture("statistics")["cases"] as! [[String: Any]]).first {
    $0["id"] as? String == "missing-heart-barrier"
  }!
  for point in row["points"] as! [[String: Any]] {
    let t = WorkoutDistancePolicy.seconds(fixtureLong(point["timeUs"]))
    var payload: [String: WorkoutJSON] = ["representation": .string("rawSeries"), "activeEnergyKcal": .number(0)]
    if let value = point["value"] as? Double { payload["heartRateBpm"] = .number(value) }
    try archive.append(
      WorkoutEvent(
        workoutId: ride.id, kind: "health", source: "phone",
        timestamp: start.addingTimeInterval(t), elapsedSeconds: t, payload: payload))
  }
  let reader = MonitorDataStore(root: root)
  var query = MonitorRequest(source: "workout", id: ride.id, startSeconds: 0, endSeconds: 1, metrics: ["heartRateBpm"])
  let statistics = (try reader.rangeStats(query)["statistics"] as! [String: [String: Any]])["heartRateBpm"]!
  precondition(statistics["integral"] as? Double == 0, "Q09 missing-heart-barrier: expected integral 0")
  query.seconds = 1
  query.anchor = MonitorObservationAnchor(metric: "heartRateBpm", observationId: "unmatched")
  let result = try reader.inspectAt(query)["points"] as! [String: Any]
  precondition(result["heartRateBpm"] is [String: Any], "C36 unmatched anchor: expected nearest valid original")
  print("Stored inspection regressions: 2 assertions passed")
}

func runInspectionPresentationFixtures() throws {
  var assertions = 0
  let gate = MonitorInspectionGate()
  let first = try gate.begin(source: "a", revision: "1")
  let second = try gate.begin(source: "b", revision: "1")
  precondition(!gate.accepts(first) && gate.accepts(second), "a newer request supersedes the older ticket")
  let cancelled = try gate.cancel()
  precondition(cancelled, "cancel reports the pending request")
  let repeated = try gate.cancel()
  precondition(!gate.accepts(second) && !repeated, "cancel rejects the ticket and clears the pending flag")
  assertions += 3
  for row in try inspectionFixture("tail")["cases"] as! [[String: Any]] {
    let extent = try MonitorInspection.heldTail(
      metric: "humanPowerW", identity: "a", time: 10, value: 200,
      evidence: row["evidence"] as! String, acquired: row["acquired"] as? Double, now: row["now"] as! Double,
      elapsed: row["elapsed"] as! Double)
    precondition(extent?.endSeconds == row["expected"] as? Double, row["id"] as! String)
    assertions += 1
  }
  print("Swift inspection gate and held-tail adapters: \(assertions) assertions passed")
}

func runStoredPyramidPersistenceFixture() throws {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent("inspection-pyramid-\(UUID().uuidString)")
  defer { try? FileManager.default.removeItem(at: root) }
  let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
  let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: db)
  let start = Date(timeIntervalSince1970: 1780000000)
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
  let full = ProcessInfo.processInfo.environment["POWER_LOG_STRESS_ARGUMENT"] == "--full"
  let count = full ? 230400 : 16384
  for offset in stride(from: 0, to: count, by: 256) {
    let events = try (offset..<min(offset + 256, count)).map { index in
      try WorkoutEvent(
        workoutId: ride.id, kind: "telemetry", source: "controller",
        timestamp: start.addingTimeInterval(Double(index) / 8), elapsedSeconds: Double(index) / 8,
        payload: [
          "humanPowerW": .number(100), "cadenceRpm": .number(Double(75 + index * 13 % 19)),
          "clockEpoch": .string("00000000-0000-4000-8000-000000000001"),
          "connectionEpoch": .string("00000000-0000-4000-8000-000000000002"),
        ])
    }
    _ = try archive.appendBatch(events)
  }
  _ = try archive.finish(id: ride.id, endedAt: start.addingTimeInterval(Double(count) / 8))
  let reader = MonitorDataStore(root: root)
  let request = MonitorRequest(source: "workout", id: ride.id, metrics: ["cadenceRpm"], buckets: full ? 360 : 32)
  _ = try reader.readPlot(request)
  let stored = try db.read {
    try $0.rows("SELECT value FROM derived_cache WHERE key LIKE 'lod|%'", limit: 1).first?.data("value")
  }
  precondition(
    stored != nil && stored!.count <= 4194304, "8 Hz cadence with UUID epochs persists inside the byte limit"
  )
  PowerLogStore.evictSharedForTesting(databaseURL: db.databaseURL)
  let reopened = MonitorDataStore(root: root)
  var different = request
  different.buckets = full ? 257 : 17
  _ = try reopened.readPlot(different)
  precondition(
    reopened.projectionDiagnostics()["pyramidDiskHits"] == 1
      && (reopened.projectionDiagnostics()["pyramidOriginalPoints"] ?? 0) == 0,
    "Reopened cadence pyramid serves a different bucket count without rebuilding originals")
  let failing = MonitorDataStore(root: root)
  _ = try db.transaction { try $0.execute("DELETE FROM derived_cache") }
  let failingStore = try PowerLogStore.shared(databaseURL: db.databaseURL)
  try failingStore.read { sql in
    failingStore.beforeCommitForTesting = {
      if try sql.scalarInt("SELECT count(*) FROM derived_cache WHERE key LIKE 'lod|%'") == 1 {
        throw PowerLogStorageError.sqlite(13, "Injected pyramid save failure")
      }
    }
  }
  defer { failingStore.beforeCommitForTesting = nil }
  _ = try failing.readPlot(request)
  precondition(
    failing.projectionDiagnostics()["pyramidSaveFailures"] == 1, "Pyramid save failure is visible in diagnostics")
  print("Stored pyramid persistence: 3 assertions passed; \(stored!.count) persisted bytes")
}

func runStoredNumericEvidenceFixture() throws {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent("inspection-evidence-\(UUID().uuidString)")
  defer { try? FileManager.default.removeItem(at: root) }
  let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
  let start = Date(timeIntervalSince1970: 1780000000)
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: true, sampleHz: 8)
  let events = try (0..<128).map { index in
    try WorkoutEvent(
      workoutId: ride.id, kind: "telemetry", source: "controller",
      timestamp: start.addingTimeInterval(Double(index) / 8), elapsedSeconds: Double(index) / 8,
      payload: [
        "humanPowerW": .number(100), "cadenceRpm": .number(80),
        "clockEpoch": .string(String(format: "00000000-0000-4000-8000-%012d", index < 64 ? 1 : 2)),
        "connectionEpoch": .string(String(format: "00000000-0000-4000-9000-%012d", index + 1)),
      ])
  }
  var locations: [WorkoutEvent] = []
  for index in 0..<128 {
    let time = Double(index) / 8
    let accuracy = Double(index % 3) + 0.5
    let clock = String(format: "00000000-0000-4000-8000-%012d", index < 64 ? 1 : 2)
    let connection = String(format: "00000000-0000-4000-9000-%012d", index + 1)
    let payload: [String: WorkoutJSON] = [
      "latitude": .number(0), "longitude": .number(0), "speedMps": .number(5),
      "speedAccuracyMps": .number(accuracy), "horizontalAccuracyM": .number(2),
      "clockEpoch": .string(clock), "connectionEpoch": .string(connection),
    ]
    locations.append(
      try WorkoutEvent(
        workoutId: ride.id, kind: "location", source: "phone",
        timestamp: start.addingTimeInterval(time), elapsedSeconds: time, payload: payload))
  }
  _ = try archive.appendBatch(events + locations)
  let reader = MonitorDataStore(root: root, projectionCacheBytes: 0)
  let query = MonitorRequest(
    source: "workout", id: ride.id, startSeconds: 0, endSeconds: 16,
    metrics: ["humanPowerW"], buckets: 256)
  let rows = (try reader.readPlot(query)["series"] as! [String: [[String: Any]]])["humanPowerW"]!
  precondition(rows.count == 128, "Streaming numeric evidence retains every original")
  precondition(
    rows.filter { $0["startsSegment"] as? Bool == true }.map { $0["elapsedSeconds"] as! Double } == [0, 8],
    "Numeric page retains each original clock epoch across changing connection evidence")
  let stats = (try reader.rangeStats(query)["statistics"] as! [String: [String: Any]])["humanPowerW"]!
  precondition(
    stats["count"] as? Int == 128 && stats["coveredSeconds"] as? Double == 0,
    "Numeric page retains every connection barrier through flush")
  var locationQuery = query
  locationQuery.metrics = ["speedMps"]
  let speed = (try reader.readPlot(locationQuery)["series"] as! [String: [[String: Any]]])["speedMps"]!
  precondition(
    speed.count == 128
      && speed.filter { $0["startsSegment"] as? Bool == true }
        .map { $0["elapsedSeconds"] as! Double } == [0, 8],
    "Numeric page retains original clock evidence across changing accuracy and connections")
  print("Swift stored numeric evidence: 4 assertions passed")
}

func runStoredN1SelectionFixtures() throws {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent("inspection-n1-\(UUID().uuidString)")
  defer { try? FileManager.default.removeItem(at: root) }
  let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
  let start = Date(timeIntervalSince1970: 1780000000)
  let reader = MonitorDataStore(root: root)
  var assertions = 0
  var caseIndex = 0
  for row in try inspectionFixture("selection")["cases"] as! [[String: Any]]
  where (row["id"] as! String).hasPrefix("n1-") {
    caseIndex += 1
    let ride = try archive.create(
      startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false,
      recordGPS: false, sampleHz: 8)
    let points = row["points"] as! [[String: Any]]
    let names = points.map { $0["id"] as! String }.sorted()
    var identities: [String: String] = [:]
    for point in points {
      let name = point["id"] as! String
      let time = point["elapsedSeconds"] as? Double ?? WorkoutDistancePolicy.seconds(fixtureLong(point["timeUs"]))
      let event = try WorkoutEvent(
        workoutId: ride.id, kind: "telemetry", source: "controller",
        timestamp: start.addingTimeInterval(time), elapsedSeconds: time,
        payload: ["humanPowerW": .number((point["value"] as! NSNumber).doubleValue), "cadenceRpm": .number(80)],
        eventId: String(format: "00000000-0000-4000-8000-%012d", caseIndex * 100 + names.firstIndex(of: name)! + 1))
      identities[name] = try PowerLogStore.physicalIdentity(event)
      try archive.append(event)
    }
    let time = WorkoutDistancePolicy.seconds(fixtureLong(row["timeUs"]))
    var request = MonitorRequest(source: "workout", id: ride.id, seconds: time, metrics: ["humanPowerW"])
    if let anchor = row["anchor"] as? String {
      request.anchor = MonitorObservationAnchor(metric: "humanPowerW", observationId: identities[anchor]!)
    }
    let selected = (try reader.inspectAt(request)["points"] as! [String: Any])["humanPowerW"] as? [String: Any]
    precondition(
      selected?["observationId"] as? String == (row["expected"] as? String).flatMap { identities[$0] },
      row["id"] as! String)
    assertions += 1
    let plotted =
      (try reader.readPlot(
        MonitorRequest(
          source: "workout", id: ride.id,
          metrics: ["humanPowerW"], buckets: 256))["series"] as! [String: [[String: Any]]])["humanPowerW"]!
    precondition(
      Set(plotted.map { $0["observationId"] as! String }) == Set(identities.values),
      "Stored N1 plots retain every admitted original in its integer bins")
    assertions += 1
    if let segments = [
      "n1-gap-5999999": [true, false],
      "n1-gap-6000000": [true, true],
      "n1-gap-6000001": [true, true],
    ][row["id"] as! String] {
      precondition(
        plotted.map { $0["startsSegment"] as! Bool } == segments,
        "Stored N1 plot gaps preserve the exclusive boundary")
      assertions += 1
    }
    if row["id"] as! String == "n1-collapsed-originals" {
      precondition(
        plotted.map { $0["elapsedSeconds"] as! Double } == [1.0000008, 1.0000002],
        "N1 plot order retains unchanged original times")
      assertions += 1
    }
  }
  for row in try inspectionFixture("statistics")["cases"] as! [[String: Any]]
  where (row["id"] as! String).hasPrefix("n1-") {
    let ride = try archive.create(
      startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false,
      recordGPS: false, sampleHz: 8)
    for point in row["points"] as! [[String: Any]] {
      let time = point["elapsedSeconds"] as! Double
      try archive.append(
        WorkoutEvent(
          workoutId: ride.id, kind: "telemetry", source: "controller",
          timestamp: start.addingTimeInterval(time), elapsedSeconds: time,
          payload: ["humanPowerW": .number((point["value"] as! NSNumber).doubleValue), "cadenceRpm": .number(80)]))
    }
    if row["id"] as! String == "n1-metadata-stop" { try archive.update(id: ride.id, stopElapsedSeconds: 1.0000005) }
    for event in row["lifecycle"] as! [[String: Any]] {
      try archive.append(
        WorkoutEvent(
          workoutId: ride.id, kind: "lifecycle", source: "phone",
          timestamp: start.addingTimeInterval(1.0000005), elapsedSeconds: 1.0000005,
          payload: ["action": .string(event["action"] as! String)]))
    }
    let query = MonitorRequest(
      source: "workout", id: ride.id,
      startSeconds: row["id"] as! String == "n1-collapsed-range" ? 1.0000005 : 0,
      endSeconds: row["id"] as! String == "n1-collapsed-range" ? 1.0000009 : 2, metrics: ["humanPowerW"])
    let result = (try reader.rangeStats(query)["statistics"] as! [String: [String: Any]])["humanPowerW"]!
    precondition(result["count"] as? Int == 2, row["id"] as! String)
    assertions += 1
  }
  print("Swift stored N1 selection fixtures: \(assertions) assertions passed")
}
