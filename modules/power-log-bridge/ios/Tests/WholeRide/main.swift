import Darwin
import Foundation
import PowerLogCore

// A live ride on a Whole ride view with every chart selected: raw 8 Hz capture committed per sample, 1 Hz GPS, Watch
// heart rate and the engine's 5 s timing commit. Plot and Wh/km chart reads follow the read interval; statistics and
// Wh/km totals follow the live energy cadence.
setbuf(stdout, nil)
let environment = ProcessInfo.processInfo.environment
let minutes = Int(environment["POWER_LOG_WHOLE_RIDE_MINUTES"] ?? "") ?? 30
let plots = environment["POWER_LOG_WHOLE_RIDE_PLOTS"] != "0"
guard let readEvery = Int(environment["POWER_LOG_WHOLE_RIDE_READ_EVERY"] ?? "1"), readEvery > 0 else {
  fatalError("POWER_LOG_WHOLE_RIDE_READ_EVERY must be a positive number of seconds")
}
let statisticsMode = environment["POWER_LOG_WHOLE_RIDE_STATS"] ?? "all"
guard ["all", "live"].contains(statisticsMode) else { fatalError("POWER_LOG_WHOLE_RIDE_STATS must be all or live") }
let lock: Range<Int>? = environment["POWER_LOG_WHOLE_RIDE_LOCK"].map { value in
  let parts = value.split(separator: ":").map { Int($0) }
  guard parts.count == 2, let start = parts[0], let length = parts[1], start >= 0, length > 0 else {
    fatalError("POWER_LOG_WHOLE_RIDE_LOCK must be start:minutes")
  }
  return (start * 60)..<((start + length) * 60)
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-whole-ride-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: db)
let monitor = MonitorDataStore(root: root)
let origin = Date(timeIntervalSince1970: floor(Date().timeIntervalSince1970) - Double(minutes * 60 + 60))
let ride = try archive.create(
  startedAt: origin, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true, sampleHz: 8)
try archive.append(
  WorkoutEvent(
    workoutId: ride.id, kind: "lifecycle", source: "phone", timestamp: origin, elapsedSeconds: 0,
    payload: ["action": .string("start")]))
try archive.update(id: ride.id, phase: "running")
let epoch = UUID().uuidString.lowercased()
let connection = UUID().uuidString.lowercased()
let allMetrics = [
  "humanPowerW", "motorInputPowerW", "pedalTorqueNm", "cadenceRpm", "heartRateBpm", "batteryVoltageV",
  "batteryCurrentA", "consumedAh", "consumedWh", "controllerEnergyWh", "calculatedEnergyWh", "controllerChargeAh",
  "calculatedChargeAh", "motorCurrentA", "motorTempC", "controllerTempC", "motorRpm", "throttleVoltageV", "speedMps",
  "controllerSpeedMps", "speedRaw", "distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters",
  "altitudeMeters",
]
guard let plotted = Int(environment["POWER_LOG_WHOLE_RIDE_METRICS"] ?? String(allMetrics.count)),
  (1...allMetrics.count).contains(plotted)
