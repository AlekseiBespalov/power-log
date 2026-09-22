import Foundation
import Darwin

var checks = 0
func check(_ value: Bool, _ label: String) { checks += 1; if !value { fatalError(label) } }
func rejects(_ operation: () throws -> Void, _ label: String) {
  do { try operation(); fatalError(label) } catch { checks += 1 }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-capture-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let date = Date(timeIntervalSince1970: 1_780_000_000)
let epoch = UUID().uuidString.lowercased()
let live = UUID().uuidString.lowercased()
var clock = CycCaptureClock(origin: 100, wallOrigin: date, sessionID: live, epochID: epoch)
func frame(_ seconds: Double, liveID: String = live) -> PowerLogCaptureFrame {
  let sample = clock.observation(["humanPowerW": 123.25, "cadenceRpm": 81.5, "batteryVoltageV": 52.8,
    "batteryCurrentA": 4, "motorInputPowerW": 211.2], monotonic: 100 + seconds, wall: date.addingTimeInterval(seconds))
  return PowerLogCaptureFrame(sample: sample, liveID: liveID, liveStartedAt: WorkoutCoding.timestamp(date), liveOrigin: 100, liveElapsed: seconds)
}
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("main/workouts"))
let store = archive.store
let ride = try archive.create(startedAt: date, indoor: true, watchEnabled: false)
let nextRide = try archive.create(startedAt: date, indoor: true, watchEnabled: false)
let anchor = WorkoutTimelineAnchor(epoch: epoch, monotonicOrigin: 100, startedAt: WorkoutCoding.timestamp(date))
let inbox = PowerLogCaptureInbox()
inbox.setDestination(PowerLogCaptureDestination(id: ride.id, generation: UUID(), timeline: anchor))
check(try inbox.admit(frame(0)), "first admitted frame requests one owner wake")
check(try !inbox.admit(frame(0.125)), "an occupied inbox coalesces owner wakeups")
inbox.setDestination(PowerLogCaptureDestination(id: nextRide.id, generation: UUID(), timeline: anchor))
check(inbox.first?.ride?.id == ride.id, "queued frame retains original ride after ownership changes")
let batch = PowerLogCaptureBatch()
for admitted in inbox.take(upTo: 64) {
  try batch.append(PowerLogCaptureRecord(frame: admitted, ride: admitted.mappedRide()), at: 0)
}
enum Fault: Error { case disk }
store.beforeCommitForTesting = { throw Fault.disk }
rejects({ _ = try batch.flush(store: store) }, "transaction failure must surface")
check(batch.records.count == 2, "failure retains complete final partial batch")
check(try store.read { try $0.scalarInt("SELECT count(*) FROM observations") } == 0, "failed batch persists no partial originals")
check(try archive.metadata(id: ride.id).eventCount == 0, "failed batch persists no partial ride membership")
store.beforeCommitForTesting = nil
let committed = try batch.flush(store: store)
check(committed.count == 2 && batch.isEmpty, "retry succeeds without another controller packet")
check(try archive.metadata(id: ride.id).eventCount == 2, "both frames remain assigned to first ride")
check(try archive.metadata(id: nextRide.id).eventCount == 0, "delayed flush never writes into next ride")
check(try store.read { try $0.scalarInt("SELECT count(*) FROM observations") } == 2, "live and ride share physical originals")
check(try store.read { try $0.scalarInt("SELECT count(*) FROM collection_memberships") } == 4, "both memberships committed atomically")
check(try store.read { try $0.scalarInt("PRAGMA synchronous") } == 2, "production capture uses FULL durability")

let replay = PowerLogCaptureBatch()
let first = try archive.pageEvents(id: ride.id).first!.event
var replayFrame = PowerLogCaptureFrame(sample: first.payload.mapValues(\.any), liveID: live,
  liveStartedAt: WorkoutCoding.timestamp(date), liveOrigin: 100, liveElapsed: 0)
replayFrame.ride = PowerLogCaptureDestination(id: ride.id, generation: UUID(), timeline: anchor)
try replay.append(PowerLogCaptureRecord(frame: replayFrame, ride: first), at: 1)
_ = try replay.flush(store: store)
check(try archive.metadata(id: ride.id).eventCount == 2, "replaying committed IDs is idempotent")

var cutoff = anchor; cutoff.stopMonotonic = 100.5; cutoff.stopUTC = WorkoutCoding.timestamp(date.addingTimeInterval(0.5))
inbox.setDestination(PowerLogCaptureDestination(id: ride.id, generation: UUID(), timeline: cutoff))
_ = try inbox.admit(frame(0.5)); _ = try inbox.admit(frame(0.625))
let endFrames = inbox.take(upTo: 64)
check(try endFrames[0].mappedRide() != nil && endFrames[1].mappedRide() == nil, "frozen cutoff includes boundary and excludes later acquisition")
let tail = PowerLogCaptureBatch()
for value in endFrames { try tail.append(PowerLogCaptureRecord(frame: value, ride: value.mappedRide()), at: 2) }
_ = try tail.flush(store: store)
check(try archive.metadata(id: ride.id).eventCount == 3, "stop tail includes only eligible original")
check(try store.collection(id: live).int("event_count") == 4, "post-stop preview remains live-only")
let ownerFallback = try PowerLogCaptureCutoff.owner(anchor, at: date.addingTimeInterval(0.5), elapsedSeconds: nil)
check(ownerFallback.stopMonotonic == 100.5, "missing owner elapsed derives cutoff from original owner UTC and anchor")
check(try endFrames[1].mappedRide(timeline: ownerFallback) == nil, "status without elapsed never admits post-stop bike data")
check(try PowerLogCaptureCutoff.owner(ownerFallback, at: date.addingTimeInterval(20), elapsedSeconds: 20) == ownerFallback,
  "later owner delivery cannot extend a committed capture cutoff")
