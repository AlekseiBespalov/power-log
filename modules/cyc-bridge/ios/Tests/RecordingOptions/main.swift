import Foundation

var assertions = 0
func check(_ value: Bool, _ message: String) {
  assertions += 1
  if !value { fatalError(message) }
}
func rejects(_ message: String, _ body: () throws -> Void) {
  do {
    try body()
    check(false, message)
  } catch { check(true, message) }
}
for (version, available, watchAllowed, phoneHealth) in [
  (16, true, false, false), (17, true, true, false), (25, true, true, false), (26, true, true, true),
  (16, false, false, false), (17, false, false, false), (26, false, false, false),
] {
  let capabilities = WorkoutCapabilities(iOSMajorVersion: version, healthAvailable: available)
  check(capabilities.phoneWorkout, "iOS \(version) always supports phone recording")
  check(capabilities.watchWorkout == watchAllowed, "Watch ownership requires iOS 17 and HealthKit")
  check(capabilities.watchHealth == watchAllowed, "Watch Health follows Watch ownership")
  check(capabilities.phoneHealth == phoneHealth, "phone Health requires iOS 26 and HealthKit")
  check(capabilities.healthProvider == (available ? "appleHealth" : nil), "provider describes HealthKit availability")
  check(capabilities.gps && !capabilities.foregroundOnly, "phone location and background capture stay available")
}
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
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-recording-options-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let start = Date(timeIntervalSince1970: 1_780_000_000)
func timing(_ elapsed: Double, timer: Double? = nil, utc: Date? = nil) throws -> WorkoutOwnerTiming {
  try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(utc ?? start.addingTimeInterval(elapsed)),
    elapsedSeconds: elapsed, timerSeconds: timer ?? elapsed)
}
func archive(_ name: String) throws -> WorkoutArchive {
  try WorkoutArchive(
    rootURL: root.appendingPathComponent(name),
    store: PowerLogStore.shared(databaseURL: root.appendingPathComponent(name + ".sqlite3")))
}
let local = try archive("local")
let control = WorkoutControlJournal(store: local.store)
for version in [16, 17, 25, 26] {
  let store = try archive("ios-\(version)")
  for watch in [false, true] {
    let options = WorkoutRecordingPolicy.effectiveOptions(
      capabilities: WorkoutCapabilities(iOSMajorVersion: version, healthAvailable: true),
      indoor: false, useWatch: watch)
    let ride = try store.create(
      startedAt: start, indoor: false, watchEnabled: options.useWatch,
      saveToHealth: options.saveToHealth, recordGPS: options.recordGPS)
    let frozen = try archive("ios-\(version)").metadata(id: ride.id)
    check(frozen.watchEnabled == (watch && version >= 17), "archive freezes the supported owner")
    check(frozen.saveToHealth == (version >= 26 || watch && version >= 17), "archive freezes owner-specific Health")
    check(frozen.recordGPS, "local and Watch recording retain requested GPS below iOS 26")
    check(
      frozen.healthKitState == (frozen.saveToHealth ? "notSaved" : "notRequested"),
      "phone rides below 26 never become pending Health saves")
  }
}
func telemetry(_ id: String, _ seconds: Double) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(seconds),
    elapsedSeconds: seconds,
    payload: ["humanPowerW": .number(192.125), "cadenceRpm": .number(86.75), "motorInputPowerW": .number(311.25)])
}

for indoor in [false, true] {
  for saves in [false, true] {
    for gps in [false, true] {
      for watch in [false, true] {
        let m = try local.create(
          startedAt: start, indoor: indoor, watchEnabled: watch, saveToHealth: saves, recordGPS: gps)
        let reopened = try WorkoutArchive(rootURL: local.rootURL, store: local.store).metadata(id: m.id)
        check(reopened.saveToHealth == saves && reopened.recordGPS == gps, "flags freeze across archive reconstruction")
        check(
          reopened.dictionary["saveToHealth"] as? Bool == saves && reopened.dictionary["recordGPS"] as? Bool == gps,
          "bridge exposes frozen options")
        let command = try WorkoutCommand(
          workoutID: m.id, origin: "phone", originSequence: 1, action: "start", requestedAt: start,
          options: ["indoor": .bool(indoor), "saveToHealth": .bool(saves), "recordGPS": .bool(gps)])
        check(try WorkoutCommand.decode(command.packet) == command, "start command preserves options")
        let event = try telemetry(m.id, 1)
        try local.append(event)
        let insertion = WorkoutHealthInsertionJournal(archive: local)
        var effects = 0
        var completed = false
        var failed = false
        insertion.insert(
          [event], authorized: { _ in true },
          operation: { _, done in
            effects += 1
            done(.success(()))
          },
          completion: { result in
            completed = true
            if case .failure = result { failed = true }
          })
        check(
          completed && effects == (saves ? 1 : 0) && failed == !saves,
          "actual insertion boundary never runs an opted-out Health effect")
        if !saves {
          check(
            try insertion.pending(id: m.id).isEmpty && insertion.outcome(id: m.id) == "notRequested",
            "skipped insertion cannot accumulate retry work")
          check(try !insertion.needsRepair(event), "skipped originals never request repair")
          rejects("repair cannot reopen skipped saving") { try insertion.beginRepair(id: m.id) }
          rejects("archive outcome cannot later become saved") {
            _ = try local.update(id: m.id, healthKitState: "saved")
          }
          rejects("owner cannot report a saved skipped workout") {
            _ = try control.observe(
              workoutID: m.id, owner: watch ? "watch" : "phone", phase: "running", at: start, health: "saved")
          }
        }
      }
    }
  }
}
let malformedID = UUID().uuidString.lowercased()
rejects("malformed new option does not silently become legacy true") {
  _ = try WorkoutCommand(
    workoutID: malformedID, origin: "phone", originSequence: 1, action: "start", requestedAt: start,
    options: ["saveToHealth": .string("false")])
}

