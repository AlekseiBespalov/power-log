import Foundation

struct WorkoutCommand: Codable, Equatable {
  let id: String
  let workoutID: String
  let origin: String
  let originSequence: Int64
  let action: String
  let requestedAt: String
  var options: [String: WorkoutJSON] = [:]

  init(id: String = UUID().uuidString.lowercased(), workoutID: String, origin: String,
       originSequence: Int64, action: String, requestedAt: Date, options: [String: WorkoutJSON] = [:]) throws {
    self.id = try WorkoutCoding.id(id); self.workoutID = try WorkoutCoding.id(workoutID)
    self.origin = origin; self.originSequence = originSequence; self.action = action
    self.requestedAt = WorkoutCoding.timestamp(requestedAt)
    // Integral JSON numbers decode as integers. Canonicalize intent at creation
    // so a whole-second cutoff remains identical after its durable round trip.
    self.options = try JSONDecoder().decode([String: WorkoutJSON].self, from: WorkoutCoding.encoder().encode(options))
    try validate()
  }
  var endsWorkout: Bool { action == "stop" || action == "discard" }
  func validate() throws {
    _ = try WorkoutCoding.id(id); _ = try WorkoutCoding.id(workoutID); _ = try WorkoutCoding.date(requestedAt)
    for key in ["saveToHealth", "recordGPS"] { if let value = options[key] { guard case .bool = value else { throw WorkoutDataError.invalid("Invalid recording option") } } }
    guard ["phone", "watch"].contains(origin), originSequence > 0,
      ["start", "pause", "resume", "lap", "stop", "discard", "status"].contains(action) else {
      throw WorkoutDataError.invalid("Invalid durable workout command")
    }
  }
  var packet: [String: Any] {
    var result: [String: Any] = ["schemaVersion": 1, "kind": "command", "messageId": id,
      "workoutId": workoutID, "origin": origin, "originSequence": String(originSequence),
      "action": action, "timestamp": requestedAt]
    result.merge(options.mapValues(\.any)) { current, _ in current }; return result
  }
  static func decode(_ packet: [String: Any]) throws -> Self {
    guard let id = packet["messageId"] as? String, let workoutID = packet["workoutId"] as? String,
      let origin = packet["origin"] as? String, let sequence = packet["originSequence"] as? String,
      let number = Int64(sequence), let action = packet["action"] as? String,
      let timestamp = packet["timestamp"] as? String else { throw WorkoutDataError.invalid("Command ordering is missing") }
    var options: [String: WorkoutJSON] = [:]
    for key in ["indoor", "eBike", "saveToHealth", "recordGPS"] {
      if let raw = packet[key] {
        guard let value = raw as? Bool else { throw WorkoutDataError.invalid("Invalid recording option") }; options[key] = .bool(value)
      }
    }
    if let elapsed = packet["cutoffElapsedSeconds"] as? Double, elapsed.isFinite, elapsed >= 0 { options["cutoffElapsedSeconds"] = .number(elapsed) }
    return try Self(id: id, workoutID: workoutID, origin: origin, originSequence: number,
                    action: action, requestedAt: WorkoutCoding.date(timestamp), options: options)
  }
}

struct WorkoutOwnerSnapshot: Codable, Equatable {
  let workoutID: String
  let owner: String
  var ownerRevision: Int64
  var effectiveAt: String
  var phase: String
  var healthOutcome: String
  var healthWorkoutID: String?
  var stopCutoff: String?
  var normalized: Self { var value = self; value.phase = WorkoutOwnerPhase.canonical(phase); return value }
}

enum WorkoutOwnerPhase {
  static let values: Set<String> = ["ready", "preparing", "running", "paused", "recoverable", "finishing", "completed", "failed"]
  static func canonical(_ value: String) -> String { value == "finished" ? "completed" : value }
  static func terminal(_ value: String) -> Bool { ["completed", "failed"].contains(canonical(value)) }
}

enum WorkoutDeletionPolicy {
  static func phone(phase: String, selectedPhase: String?, healthBusy: Bool, pendingAction: Bool, backgroundBusy: Bool) -> Bool {
    ["completed", "failed"].contains(phase) && (selectedPhase == nil || ["completed", "failed"].contains(selectedPhase!)) &&
      !healthBusy && !pendingAction && !backgroundBusy
  }
  static func watch(targetID: String, nativeID: String?, probeResolved: Bool, busy: Bool) -> Bool {
    probeResolved && !busy && nativeID != targetID
  }
}

struct WorkoutDeletionProbeGate {
  private(set) var inFlight = false
  mutating func begin() -> Bool {
    guard !inFlight else { return false }; inFlight = true; return true
  }
  mutating func finish() { inFlight = false }
}

struct WorkoutDeletionRequest {
  let workoutID: String
  let messageID: String
  init(_ packet: [String: Any]) throws {
    guard packet["schemaVersion"] as? Int == 1, packet["kind"] as? String == "deleteWorkout",
      let id = packet["workoutId"] as? String, let message = packet["messageId"] as? String,
      let timestamp = packet["requestedAt"] as? String else { throw WorkoutDataError.invalid("Invalid deletion request") }
    workoutID = try WorkoutCoding.id(id); messageID = try WorkoutCoding.id(message); _ = try WorkoutCoding.date(timestamp)
  }
  func acknowledgement(deleted: Bool, reason: String? = nil) -> [String: Any] {
    var packet: [String: Any] = ["schemaVersion": 1, "kind": "deleteWorkoutAck", "workoutId": workoutID,
      "messageId": UUID().uuidString.lowercased(), "acknowledgedMessageId": messageID, "outcome": deleted ? "deleted" : "deferred"]
    if let reason { packet["reason"] = String(reason.prefix(500)) }; return packet
  }
}

/// A delayed native callback can publish only to the session which admitted it.
struct WorkoutEffectIdentity: Equatable {
  let workoutID: String
  let generation: UUID
  func matches(workoutID: String?, generation: UUID) -> Bool { self.workoutID == workoutID && self.generation == generation }
  static func require(command: WorkoutCommand?, workoutID: String, nativeWorkoutID: String?) throws {
    guard command == nil || command?.workoutID == workoutID,
      nativeWorkoutID == nil || nativeWorkoutID == workoutID else { throw WorkoutDataError.invalid("Workout command/session identity mismatch") }
  }
}

struct WorkoutNativeAbsenceEvidence {
  let identity: WorkoutEffectIdentity
  let probeID: UUID
  func matches(workoutID: String, generation: UUID, probeID: UUID) -> Bool {
    self.probeID == probeID && identity.matches(workoutID: workoutID, generation: generation)
  }
}

enum WorkoutSavedOwnerLookupError: Error, LocalizedError {
  case notAccessible
  var errorDescription: String? { "The original saved Health workout is not accessible; its historical outcome remains unknown." }
}

struct WorkoutCommandResult: Codable, Equatable {
  let commandID: String
  var outcome: String // accepted, executing (uncertain after restart), applied, failed, rejected
  var ownerRevision: Int64?
  var reason: String?
  var missingOriginSequence: Int64?
  var isTerminal: Bool { ["applied", "failed", "rejected"].contains(outcome) }
}

/// Pure decisions used by both native owners. No delivery ACK can create an applied transition.
enum WorkoutControlReducer {
  static func accepts(_ incoming: WorkoutOwnerSnapshot, previous: WorkoutOwnerSnapshot?) throws -> Bool {
    let incoming = incoming.normalized, previous = previous?.normalized
    _ = try WorkoutCoding.id(incoming.workoutID); _ = try WorkoutCoding.date(incoming.effectiveAt)
    if let cutoff = incoming.stopCutoff { _ = try WorkoutCoding.date(cutoff) }
    guard incoming.ownerRevision > 0, ["phone", "watch"].contains(incoming.owner),
      WorkoutOwnerPhase.values.contains(incoming.phase),
      ["pending", "saved", "failed", "notSaved", "unavailable", "unknown", "discarded", "notRequested"].contains(incoming.healthOutcome) else {
      throw WorkoutDataError.invalid("Invalid owner revision")
    }
    guard let previous else { return true }
    guard previous.workoutID == incoming.workoutID, previous.owner == incoming.owner else {
      throw WorkoutDataError.invalid("Workout owner cannot change")
    }
    if incoming.ownerRevision < previous.ownerRevision { return false }
    if incoming.ownerRevision == previous.ownerRevision {
      guard incoming == previous else { throw WorkoutDataError.invalid("Conflicting owner snapshot revision") }
      return false
    }
    if previous.healthOutcome == "notRequested", !["notRequested", "discarded"].contains(incoming.healthOutcome) { throw WorkoutDataError.invalid("Skipped Health saving cannot be resumed") }
    if previous.healthOutcome == "discarded", incoming.healthOutcome != "discarded" { throw WorkoutDataError.invalid("A discarded workout cannot be saved or resumed") }
    if previous.stopCutoff != nil && incoming.stopCutoff != previous.stopCutoff {
      throw WorkoutDataError.invalid("The original stop cutoff is immutable")
    }
    if (previous.stopCutoff != nil || WorkoutOwnerPhase.terminal(previous.phase)) && ["running", "paused", "preparing"].contains(incoming.phase) {
      throw WorkoutDataError.invalid("An ended workout cannot resume")
    }
    return true
  }
  static func rejection(_ command: WorkoutCommand, snapshot: WorkoutOwnerSnapshot?) -> String? {
    let phase = snapshot?.phase ?? "ready"
    switch command.action {
    case "start": return snapshot == nil || phase == "preparing" ? nil : "A session already belongs to this workout"
    case "pause": return phase == "running" ? nil : "The owner is not running"
    case "resume": return phase == "paused" ? nil : "The owner is not paused"
    case "lap": return ["running", "paused"].contains(phase) ? nil : "The owner is not active"
    case "discard": return ["running", "paused"].contains(phase) ? nil : "Only an active ride can be discarded"
    case "stop": return nil
    default: return nil
    }
  }
}