else { fatalError("POWER_LOG_WHOLE_RIDE_METRICS must be a number of metrics from 1 to \(allMetrics.count)") }
let metrics = Array(allMetrics.prefix(plotted))
let statisticsMetrics = statisticsMode == "live" ? ["batteryVoltageV", "throttleVoltageV"] : metrics
func whole(_ metrics: [String], end: Double) -> MonitorRequest {
  MonitorRequest(
    source: "workout", id: ride.id, startSeconds: 0, endSeconds: end, metrics: metrics, buckets: 400, pixelWidth: 400)
}
func energyInterval(_ seconds: Double) -> Double { seconds < 60 ? 2 : seconds < 600 ? 5 : seconds < 3_600 ? 15 : 30 }
func telemetry(_ frame: Int) throws -> WorkoutEvent {
  let time = Double(frame) / 8
  return try WorkoutEvent(
    workoutId: ride.id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
    elapsedSeconds: time,
    payload: [
      "humanPowerW": .number(Double(frame % 300) + 0.125), "cadenceRpm": .number(80 + Double(frame % 4)),
      "batteryCurrentA": .number(Double(frame % 64) / 8), "batteryVoltageV": .number(52.8 - Double(frame) / 100_000),
      "motorInputPowerW": .number(211.2), "controllerSpeedMps": .number(5 + Double(frame % 16) / 8),
      "speedRaw": .number(500 + Double(frame % 16)), "pedalTorqueNm": .number(Double(frame % 40)),
      "motorCurrentA": .number(4), "motorTempC": .number(40), "controllerTempC": .number(35),
      "motorRpm": .number(180), "throttleVoltageV": .number(0.8), "assistLevel": .number(2), "faultCode": .number(0),
      "raceMode": .number(0), "consumedAh": .number(Double(frame) / 28_800),
      "consumedWh": .number(Double(frame) / 545),
      "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(connection),
      "connectionEpoch": .string(connection), "clockEpoch": .string(epoch),
      "observationSequence": .string(String(frame + 1)), "acquisitionMonotonic": .number(100 + time),
    ])
}
struct Cost {
  var calls = 0
  var cpu = 0.0
  var wall = 0.0
  var retries = 0
  var rows = 0
  var resumed = 0
  static func += (total: inout Cost, read: Cost) {
    total.calls += read.calls
    total.cpu += read.cpu
    total.wall += read.wall
    total.retries += read.retries
    total.rows += read.rows
    total.resumed += read.resumed
  }
  var perMinute: String {
    calls == 0
      ? String(repeating: " ", count: 24) + "-"
      : String(format: "%4ld %6.1f %6.0f %6.0f", calls, cpu / Double(calls), cpu / 60, wall / 60)
  }
  var perRead: String {
    calls == 0 ? "-" : String(format: "cpu %.1f wall %.1f ms/read", cpu / Double(calls), wall / Double(calls))
  }
  var energyRows: String {
    String(format: "%8.0f%6ld", calls == 0 ? 0 : Double(rows) / Double(calls), resumed)
  }
}
func storeCPU() throws -> Double { try db.diagnostics()["executionCPUMilliseconds"] as? Double ?? 0 }
// SQLite work runs on the store's executor thread, so a read's CPU is its calling thread's plus that of its store jobs.
func measure(_ body: () throws -> [String: Any]) throws -> Cost {
  var cost = Cost(calls: 1)
  let storeBefore = try storeCPU()
  let cpuBefore = PowerLogStore.threadCPUMilliseconds()
  let began = ProcessInfo.processInfo.systemUptime
  var result = try body()
  if result["status"] as? String == "retry" {
    cost.retries = 1
    monitor.drainDistanceForTesting()
    result = try body()
  }
  cost.wall = (ProcessInfo.processInfo.systemUptime - began) * 1_000
  let callerCPU = PowerLogStore.threadCPUMilliseconds() - cpuBefore
  precondition(result["status"] as? String == "ok", "Whole-ride read did not complete: \(result["status"] ?? "nil")")
  cost.cpu = try callerCPU + storeCPU() - storeBefore
  return cost
}
func measureEnergy(_ request: MonitorRequest, resumed counter: String) throws -> Cost {
  let before = monitor.energyDiagnostics()
  var cost = try measure { try monitor.readEnergyPerDistance(request) }
  let after = monitor.energyDiagnostics()
  cost.rows = after["energyRows", default: 0] - before["energyRows", default: 0]
  cost.resumed = after[counter, default: 0] - before[counter, default: 0]
  return cost
}
func measureTotals(end: Double) throws -> Cost {
  try measureEnergy(whole([], end: end), resumed: "energyResumedReads")
}
// The JS adapter sends the Wh/km chart read the per-chart budget of the raw charts in the same plot request.
let chartBuckets = Int(try InspectionPlotSlots.shared.buckets(requested: 400, metrics: Int32(metrics.count)))
func milliseconds(_ time: timeval) -> Double { Double(time.tv_sec) * 1_000 + Double(time.tv_usec) / 1_000 }
func processCPU() -> Double {
  var usage = rusage()
  getrusage(RUSAGE_SELF, &usage)
  return milliseconds(usage.ru_utime) + milliseconds(usage.ru_stime)
}
func footprintMegabytes() -> Double {
  var info = task_vm_info_data_t()
  var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
  let status = withUnsafeMutablePointer(to: &info) {
    $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
      task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
    }
  }
  return status == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576 : .nan
}
let counters: [(name: String, label: String)] = [
  ("evictions", "evict"), ("projectionHits", "hits"), ("invalidations", "inval"), ("streamingFallbacks", "fallb"),
  ("pyramidBuilds", "pyrB"), ("pyramidInvalidations", "pyrI"), ("plotScannedPoints", "plotPts"),
  ("statisticsScannedPoints", "statPts"), ("summaryAppendedPoints", "sumApp"), ("summaryRebuilds", "sumReb"),
  ("summaryFallbacks", "sumFb"),
]
func counterColumns(_ current: [String: Int], since previous: [String: Int]) -> String {
  counters.map { String(format: "%9ld", (current[$0.name] ?? 0) - (previous[$0.name] ?? 0)) }.joined()
}
func counterList(_ current: [String: Int], since previous: [String: Int]) -> String {
  counters.map { "\($0.label) \((current[$0.name] ?? 0) - (previous[$0.name] ?? 0))" }.joined(separator: " ")
}
let groups = ["plot", "chart", "stats", "totals"]
let groupColumns = "   n    cpu  cpu/s wall/s"
print(
  "Whole-ride host run: \(minutes) ride minutes; plot and Wh/km chart reads every \(readEvery) s; statistics "
    + (statisticsMode == "live" ? "for batteryVoltageV and throttleVoltageV" : "for every metric")
    + (lock.map { "; no reads from ride minute \($0.lowerBound / 60) for \($0.count / 60) min" } ?? ""))
