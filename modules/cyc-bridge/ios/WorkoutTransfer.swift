import Foundation
import CryptoKit
import Compression

struct WorkoutSourceSeal: Codable, Equatable {
  var producer: String
  var lastSequence: Int64
  var count: Int64
  var digest: String
  var outcome: String // pending, sealed, unavailable
  var reason: String?
  var provenance: String
}
struct WorkoutSeal: Codable, Equatable {
  let workoutID: String
  let sealRevision: Int64
  let collectionRevision: Int64
  let ownerRevision: Int64
  let stopCutoff: String
  let healthOutcome: String
  let requirements: [String: String]
  let sources: [WorkoutSourceSeal]
  var stopElapsedSeconds: Double? = nil
  var saveToHealth: Bool? = nil
  var recordGPS: Bool? = nil
  var resolved: Bool { !sources.contains { $0.outcome == "pending" } && !requirements.values.contains("pending") }
  var partial: Bool { sources.contains { $0.outcome == "unavailable" } || requirements.values.contains("unavailable") }
}
struct WorkoutChunkManifest: Codable, Equatable {
  let formatVersion: Int
  let workoutID: String
  let producer: String
  let firstSequence: Int64
  let lastSequence: Int64
  let count: Int
  let uncompressedBytes: Int
  let compressedBytes: Int
  let contentHash: String
  var identity: String { WorkoutChunkCodec.hash(Data("\(formatVersion):\(workoutID):\(producer):\(firstSequence):\(lastSequence)".utf8)) }
}
struct WorkoutChunk { let manifest: WorkoutChunkManifest; let data: Data }

enum WorkoutSourceSnapshot {
  case pending(WorkoutSourceSeal)
  case complete(WorkoutSourceSeal)
  var seal: WorkoutSourceSeal { switch self { case .pending(let value), .complete(let value): return value } }
  var isPending: Bool { if case .pending = self { return true }; return false }
}

enum WorkoutChunkCodec {
  static let maximumRecords = 128
  static let maximumBytes = 524_288
  static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
  static func encode(workoutID: String, producer: String, firstSequence: Int64, events: [WorkoutEvent]) throws -> WorkoutChunk {
    guard !events.isEmpty, events.count <= maximumRecords, firstSequence > 0, firstSequence <= Int64.max - Int64(events.count - 1),
      events.allSatisfy({ $0.workoutId == workoutID }) else { throw WorkoutDataError.invalid("Invalid archival chunk range") }
    let plain = try WorkoutCoding.encoder().encode(events)
    guard plain.count <= maximumBytes else { throw WorkoutDataError.invalid("Archival chunk exceeds its decoded bound") }
    let data = try transform(plain, encode: true, capacity: maximumBytes)
    return WorkoutChunk(manifest: WorkoutChunkManifest(formatVersion: 1, workoutID: workoutID,
      producer: producer, firstSequence: firstSequence, lastSequence: firstSequence + Int64(events.count - 1),
      count: events.count, uncompressedBytes: plain.count, compressedBytes: data.count, contentHash: hash(data)), data: data)
  }
  static func validate(_ manifest: WorkoutChunkManifest, data: Data) throws {
    _ = try WorkoutCoding.id(manifest.workoutID)
    guard manifest.formatVersion == 1, ["watch", "phone", "cyc"].contains(manifest.producer),
      manifest.count > 0, manifest.count <= maximumRecords, manifest.firstSequence > 0,
      manifest.lastSequence >= manifest.firstSequence, manifest.lastSequence - manifest.firstSequence < Int64(maximumRecords),
      manifest.lastSequence - manifest.firstSequence + 1 == Int64(manifest.count),
      (1...maximumBytes).contains(manifest.uncompressedBytes), (1...maximumBytes).contains(manifest.compressedBytes),
      data.count == manifest.compressedBytes, hash(data) == manifest.contentHash else {
      throw WorkoutDataError.invalid("Invalid archival chunk identity, bounds or digest")
    }
  }
  static func decode(_ chunk: WorkoutChunk) throws -> [WorkoutEvent] {
    try validate(chunk.manifest, data: chunk.data)
    let plain = try transform(chunk.data, encode: false, capacity: chunk.manifest.uncompressedBytes + 1)
    guard plain.count == chunk.manifest.uncompressedBytes else { throw WorkoutDataError.invalid("Incorrect decoded chunk size") }
    let events = try JSONDecoder().decode([WorkoutEvent].self, from: plain)
    guard events.count == chunk.manifest.count else { throw WorkoutDataError.invalid("Incorrect chunk count") }
    for event in events { try event.validate(); guard event.workoutId == chunk.manifest.workoutID else { throw WorkoutDataError.invalid("Mixed workout chunk") } }
    guard try WorkoutCoding.encoder().encode(events) == plain else { throw WorkoutDataError.invalid("Noncanonical archival encoding") }
    return events
  }
  private static func transform(_ data: Data, encode: Bool, capacity: Int) throws -> Data {
    var buffer = Data(count: capacity)
    let written = buffer.withUnsafeMutableBytes { output in data.withUnsafeBytes { input in
      let destination = output.bindMemory(to: UInt8.self).baseAddress!
      let source = input.bindMemory(to: UInt8.self).baseAddress!
      return encode ? compression_encode_buffer(destination, capacity, source, data.count, nil, COMPRESSION_ZLIB)
        : compression_decode_buffer(destination, capacity, source, data.count, nil, COMPRESSION_ZLIB)
    } }
    guard written > 0, written < capacity else { throw WorkoutDataError.invalid("Invalid or oversized compressed archive") }
    buffer.count = written; return buffer
  }
}

