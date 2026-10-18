import Foundation
import PowerLogCore

struct ExportFailure: Error, LocalizedError, Equatable {
  let code: String
  let message: String
  var errorDescription: String? { message }
}

final class ExportSource: @unchecked Sendable {
  static let shared = ExportSource {
    try WorkoutArchive(
      rootURL: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("PowerLog/workouts", isDirectory: true))
  }
  static let maximumSessions = 16
  static let maximumKey = 9_007_199_254_740_991.0
  static let producerCodes = ["controller": 0.0, "phone": 1.0, "watch": 2.0]
  struct Job {
    let projection: String
    let examined: Int
    let steps: Int
    let reprepares: Int
  }
  var projectHealth: (WorkoutArchive, String) throws -> [String: Any] = { try $0.healthProjection(id: $1) }
  var openSessionCount: Int {
    lock.lock()
    defer { lock.unlock() }
    return sessions.count
  }
  var beforePinnedOpen: (() throws -> Void)?
  static var jobObserverForTesting: ((Job) -> Void)?

  let queue = DispatchQueue(label: "app.powerlog.export.source", qos: .userInitiated)
  let pageRows: Int
  let pageBytes: Int
  let jobRows: Int
  let distanceWait: Double
  private let provider: () throws -> WorkoutArchive
  private var opened: WorkoutArchive?
  private let lock = NSLock()
  private var sessions: [String: Session] = [:]

  init(
    pageRows: Int = 4096, pageBytes: Int = 4 * 1024 * 1024, jobRows: Int = PowerLogStorageLimits.pageRows,
    distanceWait: Double = 60, archive: @escaping () throws -> WorkoutArchive
  ) {
    precondition((1...4096).contains(pageRows) && (1...PowerLogStorageLimits.pageRows).contains(jobRows))
    self.pageRows = pageRows
    self.pageBytes = pageBytes
    self.jobRows = jobRows
    self.distanceWait = distanceWait
    provider = archive
  }

  private final class Session {
    let id = UUID().uuidString.lowercased()
    let rideID: String
    let kind: String
    let revision: Int64
    let distance: WorkoutDistanceSnapshot?
    let readLease: PowerLogReadLease
    let lease: WorkoutDistanceLease?
    var connections = Set<String>()
    init(
      rideID: String, kind: String, revision: Int64, distance: WorkoutDistanceSnapshot?, lease: WorkoutDistanceLease?,
      readLease: PowerLogReadLease
    ) {
      self.readLease = readLease
      self.rideID = rideID
      self.kind = kind
      self.revision = revision
      self.distance = distance
      self.lease = lease
    }
  }

  func archive() throws -> WorkoutArchive {
    lock.lock()
    defer { lock.unlock() }
    if let opened { return opened }
    let archive = try provider()
    opened = archive
    return archive
  }

  func open(_ request: [String: Any]) throws -> [String: Any] {
    do { return try admit(request) } catch { throw Self.failure(error) }
  }

  static func ready(projection: [String: Any], selectedRideID: String?) throws -> Bool {
    try RideCompletion.shared.exportReady(
      snapshot: WorkoutArchive.completionProjection(projection), selectedRideId: selectedRideID)
  }

  func page(_ request: [String: Any]) throws -> [String: Any] {
    do { return try read(request) } catch { throw Self.failure(error) }
  }

  func close(_ session: String) {
    lock.lock()
    let removed = sessions.removeValue(forKey: session)
    lock.unlock()
    removed?.lease?.release()
  }

  func closeAll() {
    lock.lock()
    let removed = Array(sessions.values)
    sessions.removeAll()
    lock.unlock()
    for session in removed { session.lease?.release() }
  }

