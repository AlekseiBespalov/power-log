import Foundation

var assertions = 0
func check(_ value: Bool, _ message: String) { assertions += 1; if !value { fatalError(message) } }
func rejects(_ message: String, _ body: () throws -> Void) {
  do { try body(); check(false, message) } catch { check(true, message) }
}
for rate in [2.0, 4.0, 8.0] { check(try WorkoutRecordingPolicy.sampleHz(rate) == rate, "supported start sampling rate remains exact") }
for rate in [0.0, 1.0, 3.0, 16.0, .infinity, .nan] { rejects("invalid start sampling rate fails admission") { _ = try WorkoutRecordingPolicy.sampleHz(rate) } }
var sampling = WorkoutSamplingOwner()
sampling.update(id: "active", rate: 8)
check(sampling.connectionRate(2) == 8, "reconnection cannot apply a future default to an admitted owner")
sampling.update(id: "active", rate: 2)
check(sampling.connectionRate(4) == 8, "repeated paused/recoverable ownership updates preserve admitted rate")
sampling.update(id: nil, rate: 8)
check(sampling.connectionRate(4) == 4, "terminal owner releases future connection defaults")
sampling.update(id: "next", rate: 4)
check(sampling.connectionRate(2) == 4, "a new ride admits its own rate")
var recoveredSampling = WorkoutSamplingOwner()
recoveredSampling.update(id: "next", rate: 4)
check(recoveredSampling.connectionRate(8) == 4, "restored owner rate takes precedence over changed connection defaults")
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-recording-options-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let start = Date(timeIntervalSince1970: 1_780_000_000)
func archive(_ name: String) throws -> WorkoutArchive {
  try WorkoutArchive(rootURL: root.appendingPathComponent(name), store: PowerLogStore.shared(databaseURL: root.appendingPathComponent(name + ".sqlite3")))
}
let local = try archive("local"), control = WorkoutControlJournal(store: local.store)
func telemetry(_ id: String, _ seconds: Double) throws -> WorkoutEvent {
  try WorkoutEvent(workoutId: id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(seconds), elapsedSeconds: seconds,
    payload: ["humanPowerW": .number(192.125), "cadenceRpm": .number(86.75), "motorInputPowerW": .number(311.25)])
}

// Legacy absence remains readable; explicit GPS is independent of indoor.
for indoor in [false, true] {
  let old = WorkoutMetadata(id: UUID().uuidString.lowercased(), startedAt: WorkoutCoding.timestamp(start), indoor: indoor, watchEnabled: false)
  let decoded = try JSONDecoder().decode(WorkoutMetadata.self, from: WorkoutCoding.encoder().encode(old))
  check(decoded.savesToHealth && decoded.recordsGPS == !indoor, "legacy defaults decode without migration")
  for saves in [false, true] { for gps in [false, true] { for watch in [false, true] {
    let m = try local.create(startedAt: start, indoor: indoor, watchEnabled: watch, saveToHealth: saves, recordGPS: gps)
    let reopened = try WorkoutArchive(rootURL: local.rootURL, store: local.store).metadata(id: m.id)
    check(reopened.savesToHealth == saves && reopened.recordsGPS == gps, "flags freeze across archive reconstruction")
    check(reopened.dictionary["saveToHealth"] as? Bool == saves && reopened.dictionary["recordGPS"] as? Bool == gps, "bridge exposes effective options")
    let command = try WorkoutCommand(workoutID: m.id, origin: "phone", originSequence: 1, action: "start", requestedAt: start,
      options: ["indoor": .bool(indoor), "saveToHealth": .bool(saves), "recordGPS": .bool(gps)])
    check(try WorkoutCommand.decode(command.packet) == command, "start command preserves options")
    let event = try telemetry(m.id, 1)
    try local.append(event)
    let insertion = WorkoutHealthInsertionJournal(archive: local)
    var effects = 0, completed = false, failed = false
    insertion.perform([event], operation: { done in effects += 1; done(.success("applied")) }, completion: { result in
      completed = true; if case .failure = result { failed = true }
    })
    check(completed && effects == (saves ? 1 : 0) && failed == !saves, "actual insertion boundary never runs an opted-out Health effect")
    if !saves {
      check(try insertion.pending(id: m.id).isEmpty && insertion.outcome(id: m.id) == "notRequested", "skipped insertion cannot accumulate retry work")
      check(try !insertion.needsRepair(event), "skipped originals never request repair")
      rejects("repair cannot reopen skipped saving") { try insertion.beginRepair(id: m.id) }
      rejects("archive outcome cannot later become saved") { _ = try local.update(id: m.id, healthKitState: "saved") }
      rejects("owner cannot report a saved skipped workout") { _ = try control.observe(workoutID: m.id, owner: watch ? "watch" : "phone", phase: "running", at: start, health: "saved") }
    }
  } } }
}
let malformedID = UUID().uuidString.lowercased()
rejects("malformed new option does not silently become legacy true") {
  _ = try WorkoutCommand(workoutID: malformedID, origin: "phone", originSequence: 1, action: "start", requestedAt: start,
    options: ["saveToHealth": .string("false")])
}

