import Foundation

var assertions = 0
func check(_ value: Bool, _ message: String) { assertions += 1; if !value { fatalError(message) } }
func rejects(_ message: String, _ body: () throws -> Void) {
  do { try body(); check(false, message) } catch { check(true, message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-continuous-sync-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: root) }
let start = Date(timeIntervalSince1970: 1_780_000_000), startedAt = WorkoutCoding.timestamp(start)
func makeArchive(_ name: String) throws -> WorkoutArchive {
  try WorkoutArchive(rootURL: root.appendingPathComponent(name), store: PowerLogStore.shared(databaseURL: root.appendingPathComponent(name + ".sqlite3")))
}
let source = try makeArchive("source"), destination = try makeArchive("destination")
let sender = WorkoutChunkSender(archive: source), receiver = WorkoutTransferJournal(archive: destination)
let transfer = WorkoutTransferJournal(archive: source)
func event(_ id: String, _ index: Int, payload: [String: WorkoutJSON]? = nil) throws -> WorkoutEvent {
  try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(Double(index) / 8),
    elapsedSeconds: Double(index) / 8, payload: payload ?? ["heartRateBpm": .number(100.125 + Double(index % 80)), "rawQuantity": .integer(Int64(index))])
}
func ride(_ archive: WorkoutArchive = source, watch: Bool = true) throws -> String {
  try archive.create(startedAt: start, indoor: true, watchEnabled: watch).id
}
func append(_ id: String, from: Int, count: Int) throws -> [WorkoutEvent] {
  let events = try (from..<(from + count)).map { try event(id, $0) }
  _ = try source.appendBatch(events, producer: "watch", firstSequence: Int64(from + 1)); return events
}
func ack(_ chunk: WorkoutChunk, using sender: WorkoutChunkSender = sender) throws -> Bool {
  let m = chunk.manifest
  return try sender.acknowledge(id: m.workoutID, producer: m.producer, identity: m.identity, lastSequence: m.lastSequence, contentHash: m.contentHash)
}
func metadata(_ chunk: WorkoutChunk) -> [String: Any] { WorkoutChunkWire.metadata(chunk: chunk, startedAt: startedAt, indoor: true) }
func chunk(_ id: String, first: Int64 = 1, count: Int = 1, payload: [String: WorkoutJSON]? = nil) throws -> WorkoutChunk {
  try WorkoutChunkCodec.encode(workoutID: id, producer: "watch", firstSequence: first,
    events: (0..<count).map { try event(id, Int(first) + $0, payload: payload) })
}

let id = try ride()
var originals = try append(id, from: 0, count: 128)
let first = try sender.prepare(id: id)!
check(first.manifest.count == 128 && first.manifest.firstSequence == 1, "full-rate originals use the production 128-record bound")
originals += try append(id, from: 128, count: 32)
check(try sender.prepare(id: id)!.manifest == first.manifest, "new capture cannot change a pending immutable range")
let restarted = WorkoutChunkSender(archive: try WorkoutArchive(rootURL: source.rootURL, store: source.store))
check(try restarted.prepare(id: id)!.data == first.data, "sender restart reconstructs the exact frozen chunk")
let live = try WorkoutChunkWire.encode(chunk: first, startedAt: startedAt, indoor: true)!
check(live.count <= 60_000, "whole JSON and base64 live envelope is bounded")
let decoded = try WorkoutChunkWire.decode(live)
check(decoded.chunk.data == first.data && decoded.chunk.manifest == first.manifest, "wire codec preserves compressed originals and manifest")
rejects("oversize wire rejected before parsing") { _ = try WorkoutChunkWire.decode(Data(repeating: 0, count: 60_001)) }
var badWire = try JSONSerialization.jsonObject(with: live) as! [String: Any]
badWire["workoutId"] = UUID().uuidString.lowercased()
rejects("outer identity must match the compressed manifest") { _ = try WorkoutChunkWire.decode(JSONSerialization.data(withJSONObject: badWire)) }
check(try !sender.acknowledge(id: id, producer: "cyc", identity: first.manifest.identity, lastSequence: 128, contentHash: first.manifest.contentHash), "another producer cannot advance Watch originals")
check(try !sender.acknowledge(id: id, producer: "watch", identity: first.manifest.identity, lastSequence: 128, contentHash: String(repeating: "0", count: 64)), "forged hash cannot advance progress")
check(try !sender.acknowledge(id: id, producer: "watch", identity: first.manifest.identity, lastSequence: 129, contentHash: first.manifest.contentHash), "wrong range cannot advance progress")
check(try sender.acknowledgedSequence(id: id) == 0, "transport attempts and forged ACKs retain unacknowledged progress")

