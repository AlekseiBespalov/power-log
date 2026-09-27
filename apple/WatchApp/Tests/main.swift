import Foundation

var assertions = 0
func check(_ value: Bool, line: UInt = #line) {
  assertions += 1
  precondition(value, "Check failed at line \(line)")
}

// The same original sample changes only presentation as time passes; it expires at six seconds.
check(WatchReadingFreshness.heartRate(value: 123, age: 14.9, running: true) == "123")
check(WatchReadingFreshness.heartRate(value: 123, age: 15, running: true) == "—")
check(WatchReadingFreshness.heartRate(value: 123, age: nil, running: true) == "—")
check(WatchReadingFreshness.heartRate(value: 123, age: 0, running: false) == "—")
check(WatchReadingFreshness.bikeSample(age: nil, running: true) == .unavailable)
check(WatchReadingFreshness.bikeSample(age: 0, running: true) == .live)
check(WatchReadingFreshness.bikeSample(age: 2.5, running: true) == .live)
check(WatchReadingFreshness.bikeSample(age: 2.500_001, running: true) == .held)
check(WatchReadingFreshness.bikeSample(age: 3, running: true) == .held)
check(WatchReadingFreshness.bikeSample(age: 5.999, running: true) == .held)
check(WatchReadingFreshness.bikeSample(age: 6, running: true) == .unavailable)
check(WatchReadingFreshness.bikeSample(age: 6.000_001, running: true) == .unavailable)
check(WatchReadingFreshness.bikeSample(age: 60, running: true) == .unavailable)
check(WatchReadingFreshness.bikeSample(age: 0, running: false) == .unavailable)
check(WatchReadingFreshness.bikeSample(age: 4, running: false) == .unavailable)
check(WatchReadingFreshness.bikeSample(age: -2, running: true) == .unavailable)
check(WatchReadingFreshness.bikeSample(age: -2.000_001, running: true) == .unavailable)
check(WatchReadingFreshness.bikeSample(age: .infinity, running: true) == .unavailable)
check(WatchReadingFreshness.bikeSample(age: .nan, running: true) == .unavailable)
print("Watch reading freshness: 15 boundary checks passed")

// Exercise the policy used by the Watch's actual launch handler and idle view.
// A HealthKit launch is not a recording and says nothing about phone reachability.
let launchUptime = 1_000.0
var handoff = WatchStartHandoff()
handoff.begin(at: launchUptime, phase: "ready", hasRide: false, canStart: true, hasIssue: false)
check(handoff.isWaiting(at: launchUptime))
check(handoff.isWaiting(at: launchUptime + 44.999))
check(!handoff.isWaiting(at: launchUptime + 45))
check(!handoff.isWaiting(at: launchUptime - 1))
// Repeated delivery inside the window cannot extend the spinner.
handoff.begin(at: launchUptime + 44, phase: "ready", hasRide: false, canStart: true, hasIssue: false)
check(!handoff.isWaiting(at: launchUptime + 46))
handoff.begin(at: launchUptime + 60, phase: "ready", hasRide: false, canStart: true, hasIssue: false)
check(handoff.isWaiting(at: launchUptime + 104.999))
check(!handoff.isWaiting(at: launchUptime + 105))
handoff.begin(at: launchUptime - 1, phase: "ready", hasRide: false, canStart: true, hasIssue: false)
check(handoff.isWaiting(at: launchUptime + 43.999))
check(!handoff.isWaiting(at: launchUptime + 44))
handoff.expire(at: launchUptime + 43.999)
check(handoff.beganAt != nil)
handoff.expire(at: launchUptime + 44)
check(handoff.beganAt == nil)
handoff.clear()
check(!handoff.isWaiting(at: launchUptime))
for phase in ["running", "paused", "preparing", "finished", "failed", "recoverable"] {
  handoff.begin(at: launchUptime, phase: phase, hasRide: false, canStart: true, hasIssue: false)
  check(handoff.beganAt == nil)
}
handoff.begin(at: launchUptime, phase: "ready", hasRide: true, canStart: true, hasIssue: false)
check(handoff.beganAt == nil)
handoff.begin(at: launchUptime, phase: "ready", hasRide: false, canStart: false, hasIssue: false)
check(handoff.beganAt == nil)
handoff.begin(at: launchUptime, phase: "ready", hasRide: false, canStart: true, hasIssue: true)
check(handoff.beganAt == nil)

func idle(
  _ phase: String = "ready", busy: Bool = false, recovering: Bool = false, finishing: Bool = false,
  stopPending: Bool = false, native: Bool = false, discarded: Bool = false, discardPending: Bool = false,
  issue: Bool = false, waiting: Bool = false
) -> WatchWorkoutPresentation {
  WatchWorkoutPresentation.idle(
    phase: phase, busy: busy, recovering: recovering, finishing: finishing,
    stopPending: stopPending, hasNativeSession: native, discarded: discarded, discardPending: discardPending,
    hasIssue: issue, awaitingStart: waiting)
}
check(idle() == .ready)
check(idle(waiting: true) == .preparing)
check(idle(issue: true, waiting: true) == .ready)  // The existing failure remains the visible issue.
check(idle("failed", issue: true, waiting: true) == .startFailed)
check(idle("recoverable", issue: true, waiting: true) == .attention)
check(idle(busy: true) == .preparing)
check(idle(recovering: true) == .preparing)
// Native stop/end may be asynchronous after phase has already become finished.
check(idle("finished", finishing: true) == .saving)
check(idle("finished", stopPending: true) == .saving)
check(idle("finished", native: true) == .saving)
check(idle("finished", native: true, issue: true) == .attention)
check(idle("finished", stopPending: true, issue: true) == .attention)
check(idle("finished", finishing: true, discardPending: true) == .discarding)
check(idle("finished", discardPending: true) == .discarding)
check(idle("finished", discardPending: true, issue: true) == .attention)
check(idle("finished", discarded: true, waiting: true) == .discarded)
check(idle("finished", waiting: true) == .saved)  // A late launch cannot replace a saved ride.
check(idle("finished", issue: true) == .saved)  // A separate Health failure does not erase local storage.
check(WatchWorkoutPresentation.saving.showsProgress)
check(!WatchWorkoutPresentation.saved.showsProgress)
check(!WatchWorkoutPresentation.attention.showsProgress)
check(
  WatchWorkoutPresentation.savedDetail(saveToHealth: false, healthState: "notRequested")
    == "Saved on Watch. Health saving is off.")
check(WatchWorkoutPresentation.savedDetail(saveToHealth: true, healthState: "saved") == "Saved on Watch and in Health.")
check(
  WatchWorkoutPresentation.savedDetail(saveToHealth: true, healthState: "pending")
    == "Saved on Watch. Health save needs attention.")
check(
  WatchWorkoutPresentation.savedDetail(saveToHealth: true, healthState: "failed")
    == "Saved on Watch. Health save needs attention.")
print("Watch launch and save presentation: 46 checks passed")

// A notification arriving while a short final page is being read must not disappear.
// This models real anchored reads: each page sees a fixed database snapshot.
var drain = WatchQueryDrain()
var database = Array(0..<600)
var anchor = 0
var observed: [Int] = []
var pages = 0
check(drain.invalidate())
while drain.isReading {
  pages += 1
  let page = Array(database.dropFirst(anchor).prefix(256))
  if pages == 3 {
    database.append(contentsOf: 600..<900)
    check(!drain.invalidate())  // Coalesce; never launch a second concurrent page.
    check(!drain.invalidate())
  }
  observed.append(contentsOf: page)
  anchor += page.count
  let readAnother = drain.finishPage(hasMore: page.count == 256)
  check(readAnother == drain.isReading)
}
check(observed == database)
check(pages == 5)

// An exact full page of deletion tombstones also requires another read.
check(drain.invalidate())
check(drain.finishPage(hasMore: true))
check(!drain.finishPage(hasMore: false))
check(!drain.isReading)

// Cancellation ignores late page results and invalidations; a new generation starts independently.
check(drain.invalidate())
check(!drain.invalidate())
drain.stop()
check(!drain.finishPage(hasMore: true))
check(!drain.invalidate())
check(!drain.isReading)
var recoveredDrain = WatchQueryDrain()
check(recoveredDrain.invalidate())
recoveredDrain.fail()
check(!recoveredDrain.isReading)
check(recoveredDrain.invalidate())  // Retry from the last durably committed anchor.
check(!recoveredDrain.finishPage(hasMore: false))
print("Watch bounded Health query lifecycle checks passed")

let root = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-watch-tests-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: root) }
let journal = try WatchWorkoutJournal(rootURL: root)
let id = UUID().uuidString.lowercased()
let stamp = Date(timeIntervalSince1970: 1_800_000_000)
try journal.create(
  id: id,
  metadata: [
    "workoutId": id, "startedAt": WorkoutCoding.timestamp(stamp),
    "saveToHealth": true, "recordGPS": true, "phase": "running",
  ])
func event(_ number: Int, source: String = "watch") throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: id, kind: source == "cyc" ? "telemetry" : "health", source: source,
    timestamp: stamp.addingTimeInterval(Double(number)), elapsedSeconds: Double(number),
    payload: ["heartRateBpm": .number(Double(100 + number))])
}
let first = try event(1)
let second = try event(2)
check(try journal.archive.appendBatch([first]) == 1)
check(try journal.archive.appendBatch([second]) == 1)
check(try journal.archive.appendBatch([first, second]) == 0)
check(try journal.archive.hasEvent(id: id, eventID: first.eventId))
check(try encodedRecords(journal, id: id).count == 2)
check(
  !FileManager.default.fileExists(atPath: root.appendingPathComponent(id).appendingPathComponent("records.jsonl").path))
let reopened = try WatchWorkoutJournal(rootURL: root)
check(try encodedRecords(reopened, id: id).count == 2)
check(reopened.store === journal.store)

let metadataJournal = try WatchWorkoutJournal(rootURL: root.appendingPathComponent("metadata"))
let earlierID = "00000000-0000-0000-0000-000000000001"
let laterID = "00000000-0000-0000-0000-000000000002"
let earlierMetadata: [String: Any] = [
  "workoutId": earlierID, "startedAt": WorkoutCoding.timestamp(stamp),
  "phase": "running", "indoor": false, "saveToHealth": false, "recordGPS": false,
]
try metadataJournal.create(id: earlierID, metadata: earlierMetadata)
try metadataJournal.create(
  id: laterID,
  metadata: [
    "workoutId": laterID, "startedAt": WorkoutCoding.timestamp(stamp.addingTimeInterval(1)),
    "phase": "running", "indoor": true, "saveToHealth": true, "recordGPS": true,
  ])