/// A transaction is the effect boundary: intent before HealthKit; observed native result afterwards.
/// Replay entries live for the collection lifetime. An executing entry must be reconciled, never blindly repeated.
final class WorkoutControlJournal {
  let store: PowerLogStore
  init(store: PowerLogStore) { self.store = store }
  private func encode<T: Encodable>(_ value: T) throws -> Data { try WorkoutCoding.encoder().encode(value) }
  private func read<T: Decodable>(_ type: T.Type, db: PowerLogDatabase, namespace: String, key: String) throws -> T? {
    try db.get(namespace: namespace, key: key).map { try JSONDecoder().decode(type, from: $0) }
  }
  func create(workoutID: String, origin: String, action: String, at: Date,
              options: [String: WorkoutJSON] = [:]) throws -> WorkoutCommand {
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: workoutID)
      let sequence = try db.nextSequence(namespace: "command-origins", key: workoutID + ":" + origin)
      let command = try WorkoutCommand(workoutID: workoutID, origin: origin, originSequence: sequence,
                                       action: action, requestedAt: at, options: options)
      try db.put(namespace: "outgoing-commands", key: command.id, value: encode(command), immutable: true)
      return command
    }
  }
  func accept(_ command: WorkoutCommand) throws -> WorkoutCommandResult {
    try command.validate()
    return try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: command.workoutID)
      try db.put(namespace: "commands", key: command.id, value: encode(command), immutable: true)
      // This second immutable index rejects a different command reusing an origin's sequence.
      try db.put(namespace: "command-sequences", key: command.workoutID + ":" + command.origin + ":" + String(command.originSequence), value: encode(command.id), immutable: true)
      if let previous = try read(WorkoutCommandResult.self, db: db, namespace: "command-results", key: command.id) { return previous }
      let previous = try read(Int64.self, db: db, namespace: "applied-origins", key: command.workoutID + ":" + command.origin) ?? 0
      let result = WorkoutCommandResult(commandID: command.id, outcome: "accepted",
        missingOriginSequence: command.originSequence > previous + 1 ? previous + 1 : nil)
      try db.put(namespace: "command-results", key: command.id, value: encode(result))
      return result
    }
  }
  func begin(_ command: WorkoutCommand) throws -> WorkoutCommandResult {
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: command.workoutID)
      guard try self.command(id: command.id) == command else { throw WorkoutDataError.invalid("Command identity changed before execution") }
      try repairTerminalSlot(workoutID: command.workoutID)
      guard var result = try read(WorkoutCommandResult.self, db: db, namespace: "command-results", key: command.id) else {
        throw WorkoutDataError.invalid("Command intent has not been committed")
      }
      if result.isTerminal || result.outcome == "executing" { return result }
      if command.action == "start", let reason = try cancelledStart(workoutID: command.workoutID) {
        return try settleWithoutEffect(command, reason: reason)
      }
      let applied = try read(Int64.self, db: db, namespace: "applied-origins", key: command.workoutID + ":" + command.origin) ?? 0
      guard command.originSequence == applied + 1 else {
        result.missingOriginSequence = command.originSequence > applied ? applied + 1 : nil
        result.reason = command.originSequence <= applied ? "Owner sequence requires reconciliation" : nil
        try db.put(namespace: "command-results", key: command.id, value: encode(result)); return result
      }
      if let active = try read(String.self, db: db, namespace: "active-owner-command", key: command.workoutID), active != command.id {
        return result
      }
      let snapshot = try read(WorkoutOwnerSnapshot.self, db: db, namespace: "owner-snapshots", key: command.workoutID)
      if let reason = WorkoutControlReducer.rejection(command, snapshot: snapshot) {
        result.outcome = "rejected"; result.reason = reason
        try db.put(namespace: "applied-origins", key: command.workoutID + ":" + command.origin, value: encode(command.originSequence))
      } else {
        result.outcome = "executing"; result.missingOriginSequence = nil
        try db.put(namespace: "active-owner-command", key: command.workoutID, value: encode(command.id))
      }
      try db.put(namespace: "command-results", key: command.id, value: encode(result)); return result
    }
  }
  func result(id: String) throws -> WorkoutCommandResult? {
    try store.read { db in try read(WorkoutCommandResult.self, db: db, namespace: "command-results", key: id) }
  }
  func command(id: String) throws -> WorkoutCommand? {
    try store.read { db in try read(WorkoutCommand.self, db: db, namespace: "commands", key: id) }
  }
  func active(workoutID: String) throws -> WorkoutCommand? {
    try store.read { db in
      guard let id = try read(String.self, db: db, namespace: "active-owner-command", key: workoutID) else { return nil }
      guard let command = try read(WorkoutCommand.self, db: db, namespace: "commands", key: id), command.workoutID == workoutID else {
        throw WorkoutDataError.invalid("Active owner slot has a different workout identity; recovery evidence is required")
      }
      return command
    }
  }
  func snapshot(workoutID: String) throws -> WorkoutOwnerSnapshot? {
    try store.read { db in try read(WorkoutOwnerSnapshot.self, db: db, namespace: "owner-snapshots", key: workoutID)?.normalized }
  }
  func accept(snapshot: WorkoutOwnerSnapshot) throws -> Bool {
    let snapshot = snapshot.normalized
    return try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: snapshot.workoutID)
      try requireHealthIntent(id: snapshot.workoutID, health: snapshot.healthOutcome, db: db)
      let previous = try read(WorkoutOwnerSnapshot.self, db: db, namespace: "owner-snapshots", key: snapshot.workoutID)
      guard try WorkoutControlReducer.accepts(snapshot, previous: previous) else { return false }
      try db.put(namespace: "owner-snapshots", key: snapshot.workoutID, value: encode(snapshot))
      return true
    }
  }
  @discardableResult
  func observe(workoutID: String, owner: String, phase: String, at: Date, health: String,
               healthID: String? = nil, cutoff: Date? = nil, command: WorkoutCommand? = nil,
               failure: String? = nil) throws -> WorkoutOwnerSnapshot {
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: workoutID)
      try requireHealthIntent(id: workoutID, health: health, db: db)
      let previous = try read(WorkoutOwnerSnapshot.self, db: db, namespace: "owner-snapshots", key: workoutID)
      if let command {
        try WorkoutEffectIdentity.require(command: command, workoutID: workoutID, nativeWorkoutID: nil)
        guard try self.command(id: command.id) == command, let result = try result(id: command.id) else {
          throw WorkoutDataError.invalid("Owner completion has no matching durable intent")
        }
        if result.isTerminal {
          guard let previous, previous.owner == owner else { throw WorkoutDataError.invalid("Terminal command has no matching owner evidence") }
          try repairTerminalSlot(workoutID: workoutID)
          return previous.normalized
        }
        guard result.outcome == "executing", try active(workoutID: workoutID)?.id == command.id else {
          throw WorkoutDataError.invalid("Only the matching active command can complete a native effect")
        }
        if failure == nil, command.endsWorkout, !["finishing", "completed"].contains(WorkoutOwnerPhase.canonical(phase)) {
          throw WorkoutDataError.invalid("A stop receipt requires an observed stopped owner")
        }
        if failure == nil, command.action == "discard", (health != "discarded" || WorkoutOwnerPhase.canonical(phase) != "completed") {
          throw WorkoutDataError.invalid("Discard requires the native builder's discarded outcome")
        }
      }
      let snapshot = WorkoutOwnerSnapshot(workoutID: workoutID, owner: owner,
        ownerRevision: (previous?.ownerRevision ?? 0) + 1, effectiveAt: WorkoutCoding.timestamp(at),
        phase: WorkoutOwnerPhase.canonical(phase), healthOutcome: health, healthWorkoutID: healthID ?? previous?.healthWorkoutID,
        stopCutoff: previous?.stopCutoff ?? cutoff.map(WorkoutCoding.timestamp))
      guard try WorkoutControlReducer.accepts(snapshot, previous: previous) else { return previous! }
      try db.put(namespace: "owner-snapshots", key: workoutID, value: encode(snapshot))
      if let command {
        let result = WorkoutCommandResult(commandID: command.id, outcome: failure == nil ? "applied" : "failed",
                                          ownerRevision: snapshot.ownerRevision, reason: failure)
        try db.put(namespace: "command-results", key: command.id, value: encode(result))
        let applied = try read(Int64.self, db: db, namespace: "applied-origins", key: workoutID + ":" + command.origin) ?? 0
        try db.put(namespace: "applied-origins", key: workoutID + ":" + command.origin, value: encode(max(applied, command.originSequence)))
        try db.remove(namespace: "active-owner-command", key: workoutID)
        try advanceTerminalOrigin(workoutID: workoutID, origin: command.origin, db: db)
      }
      return snapshot
    }
  }
  private func requireHealthIntent(id: String, health: String, db: PowerLogDatabase) throws {
    if let data = try db.rows("SELECT metadata FROM collections WHERE id=? AND kind='workout'", [.text(id)], limit: 1).first?.data("metadata") {
      let metadata = try JSONDecoder().decode(WorkoutMetadata.self, from: data)
      guard metadata.savesToHealth || ["notRequested", "discarded"].contains(health) else { throw WorkoutDataError.invalid("Owner outcome conflicts with frozen Health intent") }
    }
  }
  func outgoing(workoutID: String, origin: String, sequence: Int64) throws -> WorkoutCommand? {
    // Only the bounded page is retained in memory; retry knowledge itself is never truncated.
    try store.read { db in
      var after: String? = nil
      while true {
        let page = try db.page(namespace: "outgoing-commands", after: after ?? "", limit: 128)
        for item in page {
          let command = try JSONDecoder().decode(WorkoutCommand.self, from: item.value)
          if command.workoutID == workoutID && command.origin == origin && command.originSequence == sequence { return command }
        }
        guard page.count == 128 else { return nil }; after = page.last?.key
      }
    }
  }
}