/// The receipt lookup precedes decompression. Records, contiguous source ranges, and receipt share one commit.
final class WorkoutTransferJournal {
  let archive: WorkoutArchive
  var beforeVerificationCommit: (() -> Void)?
  var onVerifyPage: (() -> Void)?
  var onDecode: (() -> Void)? // production effect boundary used by replay/fault tests
  init(archive: WorkoutArchive) { self.archive = archive }
  /// Both live envelopes and staged files enter the same original/receipt transaction.
  @discardableResult
  func receiveWatch(_ chunk: WorkoutChunk, startedAt: String, indoor: Bool, saveToHealth: Bool = true, recordGPS: Bool? = nil) throws -> Bool {
    guard chunk.manifest.producer == "watch" else { throw WorkoutDataError.invalid("Expected Watch originals") }
    let start = try WorkoutCoding.date(startedAt)
    try WorkoutChunkCodec.validate(chunk.manifest, data: chunk.data)
    return try archive.store.transaction(priority: .normal) { db in
      let id = chunk.manifest.workoutID
      try archive.store.requireWorkoutAvailable(id: id)
      if try db.scalarInt("SELECT count(*) FROM collections WHERE id=?", [.text(id)]) == 0 {
        _ = try archive.create(id: id, startedAt: start, indoor: indoor, watchEnabled: true, saveToHealth: saveToHealth, recordGPS: recordGPS)
      } else {
        let metadata = try archive.metadata(id: id)
        try WorkoutRecordingPolicy.requireOptions(metadata, saveToHealth: saveToHealth, recordGPS: recordGPS ?? !indoor)
        guard metadata.watchEnabled else {
          throw WorkoutDataError.invalid("Watch chunk conflicts with collection ownership")
        }
      }
      return try receive(chunk)
    }
  }
  @discardableResult
  func receive(_ chunk: WorkoutChunk) throws -> Bool {
    try WorkoutChunkCodec.validate(chunk.manifest, data: chunk.data)
    return try archive.store.transaction(priority: .normal) { db in
      try archive.store.requireWorkoutAvailable(id: chunk.manifest.workoutID)
      let bytes = try WorkoutCoding.encoder().encode(chunk.manifest)
      if let existing = try db.get(namespace: "chunk-receipts", key: chunk.manifest.identity) {
        guard existing == bytes else { throw WorkoutDataError.invalid("Changed content under an existing chunk identity") }
        try archive.store.recordReceiptHit()
        return false
      }
      onDecode?()
      let events = try WorkoutChunkCodec.decode(chunk)
      guard chunk.manifest.producer != "watch" || events.allSatisfy({ $0.source == "watch" }) else {
        throw WorkoutDataError.invalid("Watch source cannot relabel another producer's originals")
      }
      try register(id: chunk.manifest.workoutID, producer: chunk.manifest.producer)
      _ = try archive.appendBatch(events, producer: chunk.manifest.producer, firstSequence: chunk.manifest.firstSequence)
      try db.put(namespace: "chunk-receipts", key: chunk.manifest.identity, value: bytes, immutable: true)
      return true
    }
  }
  func source(id: String, producer: String, outcome: String = "sealed", reason: String? = nil) throws -> WorkoutSourceSeal {
    let progress = try archive.sourceProgress(id: id, producer: producer)
    let cacheKey = id + ":" + producer
    if let data = try archive.store.read({ db in try db.get(namespace: "source-digest-cache", key: cacheKey) }),
      var cached = try? JSONDecoder().decode(WorkoutSourceSeal.self, from: data),
      cached.lastSequence == progress.lastSequence, cached.count == progress.count {
      cached.outcome = outcome; cached.reason = reason; return cached
    }
    var digest = SHA256(); var last: Int64 = 0; var count: Int64 = 0
    while true {
      guard last < progress.lastSequence else { break }
      let page = try archive.pageEvents(id: id, afterSequence: last, limit: Int(min(128, progress.lastSequence - last)), producer: producer)
      try autoreleasepool {
        for row in page {
          guard row.sequence == last + 1 else { throw WorkoutDataError.invalid("Source archival range has a hole") }
          digest.update(data: try WorkoutCoding.encoder().encode(row.event)); digest.update(data: Data([10]))
          last = row.sequence; count += 1
        }
      }
      if page.count < 128 { break }
    }
    let result = WorkoutSourceSeal(producer: producer, lastSequence: last, count: count,
      digest: digest.finalize().map { String(format: "%02x", $0) }.joined(), outcome: outcome,
      reason: reason, provenance: "committedLocalSnapshot")
    try archive.store.transaction(priority: .normal) { db in
      try archive.store.requireWorkoutAvailable(id: id)
      try db.put(namespace: "source-digest-cache", key: cacheKey, value: WorkoutCoding.encoder().encode(result))
    }
    return result
  }
  /// A sparse replica is ordinary pending input; it must never prevent another producer streaming.
  func sourceSnapshot(id: String, producer: String, outcome: String = "sealed", reason: String? = nil) throws -> WorkoutSourceSnapshot {
    let progress = try archive.sourceProgress(id: id, producer: producer)
    guard progress.count == progress.lastSequence else {
      return .pending(WorkoutSourceSeal(producer: producer, lastSequence: 0, count: 0,
        digest: WorkoutChunkCodec.hash(Data()), outcome: "pending", reason: "Awaiting contiguous original source",
        provenance: "committedLocalSnapshot"))
    }
    return .complete(try source(id: id, producer: producer, outcome: outcome, reason: reason))
  }
  func nextChunk(id: String, producer: String, after: Int64) throws -> WorkoutChunk? {
    guard after >= 0, after < Int64.max else { throw WorkoutDataError.invalid("Invalid outgoing archival cursor") }
    let rows = try archive.pageEvents(id: id, afterSequence: after, limit: WorkoutChunkCodec.maximumRecords, producer: producer)
    guard let first = rows.first else { return nil }
    guard rows.enumerated().allSatisfy({ $0.element.sequence == after + Int64($0.offset) + 1 }) else {
      throw WorkoutDataError.invalid("Missing outgoing archival sequence")
    }
    // Shrink by encoded bytes as individual extension payloads may approach 32 KiB.
    var events: [WorkoutEvent] = []; var bytes = 2
    for row in rows {
      let size = try WorkoutCoding.encoder().encode(row.event).count + 1
      if bytes + size >= WorkoutChunkCodec.maximumBytes { break }
      events.append(row.event); bytes += size
    }
    return try WorkoutChunkCodec.encode(workoutID: id, producer: producer, firstSequence: first.sequence, events: events)
  }
  func register(id: String, producer: String) throws {
    guard ["phone", "watch", "cyc"].contains(producer) else { throw WorkoutDataError.invalid("Unknown source") }
    try archive.store.transaction(priority: .capture) { db in
      try archive.store.requireWorkoutAvailable(id: id)
      let key = id + ":" + producer
      if try db.get(namespace: "source-roster", key: key) == nil {
        try db.put(namespace: "source-roster", key: key, value: Data(producer.utf8), immutable: true)
        _ = try db.nextSequence(namespace: "roster-revision", key: id)
        _ = try archive.update(id: id, finalizationState: "pending")
      }
    }
  }
  func roster(id: String) throws -> [String] {
    try archive.store.read { db in
      try archive.store.requireWorkoutAvailable(id: id)
      return try ["cyc", "phone", "watch"].filter { try db.get(namespace: "source-roster", key: id + ":" + $0) != nil }
    }
  }
  func saveSource(id: String, source: WorkoutSourceSeal) throws {
    let id = try WorkoutCoding.id(id)
    guard ["phone", "watch", "cyc"].contains(source.producer),
      source.count >= 0, source.count <= PowerLogStorageLimits.maximumCollectionRecords, source.lastSequence == source.count,
      ["pending", "sealed", "unavailable"].contains(source.outcome),
      source.digest.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
      source.provenance.utf8.count <= 1024, (source.reason?.utf8.count ?? 0) <= 1024 else {
      throw WorkoutDataError.invalid("Invalid source declaration")
    }
    try archive.store.transaction(priority: .capture) { db in
      try archive.store.requireWorkoutAvailable(id: id)
      let key = id + ":" + source.producer
      if let data = try db.get(namespace: "source-seals", key: key) {
        let current = try JSONDecoder().decode(WorkoutSourceSeal.self, from: data)
        // Retries can arrive over either transport after a newer boundary was accepted.
        guard source.lastSequence >= current.lastSequence else { return }
        if source.lastSequence == current.lastSequence {
          guard source.digest == current.digest else { throw WorkoutDataError.invalid("Changed digest under an existing source boundary") }
          let rank = ["pending": 0, "unavailable": 1, "sealed": 2]
          guard rank[source.outcome]! > rank[current.outcome]! else { return }
        }
      }
      try register(id: id, producer: source.producer)
      try db.put(namespace: "source-seals", key: id + ":" + source.producer, value: WorkoutCoding.encoder().encode(source))
    }
  }
  func declaredSource(id: String, producer: String) throws -> WorkoutSourceSeal? {
    try archive.store.read { db in
      try archive.store.requireWorkoutAvailable(id: id)
      return try db.get(namespace: "source-seals", key: id + ":" + producer)
      .map { try JSONDecoder().decode(WorkoutSourceSeal.self, from: $0) } }
  }
  func accept(seal: WorkoutSeal) throws -> Bool {
    _ = try WorkoutCoding.id(seal.workoutID); _ = try WorkoutCoding.date(seal.stopCutoff)
    guard seal.sealRevision > 0, seal.ownerRevision > 0, seal.sources.count <= 3,
      ["saved", "pending", "failed", "notSaved", "unavailable", "notRequested"].contains(seal.healthOutcome),
      seal.stopElapsedSeconds.map({ $0.isFinite && $0 >= 0 && $0 <= 2_678_400 }) ?? true,
      seal.sources.allSatisfy({ ["watch", "phone", "cyc"].contains($0.producer) && $0.count >= 0 && $0.lastSequence == $0.count && ["pending", "sealed", "unavailable"].contains($0.outcome) && $0.digest.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil }),
      seal.requirements.values.allSatisfy({ ["pending", "sealed", "unavailable", "notRequested"].contains($0) }),
      Set(seal.sources.map(\.producer)).count == seal.sources.count else {
      throw WorkoutDataError.invalid("Invalid seal revision or roster")
    }
    return try archive.store.transaction(priority: .normal) { db in
      try archive.store.requireWorkoutAvailable(id: seal.workoutID)
      let metadata = try archive.metadata(id: seal.workoutID)
      try WorkoutRecordingPolicy.requireOptions(metadata, saveToHealth: seal.saveToHealth ?? true, recordGPS: seal.recordGPS ?? !metadata.indoor)
      guard metadata.savesToHealth ? seal.healthOutcome != "notRequested" : seal.healthOutcome == "notRequested" else { throw WorkoutDataError.invalid("Seal conflicts with frozen Health intent") }
      if !metadata.savesToHealth, metadata.watchEnabled {
        guard seal.requirements["healthSave"] == "notRequested", seal.requirements["cycInsertion"] == "notRequested",
          seal.requirements["healthExtraction"] == "notRequested", seal.requirements["localSensors"] != nil,
          seal.requirements["ownerEnded"] != nil else { throw WorkoutDataError.invalid("Local Watch finalization requirements are missing") }
      }
      let data = try WorkoutCoding.encoder().encode(seal)
      try db.put(namespace: "immutable-seals", key: seal.workoutID + ":" + String(seal.sealRevision), value: data, immutable: true)
      let current = try currentSeal(id: seal.workoutID)
      guard current == nil || seal.sealRevision > current!.sealRevision else { return false }
      if let current {
        guard seal.stopCutoff == current.stopCutoff, current.stopElapsedSeconds == nil || seal.stopElapsedSeconds == current.stopElapsedSeconds, seal.ownerRevision >= current.ownerRevision,
          Set(current.sources.map(\.producer)).isSubset(of: Set(seal.sources.map(\.producer))) else {
          throw WorkoutDataError.invalid("New seal regresses the owner cutoff, revision or source roster")
        }
      }
      try db.put(namespace: "current-seal", key: seal.workoutID, value: data)
      try archive.update(id: seal.workoutID, sealRevision: seal.sealRevision, finalizationState: "pending")
      return true
    }
  }
  func currentSeal(id: String) throws -> WorkoutSeal? {
    try archive.store.read { db in
      try archive.store.requireWorkoutAvailable(id: id)
      return try db.get(namespace: "current-seal", key: id)
      .map { try JSONDecoder().decode(WorkoutSeal.self, from: $0) } }
  }
  func verify(id: String) throws -> Bool {
    guard let seal = try currentSeal(id: id), seal.resolved else { return false }
    func matchesCurrentSources() throws -> Bool {
      let registered = try roster(id: id)
      guard Set(registered).isSubset(of: Set(seal.sources.map(\.producer))) else { return false }
      for expected in seal.sources where expected.outcome != "pending" {
        let actual = try archive.sourceProgress(id: id, producer: expected.producer)
        guard actual.count == expected.count, actual.lastSequence == expected.lastSequence else { return false }
      }
      return true
    }
    guard try matchesCurrentSources() else { return false }
    let metadata = try archive.metadata(id: id)
    // Constant work for already verified unchanged snapshots, before any sample page decode.
    if metadata.verifiedSealRevision == seal.sealRevision && metadata.finalizationState == (seal.partial ? "partial" : "complete") { return true }
    for expected in seal.sources where expected.outcome != "pending" {
      var digest = SHA256(); var after: Int64 = 0; var count: Int64 = 0
      while after < expected.lastSequence {
        onVerifyPage?()
        let rows = try archive.pageEvents(id: id, afterSequence: after,
          limit: Int(min(128, expected.lastSequence - after)), producer: expected.producer)
        guard !rows.isEmpty else { return false }
        let contiguous = try autoreleasepool { () throws -> Bool in
          for row in rows {
            guard row.sequence == after + 1 else { return false }
            digest.update(data: try WorkoutCoding.encoder().encode(row.event)); digest.update(data: Data([10]))
            after = row.sequence; count += 1
          }
          return true
        }
        guard contiguous else { return false }
      }
      guard count == expected.count, digest.finalize().map({ String(format: "%02x", $0) }).joined() == expected.digest else { return false }
    }
    beforeVerificationCommit?()
    return try archive.store.transaction(priority: .normal) { _ in
      // The paged hash held no snapshot. Recheck its exact requirements in the final write transaction.
      guard try currentSeal(id: id)?.sealRevision == seal.sealRevision, try matchesCurrentSources() else { return false }
      try archive.update(id: id, sealRevision: seal.sealRevision, verifiedSealRevision: seal.sealRevision,
                         finalizationState: seal.partial ? "partial" : "complete")
      return true
    }
  }
}

