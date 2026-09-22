import Foundation

var assertions = 0
func check(_ value: Bool, _ message: String) {
  assertions += 1
  if !value { fatalError(message) }
}
func rejects(_ body: () throws -> Void, _ message: String) {
  assertions += 1
  do { try body(); fatalError("Expected rejection: " + message) } catch { }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-owner-regression-" + UUID().uuidString)
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root)
let control = WorkoutControlJournal(store: archive.store)
let transfer = WorkoutTransferJournal(archive: archive)
let now = Date(timeIntervalSince1970: 1_800_000_000)
func ride(watch: Bool = true) throws -> String {
  try archive.create(id: UUID().uuidString.lowercased(), startedAt: now, indoor: true, watchEnabled: watch).id
}
func command(_ id: String, _ sequence: Int64, _ action: String, origin: String = "phone") throws -> WorkoutCommand {
  try WorkoutCommand(workoutID: id, origin: origin, originSequence: sequence, action: action, requestedAt: now)
}
func seed<T: Encodable>(_ namespace: String, _ key: String, _ value: T) throws {
  try archive.store.transaction { db in try db.put(namespace: namespace, key: key, value: WorkoutCoding.encoder().encode(value)) }
}
func counter(_ id: String, _ origin: String = "phone") throws -> Int64 {
  try archive.store.read { db in try db.get(namespace: "applied-origins", key: id + ":" + origin).map { try JSONDecoder().decode(Int64.self, from: $0) } ?? 0 }
}

// Production admission runs before any native call, and completion is another scoped transaction.
let first = try ride(), other = try ride()
let firstStart = try command(first, 1, "start"), otherStart = try command(other, 1, "start")
check(try control.prepare(firstStart).execute, "first owner start is admitted")
check(try control.prepare(otherStart).execute, "independent durable slot exists for second ID")
rejects({ _ = try control.observe(workoutID: other, owner: "watch", phase: "running", at: now, health: "pending", command: firstStart) }, "foreign completion")
check(try control.snapshot(workoutID: first) == nil && control.snapshot(workoutID: other) == nil, "foreign completion creates no snapshots")
check(try control.active(workoutID: first)?.id == firstStart.id && control.active(workoutID: other)?.id == otherStart.id, "foreign completion releases neither active slot")
check(try counter(first) == 0 && counter(other) == 0, "foreign completion advances no origin")
let firstSnapshot = try control.observe(workoutID: first, owner: "watch", phase: "running", at: now, health: "pending", command: firstStart)
let duplicate = try control.observe(workoutID: first, owner: "watch", phase: "failed", at: now.addingTimeInterval(5), health: "failed", command: firstStart)
check(duplicate == firstSnapshot, "terminal duplicate completion cannot change owner revision or phase")
check(try control.active(workoutID: other)?.id == otherStart.id, "same-ride duplicate leaves other active slot untouched")
rejects({ try WorkoutEffectIdentity.require(command: firstStart, workoutID: other, nativeWorkoutID: other) }, "command before native call")
rejects({ try WorkoutEffectIdentity.require(command: firstStart, workoutID: first, nativeWorkoutID: other) }, "native session before call")
let generation = UUID(), nextGeneration = UUID()
let identity = WorkoutEffectIdentity(workoutID: first, generation: generation)
check(identity.matches(workoutID: first, generation: generation), "matching callback can publish")
check(!identity.matches(workoutID: other, generation: generation), "delayed old-ride callback cannot publish to new ride")
check(!identity.matches(workoutID: first, generation: nextGeneration), "same-ID old-session callback cannot publish after recovery")

// T2: legacy terminal result with a dangling slot and no matching owner snapshot.
let t2 = try ride(), t2Start = try command(t2, 1, "start")
_ = try control.prepare(t2Start)
try seed("command-results", t2Start.id, WorkoutCommandResult(commandID: t2Start.id, outcome: "applied", ownerRevision: 1))
try control.repairTerminalSlot(workoutID: t2)
check(try control.active(workoutID: t2) == nil && control.snapshot(workoutID: t2) == nil, "T2 removes only derivable bookkeeping, without native proof")
check(try counter(t2) == 1, "terminal slot repair consumes its contiguous protocol receipt")
let t2Stop = try command(t2, 2, "stop")
let unresolvedT2 = try WorkoutOwnerAdmission.prepare(t2Stop, control: control, nativeWorkoutID: nil, readyToStart: true, recovering: false)
check(!unresolvedT2.execute && !unresolvedT2.result.isTerminal, "T2 missing owner remains actionable and unresolved")
check(try control.snapshot(workoutID: t2) == nil && archive.metadata(id: t2).endedAt == nil, "T2 recovery does not fabricate end or samples")

// T3: its real terminal cutoff settles an older stop below an already advanced origin.
let t3 = try ride(), actualCutoff = now.addingTimeInterval(32)
let t3Stop = try command(t3, 1, "stop")
_ = try control.accept(t3Stop)
try seed("applied-origins", t3 + ":phone", Int64(2))
let legacyOwner = WorkoutOwnerSnapshot(workoutID: t3, owner: "watch", ownerRevision: 7,
  effectiveAt: WorkoutCoding.timestamp(actualCutoff), phase: "finished", healthOutcome: "saved", stopCutoff: WorkoutCoding.timestamp(actualCutoff))
