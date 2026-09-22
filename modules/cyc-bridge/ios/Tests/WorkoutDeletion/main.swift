import Foundation

var assertions = 0
func check(_ value: Bool, _ message: String) { assertions += 1; if !value { fatalError(message) } }
func rejected(_ body: () throws -> Void, _ message: String) {
  assertions += 1
  do { try body(); fatalError("Expected rejection: " + message) } catch { }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-delete-tests-" + UUID().uuidString)
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root)
let store = archive.store, control = WorkoutControlJournal(store: archive.store), transfer = WorkoutTransferJournal(archive: archive)
let now = Date(timeIntervalSince1970: 1_800_000_000)
func create(_ watch: Bool = false) throws -> String { try archive.create(startedAt: now, indoor: true, watchEnabled: watch).id }
func count(_ table: String, _ id: String) throws -> Int64 {
  try store.read { try $0.scalarInt("SELECT count(*) FROM \(table) WHERE collection_id=?", [.text(id)]) ?? 0 }
}
func bytes(_ namespace: String, _ key: String) throws -> Data? { try store.read { try $0.get(namespace: namespace, key: key) } }
func event(_ id: String, sequence: Int, eventID: String = UUID().uuidString.lowercased()) throws -> WorkoutEvent {
  try WorkoutEvent(workoutId: id, kind: "telemetry", source: "cyc", timestamp: now.addingTimeInterval(Double(sequence)),
    elapsedSeconds: Double(sequence), payload: ["humanPowerW": .integer(123), "cadenceRpm": .integer(81)], eventId: eventID)
}

let deletedID = try create(true), retainedID = try create()
let sharedID = UUID().uuidString.lowercased(), shared = try event(deletedID, sequence: 0, eventID: sharedID)
let liveID = UUID().uuidString.lowercased()
try store.createCollection(id: liveID, kind: "live", startedAt: WorkoutCoding.timestamp(now))
try archive.append(shared)
try store.appendBatch([event(liveID, sequence: 0, eventID: sharedID)])
let insertion = WorkoutHealthInsertionJournal(archive: archive)
try insertion.prepare([shared]); try insertion.record([shared], outcome: "applied")
let foreignID = UUID().uuidString.lowercased(), foreignHere = try event(deletedID, sequence: 1, eventID: foreignID)
let foreignOwner = try event(retainedID, sequence: 1, eventID: foreignID)
try archive.append(foreignHere); try archive.append(foreignOwner)
try insertion.prepare([foreignOwner]); try insertion.record([foreignOwner], outcome: "applied")
let foreignReceipt = try bytes("health-insertion-intents", foreignID)
for offset in stride(from: 2, to: 770, by: 256) {
  _ = try archive.appendBatch((offset..<min(offset + 256, 770)).map { try event(deletedID, sequence: $0) })
}
for _ in 0..<260 { try archive.update(id: deletedID, healthKitState: "saved") }
try archive.finish(id: deletedID, endedAt: now.addingTimeInterval(770), finalPhase: "completed")
try transfer.register(id: deletedID, producer: "cyc")
let start = try control.createRemote(workoutID: deletedID, origin: "phone", action: "start", at: now)
_ = try control.prepare(start)
let chunk = try transfer.nextChunk(id: deletedID, producer: "cyc", after: 0)!
let incoming = WorkoutCoding.dictionary(chunk.manifest)
let incomingMetadata: [String: Any] = ["kind": "workoutChunk", "workoutId": deletedID,
  "chunkIdentity": chunk.manifest.identity, "manifest": incoming]