print(
  "plot = whole-ride plot of \(metrics.count) metrics; chart = Wh/km chart; stats = range statistics and totals = "
    + (statisticsMode == "live" ? "the Wh/km averages read" : "the Wh/km averages and statistics reads")
    + ", both on the energy cadence")
print(
  "n = reads; cpu = CPU ms per read (calling thread and its SQLite jobs); cpu/s and wall/s = ms per ride second; "
    + "proc/s = process CPU ms per ride second, capture and background work included")
print(
  "foot = phys_footprint MB; peak = workspaces' peakNativeAdapterBytes MB; counters are per-minute deltas; sumApp, "
    + "sumReb and sumFb = originals appended to whole-range slot summaries, summary rebuilds and reads served without "
    + "a summary; a lock reports its first reads on their own line")
print("tRows = rows fed per Wh/km totals read; tRes = Wh/km totals reads resumed from a checkpoint")
print("cRows = rows fed per Wh/km chart read; cRes = Wh/km chart reads resumed from their series")
print(
  String(repeating: " ", count: 6)
    + groups.map { " |" + String(repeating: " ", count: groupColumns.count - $0.count) + $0 }.joined() + " |"
    + String(repeating: " ", count: 22) + " |")
print(
  "minute" + groups.map { _ in " |" + groupColumns }.joined() + " |  proc/s   foot   peak |"
    + counters.map { String(repeating: " ", count: 9 - $0.label.count) + $0.label }.joined()
    + "   tRows  tRes   cRows  cRes")