try seed("owner-snapshots", t3, legacyOwner)
let oldStop = try WorkoutOwnerAdmission.query(t3Stop, control: control, nativeWorkoutID: other)
check(oldStop.outcome == "applied", "T3 old stop settles from original terminal proof")
check(try counter(t3) == 2, "T3 counter never moves backward")
check(try control.snapshot(workoutID: t3)?.stopCutoff == legacyOwner.stopCutoff, "T3 retains actual cutoff instead of requested stop time")
check(try control.snapshot(workoutID: t3)?.ownerRevision == 7 && control.snapshot(workoutID: t3)?.phase == "completed", "legacy UI phase canonicalizes without invented owner revisions")
var badPhase = legacyOwner; badPhase.phase = "unexpected"; badPhase.ownerRevision = 8
rejects({ _ = try control.accept(snapshot: badPhase) }, "unsupported wire phase")
var backwards = legacyOwner; backwards.phase = "running"; backwards.ownerRevision = 8
rejects({ _ = try control.accept(snapshot: backwards) }, "terminal owner cannot run again")
var changedCutoff = legacyOwner; changedCutoff.stopCutoff = WorkoutCoding.timestamp(now); changedCutoff.ownerRevision = 8
rejects({ _ = try control.accept(snapshot: changedCutoff) }, "immutable original cutoff")

// T4: an old executing start must reconcile; a proven different active owner rejects it durably.
let t4 = try ride(), t4Start = try command(t4, 1, "start")
check(try WorkoutOwnerAdmission.prepare(t4Start, control: control, nativeWorkoutID: nil, readyToStart: true, recovering: false).execute, "fresh start executes once")
let uncertain = try WorkoutOwnerAdmission.prepare(t4Start, control: control, nativeWorkoutID: nil, readyToStart: true, recovering: false)
check(!uncertain.execute && uncertain.reconcile, "executing replay never blindly starts Health again")
let queryResult = try WorkoutOwnerAdmission.query(t4Start, control: control, nativeWorkoutID: nil)
check(!queryResult.isTerminal && queryResult.reason != nil, "privacy-limited or empty native query is not never-started proof")
check(try control.active(workoutID: t4)?.id == t4Start.id, "unresolved query retains original intent")
let conflict = try WorkoutOwnerAdmission.query(t4Start, control: control, nativeWorkoutID: t3)
check(conflict.outcome == "rejected" && conflict.reason == "Another workout is active", "proven conflict is a terminal same-command rejection")
check(try control.active(workoutID: t4) == nil && control.snapshot(workoutID: t4) == nil, "conflict releases command but invents no native snapshot")
let t4Stop = try command(t4, 2, "stop")
let rejectedStop = try WorkoutOwnerAdmission.query(t4Stop, control: control, nativeWorkoutID: t3)
check(rejectedStop.outcome == "rejected", "stop after rejected start releases UI without false completion")
check(try archive.metadata(id: t4).endedAt == nil && archive.metadata(id: t4).eventCount == 0, "failed unconfirmed start has no invented interval or records")
let later = try ride(), laterStart = try command(later, 1, "start", origin: "watch")
check(try WorkoutOwnerAdmission.prepare(laterStart, control: control, nativeWorkoutID: nil, readyToStart: true, recovering: false).execute, "future local start has its own command and slot")
check(try control.active(workoutID: later)?.workoutID == later && control.active(workoutID: t4) == nil, "future local start cannot inherit T4")
let earlyID = try ride(), earlyStart = try command(earlyID, 1, "start")
let early = try WorkoutOwnerAdmission.prepare(earlyStart, control: control, nativeWorkoutID: nil, readyToStart: false, recovering: true)
check(!early.execute && early.result.outcome == "accepted" && early.result.reason != nil, "early recovery return stays accepted and explicit")
check(try control.active(workoutID: earlyID) == nil, "early return cannot poison executing slot")
for action in ["pause", "resume", "lap", "stop"] {
  let target = try ride(), intent = try command(target, 1, action)
  let decision = try WorkoutOwnerAdmission.prepare(intent, control: control, nativeWorkoutID: first, readyToStart: false, recovering: false)
  check(!decision.execute && !decision.reconcile, "foreign " + action + " cannot reach a native effect")
  check(try control.active(workoutID: target) == nil && control.snapshot(workoutID: target) == nil, "foreign " + action + " does not mutate current owner")
}