extension WorkoutControlJournal {
  /// A terminal receipt consumes a protocol slot, but is never proof that Health ran.

  func repairTerminalSlot(workoutID: String) throws {
    try store.transaction(priority: .capture) { db in
      guard let active = try active(workoutID: workoutID), let result = try result(id: active.id), result.isTerminal else { return }
      try db.remove(namespace: "active-owner-command", key: workoutID)
      try advanceTerminalOrigin(workoutID: workoutID, origin: active.origin, db: db)
    }
  }
  private func advanceTerminalOrigin(workoutID: String, origin: String, db: PowerLogDatabase) throws {
    let key = workoutID + ":" + origin
    let old = try read(Int64.self, db: db, namespace: "applied-origins", key: key) ?? 0
    var applied = old
    while let id = try read(String.self, db: db, namespace: "command-sequences", key: key + ":" + String(applied + 1)),
      let result = try result(id: id), result.isTerminal { applied += 1 }
    if old != applied { try db.put(namespace: "applied-origins", key: key, value: encode(applied)) }
  }
  /// Command-only rejection/query receipt. It cannot create or change a native snapshot.
  @discardableResult
  func settleWithoutEffect(_ command: WorkoutCommand, outcome: String = "rejected", reason: String) throws -> WorkoutCommandResult {
    guard ["applied", "failed", "rejected"].contains(outcome) else { throw WorkoutDataError.invalid("Invalid command-only outcome") }
    return try store.transaction(priority: .capture) { db in
      _ = try accept(command)
      if let old = try result(id: command.id), old.isTerminal { try repairTerminalSlot(workoutID: command.workoutID); return old }
      let result = WorkoutCommandResult(commandID: command.id, outcome: outcome,
        ownerRevision: try snapshot(workoutID: command.workoutID)?.ownerRevision, reason: reason)
      try db.put(namespace: "command-results", key: command.id, value: encode(result))
      if try active(workoutID: command.workoutID)?.id == command.id { try db.remove(namespace: "active-owner-command", key: command.workoutID) }
      try advanceTerminalOrigin(workoutID: command.workoutID, origin: command.origin, db: db)
      return result
    }
  }
  /// Legacy queries remain terminal protocol receipts without skipping earlier effects.
  func settleQuery(_ command: WorkoutCommand) throws -> WorkoutCommandResult {
    guard command.action == "status" else { throw WorkoutDataError.invalid("Not an owner query") }
    return try settleWithoutEffect(command, outcome: "applied", reason: "Read-only owner query")
  }
  /// Terminal owner evidence is required to settle a stop skipped by inconsistent counters.
  func settleTerminal(_ command: WorkoutCommand) throws -> WorkoutCommandResult? {
    guard let snapshot = try snapshot(workoutID: command.workoutID), WorkoutOwnerPhase.terminal(snapshot.phase) else { return nil }
    let stopped = command.endsWorkout && snapshot.phase == "completed" && snapshot.stopCutoff != nil && (command.action != "discard" || snapshot.healthOutcome == "discarded")
    return try settleWithoutEffect(command, outcome: stopped ? "applied" : "rejected",
      reason: stopped ? "Original owner already ended; its observed cutoff is retained" : "The original owner is terminal")
  }
  func rejectedStart(workoutID: String) throws -> Bool {
    try store.read { db in
      for origin in ["phone", "watch"] {
        if let id = try read(String.self, db: db, namespace: "command-sequences", key: workoutID + ":" + origin + ":1"),
          let command = try command(id: id), command.action == "start", try result(id: id)?.outcome == "rejected" { return true }
      }
      return false
    }
  }
  func initialStart(workoutID: String) throws -> WorkoutCommand? {
    try store.read { db in
      for origin in ["phone", "watch"] {
        if let id = try read(String.self, db: db, namespace: "command-sequences", key: workoutID + ":" + origin + ":1"),
          let command = try command(id: id), command.action == "start" { return command }
      }
      return nil
    }
  }
  func cancelledStart(workoutID: String) throws -> String? {
    try store.read { db in try db.get(namespace: "cancelled-owner-start", key: workoutID).flatMap { String(data: $0, encoding: .utf8) } }
  }
  /// The atomic cancellation receipt outranks a phone checkpoint written before that receipt.
  func cancelledWithoutOwner(workoutID: String) throws -> String? {
    try store.read { _ in
      guard try snapshot(workoutID: workoutID) == nil, try active(workoutID: workoutID) == nil else { return nil }
      return try cancelledStart(workoutID: workoutID)
    }
  }
  func cancelStartIntent(workoutID: String, reason: String) throws {
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: workoutID)
      try db.put(namespace: "cancelled-owner-start", key: workoutID, value: Data(reason.utf8))
    }
  }
  func pendingStop(workoutID: String, origin: String) throws -> WorkoutCommand? {
    try store.read { db in
      let prefix = workoutID + ":" + origin + ":"
      var after = prefix, first: WorkoutCommand?
      while true {
        let page = try db.page(namespace: "command-sequences", after: after, limit: 128)
        for row in page {
          guard row.key.hasPrefix(prefix) else { return first }
          let id = try JSONDecoder().decode(String.self, from: row.value)
          if let command = try command(id: id), command.workoutID == workoutID, command.origin == origin,
            command.endsWorkout, try result(id: id)?.isTerminal == false,
            first == nil || command.originSequence < first!.originSequence { first = command }
        }
        guard page.count == 128 else { return first }; after = page.last!.key
      }
    }
  }
  func remoteCommand(id: String, workoutID: String) throws -> WorkoutCommand? {
    try store.read { db in
      guard let command = try read(WorkoutCommand.self, db: db, namespace: "outgoing-commands", key: id), command.workoutID == workoutID,
        try self.command(id: id) == nil else { return nil } // Locally admitted effects are never transport commands.
      return command
    }
  }
  func retryForAcknowledgement(_ result: WorkoutCommandResult, acknowledgedID: String, workoutID: String) throws -> WorkoutCommand? {
    guard result.commandID == acknowledgedID, let acknowledged = try remoteCommand(id: acknowledgedID, workoutID: workoutID),
      let missing = result.missingOriginSequence, missing > 0,
      let candidate = try outgoing(workoutID: workoutID, origin: acknowledged.origin, sequence: missing),
      try remoteCommand(id: candidate.id, workoutID: workoutID) != nil else { return nil }
    return candidate
  }
}

