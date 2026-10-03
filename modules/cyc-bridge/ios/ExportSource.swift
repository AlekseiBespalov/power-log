import Foundation

/// A rejection whose code is an `ExportErrorCode` of src/core/export/types.ts.
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
  static let producerCodes = ["cyc": 0.0, "phone": 1.0, "watch": 2.0]
  struct Job {
    let projection: String
    let examined: Int
    let steps: Int
    let reprepares: Int
  }
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
    let lease: WorkoutDistanceLease?
    var connections = Set<String>()
    init(
      rideID: String, kind: String, revision: Int64, distance: WorkoutDistanceSnapshot?, lease: WorkoutDistanceLease?
    ) {
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
    let selection = request["distanceSource"] as? String ?? "auto"
    guard selection == "auto" || WorkoutDistancePolicy.sources.contains(selection) else {
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
    let metadata: WorkoutMetadata
    do { metadata = try archive.metadata(id: rideID) } catch WorkoutDataError.invalid {
      throw ExportFailure(code: "gate", message: "Only saved rides can be exported.")
    }
    guard metadata.phase == "completed", let endedAt = metadata.endedAt, metadata.sealVerified,
      let finalization = metadata.finalizationState, ["complete", "partial"].contains(finalization),
      let revision = metadata.collectionRevision
    else {
      throw ExportFailure(
        code: "gate", message: "Finish the ride and wait for its final Watch archive before exporting it.")
    }
    let gps = try ["phone", "watch"].filter { try present(rideID, kind: "location", source: $0, revision: revision) }
    let health = try ["phone", "watch"].filter { try present(rideID, kind: "health", source: $0, revision: revision) }
    let distance =
      kind == "fit" ? try leasedDistance(rideID, revision: revision, selection: selection, archive: archive) : nil
    let session = Session(
      rideID: rideID, kind: kind, revision: revision, distance: distance?.snapshot, lease: distance?.lease)
    lock.lock()
    guard sessions.count < Self.maximumSessions else {
      lock.unlock()
      session.lease?.release()
      throw Self.tooMany
    }
    sessions[session.id] = session
    lock.unlock()
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
    let ride: [String: Any] = [
      "startedAt": metadata.startedAt, "endedAt": endedAt, "ownerTiming": ownerTiming, "indoor": metadata.indoor,
      "interrupted": metadata.interrupted, "watchEnabled": metadata.watchEnabled,
      "saveToHealth": metadata.saveToHealth, "recordGPS": metadata.recordGPS,
      "health": [
        "provider": "appleHealth", "state": metadata.healthKitState,
        "workoutUUID": metadata.healthKitUUID as Any? ?? NSNull(), "export": NSNull(),
      ],
      "watchSyncState": metadata.watchSyncState ?? (metadata.watchEnabled ? "pending" : "notRequired"),
      "finalizationState": finalization, "example": metadata.example == true, "sampleHz": NSNull(),
    ]
    return [
      "session": session.id, "metadata": ride, "elapsedEnd": metadata.elapsedSeconds,
      "producers": ["gps": gps, "health": health], "distanceProfile": profile,
    ]
  }

  private static let tooMany = ExportFailure(code: "limit", message: "Too many exports are open. Try again shortly.")

  private func leasedDistance(_ id: String, revision: Int64, selection: String, archive: WorkoutArchive) throws -> (
    snapshot: WorkoutDistanceSnapshot, lease: WorkoutDistanceLease
  )? {
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
      } catch WorkoutDistanceError.invalid, WorkoutDataError.invalid {
        return nil
      }
    }
  }

  /// A producer is present when it has a selected observation at R; Health counts measurements only.
  static func presenceSQL(kind: String, limit: Int) -> String {
    let health = kind == "health"
    return
      "SELECT m.elapsed_seconds,m.observation_id,CASE WHEN m.revision<=? AND \(PowerLogStore.selectedMembershipSQL)"
      + (health ? " AND o.representation NOT IN ('workoutAssociation','healthTombstone','workoutMetadata')" : "")
      + " THEN 1 ELSE 0 END FROM collection_memberships m INDEXED BY membership_stream_time"
      + (health ? " JOIN observations o ON o.id=m.observation_id" : "")
      + " WHERE m.collection_id=? AND m.kind='\(kind)' AND m.source=? AND (m.elapsed_seconds,m.observation_id)>(?,?) ORDER BY m.elapsed_seconds,m.observation_id LIMIT \(limit)"
  }

  private func present(_ id: String, kind: String, source: String, revision: Int64) throws -> Bool {
    let store = try archive().store
    let sql = Self.presenceSQL(kind: kind, limit: jobRows)
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
            "token": item.token, "vendor": "cyc", "model": identity?.string("model") as Any? ?? NSNull(),
            "firmware": identity?.string("firmware") as Any? ?? NSNull(),
            "protocol": identity?.string("protocol") as Any? ?? NSNull(),
          ])
        }
        return examined
      }
      Self.jobObserverForTesting?(
        Job(projection: projection.name, examined: examined, steps: work.steps, reprepares: work.reprepares))
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

  private func distancePage(_ session: Session, after: WorkoutDistanceCursor?) throws -> [String: Any] {
    var columns = [[UInt64]](repeating: [], count: 6)
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
          guard rows == 0 || (rows + 1) * 8 * columns.count <= pageBytes else { return false }
          let values = [
            interval.start, interval.end, interval.meters, try Self.exact(interval.segment),
            interval.startSpeed ?? .nan, interval.endSpeed ?? .nan,
          ]
          for index in columns.indices { columns[index].append(values[index].bitPattern.littleEndian) }
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
    let names = ["start", "end", "meters", "segment", "startSpeed", "endSpeed"]
    return [
      "rows": rows, "last": last.map { [$0.time, Double($0.pointID)] } as Any? ?? NSNull(), "done": done,
      "columns": Dictionary(
        uniqueKeysWithValues: names.indices.map { (names[$0], columns[$0].withUnsafeBytes { Data($0) }) }),
    ]
  }

  static let identitySQL =
    "SELECT json_extract(CAST(o.extra AS TEXT),'$.controllerModel') AS model,json_extract(CAST(o.extra AS TEXT),'$.firmwareLabel') AS firmware,json_extract(CAST(o.extra AS TEXT),'$.controllerProtocol') AS protocol FROM observations o WHERE o.id=?"

  static func candidateSQL(projection: String, kind: String, limit: Int) -> String? {
    ExportProjection.all[projection].map { $0.sql(kind, limit: limit) }
  }

  static func columns(projection: String, kind: String) -> [(name: String, type: String)] {
    if projection == "distance" {
      return ["start", "end", "meters", "segment", "startSpeed", "endSpeed"].map { ($0, "number") }
    }
    return ExportProjection.all[projection].map { definition in
      definition.columns(kind).map { ($0.name, $0.value == .text ? "string" : "number") }
    } ?? []
  }

  private static func key(_ row: PowerLogStatementRow) throws -> Key {
    guard let id = row.integer(0), let elapsed = row.double(1), elapsed.isFinite, let producer = row.text(2),
      let sequence = row.integer(3)
    else { throw ExportFailure(code: "gate", message: "A stored observation of this ride has no timeline position.") }
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
        code: "gate", message: "This ride's stored data could not be read for export. \(error.localizedDescription)")
    }
  }
}

