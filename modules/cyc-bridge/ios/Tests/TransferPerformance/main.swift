import Foundation
import Darwin

// Disposable synthetic fixture. Run baseline and candidate with the same frame count.
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-transfer-profile-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: root) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("sender/power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("sender/workouts"), store: store)
let received = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("receiver/power-log.sqlite3"))
let destination = try WorkoutArchive(rootURL: root.appendingPathComponent("receiver/workouts"), store: received)
let sender = WorkoutTransferJournal(archive: archive), receiver = WorkoutTransferJournal(archive: destination)
let start = Date(timeIntervalSince1970: 1_780_000_000)
let frameCount = CommandLine.arguments.dropFirst().compactMap(Int.init).first ?? 24_000
let ride = try archive.create(startedAt: start, indoor: false, watchEnabled: true)
_ = try destination.create(id: ride.id, startedAt: start, indoor: false, watchEnabled: true)
func now() -> Double { ProcessInfo.processInfo.systemUptime }
func cpu() -> Double {
  var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
  return Double(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) + Double(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1_000_000
}
var times: [String: Double] = [:], cpuTimes: [String: Double] = [:]
func measure<T>(_ name: String, _ body: () throws -> T) rethrows -> T {
  let wall = now(), used = cpu()
  defer { times[name, default: 0] += (now() - wall) * 1000; cpuTimes[name, default: 0] += (cpu() - used) * 1000 }
  return try autoreleasepool(invoking: body)
}
var capture = CycCaptureClock(origin: 100, wallOrigin: start), batch: [WorkoutEvent] = []
try measure("fixture") {
  for i in 0..<frameCount {
    try autoreleasepool {
      let t = Double(i) / 8
      var values: [String: Double] = [:]
      for (j, name) in PowerLogStore.telemetryColumns.enumerated() { values[name] = Double(j + i % 100) + 0.125 }
      let sample = capture.observation(values, monotonic: 100 + t, wall: start.addingTimeInterval(t))
      batch.append(try WorkoutEvent(dictionary: ["schemaVersion": 1, "eventId": sample["observationId"]!, "workoutId": ride.id,
        "kind": "telemetry", "source": "cyc", "timestamp": sample["timestamp"]!, "elapsedSeconds": t, "payload": sample]))
      if i % 8 == 0 {
        batch.append(try WorkoutEvent(workoutId: ride.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(t), elapsedSeconds: t,
          payload: ["heartRateBpm": .number(100), "representation": .string("rawQuantity")]))
        batch.append(try WorkoutEvent(workoutId: ride.id, kind: "location", source: "watch", timestamp: start.addingTimeInterval(t), elapsedSeconds: t,
          payload: ["latitude": .number(0), "longitude": .number(0), "speedMps": .number(4), "horizontalAccuracyM": .number(2)]))
      }
      if batch.count >= 250 { _ = try store.appendBatch(batch); batch.removeAll(keepingCapacity: true) }
    }
  }
  _ = try store.appendBatch(batch); batch.removeAll()
}
var seals: [WorkoutSourceSeal] = []
try measure("sourceSeal") { for producer in ["cyc", "watch"] { seals.append(try sender.source(id: ride.id, producer: producer)) } }
let probeStore = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("probe/power-log.sqlite3"))
let probeArchive = try WorkoutArchive(rootURL: root.appendingPathComponent("probe/workouts"), store: probeStore)
_ = try probeArchive.create(id: ride.id, startedAt: start, indoor: false, watchEnabled: true)
// Isolate the hot reader, canonical encoder, validation and wire decode on bounded pages.
for page in 0..<min(frameCount / 128, 32) {
  let events = try measure("probeRead") { try archive.pageEvents(id: ride.id, afterSequence: Int64(page * 128), limit: 128, producer: "cyc").map(\.event) }
  try measure("probeValidate") { for event in events { try event.validate() } }
  try measure("probeEncode") { for event in events { _ = try WorkoutCoding.encoder().encode(event) } }
  let chunk = try measure("probeCodecEncode") { try WorkoutChunkCodec.encode(workoutID: ride.id, producer: "cyc", firstSequence: Int64(page * 128 + 1), events: events) }
  try measure("probeCodecDecode") { _ = try WorkoutChunkCodec.decode(chunk) }
  for offset in stride(from: 0, to: events.count, by: 32) {
    try measure("probeAppend32") {
      _ = try probeStore.transaction(priority: .normal) { _ in
        try probeArchive.appendBatch(Array(events[offset..<min(events.count, offset + 32)]), producer: "cyc", firstSequence: Int64(page * 128 + offset + 1))
      }
    }
  }
}
let liveID = UUID().uuidString.lowercased()
try received.createCollection(id: liveID, kind: "live", startedAt: WorkoutCoding.timestamp(start))
let captureQueue = DispatchQueue(label: "transfer-profile.capture")
let timer = DispatchSource.makeTimerSource(queue: captureQueue)
var captured: [Double] = [], captureError: Error?, liveClock = CycCaptureClock(origin: now(), wallOrigin: start)
let origin = now()
timer.schedule(deadline: .now(), repeating: .milliseconds(125))
timer.setEventHandler {
  do {
    let t = now() - origin
    let sample = liveClock.observation(["humanPowerW": 120.0, "cadenceRpm": 80.0], monotonic: origin + t, wall: start.addingTimeInterval(t))
    let begin = now(); _ = try received.appendTelemetry(sample, collectionID: liveID, elapsedSeconds: t)
    captured.append((now() - begin) * 1000)
  } catch { captureError = error }
}
timer.resume()
var chunks = 0, compressedBytes = 0, plainBytes = 0, duplicateHits = 0
try measure("transfer") {
  for producer in ["cyc", "watch"] {
    var after: Int64 = 0
    while try autoreleasepool(invoking: { () throws -> Bool in
      guard let chunk = try measure("nextChunk", { try sender.nextChunk(id: ride.id, producer: producer, after: after) }) else { return false }
      let inserted = try measure("receive") { try receiver.receive(chunk) }; precondition(inserted)
      chunks += 1; compressedBytes += chunk.data.count; plainBytes += chunk.manifest.uncompressedBytes
      if chunks % 50 == 0 { let inserted = try measure("duplicate") { try receiver.receive(chunk) }; precondition(!inserted); duplicateHits += 1 }
      after = chunk.manifest.lastSequence; return true
    }) {}
  }
}
timer.cancel(); captureQueue.sync {}
if let captureError { throw captureError }
let seal = WorkoutSeal(workoutID: ride.id, sealRevision: 1, collectionRevision: try archive.revision(id: ride.id), ownerRevision: 1,
  stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(Double(frameCount) / 8)), healthOutcome: "saved", requirements: ["synthetic": "sealed"], sources: seals)
let accepted = try receiver.accept(seal: seal); precondition(accepted)
var pages = 0; receiver.onVerifyPage = { pages += 1 }
try measure("verify") { let verified = try receiver.verify(id: ride.id); precondition(verified) }
let firstPages = pages
try measure("repeat100Verify") { for _ in 0..<100 { let verified = try receiver.verify(id: ride.id); precondition(verified) } }
precondition(pages == firstPages)
let sorted = captured.sorted(); var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
let result: [String: Any] = ["synthetic": true, "frames": frameCount, "records": frameCount + 2 * ((frameCount + 7) / 8),
  "chunks": chunks, "compressedBytes": compressedBytes, "plainBytes": plainBytes, "duplicateHits": duplicateHits, "verificationPages": pages,
  "milliseconds": times, "cpuMilliseconds": cpuTimes, "captureSamples": sorted.count,
  "captureP95Ms": sorted.isEmpty ? 0 : sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))], "captureMaxMs": sorted.last ?? 0,
  "peakResidentBytes": usage.ru_maxrss]
let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
if let path = CommandLine.arguments.first(where: { $0.hasSuffix(".json") }) { try data.write(to: URL(fileURLWithPath: path)) }
print(String(decoding: data, as: UTF8.self))