// Real local owner commands commit event + receipt atomically, including retry after a storage failure.
let ride = try local.create(startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
func apply(_ action: String, seconds: Double, failCommit: Bool = false) throws -> WorkoutCommand {
  let command = try control.admitLocal(
    workoutID: ride.id, origin: "phone", action: action, at: start.addingTimeInterval(seconds))
  check(try control.prepare(command).execute, "local action is admitted once")
  if failCommit {
    local.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Injected commit failure") }
    rejects("local effect rolls back without a receipt") {
      _ = try WorkoutLocalOwner.observe(
        id: ride.id, phase: "paused", at: start.addingTimeInterval(seconds), elapsed: seconds,
        command: command, cutoff: nil, discarded: false, archive: local, control: control)
    }
    local.store.beforeCommitForTesting = nil
    check(
      try !local.hasEvent(id: ride.id, eventID: command.id) && control.result(id: command.id)?.outcome == "executing",
      "retry retains intent and no partial original")
  }
  let phase = action == "pause" ? "paused" : action == "stop" ? "completed" : "running"
  let date = start.addingTimeInterval(seconds)
  let snapshot = try WorkoutLocalOwner.observe(
    id: ride.id, phase: phase, at: date, elapsed: seconds,
    command: command, cutoff: action == "stop" ? date : nil, discarded: false, archive: local, control: control,
    timing: try timing(seconds, timer: action == "stop" ? 4 : seconds))
  check(
    snapshot.healthOutcome == "notRequested" && snapshot.healthWorkoutID == nil, "local owner needs no Health identity")
  check(
    try local.hasEvent(id: ride.id, eventID: command.id) && control.result(id: command.id)?.outcome == "applied",
    "original and receipt share the durable effect")
  return command
}
_ = try apply("start", seconds: 0)
let beforePause = try telemetry(ride.id, 1)
let paused = try telemetry(ride.id, 3)
let afterResume = try telemetry(ride.id, 5)
try local.append(beforePause)
_ = try apply("pause", seconds: 2, failCommit: true)
try local.append(paused)
_ = try apply("resume", seconds: 4)
try local.append(afterResume)
_ = try apply("lap", seconds: 5.5)
let stop = try apply("stop", seconds: 6)
try local.update(
  id: ride.id, healthKitState: "notRequested", stopElapsedSeconds: 6, ownerTiming: try timing(6, timer: 4))
try local.finish(id: ride.id, endedAt: start.addingTimeInterval(6))
check(try local.hasEvent(id: ride.id, eventID: paused.eventId), "paused CYC originals are retained")
check(
  try WorkoutHealthEligibility.permits(beforePause, archive: local)
    && !WorkoutHealthEligibility.permits(paused, archive: local)
    && WorkoutHealthEligibility.permits(afterResume, archive: local),
  "canonical lifecycle retains active and paused intervals")
let phoneSeal = try WorkoutPhoneSealRepair.seal(
  id: ride.id, archive: local, transfer: WorkoutTransferJournal(archive: local), control: control)
check(
  phoneSeal.resolved && !phoneSeal.partial && phoneSeal.requirements["healthSave"] == "notRequested",
  "phone local Save produces a complete skipped-Health seal")
check(
  try local.metadata(id: ride.id).finalizationState == "complete" && !local.store.isWorkoutDeleted(id: ride.id),
  "Save retains verified originals")
let reopenedControl = WorkoutControlJournal(store: local.store)
check(try reopenedControl.prepare(stop).result.outcome == "applied", "stop retry after restart cannot rerun effects")
var savedAgain = try reopenedControl.snapshot(workoutID: ride.id)!
savedAgain.ownerRevision += 1
savedAgain.healthOutcome = "saved"
rejects("delayed owner saved transition is fenced") { _ = try reopenedControl.accept(snapshot: savedAgain) }

// Late originals and failed writes are rediscovered by ID after the selected ride has changed.
let late = try telemetry(ride.id, 5.75)
try local.append(late)
check(
  try WorkoutPhoneSealRepair.pendingLocal(archive: local).contains(ride.id),
  "later original invalidates and rediscovers the old local seal")
let another = try local.create(
  startedAt: start.addingTimeInterval(100), indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
try local.store.transaction { db in try db.put(namespace: "phone-current", key: "workout", value: Data(another.id.utf8))
}
local.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Injected seal failure") }
rejects("failed seal stays eligible") {
  _ = try WorkoutPhoneSealRepair.seal(
    id: ride.id, archive: local, transfer: WorkoutTransferJournal(archive: local), control: control)
}
local.store.beforeCommitForTesting = nil
check(
  try WorkoutPhoneSealRepair.pendingLocal(archive: local).contains(ride.id),
  "storage failure preserves durable rediscovery")
_ = try WorkoutPhoneSealRepair.seal(
  id: ride.id, archive: local, transfer: WorkoutTransferJournal(archive: local), control: control)
check(
  try !WorkoutPhoneSealRepair.pendingLocal(archive: local).contains(ride.id),
  "successful exact verification removes only settled work")
check(
  try local.store.read { try $0.get(namespace: "phone-current", key: "workout") } == Data(another.id.utf8),
  "historical seal repair never selects another ride")
check(
  try WorkoutTransferJournal(archive: local).verify(id: ride.id) && local.hasEvent(id: ride.id, eventID: late.eventId),
  "late original is retained by the repaired strict seal")

// A persisted stop cutoff with a lost owner commit is repaired before any final seal can verify.
let interruptedStop = try local.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
_ = try control.observe(
  workoutID: interruptedStop.id, owner: "phone", phase: "running", at: start, health: "notRequested")
let interruptedCommand = try control.admitLocal(
  workoutID: interruptedStop.id, origin: "phone", action: "stop", at: start.addingTimeInterval(9))
_ = try control.prepare(interruptedCommand)
try local.update(id: interruptedStop.id, stopElapsedSeconds: 9, ownerTiming: try timing(9))
try local.finish(id: interruptedStop.id, endedAt: start.addingTimeInterval(9), finalPhase: "finishing")
local.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Injected owner stop commit failure") }
rejects("stop owner and original roll back together") {
  _ = try WorkoutLocalOwner.observe(
    id: interruptedStop.id, phase: "completed", at: start.addingTimeInterval(9), elapsed: 9,
    command: interruptedCommand, cutoff: start.addingTimeInterval(9), discarded: false, archive: local,
    control: control,
    timing: try timing(9)
  )
}
local.store.beforeCommitForTesting = nil
check(
  try control.active(workoutID: interruptedStop.id)?.id == interruptedCommand.id
    && control.snapshot(workoutID: interruptedStop.id)?.phase == "running",
  "failed owner commit remains explicitly executing")
let beforeStaleRestore = try local.metadata(id: interruptedStop.id).eventCount
let staleRestore = try WorkoutLocalOwner.restore(
  id: interruptedStop.id, epoch: "previous-process", checkpoint: try timing(5),
  needsInterruption: true, archive: local)
check(
  staleRestore.cutoff == start.addingTimeInterval(9) && staleRestore.timing.elapsedSeconds == 9,
  "committed archive stop outranks the old running phone checkpoint")
check(
  try local.metadata(id: interruptedStop.id).eventCount == beforeStaleRestore,
  "stale restore does not add a pause after a retained stop")
let repairedStop = try WorkoutPhoneSealRepair.seal(
  id: interruptedStop.id, archive: local, transfer: WorkoutTransferJournal(archive: local), control: control)
check(
  try repairedStop.resolved && control.active(workoutID: interruptedStop.id) == nil,
  "by-ID local repair commits stop before final seal")
check(
  try control.result(id: interruptedCommand.id)?.outcome == "applied"
    && control.snapshot(workoutID: interruptedStop.id)?.stopCutoff
      == WorkoutCoding.timestamp(start.addingTimeInterval(9)),
  "original stop identity and cutoff survive repair")
check(
  try local.hasEvent(id: interruptedStop.id, eventID: interruptedCommand.id)
    && WorkoutTransferJournal(archive: local).verify(id: interruptedStop.id),
  "repaired stop original participates in exact final verification")
let afterOwnerRestore = try WorkoutLocalOwner.restore(
  id: interruptedStop.id, epoch: "previous-process", checkpoint: try timing(5),
  needsInterruption: true, archive: local)
check(
  afterOwnerRestore.cutoff == staleRestore.cutoff
    && afterOwnerRestore.timing.elapsedSeconds == staleRestore.timing.elapsedSeconds,
  "restore after owner receipt and seal retains the same immutable cutoff")

// The local Resume effect cannot outrun its phone checkpoint: a projection write
// failure rolls back the owner, original and checkpoint as one production action.
let resumeFailure = try local.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
_ = try control.observe(
  workoutID: resumeFailure.id, owner: "phone", phase: "paused", at: start.addingTimeInterval(2), health: "notRequested")
try local.append(
  WorkoutEvent(
    workoutId: resumeFailure.id, kind: "lifecycle", source: "phone", timestamp: start.addingTimeInterval(2),
    elapsedSeconds: 2, payload: ["action": .string("pause")]))
let pausedCheckpoint = Data("paused timer=2 elapsed=2".utf8)
try local.store.transaction { try $0.put(namespace: "phone-current", key: "workout", value: pausedCheckpoint) }
let resumeAttempt = try control.admitLocal(
  workoutID: resumeFailure.id, origin: "phone", action: "resume", at: start.addingTimeInterval(10))
_ = try control.prepare(resumeAttempt)
rejects("failed Resume checkpoint does not publish a running owner") {
  _ = try WorkoutLocalOwner.observe(
    id: resumeFailure.id, phase: "running", at: start.addingTimeInterval(10), elapsed: 10,
    command: resumeAttempt, cutoff: nil, discarded: false, archive: local, control: control,
    checkpoint: {
      try local.store.transaction {
        try $0.put(namespace: "phone-current", key: "workout", value: Data("running".utf8))
      }
      throw WorkoutDataError.invalid("Injected phone checkpoint failure")
    })
}
check(
  try control.snapshot(workoutID: resumeFailure.id)?.phase == "paused"
    && !local.hasEvent(id: resumeFailure.id, eventID: resumeAttempt.id),
  "failed Resume retains the previous paused owner and lifecycle")
check(
  try local.store.read { try $0.get(namespace: "phone-current", key: "workout") } == pausedCheckpoint,
  "failed Resume retains the frozen phone timer checkpoint")
check(
  try control.active(workoutID: resumeFailure.id)?.id == resumeAttempt.id,
  "failed Resume remains explicitly recoverable")
_ = try control.observe(
  workoutID: resumeFailure.id, owner: "phone", phase: "paused", at: start.addingTimeInterval(100),
  health: "notRequested",
  command: resumeAttempt, failure: "Resume did not commit")
let stopAfterResumeFailure = try control.admitLocal(
  workoutID: resumeFailure.id, origin: "phone", action: "stop", at: start.addingTimeInterval(100))
_ = try control.prepare(stopAfterResumeFailure)
_ = try WorkoutLocalOwner.observe(
  id: resumeFailure.id, phase: "completed", at: start.addingTimeInterval(100), elapsed: 100,
  command: stopAfterResumeFailure, cutoff: start.addingTimeInterval(100), discarded: false, archive: local,
  control: control, timing: try timing(100, timer: 2))
try local.finish(id: resumeFailure.id, endedAt: start.addingTimeInterval(100))
check(
  try WorkoutAnalysis.summarize(archive: local, id: resumeFailure.id).timerSeconds == 2,
  "recovery and Finish after failed Resume retain only the original active time")

// Every local action uses the owner transaction's checkpoint boundary. Inject a
// failure after owner/original writes, then retry, including Start and terminal Save/Discard.
for discardEnd in [false, true] {
  let atomic = try local.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
  try local.update(id: atomic.id, phase: "preparing")
  var previousCheckpoint = Data("preparing".utf8)
  try local.store.transaction { try $0.put(namespace: "phone-current", key: "workout", value: previousCheckpoint) }
  for (index, action) in ["start", "pause", "resume", "lap", discardEnd ? "discard" : "stop"].enumerated() {
    let seconds = Double(index)
    let at = start.addingTimeInterval(seconds)
    let nextPhase = action == "pause" ? "paused" : ["stop", "discard"].contains(action) ? "completed" : "running"
    let command = try control.admitLocal(
      workoutID: atomic.id, origin: "phone", action: action, at: at,
      options: [
        "cutoffElapsedSeconds": .number(seconds), "timerSeconds": .number(max(0, seconds - 1)),
        "cutoffUTC": .string(WorkoutCoding.timestamp(at)),
      ])
    _ = try control.prepare(command)
    let previousOwner = try control.snapshot(workoutID: atomic.id)
    let previousCount = try local.metadata(id: atomic.id).eventCount
    let checkpoint = Data("\(nextPhase):\(seconds)".utf8)
    func applyAtomic(_ fail: Bool) throws {
      _ = try WorkoutLocalOwner.observe(
        id: atomic.id, phase: nextPhase, at: at, elapsed: seconds, command: command,
        cutoff: command.endsWorkout ? at : nil, discarded: action == "discard", archive: local, control: control,
        timing: try timing(seconds, timer: max(0, seconds - 1)),
        checkpoint: {
          if action == "start" {
            _ = try WorkoutPhoneStartProjection.confirm(
              archive: local, id: atomic.id, startedAt: at, now: at, uptime: 20, epoch: "local-start")
          }
          try local.update(id: atomic.id, phase: nextPhase)
          if command.endsWorkout {
            try local.update(
              id: atomic.id, stopElapsedSeconds: seconds, ownerTiming: try timing(seconds, timer: max(0, seconds - 1)))
            try local.finish(id: atomic.id, endedAt: at)
          }
          try local.store.transaction { try $0.put(namespace: "phone-current", key: "workout", value: checkpoint) }
          if fail { throw WorkoutDataError.invalid("Injected \(action) checkpoint commit failure") }
        })
    }
    rejects("\(action) checkpoint failure is atomic") { try applyAtomic(true) }
    check(
      try control.snapshot(workoutID: atomic.id) == previousOwner
        && local.metadata(id: atomic.id).eventCount == previousCount,
      "\(action) owner and original roll back before checkpoint confirmation")
    check(
      try local.store.read { try $0.get(namespace: "phone-current", key: "workout") } == previousCheckpoint,
      "\(action) cannot replace its prior checkpoint on failure")
    if command.endsWorkout {
      let restored = try WorkoutLocalOwner.restore(
        id: atomic.id, epoch: "old-epoch", checkpoint: try timing(1),
        needsInterruption: true, archive: local, pendingCommand: command)
      check(
        restored.cutoff == at && restored.timing.elapsedSeconds == seconds
          && restored.timing.timerSeconds == seconds - 1,
        "pending terminal intent restores exact cutoff and timer before any later retry")
      check(
        try local.metadata(id: atomic.id).eventCount == previousCount,
        "pending terminal intent does not add a conflicting interruption")
    }
    try applyAtomic(false)
    check(
      try control.result(id: command.id)?.outcome == "applied" && local.hasEvent(id: atomic.id, eventID: command.id),
      "\(action) original and receipt commit on retry")
    check(
      try local.store.read { try $0.get(namespace: "phone-current", key: "workout") } == checkpoint,
      "\(action) publishes its matching checkpoint")
    previousCheckpoint = checkpoint
  }
  check(
    try WorkoutAnalysis.summarize(archive: local, id: atomic.id).timerSeconds == 3,
    "complete atomic local lifecycle yields the same active FIT duration")
}

// Discard has a different command/outcome and remains the explicit deletion entry point.
let discarded = try local.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
_ = try control.observe(workoutID: discarded.id, owner: "phone", phase: "running", at: start, health: "notRequested")
let discard = try control.admitLocal(
  workoutID: discarded.id, origin: "phone", action: "discard", at: start.addingTimeInterval(4))
_ = try control.prepare(discard)
_ = try WorkoutLocalOwner.observe(
  id: discarded.id, phase: "completed", at: start.addingTimeInterval(4), elapsed: 4,
  command: discard, cutoff: start.addingTimeInterval(4), discarded: true, archive: local, control: control,
  timing: try timing(4))
check(
  try control.snapshot(workoutID: discarded.id)?.healthOutcome == "discarded",
  "Discard remains distinct from skipped Health Save")
_ = try local.store.markWorkoutDeleted(id: discarded.id)
check(
  try local.store.isWorkoutDeleted(id: discarded.id) && !local.store.isWorkoutDeleted(id: ride.id),
  "only explicit Discard deletes its collection")

// Watch originals establish intent before status; Data/file paths retain the same frozen intent and exact seal.
let watch = try WatchWorkoutJournal(rootURL: root.appendingPathComponent("watch"))
let watchID = UUID().uuidString.lowercased()
let initial: [String: Any] = [
  "workoutId": watchID, "startedAt": WorkoutCoding.timestamp(start), "indoor": false,
  "saveToHealth": false, "recordGPS": false, "healthKitState": "notRequested", "phase": "running",
]
try watch.create(id: watchID, metadata: initial)
_ = try watch.control.observe(workoutID: watchID, owner: "watch", phase: "running", at: start, health: "notRequested")
let sensor = try WorkoutEvent(
  workoutId: watchID, kind: "health", source: "watch", timestamp: start.addingTimeInterval(1), elapsedSeconds: 1,
  payload: ["heartRateBpm": .number(141.25), "representation": .string("rawQuantity")])
try watch.archive.append(sensor)
let sender = WorkoutChunkSender(archive: watch.archive)
let chunk = try sender.prepare(id: watchID)!
let wire = try WorkoutChunkWire.encode(
  chunk: chunk, startedAt: initial["startedAt"] as! String, indoor: false, saveToHealth: false, recordGPS: false)!
let decoded = try WorkoutChunkWire.decode(wire)
check(
  decoded.metadata["saveToHealth"] as? Bool == false && decoded.metadata["recordGPS"] as? Bool == false,
  "full wire carries opt-out before owner status")
let destination = try archive("destination")
let receiver = WorkoutTransferJournal(archive: destination)
let inbox = try WorkoutChunkInbox(root: root.appendingPathComponent("inbox"), store: destination.store)
check(try inbox.stage(data: decoded.chunk.data, metadata: decoded.metadata), "opted-out original uses normal inbox")
let item = try inbox.claim()!
check(
  try receiver.receiveWatch(
    WorkoutChunk(manifest: chunk.manifest, data: Data(contentsOf: inbox.url(item))),
    startedAt: initial["startedAt"] as! String,
    indoor: false, saveToHealth: false, recordGPS: false), "file worker adopts the original frozen options")
try inbox.finish(item, success: true)
let received = try destination.metadata(id: watchID)
check(
  !received.saveToHealth && !received.recordGPS && received.endedAt == nil,
  "chunk adoption creates an active local collection without an end")
rejects("duplicate with changed options cannot silently enable Health") {
  _ = try receiver.receiveWatch(
    chunk, startedAt: initial["startedAt"] as! String, indoor: false, saveToHealth: true, recordGPS: false)
}
let owner = try watch.control.observe(
  workoutID: watchID, owner: "watch", phase: "completed", at: start.addingTimeInterval(2), health: "notRequested",
  cutoff: start.addingTimeInterval(2), timing: try timing(2))
let source = try watch.transfer.source(id: watchID, producer: "watch")
let seal = WorkoutSeal(
  workoutID: watchID, sealRevision: 1, collectionRevision: try watch.archive.revision(id: watchID),
  ownerRevision: owner.ownerRevision,
  stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(2)), healthOutcome: "notRequested",
  requirements: [
    "healthSave": "notRequested", "cycInsertion": "notRequested", "healthExtraction": "notRequested",
    "localSensors": "sealed", "ownerEnded": "sealed", "gps": "notRequested",
  ],
  sources: [source], stopElapsedSeconds: 2, timerSeconds: 2, saveToHealth: false, recordGPS: false)
check(try receiver.accept(seal: seal), "skipped Health and GPS requirements are accepted")
check(try receiver.verify(id: watchID), "normal exact-source final verification succeeds with skipped Health")
check(try destination.pageEvents(id: watchID).first?.event == sensor, "sensor originals retain exact data and identity")
var changed = initial
changed["saveToHealth"] = true
changed["healthKitState"] = "saved"
rejects("Watch journal rejects intent mutation across recovery") { try watch.save(id: watchID, metadata: changed) }
let snapshot = WorkoutOwnerSnapshot(
  workoutID: watchID, owner: "watch", ownerRevision: 3, effectiveAt: WorkoutCoding.timestamp(start), phase: "running",
  healthOutcome: "notRequested")
rejects("owner adoption cannot overwrite the chunk's frozen intent") {
  _ = try WorkoutOwnerAdoption.accept(
    snapshot, archive: destination, control: WorkoutControlJournal(store: destination.store), startedAt: start,
    indoor: false, saveToHealth: true, recordGPS: true)
}
// Boundary-spanning series parents remain query candidates; exact points alone belong inside the ride.
let window = WorkoutSensorWindow(start: start, cutoff: start.addingTimeInterval(10))
check(
  !window.contains(start: start.addingTimeInterval(-2), end: start.addingTimeInterval(12)),
  "parent aggregate cannot substitute for in-window points")
let pointIntervals: [(Double, Double)] = [(-2, -1), (-1, 0), (0, 1), (9, 10), (10, 11), (11, 12)]
let retainedPoints = pointIntervals.filter {
  window.contains(start: start.addingTimeInterval($0.0), end: start.addingTimeInterval($0.1))
}
check(
  retainedPoints.count == 2 && retainedPoints[0].0 == 0 && retainedPoints[1].1 == 10,
  "only exact contained series points survive")
let seriesEvents = try retainedPoints.map { point in
  try WorkoutEvent(
    workoutId: watchID, kind: "health", source: "watch", timestamp: start.addingTimeInterval(point.1),
    elapsedSeconds: point.1,
    payload: [
      "representation": .string("rawSeries"), "heartRateBpm": .number(140),
      "sampleStart": .string(WorkoutCoding.timestamp(start.addingTimeInterval(point.0))),
      "sampleEnd": .string(WorkoutCoding.timestamp(start.addingTimeInterval(point.1))),
    ], eventId: WorkoutStableIdentity.uuid("boundary-series:" + String(point.0)))
}
try watch.commitHealthPage(id: watchID, events: seriesEvents, progressKey: "local-final-test", anchor: Data([1]))
let afterPoints = try watch.archive.metadata(id: watchID).eventCount
try watch.commitHealthPage(id: watchID, events: seriesEvents, progressKey: "local-final-test", anchor: Data([1]))
check(
  try watch.archive.metadata(id: watchID).eventCount == afterPoints,
  "replayed boundary series does not duplicate originals")
var pendingRequirements = seal.requirements
pendingRequirements["ownerEnded"] = "pending"
let early = WorkoutSeal(
  workoutID: watchID, sealRevision: 2, collectionRevision: seal.collectionRevision, ownerRevision: seal.ownerRevision,
  stopCutoff: seal.stopCutoff, healthOutcome: "notRequested", requirements: pendingRequirements, sources: seal.sources,
  stopElapsedSeconds: seal.stopElapsedSeconds, timerSeconds: seal.timerSeconds, saveToHealth: false, recordGPS: false)
check(!early.resolved, "local extraction alone cannot seal before native owner end")
_ = try receiver.accept(seal: early)
check(try !receiver.verify(id: watchID), "strict final verification waits for owner-ended requirement")

for wallOffset in [-300.0, 300.0] {
  for resumeAfterRecovery in [false, true] {
    let interrupted = try local.create(
      startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
    let initialCommand = try control.admitLocal(workoutID: interrupted.id, origin: "phone", action: "start", at: start)
    _ = try control.prepare(initialCommand)
    _ = try WorkoutLocalOwner.observe(
      id: interrupted.id, phase: "running", at: start, elapsed: 0,
      command: initialCommand, cutoff: nil, discarded: false, archive: local, control: control)
    for (action, seconds) in [("pause", 2.0), ("resume", 4.0)] {
      try local.append(
        WorkoutEvent(
          workoutId: interrupted.id, kind: "lifecycle", source: "phone",
          timestamp: start.addingTimeInterval(seconds), elapsedSeconds: seconds, payload: ["action": .string(action)]))
    }
    let checkpoint = try timing(10, timer: 8, utc: start.addingTimeInterval(wallOffset))
    try local.store.transaction { db in
      try db.put(
        namespace: "phone-current", key: "workout",
        value: JSONSerialization.data(withJSONObject: [
          "checkpointUTC": checkpoint.timestamp, "elapsedSeconds": checkpoint.elapsedSeconds,
          "timerSeconds": checkpoint.timerSeconds,
        ]))
    }
    let lastUTC = start.addingTimeInterval(wallOffset + 2)
    let committed = try WorkoutEvent(
      workoutId: interrupted.id, kind: "telemetry", source: "cyc", timestamp: lastUTC,
      elapsedSeconds: 12,
      payload: ["humanPowerW": .number(100), "cadenceRpm": .number(70), "clockEpoch": .string("old-process")])
    try local.append(committed)
    local.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("injected recovery failure") }
    rejects("interruption transaction rolls back") {
      _ = try WorkoutLocalOwner.restore(
        id: interrupted.id, epoch: "old-process", checkpoint: checkpoint,
        needsInterruption: true, archive: local)
    }
    local.store.beforeCommitForTesting = nil
    let reopened = try archive("local")
    let persisted =
      try JSONSerialization.jsonObject(
        with: reopened.store.read {
          try $0.get(namespace: "phone-current", key: "workout")!
        }) as! [String: Any]
    let reopenedCheckpoint = try WorkoutOwnerTiming(
      timestamp: persisted["checkpointUTC"] as! String,
      elapsedSeconds: persisted["elapsedSeconds"] as! Double, timerSeconds: persisted["timerSeconds"] as! Double)
    let recovered = try WorkoutLocalOwner.restore(
      id: interrupted.id, epoch: "old-process", checkpoint: reopenedCheckpoint,
      needsInterruption: true, archive: reopened)
    check(
      recovered.timing.elapsedSeconds == 12 && recovered.timing.timerSeconds == 10,
      "committed capture advances elapsed and active checkpoint without downtime")
    check(
      recovered.timing.timestamp == committed.timestamp, "recovery retains original cutoff UTC through clock shifts")
    let count = try local.metadata(id: interrupted.id).eventCount
    _ = try WorkoutLocalOwner.restore(
      id: interrupted.id, epoch: "old-process", checkpoint: checkpoint,
      needsInterruption: true, archive: local)
    check(try local.metadata(id: interrupted.id).eventCount == count, "repeated interruption is idempotent")
    let originalAnchor = WorkoutTimelineAnchor(
      epoch: "old-process", monotonicOrigin: 100, startedAt: WorkoutCoding.timestamp(start))
    let resumed = originalAnchor.resuming(timing: recovered.timing, epoch: "new-process", uptime: 1)
    let mapping = try resumed.map(epoch: "new-process", acquisition: 1.5, timestamp: start.addingTimeInterval(900))
    check(mapping.elapsed == 12.5 && mapping.eligible, "new capture cannot precede committed originals after reboot")
    var endTiming = recovered.timing
    if resumeAfterRecovery {
      let resumeAt = start.addingTimeInterval(900)
      let command = try control.admitLocal(workoutID: interrupted.id, origin: "phone", action: "resume", at: resumeAt)
      _ = try control.prepare(command)
      _ = try WorkoutLocalOwner.observe(
        id: interrupted.id, phase: "running", at: resumeAt, elapsed: 12,
        command: command, cutoff: nil, discarded: false, archive: local, control: control)
      endTiming = try timing(22, timer: 20, utc: resumeAt.addingTimeInterval(10))
    }
    let cutoff = try WorkoutCoding.date(endTiming.timestamp)
    let finish = try control.admitLocal(
      workoutID: interrupted.id, origin: "phone", action: "stop",
      at: start.addingTimeInterval(1000),
      options: [
        "cutoffUTC": .string(endTiming.timestamp),
        "cutoffElapsedSeconds": .number(endTiming.elapsedSeconds), "timerSeconds": .number(endTiming.timerSeconds),
      ])
    check(
      try WorkoutCommand.decode(finish.packet) == finish,
      "cutoff tuple survives command transfer independently of request UTC")
    _ = try control.prepare(finish)
    let pending = try WorkoutLocalOwner.restore(
      id: interrupted.id, epoch: "new-process", checkpoint: checkpoint,
      needsInterruption: true, archive: local, pendingCommand: finish)
    check(
      pending.timing == endTiming && pending.cutoff == cutoff, "delayed Finish keeps cutoff separate from request UTC")
    _ = try WorkoutLocalOwner.observe(
      id: interrupted.id, phase: "completed", at: cutoff, elapsed: endTiming.elapsedSeconds,
      command: finish, cutoff: cutoff, discarded: false, archive: local, control: control, timing: endTiming)
    try local.update(id: interrupted.id, stopElapsedSeconds: endTiming.elapsedSeconds, ownerTiming: endTiming)
    try local.finish(id: interrupted.id, endedAt: cutoff)
    let terminal = try WorkoutLocalOwner.restore(
      id: interrupted.id, epoch: "new-process", checkpoint: checkpoint,
      needsInterruption: true, archive: local, pendingCommand: initialCommand)
    check(terminal.timing == endTiming, "committed stop outranks stale checkpoint")
    _ = try WorkoutPhoneSealRepair.seal(
      id: interrupted.id, archive: local,
      transfer: WorkoutTransferJournal(archive: local), control: control)
    let summary = try WorkoutAnalysis.summarize(archive: local, id: interrupted.id)
    check(
      summary.elapsedSeconds == endTiming.elapsedSeconds && summary.timerSeconds == endTiming.timerSeconds,
      "summary elapsed and active time exclude restart downtime")
  }
}
let uncertainRide = try local.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false)
try local.append(
  WorkoutEvent(
    workoutId: uncertainRide.id, kind: "lifecycle", source: "phone", timestamp: start,
    elapsedSeconds: 0, payload: ["action": .string("start")]))