let incomingFile = root.appendingPathComponent("incoming-test.plchunk"); try chunk.data.write(to: incomingFile)
let inbox = try WorkoutChunkInbox(root: root.appendingPathComponent("inbox"), store: store)
check(try inbox.stage(file: incomingFile, metadata: incomingMetadata), "old chunk is staged before deletion")
let claimed = try inbox.claim()!
let exports = try archive.directory(id: deletedID)
let staging = exports.appendingPathComponent("original-" + UUID().uuidString).appendingPathComponent("PowerLog-original")
try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
for index in 0..<180 { try Data("retained raw staging".utf8).write(to: staging.appendingPathComponent("events-\(index).jsonl")) }
let outside = root.appendingPathComponent("outside.txt"); try Data("keep".utf8).write(to: outside)
try FileManager.default.createSymbolicLink(at: exports.appendingPathComponent("outside-link"), withDestinationURL: outside)
let current: [String: Any] = ["id": deletedID, "phase": "completed", "useWatch": true]
try store.transaction { try $0.put(namespace: "phone-current", key: "workout", value: JSONSerialization.data(withJSONObject: current)) }
check(try store.historyDeletion() == nil, "a catalog without deletions has no invalidation marker")
store.beforeCommitForTesting = { throw WorkoutDataError.invalid("marker crash") }
rejected({ _ = try store.markWorkoutDeleted(id: deletedID, watchRequired: true) }, "failed marker commit rolls back hiding and checkpoint removal")
store.beforeCommitForTesting = nil
check(try !store.isWorkoutDeleted(id: deletedID) && bytes("phone-current", "workout") != nil, "marker failure leaves prior visible state intact")
check(try store.historyDeletion() == nil, "failed deletion cannot publish a catalog invalidation")
let deletion = try store.markWorkoutDeleted(id: deletedID, watchRequired: true)
check(try store.historyDeletion() == WorkoutHistoryDeletion(revision: deletion.messageID, deletedWorkoutID: deletedID), "deletion and typed catalog identity commit together")
check(try bytes("phone-current", "workout") == nil, "selected terminal checkpoint clears atomically with deletion")
check(try archive.list().allSatisfy { $0.id != deletedID }, "deleted ride hides before physical cleanup")
check(try count("collection_memberships", deletedID) == 770, "logical deletion does not perform a long cleanup transaction")
check(try store.markWorkoutDeleted(id: deletedID).messageID == deletion.messageID, "repeated deletion retains original durable message identity")
check(try store.historyDeletion()?.revision == deletion.messageID, "repeated deletion does not invalidate the catalog again")
rejected({ _ = try archive.metadata(id: deletedID) }, "metadata hidden")
do {
  _ = try archive.metadata(id: deletedID)
  fatalError("Expected typed deleted read")
} catch let error as PowerLogStorageError {
  check(error.bridgeCode == "ERR_RIDE_DELETED", "real archive read exposes the stable deletion error code")
}
check(PowerLogStorageError.busy.bridgeCode == nil && PowerLogStorageError.invalid("This ride was deleted from Power Log.").bridgeCode == nil,
  "ordinary storage failures never become deletion by matching their message")
rejected({ _ = try archive.pageEvents(id: deletedID) }, "chart/event read hidden")
rejected({ _ = try archive.create(id: deletedID, startedAt: now, indoor: true, watchEnabled: true) }, "late creation fenced")
rejected({ try archive.append(event(deletedID, sequence: 999)) }, "late sample fenced")
rejected({ _ = try control.prepare(start) }, "late START fenced")
rejected({ _ = try control.observe(workoutID: deletedID, owner: "watch", phase: "running", at: now, health: "pending") }, "late owner snapshot fenced")
rejected({ _ = try transfer.receive(chunk) }, "late archive chunk fenced")
rejected({ try transfer.register(id: deletedID, producer: "watch") }, "late empty producer registration fenced")
rejected({ try insertion.prepare([shared]) }, "late Health intent fenced")
rejected({ _ = try archive.directory(id: deletedID) }, "later export directory cannot be recreated")
check(try inbox.stage(file: incomingFile, metadata: incomingMetadata), "deleted incoming file is intentionally consumed")
let before = try count("collection_memberships", deletedID)
store.beforeCommitForTesting = { throw WorkoutDataError.invalid("cleanup crash") }
rejected({ _ = try store.cleanupWorkoutPage(id: deletedID) }, "cleanup transaction rollback")
store.beforeCommitForTesting = nil
check(try count("collection_memberships", deletedID) == before, "failed cleanup page removes no source prefix")
_ = try store.cleanupWorkoutPage(id: deletedID)
check(try count("collection_memberships", deletedID) == before - 128, "one cleanup page removes at most128 memberships")
let peakPages = try store.read { try $0.scalarInt("PRAGMA page_count")! }
var steps = 0, cacheCalls = 0
while true {
  // Reconstruct after every page: every stage/cursor/file traversal is durable.
  let restored = try WorkoutArchive(rootURL: root)
  let done = try restored.cleanupDeletedWorkoutPage(id: deletedID) { cacheCalls += 1; return cacheCalls > 1 }
  steps += 1; if done { break }; check(steps < 1000, "bounded cleanup converges after restart")
}
check(cacheCalls == 2, "busy analytical cache delays final cleanup completion until eviction")
check(try store.workoutDeletion(id: deletedID)?.cleanupComplete == true, "complete cleanup remains durably acknowledged locally")
check(try store.read { try $0.scalarInt("SELECT count(*) FROM collections WHERE id=?", [.text(deletedID)]) } == 0, "target collection reclaimed")
check(try count("collection_memberships", liveID) == 1 && archive.pageEvents(id: retainedID).count == 1, "live and other workout originals survive")
check(try bytes("health-insertion-intents", sharedID) == nil && bytes("health-insertion-results", sharedID) == nil, "deleted Health receipts disappear even when live shares the original")
check(try bytes("health-insertion-intents", foreignID) == foreignReceipt && bytes("health-insertion-results", foreignID) != nil, "foreign encoded Health association remains intact")
check(try control.command(id: start.id) == nil && control.result(id: start.id) == nil, "command and associated receipt bookkeeping reclaimed")
check(!FileManager.default.fileExists(atPath: exports.path) && !FileManager.default.fileExists(atPath: inbox.url(claimed).path), "nested interrupted exports and staged chunks reclaimed")
check(try String(contentsOf: outside, encoding: .utf8) == "keep", "cleanup never follows owned symlink into another recording")
try inbox.finish(claimed, success: false)
check(try inbox.pendingCount == 0, "late failed import completion cannot restore deleted retry inbox")
check(try store.read { try $0.scalarInt("PRAGMA freelist_count")! } > 0, "deleted pages are reusable without VACUUM")
let replacement = try create()
_ = try archive.appendBatch((0..<256).map { try event(replacement, sequence: $0) })
check(try store.read { try $0.scalarInt("PRAGMA page_count")! } <= peakPages + 16, "new capture reuses reclaimed database pages")
rejected({ _ = try transfer.receive(chunk) }, "deleted originals cannot resurrect after completed cleanup")
check(try store.historyDeletion()?.revision == deletion.messageID, "cleanup does not remove or advance the deletion signal")

