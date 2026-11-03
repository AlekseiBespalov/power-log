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
    sample: sample, liveID: live, liveElapsed: seconds)
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
let racing = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
let racingRecorder = PowerLogCaptureRecorder(store: archive.store)
racingRecorder.setDestination(PowerLogCaptureDestination(id: racing.id, generation: UUID(), timeline: anchor))
let racingGate = DispatchSemaphore(value: 0)
racingRecorder.queue.async { racingGate.wait() }
for index in 0..<16 { try racingRecorder.admit(frame(40 + Double(index) / 8)) }
var admittedDuringCommit = false
archive.store.beforeCommitForTesting = {
  guard !admittedDuringCommit else { return }
  admittedDuringCommit = true
  try racingRecorder.admit(frame(42))
}
racingGate.signal()
let racingDeadline = Date().addingTimeInterval(5)
while !racingRecorder.inbox.isEmpty && Date() < racingDeadline { usleep(10_000) }
archive.store.beforeCommitForTesting = nil
check(racingRecorder.inbox.isEmpty, "a frame admitted while the drain commits is drained without a barrier")
try racingRecorder.barrier()
check(try archive.metadata(id: racing.id).eventCount == 17, "the frame admitted during the commit is recorded")
let barrierRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
let barrierInbox = PowerLogCaptureInbox()
barrierInbox.setDestination(PowerLogCaptureDestination(id: barrierRide.id, generation: UUID(), timeline: anchor))
for index in 0..<16 { _ = try barrierInbox.admit(frame(50 + Double(index) / 8)) }
let barrierRecorder = PowerLogCaptureRecorder(store: archive.store, inbox: barrierInbox)
var admittedDuringBarrier = false
archive.store.beforeCommitForTesting = {
  guard !admittedDuringBarrier else { return }
  admittedDuringBarrier = true
  try barrierRecorder.admit(frame(52))
}
try barrierRecorder.barrier()
let barrierDeadline = Date().addingTimeInterval(5)
while !barrierInbox.isEmpty && Date() < barrierDeadline { usleep(10_000) }
archive.store.beforeCommitForTesting = nil
check(barrierInbox.isEmpty, "a frame admitted while a barrier commits is drained without another barrier")
let skipped = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
let skipping = PowerLogCaptureRecorder(store: archive.store)
skipping.setDestination(PowerLogCaptureDestination(id: skipped.id, generation: UUID(), timeline: anchor))
var rejected: [Error] = []
skipping.failed = { rejected.append($0) }
var unrecordable = try frame(54).sample
unrecordable["sequence"] = 1.5
try skipping.admit(
  PowerLogCaptureFrame(sample: unrecordable, liveID: live, liveElapsed: 54))
try skipping.admit(frame(54.125))
try skipping.barrier()
check(rejected.count == 1 && skipping.inbox.isEmpty, "a frame that cannot be recorded is reported and skipped")
check(try archive.metadata(id: skipped.id).eventCount == 1, "frames after an unrecordable one are still recorded")
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
let reclaimArchive = try WorkoutArchive(rootURL: root.appendingPathComponent("reclaim"))
let doomed = try reclaimArchive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
var doomedBatch: [WorkoutEvent] = []
for sample in 0..<4096 {
  doomedBatch.append(
    try WorkoutEvent(
      workoutId: doomed.id, kind: "telemetry", source: "controller",
      timestamp: start.addingTimeInterval(Double(sample) / 8), elapsedSeconds: Double(sample) / 8,
      payload: [
        "humanPowerW": .number(Double(100 + sample % 211)), "cadenceRpm": .number(80),
        "batteryCurrentA": .number(Double(sample % 97) / 4),
      ]))
  if doomedBatch.count == 512 {
    _ = try reclaimArchive.appendBatch(doomedBatch)
    doomedBatch.removeAll()
  }
}
_ = try reclaimArchive.update(id: doomed.id, phase: "completed")
let recording = try reclaimArchive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8)
_ = try reclaimArchive.store.markWorkoutDeleted(id: doomed.id)
while try !reclaimArchive.cleanupDeletedWorkoutPage(id: doomed.id) {}
let reclaimRecorder = PowerLogCaptureRecorder(store: reclaimArchive.store)
reclaimRecorder.setDestination(PowerLogCaptureDestination(id: recording.id, generation: UUID(), timeline: anchor))
let captureState = NSCondition()
var captureCommits = 0
var captureFailures: [Error] = []
var reclaimFinished = false
reclaimRecorder.failed = { error in
  captureState.lock()
  captureFailures.append(error)
  captureState.unlock()
}
let capturing = DispatchGroup()
DispatchQueue.global().async(group: capturing) {
  var second = 0.0
  while captureState.withLock({ !reclaimFinished }) {
    do {
      for index in 0..<8 { try reclaimRecorder.admit(frame(70 + second + Double(index) / 8)) }
      try reclaimRecorder.barrier()
      captureState.withLock {
        captureCommits += 1
        captureState.broadcast()
      }
    } catch {
      captureState.withLock { captureFailures.append(error) }
    }
    second += 1
  }
}
var commitWhileReclaiming: Bool?
reclaimArchive.store.reclaimStepObserverForTesting = { action in
  guard action == .reclaim, commitWhileReclaiming == nil else { return }
  captureState.lock()
  let before = captureCommits
  let deadline = Date().addingTimeInterval(5)
  while captureCommits == before, captureState.wait(until: deadline) {}
  commitWhileReclaiming = captureCommits > before
  captureState.unlock()
}
let reclaimed = reclaimArchive.store.reclaimSpace()
reclaimArchive.store.reclaimStepObserverForTesting = nil
captureState.withLock { reclaimFinished = true }
capturing.wait()
try reclaimRecorder.barrier()
check(reclaimed.failure == nil && reclaimed.steps > 1, "the deleted ride is reclaimed in several steps")
check(commitWhileReclaiming == true, "a capture batch commits while the reclaim is between steps")
check(captureFailures.isEmpty, "no capture batch fails while space is reclaimed")
check(
  try reclaimArchive.metadata(id: recording.id).eventCount == 8 * captureCommits,
  "every capture batch committed during the reclaim is retained")
check(
  try reclaimArchive.store.read { try $0.rows("PRAGMA integrity_check", limit: 1).first?.string("integrity_check") }
    == "ok", "capture during reclaim keeps the store intact")
print("Phone canonical capture: \(checks) checks passed")
