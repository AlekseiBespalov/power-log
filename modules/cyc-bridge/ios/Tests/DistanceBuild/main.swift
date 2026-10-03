import CryptoKit
import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-distance-build-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("store.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"), store: store)
let service = WorkoutDistanceStore(store: store)
let start = Date(timeIntervalSince1970: 1_780_000_000.25)
func stable(_ label: String) -> String { WorkoutStableIdentity.uuid("distance-build:" + label) }
func event(
  _ id: String, _ label: String, _ kind: String, _ source: String, _ time: Double?, _ payload: [String: WorkoutJSON],
  stamp: Double? = nil
) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: id, kind: kind, source: source, timestamp: start.addingTimeInterval(stamp ?? time!),
    elapsedSeconds: time, payload: payload, eventId: stable(id + ":" + label))
}
func timestamp(_ seconds: Double) -> WorkoutJSON { .string(WorkoutCoding.timestamp(start.addingTimeInterval(seconds))) }
func bits(_ value: Double?) -> String { value.map { String($0.bitPattern, radix: 16) } ?? "-" }

// A seeded 1-hour Watch-owned ride with both GPS producers, both Health sources and the controller.
let rideID = stable("ride")
let workoutUUID = stable("workout")
_ = try archive.create(
  id: rideID, startedAt: start, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true)
try archive.update(id: rideID, healthKitUUID: workoutUUID)
struct Fix {
  let latitude: Double
  let longitude: Double
  let speed: Double?
  let speedAccuracy: Double?
}
var fixes: [String: Fix] = [:]
var timeline: [(time: Double, event: WorkoutEvent, producer: String?, sequence: Int64?)] = []
func add(_ event: WorkoutEvent, producer: String? = nil, sequence: Int64? = nil) {
  timeline.append((event.elapsedSeconds ?? -1, event, producer, sequence))
}
var late: [WorkoutEvent] = []
for (label, time, action) in [("start", 0.0, "start"), ("pause", 1200, "pause"), ("resume", 1260, "resume")] {
  add(try event(rideID, label, "lifecycle", "watch", time, ["action": .string(action)]))
}
add(try event(rideID, "lap", "lifecycle", "watch", 1800, ["action": .string("lap")]))
add(
  try event(
    rideID, "interruption", "lifecycle", "watch", 2400,
    ["action": .string("pause"), "interrupted": .bool(true), "cycSequence": .string("9600")]), producer: "owner",
  sequence: 1)
add(
  try event(rideID, "recovered", "lifecycle", "watch", 2400, ["action": .string("resume")]), producer: "owner",
  sequence: 2)
late.append(try event(rideID, "stop", "lifecycle", "watch", 3600, ["action": .string("stop")]))
var telemetry: [WorkoutEvent] = []
for index in 0..<14_400 {
  let time = Double(index) / 4
  var payload: [String: WorkoutJSON] = [
    "humanPowerW": .number(Double(150 + index % 50)), "cadenceRpm": .number(80),
    "controllerSpeedMps": .number(4 + Double(index % 40) / 10), "controllerModel": .string("X6"),
    "controllerProtocol": .string("5.3"), "captureSessionID": .string(stable(time < 1800 ? "session-a" : "session-b")),
    "observationSequence": .string(String(index + 1)), "connectionEpoch": .string(time < 3000 ? "link-a" : "link-b"),
    "clockEpoch": .string(time < 2400 ? "clock-a" : "clock-b"), "acquisitionMonotonic": .number(1000 + time),
  ]
  if index == 2400 { payload["clockDiscontinuitySeconds"] = .number(2) }
  if index == 3600 { payload["controllerSpeedMps"] = .number(55) }
  if index == 4000 { payload["distanceBarrier"] = .bool(true) }
  let frame = try event(rideID, "frame-\(index)", "telemetry", "cyc", time, payload)
  telemetry.append(frame)
  add(frame)
}
for (offset, link) in [(1, "link-r1"), (2, "link-r2"), (3, "link-a")] {
  add(
    try event(
      rideID, "relay-\(offset)", "telemetry", "cyc", 1500,
      [
        "humanPowerW": .number(200), "cadenceRpm": .number(85), "controllerSpeedMps": .number(5 + Double(offset)),
        "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"),
        "captureSessionID": .string(stable("session-a")), "observationSequence": .string(String(90_000 + offset)),
        "connectionEpoch": .string(link), "clockEpoch": .string("clock-a"),
      ]), producer: "relay", sequence: Int64(offset))
}
late.append(
  try event(
    rideID, "frame-correction", "telemetry", "cyc", telemetry[5000].elapsedSeconds,
    [
      "humanPowerW": .number(180), "cadenceRpm": .number(82), "controllerSpeedMps": .number(9),
      "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"),
      "captureSessionID": .string(stable("session-a")), "connectionEpoch": .string("link-a"),
      "clockEpoch": .string("clock-a"), "supersedesEventId": .string(telemetry[5000].eventId),
    ]))
