import Foundation

var assertions = 0
func check(_ value: Bool, _ message: String) { assertions += 1; if !value { fatalError(message) } }
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-transfer-scheduling-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: root) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: store)
let forwarder = WorkoutTelemetryForwarder(archive: archive)
let transfer = WorkoutTransferJournal(archive: archive)
let start = Date(timeIntervalSince1970: 1_780_000_000)
func ride(_ offset: Double, count: Int = 32, watch: Bool = true) throws -> String {
  let metadata = try archive.create(startedAt: start.addingTimeInterval(offset), indoor: true, watchEnabled: watch)
  let events = try (0..<count).map { index in
    try WorkoutEvent(workoutId: metadata.id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(offset + Double(index) / 8),
      elapsedSeconds: Double(index) / 8, payload: ["humanPowerW": .integer(Int64(index)), "cadenceRpm": .integer(80)])
  }
  _ = try archive.appendBatch(events, producer: "cyc", firstSequence: 1)
  try transfer.register(id: metadata.id, producer: "cyc")
  return metadata.id
}
func seal(_ id: String, partial: Bool = false) throws {
  _ = try archive.update(id: id, phase: "completed", healthKitState: "saved")
  let source = try transfer.source(id: id, producer: "cyc")
  let value = WorkoutSeal(workoutID: id, sealRevision: 1, collectionRevision: try archive.revision(id: id), ownerRevision: 1,
    stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(1000)), healthOutcome: "saved",
    requirements: partial ? ["heart": "unavailable"] : [:], sources: [source])
  _ = try transfer.accept(seal: value)
  check(try transfer.verify(id: id), "test archive must be genuinely verified")
}
let complete = try ride(0)
let partial = try ride(1)
check(try forwarder.stageNext(id: complete), "unverified originals stage normally")
check(try forwarder.stageNext(id: partial), "partial candidate initially stages")
let completeOriginals = try WorkoutCoding.encoder().encode(archive.pageEvents(id: complete).map(\.event))
try seal(complete); try seal(partial, partial: true)
check(try forwarder.isCurrentlyVerified(id: complete), "complete current seal is recognized")
check(try forwarder.isCurrentlyVerified(id: partial), "verified partial current seal is recognized")
check(try !forwarder.stageNext(id: complete) && !forwarder.stageNext(id: partial), "direct staging cannot reupload verified restored examples")
check(try forwarder.pruneVerifiedPackets() == 2, "only redundant verified telemetry is removed")
check(try WorkoutCoding.encoder().encode(archive.pageEvents(id: complete).map(\.event)) == completeOriginals, "pruning never changes preserved originals")
check(try forwarder.outbox.packets().isEmpty, "verified transport copies no longer consume the 16 slots")
// New source data invalidates the old seal, including if a stale UI flag were retained.
let correction = try WorkoutEvent(workoutId: partial, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(10),
  elapsedSeconds: 10, payload: ["humanPowerW": .integer(91), "cadenceRpm": .integer(80)])
_ = try archive.appendBatch([correction])
check(try !forwarder.isCurrentlyVerified(id: partial), "new canonical source data reopens delivery eligibility")
check(try forwarder.stageNext(id: partial), "new source tail remains deliverable")
check(try forwarder.pruneVerifiedPackets() == 0, "old verified metadata cannot discard the newly unverified packet")
while try forwarder.stageNext(id: partial) { }
for packet in try forwarder.outbox.packets() { try forwarder.outbox.acknowledge(packet.key) }
// Complete restored examples can exceed one scan page without hiding later work.
for index in 0..<65 { let id = try ride(Double(index + 2), count: 1); try seal(id) }
let a = try ride(100), b = try ride(101), phone = try ride(102, watch: false)
check(try !forwarder.stageNext(id: phone), "phone-only telemetry never enters Watch outbox")
check(try forwarder.stageNextPending(), "paged eligibility scan reaches first unfinished producer")
check(try forwarder.stageNextPending(), "next scheduling turn advances another unfinished producer")
let staged = try forwarder.outbox.packets().map { try JSONSerialization.jsonObject(with: $0.value) as! [String: Any] }
check(Set(staged.compactMap { $0["workoutId"] as? String }) == Set([a, b]), "one oldest collection cannot monopolize all producer slots")
let cursorBefore = try store.read { try $0.get(namespace: "telemetry-forward-cursor", key: a) }
for _ in staged.count..<WorkoutBoundedOutbox.maximumEvents {
  check(try forwarder.outbox.enqueue(["schemaVersion": 1, "kind": "events", "messageId": UUID().uuidString.lowercased(), "workoutId": a, "events": []]), "test can fill bounded data admission")
}
check(try !forwarder.stageNext(id: a), "full outbox applies backpressure")
check(try store.read { try $0.get(namespace: "telemetry-forward-cursor", key: a) } == cursorBefore, "failed admission cannot skip canonical data")
for packet in try forwarder.outbox.packets() { try forwarder.outbox.acknowledge(packet.key) }
let restarted = WorkoutTelemetryForwarder(archive: try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: store))
check(try restarted.stageNextPending(), "canonical scheduling cursor resumes after recreation")
let next = try restarted.outbox.packets().map { try JSONSerialization.jsonObject(with: $0.value) as! [String: Any] }
check(next.first?["firstSequence"] as? String == "17", "restart uses contiguous producer sequence")
let beforeFailure = try store.read { try $0.get(namespace: "telemetry-forward-cursor", key: b) }
let beforePackets = try restarted.outbox.packets().count
store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected forwarding commit failure") }
var failed = false
do { _ = try restarted.stageNext(id: b) } catch { failed = true }
store.beforeCommitForTesting = nil
check(failed, "forwarding exposes failed durable admission")
check(try store.read { try $0.get(namespace: "telemetry-forward-cursor", key: b) } == beforeFailure,
  "failed commit rolls back the producer cursor")
