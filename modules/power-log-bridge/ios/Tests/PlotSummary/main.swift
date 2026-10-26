import Foundation
import PowerLogCore

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-plot-summary-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: db)
let origin = Date(timeIntervalSince1970: 1_780_000_000)
final class PaceClock: @unchecked Sendable {
  private let lock = NSLock()
  private var seconds = 0.0
  func now() -> Double {
    lock.lock()
    defer { lock.unlock() }
    seconds += WorkoutDistanceStore.pacedRebuildSeconds
    return seconds
  }
}
let paceClock = PaceClock()
WorkoutDistanceStore.clockForTesting = paceClock.now
func newRide(gps: Bool = false) throws -> String {
  try archive.create(
    startedAt: origin, indoor: !gps, watchEnabled: true, saveToHealth: true, recordGPS: gps, sampleHz: 8,
    batteryConsumptionSource: "calculated"
  ).id
}
func append(_ events: [WorkoutEvent]) throws {
  for offset in stride(from: 0, to: events.count, by: 256) {
    _ = try db.appendBatch(Array(events[offset..<min(events.count, offset + 256)]))
  }
}
func series(_ result: [String: Any], _ metric: String = "humanPowerW") -> [[String: Any]] {
  (result["series"] as? [String: [[String: Any]]])?[metric] ?? []
}
func canonical(_ result: [String: Any]) throws -> Data {
  try JSONSerialization.data(withJSONObject: result["series"] as Any, options: [.sortedKeys])
}
struct Change {
  let before: [String: Int]
  let after: [String: Int]
  subscript(_ name: String) -> Int { (after[name] ?? 0) - (before[name] ?? 0) }
}
func change(_ reader: MonitorDataStore, _ body: () throws -> Void) rethrows -> Change {
  let before = reader.projectionDiagnostics()
  try body()
  return Change(before: before, after: reader.projectionDiagnostics())
}
func whole(_ id: String, _ metrics: [String], buckets: Int) -> MonitorRequest {
  MonitorRequest(source: "workout", id: id, metrics: metrics, buckets: buckets)
}

// A rising ramp keeps exactly the first and the last original of every run of one bucket and one segment.
func expectedTimes(_ times: [Int64], bucket: (Int64) -> Int64) -> [Int64] {
  var kept = Set<Int64>()
  var run: (bucket: Int64, segment: Int, first: Int64, last: Int64)?
  var segment = 0
  var previous: Int64?
  for time in times {
    if let previous, time - previous >= 6_000_000 { segment += 1 }
    previous = time
    let index = bucket(time)
    if let current = run, current.bucket == index, current.segment == segment {
      run = (index, segment, current.first, time)
    } else {
      if let current = run { kept.formUnion([current.first, current.last]) }
      run = (index, segment, time, time)
    }
  }
  if let current = run { kept.formUnion([current.first, current.last]) }
  return kept.sorted()
}
// Stored times are floored microseconds, so each ramp time uses a double that floors to exactly that microsecond.
func exactSeconds(_ microseconds: Int64) -> Double {
  var seconds = Double(microseconds) / 1_000_000
  while WorkoutDistancePolicy.timeUs(seconds) < microseconds { seconds = seconds.nextUp }
  while WorkoutDistancePolicy.timeUs(seconds) > microseconds { seconds = seconds.nextDown }
  return seconds
}
func rampRide(_ times: [Int64]) throws -> String {
  let id = try newRide()
  try append(
    try times.enumerated().map { index, time in
      let seconds = exactSeconds(time)
      return try WorkoutEvent(
        workoutId: id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(seconds),
        elapsedSeconds: seconds, payload: ["humanPowerW": .number(Double(index)), "cadenceRpm": .number(80)])
    })
  return id
}
func plottedTimes(_ reader: MonitorDataStore, _ request: MonitorRequest) throws -> [Int64] {
  let result = try reader.readPlot(request)
  check(result["status"] as? String == "ok", "a plot of a ramp completes")
  return series(result).map { WorkoutDistancePolicy.timeUs($0["elapsedSeconds"] as! Double) }
}
func exactWidth(_ buckets: Int, _ end: Int64) -> (Int64) -> Int64 {
  { min(Int64(buckets) - 1, $0 * Int64(buckets) / end) }
}
func denseTimes(_ buckets: Int, _ end: Int64) -> [Int64] {
  Array(stride(from: Int64(0), to: end, by: Int(max(1, end / (Int64(buckets) * 8))))) + [end]
}