// Historical deletion changes the catalog without changing the selected ride.
let historicalID = try create(true)
try archive.finish(id: historicalID, endedAt: now.addingTimeInterval(1), finalPhase: "completed")
let retainedCurrent = try JSONSerialization.data(withJSONObject: ["id": retainedID, "phase": "running"])
try store.transaction { try $0.put(namespace: "phone-current", key: "workout", value: retainedCurrent) }
let historicalDeletion = try store.markWorkoutDeleted(id: historicalID, watchRequired: true)
check(try store.historyDeletion() == WorkoutHistoryDeletion(revision: historicalDeletion.messageID, deletedWorkoutID: historicalID), "historical discard invalidates its catalog identity")
check(try bytes("phone-current", "workout") == retainedCurrent, "historical deletion leaves the active current ride unchanged")
_ = try store.markWorkoutDeleted(id: deletedID)
check(try store.historyDeletion()?.revision == historicalDeletion.messageID, "late duplicate deletion cannot replace the newer catalog marker")
check(try WorkoutArchive(rootURL: root).store.historyDeletion() == store.historyDeletion(), "catalog invalidation survives archive reconstruction")
check(try store.read { try $0.scalarInt("SELECT count(*) FROM durable_records WHERE namespace='catalog-state'") } == 1,
  "deletion invalidation occupies one fixed record rather than a growing ID list")

