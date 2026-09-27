import Foundation

/// The Watch uses the same SQLite schema/executor as the phone. Files here are bounded transport staging only.
final class WatchWorkoutJournal {
  static let unavailableOwnerIssue =
    "The original owner is unavailable. Unlock Watch and check recovery again; no end time has been inferred."
  let archive: WorkoutArchive
  let store: PowerLogStore
  let control: WorkoutControlJournal
  let transfer: WorkoutTransferJournal
  let directory: URL
  var beforeSealReceiptCommit: (() throws -> Void)?
  var beforeSealPublicationCommit: (() throws -> Void)?
  private let background = DispatchQueue(label: "app.powerlog.watch.journal", qos: .userInitiated)
  init(rootURL: URL? = nil) throws {
    directory =
      try rootURL
      ?? FileManager.default.url(
        for: .applicationSupportDirectory,
        in: .userDomainMask, appropriateFor: nil, create: true
      ).appendingPathComponent("PowerLog/watch-workouts", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    store = try PowerLogStore.shared(databaseURL: PowerLogStore.databaseURL(forRoot: directory))
    archive = try WorkoutArchive(rootURL: directory, store: store)
    control = WorkoutControlJournal(store: store)
    transfer = WorkoutTransferJournal(archive: archive)
    // Native outstanding transfers are available only after WC activation; reconcile files then.
  }
  /// Runs store work off the main actor; the store's own executor serializes access.
  func detached<T>(_ body: @escaping () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { continuation in
      background.async { continuation.resume(with: Result { try body() }) }
    }
  }
  func create(id: String, metadata: [String: Any]) throws {
    let values = try Self.validateMetadata(metadata, id: id)
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: id)
      guard try db.get(namespace: "watch-metadata", key: id) == nil else {
        throw WorkoutDataError.invalid("This ride already exists")
      }
      _ = try archive.create(
        id: id, startedAt: values.startedAt, indoor: metadata["indoor"] as? Bool ?? false, watchEnabled: true,
        saveToHealth: values.saveToHealth, recordGPS: values.recordGPS)
      try save(id: id, metadata: metadata)
      try transfer.register(id: id, producer: "watch")
    }
  }
  func save(id: String, metadata: [String: Any]) throws {
    let values = try Self.validateMetadata(metadata, id: id)
    let record = try archive.metadata(id: id)
    try WorkoutRecordingPolicy.requireOptions(record, saveToHealth: values.saveToHealth, recordGPS: values.recordGPS)
    let bytes = try JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys])
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: id)
      if let previous = try db.get(namespace: "watch-metadata", key: id),
        let saved = try JSONSerialization.jsonObject(with: previous) as? [String: Any], saved["endedAt"] != nil
      {
        guard try Self.timing(saved) == Self.timing(metadata) else {
          throw WorkoutDataError.invalid("The original Watch cutoff timing is immutable")
        }
      }
      try db.put(namespace: "watch-metadata", key: id, value: bytes)
      if let reason = metadata["error"] as? String {
        try WorkoutHealthWriteBounds.report(reason, id: id, archive: archive)
      }
      try archive.update(
        id: id, phase: metadata["phase"] as? String, healthKitState: metadata["healthKitState"] as? String,
        healthKitUUID: metadata["healthKitUUID"] as? String,
        stopElapsedSeconds: metadata["stopElapsedSeconds"] as? Double,
        ownerTiming: metadata["endedAt"] == nil ? nil : Self.timing(metadata),
        elapsedSeconds: Self.timing(metadata)?.elapsedSeconds)
      if let cutoff = metadata["endedAt"] as? String {
        let existing = try archive.metadata(id: id).endedAt
        guard existing == nil || existing == cutoff else {
          throw WorkoutDataError.invalid("The original Watch cutoff is immutable")
        }
        try archive.finish(id: id, endedAt: WorkoutCoding.date(cutoff), finalPhase: metadata["phase"] as? String)
      }
    }
  }
  /// Repair only the projection derivable from this ride's durable owner snapshot.
  func reconcileOwnerMetadata(id: String) throws {
    guard let owner = try control.snapshot(workoutID: id), owner.owner == "watch" else { return }
    var saved = try metadata(id: id)
    saved["phase"] = owner.phase
    saved["healthKitState"] = owner.healthOutcome
    saved["healthKitUUID"] = owner.healthWorkoutID
    if let reason = owner.healthReason { saved["error"] = reason }
    saved["ownerRevision"] = String(owner.ownerRevision)
    if let cutoff = owner.stopCutoff {
      guard let timing = owner.timing, timing.timestamp == cutoff else {
        throw WorkoutDataError.invalid("The original Watch cutoff timing is unavailable")
      }
      Self.retain(timing, in: &saved, terminal: true)
    }
    try save(id: id, metadata: saved)
  }
  /// Use only after native and saved-owner lookup found no owner, or cancellation just committed.
  /// The cancellation fence remains durable; only the resolved failure presentation changes.
  func reconcileCancelledStart(id: String) throws -> [String: Any]? {
    try store.transaction(priority: .capture) { db in
      guard let reason = try control.cancelledWithoutOwner(workoutID: id),
        try db.get(namespace: "watch-metadata", key: id) != nil
      else { return nil }
      var saved = try metadata(id: id)
      guard saved["endedAt"] == nil else { return nil }
      saved["phase"] = "failed"
      saved["healthKitState"] = saved["saveToHealth"] as? Bool == false ? "notRequested" : "unknown"
      if Self.isResolvedCancellationIssue(saved["error"] as? String, reason: reason) { saved["error"] = nil }
      try save(id: id, metadata: saved)
      return saved
    }
  }
  static func isResolvedCancellationIssue(_ issue: String?, reason: String) -> Bool {
    guard let issue else { return false }
    return issue == unavailableOwnerIssue || issue == reason || issue == WorkoutOwnerAdmission.cancelledStopReason
  }
  private static func validateMetadata(_ metadata: [String: Any], id: String) throws -> (
    startedAt: Date, saveToHealth: Bool, recordGPS: Bool
  ) {
    guard metadata["workoutId"] as? String == id else {
      throw WorkoutDataError.invalid("Watch metadata belongs to another workout")
    }
    guard let start = metadata["startedAt"] as? String,
      let saveToHealth = metadata["saveToHealth"] as? Bool, let recordGPS = metadata["recordGPS"] as? Bool
    else {
      throw WorkoutDataError.invalid("Watch metadata is missing its start or recording options")
    }
    _ = try timing(metadata)
    _ = try timeline(metadata)
    return (try WorkoutCoding.date(start), saveToHealth, recordGPS)
  }
  func metadata(id: String) throws -> [String: Any] {
    try store.read { db in
      try store.requireWorkoutAvailable(id: id)
      guard let bytes = try db.get(namespace: "watch-metadata", key: id),
        let result = try JSONSerialization.jsonObject(with: bytes) as? [String: Any]
      else { throw WorkoutDataError.invalid("Watch ride metadata is unavailable") }
      _ = try Self.validateMetadata(result, id: id)
      return result
    }
  }
  func allMetadata() throws -> [[String: Any]] {

    try store.read { db in
      var result: [(startedAt: Date, metadata: [String: Any])] = []
      var after: String? = nil
      while true {
        let page = try db.page(namespace: "watch-metadata", after: after ?? "", limit: 128)
        for item in page where try !store.isWorkoutDeleted(id: item.key) {
          guard let value = try JSONSerialization.jsonObject(with: item.value) as? [String: Any] else {
            throw WorkoutDataError.invalid("Invalid Watch catalog metadata")
          }
          result.append((try Self.validateMetadata(value, id: item.key).startedAt, value))
        }
        guard page.count == 128 else { break }
        after = page.last?.key
      }
      return result.sorted { $0.startedAt > $1.startedAt }.map(\.metadata)
    }
  }
  func commitHealthPage(id: String, events: [WorkoutEvent], progressKey: String, anchor: Data) throws {
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: id)
      _ = try archive.appendBatch(events)
      try db.put(namespace: "health-query-progress", key: id + ":" + progressKey, value: anchor)
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
      guard try Data(contentsOf: url) == chunk.data else {
        throw WorkoutDataError.invalid("Changed staged Watch chunk")
      }
      return url
    }
    let staged = try stagedChunks()
    guard staged.count < WorkoutChunkInbox.maximumFiles,
      staged.reduce(0, { $0 + $1.bytes }) + chunk.data.count <= WorkoutChunkInbox.maximumBytes
    else {
      throw WorkoutDataError.invalid("Watch transfer staging is full")
    }
    try store.transaction(priority: .normal) { db in
      try store.requireWorkoutAvailable(id: chunk.manifest.workoutID)
      try db.put(
        namespace: "sent-chunks", key: chunk.manifest.identity,
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
    return revision == nil || metadata["acknowledgedSealRevision"] as? String != revision
      || metadata["archiveDirty"] as? Bool == true ? id : nil
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
      saved["sealRevision"] = String(seal.sealRevision)
      saved["archiveDirty"] = false
      try save(id: seal.workoutID, metadata: saved)
      return saved
    }
  }
  struct StagedChunk {
    let identity: String
    let workoutID: String?
    let bytes: Int
  }
  func stagedChunks() throws -> [StagedChunk] {
    var result: [StagedChunk] = []
    let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
    for file in files where file.pathExtension == "plchunk" {
      let identity = file.deletingPathExtension().lastPathComponent
      let manifest = try store.read { db in
        try db.get(namespace: "sent-chunks", key: identity)
          .map { try JSONDecoder().decode(WorkoutChunkManifest.self, from: $0) }
      }
      result.append(
        StagedChunk(
          identity: identity, workoutID: manifest?.workoutID,
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
  func reserveStaging(
    for chunk: WorkoutChunk, priorityID: String?, referenced: () -> Set<String>,
    nativeCount: () -> Int, cancel: (String) -> Void
  ) throws -> Bool {
    let isPriority = chunk.manifest.workoutID == priorityID
    let fileLimit = WorkoutChunkInbox.maximumFiles - (isPriority ? 0 : 1)
    let byteLimit = WorkoutChunkInbox.maximumBytes - (isPriority ? 0 : WorkoutChunkCodec.maximumBytes)
    func fits() throws -> Bool {
      let staged = try stagedChunks()
      let additional = staged.contains { $0.identity == chunk.manifest.identity } ? 0 : chunk.data.count
      return staged.count + (additional == 0 ? 0 : 1) <= fileLimit
        && staged.reduce(0, { $0 + $1.bytes }) + additional <= byteLimit && nativeCount() < fileLimit
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
  struct RecoveryProjection {
    let lapCount: Int
    let events: [WorkoutEvent]
  }
  /// At most 48 decoded originals; history length cannot delay attaching the native session.
  func recoveryProjection(id: String, onDecode: (() -> Void)? = nil) throws -> RecoveryProjection {
    try store.read { db in
      try store.requireWorkoutAvailable(id: id)
      let laps =
        try db.scalarInt(
          "SELECT count(*) FROM collection_memberships m JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.source='watch' AND l.action='lap'",
          [.text(id)]) ?? 0
      var events: [WorkoutEvent] = []
      for (kind, source) in [("telemetry", "cyc"), ("health", "watch"), ("location", "watch")] {
        let rows = try db.rows(
          "SELECT m.*,o.original_timestamp,o.extra FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.kind=? AND m.source=? ORDER BY m.elapsed_seconds DESC,m.observation_id DESC LIMIT 16",
          [.text(id), .text(kind), .text(source)], limit: 16)
        for row in rows {
          onDecode?()
          events.append(try store.decodeEvent(row, db: db).event)
        }
      }
      return RecoveryProjection(
        lapCount: Int(laps),
        events: events.sorted {
          if $0.elapsedSeconds != $1.elapsedSeconds { return ($0.elapsedSeconds ?? 0) < ($1.elapsedSeconds ?? 0) }
          return $0.eventId < $1.eventId
        })
    }
  }
  func metadataPage(after: String = "", limit: Int = 8) throws -> [(id: String, value: [String: Any])] {
    try store.read { db in
      try db.rows(
        "SELECT key,value FROM durable_records r WHERE namespace='watch-metadata' AND key>? AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=r.key) ORDER BY key LIMIT ?",
        [.text(after), .integer(Int64(limit))], limit: limit
      ).map {
        guard let value = try JSONSerialization.jsonObject(with: $0.data("value")!) as? [String: Any] else {
          throw WorkoutDataError.invalid("Invalid Watch catalog metadata")
        }
        _ = try Self.validateMetadata(value, id: $0.string("key")!)
        return ($0.string("key")!, value)
      }
    }
  }
}

extension WatchWorkoutJournal {
  /// Historical input commits into its own collection without selecting it as the active owner.
  func acceptTelemetry(_ events: [WorkoutEvent], firstSequence: Int64) throws {
    guard let id = events.first?.workoutId,
      events.allSatisfy({ $0.workoutId == id && $0.source == "cyc" && $0.kind == "telemetry" })
    else { throw WorkoutDataError.invalid("Invalid CYC collection") }
    try store.transaction(priority: .capture) { _ in
      try transfer.receiveLive(events, producer: "cyc", firstSequence: firstSequence)
      var item = try metadata(id: id)
      item["archiveDirty"] = true
      let record = try archive.metadata(id: id)
      item["cycHealthSamplesIncomplete"] = record.saveToHealth && record.healthKitState != "unavailable"
      try save(id: id, metadata: item)
    }
  }
}

extension WatchWorkoutJournal {
  func healthElapsed(id: String, date: Date) throws -> Double {
    max(0, date.timeIntervalSince(try WorkoutCoding.date(archive.metadata(id: id).startedAt)))
  }
}

extension WatchWorkoutJournal {
  static func timing(_ metadata: [String: Any]) throws -> WorkoutOwnerTiming? {
    if let value = metadata["endedAt"] {
      guard let cutoff = value as? String else { throw WorkoutDataError.invalid("Invalid Watch cutoff UTC") }
      guard let elapsed = metadata["stopElapsedSeconds"] as? Double,
        let timer = metadata["timerSeconds"] as? Double
      else { throw WorkoutDataError.invalid("Watch cutoff is missing retained timing") }
      return try WorkoutOwnerTiming(timestamp: cutoff, elapsedSeconds: elapsed, timerSeconds: timer)
    }
    guard let value = metadata["checkpoint"] else { return nil }
    let timing = try JSONDecoder().decode(
      WorkoutOwnerTiming.self, from: JSONSerialization.data(withJSONObject: value))
    return try WorkoutOwnerTiming(
      timestamp: timing.timestamp, elapsedSeconds: timing.elapsedSeconds, timerSeconds: timing.timerSeconds)
  }

  static func timeline(_ metadata: [String: Any]) throws -> WorkoutTimelineAnchor? {
    guard let value = metadata["timelineAnchor"] else { return nil }
    return try JSONDecoder().decode(
      WorkoutTimelineAnchor.self, from: JSONSerialization.data(withJSONObject: value))
  }

  static func retain(_ timing: WorkoutOwnerTiming, in metadata: inout [String: Any], terminal: Bool = false) {
    metadata["checkpoint"] = WorkoutCoding.dictionary(timing)
    metadata["timerSeconds"] = timing.timerSeconds
    if terminal {
      metadata["endedAt"] = timing.timestamp
      metadata["stopElapsedSeconds"] = timing.elapsedSeconds
    }
  }

  func recoverTiming(id: String, epoch: String, uptime: Double, resumedAt: Date, running: Bool?) throws -> [String: Any]
  {
    try store.transaction(priority: .capture) { db in
      try reconcileOwnerMetadata(id: id)
      var saved = try metadata(id: id)
      guard saved["endedAt"] == nil else { return saved }
      if let command = try control.pendingStop(workoutID: id, origin: "watch"), command.options["cutoffUTC"] != nil {
        Self.retain(try WorkoutOwnerTiming.command(command), in: &saved, terminal: true)
        saved["phase"] = "finishing"
        try save(id: id, metadata: saved)
        return saved
      }
      guard let checkpoint = try Self.timing(saved), let anchor = try Self.timeline(saved) else {
        throw WorkoutDataError.invalid("The retained Watch timeline is unavailable; recovery remains unresolved")
      }
      let pending = try control.active(workoutID: id)
      let missingTransition =
        try pending.map {
          try ["pause", "resume"].contains($0.action) && !archive.hasEvent(id: id, eventID: $0.id)
        } ?? false
      let lastAction = try db.rows(
        "SELECT l.action FROM collection_memberships m JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.deleted=0 AND l.action IN ('start','pause','resume','stop') ORDER BY m.elapsed_seconds DESC,m.ordinal DESC LIMIT 1",
        [.text(id)], limit: 1
      ).first?.string("action")
      let knownRunning =
        lastAction.map { ["start", "resume"].contains($0) }
        ?? (saved["phase"] as? String).flatMap { ["running", "paused"].contains($0) ? $0 == "running" : nil }
      let changedNativeState = running != nil && knownRunning != nil && running != knownRunning
      let preserveActive = missingTransition || changedNativeState
      let retained = try WorkoutOwnerTiming.retained(
        id: id, checkpoint: checkpoint, archive: archive, preserveActive: preserveActive)
      let activeUncertainty =
        preserveActive && retained.elapsedSeconds > checkpoint.elapsedSeconds
        ? "Active time after the last checkpoint is excluded because the native pause or resume time is uncertain" : nil
      if let activeUncertainty {
        try WorkoutLocalOwner.pauseUncertainActive(id: id, checkpoint: checkpoint, archive: archive)
        let previous = saved["error"] as? String
        if previous?.contains(activeUncertainty) != true {
          saved["error"] = previous.map { $0 + ". " + activeUncertainty } ?? activeUncertainty
        }
        var uncertain = anchor
        uncertain.uncertainty = activeUncertainty
        saved["timelineAnchor"] = WorkoutCoding.dictionary(uncertain)
      }
      try WorkoutLocalOwner.interrupt(id: id, epoch: anchor.epoch, timing: retained, archive: archive)
      Self.retain(retained, in: &saved)
      saved["interrupted"] = true
      saved["archiveDirty"] = true
      if let running {
        var resumed = anchor.resuming(timing: retained, epoch: epoch, uptime: uptime)
        if let activeUncertainty { resumed.uncertainty = (resumed.uncertainty ?? "") + "; " + activeUncertainty }
        saved["timelineAnchor"] = WorkoutCoding.dictionary(resumed)
        if running {
          let event = try WorkoutEvent(
            workoutId: id, kind: "lifecycle", source: "watch", timestamp: resumedAt,
            elapsedSeconds: retained.elapsedSeconds,
            payload: [
              "action": .string("resume"), "clockEpoch": .string(epoch),
              "timelineMappingUncertainty": .string("Process downtime is not measured"),
            ],
            eventId: WorkoutStableIdentity.uuid("watch-resume:\(id):\(epoch)"))
          try archive.append(event)
        }
      }
      try save(id: id, metadata: saved)
      return saved
    }
  }
}

extension WorkoutOwnerTiming {
  func advancing(
    anchor: WorkoutTimelineAnchor, at timestamp: Date, from previousUptime: Double, to uptime: Double,
    running: Bool
  ) throws -> WorkoutOwnerTiming {
    guard previousUptime.isFinite, uptime.isFinite, uptime >= previousUptime,
      anchor.monotonicOrigin.isFinite
    else { throw WorkoutDataError.invalid("Invalid Watch process timing") }
    let elapsed = max(elapsedSeconds, uptime - anchor.monotonicOrigin)
    let timer = timerSeconds + (running ? uptime - previousUptime : 0)
    return try WorkoutOwnerTiming(
      timestamp: WorkoutCoding.timestamp(timestamp), elapsedSeconds: elapsed,
      timerSeconds: min(elapsed, timer))
  }

  func transitioning(
    anchor: WorkoutTimelineAnchor, at timestamp: Date, from previousUptime: Double, to uptime: Double,
    receivedAt receiptUptime: Double, wasRunning: Bool, running: Bool
  ) throws -> WorkoutOwnerTiming {
    guard receiptUptime.isFinite, receiptUptime >= (anchor.epochStart ?? anchor.monotonicOrigin),
      receiptUptime <= uptime
    else { throw WorkoutDataError.invalid("Native transition belongs to an unavailable recording epoch") }
    let advanced = try advancing(
      anchor: anchor, at: timestamp, from: previousUptime, to: uptime, running: wasRunning)
    let delay = uptime - receiptUptime
    let correction = (running ? delay : 0) - (wasRunning ? delay : 0)
    return try WorkoutOwnerTiming(
      timestamp: advanced.timestamp, elapsedSeconds: advanced.elapsedSeconds,
      timerSeconds: min(advanced.elapsedSeconds, max(0, advanced.timerSeconds + correction)))
  }
}

extension WatchWorkoutJournal {
  func beginClockAbandonment(id: String, endNative: ([String: Any]) -> Void) throws -> Bool {
    let settled = try store.transaction(priority: .capture) { _ -> [String: Any]? in
      guard var item = try settleInvalidClock(id: id), let timing = try Self.timing(item) else { return nil }
      let cutoff = try WorkoutCoding.date(timing.timestamp)
      let owner = try control.observe(
        workoutID: id, owner: "watch", phase: "finishing", at: cutoff,
        health: item["healthKitState"] as? String ?? "pending", cutoff: cutoff, timing: timing,
        healthReason: item["error"] as? String)
      item["phase"] = owner.phase
      item["phaseTimestamp"] = owner.effectiveAt
      item["ownerRevision"] = String(owner.ownerRevision)
      try save(id: id, metadata: item)
      return item
    }
    guard let settled else { return false }
    endNative(settled)
    return true
  }

  func retainNativeStop(id: String, timing: WorkoutOwnerTiming) throws -> [String: Any] {
    try store.transaction(priority: .capture) { _ in
      var item = try metadata(id: id)
      if item["endedAt"] == nil {
        Self.retain(timing, in: &item, terminal: true)
        item["phase"] = "finishing"
        try save(id: id, metadata: item)
      }
      return item
    }
  }

  func confirmNativeEnd(id: String, nativeEnded: Bool) throws -> [String: Any] {
    guard nativeEnded else { throw WorkoutNativeEndingError() }
    return try store.transaction(priority: .capture) { _ in
      var item = try metadata(id: id)
      guard item["endedAt"] != nil else { throw WorkoutDataError.invalid("Native completion has no retained cutoff") }
      item["localRecorderEnded"] = true
      try save(id: id, metadata: item)
      return item
    }
  }

  func completeNativeEnd(id: String, command: WorkoutCommand?) throws -> [String: Any] {
    try store.transaction(priority: .capture) { _ in
      var item = try confirmNativeEnd(id: id, nativeEnded: true)
      guard let timing = try Self.timing(item) else {
        throw WorkoutDataError.invalid("Native completion has no timing")
      }
      let owner = try control.observe(
        workoutID: id, owner: "watch", phase: "completed", at: WorkoutCoding.date(timing.timestamp),
        health: item["healthKitState"] as? String ?? "pending", healthID: item["healthKitUUID"] as? String,
        cutoff: WorkoutCoding.date(timing.timestamp), command: command,
        failure: command != nil && command?.action != "stop" ? "The recorder ended before this action completed" : nil,
        timing: timing, healthReason: item["error"] as? String)
      item["phase"] = owner.phase
      item["ownerRevision"] = String(owner.ownerRevision)
      item["phaseTimestamp"] = owner.effectiveAt
      try save(id: id, metadata: item)
      return item
    }
  }

  func prepareOriginalChunk(id: String) throws -> WorkoutChunk? {
    guard try metadata(id: id)["discardRequested"] as? Bool != true else { return nil }
    return try WorkoutChunkSender(archive: archive).prepare(id: id)
  }

  func settleInvalidClock(id: String) throws -> [String: Any]? {
    try store.transaction(priority: .capture) { _ in
      guard let reason = try WorkoutHealthFinalizationGate.settle(id: id, archive: archive) else { return nil }
      var item = try metadata(id: id)
      item["healthKitState"] = item["saveToHealth"] as? Bool == true ? "unavailable" : "notRequested"
      item["error"] = reason
      item["cycHealthSamplesIncomplete"] = false
      item["healthSamplesIncomplete"] = false
      if item["saveToHealth"] as? Bool == false { item["localSensorOutcome"] = "unavailable" }
      item["archiveDirty"] = true
      try save(id: id, metadata: item)
      return item
    }
  }
  static func localSensorsSettled(_ item: [String: Any]) -> Bool {
    item["finalLocalSensorsExtracted"] as? Bool == true || item["localSensorOutcome"] as? String == "unavailable"
  }
}

extension WatchWorkoutJournal {
  static func ownerEnded(_ item: [String: Any], owner: WorkoutOwnerSnapshot?) -> Bool {
    item["localRecorderEnded"] as? Bool == true && owner?.phase == "completed"
  }

  static func requirements(_ item: [String: Any], owner: WorkoutOwnerSnapshot?, insertionOutcome: String) throws
    -> [String: String]
  {
    let health = item["healthKitState"] as? String ?? "pending"
    guard let saves = item["saveToHealth"] as? Bool, let gps = item["recordGPS"] as? Bool else {
      throw WorkoutDataError.invalid("Watch metadata is missing recording options")
    }
    var requirements = [
      "healthSave": saves ? (health == "saved" ? "sealed" : "pending") : "notRequested",
      "healthExtraction": saves
        ? (item["healthSamplesIncomplete"] as? Bool == true || item["finalHealthExtracted"] as? Bool != true
          ? "pending" : "sealed") : "notRequested",
      "cycInsertion": insertionOutcome,
      "gps": gps ? (item["gpsOutcome"] as? String ?? "sealed") : "notRequested",
      "ownerEnded": ownerEnded(item, owner: owner) ? "sealed" : "pending",
    ]
    if !saves {
      requirements["localSensors"] =
        item["localSensorOutcome"] as? String
        ?? (item["finalLocalSensorsExtracted"] as? Bool == true ? "sealed" : "pending")
    }
    if health == "unavailable" {
      requirements["healthSave"] = "unavailable"
      requirements["healthExtraction"] = "unavailable"
      requirements["cycInsertion"] = "unavailable"
    }
    return requirements
  }
}