check(try restarted.outbox.packets().count == beforePackets, "failed commit cannot publish an uncommitted packet")
check(try restarted.stageNext(id: b), "same source tail retries after a failed commit")
// A broken legacy producer must not head-of-line block a newer healthy ride.
for packet in try forwarder.outbox.packets() { try forwarder.outbox.acknowledge(packet.key) }
let hole = try archive.create(startedAt: start.addingTimeInterval(-100), indoor: true, watchEnabled: true)
let holeEvent = try WorkoutEvent(workoutId: hole.id, kind: "telemetry", source: "cyc", timestamp: start,
  elapsedSeconds: 1, payload: ["humanPowerW": .integer(100), "cadenceRpm": .integer(80)])
_ = try archive.appendBatch([holeEvent], producer: "cyc", firstSequence: 2)
let healthy = try ride(200)
let holeOriginals = try WorkoutCoding.encoder().encode(archive.pageEvents(id: hole.id).map(\.event))
check(try restarted.stageNextPending(), "broken oldest source does not block a healthy producer")
check(restarted.deferredSource, "missing source is reported separately from healthy forwarding")
check(try store.read { try $0.get(namespace: "telemetry-forward-cursor", key: hole.id) } == nil,
  "deferring a source hole cannot advance its cursor")
check(try WorkoutCoding.encoder().encode(archive.pageEvents(id: hole.id).map(\.event)) == holeOriginals,
  "deferring a source hole preserves all retained originals")
let afterHole = WorkoutTelemetryForwarder(archive: archive)
_ = try afterHole.stageNextPending()
check(!afterHole.deferredSource, "durable revision-scoped fence avoids unchanged hole retries after recreation")
let holeFirst = try WorkoutEvent(workoutId: hole.id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(-1),
  elapsedSeconds: 0, payload: ["humanPowerW": .integer(90), "cadenceRpm": .integer(80)])
_ = try archive.appendBatch([holeFirst], producer: "cyc", firstSequence: 1)
check(try afterHole.stageNextPending(), "filling a missing original invalidates its derived defer fence")
let holePackets = try afterHole.outbox.packets().compactMap { try JSONSerialization.jsonObject(with: $0.value) as? [String: Any] }
check(holePackets.contains { $0["workoutId"] as? String == hole.id && $0["firstSequence"] as? String == "1" },
  "healed original resumes from its first contiguous sequence")
// An acknowledged Health workout cannot acknowledge a missing producer seal.
let receivingStore = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("receiver.sqlite3"))
let receiving = try WorkoutArchive(rootURL: root.appendingPathComponent("receiver"), store: receivingStore)
_ = try receiving.create(id: b, startedAt: start, indoor: true, watchEnabled: true)
let receiver = WorkoutTransferJournal(archive: receiving)
try receiver.register(id: b, producer: "watch"); try receiver.register(id: b, producer: "cyc")
let emptyWatch = try receiver.source(id: b, producer: "watch")
_ = try receiver.accept(seal: WorkoutSeal(workoutID: b, sealRevision: 1, collectionRevision: 1, ownerRevision: 1,
  stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(60)), healthOutcome: "saved", requirements: [:], sources: [emptyWatch]))
check(try !receiver.verify(id: b), "fair delivery must not bypass missing controller verification")
var after: Int64 = 0
while let chunk = try transfer.nextChunk(id: b, producer: "cyc", after: after) { _ = try receiver.receive(chunk); after = chunk.manifest.lastSequence }
let source = try transfer.source(id: b, producer: "cyc")
_ = try receiver.accept(seal: WorkoutSeal(workoutID: b, sealRevision: 2, collectionRevision: 2, ownerRevision: 1,
  stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(60)), healthOutcome: "saved", requirements: [:], sources: [emptyWatch, source]))
check(try receiver.verify(id: b), "complete producer data and newer seal converge")
print("Transfer scheduling: \(assertions) assertions passed")
