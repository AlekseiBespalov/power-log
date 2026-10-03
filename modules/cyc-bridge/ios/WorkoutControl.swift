import Foundation

struct WorkoutCommand: Codable, Equatable {
  let id: String
  let workoutID: String
  let origin: String
  let originSequence: Int64
  let action: String
  let requestedAt: String
  var options: [String: WorkoutJSON] = [:]

  init(
    id: String = UUID().uuidString.lowercased(), workoutID: String, origin: String,
    originSequence: Int64, action: String, requestedAt: Date, options: [String: WorkoutJSON] = [:]
  ) throws {
    self.id = try WorkoutCoding.id(id)
    self.workoutID = try WorkoutCoding.id(workoutID)
    self.origin = origin
    self.originSequence = originSequence
    self.action = action
    self.requestedAt = WorkoutCoding.timestamp(requestedAt)
    // Integral JSON numbers decode as integers. Canonicalize intent at creation
    // so a whole-second cutoff remains identical after its durable round trip.
    self.options = try JSONDecoder().decode([String: WorkoutJSON].self, from: WorkoutCoding.encoder().encode(options))
    try validate()
  }
  var endsWorkout: Bool { action == "stop" || action == "discard" }
  func validate() throws {
    _ = try WorkoutCoding.id(id)
    _ = try WorkoutCoding.id(workoutID)
    _ = try WorkoutCoding.date(requestedAt)
    for key in ["saveToHealth", "recordGPS"] {
      if let value = options[key] {
        guard case .bool = value else { throw WorkoutDataError.invalid("Invalid recording option") }
      }
    }
    guard ["phone", "watch"].contains(origin), originSequence > 0,
      ["start", "pause", "resume", "lap", "stop", "discard"].contains(action)
    else {
      throw WorkoutDataError.invalid("Invalid durable workout command")
    }
  }
  var packet: [String: Any] {
    var result: [String: Any] = [
      "schemaVersion": 1, "kind": "command", "messageId": id,
      "workoutId": workoutID, "origin": origin, "originSequence": String(originSequence),
      "action": action, "timestamp": requestedAt,
    ]
    result.merge(options.mapValues(\.any)) { current, _ in current }
    return result
  }
  static func decode(_ packet: [String: Any]) throws -> Self {
    guard let id = packet["messageId"] as? String, let workoutID = packet["workoutId"] as? String,
      let origin = packet["origin"] as? String, let sequence = packet["originSequence"] as? String,
      let number = Int64(sequence), let action = packet["action"] as? String,
      let timestamp = packet["timestamp"] as? String
    else { throw WorkoutDataError.invalid("Command ordering is missing") }
    var options: [String: WorkoutJSON] = [:]
    for key in ["indoor", "saveToHealth", "recordGPS"] {
      if let raw = packet[key] {
        guard let value = raw as? Bool else { throw WorkoutDataError.invalid("Invalid recording option") }
        options[key] = .bool(value)
      }
    }
    for key in ["cutoffElapsedSeconds", "timerSeconds"] {
      if let raw = packet[key] {
        let decoded = try JSONDecoder().decode(
          [String: WorkoutJSON].self,
          from: JSONSerialization.data(withJSONObject: [key: raw]))
        guard let value = decoded[key]?.number, value.isFinite, value >= 0, value <= 2_678_400 else {
          throw WorkoutDataError.invalid("Invalid command timing")
        }
        options[key] = .number(value)
      }
    }
    if let cutoff = packet["cutoffUTC"] as? String {
      _ = try WorkoutCoding.date(cutoff)
      options["cutoffUTC"] = .string(cutoff)
    }
    return try Self(
      id: id, workoutID: workoutID, origin: origin, originSequence: number,
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
  var timing: WorkoutOwnerTiming? = nil
  var healthReason: String? = nil
}

enum WorkoutOwnerPhase {
  static let values: Set<String> = [
    "ready", "preparing", "running", "paused", "recoverable", "finishing", "completed", "failed",
  ]
  static func canonical(_ value: String) -> String { value == "finished" ? "completed" : value }
  static func terminal(_ value: String) -> Bool { ["completed", "failed"].contains(value) }
}

enum WorkoutDeletionPolicy {
  static func phone(phase: String, selectedPhase: String?, healthBusy: Bool, pendingAction: Bool, backgroundBusy: Bool)
    -> Bool
  {
    ["completed", "failed"].contains(phase)
      && (selectedPhase == nil || ["completed", "failed"].contains(selectedPhase!)) && !healthBusy && !pendingAction
      && !backgroundBusy
  }
  static func watch(targetID: String, nativeID: String?, probeResolved: Bool, busy: Bool) -> Bool {
    probeResolved && !busy && nativeID != targetID
  }
}

struct WorkoutDeletionProbeGate {
  private(set) var inFlight = false
  mutating func begin() -> Bool {
    guard !inFlight else { return false }
    inFlight = true
    return true
  }
  mutating func finish() { inFlight = false }
}

struct WorkoutDeletionRequest {
  let workoutID: String
  let messageID: String
  init(_ packet: [String: Any]) throws {
    guard packet["schemaVersion"] as? Int == 1, packet["kind"] as? String == "deleteWorkout",
      let id = packet["workoutId"] as? String, let message = packet["messageId"] as? String,
      let timestamp = packet["requestedAt"] as? String
    else { throw WorkoutDataError.invalid("Invalid deletion request") }
    workoutID = try WorkoutCoding.id(id)
    messageID = try WorkoutCoding.id(message)
    _ = try WorkoutCoding.date(timestamp)
  }
  func acknowledgement(deleted: Bool, reason: String? = nil) -> [String: Any] {
    var packet: [String: Any] = [
      "schemaVersion": 1, "kind": "deleteWorkoutAck", "workoutId": workoutID,
      "messageId": UUID().uuidString.lowercased(), "acknowledgedMessageId": messageID,
      "outcome": deleted ? "deleted" : "deferred",
    ]
    if let reason { packet["reason"] = String(reason.prefix(500)) }
    return packet
  }
}

/// A delayed native callback can publish only to the session which admitted it.
struct WorkoutEffectIdentity: Equatable {
  let workoutID: String
  let generation: UUID
  func matches(workoutID: String?, generation: UUID) -> Bool {
    self.workoutID == workoutID && self.generation == generation
  }
  static func require(command: WorkoutCommand?, workoutID: String, nativeWorkoutID: String?) throws {
    guard command == nil || command?.workoutID == workoutID,
      nativeWorkoutID == nil || nativeWorkoutID == workoutID
    else { throw WorkoutDataError.invalid("Workout command/session identity mismatch") }
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
  var errorDescription: String? {
    "The original saved Health workout is not accessible; its historical outcome remains unknown."
  }
}

struct WorkoutCommandResult: Codable, Equatable {
  let commandID: String
  var outcome: String  // accepted, executing (uncertain after restart), applied, failed, rejected
  var ownerRevision: Int64?
  var reason: String?
  var missingOriginSequence: Int64?
  var isTerminal: Bool { ["applied", "failed", "rejected"].contains(outcome) }
}

/// Pure decisions used by both native owners. No delivery ACK can create an applied transition.
enum WorkoutControlReducer {
  static func accepts(_ incoming: WorkoutOwnerSnapshot, previous: WorkoutOwnerSnapshot?) throws -> Bool {
    _ = try WorkoutCoding.id(incoming.workoutID)
    _ = try WorkoutCoding.date(incoming.effectiveAt)
    if ["finishing", "completed"].contains(incoming.phase) {
      guard incoming.stopCutoff != nil, incoming.timing != nil else {
        throw WorkoutDataError.invalid("Terminal owner snapshot has no complete retained timing")
      }
    }
    if let cutoff = incoming.stopCutoff {
      _ = try WorkoutCoding.date(cutoff)
      guard incoming.timing != nil else { throw WorkoutDataError.invalid("Owner cutoff has no retained timing") }
    }
    if incoming.healthOutcome == "unavailable", incoming.healthReason?.isEmpty != false {
      throw WorkoutDataError.invalid("Terminal Health unavailability requires a reason")
    }
    if let timing = incoming.timing {
      try timing.validate()
      guard incoming.stopCutoff == nil || incoming.stopCutoff == timing.timestamp else {
        throw WorkoutDataError.invalid("Owner cutoff and retained timing disagree")
      }
    }
    try PowerLogRevision.validate(incoming.ownerRevision)
    guard incoming.ownerRevision > 0, ["phone", "watch"].contains(incoming.owner),
      WorkoutOwnerPhase.values.contains(incoming.phase),
      ["pending", "saved", "failed", "notSaved", "unavailable", "unknown", "discarded", "notRequested"].contains(
        incoming.healthOutcome)
    else {
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
    if previous.healthOutcome == "unavailable",
      incoming.healthOutcome != "unavailable" || incoming.healthReason != previous.healthReason
    {
      throw WorkoutDataError.invalid("Terminal Health unavailability cannot resume")
    }
    if previous.healthOutcome == "notRequested", !["notRequested", "discarded"].contains(incoming.healthOutcome) {
      throw WorkoutDataError.invalid("Skipped Health saving cannot be resumed")
    }
    if previous.healthOutcome == "discarded", incoming.healthOutcome != "discarded" {
      throw WorkoutDataError.invalid("A discarded workout cannot be saved or resumed")
    }
    if previous.stopCutoff != nil && (incoming.stopCutoff != previous.stopCutoff || incoming.timing != previous.timing)
    {
      throw WorkoutDataError.invalid("The original stop cutoff is immutable")
    }
    if (previous.stopCutoff != nil || WorkoutOwnerPhase.terminal(previous.phase))
      && ["running", "paused", "preparing"].contains(incoming.phase)
    {
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
  func create(
    workoutID: String, origin: String, action: String, at: Date,
    options: [String: WorkoutJSON] = [:]
  ) throws -> WorkoutCommand {
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: workoutID)
      let sequence = try db.nextSequence(namespace: "command-origins", key: workoutID + ":" + origin)
      let command = try WorkoutCommand(
        workoutID: workoutID, origin: origin, originSequence: sequence,
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
      try db.put(
        namespace: "command-sequences",
        key: command.workoutID + ":" + command.origin + ":" + String(command.originSequence), value: encode(command.id),
        immutable: true)
      if let previous = try read(WorkoutCommandResult.self, db: db, namespace: "command-results", key: command.id) {
        return previous
      }
      let previous =
        try read(Int64.self, db: db, namespace: "applied-origins", key: command.workoutID + ":" + command.origin) ?? 0
      let result = WorkoutCommandResult(
        commandID: command.id, outcome: "accepted",
        missingOriginSequence: command.originSequence > previous + 1 ? previous + 1 : nil)
      try db.put(namespace: "command-results", key: command.id, value: encode(result))
      return result
    }
  }
  func begin(_ command: WorkoutCommand) throws -> WorkoutCommandResult {
    try store.transaction(priority: .capture) { db in
      try store.requireWorkoutAvailable(id: command.workoutID)
      guard try self.command(id: command.id) == command else {
        throw WorkoutDataError.invalid("Command identity changed before execution")
      }
      try repairTerminalSlot(workoutID: command.workoutID)
      guard var result = try read(WorkoutCommandResult.self, db: db, namespace: "command-results", key: command.id)
      else {
        throw WorkoutDataError.invalid("Command intent has not been committed")
      }
      if result.isTerminal || result.outcome == "executing" { return result }
      if command.action == "start", let reason = try cancelledStart(workoutID: command.workoutID) {
        return try settleWithoutEffect(command, reason: reason)
      }
      let applied =
        try read(Int64.self, db: db, namespace: "applied-origins", key: command.workoutID + ":" + command.origin) ?? 0
      guard command.originSequence == applied + 1 else {
        result.missingOriginSequence = command.originSequence > applied ? applied + 1 : nil
        result.reason = command.originSequence <= applied ? "Owner sequence requires reconciliation" : nil
        try db.put(namespace: "command-results", key: command.id, value: encode(result))
        return result
      }
      if let active = try read(String.self, db: db, namespace: "active-owner-command", key: command.workoutID),
        active != command.id
      {
        return result
      }
      let snapshot = try read(WorkoutOwnerSnapshot.self, db: db, namespace: "owner-snapshots", key: command.workoutID)
      if let reason = WorkoutControlReducer.rejection(command, snapshot: snapshot) {
        result.outcome = "rejected"
        result.reason = reason
        try db.put(
          namespace: "applied-origins", key: command.workoutID + ":" + command.origin,
          value: encode(command.originSequence))
      } else {
        result.outcome = "executing"
        result.missingOriginSequence = nil
        try db.put(namespace: "active-owner-command", key: command.workoutID, value: encode(command.id))
      }
      try db.put(namespace: "command-results", key: command.id, value: encode(result))
      return result
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
      guard let id = try read(String.self, db: db, namespace: "active-owner-command", key: workoutID) else {
        return nil
      }
      guard let command = try read(WorkoutCommand.self, db: db, namespace: "commands", key: id),
        command.workoutID == workoutID
      else {
        throw WorkoutDataError.invalid(
          "Active owner slot has a different workout identity; recovery evidence is required")
      }
      return command
    }
  }
  func snapshot(workoutID: String) throws -> WorkoutOwnerSnapshot? {
    try store.read { db in try read(WorkoutOwnerSnapshot.self, db: db, namespace: "owner-snapshots", key: workoutID) }
  }
  func accept(snapshot: WorkoutOwnerSnapshot) throws -> Bool {
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
  func observe(
    workoutID: String, owner: String, phase: String, at: Date, health: String,
    healthID: String? = nil, cutoff: Date? = nil, command: WorkoutCommand? = nil,
    failure: String? = nil, timing: WorkoutOwnerTiming? = nil, healthReason: String? = nil
  ) throws -> WorkoutOwnerSnapshot {
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
          guard let previous, previous.owner == owner else {
            throw WorkoutDataError.invalid("Terminal command has no matching owner evidence")
          }
          try repairTerminalSlot(workoutID: workoutID)
          return previous
        }
        guard result.outcome == "executing", try active(workoutID: workoutID)?.id == command.id else {
          throw WorkoutDataError.invalid("Only the matching active command can complete a native effect")
        }
        if failure == nil, command.endsWorkout, !["finishing", "completed"].contains(phase) {
          throw WorkoutDataError.invalid("A stop receipt requires an observed stopped owner")
        }
        if failure == nil, command.action == "discard", (health != "discarded" || phase != "completed") {
          throw WorkoutDataError.invalid("Discard requires the native builder's discarded outcome")
        }
      }
      let snapshot = WorkoutOwnerSnapshot(
        workoutID: workoutID, owner: owner,
        ownerRevision: try PowerLogRevision.next(previous?.ownerRevision ?? 0),
        effectiveAt: WorkoutCoding.timestamp(at),
        phase: phase, healthOutcome: health, healthWorkoutID: healthID ?? previous?.healthWorkoutID,
        stopCutoff: previous?.stopCutoff ?? cutoff.map(WorkoutCoding.timestamp),
        timing: previous?.stopCutoff == nil ? timing : previous?.timing,
        healthReason: healthReason ?? previous?.healthReason)
      guard try WorkoutControlReducer.accepts(snapshot, previous: previous) else { return previous! }
      try db.put(namespace: "owner-snapshots", key: workoutID, value: encode(snapshot))
      if let command {
        let result = WorkoutCommandResult(
          commandID: command.id, outcome: failure == nil ? "applied" : "failed",
          ownerRevision: snapshot.ownerRevision, reason: failure)
        try db.put(namespace: "command-results", key: command.id, value: encode(result))
        let applied =
          try read(Int64.self, db: db, namespace: "applied-origins", key: workoutID + ":" + command.origin) ?? 0
        try db.put(
          namespace: "applied-origins", key: workoutID + ":" + command.origin,
          value: encode(max(applied, command.originSequence)))
        try db.remove(namespace: "active-owner-command", key: workoutID)
        try advanceTerminalOrigin(workoutID: workoutID, origin: command.origin, db: db)
      }
      return snapshot
    }
  }
  private func requireHealthIntent(id: String, health: String, db: PowerLogDatabase) throws {
    if let data = try db.rows("SELECT metadata FROM collections WHERE id=? AND kind='workout'", [.text(id)], limit: 1)
      .first?.data("metadata")
    {
      let metadata = try JSONDecoder().decode(WorkoutMetadata.self, from: data)
      guard metadata.saveToHealth || ["notRequested", "discarded"].contains(health) else {
        throw WorkoutDataError.invalid("Owner outcome conflicts with frozen Health intent")
      }
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
          if command.workoutID == workoutID && command.origin == origin && command.originSequence == sequence {
            return command
          }
        }
        guard page.count == 128 else { return nil }
        after = page.last?.key
      }
    }
  }
}

extension WorkoutControlJournal {
  /// A terminal receipt consumes a protocol slot, but is never proof that Health ran.