/// Queries deliberately have no origin sequence or active-command slot.
struct WorkoutOwnerQuery: Equatable {
  let workoutID: String
  let id: String
  let pendingCommandID: String?
  init(workoutID: String, id: String = UUID().uuidString.lowercased(), pendingCommandID: String? = nil) throws {
    self.workoutID = try WorkoutCoding.id(workoutID); self.id = try WorkoutCoding.id(id)
    self.pendingCommandID = try pendingCommandID.map(WorkoutCoding.id)
  }
  var packet: [String: Any] {
    var value: [String: Any] = ["schemaVersion": 1, "kind": "ownerQuery", "workoutId": workoutID, "messageId": id, "queryId": id]
    if let pendingCommandID { value["pendingCommandId"] = pendingCommandID }; return value
  }
  static func decode(_ value: [String: Any]) throws -> Self {
    guard value["kind"] as? String == "ownerQuery", let id = value["queryId"] as? String, id == value["messageId"] as? String,
      let workoutID = value["workoutId"] as? String else { throw WorkoutDataError.invalid("Invalid owner query identity") }
    return try Self(workoutID: workoutID, id: id, pendingCommandID: value["pendingCommandId"] as? String)
  }
  func matches(_ packet: [String: Any]) -> Bool { packet["kind"] as? String == "ownerReply" && packet["queryId"] as? String == id && packet["workoutId"] as? String == workoutID }
}

enum WorkoutOwnerAdmission {
  static let cancelledStopReason = "Unconfirmed start cancelled. Earlier data is retained; no workout end time was inferred"
  static func cancelPhoneAfterLookup(_ error: Error, workoutID: String, control: WorkoutControlJournal,
                                     nativeAbsenceConfirmed: Bool, effectInFlight: Bool) throws -> WorkoutCommandResult? {
    guard error is WorkoutSavedOwnerLookupError, let stop = try control.pendingStop(workoutID: workoutID, origin: "phone") else { return nil }
    return try cancelUnconfirmed(stop, control: control, nativeAbsenceConfirmed: nativeAbsenceConfirmed, effectInFlight: effectInFlight)
  }
  /// Explicit cancellation removes the intent to start again, not historical workout evidence.
  /// A successful, fresh no-active-session probe is required; empty Health history alone is insufficient.
  static func cancelUnconfirmed(_ stop: WorkoutCommand, control: WorkoutControlJournal,
                                nativeAbsenceConfirmed: Bool, effectInFlight: Bool) throws -> WorkoutCommandResult? {
    guard stop.action == "stop", nativeAbsenceConfirmed, !effectInFlight else { return nil }
    return try control.store.transaction(priority: .capture) { _ in
      guard try control.snapshot(workoutID: stop.workoutID) == nil else { return nil }
      let reason = "Unconfirmed start cancelled by explicit stop after the owner verified no active native session; historical outcome remains unknown"
      try control.cancelStartIntent(workoutID: stop.workoutID, reason: reason)
      if let start = try control.initialStart(workoutID: stop.workoutID), let result = try control.result(id: start.id), !result.isTerminal {
        _ = try control.settleWithoutEffect(start, reason: reason)
      }
      return try control.settleWithoutEffect(stop, reason: cancelledStopReason)
    }
  }
  /// A query may settle derivable receipts, but never admits a fresh native effect.
  static func query(_ command: WorkoutCommand, control: WorkoutControlJournal, nativeWorkoutID: String?) throws -> WorkoutCommandResult {
    _ = try control.accept(command); try control.repairTerminalSlot(workoutID: command.workoutID)
    if command.action == "status" { return try control.settleQuery(command) }
    if let result = try control.result(id: command.id), result.isTerminal { return result }
    if command.action == "start", let reason = try control.cancelledStart(workoutID: command.workoutID) { return try control.settleWithoutEffect(command, reason: reason) }
    if let result = try control.settleTerminal(command) { return result }
    if command.action == "start", let nativeWorkoutID, nativeWorkoutID != command.workoutID {
      return try control.settleWithoutEffect(command, reason: "Another workout is active")
    }
    if command.action != "start", nativeWorkoutID != command.workoutID,
      try control.snapshot(workoutID: command.workoutID) == nil, try control.rejectedStart(workoutID: command.workoutID) {
      return try control.settleWithoutEffect(command, reason: "The original start was rejected; no matching owner has confirmed this action")
    }
    var result = try control.result(id: command.id)!
    result.reason = "The original owner has not confirmed this action; recovery remains unresolved"
    return result
  }
  /// Runs before prepare() can mark an effect executing. Production Watch routing uses this boundary.
  static func prepare(_ command: WorkoutCommand, control: WorkoutControlJournal, nativeWorkoutID: String?,
                      readyToStart: Bool, recovering: Bool) throws -> WorkoutCommandPreparation {
    _ = try control.accept(command); try control.repairTerminalSlot(workoutID: command.workoutID)
    func settled(_ result: WorkoutCommandResult) -> WorkoutCommandPreparation { WorkoutCommandPreparation(result: result, execute: false, reconcile: false) }
    if command.action == "status" { return settled(try control.settleQuery(command)) }
    if let result = try control.result(id: command.id), result.isTerminal { return settled(result) }
    if let result = try control.settleTerminal(command) { return settled(result) }
    if command.action == "start", let nativeWorkoutID, nativeWorkoutID != command.workoutID {
      return settled(try control.settleWithoutEffect(command, reason: "Another workout is active"))
    }
    if command.action == "start", nativeWorkoutID == nil, (!readyToStart || recovering) {
      return settled(WorkoutCommandResult(commandID: command.id, outcome: "accepted", reason: "Owner recovery is still in progress; retry this request"))
    }
    if command.action != "start", nativeWorkoutID != command.workoutID {
      return settled(try query(command, control: control, nativeWorkoutID: nativeWorkoutID))
    }
    return try control.prepare(command)
  }
}

/// Finalization and explicit repair read one immutable collection identity, never the selected UI ride.
enum WorkoutPhoneSealRepair {
  /// Metadata-only keyset discovery; later originals invalidate finalization in the same append transaction.
  static func pendingLocal(archive: WorkoutArchive, afterID: String = "", limit: Int = 8) throws -> [String] {
    try archive.store.read { db in
      try db.rows("SELECT id FROM collections WHERE kind='workout' AND ended_at IS NOT NULL AND id>? AND json_extract(CAST(metadata AS TEXT),'$.watchEnabled')=0 AND json_extract(CAST(metadata AS TEXT),'$.saveToHealth')=0 AND json_extract(CAST(metadata AS TEXT),'$.healthKitState')='notRequested' AND coalesce(json_extract(CAST(metadata AS TEXT),'$.finalizationState'),'pending') NOT IN ('complete','partial') AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=collections.id) ORDER BY id LIMIT ?",
        [.text(afterID), .integer(Int64(max(1, min(8, limit))))], limit: max(1, min(8, limit))).compactMap { $0.string("id") }
    }
  }
  @discardableResult
  static func seal(id: String, archive: WorkoutArchive, transfer: WorkoutTransferJournal,
                   control: WorkoutControlJournal) throws -> WorkoutSeal {
    var metadata = try archive.metadata(id: id), owner = try control.snapshot(workoutID: id)
    if !metadata.watchEnabled, !metadata.savesToHealth, let endedAt = metadata.endedAt {
      // Local capture already froze its cutoff. Repair a lost local stop commit by its exact ID;
      // other unresolved actions (especially Discard) can never become a Save through sealing.
      try archive.store.transaction(priority: .capture) { _ in
        let retained = try control.active(workoutID: id) ?? control.nextReady(workoutID: id, origin: "phone")
        if let retained, retained.action == "stop" {
          let prepared = try control.prepare(retained)
          if prepared.execute || prepared.reconcile {
            let cutoff = try WorkoutCoding.date(endedAt)
            _ = try WorkoutLocalOwner.observe(id: id, phase: "completed", at: cutoff,
              elapsed: metadata.stopElapsedSeconds ?? max(0, cutoff.timeIntervalSince(try WorkoutCoding.date(metadata.startedAt))),
              command: retained, cutoff: cutoff, discarded: false, archive: archive, control: control)
            try archive.finish(id: id, endedAt: cutoff, finalPhase: "completed")
          }
        }
        owner = try control.snapshot(workoutID: id)
        guard owner?.owner == "phone", owner?.phase == "completed", owner?.stopCutoff == endedAt,
          try control.active(workoutID: id) == nil else { throw WorkoutDataError.invalid("The original local stop has not committed") }
      }
      metadata = try archive.metadata(id: id)
    }
    guard !metadata.watchEnabled, owner == nil || owner?.owner == "phone",
      let cutoff = owner?.stopCutoff ?? metadata.endedAt else { throw WorkoutDataError.invalid("The original phone owner has not confirmed an end time") }
    let health = owner?.healthOutcome ?? metadata.healthKitState
    let sources = try transfer.roster(id: id).map { try transfer.source(id: id, producer: $0) }
    let requirements = ["healthSave": metadata.savesToHealth ? (health == "saved" ? "sealed" : "pending") : "notRequested",
      "cycInsertion": try WorkoutHealthInsertionJournal(archive: archive).outcome(id: id)]
    let previous = try transfer.currentSeal(id: id)
    if let previous, previous.sources == sources, previous.requirements == requirements,
      previous.ownerRevision == (owner?.ownerRevision ?? 0), previous.healthOutcome == health,
      previous.stopCutoff == cutoff, previous.stopElapsedSeconds == metadata.stopElapsedSeconds {
      _ = try transfer.verify(id: id); return previous
    }
    let seal = WorkoutSeal(workoutID: id, sealRevision: (previous?.sealRevision ?? 0) + 1,
      collectionRevision: metadata.collectionRevision ?? 0, ownerRevision: owner?.ownerRevision ?? 0,
      stopCutoff: cutoff, healthOutcome: health, requirements: requirements, sources: sources,
      stopElapsedSeconds: metadata.stopElapsedSeconds, saveToHealth: metadata.savesToHealth, recordGPS: metadata.recordsGPS)
    _ = try transfer.accept(seal: seal); _ = try transfer.verify(id: id); return seal
  }
}