let fixtures = ProcessInfo.processInfo.environment["POWER_LOG_CONTRACT_FIXTURES"] ?? "tests/fixtures/contract"
let rule =
  try JSONSerialization.jsonObject(
    with: Data(contentsOf: URL(fileURLWithPath: fixtures).appendingPathComponent("inspection/aligned-slots.json")))
  as! [String: Any]
var slotCases = 0
for row in rule["slots"] as! [[String: Any]] where row["invalid"] == nil {
  let start = Int64(row["startUs"] as! String)!
  let end = Int64(row["endUs"] as! String)!
  let buckets = row["buckets"] as! Int
  // A ride's whole range starts at zero and its data end is at least ten seconds.
  guard start == 0, end >= 10_000_000 else { continue }
  let times: [Int64]
  let bucket: (Int64) -> Int64
  if let slot = (row["slotUs"] as? String).flatMap({ Int64($0) }) {
    let step = min(slot / 8, 1_000_000)
    var edges: [Int64] = []
    for index in 0...(end / slot) {
      let edge = index * slot
      edges += [edge - step, edge, edge + step, edge + slot / 2 - step, edge + slot / 2, edge + slot / 2 + step]
    }
    times = edges.filter { $0 >= 0 && $0 < end } + [end]
    bucket = { $0 / slot }
  } else {
    times = denseTimes(buckets, end)
    bucket = exactWidth(buckets, end)
  }
  let plotted = try plottedTimes(
    MonitorDataStore(root: root), whole(try rampRide(times), ["humanPowerW"], buckets: buckets))
  check(plotted == expectedTimes(times, bucket: bucket), "whole-range plot follows aligned-slots \(row["id"]!)")
  slotCases += 1
}
check(slotCases >= 10, "the shared slot fixture drives the whole-range plot rule")
let fillers = MonitorDataStore.metrics.subtracting(["humanPowerW"]).sorted()
var budgetCases = 0
for row in rule["budgets"] as! [[String: Any]] where row["invalid"] == nil {
  let metrics = row["metrics"] as! Int
  let buckets = row["buckets"] as! Int
  guard (1...MonitorDataStore.metrics.count).contains(metrics), buckets > 10 else { continue }
  // Half a second short of one second per bucket keeps exact-width buckets, so the grid shows the bucket count.
  let end = Int64(buckets) * 1_000_000 - 500_000
  let times = denseTimes(buckets, end)
  let request = whole(
    try rampRide(times), ["humanPowerW"] + fillers.prefix(metrics - 1), buckets: row["requested"] as! Int)
  check(
    try plottedTimes(MonitorDataStore(root: root), request)
      == expectedTimes(times, bucket: exactWidth(buckets, end)), "whole-range plot budget follows \(row["id"]!)")
  budgetCases += 1
}
check(budgetCases >= 6, "the shared budget fixture drives the whole-range bucket count")