for (source, offset) in [("watch", 0.0), ("phone", 0.5)] {
  var gps: [WorkoutEvent] = []
  for index in 0..<3600 where source == "watch" || !(1500..<1520).contains(index) {
    let time = Double(index) + offset
    let fix = Fix(
      latitude: 45 + Double(index) * 0.00004 + (source == "phone" ? 0.00001 : 0),
      longitude: 7 + Double(index) * 0.00003 + Double(index % 13) * 0.000001,
      speed: (3100..<3110).contains(index) ? 0.2 : 5 + Double(index % 7) / 10,
      speedAccuracy: source == "watch" ? 0.5 : (index % 97 == 0 ? -1 : nil))
    var payload: [String: WorkoutJSON] = [
      "latitude": .number(fix.latitude), "longitude": .number(fix.longitude),
      "horizontalAccuracyM": .number(index % 211 == 0 ? 80 : 4), "clockEpoch": .string(time < 2400 ? "gps-a" : "gps-b"),
    ]
    if let speed = fix.speed { payload["speedMps"] = .number(speed) }
    if let accuracy = fix.speedAccuracy { payload["speedAccuracyMps"] = .number(accuracy) }
    if index == 2000 { payload["distanceBarrier"] = .bool(true) }
    let fixEvent = try event(rideID, "\(source)-fix-\(index)", "location", source, time, payload)
    fixes[fixEvent.eventId] = fix
    gps.append(fixEvent)
    add(fixEvent)
  }
  if source == "watch" {
    for (label, epoch, sequence) in [("tie-a", "gps-tie", Int64(1)), ("tie-b", "gps-a", 2)] {
      let fix = Fix(latitude: 45.04 + Double(sequence) * 0.00001, longitude: 7.03, speed: 5, speedAccuracy: 0.5)
      let tie = try event(
        rideID, "watch-\(label)", "location", "watch", 1000,
        [
          "latitude": .number(fix.latitude), "longitude": .number(fix.longitude), "horizontalAccuracyM": .number(4),
          "speedMps": .number(5), "speedAccuracyMps": .number(0.5), "clockEpoch": .string(epoch),
        ])
      fixes[tie.eventId] = fix
      add(tie, producer: "watch-relay", sequence: sequence)
    }
  }
  late.append(
    try event(
      rideID, "\(source)-fix-tombstone", "location", source, gps[700].elapsedSeconds,
      [
        "latitude": .number(0), "longitude": .number(0), "horizontalAccuracyM": .number(4),
        "supersedesEventId": .string(gps[700].eventId), "deleted": .bool(true),
      ]))
}
for (source, step) in [("watch", 10.0), ("phone", 15.0)] {
  var samples: [WorkoutEvent] = []
  var begin = 0.0
  while begin + step <= 3600 {
    var payload: [String: WorkoutJSON] = [
      "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "unit": .string("m"),
      "value": .number(40 + Double(Int(begin) % 7)), "representation": .string("rawQuantity"),
      "sampleCount": .number(1), "sampleUUID": .string(stable("\(source)-sample-\(begin)")),
      "sampleStart": timestamp(begin), "sampleEnd": timestamp(begin + step),
    ]
    if source == "watch" && Int(begin / step) % 2 == 0 {
      payload["associatedWorkoutUUID"] = .string(workoutUUID.uppercased())
    }
    let sample = try event(rideID, "\(source)-sample-\(begin)", "health", source, begin + step, payload)
    samples.append(sample)
    add(sample)
    begin += step
  }
  for sample in samples where sample.payload["associatedWorkoutUUID"] == nil {
    late.append(
      try event(
        rideID, "receipt-" + sample.eventId, "health", source, 3601,
        [
          "representation": .string("workoutAssociation"), "sampleUUID": sample.payload["sampleUUID"]!,
          "associatedWorkoutUUID": .string(workoutUUID),
        ]))
  }
  late.append(
    try event(
      rideID, "\(source)-sample-tombstone", "health", source, samples[80].elapsedSeconds! + 0.001,
      [
        "representation": .string("healthTombstone"), "sampleUUID": samples[80].payload["sampleUUID"]!,
        "supersedesEventId": .string(samples[80].eventId), "deleted": .bool(true),
      ]))
  for minute in stride(from: 60.0, through: 3540, by: source == "watch" ? 60 : 120) {
    add(
      try event(
        rideID, "\(source)-total-\(minute)", "health", source, minute,
        [
          "distanceMeters": .number(minute * 5.5),
          "representation": .string(source == "watch" ? "cumulativeWorkoutTotal" : ""),
        ]))
  }
}
for (label, time) in [("watch-total-late-a", 2990.0), ("watch-total-late-b", 2995.0)] {
  add(
    try event(
      rideID, label, "health", "watch", time,
      ["distanceMeters": .number(time * 5.6), "representation": .string("cumulativeWorkoutTotal")]))
}
for second in 0..<3600 {
  add(
    try event(
      rideID, "heart-\(second)", "health", "watch", Double(second),
      [
        "heartRateBpm": .number(Double(110 + second % 40)), "representation": .string("rawQuantity"),
        "sampleUUID": .string(stable("heart-\(second)")),
      ]))
}
for (label, a, b, meters) in [("overlap-a", 500.0, 512.0, 60.0), ("overlap-b", 505.0, 515.0, 50.0)] {
  add(
    try event(
      rideID, label, "health", "watch", b,
      [
        "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "unit": .string("m"),
        "value": .number(meters), "representation": .string("rawQuantity"), "sampleCount": .number(1),
        "sampleUUID": .string(stable(label)), "sampleStart": timestamp(a), "sampleEnd": timestamp(b),
        "associatedWorkoutUUID": .string(workoutUUID),
      ]))
}
add(
  try event(
    rideID, "before-start", "health", "watch", nil,
    [
      "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "unit": .string("m"),
      "value": .number(30), "representation": .string("rawQuantity"), "sampleCount": .number(1),
      "sampleUUID": .string(stable("before-start")), "sampleStart": timestamp(-40), "sampleEnd": timestamp(-30),
      "associatedWorkoutUUID": .string(workoutUUID),
    ], stamp: -30))