// New queries bypass ordering entirely; legacy queries cannot skip unfinished effects.
let query = try WorkoutOwnerQuery(workoutID: t4, pendingCommandID: t4Start.id)
check(query.packet["originSequence"] == nil && query.packet["origin"] == nil, "query has no owner sequence")
check(try WorkoutOwnerQuery.decode(query.packet) == query, "query wire identity round trips")
check(query.matches(["kind": "ownerReply", "queryId": query.id, "workoutId": t4]), "matching query reply is admitted")
check(!query.matches(["kind": "ownerReply", "queryId": query.id, "workoutId": t3]), "cross-ride reply ignored")
check(!query.matches(["kind": "ownerReply", "queryId": UUID().uuidString, "workoutId": t4]), "superseded query reply ignored")
let ordered = try ride(), orderedStart = try command(ordered, 1, "start"), legacyQuery = try command(ordered, 2, "status")
check(try WorkoutOwnerAdmission.prepare(legacyQuery, control: control, nativeWorkoutID: other, readyToStart: false, recovering: true).result.outcome == "applied", "legacy status gets read-only terminal receipt")
check(try counter(ordered) == 0 && control.snapshot(workoutID: ordered) == nil, "legacy status cannot skip missing start or create owner")
_ = try control.prepare(orderedStart)
_ = try control.observe(workoutID: ordered, owner: "watch", phase: "running", at: now, health: "pending", command: orderedStart)
check(try counter(ordered) == 2, "completed start drains contiguous terminal legacy query")
check(try control.snapshot(workoutID: ordered)?.ownerRevision == 1, "legacy query does not increase owner revision")

// Retry lookup is scoped by the actual acknowledged remote command, never current selection.
let remoteOld = try ride(), remoteNew = try ride(), localID = try ride(watch: false)
let oldStart = try control.createRemote(workoutID: remoteOld, origin: "phone", action: "start", at: now)
let oldStopRemote = try control.createRemote(workoutID: remoteOld, origin: "phone", action: "stop", at: now)
let newStart = try control.createRemote(workoutID: remoteNew, origin: "phone", action: "start", at: now)
let localStart = try control.admitLocal(workoutID: localID, origin: "phone", action: "start", at: now)
let ack = WorkoutCommandResult(commandID: oldStopRemote.id, outcome: "accepted", missingOriginSequence: 1)
check(try control.retryForAcknowledgement(ack, acknowledgedID: oldStopRemote.id, workoutID: remoteOld)?.id == oldStart.id, "old stop retries its own start")
check(try control.retryForAcknowledgement(ack, acknowledgedID: oldStopRemote.id, workoutID: remoteNew) == nil, "wrong-workout acknowledgement cannot retry current start")
check(try control.remoteCommand(id: localStart.id, workoutID: localID) == nil, "locally admitted phone start is never remote transport")
let localACK = WorkoutCommandResult(commandID: localStart.id, outcome: "accepted", missingOriginSequence: 1)
check(try control.retryForAcknowledgement(localACK, acknowledgedID: localStart.id, workoutID: localID) == nil, "foreign ACK cannot export phone-local intent")
let done = WorkoutCommandResult(commandID: oldStopRemote.id, outcome: "rejected", reason: "Owner conflict")
check(!(try control.completeRemote(done, acknowledgedID: oldStopRemote.id, workoutID: remoteNew)), "wrong identity cannot clear pending action")
check(try control.pendingRemote(workoutID: remoteNew)?.commandID == newStart.id, "new ride pending start remains selected")
check(try control.completeRemote(done, acknowledgedID: oldStopRemote.id, workoutID: remoteOld), "same terminal rejection clears original pending action")
check(try control.pendingRemote(workoutID: remoteOld) == nil, "original pending action removed durably")
try archive.store.transaction { db in
  try db.put(namespace: "phone-current", key: "workout", value: JSONSerialization.data(withJSONObject:
    ["id": remoteOld, "phase": "recoverable", "useWatch": true, "timerSeconds": 0]))
}
let remoteRestart = WorkoutControlJournal(store: archive.store)
check(try remoteRestart.rejectedRemoteWithoutOwner(workoutID: remoteOld) == done.reason,
  "remote ACK committed before the UI checkpoint retains terminal rejection for automatic restore")
check(try remoteRestart.rejectedRemoteWithoutOwner(workoutID: remoteNew) == nil,
  "another ride's pending action cannot use the old rejection projection")
check(try archive.metadata(id: remoteOld).endedAt == nil && remoteRestart.snapshot(workoutID: remoteOld) == nil,
  "remote rejection projection invents no historical end or owner snapshot")
let followOnRemote = try control.createRemote(workoutID: remoteOld, origin: "phone", action: "stop", at: actualCutoff)
check(try control.rejectedRemoteWithoutOwner(workoutID: remoteOld) == nil, "new pending action defers a previous terminal rejection projection")
_ = try control.completeRemote(WorkoutCommandResult(commandID: followOnRemote.id, outcome: "applied"), acknowledgedID: followOnRemote.id, workoutID: remoteOld)
check(try control.rejectedRemoteWithoutOwner(workoutID: remoteOld) == nil, "later applied owner action clears obsolete terminal rejection projection")
_ = try control.completeRemote(done, acknowledgedID: oldStopRemote.id, workoutID: remoteOld)
check(try control.rejectedRemoteWithoutOwner(workoutID: remoteOld) == nil, "old duplicate ACK cannot reintroduce a released rejection after later owner success")

// Recovery of an unconfirmed start restores the actual owner timeline, including after restart.
let recoveringID = try ride()
try archive.update(id: recoveringID, phase: "recoverable")
let anchor = try WorkoutPhoneStartProjection.confirmOwnerPhase(archive: archive, id: recoveringID, localPhase: "preparing", ownerPhase: "running",
  startedAt: now.addingTimeInterval(3), now: now.addingTimeInterval(13), uptime: 100, epoch: "test")