/// Corrections keep every prior payload, including a later return to a previously seen value.
enum WorkoutHealthRevisionJournal {
  struct Head: Codable { let eventID: String; let payload: Data }
  @discardableResult
  static func append(_ input: WorkoutEvent, logicalID: String, archive: WorkoutArchive) throws -> (event: WorkoutEvent, inserted: Bool) {
    try archive.store.transaction(priority: .capture) { db in
      try archive.store.requireWorkoutAvailable(id: input.workoutId)
      let key = input.workoutId + ":" + logicalID
      let payload = try WorkoutCoding.encoder().encode(input.payload)
      let previous = try db.get(namespace: "health-correction-heads", key: key)
        .map { try JSONDecoder().decode(Head.self, from: $0) }
      var event = input
      if previous?.payload == payload { event.eventId = previous!.eventID; return (event, false) }
      event.eventId = WorkoutStableIdentity.uuid(logicalID + ":" + (previous?.eventID ?? "initial") + ":" + WorkoutChunkCodec.hash(payload))
      if let previous { event.payload["supersedesEventId"] = .string(previous.eventID) }
      _ = try archive.appendBatch([event])
      try db.put(namespace: "health-correction-heads", key: key,
        value: WorkoutCoding.encoder().encode(Head(eventID: event.eventId, payload: payload)))
      return (event, true)
    }
  }
}

