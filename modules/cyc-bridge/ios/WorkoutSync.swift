import Foundation

/// The full serialized envelope, including base64, fits the immediate WC data channel.
enum WorkoutChunkWire {
  static let maximumBytes = 60_000
  static func metadata(chunk: WorkoutChunk, startedAt: String, indoor: Bool, saveToHealth: Bool = true, recordGPS: Bool? = nil) -> [String: Any] {
    ["schemaVersion": 1, "kind": "workoutChunk", "workoutId": chunk.manifest.workoutID,
     "chunkIdentity": chunk.manifest.identity, "manifest": WorkoutCoding.dictionary(chunk.manifest),
     "startedAt": startedAt, "indoor": indoor, "saveToHealth": saveToHealth, "recordGPS": recordGPS ?? !indoor]
  }
  static func encode(chunk: WorkoutChunk, startedAt: String, indoor: Bool, saveToHealth: Bool = true, recordGPS: Bool? = nil) throws -> Data? {
    try WorkoutChunkCodec.validate(chunk.manifest, data: chunk.data)
    _ = try WorkoutCoding.date(startedAt)
    guard chunk.manifest.producer == "watch" else { throw WorkoutDataError.invalid("Live archival data must originate on Watch") }
    var packet = metadata(chunk: chunk, startedAt: startedAt, indoor: indoor, saveToHealth: saveToHealth, recordGPS: recordGPS)
    packet["data"] = chunk.data.base64EncodedString()
    let encoded = try JSONSerialization.data(withJSONObject: packet, options: [.sortedKeys])
    return encoded.count <= maximumBytes ? encoded : nil
  }
  static func decode(_ data: Data) throws -> (chunk: WorkoutChunk, metadata: [String: Any]) {
    guard data.count <= maximumBytes,
      var packet = try JSONSerialization.jsonObject(with: data) as? [String: Any],
      packet["schemaVersion"] as? Int == 1, packet["kind"] as? String == "workoutChunk",
      let raw = packet["manifest"] as? [String: Any], let startedAt = packet["startedAt"] as? String,
      packet["indoor"] as? Bool != nil, let encoded = packet.removeValue(forKey: "data") as? String,
      let bytes = Data(base64Encoded: encoded) else { throw WorkoutDataError.invalid("Invalid live archival envelope") }
    let manifest = try JSONDecoder().decode(WorkoutChunkManifest.self, from: JSONSerialization.data(withJSONObject: raw))
    guard manifest.producer == "watch", packet["workoutId"] as? String == manifest.workoutID,
      packet["chunkIdentity"] as? String == manifest.identity else { throw WorkoutDataError.invalid("Invalid live archival identity") }
    _ = try WorkoutCoding.date(startedAt)
    try WorkoutChunkCodec.validate(manifest, data: bytes)
    return (WorkoutChunk(manifest: manifest, data: bytes), packet)
  }
}

struct WorkoutChunkAttempt {
  let sendLive: Bool
  let sendFile: Bool
}

