import Foundation
import PowerLogCore

func runGPSClockFixtures() throws {
  var assertions = 0
  func check(_ value: Bool, _ message: String) {
    assertions += 1
    if !value { FileHandle.standardError.write(Data((message + "\n").utf8)) }
    precondition(value, message)
  }
  let reference = Date(timeIntervalSinceReferenceDate: 789_004_800)
  let referenceAnchor = WorkoutTimelineAnchor(
    epoch: "date", monotonicOrigin: 0, startedAt: WorkoutCoding.timestamp(reference))
  let fraction = reference.timeIntervalSinceReferenceDate.nextUp - reference.timeIntervalSinceReferenceDate
  let ageClock = WorkoutTimelineAnchor.LocationClock()
  let expired = try ageClock.map(
    timestamp: reference.addingTimeInterval(5), latitude: 0, longitude: 0, accuracy: 3,
    receivedAt: reference.addingTimeInterval(20 + fraction), uptime: 20, anchor: referenceAnchor)
  if ProcessInfo.processInfo.environment["POWER_LOG_GPS_DATE_CASE"] != "clock" {
    check(expired == nil, "Date age 15.00000011920929 remains expired")
  }
  let barrierClock = WorkoutTimelineAnchor.LocationClock()
  let barrier = try barrierClock.map(
    timestamp: reference.addingTimeInterval(21 + fraction), latitude: 0, longitude: 0, accuracy: 3,
    receivedAt: reference.addingTimeInterval(21 + fraction), uptime: 20, anchor: referenceAnchor)!
  check(barrier.uncertain, "Date discrepancy 1.00000011920929 starts uncertainty")
  let heldBarrier = try barrierClock.map(
    timestamp: reference.addingTimeInterval(35 + fraction), latitude: 0, longitude: 0, accuracy: 3,
    receivedAt: reference.addingTimeInterval(35 + fraction), uptime: 34, anchor: referenceAnchor)!
  check(heldBarrier.uncertain, "Date uncertainty lasts for fifteen monotonic seconds")
  let settled = try barrierClock.map(
    timestamp: reference.addingTimeInterval(37 + fraction), latitude: 0, longitude: 0, accuracy: 3,
    receivedAt: reference.addingTimeInterval(37 + fraction), uptime: 36, anchor: referenceAnchor)!
  check(!settled.uncertain, "Date uncertainty ends after its inclusive fifteen-second boundary")
  let base = URL(fileURLWithPath: "tests/fixtures/contract/gps")
  func fixture(_ file: String) throws -> [String: Any] {
    try JSONSerialization.jsonObject(with: Data(contentsOf: base.appendingPathComponent(file + ".json")))
      as! [String: Any]
  }
  let start = "2026-01-01T00:00:00Z"
  let startUs = try WorkoutCoding.timestampMicroseconds(start)
  func timestamp(_ us: Int64) throws -> ProtocolTimestamp {
    try Timestamps.shared.parse(
      original: String(
        WorkoutCoding.timestamp(Date(timeIntervalSince1970: Double((startUs + us) / 1_000_000))).prefix(19))
        + String(format: ".%06lldZ", (startUs + us) % 1_000_000))
  }
  func date(_ stamp: ProtocolTimestamp) -> Date {
    let whole = stamp.comparisonUs / 1_000_000 - Int64(Date.timeIntervalBetween1970AndReferenceDate)
    let nanos = GPSReceipt(timestamp: stamp, monotonicSeconds: 0).submicrosecondNanos
    return Date(
      timeIntervalSinceReferenceDate: Double(whole) + Double(stamp.comparisonUs % 1_000_000) / 1_000_000 + Double(nanos)
        / 1_000_000_000)
  }
  func original(_ timestamp: ProtocolTimestamp, _ row: [String: Any] = [:]) throws -> GPSOriginal {
    try WorkoutTimelineAnchor.LocationClock.original(
      timestamp: date(timestamp),
      latitude: row["latitude"] as? Double ?? 0, longitude: row["longitude"] as? Double ?? 0,
      accuracy: row["accuracy"] as? Double ?? 3)
  }
  func rawOriginal(_ timestamp: ProtocolTimestamp) -> GPSOriginal {
    GPSOriginal(timestamp: timestamp, latitude: 0, longitude: 0, horizontalAccuracyM: 3)
  }
  func receipt(_ us: Int64, _ uptime: Double) throws -> GPSReceipt {
    try GPSReceipt(
      timestamp: WorkoutTimelineAnchor.LocationClock.timestamp(date(timestamp(us))), monotonicSeconds: uptime)
  }
  let anchor = WorkoutTimelineAnchor(epoch: "first", monotonicOrigin: 0, startedAt: start)
  for row in try fixture("acquisition")["cases"] as! [[String: Any]] {
    let clock = WorkoutTimelineAnchor.LocationClock()
    let name = row["id"] as! String
    let stamp =
      try (row["timestamp"] as? String).map { try Timestamps.shared.parse(original: $0) }
      ?? timestamp(Int64(row["fixUs"] as! String)!)
    let changed = WorkoutTimelineAnchor(
      epoch: "first", monotonicOrigin: row["origin"] as? Double ?? 0, startedAt: start,
      stopMonotonic: row["stop"] as? Double, epochStart: row["epochStart"] as? Double)
    let batch = try clock.mapBatch(
      originals: [original(stamp, row)],
      received: receipt(Int64(row["receiptUs"] as! String)!, row["uptime"] as! Double), anchor: changed)
    check(!batch.fixes.isEmpty == row["mapped"] as! Bool, name)
    check(batch.discontinuity == batch.fixes.isEmpty, name + " discontinuity")
    if let fix = batch.fixes.first?.fix {
      check(fix.core.elapsedUs == Int64(row["elapsedUs"] as! String)!, name + " elapsed key")
      check(clock.admit(fix) && !clock.admit(fix), name + " physical dedup")
    }
  }
  for row in try fixture("clock")["cases"] as! [[String: Any]] {
    let clock = WorkoutTimelineAnchor.LocationClock()
    if row["prime"] as? Bool == true {
      _ = try clock.mapBatch(
        originals: [original(timestamp(1_000_000_000))],
        received: receipt(1_000_000_000, 1000),
        anchor: anchor)
    }
    let us = Int64(row["receiptUs"] as! String)!
    let batch = try clock.mapBatch(
      originals: [original(timestamp(us))],
      received: receipt(us, row["uptime"] as! Double),
      anchor: anchor)
    check(batch.fixes.first!.fix.uncertain == row["uncertain"] as! Bool, row["id"] as! String)
  }
  for row in try fixture("raw-time")["cases"] as! [[String: Any]] {
    let clock = WorkoutTimelineAnchor.LocationClock()
    if let prime = row["prime"] as? String {
      let stamp = try Timestamps.shared.parse(original: prime)
      _ = try clock.mapBatch(
        originals: [rawOriginal(stamp)],
        received: GPSReceipt(timestamp: stamp, monotonicSeconds: row["primeUptime"] as! Double), anchor: anchor)
    }
    let batch = try clock.mapBatch(
      originals: [rawOriginal(Timestamps.shared.parse(original: row["fix"] as! String))],
      received: GPSReceipt(
        timestamp: Timestamps.shared.parse(original: row["receipt"] as! String),
        monotonicSeconds: row["uptime"] as! Double), anchor: anchor)
    check(!batch.fixes.isEmpty == row["mapped"] as! Bool, row["id"] as! String)
    if let fix = batch.fixes.first?.fix {
      check(fix.uncertain == row["uncertain"] as! Bool, "raw clock threshold")
      check(fix.core.elapsedUs == Int64(row["elapsedUs"] as! String)!, "raw floor once")
      check(abs(fix.acquisition - (row["acquisition"] as! Double)) < 1e-10, "raw acquisition precision")
    }
  }
  let clock = WorkoutTimelineAnchor.LocationClock()
  for row in try fixture("acquisition")["ordering"] as! [[String: Any]] {
    let fix = try clock.mapBatch(
      originals: [original(timestamp(Int64(row["timeUs"] as! String)!))],
      received: receipt(20_000_000, 20), anchor: anchor
    ).fixes.first!.fix
    check(clock.admit(fix) == row["accepted"] as! Bool, "strict acquisition order")
  }
  let batchClock = WorkoutTimelineAnchor.LocationClock()
  let batch = try batchClock.mapBatch(
    originals: [
      original(timestamp(12_000_000)), original(timestamp(10_000_000)), original(timestamp(11_000_000)),
      original(timestamp(11_000_000), ["latitude": 91.0]),
    ],
    received: receipt(20_000_000, 20), anchor: anchor)
  check(
    batch.discontinuity && batch.fixes.map(\.index) == [1, 2, 0],
    "core orders whole callbacks and rejects malformed fixes")
  for (_, fix) in batch.fixes { check(batchClock.admit(fix), "sorted fixes admitted") }
  let changed = WorkoutTimelineAnchor(epoch: "second", monotonicOrigin: 0, startedAt: start)
  let replay = try batchClock.mapBatch(
    originals: [original(timestamp(12_000_000))],
    received: receipt(20_000_000, 20), anchor: changed
  ).fixes.first!.fix
  check(!batchClock.admit(replay), "epoch retains physical dedup")
  print("GPS clock contract: \(assertions) assertions passed.")
}

func gpsClockFix(_ anchor: WorkoutTimelineAnchor, acquisition: Double, timestamp: Date) throws
  -> WorkoutTimelineAnchor.LocationClock.Fix?
{
  try WorkoutTimelineAnchor.LocationClock().map(
    timestamp: timestamp, latitude: 0, longitude: 0, accuracy: 3,
    receivedAt: timestamp, uptime: acquisition, anchor: anchor)
}