let attempt0 = try sender.attempt(id: id, reachable: true, liveFits: true, hasOutstandingFile: false, now: 100)
check(attempt0.sendLive && !attempt0.sendFile, "reachable first delivery uses WC data")
let attempt1 = try restarted.attempt(id: id, reachable: true, liveFits: true, hasOutstandingFile: false, now: 101)
check(!attempt1.sendLive && !attempt1.sendFile, "recreated sender respects persisted retry clock")
check(try sender.attempt(id: id, reachable: true, liveFits: true, hasOutstandingFile: false, now: 103).sendLive, "missing callback retries independently after three seconds")
check(try !sender.attempt(id: id, reachable: true, liveFits: true, hasOutstandingFile: false, now: 106).sendLive, "backoff prevents per-heartbeat immediate duplicate")
let fallback = try sender.attempt(id: id, reachable: true, liveFits: true, hasOutstandingFile: false, now: 115)
check(fallback.sendFile, "lost live ACK admits immutable background fallback at fifteen seconds")
check(try !sender.attempt(id: id, reachable: false, liveFits: true, hasOutstandingFile: true, now: 120).sendFile, "native outstanding reference suppresses duplicate file submission")
try sender.deferFile(id: id)
check(try sender.attempt(id: id, reachable: false, liveFits: true, hasOutstandingFile: false, now: 120).sendFile, "capacity denial returns file retry to bounded first delay")