  func repairTerminalSlot(workoutID: String) throws {
    try store.transaction(priority: .capture) { db in
      guard let active = try active(workoutID: workoutID), let result = try result(id: active.id), result.isTerminal
      else { return }
      try db.remove(namespace: "active-owner-command", key: workoutID)
      try advanceTerminalOrigin(workoutID: workoutID, origin: active.origin, db: db)
    }
  }
  private func advanceTerminalOrigin(workoutID: String, origin: String, db: PowerLogDatabase) throws {
    let key = workoutID + ":" + origin
    let old = try read(Int64.self, db: db, namespace: "applied-origins", key: key) ?? 0
    var applied = old
    while let id = try read(String.self, db: db, namespace: "command-sequences", key: key + ":" + String(applied + 1)),
      let result = try result(id: id), result.isTerminal
    { applied += 1 }
    if old != applied { try db.put(namespace: "applied-origins", key: key, value: encode(applied)) }
  }
  @discardableResult
  func settleWithoutEffect(_ command: WorkoutCommand, outcome: String = "rejected", reason: String) throws
    -> WorkoutCommandResult
  {
    guard ["applied", "failed", "rejected"].contains(outcome) else {
      throw WorkoutDataError.invalid("Invalid command-only outcome")
    }
    return try store.transaction(priority: .capture) { db in
      _ = try accept(command)
      if let old = try result(id: command.id), old.isTerminal {
        try repairTerminalSlot(workoutID: command.workoutID)
        return old
      }
      let result = WorkoutCommandResult(
        commandID: command.id, outcome: outcome,
        ownerRevision: try snapshot(workoutID: command.workoutID)?.ownerRevision, reason: reason)
      try db.put(namespace: "command-results", key: command.id, value: encode(result))
      if try active(workoutID: command.workoutID)?.id == command.id {
        try db.remove(namespace: "active-owner-command", key: command.workoutID)
      }
      try advanceTerminalOrigin(workoutID: command.workoutID, origin: command.origin, db: db)
      return result
    }
  }
  /// Terminal owner evidence is required to settle a stop skipped by inconsistent counters.
  func settleTerminal(_ command: WorkoutCommand) throws -> WorkoutCommandResult? {
    guard let snapshot = try snapshot(workoutID: command.workoutID), WorkoutOwnerPhase.terminal(snapshot.phase) else {
      return nil
    }
    let stopped =
      command.endsWorkout && snapshot.phase == "completed" && snapshot.stopCutoff != nil
      && (command.action != "discard" || snapshot.healthOutcome == "discarded")
    return try settleWithoutEffect(
      command, outcome: stopped ? "applied" : "rejected",
      reason: stopped
        ? "Original owner already ended; its observed cutoff is retained" : "The original owner is terminal")
  }
  func rejectedStart(workoutID: String) throws -> Bool {
    try store.read { db in
      for origin in ["phone", "watch"] {
        if let id = try read(String.self, db: db, namespace: "command-sequences", key: workoutID + ":" + origin + ":1"),
          let command = try command(id: id), command.action == "start", try result(id: id)?.outcome == "rejected"
        {
          return true
        }
      }
      return false
    }
  }
  func initialStart(workoutID: String) throws -> WorkoutCommand? {
    try store.read { db in
      for origin in ["phone", "watch"] {
        if let id = try read(String.self, db: db, namespace: "command-sequences", key: workoutID + ":" + origin + ":1"),
          let command = try command(id: id), command.action == "start"
        {
          return command
        }
      }
      return nil
    }
  }
  func cancelledStart(workoutID: String) throws -> String? {
    try store.read { db in
      try db.get(namespace: "cancelled-owner-start", key: workoutID).flatMap { String(data: $0, encoding: .utf8) }
    }
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
      var after = prefix
      var first: WorkoutCommand?
      while true {
        let page = try db.page(namespace: "command-sequences", after: after, limit: 128)
        for row in page {
          guard row.key.hasPrefix(prefix) else { return first }
          let id = try JSONDecoder().decode(String.self, from: row.value)
          if let command = try command(id: id), command.workoutID == workoutID, command.origin == origin,
            command.endsWorkout, try result(id: id)?.isTerminal == false,
            first == nil || command.originSequence < first!.originSequence
          {
            first = command
          }
        }
        guard page.count == 128 else { return first }
        after = page.last!.key
      }
    }
  }
  func remoteCommand(id: String, workoutID: String) throws -> WorkoutCommand? {
    try store.read { db in
      guard let command = try read(WorkoutCommand.self, db: db, namespace: "outgoing-commands", key: id),
        command.workoutID == workoutID,
        try self.command(id: id) == nil
      else { return nil }  // Locally admitted effects are never transport commands.
      return command
    }
  }
  func retryForAcknowledgement(_ result: WorkoutCommandResult, acknowledgedID: String, workoutID: String) throws
    -> WorkoutCommand?
  {
    guard result.commandID == acknowledgedID,
      let acknowledged = try remoteCommand(id: acknowledgedID, workoutID: workoutID),
      let missing = result.missingOriginSequence, missing > 0,
      let candidate = try outgoing(workoutID: workoutID, origin: acknowledged.origin, sequence: missing),
      try remoteCommand(id: candidate.id, workoutID: workoutID) != nil
    else { return nil }
    return candidate
  }
}

