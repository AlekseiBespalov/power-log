import Foundation

/// Workout collection facade over canonical SQLite. Directories contain derived exports only.
final class WorkoutArchive {
  let rootURL: URL
  let store: PowerLogStore
  init(rootURL: URL, store: PowerLogStore? = nil) throws {
    self.rootURL = rootURL
    self.store = try store ?? PowerLogStore.shared(databaseURL: PowerLogStore.databaseURL(forRoot: rootURL))
    try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
    try self.store.recoverOnce(kind: "workout") {
      // Only a small catalog page is resident. Committed observations need no replay or repair.
      var after = ""
      while true {
        let ids = try self.store.read { db in try db.rows("SELECT id FROM collections WHERE kind='workout' AND phase IN ('running','paused','preparing','finishing') AND id>? AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=collections.id) ORDER BY id LIMIT 64", [.text(after)], limit: 64).compactMap { $0.string("id") } }
        if ids.isEmpty { break }
        for id in ids {
          try mutate(id: id) { m in
            m.phase = "recoverable"; m.interrupted = true
            let warning = "Workout was interrupted; recovery requires an explicit action."
            if !m.warnings.contains(warning) { m.warnings.append(warning) }
          }
        }
        after = ids.last!
      }
    }
  }
  func directory(id: String) throws -> URL {
    try store.requireWorkoutAvailable(id: id)
    let result = rootURL.appendingPathComponent(try WorkoutCoding.id(id), isDirectory: true)
    try FileManager.default.createDirectory(at: result, withIntermediateDirectories: true)
    return result
  }
  func create(id: String = UUID().uuidString, startedAt: Date, indoor: Bool, watchEnabled: Bool, saveToHealth: Bool = true, recordGPS: Bool? = nil, example: Bool = false) throws -> WorkoutMetadata {
    let id = try WorkoutCoding.id(id)
    var m = WorkoutMetadata(id: id, startedAt: WorkoutCoding.timestamp(startedAt), indoor: indoor, watchEnabled: watchEnabled)
    m.saveToHealth = saveToHealth; m.recordGPS = recordGPS ?? !indoor; m.example = example ? true : nil
    m.healthKitState = saveToHealth ? "notSaved" : "notRequested"
    m.watchSyncState = watchEnabled ? "pending" : "notRequired"; m.collectionRevision = 0
    try store.createCollection(id: id, kind: "workout", startedAt: m.startedAt, metadata: WorkoutCoding.encoder().encode(m))
    return m
  }
  func revision(id: String) throws -> Int64 { try store.collection(id: id).int("revision")! }
  func metadata(id: String, atRevision: Int64? = nil) throws -> WorkoutMetadata {
    try store.read { db in
      let row = try store.collection(id: id)
      guard row.string("kind") == "workout", let data = row.data("metadata") else { throw WorkoutDataError.invalid("Workout does not exist") }
      let currentRevision = row.int("revision")!
      guard atRevision == nil || (atRevision! >= 0 && atRevision! <= currentRevision) else { throw WorkoutDataError.invalid("Invalid workout revision") }
      if let atRevision, atRevision < currentRevision {
        guard let version = try db.rows("SELECT metadata FROM collection_versions WHERE collection_id=? AND revision<=? ORDER BY revision DESC LIMIT 1", [.text(try WorkoutCoding.id(id)), .integer(atRevision)], limit: 1).first?.data("metadata") else { throw WorkoutDataError.invalid("Workout metadata revision is unavailable") }
        var m = try JSONDecoder().decode(WorkoutMetadata.self, from: version)
        m.collectionRevision = atRevision
        m.eventCount = Int(try db.scalarInt("SELECT ordinal FROM collection_memberships WHERE collection_id=? AND revision<=? ORDER BY revision DESC LIMIT 1", [.text(m.id), .integer(atRevision)]) ?? 0)
        return m
      }
      var m = try JSONDecoder().decode(WorkoutMetadata.self, from: data)
      m.collectionRevision = currentRevision; m.eventCount = Int(row.int("event_count")!)
      m.phase = row.string("phase")!; m.endedAt = row.string("ended_at")
      return m
    }
  }
  func list(limit: Int = 100, beforeStartedAt: String? = nil, beforeID: String = "") throws -> [WorkoutMetadata] {
    try store.catalog(kind: "workout", beforeStartedAt: beforeStartedAt, beforeID: beforeID, limit: limit).map { row in
      var m = try JSONDecoder().decode(WorkoutMetadata.self, from: row.data("metadata")!)
      m.collectionRevision = row.int("revision"); m.eventCount = Int(row.int("event_count")!)
      m.phase = row.string("phase")!; m.endedAt = row.string("ended_at"); return m
    }
  }
  @discardableResult
  private func mutate(id: String, _ body: (inout WorkoutMetadata) throws -> Void) throws -> WorkoutMetadata {
    try store.transaction { db in
      var m = try metadata(id: id); try body(&m)
      let next = (m.collectionRevision ?? 0) + 1; m.collectionRevision = next
      let data = try WorkoutCoding.encoder().encode(m)
      guard data.count <= PowerLogStorageLimits.recordBytes else { throw WorkoutDataError.invalid("Workout metadata exceeds limit") }
      try db.execute("UPDATE collections SET metadata=?,started_at=?,ended_at=?,phase=?,revision=? WHERE id=?", [.blob(data), .text(m.startedAt), .optional(m.endedAt), .text(m.phase), .integer(next), .text(m.id)])
      try db.execute("INSERT INTO collection_versions(collection_id,revision,metadata) VALUES(?,?,?)", [.text(m.id), .integer(next), .blob(data)])
      try store.recordChange(db, id: m.id, revision: next, minUS: nil, maxUS: nil, kind: "metadata", metrics: [])
      return m
    }
  }
  @discardableResult
  func confirmStart(id: String, startedAt: Date) throws -> WorkoutMetadata {
    try mutate(id: id) { m in
      guard m.phase == "preparing", m.endedAt == nil else { throw WorkoutDataError.invalid("Only a preparing workout may confirm its start") }
      // The owner's originals can arrive before its status over another transport.
      // Confirm catalog metadata only: admitted timestamps and elapsed mappings are immutable.
      m.startedAt = WorkoutCoding.timestamp(startedAt)
    }
  }
  @discardableResult
  func update(id: String, phase: String? = nil, healthKitState: String? = nil, healthKitUUID: String? = nil,
              warnings: [String]? = nil, watchSyncState: String? = nil, sealRevision: Int64? = nil,
              verifiedSealRevision: Int64? = nil, finalizationState: String? = nil, stopElapsedSeconds: Double? = nil) throws -> WorkoutMetadata {
    try mutate(id: id) { m in
      if let phase {
        guard ["preparing","running","paused","finishing","recoverable","completed","awaitingWatchSync","failed"].contains(phase) else { throw WorkoutDataError.invalid("Invalid workout phase") }; m.phase = phase
      }
      if let healthKitState {
        guard (m.savesToHealth && m.healthKitState != "notRequested") || ["notRequested", "discarded"].contains(healthKitState) else { throw WorkoutDataError.invalid("Health saving was not requested for this ride") }
        guard healthKitState.utf8.count <= 128 else { throw WorkoutDataError.invalid("Invalid HealthKit save state") }; m.healthKitState = healthKitState }
      if let healthKitUUID { m.healthKitUUID = try WorkoutCoding.id(healthKitUUID) }
      if let watchSyncState { guard ["pending","received","notRequired"].contains(watchSyncState) else { throw WorkoutDataError.invalid("Invalid Watch synchronization state") }; m.watchSyncState = watchSyncState }
      if let warnings { guard warnings.count <= 64, warnings.allSatisfy({ $0.utf8.count <= 1024 }) else { throw WorkoutDataError.invalid("Workout warnings exceed limit") }; m.warnings = warnings }
      if let sealRevision { guard sealRevision > 0 else { throw WorkoutDataError.invalid("Invalid seal revision") }; m.sealRevision = sealRevision }
      if let verifiedSealRevision { guard verifiedSealRevision >= 0 else { throw WorkoutDataError.invalid("Invalid verified seal revision") }; m.verifiedSealRevision = verifiedSealRevision }
      if let finalizationState { guard ["pending","complete","partial"].contains(finalizationState) else { throw WorkoutDataError.invalid("Invalid finalization state") }; m.finalizationState = finalizationState }
      if let stopElapsedSeconds { guard stopElapsedSeconds.isFinite, stopElapsedSeconds >= 0, stopElapsedSeconds <= 2_678_400 else { throw WorkoutDataError.invalid("Invalid stop cutoff") }; m.stopElapsedSeconds = stopElapsedSeconds }
    }
  }
  func append(_ event: WorkoutEvent) throws { _ = try store.appendBatch([event]) }
  @discardableResult
  func appendBatch(_ events: [WorkoutEvent], producer: String? = nil, firstSequence: Int64? = nil) throws -> Int { try store.appendBatch(events, producer: producer, firstSequence: firstSequence) }
  func pageEvents(id: String, afterSequence: Int64 = 0, limit: Int = 256, producer: String? = nil, throughRevision: Int64? = nil) throws -> [PowerLogEventRecord] {
    try store.pageEvents(id: id, afterSequence: afterSequence, limit: limit, producer: producer, throughRevision: throughRevision)
  }
  func hasEvent(id: String, eventID: String) throws -> Bool { try store.hasEvent(id: id, eventID: eventID) }
  func associateHealthSamples(id: String, source: String, sampleIDs: [String], healthWorkoutID: String, at date: Date) throws {
    let metadata = try self.metadata(id: id)
    guard metadata.savesToHealth, ["watch", "phone"].contains(source) else {
      throw WorkoutDataError.invalid("Health association requires a saved-workout query")
    }
    let healthID = try WorkoutCoding.id(healthWorkoutID)
    let start = try WorkoutCoding.date(metadata.startedAt)
    var batch: [WorkoutEvent] = []
    for sampleID in Set(sampleIDs).sorted() {
      let sample = try WorkoutCoding.id(sampleID)
      let identity = WorkoutStableIdentity.uuid("health-association:\(id):\(source):\(healthID):\(sample)")
      batch.append(try WorkoutEvent(workoutId: id, kind: "health", source: source, timestamp: date,
        elapsedSeconds: max(0, date.timeIntervalSince(start)),
        payload: ["representation": .string("workoutAssociation"), "sampleUUID": .string(sample),
          "associatedWorkoutUUID": .string(healthID)], eventId: identity))
      if batch.count == 128 { _ = try appendBatch(batch); batch.removeAll(keepingCapacity: true) }
    }
    if !batch.isEmpty { _ = try appendBatch(batch) }
  }
  func sourceProgress(id: String, producer: String) throws -> (count: Int64, lastSequence: Int64) { try store.sourceProgress(id: id, producer: producer) }
  @discardableResult
  func finish(id: String, endedAt: Date, finalPhase: String? = nil) throws -> WorkoutMetadata {
    try mutate(id: id) { m in
      let phase = finalPhase ?? "completed"
      guard ["finishing","recoverable","completed","awaitingWatchSync","failed"].contains(phase) else { throw WorkoutDataError.invalid("Invalid final workout phase") }
      m.endedAt = WorkoutCoding.timestamp(endedAt); m.phase = phase
    }
  }
  /// Revision and keyset boundaries remain stable without retaining a SQLite snapshot between pages.
  func forEachEvent(id: String, revision: Int64? = nil, orderByTime: Bool = false, selectedOnly: Bool = false,
                    _ body: (WorkoutEvent) throws -> Void) throws {
    let id = try WorkoutCoding.id(id), chosenRevision = try revision ?? self.revision(id: id)
    guard chosenRevision >= 0, chosenRevision <= (try self.revision(id: id)) else { throw WorkoutDataError.invalid("Invalid event revision") }
    var cursor: PowerLogRow?
    while true {
      let page: [(PowerLogRow, WorkoutEvent)] = try store.read(priority: .background) { db in
        try store.requireWorkoutAvailable(id: id)
        let rank = "CASE WHEN m.kind='lifecycle' THEN 0 ELSE 1 END"
        var sql = "SELECT m.*,o.original_timestamp,o.extra,\(rank) AS rank FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.revision<=?"
        var values: [PowerLogSQLValue] = [.text(id), .integer(chosenRevision)]
        if selectedOnly {
          sql += " AND " + PowerLogStore.selectedMembershipSQL
          values += [.integer(chosenRevision), .integer(chosenRevision)]
        }
        if let cursor {
          if orderByTime {
            sql += " AND (m.elapsed_seconds,\(rank),m.event_id,m.id)>(?,?,?,?)"
            values += [cursor["elapsed_seconds"],cursor["rank"],cursor["event_id"],cursor["id"]]
          } else { sql += " AND m.id>?"; values.append(cursor["id"]) }
        }
        sql += orderByTime ? " ORDER BY m.elapsed_seconds,\(rank),m.event_id,m.id LIMIT 256" : " ORDER BY m.id LIMIT 256"
        return try db.rows(sql, values, limit: 256).map { ($0, try store.decodeEvent($0, db: db).event) }
      }
      if page.isEmpty { return }
      try autoreleasepool { for (_, event) in page { try body(event) } }
      cursor = page.last!.0
    }
  }
  /// Appends commit synchronously under FULL. A flush is a serialization barrier, not another fsync per frame.
  func flush() throws { try store.read(priority: .capture) { _ in () } }