// Real inbox admission, failed original transaction, restart, duplicate delivery and lost ACK.
let inbox = try WorkoutChunkInbox(root: root.appendingPathComponent("inbox"), store: destination.store)
inbox.setPreferredWorkoutID(id)
check(try inbox.stage(data: decoded.chunk.data, metadata: decoded.metadata), "Data path stages a live chunk")
let incomingFile = root.appendingPathComponent("native-incoming.plchunk")
try first.data.write(to: incomingFile)
check(try inbox.stage(file: incomingFile, metadata: metadata(first)), "temporary file uses exactly the same admission and duplicate ledger as Data")
for _ in 0..<20 { check(try inbox.stage(data: first.data, metadata: metadata(first)), "duplicate admission reuses its staged identity") }
check(try inbox.pendingCount == 1, "duplicate storms allocate one row/file")
let claimed = try inbox.claim(now: 1)!
check(try inbox.claim(now: 1) == nil, "production inbox permits one import claim")
destination.store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected original commit failure") }
rejects("original failure propagates without receipt") { _ = try receiver.receiveWatch(first, startedAt: startedAt, indoor: true) }
destination.store.beforeCommitForTesting = nil
check(try destination.store.read { try $0.get(namespace: "chunk-receipts", key: first.manifest.identity) } == nil, "failed import never creates an immutable receipt")
try inbox.finish(claimed, success: false, now: 1)
check(try inbox.claim(now: 5) == nil, "failed import releases claim but respects due time")
try FileManager.default.removeItem(at: inbox.url(claimed))
let restartedInbox = try WorkoutChunkInbox(root: inbox.root, store: destination.store)
check(try restartedInbox.stage(data: first.data, metadata: metadata(first)), "duplicate replay heals missing staging file after restart")
check(try Data(contentsOf: restartedInbox.url(claimed)) == first.data, "healed staging preserves exact immutable bytes")
let retried = try restartedInbox.claim(now: 6)!
check(try receiver.receiveWatch(first, startedAt: startedAt, indoor: true), "retry commits real originals and receipt")
let provisional = try destination.metadata(id: id)
check(provisional.watchEnabled && provisional.endedAt == nil && provisional.sealRevision == nil, "active chunk creates provisional collection without a fabricated finish")
check(try destination.store.read { try $0.get(namespace: "owner-snapshots", key: id) } == nil, "chunk ingestion never creates recording ownership")
try restartedInbox.finish(retried, success: true, now: 6)
check(try !receiver.receiveWatch(first, startedAt: startedAt, indoor: true), "lost ACK replay hits committed receipt without original duplication")
check(try restarted.prepare(id: id)!.manifest == first.manifest, "lost ACK retains same pending sender identity")
source.store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected ACK commit failure") }
rejects("ACK commit failure cannot partially advance") { _ = try ack(first) }
source.store.beforeCommitForTesting = nil
check(try sender.acknowledgedSequence(id: id) == 0 && sender.pending(id: id) == first.manifest, "ACK cursor and pending manifest roll back atomically")
check(try ack(first, using: restarted), "hash-bound receipt advances the actual producer cursor")
check(try !ack(first), "duplicate stale ACK does not mutate current state")
let tail = try sender.prepare(id: id)!
check(tail.manifest.firstSequence == 129 && tail.manifest.count == 32, "ACK exposes only the new captured tail")
check(try !ack(first), "old ACK cannot retire new pending tail")
_ = try receiver.receiveWatch(tail, startedAt: startedAt, indoor: true); _ = try ack(tail)
check(try sender.prepare(id: id) == nil, "all captured originals acknowledged while ride is still active")
var received: [WorkoutEvent] = []; var cursor: Int64 = 0
while true {
  let rows = try destination.pageEvents(id: id, afterSequence: cursor, limit: 128, producer: "watch")
  if rows.isEmpty { break }; received += rows.map(\.event); cursor = rows.last!.sequence
}
check(received == originals, "actual sender/receiver retains original IDs, times, fractions and payloads")
for rate in [2, 4] {
  let rateID = try ride()
  let rateOriginals = try (0..<(rate * 10)).map { index -> WorkoutEvent in
    var sample = try event(rateID, index)
    sample.timestamp = WorkoutCoding.timestamp(start.addingTimeInterval(Double(index) / Double(rate)))
    sample.elapsedSeconds = Double(index) / Double(rate)
    return sample
  }
  _ = try source.appendBatch(rateOriginals, producer: "watch", firstSequence: 1)
  let rateChunk = try sender.prepare(id: rateID)!
  let ratePacket = try WorkoutChunkWire.decode(WorkoutChunkWire.encode(chunk: rateChunk, startedAt: startedAt, indoor: true)!)
  _ = try receiver.receiveWatch(ratePacket.chunk, startedAt: startedAt, indoor: true)
  check(try ack(rateChunk), "\(rate) Hz original stream receives a hash-bound ACK")
  check(try destination.pageEvents(id: rateID, producer: "watch").map(\.event) == rateOriginals,
    "\(rate) Hz source IDs, times and original payloads survive production wire and receiver")
}
let phoneID = try ride(destination, watch: false)
rejects("Watch data cannot adopt a phone-owned collection") { _ = try receiver.receiveWatch(chunk(phoneID), startedAt: startedAt, indoor: true) }
let preparing = try destination.create(startedAt: start.addingTimeInterval(-2), indoor: true, watchEnabled: true)
check(try receiver.receiveWatch(chunk(preparing.id), startedAt: startedAt, indoor: true),
  "Watch-enabled phone preparation accepts originals before owner-status confirms actual start")
check(try destination.metadata(id: preparing.id).startedAt == preparing.startedAt,
  "chunk reception preserves the existing timeline for authoritative owner-status reconciliation")
check(try sender.prepare(id: ride(source, watch: false)) == nil, "phone-only archive never becomes an outgoing Watch stream")

// An ordinary CYC replica gap cannot block unrelated complete Watch source transfer.
let sparseID = try ride()
let cyc2 = try WorkoutEvent(workoutId: sparseID, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(1), payload: ["humanPowerW": .integer(122), "cadenceRpm": .integer(80)])
_ = try source.appendBatch([cyc2], producer: "cyc", firstSequence: 2)
let forgedWatch = try WorkoutChunkCodec.encode(workoutID: sparseID, producer: "watch", firstSequence: 1, events: [cyc2])
rejects("Watch producer manifest cannot wrap and relabel a CYC original") { _ = try receiver.receiveWatch(forgedWatch, startedAt: startedAt, indoor: true) }
check(try destination.store.read { try $0.get(namespace: "chunk-receipts", key: forgedWatch.manifest.identity) } == nil,
  "foreign-source Watch envelope cannot commit a receipt")
