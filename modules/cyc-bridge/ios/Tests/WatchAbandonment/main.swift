import Foundation

var checks = 0
func check(_ value: Bool, _ message: String) {
  checks += 1
  precondition(value, message)
}
func rejects(_ message: String, _ body: () throws -> Void) {
  do {
    try body()
    preconditionFailure(message)
  } catch { checks += 1 }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-abandonment-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let start = Date(timeIntervalSince1970: 1_780_000_000)
for saves in [true, false] {
  for activePhase in ["running", "paused"] {
    let id = UUID().uuidString.lowercased()
    let watch = try WatchWorkoutJournal(rootURL: root.appendingPathComponent(id))
    let initial: [String: Any] = [
      "workoutId": id, "startedAt": WorkoutCoding.timestamp(start), "phase": activePhase,
      "saveToHealth": saves, "recordGPS": false, "healthKitState": saves ? "pending" : "notRequested",
    ]
    try watch.create(id: id, metadata: initial)
    let activeTiming = try WorkoutOwnerTiming(
      timestamp: WorkoutCoding.timestamp(start), elapsedSeconds: 0, timerSeconds: 0)
    let active = try watch.control.observe(
      workoutID: id, owner: "watch", phase: activePhase, at: start,
      health: saves ? "pending" : "notRequested", timing: activeTiming)
    let cutoff = try WorkoutOwnerTiming(
      timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(-100)), elapsedSeconds: 10, timerSeconds: 8)
    let stopped = try watch.retainNativeStop(id: id, timing: cutoff)
    check(try WatchWorkoutJournal.timing(stopped) == cutoff, "unsolicited native stop retains its full cutoff")
    let phone = try WorkoutArchive(rootURL: root.appendingPathComponent("phone-" + id))
    _ = try phone.create(
      id: id, startedAt: start, indoor: true, watchEnabled: true, saveToHealth: saves, recordGPS: false)
    let control = WorkoutControlJournal(store: phone.store)
    _ = try control.accept(snapshot: active)
    var phoneAnchor = WorkoutTimelineAnchor(epoch: "phone", monotonicOrigin: 100, startedAt: activeTiming.timestamp)
    phoneAnchor = try PowerLogCaptureCutoff.owner(phoneAnchor, timing: cutoff)
    let closed = phoneAnchor
    for revision in [active.ownerRevision, active.ownerRevision + 1] {
      var delayed = active
      delayed.ownerRevision = revision
      if WorkoutPhoneTerminalProjection.acceptsStatus(
        delayed, after: nil, stopRequested: phoneAnchor.stopMonotonic != nil)
      {
        phoneAnchor = WorkoutTimelineAnchor(epoch: "phone", monotonicOrigin: 200, startedAt: activeTiming.timestamp)
      }
      check(phoneAnchor.stopMonotonic == closed.stopMonotonic, "duplicate or newer active status cannot reopen Finish")
    }
    var builderRetained = true
    var ending = WorkoutNativeEndingGate()
    var nativeEndCalls = 0
    let endNative: ([String: Any]) -> Void = { _ in
      let published = try! watch.control.snapshot(workoutID: id)!
      check(published.phase == "finishing", "abandonment publishes an authoritative finishing owner")
      check(
        published.timing == cutoff && published.stopCutoff == cutoff.timestamp, "published owner retains cutoff timing")
      check(
        published.healthReason == WorkoutHealthFinalizationGate.clockReason, "published owner retains the clock reason")
      check(
        published.healthOutcome == (saves ? "unavailable" : "notRequested"), "published owner retains Health intent")
      builderRetained = false
      ending.begin(id: id)
      nativeEndCalls += 1
    }
    watch.store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected metadata commit failure") }
    rejects("metadata failure must leave native teardown retryable") {
      _ = try watch.beginClockAbandonment(id: id, endNative: endNative)
    }
    watch.store.beforeCommitForTesting = nil
    check(
      builderRetained && ending.workoutID == nil && nativeEndCalls == 0,
      "failed commit cannot clear builder or occupy ending gate")
    check(try watch.control.snapshot(workoutID: id) == active, "failed metadata commit rolls back owner publication")
    check(
      try watch.beginClockAbandonment(id: id, endNative: endNative), "Finish retries after failed metadata persistence")
    check(
      !builderRetained && ending.workoutID == id && nativeEndCalls == 1,
      "committed abandonment starts native cleanup once")
    rejects("Start waits for confirmed native end") { try ending.requireAvailable() }
    let receiver = WorkoutTransferJournal(archive: phone)
    func seal(_ revision: Int64, _ item: [String: Any], _ owner: WorkoutOwnerSnapshot) throws -> WorkoutSeal {
      WorkoutSeal(
        workoutID: id, sealRevision: revision, collectionRevision: try watch.archive.revision(id: id),
        ownerRevision: owner.ownerRevision, stopCutoff: cutoff.timestamp, healthOutcome: owner.healthOutcome,
        requirements: try WatchWorkoutJournal.requirements(
          item, owner: owner, insertionOutcome: saves ? "unavailable" : "notRequested"),
        sources: [], stopElapsedSeconds: cutoff.elapsedSeconds, timerSeconds: cutoff.timerSeconds,
        saveToHealth: saves, recordGPS: false, healthReason: owner.healthReason)
    }
    let finishing = try watch.control.snapshot(workoutID: id)!
    let pending = try seal(1, watch.metadata(id: id), finishing)
    check(pending.requirements["ownerEnded"] == "pending", "Health completion does not imply native completion")
    for claimedEnd in [nil, "unavailable", "notRequested"] as [String?] {
      var packet = WorkoutCoding.dictionary(pending)
      var requirements = pending.requirements
      requirements["ownerEnded"] = claimedEnd
      packet["requirements"] = requirements
      let invalid = try JSONDecoder().decode(WorkoutSeal.self, from: JSONSerialization.data(withJSONObject: packet))
      rejects("Watch seals require explicit pending or confirmed native completion") {
        _ = try receiver.accept(seal: invalid)
      }
    }
    _ = try receiver.accept(seal: pending)
    check(try !receiver.verify(id: id), "even an empty fully transferred ride cannot verify before native end")
    check(
      try WorkoutOwnerStopPolicy.isUnconfirmed(
        watchOwned: true, phase: "completed", hasCutoff: true, ownerPhase: finishing.phase,
        stopOutcome: nil, verified: receiver.verify(id: id)), "unverified abandonment keeps the phone Start fence")
    rejects("a stopped native session is not ended") { _ = try watch.confirmNativeEnd(id: id, nativeEnded: false) }
    check(!ending.confirm(id: id, nativeEnded: false), "native ending gate survives delayed termination")
    for failurePoint in ["native end", "owner completion"] {
      let namespace = failurePoint == "native end" ? "watch-metadata" : "owner-snapshots"
      _ = try watch.store.transaction { db in
        try db.execute(
          "CREATE TEMP TRIGGER fail_completion BEFORE INSERT ON durable_records WHEN NEW.namespace='\(namespace)' BEGIN SELECT RAISE(ABORT,'Injected completion write failure'); END"
        )
      }
      rejects("post-termination persistence failure keeps completion retryable without a builder") {
        _ = try ending.complete(id: id, nativeEnded: true) {
          try watch.completeNativeEnd(id: id, command: nil)
        }
      }
      _ = try watch.store.transaction { db in try db.execute("DROP TRIGGER fail_completion") }
      check(
        !builderRetained && ending.workoutID == id,
        "failed completion keeps the ending operation after builder teardown")
      check(
        try watch.control.snapshot(workoutID: id)?.phase == "finishing",
        "failed completion cannot publish an ended owner")
      check(
        try watch.metadata(id: id)["localRecorderEnded"] as? Bool != true,
        "native-end evidence and owner completion commit together")
      rejects("failed completion still fences another Start") { try ending.requireAvailable() }
    }
    let item = try ending.complete(id: id, nativeEnded: true) { try watch.completeNativeEnd(id: id, command: nil) }!
    let owner = try watch.control.snapshot(workoutID: id)!
    check(
      ending.workoutID == nil && owner.phase == "completed",
      "automatic completion retry releases state only after durable native end")
    let final = try seal(2, item, owner)
    _ = try receiver.accept(seal: final)
    check(try receiver.verify(id: id), "confirmed native termination permits verification")
    try ending.requireAvailable()
    let restarted = try WatchWorkoutJournal(rootURL: watch.directory)
    check(
      try WatchWorkoutJournal.requirements(
        restarted.metadata(id: id), owner: restarted.control.snapshot(workoutID: id), insertionOutcome: "unavailable")[
          "ownerEnded"] == "sealed",
      "native completion survives restart")
  }
}
for saves in [true, false] {
  let id = UUID().uuidString.lowercased()
  let journal = try WatchWorkoutJournal(rootURL: root.appendingPathComponent(id))
  try journal.create(
    id: id,
    metadata: [
      "workoutId": id, "startedAt": WorkoutCoding.timestamp(start), "phase": "running",
      "saveToHealth": saves, "recordGPS": false, "healthKitState": saves ? "pending" : "notRequested",
    ])
  let cutoff = try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(10)), elapsedSeconds: 10, timerSeconds: 8)
  check(
    WorkoutRecoveryPlanner.inspectsWatchCandidate(
      phase: "completed", health: saves ? "saved" : "notRequested", finalHealthExtracted: true, nativeOwnerEnded: false),
    "restart still resolves native ownership after Health extraction completed")
  check(
    !WorkoutRecoveryPlanner.inspectsWatchCandidate(
      phase: "completed", health: saves ? "saved" : "notRequested", finalHealthExtracted: true, nativeOwnerEnded: true),
    "confirmed completion does not require another native recovery")
  let stopped = try journal.retainNativeStop(id: id, timing: cutoff)
  let retained = try WatchWorkoutJournal.timing(stopped)!
  let finishing = try journal.control.observe(
    workoutID: id, owner: "watch", phase: "finishing", at: WorkoutCoding.date(retained.timestamp),
    health: saves ? "saved" : "notRequested", cutoff: WorkoutCoding.date(retained.timestamp), timing: retained)
  check(finishing.timing == cutoff, "unsolicited terminal callback can finalize without a prior Finish command")
  check(
    try WatchWorkoutJournal.requirements(stopped, owner: finishing, insertionOutcome: "sealed")["ownerEnded"]
      == "pending",
    "normal finalization also waits for native termination")
  let newer = try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(20)), elapsedSeconds: 20, timerSeconds: 18)
  check(
    try WatchWorkoutJournal.timing(journal.retainNativeStop(id: id, timing: newer)) == cutoff,
    "later terminal callbacks cannot move the retained cutoff")
}
for nativePhase in ["stopped", "ended"] {
  let id = UUID().uuidString.lowercased()
  let watch = try WatchWorkoutJournal(rootURL: root.appendingPathComponent(id))
  try watch.create(
    id: id,
    metadata: [
      "workoutId": id, "startedAt": WorkoutCoding.timestamp(start), "phase": "running", "saveToHealth": true,
      "recordGPS": false, "healthKitState": "pending",
    ])
  let timing = try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(10)), elapsedSeconds: 10, timerSeconds: 10)
  _ = try watch.control.observe(
    workoutID: id, owner: "watch", phase: "running", at: start, health: "pending", timing: timing)
  let stop = try watch.control.admitLocal(
    workoutID: id, origin: "watch", action: "stop", at: start.addingTimeInterval(10))
  _ = try watch.control.prepare(stop)
  let reconciled = try WorkoutRecoveredOwnerCommand.reconcile(
    workoutID: id, owner: "watch", nativePhase: nativePhase, observedAt: start.addingTimeInterval(11),
    nativeEventDates: [:], observedLapIDs: [],
    cutoff: start.addingTimeInterval(10), health: "pending", healthID: nil, archive: watch.archive,
    control: watch.control, timing: timing)
  let owner = try watch.control.snapshot(workoutID: id)!
  let receipt = try watch.control.result(id: stop.id)
  if nativePhase == "stopped" {
    check(
      reconciled.completed == nil && reconciled.required?.id == stop.id,
      "owner query leaves Stop unresolved until native termination")
    check(
      owner.phase == "finishing" && receipt?.outcome != "applied",
      "stopped query publishes finishing without an applied Stop")
    check(
      WorkoutOwnerStopPolicy.isUnconfirmed(
        watchOwned: true, phase: "completed", hasCutoff: true, ownerPhase: owner.phase, stopOutcome: receipt?.outcome,
        verified: false), "phone Start fence survives stopped owner reconciliation")
  } else {
    check(owner.phase == "completed" && receipt?.outcome == "applied", "confirmed ended recovery can complete Stop")
  }
}
print("Watch abandonment: \(checks) assertions passed")