late.append(
  try event(
    rideID, "watch-final", "health", "watch", 3600,
    ["distanceMeters": .number(20_100), "representation": .string("finalWorkoutTotal")]))

func admit(
  _ entries: ArraySlice<(time: Double, event: WorkoutEvent, producer: String?, sequence: Int64?)>,
  reversed: Bool = false
) throws {
  let ordered = entries.filter { $0.producer == nil }.map(\.event)
  let plain = reversed ? ordered.reversed() : ordered
  for first in stride(from: 0, to: plain.count, by: 512) {
    _ = try archive.appendBatch(Array(plain[first..<min(first + 512, plain.count)]))
  }
  for entry in entries.reversed() where entry.producer != nil {
    _ = try archive.appendBatch([entry.event], producer: entry.producer, firstSequence: entry.sequence)
  }
}
timeline = timeline.enumerated().sorted { ($0.element.time, $0.offset) < ($1.element.time, $1.offset) }.map(\.element)
let phases = [3000.0, 3060, 3100].map { limit in timeline.firstIndex { $0.time >= limit }! }
func work<T>(_ body: () throws -> T) throws -> (T, reprepares: Int) {
  let before = try store.read { $0.workForTesting.reprepares }
  let value = try body()
  return (value, try store.read { $0.workForTesting.reprepares } - before)
}
var digest = SHA256()
var lines = 0
func record(_ line: String) {
  digest.update(data: Data((line + "\n").utf8))
  lines += 1
}
/// GPS meters depend on libm, so the digest masks them and each GPS point is recomputed from its two fixes.
func describe(_ snapshot: WorkoutDistanceSnapshot) throws {
  let gps = snapshot.source?.hasPrefix("gps:") == true
  func meters(_ value: Double?, _ geometry: Bool) -> String { geometry ? "~" : bits(value) }
  record(
    "\(snapshot.selection) \(snapshot.source ?? "-") \(snapshot.method ?? "-") \(snapshot.estimated) \(meters(snapshot.totalMeters, gps)) \(bits(snapshot.coveredSeconds)) \(bits(snapshot.activeSeconds)) \(snapshot.outcome) \(bits(snapshot.healthReportedMeters)) \(snapshot.healthReportedSource ?? "-") \(snapshot.healthReportedProvisional) \(snapshot.healthReportedAt ?? "-") \(snapshot.revision) \(snapshot.storageID) \(snapshot.maximumPointID) \(bits(snapshot.endSeconds)) \(snapshot.activeIntervals.map { $0.map(bits).joined(separator: ",") })"
  )
  for source in snapshot.sources {
    record(
      "\(source.source) \(source.label) \(source.estimated) \(source.partial) \(bits(source.coveredSeconds)) \(bits(source.uncoveredSeconds)) \(meters(source.distanceMeters, source.source.hasPrefix("gps:")))"
    )
  }
  var after: WorkoutDistanceCursor?
  var total = 0.0
  var count = 0
  while true {
    let page = try service.page(snapshot: snapshot, after: after, limit: 256)
    for point in page {
      record(
        "\(point.pointID) \(bits(point.elapsedSeconds)) \(point.timestamp) \(meters(point.distanceMeters, gps)) \(meters(point.incrementMeters, gps)) \(bits(point.startSeconds)) \(bits(point.endSeconds)) \(point.segment) \(point.startAnchor) \(point.endAnchor) \(bits(point.startSpeed)) \(bits(point.endSpeed)) \(point.indivisible) \(bits(point.cumulativeCoveredSeconds)) \(point.plotSegment)"
      )
      if gps, point.startSeconds < point.endSeconds {
        let a = fixes[point.startAnchor]!
        let b = fixes[point.endAnchor]!
        func fix(_ value: Fix) -> WorkoutGPSFix {
          WorkoutGPSFix(time: 0, latitude: value.latitude, longitude: value.longitude, horizontalAccuracy: 4)
        }
        let stationary = [a, b].allSatisfy {
          WorkoutDistancePolicy.validSpeed($0.speed, accuracy: $0.speedAccuracy).map { $0 < 0.5 } == true
        }
        let chord = stationary ? 0 : WorkoutGPSDistanceAccumulator.meters(fix(a), fix(b))
        total += chord
        check(point.incrementMeters == chord && point.distanceMeters == total, "GPS point \(point.pointID) geometry")
      } else if gps {
        check(point.distanceMeters == total, "GPS anchor \(point.pointID) carries the cumulative distance")
      }
    }
    count += page.count
    after = page.last?.cursor
    if page.count < 256 { break }
  }
  var cursor: WorkoutDistanceCursor?
  var intervals = 0
  while true {
    let job = try service.intervals(snapshot: snapshot, after: cursor, limit: 512) { interval in
      record(
        "\(bits(interval.cursor.time)) \(interval.cursor.pointID) \(bits(interval.start)) \(bits(interval.end)) \(meters(interval.meters, gps)) \(interval.segment) \(bits(interval.startSpeed)) \(bits(interval.endSpeed))"
      )
      intervals += 1
      return true
    }
    if let last = job.last { cursor = last }
    if job.examined < 512 { break }
  }
  check(snapshot.source == nil || (count > intervals && intervals > 0), "\(snapshot.selection) has its points")
}
try admit(timeline[..<phases[0]])
let (cold, coldReprepares) = try work { try service.snapshot(id: rideID) }
try describe(cold)
try admit(timeline[phases[0]..<phases[1]], reversed: true)
let (appended, appendReprepares) = try work { try service.snapshot(id: rideID) }
try describe(appended)
try archive.append(
  try event(
    rideID, "late-heart", "health", "watch", 3030,
    [
      "heartRateBpm": .number(150), "representation": .string("rawQuantity"),
      "sampleUUID": .string(stable("late-heart")),
    ]))