/// Queries deliberately have no origin sequence or active-command slot.
struct WorkoutOwnerQuery: Equatable {
  let workoutID: String
  let id: String
  let pendingCommandID: String?
  init(workoutID: String, id: String = UUID().uuidString.lowercased(), pendingCommandID: String? = nil) throws {
    self.workoutID = try WorkoutCoding.id(workoutID)
    self.id = try WorkoutCoding.id(id)
    self.pendingCommandID = try pendingCommandID.map(WorkoutCoding.id)
  }
  var packet: [String: Any] {
    var value: [String: Any] = [
      "schemaVersion": 1, "kind": "ownerQuery", "workoutId": workoutID, "messageId": id, "queryId": id,
    ]
    if let pendingCommandID { value["pendingCommandId"] = pendingCommandID }
    return value
  }
  static func decode(_ value: [String: Any]) throws -> Self {
    guard value["kind"] as? String == "ownerQuery", let id = value["queryId"] as? String,
      id == value["messageId"] as? String,
      let workoutID = value["workoutId"] as? String
    else { throw WorkoutDataError.invalid("Invalid owner query identity") }
    return try Self(workoutID: workoutID, id: id, pendingCommandID: value["pendingCommandId"] as? String)
  }
  func matches(_ packet: [String: Any]) -> Bool {
    packet["kind"] as? String == "ownerReply" && packet["queryId"] as? String == id
      && packet["workoutId"] as? String == workoutID
  }
}

enum WorkoutOwnerAdmission {
  static let cancelledStopReason =
    "Unconfirmed start cancelled. Earlier data is retained; no workout end time was inferred"
  static func cancelPhoneAfterLookup(
    _ error: Error, workoutID: String, control: WorkoutControlJournal,
    nativeAbsenceConfirmed: Bool, effectInFlight: Bool
  ) throws -> WorkoutCommandResult? {
    guard error is WorkoutSavedOwnerLookupError,
      let stop = try control.pendingStop(workoutID: workoutID, origin: "phone")
    else { return nil }
    return try cancelUnconfirmed(
      stop, control: control, nativeAbsenceConfirmed: nativeAbsenceConfirmed, effectInFlight: effectInFlight)
  }
  /// Explicit cancellation removes the intent to start again, not historical workout evidence.
  /// A successful, fresh no-active-session probe is required; empty Health history alone is insufficient.
  static func cancelUnconfirmed(
    _ stop: WorkoutCommand, control: WorkoutControlJournal,
    nativeAbsenceConfirmed: Bool, effectInFlight: Bool
  ) throws -> WorkoutCommandResult? {
    guard stop.action == "stop", nativeAbsenceConfirmed, !effectInFlight else { return nil }
    return try control.store.transaction(priority: .capture) { _ in
      guard try control.snapshot(workoutID: stop.workoutID) == nil else { return nil }
      let reason =
        "Unconfirmed start cancelled by explicit stop after the owner verified no active native session; historical outcome remains unknown"
      try control.cancelStartIntent(workoutID: stop.workoutID, reason: reason)
      if let start = try control.initialStart(workoutID: stop.workoutID), let result = try control.result(id: start.id),
        !result.isTerminal
      {
        _ = try control.settleWithoutEffect(start, reason: reason)
      }
      return try control.settleWithoutEffect(stop, reason: cancelledStopReason)
    }
  }
  /// A query may settle derivable receipts, but never admits a fresh native effect.
  static func query(_ command: WorkoutCommand, control: WorkoutControlJournal, nativeWorkoutID: String?) throws
    -> WorkoutCommandResult
  {
    _ = try control.accept(command)
    try control.repairTerminalSlot(workoutID: command.workoutID)
    if let result = try control.result(id: command.id), result.isTerminal { return result }
    if command.action == "start", let reason = try control.cancelledStart(workoutID: command.workoutID) {
      return try control.settleWithoutEffect(command, reason: reason)
    }
    if let result = try control.settleTerminal(command) { return result }
    if command.action == "start", let nativeWorkoutID, nativeWorkoutID != command.workoutID {
      return try control.settleWithoutEffect(command, reason: "Another workout is active")
    }
    if command.action != "start", nativeWorkoutID != command.workoutID,
      try control.snapshot(workoutID: command.workoutID) == nil, try control.rejectedStart(workoutID: command.workoutID)
    {
      return try control.settleWithoutEffect(
        command, reason: "The original start was rejected; no matching owner has confirmed this action")
    }
    var result = try control.result(id: command.id)!
    result.reason = "The original owner has not confirmed this action; recovery remains unresolved"
    return result
  }
  /// Runs before prepare() can mark an effect executing. Production Watch routing uses this boundary.
  static func prepare(
    _ command: WorkoutCommand, control: WorkoutControlJournal, nativeWorkoutID: String?,
    readyToStart: Bool, recovering: Bool
  ) throws -> WorkoutCommandPreparation {
    _ = try control.accept(command)
    try control.repairTerminalSlot(workoutID: command.workoutID)
    func settled(_ result: WorkoutCommandResult) -> WorkoutCommandPreparation {
      WorkoutCommandPreparation(result: result, execute: false, reconcile: false)
    }
    if let result = try control.result(id: command.id), result.isTerminal { return settled(result) }
    if let result = try control.settleTerminal(command) { return settled(result) }
    if command.action == "start", let nativeWorkoutID, nativeWorkoutID != command.workoutID {
      return settled(try control.settleWithoutEffect(command, reason: "Another workout is active"))
    }
    if command.action == "start", nativeWorkoutID == nil, (!readyToStart || recovering) {
      return settled(
        WorkoutCommandResult(
          commandID: command.id, outcome: "accepted", reason: "Owner recovery is still in progress; retry this request")
      )
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
      try db.rows(
        "SELECT id FROM collections WHERE kind='workout' AND ended_at IS NOT NULL AND id>? AND json_extract(CAST(metadata AS TEXT),'$.watchEnabled')=0 AND json_extract(CAST(metadata AS TEXT),'$.saveToHealth')=0 AND json_extract(CAST(metadata AS TEXT),'$.healthKitState')='notRequested' AND coalesce(json_extract(CAST(metadata AS TEXT),'$.finalizationState'),'pending') NOT IN ('complete','partial') AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=collections.id) ORDER BY id LIMIT ?",
        [.text(afterID), .integer(Int64(max(1, min(8, limit))))], limit: max(1, min(8, limit))
      ).compactMap { $0.string("id") }
    }
  }
  @discardableResult
  static func seal(
    id: String, archive: WorkoutArchive, transfer: WorkoutTransferJournal,
    control: WorkoutControlJournal
  ) throws -> WorkoutSeal {
    var metadata = try archive.metadata(id: id)
    var owner = try control.snapshot(workoutID: id)
    if !metadata.watchEnabled, !metadata.saveToHealth, let endedAt = metadata.endedAt {
      // Local capture already froze its cutoff. Repair a lost local stop commit by its exact ID;
      // other unresolved actions (especially Discard) can never become a Save through sealing.
      try archive.store.transaction(priority: .capture) { _ in
        let retained = try control.active(workoutID: id) ?? control.nextReady(workoutID: id, origin: "phone")
        if let retained, retained.action == "stop" {
          let prepared = try control.prepare(retained)
          if prepared.execute || prepared.reconcile {
            let timing = try WorkoutOwnerTiming.terminal(metadata)
            let cutoff = try WorkoutCoding.date(timing.timestamp)
            _ = try WorkoutLocalOwner.observe(
              id: id, phase: "completed", at: cutoff,
              elapsed: timing.elapsedSeconds,
              command: retained, cutoff: cutoff, discarded: false, archive: archive, control: control, timing: timing)
            try archive.finish(id: id, endedAt: cutoff, finalPhase: "completed")
          }
        }
        owner = try control.snapshot(workoutID: id)
        guard owner?.owner == "phone", owner?.phase == "completed", owner?.stopCutoff == endedAt,
          try control.active(workoutID: id) == nil
        else { throw WorkoutDataError.invalid("The original local stop has not committed") }
      }
      metadata = try archive.metadata(id: id)
    }
    guard !metadata.watchEnabled, owner == nil || owner?.owner == "phone",
      let cutoff = owner?.stopCutoff ?? metadata.endedAt
    else { throw WorkoutDataError.invalid("The original phone owner has not confirmed an end time") }
    let health = owner?.healthOutcome ?? metadata.healthKitState
    let sources = try transfer.roster(id: id).map { try transfer.source(id: id, producer: $0) }
    let requirements = [
      "healthSave": metadata.saveToHealth
        ? (health == "unavailable" ? "unavailable" : health == "saved" ? "sealed" : "pending") : "notRequested",
      "cycInsertion": try WorkoutHealthInsertionJournal(archive: archive).outcome(id: id),
    ]
    let previous = try transfer.currentSeal(id: id)
    if let previous, previous.sources == sources, previous.requirements == requirements,
      previous.ownerRevision == (owner?.ownerRevision ?? 0), previous.healthOutcome == health,
      previous.stopCutoff == cutoff, previous.stopElapsedSeconds == metadata.stopElapsedSeconds
    {
      _ = try transfer.verify(id: id)
      return previous
    }
    let seal = WorkoutSeal(
      workoutID: id, sealRevision: try PowerLogRevision.next(previous?.sealRevision ?? 0),
      collectionRevision: metadata.collectionRevision ?? 0, ownerRevision: owner?.ownerRevision ?? 0,
      stopCutoff: cutoff, healthOutcome: health, requirements: requirements, sources: sources,
      stopElapsedSeconds: try WorkoutOwnerTiming.terminal(metadata).elapsedSeconds,
      timerSeconds: try WorkoutOwnerTiming.terminal(metadata).timerSeconds, saveToHealth: metadata.saveToHealth,
      recordGPS: metadata.recordGPS, healthReason: owner?.healthReason)
    _ = try transfer.accept(seal: seal)
    _ = try transfer.verify(id: id)
    return seal
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
      return WorkoutCommandPreparation(
        result: decision,
        execute: decision.outcome == "executing" && previous?.outcome != "executing",
        reconcile: decision.outcome == "executing" && previous?.outcome == "executing")
    }
  }
}

