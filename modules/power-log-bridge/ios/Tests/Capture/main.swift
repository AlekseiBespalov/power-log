import Foundation
import PowerLogCore

var checks = 0
func check(_ value: Bool, _ label: String) {
  checks += 1
  if !value { fatalError(label) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-capture-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let start = Date(timeIntervalSince1970: 1_780_000_000)
let epoch = UUID().uuidString.lowercased()
let live = UUID().uuidString.lowercased()
var clock = TelemetryCaptureClock(origin: 100, wallOrigin: start, sessionID: live, epochID: epoch)
func frame(_ seconds: Double) throws -> PowerLogCaptureFrame {
  let sample = try clock.observation(
    testGeneratedTelemetryReading([
      "humanPowerW": 123 + seconds, "cadenceRpm": 81.5,
      "batteryVoltageV": 52.8, "batteryCurrentA": 4, "motorInputPowerW": 211.2,
    ]), connectionEpoch: epoch,
    monotonic: 100 + seconds, wall: start.addingTimeInterval(seconds))
  return PowerLogCaptureFrame(
    sample: sample, liveID: live, liveStartedAt: WorkoutCoding.timestamp(start), liveOrigin: 100, liveElapsed: seconds)
}
let archive = try WorkoutArchive(rootURL: root)
let ride = try archive.create(
  startedAt: start, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false, sampleHz: 8)
let next = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
let anchor = WorkoutTimelineAnchor(epoch: epoch, monotonicOrigin: 100, startedAt: WorkoutCoding.timestamp(start))
let inbox = PowerLogCaptureInbox()
inbox.setDestination(PowerLogCaptureDestination(id: ride.id, generation: UUID(), timeline: anchor))
check(try inbox.admit(frame(0)), "first frame wakes drainer")
check(try !inbox.admit(frame(0.125)), "capture wakes coalesce")
inbox.setDestination(PowerLogCaptureDestination(id: next.id, generation: UUID(), timeline: anchor))
let batch = PowerLogCaptureBatch()
for frame in inbox.take(upTo: 64) {
  try batch.append(PowerLogCaptureRecord(frame: frame, rideElapsed: frame.rideElapsed()), at: 0)
}
enum Fault: Error { case disk }
archive.store.beforeCommitForTesting = { throw Fault.disk }
do {
  try batch.flush(store: archive.store)
  fatalError("failure did not surface")
} catch { checks += 1 }
check(batch.records.count == 2, "failed commit retains exactly the batch")
check(try archive.metadata(id: ride.id).eventCount == 0, "failed commit has no partial membership")
archive.store.beforeCommitForTesting = nil
try batch.flush(store: archive.store)
check(try archive.metadata(id: ride.id).eventCount == 2, "retry commits original destination")
check(try archive.metadata(id: next.id).eventCount == 0, "queued frames never move into next ride")
check(
  try archive.store.read { try $0.scalarInt("SELECT count(*) FROM collection_memberships") } == 2,
  "one durable membership per raw frame")
check(
  try archive.store.read { try $0.scalarInt("SELECT count(*) FROM collections WHERE kind='live'") } == 0,
  "no persistent live mirror")
let recorder = PowerLogCaptureRecorder(store: archive.store)
recorder.setDestination(PowerLogCaptureDestination(id: ride.id, generation: UUID(), timeline: anchor))
for index in 2..<258 {
  try recorder.admit(frame(Double(index) / 8))
  if index % 64 == 0 { try recorder.barrier() }
}
try recorder.barrier()
var events: [WorkoutEvent] = []
try archive.forEachEvent(id: ride.id) { events.append($0) }
check(events.count == 258, "every 8 Hz original is retained")
check(events[1].elapsedSeconds == 0.125, "sub-second time remains exact")
check(events[1].number("humanPowerW") == 123.125, "sub-second values are never downsampled")
check(events.last?.number("batteryCurrentA") == 4, "secondary controller quantities remain recorded")
let bounded = PowerLogCaptureInbox()
for index in 0..<PowerLogCaptureInbox.maximumFrames { _ = try bounded.admit(frame(100 + Double(index) / 8)) }
do {
  _ = try bounded.admit(frame(200))
  fatalError("inbox was unbounded")
} catch { checks += 1 }
check(bounded.count == 480, "capture backpressure has a fixed memory bound")
let preview = try PowerLogStore.memory()
try preview.ensureLiveCollection(id: live, startedAt: WorkoutCoding.timestamp(start), monotonicOrigin: 100)
for index in 0..<300 {
  _ = try preview.appendTelemetry(
    frame(300 + Double(index) / 8).sample, collectionID: live, elapsedSeconds: Double(index) / 8)
}
let revision = try preview.collection(id: live).int("revision")!
try preview.prunePreview(id: live, retaining: 256)
check(
  try preview.read { try $0.scalarInt("SELECT count(*) FROM collection_memberships") } == 256,
  "preview discards oldest originals at its bound")
check(try preview.collection(id: live).int("revision")! > revision, "preview pruning invalidates old plot revisions")
check(!FileManager.default.fileExists(atPath: preview.databaseURL.path), "preview has no disk database")
clock = TelemetryCaptureClock(origin: 100, wallOrigin: start, sessionID: UUID().uuidString.lowercased(), epochID: epoch)
let fenced = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
let fencedRecorder = PowerLogCaptureRecorder(store: archive.store)
let fencedGeneration = UUID()
fencedRecorder.setDestination(PowerLogCaptureDestination(id: fenced.id, generation: fencedGeneration, timeline: anchor))
let held = DispatchSemaphore(value: 0)
let release = DispatchSemaphore(value: 0)
let done = DispatchSemaphore(value: 0)
DispatchQueue.global().async {
  try! archive.store.transaction { _ in
    held.signal()
    release.wait()
  }
}
held.wait()
for index in 0..<8 { try fencedRecorder.admit(frame(Double(index) / 8)) }
fencedRecorder.stopDestination(id: fenced.id, generation: fencedGeneration, elapsed: 0.5)
fencedRecorder.setDestination(nil)
DispatchQueue.global().async {
  try! fencedRecorder.barrier()
  done.signal()
}
release.signal()
check(done.wait(timeout: .now() + 5) == .success, "stalled flush completes")
check(
  try archive.metadata(id: fenced.id).eventCount == 5,
  "Stop fence survives destination clearing and excludes later queued originals inside the storage transaction")
print("Phone canonical capture: \(checks) checks passed")