let (delayed, _) = try work { try service.snapshot(id: rideID) }
try describe(delayed)
try admit(timeline[phases[1]..<phases[2]])
let (again, _) = try work { try service.snapshot(id: rideID) }
try describe(again)
try admit(timeline[phases[2]...])
_ = try archive.appendBatch(late)
_ = try archive.update(id: rideID, stopElapsedSeconds: 3600)
_ = try archive.finish(id: rideID, endedAt: start.addingTimeInterval(3600))
let (rebuilt, rebuildReprepares) = try work { try service.snapshot(id: rideID) }
for selection in ["auto"] + WorkoutDistancePolicy.sources {
  try describe(service.snapshot(id: rideID, selection: selection))
}
let (older, olderReprepares) = try work { try service.snapshot(id: rideID, revision: again.revision - 100) }
for selection in WorkoutDistancePolicy.sources {
  try describe(service.snapshot(id: rideID, revision: older.revision, selection: selection))
}
let identity = digest.finalize().map { String(format: "%02x", $0) }.joined()
check(
  identity == "0370c4c73494984d3f7c30e097c2f9eaa1f325966190de8ef78fd21a63342d6f",
  "profiles, points, intervals and snapshot fields match their recorded digest: \(identity) over \(lines) lines")
check(
  appended.generation == cold.generation && again.generation == delayed.generation
    && Set([cold.generation, delayed.generation, rebuilt.generation, older.generation]).count == 4,
  "appends extend the generation, while late originals and an older revision build their own")
