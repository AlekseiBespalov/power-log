import Foundation
import CryptoKit

// Optional local CPU/storage benchmark. The routine native suite does not run this file.
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-distance-benchmark-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("store.sqlite3"))
let service = WorkoutDistanceStore(store: store)
let start = Date(timeIntervalSince1970: 1_780_000_000)
let durations = CommandLine.arguments.dropFirst().compactMap(Double.init)
func assertNear(_ actual: Double?, _ expected: Double, _ message: String) {
  precondition(actual != nil && abs(actual! - expected) < 0.0001, message)
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
for seconds in durations.isEmpty ? [3420, 28800] : durations {
  precondition(seconds >= 2 && seconds <= 28800)
  let id = UUID().uuidString.lowercased()
  let count = Int(seconds * 8)
  try store.createCollection(id: id, kind: "workout", startedAt: WorkoutCoding.timestamp(start))
  var secondID: String?
  func sample(_ index: Int, speed: Double = 4, replaces: String? = nil) throws -> WorkoutEvent {
    let elapsed = Double(index) / 8
    var payload: [String: WorkoutJSON] = [
      "humanPowerW": .number(150), "cadenceRpm": .number(80), "controllerSpeedMps": .number(speed),
      "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(id),
      "connectionEpoch": .string("connection"), "clockEpoch": .string("epoch"),
      "acquisitionMonotonic": .number(elapsed),
    ]
    if let replaces { payload["supersedesEventId"] = .string(replaces) }
    return try WorkoutEvent(
      workoutId: id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(elapsed),
      elapsedSeconds: elapsed, payload: payload)
  }
  let generationStart = ProcessInfo.processInfo.systemUptime
  for first in stride(from: 0, to: count, by: 512) {
    try autoreleasepool {
      let events = try (first..<min(first + 512, count)).map { try sample($0) }
      if first == 0 { secondID = events[1].eventId }
      _ = try store.appendBatch(events)
    }
  }
  let generateSeconds = ProcessInfo.processInfo.systemUptime - generationStart
  let before = try originals(id)
  let bytesBefore = try derivedBytes()
  var inputRows = 0
  var maxPage = 0
  WorkoutDistanceStore.inputPageObserverForTesting = {
    inputRows += $0
    maxPage = max(maxPage, $0)
  }
  let coldStart = ProcessInfo.processInfo.systemUptime
  let snapshot = try service.snapshot(id: id)
  let cold = ProcessInfo.processInfo.systemUptime - coldStart
  precondition(snapshot.source == "controller")
  assertNear(snapshot.totalMeters, Double(count - 1) / 2, "full controller profile must retain every accepted interval")
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
  assertNear(appended.totalMeters, Double(count) / 2, "append total")
  _ = try store.appendBatch([sample(1, speed: 2, replaces: secondID)])
  let correctionStart = ProcessInfo.processInfo.systemUptime
  let corrected = try service.snapshot(id: id)
  let correction = ProcessInfo.processInfo.systemUptime - correctionStart
  precondition(
    corrected.generation != appended.generation, "early correction must publish separate coherent generation")
  assertNear(corrected.totalMeters, Double(count) / 2 - 0.25, "early correction adjusts only its adjoining intervals")
  assertNear(
    try service.page(snapshot: snapshot, start: seconds - 1).last?.distanceMeters, Double(count - 1) / 2,
    "old readers retain their exact prefix")
  precondition(maxPage <= 128)
  let output: [String: Any] = [
    "durationSeconds": seconds, "rateHz": 8, "originalCount": count, "generateSeconds": generateSeconds,
    "coldSeconds": cold, "warm1000NeighborAndRangeSeconds": inspect, "appendSeconds": append,
    "appendInputs": appendInputs, "correctionSeconds": correction, "profilePointCount": snapshot.maximumPointID,
    "derivedBytes": bytes, "bytesPerPoint": Double(bytes) / Double(snapshot.maximumPointID),
    "maximumInputPage": maxPage, "originalHashesUnchanged": true,
  ]
  print(String(data: try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]), encoding: .utf8)!)
  fflush(stdout)
  WorkoutDistanceStore.inputPageObserverForTesting = nil
}