struct WorkoutCommandPreparation {
  let result: WorkoutCommandResult
  let execute: Bool
  let reconcile: Bool
}
extension WorkoutControlJournal {

  func prepare(_ command: WorkoutCommand) throws -> WorkoutCommandPreparation {
    try store.transaction(priority: .capture) { _ in
      let previous = try result(id: command.id)
      _ = try accept(command)
      let decision = try begin(command)
      return WorkoutCommandPreparation(result: decision,
        execute: decision.outcome == "executing" && previous?.outcome != "executing",
        reconcile: decision.outcome == "executing" && previous?.outcome == "executing")
    }
  }
}

enum WorkoutRecoveryAction: Equatable { case settled, findSavedWorkout, finishSameSession, reconcileLap, applyPause, applyResume, observeSession }
enum WorkoutUnconfirmedStopPolicy {
  enum Action { case cancelPreparation, retainOwnerIntent, finishConfirmed }
  static func action(hasTimeline: Bool, preparing: Bool, hasNativeIntent: Bool) -> Action {
    if hasTimeline { return .finishConfirmed }
    return preparing && !hasNativeIntent ? .cancelPreparation : .retainOwnerIntent
  }
  static func confirmedCutoff(requestedAt: Date, actualStart: Date, now: Date) -> Date { max(actualStart, min(requestedAt, now)) }
}
enum WorkoutOwnerTiming {
  static func elapsed(start: Date, end: Date?, reported: Double?, now: Date) -> Double {
    if let reported, reported.isFinite, reported >= 0 { return reported }
    return max(0, (end ?? now).timeIntervalSince(start))
  }
}
enum WorkoutRecoveryPlanner {
  static func inspectsWatchCandidate(phase: String, health: String, finalHealthExtracted: Bool) -> Bool {
    ["running", "paused", "finishing", "recoverable"].contains(phase) ||
      (WorkoutOwnerPhase.canonical(phase) == "completed" && (!["saved", "notRequested"].contains(health) || !finalHealthExtracted))
  }
  static func needsSavedIdentityLookup(requestedID: String?, candidateID: String?, hasNativeSession: Bool) -> Bool {
    requestedID != nil && candidateID == nil && !hasNativeSession
  }
  static func waitsForStart(phase: String, startCompletionPending: Bool) -> Bool {
    startCompletionPending && ["preparing", "recoverable"].contains(phase)
  }
  /// Ended presentation is independent of the unresolved native operation and final seal.
  static func action(hasCutoff: Bool, verifiedFinality: Bool, nativePhase: String?, pendingAction: String?) -> WorkoutRecoveryAction {
    if verifiedFinality && pendingAction == nil { return .settled }
    if nativePhase == "stopped" || nativePhase == "ended" { return .finishSameSession }
    if ["stop", "discard"].contains(pendingAction ?? ""), nativePhase != nil { return .finishSameSession }
    if pendingAction == "lap", nativePhase != nil { return .reconcileLap }
    if hasCutoff && pendingAction != "pause" && pendingAction != "resume" { return nativePhase == nil ? .findSavedWorkout : .finishSameSession }
    guard let nativePhase else { return .findSavedWorkout }
    if pendingAction == "pause" && nativePhase != "paused" { return .applyPause }
    if pendingAction == "resume" && nativePhase != "running" { return .applyResume }
    return .observeSession
  }
}

struct WorkoutTimelineAnchor: Codable, Equatable {
  let epoch: String
  let monotonicOrigin: Double
  let startedAt: String
  var stopMonotonic: Double?
  var stopUTC: String?
  var uncertainty: String? = nil
  struct Mapping { let elapsed: Double; let eligible: Bool; let uncertainty: String? }
  func map(epoch sampleEpoch: String?, acquisition: Double?, timestamp: Date) throws -> Mapping {
    if sampleEpoch == epoch, let acquisition, acquisition.isFinite {
      let elapsed = acquisition - monotonicOrigin
      return Mapping(elapsed: max(0, elapsed), eligible: elapsed >= 0 && (stopMonotonic.map { acquisition <= $0 } ?? true), uncertainty: uncertainty)
    }
    let start = try WorkoutCoding.date(startedAt)
    let elapsed = timestamp.timeIntervalSince(start)
    let cutoff = try stopUTC.map(WorkoutCoding.date)
    return Mapping(elapsed: max(0, elapsed), eligible: elapsed >= 0 && (cutoff.map { timestamp <= $0 } ?? true),
      uncertainty: "UTC fallback for a different or unavailable acquisition epoch")
  }
}

/// Transport staging is bounded; canonical observations remain the source for offline recovery.
final class WorkoutBoundedOutbox {
  static let maximumEvents = 16
  static let maximumPackets = 64
  static let maximumBytes = 512 * 1024
  let store: PowerLogStore
  init(store: PowerLogStore) { self.store = store }
  func packets() throws -> [(key: String, value: Data)] {
    try store.read { db in try db.page(namespace: "phone-outbox", limit: Self.maximumPackets) }
  }
  @discardableResult
  func enqueue(_ packet: [String: Any]) throws -> Bool {
    guard let id = packet["messageId"] as? String, UUID(uuidString: id) != nil else { throw WorkoutDataError.invalid("Invalid outbox identity") }
    let data = try JSONSerialization.data(withJSONObject: packet, options: [.sortedKeys])
    guard data.count <= 60_000 else { throw WorkoutDataError.invalid("Outbox packet exceeds bound") }
    return try store.transaction(priority: .capture) { db in
      if let previous = try db.get(namespace: "phone-outbox", key: id) {
        guard previous == data else { throw WorkoutDataError.invalid("Changed outbox identity") }; return true
      }
      let rows = try db.page(namespace: "phone-outbox", limit: Self.maximumPackets)
      let eventCount = rows.filter { (try? JSONSerialization.jsonObject(with: $0.value) as? [String: Any])?["kind"] as? String == "events" }.count
      let isEvents = packet["kind"] as? String == "events"
      guard rows.count < Self.maximumPackets, rows.reduce(data.count, { $0 + $1.value.count }) <= Self.maximumBytes,
        !isEvents || eventCount < Self.maximumEvents else { return false }
      try db.put(namespace: "phone-outbox", key: id, value: data, immutable: true); return true
    }
  }
  func acknowledge(_ id: String) throws {
    try store.transaction(priority: .capture) { db in try db.remove(namespace: "phone-outbox", key: id) }
  }
}

extension WorkoutControlJournal {
  func nextReady(workoutID: String, origin: String) throws -> WorkoutCommand? {
    try store.read { db in
      let applied = try db.get(namespace: "applied-origins", key: workoutID + ":" + origin)
        .map { try JSONDecoder().decode(Int64.self, from: $0) } ?? 0
      guard let data = try db.get(namespace: "command-sequences", key: workoutID + ":" + origin + ":" + String(applied + 1)) else { return nil }
      let id = try JSONDecoder().decode(String.self, from: data)
      return try command(id: id)
    }
  }
  func admitLocal(workoutID: String, origin: String, action: String, at: Date,
                  options: [String: WorkoutJSON] = [:]) throws -> WorkoutCommand {
    try store.transaction(priority: .capture) { _ in
      try repairTerminalSlot(workoutID: workoutID)
      if try active(workoutID: workoutID) != nil, !["stop", "discard"].contains(action) {
        throw WorkoutDataError.invalid("Wait for the pending owner action before issuing another action")
      }
      let command = try create(workoutID: workoutID, origin: origin, action: action, at: at, options: options)
      _ = try accept(command)
      return command
    }
  }
}