  private func admit(_ request: [String: Any]) throws -> [String: Any] {
    guard let rideID = request["rideId"] as? String, let kind = request["kind"] as? String else {
      throw ExportFailure(code: "unsupported", message: "The export request is incomplete.")
    }
    guard ["fit", "zip"].contains(kind) else {
      throw ExportFailure(code: "unsupported", message: "This export kind is not supported.")
    }
    if let requested = request["distanceSource"] as? String, !DistancePolicy.shared.validSelection(selection: requested)
    {
      throw ExportFailure(code: "unsupported", message: "This distance source is not supported.")
    }
    let selection = try archive().metadata(id: rideID).distanceSource
    guard DistancePolicy.shared.validSelection(selection: selection) else {
      throw ExportFailure(code: "unsupported", message: "This distance source is not supported.")
    }
    guard (try? WorkoutCoding.id(rideID)) == rideID else {
      throw ExportFailure(code: "gate", message: "This ride identifier is not one Power Log stored.")
    }
    lock.lock()
    let open = sessions.count
    lock.unlock()
    guard open < Self.maximumSessions else { throw Self.tooMany }
    let archive = try archive()
    let readLease = try PowerLogReadLease(store: archive.store, id: rideID)
    var retainedLease = false
    defer { if !retainedLease { readLease.release() } }
    let initial = try archive.metadata(id: rideID)
    guard try Self.ready(projection: initial.dictionary, selectedRideID: rideID),
      let revision = initial.collectionRevision
    else {
      throw ExportFailure(
        code: "gate", message: "Finish the ride before exporting it.")
    }
    let gps = try ["phone", "watch"].filter { try present(rideID, kind: "location", source: $0, revision: revision) }
    let health = try ["phone", "watch"].filter {
      try present(rideID, kind: "health", source: $0, revision: revision, exportKind: kind)
    }
    let distance =
      kind == "fit" ? try leasedDistance(rideID, revision: revision, selection: selection, archive: archive) : nil
    defer { if !retainedLease { distance?.lease.release() } }
    try beforePinnedOpen?()
    let (metadata, healthProjection) = try archive.store.read { _ in
      let metadata = try archive.metadata(id: rideID)
      let health = try projectHealth(archive, rideID)
      let snapshot = try WorkoutArchive.completionProjection(metadata.dictionary.merging(health) { _, new in new })
      guard
        RideCompletion.shared.openExport(
          snapshot: snapshot, selectedRideId: rideID, pinRevision: revision)
      else { throw ExportFailure(code: "gate", message: "The recording changed before export opened.") }
      return (metadata, health)
    }
    guard let endedAt = metadata.endedAt else {
      throw ExportFailure(code: "gate", message: "Finish the ride before exporting it.")
    }
    let session = Session(
      rideID: rideID, kind: kind, revision: revision, distance: distance?.snapshot, lease: distance?.lease,
      readLease: readLease)
    var profile: Any = NSNull()
    if let source = session.distance?.source {
      let parts = source.split(separator: ":").map(String.init)
      var value: [String: Any] = ["source": source, "kind": parts[0]]
      if parts.count == 2 { value["producer"] = parts[1] }
      profile = value
    }
    let ownerTiming: Any =
      metadata.ownerTiming.map {
        ["timestamp": $0.timestamp, "elapsedSeconds": $0.elapsedSeconds, "timerSeconds": $0.timerSeconds]
      } ?? NSNull()
    var ride: [String: Any] = [
      "startedAt": metadata.startedAt, "endedAt": endedAt, "ownerTiming": ownerTiming, "indoor": metadata.indoor,
      "interrupted": metadata.interrupted, "watchEnabled": metadata.watchEnabled,
      "saveToHealth": metadata.saveToHealth, "recordGPS": metadata.recordGPS,
      "batteryConsumptionSource": metadata.batteryConsumptionSource,
      "health": [
        "provider": "appleHealth", "state": metadata.healthKitState,
        "workoutUUID": metadata.healthKitUUID as Any? ?? NSNull(), "export": NSNull(),
      ],
      "example": metadata.example == true, "sampleHz": metadata.sampleHz,
    ]
    ride.merge(healthProjection) { _, new in new }
    lock.lock()
    guard sessions.count < Self.maximumSessions else {
      lock.unlock()
      throw Self.tooMany
    }
    sessions[session.id] = session
    retainedLease = true
    lock.unlock()
    return [
      "session": session.id, "metadata": ride, "elapsedEnd": metadata.elapsedSeconds,
      "producers": ["gps": gps, "health": health], "distanceProfile": profile,
    ]
  }

