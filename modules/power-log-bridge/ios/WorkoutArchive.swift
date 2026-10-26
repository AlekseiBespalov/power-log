import Foundation
import PowerLogCore

final class WorkoutArchive {
  let rootURL: URL
  let store: PowerLogStore
  // Read and written only inside store jobs, which the store executor serializes.
  private var scopedRecords: [String: WorkoutMetadata]?
  init(rootURL: URL, store: PowerLogStore? = nil) throws {
    self.rootURL = rootURL
    self.store = try store ?? PowerLogStore.shared(databaseURL: PowerLogStore.databaseURL(forRoot: rootURL))
    try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
    try self.store.recoverOnce(kind: "workout") {
      var after = ""
      while true {
        let ids = try self.store.read { db in
          try db.rows(
            "SELECT id FROM collections WHERE kind='workout' AND phase IN ('running','paused','preparing','finishing') AND id>? AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=collections.id) ORDER BY id LIMIT 64",
            [.text(after)], limit: 64
          ).compactMap { $0.string("id") }
        }
        if ids.isEmpty { break }
        for id in ids {
          try mutate(id: id) { m in
            m.phase = "interrupted"
            m.interrupted = true
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
  func create(
    id: String = UUID().uuidString, startedAt: Date, indoor: Bool, watchEnabled: Bool, saveToHealth: Bool,
    recordGPS: Bool, sampleHz: Int, example: Bool = false, distanceSource: String = "auto",
    batteryConsumptionSource: String = "controller"
  ) throws -> WorkoutMetadata {
    try create(
      id: id, startedAt: WorkoutCoding.timestamp(startedAt), indoor: indoor, watchEnabled: watchEnabled,
      saveToHealth: saveToHealth, recordGPS: recordGPS, sampleHz: sampleHz, example: example,
      distanceSource: distanceSource, batteryConsumptionSource: batteryConsumptionSource)
  }
  func create(
    id: String = UUID().uuidString, startedAt: String, indoor: Bool, watchEnabled: Bool, saveToHealth: Bool,
    recordGPS: Bool, sampleHz: Int, example: Bool = false, distanceSource: String = "auto",
    batteryConsumptionSource: String = "controller"
  ) throws -> WorkoutMetadata {
    _ = try WorkoutCoding.timestampMicroseconds(startedAt)
    let id = try WorkoutCoding.id(id)
    var m = WorkoutMetadata(
      id: id, startedAt: startedAt, indoor: indoor, watchEnabled: watchEnabled,
      saveToHealth: saveToHealth, recordGPS: recordGPS, sampleHz: sampleHz)
    m.distanceSource = distanceSource == "auto" ? (recordGPS && !indoor ? "gps:phone" : "controller") : distanceSource
    m.batteryConsumptionSource = batteryConsumptionSource
    try m.validate()
    m.example = example ? true : nil
    m.healthKitState = saveToHealth ? "notSaved" : "notRequested"
    m.collectionRevision = 0
    try store.createCollection(
      id: id, kind: "workout", startedAt: m.startedAt, metadata: WorkoutCoding.encoder().encode(m))
    return m
  }
  func revision(id: String) throws -> Int64 { try store.collection(id: id).int("revision")! }
  /// Runs a read-only `body` as one store job that decodes each ride's current metadata once.
  func reading<T>(_ body: () throws -> T) throws -> T {
    try store.read { _ in
      guard scopedRecords == nil else { return try body() }
      scopedRecords = [:]
      defer { scopedRecords = nil }
      return try body()
    }
  }
  func metadata(id: String, atRevision: Int64? = nil) throws -> WorkoutMetadata {
    try store.read { db in
      if atRevision == nil, let scoped = scopedRecords?[id] { return scoped }
      let row = try store.collection(id: id)
      guard row.string("kind") == "workout", let data = row.data("metadata") else {
        throw WorkoutDataError.invalid("Workout does not exist")
      }
      let currentRevision = row.int("revision")!
      guard atRevision == nil || (atRevision! >= 0 && atRevision! <= currentRevision) else {
        throw WorkoutDataError.invalid("Invalid workout revision")
      }
      if let atRevision, atRevision < currentRevision {
        guard
          let version = try db.rows(
            "SELECT metadata FROM collection_versions WHERE collection_id=? AND revision<=? ORDER BY revision DESC LIMIT 1",
            [.text(try WorkoutCoding.id(id)), .integer(atRevision)], limit: 1
          ).first?.data("metadata")
        else { throw WorkoutDataError.invalid("Workout metadata revision is unavailable") }
        var m = try RideRecord.decode(version)
        m.elapsedSeconds = try measuredElapsed(m, revision: atRevision)
        m.collectionRevision = atRevision
        m.eventCount = Int(
          try db.scalarInt(
            "SELECT ordinal FROM collection_memberships WHERE collection_id=? AND revision<=? ORDER BY revision DESC LIMIT 1",
            [.text(m.id), .integer(atRevision)]) ?? 0)
        return try projectClosure(projectHealth(m, db: db))
      }
      var m = try RideRecord.decode(data)
      m.collectionRevision = currentRevision
      m.eventCount = Int(row.int("event_count")!)
      m.phase = row.string("phase")!
      m.endedAt = row.string("ended_at")
      m.elapsedSeconds = try measuredElapsed(m, revision: row.int("revision")!)
      let record = try projectClosure(projectHealth(m, db: db))
      if scopedRecords != nil { scopedRecords?[id] = record }
      return record
    }
  }
  func list(limit: Int = 100, beforeStartedAt: String? = nil, beforeID: String = "") throws -> [WorkoutMetadata] {
    try store.catalog(kind: "workout", beforeStartedAt: beforeStartedAt, beforeID: beforeID, limit: limit).map { row in
      var m = try RideRecord.decode(row.data("metadata")!)
      m.collectionRevision = row.int("revision")
      m.eventCount = Int(row.int("event_count")!)
      m.phase = row.string("phase")!
      m.endedAt = row.string("ended_at")
      m.elapsedSeconds = try measuredElapsed(m, revision: row.int("revision")!)
      return try store.read { try projectClosure(projectHealth(m, db: $0)) }
    }
  }
  private func projectClosure(_ metadata: WorkoutMetadata) throws -> WorkoutMetadata { metadata }
  private func projectHealth(_ metadata: WorkoutMetadata, db: PowerLogDatabase) throws -> WorkoutMetadata {
    metadata
  }
  private func measuredElapsed(_ metadata: WorkoutMetadata, revision: Int64) throws -> Double {
    try metadata.stopElapsedSeconds
      ?? max(
        metadata.elapsedSeconds, metadata.ownerTiming?.elapsedSeconds ?? 0,
        store.appElapsed(id: metadata.id, revision: revision))
  }
  @discardableResult
  private func mutate(
    id: String, keepsUnchanged: Bool = false, _ body: (inout WorkoutMetadata) throws -> Void
  ) throws -> WorkoutMetadata {
    try store.transaction { db in
      try store.requireWorkoutWritable(id: id)
      var m = try metadata(id: id)
      let before = m
      let original = keepsUnchanged ? try WorkoutCoding.encoder().encode(m) : nil
      try body(&m)
      try m.validate()
      if let original, try WorkoutCoding.encoder().encode(m) == original { return m }
      let kind = try Self.onlyTimingChanged(before, m) ? "timing" : "metadata"
      try commitRevision(&m, db: db, kind: kind)
      return m
    }
  }
  private static func onlyTimingChanged(_ before: WorkoutMetadata, _ after: WorkoutMetadata) throws -> Bool {
    guard before.ownerTiming != after.ownerTiming || before.elapsedSeconds != after.elapsedSeconds else { return false }
    var timed = before
    timed.ownerTiming = after.ownerTiming
    timed.elapsedSeconds = after.elapsedSeconds
    return try WorkoutCoding.encoder().encode(timed) == WorkoutCoding.encoder().encode(after)
  }
  private func commitRevision(_ m: inout WorkoutMetadata, db: PowerLogDatabase, kind: String) throws {
    if scopedRecords != nil { scopedRecords = [:] }
    let next = try PowerLogRevision.next(m.collectionRevision ?? 0)
    m.collectionRevision = next
    let data = try WorkoutCoding.encoder().encode(m)
    guard data.count <= PowerLogStorageLimits.recordBytes else {
      throw WorkoutDataError.invalid("Workout metadata exceeds limit")
    }
    try db.execute(
      "UPDATE collections SET metadata=?,started_at=?,ended_at=?,phase=?,revision=? WHERE id=?",
      [.blob(data), .text(m.startedAt), .optional(m.endedAt), .text(m.phase), .integer(next), .text(m.id)])
    try db.execute(
      "INSERT INTO collection_versions(collection_id,revision,metadata) VALUES(?,?,?)",
      [.text(m.id), .integer(next), .blob(data)])
    try store.recordChange(db, id: m.id, revision: next, minUS: nil, maxUS: nil, kind: kind, metrics: [])
  }
  @discardableResult
  func recordOwnerStart(id: String, startedAt: ProtocolTimestamp) throws -> WorkoutMetadata {
    try mutate(id: id) { $0.startedAt = startedAt.original }
  }

  @discardableResult
  func confirmStart(id: String, startedAt: Date) throws -> WorkoutMetadata {
    try confirmStart(id: id, startedAt: WorkoutCoding.timestamp(startedAt))
  }
  @discardableResult
  func confirmStart(id: String, startedAt: String) throws -> WorkoutMetadata {
    _ = try WorkoutCoding.timestampMicroseconds(startedAt)
    return try mutate(id: id) { m in
      guard m.phase == "preparing", m.endedAt == nil else {
        throw WorkoutDataError.invalid("Only a preparing workout may confirm its start")
      }
      // The owner's originals can arrive before its status over another transport.
      // Confirm catalog metadata only: admitted timestamps and elapsed mappings are immutable.
      m.startedAt = startedAt
    }
  }
  /// Every update advances the collection revision unless `onlyIfChanged` is set and no stored field changes.
  @discardableResult
  func update(
    id: String, phase: String? = nil, healthKitState: String? = nil, healthKitUUID: String? = nil,
    healthReason: String? = nil, stopElapsedSeconds: Double? = nil,
    ownerTiming: WorkoutOwnerTiming? = nil, elapsedSeconds: Double? = nil, onlyIfChanged: Bool = false
  ) throws -> WorkoutMetadata {
    try mutate(id: id, keepsUnchanged: onlyIfChanged) { m in
      if m.healthKitState == "unavailable", let healthKitState, healthKitState != "unavailable" {
        throw WorkoutDataError.invalid("Terminal Health unavailability cannot resume")
      }
      if let phase {
        guard ["preparing", "running", "paused", "finishing", "interrupted", "completed", "failed"].contains(phase)
        else { throw WorkoutDataError.invalid("Invalid workout phase") }
        m.phase = phase
      }
      if let healthKitState {
        if ["saved", "discarded"].contains(m.healthKitState), ["pending", "notSaved"].contains(healthKitState) {
          return
        }
        guard
          (m.saveToHealth && m.healthKitState != "notRequested")
            || ["notRequested", "discarded"].contains(healthKitState)
        else { throw WorkoutDataError.invalid("Health saving was not requested for this ride") }
        guard HealthOutcome.entries.contains(where: { $0.name == healthKitState }) else {
          throw WorkoutDataError.invalid("Invalid HealthKit save state")
        }
        m.healthKitState = healthKitState
        if ["unavailable", "discarded"].contains(healthKitState) {
          m.nativeHealthEvidence = WorkoutNativeHealthEvidence(
            outcome: healthKitState, reason: healthReason ?? m.nativeHealthEvidence?.reason ?? "nativeOwnerLost")
        }
      }
      if let healthKitUUID { m.healthKitUUID = try WorkoutCoding.id(healthKitUUID) }
      if let healthReason { m.healthReason = String(healthReason.prefix(500)) }
      if let healthReason, var evidence = m.nativeHealthEvidence {
        evidence.reason = String(healthReason.prefix(500))
        m.nativeHealthEvidence = evidence
      }
      if ["saved", "notRequested", "discarded"].contains(m.healthKitState) { m.healthReason = nil }
      if let ownerTiming {
        try ownerTiming.validate()
        if m.endedAt != nil, let previous = m.ownerTiming, previous != ownerTiming {
          throw WorkoutDataError.invalid("The retained terminal timing is immutable")
        }
        m.ownerTiming = ownerTiming
      }
      if let elapsedSeconds {
        guard elapsedSeconds.isFinite, elapsedSeconds >= 0, elapsedSeconds <= 2_678_400 else {
          throw WorkoutDataError.invalid("Invalid catalog elapsed time")
        }
        m.elapsedSeconds = elapsedSeconds
      }
      if let stopElapsedSeconds {
        guard stopElapsedSeconds.isFinite, stopElapsedSeconds >= 0, stopElapsedSeconds <= 2_678_400 else {
          throw WorkoutDataError.invalid("Invalid stop cutoff")
        }
        m.stopElapsedSeconds = stopElapsedSeconds
        m.elapsedSeconds = stopElapsedSeconds
      }
    }
  }
  func append(_ event: WorkoutEvent) throws { _ = try store.appendBatch([event]) }
  @discardableResult
  func appendBatch(_ events: [WorkoutEvent], producer: String? = nil, firstSequence: Int64? = nil) throws -> Int {
    try store.appendBatch(events, producer: producer, firstSequence: firstSequence)
  }
  func pageEvents(
    id: String, afterSequence: Int64 = 0, limit: Int = 256, producer: String? = nil, throughRevision: Int64? = nil
  ) throws -> [PowerLogEventRecord] {
    try store.pageEvents(
      id: id, afterSequence: afterSequence, limit: limit, producer: producer, throughRevision: throughRevision)
  }
  func hasEvent(id: String, eventID: String) throws -> Bool { try store.hasEvent(id: id, eventID: eventID) }
  /// Builds association originals without the event schema check, which the store's append runs once for each.
  func healthAssociationEvent(
    id: String, ride: WorkoutMetadata, source: String, healthWorkoutID: String, at date: Date
  ) throws -> (String) throws -> WorkoutEvent {
    guard ride.saveToHealth, ["watch", "phone"].contains(source) else {
      throw WorkoutDataError.invalid("Health association requires a saved-workout query")
    }
    let healthID = try WorkoutCoding.id(healthWorkoutID)
    let workoutID = try WorkoutCoding.id(id)
    let timestamp = WorkoutCoding.timestamp(date)
    let elapsed = max(0, date.timeIntervalSince(try WorkoutCoding.date(ride.startedAt)))
    return { sampleID in
      let sample = try WorkoutCoding.id(sampleID)
      return try WorkoutEvent(
        storedEventID: WorkoutCoding.id(
          WorkoutStableIdentity.uuid("health-association:\(id):\(source):\(healthID):\(sample)")),
        workoutID: workoutID, kind: "health", source: source, originalTimestamp: timestamp, elapsedSeconds: elapsed,
        payload: [
          "representation": .string("workoutAssociation"), "sampleUUID": .string(sample),
          "associatedWorkoutUUID": .string(healthID),
        ])
    }
  }
  func associateHealthSamples(id: String, source: String, sampleIDs: [String], healthWorkoutID: String, at date: Date)
    throws
  {
    let event = try healthAssociationEvent(
      id: id, ride: metadata(id: id), source: source, healthWorkoutID: healthWorkoutID, at: date)
    var batch: [WorkoutEvent] = []
    for sampleID in Set(sampleIDs).sorted() {
      batch.append(try event(sampleID))
      if batch.count == 128 {
        _ = try appendBatch(batch)
        batch.removeAll(keepingCapacity: true)
      }
    }
    if !batch.isEmpty { _ = try appendBatch(batch) }
  }
  func sourceProgress(id: String, producer: String) throws -> (count: Int64, lastSequence: Int64) {
    try store.sourceProgress(id: id, producer: producer)
  }
  @discardableResult
  func finish(id: String, endedAt: Date, finalPhase: String? = nil) throws -> WorkoutMetadata {
    try finish(id: id, endedAt: WorkoutCoding.timestamp(endedAt), finalPhase: finalPhase)
  }
  @discardableResult
  func finish(id: String, endedAt: String, finalPhase: String? = nil) throws -> WorkoutMetadata {
    _ = try WorkoutCoding.timestampMicroseconds(endedAt)
    return try mutate(id: id) { m in
      let phase = finalPhase ?? "completed"
      guard ["finishing", "interrupted", "completed", "failed"].contains(phase) else {
        throw WorkoutDataError.invalid("Invalid final workout phase")
      }
      m.endedAt = endedAt
      m.phase = phase
    }
  }
  /// Revision and keyset boundaries remain stable without retaining a SQLite snapshot between pages.
  func forEachEvent(
    id: String, revision: Int64? = nil, orderByTime: Bool = false, selectedOnly: Bool = false,
    _ body: (WorkoutEvent) throws -> Void
  ) throws {
    try forEachRecord(id: id, revision: revision, orderByTime: orderByTime, selectedOnly: selectedOnly) {
      try body($0.event)
    }
  }
  func forEachRecord(
    id: String, revision: Int64? = nil, orderByTime: Bool = false, selectedOnly: Bool = false,
    _ body: (PowerLogEventRecord) throws -> Void
  ) throws {
    let id = try WorkoutCoding.id(id)
    let chosenRevision = try revision ?? self.revision(id: id)
    guard chosenRevision >= 0, chosenRevision <= (try self.revision(id: id)) else {
      throw WorkoutDataError.invalid("Invalid event revision")
    }
    var cursor: PowerLogRow?
    while true {
      let page: [(PowerLogRow, PowerLogEventRecord)] = try store.read(priority: .background) { db in
        try store.requireWorkoutAvailable(id: id)
        let rank = "CASE WHEN m.kind='lifecycle' THEN 0 ELSE 1 END"
        var sql =
          "SELECT m.*,o.original_timestamp,o.extra,\(rank) AS rank FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.revision<=?"
        var values: [PowerLogSQLValue] = [.text(id), .integer(chosenRevision)]
        if selectedOnly {
          sql += " AND " + PowerLogStore.selectedMembershipSQL
          values += [.integer(chosenRevision), .integer(chosenRevision)]
        }
        if let cursor {
          if orderByTime {
            sql += " AND (m.elapsed_seconds,\(rank),m.producer,m.sequence,m.id)>(?,?,?,?,?)"
            values += [cursor["elapsed_seconds"], cursor["rank"], cursor["producer"], cursor["sequence"], cursor["id"]]
          } else {
            sql += " AND m.id>?"
            values.append(cursor["id"])
          }
        }
        sql +=
          orderByTime
          ? " ORDER BY m.elapsed_seconds,\(rank),m.producer,m.sequence,m.id LIMIT 256" : " ORDER BY m.id LIMIT 256"
        return try db.rows(sql, values, limit: 256).map { ($0, try store.decodeEvent($0, db: db)) }
      }
      if page.isEmpty { return }
      try autoreleasepool { for (_, event) in page { try body(event) } }
      cursor = page.last!.0
    }
  }
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
        leaf.dropLast(8).allSatisfy({ $0.isHexDigit })
      else { throw WorkoutDataError.invalid("Invalid deleted staging path") }
      let file = rootURL.appendingPathComponent(relative)
      if fm.fileExists(atPath: file.path) { try fm.removeItem(at: file) }
    }
    if !record.files.isEmpty {
      try store.deletionFilesRemoved(id: id)
      return false
    }
    if record.cleanupComplete { return true }
    guard record.cleanupPhase == 10 else { return false }
    var stack = record.directoryStack ?? [record.id]
    // A crashed original export leaves nested staging. Visit one child at a time and
    // persist the bounded traversal stack; never follow links outside the owned directory.
    for _ in 0..<128 {
      guard let relative = stack.popLast() else { break }
      let components = relative.split(separator: "/")
      guard components.first.map(String.init) == record.id, !components.contains(".."), !components.contains("."),
        components.count <= 64
      else {
        throw WorkoutDataError.invalid("Invalid deleted export path")
      }
      let file = rootURL.appendingPathComponent(relative)
      guard
        fm.fileExists(atPath: file.path)
          || (try? file.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true
      else { continue }
      let info = try file.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
      if info.isDirectory == true, info.isSymbolicLink != true {
        guard
          let children = fm.enumerator(
            at: file, includingPropertiesForKeys: nil, options: [.skipsSubdirectoryDescendants])
        else {
          throw WorkoutDataError.invalid("Deleted exports could not be inspected")
        }
        if let child = children.nextObject() as? URL {
          stack.append(relative)
          stack.append(relative + "/" + child.lastPathComponent)
          continue
        }
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

extension WorkoutArchive {
  static func completionProjection(_ fields: [String: Any]) throws -> RideCompletionSnapshot {
    try CompletionCodec.shared.decodeSnapshot(
      json: String(decoding: JSONSerialization.data(withJSONObject: fields), as: UTF8.self))
  }

  func completion(id: String) throws -> RideCompletionSnapshot {
    let record = try metadata(id: id)
    let fields = try record.dictionary.merging(healthProjection(id: id)) { _, new in new }
    return try Self.completionProjection(fields)
  }

  func healthProjection(id: String) throws -> [String: Any] {
    let record = try metadata(id: id)
    return [
      "componentResults": [], "recordingOwner": "phone", "healthPublisher": record.watchEnabled ? "watch" : "phone",
    ]
  }
}