rejects("regression reproduces strict legacy source hole") { _ = try transfer.source(id: sparseID, producer: "cyc") }
let sparse = try transfer.sourceSnapshot(id: sparseID, producer: "cyc")
check(sparse.isPending && sparse.seal.count == 0 && sparse.seal.lastSequence == 0 && sparse.seal.outcome == "pending", "sparse snapshot is a valid unresolved descriptor")
_ = try append(sparseID, from: 0, count: 4)
check(try sender.prepare(id: sparseID)!.manifest.count == 4, "Watch stream moves independently of sparse CYC replica")
let cyc1 = try WorkoutEvent(workoutId: sparseID, kind: "telemetry", source: "cyc", timestamp: start, payload: ["humanPowerW": .integer(121), "cadenceRpm": .integer(80)])
_ = try source.appendBatch([cyc1], producer: "cyc", firstSequence: 1)
let healed = try transfer.sourceSnapshot(id: sparseID, producer: "cyc")
check(!healed.isPending && healed.seal.count == 2 && healed.seal.outcome == "sealed", "late original heals source completeness without renumbering")
let empty = try transfer.sourceSnapshot(id: sparseID, producer: "phone")
check(!empty.isPending && empty.seal.count == 0, "empty sources have a valid complete digest")
let interior = try ride()
_ = try source.appendBatch([event(interior, 0)], producer: "watch", firstSequence: 1)
_ = try source.appendBatch([event(interior, 2)], producer: "watch", firstSequence: 3)
rejects("nextChunk validates every interior source sequence") { _ = try transfer.nextChunk(id: interior, producer: "watch", after: 0) }
rejects("sender cannot relabel an interior gap as a contiguous range") { _ = try sender.prepare(id: interior) }

// All chunks were acknowledged before stop: the final seal still has its independent durable retry.
_ = try source.finish(id: id, endedAt: start.addingTimeInterval(20), finalPhase: "completed")
let sourceSeal = try transfer.source(id: id, producer: "watch")
let seal1 = WorkoutSeal(workoutID: id, sealRevision: 1, collectionRevision: try source.revision(id: id), ownerRevision: 1,
  stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(20)), healthOutcome: "saved", requirements: [:], sources: [sourceSeal])
_ = try transfer.accept(seal: seal1)
let seals = WorkoutSealSubmissionJournal(archive: source)
check(try seals.shouldSubmit(id: id, revision: 1, now: 200), "unreachable final seal is eligible after all chunks have already completed")
try seals.recordSubmission(id: id, revision: 1, now: 200)
let restartedSeals = WorkoutSealSubmissionJournal(archive: source)
check(try !restartedSeals.shouldSubmit(id: id, revision: 1, now: 201), "restart retains background seal submission backoff")
check(try restartedSeals.shouldSubmit(id: id, revision: 1, now: 230), "completed unacknowledged background seal becomes eligible again")
_ = try receiver.accept(seal: seal1)
check(try receiver.verify(id: id), "late final seal verifies originals delivered while active")
check(try seals.acknowledge(id: id, revision: 1), "exact seal receipt is independently durable")
check(try !restartedSeals.shouldSubmit(id: id, revision: 1, now: 500), "unchanged acknowledged seal needs no resubmission")
let correction = try append(id, from: 160, count: 1)
let newSource = try transfer.source(id: id, producer: "watch")
let seal2 = WorkoutSeal(workoutID: id, sealRevision: 2, collectionRevision: try source.revision(id: id), ownerRevision: 1,
  stopCutoff: seal1.stopCutoff, healthOutcome: "saved", requirements: [:], sources: [newSource])
_ = try transfer.accept(seal: seal2)
check(try seals.shouldSubmit(id: id, revision: 2, now: 201), "new Health revision bypasses old seal backoff")
check(try !seals.acknowledge(id: id, revision: 1), "stale seal ACK cannot finish new originals")
_ = try receiver.accept(seal: seal2)
check(try !receiver.verify(id: id), "new seal before correction remains incomplete")
let corrected = try sender.prepare(id: id)!
_ = try receiver.receiveWatch(corrected, startedAt: startedAt, indoor: true)
check(try receiver.verify(id: id), "tail with no embedded seal unblocks existing final verification")
check(try destination.pageEvents(id: id, afterSequence: 160, producer: "watch").map(\.event) == correction, "late Health original is exact")