// Real local owner commands commit event + receipt atomically, including retry after a storage failure.
let ride = try local.create(startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
func apply(_ action: String, seconds: Double, failCommit: Bool = false) throws -> WorkoutCommand {
  let command = try control.admitLocal(workoutID: ride.id, origin: "phone", action: action, at: start.addingTimeInterval(seconds))
  check(try control.prepare(command).execute, "local action is admitted once")
  if failCommit {
    local.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Injected commit failure") }
    rejects("local effect rolls back without a receipt") {
      _ = try WorkoutLocalOwner.observe(id: ride.id, phase: "paused", at: start.addingTimeInterval(seconds), elapsed: seconds,
        command: command, cutoff: nil, discarded: false, archive: local, control: control)
    }
    local.store.beforeCommitForTesting = nil
    check(try !local.hasEvent(id: ride.id, eventID: command.id) && control.result(id: command.id)?.outcome == "executing", "retry retains intent and no partial original")
  }
  let phase = action == "pause" ? "paused" : action == "stop" ? "completed" : "running"
  let date = start.addingTimeInterval(seconds)
  let snapshot = try WorkoutLocalOwner.observe(id: ride.id, phase: phase, at: date, elapsed: seconds,
    command: command, cutoff: action == "stop" ? date : nil, discarded: false, archive: local, control: control)
  check(snapshot.healthOutcome == "notRequested" && snapshot.healthWorkoutID == nil, "local owner needs no Health identity")
  check(try local.hasEvent(id: ride.id, eventID: command.id) && control.result(id: command.id)?.outcome == "applied", "original and receipt share the durable effect")
  return command
}
_ = try apply("start", seconds: 0)
let beforePause = try telemetry(ride.id, 1), paused = try telemetry(ride.id, 3), afterResume = try telemetry(ride.id, 5)
try local.append(beforePause)
_ = try apply("pause", seconds: 2, failCommit: true)
try local.append(paused)
_ = try apply("resume", seconds: 4)
try local.append(afterResume)
_ = try apply("lap", seconds: 5.5)
let stop = try apply("stop", seconds: 6)
try local.update(id: ride.id, healthKitState: "notRequested", stopElapsedSeconds: 6)
try local.finish(id: ride.id, endedAt: start.addingTimeInterval(6))
check(try local.hasEvent(id: ride.id, eventID: paused.eventId), "paused CYC originals are retained")
check(try WorkoutHealthEligibility.permits(beforePause, archive: local) && !WorkoutHealthEligibility.permits(paused, archive: local) && WorkoutHealthEligibility.permits(afterResume, archive: local), "canonical lifecycle retains active and paused intervals")
let phoneSeal = try WorkoutPhoneSealRepair.seal(id: ride.id, archive: local, transfer: WorkoutTransferJournal(archive: local), control: control)
check(phoneSeal.resolved && !phoneSeal.partial && phoneSeal.requirements["healthSave"] == "notRequested", "phone local Save produces a complete skipped-Health seal")
check(try local.metadata(id: ride.id).finalizationState == "complete" && !local.store.isWorkoutDeleted(id: ride.id), "Save retains verified originals")
let reopenedControl = WorkoutControlJournal(store: local.store)
check(try reopenedControl.prepare(stop).result.outcome == "applied", "stop retry after restart cannot rerun effects")
var savedAgain = try reopenedControl.snapshot(workoutID: ride.id)!
savedAgain.ownerRevision += 1; savedAgain.healthOutcome = "saved"
rejects("delayed owner saved transition is fenced") { _ = try reopenedControl.accept(snapshot: savedAgain) }

// Late originals and failed writes are rediscovered by ID after the selected ride has changed.
let late = try telemetry(ride.id, 5.75)
try local.append(late)
check(try WorkoutPhoneSealRepair.pendingLocal(archive: local).contains(ride.id), "later original invalidates and rediscovers the old local seal")
let another = try local.create(startedAt: start.addingTimeInterval(100), indoor: true, watchEnabled: false, saveToHealth: false)
try local.store.transaction { db in try db.put(namespace: "phone-current", key: "workout", value: Data(another.id.utf8)) }
local.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Injected seal failure") }
rejects("failed seal stays eligible") { _ = try WorkoutPhoneSealRepair.seal(id: ride.id, archive: local, transfer: WorkoutTransferJournal(archive: local), control: control) }
local.store.beforeCommitForTesting = nil
check(try WorkoutPhoneSealRepair.pendingLocal(archive: local).contains(ride.id), "storage failure preserves durable rediscovery")
_ = try WorkoutPhoneSealRepair.seal(id: ride.id, archive: local, transfer: WorkoutTransferJournal(archive: local), control: control)
check(try !WorkoutPhoneSealRepair.pendingLocal(archive: local).contains(ride.id), "successful exact verification removes only settled work")
check(try local.store.read { try $0.get(namespace: "phone-current", key: "workout") } == Data(another.id.utf8), "historical seal repair never selects another ride")
check(try WorkoutTransferJournal(archive: local).verify(id: ride.id) && local.hasEvent(id: ride.id, eventID: late.eventId), "late original is retained by the repaired strict seal")

// A persisted stop cutoff with a lost owner commit is repaired before any final seal can verify.
let interruptedStop = try local.create(startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false)
_ = try control.observe(workoutID: interruptedStop.id, owner: "phone", phase: "running", at: start, health: "notRequested")
let interruptedCommand = try control.admitLocal(workoutID: interruptedStop.id, origin: "phone", action: "stop", at: start.addingTimeInterval(9))
_ = try control.prepare(interruptedCommand)
try local.update(id: interruptedStop.id, stopElapsedSeconds: 9)
try local.finish(id: interruptedStop.id, endedAt: start.addingTimeInterval(9), finalPhase: "finishing")
local.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Injected owner stop commit failure") }
rejects("stop owner and original roll back together") {
  _ = try WorkoutLocalOwner.observe(id: interruptedStop.id, phase: "completed", at: start.addingTimeInterval(9), elapsed: 9,
    command: interruptedCommand, cutoff: start.addingTimeInterval(9), discarded: false, archive: local, control: control)
}
local.store.beforeCommitForTesting = nil
check(try control.active(workoutID: interruptedStop.id)?.id == interruptedCommand.id && control.snapshot(workoutID: interruptedStop.id)?.phase == "running", "failed owner commit remains explicitly executing")
let beforeStaleRestore = try local.metadata(id: interruptedStop.id).eventCount
let staleRestore = try WorkoutLocalOwner.restore(id: interruptedStop.id, epoch: "previous-process", checkpointElapsed: 5,
  needsInterruption: true, archive: local)
check(staleRestore.cutoff == start.addingTimeInterval(9) && staleRestore.elapsed == 9, "committed archive stop outranks the old running phone checkpoint")
check(try local.metadata(id: interruptedStop.id).eventCount == beforeStaleRestore, "stale restore does not add a pause after a retained stop")
let repairedStop = try WorkoutPhoneSealRepair.seal(id: interruptedStop.id, archive: local, transfer: WorkoutTransferJournal(archive: local), control: control)
check(try repairedStop.resolved && control.active(workoutID: interruptedStop.id) == nil, "by-ID local repair commits stop before final seal")
check(try control.result(id: interruptedCommand.id)?.outcome == "applied" && control.snapshot(workoutID: interruptedStop.id)?.stopCutoff == WorkoutCoding.timestamp(start.addingTimeInterval(9)), "original stop identity and cutoff survive repair")
check(try local.hasEvent(id: interruptedStop.id, eventID: interruptedCommand.id) && WorkoutTransferJournal(archive: local).verify(id: interruptedStop.id), "repaired stop original participates in exact final verification")
let afterOwnerRestore = try WorkoutLocalOwner.restore(id: interruptedStop.id, epoch: "previous-process", checkpointElapsed: 5,
  needsInterruption: true, archive: local)
check(afterOwnerRestore.cutoff == staleRestore.cutoff && afterOwnerRestore.elapsed == staleRestore.elapsed, "restore after owner receipt and seal retains the same immutable cutoff")

// The local Resume effect cannot outrun its phone checkpoint: a projection write
// failure rolls back the owner, original and checkpoint as one production action.
let resumeFailure = try local.create(startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false)
_ = try control.observe(workoutID: resumeFailure.id, owner: "phone", phase: "paused", at: start.addingTimeInterval(2), health: "notRequested")
try local.append(WorkoutEvent(workoutId: resumeFailure.id, kind: "lifecycle", source: "phone", timestamp: start.addingTimeInterval(2),
  elapsedSeconds: 2, payload: ["action": .string("pause")]))
let pausedCheckpoint = Data("paused timer=2 elapsed=2".utf8)
try local.store.transaction { try $0.put(namespace: "phone-current", key: "workout", value: pausedCheckpoint) }
let resumeAttempt = try control.admitLocal(workoutID: resumeFailure.id, origin: "phone", action: "resume", at: start.addingTimeInterval(10))
_ = try control.prepare(resumeAttempt)
rejects("failed Resume checkpoint does not publish a running owner") {
  _ = try WorkoutLocalOwner.observe(id: resumeFailure.id, phase: "running", at: start.addingTimeInterval(10), elapsed: 10,
    command: resumeAttempt, cutoff: nil, discarded: false, archive: local, control: control, checkpoint: {
      try local.store.transaction { try $0.put(namespace: "phone-current", key: "workout", value: Data("running".utf8)) }
      throw WorkoutDataError.invalid("Injected phone checkpoint failure")
    })
}
check(try control.snapshot(workoutID: resumeFailure.id)?.phase == "paused" && !local.hasEvent(id: resumeFailure.id, eventID: resumeAttempt.id), "failed Resume retains the previous paused owner and lifecycle")
check(try local.store.read { try $0.get(namespace: "phone-current", key: "workout") } == pausedCheckpoint, "failed Resume retains the frozen phone timer checkpoint")
check(try control.active(workoutID: resumeFailure.id)?.id == resumeAttempt.id, "failed Resume remains explicitly recoverable")
_ = try control.observe(workoutID: resumeFailure.id, owner: "phone", phase: "paused", at: start.addingTimeInterval(100), health: "notRequested",
  command: resumeAttempt, failure: "Resume did not commit")
let stopAfterResumeFailure = try control.admitLocal(workoutID: resumeFailure.id, origin: "phone", action: "stop", at: start.addingTimeInterval(100))
_ = try control.prepare(stopAfterResumeFailure)
_ = try WorkoutLocalOwner.observe(id: resumeFailure.id, phase: "completed", at: start.addingTimeInterval(100), elapsed: 100,
  command: stopAfterResumeFailure, cutoff: start.addingTimeInterval(100), discarded: false, archive: local, control: control)
try local.finish(id: resumeFailure.id, endedAt: start.addingTimeInterval(100))
check(try WorkoutFIT.summarize(archive: local, id: resumeFailure.id).timerSeconds == 2, "recovery and Finish after failed Resume retain only the original active time")

// Every local action uses the owner transaction's checkpoint boundary. Inject a
// failure after owner/original writes, then retry, including Start and terminal Save/Discard.
for discardEnd in [false, true] {
  let atomic = try local.create(startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false)
  try local.update(id: atomic.id, phase: "preparing")
  var previousCheckpoint = Data("preparing".utf8)
  try local.store.transaction { try $0.put(namespace: "phone-current", key: "workout", value: previousCheckpoint) }
  for (index, action) in ["start", "pause", "resume", "lap", discardEnd ? "discard" : "stop"].enumerated() {
    let seconds = Double(index), at = start.addingTimeInterval(seconds)
    let nextPhase = action == "pause" ? "paused" : ["stop", "discard"].contains(action) ? "completed" : "running"
    let command = try control.admitLocal(workoutID: atomic.id, origin: "phone", action: action, at: at,
      options: ["cutoffElapsedSeconds": .number(seconds), "timerSeconds": .number(max(0, seconds - 1))])
    _ = try control.prepare(command)
    let previousOwner = try control.snapshot(workoutID: atomic.id), previousCount = try local.metadata(id: atomic.id).eventCount
    let checkpoint = Data("\(nextPhase):\(seconds)".utf8)
    func applyAtomic(_ fail: Bool) throws {
      _ = try WorkoutLocalOwner.observe(id: atomic.id, phase: nextPhase, at: at, elapsed: seconds, command: command,
        cutoff: command.endsWorkout ? at : nil, discarded: action == "discard", archive: local, control: control, checkpoint: {
          if action == "start" { _ = try WorkoutPhoneStartProjection.confirm(archive: local, id: atomic.id, startedAt: at, now: at, uptime: 20, epoch: "local-start") }
          try local.update(id: atomic.id, phase: nextPhase)
          if command.endsWorkout { try local.update(id: atomic.id, stopElapsedSeconds: seconds); try local.finish(id: atomic.id, endedAt: at) }
          try local.store.transaction { try $0.put(namespace: "phone-current", key: "workout", value: checkpoint) }
          if fail { throw WorkoutDataError.invalid("Injected \(action) checkpoint commit failure") }
        })
    }
    rejects("\(action) checkpoint failure is atomic") { try applyAtomic(true) }
    check(try control.snapshot(workoutID: atomic.id) == previousOwner && local.metadata(id: atomic.id).eventCount == previousCount,
      "\(action) owner and original roll back before checkpoint confirmation")
    check(try local.store.read { try $0.get(namespace: "phone-current", key: "workout") } == previousCheckpoint, "\(action) cannot replace its prior checkpoint on failure")
    if command.endsWorkout {
      let restored = try WorkoutLocalOwner.restore(id: atomic.id, epoch: "old-epoch", checkpointElapsed: 1,
        needsInterruption: true, archive: local, pendingCommand: command)
      check(restored.cutoff == at && restored.elapsed == seconds && restored.timer == seconds - 1, "pending terminal intent restores exact cutoff and timer before any later retry")
      check(try local.metadata(id: atomic.id).eventCount == previousCount, "pending terminal intent does not add a conflicting interruption")
    }
    try applyAtomic(false)
    check(try control.result(id: command.id)?.outcome == "applied" && local.hasEvent(id: atomic.id, eventID: command.id), "\(action) original and receipt commit on retry")
    check(try local.store.read { try $0.get(namespace: "phone-current", key: "workout") } == checkpoint, "\(action) publishes its matching checkpoint")
    previousCheckpoint = checkpoint
  }
  check(try WorkoutFIT.summarize(archive: local, id: atomic.id).timerSeconds == 3, "complete atomic local lifecycle yields the same active FIT duration")
}

// Discard has a different command/outcome and remains the explicit deletion entry point.
let discarded = try local.create(startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false)
_ = try control.observe(workoutID: discarded.id, owner: "phone", phase: "running", at: start, health: "notRequested")
let discard = try control.admitLocal(workoutID: discarded.id, origin: "phone", action: "discard", at: start.addingTimeInterval(4))
_ = try control.prepare(discard)
_ = try WorkoutLocalOwner.observe(id: discarded.id, phase: "completed", at: start.addingTimeInterval(4), elapsed: 4,
  command: discard, cutoff: start.addingTimeInterval(4), discarded: true, archive: local, control: control)
check(try control.snapshot(workoutID: discarded.id)?.healthOutcome == "discarded", "Discard remains distinct from skipped Health Save")
_ = try local.store.markWorkoutDeleted(id: discarded.id)
check(try local.store.isWorkoutDeleted(id: discarded.id) && !local.store.isWorkoutDeleted(id: ride.id), "only explicit Discard deletes its collection")

// Watch originals establish intent before status; Data/file paths retain the same frozen intent and exact seal.
let watch = try WatchWorkoutJournal(rootURL: root.appendingPathComponent("watch"))
let watchID = UUID().uuidString.lowercased()
let initial: [String: Any] = ["workoutId": watchID, "startedAt": WorkoutCoding.timestamp(start), "indoor": false,
  "saveToHealth": false, "recordGPS": false, "healthKitState": "notRequested", "phase": "running"]
try watch.create(id: watchID, metadata: initial)
_ = try watch.control.observe(workoutID: watchID, owner: "watch", phase: "running", at: start, health: "notRequested")
let sensor = try WorkoutEvent(workoutId: watchID, kind: "health", source: "watch", timestamp: start.addingTimeInterval(1), elapsedSeconds: 1,
  payload: ["heartRateBpm": .number(141.25), "representation": .string("rawQuantity")])
try watch.archive.append(sensor)
let sender = WorkoutChunkSender(archive: watch.archive), chunk = try sender.prepare(id: watchID)!
let wire = try WorkoutChunkWire.encode(chunk: chunk, startedAt: initial["startedAt"] as! String, indoor: false, saveToHealth: false, recordGPS: false)!
let decoded = try WorkoutChunkWire.decode(wire)
check(decoded.metadata["saveToHealth"] as? Bool == false && decoded.metadata["recordGPS"] as? Bool == false, "full wire carries opt-out before owner status")
let destination = try archive("destination"), receiver = WorkoutTransferJournal(archive: destination)
let inbox = try WorkoutChunkInbox(root: root.appendingPathComponent("inbox"), store: destination.store)
check(try inbox.stage(data: decoded.chunk.data, metadata: decoded.metadata), "opted-out original uses normal inbox")
let item = try inbox.claim()!
check(try receiver.receiveWatch(WorkoutChunk(manifest: chunk.manifest, data: Data(contentsOf: inbox.url(item))), startedAt: initial["startedAt"] as! String,
  indoor: false, saveToHealth: false, recordGPS: false), "file worker adopts the original frozen options")
try inbox.finish(item, success: true)
let received = try destination.metadata(id: watchID)
check(!received.savesToHealth && !received.recordsGPS && received.endedAt == nil, "chunk adoption creates an active local collection without an end")
rejects("duplicate with changed options cannot silently enable Health") {
  _ = try receiver.receiveWatch(chunk, startedAt: initial["startedAt"] as! String, indoor: false)
}
let owner = try watch.control.observe(workoutID: watchID, owner: "watch", phase: "completed", at: start.addingTimeInterval(2), health: "notRequested", cutoff: start.addingTimeInterval(2))
let source = try watch.transfer.source(id: watchID, producer: "watch")
let seal = WorkoutSeal(workoutID: watchID, sealRevision: 1, collectionRevision: try watch.archive.revision(id: watchID), ownerRevision: owner.ownerRevision,
  stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(2)), healthOutcome: "notRequested",
  requirements: ["healthSave": "notRequested", "cycInsertion": "notRequested", "healthExtraction": "notRequested", "localSensors": "sealed", "ownerEnded": "sealed", "gps": "notRequested"],
  sources: [source], stopElapsedSeconds: 2, saveToHealth: false, recordGPS: false)