/// One immutable pending range per ride. Radio callbacks never advance this durable cursor.
final class WorkoutChunkSender {
  private struct State: Codable {
    let workoutID: String
    var acknowledged: Int64 = 0
    var manifest: WorkoutChunkManifest?
    var firstLiveAttempt: Double?
    var lastLiveAttempt: Double?
    var liveAttempts: Int = 0
    var lastFileAttempt: Double?
    var fileAttempts: Int = 0
  }
  let archive: WorkoutArchive
  init(archive: WorkoutArchive) { self.archive = archive }
  private func state(_ id: String, db: PowerLogDatabase) throws -> State {
    try archive.store.requireWorkoutAvailable(id: id)
    if let bytes = try db.get(namespace: "outgoing-watch-chunk", key: id) {
      let value = try JSONDecoder().decode(State.self, from: bytes)
      guard value.workoutID == id else { throw WorkoutDataError.invalid("Outgoing state belongs to another workout") }
      return value
    }
    // Earlier versions already retained a contiguous application-acknowledged source cursor.
    let previous = try db.get(namespace: "sent-progress", key: id + ":watch")
      .flatMap { String(data: $0, encoding: .utf8) }.flatMap(Int64.init) ?? 0
    guard previous >= 0, previous <= (try archive.sourceProgress(id: id, producer: "watch").lastSequence) else {
      throw WorkoutDataError.invalid("Invalid preserved Watch cursor")
    }
    return State(workoutID: id, acknowledged: previous)
  }
  private func save(_ state: State, db: PowerLogDatabase) throws {
    try db.put(namespace: "outgoing-watch-chunk", key: state.workoutID, value: WorkoutCoding.encoder().encode(state))
  }
  func acknowledgedSequence(id: String) throws -> Int64 { try archive.store.read { try state(id, db: $0).acknowledged } }
  func pending(id: String) throws -> WorkoutChunkManifest? { try archive.store.read { try state(id, db: $0).manifest } }
  func prepare(id: String) throws -> WorkoutChunk? {
    let (metadata, initial) = try archive.store.read { db in (try archive.metadata(id: id), try state(id, db: db)) }
    guard metadata.watchEnabled else { return nil }
    if let manifest = initial.manifest {
      let rows = try archive.pageEvents(id: id, afterSequence: manifest.firstSequence - 1, limit: manifest.count, producer: "watch")
      guard rows.count == manifest.count,
        rows.enumerated().allSatisfy({ $0.element.sequence == manifest.firstSequence + Int64($0.offset) }) else {
        throw WorkoutDataError.invalid("Pending Watch originals are incomplete")
      }
      let chunk = try WorkoutChunkCodec.encode(workoutID: id, producer: "watch", firstSequence: manifest.firstSequence, events: rows.map(\.event))
      guard chunk.manifest == manifest else { throw WorkoutDataError.invalid("Pending Watch chunk changed") }
      return chunk
    }
    // Paging, compression and wire-size fitting hold no write transaction or capture executor.
    guard var chunk = try WorkoutTransferJournal(archive: archive).nextChunk(id: id, producer: "watch", after: initial.acknowledged) else { return nil }
    var events: [WorkoutEvent]?
    while try WorkoutChunkWire.encode(chunk: chunk, startedAt: metadata.startedAt, indoor: metadata.indoor, saveToHealth: metadata.savesToHealth, recordGPS: metadata.recordsGPS) == nil && chunk.manifest.count > 1 {
      if events == nil { events = try WorkoutChunkCodec.decode(chunk) }
      events!.removeLast(max(1, events!.count / 4))
      chunk = try WorkoutChunkCodec.encode(workoutID: id, producer: "watch", firstSequence: chunk.manifest.firstSequence, events: events!)
    }
    return try archive.store.transaction(priority: .background) { db in
      var value = try state(id, db: db)
      // Another prepare/ACK may have won while encoding. Its manifest/cursor owns the next pump.
      guard value.manifest == nil, value.acknowledged == initial.acknowledged else { return nil }
      value.manifest = chunk.manifest
      value.firstLiveAttempt = nil; value.lastLiveAttempt = nil; value.liveAttempts = 0
      value.lastFileAttempt = nil; value.fileAttempts = 0
      try save(value, db: db)
      try db.put(namespace: "sent-chunks", key: chunk.manifest.identity, value: WorkoutCoding.encoder().encode(chunk.manifest), immutable: true)
      return chunk
    }
  }
  @discardableResult
  func acknowledge(id: String, producer: String, identity: String, lastSequence: Int64, contentHash: String) throws -> Bool {
    try archive.store.transaction(priority: .capture) { db in
      var value = try state(id, db: db)
      guard let manifest = value.manifest, producer == "watch", manifest.workoutID == id,
        manifest.identity == identity, manifest.lastSequence == lastSequence, manifest.contentHash == contentHash,
        manifest.firstSequence == value.acknowledged + 1 else { return false }
      value.acknowledged = manifest.lastSequence; value.manifest = nil
      value.firstLiveAttempt = nil; value.lastLiveAttempt = nil; value.liveAttempts = 0
      value.lastFileAttempt = nil; value.fileAttempts = 0
      try save(value, db: db)
      try db.put(namespace: "sent-progress", key: id + ":watch", value: Data(String(value.acknowledged).utf8))
      try db.put(namespace: "acknowledged-chunks", key: identity, value: WorkoutCoding.encoder().encode(manifest), immutable: true)
      return true
    }
  }
  /// Persist the attempt before effects: hung callbacks and process restart cannot create a tight loop.
  func attempt(id: String, reachable: Bool, liveFits: Bool, hasOutstandingFile: Bool,
               now: Double = Date().timeIntervalSince1970) throws -> WorkoutChunkAttempt {
    guard now.isFinite else { throw WorkoutDataError.invalid("Invalid transfer clock") }
    return try archive.store.transaction(priority: .background) { db in
      var value = try state(id, db: db)
      guard value.manifest != nil else { return WorkoutChunkAttempt(sendLive: false, sendFile: false) }
      func due(_ previous: Double?, attempts: Int) -> Bool {
        guard let previous else { return true }
        let delay = min(30, 3 * pow(2, Double(min(max(0, attempts - 1), 4))))
        return now < previous || now - previous >= delay
      }
      let live = reachable && liveFits && due(value.lastLiveAttempt, attempts: value.liveAttempts)
      let overdue = value.firstLiveAttempt.map { now < $0 || now - $0 >= 15 } ?? false
      let file = (!reachable || !liveFits || overdue) && !hasOutstandingFile && due(value.lastFileAttempt, attempts: value.fileAttempts)
      if live {
        if value.firstLiveAttempt == nil { value.firstLiveAttempt = now }
        value.lastLiveAttempt = now; value.liveAttempts = min(5, value.liveAttempts + 1)
      }
      if file { value.lastFileAttempt = now; value.fileAttempts = min(5, value.fileAttempts + 1) }
      if live || file { try save(value, db: db) }
      return WorkoutChunkAttempt(sendLive: live, sendFile: file)
    }
  }
  /// Capacity is separate from an attempted native submission; keep its retry at the first delay.
  func deferFile(id: String) throws {
    try archive.store.transaction(priority: .background) { db in
      var value = try state(id, db: db)
      value.fileAttempts = min(1, value.fileAttempts); try save(value, db: db)
    }
  }
}