  private static let tooMany = ExportFailure(code: "limit", message: "Too many exports are open. Try again shortly.")

  private func leasedDistance(_ id: String, revision: Int64, selection: String, archive: WorkoutArchive) throws -> (
    snapshot: WorkoutDistanceSnapshot, lease: WorkoutDistanceLease
  ) {
    let distances = WorkoutDistanceStore(store: archive.store)
    let deadline = ProcessInfo.processInfo.systemUptime + distanceWait
    while true {
      do {
        return try distances.leasedSnapshot(id: id, revision: revision, selection: selection)
      } catch WorkoutDistanceError.pending, WorkoutDistanceError.expired {
        guard ProcessInfo.processInfo.systemUptime < deadline else {
          throw ExportFailure(code: "gate", message: "Distance is still being calculated. Try the export again.")
        }
        Thread.sleep(forTimeInterval: 0.25)
      }
    }
  }

  static func presenceSQL(kind: String, limit: Int, exportKind: String = "fit") -> String {
    let health = kind == "health"
    return
      "SELECT m.elapsed_seconds,m.observation_id,CASE WHEN m.revision<=? AND \(PowerLogStore.selectedMembershipSQL)"
      + (health && exportKind == "fit"
        ? " AND o.representation NOT IN ('workoutAssociation','healthTombstone','workoutMetadata')" : "")
      + " THEN 1 ELSE 0 END FROM collection_memberships m INDEXED BY membership_stream_time"
      + (health ? " JOIN observations o ON o.id=m.observation_id" : "")
      + " WHERE m.collection_id=? AND m.kind='\(kind)' AND m.source=? AND (m.elapsed_seconds,m.observation_id)>(?,?) ORDER BY m.elapsed_seconds,m.observation_id LIMIT \(limit)"
  }

  private func present(
    _ id: String, kind: String, source: String, revision: Int64, exportKind: String = "fit"
  ) throws -> Bool {
    let store = try archive().store
    let sql = Self.presenceSQL(kind: kind, limit: jobRows, exportKind: exportKind)
    var elapsed = -Double.greatestFiniteMagnitude
    var observation = Int64.min
    while true {
      var found = false
      var work = (steps: 0, reprepares: 0)
      let examined = try store.read(priority: .background) { db -> Int in
        try store.requireWorkoutAvailable(id: id)
        return try db.scan(
          sql,
          [
            .integer(revision), .integer(revision), .integer(revision), .text(id), .text(source), .real(elapsed),
            .integer(observation),
          ], limit: jobRows, observeWork: Self.jobObserverForTesting == nil ? nil : { work = ($0, $1) }
        ) { row in
          elapsed = row.double(0)!
          observation = row.integer(1)!
          found = row.integer(2) == 1
          return !found
        }
      }
      Self.jobObserverForTesting?(
        Job(projection: "producers", examined: examined, steps: work.steps, reprepares: work.reprepares))
      if found { return true }
      if examined < jobRows { return false }
    }
  }

  private func read(_ request: [String: Any]) throws -> [String: Any] {
    guard let sessionID = request["session"] as? String, let name = request["projection"] as? String else {
      throw ExportFailure(code: "unsupported", message: "The export page request is incomplete.")
    }
    lock.lock()
    let session = sessions[sessionID]
    lock.unlock()
    guard let session else { throw ExportFailure(code: "cancelled", message: "This export is no longer open.") }
    try archive().store.requireWorkoutAvailable(id: session.rideID)
    let after = request["after"].flatMap { $0 is NSNull ? nil : $0 }
    if name == "distance" {
      guard session.kind == "fit" else {
        throw ExportFailure(code: "unsupported", message: "This export does not read distance.")
      }
      return try distancePage(session, after: try Self.distanceCursor(after))
    }
    guard let projection = ExportProjection.all[name], projection.kinds.contains(session.kind) else {
      throw ExportFailure(code: "unsupported", message: "This export does not read \(name).")
    }
    return try observationPage(session, projection, after: try Self.cursor(after))
  }

  private struct Key {
    var elapsed: Double
    var producer: String
    var sequence: Int64
    var id: Int64
    var code: Double
    var values: [Double] { [elapsed, code, Double(sequence), Double(id)] }
  }