check(try receiver.accept(seal: seal), "skipped Health and GPS requirements are accepted")
check(try receiver.verify(id: watchID), "normal exact-source final verification succeeds with skipped Health")
check(try destination.pageEvents(id: watchID).first?.event == sensor, "sensor originals retain exact data and identity")
var changed = initial; changed["saveToHealth"] = true; changed["healthKitState"] = "saved"
rejects("Watch journal rejects intent mutation across recovery") { try watch.save(id: watchID, metadata: changed) }
let snapshot = WorkoutOwnerSnapshot(workoutID: watchID, owner: "watch", ownerRevision: 3, effectiveAt: WorkoutCoding.timestamp(start), phase: "running", healthOutcome: "notRequested")
rejects("owner adoption cannot overwrite the chunk's frozen intent") {
  _ = try WorkoutOwnerAdoption.accept(snapshot, archive: destination, control: WorkoutControlJournal(store: destination.store), startedAt: start, indoor: false)
}
// Boundary-spanning series parents remain query candidates; exact points alone belong inside the ride.
let window = WorkoutSensorWindow(start: start, cutoff: start.addingTimeInterval(10))
check(!window.contains(start: start.addingTimeInterval(-2), end: start.addingTimeInterval(12)), "parent aggregate cannot substitute for in-window points")
let pointIntervals: [(Double, Double)] = [(-2,-1),(-1,0),(0,1),(9,10),(10,11),(11,12)]
let retainedPoints = pointIntervals.filter { window.contains(start: start.addingTimeInterval($0.0), end: start.addingTimeInterval($0.1)) }
check(retainedPoints.count == 2 && retainedPoints[0].0 == 0 && retainedPoints[1].1 == 10, "only exact contained series points survive")
let seriesEvents = try retainedPoints.map { point in
  try WorkoutEvent(workoutId: watchID, kind: "health", source: "watch", timestamp: start.addingTimeInterval(point.1), elapsedSeconds: point.1,
    payload: ["representation": .string("rawSeries"), "heartRateBpm": .number(140), "sampleStart": .string(WorkoutCoding.timestamp(start.addingTimeInterval(point.0))),
      "sampleEnd": .string(WorkoutCoding.timestamp(start.addingTimeInterval(point.1)))], eventId: WorkoutStableIdentity.uuid("boundary-series:" + String(point.0)))
}
try watch.commitHealthPage(id: watchID, events: seriesEvents, progressKey: "local-final-test", anchor: Data([1]), completed: true)
let afterPoints = try watch.archive.metadata(id: watchID).eventCount
try watch.commitHealthPage(id: watchID, events: seriesEvents, progressKey: "local-final-test", anchor: Data([1]), completed: true)
check(try watch.archive.metadata(id: watchID).eventCount == afterPoints, "replayed boundary series does not duplicate originals")
var pendingRequirements = seal.requirements; pendingRequirements["ownerEnded"] = "pending"
let early = WorkoutSeal(workoutID: watchID, sealRevision: 2, collectionRevision: seal.collectionRevision, ownerRevision: seal.ownerRevision,
  stopCutoff: seal.stopCutoff, healthOutcome: "notRequested", requirements: pendingRequirements, sources: seal.sources,
  stopElapsedSeconds: seal.stopElapsedSeconds, saveToHealth: false, recordGPS: false)