let metadataRestart = try WatchWorkoutJournal(rootURL: metadataJournal.directory)
check(try metadataRestart.allMetadata().compactMap { $0["workoutId"] as? String } == [laterID, earlierID])
check(try metadataRestart.archive.metadata(id: earlierID).saveToHealth == false)
check(try metadataRestart.archive.metadata(id: earlierID).recordGPS == false)
check(try metadataRestart.archive.metadata(id: laterID).saveToHealth == true)
check(try metadataRestart.archive.metadata(id: laterID).recordGPS == true)
for field in ["saveToHealth", "recordGPS", "startedAt"] {
  for invalid in [nil, "invalid"] as [String?] {
    let rejectedID = UUID().uuidString.lowercased()
    var rejected = earlierMetadata
    rejected["workoutId"] = rejectedID
    rejected[field] = invalid
    do {
      try metadataJournal.create(id: rejectedID, metadata: rejected)
      fatalError("Invalid Watch metadata created a ride")
    } catch {}
    check(try metadataJournal.store.read { try $0.get(namespace: "watch-metadata", key: rejectedID) } == nil)
    rejected["workoutId"] = earlierID
    do {
      try metadataJournal.save(id: earlierID, metadata: rejected)
      fatalError("Invalid Watch metadata replaced a ride")
    } catch {}
    check(
      try metadataJournal.metadata(id: earlierID)["startedAt"] as? String == earlierMetadata["startedAt"] as? String)
    try metadataJournal.store.transaction { db in
      try db.put(namespace: "watch-metadata", key: earlierID, value: JSONSerialization.data(withJSONObject: rejected))
    }
    do {
      _ = try metadataRestart.metadata(id: earlierID)
      fatalError("Invalid Watch metadata read")
    } catch {}
    do {
      _ = try metadataRestart.allMetadata()
      fatalError("Invalid Watch metadata entered recovery")
    } catch {}
    do {
      _ = try metadataRestart.metadataPage()
      fatalError("Invalid Watch metadata entered sync")
    } catch {}
    try metadataJournal.save(id: earlierID, metadata: earlierMetadata)
  }
}
for field in ["saveToHealth", "recordGPS"] {
  var changed = earlierMetadata
  changed[field] = true
  do {
    try metadataJournal.save(id: earlierID, metadata: changed)
    fatalError("Frozen recording option changed")
  } catch {}
  check(try metadataJournal.metadata(id: earlierID)[field] as? Bool == false)
}
print("Watch required metadata, frozen recording options and start ordering checks passed")

let stableVectors = [
  ("deleted:00000000-0000-0000-0000-000000000001", "8876706e-0381-586d-8698-70225f42e121"),
  ("state:00000000-0000-0000-0000-000000000001:3:1800000000.0", "d275131b-a781-521e-b77a-21ecb69666b8"),
]
for (identity, expected) in stableVectors {
  check(WorkoutStableIdentity.uuid(identity) == expected)
}
let rawQuantity = try WorkoutEvent(
  workoutId: earlierID, kind: "health", source: "watch", timestamp: stamp,
  payload: ["representation": .string("rawQuantity"), "sampleUUID": .string(earlierID)], eventId: earlierID)
check(try metadataJournal.archive.appendBatch([rawQuantity]) == 1)
check(try metadataRestart.archive.hasEvent(id: earlierID, eventID: earlierID))
check(try metadataRestart.archive.appendBatch([rawQuantity]) == 0)
check(try metadataRestart.archive.metadata(id: earlierID).eventCount == 1)
print("Watch stable identities retain raw quantity deduplication across journal reconstruction")

// The actual owner reducer rejects stale and conflicting same-revision snapshots, across restart.
let control = journal.control
let start = try control.create(
  workoutID: id, origin: "phone", action: "start", at: stamp,
  options: ["saveToHealth": .bool(true), "recordGPS": .bool(true)])
check(try control.accept(start).outcome == "accepted")
check(try control.begin(start).outcome == "executing")
let running = try control.observe(
  workoutID: id, owner: "watch", phase: "running", at: stamp,
  health: "pending", command: start)
let pause = try control.create(workoutID: id, origin: "phone", action: "pause", at: stamp.addingTimeInterval(2))
let resume = try control.create(workoutID: id, origin: "phone", action: "resume", at: stamp.addingTimeInterval(3))
check(try control.accept(resume).missingOriginSequence == pause.originSequence)
check(try control.begin(resume).outcome == "accepted")
_ = try control.accept(pause)
_ = try control.begin(pause)
let paused = try control.observe(
  workoutID: id, owner: "watch", phase: "paused", at: stamp.addingTimeInterval(2), health: "pending", command: pause)
check(try control.begin(resume).outcome == "executing")
let resumed = try control.observe(
  workoutID: id, owner: "watch", phase: "running", at: stamp.addingTimeInterval(3), health: "pending", command: resume)
check(resumed.ownerRevision == 3)
check(try !control.accept(snapshot: paused))
check(try !reopened.control.accept(snapshot: running))
var conflicting = resumed
conflicting.phase = "paused"
do {
  _ = try control.accept(snapshot: conflicting)
  fatalError("Conflicting revision accepted")
} catch {}
let duplicate = try WorkoutCommand(
  id: pause.id, workoutID: id, origin: "phone", originSequence: pause.originSequence, action: "stop", requestedAt: stamp
)
do {
  _ = try control.accept(duplicate)
  fatalError("Changed command content accepted")
} catch {}

// An applied side effect whose local receipt fails stays executing and requires reconciliation.
let lap = try control.create(workoutID: id, origin: "phone", action: "lap", at: stamp.addingTimeInterval(4))
_ = try control.accept(lap)
_ = try control.begin(lap)
var externalLapIDs = Set([lap.id])  // Native event metadata already contains the stable operation ID.
enum InjectedFailure: Error { case commit }
journal.store.beforeCommitForTesting = { throw InjectedFailure.commit }
do {
  _ = try control.observe(workoutID: id, owner: "watch", phase: "running", at: stamp, health: "pending", command: lap)
  fatalError("Injected receipt failure ignored")
} catch {}
journal.store.beforeCommitForTesting = nil
check(try reopened.control.result(id: lap.id)?.outcome == "executing")
check(try reopened.control.active(workoutID: id)?.id == lap.id)
if !externalLapIDs.contains(lap.id) { externalLapIDs.insert(lap.id) }
_ = try reopened.control.observe(
  workoutID: id, owner: "watch", phase: "running", at: stamp, health: "pending", command: lap)
check(externalLapIDs.count == 1)
check(try control.accept(lap).outcome == "applied")

// Durable replay results are not evicted after 256 commands.
for number in 0..<270 {
  let command = try control.create(
    workoutID: id, origin: "phone", action: "lap", at: stamp.addingTimeInterval(Double(number + 5)))
  _ = try control.accept(command)
  _ = try control.begin(command)
  _ = try control.observe(
    workoutID: id, owner: "watch", phase: "running", at: stamp, health: "pending", command: command)
}
check(try control.accept(lap).outcome == "applied")
let stop = try control.create(workoutID: id, origin: "phone", action: "stop", at: stamp.addingTimeInterval(400))
_ = try control.accept(stop)
_ = try control.begin(stop)
let ended = try control.observe(
  workoutID: id, owner: "watch", phase: "completed", at: stamp.addingTimeInterval(410),
  health: "pending", cutoff: WorkoutCoding.date(stop.requestedAt), command: stop,
  timing: WorkoutOwnerTiming(timestamp: stop.requestedAt, elapsedSeconds: 400, timerSeconds: 400))
check(ended.stopCutoff == stop.requestedAt)
var regression = ended
regression.ownerRevision += 1
regression.phase = "running"
do {
  _ = try control.accept(snapshot: regression)
  fatalError("Ended owner resumed")
} catch {}

// Incoming chunk receipt bypasses decoding and insertion on matching retry; changed identity conflicts.
let destination = root.appendingPathComponent("receiver")
let receiverArchive = try WorkoutArchive(rootURL: destination)
_ = try receiverArchive.create(
  id: id, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
let receiver = WorkoutTransferJournal(archive: receiverArchive)
let chunk = try WorkoutChunkCodec.encode(workoutID: id, producer: "watch", firstSequence: 1, events: [first, second])
var decoded = 0
receiver.onDecode = { decoded += 1 }
check(try receiver.receive(chunk))
check(try !receiver.receive(chunk))
check(decoded == 1)
let changed = try WorkoutChunkCodec.encode(
  workoutID: id, producer: "watch", firstSequence: 1, events: [try event(3), second])
do {
  _ = try receiver.receive(changed)
  fatalError("Chunk identity conflict ignored")
} catch {}
check(decoded == 1)
let third = try event(3)
let thirdChunk = try WorkoutChunkCodec.encode(workoutID: id, producer: "watch", firstSequence: 3, events: [third])
receiverArchive.store.beforeCommitForTesting = { throw InjectedFailure.commit }
do {
  _ = try receiver.receive(thirdChunk)
  fatalError("Chunk partial commit survived")
} catch {}
receiverArchive.store.beforeCommitForTesting = nil
check(try receiverArchive.metadata(id: id).eventCount == 2)
check(
  try receiverArchive.store.read { db in try db.get(namespace: "chunk-receipts", key: thirdChunk.manifest.identity) }
    == nil)
check(try receiver.receive(thirdChunk))
check(try receiverArchive.metadata(id: id).eventCount == 3)

// Highest observed sequence is not proof of completeness: holes block exact seal verification.
let late = try event(5)
let hole = try WorkoutChunkCodec.encode(workoutID: id, producer: "watch", firstSequence: 5, events: [late])
_ = try receiver.receive(hole)
do {
  _ = try receiver.source(id: id, producer: "watch")
  fatalError("Hole concealed")
} catch {}
let fourth = try event(4)
_ = try receiver.receive(WorkoutChunkCodec.encode(workoutID: id, producer: "watch", firstSequence: 4, events: [fourth]))
let source = try receiver.source(id: id, producer: "watch")
let seal1 = WorkoutSeal(
  workoutID: id, sealRevision: 1, collectionRevision: 1, ownerRevision: ended.ownerRevision,
  stopCutoff: stop.requestedAt, healthOutcome: "saved", requirements: ["ownerEnded": "sealed", "extraction": "sealed"],
  sources: [source],
  stopElapsedSeconds: 400, timerSeconds: 400, saveToHealth: true, recordGPS: false)
check(try receiver.accept(seal: seal1))
check(try receiver.verify(id: id))
check(try receiverArchive.metadata(id: id).finalizationState == "complete")
var pending = source
pending.outcome = "pending"
let seal2 = WorkoutSeal(
  workoutID: id, sealRevision: 2, collectionRevision: 2, ownerRevision: ended.ownerRevision,
  stopCutoff: stop.requestedAt, healthOutcome: "saved",
  requirements: ["ownerEnded": "sealed", "extraction": "pending"], sources: [pending],
  stopElapsedSeconds: 400, timerSeconds: 400, saveToHealth: true, recordGPS: false)
check(try receiver.accept(seal: seal2))
check(try !receiver.verify(id: id))
check(try !receiver.accept(seal: seal1))
check(try receiver.currentSeal(id: id)?.sealRevision == 2)
check(try receiverArchive.metadata(id: id).finalizationState == "pending")

// Health records and query progress are one transaction, so a failed page is fully retryable.
let pageEvent = try event(6)
journal.store.beforeCommitForTesting = { throw InjectedFailure.commit }
do {
  try journal.commitHealthPage(id: id, events: [pageEvent], progressKey: "heart", anchor: Data([1, 2, 3]))
  fatalError("Health page fault ignored")
} catch {}
journal.store.beforeCommitForTesting = nil
check(try journal.healthAnchor(id: id, progressKey: "heart") == nil)
check(try !journal.archive.hasEvent(id: id, eventID: pageEvent.eventId))
try journal.commitHealthPage(id: id, events: [pageEvent], progressKey: "heart", anchor: Data([1, 2, 3]))
check(try journal.healthAnchor(id: id, progressKey: "heart") == Data([1, 2, 3]))
check(try journal.archive.hasEvent(id: id, eventID: pageEvent.eventId))
print("Watch SQLite production control/receipt/seal/Health-page fault and restart checks passed")

// The production effect boundary permits one fresh execution and requires reconciliation on restart.
let effectID = UUID().uuidString.lowercased()
let effectCommand = try control.create(
  workoutID: effectID, origin: "watch", action: "start", at: stamp,
  options: ["saveToHealth": .bool(true), "recordGPS": .bool(false)])
check(try control.prepare(effectCommand).execute)
check(try reopened.control.prepare(effectCommand).reconcile)

// A -> B -> A totals are three immutable revisions; retrying the current A is a no-op.
func total(_ value: Double) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: id, kind: "health", source: "watch", timestamp: stamp,
    payload: [
      "logicalTotalID": .string("energy"), "representation": .string("finalWorkoutTotal"),
      "activeEnergyKcal": .number(value),
    ])
}
let totalA = try WorkoutHealthRevisionJournal.append(total(10), logicalID: "energy", archive: journal.archive)
let totalB = try WorkoutHealthRevisionJournal.append(total(20), logicalID: "energy", archive: journal.archive)
let totalA2 = try WorkoutHealthRevisionJournal.append(total(10), logicalID: "energy", archive: journal.archive)
check(totalA.event.eventId != totalA2.event.eventId)
check(totalB.event.payload["supersedesEventId"]?.string == totalA.event.eventId)
check(totalA2.event.payload["supersedesEventId"]?.string == totalB.event.eventId)
check(try !WorkoutHealthRevisionJournal.append(total(10), logicalID: "energy", archive: journal.archive).inserted)
print("Production side effect reconciliation and immutable correction checks passed")