  private func observationPage(_ session: Session, _ projection: ExportProjection, after: Key?) throws -> [String: Any]
  {
    let store = try archive().store
    let columns = projection.columns(session.kind)
    let sql = projection.sql(session.kind, limit: jobRows)
    let connection = projection.name == "telemetry" ? columns.firstIndex { $0.name == "connection" } : nil
    let numericBytes = 8 * columns.filter { $0.value != .text }.count
    let target = columns.firstIndex { $0.name == "targetEventId" }
    let representation = columns.firstIndex { $0.name == "representation" }
    let evidence = columns.firstIndex { $0.name == "evidence" }
    let retainedKeys = ["logicalKey", "valueBits"].compactMap { name in columns.firstIndex { $0.name == name } }
    var numbers = [[UInt64]](repeating: [], count: columns.count)
    var texts = [[Any]](repeating: [], count: columns.count)
    for index in columns.indices {
      if columns[index].value == .text {
        texts[index].reserveCapacity(pageRows)
      } else {
        numbers[index].reserveCapacity(pageRows)
      }
    }
    var scratch = [String?](repeating: nil, count: columns.count)
    if after == nil, connection != nil { session.connections.removeAll() }
    var seen = session.connections
    var connections: [[String: Any]] = []
    var cursor =
      after ?? Key(elapsed: -Double.greatestFiniteMagnitude, producer: "", sequence: Int64.min, id: Int64.min, code: 0)
    var rows = 0
    var bytes = 0
    var last: Key?
    var done = false
    while true {
      var full = false
      var work = (steps: 0, reprepares: 0)
      var targetWork = (steps: 0, reprepares: 0)
      let examined = try store.read(priority: .background) { db -> Int in
        try store.requireWorkoutAvailable(id: session.rideID)
        var firstSeen: [(token: String, observation: Int64)] = []
        let examined = try db.scan(
          sql,
          [
            .integer(session.revision), .integer(session.revision), .integer(session.revision),
            .text(session.rideID), .real(cursor.elapsed), .text(cursor.producer), .integer(cursor.sequence),
            .integer(cursor.id),
          ], limit: jobRows, observeWork: Self.jobObserverForTesting == nil ? nil : { work = ($0, $1) }
        ) { row in
          let key = try Self.key(row)
          guard row.integer(4) == 1 else {
            cursor = key
            return true
          }
          try Self.deliverable(key)
          var size = numericBytes
          for index in columns.indices where columns[index].value == .text {
            scratch[index] = row.text(Int32(index + 6))
            size += scratch[index]?.utf8.count ?? 0
          }
          if let target {
            guard let evidence, let extra = scratch[evidence] else {
              throw ExportFailure(code: "page", message: "The Health evidence could not be read.")
            }
            let payload = try JSONDecoder().decode([String: WorkoutJSON].self, from: Data(extra.utf8))
            for index in retainedKeys {
              scratch[index] = payload[columns[index].name]?.string
              size += scratch[index]?.utf8.count ?? 0
            }
            scratch[target] = try Self.healthTarget(
              db, id: session.rideID, revision: session.revision, membership: key.id,
              candidate: payload["targetEventId"]?.string ?? payload["supersedesEventId"]?.string,
              tombstone: representation.flatMap { scratch[$0] } == "healthTombstone",
              observeWork: Self.jobObserverForTesting == nil
                ? nil
                : { steps, reprepares in
                  targetWork.steps += steps
                  targetWork.reprepares += reprepares
                })
            size += scratch[target]?.utf8.count ?? 0
          }
          guard rows == 0 || bytes + size <= pageBytes else {
            full = true
            return false
          }
          for index in columns.indices {
            let column = Int32(index + 6)
            switch columns[index].value {
            case .text: texts[index].append(scratch[index] ?? NSNull())
            case .number: numbers[index].append((row.double(column) ?? .nan).bitPattern.littleEndian)
            case .key: numbers[index].append(try Self.exact(row.integer(column)).bitPattern.littleEndian)
            case .decimal: numbers[index].append(try Self.decimal(row, column).bitPattern.littleEndian)
            }
          }
          if let connection, let token = scratch[connection], seen.insert(token).inserted {
            firstSeen.append((token, row.integer(5)!))
          }
          rows += 1
          bytes += size
          last = key
          cursor = key
          full = rows == pageRows
          return !full
        }
        for item in firstSeen {
          let identity = try db.rows(Self.identitySQL, [.integer(item.observation)], limit: 1).first
          connections.append([
            "token": item.token, "vendor": "controller", "model": identity?.string("model") as Any? ?? NSNull(),
            "firmware": identity?.string("firmware") as Any? ?? NSNull(),
            "protocol": identity?.string("protocol") as Any? ?? NSNull(),
          ])
        }
        return examined
      }
      Self.jobObserverForTesting?(
        Job(
          projection: projection.name, examined: examined, steps: work.steps + targetWork.steps,
          reprepares: work.reprepares + targetWork.reprepares))
      if full { break }
      if examined < jobRows {
        done = true
        break
      }
    }
    var page: [String: Any] = [
      "rows": rows, "last": last?.values as Any? ?? NSNull(), "done": done,
      "columns": Dictionary(
        uniqueKeysWithValues: columns.indices.map { index -> (String, Any) in
          (
            columns[index].name,
            columns[index].value == .text ? texts[index] : numbers[index].withUnsafeBytes { Data($0) }
          )
        }),
    ]
    if connection != nil {
      session.connections = seen
      page["connections"] = connections
    }
    return page
  }