let uncertainPause = try control.admitLocal(
  workoutID: uncertainRide.id, origin: "phone", action: "pause",
  at: start.addingTimeInterval(10))
_ = try control.prepare(uncertainPause)
try local.append(telemetry(uncertainRide.id, 12))
let uncertainCheckpoint = try timing(8)
let uncertainRecovery = try WorkoutLocalOwner.restore(
  id: uncertainRide.id, epoch: "old-health-process",
  checkpoint: uncertainCheckpoint, needsInterruption: true, archive: local, pendingCommand: uncertainPause)
check(
  uncertainRecovery.timing.elapsedSeconds == 12 && uncertainRecovery.timing.timerSeconds == 8,
  "an unresolved native pause cannot add uncertain active time beyond the checkpoint")
_ = try WorkoutRecoveredOwnerCommand.reconcile(
  workoutID: uncertainRide.id, owner: "phone", nativePhase: "paused",
  observedAt: start.addingTimeInterval(1000), nativeEventDates: ["pause": start.addingTimeInterval(10)],
  observedLapIDs: [],
  cutoff: nil, health: "pending", healthID: nil, archive: local, control: control, timing: uncertainRecovery.timing)
check(
  try WorkoutAnalysis.summarize(archive: local, id: uncertainRide.id).timerSeconds == 8,
  "lifecycle analytics exclude the same uncertain active interval as the retained owner timer")