// Near-limit individual originals fit the real live envelope; candidates shrink before persistence.
func noise(_ size: Int, seed: UInt64) -> String {
  var state = seed
  return String(bytes: (0..<size).map { _ in state = state &* 6364136223846793005 &+ 1442695040888963407; return UInt8(33 + ((state >> 32) % 90)) }, encoding: .utf8)!
}
func largePayload(seed: UInt64) -> [String: WorkoutJSON] {
  Dictionary(uniqueKeysWithValues: (0..<8).map { ("raw" + String($0), .string(noise(3_875, seed: seed &+ UInt64($0 * 100)))) })
}
let largeID = try ride()
let largeEvents = try (0..<16).map { try event(largeID, $0, payload: largePayload(seed: UInt64($0 + 1))) }
check(try largeEvents.allSatisfy { try $0.validate() <= 32_768 }, "near-limit fixture is legal production input")
var maximumEvent = largeEvents[0]
maximumEvent.payload["padding"] = .string("")
let paddingBytes = 32_768 - (try WorkoutCoding.encoder().encode(maximumEvent).count)
maximumEvent.payload["padding"] = .string(String(repeating: "a", count: paddingBytes))
check(try maximumEvent.validate() == 32_768, "exact 32 KiB source event is legal")
let maximumChunk = try WorkoutChunkCodec.encode(workoutID: largeID, producer: "watch", firstSequence: 1, events: [maximumEvent])
check(try WorkoutChunkWire.encode(chunk: maximumChunk, startedAt: startedAt, indoor: true)!.count <= 60_000,
  "exact maximum legal event fits complete live envelope without being skipped")
_ = try source.appendBatch(largeEvents, producer: "watch", firstSequence: 1)
let largeChunk = try sender.prepare(id: largeID)!
check(largeChunk.manifest.count < 16 && largeChunk.manifest.count > 0, "wire fitting shrinks candidate before immutable manifest publication")
check(try WorkoutChunkWire.encode(chunk: largeChunk, startedAt: startedAt, indoor: true)!.count <= 60_000, "near-limit original still fits actual wire bound")
check(largeChunk.manifest.uncompressedBytes <= WorkoutChunkCodec.maximumBytes, "large chunk decoded size is bounded")

// Production receiver reservation, failed-history displacement and two-active-claims fairness.
let capacityStore = try makeArchive("capacity")
let capacity = try WorkoutChunkInbox(root: root.appendingPathComponent("capacity-inbox"), store: capacityStore.store)
var historical: [WorkoutChunk] = []
for _ in 0..<7 {
  let c = try chunk(UUID().uuidString.lowercased()); historical.append(c)
  check(try capacity.stage(data: c.data, metadata: metadata(c)), "seven historical identities fit reserved count capacity")
}
let extra = try chunk(UUID().uuidString.lowercased())
check(try !capacity.stage(data: extra.data, metadata: metadata(extra)), "idle historical admission preserves one future active slot")
let activeID = UUID().uuidString.lowercased(); capacity.setPreferredWorkoutID(activeID)
let activeChunk = try chunk(activeID)
check(try capacity.stage(data: activeChunk.data, metadata: metadata(activeChunk)), "native active ride enters saturated historical inbox")
let activeClaim = try capacity.claim(now: 10)!
check(activeClaim.workoutID == activeID, "native priority precedes oldest history")
// Model the real deletion sweep's ledger-before-file window while this import still owns its claim.
try capacityStore.store.transaction { try $0.remove(namespace: "incoming-chunks", key: activeClaim.identity) }
let nextActiveID = UUID().uuidString.lowercased(); capacity.setPreferredWorkoutID(nextActiveID)
let nextActive = try chunk(nextActiveID)
check(try capacity.stage(data: nextActive.data, metadata: metadata(nextActive)), "new native priority evicts unclaimed history to restore reserve")
check(FileManager.default.fileExists(atPath: capacity.url(activeClaim).path), "capacity reclamation never evicts the currently claimed previous ride")
check(try FileManager.default.contentsOfDirectory(at: capacity.root, includingPropertiesForKeys: nil).filter { $0.pathExtension == "plchunk" }.count == 8,
  "claimed file still consumes capacity when its deletion ledger was already reclaimed")
