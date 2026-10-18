import Foundation

func check(_ value: Bool, _ message: String) { precondition(value, message) }
let root = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-interrupted-\(UUID())")
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
let start = Date(timeIntervalSince1970: 1_780_000_000)
func ride(_ actions: [(String, Double)], sample: Double? = nil) throws -> String {
  let record = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
  if let sample {
    try archive.append(
      WorkoutEvent(
        workoutId: record.id, kind: "telemetry", source: "controller", timestamp: start.addingTimeInterval(sample),
        elapsedSeconds: sample, payload: ["humanPowerW": .number(120), "cadenceRpm": .number(80)]))
  }
  for (action, elapsed) in actions {
    try archive.append(
      WorkoutEvent(
        workoutId: record.id, kind: "lifecycle", source: "phone", timestamp: start.addingTimeInterval(elapsed),
        elapsedSeconds: elapsed, payload: ["action": .string(action)]))
  }
  try archive.update(id: record.id, phase: "interrupted")
  return record.id
}
for (actions, sample, expectedEnd, expectedTimer) in [
  ([("start", 0.0), ("lap", 50.0), ("pause", 60.0)], 10.0 as Double?, 60.0, 60.0),
  ([("start", 0.0), ("pause", 10.0), ("resume", 30.0), ("lap", 50.0), ("pause", 60.0)], nil, 60.0, 40.0),
  ([("start", 0.0), ("pause", 10.0), ("lap", 60.0)], nil, 60.0, 10.0),
  ([("start", 0.0)], nil, 0.0, 0.0),
  ([], nil, 0.0, 0.0),
] {
  let id = try ride(actions, sample: sample)
  let timing = try WorkoutOwnerTiming.lastOriginal(id: id, startedAt: WorkoutCoding.timestamp(start), archive: archive)
  check(
    timing.elapsedSeconds == expectedEnd && timing.timerSeconds == expectedTimer, "cutoff includes events and pauses")
  check(
    try WorkoutCoding.date(timing.timestamp) == start.addingTimeInterval(expectedEnd), "end timestamp matches cutoff")
  try phoneFixtureInterruption(
    archive: archive, id: id, epoch: "test-restart",
    timing: WorkoutOwnerTiming(
      timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(3_600)), elapsedSeconds: 3_600,
      timerSeconds: expectedTimer))
  check(
    try WorkoutOwnerTiming.lastOriginal(id: id, startedAt: WorkoutCoding.timestamp(start), archive: archive) == timing,
    "synthetic restart bookkeeping cannot extend the ride")
}
print("Interrupted Finish retains laps/pauses, pause-aware active duration and excludes restart downtime")

let id = try ride([("start", 0), ("pause", 20), ("resume", 40), ("lap", 50), ("pause", 60)], sample: 10)
let cutoff = try WorkoutOwnerTiming.lastOriginal(id: id, startedAt: WorkoutCoding.timestamp(start), archive: archive)
try archive.append(
  WorkoutEvent(
    workoutId: id, kind: "lifecycle", source: "phone", timestamp: WorkoutCoding.date(cutoff.timestamp),
    elapsedSeconds: cutoff.elapsedSeconds, payload: ["action": .string("stop")]))
try archive.update(id: id, phase: "completed", stopElapsedSeconds: cutoff.elapsedSeconds, ownerTiming: cutoff)
try archive.finish(id: id, endedAt: cutoff.timestamp)
let source = ExportSource { archive }
let session = try source.open(["rideId": id, "kind": "fit"])
defer { source.close(session["session"] as! String) }
let metadata = session["metadata"] as! [String: Any]
check(session["elapsedEnd"] as? Double == 60, "FIT uses the same terminal cutoff")
check(
  (metadata["ownerTiming"] as? [String: Any])?["timerSeconds"] as? Double == 40, "FIT retains pause-aware active time")
let page = try source.page(["session": session["session"]!, "projection": "lifecycle", "after": NSNull()])
let columns = page["columns"] as! [String: Any]
check((columns["action"] as! [String]).contains("lap"), "the retained late lap is exported")
let elapsed = (columns["elapsedSeconds"] as! Data).withUnsafeBytes { raw in
  (0..<(raw.count / 8)).map {
    Double(bitPattern: UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: $0 * 8, as: UInt64.self)))
  }
}
check(
  elapsed.allSatisfy { (0...60).contains($0) } && elapsed.contains(50),
  "all exported events are within the finished ride")
print("Interrupted Finish FIT metadata and lap bounds passed")
