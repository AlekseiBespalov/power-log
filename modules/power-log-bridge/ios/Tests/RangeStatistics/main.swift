import Foundation
import PowerLogCore

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-range-statistics-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: db)
let origin = Date(timeIntervalSince1970: 1_780_000_000)
let reader = MonitorDataStore(root: root)
let direct = MonitorDataStore(root: root, projectionCacheBytes: 0)
let metrics = ["batteryVoltageV", "heartRateBpm", "humanPowerW", "throttleVoltageV"]
let lagSeconds = Double(MonitorDataStore.statisticsPrefixLagUs) / 1_000_000
let blockSeconds = Double(MonitorDataStore.statisticsPrefixBlockUs) / 1_000_000

struct Change {
  let before: [String: Int]
  let after: [String: Int]
  subscript(_ name: String) -> Int { (after[name] ?? 0) - (before[name] ?? 0) }
}
func statistics(
  _ id: String, end: Double, endpoints: Bool = false
) throws -> (prefixed: [String: Any], direct: [String: Any], change: Change) {
  var request = MonitorRequest(source: "workout", id: id, startSeconds: 0, endSeconds: end, metrics: metrics)
  request.includeEndpoints = endpoints
  let before = reader.projectionDiagnostics()
  let prefixed = try reader.rangeStats(request)
  let change = Change(before: before, after: reader.projectionDiagnostics())
  return (prefixed, try direct.rangeStats(request), change)
}
func same(_ a: [String: Any], _ b: [String: Any]) -> Bool {
  a["status"] as? String == "ok" && b["status"] as? String == "ok"
    && NSDictionary(dictionary: a["statistics"] as? [String: Any] ?? [:]).isEqual(
      to: b["statistics"] as? [String: Any] ?? [:])
    && NSDictionary(dictionary: a["endpoints"] as? [String: Any] ?? [:]).isEqual(
      to: b["endpoints"] as? [String: Any] ?? [:])
}

let ride = try archive.create(
  startedAt: origin, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: false, sampleHz: 8
).id
try archive.append(
  WorkoutEvent(
    workoutId: ride, kind: "lifecycle", source: "phone", timestamp: origin, elapsedSeconds: 0,
    payload: ["action": .string("start")]))
try archive.update(id: ride, phase: "running")
let connection = "a1b2c3d4-0000-4000-8000-00000000c0de"
var epoch = "a1b2c3d4-0000-4000-8000-000000000001"
var sequence = 0
// Coincident minima tie, so the stable observation identity picks the reported minimum.
let tiedLow = "00000000-0000-4000-8000-000000000001"
let tiedHigh = "ffffffff-ffff-4fff-8fff-fffffffffff1"
var frameIDs: [Int: String] = [:]
func append(_ events: [WorkoutEvent]) throws {
  for offset in stride(from: 0, to: events.count, by: 256) {
    _ = try db.appendBatch(Array(events[offset..<min(events.count, offset + 256)]))
  }
}
func frame(
  _ index: Int, voltage: Double? = nil, id: String = UUID().uuidString.lowercased(), extra: [String: WorkoutJSON] = [:]
) throws -> WorkoutEvent {
  let time = Double(index) / 8
  sequence += 1
  var payload: [String: WorkoutJSON] = [
    "humanPowerW": .number(index % 97 == 5 ? -5 : Double(index % 300) + 0.5),
    "throttleVoltageV": .number(index % 120 < 100 ? 0.8 : 1.25), "cadenceRpm": .number(80),
    "clockEpoch": .string(epoch), "connectionEpoch": .string(connection),
    "observationSequence": .string(String(sequence)),
  ]
  if index % 31 != 7 { payload["batteryVoltageV"] = .number(voltage ?? 52 - Double((index / 50) % 7) / 2) }
  for (key, value) in extra { payload[key] = value }
  frameIDs[index] = id
  return try WorkoutEvent(
    workoutId: ride, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
    elapsedSeconds: time, payload: payload, eventId: id)
}
func heartRate(_ time: Double) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: ride, kind: "health", source: "watch", timestamp: origin.addingTimeInterval(time),
    elapsedSeconds: time,
    payload: [
      "heartRateBpm": .number(Int(time) % 45 == 4 ? 0 : 100 + Double(Int(time) % 40)),
      "representation": .string("rawQuantity"),
    ])
}
func lifecycle(_ time: Double, _ action: String) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: ride, kind: "lifecycle", source: "phone", timestamp: origin.addingTimeInterval(time),
    elapsedSeconds: time, payload: ["action": .string(action)])
}