check(try capacityStore.store.read { try $0.get(namespace: "chunk-receipts", key: historical.last!.manifest.identity) } == nil, "eviction produces no successful receipt or source ACK")
try capacity.finish(activeClaim, success: false, now: 10)
let preferred1 = try capacity.claim(now: 10)!
check(preferred1.workoutID == nextActiveID, "new active admitted chunk is runnable")
try capacity.finish(preferred1, success: true, now: 10)
let active2 = try chunk(nextActiveID, first: 2)
check(try capacity.stage(data: active2.data, metadata: metadata(active2)), "new active chunk enters after preceding import")
let preferred2 = try capacity.claim(now: 10)!
check(preferred2.workoutID == nextActiveID, "second active claim remains preferred")
try capacity.finish(preferred2, success: true, now: 10)
let active3 = try chunk(nextActiveID, first: 3)
check(try capacity.stage(data: active3.data, metadata: metadata(active3)), "third active identity can stage")
let fair = try capacity.claim(now: 10)!
check(fair.workoutID == historical.first!.manifest.workoutID, "after two active claims scheduler selects oldest due historical work")
try capacity.finish(fair, success: false, now: 10)
let unseen = try chunk(UUID().uuidString.lowercased())
check(try capacity.stage(data: unseen.data, metadata: metadata(unseen)), "unknown owner-status-late ride can displace failed unclaimed history when saturated")
check(try capacity.pendingCount <= 8, "all production saturation paths retain bounded ledger count")

let byteArchive = try makeArchive("byte-capacity")
let byteInbox = try WorkoutChunkInbox(root: root.appendingPathComponent("byte-inbox"), store: byteArchive.store)
// Distinct random payloads keep compression representative of irreducible originals.
func bigChunk(_ id: String) throws -> WorkoutChunk {
  try WorkoutChunkCodec.encode(workoutID: id, producer: "watch", firstSequence: 1,
    events: (0..<16).map { try event(id, $0, payload: largePayload(seed: UInt64($0 + 80))) })
}
var admitted = 0, bytes = 0
while admitted < 8 {
  let c = try bigChunk(UUID().uuidString.lowercased())
  if try !byteInbox.stage(data: c.data, metadata: metadata(c)) { break }
  admitted += 1; bytes += c.data.count
}
check(admitted < 7 && bytes <= WorkoutChunkInbox.maximumBytes - WorkoutChunkCodec.maximumBytes, "byte reservation applies independently of file count")
let priorityLarge = try bigChunk(UUID().uuidString.lowercased())
byteInbox.setPreferredWorkoutID(priorityLarge.manifest.workoutID)
check(try byteInbox.stage(data: priorityLarge.data, metadata: metadata(priorityLarge)), "512 KiB reserve admits active large file")
check(bytes + priorityLarge.data.count <= WorkoutChunkInbox.maximumBytes, "combined actual staged bytes remain below 2 MiB")

// Normal deletion reclaims new durable transport states through their encoded ride association.
let deletingID = try ride()
_ = try append(deletingID, from: 0, count: 1)
let deletingChunk = try sender.prepare(id: deletingID)!
_ = try source.finish(id: deletingID, endedAt: start.addingTimeInterval(1), finalPhase: "completed")
let deletingSeal = WorkoutSeal(workoutID: deletingID, sealRevision: 1, collectionRevision: try source.revision(id: deletingID), ownerRevision: 1,
  stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(1)), healthOutcome: "saved", requirements: [:],
  sources: [try transfer.source(id: deletingID, producer: "watch")])
_ = try transfer.accept(seal: deletingSeal)
try seals.recordSubmission(id: deletingID, revision: 1, now: 1)
_ = try source.store.markWorkoutDeleted(id: deletingID)
rejects("deleted sender cannot reconstruct or requeue originals") { _ = try sender.prepare(id: deletingID) }
rejects("late ACK cannot resurrect deleted sender state") { _ = try ack(deletingChunk) }
while try !source.cleanupDeletedWorkoutPage(id: deletingID) { }
for namespace in ["outgoing-watch-chunk", "outgoing-watch-seal"] {
  check(try source.store.read { try $0.get(namespace: namespace, key: deletingID) } == nil, "deletion reclaims \(namespace) by encoded workout identity")
}
_ = try destination.store.markWorkoutDeleted(id: deletingID)
rejects("late incoming chunk cannot create a deleted provisional collection") { _ = try receiver.receiveWatch(deletingChunk, startedAt: startedAt, indoor: true) }
print("Continuous Watch sync: \(assertions) assertions passed; frozen sender, hash ACK, real inbox/receipt, restart, sparse healing, final seal, wire and saturation")