extension WorkoutTransferJournal {
  /// Only the accepted current revision may project lifecycle/Health fields or generate a seal ACK.
  func acceptCurrent(_ incoming: WorkoutSeal) throws -> WorkoutSeal? {
    _ = try accept(seal: incoming)
    guard let current = try currentSeal(id: incoming.workoutID), current.sealRevision == incoming.sealRevision else { return nil }
    return current
  }
}

extension WorkoutTransferJournal {
  func sequence(id: String, eventID: String, producer: String) throws -> Int64 {
    try archive.store.read { db in
      try archive.store.requireWorkoutAvailable(id: id)
      guard let value = try db.scalarInt("SELECT sequence FROM collection_memberships WHERE collection_id=? AND event_id=? AND producer=?",
        [.text(id), .text(eventID), .text(producer)]) else { throw WorkoutDataError.invalid("Canonical archival sequence unavailable") }
      return value
    }
  }
  /// Live subsets use the original committed stream positions; missing positions remain real holes.
  func receiveLive(_ events: [WorkoutEvent], producer: String, firstSequence: Int64) throws {
    guard !events.isEmpty, events.count <= 128, let id = events.first?.workoutId,
      events.allSatisfy({ $0.workoutId == id && $0.source == producer }) else { throw WorkoutDataError.invalid("Invalid live archival stream") }
    try archive.store.transaction(priority: .capture) { _ in
      try register(id: id, producer: producer)
      _ = try archive.appendBatch(events, producer: producer, firstSequence: firstSequence)
    }
  }
}

/// Session ownership and a particular finish attempt are separate native lifetimes.
struct WorkoutHealthCallbackIdentity {
  let workoutID: String
  let generation: UInt64
  let finishAttempt: UInt64?
  func matches(_ current: WorkoutHealthCallbackIdentity) -> Bool {
    workoutID == current.workoutID && generation == current.generation &&
      (finishAttempt == nil || finishAttempt == current.finishAttempt)
  }
}
enum WorkoutHealthFinalizationGate {
  static func canFinish(nativePhase: String) -> Bool { nativePhase == "stopped" || nativePhase == "ended" }
}
struct WorkoutHealthMirrorAdmission {
  var primaryOwner = false
  var ownershipInFlight = false
  var repairInFlight = false
  var pendingWrites = 0
  var awaitingStopCompletion = false
  var finishing = false
  var finishStarted = false
  var permitted: Bool {
    !primaryOwner && !ownershipInFlight && !repairInFlight && pendingWrites == 0 &&
      !awaitingStopCompletion && !finishing && !finishStarted
  }
}

/// The numeric and authorization boundary is shared by live insertion and explicit repair.
/// Only successful native completion changes ready quantities to applied receipts.
struct WorkoutHealthTelemetryPlan {
  struct Quantity { let workoutID: String; let eventID: String; let metric: String; let value: Double }
  static let metrics = ["humanPowerW", "cadenceRpm"]
  var quantities: [Quantity] = []
  var results: [String: [String: String]] = [:]
  init(events: [WorkoutEvent], previous: [String: [String: String]] = [:], authorized: (String) -> Bool) {
    for event in events {
      var metrics = previous[event.eventId] ?? [:]
      for metric in Self.metrics where metrics[metric] != "applied" {
        guard let value = event.number(metric), value.isFinite, value >= 0 else { metrics[metric] = "invalid"; continue }
        guard authorized(metric) else { metrics[metric] = "denied"; continue }
        metrics[metric] = "ready"
        quantities.append(Quantity(workoutID: event.workoutId, eventID: event.eventId, metric: metric, value: value))
      }
      results[event.eventId] = metrics
    }
  }
  var committed: [String: [String: String]] {
    results.mapValues { $0.mapValues { $0 == "ready" ? "applied" : $0 } }
  }
}