// The native recovery planner keeps completed presentation from bypassing a pending Health operation.
check(
  WorkoutRecoveryPlanner.action(hasCutoff: true, verifiedFinality: false, nativePhase: nil, pendingAction: "stop")
    == .findSavedWorkout)
check(
  WorkoutRecoveryPlanner.action(hasCutoff: true, verifiedFinality: false, nativePhase: "running", pendingAction: "stop")
    == .finishSameSession)
check(
  WorkoutRecoveryPlanner.action(hasCutoff: false, verifiedFinality: false, nativePhase: "running", pendingAction: "lap")
    == .reconcileLap)
check(
  WorkoutRecoveryPlanner.action(
    hasCutoff: false, verifiedFinality: false, nativePhase: "running", pendingAction: "pause") == .applyPause)
check(
  WorkoutRecoveryPlanner.action(
    hasCutoff: false, verifiedFinality: false, nativePhase: "paused", pendingAction: "pause") == .observeSession)
check(
  WorkoutRecoveryPlanner.action(hasCutoff: true, verifiedFinality: true, nativePhase: nil, pendingAction: nil)
    == .settled)
print("Native owner recovery planning checks passed")

// A stale seal arriving through another transport cannot project its different cutoff/Health state.
let newerID = UUID().uuidString.lowercased()
_ = try receiverArchive.create(
  id: newerID, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
let emptySource = try receiver.source(id: newerID, producer: "watch")
let newest = WorkoutSeal(
  workoutID: newerID, sealRevision: 3, collectionRevision: 3, ownerRevision: 3,
  stopCutoff: WorkoutCoding.timestamp(stamp.addingTimeInterval(60)), healthOutcome: "saved",
  requirements: ["ownerEnded": "sealed"],
  sources: [emptySource], stopElapsedSeconds: 60, timerSeconds: 60, saveToHealth: true, recordGPS: false)
check(try receiver.acceptCurrent(newest)?.healthOutcome == "saved")
let outdated = WorkoutSeal(
  workoutID: newerID, sealRevision: 2, collectionRevision: 2, ownerRevision: 2,
  stopCutoff: WorkoutCoding.timestamp(stamp.addingTimeInterval(90)), healthOutcome: "failed",
  requirements: ["ownerEnded": "sealed"],
  sources: [emptySource], stopElapsedSeconds: 60, timerSeconds: 60, saveToHealth: true, recordGPS: false)
let catalogBeforeStale = try receiverArchive.metadata(id: newerID)
check(try receiver.acceptCurrent(outdated) == nil)
check(try receiver.currentSeal(id: newerID)?.stopCutoff == newest.stopCutoff)
check(try receiver.currentSeal(id: newerID)?.healthOutcome == "saved")
check(try receiverArchive.metadata(id: newerID).collectionRevision == catalogBeforeStale.collectionRevision)
print("Stale seal projection and acknowledgement boundary checks passed")

// Actual live-subset ingestion preserves holes until canonical final chunks fill omitted raw Health rows.
let liveRoot = root.appendingPathComponent("live-subset-receiver")
let liveArchive = try WorkoutArchive(rootURL: liveRoot)
_ = try liveArchive.create(
  id: id, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
let liveTransfer = WorkoutTransferJournal(archive: liveArchive)
// Watch sequence 1 raw Health is intentionally absent from the live transport; seq2 is visible GPS/summary.
try liveTransfer.receiveLive([second], producer: "watch", firstSequence: 2)
check(try liveArchive.sourceProgress(id: id, producer: "watch").count == 1)
check(try liveArchive.sourceProgress(id: id, producer: "watch").lastSequence == 2)
do {
  _ = try liveTransfer.source(id: id, producer: "watch")
  fatalError("Sparse live subset falsely sealed")
} catch {}
check(try liveTransfer.receive(chunk))
check(try liveArchive.metadata(id: id).eventCount == 2)
check(try liveTransfer.sequence(id: id, eventID: first.eventId, producer: "watch") == 1)
check(try liveTransfer.sequence(id: id, eventID: second.eventId, producer: "watch") == 2)
check(try liveTransfer.source(id: id, producer: "watch").count == 2)
print("Watch live subset to final canonical chunk integration passed")

// Repeated verification is a receipt fast path; a racing append cannot be marked complete.
var verificationPages = 0
liveTransfer.onVerifyPage = { verificationPages += 1 }
let liveSource = try liveTransfer.source(id: id, producer: "watch")
let liveSeal = WorkoutSeal(
  workoutID: id, sealRevision: 1, collectionRevision: 1, ownerRevision: 1,
  stopCutoff: stop.requestedAt, healthOutcome: "saved", requirements: ["ownerEnded": "sealed"], sources: [liveSource],
  stopElapsedSeconds: 400, timerSeconds: 400, saveToHealth: true,
  recordGPS: false)
_ = try liveTransfer.accept(seal: liveSeal)
check(try liveTransfer.verify(id: id))
let firstVerificationPages = verificationPages
check(firstVerificationPages > 0)
for _ in 0..<20 { check(try liveTransfer.verify(id: id)) }
check(verificationPages == firstVerificationPages)
let raceRoot = root.appendingPathComponent("verification-race")
let raceArchive = try WorkoutArchive(rootURL: raceRoot)
_ = try raceArchive.create(
  id: id, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
let raceTransfer = WorkoutTransferJournal(archive: raceArchive)
_ = try raceTransfer.receive(chunk)
_ = try raceTransfer.accept(seal: liveSeal)
raceTransfer.beforeVerificationCommit = { try! raceArchive.append(third) }
check(try !raceTransfer.verify(id: id))
check(try raceArchive.metadata(id: id).finalizationState == "pending")
check(try raceArchive.metadata(id: id).verifiedSealRevision == nil)
print("Verified seal fast path and append race tests passed")

// Actual Health effect adapter: intent failure blocks native work, result failure remains pending.
let healthRoot = root.appendingPathComponent("health-effect")
let healthArchive = try WorkoutArchive(rootURL: healthRoot)
_ = try healthArchive.create(
  id: id, startedAt: stamp, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false)
let rider = try WorkoutEvent(
  workoutId: id, kind: "telemetry", source: "cyc", timestamp: stamp, elapsedSeconds: 0,
  payload: ["humanPowerW": .number(100), "cadenceRpm": .number(70)])
try healthArchive.append(rider)
let healthEffects = WorkoutHealthInsertionJournal(archive: healthArchive)
var nativeWrites = 0
var failedWrites = 0
healthArchive.store.beforeCommitForTesting = { throw InjectedFailure.commit }
healthEffects.insert(
  [rider], authorized: { _ in true },
  operation: { _, done in
    nativeWrites += 1
    done(.success(()))
  }, completion: { result in if case .failure = result { failedWrites += 1 } })
healthArchive.store.beforeCommitForTesting = nil
check(nativeWrites == 0 && failedWrites == 1)
check(try healthEffects.outcome(id: id) == "pending")
healthEffects.insert(
  [rider], authorized: { _ in true },
  operation: { _, done in
    nativeWrites += 1
    healthArchive.store.beforeCommitForTesting = { throw InjectedFailure.commit }
    done(.success(()))
  }, completion: { result in if case .failure = result { failedWrites += 1 } })
healthArchive.store.beforeCommitForTesting = nil
check(nativeWrites == 1 && failedWrites == 2)
check(try healthEffects.outcome(id: id) == "pending")
healthEffects.insert(
  [rider], authorized: { _ in true }, operation: { _, done in done(.success(())) },
  completion: { result in try! result.get() })
check(try healthEffects.outcome(id: id) == "sealed")
print("Native Health effect adapter intent/result fault tests passed")

// Acquisition time, never delivery age, maps local membership through wall changes and late delivery.
var timeline = WorkoutTimelineAnchor(epoch: "process", monotonicOrigin: 1000, startedAt: WorkoutCoding.timestamp(stamp))
let backwardsWall = try timeline.map(epoch: "process", acquisition: 1050, timestamp: stamp.addingTimeInterval(-3600))
check(backwardsWall.eligible && backwardsWall.elapsed == 50 && backwardsWall.uncertainty == nil)
timeline.stopMonotonic = 1100
timeline.stopUTC = WorkoutCoding.timestamp(stamp.addingTimeInterval(100))
let delayedPreStop = try timeline.map(epoch: "process", acquisition: 1099, timestamp: stamp.addingTimeInterval(99))
check(delayedPreStop.eligible && delayedPreStop.elapsed == 99)
check(try !timeline.map(epoch: "process", acquisition: 1101, timestamp: stamp.addingTimeInterval(99)).eligible)
check(
  try timeline.map(epoch: "new-process", acquisition: 20, timestamp: stamp.addingTimeInterval(99)).uncertainty != nil)
print("Native acquisition timeline wall-change and delayed-stop tests passed")

// Local double-lap admission fails before minting an origin sequence; stop queues behind the lap.
let admissionID = UUID().uuidString.lowercased()
let admitStart = try control.admitLocal(
  workoutID: admissionID, origin: "phone", action: "start", at: stamp,
  options: ["saveToHealth": .bool(true), "recordGPS": .bool(false)])
_ = try control.begin(admitStart)
_ = try control.observe(
  workoutID: admissionID, owner: "phone", phase: "running", at: stamp, health: "pending", command: admitStart)
let firstLap = try control.admitLocal(workoutID: admissionID, origin: "phone", action: "lap", at: stamp)
_ = try control.begin(firstLap)
do {
  _ = try control.admitLocal(workoutID: admissionID, origin: "phone", action: "lap", at: stamp)
  fatalError("Double lap admitted")
} catch {}
let queuedStop = try control.admitLocal(workoutID: admissionID, origin: "phone", action: "stop", at: stamp)
check(queuedStop.originSequence == firstLap.originSequence + 1)
check(try control.begin(queuedStop).outcome == "accepted")
_ = try control.observe(
  workoutID: admissionID, owner: "phone", phase: "running", at: stamp, health: "pending", command: firstLap)
check(try control.nextReady(workoutID: admissionID, origin: "phone")?.id == queuedStop.id)
check(try control.prepare(queuedStop).execute)
print("Local deferred-lap admission and ordered stop drain tests passed")

// Offline staging stays bounded while canonical source rows can grow independently.
let bounded = WorkoutBoundedOutbox(store: journal.store)
var staged = 0
for number in 0..<1000 {
  if try bounded.enqueue([
    "messageId": UUID().uuidString.lowercased(), "kind": "events", "workoutId": id, "sequence": number,
  ]) {
    staged += 1
  }
}
check(staged == WorkoutBoundedOutbox.maximumEvents)
check(try bounded.packets().count == WorkoutBoundedOutbox.maximumEvents)
check(try bounded.packets().reduce(0, { $0 + $1.value.count }) <= WorkoutBoundedOutbox.maximumBytes)
check(
  try bounded.enqueue([
    "messageId": UUID().uuidString.lowercased(), "kind": "command", "action": "stop", "workoutId": id,
  ]))
let oldest = try bounded.packets().first!.key
try bounded.acknowledge(oldest)
check(try bounded.packets().count == WorkoutBoundedOutbox.maximumEvents)
print("Offline transport bounded staging and reserved control capacity tests passed")

// The actual Health effect eligibility reads owner intervals while raw paused originals stay retained.
let eligibilityRoot = root.appendingPathComponent("eligibility")
let eligibilityArchive = try WorkoutArchive(rootURL: eligibilityRoot)
_ = try eligibilityArchive.create(
  id: id, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
for (time, action) in [(0.0, "start"), (10.0, "pause"), (20.0, "resume"), (30.0, "stop")] {
  try eligibilityArchive.append(
    WorkoutEvent(
      workoutId: id, kind: "lifecycle", source: "watch", timestamp: stamp.addingTimeInterval(time),
      elapsedSeconds: time, payload: ["action": .string(action)]))
}
for (time, allowed) in [(5.0, true), (15.0, false), (25.0, true), (35.0, false)] {
  let original = try WorkoutEvent(
    workoutId: id, kind: "telemetry", source: "cyc", timestamp: stamp.addingTimeInterval(time),
    elapsedSeconds: time, payload: ["humanPowerW": .number(100), "cadenceRpm": .number(70)])
  try eligibilityArchive.append(original)
  check(try WorkoutHealthEligibility.permits(original, archive: eligibilityArchive) == allowed)
}
check(try eligibilityArchive.metadata(id: id).eventCount == 8)
print("Owner interval Health eligibility with retained paused originals passed")

// Hostile integer boundaries fail validation before subtracting, and unavailable never hides declared rows.
let overflowManifest = WorkoutChunkManifest(
  formatVersion: 1, workoutID: id, producer: "watch", firstSequence: 1,
  lastSequence: Int64.min, count: 1, uncompressedBytes: 1, compressedBytes: 1,
  contentHash: WorkoutChunkCodec.hash(Data([1])))
do {
  try WorkoutChunkCodec.validate(overflowManifest, data: Data([1]))
  fatalError("Overflow range accepted")
} catch {}
let missingRoot = root.appendingPathComponent("unavailable-missing")
let missingArchive = try WorkoutArchive(rootURL: missingRoot)
_ = try missingArchive.create(
  id: id, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
let missingTransfer = WorkoutTransferJournal(archive: missingArchive)
var declaredUnavailable = source
declaredUnavailable.outcome = "unavailable"
let warnedSeal = WorkoutSeal(
  workoutID: id, sealRevision: 1, collectionRevision: 1, ownerRevision: 1,
  stopCutoff: stop.requestedAt, healthOutcome: "saved", requirements: ["ownerEnded": "sealed"],
  sources: [declaredUnavailable],
  stopElapsedSeconds: 400, timerSeconds: 400, saveToHealth: true, recordGPS: false)
_ = try missingTransfer.accept(seal: warnedSeal)
check(try !missingTransfer.verify(id: id))
print("Chunk integer-boundary and unavailable-row completeness tests passed")

// Production file admission handles a duplicate storm with a stalled consumer and recovers its ledger.
let inboxRoot = root.appendingPathComponent("bounded-inbox")
let inboxStore = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("inbox-test.sqlite3"))
let inbox = try WorkoutChunkInbox(root: inboxRoot, store: inboxStore)
inbox.setPreferredWorkoutID(id)
let incomingFile = root.appendingPathComponent("incoming.plchunk")
func incomingMetadata(_ chunk: WorkoutChunk) -> [String: Any] {
  [
    "kind": "workoutChunk", "workoutId": chunk.manifest.workoutID, "chunkIdentity": chunk.manifest.identity,
    "manifest": WorkoutCoding.dictionary(chunk.manifest),
  ]
}
try chunk.data.write(to: incomingFile)
check(try inbox.stage(file: incomingFile, metadata: incomingMetadata(chunk)))
let claimed = try inbox.claim(now: 100)!
for _ in 0..<1000 {
  check(try inbox.stage(file: incomingFile, metadata: incomingMetadata(chunk)))
  check(try inbox.claim(now: 100) == nil)
}
check(try inbox.pendingCount == 1)
for sequence in 3...12 {
  let next = try WorkoutChunkCodec.encode(
    workoutID: id, producer: "watch", firstSequence: Int64(sequence), events: [first])
  try next.data.write(to: incomingFile)
  check(try inbox.stage(file: incomingFile, metadata: incomingMetadata(next)) == (sequence <= 9))
}
check(try inbox.pendingCount == WorkoutChunkInbox.maximumFiles)
check(
  try FileManager.default.contentsOfDirectory(at: inboxRoot, includingPropertiesForKeys: nil).count
    == WorkoutChunkInbox.maximumFiles)
try inbox.finish(claimed, success: false, now: 100)
let nextClaim = try inbox.claim(now: 101)!
check(nextClaim.identity != claimed.identity)
try inbox.finish(nextClaim, success: true, now: 101)
let restartedInbox = try WorkoutChunkInbox(root: inboxRoot, store: inboxStore)
check(try restartedInbox.pendingCount == 7)
check(try restartedInbox.claim(now: 106) != nil)
let lease = WorkoutImportLease(now: 10)
check(lease.permits(now: 29.999))
check(!lease.permits(now: 30))
let cancelledLease = WorkoutImportLease(now: 10)
cancelledLease.expire()
check(!cancelledLease.permits(now: 11))
print("Incoming duplicate storm, bounded stalled consumer, restart and expiration tests passed")

// Missing insertion receipts are sought independently of later successful events, including delayed saved-workout replay.
let retryLedger = WorkoutHealthInsertionJournal(archive: eligibilityArchive)
let missingBefore = try retryLedger.pending(id: id)
check(missingBefore.count == 4)
try retryLedger.prepare([missingBefore[1]])
try retryLedger.recordMetrics(
  [missingBefore[1]], results: WorkoutHealthTelemetryPlan(events: [missingBefore[1]]) { _ in true }.committed)
let missingAfter = try retryLedger.pending(id: id)
check(missingAfter.map(\.eventId) == [missingBefore[0].eventId, missingBefore[2].eventId, missingBefore[3].eventId])
print("Per-event insertion retry does not skip a gap after later success")

// The same production adoption transaction runs before Engine identity mutation.
let adoptionRoot = root.appendingPathComponent("adoption")
let adoptionArchive = try WorkoutArchive(rootURL: adoptionRoot)
let adoptionControl = WorkoutControlJournal(store: adoptionArchive.store)
let activeID = UUID().uuidString.lowercased()
_ = try adoptionArchive.create(
  id: activeID, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
let activeOwner = WorkoutOwnerSnapshot(
  workoutID: activeID, owner: "watch", ownerRevision: 2,
  effectiveAt: WorkoutCoding.timestamp(stamp), phase: "paused", healthOutcome: "pending")
_ = try adoptionControl.accept(snapshot: activeOwner)
let adopted = try WorkoutOwnerAdoption.accept(
  activeOwner, archive: adoptionArchive, control: adoptionControl,
  startedAt: stamp.addingTimeInterval(100), indoor: false, saveToHealth: true, recordGPS: false)
check(adopted?.startedAt == WorkoutCoding.timestamp(stamp) && adopted?.indoor == true)
var staleOwner = activeOwner
staleOwner.ownerRevision = 1
staleOwner.phase = "running"
check(
  try WorkoutOwnerAdoption.accept(
    staleOwner, archive: adoptionArchive, control: adoptionControl, startedAt: stamp, indoor: true, saveToHealth: true,
    recordGPS: false) == nil)
_ = try adoptionArchive.finish(id: activeID, endedAt: stamp.addingTimeInterval(60), finalPhase: "completed")
check(
  try WorkoutOwnerAdoption.accept(
    activeOwner, archive: adoptionArchive, control: adoptionControl, startedAt: stamp, indoor: true, saveToHealth: true,
    recordGPS: false) == nil)
var newActive = activeOwner
newActive.ownerRevision = 3
check(
  try WorkoutOwnerAdoption.accept(
    newActive, archive: adoptionArchive, control: adoptionControl, startedAt: stamp, indoor: true, saveToHealth: true,
    recordGPS: false) == nil)
check(try adoptionControl.snapshot(workoutID: activeID) == activeOwner)
print("Remote identity adoption rejects stale/terminal rides and loads an existing active collection")

let unseenID = UUID().uuidString.lowercased()
let unseenOwner = WorkoutOwnerSnapshot(
  workoutID: unseenID, owner: "watch", ownerRevision: 1,
  effectiveAt: WorkoutCoding.timestamp(stamp), phase: "running", healthOutcome: "pending")
adoptionArchive.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Adoption commit failure") }
do {
  _ = try WorkoutOwnerAdoption.accept(
    unseenOwner, archive: adoptionArchive, control: adoptionControl, startedAt: stamp, indoor: true, saveToHealth: true,
    recordGPS: false)
  fatalError("Failed adoption committed")
} catch {}
adoptionArchive.store.beforeCommitForTesting = nil
check(try adoptionControl.snapshot(workoutID: unseenID) == nil)
check((try? adoptionArchive.metadata(id: unseenID)) == nil)
check(
  try WorkoutOwnerAdoption.accept(
    unseenOwner, archive: adoptionArchive, control: adoptionControl, startedAt: stamp, indoor: true, saveToHealth: true,
    recordGPS: false)?.id == unseenID)
print("Remote adoption fault rolls back collection and owner together")

// Exercise the composed production adoption -> active initialization, with an existing nonempty archive.
let runningRow = try WorkoutEvent(
  workoutId: unseenID, kind: "health", source: "watch", timestamp: stamp,
  payload: ["heartRateBpm": .number(100)])
try adoptionArchive.append(runningRow)
let initialized = try WorkoutOwnerAdoption.activate(
  unseenOwner, archive: adoptionArchive, control: adoptionControl,
  startedAt: stamp.addingTimeInterval(999), indoor: false, now: stamp.addingTimeInterval(60), uptime: 1000,
  epoch: "adopted-epoch", reportedElapsed: 60, reportedTimer: 20, saveToHealth: true, recordGPS: false)!
check(initialized.phase == "running" && initialized.elapsed == 60 && initialized.active == 20)
check(initialized.metadata.eventCount == 1 && initialized.metadata.startedAt == WorkoutCoding.timestamp(stamp))
let adoptedMapping = try initialized.timeline.map(
  epoch: "adopted-epoch", acquisition: 1005, timestamp: stamp.addingTimeInterval(-3600))
check(
  adoptedMapping.elapsed == 65 && adoptedMapping.eligible
    && adoptedMapping.uncertainty?.contains("transit time") == true)
print("Nonempty active adoption initializes phase and explicit uncertain epoch without confirming start again")

// Recovered local effects have no phone replay and need no new delegate callback to release queued stop.
for action in ["pause", "resume", "lap"] {
  let recoveryID = UUID().uuidString.lowercased()
  let recoveryRoot = root.appendingPathComponent("local-recovery-" + action)
  let original = try WatchWorkoutJournal(rootURL: recoveryRoot)
  try original.create(
    id: recoveryID,
    metadata: [
      "workoutId": recoveryID, "startedAt": WorkoutCoding.timestamp(stamp), "phase": "running", "indoor": true,
      "saveToHealth": true, "recordGPS": false,
    ])
  let firstCommand = try original.control.admitLocal(
    workoutID: recoveryID, origin: "watch", action: "start", at: stamp,
    options: ["saveToHealth": .bool(true), "recordGPS": .bool(false)])
  _ = try original.control.begin(firstCommand)
  _ = try original.control.observe(
    workoutID: recoveryID, owner: "watch", phase: action == "resume" ? "paused" : "running", at: stamp,
    health: "pending", command: firstCommand)
  let interrupted = try original.control.admitLocal(
    workoutID: recoveryID, origin: "watch", action: action, at: stamp.addingTimeInterval(10),
    options: [
      "cutoffUTC": .string(WorkoutCoding.timestamp(stamp.addingTimeInterval(10))),
      "cutoffElapsedSeconds": .number(10), "timerSeconds": .number(8),
    ])
  check(try original.control.prepare(interrupted).execute)
  // HealthKit applied this effect, then the process died before the local result transaction.
  let nativePhase = action == "pause" ? "paused" : "running"
  let nativeLaps: Set<String> = action == "lap" ? [interrupted.id] : []
  let queued = try original.control.admitLocal(
    workoutID: recoveryID, origin: "watch", action: "stop", at: stamp.addingTimeInterval(20))
  check(try original.control.begin(queued).outcome == "accepted")
  let restored = try WatchWorkoutJournal(rootURL: recoveryRoot)
  func reconcileLocal() throws -> WorkoutRecoveredCommandResult {
    try WorkoutRecoveredOwnerCommand.reconcile(
      workoutID: recoveryID, owner: "watch", nativePhase: nativePhase,
      observedAt: stamp.addingTimeInterval(30), nativeEventDates: [action: stamp.addingTimeInterval(10)],
      observedLapIDs: nativeLaps,
      cutoff: stamp.addingTimeInterval(20), health: "pending", healthID: nil, archive: restored.archive,
      control: restored.control,
      timing: try WorkoutOwnerTiming(
        timestamp: WorkoutCoding.timestamp(stamp.addingTimeInterval(20)), elapsedSeconds: 20, timerSeconds: 15)
    ) { snapshot in
      var metadata = try restored.metadata(id: recoveryID)
      metadata["phase"] = snapshot.phase
      metadata["ownerRevision"] = String(snapshot.ownerRevision)
      if snapshot.stopCutoff != nil, let timing = snapshot.timing {
        WatchWorkoutJournal.retain(timing, in: &metadata, terminal: true)
      }
      try restored.save(id: recoveryID, metadata: metadata)
    }
  }
  restored.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Recovery result commit interrupted") }
  do {
    _ = try reconcileLocal()
    fatalError("Faulty recovery committed")
  } catch {}
  restored.store.beforeCommitForTesting = nil
  check(try restored.control.result(id: interrupted.id)?.outcome == "executing")
  check(try restored.archive.metadata(id: recoveryID).eventCount == 0)
  let resolved = try reconcileLocal()
  check(resolved.required == nil && resolved.completed?.id == interrupted.id && resolved.next?.id == queued.id)
  check(resolved.snapshot?.phase == "finishing" && resolved.snapshot?.stopCutoff == queued.requestedAt)
  check(try restored.control.result(id: interrupted.id)?.outcome == "applied")
  check(try restored.archive.metadata(id: recoveryID).eventCount == 1)
  let replay = try reconcileLocal()
  check(replay.completed == nil && replay.next?.id == queued.id)
  check(try restored.control.snapshot(workoutID: recoveryID)?.ownerRevision == resolved.snapshot?.ownerRevision)
  check(try restored.archive.metadata(id: recoveryID).eventCount == 1)
  check(try restored.control.prepare(queued).execute)
  _ = try restored.control.observe(
    workoutID: recoveryID, owner: "watch", phase: "completed", at: stamp.addingTimeInterval(20),
    health: "pending", cutoff: stamp.addingTimeInterval(20), command: queued,
    timing: WorkoutOwnerTiming(timestamp: queued.requestedAt, elapsedSeconds: 20, timerSeconds: 20))
  check(try restored.control.result(id: queued.id)?.outcome == "applied")
  check(try restored.control.active(workoutID: recoveryID) == nil)
}
print("Recovered local pause/resume/lap atomically resolve once and drain stop without delegate replay")

// An applied same-phase lap ACK releases its own pending action; stale/unrelated ACKs cannot release a newer one.
let remoteControl = WorkoutControlJournal(store: adoptionArchive.store)
let remoteLap = try remoteControl.createRemote(workoutID: unseenID, origin: "phone", action: "lap", at: stamp)
let appliedLap = WorkoutCommandResult(commandID: remoteLap.id, outcome: "applied", ownerRevision: 4)
check(try remoteControl.pendingRemote(workoutID: unseenID)?.action == "lap")
check(try remoteControl.completeRemote(appliedLap, acknowledgedID: remoteLap.id, workoutID: unseenID))
check(try remoteControl.pendingRemote(workoutID: unseenID) == nil)
let laterLap = try remoteControl.createRemote(workoutID: unseenID, origin: "phone", action: "lap", at: stamp)
let restoredRemote = WorkoutControlJournal(store: adoptionArchive.store)
check(try !restoredRemote.completeRemote(appliedLap, acknowledgedID: remoteLap.id, workoutID: unseenID))
check(try restoredRemote.pendingRemote(workoutID: unseenID)?.commandID == laterLap.id)
let laterAccepted = WorkoutCommandResult(commandID: laterLap.id, outcome: "accepted", ownerRevision: 4)
check(try !restoredRemote.completeRemote(laterAccepted, acknowledgedID: laterLap.id, workoutID: unseenID))
let laterFailed = WorkoutCommandResult(commandID: laterLap.id, outcome: "failed", ownerRevision: 5)
check(try !restoredRemote.completeRemote(laterFailed, acknowledgedID: remoteLap.id, workoutID: unseenID))
check(try restoredRemote.completeRemote(laterFailed, acknowledgedID: laterLap.id, workoutID: unseenID))
print("Matching terminal remote action ACK persists across restart and ignores stale same-phase replies")

// Both fresh capture and reconnect use the same canonical cursor, even while offline admission is full.
let forwardingRoot = root.appendingPathComponent("contiguous-forwarding")
let forwardingArchive = try WorkoutArchive(rootURL: forwardingRoot)
let receivingArchive = try WorkoutArchive(rootURL: root.appendingPathComponent("contiguous-receiver"))
_ = try forwardingArchive.create(
  id: id, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
_ = try receivingArchive.create(
  id: id, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
let forwarder = WorkoutTelemetryForwarder(archive: forwardingArchive)
let receivingTransfer = WorkoutTransferJournal(archive: receivingArchive)
func appendCaptured(_ range: Range<Int>) throws {
  for start in stride(from: range.lowerBound, to: range.upperBound, by: 128) {
    let events = try (start..<min(start + 128, range.upperBound)).map { number in
      try WorkoutEvent(
        workoutId: id, kind: "telemetry", source: "cyc", timestamp: stamp.addingTimeInterval(Double(number)),
        elapsedSeconds: Double(number),
        payload: ["humanPowerW": .number(Double(number % 300)), "cadenceRpm": .number(80)])
    }
    _ = try forwardingArchive.appendBatch(events)
  }
}
try appendCaptured(0..<300)
forwardingArchive.store.beforeCommitForTesting = {
  throw WorkoutDataError.invalid("Outgoing packet/cursor commit failure")
}
do {
  _ = try forwarder.stageNext(id: id)
  fatalError("Failed forwarding transaction committed")
} catch {}
forwardingArchive.store.beforeCommitForTesting = nil
check(try forwarder.outbox.packets().isEmpty)
check(try forwardingArchive.store.read { db in try db.get(namespace: "telemetry-forward-cursor", key: id) } == nil)
for _ in 0..<WorkoutBoundedOutbox.maximumEvents { check(try forwarder.stageNext(id: id)) }
check(try !forwarder.stageNext(id: id))
try appendCaptured(300..<500)  // Live frames continue while offline outbox is full.
check(try !forwarder.stageNext(id: id))
var delivered = 0
var connected = false
while true {
  let packets = try forwarder.outbox.packets()
  if packets.isEmpty {
    if try !forwarder.stageNext(id: id) { break }
    continue
  }
  let packet = packets[0]
  let envelope = try JSONSerialization.jsonObject(with: packet.value) as! [String: Any]
  let events = try (envelope["events"] as! [[String: Any]]).map(WorkoutEvent.init(dictionary:))
  try receivingTransfer.receiveLive(
    events, producer: "cyc", firstSequence: Int64(envelope["firstSequence"] as! String)!)
  try forwarder.outbox.acknowledge(packet.key)
  delivered += events.count
  if !connected {
    try appendCaptured(500..<516)
    connected = true
  }  // A new tail arrives immediately on reconnect.
  _ = try WorkoutTelemetryForwarder(archive: forwardingArchive).stageNext(id: id)  // Recreated scheduler/cursor.
}
check(delivered == 516)
let sentSource = try WorkoutTransferJournal(archive: forwardingArchive).source(id: id, producer: "cyc")
let receivedSource = try receivingTransfer.source(id: id, producer: "cyc")
check(sentSource == receivedSource && receivedSource.count == 516 && receivedSource.lastSequence == 516)
print("Full offline outbox plus new reconnect capture drains every canonical source sequence")

// Watch lifecycle may arrive before running status: production start projection preserves those records.
let earlyRoot = root.appendingPathComponent("early-watch-start")
let earlyArchive = try WorkoutArchive(rootURL: earlyRoot)
_ = try earlyArchive.create(
  id: id, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
try earlyArchive.update(id: id, phase: "preparing")
let earlyEvent = try WorkoutEvent(
  workoutId: id, kind: "lifecycle", source: "watch", timestamp: stamp.addingTimeInterval(1),
  elapsedSeconds: 0, payload: ["action": .string("start")])
try WorkoutTransferJournal(archive: earlyArchive).receiveLive([earlyEvent], producer: "watch", firstSequence: 1)
let confirmed = try WorkoutPhoneStartProjection.confirm(
  archive: earlyArchive, id: id, startedAt: stamp.addingTimeInterval(1),
  now: stamp.addingTimeInterval(3), uptime: 1000, epoch: "confirmed-start", startedUptime: 998)
check(confirmed.startedAt == WorkoutCoding.timestamp(stamp.addingTimeInterval(1)))
check(try earlyArchive.metadata(id: id).eventCount == 1)
check(try earlyArchive.pageEvents(id: id).first?.event == earlyEvent)
print("Early Watch lifecycle then owner running confirmation preserves the original event and mapping")

// Startup reconstructs only bounded display rows plus an indexed lifecycle count, even for long archives.
let longJournal = try WatchWorkoutJournal(rootURL: root.appendingPathComponent("bounded-recovery-display"))
let longID = UUID().uuidString.lowercased()
try longJournal.create(
  id: longID,
  metadata: [
    "workoutId": longID, "startedAt": WorkoutCoding.timestamp(stamp), "phase": "running", "indoor": true,
    "saveToHealth": true, "recordGPS": false,
  ])
for offset in stride(from: 0, to: 4096, by: 128) {
  let page = try (offset..<offset + 128).map { number in
    try WorkoutEvent(
      workoutId: longID, kind: "health", source: "watch", timestamp: stamp.addingTimeInterval(Double(number)),
      elapsedSeconds: Double(number), payload: ["heartRateBpm": .number(100 + Double(number % 30))])
  }
  _ = try longJournal.archive.appendBatch(page)
}
for number in 0..<3 {
  try longJournal.archive.append(
    WorkoutEvent(
      workoutId: longID, kind: "lifecycle", source: "watch", timestamp: stamp.addingTimeInterval(Double(number)),
      elapsedSeconds: Double(number), payload: ["action": .string("lap")]))
}
var recoveredDecodes = 0
let longProjection = try longJournal.recoveryProjection(id: longID, onDecode: { recoveredDecodes += 1 })
check(longProjection.lapCount == 3 && recoveredDecodes == 16 && longProjection.events.count == 16)
check(longProjection.events.last?.elapsedSeconds == 4095)
print("Long archive recovery decodes 16 recent display rows, not 4099 historical records")
for number in 4096...4097 {
  try longJournal.archive.append(
    WorkoutEvent(
      workoutId: longID, kind: "health", source: "watch", timestamp: stamp.addingTimeInterval(-Double(number)),
      elapsedSeconds: Double(number), payload: ["heartRateBpm": .number(140)]))
}
let reversedProjection = try longJournal.recoveryProjection(id: longID)
check(reversedProjection.events.last?.elapsedSeconds == 4097)
check(reversedProjection.events.last?.timestamp == WorkoutCoding.timestamp(stamp.addingTimeInterval(-4097)))

var workQueue = WorkoutBoundedWorkQueue()
check(workQueue.request("active"))
for number in 0..<1000 { check(!workQueue.request("ride-\(number)")) }
check(workQueue.active == "active" && workQueue.pending.count == WorkoutBoundedWorkQueue.maximumPending)
for _ in 0..<1000 { check(!workQueue.request("active")) }
var nextWork = workQueue.finish("active")
var jobs = 0
while let next = nextWork {
  check(workQueue.request(next))
  jobs += 1
  nextWork = workQueue.finish(next)
}
check(jobs == WorkoutBoundedWorkQueue.maximumPending && workQueue.active == nil && workQueue.pending.isEmpty)
check(workQueue.request("ride-999"))  // Catalog rediscovery admits an earlier overflow when capacity returns.
print("Archive worker admission stays at one active and eight pending under multi-ride storms")

// Global cursor discovery survives selecting a new phone-owned ride and restarting the forwarder.
let selectedNewID = UUID().uuidString.lowercased()
_ = try forwardingArchive.create(
  id: selectedNewID, startedAt: stamp.addingTimeInterval(1000), indoor: true, watchEnabled: false, saveToHealth: true,
  recordGPS: false)
try forwardingArchive.append(
  WorkoutEvent(
    workoutId: selectedNewID, kind: "telemetry", source: "cyc", timestamp: stamp.addingTimeInterval(1001),
    elapsedSeconds: 1, payload: ["humanPowerW": .number(50), "cadenceRpm": .number(60)]))
try appendCaptured(516..<532)
check(try WorkoutTelemetryForwarder(archive: forwardingArchive).stageNextPending())
let oldPacket = try forwarder.outbox.packets().first!
let oldEnvelope = try JSONSerialization.jsonObject(with: oldPacket.value) as! [String: Any]
check(oldEnvelope["workoutId"] as? String == id && oldEnvelope["firstSequence"] as? String == "517")
let oldEvents = try (oldEnvelope["events"] as! [[String: Any]]).map(WorkoutEvent.init(dictionary:))
try receivingTransfer.receiveLive(oldEvents, producer: "cyc", firstSequence: 517)
try forwarder.outbox.acknowledge(oldPacket.key)
check(try !WorkoutTelemetryForwarder(archive: forwardingArchive).stageNextPending())
check(try receivingArchive.sourceProgress(id: id, producer: "cyc").lastSequence == 532)
check(try forwardingArchive.sourceProgress(id: selectedNewID, producer: "cyc").count == 1)
print("Old Watch collection continues forwarding after a new phone owner and scheduler restart")

// Historical receive -> same-workout native insertion -> final Health page -> new verified seal, active owner unchanged.
let historyJournal = try WatchWorkoutJournal(rootURL: root.appendingPathComponent("historical-health"))
let historyID = UUID().uuidString.lowercased()
let activeRideID = UUID().uuidString.lowercased()
let oldHealthUUID = UUID().uuidString.lowercased()
try historyJournal.create(
  id: historyID,
  metadata: [
    "workoutId": historyID, "startedAt": WorkoutCoding.timestamp(stamp), "phase": "completed",
    "endedAt": WorkoutCoding.timestamp(stamp.addingTimeInterval(10)), "indoor": true, "saveToHealth": true,
    "recordGPS": false, "stopElapsedSeconds": 10.0, "timerSeconds": 8.0,
  ])
try historyJournal.create(
  id: activeRideID,
  metadata: [
    "workoutId": activeRideID, "startedAt": WorkoutCoding.timestamp(stamp.addingTimeInterval(20)), "phase": "running",
    "indoor": true, "saveToHealth": true, "recordGPS": false,
  ])
for (time, action) in [(0.0, "start"), (10.0, "stop")] {
  try historyJournal.archive.append(
    WorkoutEvent(
      workoutId: historyID, kind: "lifecycle", source: "watch", timestamp: stamp.addingTimeInterval(time),
      elapsedSeconds: time, payload: ["action": .string(action)]))
}
let activeSnapshot = try historyJournal.control.observe(
  workoutID: activeRideID, owner: "watch", phase: "running", at: stamp.addingTimeInterval(20), health: "pending")
let activeMetadata = try JSONSerialization.data(
  withJSONObject: historyJournal.metadata(id: activeRideID), options: [.sortedKeys])
let historicalFrame = try WorkoutEvent(
  workoutId: historyID, kind: "telemetry", source: "cyc", timestamp: stamp.addingTimeInterval(5), elapsedSeconds: 5,
  payload: ["humanPowerW": .number(120), "cadenceRpm": .number(70)])
try historyJournal.acceptTelemetry([historicalFrame], firstSequence: 1)
let historicalInsertion = WorkoutHealthInsertionJournal(archive: historyJournal.archive)
check(try WorkoutHealthEligibility.permits(historicalFrame, archive: historyJournal.archive))
var historicalNativeWrites = 0
historicalInsertion.insert(
  try historicalInsertion.pending(id: historyID), authorized: { _ in true },
  operation: { _, done in
    check(historicalFrame.workoutId == historyID && UUID(uuidString: oldHealthUUID) != nil)
    historicalNativeWrites += 1
    done(.success(()))
  }, completion: { try! $0.get() })
let finalSample = try WorkoutEvent(
  workoutId: historyID, kind: "health", source: "watch", timestamp: stamp.addingTimeInterval(5), elapsedSeconds: 5,
  payload: [
    "representation": .string("rawQuantity"), "sampleUUID": .string(UUID().uuidString.lowercased()),
    "value": .number(120),
  ])
try historyJournal.commitHealthPage(
  id: historyID, events: [finalSample], progressKey: "final-delayed-cyc", anchor: Data([1]))
let historicalOwner = try historyJournal.control.observe(
  workoutID: historyID, owner: "watch", phase: "completed", at: stamp.addingTimeInterval(10), health: "saved",
  healthID: oldHealthUUID, cutoff: stamp.addingTimeInterval(10),
  timing: WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(stamp.addingTimeInterval(10)), elapsedSeconds: 10, timerSeconds: 10))
let historicalSources = try ["watch", "cyc"].map { try historyJournal.transfer.source(id: historyID, producer: $0) }
let historicalSeal = WorkoutSeal(
  workoutID: historyID, sealRevision: 1,
  collectionRevision: try historyJournal.archive.metadata(id: historyID).collectionRevision!,
  ownerRevision: historicalOwner.ownerRevision,
  stopCutoff: WorkoutCoding.timestamp(stamp.addingTimeInterval(10)), healthOutcome: "saved",
  requirements: [
    "healthSave": "sealed", "healthExtraction": "sealed", "ownerEnded": "sealed",
    "cycInsertion": try historicalInsertion.outcome(id: historyID),
  ], sources: historicalSources, stopElapsedSeconds: 10, timerSeconds: 8, saveToHealth: true, recordGPS: false)
_ = try historyJournal.transfer.accept(seal: historicalSeal)
check(try historyJournal.transfer.verify(id: historyID))
let historicalFinalMetadata = try historyJournal.archive.metadata(id: historyID)
check(historicalNativeWrites == 1 && historicalFinalMetadata.finalizationState == "complete")
check(try historyJournal.control.snapshot(workoutID: activeRideID) == activeSnapshot)
check(
  try JSONSerialization.data(withJSONObject: historyJournal.metadata(id: activeRideID), options: [.sortedKeys])
    == activeMetadata)
print("Historical late input reaches verified Health finality while a different owner stays active")

// The very first accepted owner status may already be terminal; confirming its start never runs native effects.
for firstPhase in ["running", "paused", "finishing", "completed"] {
  let firstStatusID = UUID().uuidString.lowercased()
  _ = try earlyArchive.create(
    id: firstStatusID, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
  try earlyArchive.update(id: firstStatusID, phase: "preparing")
  let actualStart = stamp.addingTimeInterval(20)
  let earlyHealth = try WorkoutEvent(
    workoutId: firstStatusID, kind: "health", source: "watch", timestamp: actualStart.addingTimeInterval(5),
    elapsedSeconds: 5, payload: ["representation": .string("rawQuantity"), "heartRateBpm": .number(130)])
  try WorkoutTransferJournal(archive: earlyArchive).receiveLive([earlyHealth], producer: "watch", firstSequence: 1)
  let firstStatus = try WorkoutPhoneStartProjection.confirmOwnerPhase(
    archive: earlyArchive, id: firstStatusID, localPhase: "preparing",
    ownerPhase: firstPhase, startedAt: actualStart, now: actualStart.addingTimeInterval(30), uptime: 2000,
    epoch: "first-" + firstPhase,
    ownerTiming: WorkoutOwnerTiming(
      timestamp: WorkoutCoding.timestamp(actualStart.addingTimeInterval(30)), elapsedSeconds: 30, timerSeconds: 20))
  check(firstStatus?.startedAt == WorkoutCoding.timestamp(actualStart))
  check(try earlyArchive.metadata(id: firstStatusID).startedAt == WorkoutCoding.timestamp(actualStart))
  check(try earlyArchive.pageEvents(id: firstStatusID).map(\.event) == [earlyHealth])
  try earlyArchive.update(id: firstStatusID, phase: firstPhase == "finishing" ? "completed" : firstPhase)
  let duplicate = try WorkoutPhoneStartProjection.confirmOwnerPhase(
    archive: earlyArchive, id: firstStatusID, localPhase: firstPhase,
    ownerPhase: firstPhase, startedAt: actualStart, now: actualStart.addingTimeInterval(31), uptime: 2001,
    epoch: "later")
  check(duplicate == nil)
}
print("First running, paused, finishing or completed owner status confirms the actual start without replaying effects")

// The same terminal projection is used for ephemeral seals and metadata on the first file chunk.
for chunkFirst in [false, true] {
  let terminalID = UUID().uuidString.lowercased()
  let actualStart = stamp.addingTimeInterval(30)
  _ = try earlyArchive.create(
    id: terminalID, startedAt: stamp, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
  _ = try earlyArchive.update(id: terminalID, phase: "preparing")
  let terminalTransfer = WorkoutTransferJournal(archive: earlyArchive)
  let original = try WorkoutEvent(
    workoutId: terminalID, kind: "health", source: "watch", timestamp: actualStart.addingTimeInterval(5),
    elapsedSeconds: 5, payload: ["heartRateBpm": .number(130)])
  if chunkFirst {
    _ = try terminalTransfer.receive(
      WorkoutChunkCodec.encode(workoutID: terminalID, producer: "watch", firstSequence: 1, events: [original]))
  }
  let terminalSeal = WorkoutSeal(
    workoutID: terminalID, sealRevision: 1, collectionRevision: try earlyArchive.revision(id: terminalID),
    ownerRevision: 2,
    stopCutoff: WorkoutCoding.timestamp(actualStart.addingTimeInterval(10)), healthOutcome: "saved",
    requirements: ["ownerEnded": "sealed", "healthExtraction": "pending"], sources: [], stopElapsedSeconds: 10,
    timerSeconds: 8,
    saveToHealth: true,
    recordGPS: false)
  do {
    _ = try WorkoutPhoneTerminalProjection.accept(
      archive: earlyArchive, transfer: terminalTransfer, incoming: terminalSeal, startedAt: nil,
      localPhase: "preparing", timerSeconds: 8, now: actualStart.addingTimeInterval(20), uptime: 2000,
      epoch: "terminal-first")
    fatalError("Preparing terminal seal accepted without an owner start")
  } catch {}
  check(try terminalTransfer.currentSeal(id: terminalID) == nil)
  check(try earlyArchive.metadata(id: terminalID).phase == "preparing")
  let projected = try WorkoutPhoneTerminalProjection.accept(
    archive: earlyArchive, transfer: terminalTransfer, incoming: terminalSeal, startedAt: actualStart,
    localPhase: "preparing", timerSeconds: 8, now: actualStart.addingTimeInterval(20), uptime: 2000,
    epoch: "terminal-first")!
  check(
    projected.startedAt == WorkoutCoding.timestamp(actualStart) && projected.elapsedSeconds == 10
      && projected.timerSeconds == 8)
  check(projected.preparationAnchor?.stopMonotonic == 2000)
  let staleRunning = WorkoutOwnerSnapshot(
    workoutID: terminalID, owner: "watch", ownerRevision: 1, effectiveAt: WorkoutCoding.timestamp(actualStart),
    phase: "running", healthOutcome: "pending", healthWorkoutID: nil, stopCutoff: nil)
  check(!WorkoutPhoneTerminalProjection.acceptsStatus(staleRunning, after: terminalSeal))
  let finalStatus = WorkoutOwnerSnapshot(
    workoutID: terminalID, owner: "watch", ownerRevision: 2, effectiveAt: terminalSeal.stopCutoff,
    phase: "completed", healthOutcome: "saved", healthWorkoutID: nil, stopCutoff: terminalSeal.stopCutoff,
    timing: try WorkoutOwnerTiming(timestamp: terminalSeal.stopCutoff, elapsedSeconds: 10, timerSeconds: 8))
  check(WorkoutPhoneTerminalProjection.acceptsStatus(finalStatus, after: terminalSeal))
  check(try earlyArchive.metadata(id: terminalID).phase == "completed")
  check(try earlyArchive.metadata(id: terminalID).startedAt == WorkoutCoding.timestamp(actualStart))
  if chunkFirst {
    check(
      try WorkoutCoding.encoder().encode(earlyArchive.pageEvents(id: terminalID).first!.event)
        == WorkoutCoding.encoder().encode(original))
  }
  let terminalRevision = try earlyArchive.revision(id: terminalID)
  let duplicate = try WorkoutPhoneTerminalProjection.accept(
    archive: earlyArchive, transfer: terminalTransfer, incoming: terminalSeal, startedAt: actualStart,
    localPhase: "completed", timerSeconds: 8, now: actualStart.addingTimeInterval(21), uptime: 2001, epoch: "duplicate")!
  check(duplicate.preparationAnchor == nil && duplicate.elapsedSeconds == 10 && duplicate.timerSeconds == 8)
  check(try earlyArchive.revision(id: terminalID) == terminalRevision)
  let laterStatus = try WorkoutPhoneStartProjection.confirmOwnerPhase(
    archive: earlyArchive, id: terminalID, localPhase: "completed", ownerPhase: "completed",
    startedAt: actualStart, now: actualStart.addingTimeInterval(22), uptime: 2002, epoch: "status-later")
  check(laterStatus == nil)
  // If the app interrupted after archive commit but before phone-current, replay can finish preparation.
  let recovered = try WorkoutPhoneTerminalProjection.accept(
    archive: earlyArchive, transfer: WorkoutTransferJournal(archive: earlyArchive), incoming: terminalSeal,
    startedAt: actualStart, localPhase: "preparing", timerSeconds: 8, now: actualStart.addingTimeInterval(23),
    uptime: 2003, epoch: "reopened")!
  check(recovered.preparationAnchor != nil && recovered.startedAt == projected.startedAt)
  check(try earlyArchive.revision(id: terminalID) == terminalRevision)
}
print(
  "Seal-first and first-chunk terminal projection confirm original start, freeze timing, retain rows and recover preparation idempotently"
)

let discardedJournal = try WatchWorkoutJournal(rootURL: root.appendingPathComponent("discarded-pending-count"))
let discardedID = UUID().uuidString.lowercased()
try discardedJournal.create(
  id: discardedID,
  metadata: [
    "workoutId": discardedID,
    "startedAt": "2026-01-01T00:00:00.000Z", "endedAt": "2026-01-01T00:01:00.000Z", "phase": "completed",
    "stopElapsedSeconds": 60.0, "timerSeconds": 50.0,
    "healthKitState": "discarded", "discardRequested": true, "archiveDirty": true, "indoor": true, "saveToHealth": true,
    "recordGPS": false,
  ])
check(try discardedJournal.metadataPage().first?.id == discardedID)
print("Discarded rides retain automatic deletion delivery without a pending-ride warning")

let timingJournal = try WatchWorkoutJournal(rootURL: root.appendingPathComponent("retained-timing"))
let timingID = UUID().uuidString.lowercased()
let timingAnchor = WorkoutTimelineAnchor(
  epoch: "old-process", monotonicOrigin: 100, startedAt: WorkoutCoding.timestamp(stamp))
let timingCheckpoint = try WorkoutOwnerTiming(
  timestamp: WorkoutCoding.timestamp(stamp.addingTimeInterval(10)), elapsedSeconds: 10, timerSeconds: 8)
try timingJournal.create(
  id: timingID,
  metadata: [
    "workoutId": timingID, "startedAt": timingAnchor.startedAt, "phase": "running",
    "indoor": true, "saveToHealth": false, "recordGPS": false,
    "timelineAnchor": WorkoutCoding.dictionary(timingAnchor), "checkpoint": WorkoutCoding.dictionary(timingCheckpoint),
  ])
let committedAfterCheckpoint = try WorkoutEvent(
  workoutId: timingID, kind: "location", source: "watch", timestamp: stamp.addingTimeInterval(-100),
  elapsedSeconds: 14, payload: ["latitude": .number(0), "longitude": .number(0)])
try timingJournal.archive.append(committedAfterCheckpoint)
let timingRestart = try WatchWorkoutJournal(rootURL: timingJournal.directory)
timingRestart.store.beforeCommitForTesting = { throw InjectedFailure.commit }
do {
  _ = try timingRestart.recoverTiming(
    id: timingID, epoch: "new-process", uptime: 2, resumedAt: stamp.addingTimeInterval(1000), running: true)
  fatalError("Recovery fault committed partial timing")
} catch {}
timingRestart.store.beforeCommitForTesting = nil
check(try timingRestart.archive.metadata(id: timingID).eventCount == 1)
check(try WatchWorkoutJournal.timing(timingRestart.metadata(id: timingID)) == timingCheckpoint)
let resumedMetadata = try timingRestart.recoverTiming(
  id: timingID, epoch: "new-process", uptime: 2, resumedAt: stamp.addingTimeInterval(1000), running: true)
let retained = try WatchWorkoutJournal.timing(resumedMetadata)!
check(retained.timestamp == committedAfterCheckpoint.timestamp)
check(retained.elapsedSeconds == 14)
check(retained.timerSeconds == 12)
let resumedAnchor = try WatchWorkoutJournal.timeline(resumedMetadata)!
check(resumedAnchor.startedAt == timingAnchor.startedAt && resumedAnchor.epoch == "new-process")
check(resumedAnchor.uncertainty != nil)
check(
  try resumedAnchor.map(epoch: "new-process", acquisition: 3, timestamp: stamp.addingTimeInterval(10_000)).elapsed == 15
)
let progressed = try retained.advancing(
  anchor: resumedAnchor, at: stamp.addingTimeInterval(-500), from: 2, to: 5, running: true)
check(progressed.elapsedSeconds == 17 && progressed.timerSeconds == 15)
check(progressed.timestamp == WorkoutCoding.timestamp(stamp.addingTimeInterval(-500)))
let pausedTiming = try progressed.advancing(
  anchor: resumedAnchor, at: stamp.addingTimeInterval(50_000), from: 5, to: 10, running: false)
check(pausedTiming.elapsedSeconds == 22 && pausedTiming.timerSeconds == 15)
check(pausedTiming.timestamp == WorkoutCoding.timestamp(stamp.addingTimeInterval(50_000)))
let activeAgain = try pausedTiming.advancing(anchor: resumedAnchor, at: stamp, from: 10, to: 12, running: true)
check(activeAgain.elapsedSeconds == 24 && activeAgain.timerSeconds == 17)
let retainedEvents = try timingRestart.archive.pageEvents(id: timingID).map(\.event)
let interruption = retainedEvents.first { $0.payload["interrupted"] == .bool(true) }!
check(interruption.elapsedSeconds == 14 && interruption.timestamp == committedAfterCheckpoint.timestamp)
check(interruption.source == "watch" && interruption.payload["action"] == .string("pause"))
check(retainedEvents.last?.payload["action"] == .string("resume"))
check(retainedEvents.last?.timestamp == WorkoutCoding.timestamp(stamp.addingTimeInterval(1000)))
check(retainedEvents.last?.timestamp != interruption.timestamp)
var terminalMetadata = resumedMetadata
WatchWorkoutJournal.retain(retained, in: &terminalMetadata, terminal: true)
terminalMetadata["phase"] = "completed"
try timingRestart.save(id: timingID, metadata: terminalMetadata)
check(try WorkoutOwnerTiming.terminal(timingRestart.archive.metadata(id: timingID)) == retained)
let stoppedMetadata = try timingRestart.recoverTiming(
  id: timingID, epoch: "third-process", uptime: 10_000, resumedAt: stamp.addingTimeInterval(1000), running: true)
check(try WatchWorkoutJournal.timing(stoppedMetadata) == retained)
check(try timingRestart.archive.metadata(id: timingID).eventCount == retainedEvents.count)
for field in ["stopElapsedSeconds", "timerSeconds"] {
  var invalid = stoppedMetadata
  invalid[field] = nil
  do {
    try timingRestart.save(id: timingID, metadata: invalid)
    fatalError("Incomplete Watch terminal boundary accepted")
  } catch {}
  check(try WatchWorkoutJournal.timing(timingRestart.metadata(id: timingID)) == retained)
}
var changedTiming = stoppedMetadata
changedTiming["timerSeconds"] = 11.0
do {
  try timingRestart.save(id: timingID, metadata: changedTiming)
  fatalError("Changed Watch cutoff timing accepted")
} catch {}
check(try WatchWorkoutJournal.timing(timingRestart.metadata(id: timingID)) == retained)
let receiptID = UUID().uuidString.lowercased()
try timingRestart.create(
  id: receiptID,
  metadata: [
    "workoutId": receiptID, "startedAt": timingAnchor.startedAt, "phase": "running",
    "saveToHealth": false, "recordGPS": false,
    "timelineAnchor": WorkoutCoding.dictionary(timingAnchor), "checkpoint": WorkoutCoding.dictionary(timingCheckpoint),
  ])
_ = try timingRestart.control.observe(
  workoutID: receiptID, owner: "watch", phase: "completed", at: stamp.addingTimeInterval(2000),
  health: "notRequested", cutoff: WorkoutCoding.date(retained.timestamp), timing: retained)
let repairedMetadata = try timingRestart.recoverTiming(
  id: receiptID, epoch: "unused", uptime: 1, resumedAt: stamp.addingTimeInterval(1000), running: true)
check(try WatchWorkoutJournal.timing(repairedMetadata) == retained)
check(try timingRestart.archive.metadata(id: receiptID).eventCount == 0)
do {
  _ = try metadataRestart.recoverTiming(
    id: earlierID, epoch: "missing", uptime: 1, resumedAt: stamp.addingTimeInterval(1000), running: true)
  fatalError("Missing retained Watch timing was reconstructed")
} catch {}
check(try metadataRestart.metadata(id: earlierID)["endedAt"] == nil)
let queuedTimingID = UUID().uuidString.lowercased()
try timingRestart.create(
  id: queuedTimingID,
  metadata: [
    "workoutId": queuedTimingID, "startedAt": timingAnchor.startedAt, "phase": "running",
    "saveToHealth": false, "recordGPS": false,
    "timelineAnchor": WorkoutCoding.dictionary(timingAnchor), "checkpoint": WorkoutCoding.dictionary(timingCheckpoint),
  ])
let queuedFinish = try timingRestart.control.admitLocal(
  workoutID: queuedTimingID, origin: "watch", action: "stop", at: stamp.addingTimeInterval(1000),
  options: [
    "cutoffUTC": .string(retained.timestamp), "cutoffElapsedSeconds": .number(retained.elapsedSeconds),
    "timerSeconds": .number(retained.timerSeconds),
  ])
let queuedRecovery = try timingRestart.recoverTiming(
  id: queuedTimingID, epoch: "never-resumed", uptime: 2000, resumedAt: stamp.addingTimeInterval(1000), running: true)
check(try WatchWorkoutJournal.timing(queuedRecovery) == retained)
check(try timingRestart.archive.metadata(id: queuedTimingID).eventCount == 0)
check(
  try timingRestart.control.command(id: queuedFinish.id)?.requestedAt
    == WorkoutCoding.timestamp(stamp.addingTimeInterval(1000)))
let phoneStopID = UUID().uuidString.lowercased()
try timingRestart.create(
  id: phoneStopID,
  metadata: [
    "workoutId": phoneStopID, "startedAt": timingAnchor.startedAt, "phase": "running",
    "saveToHealth": false, "recordGPS": false,
    "timelineAnchor": WorkoutCoding.dictionary(timingAnchor), "checkpoint": WorkoutCoding.dictionary(timingCheckpoint),
  ])
_ = try timingRestart.control.admitLocal(
  workoutID: phoneStopID, origin: "phone", action: "stop", at: stamp.addingTimeInterval(1000),
  options: [
    "cutoffUTC": .string(WorkoutCoding.timestamp(stamp.addingTimeInterval(1000))),
    "cutoffElapsedSeconds": .number(1000), "timerSeconds": .number(1000),
  ])
let phoneStopRecovery = try timingRestart.recoverTiming(
  id: phoneStopID, epoch: "owner-unavailable", uptime: 2000, resumedAt: stamp.addingTimeInterval(1000), running: nil)
check(try WatchWorkoutJournal.timing(phoneStopRecovery) == timingCheckpoint)
check(phoneStopRecovery["endedAt"] == nil)
for action in ["pause", "resume"] {
  let transitionID = UUID().uuidString.lowercased()
  let checkpoint = try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(stamp.addingTimeInterval(8)), elapsedSeconds: 8, timerSeconds: 6)
  let priorPhase = action == "pause" ? "running" : "paused"
  try timingRestart.create(
    id: transitionID,
    metadata: [
      "workoutId": transitionID, "startedAt": timingAnchor.startedAt, "phase": priorPhase,
      "saveToHealth": false, "recordGPS": false,
      "timelineAnchor": WorkoutCoding.dictionary(timingAnchor), "checkpoint": WorkoutCoding.dictionary(checkpoint),
    ])
  _ = try timingRestart.control.observe(
    workoutID: transitionID, owner: "watch", phase: priorPhase,
    at: stamp.addingTimeInterval(8), health: "notRequested", timing: checkpoint)
  let command = try timingRestart.control.admitLocal(
    workoutID: transitionID, origin: "watch", action: action,
    at: stamp.addingTimeInterval(10))
  check(try timingRestart.control.prepare(command).execute)
  let captured = try WorkoutEvent(
    workoutId: transitionID, kind: "location", source: "watch",
    timestamp: stamp.addingTimeInterval(-200), elapsedSeconds: 12,
    payload: ["latitude": .number(0), "longitude": .number(0)])
  try timingRestart.archive.append(captured)
  try timingRestart.archive.append(
    WorkoutEvent(
      workoutId: transitionID, kind: "health", source: "watch", timestamp: stamp.addingTimeInterval(3600),
      elapsedSeconds: 3600, payload: ["representation": .string("rawSeries"), "heartRateBpm": .number(100)]))
  let nativeRunning = action == "resume"
  let recoveredMetadata = try timingRestart.recoverTiming(
    id: transitionID, epoch: "transition-restart",
    uptime: 2, resumedAt: stamp.addingTimeInterval(1000), running: nativeRunning)
  let recoveredTiming = try WatchWorkoutJournal.timing(recoveredMetadata)!
  check(recoveredTiming.elapsedSeconds == 12 && recoveredTiming.timerSeconds == 6)
  check(recoveredTiming.timestamp == captured.timestamp)
  check(recoveredMetadata["error"] as? String != nil)
  check(try WatchWorkoutJournal.timeline(recoveredMetadata)?.uncertainty?.contains("Active time") == true)
  _ = try WorkoutRecoveredOwnerCommand.reconcile(
    workoutID: transitionID, owner: "watch",
    nativePhase: nativeRunning ? "running" : "paused", observedAt: stamp.addingTimeInterval(1001),
    nativeEventDates: [action: stamp.addingTimeInterval(10)], observedLapIDs: [], cutoff: nil,
    health: "notRequested", healthID: nil, archive: timingRestart.archive, control: timingRestart.control,
    timing: recoveredTiming)
  let afterReconcile = try WatchWorkoutJournal.timing(timingRestart.metadata(id: transitionID))!
  check(afterReconcile.elapsedSeconds == 12 && afterReconcile.timerSeconds == 6)
  check(try timingRestart.archive.hasEvent(id: transitionID, eventID: command.id))
  let recoveredEvents = try timingRestart.archive.pageEvents(id: transitionID).map(\.event)
  let uncertainPause = recoveredEvents.first { $0.payload["action"] == .string("pause") && $0.elapsedSeconds == 8 }!
  check(uncertainPause.timestamp == checkpoint.timestamp && uncertainPause.payload["interrupted"] != .bool(true))
  check(uncertainPause.payload["timelineMappingUncertainty"] != nil)
  check(recoveredEvents.first { $0.eventId == command.id }?.elapsedSeconds == 12)
  let hardBoundary = recoveredEvents.first { $0.payload["interrupted"] == .bool(true) }!
  check(hardBoundary.timestamp == captured.timestamp && hardBoundary.elapsedSeconds == 12)
  if nativeRunning {
    check(
      recoveredEvents.first {
        $0.eventId == WorkoutStableIdentity.uuid("watch-resume:\(transitionID):transition-restart")
      }?.timestamp
        == WorkoutCoding.timestamp(stamp.addingTimeInterval(1000)))
  }
  let newCapture = try afterReconcile.advancing(
    anchor: WatchWorkoutJournal.timeline(recoveredMetadata)!,
    at: stamp.addingTimeInterval(-1000), from: 2, to: 4, running: nativeRunning)
  check(newCapture.elapsedSeconds == 14 && newCapture.timerSeconds == (nativeRunning ? 8 : 6))
}
for committedTransition in [false, true] {
  let nativeTransitionID = UUID().uuidString.lowercased()
  let checkpoint = try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(stamp.addingTimeInterval(8)), elapsedSeconds: 8, timerSeconds: 6)
  try timingRestart.create(
    id: nativeTransitionID,
    metadata: [
      "workoutId": nativeTransitionID, "startedAt": timingAnchor.startedAt, "phase": "running",
      "saveToHealth": false, "recordGPS": false,
      "timelineAnchor": WorkoutCoding.dictionary(timingAnchor), "checkpoint": WorkoutCoding.dictionary(checkpoint),
    ])
  if committedTransition {
    try timingRestart.archive.append(
      WorkoutEvent(
        workoutId: nativeTransitionID, kind: "lifecycle", source: "watch",
        timestamp: stamp.addingTimeInterval(10), elapsedSeconds: 10, payload: ["action": .string("pause")]))
  }
  try timingRestart.archive.append(
    WorkoutEvent(
      workoutId: nativeTransitionID, kind: "location", source: "watch",
      timestamp: stamp.addingTimeInterval(12), elapsedSeconds: 12,
      payload: ["latitude": .number(0), "longitude": .number(0)]))
  let nativeRecovery = try timingRestart.recoverTiming(
    id: nativeTransitionID, epoch: "native-transition",
    uptime: 2, resumedAt: stamp.addingTimeInterval(1000), running: false)
  let nativeTiming = try WatchWorkoutJournal.timing(nativeRecovery)!
  check(nativeTiming.elapsedSeconds == 12 && nativeTiming.timerSeconds == (committedTransition ? 8 : 6))
}
print("Watch retained recovery timing, committed floor, cutoff immutability and receipt repair helper checks passed")

print("Watch total: \(assertions) assertions passed")