  static let healthTargetSQL =
    "SELECT event_id FROM collection_memberships WHERE collection_id=? AND event_id=? AND kind='health' AND revision<=? LIMIT 1"
  static let healthParentTargetsSQL = """
    SELECT target.event_id FROM collection_memberships m
    JOIN observations o ON o.id=m.observation_id
    CROSS JOIN health_samples original INDEXED BY health_external
      ON original.external_id=json_extract(o.extra,'$.sampleUUID')
    CROSS JOIN collection_memberships target INDEXED BY membership_observation
      ON target.observation_id=original.observation_id
    WHERE m.id=? AND original.member_index IS NOT NULL
      AND target.collection_id=m.collection_id AND target.kind='health'
      AND target.source=m.source AND target.revision<m.revision
      AND original.origin_revision IS
        (SELECT version.digest FROM health_parent_versions version
         WHERE version.ride=m.collection_id AND version.provider=original.provider
           AND version.data_origin=original.data_origin AND version.parent_id=original.provider_parent_id
           AND version.admitted_revision<=m.revision ORDER BY version.admitted_revision DESC LIMIT 1)
    LIMIT 2
    """

  private static func healthTarget(
    _ db: PowerLogDatabase, id: String, revision: Int64, membership: Int64, candidate: String?, tombstone: Bool,
    observeWork: ((Int, Int) -> Void)?
  ) throws -> String? {
    if let candidate {
      var target: String?
      _ = try db.scan(
        healthTargetSQL, [.text(id), .text(candidate), .integer(revision)], limit: 1, observeWork: observeWork
      ) { row in
        target = row.text(0)
        return true
      }
      if let target { return target }
    }
    guard tombstone else { return nil }
    var targets: [String] = []
    _ = try db.scan(
      healthParentTargetsSQL, [.integer(membership)], limit: 2, observeWork: observeWork
    ) { row in
      if let target = row.text(0) { targets.append(target) }
      return true
    }
    return targets.count == 1 ? targets[0] : nil
  }