// The original delete identity is independent of ordered owner commands and survives an offline restart.
let outbox = WorkoutBoundedOutbox(store: store)
let healthyPacket: [String: Any] = ["kind": "ownerQuery", "workoutId": retainedID, "messageId": UUID().uuidString.lowercased()]
check(try outbox.enqueue(healthyPacket), "healthy traffic coexists with deletion work")
var deletes: [WorkoutDeletionRecord] = []
for _ in 0..<80 { deletes.append(try store.markWorkoutDeleted(id: UUID().uuidString.lowercased(), watchRequired: true)) }
let first = deletes[0]
check(try !store.acknowledgeWorkoutDeletion(id: first.id, messageID: UUID().uuidString, deleted: true), "foreign message cannot settle deletion")
check(try !store.acknowledgeWorkoutDeletion(id: retainedID, messageID: first.messageID, deleted: true), "foreign workout cannot settle deletion")
check(try store.acknowledgeWorkoutDeletion(id: first.id, messageID: first.messageID, deleted: false, now: 1), "deferred delivery records a retry")
check(try store.workoutDeletion(id: first.id)?.watchAcknowledged == false, "deferred is not final deletion receipt")
var cursor = "", passes = 0
while try deletes.contains(where: { try store.workoutDeletion(id: $0.id)?.watchAcknowledged != true }) {
  let page = try store.deletionPage(after: cursor, limit: 8); cursor = page.last?.id ?? ""
  for pending in page where pending.watchRequired && !pending.watchAcknowledged { _ = try outbox.enqueue(pending.packet) }
  for packet in try outbox.packets().compactMap({ try JSONSerialization.jsonObject(with: $0.value) as? [String: Any] }).filter({ $0["kind"] as? String == "deleteWorkout" }).prefix(2) {
    let id = packet["workoutId"] as! String, message = packet["messageId"] as! String
    _ = try store.acknowledgeWorkoutDeletion(id: id, messageID: message, deleted: true)
    try outbox.acknowledge(message)
  }
  passes += 1; check(passes < 200, "more offline deletions than outbox capacity converge with bounded rotation")
}
check(try outbox.packets().contains { $0.key == healthyPacket["messageId"] as? String }, "healthy outbox item retained during delete backlog")
check(try !store.acknowledgeWorkoutDeletion(id: first.id, messageID: first.messageID, deleted: false), "obsolete deferred reply cannot reopen completed deletion")
let request = try WorkoutDeletionRequest(first.packet)
check(request.acknowledgement(deleted: true)["acknowledgedMessageId"] as? String == first.messageID, "Watch deletion ACK preserves original message identity")
for phase in ["running", "paused", "preparing", "recoverable", "finishing"] {
  check(!WorkoutDeletionPolicy.phone(phase: phase, selectedPhase: nil, healthBusy: false, pendingAction: false, backgroundBusy: false), "phone rejects unsafe phase " + phase)
}
check(WorkoutDeletionPolicy.phone(phase: "failed", selectedPhase: "failed", healthBusy: false, pendingAction: false, backgroundBusy: false), "canceled zero-duration attempt is deletable")
check(!WorkoutDeletionPolicy.phone(phase: "completed", selectedPhase: nil, healthBusy: true, pendingAction: false, backgroundBusy: false), "native Health work blocks deletion")
check(!WorkoutDeletionPolicy.phone(phase: "completed", selectedPhase: nil, healthBusy: false, pendingAction: true, backgroundBusy: false), "pending command blocks deletion")
check(!WorkoutDeletionPolicy.phone(phase: "completed", selectedPhase: nil, healthBusy: false, pendingAction: false, backgroundBusy: true), "active target file job blocks deletion")
check(!WorkoutDeletionPolicy.watch(targetID: deletedID, nativeID: deletedID, probeResolved: true, busy: false), "Watch never deletes active native owner")
check(!WorkoutDeletionPolicy.watch(targetID: deletedID, nativeID: nil, probeResolved: false, busy: false), "native query failure is not absence proof")
check(WorkoutDeletionPolicy.watch(targetID: deletedID, nativeID: nil, probeResolved: true, busy: false), "fresh later absence permits retry after active owner ends")
check(WorkoutDeletionPolicy.watch(targetID: deletedID, nativeID: retainedID, probeResolved: true, busy: false), "different native owner can continue unchanged")
var probe = WorkoutDeletionProbeGate()
check(probe.begin(), "first native deletion probe admitted")
for _ in 0..<100 { check(!probe.begin(), "duplicate held deletion probe deferred before native query") }
probe.finish(); check(probe.begin(), "finished native probe permits fresh retry")
// Both production workers remove committed deletions before handing off their active job.
// The next healthy identity must be admitted, not merely remain somewhere in pending.
for worker in ["archive", "historical Health"] {
  var work = WorkoutBoundedWorkQueue()
  check(work.request("A"), worker + " admits active A")
  check(!work.request(deletedID) && !work.request("C"), worker + " queues deleted B then healthy C")
  work.removePending(deletedID)
  work.removePending(deletedID)
  check(work.active == "A" && work.pending == ["C"], worker + " repeated deletion only removes target pending work")
  if let next = work.finish("A") {
    check(next == "C" && work.request(next) && work.active == "C", worker + " starts healthy C on actual completion handoff")
  } else { check(false, worker + " must hand off to C") }
  work.removePending("C")
  check(work.active == "C", worker + " pending removal cannot interrupt active work")
}
print("Workout deletion lifecycle, replay, cleanup and ownership: \(assertions) assertions passed")