enum WorkoutRecoveryAction: Equatable {
  case settled, findSavedWorkout, finishSameSession, reconcileLap, applyPause, applyResume, observeSession
}
enum WorkoutUnconfirmedStopPolicy {
  enum Action { case cancelPreparation, retainOwnerIntent, finishConfirmed }
  static func action(hasConfirmedOwner: Bool, preparing: Bool, hasNativeIntent: Bool) -> Action {
    if hasConfirmedOwner { return .finishConfirmed }
    return preparing && !hasNativeIntent ? .cancelPreparation : .retainOwnerIntent
  }
}
struct WorkoutOwnerTiming: Codable, Equatable {
  static var recoveryPageObserverForTesting: ((Int) -> Void)?
  static var recoveryWorkObserverForTesting: ((Int) -> Void)?
  let timestamp: String
  let elapsedSeconds: Double
  let timerSeconds: Double

  init(timestamp: String, elapsedSeconds: Double, timerSeconds: Double) throws {
    self.timestamp = timestamp
    self.elapsedSeconds = elapsedSeconds
    self.timerSeconds = timerSeconds
    try validate()
  }
  func validate() throws {
    _ = try WorkoutCoding.date(timestamp)
    guard elapsedSeconds.isFinite, elapsedSeconds >= 0, elapsedSeconds <= 2_678_400,
      timerSeconds.isFinite, timerSeconds >= 0, timerSeconds <= elapsedSeconds
    else { throw WorkoutDataError.invalid("Invalid retained owner timing") }
  }
  static func remote(id: String, timestamp: String, elapsed: Double?, timer: Double?, archive: WorkoutArchive) throws
    -> Self
  {
    guard let elapsed, let timer else { throw WorkoutDataError.invalid("Remote owner has no measured timing") }
    let reported = try Self(timestamp: timestamp, elapsedSeconds: elapsed, timerSeconds: timer)
    return try retained(id: id, checkpoint: reported, archive: archive, preserveActive: true)
  }
  static func command(_ command: WorkoutCommand) throws -> Self {
    guard let utc = command.options["cutoffUTC"]?.string,
      let elapsed = command.options["cutoffElapsedSeconds"]?.number,
      let timer = command.options["timerSeconds"]?.number
    else { throw WorkoutDataError.invalid("The owner command has no retained timing") }
    return try Self(timestamp: utc, elapsedSeconds: elapsed, timerSeconds: timer)
  }
  static func terminal(_ metadata: WorkoutMetadata) throws -> Self {
    guard let timing = metadata.ownerTiming, timing.timestamp == metadata.endedAt,
      timing.elapsedSeconds == metadata.stopElapsedSeconds
    else { throw WorkoutDataError.invalid("The stopped owner has no complete retained timing") }
    try timing.validate()
    return timing
  }
  static func stopping(
    command: WorkoutCommand?, frozen: Self?, at: Date, elapsed: Double, timer: Double
  ) throws -> Self {
    if let command { return try Self.command(command) }
    if let frozen {
      try frozen.validate()
      return frozen
    }
    return try Self(timestamp: WorkoutCoding.timestamp(at), elapsedSeconds: elapsed, timerSeconds: timer)
  }
  static func checkpoint(_ value: [String: Any]) throws -> Self {
    guard let utc = value["checkpointUTC"] as? String,
      let elapsed = value["elapsedSeconds"] as? Double, let timer = value["timerSeconds"] as? Double
    else { throw WorkoutDataError.invalid("The recording checkpoint has no complete retained timing") }
    return try Self(timestamp: utc, elapsedSeconds: elapsed, timerSeconds: timer)
  }
  static func retained(id: String, checkpoint: Self, archive: WorkoutArchive, preserveActive: Bool = false) throws
    -> Self
  {
    try checkpoint.validate()
    return try archive.store.read { db in
      guard
        let latest = try db.rows(
          "SELECT m.elapsed_seconds,o.original_timestamp FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.deleted=0 AND m.original_elapsed_seconds IS NOT NULL AND m.kind IN ('telemetry','location','lifecycle') AND m.elapsed_seconds>? ORDER BY m.elapsed_seconds DESC,m.ordinal DESC LIMIT 1",
          [.text(id), .real(checkpoint.elapsedSeconds)], limit: 1
        ).first,
        let elapsed = latest.double("elapsed_seconds"), let utc = latest.string("original_timestamp")
      else { return checkpoint }
      if preserveActive {
        return try Self(timestamp: utc, elapsedSeconds: elapsed, timerSeconds: checkpoint.timerSeconds)
      }
      let prior = try db.rows(
        "SELECT l.action FROM collection_memberships m INDEXED BY membership_lifecycle_time JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.deleted=0 AND m.original_elapsed_seconds IS NOT NULL AND m.elapsed_seconds<=? AND l.action IN ('start','resume','pause','stop','discard','interruption') ORDER BY m.elapsed_seconds DESC,m.producer DESC,m.sequence DESC LIMIT 1",
        [.text(id), .real(checkpoint.elapsedSeconds)], limit: 1
      ).first
      var active = prior.map { ["start", "resume"].contains($0.string("action")!) } ?? true
      var position = checkpoint.elapsedSeconds
      var timer = checkpoint.timerSeconds
      var cursor = checkpoint.elapsedSeconds
      var producer = ""
      var sequence: Int64 = 0
      while true {
        let lowerBound = producer.isEmpty ? "m.elapsed_seconds>?" : "(m.elapsed_seconds,m.producer,m.sequence)>(?,?,?)"
        let lowerValues: [PowerLogSQLValue] =
          producer.isEmpty
          ? [.real(cursor)] : [.real(cursor), .text(producer), .integer(sequence)]
        let rows = try db.rows(
          "SELECT m.elapsed_seconds,m.producer,m.sequence,l.action FROM collection_memberships m INDEXED BY membership_lifecycle_time JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.deleted=0 AND m.original_elapsed_seconds IS NOT NULL AND \(lowerBound) AND m.elapsed_seconds<=? ORDER BY m.elapsed_seconds,m.producer,m.sequence LIMIT 512",
          [.text(id)] + lowerValues + [.real(elapsed)], limit: 512, observeWork: Self.recoveryWorkObserverForTesting)
        Self.recoveryPageObserverForTesting?(rows.count)
        for row in rows {
          let at = row.double("elapsed_seconds") ?? 0
          if at > position {
            if active { timer += at - position }
            position = at
          }
          if ["start", "resume"].contains(row.string("action") ?? "") { active = true }
          if ["pause", "stop", "discard", "interruption"].contains(row.string("action") ?? "") { active = false }
        }
        if rows.count < 512 { break }
        cursor = rows.last!.double("elapsed_seconds")!
        producer = rows.last!.string("producer")!
        sequence = rows.last!.int("sequence")!
      }
      if active { timer += elapsed - position }
      return try Self(timestamp: utc, elapsedSeconds: elapsed, timerSeconds: min(elapsed, timer))
    }
  }

}
struct WorkoutInterruptionBoundary {
  let time: Double
  let producer: String
  let sequence: Int64
  let clockEpoch: String?
  let cycSequence: Int64