/// Production Health side effects cannot outrun durable intent or manufacture a successful receipt.
final class WorkoutHealthInsertionJournal {
  let archive: WorkoutArchive
  init(archive: WorkoutArchive) { self.archive = archive }
  func prepare(_ events: [WorkoutEvent]) throws {
    try archive.store.transaction(priority: .capture) { db in
      for event in events {
        try archive.store.requireWorkoutAvailable(id: event.workoutId)
        try WorkoutRecordingPolicy.requireHealthWrite(id: event.workoutId, archive: archive)
        try db.put(namespace: "health-insertion-intents", key: event.eventId,
          value: WorkoutCoding.encoder().encode(event), immutable: true)
      }
    }
  }
  func record(_ events: [WorkoutEvent], outcome: String) throws {
    guard ["applied", "unavailable", "excluded"].contains(outcome) else { throw WorkoutDataError.invalid("Unresolved Health insertion result") }
    try archive.store.transaction(priority: .capture) { db in
      for event in events {
        try archive.store.requireWorkoutAvailable(id: event.workoutId)
        guard try db.get(namespace: "health-insertion-intents", key: event.eventId) != nil else { throw WorkoutDataError.invalid("Missing Health insertion intent") }
        if let previous = try db.get(namespace: "health-insertion-results", key: event.eventId), previous != Data(outcome.utf8) {
          try db.remove(namespace: "health-insertion-progress", key: event.workoutId)
        }
        try db.put(namespace: "health-insertion-results", key: event.eventId, value: Data(outcome.utf8))
      }
    }
  }
  func metricResults(_ events: [WorkoutEvent]) throws -> [String: [String: String]] {
    try archive.store.read { db in
      var result: [String: [String: String]] = [:]
      for event in events {
        try archive.store.requireWorkoutAvailable(id: event.workoutId)
        if let data = try db.get(namespace: "health-insertion-metrics", key: event.eventId) {
          result[event.eventId] = try JSONDecoder().decode([String: String].self, from: data)
        } else if try db.get(namespace: "health-insertion-results", key: event.eventId) == Data("applied".utf8) {
          result[event.eventId] = Dictionary(uniqueKeysWithValues: WorkoutHealthTelemetryPlan.metrics.map { ($0, "applied") })
        }
      }
      return result
    }
  }
  func recordMetrics(_ events: [WorkoutEvent], results: [String: [String: String]]) throws {
    try archive.store.transaction { db in
      for event in events {
        try archive.store.requireWorkoutAvailable(id: event.workoutId)
        guard let metrics = results[event.eventId], Set(metrics.keys) == Set(WorkoutHealthTelemetryPlan.metrics),
          metrics.values.allSatisfy({ ["applied", "denied", "invalid"].contains($0) }) else { throw WorkoutDataError.invalid("Incomplete Health metric receipts") }
        try db.put(namespace: "health-insertion-metrics", key: event.eventId, value: WorkoutCoding.encoder().encode(metrics))
        try record([event], outcome: metrics.values.allSatisfy({ $0 == "applied" }) ? "applied" : "unavailable")
      }
    }
  }
  /// A retry after an uncertain native outcome must use documented higher-version replacement.
  /// Legacy unavailable batches may already contain one applied metric at version 1.
  func reserveVersions(_ quantities: [WorkoutHealthTelemetryPlan.Quantity], minimumVersion: Int = 1) throws -> [String: Int] {
    guard minimumVersion >= 1 else { throw WorkoutDataError.invalid("Invalid Health insertion version") }
    return try archive.store.transaction { db in
      var versions: [String: Int] = [:]
      for quantity in quantities {
        try archive.store.requireWorkoutAvailable(id: quantity.workoutID)
        let key = quantity.eventID + "." + quantity.metric
        let previous = try db.get(namespace: "health-insertion-versions", key: key)
          .flatMap { String(data: $0, encoding: .utf8) }.flatMap(Int.init)
        let legacy = try db.get(namespace: "health-insertion-results", key: quantity.eventID) == Data("unavailable".utf8)
        let value = previous ?? (legacy ? 1 : 0)
        guard value < Int.max else { throw WorkoutDataError.invalid("Health insertion version exhausted") }
        let next = max(value + 1, minimumVersion)
        versions[key] = next
        try db.put(namespace: "health-insertion-versions", key: key, value: Data(String(next).utf8))
      }
      return versions
    }
  }
  func beginRepair(id: String) throws {
    try WorkoutRecordingPolicy.requireHealthWrite(id: id, archive: archive)
    try archive.store.transaction { db in
      try archive.store.requireWorkoutAvailable(id: id)
      try db.put(namespace: "health-insertion-repair", key: id, value: Data("pending".utf8))
      try db.remove(namespace: "health-insertion-progress", key: id)
      _ = try archive.update(id: id, finalizationState: "pending")
    }
  }
  func needsRepair(_ event: WorkoutEvent) throws -> Bool {
    if try !archive.metadata(id: event.workoutId).savesToHealth { return false }
    return try archive.store.read { db in
      try archive.store.requireWorkoutAvailable(id: event.workoutId)
      let result = try db.get(namespace: "health-insertion-results", key: event.eventId).flatMap { String(data: $0, encoding: .utf8) }
      return result != "applied" && result != "excluded"
    }
  }
  func finishRepair(id: String) throws {
    // Advance at most 128 originals per transaction; retain the marker until all pages settle.
    while true {
      let before = try archive.store.read { try $0.get(namespace: "health-insertion-progress", key: id) }
      guard try pending(id: id, limit: 128).isEmpty else { throw WorkoutDataError.invalid("Health repair still has pending insertions") }
      let after = try archive.store.read { try $0.get(namespace: "health-insertion-progress", key: id) }
      if before == after { break }
    }
    try archive.store.transaction { db in
      try archive.store.requireWorkoutAvailable(id: id)
      let progress = try db.get(namespace: "health-insertion-progress", key: id).map { try JSONDecoder().decode(Progress.self, from: $0) } ?? Progress()
      let source = try archive.sourceProgress(id: id, producer: "cyc")
      guard progress.sequence == source.lastSequence, source.count == source.lastSequence else {
        throw WorkoutDataError.invalid("Health repair source is incomplete")
      }
      try db.remove(namespace: "health-insertion-repair", key: id)
    }
  }
  /// Used by native adapters and fault tests: failure before/after native execution stays pending.
  func perform(_ events: [WorkoutEvent], operation: (@escaping (Result<String, Error>) -> Void) -> Void,
               completion: @escaping (Result<Void, Error>) -> Void) {
    do { try prepare(events) } catch { completion(.failure(error)); return }
    operation { result in
      do { let outcome = try result.get(); try self.record(events, outcome: outcome); completion(.success(())) }
      catch { completion(.failure(error)) }
    }
  }
  private struct Progress: Codable { var sequence: Int64 = 0; var partial = false }
  func pending(id: String, limit: Int = 16) throws -> [WorkoutEvent] {
    if try !archive.metadata(id: id).savesToHealth { return [] }
    return try archive.store.transaction(priority: .background) { db in
      try archive.store.requireWorkoutAvailable(id: id)
      let previous = try db.get(namespace: "health-insertion-progress", key: id)
      var progress = try previous.map { try JSONDecoder().decode(Progress.self, from: $0) } ?? Progress()
      let page = try archive.pageEvents(id: id, afterSequence: progress.sequence, limit: limit, producer: "cyc")
      var missing: [WorkoutEvent] = [], contiguous = true
      for row in page {
        let result = try db.get(namespace: "health-insertion-results", key: row.event.eventId).flatMap { String(data: $0, encoding: .utf8) }
        if !["applied", "unavailable", "excluded"].contains(result ?? "") { missing.append(row.event); contiguous = false }
        if contiguous && row.sequence == progress.sequence + 1 {
          progress.sequence = row.sequence; progress.partial = progress.partial || result == "unavailable"
        } else { contiguous = false }
      }
      let updated = try WorkoutCoding.encoder().encode(progress)
      if previous != updated { try db.put(namespace: "health-insertion-progress", key: id, value: updated) }
      return missing
    }
  }
  func outcome(id: String) throws -> String {
    try archive.store.requireWorkoutAvailable(id: id)
    if try !archive.metadata(id: id).savesToHealth { return "notRequested" }
    if try archive.store.read({ try $0.get(namespace: "health-insertion-repair", key: id) }) != nil { return "pending" }
    while true {
      let before = try archive.store.read { db in try db.get(namespace: "health-insertion-progress", key: id) }
      if try !pending(id: id, limit: 128).isEmpty { return "pending" }
      let after = try archive.store.read { db in try db.get(namespace: "health-insertion-progress", key: id) }
      let progress = try after.map { try JSONDecoder().decode(Progress.self, from: $0) } ?? Progress()
      let source = try archive.sourceProgress(id: id, producer: "cyc")
      if progress.sequence == source.lastSequence && source.count == source.lastSequence { return progress.partial ? "unavailable" : "sealed" }
      if before == after { return "pending" } // A source sequence hole cannot be skipped.
    }
  }

}

