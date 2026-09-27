import Foundation

var assertions = 0
func check(_ value: @autoclosure () -> Bool, _ message: String) {
  assertions += 1
  if !value() { fatalError(message) }
}
let now = 1_000.0
let vocabulary = [
  "off", "waiting", "receiving", "weak", "stale", "paused", "denied", "restricted", "notDetermined", "unavailable",
]
for status in vocabulary { check(WorkoutStreamStatus.normalized(status) == status, "Phone accepts semantic \(status)") }
for unknown: Any in ["GPS ±5 m", "inactive", "missing", "authorizedAlways", "unknown", 5, NSNull()] {
  check(WorkoutStreamStatus.normalized(unknown) == "waiting", "Unknown incoming status normalizes to waiting")
}
check(
  WorkoutStreamStatus.resolve(requested: false, phase: "running", age: nil, staleAfter: 2.5) == "off",
  "Unselected streams are off")
check(
  WorkoutStreamStatus.resolve(requested: true, phase: "running", age: nil, staleAfter: 2.5) == "waiting",
  "Requested streams wait for their first sample")
check(
  WorkoutStreamStatus.resolve(requested: true, phase: "paused", age: 20, staleAfter: 2.5) == "paused",
  "Paused streams take precedence over freshness")
check(
  WorkoutStreamStatus.resolve(requested: true, phase: "running", age: 5.999, staleAfter: 6) == "receiving",
  "Fresh telemetry is receiving")
check(
  WorkoutStreamStatus.resolve(requested: true, phase: "running", age: 6, staleAfter: 6) == "stale",
  "Old telemetry is stale")
check(
  WorkoutStreamStatus.resolve(requested: true, phase: "running", age: 20, staleAfter: 10, status: "denied") == "denied",
  "Permission denial is not hidden by an old fix")
for (accuracy, expected) in [
  (0.0, "receiving"), (50.0, "receiving"), (50.1, "weak"), (-1.0, "unavailable"), (Double.infinity, "unavailable"),
] {
  check(WorkoutStreamStatus.location(accuracy) == expected, "Phone classifies GPS accuracy \(accuracy)")
  var watch = WatchGPSStream()
  watch.receive(at: now, accuracy: accuracy)
  let data = try JSONSerialization.data(withJSONObject: watch.packet(requested: true, phase: "running", now: now))
  let packet = try JSONSerialization.jsonObject(with: data) as! [String: Any]
  check(packet["gpsStatus"] as? String == expected, "Watch wire status is semantic for accuracy \(accuracy)")
  check(WorkoutStreamStatus.normalized(packet["gpsStatus"]) == expected, "Phone accepts the Watch wire status")
  check(
    WorkoutStreamStatus.accuracy(packet["gpsAccuracyM"]) == (accuracy.isFinite && accuracy >= 0 ? accuracy : nil),
    "Watch numeric accuracy survives the wire and phone parsing")
}
var watch = WatchGPSStream()
check(
  watch.packet(requested: true, phase: "running", now: now)["gpsStatus"] as? String == "waiting",
  "Watch waits before its first GPS fix")
check(
  watch.packet(requested: true, phase: "running", now: now)["gpsAccuracyM"] == nil,
  "Missing GPS accuracy is omitted rather than zero")
watch.receive(at: now, accuracy: 5)
for (requested, phase, expected) in [
  (false, "running", "off"), (false, "paused", "off"), (true, "paused", "paused"), (true, "completed", "off"),
  (true, "running", "stale"),
] {
  check(
    watch.packet(requested: requested, phase: phase, now: now + 11)["gpsStatus"] as? String
      == expected, "Watch wire status respects selection, phase and freshness")
}
for status in ["denied", "restricted", "notDetermined", "unavailable"] {
  watch.status = status
  check(
    watch.packet(requested: true, phase: "running", now: now + 11)["gpsStatus"] as? String == status,
    "Watch wire preserves provider state \(status)")
}
check(
  WorkoutStreamStatus.accuracy("5") == nil && WorkoutStreamStatus.accuracy(Double.nan) == nil,
  "Phone rejects nonnumeric or nonfinite accuracy")
var adopted = WorkoutLivePresentation()
adopted.id = "ride-a"
check(adopted.heartFreshness.receive(id: "heart-a", sequence: 5_000, age: 0, at: now), "Ride A heart is admitted")
check(adopted.gpsFreshness.receive(id: "gps-a", sequence: 5_000, age: 0, at: now), "Ride A GPS is admitted")
check(adopted.cycFreshness.receive(id: "cyc-a", sequence: 5_000, age: 0, at: now), "Ride A CYC is admitted")
adopted.gpsStatus = "weak"
adopted.gpsAccuracy = 75
adopted.metrics = ["heartRateBpm": 180, "riderPowerW": 220]
adopted.id = "ride-a"
check(adopted.heartFreshness.age(at: now) == 0, "Same ride identity preserves freshness")
adopted.id = "ride-b"
check(
  adopted.heartFreshness.age(at: now) == nil && adopted.gpsFreshness.age(at: now) == nil
    && adopted.cycFreshness.age(at: now) == nil,
  "Adopting another ride resets every stream's freshness")
check(
  adopted.gpsStatus == "off" && adopted.gpsAccuracy == nil && adopted.metrics.isEmpty,
  "Adoption clears GPS presentation and previous ride readings")
check(
  adopted.heartFreshness.receive(id: "heart-b", sequence: 1, age: 0, at: now + 1), "Ride B heart starts at sequence 1")
check(adopted.gpsFreshness.receive(id: "gps-b", sequence: 1, age: 0, at: now + 1), "Ride B GPS starts at sequence 1")
check(adopted.cycFreshness.receive(id: "cyc-b", sequence: 1, age: 0, at: now + 1), "Ride B CYC starts at sequence 1")
check(
  WorkoutStreamStatus.resolve(
    requested: true, phase: "running", age: adopted.heartFreshness.age(at: now + 1), staleAfter: 15) == "receiving",
  "New ride heart stream becomes receiving immediately")
print("Native stream status: \(assertions) assertions passed; semantic Watch wire round-trip")