check(anchor?.startedAt == WorkoutCoding.timestamp(now.addingTimeInterval(3)) && anchor?.monotonicOrigin == 90, "recoverable no-timeline start receives actual elapsed anchor")
let localOwner = WorkoutOwnerSnapshot(workoutID: localID, owner: "watch", ownerRevision: 1, effectiveAt: WorkoutCoding.timestamp(now), phase: "running", healthOutcome: "pending")
check(try WorkoutOwnerAdoption.accept(localOwner, archive: archive, control: control, startedAt: now, indoor: true) == nil, "Watch cannot adopt existing phone-owned collection")

// Target-specific resealing works while another collection is selected, without changing it.
let completedPhone = try ride(watch: false)
try archive.finish(id: completedPhone, endedAt: actualCutoff)
try archive.update(id: completedPhone, healthKitState: "saved")
_ = try control.observe(workoutID: completedPhone, owner: "phone", phase: "completed", at: actualCutoff, health: "saved", cutoff: actualCutoff)
let beforeOther = try archive.metadata(id: t4)
let phoneSeal = try WorkoutPhoneSealRepair.seal(id: completedPhone, archive: archive, transfer: transfer, control: control)
let repeatedSeal = try WorkoutPhoneSealRepair.seal(id: completedPhone, archive: archive, transfer: transfer, control: control)
check(phoneSeal.workoutID == completedPhone && phoneSeal.stopCutoff == WorkoutCoding.timestamp(actualCutoff), "reseal targets historical original metadata")
check(phoneSeal.sealRevision == repeatedSeal.sealRevision, "unchanged explicit repair does not churn seals")
check(try archive.metadata(id: t4).dictionary as NSDictionary == beforeOther.dictionary as NSDictionary, "historical repair cannot change current interrupted collection")
rejects({ _ = try WorkoutPhoneSealRepair.seal(id: t4, archive: archive, transfer: transfer, control: control) }, "Watch ride cannot use phone repair")

// Repairing an unavailable receipt recomputes progress and creates one replacement seal.
let repairedPhone = try ride(watch: false)
let telemetry = try WorkoutEvent(workoutId: repairedPhone, kind: "telemetry", source: "cyc", timestamp: now,
  elapsedSeconds: 0, payload: ["humanPowerW": .integer(120), "cadenceRpm": .integer(80)])
try archive.append(telemetry)
try archive.finish(id: repairedPhone, endedAt: actualCutoff)
try archive.update(id: repairedPhone, healthKitState: "saved")
_ = try control.observe(workoutID: repairedPhone, owner: "phone", phase: "completed", at: actualCutoff, health: "saved", cutoff: actualCutoff)
let insertion = WorkoutHealthInsertionJournal(archive: archive)
try insertion.prepare([telemetry]); try insertion.record([telemetry], outcome: "unavailable")
let partialSeal = try WorkoutPhoneSealRepair.seal(id: repairedPhone, archive: archive, transfer: transfer, control: control)
check(partialSeal.requirements["cycInsertion"] == "unavailable", "partial seal describes original unavailable receipt")
try insertion.beginRepair(id: repairedPhone)
try insertion.record([telemetry], outcome: "applied")
try insertion.finishRepair(id: repairedPhone)
let repairedSeal = try WorkoutPhoneSealRepair.seal(id: repairedPhone, archive: archive, transfer: transfer, control: control)
check(repairedSeal.sealRevision == partialSeal.sealRevision + 1 && repairedSeal.requirements["cycInsertion"] == "sealed", "repair replaces the partial seal only after receipt progress resolves")
check(try archive.metadata(id: repairedPhone).eventCount == 1 && archive.pageEvents(id: repairedPhone).first?.event == telemetry, "repair preserves original samples exactly")
check(WorkoutRecoveryPlanner.action(hasCutoff: false, verifiedFinality: false, nativePhase: "stopped", pendingAction: "lap") == .finishSameSession,
  "stopped native owner cannot replay a pending lap")
check(WorkoutRecoveryPlanner.action(hasCutoff: false, verifiedFinality: false, nativePhase: "ended", pendingAction: "resume") == .finishSameSession,
  "ended native owner cannot resume")

check(WorkoutUnconfirmedStopPolicy.action(hasTimeline: false, preparing: true, hasNativeIntent: false) == .cancelPreparation,
  "phone stop before native admission cancels preparation only")
check(WorkoutUnconfirmedStopPolicy.action(hasTimeline: false, preparing: true, hasNativeIntent: true) == .retainOwnerIntent,
  "phone stop during native start retains intent without an end")
check(WorkoutUnconfirmedStopPolicy.action(hasTimeline: false, preparing: false, hasNativeIntent: false) == .retainOwnerIntent,
  "interrupted unconfirmed phone owner is not declared ended")
check(WorkoutUnconfirmedStopPolicy.confirmedCutoff(requestedAt: now, actualStart: actualCutoff, now: actualCutoff.addingTimeInterval(1)) == actualCutoff,
  "queued stop before actual start clamps only after native confirmation")