/// Retained paused originals do not become Health samples. Authoritative lifecycle uses the workout timeline.
enum WorkoutHealthEligibility {
  static func permits(_ event: WorkoutEvent, archive: WorkoutArchive) throws -> Bool {
    try archive.store.read { db in
      let elapsed = event.elapsedSeconds
      let date = try event.date
      let metadata = try archive.metadata(id: event.workoutId)
      if let elapsed, let cutoff = metadata.stopElapsedSeconds, elapsed >= cutoff { return false }
      if (elapsed == nil || metadata.stopElapsedSeconds == nil), let endedAt = metadata.endedAt,
        date >= (try WorkoutCoding.date(endedAt)) { return false }
      let utc = Int64((date.timeIntervalSince1970 * 1_000_000).rounded())
      let key = elapsed == nil ? "query_us" : "elapsed_seconds"
      var before: PowerLogSQLValue = elapsed.map(PowerLogSQLValue.real) ?? .integer(utc)
      var beforeID = Int64.max
      while true {
        let rows = try db.rows("SELECT m.*,o.original_timestamp,o.extra FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND (m.\(key),m.id)<=(?,?) ORDER BY m.\(key) DESC,m.id DESC LIMIT 64",
          [.text(event.workoutId), before, .integer(beforeID)], limit: 64)
        for row in rows {
          let lifecycle = try archive.store.decodeEvent(row, db: db).event
          switch lifecycle.payload["action"]?.string {
          case "start", "resume": return true
          case "pause", "stop": return false
          default: break
          }
        }
        guard rows.count == 64, let last = rows.last, let rowID = last.int("id") else { return false }
        before = last[key]; beforeID = rowID - 1
      }
    }
  }
}

