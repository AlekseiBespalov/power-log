import Foundation
import Darwin

// Exercise real exporters while native acquisition continues, using benchmark:native output only.
guard CommandLine.arguments.count == 2,
  CommandLine.arguments[1].contains("power-log-sqlite-perf-") else {
  fatalError("Pass the disposable PowerLog directory produced by benchmark:native --keep")
}
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: store)
let row = try store.read { try $0.rows("SELECT id FROM collections WHERE kind='workout' ORDER BY event_count DESC LIMIT 1", limit: 1).first! }
let id = row.string("id")!, transfer = WorkoutTransferJournal(archive: archive)
let metadata = try archive.metadata(id: id)
let start = try WorkoutCoding.date(metadata.startedAt)
let producers = try store.read { try $0.rows("SELECT producer FROM collection_sources WHERE collection_id=?", [.text(id)], limit: 3).compactMap { $0.string("producer") } }
let sources = try producers.map { try transfer.source(id: id, producer: $0) }
_ = try transfer.accept(seal: WorkoutSeal(workoutID: id, sealRevision: (metadata.sealRevision ?? 0) + 1,
  collectionRevision: try archive.revision(id: id), ownerRevision: 1, stopCutoff: metadata.endedAt!,
  healthOutcome: "saved", requirements: ["syntheticExtraction": "sealed"], sources: sources))
let verified = try transfer.verify(id: id)
precondition(verified)
let revision = try archive.revision(id: id)
let liveID = UUID().uuidString.lowercased()
try store.createCollection(id: liveID, kind: "live", startedAt: metadata.startedAt)
let queue = DispatchQueue(label: "performance.export.capture", autoreleaseFrequency: .workItem)
let timer = DispatchSource.makeTimerSource(queue: queue)
let origin = ProcessInfo.processInfo.systemUptime
var capture = CycCaptureClock(origin: origin, wallOrigin: start)
var timings: [Double] = [], captureError: Error?
timer.schedule(deadline: .now(), repeating: .milliseconds(125))
timer.setEventHandler {
  do {
    let now = ProcessInfo.processInfo.systemUptime, elapsed = now - origin
    let sample = capture.observation(["humanPowerW": 120, "cadenceRpm": 80], monotonic: now, wall: start.addingTimeInterval(elapsed))
    _ = try store.appendTelemetry(sample, collectionID: liveID, elapsedSeconds: elapsed)
    timings.append((ProcessInfo.processInfo.systemUptime - now) * 1_000)
  } catch { captureError = error }
}
func milliseconds(_ body: () throws -> Void) rethrows -> Double {
  let start = ProcessInfo.processInfo.systemUptime
  try autoreleasepool(invoking: body)
  return (ProcessInfo.processInfo.systemUptime - start) * 1_000
}
let output = root.appendingPathComponent("export-performance-" + UUID().uuidString)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: output) }
timer.resume()
let fit = output.appendingPathComponent("workout.fit"), zip = output.appendingPathComponent("original.zip")
let fitMS = try milliseconds { _ = try WorkoutFIT.export(archive: archive, id: id, to: fit, revision: revision) }
let zipMS = try milliseconds { try WorkoutOriginalExport.write(archive: archive, id: id, to: zip, revision: revision) }
timer.cancel(); queue.sync {}
if let captureError { throw captureError }
let verifiedRevision = try archive.revision(id: id)
precondition(verifiedRevision == revision, "Export changed the workout originals or metadata")
precondition(!timings.isEmpty, "Capture did not run concurrently")
let sorted = timings.sorted()
var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
let result: [String: Any] = ["provenance": "macOS optimized synthetic native exporters, not device evidence", "events": metadata.eventCount,
  "fitMs": fitMS, "originalZIPMs": zipMS,
  "fitBytes": try fit.resourceValues(forKeys: [.fileSizeKey]).fileSize!, "originalZIPBytes": try zip.resourceValues(forKeys: [.fileSizeKey]).fileSize!,
  "captureSamples": timings.count, "captureCommitP95Ms": sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))],
  "captureCommitMaxMs": sorted.last!, "processPeakResidentBytes": usage.ru_maxrss]
print(String(data: try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]), encoding: .utf8)!)