  func precedes(time: Double, producer: String?, sequence: Int64?, clockEpoch: String?) -> Bool {
    if self.time != time { return self.time < time }
    if self.producer == producer, let sequence { return self.sequence < sequence }
    if producer == "cyc", let sequence { return cycSequence < sequence }
    return self.clockEpoch != nil && clockEpoch != nil && self.clockEpoch != clockEpoch
  }

  static func index(
    _ boundaries: [Self], time: Double, producer: String? = nil, sequence: Int64? = nil, clockEpoch: String? = nil
  ) -> Int {
    var low = 0
    var high = boundaries.count
    while low < high {
      let mid = (low + high) / 2
      if boundaries[mid].time < time { low = mid + 1 } else { high = mid }
    }
    while low < boundaries.count,
      boundaries[low].precedes(time: time, producer: producer, sequence: sequence, clockEpoch: clockEpoch)
    {
      low += 1
    }
    return low
  }

  static func resumedAtBoundary(
    _ boundaries: [Self], time: Double, producer: String?, sequence: Int64?, clockEpoch: String?
  ) -> Bool? {
    let first = index(boundaries, time: time)
    guard first < boundaries.count, boundaries[first].time == time else { return nil }
    return index(boundaries, time: time, producer: producer, sequence: sequence, clockEpoch: clockEpoch) > first
  }

  static func load(store: PowerLogStore, id: String, revision: Int64) throws -> [Self] {
    var result: [Self] = []
    var cursor = -1.0
    var producer = ""
    var sequence: Int64 = 0
    while true {
      let rows = try store.read(priority: .background) { db in
        try db.rows(
          "SELECT m.elapsed_seconds,m.producer,m.sequence,m.source,o.clock_epoch,json_extract(o.extra,'$.cycSequence') AS cyc_sequence FROM collection_memberships m INDEXED BY membership_lifecycle_time JOIN observations o ON o.id=m.observation_id JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND (json_extract(o.extra,'$.interrupted')=1 OR l.action='interruption') AND (m.elapsed_seconds,m.producer,m.sequence)>(?,?,?) ORDER BY m.elapsed_seconds,m.producer,m.sequence LIMIT 256",
          [
            .text(id), .integer(revision), .integer(revision), .integer(revision), .real(cursor), .text(producer),
            .integer(sequence),
          ], limit: 256)
      }
      guard result.count + rows.count <= 10_000 else {
        throw WorkoutDataError.invalid("Too many workout interruptions")
      }
      for row in rows {
        let time = row.double("elapsed_seconds")!
        let epoch = row.string("clock_epoch")
        guard let retainedSequence = row.string("cyc_sequence").flatMap(Int64.init), retainedSequence >= 0 else {
          throw WorkoutDataError.invalid("Interruption has no retained telemetry sequence")
        }
        result.append(
          Self(
            time: time, producer: row.string("producer")!, sequence: row.int("sequence")!, clockEpoch: epoch,
            cycSequence: retainedSequence))
      }
      guard rows.count == 256, let last = rows.last else { return result }
      cursor = last.double("elapsed_seconds")!
      producer = last.string("producer")!
      sequence = last.int("sequence")!
    }
  }
}

enum WorkoutRecoveryPlanner {
  static func inspectsWatchCandidate(
    phase: String, health: String, finalHealthExtracted: Bool, nativeOwnerEnded: Bool = true
  ) -> Bool {
    if health == "unavailable" { return !nativeOwnerEnded }
    return ["running", "paused", "finishing", "recoverable"].contains(phase)
      || (phase == "completed"
        && (!["saved", "notRequested"].contains(health) || !finalHealthExtracted
          || !nativeOwnerEnded))
  }
  static func needsSavedIdentityLookup(requestedID: String?, candidateID: String?, hasNativeSession: Bool) -> Bool {
    requestedID != nil && candidateID == nil && !hasNativeSession
  }
  static func waitsForStart(phase: String, startCompletionPending: Bool) -> Bool {
    startCompletionPending && ["preparing", "recoverable"].contains(phase)
  }
  /// Ended presentation is independent of the unresolved native operation and final seal.
  static func action(hasCutoff: Bool, verifiedFinality: Bool, nativePhase: String?, pendingAction: String?)
    -> WorkoutRecoveryAction
  {
    if verifiedFinality && pendingAction == nil { return .settled }
    if nativePhase == "stopped" || nativePhase == "ended" { return .finishSameSession }
    if ["stop", "discard"].contains(pendingAction ?? ""), nativePhase != nil { return .finishSameSession }
    if pendingAction == "lap", nativePhase != nil { return .reconcileLap }
    if hasCutoff && pendingAction != "pause" && pendingAction != "resume" {
      return nativePhase == nil ? .findSavedWorkout : .finishSameSession
    }
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
  var uncertain: Bool? = nil
  var epochStart: Double? = nil
  func resuming(timing: WorkoutOwnerTiming, epoch: String, uptime: Double) -> Self {
    Self(
      epoch: epoch, monotonicOrigin: uptime - timing.elapsedSeconds, startedAt: startedAt, uncertain: true,
      epochStart: uptime)
  }
  struct Mapping {
    let elapsed: Double
    let eligible: Bool
    let uncertain: Bool
  }
  func map(epoch sampleEpoch: String?, acquisition: Double?, timestamp: Date) throws -> Mapping {
    if sampleEpoch == epoch, let acquisition, acquisition.isFinite {
      let elapsed = acquisition - monotonicOrigin
      return Mapping(
        elapsed: max(0, elapsed),
        eligible: elapsed >= 0 && acquisition >= (epochStart ?? monotonicOrigin)
          && (stopMonotonic.map { acquisition <= $0 } ?? true),
        uncertain: uncertain == true)
    }
    return Mapping(elapsed: 0, eligible: false, uncertain: true)
  }
}

extension WorkoutTimelineAnchor {
  struct LocationClock {
    private var receipt: (utc: Date, uptime: Double)?
    private var uncertainUntil: Double = 0
    private var epoch: String?
    private var lastAcquisition: Double?
    private var recentOriginals: [Original] = []
    fileprivate struct Original: Equatable {
      let timestamp: Date
      let latitude: Double
      let longitude: Double
      let accuracy: Double
    }

    struct Fix {
      let elapsed: Double
      let acquisition: Double
      let epoch: String
      let uncertain: Bool
      fileprivate let original: Original
    }

    mutating func map(
      timestamp: Date, latitude: Double, longitude: Double, accuracy: Double,
      receivedAt: Date, uptime: Double, anchor: WorkoutTimelineAnchor
    ) throws -> Fix? {
      if epoch != anchor.epoch {
        epoch = anchor.epoch
        receipt = nil
        lastAcquisition = nil
        let expected = try WorkoutCoding.date(anchor.startedAt).addingTimeInterval(uptime - anchor.monotonicOrigin)
        uncertainUntil = abs(receivedAt.timeIntervalSince(expected)) > 1 ? uptime + 15 : 0
      }
      if let receipt,
        abs(receivedAt.timeIntervalSince(receipt.utc) - (uptime - receipt.uptime)) > 1
      {
        uncertainUntil = uptime + 15
      }
      receipt = (receivedAt, uptime)
      let age = receivedAt.timeIntervalSince(timestamp)
      guard age.isFinite, age >= 0, age <= 15 else { return nil }
      let acquisition = uptime - age
      let mapped = try anchor.map(epoch: anchor.epoch, acquisition: acquisition, timestamp: timestamp)
      guard mapped.eligible else { return nil }
      return Fix(
        elapsed: mapped.elapsed, acquisition: acquisition, epoch: anchor.epoch,
        uncertain: uptime <= uncertainUntil || mapped.uncertain,
        original: Original(timestamp: timestamp, latitude: latitude, longitude: longitude, accuracy: accuracy))
    }
    mutating func admit(_ fix: Fix) -> Bool {
      guard !recentOriginals.contains(fix.original) else { return false }
      guard lastAcquisition.map({ fix.acquisition > $0 + 0.000_001 }) ?? true else { return false }
      lastAcquisition = fix.acquisition
      if recentOriginals.count == 256 { recentOriginals.removeFirst() }
      recentOriginals.append(fix.original)
      return true
    }
  }