check(WorkoutRecoveryPlanner.waitsForStart(phase: "preparing", startCompletionPending: true), "recovery cannot supersede in-flight preparation")
check(WorkoutRecoveryPlanner.waitsForStart(phase: "recoverable", startCompletionPending: true), "readiness timeout keeps original pending native callback valid")
check(!WorkoutRecoveryPlanner.waitsForStart(phase: "recoverable", startCompletionPending: false), "restarted unresolved owner requires native recovery")

check(WorkoutRecoveryPlanner.inspectsWatchCandidate(phase: "recoverable", health: "unknown", finalHealthExtracted: false),
  "second recovery re-queries interrupted Watch owner after Health becomes accessible")
check(WorkoutRecoveryPlanner.needsSavedIdentityLookup(requestedID: t4, candidateID: nil, hasNativeSession: false),
  "explicit recovery inspects original saved ID even when local Watch metadata is missing")
check(!WorkoutRecoveryPlanner.needsSavedIdentityLookup(requestedID: nil, candidateID: nil, hasNativeSession: false),
  "recovery without an identity cannot adopt an arbitrary saved workout")

// Explicit stop may cancel an unconfirmed attempt after a fresh native-absence probe.
let cancellable = try ride(), pendingStart = try command(cancellable, 1, "start"), cancelStop = try command(cancellable, 2, "stop")
_ = try control.prepare(pendingStart)
check(try WorkoutOwnerAdmission.cancelUnconfirmed(cancelStop, control: control, nativeAbsenceConfirmed: false, effectInFlight: false) == nil,
  "failed or absent native query cannot cancel uncertain intent")
check(try WorkoutOwnerAdmission.cancelUnconfirmed(cancelStop, control: control, nativeAbsenceConfirmed: true, effectInFlight: true) == nil,
  "native start in flight cannot be canceled by an old no-session result")
check(try WorkoutOwnerAdmission.cancelUnconfirmed(pendingStart, control: control, nativeAbsenceConfirmed: true, effectInFlight: false) == nil,
  "read-only start reconciliation is not explicit stop authorization")
let cancellation = try WorkoutOwnerAdmission.cancelUnconfirmed(cancelStop, control: control, nativeAbsenceConfirmed: true, effectInFlight: false)
check(cancellation?.outcome == "rejected" && cancellation?.reason?.contains("no workout end time") == true, "explicit stop cancels attempt with truthful unknown historical outcome")
check(try control.result(id: pendingStart.id)?.outcome == "rejected" && control.active(workoutID: cancellable) == nil, "canceled start has durable tombstone and releases its slot")
check(try !WorkoutOwnerAdmission.prepare(pendingStart, control: control, nativeWorkoutID: nil, readyToStart: true, recovering: false).execute,
  "delayed delivery cannot restart canceled attempt")
check(try archive.metadata(id: cancellable).endedAt == nil && control.snapshot(workoutID: cancellable) == nil, "canceling intent invents neither final workout nor cutoff")
let probeID = UUID(), absence = WorkoutNativeAbsenceEvidence(identity: identity, probeID: probeID)
check(absence.matches(workoutID: first, generation: generation, probeID: probeID), "fresh same-invocation native absence can authorize cancellation")
check(!absence.matches(workoutID: first, generation: generation, probeID: UUID()), "previous check's absence cannot authorize cancellation")
check(!absence.matches(workoutID: first, generation: nextGeneration, probeID: probeID), "different session generation invalidates native absence")
check(try WorkoutOwnerAdmission.cancelUnconfirmed(t2Stop, control: control, nativeAbsenceConfirmed: true, effectInFlight: false)?.outcome == "rejected",
  "explicit T2 stop can release UI after native absence without rewriting corrupt applied start")
check(try control.result(id: t2Start.id)?.outcome == "applied" && control.snapshot(workoutID: t2) == nil, "T2 old receipt is retained without fabricated owner proof")

// Phone restore retains START as the active command even after the user queues STOP.
// Cancellation must use that durable STOP and distinguish an empty saved lookup from query failure.
let interruptedPhone = try ride(watch: false)
let phoneStart = try control.admitLocal(workoutID: interruptedPhone, origin: "phone", action: "start", at: now)
_ = try control.prepare(phoneStart)
let phoneStop = try control.admitLocal(workoutID: interruptedPhone, origin: "phone", action: "stop", at: actualCutoff)
check(try control.begin(phoneStop).outcome == "accepted", "phone STOP remains queued behind the executing START")
check(try control.active(workoutID: interruptedPhone)?.id == phoneStart.id && control.pendingStop(workoutID: interruptedPhone, origin: "phone")?.id == phoneStop.id,
  "restarted phone can find explicit STOP without replacing its active START")
check(try WorkoutOwnerAdmission.cancelPhoneAfterLookup(NSError(domain: "HealthQuery", code: 1), workoutID: interruptedPhone, control: control,
  nativeAbsenceConfirmed: true, effectInFlight: false) == nil, "arbitrary Health query failure cannot cancel phone intent")