func interruption(_ time: Double) throws {
  let timing = try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(origin.addingTimeInterval(time)), elapsedSeconds: time, timerSeconds: time)
  try phoneFixtureInterruption(archive: archive, id: ride, epoch: epoch, timing: timing)
}

let step = 10.0
var lastFrame = -1
var steady = 0
var prefixed = false
var lateLifecycle = 0
for stepIndex in 0..<42 {
  let stepEnd = Double(stepIndex + 1) * step
  var events: [WorkoutEvent] = []
  var semantic = false
  while Double(lastFrame + 1) / 8 < stepEnd {
    lastFrame += 1
    let time = Double(lastFrame) / 8
    if time >= 150 && time < 156 { continue }
    if lastFrame % 400 == 123 {
      events.append(try frame(lastFrame, voltage: 40, id: lastFrame == 123 ? tiedHigh : UUID().uuidString.lowercased()))
      events.append(try frame(lastFrame, voltage: 40, id: lastFrame == 123 ? tiedLow : UUID().uuidString.lowercased()))
    } else {
      events.append(try frame(lastFrame))
    }
    if time == 262 {
      try append(events)
      events = []
      try interruption(time)
      epoch = "a1b2c3d4-0000-4000-8000-000000000002"
      semantic = true
    }
  }
  try append(events)
  for sample in stride(from: stepEnd - 60, to: stepEnd - 50, by: 5) where sample >= 0 {
    try archive.append(heartRate(sample + 4))
  }
  let lifecycleEvents = [(200.0, "pause"), (212.0, "resume"), (270.0, "resume")]
  for (time, action) in lifecycleEvents where time < stepEnd && time >= stepEnd - step {
    try archive.append(lifecycle(time, action))
    semantic = true
  }
  switch stepEnd {
  case 300:
    try archive.append(try frame(400, voltage: 39.5, extra: ["supersedesEventId": .string(frameIDs[400]!)]))
    semantic = true
  case 320:
    try archive.append(
      try frame(480, extra: ["supersedesEventId": .string(frameIDs[480]!), "deleted": .bool(true)]))
    semantic = true
  case 380:
    try archive.append(lifecycle(120, "pause"))
    try archive.append(lifecycle(125, "resume"))
    lateLifecycle += 1
  case 400:
    try interruption(230)
    lateLifecycle += 1
  default:
    break
  }
  let lateHeartRate = stepEnd == 340
  if lateHeartRate { try archive.append(heartRate(101.5)) }
  let end = Double(lastFrame) / 8
  let read = try statistics(ride, end: end, endpoints: stepIndex % 3 == 0)
  check(
    same(read.prefixed, read.direct),
    "after ride second \(stepEnd) the prefix-backed whole-range statistics equal a direct reduction")
  guard end >= lagSeconds + blockSeconds else { continue }
  check(
    read.change["statisticsPrefixHits"] + read.change["statisticsPrefixBuilds"] == metrics.count,
    "a whole-range statistics read at ride second \(stepEnd) is answered from every channel's prefix")
  if !prefixed {
    prefixed = true
    check(read.change["statisticsPrefixBuilds"] == metrics.count, "the first long enough read builds every prefix")
  } else if [380, 400].contains(stepEnd) {
    check(
      read.change["statisticsPrefixInvalidations"] == metrics.count,
      "a lifecycle change committed before the prefix end rebuilds every prefix")
  } else if lateHeartRate {
    check(
      read.change["statisticsPrefixInvalidations"] == 1 && read.change["statisticsPrefixBuilds"] == 1,
      "a heart rate committed before the prefix end rebuilds only the heart rate prefix")
  } else if semantic {
    check(read.change["statisticsPrefixInvalidations"] > 0, "a correction or lifecycle change rebuilds prefixes")
  } else {
    steady += 1
    check(
      read.change["statisticsPrefixHits"] == metrics.count
        && read.change["statisticsScannedPoints"] <= metrics.count * Int((lagSeconds + blockSeconds + step) * 8 + 4),
      "a steady whole-range statistics read at ride second \(stepEnd) scans only the rows after its prefix")
    check(
      read.change["statisticsPrefixFoldedPoints"] <= metrics.count * Int((blockSeconds + step) * 8 + 4),
      "a steady whole-range statistics read at ride second \(stepEnd) folds only newly settled rows")
  }
}
check(lateLifecycle == 2 && steady >= 20, "the live ride reaches its late changes and steady reads")

