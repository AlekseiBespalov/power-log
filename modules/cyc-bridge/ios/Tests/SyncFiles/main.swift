import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-sync-files-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let journal = try WatchWorkoutJournal(rootURL: root)
let sender = WorkoutChunkSender(archive: journal.archive)
let start = Date(timeIntervalSince1970: 1_700_000_000)
func makeRide() throws -> WorkoutChunk {
  let id = UUID().uuidString.lowercased()
  try journal.create(id: id, metadata: ["workoutId": id, "startedAt": WorkoutCoding.timestamp(start), "indoor": true, "phase": "running"])
  let events = try (0..<128).map { index in
    try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(Double(index) / 8),
      elapsedSeconds: Double(index) / 8, payload: ["heartRateBpm": .number(Double(80 + index % 30))])
  }
  _ = try journal.archive.appendBatch(events)
  return try sender.prepare(id: id)!
}
var old: [WorkoutChunk] = []
for _ in 0..<8 { let chunk = try makeRide(); _ = try journal.stage(chunk); old.append(chunk) }
var referenced = Set(old.map { $0.manifest.identity })
let fresh = try makeRide()
var cancellations: [String] = []
check(try !journal.reserveStaging(for: fresh, priorityID: fresh.manifest.workoutID,
  referenced: { referenced }, nativeCount: { referenced.count }, cancel: { cancellations.append($0) }),
  "A cancellation request cannot permit removal while native still references every file")
check(try journal.stagedChunks().count == 8, "Referenced files survive deferred cancellation")
check(!cancellations.isEmpty, "Former active backlog is actually asked to relinquish capacity")
check(try journal.reserveStaging(for: fresh, priorityID: fresh.manifest.workoutID,
  referenced: { referenced }, nativeCount: { referenced.count }, cancel: { referenced.remove($0) }),
  "New active ride reclaims capacity after native cancellation is observable")
let freshURL = try journal.stage(fresh)
check(try journal.stagedChunks().count == 8, "Active admission stays within eight files")
let retained = Set(try journal.stagedChunks().map(\.identity))
let displaced = old.first { !retained.contains($0.manifest.identity) }!
check(try sender.acknowledgedSequence(id: displaced.manifest.workoutID) == 0, "Eviction never acknowledges data")
check(try sender.pending(id: displaced.manifest.workoutID) == displaced.manifest, "Eviction retains immutable pending range")
check(try sender.prepare(id: displaced.manifest.workoutID)?.data == displaced.data, "Displaced file reconstructs identically from originals")
check(try journal.archive.sourceProgress(id: displaced.manifest.workoutID, producer: "watch").count == 128, "No source data removed")

referenced.insert(fresh.manifest.identity)
check(try sender.acknowledge(id: fresh.manifest.workoutID, producer: "watch", identity: fresh.manifest.identity,
  lastSequence: fresh.manifest.lastSequence, contentHash: fresh.manifest.contentHash), "Phone commit receipt advances pending chunk")
try journal.reconcileChunks(referenced: referenced)
check(FileManager.default.fileExists(atPath: freshURL.path), "ACK before native completion retains referenced file")
referenced.remove(fresh.manifest.identity)
try journal.reconcileChunks(referenced: referenced)
check(!FileManager.default.fileExists(atPath: freshURL.path), "Native completion after ACK reclaims file")
try journal.reconcileChunks(referenced: referenced)
check(try journal.stagedChunks().count == 7, "Duplicate completion is idempotent")
check(try !journal.reserveStaging(for: displaced, priorityID: nil, referenced: { referenced }, nativeCount: { referenced.count }, cancel: { _ in }),
  "Idle historical work preserves the future active slot")

let finished = old.first { retained.contains($0.manifest.identity) }!
referenced.remove(finished.manifest.identity)
try journal.reconcileChunks(referenced: referenced)
check(try journal.stagedChunks().contains { $0.identity == finished.manifest.identity }, "Native success before phone ACK retains retryable file")
let retry = try sender.prepare(id: finished.manifest.workoutID)!
_ = try journal.stage(retry)
referenced.insert(finished.manifest.identity)
check(try sender.acknowledge(id: finished.manifest.workoutID, producer: "watch", identity: finished.manifest.identity,
  lastSequence: finished.manifest.lastSequence, contentHash: finished.manifest.contentHash), "Receipt matches reconstructed retry")
try journal.reconcileChunks(referenced: referenced)
check(try journal.stagedChunks().contains { $0.identity == finished.manifest.identity }, "Late old completion cannot remove a newer native reference")
referenced.remove(finished.manifest.identity)
try journal.reconcileChunks(referenced: referenced)
check(try journal.reserveStaging(for: displaced, priorityID: nil, referenced: { referenced }, nativeCount: { referenced.count }, cancel: { _ in }),
  "Historical work becomes admissible after genuine completion")
_ = try journal.stage(try sender.prepare(id: displaced.manifest.workoutID)!)
check(try journal.stagedChunks().count == 7, "Historical recovery remains bounded")
print("Watch file lifecycle: \(assertions) assertions passed; ACK/native ordering, active transition, reference safety and exact reconstruction")