check(try WorkoutOwnerAdmission.cancelPhoneAfterLookup(WorkoutSavedOwnerLookupError.notAccessible, workoutID: interruptedPhone, control: control,
  nativeAbsenceConfirmed: false, effectInFlight: false) == nil, "empty saved lookup without successful native absence cannot cancel phone intent")
check(try WorkoutOwnerAdmission.cancelPhoneAfterLookup(WorkoutSavedOwnerLookupError.notAccessible, workoutID: interruptedPhone, control: control,
  nativeAbsenceConfirmed: true, effectInFlight: true) == nil, "empty saved lookup cannot cancel an in-flight phone effect")
check(try control.active(workoutID: interruptedPhone)?.id == phoneStart.id && control.result(id: phoneStop.id)?.outcome == "accepted",
  "unavailable recovery preserves both original phone intents")
let phoneCancellation = try WorkoutOwnerAdmission.cancelPhoneAfterLookup(WorkoutSavedOwnerLookupError.notAccessible, workoutID: interruptedPhone, control: control,
  nativeAbsenceConfirmed: true, effectInFlight: false)
check(phoneCancellation?.commandID == phoneStop.id && phoneCancellation?.outcome == "rejected", "fresh native absence settles the actual queued phone STOP")
let reopenedControl = WorkoutControlJournal(store: archive.store)
check(try reopenedControl.result(id: phoneStart.id)?.outcome == "rejected" && reopenedControl.active(workoutID: interruptedPhone) == nil &&
  reopenedControl.pendingStop(workoutID: interruptedPhone, origin: "phone") == nil, "phone cancellation remains terminal after journal reconstruction")
check(try !WorkoutOwnerAdmission.prepare(phoneStart, control: reopenedControl, nativeWorkoutID: nil, readyToStart: true, recovering: false).execute,
  "phone restart cannot replay a canceled native start")
check(try archive.metadata(id: interruptedPhone).endedAt == nil && control.snapshot(workoutID: interruptedPhone) == nil,
  "combined phone recovery cancellation retains unknown historical outcome without an end")
// Crash after the atomic receipts but before the UI checkpoint must not reopen an unresolved start.
try archive.store.transaction { db in
  try db.put(namespace: "phone-current", key: "workout", value: JSONSerialization.data(withJSONObject:
    ["id": interruptedPhone, "phase": "recoverable", "useWatch": false, "timerSeconds": 0]))
}
check(try reopenedControl.cancelledWithoutOwner(workoutID: interruptedPhone) != nil && reopenedControl.pendingStop(workoutID: interruptedPhone, origin: "phone") == nil,
  "automatic restore derives released cancellation from its durable receipt despite stale recoverable checkpoint and no pending STOP")
check(try archive.metadata(id: interruptedPhone).endedAt == nil, "cancellation projection repair does not infer a historical end")
check(try reopenedControl.cancelledWithoutOwner(workoutID: other) == nil, "executing foreign owner cannot use another attempt's cancellation projection")
check(try reopenedControl.cancelledWithoutOwner(workoutID: first) == nil, "actual owner snapshot prevents unconfirmed cancellation projection")

// The transport may deliver STOP before its START. The workout-level tombstone fences the later arrival.
let reverseDelivery = try ride(), earlyStop = try command(reverseDelivery, 2, "stop"), lateStart = try command(reverseDelivery, 1, "start")
check(try WorkoutOwnerAdmission.cancelUnconfirmed(earlyStop, control: control, nativeAbsenceConfirmed: true, effectInFlight: false)?.outcome == "rejected",
  "explicit STOP can cancel an unconfirmed attempt before its START arrives")
let lateDecision = try WorkoutOwnerAdmission.prepare(lateStart, control: reopenedControl, nativeWorkoutID: nil, readyToStart: true, recovering: false)
check(!lateDecision.execute && lateDecision.result.outcome == "rejected", "late START is durably rejected by the workout cancellation tombstone")
check(try counter(reverseDelivery) == 2 && control.active(workoutID: reverseDelivery) == nil,
  "reversed command delivery settles the contiguous prefix without admitting an effect")
check(try archive.metadata(id: reverseDelivery).endedAt == nil && control.snapshot(workoutID: reverseDelivery) == nil,
  "reversed delivery cancellation creates no native owner evidence")
let beforeIntentPhone = try ride(watch: false)
let beforeIntentStop = try control.admitLocal(workoutID: beforeIntentPhone, origin: "phone", action: "stop", at: actualCutoff)
check(try WorkoutOwnerAdmission.cancelPhoneAfterLookup(WorkoutSavedOwnerLookupError.notAccessible, workoutID: beforeIntentPhone, control: control,
  nativeAbsenceConfirmed: true, effectInFlight: false)?.commandID == beforeIntentStop.id,
  "phone crash before native START admission still permits explicit cancellation after successful absence")
check(try archive.metadata(id: beforeIntentPhone).endedAt == nil && control.cancelledStart(workoutID: beforeIntentPhone) != nil,
  "pre-admission phone cancellation persists its fence without inventing a completed interval")