  static func activeInterval(id: String, elapsed: Double, archive: WorkoutArchive) throws -> Int64? {
    try archive.store.read { db in
      let metadata = try archive.metadata(id: id)
      if let cutoff = metadata.stopElapsedSeconds, elapsed >= cutoff { return nil }
      let row = try db.rows(
        "SELECT m.id,l.action FROM collection_memberships m JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.deleted=0 AND m.elapsed_seconds<=? AND l.action IN ('start','resume','pause','stop','interruption') ORDER BY m.elapsed_seconds DESC,m.producer DESC,m.sequence DESC LIMIT 1",
        [.text(id), .real(elapsed)], limit: 1
      ).first
      guard let row, ["start", "resume"].contains(row.string("action") ?? "") else { return nil }
      return row.int("id")
    }
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
    guard let id = packet["messageId"] as? String, UUID(uuidString: id) != nil else {
      throw WorkoutDataError.invalid("Invalid outbox identity")
    }
    let data = try JSONSerialization.data(withJSONObject: packet, options: [.sortedKeys])
    guard data.count <= 60_000 else { throw WorkoutDataError.invalid("Outbox packet exceeds bound") }
    return try store.transaction(priority: .capture) { db in
      if let previous = try db.get(namespace: "phone-outbox", key: id) {
        guard previous == data else { throw WorkoutDataError.invalid("Changed outbox identity") }
        return true
      }
      let rows = try db.page(namespace: "phone-outbox", limit: Self.maximumPackets)
      let eventCount = rows.filter {
        (try? JSONSerialization.jsonObject(with: $0.value) as? [String: Any])?["kind"] as? String == "events"
      }.count
      let isEvents = packet["kind"] as? String == "events"
      guard rows.count < Self.maximumPackets, rows.reduce(data.count, { $0 + $1.value.count }) <= Self.maximumBytes,
        !isEvents || eventCount < Self.maximumEvents
      else { return false }
      try db.put(namespace: "phone-outbox", key: id, value: data, immutable: true)
      return true
    }
  }
  func acknowledge(_ id: String) throws {
    try store.transaction(priority: .capture) { db in try db.remove(namespace: "phone-outbox", key: id) }
  }
}

extension WorkoutControlJournal {
  func nextReady(workoutID: String, origin: String) throws -> WorkoutCommand? {
    try store.read { db in
      let applied =
        try db.get(namespace: "applied-origins", key: workoutID + ":" + origin)
        .map { try JSONDecoder().decode(Int64.self, from: $0) } ?? 0
      guard
        let data = try db.get(namespace: "command-sequences", key: workoutID + ":" + origin + ":" + String(applied + 1))
      else { return nil }
      let id = try JSONDecoder().decode(String.self, from: data)
      return try command(id: id)
    }
  }
  func admitLocal(
    workoutID: String, origin: String, action: String, at: Date,
    options: [String: WorkoutJSON] = [:]
  ) throws -> WorkoutCommand {
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
  static func accept(
    _ incoming: WorkoutOwnerSnapshot, archive: WorkoutArchive, control: WorkoutControlJournal,
    startedAt: Date, indoor: Bool, saveToHealth: Bool, recordGPS: Bool
  ) throws -> WorkoutMetadata? {
    guard incoming.owner == "watch", ["running", "paused"].contains(incoming.phase), incoming.stopCutoff == nil else {
      return nil
    }
    return try archive.store.transaction(priority: .capture) { db in
      let previous = try control.snapshot(workoutID: incoming.workoutID)
      let newer = try WorkoutControlReducer.accepts(incoming, previous: previous)
      guard newer || previous == incoming else { return nil }
      let exists = try db.scalarInt("SELECT count(*) FROM collections WHERE id=?", [.text(incoming.workoutID)]) ?? 0
      let metadata: WorkoutMetadata
      if exists > 0 {
        metadata = try archive.metadata(id: incoming.workoutID)
        try WorkoutRecordingPolicy.requireOptions(metadata, saveToHealth: saveToHealth, recordGPS: recordGPS)
        guard metadata.watchEnabled, metadata.endedAt == nil,
          ["preparing", "running", "paused", "recoverable"].contains(metadata.phase)
        else { return nil }
      } else {
        guard previous?.stopCutoff == nil, !["completed", "failed"].contains(previous?.phase ?? "") else { return nil }
        metadata = try archive.create(
          id: incoming.workoutID, startedAt: startedAt, indoor: indoor, watchEnabled: true, saveToHealth: saveToHealth,
          recordGPS: recordGPS)
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
  static func activate(
    _ incoming: WorkoutOwnerSnapshot, archive: WorkoutArchive, control: WorkoutControlJournal,
    startedAt: Date, indoor: Bool, now: Date, uptime: Double, epoch: String,
    reportedElapsed: Double?, reportedTimer: Double?, saveToHealth: Bool, recordGPS: Bool
  ) throws -> WorkoutAdoptedState? {
    try archive.store.transaction(priority: .capture) { _ in
      guard
        let metadata = try accept(
          incoming, archive: archive, control: control, startedAt: startedAt, indoor: indoor,
          saveToHealth: saveToHealth, recordGPS: recordGPS)
      else { return nil }
      let timing = try WorkoutOwnerTiming.remote(
        id: metadata.id, timestamp: incoming.effectiveAt, elapsed: reportedElapsed, timer: reportedTimer,
        archive: archive)
      let elapsed = timing.elapsedSeconds
      let active = timing.timerSeconds
      let anchor = WorkoutTimelineAnchor(
        epoch: epoch, monotonicOrigin: uptime - elapsed, startedAt: metadata.startedAt, uncertain: true,
        epochStart: uptime)
      try archive.update(
        id: metadata.id, phase: incoming.phase, healthKitState: incoming.healthOutcome,
        healthKitUUID: incoming.healthWorkoutID)
      return WorkoutAdoptedState(
        metadata: try archive.metadata(id: metadata.id), owner: incoming, timeline: anchor, elapsed: elapsed,
        active: active)
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
  static func reconcile(
    workoutID: String, owner: String, nativePhase: String, observedAt: Date,
    nativeEventDates: [String: Date], observedLapIDs: Set<String>,
    cutoff: Date?, health: String, healthID: String?, archive: WorkoutArchive,
    control: WorkoutControlJournal, timing: WorkoutOwnerTiming? = nil,
    save: (WorkoutOwnerSnapshot) throws -> Void = { _ in }
  ) throws -> WorkoutRecoveredCommandResult {
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
      if nativePhase == "stopped" {
        guard let timing else { throw WorkoutDataError.invalid("Stopped native owner has no retained timing") }
        let snapshot = try control.observe(
          workoutID: workoutID, owner: owner, phase: "finishing", at: observedAt,
          health: health, healthID: healthID, cutoff: cutoff ?? WorkoutCoding.date(timing.timestamp), timing: timing)
        try save(snapshot)
        return WorkoutRecoveredCommandResult(snapshot: snapshot, required: command)
      }
      let ended = nativePhase == "ended"
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
      let retained =
        try command.origin == owner && command.options["cutoffUTC"] != nil
        ? WorkoutOwnerTiming.command(command) : timing
      guard let retained else { throw WorkoutDataError.invalid("Recovered owner timing is unavailable") }
      try retained.validate()
      let date = try nativeEventDates[command.action] ?? WorkoutCoding.date(retained.timestamp)
      var insertedLap = false
      if observed, ["start", "pause", "resume", "lap"].contains(command.action),
        try !archive.hasEvent(id: workoutID, eventID: command.id)
      {
        let event = try WorkoutEvent(
          workoutId: workoutID, kind: "lifecycle", source: owner, timestamp: date,
          elapsedSeconds: retained.elapsedSeconds,
          payload: [
            "action": .string(command.action),
            "operationId": .string(command.id), "recoveredNativeEffect": .bool(true),
          ], eventId: command.id)
        try archive.append(event)
        insertedLap = command.action == "lap"
      }
      let ownerTiming = (cutoff != nil || (ended && !command.endsWorkout)) ? timing : retained
      guard let ownerTiming else { throw WorkoutDataError.invalid("Recovered terminal timing is unavailable") }
      let resolvedCutoff = try cutoff ?? (ended ? WorkoutCoding.date(ownerTiming.timestamp) : nil)
      let snapshot = try control.observe(
        workoutID: workoutID, owner: owner,
        phase: ended ? "completed" : cutoff == nil ? nativePhase : "finishing", at: date,
        health: health, healthID: healthID, cutoff: resolvedCutoff, command: command,
        failure: observed ? nil : "Native session ended without evidence that the pending action was applied",
        timing: ownerTiming)
      try save(snapshot)
      return WorkoutRecoveredCommandResult(
        completed: command, snapshot: snapshot, next: try next(), insertedLap: insertedLap)
    }
  }
}

struct WorkoutPendingRemoteAction: Codable, Equatable {
  let commandID: String
  let workoutID: String
  let action: String
  init(_ command: WorkoutCommand) {
    commandID = command.id
    workoutID = command.workoutID
    action = command.action
  }
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
    self.action = action
    self.expectedPhase = expectedPhase
  }

  var nativeAction: String { action == "finish" ? "stop" : action }
  fileprivate var key: String { rideID + ":" + token }

  func requireCurrent(rideID: String?, token: String, phase: String, pendingAction: String?) throws {
    guard self.rideID == rideID, self.token == token, expectedPhase == phase, pendingAction == nil,
      action != "pause" || phase == "running", action != "resume" || phase == "paused",
      action != "finish" || ["running", "paused"].contains(phase)
    else {
      throw WorkoutDataError.invalid("Ride changed. Use its current controls.")
    }
  }
}

enum WorkoutOwnerStopPolicy {
  static func isUnconfirmed(
    watchOwned: Bool, phase: String, hasCutoff: Bool,
    ownerPhase: String?, stopOutcome: String?, verified: Bool
  ) -> Bool {
    watchOwned && phase == "completed" && hasCutoff && ownerPhase != "completed"
      && stopOutcome != "applied" && !verified
  }
}

enum WorkoutActivityPhase {
  static func resolve(
    _ phase: String, watchOwned: Bool, hasCutoff: Bool, ownerPhase: String?,
    stopOutcome: String?, verified: Bool, pendingStop: Bool, phoneStopping: Bool
  ) -> String {
    // A confirmed owner end outranks an undelivered command receipt or archive transfer.
    if watchOwned && (ownerPhase == "completed" || stopOutcome == "applied" || verified) { return "completed" }
    if pendingStop || phoneStopping || watchOwned && ownerPhase == "finishing" { return "finishing" }
    return WorkoutOwnerStopPolicy.isUnconfirmed(
      watchOwned: watchOwned, phase: phase, hasCutoff: hasCutoff,
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
      guard record.action == request.action else {
        throw WorkoutDataError.invalid("This control has already been used.")
      }
      guard
        let command = try command(id: record.commandID)
          ?? remoteCommand(id: record.commandID, workoutID: request.rideID),
        command.workoutID == request.rideID, command.action == request.nativeAction
      else {
        throw WorkoutDataError.invalid("The original ride command is unavailable.")
      }
      return command
    }
  }

  /// Bind the displayed control only when its native effect or transport intent is durable too.
  func admitActivity(
    _ request: WorkoutActivityRequest, remote: Bool, at date: Date,
    options: [String: WorkoutJSON]
  ) throws -> WorkoutCommand {
    try store.transaction(priority: .capture) { db in
      guard try activityCommand(request) == nil else {
        throw WorkoutDataError.invalid("This control has already been admitted.")
      }
      let command =
        try remote
        ? createRemote(
          workoutID: request.rideID, origin: "phone", action: request.nativeAction, at: date, options: options)
        : admitLocal(
          workoutID: request.rideID, origin: "phone", action: request.nativeAction, at: date, options: options)
      let record = WorkoutActivityCommandRecord(action: request.action, commandID: command.id)
      try db.put(
        namespace: "activity-commands", key: request.key, value: WorkoutCoding.encoder().encode(record), immutable: true
      )
      if remote {
        guard try WorkoutBoundedOutbox(store: store).enqueue(command.packet) else {
          throw WorkoutDataError.invalid("Watch transport is busy. Retry this control.")
        }
      } else {
        let result = try begin(command)
        guard result.outcome == "executing" else {
          throw WorkoutDataError.invalid(result.reason ?? "Owner command is pending.")
        }
      }
      return command
    }
  }

  /// A retry may repair staging, but cannot apply a terminal action again or affect a new ride.
  func restageActivity(_ command: WorkoutCommand, currentRideID: String?) throws -> Bool {
    try store.transaction(priority: .capture) { db in
      guard command.workoutID == currentRideID,
        try pendingRemote(workoutID: command.workoutID)?.commandID == command.id,
        try remoteCommand(id: command.id, workoutID: command.workoutID) == command
      else { return false }
      if let data = try db.get(namespace: "remote-command-results", key: command.id),
        try JSONDecoder().decode(WorkoutCommandResult.self, from: data).isTerminal
      {
        return false
      }
      try store.requireWorkoutAvailable(id: command.workoutID)
      guard try WorkoutBoundedOutbox(store: store).enqueue(command.packet) else {
        throw WorkoutDataError.invalid("Watch transport is busy. Retry this control.")
      }
      return true
    }
  }
}

extension WorkoutControlJournal {
  func createRemote(
    workoutID: String, origin: String, action: String, at: Date,
    options: [String: WorkoutJSON] = [:]
  ) throws -> WorkoutCommand {
    try store.transaction(priority: .capture) { db in
      let command = try create(workoutID: workoutID, origin: origin, action: action, at: at, options: options)
      try db.put(
        namespace: "pending-remote-action", key: workoutID,
        value: WorkoutCoding.encoder().encode(WorkoutPendingRemoteAction(command)))
      return command
    }
  }
  func pendingRemote(workoutID: String) throws -> WorkoutPendingRemoteAction? {
    try store.read { db in
      try db.get(namespace: "pending-remote-action", key: workoutID).map {
        try JSONDecoder().decode(WorkoutPendingRemoteAction.self, from: $0)
      }
    }
  }
  func confirmedRemoteStop(workoutID: String) throws -> WorkoutCommandResult? {
    try store.read { db in
      try read(WorkoutCommandResult.self, db: db, namespace: "confirmed-remote-stop", key: workoutID)
    }
  }
  func rejectedRemoteWithoutOwner(workoutID: String) throws -> String? {
    try store.read { db in
      guard try pendingRemote(workoutID: workoutID) == nil, try snapshot(workoutID: workoutID) == nil,
        try active(workoutID: workoutID) == nil,
        let result = try read(WorkoutCommandResult.self, db: db, namespace: "terminal-remote-action", key: workoutID),
        ["failed", "rejected"].contains(result.outcome),
        let command = try remoteCommand(id: result.commandID, workoutID: workoutID),
        ["start", "stop"].contains(command.action)
      else { return nil }
      return result.reason ?? "The original owner rejected the unconfirmed action; no workout end time was inferred."
    }
  }
  func completeRemote(_ result: WorkoutCommandResult, acknowledgedID: String, workoutID: String) throws -> Bool {
    try store.transaction(priority: .capture) { db in
      guard result.isTerminal, result.commandID == acknowledgedID,
        let command = try remoteCommand(id: acknowledgedID, workoutID: workoutID)
      else { return false }
      try db.put(namespace: "remote-command-results", key: acknowledgedID, value: encode(result))
      if command.endsWorkout, result.outcome == "applied" {
        try db.put(namespace: "confirmed-remote-stop", key: workoutID, value: encode(result))
      }
      guard let pending = try pendingRemote(workoutID: workoutID),
        pending.matches(result, acknowledgedID: acknowledgedID, workoutID: workoutID)
      else { return false }
      // The receipt and cleared intent must survive a crash before phone-current is checkpointed.
      if result.outcome == "applied" {
        try db.remove(namespace: "terminal-remote-action", key: workoutID)
      } else {
        try db.put(namespace: "terminal-remote-action", key: workoutID, value: encode(result))
      }
      try db.remove(namespace: "pending-remote-action", key: workoutID)
      return true
    }
  }
}

enum WorkoutPhoneStartProjection {
  static func hasConfirmedOwner(id: String, control: WorkoutControlJournal) throws -> Bool {
    try control.snapshot(workoutID: id) != nil
  }

  static func prepare(
    archive: WorkoutArchive, id: String, startedAt: Date, uptime: Double, epoch: String,
    persistIntent: (WorkoutTimelineAnchor, WorkoutOwnerTiming) throws -> Void
  ) throws {
    try archive.store.transaction(priority: .capture) { _ in
      let anchor = try confirm(
        archive: archive, id: id, startedAt: startedAt, now: startedAt, uptime: uptime, epoch: epoch)
      let timing = try WorkoutOwnerTiming(timestamp: anchor.startedAt, elapsedSeconds: 0, timerSeconds: 0)
      try persistIntent(anchor, timing)
    }
  }
  static func confirm(
    archive: WorkoutArchive, id: String, startedAt: Date, now: Date, uptime: Double, epoch: String,
    startedUptime: Double? = nil, ownerTiming: WorkoutOwnerTiming? = nil
  )
    throws -> WorkoutTimelineAnchor
  {
    try archive.store.transaction(priority: .capture) { _ in
      let previous = try archive.metadata(id: id)
      if previous.phase == "recoverable", previous.endedAt == nil { try archive.update(id: id, phase: "preparing") }
      let metadata = try archive.confirmStart(id: id, startedAt: startedAt)
      if let ownerTiming {
        let timing = try WorkoutOwnerTiming.retained(
          id: id, checkpoint: ownerTiming, archive: archive, preserveActive: true)
        return WorkoutTimelineAnchor(
          epoch: epoch, monotonicOrigin: uptime - timing.elapsedSeconds, startedAt: metadata.startedAt,
          uncertain: true, epochStart: uptime)
      }
      guard let origin = startedUptime ?? (now == startedAt ? uptime : nil), origin <= uptime else {
        throw WorkoutDataError.invalid("Phone start has no monotonic acquisition timing")
      }
      return WorkoutTimelineAnchor(
        epoch: epoch, monotonicOrigin: origin, startedAt: metadata.startedAt, epochStart: origin)
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
    if active == id {
      invalidated = true
      return false
    }
    if active == nil {
      active = id
      return true
    }
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
  static func confirmOwnerPhase(
    archive: WorkoutArchive, id: String, localPhase: String, ownerPhase: String,
    startedAt: Date?, now: Date, uptime: Double, epoch: String, ownerTiming: WorkoutOwnerTiming? = nil
  ) throws -> WorkoutTimelineAnchor? {
    guard localPhase == "preparing", ["running", "paused", "finishing", "completed"].contains(ownerPhase) else {
      return nil
    }
    guard let startedAt else {
      throw WorkoutDataError.invalid("The started owner did not provide its original start time")
    }
    guard let ownerTiming else { throw WorkoutDataError.invalid("The started owner has no measured timing") }
    return try confirm(
      archive: archive, id: id, startedAt: startedAt, now: now, uptime: uptime, epoch: epoch, ownerTiming: ownerTiming)
  }
}

struct WorkoutPhoneTerminalProjection {
  let seal: WorkoutSeal
  let startedAt: String
  let elapsedSeconds: Double
  let timerSeconds: Double
  let preparationAnchor: WorkoutTimelineAnchor?

  static func request(archive: WorkoutArchive, command: WorkoutCommand) throws {
    guard command.endsWorkout, try archive.metadata(id: command.workoutID).watchEnabled else {
      throw WorkoutDataError.invalid("Expected a Watch stop request")
    }
    _ = try WorkoutOwnerTiming.command(command)
    try archive.update(id: command.workoutID, phase: "finishing")
  }

  static func timing(_ snapshot: WorkoutOwnerSnapshot) throws -> WorkoutOwnerTiming {
    guard let timing = snapshot.timing, snapshot.stopCutoff == timing.timestamp else {
      throw WorkoutDataError.invalid("The stopped Watch owner has no complete retained timing")
    }
    try timing.validate()
    return timing
  }

  static func retain(
    archive: WorkoutArchive, id: String, timing: WorkoutOwnerTiming, phase: String, health: String,
    healthReason: String? = nil
  ) throws {
    try archive.store.transaction(priority: .capture) { _ in
      try archive.update(
        id: id, healthKitState: health, healthReason: healthReason, stopElapsedSeconds: timing.elapsedSeconds,
        ownerTiming: timing)
      try archive.finish(id: id, endedAt: WorkoutCoding.date(timing.timestamp), finalPhase: phase)
    }
  }

  static func acceptsStatus(
    _ snapshot: WorkoutOwnerSnapshot, after seal: WorkoutSeal?, stopRequested: Bool = false
  ) -> Bool {
    if stopRequested, ["running", "paused", "preparing", "recoverable"].contains(snapshot.phase) { return false }
    guard let seal else { return true }
    return snapshot.ownerRevision >= seal.ownerRevision && snapshot.stopCutoff == seal.stopCutoff
      && snapshot.timing?.elapsedSeconds == seal.stopElapsedSeconds
      && snapshot.timing?.timerSeconds == seal.timerSeconds
      && ["finishing", "completed", "failed"].contains(snapshot.phase)
  }

  /// A terminal archive may be the first owner message. Confirm its start before
  /// finishing the catalog, in the same transaction, including chunk-carried seals.
  static func accept(
    archive: WorkoutArchive, transfer: WorkoutTransferJournal, incoming: WorkoutSeal,
    startedAt: Date?, localPhase: String?, timerSeconds: Double?, now: Date, uptime: Double,
    epoch: String
  ) throws -> Self? {
    guard timerSeconds.map({ $0.isFinite && $0 >= 0 && $0 <= 2_678_400 }) ?? true else {
      throw WorkoutDataError.invalid("Invalid owner active duration")
    }
    return try archive.store.transaction { _ in
      guard try archive.metadata(id: incoming.workoutID).watchEnabled else {
        throw WorkoutDataError.invalid("A Watch seal cannot replace a phone-owned workout")
      }
      guard let seal = try transfer.acceptCurrent(incoming) else { return nil }
      var metadata = try archive.metadata(id: seal.workoutID)
      if metadata.phase == "preparing" {
        guard let startedAt else {
          throw WorkoutDataError.invalid("The terminal owner did not provide its original start time")
        }
        metadata = try archive.confirmStart(id: seal.workoutID, startedAt: startedAt)
      } else if localPhase == "preparing", let startedAt,
        metadata.startedAt != WorkoutCoding.timestamp(startedAt)
      {
        throw WorkoutDataError.invalid("Terminal start conflicts with the confirmed collection")
      }
      let elapsed = seal.stopElapsedSeconds
      var anchor: WorkoutTimelineAnchor?
      if localPhase == "preparing" {
        var mapped = WorkoutTimelineAnchor(
          epoch: epoch, monotonicOrigin: uptime - elapsed, startedAt: metadata.startedAt)
        mapped.stopUTC = seal.stopCutoff
        mapped.stopMonotonic = mapped.monotonicOrigin + elapsed
        anchor = mapped
      }
      let timing = try WorkoutOwnerTiming(
        timestamp: seal.stopCutoff, elapsedSeconds: seal.stopElapsedSeconds, timerSeconds: seal.timerSeconds)
      if metadata.endedAt != seal.stopCutoff || metadata.phase != "completed"
        || metadata.healthKitState != seal.healthOutcome || metadata.ownerTiming != timing
      {
        try retain(
          archive: archive, id: seal.workoutID, timing: timing, phase: "completed", health: seal.healthOutcome,
          healthReason: seal.healthReason)
      }
      return Self(
        seal: seal, startedAt: metadata.startedAt, elapsedSeconds: elapsed, timerSeconds: seal.timerSeconds,
        preparationAnchor: anchor)
    }
  }
}

/// Phone recording without Health owns local effects directly; original event and command receipt commit together.
enum WorkoutLocalOwner {
  static func restore(
    id: String, epoch: String?, checkpoint: WorkoutOwnerTiming, needsInterruption: Bool,
    archive: WorkoutArchive, pendingCommand: WorkoutCommand? = nil
  ) throws -> (cutoff: Date?, timing: WorkoutOwnerTiming) {
    try archive.store.transaction(priority: .capture) { _ in
      let metadata = try archive.metadata(id: id)
      if metadata.endedAt != nil {
        let timing = try WorkoutOwnerTiming.terminal(metadata)
        return (try WorkoutCoding.date(timing.timestamp), timing)
      }
      if let command = pendingCommand, command.endsWorkout {
        let timing = try WorkoutOwnerTiming.command(command)
        return (try WorkoutCoding.date(timing.timestamp), timing)
      }
      let uncertainActive =
        try metadata.saveToHealth
        && pendingCommand.map {
          guard ["pause", "resume"].contains($0.action) else { return false }
          return try !archive.hasEvent(id: id, eventID: $0.id)
        } == true
      let timing = try WorkoutOwnerTiming.retained(
        id: id, checkpoint: checkpoint, archive: archive, preserveActive: uncertainActive)
      if uncertainActive, timing.elapsedSeconds > checkpoint.elapsedSeconds {
        try pauseUncertainActive(id: id, checkpoint: checkpoint, archive: archive)
      }
      if needsInterruption, let epoch {
        try interrupt(id: id, epoch: epoch, timing: timing, archive: archive)
      }
      return (nil, timing)
    }
  }
  static func pauseUncertainActive(id: String, checkpoint: WorkoutOwnerTiming, archive: WorkoutArchive) throws {
    try checkpoint.validate()
    let source = try archive.metadata(id: id).watchEnabled ? "watch" : "phone"
    let eventID = WorkoutStableIdentity.uuid(
      "uncertain-active:\(id):\(checkpoint.elapsedSeconds):\(checkpoint.timestamp)")
    guard try !archive.hasEvent(id: id, eventID: eventID) else { return }
    try archive.append(
      WorkoutEvent(
        workoutId: id, kind: "lifecycle", source: source,
        timestamp: WorkoutCoding.date(checkpoint.timestamp), elapsedSeconds: checkpoint.elapsedSeconds,
        payload: ["action": .string("pause")], eventId: eventID))
    try WorkoutTransferJournal(archive: archive).register(id: id, producer: source)
  }
  static func interrupt(id: String, epoch: String, timing: WorkoutOwnerTiming, archive: WorkoutArchive) throws {
    try timing.validate()
    try archive.store.transaction(priority: .capture) { _ in
      let metadata = try archive.metadata(id: id)
      guard metadata.endedAt == nil else { throw WorkoutDataError.invalid("The owner already stopped") }
      let eventID = WorkoutStableIdentity.uuid("local-interruption:\(id):\(epoch):\(timing.elapsedSeconds)")
      guard try !archive.hasEvent(id: id, eventID: eventID) else { return }
      let source = metadata.watchEnabled ? "watch" : "phone"
      let event = try WorkoutEvent(
        workoutId: id, kind: "lifecycle", source: source, timestamp: WorkoutCoding.date(timing.timestamp),
        elapsedSeconds: timing.elapsedSeconds,
        payload: [
          "action": .string("pause"), "interrupted": .bool(true),
          "clockEpoch": .string(epoch), "timerSeconds": .number(timing.timerSeconds),
          "cycSequence": .string(String(try archive.sourceProgress(id: id, producer: "cyc").lastSequence)),
        ], eventId: eventID)
      try archive.append(event)
      try WorkoutTransferJournal(archive: archive).register(id: id, producer: source)
    }
  }
  static func observe(
    id: String, phase: String, at: Date, elapsed: Double, command: WorkoutCommand?,
    cutoff: Date?, discarded: Bool, archive: WorkoutArchive, control: WorkoutControlJournal,
    failure: String? = nil, timing: WorkoutOwnerTiming? = nil, checkpoint: (() throws -> Void)? = nil
  ) throws -> WorkoutOwnerSnapshot {
    try archive.store.transaction(priority: .capture) { _ in
      let metadata = try archive.metadata(id: id)
      guard !metadata.watchEnabled, !metadata.saveToHealth else {
        throw WorkoutDataError.invalid("Expected a local phone owner")
      }
      if let command, failure == nil, ["start", "pause", "resume", "lap", "stop", "discard"].contains(command.action),
        try !archive.hasEvent(id: id, eventID: command.id)
      {
        let event = try WorkoutEvent(
          workoutId: id, kind: "lifecycle", source: "phone", timestamp: at,
          elapsedSeconds: command.action == "start" ? 0 : elapsed,
          payload: [
            "action": .string(command.endsWorkout ? "stop" : command.action), "operationId": .string(command.id),
          ], eventId: command.id)
        try archive.append(event)
        try WorkoutTransferJournal(archive: archive).register(id: id, producer: "phone")
      }
      let snapshot = try control.observe(
        workoutID: id, owner: "phone", phase: phase, at: at,
        health: discarded ? "discarded" : "notRequested", cutoff: cutoff, command: command, failure: failure,
        timing: timing)
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