rejects({ _ = try PowerLogCaptureCutoff.owner(anchor, at: date.addingTimeInterval(-1), elapsedSeconds: nil) },
  "untrustworthy stop before actual start is rejected")
rejects({ _ = try PowerLogCaptureCutoff.owner(anchor, at: date.addingTimeInterval(1), elapsedSeconds: .nan) },
  "malformed owner elapsed is not silently replaced with invented time")

let nextLive = UUID().uuidString.lowercased()
let retained = PowerLogCaptureBatch()
try retained.append(PowerLogCaptureRecord(frame: frame(1), ride: nil), at: 3)
try retained.append(PowerLogCaptureRecord(frame: frame(1.125, liveID: nextLive), ride: nil), at: 3)
store.beforeCommitForTesting = { throw Fault.disk }
rejects({ _ = try retained.flush(store: store) }, "reconnect batch can fail without remapping epochs")
store.beforeCommitForTesting = nil
_ = try retained.flush(store: store)
check(try store.collection(id: live).int("event_count") == 5, "old live epoch survives failed reconnect batch")
check(try store.collection(id: nextLive).int("event_count") == 1, "new live epoch has its own membership")

let pressure = PowerLogCaptureInbox()
pressure.setDestination(PowerLogCaptureDestination(id: ride.id, generation: UUID(), timeline: anchor))
for i in 0..<PowerLogCaptureInbox.maximumFrames { _ = try pressure.admit(frame(2 + Double(i) / 8)) }
rejects({ _ = try pressure.admit(frame(20)) }, "capture admission is bounded and explicitly fails under pressure")
check(pressure.count == PowerLogCaptureInbox.maximumFrames, "overflow cannot overwrite retained originals")
let fault = pressure.fault!
check(fault.workoutID == ride.id, "overflow retains recording identity in a bounded fault latch")
_ = pressure.take(upTo: 1)
pressure.setDestination(PowerLogCaptureDestination(id: nextRide.id, generation: UUID(), timeline: anchor))
_ = try pressure.admit(frame(20.125))
check(pressure.take(upTo: 64).last?.ride == nil, "uncommitted capture fault prevents silently healthy admission")
store.beforeCommitForTesting = { throw Fault.disk }
rejects({ try fault.persist(archive: archive) }, "fault persistence failure is retriable")
check(pressure.fault?.id == fault.id, "fault survives until durable acknowledgement")
store.beforeCommitForTesting = nil
try fault.persist(archive: archive); pressure.acknowledgeFault(fault.id)
check(try archive.metadata(id: ride.id).warnings.contains(fault.message), "overflow remains a durable ride notice after later successful writes")
check(try store.read { try $0.get(namespace: "capture-faults", key: fault.id) } != nil, "admission gap identity remains available for diagnosis")

check(CycReconnectPolicy.mayRetry(attempt: 6, activeRide: true), "active ride retains connection intent after fast retry budget")
check(!CycReconnectPolicy.mayRetry(attempt: 6, activeRide: false), "idle preview retains finite retry budget")
check(CycReconnectPolicy.recoveryDelay(attempt: 60, activeRide: true, confirmedPeerDisconnect: false, stableTelemetrySeconds: nil) == 30,
  "long recovery has bounded low-duty backoff")
let cancellationWait = CycPollingSchedule.delay(now: 100, poweredOn: true, verified: false, scanning: false,
  scanDeadline: nil, reconnectDue: 99, connectionDeadline: 105, cancellationPending: true,
  responseDeadline: nil, writeDeadline: nil, nextPoll: nil, sampleDeadline: nil)
check(cancellationWait == 5, "past reconnect deadline does not create a 200 Hz cancellation spin")
let poweredOffWait = CycPollingSchedule.delay(now: 100, poweredOn: false, verified: true, scanning: false,
  scanDeadline: 90, reconnectDue: 90, connectionDeadline: 90, cancellationPending: false,
  responseDeadline: 90, writeDeadline: 90, nextPoll: 90, sampleDeadline: 90)
check(poweredOffWait == 5, "Bluetooth-off ignores expired transport deadlines")
check(CycPollingSchedule.delay(now: 100, poweredOn: true, verified: true, scanning: false,
  scanDeadline: nil, reconnectDue: nil, connectionDeadline: nil, cancellationPending: false,
  responseDeadline: nil, writeDeadline: nil, nextPoll: 100.125, sampleDeadline: nil) == 0.125,
  "active polling preserves exact 8 Hz native cadence")