let falseStopID = try ride(), falseStop = try command(falseStopID, 1, "stop")
_ = try control.prepare(falseStop)
rejects({ _ = try control.observe(workoutID: falseStopID, owner: "phone", phase: "running", at: now, health: "pending", command: falseStop) }, "running observation cannot complete a stop")
check(try control.result(id: falseStop.id)?.outcome == "executing", "invalid stop observation preserves pending intent")
check(WorkoutRecoveryPlanner.action(hasCutoff: false, verifiedFinality: false, nativePhase: "running", pendingAction: "stop") == .finishSameSession,
  "recovered running owner with pending stop is stopped instead of observed as running")

check(WorkoutOwnerTiming.elapsed(start: now, end: actualCutoff, reported: nil, now: now.addingTimeInterval(36_000)) == 32,
  "old completed record without elapsed field uses original end, not hours since start")
check(WorkoutOwnerTiming.elapsed(start: now, end: actualCutoff, reported: 31.5, now: now.addingTimeInterval(36_000)) == 31.5,
  "known monotonic owner elapsed survives historical recovery")

// Real Watch writer keeps its catalog and owner-derived projection consistent.
let watchJournal = try WatchWorkoutJournal(rootURL: root.appendingPathComponent("watch"))
let watchID = UUID().uuidString.lowercased()
try watchJournal.create(id: watchID, metadata: ["workoutId": watchID, "startedAt": WorkoutCoding.timestamp(now), "phase": "running", "healthKitState": "pending"])
_ = try watchJournal.control.observe(workoutID: watchID, owner: "watch", phase: "finished", at: actualCutoff, health: "saved", cutoff: actualCutoff)
try watchJournal.reconcileOwnerMetadata(id: watchID)
let watchCatalog = try watchJournal.archive.metadata(id: watchID)
check(watchCatalog.phase == "completed" && watchCatalog.healthKitState == "saved" && watchCatalog.endedAt == WorkoutCoding.timestamp(actualCutoff), "legacy Watch owner projection repairs running/notSaved catalog")
rejects({ try watchJournal.save(id: watchID, metadata: ["workoutId": first, "phase": "running"]) }, "foreign metadata save")
check(try watchJournal.archive.metadata(id: watchID).phase == "completed", "failed foreign save leaves native catalog intact")

// A successful cancellation resolves presentation, not the durable replay fence or original data.
let noticeID = UUID().uuidString.lowercased()
let staleNotice: [String: Any] = ["workoutId": noticeID, "startedAt": WorkoutCoding.timestamp(now),
  "phase": "recoverable", "healthKitState": "unknown", "error": WatchWorkoutJournal.unavailableOwnerIssue]
try watchJournal.create(id: noticeID, metadata: staleNotice)
let retainedEvent = try WorkoutEvent(workoutId: noticeID, kind: "health", source: "watch", timestamp: now,
  payload: ["heartRateBpm": .integer(81)])
try watchJournal.append(id: noticeID, record: WorkoutCoding.encoder().encode(retainedEvent))
let originalRecords = try encodedRecords(watchJournal, id: noticeID)
check(try watchJournal.reconcileCancelledStart(id: noticeID) == nil, "unresolved attempt cannot be silently cleared")
let noticeStart = try watchJournal.control.admitLocal(workoutID: noticeID, origin: "phone", action: "start", at: now)
_ = try watchJournal.control.prepare(noticeStart)
let noticeStop = try watchJournal.control.admitLocal(workoutID: noticeID, origin: "phone", action: "stop", at: actualCutoff)
_ = try WorkoutOwnerAdmission.cancelUnconfirmed(noticeStop, control: watchJournal.control,
  nativeAbsenceConfirmed: true, effectInFlight: false)
let cancelledProjection = try watchJournal.reconcileCancelledStart(id: noticeID)
check(cancelledProjection?["phase"] as? String == "failed" && cancelledProjection?["error"] == nil,
  "successful explicit cancellation removes its resolved recovery warning")
check(cancelledProjection?["endedAt"] == nil && cancelledProjection?["healthKitState"] as? String == "unknown",
  "presentation cleanup infers neither workout completion nor a Health outcome")
check(try encodedRecords(watchJournal, id: noticeID) == originalRecords, "presentation cleanup preserves original events byte for byte")
let cancelledReason = try watchJournal.control.cancelledStart(workoutID: noticeID)!
check(WatchWorkoutJournal.isResolvedCancellationIssue(WorkoutOwnerAdmission.cancelledStopReason, reason: cancelledReason),
  "legacy cancellation diagnostic is recognized as resolved")
check(!WatchWorkoutJournal.isResolvedCancellationIssue("Local storage: disk full", reason: cancelledReason) &&
  !WatchWorkoutJournal.isResolvedCancellationIssue("Health save: denied", reason: cancelledReason) &&
  !WatchWorkoutJournal.isResolvedCancellationIssue("Ride sync will retry", reason: cancelledReason),
  "unrelated storage, Health and sync errors are never cleared")
var unrelatedNotice = try watchJournal.metadata(id: noticeID)
unrelatedNotice["error"] = "Local storage: disk full"
try watchJournal.save(id: noticeID, metadata: unrelatedNotice)
check(try watchJournal.reconcileCancelledStart(id: noticeID)?["error"] as? String == "Local storage: disk full",
  "unrelated saved diagnostic survives repeated cancellation projection")

