import Foundation

var assertions = 0
func fail(_ message: String) -> Never { FileHandle.standardError.write(Data((message + "\n").utf8)); fatalError(message) }
func check(_ value: Bool, _ message: String) { assertions += 1; if !value { fail(message) } }
func rejects(_ body: () throws -> Void, _ message: String) {
  do { try body(); fail(message) } catch { assertions += 1 }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-transfer-regression-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: root) }
let legacy = ISO8601DateFormatter(); legacy.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
let whole = ISO8601DateFormatter(); whole.formatOptions = [.withInternetDateTime]
let earliest = Date(timeIntervalSince1970: 946684800)
// Match the Date itself and its derived integer key, including epoch rounding.
for day in stride(from: 0, to: 36525, by: 17) {
  for ms in [0, 1, 2, 3, 7, 31, 123, 125, 249, 500, 998, 999] {
    let value = legacy.string(from: earliest.addingTimeInterval(Double(day) * 86400 + Double(ms) / 1000))
    let expected = legacy.date(from: value)!, actual = try WorkoutCoding.date(value)
    check(actual == expected, "Timestamp Date changed: \(value)")
    check(try PowerLogStore.microseconds(actual.timeIntervalSince1970) == PowerLogStore.microseconds(expected.timeIntervalSince1970), "Timestamp query key changed")
  }
}
for value in ["2026-09-09T00:00:00Z", "2026-09-09T00:00:00+00:00", "2026-09-09T00:00:00.123456789Z", "2026-09-09T00:00:00.123456+00:00",
  "2026-00-09T00:00:00.000Z", "2026-13-09T00:00:00.000Z", "2026-02-30T00:00:00.000Z", "2026-09-00T00:00:00.000Z",
  "2026-09-32T00:00:00.000Z", "2026-09-09T24:00:00.000Z", "2026-09-09T25:00:00.000Z", "2026-09-09T00:60:00.000Z", "2026-09-09T00:00:60.000Z",
  "1999-12-31T23:59:59.999Z", "2100-01-01T00:00:00.000Z"] {
  let parsed = legacy.date(from: value) ?? whole.date(from: value)
  let expected = parsed.flatMap { $0.timeIntervalSince1970 >= 946684800 && $0.timeIntervalSince1970 < 4102444800 ? $0 : nil }
  check((try? WorkoutCoding.date(value)) == expected, "Alternate timestamp semantics changed: \(value)")
}
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("sender/power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("sender/workouts"), store: store)
let destinationStore = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("receiver/power-log.sqlite3"))
let destination = try WorkoutArchive(rootURL: root.appendingPathComponent("receiver/workouts"), store: destinationStore)
let start = try WorkoutCoding.date("2026-09-09T00:00:00.000Z")
let ride = try archive.create(startedAt: start, indoor: false, watchEnabled: true)
_ = try destination.create(id: ride.id, startedAt: start, indoor: false, watchEnabled: true)
let sender = WorkoutTransferJournal(archive: archive), receiver = WorkoutTransferJournal(archive: destination)
let sessionID = UUID().uuidString.lowercased()
var originals: [WorkoutEvent] = []
for index in 0..<257 {
  let timestamp = ["2026-09-09T00:00:00.123456789Z", "2026-09-09T00:00:00+00:00", "2026-09-09T00:00:00.123Z"][index % 3]
  var payload: [String: WorkoutJSON] = ["humanPowerW": .integer(9_007_199_254_740_993), "cadenceRpm": .number(81.25),
    "captureSessionID": .string(sessionID), "observationSequence": .integer(Int64(index)), "unsigned": .unsigned(UInt64.max),
    "minimum": .integer(Int64.min), "nested": .object(["empty": .array([]), "null": .null, "flag": .bool(true), "quote": .string("\"/\\é🦊")])]
  if index % 2 == 0 { payload["timestamp"] = .string(timestamp); payload["sequence"] = .unsigned(UInt64.max) }
  var event = try WorkoutEvent(workoutId: ride.id, kind: "telemetry", source: "cyc", timestamp: start,
    elapsedSeconds: index % 2 == 0 ? nil : Double(index) / 8, payload: payload)
  event.timestamp = timestamp; try event.validate(); originals.append(event)
}
_ = try archive.appendBatch(originals, producer: "cyc", firstSequence: 1)
let revision = try archive.revision(id: ride.id)
let rows = try archive.pageEvents(id: ride.id, limit: 300, producer: "cyc")
check(try WorkoutCoding.encoder().encode(rows.map(\.event)) == WorkoutCoding.encoder().encode(originals), "Trusted read changed exact canonical payload")
check(try archive.appendBatch(rows.map(\.event), producer: "cyc", firstSequence: 1) == 0, "Read/replay changed identity or membership")
check(try archive.revision(id: ride.id) == revision, "Snapshot read/replay mutated originals")
let seal = try sender.source(id: ride.id, producer: "cyc")
var chunks = 0, after: Int64 = 0
while let chunk = try sender.nextChunk(id: ride.id, producer: "cyc", after: after) {
  check(chunk.manifest.count <= WorkoutChunkCodec.maximumRecords && chunk.manifest.uncompressedBytes <= WorkoutChunkCodec.maximumBytes, "Sender exceeded count/byte bound")
  let expected = Array(originals[Int(after)..<Int(chunk.manifest.lastSequence)])
  let independentlyEncoded = try WorkoutChunkCodec.encode(workoutID: ride.id, producer: "cyc", firstSequence: after + 1, events: expected)
  check(chunk.manifest == independentlyEncoded.manifest && chunk.data == independentlyEncoded.data, "Sender changed canonical chunk bytes/hash")
  if chunks == 0 {
    destinationStore.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "Injected commit failure") }
    rejects({ _ = try receiver.receive(chunk) }, "Import acknowledged failed commit")
    destinationStore.beforeCommitForTesting = nil
    check(try destination.sourceProgress(id: ride.id, producer: "cyc").count == 0, "Failed commit retained originals")
    check(try destinationStore.read { try $0.get(namespace: "chunk-receipts", key: chunk.manifest.identity) } == nil, "Failed commit retained receipt")
  }
  check(try receiver.receive(chunk), "Import failed")
  var decoded = false; receiver.onDecode = { decoded = true }
  check(try !receiver.receive(chunk) && !decoded, "Matching receipt decoded samples")
  receiver.onDecode = nil
  after = chunk.manifest.lastSequence; chunks += 1
}
check(after == 257 && chunks > 1, "Transfer dropped tail")
check(try WorkoutCoding.encoder().encode(destination.pageEvents(id: ride.id, limit: 300, producer: "cyc").map(\.event)) == WorkoutCoding.encoder().encode(originals), "Transfer lost timestamp/integer/presence")
let snapshot = WorkoutSeal(workoutID: ride.id, sealRevision: 1, collectionRevision: revision, ownerRevision: 1,
  stopCutoff: "2026-09-09T01:00:00Z", healthOutcome: "saved", requirements: [:], sources: [seal])