let growing = try newRide(gps: true)
// Outside running and paused phases every read uses the distance snapshot of its own revision.
try archive.update(id: growing, phase: "preparing")
let session = UUID().uuidString.lowercased()
var sequence = 0
var epoch = "clock-a"
var elapsed = 0.0
func telemetry(_ time: Double, power: Double, voltage: Bool = true) throws -> WorkoutEvent {
  sequence += 1
  var payload: [String: WorkoutJSON] = [
    "humanPowerW": .number(power), "cadenceRpm": .number(80 + Double(sequence % 7)),
    "controllerSpeedMps": .number(5 + Double(sequence % 5) / 4), "batteryCurrentA": .number(Double(sequence % 9)),
    "consumedWh": .number(Double(sequence) / 40), "consumedAh": .number(Double(sequence) / 2_000),
    "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(session),
    "connectionEpoch": .string("connection"), "clockEpoch": .string(epoch),
    "observationSequence": .string(String(sequence)),
  ]
  if voltage { payload["batteryVoltageV"] = .number(52 - Double(sequence % 11) / 10) }
  return try WorkoutEvent(
    workoutId: growing, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
    elapsedSeconds: time, payload: payload)
}
func location(_ time: Double) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: growing, kind: "location", source: "phone", timestamp: origin.addingTimeInterval(time),
    elapsedSeconds: time,
    payload: [
      "latitude": .number(0), "longitude": .number(time * 0.00004), "horizontalAccuracyM": .number(3),
      "speedMps": .number(4 + time.truncatingRemainder(dividingBy: 3)), "speedAccuracyMps": .number(0.5),
      "courseDegrees": .number((time * 47).truncatingRemainder(dividingBy: 360)), "courseAccuracyDegrees": .number(5),
      "altitudeMeters": .number(100 + time.truncatingRemainder(dividingBy: 17)), "verticalAccuracyM": .number(4),
      "clockEpoch": .string(epoch),
    ])
}
func heart(_ time: Double, _ bpm: Double) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: growing, kind: "health", source: "watch", timestamp: origin.addingTimeInterval(time),
    elapsedSeconds: time,
    payload: ["heartRateBpm": .number(bpm), "representation": .string("rawQuantity"), "clockEpoch": .string(epoch)])
}
func lifecycle(_ action: String, _ time: Double) throws {
  try archive.append(
    WorkoutEvent(
      workoutId: growing, kind: "lifecycle", source: "watch", timestamp: origin.addingTimeInterval(time),
      elapsedSeconds: time, payload: ["action": .string(action), "clockEpoch": .string(epoch)]))
}
func ride(to end: Double, missingVoltageEvery: Int = 0) throws {
  var events: [WorkoutEvent] = []
  while elapsed < end {
    let tick = Int((elapsed * 8).rounded())
    let missing = missingVoltageEvery > 0 && tick % missingVoltageEvery == 0
    events.append(try telemetry(elapsed, power: Double(tick * 37 % 400), voltage: !missing))
    if tick % 8 == 0 { events.append(try location(elapsed)) }
    if tick % 40 == 0 { events.append(try heart(elapsed, tick % 400 == 0 ? 0 : 100 + Double(tick % 60))) }
    elapsed += 0.125
  }
  try append(events)
}
let plotted = [
  "humanPowerW", "cadenceRpm", "batteryVoltageV", "heartRateBpm", "courseDegrees", "speedMps", "altitudeMeters",
  "distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters", "calculatedEnergyWh", "controllerEnergyWh",
  "calculatedChargeAh", "controllerChargeAh",
]
let warm = MonitorDataStore(root: root)
func agree(_ message: String) throws -> Change {
  var result: [String: Any] = [:]
  let delta = try change(warm) { result = try warm.readPlot(whole(growing, plotted, buckets: 12)) }
  let fresh = try MonitorDataStore(root: root, projectionCacheBytes: 0).readPlot(whole(growing, plotted, buckets: 12))
  check(
    result["status"] as? String == "ok" && result["revision"] as? String == fresh["revision"] as? String,
    "\(message): the summarized and the fresh read admit one revision")
  check(
    try canonical(result) == canonical(fresh),
    "\(message): summarized whole-range plot equals a fresh aligned reduction")
  return delta
}
func extended(_ delta: Change, _ message: String) {
  check(
    delta["summaryMerges"] == plotted.count && delta["summaryRebuilds"] == 0 && delta["summaryInvalidations"] == 0
      && delta["summaryFallbacks"] == 0 && delta["reducedPoints"] == 0
      && delta.after["summaryEntries"] == plotted.count,
    "\(message): every metric merges its extended summary without reducing originals again")
  check(delta["distanceScannedPoints"] > 0, "\(message): derived summaries extend with new points")
}
try ride(to: 20)
var step = try agree("cold read")
check(step["summaryRebuilds"] == plotted.count, "a cold whole-range read builds one summary per metric")
try ride(to: 40)
extended(try agree("growth across slot doublings"), "growth across slot doublings")
try ride(to: 70, missingVoltageEvery: 5)
extended(try agree("missing and invalid readings"), "missing and invalid readings")
elapsed += 10
try ride(to: 100)
extended(try agree("a gap"), "a gap")
try append([try telemetry(elapsed - 0.125, power: 444)])
step = try agree("a row at the last summarized time")
check(
  step["summaryInvalidations"] == 10 && step["summaryMerges"] == 4,
  "a row at the last summarized time rebuilds the telemetry and distance-derived summaries only")
try ride(to: 130)
extended(try agree("growth after a rebuild"), "growth after a rebuild")
try append([try heart(elapsed - 30, 150)])
step = try agree("a late heart rate")
check(
  step["summaryInvalidations"] == 1 && step["summaryMerges"] == plotted.count - 1,
  "a late heart rate rebuilds only the heart-rate summary")