// A stale checkpoint survives a failed write and is repaired after reconstruction without a new warning.
try watchJournal.save(id: noticeID, metadata: staleNotice)
watchJournal.store.beforeCommitForTesting = { throw WorkoutDataError.invalid("Injected projection commit failure") }
rejects({ _ = try watchJournal.reconcileCancelledStart(id: noticeID) }, "projection persistence failure must stay visible")
watchJournal.store.beforeCommitForTesting = nil
check(try watchJournal.metadata(id: noticeID)["error"] as? String == WatchWorkoutJournal.unavailableOwnerIssue,
  "failed commit does not pretend the recovery checkpoint was cleared")
let noticeRestart = try WatchWorkoutJournal(rootURL: root.appendingPathComponent("watch"))
check(try noticeRestart.reconcileCancelledStart(id: noticeID)?["error"] == nil,
  "durable cancellation outranks a stale recoverable checkpoint after reconstruction")
_ = try WorkoutOwnerAdmission.cancelUnconfirmed(noticeStop, control: noticeRestart.control,
  nativeAbsenceConfirmed: true, effectInFlight: false)
check(try noticeRestart.reconcileCancelledStart(id: noticeID)?["error"] == nil,
  "duplicate STOP does not resurrect the resolved cancellation diagnostic")
check(try !WorkoutOwnerAdmission.prepare(noticeStart, control: noticeRestart.control,
  nativeWorkoutID: nil, readyToStart: true, recovering: false).execute,
  "removing the warning never allows a delayed START to run")
check(try encodedRecords(noticeRestart, id: noticeID) == originalRecords, "replay and reconstruction retain original samples")

// Real saved-owner evidence wins over a stale cancellation fence; no older cleanup changes another ride.
let savedWatchBefore = try watchJournal.metadata(id: watchID)
try watchJournal.control.cancelStartIntent(workoutID: watchID, reason: cancelledReason)
check(try watchJournal.reconcileCancelledStart(id: watchID) == nil,
  "an authoritative saved owner cannot be projected as a canceled attempt")
check(try watchJournal.metadata(id: watchID) as NSDictionary == savedWatchBefore as NSDictionary,
  "cancellation cleanup leaves the separate saved ride unchanged")
print("Workout ownership and recovery: \(assertions) assertions passed")

// A durable discard remains distinct from Save across packet delivery and owner recovery.
for owner in ["phone", "watch"] {
  let discardedID = try ride(watch: owner == "watch")
  let start = try command(discardedID, 1, "start", origin: owner)
  _ = try control.prepare(start)
  _ = try control.observe(workoutID: discardedID, owner: owner, phase: "running", at: now, health: "pending", command: start)
  let discard = try command(discardedID, 2, "discard", origin: owner)
  check(try WorkoutCommand.decode(discard.packet) == discard, "discard is an explicit round-tripping command, never a stop alias")
  check(try control.prepare(discard).execute, "active owner admits discard")
  let reopened = WorkoutControlJournal(store: archive.store)
  rejects({ _ = try reopened.observe(workoutID: discardedID, owner: owner, phase: "completed", at: now, health: "pending", cutoff: now, command: discard) }, "stopped owner is not a discard receipt")
  check(try reopened.active(workoutID: discardedID)?.action == "discard", "restart retains discard intent")
  for nativePhase in ["running", "stopped", "ended"] {
    let recovered = try WorkoutRecoveredOwnerCommand.reconcile(workoutID: discardedID, owner: owner, nativePhase: nativePhase,
      observedAt: now, nativeEventDates: [:], observedLapIDs: [], cutoff: now, health: "pending", healthID: nil,
      archive: archive, control: reopened)
    check(recovered.required?.id == discard.id && recovered.completed == nil, "native \(nativePhase) alone cannot confirm discard or start a save")
  }
  archive.store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "injected discard commit failure") }
  rejects({ _ = try reopened.observe(workoutID: discardedID, owner: owner, phase: "completed", at: now,
    health: "discarded", cutoff: now, command: discard) }, "failed discard receipt remains retryable")
  archive.store.beforeCommitForTesting = nil
  check(try reopened.active(workoutID: discardedID)?.id == discard.id, "failed discard outcome does not release intent")
  let terminal = try reopened.observe(workoutID: discardedID, owner: owner, phase: "completed", at: now, health: "discarded", cutoff: now, command: discard)
  let duplicate = try reopened.prepare(discard)
  check(duplicate.result.outcome == "applied" && !duplicate.execute, "duplicate discard never touches another native session")
  var savedAgain = terminal; savedAgain.ownerRevision += 1; savedAgain.healthOutcome = "saved"
  rejects({ _ = try reopened.accept(snapshot: savedAgain) }, "discard cannot later become a saved workout")
  let another = try command(discardedID, 3, "discard", origin: owner)
  _ = try reopened.accept(another)
  check(try reopened.settleTerminal(another)?.outcome == "applied", "repeated discard observes the same terminal outcome")
}
print("Discard lifecycle regressions passed; \(assertions) total ownership assertions")