/// Validate and commit remote adoption before the live engine changes its selected workout identity.
enum WorkoutOwnerAdoption {
  static func accept(_ incoming: WorkoutOwnerSnapshot, archive: WorkoutArchive, control: WorkoutControlJournal,
                     startedAt: Date, indoor: Bool, saveToHealth: Bool = true, recordGPS: Bool? = nil) throws -> WorkoutMetadata? {
    guard incoming.owner == "watch", ["running", "paused"].contains(incoming.phase), incoming.stopCutoff == nil else { return nil }
    return try archive.store.transaction(priority: .capture) { db in
      let previous = try control.snapshot(workoutID: incoming.workoutID)
      let newer = try WorkoutControlReducer.accepts(incoming, previous: previous)
      guard newer || previous == incoming else { return nil }
      let exists = try db.scalarInt("SELECT count(*) FROM collections WHERE id=?", [.text(incoming.workoutID)]) ?? 0
      let metadata: WorkoutMetadata
      if exists > 0 {
        metadata = try archive.metadata(id: incoming.workoutID)
        try WorkoutRecordingPolicy.requireOptions(metadata, saveToHealth: saveToHealth, recordGPS: recordGPS ?? !indoor)
        guard metadata.watchEnabled, metadata.endedAt == nil, ["preparing", "running", "paused", "recoverable"].contains(metadata.phase) else { return nil }
      } else {
        guard previous?.stopCutoff == nil, !["completed", "failed"].contains(previous?.phase ?? "") else { return nil }
        metadata = try archive.create(id: incoming.workoutID, startedAt: startedAt, indoor: indoor, watchEnabled: true, saveToHealth: saveToHealth, recordGPS: recordGPS)
      }
      if newer { _ = try control.accept(snapshot: incoming) }
      return metadata
    }
  }
}

struct WorkoutAdoptedState {
  let metadata: WorkoutMetadata
  let owner: WorkoutOwnerSnapshot
  let timeline: WorkoutTimelineAnchor
  let elapsed: Double
  let active: Double
  var phase: String { owner.phase }
}
extension WorkoutOwnerAdoption {
  /// Adoption enters the reported active phase directly; it never re-runs a new collection's start effect.
  static func activate(_ incoming: WorkoutOwnerSnapshot, archive: WorkoutArchive, control: WorkoutControlJournal,
                       startedAt: Date, indoor: Bool, now: Date, uptime: Double, epoch: String,
                       reportedElapsed: Double?, reportedTimer: Double?, saveToHealth: Bool = true, recordGPS: Bool? = nil) throws -> WorkoutAdoptedState? {
    try archive.store.transaction(priority: .capture) { _ in
      guard let metadata = try accept(incoming, archive: archive, control: control, startedAt: startedAt, indoor: indoor, saveToHealth: saveToHealth, recordGPS: recordGPS) else { return nil }
      let retainedStart = try WorkoutCoding.date(metadata.startedAt)
      let reported = reportedElapsed.flatMap { $0.isFinite && $0 >= 0 && $0 <= 2_678_400 ? $0 : nil }
      let elapsed = reported ?? max(0, now.timeIntervalSince(retainedStart))
      let active = reportedTimer.flatMap { $0.isFinite && $0 >= 0 && $0 <= elapsed ? $0 : nil } ?? 0
      let anchor = WorkoutTimelineAnchor(epoch: epoch, monotonicOrigin: uptime - elapsed, startedAt: metadata.startedAt,
        uncertainty: reported == nil ? "UTC estimate when adopting a remote workout into a new acquisition epoch" :
          "Remote owner elapsed estimate anchored to a new local acquisition epoch; transit time is uncertain")
      try archive.update(id: metadata.id, phase: incoming.phase, healthKitState: incoming.healthOutcome, healthKitUUID: incoming.healthWorkoutID)
      return WorkoutAdoptedState(metadata: try archive.metadata(id: metadata.id), owner: incoming, timeline: anchor, elapsed: elapsed, active: active)
    }
  }
}

struct WorkoutRecoveredCommandResult {
  var completed: WorkoutCommand?
  var snapshot: WorkoutOwnerSnapshot?
  var required: WorkoutCommand?
  var next: WorkoutCommand?
  var insertedLap = false
}

/// Reconciles native effects already observed before a crash, without a new transport/delegate callback.
/// Lifecycle repair, terminal receipt, owner revision and caller metadata commit together.
enum WorkoutRecoveredOwnerCommand {
  static func reconcile(workoutID: String, owner: String, nativePhase: String, observedAt: Date,
                        nativeEventDates: [String: Date], observedLapIDs: Set<String>,
                        cutoff: Date?, health: String, healthID: String?, archive: WorkoutArchive,
                        control: WorkoutControlJournal,
                        save: (WorkoutOwnerSnapshot) throws -> Void = { _ in }) throws -> WorkoutRecoveredCommandResult {
    try archive.store.transaction(priority: .capture) { _ in
      func next() throws -> WorkoutCommand? {
        for origin in [owner, owner == "watch" ? "phone" : "watch"] {
          if let command = try control.nextReady(workoutID: workoutID, origin: origin) { return command }
        }
        return nil
      }
      guard let command = try control.active(workoutID: workoutID) else {
        return WorkoutRecoveredCommandResult(next: try next())
      }
      if command.action == "discard", health != "discarded" { return WorkoutRecoveredCommandResult(required: command) }
      let ended = ["stopped", "ended"].contains(nativePhase)
      let observed: Bool
      switch command.action {
      case "pause": observed = nativePhase == "paused"
      case "resume": observed = nativePhase == "running"
      case "lap": observed = observedLapIDs.contains(command.id)
      case "start": observed = ["running", "paused"].contains(nativePhase)
      case "stop", "discard": observed = ended
      default: observed = true
      }
      guard observed || ended else { return WorkoutRecoveredCommandResult(required: command) }
      let date = nativeEventDates[command.action] ?? observedAt
      var insertedLap = false
      if observed, ["start", "pause", "resume", "lap"].contains(command.action),
        try !archive.hasEvent(id: workoutID, eventID: command.id) {
        let start = try WorkoutCoding.date(archive.metadata(id: workoutID).startedAt)
        let event = try WorkoutEvent(workoutId: workoutID, kind: "lifecycle", source: owner, timestamp: date,
          elapsedSeconds: max(0, date.timeIntervalSince(start)), payload: ["action": .string(command.action),
            "operationId": .string(command.id), "recoveredNativeEffect": .bool(true),
            "timelineMappingUncertainty": .string("UTC estimate for recovered native lifecycle")], eventId: command.id)
        try archive.append(event)
        insertedLap = command.action == "lap"
      }
      let resolvedCutoff = try cutoff ?? (ended ? (command.endsWorkout ? WorkoutCoding.date(command.requestedAt) : observedAt) : nil)
      let snapshot = try control.observe(workoutID: workoutID, owner: owner,
        phase: ended ? "completed" : cutoff == nil ? nativePhase : "finishing", at: date,
        health: health, healthID: healthID, cutoff: resolvedCutoff, command: command,
        failure: observed ? nil : "Native session ended without evidence that the pending action was applied")
      try save(snapshot)
      return WorkoutRecoveredCommandResult(completed: command, snapshot: snapshot, next: try next(), insertedLap: insertedLap)
    }
  }
}

struct WorkoutPendingRemoteAction: Codable, Equatable {
  let commandID: String
  let workoutID: String
  let action: String
  init(_ command: WorkoutCommand) { commandID = command.id; workoutID = command.workoutID; action = command.action }
  func matches(_ result: WorkoutCommandResult, acknowledgedID: String, workoutID: String?) -> Bool {
    result.isTerminal && result.commandID == commandID && acknowledgedID == commandID && workoutID == self.workoutID
  }
}

struct WorkoutActivityRequest {
  let rideID: String
  let token: String
  let action: String
  let expectedPhase: String

  init(rideID: String, token: String, action: String, expectedPhase: String) throws {
    self.rideID = try WorkoutCoding.id(rideID)
    self.token = try WorkoutCoding.id(token)
    guard ["pause", "resume", "finish"].contains(action) else {
      throw WorkoutDataError.invalid("Ride controls are unavailable.")
    }
    self.action = action; self.expectedPhase = expectedPhase
  }

  var nativeAction: String { action == "finish" ? "stop" : action }
  fileprivate var key: String { rideID + ":" + token }

  func requireCurrent(rideID: String?, token: String, phase: String, pendingAction: String?) throws {
    guard self.rideID == rideID, self.token == token, expectedPhase == phase, pendingAction == nil,
      action != "pause" || phase == "running", action != "resume" || phase == "paused",
      action != "finish" || ["running", "paused"].contains(phase) else {
      throw WorkoutDataError.invalid("Ride changed. Use its current controls.")
    }
  }
}

