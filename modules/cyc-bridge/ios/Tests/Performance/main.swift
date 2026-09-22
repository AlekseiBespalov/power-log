import Foundation
import Darwin

let root = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-sqlite-perf-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: db)
let reader = MonitorDataStore(root: root)
let start = Date(timeIntervalSince1970: 1_780_000_000)
func require(_ condition: Bool) { precondition(condition) }
func now() -> Double { ProcessInfo.processInfo.systemUptime }
func elapsed(_ action: () throws -> Void) rethrows -> Double { let start = now(); try autoreleasepool(invoking: action); return (now() - start) * 1000 }
let matrix = CommandLine.arguments.contains("--matrix")
let pairs: [(Int, Int)] = matrix ? [1,4,8].flatMap { hours in [2,4,8].map { (hours,$0) } } : [(8,8)]
var results: [[String: Any]] = []
for i in 0..<100 { _ = try archive.create(startedAt: start.addingTimeInterval(-Double(i + 1) * 3600), indoor: true, watchEnabled: false) }
for (hours, hz) in pairs {
  let ride = try archive.create(startedAt: start, indoor: false, watchEnabled: true)
  let frameCount = hours * 3600 * hz
  var capture = CycCaptureClock(origin: 100, wallOrigin: start)
  var batch: [WorkoutEvent] = []
  let writeStart = now()
  func flush() throws { if !batch.isEmpty { _ = try db.appendBatch(batch); batch.removeAll(keepingCapacity: true) } }
  for i in 0..<frameCount {
    try autoreleasepool {
    let time = Double(i) / Double(hz)
    var values: [String: WorkoutJSON] = [:]
    for (j, column) in PowerLogStore.telemetryColumns.enumerated() {
      let integral = ["humanPowerW", "cadenceRpm", "motorRpm", "faultCode", "assistLevel", "raceMode", "speedRaw"].contains(column)
      values[column] = .number(column == "humanPowerW" ? Double(i % 600) : Double(j + i % 100) + (integral ? 0 : 0.125))
    }
    let sample = capture.observation(values.mapValues { $0.number! }, monotonic: 100 + time, wall: start.addingTimeInterval(time))
    batch.append(try WorkoutEvent(dictionary: ["schemaVersion": 1, "eventId": sample["observationId"]!, "workoutId": ride.id,
      "kind": "telemetry", "source": "cyc", "timestamp": sample["timestamp"]!, "elapsedSeconds": time, "payload": sample]))
    if i % hz == 0 {
      batch.append(try WorkoutEvent(workoutId: ride.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(time), elapsedSeconds: time,
        payload: ["heartRateBpm": .number(Double(90 + i % 70)), "representation": .string("rawQuantity")]))
      batch.append(try WorkoutEvent(workoutId: ride.id, kind: "location", source: "watch", timestamp: start.addingTimeInterval(time), elapsedSeconds: time,
        payload: ["latitude": .number(0), "longitude": .number(0), "speedMps": .number(4), "horizontalAccuracyM": .number(2)]))
    }
    if batch.count >= 250 { try flush() }
    }
  }
  try flush()
  _ = try archive.finish(id: ride.id, endedAt: start.addingTimeInterval(Double(hours * 3600)))
  let writeSeconds = now() - writeStart
  let native = MonitorDataStore(root: root)
  var q = MonitorRequest(source: "workout", id: ride.id, generation: 1, metrics: ["humanPowerW","cadenceRpm","heartRateBpm","speedMps"], buckets: 256)
  let describeMs = try elapsed { q.expectedRevision = try native.describeSource(q)["revision"] as? String }
  var outputPoints = 0
  let coldPlot = try elapsed {
    let plot = try native.readPlot(q)
    outputPoints = (plot["series"] as? [String: [[String: Any]]])?.values.reduce(0) { $0 + $1.count } ?? 0
    precondition(plot["status"] as? String == "ok")
  }
  let warmPlot = try elapsed { _ = try native.readPlot(q) }
  q.startSeconds = 0; q.endSeconds = Double(hours * 3600)
  let fullStats = try elapsed { _ = try native.rangeStats(q) }
  q.buckets = 255
  let resizePlot = try elapsed { _ = try native.readPlot(q) }
  q.seconds = Double(hours * 1800) + 0.01
  var cursor: [Double] = []
  for _ in 0..<20 { cursor.append(try elapsed { _ = try native.inspectAt(q) }) }
  q.startSeconds = Double(hours * 1800); q.endSeconds = q.startSeconds! + 120
  let viewport = try elapsed { _ = try native.readPlot(q) }
  let stats = try elapsed { _ = try native.rangeStats(q) }
  let catalog = try elapsed { let count = try archive.list().count; precondition(count == 100) }
  try db.checkpoint()
  let files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey])
  var sizes: [String: Int] = [:]
  for file in files where file.lastPathComponent.hasPrefix("power-log.sqlite3") { sizes[file.lastPathComponent] = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0 }
  var result: [String: Any] = ["hours":hours,"hz":hz,"frames":frameCount,"healthAndGPSPerSecond":true,"nativeAcquisitionIdentity":true,"writeSeconds":writeSeconds,
    "describeMs":describeMs,"coldFourMetricPlotMs":coldPlot,"warmFourMetricPlotMs":warmPlot,"cursorP95Ms":cursor.sorted()[18],
    "fullDomainStatsMs":fullStats,"fullDomainResizeMs":resizePlot,"twoMinuteViewportMs":viewport,"twoMinuteStatsMs":stats,"catalog100Ms":catalog,"geometryPoints":outputPoints,"files":sizes,"fileSizesAreCumulativeAcrossMatrix":matrix]
  if CommandLine.arguments.contains("--transfer"), hours == 8, hz == 8 {
    let receiverStore = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("receiver/power-log.sqlite3"))
    let receiverArchive = try WorkoutArchive(rootURL: root.appendingPathComponent("receiver/workouts"), store: receiverStore)
    _ = try receiverArchive.create(id: ride.id, startedAt: start, indoor: false, watchEnabled: true)
    let sender = WorkoutTransferJournal(archive: archive), receiver = WorkoutTransferJournal(archive: receiverArchive)
    var seals: [WorkoutSourceSeal] = []
    let sealMs = try elapsed { for producer in ["cyc", "watch"] { seals.append(try sender.source(id: ride.id, producer: producer)) } }
    let liveID = UUID().uuidString.lowercased()
    try receiverStore.createCollection(id: liveID, kind: "live", startedAt: WorkoutCoding.timestamp(start))
    let captureQueue = DispatchQueue(label: "performance.concurrent.capture")
    let timer = DispatchSource.makeTimerSource(queue: captureQueue)
    var captured: [Double] = [], captureFailure: Error?, captureClock = CycCaptureClock(origin: now(), wallOrigin: start)
    let captureOrigin = now()
    timer.schedule(deadline: .now(), repeating: .milliseconds(125))
    timer.setEventHandler {
      do {
        let t = now() - captureOrigin
        let sample = captureClock.observation(["humanPowerW": 120.0, "cadenceRpm": 80.0], monotonic: captureOrigin + t, wall: start.addingTimeInterval(t))
        captured.append(try elapsed { _ = try receiverStore.appendTelemetry(sample, collectionID: liveID, elapsedSeconds: t) })
      } catch { captureFailure = error }
    }
    timer.resume()
    var chunks = 0, compressed = 0, expanded = 0, duplicateHits = 0
    let importMs = try elapsed {
      for producer in ["cyc", "watch"] {
        var sequence: Int64 = 0
        while try autoreleasepool(invoking: { () throws -> Bool in
          guard let chunk = try sender.nextChunk(id: ride.id, producer: producer, after: sequence) else { return false }
          require(try receiver.receive(chunk)); chunks += 1; compressed += chunk.data.count; expanded += chunk.manifest.uncompressedBytes
          if chunks % 50 == 0 { require(try !receiver.receive(chunk)); duplicateHits += 1 }
          sequence = chunk.manifest.lastSequence
          return true
        }) {}
      }
    }
    timer.cancel(); captureQueue.sync {}
    if let captureFailure { throw captureFailure }
    let declaration = WorkoutSeal(workoutID: ride.id, sealRevision: 1, collectionRevision: try archive.revision(id: ride.id), ownerRevision: 1,
      stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(Double(hours * 3600))), healthOutcome: "saved",
      requirements: ["syntheticExtraction": "sealed"], sources: seals, stopElapsedSeconds: Double(hours * 3600))
    require(try receiver.accept(seal: declaration))
    var verificationPages = 0; receiver.onVerifyPage = { verificationPages += 1 }
    let verifyMs = try elapsed { require(try receiver.verify(id: ride.id)) }
    let firstPassPages = verificationPages
    let repeatedVerifyMs = try elapsed { for _ in 0..<100 { require(try receiver.verify(id: ride.id)) } }
    precondition(verificationPages == firstPassPages)
    let sortedCapture = captured.sorted()
    result["transfer"] = ["chunks":chunks,"compressedBytes":compressed,"uncompressedBytes":expanded,"duplicateReceiptHits":duplicateHits,
      "sourceSealMs":sealMs,"importMs":importMs,"verifyMs":verifyMs,"verificationPages":firstPassPages,
      "repeated100VerifyMs":repeatedVerifyMs,"extraVerifiedPageReads":verificationPages-firstPassPages,
      "concurrentCaptureSamples":captured.count,"captureCommitP95Ms":sortedCapture.isEmpty ? 0 : sortedCapture[min(sortedCapture.count-1, Int(Double(sortedCapture.count)*0.95))],
      "captureCommitMaxMs":sortedCapture.last ?? 0]
  }
  var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
  result["processPeakResidentBytes"] = usage.ru_maxrss
  results.append(result)
  print(String(data: try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]), encoding: .utf8)!)
}
let output: [String: Any] = ["platform":"macOS native Swift optimized synthetic, not device evidence", "runtime":try db.diagnostics(), "results":results]
let out = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first(where: { $0.hasSuffix(".json") }) ?? "/private/tmp/power-log-independent-review/native-performance.json")
try JSONSerialization.data(withJSONObject: output, options: [.prettyPrinted,.sortedKeys]).write(to: out)
print("Benchmark report: \(out.path)")
if !CommandLine.arguments.contains("--keep") { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
else { print("Synthetic database: \(root.path)") }