let minimum =
  (try reader.rangeStats(
    MonitorRequest(source: "workout", id: ride, startSeconds: 0, endSeconds: Double(lastFrame) / 8, metrics: metrics)
  )["statistics"] as? [String: [String: Any]])?["batteryVoltageV"]?["min"] as? [String: Any]
check(
  minimum?["value"] as? Double == 39.5,
  "a corrected original before the prefix end is the whole-range minimum after the rebuild")

var revisions = 0
for _ in 0..<600 {
  lastFrame += 1
  try archive.append(try frame(lastFrame))
  revisions += 1
}
let unattended = try statistics(ride, end: Double(lastFrame) / 8)
check(
  revisions > 512 && same(unattended.prefixed, unattended.direct)
    && unattended.change["statisticsPrefixHits"] == metrics.count
    && unattended.change["statisticsPrefixBuilds"] == 0 && unattended.change["statisticsPrefixInvalidations"] == 0,
  "more append-only revisions than the change journal keeps still extend every statistics prefix")

let tiedRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8
).id
func tiedFrames(_ range: Range<Int>) throws -> [WorkoutEvent] {
  try range.map { index in
    try WorkoutEvent(
      workoutId: tiedRide, kind: "telemetry", source: "controller",
      timestamp: origin.addingTimeInterval(Double(index) / 8), elapsedSeconds: Double(index) / 8,
      payload: [
        "batteryVoltageV": .number(48), "throttleVoltageV": .number(0.8), "humanPowerW": .number(100),
        "cadenceRpm": .number(80),
      ])
  }
}
try append(tiedFrames(0..<1_200))
var tiedRequest = MonitorRequest(
  source: "workout", id: tiedRide, startSeconds: 0, endSeconds: 149.875, metrics: ["batteryVoltageV", "humanPowerW"])
_ = try reader.rangeStats(tiedRequest)
try append(tiedFrames(1_200..<1_600))
tiedRequest.endSeconds = 199.875
let tiedBefore = reader.projectionDiagnostics()
let tied = try reader.rangeStats(tiedRequest)
let tiedChange = Change(before: tiedBefore, after: reader.projectionDiagnostics())
check(
  same(tied, try direct.rangeStats(tiedRequest)) && tiedChange["statisticsPrefixHits"] == 2
    && tiedChange["statisticsPrefixFoldedPoints"] > 0,
  "a constant channel keeps its first original as both extrema across prefix reuse")

check(
  direct.projectionDiagnostics()["statisticsPrefixFallbacks"] ?? 0 > 0
    && direct.projectionDiagnostics()["statisticsPrefixBuilds"] == nil,
  "a statistics workspace without room for a prefix answers with the direct reduction")