check(
  Set(rebuilt.sources.map(\.source)) == Set(WorkoutDistancePolicy.sources) && rebuilt.source == "gps:watch",
  "every distance source is available on the seeded ride")
check(
  coldReprepares == 0 && appendReprepares == 0 && olderReprepares == 0 && rebuildReprepares == 0,
  "no build statement is re-planned: \(coldReprepares) \(appendReprepares) \(olderReprepares) \(rebuildReprepares)")
let probe = try work {
  for kind in ["telemetry", "location", "telemetry"] {
    _ = try store.read { db in
      try db.rows(
        "SELECT count(*) AS n FROM collection_memberships m WHERE m.collection_id=? AND m.kind=?",
        [.text(rideID), .text(kind)], limit: 1)
    }
  }
}
check(probe.reprepares > 0, "a bound kind re-plans its statement, so the counter sees re-preparation")

// Bounded jobs: every executor job of a build examines a bounded batch, and capture writes run between them.
final class Log: @unchecked Sendable {
  private let lock = NSLock()
  private var entries: [(priority: PowerLogJobPriority, steps: Int, reprepares: Int)] = []
  private var building = false
  func append(_ priority: PowerLogJobPriority, _ steps: Int, _ reprepares: Int) {
    lock.lock()
    if building { entries.append((priority, steps, reprepares)) }
    lock.unlock()
  }
  func window(_ open: Bool) {
    lock.lock()
    building = open
    lock.unlock()
  }
  var jobs: [(priority: PowerLogJobPriority, steps: Int, reprepares: Int)] {
    lock.lock()
    defer { lock.unlock() }
    return entries
  }
}
/// Appends capture-style telemetry to a live collection until stopped.
final class Capture: @unchecked Sendable {
  private let condition = NSCondition()
  private var running = true
  private var done = false
  private var failure: Error?
  init(_ live: String) {
    Thread { [self] in
      for index in 1... {
        condition.lock()
        let proceed = running
        condition.unlock()
        guard proceed else { break }
        do {
          _ = try store.appendTelemetry(
            [
              "timestamp": WorkoutCoding.timestamp(start.addingTimeInterval(Double(index) / 8)), "humanPowerW": 150.0,
              "cadenceRpm": 80.0,
            ], collectionID: live, elapsedSeconds: Double(index) / 8)
        } catch {
          condition.lock()
          failure = error
          condition.unlock()
          break
        }
      }
      condition.lock()
      done = true
      condition.broadcast()
      condition.unlock()
    }.start()
  }
  func stop() throws {
    condition.lock()
    running = false
    while !done { condition.wait() }
    condition.unlock()
    if let failure { throw failure }
  }
}
func bounded(_ id: String, capture: Bool = false) throws -> (
  snapshot: WorkoutDistanceSnapshot, distance: [WorkoutDistanceStore.Job],
  executor: [(priority: PowerLogJobPriority, steps: Int, reprepares: Int)]
) {
  let log = Log()
  var distance: [WorkoutDistanceStore.Job] = []
  store.jobObserverForTesting = { log.append($0, $1, $2) }
  WorkoutDistanceStore.jobObserverForTesting = { distance.append($0) }
  defer {
    store.jobObserverForTesting = nil
    WorkoutDistanceStore.jobObserverForTesting = nil
  }
  let live = UUID().uuidString.lowercased()
  try store.createCollection(id: live, kind: "live", startedAt: WorkoutCoding.timestamp(start), monotonicOrigin: 0)
  let writer = capture ? Capture(live) : nil
  log.window(true)
  let snapshot = try service.snapshot(id: id)
  log.window(false)
  try writer?.stop()
  return (snapshot, distance, log.jobs)
}