check(try receiver.accept(seal: snapshot), "Seal rejected")
check(try receiver.verify(id: ride.id), "Canonical source digest changed")
var verificationPages = 0; receiver.onVerifyPage = { verificationPages += 1 }
check(try receiver.verify(id: ride.id) && verificationPages == 0, "Verified snapshot was rescanned")
// Large Unicode extensions must stop on bytes even when the record limit is larger.
let wide = try archive.create(startedAt: start, indoor: true, watchEnabled: true)
var large: [WorkoutEvent] = []
for _ in 0..<40 {
  var payload: [String: WorkoutJSON] = ["heartRateBpm": .integer(123)]
  for index in 0..<7 { payload["extension\(index)"] = .string(String(repeating: "é", count: 1800)) }
  large.append(try WorkoutEvent(workoutId: wide.id, kind: "health", source: "watch", timestamp: start, elapsedSeconds: 0, payload: payload))
}
_ = try archive.appendBatch(large, producer: "watch", firstSequence: 1)
let bounded = try sender.nextChunk(id: wide.id, producer: "watch", after: 0)!
check(bounded.manifest.count < WorkoutChunkCodec.maximumRecords && bounded.manifest.uncompressedBytes <= WorkoutChunkCodec.maximumBytes, "Large records exceeded byte bound")
check(try WorkoutChunkCodec.decode(bounded).count == bounded.manifest.count, "Bounded large chunk failed roundtrip")
var invalid = originals[0]; invalid.timestamp = "not UTC"
rejects({ _ = try archive.appendBatch([invalid]) }, "Import bypassed timestamp validation")
invalid = originals[0]; invalid.payload["humanPowerW"] = .number(.infinity)
rejects({ _ = try archive.appendBatch([invalid]) }, "Import bypassed number validation")
print("Transfer regression passed: \(assertions) assertions; exact timestamps, integers, absence, chunk bytes, replay, rollback, bounds and verification")