/// Columns, order and consumers follow `PROJECTIONS` in src/core/export/catalog.ts for the `ios` platform.
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
  private static func c(
    _ name: String, _ value: Value, _ table: Table, _ sql: String, zip: Bool = true, fit: Bool = true
  ) -> Column {
    Column(name: name, value: value, table: table, sql: sql, zip: zip, fit: fit)
  }
  private static func typed(_ alias: String, _ names: [String], zip: Bool = true, fit: Bool = true) -> [Column] {
    names.map { c($0, .number, .typed, "\(alias).\($0)", zip: zip, fit: fit) }
  }
  private static let elapsed = c("elapsedSeconds", .number, .membership, "m.elapsed_seconds")
  private static let timestamp = c("timestamp", .text, .observation, "o.original_timestamp", fit: false)
  private static let producer = c("producer", .text, .membership, "m.producer")
  private static let sequence = c("producerSequence", .key, .membership, "m.sequence")
  private static let epoch = c("clockEpoch", .text, .observation, "o.clock_epoch")
  private static let representation = c("representation", .text, .observation, "NULLIF(o.representation,'')")

  static let all: [String: ExportProjection] = Dictionary(
    uniqueKeysWithValues: [
      ExportProjection(
        name: "telemetry", kind: "telemetry", kinds: ["zip", "fit"], typed: ("telemetry_frames", "t"),
        columns: [elapsed, timestamp, sequence, epoch, c("connection", .text, .observation, json("connectionEpoch"))]
          + typed(
            "t",
            [
              "humanPowerW", "cadenceRpm", "motorInputPowerW", "batteryVoltageV", "batteryCurrentA", "motorCurrentA",
              "motorRpm", "pedalTorqueNm", "controllerTempC", "motorTempC", "consumedAh", "consumedWh",
            ]) + typed("t", ["throttleVoltageV", "faultCode"], fit: false) + typed("t", ["assistLevel"])
          + typed("t", ["controllerSpeedMps", "raceMode", "speedRaw"], fit: false)),
      ExportProjection(
        name: "gps", kind: "location", kinds: ["zip", "fit"], typed: ("locations", "l"),
        columns: [elapsed, timestamp, producer, sequence, epoch]
          + typed(
            "l",
            [
              "latitude", "longitude", "altitudeMeters", "verticalAccuracyM", "horizontalAccuracyM", "speedMps",
              "speedAccuracyMps",
            ]) + typed("l", ["courseDegrees", "courseAccuracyDegrees"], fit: false)
          + [c("distanceBarrier", .number, .observation, flag("distanceBarrier"))]),
      ExportProjection(
        name: "gpsDiscovery", kind: "location", kinds: ["fit"], typed: ("locations", "l"),
        columns: [elapsed, producer] + typed("l", ["horizontalAccuracyM"])),
      ExportProjection(
        name: "healthZip", kind: "health", kinds: ["zip"], typed: ("health_samples", "h"),
        columns: [elapsed, timestamp, producer, sequence, epoch]
          + typed(
            "h",
            [
              "heartRateBpm", "activeEnergyKcal", "basalEnergyKcal", "distanceMeters", "riderPowerW", "cadenceRpm",
              "speedMps",
            ])
          + [
            c("identifier", .text, .typed, "h.identifier"), c("value", .number, .typed, "h.value"),
            c("unit", .text, .typed, "h.unit"), representation, c("sampleCount", .number, .typed, "h.sampleCount"),
            c("sampleStart", .text, .typed, "h.start_timestamp"), c("sampleUUID", .text, .typed, "h.external_id"),
            c("sourceBundleIdentifier", .text, .observation, json("sourceBundleIdentifier")),
          ]),
      ExportProjection(
        name: "healthFit", kind: "health", kinds: ["fit"], typed: ("health_samples", "h"),
        columns: [
          elapsed, producer, sequence, epoch, c("connectionEpoch", .text, .observation, json("connectionEpoch")),
          representation,
        ] + typed("h", ["sampleCount", "heartRateBpm", "activeEnergyKcal"])),
      ExportProjection(
        name: "lifecycle", kind: "lifecycle", kinds: ["zip", "fit"], typed: ("lifecycle_records", "lc"),
        columns: [
          elapsed, timestamp, producer, sequence, epoch, c("action", .text, .typed, "lc.action"),
          c("timerSeconds", .number, .observation, json("timerSeconds"), fit: false),
          c("interrupted", .number, .observation, flag("interrupted")),
          c("cycSequence", .decimal, .observation, json("cycSequence")),
        ]),
    ].map { ($0.name, $0) })
}