try lifecycle("pause", elapsed)
elapsed += 2
try lifecycle("resume", elapsed)
try ride(to: elapsed + 20)
step = try agree("a pause")
check(step["summaryInvalidations"] == plotted.count, "a lifecycle change rebuilds every summary")
try phoneFixtureInterruption(
  archive: archive, id: growing, epoch: epoch,
  timing: WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(origin.addingTimeInterval(elapsed)), elapsedSeconds: elapsed,
    timerSeconds: elapsed))
epoch = "clock-b"
try lifecycle("resume", elapsed)
try ride(to: elapsed + 30)
step = try agree("an interruption")
check(step["summaryInvalidations"] == plotted.count, "an interruption rebuilds every summary")
for end in [260.0, 330, 420] {
  try ride(to: end)
  extended(try agree("growth to \(Int(end)) s"), "growth to \(Int(end)) s")
}

final class ResultBox: @unchecked Sendable {
  let lock = NSLock()
  private var value: Result<[String: Any], Error>?
  func put(_ result: Result<[String: Any], Error>) {
    lock.lock()
    defer { lock.unlock() }
    value = result
  }
  func get() throws -> [String: Any] {
    lock.lock()
    defer { lock.unlock() }
    return try value!.get()
  }
}
let earlier = ResultBox()
let delayed = ResultBox()
let earlierDone = DispatchSemaphore(value: 0)
let releaseQueue = DispatchSemaphore(value: 0)
let delayedDone = DispatchSemaphore(value: 0)
check(
  warm.submit(.plot, request: whole(growing, plotted, buckets: 12)) {
    earlier.put($0)
    earlierDone.signal()
    releaseQueue.wait()
  }, "a whole-range plot is admitted")
check(earlierDone.wait(timeout: .now() + 10) == .success, "the admitted plot completes and holds its queue")
check(
  warm.submit(.plot, request: whole(growing, plotted, buckets: 12)) {
    delayed.put($0)
    delayedDone.signal()
  }, "a second whole-range plot is admitted at the same revision")
try ride(to: elapsed + 15)
var stale = Change(before: [:], after: [:])
let newer = try change(warm) { _ = try warm.readPlot(whole(growing, plotted, buckets: 12)) }
check(newer["distanceScannedPoints"] > 0, "the newer read feeds new derived points into the summaries")
stale = try change(warm) {
  releaseQueue.signal()
  check(delayedDone.wait(timeout: .now() + 10) == .success, "the queued plot completes")
}
check(
  try delayed.get()["revision"] as? String == earlier.get()["revision"] as? String
    && canonical(try delayed.get()) == canonical(try earlier.get()),
  "a read admitted before newer summarized originals returns its own revision exactly")
check(
  stale["summaryFallbacks"] == plotted.count && stale.after["summaryEntries"] == plotted.count,
  "summaries fed past an older read's revision and distance snapshot serve it from originals and are kept")
epoch = "clock-c"
try ride(to: elapsed + 30)
extended(try agree("a new clock epoch"), "a new clock epoch")
try ride(to: 1_200)
let widening = MonitorDataStore(root: root)
let wideningMetrics = ["humanPowerW", "heartRateBpm", "distanceMeters", "controllerEnergyWh"]
for (index, buckets) in [161, 339, 512].enumerated() {
  var result: [String: Any] = [:]
  let read = try change(widening) { result = try widening.readPlot(whole(growing, wideningMetrics, buckets: buckets)) }
  let fresh = try MonitorDataStore(root: root, projectionCacheBytes: 0).readPlot(
    whole(growing, wideningMetrics, buckets: buckets))
  check(
    try canonical(result) == canonical(fresh),
    "a whole-range plot of \(buckets) buckets equals a fresh aligned reduction")
  check(
    index == 0
      || (read["summaryMerges"] == wideningMetrics.count && read["summaryRebuilds"] == 0
        && read["summaryInvalidations"] == 0),
    "a whole-range plot of \(buckets) buckets merges summaries built for fewer buckets without rebuilding them")
}