enum WorkoutOwnerStopPolicy {
  static func isUnconfirmed(watchOwned: Bool, phase: String, hasCutoff: Bool,
                            ownerPhase: String?, stopOutcome: String?, verified: Bool) -> Bool {
    watchOwned && phase == "completed" && hasCutoff && ownerPhase != "completed"
      && stopOutcome != "applied" && !verified
  }
}

enum WorkoutActivityPhase {
  static func resolve(_ phase: String, watchOwned: Bool, hasCutoff: Bool, ownerPhase: String?,
                      stopOutcome: String?, verified: Bool, pendingStop: Bool, phoneStopping: Bool) -> String {
    // A confirmed owner end outranks an undelivered command receipt or archive transfer.
    if watchOwned && (ownerPhase == "completed" || stopOutcome == "applied" || verified) { return "completed" }
    if pendingStop || phoneStopping || watchOwned && ownerPhase == "finishing" { return "finishing" }
    return WorkoutOwnerStopPolicy.isUnconfirmed(watchOwned: watchOwned, phase: phase, hasCutoff: hasCutoff,
      ownerPhase: ownerPhase, stopOutcome: stopOutcome, verified: verified) ? "recoverable" : phase
  }
}

private struct WorkoutActivityCommandRecord: Codable {
  let action: String
  let commandID: String
}

extension WorkoutControlJournal {
  func activityCommand(_ request: WorkoutActivityRequest) throws -> WorkoutCommand? {
    try store.read { db in
      guard let data = try db.get(namespace: "activity-commands", key: request.key) else { return nil }
      let record = try JSONDecoder().decode(WorkoutActivityCommandRecord.self, from: data)
      guard record.action == request.action else { throw WorkoutDataError.invalid("This control has already been used.") }
      guard let command = try command(id: record.commandID) ?? remoteCommand(id: record.commandID, workoutID: request.rideID),
        command.workoutID == request.rideID, command.action == request.nativeAction else {
        throw WorkoutDataError.invalid("The original ride command is unavailable.")
      }
      return command
    }
  }

  /// Bind the displayed control only when its native effect or transport intent is durable too.
  func admitActivity(_ request: WorkoutActivityRequest, remote: Bool, at date: Date,
                     options: [String: WorkoutJSON]) throws -> WorkoutCommand {
    try store.transaction(priority: .capture) { db in
      guard try activityCommand(request) == nil else { throw WorkoutDataError.invalid("This control has already been admitted.") }
      let command = try remote
        ? createRemote(workoutID: request.rideID, origin: "phone", action: request.nativeAction, at: date, options: options)
        : admitLocal(workoutID: request.rideID, origin: "phone", action: request.nativeAction, at: date, options: options)
      let record = WorkoutActivityCommandRecord(action: request.action, commandID: command.id)
      try db.put(namespace: "activity-commands", key: request.key, value: WorkoutCoding.encoder().encode(record), immutable: true)
      if remote {
        guard try WorkoutBoundedOutbox(store: store).enqueue(command.packet) else {
          throw WorkoutDataError.invalid("Watch transport is busy. Retry this control.")
        }
      } else {
        let result = try begin(command)
        guard result.outcome == "executing" else { throw WorkoutDataError.invalid(result.reason ?? "Owner command is pending.") }
      }
      return command
    }
  }

  /// A retry may repair staging, but cannot apply a terminal action again or affect a new ride.
  func restageActivity(_ command: WorkoutCommand, currentRideID: String?) throws -> Bool {
    try store.transaction(priority: .capture) { db in
      guard command.workoutID == currentRideID,
        try pendingRemote(workoutID: command.workoutID)?.commandID == command.id,
        try remoteCommand(id: command.id, workoutID: command.workoutID) == command else { return false }
      if let data = try db.get(namespace: "remote-command-results", key: command.id),
        try JSONDecoder().decode(WorkoutCommandResult.self, from: data).isTerminal { return false }
      try store.requireWorkoutAvailable(id: command.workoutID)
      guard try WorkoutBoundedOutbox(store: store).enqueue(command.packet) else {
        throw WorkoutDataError.invalid("Watch transport is busy. Retry this control.")
      }
      return true
    }
  }
}

extension WorkoutControlJournal {
  func createRemote(workoutID: String, origin: String, action: String, at: Date,
                    options: [String: WorkoutJSON] = [:]) throws -> WorkoutCommand {
    try store.transaction(priority: .capture) { db in
      let command = try create(workoutID: workoutID, origin: origin, action: action, at: at, options: options)
      if action != "status" { try db.put(namespace: "pending-remote-action", key: workoutID, value: WorkoutCoding.encoder().encode(WorkoutPendingRemoteAction(command))) }
      return command
    }
  }
  func pendingRemote(workoutID: String) throws -> WorkoutPendingRemoteAction? {
    try store.read { db in try db.get(namespace: "pending-remote-action", key: workoutID).map { try JSONDecoder().decode(WorkoutPendingRemoteAction.self, from: $0) } }
  }
  func confirmedRemoteStop(workoutID: String) throws -> WorkoutCommandResult? {
    try store.read { db in try read(WorkoutCommandResult.self, db: db, namespace: "confirmed-remote-stop", key: workoutID) }
  }
  func rejectedRemoteWithoutOwner(workoutID: String) throws -> String? {
    try store.read { db in
      guard try pendingRemote(workoutID: workoutID) == nil, try snapshot(workoutID: workoutID) == nil,
        try active(workoutID: workoutID) == nil,
        let result = try read(WorkoutCommandResult.self, db: db, namespace: "terminal-remote-action", key: workoutID),
        ["failed", "rejected"].contains(result.outcome),
        let command = try remoteCommand(id: result.commandID, workoutID: workoutID), ["start", "stop"].contains(command.action) else { return nil }
      return result.reason ?? "The original owner rejected the unconfirmed action; no workout end time was inferred."
    }
  }
  func completeRemote(_ result: WorkoutCommandResult, acknowledgedID: String, workoutID: String) throws -> Bool {
    try store.transaction(priority: .capture) { db in
      guard result.isTerminal, result.commandID == acknowledgedID,
        let command = try remoteCommand(id: acknowledgedID, workoutID: workoutID) else { return false }
      try db.put(namespace: "remote-command-results", key: acknowledgedID, value: encode(result))
      if command.endsWorkout, result.outcome == "applied" {
        try db.put(namespace: "confirmed-remote-stop", key: workoutID, value: encode(result))
      }
      guard let pending = try pendingRemote(workoutID: workoutID), pending.matches(result, acknowledgedID: acknowledgedID, workoutID: workoutID) else { return false }
      // The receipt and cleared intent must survive a crash before phone-current is checkpointed.
      if result.outcome == "applied" { try db.remove(namespace: "terminal-remote-action", key: workoutID) }
      else { try db.put(namespace: "terminal-remote-action", key: workoutID, value: encode(result)) }
      try db.remove(namespace: "pending-remote-action", key: workoutID); return true
    }
  }
}

enum WorkoutPhoneStartProjection {
  static func confirm(archive: WorkoutArchive, id: String, startedAt: Date, now: Date, uptime: Double, epoch: String) throws -> WorkoutTimelineAnchor {
    try archive.store.transaction(priority: .capture) { _ in
      let previous = try archive.metadata(id: id)
      if previous.phase == "recoverable", previous.endedAt == nil { try archive.update(id: id, phase: "preparing") }
      let metadata = try archive.confirmStart(id: id, startedAt: startedAt)
      let elapsed = max(0, now.timeIntervalSince(try WorkoutCoding.date(metadata.startedAt)))
      return WorkoutTimelineAnchor(epoch: epoch, monotonicOrigin: uptime - elapsed, startedAt: metadata.startedAt)
    }
  }
}

/// One active archive worker and bounded pending identity metadata. Overflow is rediscovered from the catalog.
struct WorkoutBoundedWorkQueue {
  static let maximumPending = 8
  private(set) var active: String?
  private(set) var pending: [String] = []
  private var invalidated = false
  mutating func request(_ id: String) -> Bool {
    if active == id { invalidated = true; return false }
    if active == nil { active = id; return true }
    if !pending.contains(id), pending.count < Self.maximumPending { pending.append(id) }
    return false
  }
  /// A deleted pending identity must not consume the next worker handoff.
  /// Active work remains owned until its normal completion.
  mutating func removePending(_ id: String) {
    pending.removeAll { $0 == id }
  }
  mutating func finish(_ id: String) -> String? {
    guard active == id else { return nil }
    active = nil
    if invalidated {
      // Accepted peers keep their turn. A full queue defers this redundant rerun to durable discovery.
      if pending.count < Self.maximumPending { pending.append(id) }
      invalidated = false
    }
    return pending.isEmpty ? nil : pending.removeFirst()
  }
}