// This is the same one-way queue topology as CycEngine -> inbox -> WorkoutEngine.
let ble = DispatchQueue(label: "test.capture.ble")
let owner = DispatchQueue(label: "test.capture.owner")
let barrierInbox = PowerLogCaptureInbox()
let boundaryReady = DispatchSemaphore(value: 0)
ble.async { _ = try! barrierInbox.admit(frame(21)); boundaryReady.signal() }
owner.sync {
  ble.sync {}
  let boundaryCount = barrierInbox.count
  check(boundaryCount == 1, "upstream fence admits earlier BLE callback before stop drain")
  _ = try! barrierInbox.admit(frame(22))
  check(barrierInbox.take(upTo: boundaryCount).count == 1 && barrierInbox.count == 1,
    "boundary drain is finite while later preview frames continue arriving")
}
boundaryReady.wait()

func cpuSeconds() -> Double {
  var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
  return Double(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) + Double(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1_000_000
}
func benchmark(path: String) throws -> [String: Any] {
  let batched = path == "unified"
  let bufferedLive = path == "separateBuffered"
  let dir = root.appendingPathComponent(path)
  let archive = try WorkoutArchive(rootURL: dir.appendingPathComponent("workouts"))
  let store = archive.store
  let ride = try archive.create(startedAt: date, indoor: true, watchEnabled: false)
  let liveID = UUID().uuidString.lowercased()
  try store.ensureLiveCollection(id: liveID, startedAt: WorkoutCoding.timestamp(date), monotonicOrigin: 100)
  _ = try store.read { db in try db.rows("PRAGMA wal_autocheckpoint=0", limit: 1) }
  let initialCommits = (try store.diagnostics()["commits"] as? Double) ?? 0
  let initialWAL = (try store.diagnostics()["walBytes"] as? Int64) ?? 0
  let batch = PowerLogCaptureBatch()
  let inbox = PowerLogCaptureInbox()
  inbox.setDestination(PowerLogCaptureDestination(id: ride.id, generation: UUID(), timeline: anchor))
  var oldRideBatch: [WorkoutEvent] = []
  var oldLiveBatch: [PowerLogCaptureFrame] = []
  func flushOldLive() throws {
    guard !oldLiveBatch.isEmpty else { return }
    try store.transaction { _ in
      for value in oldLiveBatch {
        _ = try store.appendTelemetry(value.sample, collectionID: liveID, elapsedSeconds: value.liveElapsed)
      }
    }
    oldLiveBatch.removeAll(keepingCapacity: true)
  }
  let began = ProcessInfo.processInfo.systemUptime, cpu = cpuSeconds()
  for i in 0..<480 {
    let value = frame(100 + Double(i) / 8, liveID: liveID)
    _ = try inbox.admit(value)
    let admitted = inbox.take(upTo: 1)[0]
    let event = try admitted.mappedRide()!
    if batched {
      try batch.append(PowerLogCaptureRecord(frame: admitted, ride: event), at: Double(i) / 8)
      if batch.records.count >= PowerLogCaptureBatch.targetFrames { _ = try batch.flush(store: store) }
    } else {
      oldLiveBatch.append(admitted)
      if !bufferedLive || oldLiveBatch.count == 8 { try flushOldLive() }
      oldRideBatch.append(event)
      if oldRideBatch.count == 16 { _ = try archive.appendBatch(oldRideBatch); oldRideBatch.removeAll(keepingCapacity: true) }
    }
  }
  _ = try batch.flush(store: store)
  try flushOldLive()
  if !oldRideBatch.isEmpty { _ = try archive.appendBatch(oldRideBatch) }
  let cpuTime = cpuSeconds() - cpu, wall = ProcessInfo.processInfo.systemUptime - began
  check(try archive.metadata(id: ride.id).eventCount == 480, "benchmark retains every ride original")
  check(try store.collection(id: liveID).int("event_count") == 480, "benchmark retains every live original")
  check(try store.read { try $0.scalarInt("SELECT count(*) FROM observations") } == 480, "benchmark stores physical originals once")
  let diagnostics = try store.diagnostics()
  return ["path": path, "samples": 480,
    "commits": (diagnostics["commits"] as? Double ?? 0) - initialCommits,
    "cpuSeconds": cpuTime, "wallSeconds": wall, "walGrowthBytes": (diagnostics["walBytes"] as? Int64 ?? 0) - initialWAL]
}
let old = try benchmark(path: "separate"), buffered = try benchmark(path: "separateBuffered"), new = try benchmark(path: "unified")
check((new["commits"] as! Double) < (old["commits"] as! Double) / 8, "unified capture reduces transaction count by more than eightfold")
check((new["commits"] as! Double) < (buffered["commits"] as! Double), "unified capture also reduces commits against separately buffered background writes")
print(String(data: try JSONSerialization.data(withJSONObject: [old, buffered, new], options: [.sortedKeys]), encoding: .utf8)!)
print("Capture: \(checks) checks passed; benchmark is synthetic macOS SQLite work, not physical iPhone acceptance.")