let wide = try newRide(gps: true)
let wideConnection = UUID().uuidString.lowercased()
let wideMetrics = [
  "humanPowerW", "motorInputPowerW", "pedalTorqueNm", "cadenceRpm", "heartRateBpm", "batteryVoltageV",
  "batteryCurrentA", "consumedAh", "consumedWh", "controllerEnergyWh", "calculatedEnergyWh", "controllerChargeAh",
  "calculatedChargeAh", "motorCurrentA", "motorTempC", "controllerTempC", "motorRpm", "throttleVoltageV", "speedMps",
  "controllerSpeedMps", "speedRaw", "distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters",
  "altitudeMeters",
]
var wideSecond = 0
func wideSeconds(_ count: Int) throws -> (telemetry: Int, locations: Int, hearts: Int) {
  var events: [WorkoutEvent] = []
  var hearts = 0
  for second in wideSecond..<(wideSecond + count) {
    for frame in (second * 8)..<((second + 1) * 8) {
      let time = Double(frame) / 8
      events.append(
        try WorkoutEvent(
          workoutId: wide, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
          elapsedSeconds: time,
          payload: [
            "humanPowerW": .number(Double(frame % 300) + 0.125), "cadenceRpm": .number(80 + Double(frame % 4)),
            "batteryCurrentA": .number(Double(frame % 64) / 8),
            "batteryVoltageV": .number(52.8 - Double(frame) / 100_000),
            "motorInputPowerW": .number(211.2), "controllerSpeedMps": .number(5 + Double(frame % 16) / 8),
            "speedRaw": .number(500 + Double(frame % 16)), "pedalTorqueNm": .number(Double(frame % 40)),
            "motorCurrentA": .number(4), "motorTempC": .number(40), "controllerTempC": .number(35),
            "motorRpm": .number(180), "throttleVoltageV": .number(0.8), "consumedAh": .number(Double(frame) / 28_800),
            "consumedWh": .number(Double(frame) / 545), "controllerModel": .string("X6"),
            "controllerProtocol": .string("5.3"), "captureSessionID": .string(wideConnection),
            "connectionEpoch": .string(wideConnection), "clockEpoch": .string("wide"),
            "observationSequence": .string(String(frame + 1)),
          ]))
    }
    let time = Double(second + 1) - 0.125
    events.append(
      try WorkoutEvent(
        workoutId: wide, kind: "location", source: "phone", timestamp: origin.addingTimeInterval(time),
        elapsedSeconds: time,
        payload: [
          "latitude": .number(0), "longitude": .number(time * 0.000045), "horizontalAccuracyM": .number(3),
          "speedMps": .number(5), "speedAccuracyMps": .number(0.5), "altitudeMeters": .number(100),
          "verticalAccuracyM": .number(4),
        ]))
    if second % 5 == 4 {
      hearts += 1
      events.append(
        try WorkoutEvent(
          workoutId: wide, kind: "health", source: "watch", timestamp: origin.addingTimeInterval(time),
          elapsedSeconds: time,
          payload: ["heartRateBpm": .number(110 + Double(second % 30)), "representation": .string("rawQuantity")]))
    }
  }
  try append(events)
  for second in wideSecond..<(wideSecond + count) where second % 5 == 4 {
    let time = Double(second + 1) - 0.125
    _ = try archive.update(
      id: wide,
      ownerTiming: WorkoutOwnerTiming(
        timestamp: WorkoutCoding.timestamp(origin.addingTimeInterval(time)), elapsedSeconds: time, timerSeconds: time),
      elapsedSeconds: time, onlyIfChanged: true)
  }
  wideSecond += count
  return (count * 8, count, hearts)
}
let telemetryChannels = 15
let locationChannels = 2
let wideReader = MonitorDataStore(root: root)
func wideRead() throws -> [String: Any] { try wideReader.readPlot(whole(wide, wideMetrics, buckets: 400)) }
_ = try wideSeconds(200)
_ = try wideRead()
check(
  wideReader.projectionDiagnostics()["summaryEntries"] == wideMetrics.count,
  "a 25-metric whole-ride view keeps one summary per metric")
