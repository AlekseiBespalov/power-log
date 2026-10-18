import Foundation

func check(_ condition: @autoclosure () -> Bool, _ message: String) { precondition(condition(), message) }
let now = Date(timeIntervalSince1970: 1_800_000_000)
func observation(_ i: Int) -> WorkoutCompanionObservation {
  WorkoutCompanionObservation(
    id: String(format: "00000000-0000-4000-8000-%012d", i), timestamp: now.addingTimeInterval(Double(i) / 8),
    start: now,
    metric: "heartRateBpm", identifier: "HKQuantityTypeIdentifierHeartRate", unit: "bpm", value: Double(100 + i % 70),
    cumulative: false)
}
var buffer = WorkoutCompanionBuffer()
for i in 0..<3_000 { try buffer.append(observation(i), now: now) }
check(buffer.entries.count <= 2_048, "all metrics share one count bound")
check(buffer.entries.reduce(0) { $0 + $1.bytes } <= 512 * 1024, "serialized bytes are bounded")
let page = buffer.page(now: now)
check(page.count == 64, "reconnect page is bounded")
buffer.acknowledge(page.last!.sequence)
check(buffer.entries.first!.sequence > page.last!.sequence, "ACK removes only acknowledged observations")
let message = WorkoutCompanionMessage(
  kind: .measurements, rideID: UUID().uuidString, observations: page.map(\.observation), sequence: page.last!.sequence)
let roundTrip = try WorkoutCompanionMessage.decode(message.encoded())
check(roundTrip.observations == message.observations, "original observation timestamps and values survive transport")
_ = buffer.page(now: now.addingTimeInterval(61))
check(buffer.entries.isEmpty && buffer.dropped > 0, "old observations become gaps instead of a durable backlog")
do {
  let invalid = WorkoutCompanionMessage(kind: .telemetry, rideID: "invalid", readings: ["power": 3])
  _ = try WorkoutCompanionMessage.decode(invalid.encoded())
  fatalError("invalid ride accepted")
} catch {}
print("Companion bounds, acknowledgements and exact timestamp transport passed")

let rideID = UUID().uuidString.lowercased()
let seconds = WorkoutHealthSeconds()
for i in 0..<48 {
  let elapsed = Double(i) / 8
  try seconds.append(
    WorkoutEvent(
      workoutId: rideID, kind: "telemetry", source: "controller", timestamp: now.addingTimeInterval(elapsed),
      elapsedSeconds: elapsed,
      payload: [
        "humanPowerW": .number(120), "cadenceRpm": .number(80), "controllerSpeedMps": .number(5),
        "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"),
        "captureSessionID": .string("bike"), "clockEpoch": .string("clock"), "connectionEpoch": .string("connection"),
        "acquisitionMonotonic": .number(elapsed), "observationSequence": .string(String(i + 1)),
      ]))
}
for second in 0..<6 {
  try seconds.append(
    WorkoutEvent(
      workoutId: rideID, kind: "location", source: "phone", timestamp: now.addingTimeInterval(Double(second)),
      elapsedSeconds: Double(second),
      payload: [
        "latitude": .number(0), "longitude": .number(Double(second) * 0.00001), "horizontalAccuracyM": .number(3),
        "speedMps": .number(1),
      ]))
}
var emitted: [Int64] = []
var meters = 0.0
// A delayed timer must drain every available closed interval, in order, without a revision or duplicate.
while let value = try seconds.drain(now: now.addingTimeInterval(10)) {
  emitted.append(value.second)
  meters += value.values["distanceDeltaMeters"] ?? 0
}
check(
  emitted == (0..<6).map { Int64(now.timeIntervalSince1970) + Int64($0) },
  "Timer jitter never skips retained Health seconds")
check(abs(meters - seconds.gpsMeters) < 0.000001 && meters > 0, "GPS Health distance survives delayed publication")
check(
  abs(seconds.controllerMeters - 29.375) < 0.000001 && abs(meters - seconds.controllerMeters) > 1,
  "Controller distance stays available for display but is not substituted into Health")
let repeated = try seconds.drain(now: now.addingTimeInterval(11))
check(repeated == nil, "Published seconds cannot be revised")
let gps = WorkoutHealthSeconds()
for (elapsed, longitude, accuracy) in [(0.0, 0.0, 3.0), (1.0, 0.00001, 100.0), (2.0, 0.00002, 3.0)] {
  try gps.append(
    WorkoutEvent(
      workoutId: rideID, kind: "location", source: "phone", timestamp: now.addingTimeInterval(elapsed),
      elapsedSeconds: elapsed,
      payload: [
        "latitude": .number(0), "longitude": .number(longitude), "horizontalAccuracyM": .number(accuracy),
        "speedMps": .number(1),
      ]))
}
check(gps.gpsMeters == 0, "Rejected GPS fixes break distance continuity")
gps.boundary()
try gps.append(
  WorkoutEvent(
    workoutId: rideID, kind: "location", source: "phone", timestamp: now.addingTimeInterval(3), elapsedSeconds: 3,
    payload: [
      "latitude": .number(0), "longitude": .number(0.00003), "horizontalAccuracyM": .number(3), "speedMps": .number(1),
    ]))
check(gps.gpsMeters == 0, "Pause and Resume never count movement during the pause")
print("Append-once seconds, delayed timer conservation, rejected-fix and pause distance boundaries passed")

let pausedState = WorkoutCompanionMessage(kind: .state, phase: "paused", stateRevision: 3)
let delayedRunning = WorkoutCompanionMessage(kind: .telemetry, phase: "running", stateRevision: 2)
check(pausedState.mayApplyState(after: 2, ended: false), "New Pause is admitted")
check(!delayedRunning.mayApplyState(after: 3, ended: false), "Late telemetry cannot resume a paused native session")
check(
  !WorkoutCompanionMessage(kind: .telemetry, phase: "running", stateRevision: 10).mayApplyState(after: 4, ended: true),
  "An ended native session cannot reopen even on a newer live snapshot")
var route = WorkoutCompanionRoute()
for i in 0..<120_001 {
  if i == 30_000 { check(route.points.count == 30_000, "All route points survive through the exact provider bound") }
  route.append([
    WorkoutCompanionLocation(
      timestamp: now.addingTimeInterval(Double(i)), latitude: 0, longitude: Double(i) / 1_000_000,
      altitude: 0, accuracy: 3, verticalAccuracy: 3, speed: 1, course: 0)
  ])
}
let routePoints = route.points
check(routePoints.count <= 30_000, "Route memory stays bounded after repeated compactions")
check(
  routePoints.first?.timestamp == now && routePoints.last?.timestamp == now.addingTimeInterval(120_000),
  "Route thinning keeps both endpoints")
check(
  zip(routePoints, routePoints.dropFirst()).allSatisfy { $0.timestamp < $1.timestamp },
  "Thinned route remains strictly ordered")
check(
  zip(routePoints, routePoints.dropFirst()).allSatisfy { $1.timestamp.timeIntervalSince($0.timestamp) <= 8 },
  "Thinning covers the whole traversed route rather than dropping its tail")
print("Companion state ordering and whole-route bounded thinning passed")