  private func distancePage(_ session: Session, after: WorkoutDistanceCursor?) throws -> [String: Any] {
    let names = Self.columns(projection: "distance", kind: "fit").filter { $0.type == "number" }.map(\.name)
    var columns = [[UInt64]](repeating: [], count: names.count)
    var starts: [String] = []
    var ends: [String] = []
    var bytes = 0
    var rows = 0
    var last: WorkoutDistanceCursor?
    var done = true
    if let snapshot = session.distance, snapshot.source != nil {
      let distances = WorkoutDistanceStore(store: try archive().store)
      for index in columns.indices { columns[index].reserveCapacity(pageRows) }
      var cursor = after
      done = false
      while true {
        var work = (steps: 0, reprepares: 0)
        let job = try distances.intervals(
          snapshot: snapshot, after: cursor, limit: jobRows,
          observeWork: Self.jobObserverForTesting == nil ? nil : { work = ($0, $1) }
        ) { interval in
          let start = String(try PowerLogStore.microseconds(interval.start))
          let end = String(try PowerLogStore.microseconds(interval.end))
          let added = 8 * columns.count + start.utf8.count + end.utf8.count
          guard rows == 0 || bytes + added <= pageBytes else { return false }
          starts.append(start)
          ends.append(end)
          bytes += added
          for (index, name) in names.enumerated() {
            let value: Double
            switch name {
            case "meters": value = interval.meters
            case "segment": value = try Self.exact(interval.segment)
            case "startSpeed": value = interval.startSpeed ?? .nan
            case "endSpeed": value = interval.endSpeed ?? .nan
            default: throw ExportFailure(code: "unsupported", message: "Unknown distance export column.")
            }
            columns[index].append(value.bitPattern.littleEndian)
          }
          _ = try Self.exact(interval.cursor.pointID)
          rows += 1
          last = interval.cursor
          return rows < pageRows
        }
        Self.jobObserverForTesting?(
          Job(projection: "distance", examined: job.examined, steps: work.steps, reprepares: work.reprepares))
        if let examined = job.last { cursor = examined }
        if job.stopped { break }
        if job.examined < jobRows {
          done = true
          break
        }
      }
    }
    var output: [String: Any] = Dictionary(
      uniqueKeysWithValues: names.indices.map { (names[$0], columns[$0].withUnsafeBytes { Data($0) }) })
    output["startUs"] = starts
    output["endUs"] = ends
    return [
      "rows": rows, "last": last.map { [$0.time, Double($0.pointID)] } as Any? ?? NSNull(), "done": done,
      "columns": output,
    ]
  }

  static let identitySQL =
    "SELECT json_extract(CAST(o.extra AS TEXT),'$.controllerModel') AS model,json_extract(CAST(o.extra AS TEXT),'$.firmwareLabel') AS firmware,json_extract(CAST(o.extra AS TEXT),'$.controllerProtocol') AS protocol FROM observations o WHERE o.id=?"

  static func candidateSQL(projection: String, kind: String, limit: Int) -> String? {
    ExportProjection.all[projection].map { $0.sql(kind, limit: limit) }
  }

  static func columns(projection: String, kind: String) -> [(name: String, type: String)] {
    if projection == "distance" {
      guard let definition = ExportCatalog.shared.projections.first(where: { $0.name == projection }),
        definition.kinds.contains(kind)
      else { return [] }
      return definition.columnsFor(platform: "ios", kind: kind).map { ($0.name, $0.type) }
    }
    return ExportProjection.all[projection].map { definition in
      definition.columns(kind).map { ($0.name, $0.value == .text ? "string" : "number") }
    } ?? []
  }

  private static func key(_ row: PowerLogStatementRow) throws -> Key {
    guard let id = row.integer(0), let elapsed = row.double(1), elapsed.isFinite, let producer = row.text(2),
      let sequence = row.integer(3)
    else { throw ExportFailure(code: "page", message: "A stored observation of this ride has no timeline position.") }
    return Key(elapsed: elapsed, producer: producer, sequence: sequence, id: id, code: producerCodes[producer] ?? -1)
  }

  private static func deliverable(_ key: Key) throws {
    guard key.code >= 0 else {
      throw ExportFailure(code: "unsupported", message: "This ride has a recording source Power Log cannot export.")
    }
    _ = try exact(key.sequence)
    _ = try exact(key.id)
  }

  private static func exact(_ value: Int64?) throws -> Double {
    guard let value, value.magnitude <= UInt64(maximumKey) else {
      throw ExportFailure(code: "limit", message: "A stored sequence exceeds the exact export range.")
    }
    return Double(value)
  }

  private static func decimal(_ row: PowerLogStatementRow, _ column: Int32) throws -> Double {
    if let value = row.integer(column) { return try exact(value) }
    if let value = row.double(column) {
      guard value.rounded() == value, abs(value) <= maximumKey else { return .nan }
      return value
    }
    return try row.text(column).flatMap { Int64($0) }.map { try exact($0) } ?? .nan
  }