for (action, elapsed, utc) in [("resume", 12.0, 1000.0), ("stop", 14.0, 1002.0)] {
  try local.append(
    WorkoutEvent(
      workoutId: uncertainRide.id, kind: "lifecycle", source: "phone",
      timestamp: start.addingTimeInterval(utc), elapsedSeconds: elapsed, payload: ["action": .string(action)]))
}
try local.update(
  id: uncertainRide.id, stopElapsedSeconds: 14,
  ownerTiming: try timing(14, timer: 10, utc: start.addingTimeInterval(1002)))
try local.finish(id: uncertainRide.id, endedAt: start.addingTimeInterval(1002))
let uncertainSummary = try WorkoutAnalysis.summarize(archive: local, id: uncertainRide.id)
check(
  uncertainSummary.elapsedSeconds == 14 && uncertainSummary.timerSeconds == 10,
  "resume adds only measured active time after the recovered boundary")

var incompleteSeal = WorkoutCoding.dictionary(seal)
incompleteSeal.removeValue(forKey: "stopElapsedSeconds")
rejects("a terminal seal requires retained elapsed") {
  _ = try JSONDecoder().decode(WorkoutSeal.self, from: JSONSerialization.data(withJSONObject: incompleteSeal))
}
incompleteSeal = WorkoutCoding.dictionary(seal)
incompleteSeal.removeValue(forKey: "timerSeconds")
rejects("a terminal seal requires retained active time") {
  _ = try JSONDecoder().decode(WorkoutSeal.self, from: JSONSerialization.data(withJSONObject: incompleteSeal))
}
print("Recording option regressions passed: \(assertions) assertions")
