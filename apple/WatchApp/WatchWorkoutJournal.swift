import Foundation

/// The Watch uses the same SQLite schema/executor as the phone. Files here are bounded transport staging only.
final class WatchWorkoutJournal {
  static let unavailableOwnerIssue = "The original owner is unavailable. Unlock Watch and check recovery again; no end time has been inferred."
  let archive: WorkoutArchive
  let store: PowerLogStore
  let control: WorkoutControlJournal
  let transfer: WorkoutTransferJournal
  let directory: URL
  var beforeSealReceiptCommit: (() throws -> Void)?
  var beforeSealPublicationCommit: (() throws -> Void)?
  private let background = DispatchQueue(label: "app.powerlog.watch.journal", qos: .userInitiated)
  init(rootURL: URL? = nil) throws {
    directory = try rootURL ?? FileManager.default.url(for: .applicationSupportDirectory,
      in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("PowerLog/watch-workouts", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    store = try PowerLogStore.shared(databaseURL: PowerLogStore.databaseURL(forRoot: directory))
    archive = try WorkoutArchive(rootURL: directory, store: store)
    control = WorkoutControlJournal(store: store); transfer = WorkoutTransferJournal(archive: archive)
    // Native outstanding transfers are available only after WC activation; reconcile files then.
  }
  /// Runs store work off the main actor; the store's own executor serializes access.
  func detached<T>(_ body: @escaping () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { continuation in
      background.async { continuation.resume(with: Result { try body() }) }
    }
  }
  func create(id: String, metadata: [String: Any]) throws {
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: id)
      guard try db.get(namespace: "watch-metadata", key: id) == nil else { throw WorkoutDataError.invalid("This ride already exists") }
      guard let date = metadata["startedAt"] as? String else { throw WorkoutDataError.invalid("Missing start time") }
      _ = try archive.create(id: id, startedAt: WorkoutCoding.date(date), indoor: metadata["indoor"] as? Bool ?? false, watchEnabled: true, saveToHealth: metadata["saveToHealth"] as? Bool ?? true, recordGPS: metadata["recordGPS"] as? Bool)
      try save(id: id, metadata: metadata)
      try transfer.register(id: id, producer: "watch")
    }
  }
  func save(id: String, metadata: [String: Any]) throws {
    guard metadata["workoutId"] as? String == id else { throw WorkoutDataError.invalid("Watch metadata belongs to another workout") }
    var metadata = metadata
    let record = try archive.metadata(id: id)
    try WorkoutRecordingPolicy.requireOptions(record, saveToHealth: metadata["saveToHealth"] as? Bool ?? true,
      recordGPS: metadata["recordGPS"] as? Bool ?? !(metadata["indoor"] as? Bool ?? false))
    metadata["saveToHealth"] = record.savesToHealth; metadata["recordGPS"] = record.recordsGPS
    if let phase = metadata["phase"] as? String { metadata["phase"] = WorkoutOwnerPhase.canonical(phase) }
    let bytes = try JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys])
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: id)
      try db.put(namespace: "watch-metadata", key: id, value: bytes)
      try archive.update(id: id, phase: metadata["phase"] as? String, healthKitState: metadata["healthKitState"] as? String,
        healthKitUUID: metadata["healthKitUUID"] as? String, stopElapsedSeconds: metadata["stopElapsedSeconds"] as? Double)
      if let cutoff = metadata["endedAt"] as? String {
        let existing = try archive.metadata(id: id).endedAt
        guard existing == nil || existing == cutoff else { throw WorkoutDataError.invalid("The original Watch cutoff is immutable") }
        try archive.finish(id: id, endedAt: WorkoutCoding.date(cutoff), finalPhase: metadata["phase"] as? String)
      }
    }
  }
  /// Repair only the projection derivable from this ride's durable owner snapshot.
  func reconcileOwnerMetadata(id: String) throws {
    guard let owner = try control.snapshot(workoutID: id), owner.owner == "watch" else { return }
    var saved = try metadata(id: id)
    saved["phase"] = owner.phase; saved["healthKitState"] = owner.healthOutcome
    saved["healthKitUUID"] = owner.healthWorkoutID; saved["ownerRevision"] = String(owner.ownerRevision)
    if let cutoff = owner.stopCutoff { saved["endedAt"] = cutoff }
    try save(id: id, metadata: saved)
  }
  /// Use only after native and saved-owner lookup found no owner, or cancellation just committed.
  /// The cancellation fence remains durable; only the resolved failure presentation changes.
  func reconcileCancelledStart(id: String) throws -> [String: Any]? {
    try store.transaction(priority: .capture) { db in
      guard let reason = try control.cancelledWithoutOwner(workoutID: id),
        try db.get(namespace: "watch-metadata", key: id) != nil else { return nil }
      var saved = try metadata(id: id)
      guard saved["endedAt"] == nil else { return nil }
      saved["phase"] = "failed"; saved["healthKitState"] = saved["saveToHealth"] as? Bool == false ? "notRequested" : "unknown"
      if Self.isResolvedCancellationIssue(saved["error"] as? String, reason: reason) { saved["error"] = nil }
      try save(id: id, metadata: saved)
      return saved
    }
  }
  static func isResolvedCancellationIssue(_ issue: String?, reason: String) -> Bool {
    guard let issue else { return false }
    return issue == unavailableOwnerIssue || issue == reason || issue == WorkoutOwnerAdmission.cancelledStopReason
  }
  func metadata(id: String) throws -> [String: Any] {
    try store.read { db in
      try store.requireWorkoutAvailable(id: id)
      guard let bytes = try db.get(namespace: "watch-metadata", key: id),
        let result = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw WorkoutDataError.invalid("Watch ride metadata is unavailable") }
      return result
    }
  }
  func allMetadata() throws -> [[String: Any]] {

    try store.read { db in
      var result: [[String: Any]] = []; var after: String? = nil
      while true {
        let page = try db.page(namespace: "watch-metadata", after: after ?? "", limit: 128)
        for item in page where try !store.isWorkoutDeleted(id: item.key) { if let value = try JSONSerialization.jsonObject(with: item.value) as? [String: Any] { result.append(value) } }
        guard page.count == 128 else { break }; after = page.last?.key
      }
      return result.sorted { ($0["startedAtMs"] as? Double ?? 0) > ($1["startedAtMs"] as? Double ?? 0) }
    }
  }
  func append(id: String, record: Data, synchronize: Bool = true) throws {
    let event = try JSONDecoder().decode(WorkoutEvent.self, from: record)
    guard event.workoutId == id else { throw WorkoutDataError.invalid("Wrong Watch collection") }
    try archive.append(event)
  }
  func contains(id: String, eventID: String) throws -> Bool { try archive.hasEvent(id: id, eventID: eventID) }
  func flush(id: String) throws { try archive.flush() }
  func commitHealthPage(id: String, events: [WorkoutEvent], progressKey: String, anchor: Data, completed: Bool) throws {
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: id)
      _ = try archive.appendBatch(events)
      try db.put(namespace: "health-query-progress", key: id + ":" + progressKey, value: anchor)
      try db.put(namespace: "health-query-completed", key: id + ":" + progressKey, value: Data([completed ? 1 : 0]))
    }
  }
  func healthAnchor(id: String, progressKey: String) throws -> Data? {
    try store.read { db in
      try store.requireWorkoutAvailable(id: id)
      return try db.get(namespace: "health-query-progress", key: id + ":" + progressKey)
    }
  }
  func stage(_ chunk: WorkoutChunk) throws -> URL {
    try store.requireWorkoutAvailable(id: chunk.manifest.workoutID)
    try WorkoutChunkCodec.validate(chunk.manifest, data: chunk.data)
    let url = directory.appendingPathComponent(chunk.manifest.identity).appendingPathExtension("plchunk")
    if FileManager.default.fileExists(atPath: url.path) {
      guard try Data(contentsOf: url) == chunk.data else { throw WorkoutDataError.invalid("Changed staged Watch chunk") }
      return url
    }
    let staged = try stagedChunks()
    guard staged.count < WorkoutChunkInbox.maximumFiles,
      staged.reduce(0, { $0 + $1.bytes }) + chunk.data.count <= WorkoutChunkInbox.maximumBytes else {
      throw WorkoutDataError.invalid("Watch transfer staging is full")
    }
    try store.transaction(priority: .normal) { db in
      try store.requireWorkoutAvailable(id: chunk.manifest.workoutID)
      try db.put(namespace: "sent-chunks", key: chunk.manifest.identity,
        value: WorkoutCoding.encoder().encode(chunk.manifest), immutable: true)
    }
    #if os(watchOS)
    try chunk.data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    #else
    try chunk.data.write(to: url, options: .atomic)
    #endif
    return url
  }
  static func syncPriorityID(metadata: [String: Any], nativeBusy: Bool) -> String? {
    guard let id = metadata["workoutId"] as? String else { return nil }
    if nativeBusy { return id }
    guard metadata["endedAt"] != nil else { return nil }
    let revision = metadata["sealRevision"] as? String
    return revision == nil || metadata["acknowledgedSealRevision"] as? String != revision ||
      metadata["archiveDirty"] as? Bool == true ? id : nil
  }
  func acknowledgeSeal(id: String, revision: Int64) throws -> [String: Any]? {
    try store.transaction(priority: .capture) { _ in
      guard try transfer.currentSeal(id: id)?.sealRevision == revision else { return nil }
      _ = try WorkoutSealSubmissionJournal(archive: archive).acknowledge(id: id, revision: revision)
      try beforeSealReceiptCommit?()
      var saved = try metadata(id: id)
      saved["acknowledgedSealRevision"] = String(revision)
      try save(id: id, metadata: saved)
      return saved
    }
  }
  func publishSeal(_ seal: WorkoutSeal, metadata: [String: Any]) throws -> [String: Any] {
    try store.transaction(priority: .normal) { _ in
      _ = try transfer.accept(seal: seal)
      try beforeSealPublicationCommit?()
      var saved = metadata
      saved["sealRevision"] = String(seal.sealRevision); saved["archiveDirty"] = false
      try save(id: seal.workoutID, metadata: saved)
      return saved
    }
  }
  struct StagedChunk { let identity: String; let workoutID: String?; let bytes: Int }
  func stagedChunks() throws -> [StagedChunk] {
    var result: [StagedChunk] = []
    let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
    for file in files where file.pathExtension == "plchunk" {
      let identity = file.deletingPathExtension().lastPathComponent
      let manifest = try store.read { db in try db.get(namespace: "sent-chunks", key: identity)
        .map { try JSONDecoder().decode(WorkoutChunkManifest.self, from: $0) } }
      result.append(StagedChunk(identity: identity, workoutID: manifest?.workoutID,
        bytes: try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0))
    }
    return result.sorted { $0.identity < $1.identity }
  }
  func reconcileChunks(referenced: Set<String>) throws {
    let sender = WorkoutChunkSender(archive: archive)
    for file in try stagedChunks() where !referenced.contains(file.identity) {
      if let id = file.workoutID, (try? sender.pending(id: id))?.identity == file.identity { continue }
      try removeChunk(file.identity)
    }
  }
  func reserveStaging(for chunk: WorkoutChunk, priorityID: String?, referenced: () -> Set<String>,
                      nativeCount: () -> Int, cancel: (String) -> Void) throws -> Bool {
    let isPriority = chunk.manifest.workoutID == priorityID
    let fileLimit = WorkoutChunkInbox.maximumFiles - (isPriority ? 0 : 1)
    let byteLimit = WorkoutChunkInbox.maximumBytes - (isPriority ? 0 : WorkoutChunkCodec.maximumBytes)
    func fits() throws -> Bool {
      let staged = try stagedChunks()
      let additional = staged.contains { $0.identity == chunk.manifest.identity } ? 0 : chunk.data.count
      return staged.count + (additional == 0 ? 0 : 1) <= fileLimit &&
        staged.reduce(0, { $0 + $1.bytes }) + additional <= byteLimit && nativeCount() < fileLimit
    }
    if try fits() { return true }
    guard isPriority else { return false }
    for old in try stagedChunks() where old.workoutID != priorityID && old.identity != chunk.manifest.identity {
      cancel(old.identity)
      guard !referenced().contains(old.identity) else { continue }
      try removeChunk(old.identity)
      if try fits() { return true }
    }
    return false
  }
  func removeChunk(_ identity: String) throws {
    guard identity.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { return }
    let url = directory.appendingPathComponent(identity).appendingPathExtension("plchunk")
    if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
  }
}