  private static func number(_ value: Any) -> Double? {
    if let value = value as? Double { return value }
    if let value = value as? Int { return Double(value) }
    return (value as? NSNumber)?.doubleValue
  }

  private static func integral(_ value: Double) -> Int64? {
    guard value.isFinite, value.rounded() == value, abs(value) <= maximumKey else { return nil }
    return Int64(value)
  }

  private static func cursor(_ value: Any?) throws -> Key? {
    guard let value else { return nil }
    guard let values = (value as? [Any])?.compactMap(number), values.count == 4, values[0].isFinite,
      let producer = producerCodes.first(where: { $0.value == values[1] })?.key,
      let sequence = integral(values[2]), let id = integral(values[3])
    else { throw invalidCursor }
    return Key(elapsed: values[0], producer: producer, sequence: sequence, id: id, code: values[1])
  }

  private static func distanceCursor(_ value: Any?) throws -> WorkoutDistanceCursor? {
    guard let value else { return nil }
    guard let values = (value as? [Any])?.compactMap(number), values.count == 2, values[0].isFinite,
      let point = integral(values[1])
    else { throw invalidCursor }
    return WorkoutDistanceCursor(time: values[0], pointID: point)
  }

  private static let invalidCursor = ExportFailure(code: "cursor", message: "The export cursor is invalid.")

  static func failure(_ error: Error) -> ExportFailure {
    switch error {
    case let failure as ExportFailure: return failure
    case PowerLogStorageError.deleted, PowerLogStorageError.missing:
      return ExportFailure(code: "deleted", message: "This ride was deleted from Power Log.")
    case WorkoutDistanceError.expired, PowerLogStorageError.revision, PowerLogStorageError.busy:
      return ExportFailure(code: "changed", message: "The ride changed while it was exported. Try the export again.")
    default:
      return ExportFailure(
        code: "page", message: "This ride's stored data could not be read for export. \(error.localizedDescription)")
    }
  }
}

private struct ExportProjection {
  enum Value { case number, key, decimal, text }
  enum Table { case membership, observation, typed }
  struct Column {
    let name: String
    let value: Value
    let table: Table
    let sql: String
    let zip: Bool
    let fit: Bool
  }
  let name: String
  let kind: String
  let kinds: Set<String>
  let typed: (table: String, alias: String)
  let columns: [Column]

  func columns(_ session: String) -> [Column] { columns.filter { session == "zip" ? $0.zip : $0.fit } }

  /// Candidates are examined in the stream's tie order whatever the selection says; the CASE column
  /// carries the revision, correction and deletion predicates so rejected rows still advance the cursor.
  func sql(_ session: String, limit: Int) -> String {
    let delivered = columns(session)
    let observation = delivered.contains { $0.table == .observation }
    let joined = delivered.contains { $0.table == .typed }
    return
      "SELECT m.id,m.elapsed_seconds,m.producer,m.sequence,CASE WHEN m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) THEN 1 ELSE 0 END,m.observation_id,"
      + delivered.map(\.sql).joined(separator: ",")
      + " FROM collection_memberships m INDEXED BY membership_kind_time"
      + (observation ? " JOIN observations o ON o.id=m.observation_id" : "")
      + (joined ? " LEFT JOIN \(typed.table) \(typed.alias) ON \(typed.alias).observation_id=m.observation_id" : "")
      + " WHERE m.collection_id=? AND m.kind='\(kind)' AND (m.elapsed_seconds,m.producer,m.sequence,m.id)>(?,?,?,?) ORDER BY m.elapsed_seconds,m.producer,m.sequence,m.id LIMIT \(limit)"
  }