extension WorkoutPhoneStartProjection {
  static func confirmOwnerPhase(archive: WorkoutArchive, id: String, localPhase: String, ownerPhase: String,
                                startedAt: Date?, now: Date, uptime: Double, epoch: String) throws -> WorkoutTimelineAnchor? {
    guard localPhase == "preparing", ["running", "paused", "finishing", "completed"].contains(ownerPhase) else { return nil }
    guard let startedAt else { throw WorkoutDataError.invalid("The started owner did not provide its original start time") }
    return try confirm(archive: archive, id: id, startedAt: startedAt, now: now, uptime: uptime, epoch: epoch)
  }
}

struct WorkoutPhoneTerminalProjection {
  let seal: WorkoutSeal
  let startedAt: String
  let elapsedSeconds: Double
  let timerSeconds: Double?
  let preparationAnchor: WorkoutTimelineAnchor?

  static func acceptsStatus(_ snapshot: WorkoutOwnerSnapshot, after seal: WorkoutSeal?) -> Bool {
    guard let seal else { return true }
    return snapshot.ownerRevision >= seal.ownerRevision && snapshot.stopCutoff == seal.stopCutoff &&
      ["finishing", "completed", "failed"].contains(snapshot.phase)
  }

  /// A terminal archive may be the first owner message. Confirm its start before
  /// finishing the catalog, in the same transaction, including chunk-carried seals.
  static func accept(archive: WorkoutArchive, transfer: WorkoutTransferJournal, incoming: WorkoutSeal,
    startedAt: Date?, localPhase: String?, timerSeconds: Double?, now: Date, uptime: Double,
    epoch: String) throws -> Self? {
    guard timerSeconds.map({ $0.isFinite && $0 >= 0 && $0 <= 2_678_400 }) ?? true else {
      throw WorkoutDataError.invalid("Invalid owner active duration")
    }
    return try archive.store.transaction { _ in
      guard try archive.metadata(id: incoming.workoutID).watchEnabled else { throw WorkoutDataError.invalid("A Watch seal cannot replace a phone-owned workout") }
      guard let seal = try transfer.acceptCurrent(incoming) else { return nil }
      var metadata = try archive.metadata(id: seal.workoutID)
      if metadata.phase == "preparing" {
        guard let startedAt else { throw WorkoutDataError.invalid("The terminal owner did not provide its original start time") }
        metadata = try archive.confirmStart(id: seal.workoutID, startedAt: startedAt)
      } else if localPhase == "preparing", let startedAt,
        metadata.startedAt != WorkoutCoding.timestamp(startedAt) {
        throw WorkoutDataError.invalid("Terminal start conflicts with the confirmed collection")
      }
      let start = try WorkoutCoding.date(metadata.startedAt), cutoff = try WorkoutCoding.date(seal.stopCutoff)
      let elapsed = seal.stopElapsedSeconds ?? max(0, cutoff.timeIntervalSince(start))
      var anchor: WorkoutTimelineAnchor?
      if localPhase == "preparing" {
        var mapped = WorkoutTimelineAnchor(epoch: epoch, monotonicOrigin: uptime - max(0, now.timeIntervalSince(start)), startedAt: metadata.startedAt)
        mapped.stopUTC = seal.stopCutoff
        mapped.stopMonotonic = mapped.monotonicOrigin + elapsed
        anchor = mapped
      }
      if metadata.endedAt != seal.stopCutoff || metadata.phase != "completed" {
        _ = try archive.finish(id: seal.workoutID, endedAt: cutoff, finalPhase: "completed")
      }
      if metadata.stopElapsedSeconds != seal.stopElapsedSeconds || metadata.healthKitState != seal.healthOutcome {
        _ = try archive.update(id: seal.workoutID, healthKitState: seal.healthOutcome, stopElapsedSeconds: seal.stopElapsedSeconds)
      }
      return Self(seal: seal, startedAt: metadata.startedAt, elapsedSeconds: elapsed, timerSeconds: timerSeconds, preparationAnchor: anchor)
    }
  }
}

/// Phone recording without Health owns local effects directly; original event and command receipt commit together.
enum WorkoutLocalOwner {
  /// Resolve a stale phone checkpoint against the durable archive in one transaction.
  /// A committed stop outranks a pre-stop phone checkpoint; otherwise close interruption.
  static func restore(id: String, epoch: String?, checkpointElapsed: Double, needsInterruption: Bool,
                      archive: WorkoutArchive, pendingCommand: WorkoutCommand? = nil) throws -> (cutoff: Date?, elapsed: Double, timer: Double?) {
    try archive.store.transaction(priority: .capture) { _ in
      let metadata = try archive.metadata(id: id)
      guard !metadata.watchEnabled, !metadata.savesToHealth else { throw WorkoutDataError.invalid("Expected a local phone owner") }
      if let endedAt = metadata.endedAt {
        let cutoff = try WorkoutCoding.date(endedAt)
        let elapsed = try metadata.stopElapsedSeconds ?? max(0, cutoff.timeIntervalSince(WorkoutCoding.date(metadata.startedAt)))
        return (cutoff, elapsed, pendingCommand?.options["timerSeconds"]?.number)
      }
      if let command = pendingCommand, command.endsWorkout {
        let cutoff = try WorkoutCoding.date(command.requestedAt)
        let elapsed = try command.options["cutoffElapsedSeconds"]?.number ?? max(0, cutoff.timeIntervalSince(WorkoutCoding.date(metadata.startedAt)))
        return (cutoff, elapsed, command.options["timerSeconds"]?.number)
      }
      if needsInterruption, let epoch { try interrupt(id: id, epoch: epoch, checkpointElapsed: checkpointElapsed, archive: archive) }
      return (nil, checkpointElapsed, nil)
    }
  }
  /// Process interruption ends the known active interval at its durable checkpoint,
  /// even when the next user action is Finish rather than explicit recovery.
  static func interrupt(id: String, epoch: String, checkpointElapsed: Double, archive: WorkoutArchive) throws {
    try archive.store.transaction(priority: .capture) { _ in
      let metadata = try archive.metadata(id: id)
      guard !metadata.watchEnabled, !metadata.savesToHealth, metadata.endedAt == nil,
        checkpointElapsed.isFinite, checkpointElapsed >= 0, checkpointElapsed <= 2_678_400 else {
        throw WorkoutDataError.invalid("Expected an interrupted local phone owner")
      }
      let eventID = WorkoutStableIdentity.uuid("local-interruption:\(id):\(epoch):\(checkpointElapsed)")
      guard try !archive.hasEvent(id: id, eventID: eventID) else { return }
      let at = try WorkoutCoding.date(metadata.startedAt).addingTimeInterval(checkpointElapsed)
      let event = try WorkoutEvent(workoutId: id, kind: "lifecycle", source: "phone", timestamp: at,
        elapsedSeconds: checkpointElapsed, payload: ["action": .string("pause"), "interrupted": .bool(true)], eventId: eventID)
      try archive.append(event); try WorkoutTransferJournal(archive: archive).register(id: id, producer: "phone")
    }
  }
  static func observe(id: String, phase: String, at: Date, elapsed: Double, command: WorkoutCommand?,
                      cutoff: Date?, discarded: Bool, archive: WorkoutArchive, control: WorkoutControlJournal,
                      failure: String? = nil, checkpoint: (() throws -> Void)? = nil) throws -> WorkoutOwnerSnapshot {
    try archive.store.transaction(priority: .capture) { _ in
      let metadata = try archive.metadata(id: id)
      guard !metadata.watchEnabled, !metadata.savesToHealth else { throw WorkoutDataError.invalid("Expected a local phone owner") }
      if let command, failure == nil, ["start", "pause", "resume", "lap", "stop", "discard"].contains(command.action),
        try !archive.hasEvent(id: id, eventID: command.id) {
        let event = try WorkoutEvent(workoutId: id, kind: "lifecycle", source: "phone", timestamp: at,
          elapsedSeconds: command.action == "start" ? 0 : elapsed,
          payload: ["action": .string(command.endsWorkout ? "stop" : command.action), "operationId": .string(command.id)], eventId: command.id)
        try archive.append(event); try WorkoutTransferJournal(archive: archive).register(id: id, producer: "phone")
      }
      let snapshot = try control.observe(workoutID: id, owner: "phone", phase: phase, at: at,
        health: discarded ? "discarded" : "notRequested", cutoff: cutoff, command: command, failure: failure)
      try checkpoint?()
      return snapshot
    }
  }
}

/// Only a durable storage fault stops a ride; a rejected or transient operation is reported and the ride continues.
enum WorkoutStorageFaultPolicy {
  static func freezesRide(_ error: Error) -> Bool {
    switch error {
    case PowerLogStorageError.sqlite: return true
    case is PowerLogStorageError, is WorkoutDataError: return false
    default: return [NSCocoaErrorDomain, NSPOSIXErrorDomain].contains((error as NSError).domain)
    }
  }
}