for _ in 0..<60 {
  let added = try wideSeconds(1)
  let read = try change(wideReader) { _ = try wideRead() }
  check(
    read["summaryMerges"] == wideMetrics.count && read["summaryRebuilds"] == 0 && read["summaryInvalidations"] == 0
      && read["summaryFallbacks"] == 0 && read["reducedPoints"] == 0,
    "a steady 25-metric whole-ride read merges every summary without reducing originals again")
  check(
    read["scannedPoints"] == added.telemetry * telemetryChannels + added.locations * locationChannels + added.hearts,
    "a steady 25-metric whole-ride read scans only the new originals of each channel")
  check(
    read["distanceScannedPoints"] <= 64 && read["hydratedPoints"] <= wideMetrics.count * 8
      && read["distanceHydratedPoints"] <= 7 * 8,
    "a steady whole-ride read reads only new derived points and hydrates only new plotted points")
}
let beforeGap = try archive.revision(id: wide)
let gap = try wideSeconds(70)
check(
  try archive.revision(id: wide) - beforeGap > Int64(PowerLogStorageLimits.changeRows),
  "the unread interval spans more revisions than the change journal keeps")
var unlocked: [String: Any] = [:]
let unlock = try change(wideReader) { unlocked = try wideRead() }
check(
  unlock["summaryMerges"] == wideMetrics.count && unlock["summaryRebuilds"] == 0
    && unlock["summaryInvalidations"] == 0 && unlock["reducedPoints"] == 0
    && unlock["scannedPoints"] == gap.telemetry * telemetryChannels + gap.locations * locationChannels + gap.hearts,
  "after more unread revisions than the change journal keeps, every summary extends with its new originals only")
check(
  try canonical(unlocked)
    == canonical(
      try MonitorDataStore(root: root, projectionCacheBytes: 0).readPlot(whole(wide, wideMetrics, buckets: 400))),
  "the extended summaries equal a fresh aligned reduction after the unread interval")

let long = try newRide()
let longMetrics = ["humanPowerW", "cadenceRpm", "batteryVoltageV", "motorTempC"]
var longFrames = 0
func longHours(_ hours: Int) throws {
  let end = longFrames + hours * 720
  try append(
    try (longFrames..<end).map { index in
      let time = Double(index) * 5
      return try WorkoutEvent(
        workoutId: long, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
        elapsedSeconds: time,
        payload: [
          "humanPowerW": .number(Double(index * 31 % 500)), "cadenceRpm": .number(Double(60 + index % 40)),
          "batteryVoltageV": .number(54 - Double(index) / 1_000), "motorTempC": .number(Double(30 + index % 25)),
        ])
    })
  longFrames = end
}
let longReader = MonitorDataStore(root: root)
var hourly: [[String: Int]] = []
for _ in 0..<6 {
  try longHours(1)
  _ = try longReader.readPlot(whole(long, longMetrics, buckets: 64))
  hourly.append(longReader.projectionDiagnostics())
}
check(
  hourly.allSatisfy {
    $0["summaryEntries"] == longMetrics.count && $0["points"] == 0
      && ($0["summaryPoints"] ?? .max) <= longMetrics.count * (4 * (512 + 2) + 64)
      && ($0["nativeAdapterBytes"] ?? .max) <= ($0["nativeAdapterByteLimit"] ?? 0)
  }, "a six-hour whole-range view keeps summaries bounded by their slot count and no projection")
let summaryBytes = (hourly[5]["summaryBytes"] ?? 0) / longMetrics.count
// The plot workspace holds half a budget plus two budgets, here one and a half summaries.
let tight = MonitorDataStore(root: root, projectionCacheBytes: summaryBytes * 3 / 5)
for _ in 0..<2 {
  var result: [String: Any] = [:]
  let read = try change(tight) { result = try tight.readPlot(whole(long, ["cadenceRpm", "humanPowerW"], buckets: 64)) }
  check(
    read["evictions"] > 0 && read.after["summaryEntries"] == 1
      && (read.after["nativeAdapterBytes"] ?? .max) <= (read.after["nativeAdapterByteLimit"] ?? 0),
    "a plot workspace that holds one summary evicts the least recently used one instead of exceeding its budget")
  check(
    try canonical(result)
      == canonical(
        try MonitorDataStore(root: root, projectionCacheBytes: 0).readPlot(
          whole(long, ["cadenceRpm", "humanPowerW"], buckets: 64))),
    "whole-range plots under summary eviction equal a fresh aligned reduction")
}

let zoomedTimes = denseTimes(512, 600_000_000)
let zoomedRamp = try rampRide(zoomedTimes)
let zoomedRequest = MonitorRequest(
  source: "workout", id: zoomedRamp, startSeconds: 0, endSeconds: 599, metrics: ["humanPowerW"], buckets: 512)