  private static let extra = "CAST(o.extra AS TEXT)"
  private static func json(_ path: String) -> String { "json_extract(\(extra),'$.\(path)')" }
  private static func flag(_ path: String) -> String {
    "CASE WHEN json_type(\(extra),'$.\(path)')='true' THEN 1 ELSE 0 END"
  }
  private static func column(_ declaration: ExportColumnDeclaration, projection: String, alias: String) -> Column {
    let name = declaration.name
    var value: Value = declaration.type == "string" ? .text : .number
    var table: Table = .typed
    var sql = "\(alias).\(name)"
    switch name {
    case "elapsedSeconds":
      table = .membership
      sql = "m.elapsed_seconds"
    case "timestamp":
      table = .observation
      sql = "o.original_timestamp"
    case "producer":
      table = .membership
      sql = "m.producer"
    case "producerSequence":
      table = .membership
      sql = "m.sequence"
      value = .key
    case "clockEpoch":
      table = .observation
      sql = "o.clock_epoch"
    case "connection", "connectionEpoch":
      table = .observation
      sql = json("connectionEpoch")
    case "channel", "timerSeconds", "startUs", "endUs":
      table = .observation
      sql = json(name)
    case "targetEventId", "logicalKey", "valueBits":
      table = .observation
      sql = "NULL"
    case "distanceBarrier", "interrupted":
      table = .observation
      sql = flag(name)
    case "controllerSequence":
      table = .observation
      sql = json(name)
      value = .decimal
    case "representation":
      table = .observation
      sql = "NULLIF(o.representation,'')"
      if projection == "healthFit" {
        sql =
          "CASE WHEN h.identifier IN ('HKQuantityTypeIdentifierActiveEnergyBurned','HKQuantityTypeIdentifierBasalEnergyBurned','totalEnergy') AND o.representation='rawQuantity' THEN 'intervalRecord' ELSE NULLIF(o.representation,'') END"
      }
    case "sampleStart", "originalStartTimestamp": sql = "h.start_timestamp"
    case "originalEndTimestamp": sql = "h.end_timestamp"
    case "dataOrigin": sql = "COALESCE(h.data_origin,\(json("sourceBundleIdentifier")))"
    case "providerParentId": sql = "COALESCE(h.provider_parent_id,h.external_id)"
    case "memberIndex": sql = "CAST(h.member_index AS TEXT)"
    case "originRevision": sql = "h.origin_revision"
    case "originalEventId":
      table = .membership
      sql = "m.event_id"
    case "observationRevision":
      table = .membership
      sql = "CAST(m.revision AS TEXT)"
    case "evidence":
      table = .observation
      sql = extra
    case "activeEnergyKcal" where projection == "healthFit", "basalActiveEnergyKcal" where projection == "healthFit":
      sql =
        "COALESCE(h.activeEnergyKcal,CASE WHEN h.identifier='HKQuantityTypeIdentifierActiveEnergyBurned' THEN h.value END)"
    case "basalEnergyKcal" where projection == "healthFit":
      sql =
        "COALESCE(h.basalEnergyKcal,CASE WHEN h.identifier='HKQuantityTypeIdentifierBasalEnergyBurned' THEN h.value END)"
    case "totalEnergyKcal":
      table = .observation
      sql = "COALESCE(\(json(name)),CASE WHEN h.identifier='totalEnergy' THEN h.value END)"
    default: break
    }
    return Column(
      name: name, value: value, table: table, sql: sql,
      zip: declaration.consumers.contains("zip"),
      fit: declaration.consumers.contains("fit") || declaration.consumers.contains("discovery"))
  }

  static let all: [String: ExportProjection] = Dictionary(
    uniqueKeysWithValues:
      ExportCatalog.shared.projections.compactMap { declaration in
        let kind: String
        let typed: (table: String, alias: String)
        switch declaration.name {
        case "telemetry":
          kind = "telemetry"
          typed = ("telemetry_frames", "t")
        case "gps", "gpsDiscovery":
          kind = "location"
          typed = ("locations", "l")
        case "healthZip", "healthFit":
          kind = "health"
          typed = ("health_samples", "h")
        case "lifecycle":
          kind = "lifecycle"
          typed = ("lifecycle_records", "lc")
        default: return nil
        }
        let columns = declaration.columns.filter { $0.platforms.contains("ios") }.map {
          column($0, projection: declaration.name, alias: typed.alias)
        }
        return (
          declaration.name,
          ExportProjection(
            name: declaration.name, kind: kind,
            kinds: Set(declaration.kinds), typed: typed, columns: columns)
        )
      })
}