/// Seal delivery remains eligible independently of the source chunk cursor and ended live session.
final class WorkoutSealSubmissionJournal {
  private struct State: Codable {
    let workoutID: String
    var acknowledgedRevision: Int64 = 0
    var submittedRevision: Int64 = 0
    var lastSubmission: Double?
  }
  private let archive: WorkoutArchive
  init(archive: WorkoutArchive) { self.archive = archive }
  private func state(_ id: String, db: PowerLogDatabase) throws -> State {
    try archive.store.requireWorkoutAvailable(id: id)
    return try db.get(namespace: "outgoing-watch-seal", key: id).map { try JSONDecoder().decode(State.self, from: $0) } ?? State(workoutID: id)
  }
  func shouldSubmit(id: String, revision: Int64, now: Double = Date().timeIntervalSince1970) throws -> Bool {
    guard now.isFinite else { throw WorkoutDataError.invalid("Invalid seal clock") }
    return try archive.store.read { db in
      guard revision > 0, try WorkoutTransferJournal(archive: archive).currentSeal(id: id)?.sealRevision == revision else { return false }
      let value = try state(id, db: db)
      guard value.acknowledgedRevision != revision else { return false }
      if value.submittedRevision != revision { return true }
      return value.lastSubmission.map { now < $0 || now - $0 >= 30 } ?? true
    }
  }
  func recordSubmission(id: String, revision: Int64, now: Double = Date().timeIntervalSince1970) throws {
    guard now.isFinite else { throw WorkoutDataError.invalid("Invalid seal clock") }
    try archive.store.transaction(priority: .background) { db in
      guard revision > 0, try WorkoutTransferJournal(archive: archive).currentSeal(id: id)?.sealRevision == revision else { return }
      var value = try state(id, db: db)
      value.submittedRevision = revision; value.lastSubmission = now
      try db.put(namespace: "outgoing-watch-seal", key: id, value: WorkoutCoding.encoder().encode(value))
    }
  }
  @discardableResult
  func acknowledge(id: String, revision: Int64) throws -> Bool {
    try archive.store.transaction(priority: .capture) { db in
      guard revision > 0, try WorkoutTransferJournal(archive: archive).currentSeal(id: id)?.sealRevision == revision else { return false }
      var value = try state(id, db: db)
      guard value.acknowledgedRevision != revision else { return false }
      value.acknowledgedRevision = revision
      try db.put(namespace: "outgoing-watch-seal", key: id, value: WorkoutCoding.encoder().encode(value))
      return true
    }
  }
}

/// HealthKit permits at most 100 KB in a rolling ten-second window. Reserve room for control traffic.
struct WorkoutTransmissionBudget {
  private var sent: [(time: TimeInterval, bytes: Int)] = []
  mutating func reserve(bytes: Int, now: TimeInterval) -> Bool {
    guard now.isFinite, bytes > 0, bytes <= 90_000 else { return false }
    sent.removeAll { now - $0.time >= 10 }
    guard sent.reduce(0, { $0 + $1.bytes }) + bytes <= 90_000 else { return false }
    sent.append((now, bytes)); return true
  }
}


enum WorkoutSyncPriority {
  static func phone(_ state: [String: Any]) -> String? {
    guard state["useWatch"] as? Bool == true, let id = state["id"] as? String,
      let phase = state["phase"] as? String else { return nil }
    if ["preparing", "running", "paused", "finishing", "recoverable"].contains(phase) { return id }
    guard phase == "completed" else { return nil }
    let seal = (state["sealRevision"] as? NSNumber)?.int64Value ?? 0
    let verified = (state["verifiedSealRevision"] as? NSNumber)?.int64Value ?? 0
    let resolved = ["complete", "partial"].contains(state["finalizationState"] as? String ?? "")
    return seal > 0 && seal == verified && resolved ? nil : id
  }
}
