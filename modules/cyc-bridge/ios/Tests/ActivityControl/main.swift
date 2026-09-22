import Foundation

var checks = 0
func check(_ condition: Bool, _ message: String) { checks += 1; if !condition { fatalError(message) } }
func rejects(_ operation: () throws -> Void, _ message: String) {
  do { try operation(); fatalError(message) } catch { checks += 1 }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-activity-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root)
let control = WorkoutControlJournal(store: archive.store)
let outbox = WorkoutBoundedOutbox(store: archive.store)
let date = Date(timeIntervalSince1970: 1_780_000_000)
func unconfirmed(_ owner: String?, _ outcome: String? = nil, verified: Bool = false, watch: Bool = true) -> Bool {
  WorkoutOwnerStopPolicy.isUnconfirmed(watchOwned: watch, phase: "completed", hasCutoff: true,
    ownerPhase: owner, stopOutcome: outcome, verified: verified)
}
check(unconfirmed("running"), "optimistic phone completion cannot replace a still-recording Watch owner")
check(unconfirmed("finishing"), "positive finishing state remains pending until owner has stopped")
check(unconfirmed("running", "rejected"), "rejected Stop cannot admit a replacement ride")
check(!unconfirmed("completed"), "confirmed owner end admits next ride without waiting archive transfer")
check(!unconfirmed("running", "applied"), "applied native end acknowledgement is sufficient before status arrives")
check(!unconfirmed(nil, verified: true) && !unconfirmed(nil, watch: false), "verified terminal seal and local owners do not await Watch confirmation")
func activityPhase(_ phase: String = "completed", watch: Bool = true, cutoff: Bool = true,
                   owner: String? = nil, outcome: String? = nil, verified: Bool = false,
                   pendingStop: Bool = false, phoneStopping: Bool = false) -> String {
  WorkoutActivityPhase.resolve(phase, watchOwned: watch, hasCutoff: cutoff, ownerPhase: owner,
    stopOutcome: outcome, verified: verified, pendingStop: pendingStop, phoneStopping: phoneStopping)
}
check(activityPhase(owner: "completed", pendingStop: true) == "completed",
  "confirmed Watch end removes Activity even while Stop acknowledgement and archive are pending")
check(activityPhase(owner: "finishing", outcome: "applied", pendingStop: true) == "completed",
  "applied Stop ends Activity even before the final owner status arrives")
check(activityPhase(owner: "running", verified: true, pendingStop: true) == "completed",
  "verified terminal archive ends Activity despite older owner or transport state")
check(activityPhase(owner: "running", pendingStop: true) == "finishing",
  "unconfirmed Stop retains Activity until owner evidence arrives")
check(activityPhase(owner: "running", outcome: "rejected") == "recoverable",
  "rejected Stop must not hide a potentially active Watch ride")
check(activityPhase(owner: "finishing") == "finishing", "owner still finishing remains visible")
check(activityPhase(watch: false, phoneStopping: true) == "finishing", "phone native Stop retains Activity until confirmed")
for phase in ["idle", "preparing", "running", "paused", "recoverable", "finishing", "completed", "failed"] {
  check(activityPhase(phase, watch: false, cutoff: false) == phase, "phone Activity follows its native phase: \(phase)")
}
check(activityPhase("running", cutoff: false, owner: "running") == "running", "active Watch ride remains visible")
check(activityPhase("paused", cutoff: false, owner: "paused") == "paused", "paused Watch ride remains visible")
let ride = try archive.create(startedAt: date, indoor: true, watchEnabled: true)
let other = try archive.create(startedAt: date, indoor: true, watchEnabled: true)
let token = UUID().uuidString.lowercased()
let pause = try WorkoutActivityRequest(rideID: ride.id, token: token, action: "pause", expectedPhase: "running")
try pause.requireCurrent(rideID: ride.id, token: token, phase: "running", pendingAction: nil)
checks += 1
rejects({ try pause.requireCurrent(rideID: other.id, token: token, phase: "running", pendingAction: nil) }, "old ride cannot control a new ride")
rejects({ try pause.requireCurrent(rideID: ride.id, token: UUID().uuidString.lowercased(), phase: "running", pendingAction: nil) }, "running-paused-running ABA rejects the old displayed token")
rejects({ try pause.requireCurrent(rideID: ride.id, token: token, phase: "paused", pendingAction: nil) }, "phase mismatch rejects a delayed control")
rejects({ try pause.requireCurrent(rideID: ride.id, token: token, phase: "running", pendingAction: "pause") }, "pending controls reject a fresh action")
rejects({ _ = try WorkoutActivityRequest(rideID: ride.id, token: token, action: "toggle", expectedPhase: "running") }, "controls require explicit desired actions")
let resume = try WorkoutActivityRequest(rideID: ride.id, token: UUID().uuidString, action: "resume", expectedPhase: "running")
rejects({ try resume.requireCurrent(rideID: ride.id, token: resume.token, phase: "running", pendingAction: nil) }, "Resume cannot be accepted from running")