let heavy = stable("heavy-health")
_ = try archive.create(
  id: heavy, startedAt: start, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true)
try archive.append(try event(heavy, "start", "lifecycle", "watch", 0, ["action": .string("start")]))
try archive.appendBatch(
  try (0..<100).map { index in
    try event(
      heavy, "fix-\(index)", "location", "watch", Double(index),
      [
        "latitude": .number(45 + Double(index) * 0.00004), "longitude": .number(7), "horizontalAccuracyM": .number(4),
        "clockEpoch": .string("gps"),
      ])
  })
try archive.append(
  try event(
    heavy, "builder-total", "health", "watch", 0.05, ["distanceMeters": .number(5), "representation": .string("")]))
for first in stride(from: 0, to: 100_000, by: 512) {
  try autoreleasepool {
    _ = try archive.appendBatch(
      try (first..<min(first + 512, 100_000)).map { index in
        try event(
          heavy, "heart-\(index)", "health", "watch", Double(index) / 10,
          [
            "heartRateBpm": .number(120), "healthKitIdentifier": .string("HKQuantityTypeIdentifierHeartRate"),
            "representation": .string("rawQuantity"), "sampleUUID": .string(stable("heavy-heart-\(index)")),
          ])
      })
  }
}
let busy = try bounded(heavy, capture: true)
let healthJobs = busy.distance.filter { $0.name == "health:watch" }.map(\.examined)
check(
  healthJobs.count == 782 && healthJobs.dropLast().allSatisfy { $0 == WorkoutDistanceStore.jobRows }
    && healthJobs.reduce(0, +) == 100_001, "100,001 Health candidates are examined in bounded jobs: \(healthJobs.count)"
)
let reportJobs = busy.distance.filter { $0.name == "report" }.map(\.examined)
check(
  reportJobs.reduce(0, +) >= 100_001 && reportJobs.allSatisfy { $0 <= WorkoutDistanceStore.jobRows },
  "a report that does not exist is searched in bounded jobs")
check(
  busy.distance.allSatisfy {
    $0.examined <= ($0.name == "points" ? WorkoutDistanceStore.writeRows : WorkoutDistanceStore.jobRows)
  },
  "every distance job examines or writes a bounded batch")
let builds = busy.executor.filter { $0.priority != .capture }
let captures = busy.executor.count - builds.count
let largest = builds.map(\.steps).max() ?? 0
check(largest < 100_000, "no executor job of the build runs more than a bounded number of VM steps: \(largest)")
let reads = busy.distance.filter { $0.name != "points" && $0.examined > 0 }
check(
  reads.allSatisfy { Double($0.steps) / Double($0.examined) < 200 },
  "each examined candidate costs bounded work: \(reads.map { Double($0.steps) / Double($0.examined) }.max() ?? 0)")
check(builds.allSatisfy { $0.reprepares == 0 }, "the build re-plans no statement")
check(captures > 0, "capture writes run between the jobs of the build")
check(
  busy.snapshot.source == "gps:watch" && busy.snapshot.sources.allSatisfy { !$0.source.hasPrefix("health:") }
    && busy.snapshot.healthReportedMeters == 5 && busy.snapshot.healthReportedProvisional,
  "Health rows without distance leave only the GPS profile and the provisional report")