let priorityID = fresh.manifest.workoutID
var priorityMetadata: [String: Any] = ["workoutId": priorityID, "phase": "completed", "endedAt": WorkoutCoding.timestamp(start.addingTimeInterval(20))]
check(WatchWorkoutJournal.syncPriorityID(metadata: priorityMetadata, nativeBusy: false) == priorityID, "Async finalization keeps the just-finished ride's reserved slot")
priorityMetadata["sealRevision"] = "1"
check(WatchWorkoutJournal.syncPriorityID(metadata: priorityMetadata, nativeBusy: false) == priorityID, "Unacknowledged final seal retains priority after native session ends")
priorityMetadata["acknowledgedSealRevision"] = "1"
check(WatchWorkoutJournal.syncPriorityID(metadata: priorityMetadata, nativeBusy: false) == nil, "Matching final receipt releases the reserve")
priorityMetadata["archiveDirty"] = true
check(WatchWorkoutJournal.syncPriorityID(metadata: priorityMetadata, nativeBusy: false) == priorityID, "New final observations restore priority")

var work = WorkoutBoundedWorkQueue()
var admitted: [String] = []
for page in 0..<3 {
  check(work.request("current"), "Current native ride starts one bounded preparation")
  _ = work.request("current")
  for index in 0..<8 { _ = work.request("history-\(page)-\(index)") }
  var finished = "current"
  while let next = work.finish(finished) {
    admitted.append(next)
    check(work.request(next), "Worker handoff retains one active operation")
    finished = next
  }
}
check(Set(admitted).count == 24, "Every item in three full catalog pages eventually runs despite current invalidation")
check(admitted.contains("history-0-7") && admitted.contains("history-2-7"), "No eighth historical peer is deterministically dropped")

let sealID = displaced.manifest.workoutID
let source = try journal.transfer.source(id: sealID, producer: "watch")
let seal = WorkoutSeal(workoutID: sealID, sealRevision: 1, collectionRevision: 1, ownerRevision: 1,
  stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(20)), healthOutcome: "saved", requirements: [:], sources: [source])
_ = try journal.transfer.accept(seal: seal)
var saved = try journal.metadata(id: sealID)
saved["phase"] = "completed"; saved["sealRevision"] = "1"; saved["endedAt"] = seal.stopCutoff; saved["archiveDirty"] = false
try journal.save(id: sealID, metadata: saved)
journal.beforeSealReceiptCommit = { throw WorkoutDataError.invalid("injected receipt projection failure") }
do { _ = try journal.acknowledgeSeal(id: sealID, revision: 1); fatalError("Fault must escape") } catch { }
check(try WorkoutSealSubmissionJournal(archive: journal.archive).shouldSubmit(id: sealID, revision: 1), "Projection failure rolls back authoritative ACK and retains retry eligibility")
check(try journal.metadata(id: sealID)["acknowledgedSealRevision"] == nil, "Failed final receipt does not partially update metadata")
journal.beforeSealReceiptCommit = nil
check(try journal.acknowledgeSeal(id: sealID, revision: 1)?["acknowledgedSealRevision"] as? String == "1", "Final ACK and visible receipt commit together")
check(try !WorkoutSealSubmissionJournal(archive: journal.archive).shouldSubmit(id: sealID, revision: 1), "Successful receipt stops final-seal retransmission")
check(try journal.acknowledgeSeal(id: sealID, revision: 2) == nil, "Unknown seal ACK cannot update receipt projection")
print("Watch native scheduling and receipt policy: \(assertions) total assertions passed")

var phoneState: [String: Any] = ["id": priorityID, "phase": "completed", "useWatch": true,
  "sealRevision": 1, "verifiedSealRevision": 0, "finalizationState": "pending"]
check(WorkoutSyncPriority.phone(phoneState) == priorityID, "Phone reserves final-tail admission after completed owner status")
phoneState["verifiedSealRevision"] = 1; phoneState["finalizationState"] = "complete"
check(WorkoutSyncPriority.phone(phoneState) == nil, "Phone releases current reserve after exact archive verification")
phoneState["sealRevision"] = 2
check(WorkoutSyncPriority.phone(phoneState) == priorityID, "New unverified seal restores final-tail priority")
phoneState["useWatch"] = false
check(WorkoutSyncPriority.phone(phoneState) == nil, "Phone-only ride never claims Watch inbox priority")

let seal2 = WorkoutSeal(workoutID: sealID, sealRevision: 2, collectionRevision: 2, ownerRevision: 1,
  stopCutoff: seal.stopCutoff, healthOutcome: "saved", requirements: ["lateHealth": "pending"], sources: [source])
journal.beforeSealPublicationCommit = { throw WorkoutDataError.invalid("injected publication projection failure") }
do { _ = try journal.publishSeal(seal2, metadata: journal.metadata(id: sealID)); fatalError("Fault must escape") } catch { }
check(try journal.transfer.currentSeal(id: sealID)?.sealRevision == 1, "Failed publication rolls back current seal")
check(try journal.metadata(id: sealID)["sealRevision"] as? String == "1", "Failed publication retains matching metadata projection")
journal.beforeSealPublicationCommit = nil
let published = try journal.publishSeal(seal2, metadata: journal.metadata(id: sealID))
check(published["sealRevision"] as? String == "2", "New seal and visible revision publish atomically")
check(try journal.acknowledgeSeal(id: sealID, revision: 1) == nil, "Old receipt cannot acknowledge newly published seal")
check(try journal.acknowledgeSeal(id: sealID, revision: 2)?["sealRevision"] as? String == "2", "Final receipt retains same published revision")
check(WatchWorkoutJournal.syncPriorityID(metadata: try journal.metadata(id: sealID), nativeBusy: false) == nil, "Published and acknowledged revisions cannot leave completed ride pending")
print("Watch and phone finalization: \(assertions) total assertions passed")