enum Fault: Error { case disk }
archive.store.beforeCommitForTesting = {
  check(try control.activityCommand(pause) != nil, "mapping exists inside admission transaction")
  check(try outbox.packets().count == 1, "remote packet is durable in the same admission transaction")
  throw Fault.disk
}
rejects({ _ = try control.admitActivity(pause, remote: true, at: date, options: [:]) }, "commit failure must surface")
archive.store.beforeCommitForTesting = nil
check(try control.activityCommand(pause) == nil, "failed transaction does not consume displayed control")
check(try control.pendingRemote(workoutID: ride.id) == nil, "failed transaction does not leave a stranded remote intent")
check(try outbox.packets().isEmpty, "failed transaction leaves no transport effect")
let command = try control.admitActivity(pause, remote: true, at: date, options: [:])
check(command.originSequence == 1, "rollback preserves contiguous command sequence")
check(try control.activityCommand(pause) == command, "duplicate displayed control resolves to exact native identity")
check(try outbox.packets().map(\.key) == [command.id], "remote command is staged before admission returns")
let conflicting = try WorkoutActivityRequest(rideID: ride.id, token: token, action: "finish", expectedPhase: "running")
rejects({ _ = try control.activityCommand(conflicting) }, "same displayed token cannot be reused for another action")
rejects({ _ = try control.admitActivity(pause, remote: true, at: date, options: [:]) }, "duplicate admission cannot create another origin sequence")

// Exercise recovery after retained command admission but missing in-memory/transport staging.
try outbox.acknowledge(command.id)
let restored = WorkoutControlJournal(store: archive.store)
check(try !restored.restageActivity(command, currentRideID: other.id), "old ride retry cannot restage transport while another ride is selected")
check(try outbox.packets().isEmpty, "old ride replay produces no side effect")
check(try restored.restageActivity(command, currentRideID: ride.id), "pending retry repairs missing staging using the exact durable command")
check(try restored.restageActivity(command, currentRideID: ride.id), "repeated transport repair is idempotent")
check(try outbox.packets().map(\.key) == [command.id], "retries retain exactly one original command packet")
let rejection = WorkoutCommandResult(commandID: command.id, outcome: "rejected", reason: "Owner has ended")
check(try restored.completeRemote(rejection, acknowledgedID: command.id, workoutID: ride.id), "owner rejection settles pending intent")
check(try restored.confirmedRemoteStop(workoutID: ride.id) == nil, "rejection is never evidence that an owner stopped")
try outbox.acknowledge(command.id)
check(try !restored.restageActivity(command, currentRideID: ride.id), "rejected owner action is never resent")
check(try restored.activityCommand(pause) == command, "settled duplicate remains recognized without a new effect")