extension WatchWorkoutJournal {
  struct RecoveryProjection { let lapCount: Int; let events: [WorkoutEvent] }
  /// At most 48 decoded originals; history length cannot delay attaching the native session.
  func recoveryProjection(id: String, onDecode: (() -> Void)? = nil) throws -> RecoveryProjection {
    try store.read { db in
      try store.requireWorkoutAvailable(id: id)
      let laps = try db.scalarInt("SELECT count(*) FROM collection_memberships m JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.source='watch' AND l.action='lap'", [.text(id)]) ?? 0
      var events: [WorkoutEvent] = []
      for (kind, source) in [("telemetry", "cyc"), ("health", "watch"), ("location", "watch")] {
        let rows = try db.rows("SELECT m.*,o.original_timestamp,o.extra FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.kind=? AND m.source=? ORDER BY m.elapsed_seconds DESC,m.observation_id DESC LIMIT 16", [.text(id), .text(kind), .text(source)], limit: 16)
        for row in rows { onDecode?(); events.append(try store.decodeEvent(row, db: db).event) }
      }
      return RecoveryProjection(lapCount: Int(laps), events: events.sorted { $0.timestamp < $1.timestamp })
    }
  }
  func metadataPage(after: String = "", limit: Int = 8) throws -> [(id: String, value: [String: Any])] {
    try store.read { db in try db.rows("SELECT key,value FROM durable_records r WHERE namespace='watch-metadata' AND key>? AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=r.key) ORDER BY key LIMIT ?", [.text(after), .integer(Int64(limit))], limit: limit).map {
      guard let value = try JSONSerialization.jsonObject(with: $0.data("value")!) as? [String: Any] else { throw WorkoutDataError.invalid("Invalid Watch catalog metadata") }
      return ($0.string("key")!, value)
    } }
  }
}

extension WatchWorkoutJournal {
  /// Historical input commits into its own collection without selecting it as the active owner.
  func acceptTelemetry(_ events: [WorkoutEvent], firstSequence: Int64) throws {
    guard let id = events.first?.workoutId, events.allSatisfy({ $0.workoutId == id && $0.source == "cyc" && $0.kind == "telemetry" }) else { throw WorkoutDataError.invalid("Invalid CYC collection") }
    try store.transaction(priority: .capture) { _ in
      try transfer.receiveLive(events, producer: "cyc", firstSequence: firstSequence)
      var item = try metadata(id: id)
      item["archiveDirty"] = true; item["cycHealthSamplesIncomplete"] = try archive.metadata(id: id).savesToHealth
      try save(id: id, metadata: item)
    }
  }
}

extension WatchWorkoutJournal {
  func elapsed(id: String, date: Date) throws -> Double {
    max(0, date.timeIntervalSince(try WorkoutCoding.date(archive.metadata(id: id).startedAt)))
  }
}