check(!early.resolved, "local extraction alone cannot seal before native owner end")
_ = try receiver.accept(seal: early)
check(try !receiver.verify(id: watchID), "strict final verification waits for owner-ended requirement")

// A process restart closes active time at the persisted checkpoint before either
// direct Finish or explicit recovery. The same original drives eligibility and FIT.
for (resumeAfterRecovery, checkpointTime) in [(false, 10.0), (true, 10.0), (false, 11.0), (true, 11.0)] {
  let interrupted = try local.create(startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false)
  let initialCommand = try control.admitLocal(workoutID: interrupted.id, origin: "phone", action: "start", at: start)
  _ = try control.prepare(initialCommand)
  _ = try WorkoutLocalOwner.observe(id: interrupted.id, phase: "running", at: start, elapsed: 0, command: initialCommand,
    cutoff: nil, discarded: false, archive: local, control: control)
  try local.append(telemetry(interrupted.id, 1))
  let beforeInterruption = try local.metadata(id: interrupted.id).eventCount
  local.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Injected interruption checkpoint failure") }
  rejects("failed interruption cannot partially retain its pause") {
    _ = try WorkoutLocalOwner.restore(id: interrupted.id, epoch: "old-process", checkpointElapsed: checkpointTime, needsInterruption: true, archive: local)
  }
  local.store.beforeCommitForTesting = nil
  check(try local.metadata(id: interrupted.id).eventCount == beforeInterruption, "failed interruption rolls back the original and producer registration")
  _ = try WorkoutLocalOwner.restore(id: interrupted.id, epoch: "old-process", checkpointElapsed: checkpointTime, needsInterruption: true, archive: local)
  let afterInterruption = try local.metadata(id: interrupted.id).eventCount
  _ = try WorkoutLocalOwner.restore(id: interrupted.id, epoch: "old-process", checkpointElapsed: checkpointTime, needsInterruption: true, archive: local)
  check(try local.metadata(id: interrupted.id).eventCount == afterInterruption, "repeated restore is idempotent at the checkpoint")
  let pause = try local.pageEvents(id: interrupted.id).first { $0.event.payload["interrupted"] == .bool(true) }!.event
  check(pause.elapsedSeconds == checkpointTime && pause.timestamp == WorkoutCoding.timestamp(start.addingTimeInterval(checkpointTime)), "interruption has its original checkpoint time rather than recovery time")
  let gapOriginal = try telemetry(interrupted.id, 20)
  try local.append(gapOriginal)
  check(try !WorkoutHealthEligibility.permits(gapOriginal, archive: local), "a retained late original inside the interrupted interval is not active riding")
  var liveTimer = checkpointTime
  if resumeAfterRecovery {
    // Engine recovers paused at t=100; a pause at recovery must not reopen t=10...100.
    _ = try control.observe(workoutID: interrupted.id, owner: "phone", phase: "paused", at: start.addingTimeInterval(100), health: "notRequested")
    try local.append(WorkoutEvent(workoutId: interrupted.id, kind: "lifecycle", source: "phone", timestamp: start.addingTimeInterval(100),
      elapsedSeconds: 100, payload: ["action": .string("pause")]))
    let resumeCommand = try control.admitLocal(workoutID: interrupted.id, origin: "phone", action: "resume", at: start.addingTimeInterval(120))
    _ = try control.prepare(resumeCommand)
    _ = try WorkoutLocalOwner.observe(id: interrupted.id, phase: "running", at: start.addingTimeInterval(120), elapsed: 120,
      command: resumeCommand, cutoff: nil, discarded: false, archive: local, control: control)
    liveTimer += 10
  }
  let end = resumeAfterRecovery ? 130.0 : 100.0
  let finalCommand = try control.admitLocal(workoutID: interrupted.id, origin: "phone", action: "stop", at: start.addingTimeInterval(end))
  _ = try control.prepare(finalCommand)
  _ = try WorkoutLocalOwner.observe(id: interrupted.id, phase: "completed", at: start.addingTimeInterval(end), elapsed: end,
    command: finalCommand, cutoff: start.addingTimeInterval(end), discarded: false, archive: local, control: control)
  try local.update(id: interrupted.id, stopElapsedSeconds: end)
  try local.finish(id: interrupted.id, endedAt: start.addingTimeInterval(end), finalPhase: "completed")
  _ = try WorkoutPhoneSealRepair.seal(id: interrupted.id, archive: local, transfer: WorkoutTransferJournal(archive: local), control: control)
  let summary = try WorkoutFIT.summarize(archive: local, id: interrupted.id)
  let fitURL = root.appendingPathComponent(interrupted.id + ".fit")
  let exported = try WorkoutFIT.export(archive: local, id: interrupted.id, to: fitURL)
  check(summary.timerSeconds == liveTimer && exported.timerSeconds == liveTimer, "live timer, history summary and FIT agree without counting downtime")
  check(summary.elapsedSeconds == end && exported.elapsedSeconds == end, "elapsed time retains the interruption gap")
  check(try Data(contentsOf: fitURL).count > 100, "actual FIT export writes the recovered lifecycle")
}
print("Recording option regressions passed: \(assertions) assertions")