print(
  "Bounded build: \(builds.count) executor jobs, largest \(largest) VM steps, \(captures) capture writes between them")

// Heavily superseded GPS and telemetry: rejected candidates still advance the job cursor.
let superseded = stable("superseded")
_ = try archive.create(
  id: superseded, startedAt: start, indoor: false, watchEnabled: false, saveToHealth: false, recordGPS: true)
try archive.append(try event(superseded, "start", "lifecycle", "phone", 0, ["action": .string("start")]))
var originals: [WorkoutEvent] = []
for index in 0..<1200 {
  originals.append(
    try event(
      superseded, "fix-\(index)", "location", "phone", Double(index) / 2,
      [
        "latitude": .number(45 + Double(index) * 0.00002), "longitude": .number(7), "horizontalAccuracyM": .number(4),
        "clockEpoch": .string("gps"),
      ]))
}
for index in 0..<1000 {
  originals.append(
    try event(
      superseded, "frame-\(index)", "telemetry", "cyc", Double(index) / 2,
      [
        "humanPowerW": .number(150), "cadenceRpm": .number(80), "controllerSpeedMps": .number(5),
        "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"),
        "captureSessionID": .string(stable("superseded-session")), "observationSequence": .string(String(index + 1)),
        "connectionEpoch": .string("link"), "clockEpoch": .string("clock"),
      ]))
}
for first in stride(from: 0, to: originals.count, by: 512) {
  _ = try archive.appendBatch(Array(originals[first..<min(first + 512, originals.count)]))
}
let tombstones = try originals.map { original in
  var payload = original.payload
  payload.removeValue(forKey: "captureSessionID")
  payload.removeValue(forKey: "observationSequence")
  payload["supersedesEventId"] = .string(original.eventId)
  payload["deleted"] = .bool(true)
  return try event(
    superseded, "tombstone-" + original.eventId, original.kind, original.source, original.elapsedSeconds! + 0.001,
    payload)
}
for first in stride(from: 0, to: tombstones.count, by: 512) {
  _ = try archive.appendBatch(Array(tombstones[first..<min(first + 512, tombstones.count)]))
}
_ = try archive.appendBatch(
  try (0..<3).flatMap { index -> [WorkoutEvent] in
    [
      try event(
        superseded, "survivor-fix-\(index)", "location", "phone", 700 + Double(index),
        [
          "latitude": .number(45.1 + Double(index) * 0.00002), "longitude": .number(7),
          "horizontalAccuracyM": .number(4), "clockEpoch": .string("gps"),
        ]),
      try event(
        superseded, "survivor-frame-\(index)", "telemetry", "cyc", 700 + Double(index),
        [
          "humanPowerW": .number(150), "cadenceRpm": .number(80), "controllerSpeedMps": .number(5),
          "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"),
          "captureSessionID": .string(stable("superseded-session")),
          "observationSequence": .string(String(5000 + index)), "connectionEpoch": .string("link"),
          "clockEpoch": .string("clock"),
        ]),
    ]
  })
let swept = try bounded(superseded)
let gpsJobs = swept.distance.filter { $0.name == "gps:phone" }
let controllerJobs = swept.distance.filter { $0.name == "controller" }
check(
  gpsJobs.map(\.examined) == Array(repeating: 128, count: 18) + [99]
    && controllerJobs.map(\.examined) == Array(repeating: 128, count: 15) + [83],
  "superseded and deleted candidates are examined in bounded jobs: \(gpsJobs.map(\.examined)) \(controllerJobs.map(\.examined))"
)
let perCandidate = (gpsJobs + controllerJobs).map { Double($0.steps) / Double(max(1, $0.examined)) }
check(perCandidate.allSatisfy { $0 < 200 }, "a rejected candidate costs bounded work: \(perCandidate.max() ?? 0)")
let phone = swept.snapshot.sources.first { $0.source == "gps:phone" }
let controller = swept.snapshot.sources.first { $0.source == "controller" }
check(
  phone?.coveredSeconds == 2 && controller?.coveredSeconds == 2 && controller?.distanceMeters == 10,
  "only the surviving originals contribute distance")
check(swept.executor.allSatisfy { $0.reprepares == 0 }, "the superseded build re-plans no statement")
print("Distance build: \(assertions) assertions passed.")