/// Bounded, file-backed admission and single-consumer scheduling shared by WC callbacks and retries.
/// Returning false never acknowledges a chunk: its sender retains the canonical source for replay.
final class WorkoutChunkInbox {
  static let maximumFiles = 8
  static let maximumBytes = 4 * WorkoutChunkCodec.maximumBytes
  struct Item: Codable {
    let identity: String
    let bytes: Int
    let contentHash: String
    let metadata: Data
    var retryAfter: Double = 0
    var admissionTurn: Int64?
    var failures: Int?
    var workoutID: String? {
      ((try? JSONSerialization.jsonObject(with: metadata)) as? [String: Any])?["workoutId"] as? String
    }
  }
  let root: URL
  private let store: PowerLogStore
  private let lock = NSLock()
  private var active: String?
  private var claimedItem: Item?
  private var preferredWorkoutID: String?
  private var consecutivePreferredClaims = 0
  init(root: URL, store: PowerLogStore) throws {
    self.root = root; self.store = store
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try removeOrphans()
  }
  /// Set only by the native workout lifecycle; synchronous callbacks never consult engine/UI state.
  func setPreferredWorkoutID(_ id: String?) {
    lock.lock(); defer { lock.unlock() }
    if preferredWorkoutID != id { consecutivePreferredClaims = 0 }
    preferredWorkoutID = id
  }
  private func items() throws -> [Item] {
    try store.read { db in try db.page(namespace: "incoming-chunks", limit: Self.maximumFiles).map { try JSONDecoder().decode(Item.self, from: $0.value) } }
  }
  private func removeOrphans() throws {
    let retained = Set(try items().map { $0.identity + ".plchunk" })
    if let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil, options: [.skipsSubdirectoryDescendants]) {
      for case let url as URL in files where url.pathExtension == "plchunk" &&
        !retained.contains(url.lastPathComponent) && url.deletingPathExtension().lastPathComponent != active {
        try FileManager.default.removeItem(at: url)
      }
    }
  }
  func url(_ item: Item) -> URL { root.appendingPathComponent(item.identity).appendingPathExtension("plchunk") }
  @discardableResult
  func stage(file: URL, metadata: [String: Any]) throws -> Bool {
    guard let size = try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber,
      size.int64Value > 0, size.int64Value <= Int64(WorkoutChunkCodec.maximumBytes) else {
      throw WorkoutDataError.invalid("Incoming file exceeds bound")
    }
    return try stage(data: Data(contentsOf: file), metadata: metadata)
  }
  @discardableResult
  func stage(data: Data, metadata: [String: Any]) throws -> Bool {
    lock.lock(); defer { lock.unlock() }
    guard metadata["kind"] as? String == "workoutChunk", let raw = metadata["manifest"] as? [String: Any] else { throw WorkoutDataError.invalid("Missing incoming manifest") }
    let encoded = try JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys])
    guard encoded.count <= 16_384 else { throw WorkoutDataError.invalid("Incoming metadata exceeds bound") }
    let manifest = try JSONDecoder().decode(WorkoutChunkManifest.self, from: JSONSerialization.data(withJSONObject: raw))
    guard metadata["chunkIdentity"] as? String == manifest.identity, metadata["workoutId"] as? String == manifest.workoutID else {
      throw WorkoutDataError.invalid("Invalid incoming chunk identity")
    }
    try WorkoutChunkCodec.validate(manifest, data: data)
    if try store.isWorkoutDeleted(id: manifest.workoutID) { return true }
    try removeOrphans()
    var current = try items()
    // Deletion cleanup may reclaim the ledger before its in-flight import releases the file.
    // Keep that protected file in count/byte admission until the claimed operation finishes.
    if let claimedItem, !current.contains(where: { $0.identity == claimedItem.identity }),
      FileManager.default.fileExists(atPath: url(claimedItem).path) { current.append(claimedItem) }
    if let existing = current.first(where: { $0.identity == manifest.identity }) {
      guard existing.contentHash == manifest.contentHash, existing.bytes == data.count else { throw WorkoutDataError.invalid("Changed staged chunk identity") }
      // A crash/storage cleanup may preserve the ledger but remove its regenerable transport file.
      if !FileManager.default.fileExists(atPath: url(existing).path) { try write(data, to: url(existing)) }
      return true
    }
    let preferred = preferredWorkoutID == manifest.workoutID
    func fits(_ items: [Item]) -> Bool {
      guard items.count < Self.maximumFiles,
        items.reduce(0, { $0 + $1.bytes }) + data.count <= Self.maximumBytes else { return false }
      if preferred { return true }
      let historical = items.filter { preferredWorkoutID == nil || $0.workoutID != preferredWorkoutID }
      return historical.count < Self.maximumFiles - 1 &&
        historical.reduce(0, { $0 + $1.bytes }) + data.count <= Self.maximumBytes - WorkoutChunkCodec.maximumBytes
    }
    if !fits(current) {
      let unknown = try store.read { try $0.scalarInt("SELECT count(*) FROM collections WHERE id=?", [.text(manifest.workoutID)]) == 0 }
      let candidates = current.filter {
        $0.identity != active && (preferredWorkoutID == nil || $0.workoutID != preferredWorkoutID) &&
          (preferred || (unknown && ($0.failures ?? 0) > 0))
      }.sorted {
        let lhsFailed = ($0.failures ?? 0) > 0, rhsFailed = ($1.failures ?? 0) > 0
        if lhsFailed != rhsFailed { return lhsFailed }
        return ($0.admissionTurn ?? 0) > ($1.admissionTurn ?? 0)
      }
      var displaced: [Item] = []
      for candidate in candidates where !fits(current) {
        current.removeAll { $0.identity == candidate.identity }; displaced.append(candidate)
      }
      guard fits(current) else { return false }
      // Ledger first: an interrupted eviction leaves only an orphan, never a false pending receipt.
      try store.transaction(priority: .normal) { db in
        for item in displaced { try db.remove(namespace: "incoming-chunks", key: item.identity) }
      }
      for item in displaced where FileManager.default.fileExists(atPath: url(item).path) {
        try FileManager.default.removeItem(at: url(item))
      }
    }
    let item = try store.transaction(priority: .normal) { db -> Item in
      try store.requireWorkoutAvailable(id: manifest.workoutID)
      let turn = try db.nextSequence(namespace: "incoming-chunk-admission", key: "turn")
      return Item(identity: manifest.identity, bytes: data.count, contentHash: manifest.contentHash,
        metadata: encoded, admissionTurn: turn, failures: 0)
    }
    let destination = url(item)
    try write(data, to: destination)
    do { try store.transaction(priority: .normal) { db in
      try store.requireWorkoutAvailable(id: manifest.workoutID)
      try db.put(namespace: "incoming-chunks", key: item.identity, value: WorkoutCoding.encoder().encode(item), immutable: true)
    } }
    catch PowerLogStorageError.deleted { try? FileManager.default.removeItem(at: destination); return true }
    catch { try? FileManager.default.removeItem(at: destination); throw error }
    return true
  }
  private func write(_ data: Data, to destination: URL) throws {
    #if os(iOS) || os(watchOS)
    try data.write(to: destination, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    #else
    try data.write(to: destination, options: .atomic)
    #endif
  }
  /// Prefer the native active ride, but service the oldest due historical item after two active claims.
  func claim(now: Double = Date().timeIntervalSince1970) throws -> Item? {
    lock.lock(); defer { lock.unlock() }
    guard active == nil else { return nil }
    let due = try items().filter { $0.retryAfter <= now }.sorted {
      if $0.admissionTurn == $1.admissionTurn { return $0.identity < $1.identity }
      return ($0.admissionTurn ?? 0) < ($1.admissionTurn ?? 0)
    }
    let historical = due.first { preferredWorkoutID == nil || $0.workoutID != preferredWorkoutID }
    let priority = due.first { preferredWorkoutID != nil && $0.workoutID == preferredWorkoutID }
    guard let item = (consecutivePreferredClaims >= 2 ? historical ?? priority : priority ?? historical) else { return nil }
    if preferredWorkoutID != nil && item.workoutID == preferredWorkoutID { consecutivePreferredClaims += 1 }
    else { consecutivePreferredClaims = 0 }
    active = item.identity; claimedItem = item; return item
  }
  func finish(_ item: Item, success: Bool, now: Double = Date().timeIntervalSince1970) throws {
    lock.lock(); defer { lock.unlock() }
    guard active == item.identity else { return }
    defer { active = nil; claimedItem = nil }
    let deleted = try item.workoutID.map { try store.isWorkoutDeleted(id: $0) } ?? false
    try store.transaction(priority: .normal) { db in
      if success || deleted { try db.remove(namespace: "incoming-chunks", key: item.identity) }
      else {
        var pending = item; pending.retryAfter = now + 5; pending.failures = min(1_000_000, (pending.failures ?? 0) + 1)
        try db.put(namespace: "incoming-chunks", key: item.identity, value: WorkoutCoding.encoder().encode(pending))
      }
    }
    if success || deleted { try? FileManager.default.removeItem(at: url(item)) }
  }
  var pendingCount: Int { get throws { lock.lock(); defer { lock.unlock() }; return try items().count } }
}

/// A queued import cannot start native work after its bounded background ownership expires.
final class WorkoutImportLease {
  private let lock = NSLock()
  private var expired = false
  private let deadline: Double
  init(now: Double = ProcessInfo.processInfo.systemUptime, seconds: Double = 20) { deadline = now + seconds }
  func expire() { lock.lock(); expired = true; lock.unlock() }
  func permits(now: Double = ProcessInfo.processInfo.systemUptime) -> Bool {
    lock.lock(); defer { lock.unlock() }; return !expired && now < deadline
  }
}


/// One contiguous canonical cursor supplies BOTH live and recovered CYC traffic.
/// Packet admission and cursor advancement are one durable transaction, so offline backlog cannot be skipped.
private enum WorkoutForwardingError: Error { case sourceHole }