let zoomedReader = MonitorDataStore(root: root)
let zoomed = try change(zoomedReader) {
  check(
    try plottedTimes(zoomedReader, zoomedRequest)
      == expectedTimes(zoomedTimes.filter { $0 <= 599_000_000 }, bucket: exactWidth(512, 599_000_000))
      + [zoomedTimes.first { $0 > 599_000_000 }!],
    "a zoomed plot keeps exact-width buckets and its successor")
}
check(
  zoomed["summaryRebuilds"] == 0 && zoomed.after["summaryEntries"] == 0 && zoomed.after["entries"] == 1,
  "a zoomed plot keeps a projection and no slot summary")
let pan = try change(zoomedReader) {
  var panned = zoomedRequest
  panned.startSeconds = 100.5
  panned.endSeconds = 400.5
  let plotted = try zoomedReader.readPlot(panned)
  check(
    try canonical(plotted) == canonical(try MonitorDataStore(root: root, projectionCacheBytes: 0).readPlot(panned)),
    "a zoomed plot from the projection equals a streamed one")
}
check(pan["scannedPoints"] == 0 && pan["projectionHits"] == 1, "a zoomed pan reuses its projection")

let replacedRide = try newRide()
func replacedFrame(_ index: Int, voltage: Double?, extra: [String: WorkoutJSON] = [:]) throws -> WorkoutEvent {
  let seconds = Double(index) / 8
  var payload: [String: WorkoutJSON] = ["humanPowerW": .number(Double(index % 200)), "cadenceRpm": .number(80)]
  if let voltage { payload["batteryVoltageV"] = .number(voltage) }
  payload.merge(extra) { _, new in new }
  return try WorkoutEvent(
    workoutId: replacedRide, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(seconds),
    elapsedSeconds: seconds, payload: payload)
}
let voltageSpikes: [Int: Double] = [100: 30.5, 200: 70.25]
let replacedOriginals = try (0..<4_000).map { try replacedFrame($0, voltage: voltageSpikes[$0] ?? 52) }
try append(replacedOriginals)
var replacedFrames = replacedOriginals.count
let replacedReader = MonitorDataStore(root: root)
let replacedRequest = whole(replacedRide, ["batteryVoltageV", "humanPowerW"], buckets: 64)
func replacedAgree(_ message: String) throws -> (Change, [[String: Any]]) {
  var result: [String: Any] = [:]
  let delta = try change(replacedReader) { result = try replacedReader.readPlot(replacedRequest) }
  let fresh = try MonitorDataStore(root: root, projectionCacheBytes: 0).readPlot(replacedRequest)
  check(try canonical(result) == canonical(fresh), "\(message): whole-range plot equals a fresh aligned reduction")
  return (delta, series(result, "batteryVoltageV"))
}
func holds(_ points: [[String: Any]], _ value: Double) -> Bool { points.contains { $0["value"] as? Double == value } }
var (replacedRead, voltages) = try replacedAgree("before corrections")
check(
  replacedRead["summaryRebuilds"] == 2 && holds(voltages, 30.5) && holds(voltages, 70.25),
  "the voltage summary holds the originals that later readings without voltage replace")
func supersede(_ index: Int, deleted: Bool = false) throws {
  var extra: [String: WorkoutJSON] = ["supersedesEventId": .string(replacedOriginals[index].eventId)]
  if deleted { extra["deleted"] = .bool(true) }
  try append([try replacedFrame(replacedFrames, voltage: nil, extra: extra)])
  replacedFrames += 1
}
try supersede(100)
(replacedRead, voltages) = try replacedAgree("a correction without voltage")
check(
  replacedRead["summaryInvalidations"] == 2 && replacedRead["summaryMerges"] == 0 && !holds(voltages, 30.5),
  "a correction without voltage rebuilds the voltage summary without the original it replaces")
try supersede(200, deleted: true)
(replacedRead, voltages) = try replacedAgree("a deletion without voltage")
check(
  replacedRead["summaryInvalidations"] == 2 && replacedRead["summaryMerges"] == 0 && !holds(voltages, 70.25),
  "a deletion without voltage rebuilds the voltage summary without the original it deletes")

