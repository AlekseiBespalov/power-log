import Foundation
import PowerLogCore

// A 20-minute full-rate application workload: raw 8 Hz capture, 4 Hz graph reads, 1 Hz companion publication,
// raw Watch observations, a 150-second disconnection, and local Finish/export with unacknowledged Watch data.
setbuf(stdout, nil)
let duration = 1_200
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-companion-soak-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let start = Date(timeIntervalSince1970: floor(Date().timeIntervalSince1970) - Double(duration + 60))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
let ride = try archive.create(
  startedAt: start, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true, sampleHz: 8)
try archive.append(
  WorkoutEvent(
    workoutId: ride.id, kind: "lifecycle", source: "phone", timestamp: start, elapsedSeconds: 0,
    payload: ["action": .string("start")]))
try archive.update(id: ride.id, phase: "running")
let monitor = MonitorDataStore(root: root)
var buffer = WorkoutCompanionBuffer()
let seconds = WorkoutHealthSeconds()
let epoch = UUID().uuidString.lowercased()
let connection = UUID().uuidString.lowercased()
var messages = 0
var samples = 0
var plots = 0
var publicationSeconds = Set<Int64>()
var maxWatchBuffer = 0
var queryDurations: [Double] = []
var pending: [WorkoutEvent] = []
func append(_ kind: String, _ source: String, _ t: Double, _ payload: [String: WorkoutJSON]) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: ride.id, kind: kind, source: source, timestamp: start.addingTimeInterval(t), elapsedSeconds: t,
    payload: payload)
}
let began = ProcessInfo.processInfo.systemUptime
for frame in 0..<(duration * 8) {
  try autoreleasepool {
    let time = Double(frame) / 8
    let event = try append(
      "telemetry", "controller", time,
      [
        "humanPowerW": .number(Double(frame % 300) + 0.125), "cadenceRpm": .number(80 + Double(frame % 4)),
        "batteryCurrentA": .number(Double(frame % 64) / 8), "batteryVoltageV": .number(52.8),
        "motorInputPowerW": .number(211.2), "controllerSpeedMps": .number(5),
        "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(connection),
        "connectionEpoch": .string(connection), "clockEpoch": .string(epoch),
        "observationSequence": .string(String(frame + 1)),
        "acquisitionMonotonic": .number(100 + time),
      ])
    pending.append(event)
    try seconds.append(event)
    samples += 1
    if frame % 4 == 0 {
      try buffer.append(
        WorkoutCompanionObservation(
          id: UUID().uuidString.lowercased(), timestamp: start.addingTimeInterval(time),
          start: start.addingTimeInterval(time),
          metric: "heartRateBpm", identifier: "HKQuantityTypeIdentifierHeartRate", unit: "bpm",
          value: 110 + Double(frame % 30), cumulative: false), now: start.addingTimeInterval(time))
    }
    if frame % 8 == 7 {
      try archive.appendBatch(pending)
      pending.removeAll(keepingCapacity: true)
      try archive.append(
        append(
          "location", "phone", time,
          [
            "latitude": .number(0), "longitude": .number(time * 0.000045), "horizontalAccuracyM": .number(3),
            "speedMps": .number(5),
          ]))
      if let value = try seconds.drain(now: start.addingTimeInterval(time)) {
        precondition(publicationSeconds.insert(value.second).inserted, "A closed Health second was revised")
        messages += 1
      }
      if !(780..<930).contains(Int(time)) {
        let page = buffer.page(now: start.addingTimeInterval(time))
        let message = WorkoutCompanionMessage(
          kind: .measurements, rideID: ride.id, observations: page.map(\.observation), sequence: page.last?.sequence)
        let received = try WorkoutCompanionMessage.decode(message.encoded())
        for observation in received.observations ?? [] {
          try archive.append(
            WorkoutEvent(
              workoutId: ride.id, kind: "health", source: "watch", timestamp: observation.timestamp,
              payload: ["heartRateBpm": .number(observation.value), "representation": .string("rawQuantity")],
              eventId: observation.id))
        }
        if let sequence = received.sequence { buffer.acknowledge(sequence) }
      } else {
        buffer.trim(now: start.addingTimeInterval(time))
      }
      maxWatchBuffer = max(maxWatchBuffer, buffer.entries.count)
    }
    if frame % 2 == 1 {
      let began = ProcessInfo.processInfo.systemUptime
      let plot = try monitor.readPlot(
        MonitorRequest(
          source: "workout", id: ride.id, startSeconds: max(0, time - 30), endSeconds: max(1, time),
          metrics: ["humanPowerW", "batteryCurrentA", "heartRateBpm"], buckets: 256))
      precondition(plot["status"] as? String == "ok", "Full-detail live graph became unavailable")
      queryDurations.append(ProcessInfo.processInfo.systemUptime - began)
      plots += 1
    }
  }
}
try archive.appendBatch(pending)
try buffer.append(
  WorkoutCompanionObservation(
    id: UUID().uuidString.lowercased(), timestamp: start.addingTimeInterval(Double(duration)),
    start: start.addingTimeInterval(Double(duration)),
    metric: "heartRateBpm", identifier: "HKQuantityTypeIdentifierHeartRate", unit: "bpm", value: 150, cumulative: false),
  now: start.addingTimeInterval(Double(duration)))
try archive.append(append("lifecycle", "phone", Double(duration), ["action": .string("stop")]))
try archive.update(id: ride.id, healthKitState: "unknown", stopElapsedSeconds: Double(duration))
try archive.finish(id: ride.id, endedAt: start.addingTimeInterval(Double(duration)))
let exporter = ExportSource { archive }
let session = try exporter.open(["rideId": ride.id, "kind": "zip"])
var after: Any = NSNull()
var exported = 0
repeat {
  let page = try exporter.page(["session": session["session"]!, "projection": "telemetry", "after": after])
  exported += page["rows"] as! Int
  after = page["last"]!
  if page["done"] as! Bool { break }
} while true
exporter.close(session["session"] as! String)
precondition(samples == duration * 8 && exported == samples, "Raw export lost full-rate originals")
precondition(messages <= duration && messages >= duration - 5, "Routine companion publication exceeded 1 Hz or stalled")
precondition(
  buffer.dropped > 0 && maxWatchBuffer <= 122, "Disconnection did not stay within its 60-second observation window")
precondition(!buffer.entries.isEmpty, "The Finish fixture must have unsettled Watch observations")
let previews = try archive.store.read { try $0.scalarInt("SELECT count(*) FROM collections WHERE kind='live'") }
precondition(previews == 0, "Ride created a persistent preview mirror")
let ordered = queryDurations.sorted()
print(
  "20-minute companion soak: \(samples) raw frames, \(plots) full-detail plots, \(messages) routine messages, \(buffer.dropped) intentionally expired Watch samples"
)
print(
  String(
    format: "Graph p95 %.1f ms; host run %.1f s; local export succeeds while Watch/Health remain unsettled",
    ordered[Int(Double(ordered.count - 1) * 0.95)] * 1000, ProcessInfo.processInfo.systemUptime - began))