final class WorkoutTelemetryForwarder {
  private(set) var deferredSource = false
  let archive: WorkoutArchive
  let outbox: WorkoutBoundedOutbox
  init(archive: WorkoutArchive) { self.archive = archive; outbox = WorkoutBoundedOutbox(store: archive.store) }
  /// Verification is useful only for the current roster and immutable source boundary.
  /// No sample decoding or hashing is needed after that exact seal was verified.
  func isCurrentlyVerified(id: String) throws -> Bool {
    let transfer = WorkoutTransferJournal(archive: archive)
    guard let seal = try transfer.currentSeal(id: id), seal.resolved else { return false }
    let metadata = try archive.metadata(id: id)
    guard metadata.phase == "completed", metadata.sealRevision == seal.sealRevision,
      metadata.verifiedSealRevision == seal.sealRevision,
      metadata.finalizationState == (seal.partial ? "partial" : "complete"),
      Set(try transfer.roster(id: id)).isSubset(of: Set(seal.sources.map(\.producer))) else { return false }
    for source in seal.sources {
      let actual = try archive.sourceProgress(id: id, producer: source.producer)
      guard actual.count == source.count, actual.lastSequence == source.lastSequence,
        actual.count == actual.lastSequence else { return false }
    }
    return true
  }
  private func rememberVerified(id: String) throws -> Bool {
    try archive.store.transaction(priority: .background) { db in
      guard try isCurrentlyVerified(id: id) else { return false }
      // Any appended sample, roster update or metadata change advances this
      // revision and invalidates the cheap discovery exclusion automatically.
      try db.put(namespace: "telemetry-forward-verified", key: id,
        value: Data(String(try archive.revision(id: id)).utf8))
      return true
    }
  }
  /// Restoring an already verified archive does not request replay to a fresh Watch.
  /// Delete only redundant transport copies, never canonical observations or receipts.
  @discardableResult
  func pruneVerifiedPackets() throws -> Int {
    try archive.store.transaction(priority: .background) { db in
      var verified: [String: Bool] = [:], removed = 0
      for item in try outbox.packets() {
        guard let packet = try? JSONSerialization.jsonObject(with: item.value) as? [String: Any],
          packet["kind"] as? String == "events", let id = packet["workoutId"] as? String else { continue }
        if verified[id] == nil { verified[id] = try rememberVerified(id: id) }
        if verified[id] == true { try db.remove(namespace: "phone-outbox", key: item.key); removed += 1 }
      }
      return removed
    }
  }
  @discardableResult
  func stageNext(id: String) throws -> Bool {
    try archive.store.transaction(priority: .normal) { db in
      guard try archive.metadata(id: id).watchEnabled, try !rememberVerified(id: id) else { return false }
      let after = try db.get(namespace: "telemetry-forward-cursor", key: id).flatMap { String(data: $0, encoding: .utf8) }.flatMap(Int64.init) ?? 0
      let rows = try archive.pageEvents(id: id, afterSequence: after, limit: 16, producer: "cyc")
      guard !rows.isEmpty else { return false }
      guard rows.enumerated().allSatisfy({ $0.element.sequence == after + Int64($0.offset) + 1 }) else { throw WorkoutForwardingError.sourceHole }
      let last = rows.last!.sequence
      let packet: [String: Any] = ["schemaVersion": 1, "kind": "events", "workoutId": id,
        "messageId": WorkoutStableIdentity.uuid("cyc-packet:\(id):\(after + 1):\(last)"),
        "firstSequence": String(after + 1), "events": rows.map { $0.event.dictionary }]
      guard try outbox.enqueue(packet) else { return false }
      try db.put(namespace: "telemetry-forward-cursor", key: id, value: Data(String(last).utf8))
      let turn = try db.nextSequence(namespace: "telemetry-forward-scheduling", key: "turn")
      try db.put(namespace: "telemetry-forward-turn", key: id, value: Data(String(turn).utf8))
      return true
    }
  }
}

extension WorkoutTelemetryForwarder {
  /// Old collections remain discoverable after selection changes or process restart.
  @discardableResult
  func stageNextPending() throws -> Bool {
    deferredSource = false
    let pruned = try pruneVerifiedPackets()
    // Keyset-page the eligibility scan so complete historical records cannot hide
    // a later live producer; no unbounded collection list is materialized.
    var afterTurn: Int64 = -1, afterStart = "", afterID = ""
    while true {
      let rows = try archive.store.read { db in
        try db.rows("SELECT c.id,c.started_at,c.revision,coalesce(CAST(CAST(t.value AS TEXT) AS INTEGER),0) AS turn FROM collections c JOIN collection_sources s ON s.collection_id=c.id AND s.producer='cyc' LEFT JOIN durable_records r ON r.namespace='telemetry-forward-cursor' AND r.key=c.id LEFT JOIN durable_records t ON t.namespace='telemetry-forward-turn' AND t.key=c.id LEFT JOIN durable_records v ON v.namespace='telemetry-forward-verified' AND v.key=c.id LEFT JOIN durable_records b ON b.namespace='telemetry-forward-blocked' AND b.key=c.id WHERE c.kind='workout' AND json_extract(CAST(c.metadata AS TEXT),'$.watchEnabled')=1 AND s.last_sequence>coalesce(CAST(CAST(r.value AS TEXT) AS INTEGER),0) AND coalesce(CAST(CAST(v.value AS TEXT) AS INTEGER),-1)<>c.revision AND coalesce(CAST(CAST(b.value AS TEXT) AS INTEGER),-1)<>c.revision AND (coalesce(CAST(CAST(t.value AS TEXT) AS INTEGER),0),c.started_at,c.id)>(?,?,?) ORDER BY turn,c.started_at,c.id LIMIT 64", [.integer(afterTurn), .text(afterStart), .text(afterID)], limit: 64)
      }
      for row in rows {
        guard let id = row.string("id") else { continue }
        if try rememberVerified(id: id) { continue }
        do { return try stageNext(id: id) || pruned > 0 }
        catch WorkoutForwardingError.sourceHole {
          // An unchanged hole cannot heal through retries. Remember only a derived
          // revision fence; an import or original-data change automatically retries it.
          try archive.store.transaction(priority: .background) { db in
            try db.put(namespace: "telemetry-forward-blocked", key: id,
              value: Data(String(row.int("revision") ?? -1).utf8))
          }
          deferredSource = true
        }
      }
      guard rows.count == 64, let last = rows.last else { return pruned > 0 }
      afterTurn = last.int("turn") ?? 0; afterStart = last.string("started_at") ?? ""; afterID = last.string("id") ?? ""
    }
  }
}