  /// Run on the same serial file worker as exports/imports, after earlier admitted work.
  /// Cache eviction can defer completion without keeping a database transaction open.
  @discardableResult
  func cleanupDeletedWorkoutPage(id: String, cachesCleared: () -> Bool = { true }) throws -> Bool {
    let record = try store.cleanupWorkoutPage(id: id)
    let fm = FileManager.default
    for relative in record.files {
      let parts = relative.split(separator: "/")
      guard (parts.count == 1 || parts.count == 2 && parts[0] == "inbox"),
        let leaf = parts.last, leaf.hasSuffix(".plchunk"), leaf.dropLast(8).count == 64,
        leaf.dropLast(8).allSatisfy({ $0.isHexDigit }) else { throw WorkoutDataError.invalid("Invalid deleted staging path") }
      let file = rootURL.appendingPathComponent(relative)
      if fm.fileExists(atPath: file.path) { try fm.removeItem(at: file) }
    }
    if !record.files.isEmpty { try store.deletionFilesRemoved(id: id); return false }
    if record.cleanupComplete { return true }
    guard record.cleanupPhase == 10 else { return false }
    var stack = record.directoryStack ?? [record.id]
    // A crashed original export leaves nested staging. Visit one child at a time and
    // persist the bounded traversal stack; never follow links outside the owned directory.
    for _ in 0..<128 {
      guard let relative = stack.popLast() else { break }
      let components = relative.split(separator: "/")
      guard components.first.map(String.init) == record.id, !components.contains(".."), !components.contains("."), components.count <= 64 else {
        throw WorkoutDataError.invalid("Invalid deleted export path")
      }
      let file = rootURL.appendingPathComponent(relative)
      guard fm.fileExists(atPath: file.path) || (try? file.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true else { continue }
      let info = try file.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
      if info.isDirectory == true, info.isSymbolicLink != true {
        guard let children = fm.enumerator(at: file, includingPropertiesForKeys: nil, options: [.skipsSubdirectoryDescendants]) else {
          throw WorkoutDataError.invalid("Deleted exports could not be inspected")
        }
        if let child = children.nextObject() as? URL { stack.append(relative); stack.append(relative + "/" + child.lastPathComponent); continue }
      }
      try fm.removeItem(at: file)
    }
    try store.updateDeletionTraversal(id: id, stack: stack)
    guard stack.isEmpty else { return false }
    guard cachesCleared() else { return false }
    try store.deletionFilesRemoved(id: id, completed: true)
    return true
  }
}