var previous = monitor.projectionDiagnostics()
var previousProcess = processCPU()
let breakdownMinutes = min(10, minutes)
var breakdownStart = previous
var breakdownPlot = Cost()
let began = ProcessInfo.processInfo.systemUptime
var nextStatistics = 0.0
for minute in 0..<minutes {
  if minute == minutes - breakdownMinutes { breakdownStart = monitor.projectionDiagnostics() }
  var costs = Dictionary(uniqueKeysWithValues: groups.map { ($0, Cost()) })
  for second in (minute * 60)..<((minute + 1) * 60) {
    try autoreleasepool {
      for frame in (second * 8)..<((second + 1) * 8) { try archive.append(telemetry(frame)) }
      let time = Double(second + 1) - 0.125
      try archive.append(
        WorkoutEvent(
          workoutId: ride.id, kind: "location", source: "phone", timestamp: origin.addingTimeInterval(time),
          elapsedSeconds: time,
          payload: [
            "latitude": .number(0), "longitude": .number(time * 0.000045), "horizontalAccuracyM": .number(3),
            "speedMps": .number(5), "speedAccuracyMps": .number(0.5), "altitudeMeters": .number(100),
            "verticalAccuracyM": .number(4), "courseDegrees": .number(90), "courseAccuracyDegrees": .number(5),
          ]))
      if second % 5 == 4 {
        try archive.append(
          WorkoutEvent(
            workoutId: ride.id, kind: "health", source: "watch", timestamp: origin.addingTimeInterval(time),
            elapsedSeconds: time,
            payload: ["heartRateBpm": .number(110 + Double(second % 30)), "representation": .string("rawQuantity")]))
        try archive.update(
          id: ride.id, phase: "running",
          ownerTiming: WorkoutOwnerTiming(
            timestamp: WorkoutCoding.timestamp(origin.addingTimeInterval(time)), elapsedSeconds: time,
            timerSeconds: time),
          elapsedSeconds: time, onlyIfChanged: true)
      }
      if lock?.contains(second) == true { return }
      let unlocking = lock?.upperBound == second
      let beforeReads = unlocking ? monitor.projectionDiagnostics() : [:]
      var reads = Dictionary(uniqueKeysWithValues: groups.map { ($0, Cost()) })
      if plots && (second % readEvery == 0 || unlocking) {
        reads["plot"]! += try measure { try monitor.readPlot(whole(metrics, end: time)) }
        var chart = whole([], end: time)
        chart.energyTotals = false
        chart.buckets = chartBuckets
        reads["chart"]! += try measureEnergy(chart, resumed: "energySeriesResumedReads")
      }
      if time >= nextStatistics {
        nextStatistics = time + energyInterval(time)
        reads["stats"]! += try measure { try monitor.rangeStats(whole(statisticsMetrics, end: time)) }
        reads["totals"]! += try measureTotals(end: time)
        if statisticsMode == "all" { reads["totals"]! += try measureTotals(end: time) }
      }
      for group in groups { costs[group]! += reads[group]! }
      if unlocking {
        print(
          "first reads after the lock at ride second \(second): "
            + groups.map { "\($0) \(reads[$0]!.perRead)" }.joined(separator: "; ") + "; totals rows "
            + String(reads["totals"]!.rows) + "; " + counterList(monitor.projectionDiagnostics(), since: beforeReads))
      }
    }
  }
  let current = monitor.projectionDiagnostics()
  let process = processCPU()
  if minute >= minutes - breakdownMinutes { breakdownPlot += costs["plot"]! }
  let retries = groups.map { costs[$0]!.retries }
  print(
    String(format: "%6ld", minute + 1) + groups.map { " |" + costs[$0]!.perMinute }.joined()
      + String(
        format: " | %7.0f %6.0f %6.1f |", (process - previousProcess) / 60, footprintMegabytes(),
        Double(current["peakNativeAdapterBytes"] ?? 0) / 1_048_576)
      + counterColumns(current, since: previous) + costs["totals"]!.energyRows + costs["chart"]!.energyRows
      + (retries.allSatisfy { $0 == 0 } ? "" : "  retries " + retries.map(String.init).joined(separator: "/")))
  previous = current
  previousProcess = process
}
let breakdown = [
  "summaryExtensionCPUMicros", "summaryMergeCPUMicros", "plotResultCPUMicros", "plotHydrationCPUMicros",
  "plotRowCPUMicros",
].map { name in
  String(
    format: "%@ %.2f", name.replacingOccurrences(of: "CPUMicros", with: ""),
    Double((previous[name] ?? 0) - (breakdownStart[name] ?? 0)) / 1_000 / Double(max(1, breakdownPlot.calls)))
}
print(
  "last \(breakdownMinutes) ride minutes, CPU ms per plot read: total with SQLite "
    + String(format: "%.1f", breakdownPlot.cpu / Double(max(1, breakdownPlot.calls))) + "; "
    + breakdown.joined(separator: "; "))
print(
  String(
    format: "Whole-ride host run %.1f s for %ld ride minutes; cache %ld of %ld bytes; %ld slot summaries hold %ld "
      + "points in %ld bytes",
    ProcessInfo.processInfo.systemUptime - began, minutes, previous["nativeAdapterBytes"] ?? 0,
    previous["nativeAdapterByteLimit"] ?? 0, previous["summaryEntries"] ?? 0, previous["summaryPoints"] ?? 0,
    previous["summaryBytes"] ?? 0))