let finish = try WorkoutActivityRequest(rideID: other.id, token: UUID().uuidString, action: "finish", expectedPhase: "paused")
try finish.requireCurrent(rideID: other.id, token: finish.token, phase: "paused", pendingAction: nil)
let finishCommand = try control.admitActivity(finish, remote: true, at: date, options: [:])
check(finishCommand.action == "stop", "Finish maps to native save/stop, never discard")
_ = try control.observe(workoutID: other.id, owner: "watch", phase: "completed", at: date, health: "saved", cutoff: date)
let restoredBeforeACK = WorkoutControlJournal(store: archive.store)
let completedOwner = try restoredBeforeACK.snapshot(workoutID: other.id)
let pendingEnd = try restoredBeforeACK.pendingRemote(workoutID: other.id)
check(pendingEnd?.commandID == finishCommand.id, "owner completion can precede its exact Stop receipt")
check(WorkoutActivityPhase.resolve("completed", watchOwned: true, hasCutoff: true, ownerPhase: completedOwner?.phase,
  stopOutcome: try restoredBeforeACK.confirmedRemoteStop(workoutID: other.id)?.outcome, verified: false,
  pendingStop: pendingEnd != nil, phoneStopping: false) == "completed",
  "restored completed owner dismisses Activity while the durable Stop transport remains pending")
check(try outbox.packets().contains { $0.key == finishCommand.id }, "presentation does not remove pending transport")
_ = try control.completeRemote(WorkoutCommandResult(commandID: finishCommand.id, outcome: "applied"), acknowledgedID: finishCommand.id, workoutID: other.id)
let restoredStop = try WorkoutControlJournal(store: archive.store).confirmedRemoteStop(workoutID: other.id)
check(restoredStop?.commandID == finishCommand.id && restoredStop?.outcome == "applied", "applied end evidence survives reconstruction before an owner status or archive arrives")
try outbox.acknowledge(finishCommand.id)
check(try !control.restageActivity(finishCommand, currentRideID: other.id), "applied Finish duplicate cannot replay owner effect")

let local = try archive.create(startedAt: date, indoor: true, watchEnabled: false, saveToHealth: false)
_ = try control.observe(workoutID: local.id, owner: "phone", phase: "running", at: date, health: "notRequested")
let localPause = try WorkoutActivityRequest(rideID: local.id, token: UUID().uuidString, action: "pause", expectedPhase: "running")
archive.store.beforeCommitForTesting = {
  check(try control.active(workoutID: local.id)?.action == "pause", "local effect slot exists before mapping transaction commits")
  throw Fault.disk
}
rejects({ _ = try control.admitActivity(localPause, remote: false, at: date, options: [:]) }, "local begin failure rolls back admission")
archive.store.beforeCommitForTesting = nil
check(try control.active(workoutID: local.id) == nil && control.activityCommand(localPause) == nil, "local failure leaves neither token binding nor active effect")
let localCommand = try control.admitActivity(localPause, remote: false, at: date, options: [:])
check(try control.active(workoutID: local.id) == localCommand, "crash before native effect retains an executing command for owner recovery")
check(try !control.restageActivity(localCommand, currentRideID: local.id), "local owner commands can never be sent to Watch")
_ = try control.observe(workoutID: local.id, owner: "phone", phase: "paused", at: date, health: "notRequested", command: localCommand)
check(try control.activityCommand(localPause) == localCommand && control.result(id: localCommand.id)?.outcome == "applied", "completed local duplicate preserves original applied effect")

let fullRide = try archive.create(startedAt: date, indoor: true, watchEnabled: true)
for _ in 0..<WorkoutBoundedOutbox.maximumPackets {
  _ = try outbox.enqueue(["messageId": UUID().uuidString, "kind": "ownerQuery", "workoutId": fullRide.id])
}
let fullRequest = try WorkoutActivityRequest(rideID: fullRide.id, token: UUID().uuidString, action: "pause", expectedPhase: "running")
rejects({ _ = try control.admitActivity(fullRequest, remote: true, at: date, options: [:]) }, "outbox capacity failure after mapping rolls back entire control admission")
check(try control.activityCommand(fullRequest) == nil && control.pendingRemote(workoutID: fullRide.id) == nil, "capacity failure never consumes control or strands command")
check(try outbox.packets().count == WorkoutBoundedOutbox.maximumPackets, "failed admission preserves all existing transport packets")
_ = try archive.store.markWorkoutDeleted(id: other.id)
for _ in 0..<32 {
  if try archive.store.cleanupWorkoutPage(id: other.id).cleanupPhase >= 10 { break }
}
check(try control.confirmedRemoteStop(workoutID: other.id) == nil && control.activityCommand(finish) == nil,
  "deletion prunes both Activity token bindings and retained end acknowledgement")
print("Activity control: \(checks) checks passed")
