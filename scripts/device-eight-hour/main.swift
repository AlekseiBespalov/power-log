import Foundation

let arguments = CommandLine.arguments.dropFirst()
guard let path = arguments.first(where: { !$0.hasPrefix("--") }), arguments.count <= 2 else {
  fatalError("Usage: sh scripts/device-eight-hour/generate.sh [--full] <new PowerLog directory>")
}
let full = arguments.contains("--full")
let root = URL(fileURLWithPath: path)
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: db)
let start = Date(timeIntervalSince1970: 1_780_000_000)
let seconds = full ? 28800 : 300
let hz = 8
let pauseAt = full ? 7200.0 : Double(seconds) / 4
let gapAt = full ? 10800.0 : Double(seconds) / 2
for i in 0..<100 {
  _ = try archive.create(
    startedAt: start.addingTimeInterval(-Double(i + 1) * 3600), indoor: true, watchEnabled: false, saveToHealth: true,
    recordGPS: false, sampleHz: 4)
}
let ride = try archive.create(
  startedAt: start, indoor: false, watchEnabled: false, saveToHealth: true, recordGPS: true, sampleHz: hz, example: true
)
var batch: [WorkoutEvent] = []
func flush() throws {
  if !batch.isEmpty {
    _ = try db.appendBatch(batch)
    batch.removeAll(keepingCapacity: true)
  }
}
func event(_ kind: String, _ source: String, _ time: Double, _ payload: [String: WorkoutJSON]) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: ride.id, kind: kind, source: source, timestamp: start.addingTimeInterval(time), elapsedSeconds: time,
    payload: payload)
}
for i in 0..<seconds * hz {
  try autoreleasepool {
    let time = Double(i) / Double(hz)
    let unavailable = (gapAt..<(gapAt + 120)).contains(time) || i % (full ? 8192 : 512) == (full ? 4095 : 255)
    batch.append(
      try event(
        "telemetry", "controller", time,
        [
          "humanPowerW": .number(unavailable ? -1 : Double(i % 600)), "cadenceRpm": .number(unavailable ? -1 : 80),
          "clockEpoch": .string("summary-clock"),
          "connectionEpoch": .string(time < gapAt ? "summary-first" : "summary-second"),
        ]))
    if i % hz == 0 {
      batch.append(
        try event(
          "health", "phone", time,
          ["heartRateBpm": .number(Double(90 + i % 70)), "representation": .string("rawQuantity")]))
      var location: [String: WorkoutJSON] = [
        "latitude": .number(0), "longitude": .number(0),
        "horizontalAccuracyM": .number((gapAt..<(gapAt + 120)).contains(time) ? 1000 : 2),
      ]
      if time < (full ? 3600 : Double(seconds) / 4) || time >= (full ? 14400 : Double(seconds) * 0.75) {
        location["speedMps"] = .number(4)
      }
      batch.append(try event("location", "phone", time, location))
    }
    if batch.count >= 250 { try flush() }
  }
}
for (time, action) in [(pauseAt, "pause"), (pauseAt + 60, "resume"), (full ? 14400.0 : Double(seconds) / 2, "lap")] {
  batch.append(try event("lifecycle", "phone", time, ["action": .string(action)]))
}
batch.append(
  try event(
    "health", "phone", Double(full ? 28802 : seconds + 2),
    [
      "activeEnergyKcal": .number(1200), "basalEnergyKcal": .number(100),
      "representation": .string("finalWorkoutTotal"),
    ]))
try flush()
_ = try archive.update(id: ride.id, stopElapsedSeconds: Double(seconds))
_ = try archive.finish(id: ride.id, endedAt: start.addingTimeInterval(Double(seconds)))
print("Generated ride \(ride.id) with \(seconds * hz) controller originals in \(root.path)")