let crowdedRide = try archive.create(
  startedAt: origin, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8
).id
func crowdedEvent(_ kind: String, _ time: Double, _ payload: [String: WorkoutJSON]) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: crowdedRide, kind: kind, source: kind == "lifecycle" ? "phone" : "controller",
    timestamp: origin.addingTimeInterval(time), elapsedSeconds: time, payload: payload)
}
var crowded: [WorkoutEvent] = []
// The pauses make each prefix's timeline signature much larger than its identity lookup reserve.
for pause in 0..<2_000 {
  let time = 0.5 + Double(pause) * 0.000_128
  crowded.append(try crowdedEvent("lifecycle", time, ["action": .string("pause")]))
  crowded.append(try crowdedEvent("lifecycle", time + 0.000_064, ["action": .string("resume")]))
}
for index in 0..<1_680 {
  crowded.append(
    try crowdedEvent(
      "telemetry", Double(index) / 8,
      [
        "humanPowerW": .number(Double(index % 300) + 0.5), "cadenceRpm": .number(Double(60 + index % 40)),
        "motorInputPowerW": .number(Double(index * 7 % 500)),
        "batteryVoltageV": .number(52 - Double(index % 20) / 10),
      ]))
  if index == 160 {
    for _ in 0..<2 {
      crowded.append(
        try crowdedEvent(
          "telemetry", 20.0625,
          ["humanPowerW": .number(150.5), "cadenceRpm": .number(80), "batteryVoltageV": .number(30)]))
    }
  }
}
try append(crowded)
func crowdedRead(_ reader: MonitorDataStore, _ metric: String) throws -> Change {
  let request = MonitorRequest(
    source: "workout", id: crowdedRide, startSeconds: 0, endSeconds: 1_679.0 / 8, metrics: [metric])
  let before = reader.projectionDiagnostics()
  let read = try reader.rangeStats(request)
  let change = Change(before: before, after: reader.projectionDiagnostics())
  check(same(read, try direct.rangeStats(request)), "a budgeted whole-range \(metric) read equals a direct reduction")
  return change
}

let tieFree = ["humanPowerW", "cadenceRpm", "motorInputPowerW"]
let roomy = MonitorDataStore(root: root)
let prefixBytes = try tieFree.map { try crowdedRead(roomy, $0)["nativeAdapterBytes"] }
check(
  roomy.projectionDiagnostics()["statisticsPrefixBuilds"] == tieFree.count && Set(prefixBytes).count == 1,
  "the tie-free channels of one ride retain equally large statistics prefixes")
// The statistics workspace receives half of the projection budget.
let crowdedLimit = prefixBytes[0] * 5 / 2
let tight = MonitorDataStore(root: root, projectionCacheBytes: crowdedLimit * 2)
let visits: [(metric: String, reused: Bool, evictions: Int)] = [
  ("humanPowerW", false, 0), ("cadenceRpm", false, 0), ("humanPowerW", true, 0),
  ("motorInputPowerW", false, 1), ("motorInputPowerW", true, 0), ("humanPowerW", true, 0),
  ("cadenceRpm", false, 1), ("humanPowerW", true, 0), ("motorInputPowerW", false, 1),
]
for (index, visit) in visits.enumerated() {
  let read = try crowdedRead(tight, visit.metric)
  check(
    read["statisticsPrefixFallbacks"] == 0 && read["evictions"] == visit.evictions
      && read[visit.reused ? "statisticsPrefixHits" : "statisticsPrefixBuilds"] == 1,
    "read \(index + 1) of \(visit.metric) evicts only the least recently used statistics prefix and never falls back")
}
check(
  (tight.projectionDiagnostics()["peakNativeAdapterBytes"] ?? .max) <= crowdedLimit,
  "evicting statistics prefixes keeps the statistics workspace within its byte limit")

let grownBytes = try crowdedRead(MonitorDataStore(root: root), "batteryVoltageV")["nativeAdapterBytes"]
check(grownBytes > prefixBytes[0], "coincident equal minima grow a statistics prefix's identity lookup")
let snug = MonitorDataStore(root: root, projectionCacheBytes: (grownBytes - 1) * 2)
check(
  try crowdedRead(snug, "batteryVoltageV")["statisticsPrefixFallbacks"] == 1
    && (snug.projectionDiagnostics()["peakNativeAdapterBytes"] ?? .max) < grownBytes,
  "a statistics prefix is admitted only with room for its identity lookup to fill")

print("Whole-range statistics prefixes: \(assertions) assertions passed")
