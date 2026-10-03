import Foundation
import CryptoKit

// Optional local CPU/storage benchmark. The routine native suite does not run this file.
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-distance-benchmark-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("store.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"), store: store)
let service = WorkoutDistanceStore(store: store)
let start = Date(timeIntervalSince1970: 1_780_000_000)
let durations = CommandLine.arguments.dropFirst().compactMap(Double.init)
func assertNear(_ actual: Double?, _ expected: Double, _ message: String, tolerance: Double = 0.0001) {
  precondition(actual != nil && abs(actual! - expected) < tolerance, message)
}
func originals(_ id: String) throws -> (Int, String) {
  var revision: Int64 = -1
  var cursor: Int64 = 0
  var count = 0
  var sha = SHA256()
  while true {
    let rows = try store.read { db in
      try db.rows(
        "SELECT m.revision,m.id,o.content_hash FROM collection_memberships m INDEXED BY membership_snapshot JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND (m.revision,m.id)>(?,?) ORDER BY m.revision,m.id LIMIT 256",
        [.text(id), .integer(revision), .integer(cursor)], limit: 256)
    }
    for row in rows {
      sha.update(data: row.data("content_hash")!)
      count += 1
      revision = row.int("revision")!
      cursor = row.int("id")!
    }
    if rows.count < 256 { break }
  }
  return (count, sha.finalize().map { String(format: "%02x", $0) }.joined())
}
func derivedBytes() throws -> Int64 {
  try store.read { db in
    try db.scalarInt(
      "SELECT coalesce(sum(pgsize),0) FROM dbstat WHERE name LIKE 'distance_%' OR name LIKE 'sqlite_autoindex_distance_%'"
    ) ?? 0
  }
}
/// With `watch` the ride is Watch-owned, with 8 Hz telemetry, 1 Hz Watch GPS and heart rate, cumulative Health
/// totals every 5 s and final totals. Otherwise it carries controller telemetry only.
func measure(seconds: Double, watch: Bool) throws {
  precondition(seconds >= 2 && seconds <= 28800)
  let id = UUID().uuidString.lowercased()
  let count = Int(seconds * 8)
  if watch {
    _ = try archive.create(
      id: id, startedAt: start, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true)
  } else {
    try store.createCollection(id: id, kind: "workout", startedAt: WorkoutCoding.timestamp(start))
  }
  let session = UUID().uuidString.lowercased()
  var secondID: String?
  func event(_ kind: String, _ source: String, _ elapsed: Double, _ payload: [String: WorkoutJSON]) throws
    -> WorkoutEvent
  {
    try WorkoutEvent(
      workoutId: id, kind: kind, source: source, timestamp: start.addingTimeInterval(elapsed),
      elapsedSeconds: elapsed, payload: payload)
  }
  func sample(_ index: Int, speed: Double = 4, replaces: String? = nil) throws -> WorkoutEvent {
    let elapsed = Double(index) / 8
    var payload: [String: WorkoutJSON] = [
      "humanPowerW": .number(150), "cadenceRpm": .number(80), "controllerSpeedMps": .number(speed),
      "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(id),
      "connectionEpoch": .string("connection"), "clockEpoch": .string("epoch"),
      "acquisitionMonotonic": .number(elapsed),
    ]
    if watch {
      payload["captureSessionID"] = .string(session)
      payload["observationSequence"] = .string(String(index + 1))
      payload["firmwareLabel"] = .string("20240601")
      payload["sourceElapsedSeconds"] = .number(elapsed)
      payload["connectionEpoch"] = .string(session)
      payload["clockEpoch"] = .string(session)
    }
    if let replaces {
      payload["supersedesEventId"] = .string(replaces)
      payload.removeValue(forKey: "observationSequence")
    }
    return try event("telemetry", "cyc", elapsed, payload)
  }
  func second(_ index: Int) throws -> [WorkoutEvent] {
    let elapsed = Double(index)
    var events = [
      try event(
        "location", "watch", elapsed,
        [
          "latitude": .number(45 + elapsed * 4 / 111_195), "longitude": .number(7), "altitudeMeters": .number(420),
          "horizontalAccuracyM": .number(4), "verticalAccuracyM": .number(6), "speedMps": .number(4),
          "speedAccuracyMps": .number(0.4), "courseDegrees": .number(0), "courseAccuracyDegrees": .number(8),
          "distanceBarrier": .bool(false), "clockEpoch": .string(session), "acquisitionMonotonic": .number(elapsed),
        ]),
      try event(
        "health", "watch", elapsed,
        [
          "healthKitIdentifier": .string("HKQuantityTypeIdentifierHeartRate"), "value": .number(120),
          "unit": .string("bpm"), "representation": .string("rawQuantity"),
          "sampleUUID": .string(UUID().uuidString.lowercased()), "sampleCount": .number(1),
          "sampleStart": .string(WorkoutCoding.timestamp(start.addingTimeInterval(elapsed))),
          "sampleEnd": .string(WorkoutCoding.timestamp(start.addingTimeInterval(elapsed))),
          "heartRateBpm": .number(120),
        ]),
    ]
    if index % 5 == 0 {
      events += try totals(elapsed, representation: "cumulativeWorkoutTotal")
    }
    return events
  }
  func totals(_ elapsed: Double, representation: String) throws -> [WorkoutEvent] {
    try [
      ("activeEnergyKcal", "HKQuantityTypeIdentifierActiveEnergyBurned", "kcal", elapsed / 6),
      ("basalEnergyKcal", "HKQuantityTypeIdentifierBasalEnergyBurned", "kcal", elapsed / 50),
      ("distanceMeters", "HKQuantityTypeIdentifierDistanceCycling", "m", elapsed * 4),
    ].map { key, identifier, unit, value in
      try event(
        "health", "watch", elapsed,
        [
          "healthKitIdentifier": .string(identifier), "value": .number(value), "unit": .string(unit),
          "representation": .string(representation), key: .number(value),
        ])
    }
  }
  let generationStart = ProcessInfo.processInfo.systemUptime
  var batch: [WorkoutEvent] = []
  func flush(_ force: Bool = false) throws {
    while batch.count >= 512 || (force && !batch.isEmpty) {
      let size = min(512, batch.count)
      _ = try store.appendBatch(Array(batch.prefix(size)))
      batch.removeFirst(size)
    }
  }
  if watch { batch.append(try event("lifecycle", "watch", 0, ["action": .string("start")])) }
  for index in 0..<count {
    try autoreleasepool {
      let frame = try sample(index)
      if index == 1 { secondID = frame.eventId }
      batch.append(frame)
      if watch, index % 8 == 0 { batch += try second(index / 8) }
      try flush()
    }
  }
  if watch {
    batch.append(try event("lifecycle", "watch", seconds, ["action": .string("stop")]))
    batch += try totals(seconds, representation: "finalWorkoutTotal")
  }
  try flush(true)
  let generateSeconds = ProcessInfo.processInfo.systemUptime - generationStart
  let before = try originals(id)
  let bytesBefore = try derivedBytes()
  var inputRows = 0
  var maxPage = 0
  WorkoutDistanceStore.inputPageObserverForTesting = {
    inputRows += $0
    maxPage = max(maxPage, $0)
  }
  defer { WorkoutDistanceStore.inputPageObserverForTesting = nil }
  let coldStart = ProcessInfo.processInfo.systemUptime
  let snapshot = try service.snapshot(id: id)
  let cold = ProcessInfo.processInfo.systemUptime - coldStart
  let controller = { (snapshot: WorkoutDistanceSnapshot) in
    snapshot.sources.first { $0.source == "controller" }?.distanceMeters
  }
  precondition(snapshot.source == (watch ? "gps:watch" : "controller"))
  assertNear(controller(snapshot), Double(count - 1) / 2, "full controller profile must retain every accepted interval")
  if watch {
    let gps = snapshot.sources.first { $0.source == "gps:watch" }?.distanceMeters
    assertNear(gps, (seconds - 1) * 4, "Watch GPS covers the ride", tolerance: seconds * 0.01)
    assertNear(snapshot.healthReportedMeters, seconds * 4, "the final Health total is reported")
  }
  let coldInputs = inputRows
  let bytes = try derivedBytes() - bytesBefore
  let warmStart = ProcessInfo.processInfo.systemUptime
  for i in 0..<1000 {
    let time = Double(i) * seconds / 1000
    _ = try service.neighbor(snapshot: snapshot, seconds: time, before: true)
    _ = try service.range(snapshot: snapshot, start: time, end: min(seconds, time + 0.4))
  }
  let inspect = ProcessInfo.processInfo.systemUptime - warmStart
  precondition(inputRows == coldInputs, "cursor reads cannot replay original inputs")
  let after = try originals(id)
  precondition(after == before, "projection must leave every original content hash untouched")
  _ = try store.appendBatch([sample(count)])
  let appendStart = ProcessInfo.processInfo.systemUptime
  let appended = try service.snapshot(id: id)
  let append = ProcessInfo.processInfo.systemUptime - appendStart
  let appendInputs = inputRows - coldInputs
  precondition(
    appended.generation == snapshot.generation && appendInputs == 1, "ordinary append must advance one input")
  assertNear(controller(appended), Double(count) / 2, "append total")
  _ = try store.appendBatch([sample(1, speed: 2, replaces: secondID)])
  let correctionStart = ProcessInfo.processInfo.systemUptime
  let corrected = try service.snapshot(id: id)
  let correction = ProcessInfo.processInfo.systemUptime - correctionStart
  precondition(
    corrected.generation != appended.generation, "early correction must publish separate coherent generation")
  assertNear(controller(corrected), Double(count) / 2 - 0.25, "early correction adjusts only its adjoining intervals")
  assertNear(
    try service.page(snapshot: snapshot, start: seconds - 1).last?.distanceMeters, snapshot.totalMeters!,
    "old readers retain their exact prefix")
  precondition(maxPage <= 128)
  let output: [String: Any] = [
    "ride": watch ? "watch-gps-health" : "controller", "durationSeconds": seconds, "rateHz": 8,
    "originalCount": after.0, "generateSeconds": generateSeconds, "coldSeconds": cold,
    "warm1000NeighborAndRangeSeconds": inspect, "appendSeconds": append, "appendInputs": appendInputs,
    "correctionSeconds": correction, "profilePointCount": snapshot.maximumPointID, "derivedBytes": bytes,
    "bytesPerPoint": Double(bytes) / Double(snapshot.maximumPointID), "maximumInputPage": maxPage,
    "originalHashesUnchanged": true,
  ]
  print(String(data: try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]), encoding: .utf8)!)
  fflush(stdout)
}
for seconds in durations.isEmpty ? [3420, 28800] : durations {
  try measure(seconds: seconds, watch: false)
  try measure(seconds: seconds, watch: true)
}