// Eight-point runs 7.3 s apart straddle every multiple of 8 s, four points on each side. The 512-slot summary keeps
// all eight points of a run across one of its 64 s slot edges and the 64-bucket plot only four, so the summary
// outgrows its point budget while the plot fits.
let runShape: [(offset: Double, power: Double)] = [
  (-0.35, 50), (-0.25, 10), (-0.15, 90), (-0.05, 40), (0.05, 60), (0.15, 5), (0.25, 95), (0.35, 30),
]
let exhaustedRide = try newRide()
var exhaustedRuns = 0
func appendRuns(_ count: Int) throws {
  var events: [WorkoutEvent] = []
  for run in (exhaustedRuns + 1)...(exhaustedRuns + count) {
    for point in runShape {
      let seconds = Double(run * 8) + point.offset
      events.append(
        try WorkoutEvent(
          workoutId: exhaustedRide, kind: "telemetry", source: "controller",
          timestamp: origin.addingTimeInterval(seconds), elapsedSeconds: seconds,
          payload: ["humanPowerW": .number(point.power), "cadenceRpm": .number(80)]))
    }
  }
  try append(events)
  exhaustedRuns += count
}
let exhaustedReader = MonitorDataStore(root: root)
let exhaustedRequest = whole(exhaustedRide, ["humanPowerW"], buckets: 64)
func exhaustedAgree(_ message: String) throws -> Change {
  var result: [String: Any] = [:]
  let delta = try change(exhaustedReader) { result = try exhaustedReader.readPlot(exhaustedRequest) }
  let fresh = try MonitorDataStore(root: root, projectionCacheBytes: 0).readPlot(exhaustedRequest)
  check(
    try result["status"] as? String == "ok" && canonical(result) == canonical(fresh),
    "\(message): whole-range plot equals a fresh aligned reduction")
  return delta
}
try appendRuns(3_000)
var exhausted = try exhaustedAgree("a summary within its point budget")
check(
  exhausted["summaryRebuilds"] == 1 && exhausted.after["summaryEntries"] == 1
    && (exhausted.after["summaryExhausted"] ?? 0) == 0,
  "a channel within the summary point budget keeps its summary")
try appendRuns(900)
exhausted = try exhaustedAgree("an extension past the summary point budget")
check(
  exhausted["summaryRebuilds"] == 0 && exhausted["summaryFallbacks"] == 1 && exhausted.after["summaryEntries"] == 0
    && exhausted.after["summaryExhausted"] == 1,
  "an extension past the summary point budget marks the channel exhausted without rebuilding it")
for step in 0..<2 {
  try appendRuns(8)
  exhausted = try exhaustedAgree("exhausted read \(step)")
  check(
    exhausted["summaryRebuilds"] == 0 && exhausted["summaryMerges"] == 0 && exhausted["summaryFallbacks"] == 1
      && exhausted.after["summaryExhausted"] == 1,
    "an exhausted channel is read in full without building its summary again")
}
let exhaustedTarget = try db.read {
  try $0.rows(
    "SELECT event_id FROM collection_memberships WHERE collection_id=? ORDER BY elapsed_seconds LIMIT 1",
    [.text(exhaustedRide)], limit: 1
  ).first!.string("event_id")!
}
let correctionTime = Double((exhaustedRuns + 1) * 8)
try append([
  try WorkoutEvent(
    workoutId: exhaustedRide, kind: "telemetry", source: "controller",
    timestamp: origin.addingTimeInterval(correctionTime), elapsedSeconds: correctionTime,
    payload: [
      "humanPowerW": .number(70), "cadenceRpm": .number(80), "supersedesEventId": .string(exhaustedTarget),
    ])
])
exhausted = try exhaustedAgree("a correction of an exhausted channel")
check(
  exhausted["summaryRebuilds"] == 1 && exhausted.after["summaryExhausted"] == 1,
  "a correction clears the exhausted mark, so the channel attempts its summary once more")
exhausted = try exhaustedAgree("a read after the renewed attempt")
check(
  exhausted["summaryRebuilds"] == 0 && exhausted["summaryFallbacks"] == 1,
  "the renewed exhausted mark serves the next read in full without another attempt")
check(exhaustedReader.discardWorkout(id: exhaustedRide), "an idle reader discards the ride")
check(
  exhaustedReader.projectionDiagnostics()["summaryExhausted"] == 0, "discarding a ride drops its exhausted marks")
print("Whole-range slot summaries: \(assertions) assertions passed")
