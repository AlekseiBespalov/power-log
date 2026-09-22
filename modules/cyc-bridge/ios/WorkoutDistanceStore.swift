import Foundation

/// Disposable, indexed profiles. Computation and JSON work stay outside the capture executor;
/// each database closure admits at most one bounded input/output page.
final class WorkoutDistanceStore {
  static let endBoundQuery = "SELECT elapsed_seconds AS end FROM collection_memberships INDEXED BY membership_time WHERE collection_id=? AND revision<=? ORDER BY elapsed_seconds DESC,observation_id DESC LIMIT 1"
  private let store: PowerLogStore
  private static let admission = NSLock()
  private static var building = Set<String>()
  /// Test instrumentation counts relevant input pages; indexed derived reads never invoke it.
  static var inputPageObserverForTesting: ((Int) -> Void)?
  static var reportRevisionObserverForTesting: ((Int64, Int64) -> Void)?
  private var encoder: JSONEncoder { WorkoutCoding.encoder() }
  private var decoder: JSONDecoder { JSONDecoder() }
  private let pageSize = 128 // at most two derived boundary points per input
  private struct Profile: Codable {
    var gps = WorkoutGPSDistanceAccumulator()
    var controller = WorkoutControllerDistanceAccumulator()
    var total = 0.0, coverage = 0.0
    var count = 0
    var lastTime = -1.0
    var lastObservation: Int64 = 0
    var lastSegment: Int? = nil
    var lastEnd = -1.0
    var lastStart: Double? = nil
    var lastHealthClusterEnd = -1.0
    var legacy = false
  }
  private struct Report: Codable {
    var meters: Double, elapsed: Double
    var timestamp: String
    var observationID: Int64
  }
  private struct State: Codable {
    var revision: Int64 = 0
    var generation = UUID().uuidString.lowercased()
    var storageID: Int64 = 0
    var fingerprint = ""
    var reports: [String: Report] = [:]
    var profiles = Dictionary(uniqueKeysWithValues: WorkoutDistancePolicy.sources.map { ($0, Profile()) })
    var maximumPointID: Int64 = 0
  }
  private struct Context {
    var id: String, revision: Int64, kind: String, started: String
    var metadata: [String: Any]
    var end: Double, active: [[Double]], owner: String, indoor: Bool
    var fingerprint: String
    var activeSeconds: Double { active.reduce(0) { $0 + $1[1] - $1[0] } }
    func activeInterval(_ time: Double) -> Int? { active.firstIndex { time >= $0[0] && time <= $0[1] } }
  }
  init(store: PowerLogStore) { self.store = store }

  func cachedSnapshot(id: String, revision: Int64, selection: String = "auto") throws -> WorkoutDistanceSnapshot? {
    try validate(selection)
    let value = try store.read { db -> Data? in
      try store.requireWorkoutAvailable(id: id)
      return try db.rows("SELECT value FROM distance_snapshots WHERE collection_id=? AND revision=? AND policy=?", [.text(id), .integer(revision), .integer(Int64(WorkoutDistancePolicy.version))], limit: 1).first?.data("value")
    }
    guard let value else { return nil }
    return select(try decoder.decode(WorkoutDistanceSnapshot.self, from: value), selection)
  }
  func latestCachedSnapshot(id: String, selection: String = "auto") throws -> WorkoutDistanceSnapshot? {
    try validate(selection)
    let value = try store.read { db -> Data? in
      try store.requireWorkoutAvailable(id: id)
      return try db.rows("SELECT value FROM distance_snapshots WHERE collection_id=? AND policy=? ORDER BY revision DESC LIMIT 1", [.text(id), .integer(Int64(WorkoutDistancePolicy.version))], limit: 1).first?.data("value")
    }
    return try value.map { select(try decoder.decode(WorkoutDistanceSnapshot.self, from: $0), selection) }
  }
  func snapshot(id: String, revision: Int64? = nil, selection: String = "auto") throws -> WorkoutDistanceSnapshot {
    try validate(selection)
    let target = try revision ?? store.collection(id: id).int("revision")!
    if let cached = try cachedSnapshot(id: id, revision: target, selection: selection) { return cached }
    let key = store.databaseURL.path + ":" + id
    Self.admission.lock(); let acquired = Self.building.insert(key).inserted; Self.admission.unlock()
    guard acquired else { throw WorkoutDistanceError.pending }
    defer { Self.admission.lock(); Self.building.remove(key); Self.admission.unlock() }
    let context = try context(id: id, revision: target)
    var state = try previousState(id: id, before: target)
    if let old = state, !(try canAppend(old, context)) { state = nil }
    var next = state ?? State()
    next.fingerprint = context.fingerprint
    if next.storageID == 0 {
      try prune(id: id)
      let initial = try encoder.encode(next)
      next.storageID = try store.transaction(priority: .background) { db in
        try store.requireWorkoutAvailable(id: id)
        try db.execute("INSERT INTO distance_generations(collection_id,generation,revision,policy,state) VALUES(?,?,-1,?,?)", [.text(id), .text(next.generation), .integer(Int64(WorkoutDistancePolicy.version)), .blob(initial)])
        return db.lastInsertedID
      }
    }
    // A failed page build never publishes a checkpoint. Remove only its uncommitted suffix.
    try removeUnpublished(id: id, generation: next.generation, after: next.maximumPointID)
    let lowerRevision = state?.revision ?? -1
    for source in WorkoutDistancePolicy.sources where !source.hasPrefix("health:") {
      try buildPhysical(source: source, context: context, lowerRevision: lowerRevision, state: &next)
    }
    for source in ["health:watch", "health:phone"] {
      try buildHealth(source: source, context: context, lowerRevision: lowerRevision, state: &next)
    }
    next.revision = target
    var sources: [WorkoutDistanceSource] = []
    for source in WorkoutDistancePolicy.sources {
      let p = next.profiles[source]!
      guard p.count > 0 else { continue }
      let uncovered = max(0, context.activeSeconds - p.coverage)
      sources.append(WorkoutDistanceSource(source: source, label: WorkoutDistancePolicy.label(source), estimated: source == "controller",
        partial: uncovered > 0.001 || p.legacy, coveredSeconds: p.coverage, uncoveredSeconds: uncovered, distanceMeters: p.total))
    }
    // Keep sources in automatic preference order in the persisted header.
    let order = (context.indoor ? [] : ["gps:" + context.owner, "gps:" + other(context.owner)]) + ["health:" + context.owner, "health:" + other(context.owner), "controller"]
    sources.sort { (order.firstIndex(of: $0.source) ?? 99) < (order.firstIndex(of: $1.source) ?? 99) }
    let automatic = order.first { source in sources.contains { $0.source == source } }
    let reported = try healthReported(context, lowerRevision: lowerRevision, state: &next)
    var result = WorkoutDistanceSnapshot(id: id, revision: target, generation: next.generation, storageID: next.storageID, selection: "auto", source: automatic,
      method: automatic.map(WorkoutDistancePolicy.method), estimated: automatic == "controller", totalMeters: sources.first { $0.source == automatic }?.distanceMeters,
      coveredSeconds: sources.first { $0.source == automatic }?.coveredSeconds ?? 0, activeSeconds: context.activeSeconds,
      outcome: automatic == nil ? "unavailable" : "ready", sources: sources, healthReportedMeters: reported.0, healthReportedSource: reported.1,
      healthReportedProvisional: reported.2, healthReportedAt: reported.3, maximumPointID: next.maximumPointID, endSeconds: context.end, activeIntervals: context.active)
    let stateData = try encoder.encode(next), resultData = try encoder.encode(result)
    try store.transaction(priority: .background) { db in
      try store.requireWorkoutAvailable(id: id)
      try db.execute("INSERT INTO distance_generations(collection_id,generation,revision,policy,state) VALUES(?,?,?,?,?) ON CONFLICT(collection_id,generation) DO UPDATE SET revision=excluded.revision,state=excluded.state", [.text(id), .text(next.generation), .integer(target), .integer(Int64(WorkoutDistancePolicy.version)), .blob(stateData)])
      try db.execute("INSERT OR REPLACE INTO distance_snapshots(collection_id,revision,policy,generation,value) VALUES(?,?,?,?,?)", [.text(id), .integer(target), .integer(Int64(WorkoutDistancePolicy.version)), .text(next.generation), .blob(resultData)])
    }
    try prune(id: id)
    try store.read { try require(result, $0) }
    result = select(result, selection)
    return result
  }

  func page(snapshot: WorkoutDistanceSnapshot, after: WorkoutDistanceCursor? = nil, start: Double = 0,
            end: Double = WorkoutDistancePolicy.maximumSeconds, limit: Int = 256) throws -> [WorkoutDistancePoint] {
    guard (1...256).contains(limit), start.isFinite, end.isFinite else { throw WorkoutDistanceError.invalid("Invalid distance page") }
    guard let source = snapshot.source else { return [] }
    let rows = try store.read { db -> [PowerLogRow] in
      try require(snapshot, db)
      return try db.rows("SELECT * FROM distance_points WHERE generation_id=? AND source=? AND point_id<=? AND elapsed_seconds>=? AND elapsed_seconds<=? AND (elapsed_seconds,point_id)>(?,?) ORDER BY elapsed_seconds,point_id LIMIT ?", [.integer(snapshot.storageID), .text(source), .integer(snapshot.maximumPointID), .real(start), .real(end), .real(after?.time ?? -1), .integer(after?.pointID ?? 0), .integer(Int64(limit))], limit: limit)
    }
    return rows.map { point($0, snapshot: snapshot) }
  }
  func neighbor(snapshot: WorkoutDistanceSnapshot, seconds: Double, before: Bool, strict: Bool = false) throws -> WorkoutDistancePoint? {
    guard seconds.isFinite else { throw WorkoutDistanceError.invalid("Invalid distance time") }
    guard let source = snapshot.source else { return nil }
    let comparator = before ? (strict ? "<" : "<=") : (strict ? ">" : ">="), order = before ? "DESC" : "ASC"
    let row = try store.read { db -> PowerLogRow? in
      try require(snapshot, db)
      return try db.rows("SELECT * FROM distance_points WHERE generation_id=? AND source=? AND point_id<=? AND elapsed_seconds\(comparator)? ORDER BY elapsed_seconds \(order),point_id \(order) LIMIT 1", [.integer(snapshot.storageID), .text(source), .integer(snapshot.maximumPointID), .real(seconds)], limit: 1).first
    }
    return row.map { point($0, snapshot: snapshot) }
  }
  func anchor(snapshot: WorkoutDistanceSnapshot, identity: String) throws -> WorkoutDistancePoint? {
    guard let source = snapshot.source else { return nil }
    let prefix = "distance:" + snapshot.generation + ":"
    guard identity.hasPrefix(prefix), let number = Int64(identity.dropFirst(prefix.count)), number > 0, number <= snapshot.maximumPointID else { return nil }
    let row = try store.read { db -> PowerLogRow? in
      try require(snapshot, db)
      return try db.rows("SELECT * FROM distance_points WHERE generation_id=? AND point_id=? AND source=?", [.integer(snapshot.storageID), .integer(number), .text(source)], limit: 1).first
    }
    return row.map { point($0, snapshot: snapshot) }
  }
  private func point(_ row: PowerLogRow, snapshot: WorkoutDistanceSnapshot) -> WorkoutDistancePoint {
    let number = row.int("point_id")!
    return WorkoutDistancePoint(pointID: number, identity: "distance:" + snapshot.generation + ":" + String(number), timestamp: row.string("timestamp")!,
      elapsedSeconds: row.double("elapsed_seconds")!, distanceMeters: row.double("distance")!, incrementMeters: row.double("increment")!,
      startSeconds: row.double("start_seconds")!, endSeconds: row.double("end_seconds")!, segment: Int(row.int("segment")!), startAnchor: row.string("start_anchor")!,
      endAnchor: row.string("end_anchor")!, startSpeed: row.double("start_speed"), endSpeed: row.double("end_speed"), indivisible: row.int("indivisible") == 1,
      cumulativeCoveredSeconds: row.double("covered")!)
  }
  func range(snapshot: WorkoutDistanceSnapshot, start: Double, end: Double) throws -> WorkoutDistanceRange {
    guard start.isFinite, end.isFinite, start <= end else { throw WorkoutDistanceError.invalid("Invalid distance range") }
    guard snapshot.source != nil else { return WorkoutDistanceRange(distanceMeters: nil, coveredSeconds: 0, unresolvedBoundary: false, partial: true) }
    func boundary(_ seconds: Double, lower: Bool) throws -> (Double, Double, Bool) {
      let previous = try neighbor(snapshot: snapshot, seconds: seconds, before: true)
      let next = try neighbor(snapshot: snapshot, seconds: seconds, before: false, strict: true)
      var meters = previous?.distanceMeters ?? 0, covered = previous?.cumulativeCoveredSeconds ?? 0
      guard let next, next.startSeconds < seconds, next.endSeconds > seconds else { return (meters, covered, false) }
      if next.indivisible {
        if lower { meters += next.incrementMeters; covered += next.endSeconds - next.startSeconds }
        return (meters, covered, true)
      }
      let contribution = next.interval.clipped(start: next.startSeconds, end: seconds)
      meters += contribution.distanceMeters ?? 0; covered += contribution.coveredSeconds
      return (meters, covered, false)
    }
    let a = try boundary(start, lower: true), b = try boundary(end, lower: false)
    let coverage = max(0, b.1 - a.1), unresolved = a.2 || b.2
    let active = snapshot.activeIntervals.reduce(0) { $0 + max(0, min(end, $1[1]) - max(start, $1[0])) }
    return WorkoutDistanceRange(distanceMeters: max(0, b.0 - a.0), coveredSeconds: coverage, unresolvedBoundary: unresolved,
      partial: unresolved || active - coverage > 0.001)
  }

  private func validate(_ selection: String) throws {
    guard selection == "auto" || WorkoutDistancePolicy.sources.contains(selection) else { throw WorkoutDistanceError.invalid("Unknown distance source") }
  }
  private func select(_ input: WorkoutDistanceSnapshot, _ selection: String) -> WorkoutDistanceSnapshot {
    guard selection != "auto" else { return input }
    var result = input; let chosen = input.sources.first { $0.source == selection }
    result.selection = selection; result.source = chosen?.source; result.method = chosen.map { WorkoutDistancePolicy.method($0.source) }
    result.totalMeters = chosen?.distanceMeters; result.coveredSeconds = chosen?.coveredSeconds ?? 0
    result.estimated = chosen?.estimated ?? false; result.outcome = chosen == nil ? "unavailable" : "ready"
    return result
  }
  private func require(_ snapshot: WorkoutDistanceSnapshot, _ db: PowerLogDatabase) throws {
    try store.requireWorkoutAvailable(id: snapshot.id)
    guard snapshot.policyVersion == WorkoutDistancePolicy.version,
      try db.scalarInt("SELECT 1 FROM distance_generations WHERE collection_id=? AND generation=?", [.text(snapshot.id), .text(snapshot.generation)]) != nil else { throw WorkoutDistanceError.expired }
  }
  private func other(_ source: String) -> String { source == "watch" ? "phone" : "watch" }
  private func previousState(id: String, before revision: Int64) throws -> State? {
    let data = try store.read { db in try db.rows("SELECT state FROM distance_generations WHERE collection_id=? AND policy=? AND revision>=0 AND revision<? ORDER BY revision DESC LIMIT 1", [.text(id), .integer(Int64(WorkoutDistancePolicy.version)), .integer(revision)], limit: 1).first?.data("state") }
    return try data.map { try decoder.decode(State.self, from: $0) }
  }
  private func context(id: String, revision: Int64) throws -> Context {
    let row = try store.collection(id: id)
    guard revision <= row.int("revision")!, revision >= 0 else { throw WorkoutDistanceError.expired }
    let meta = try store.read { db in try db.rows("SELECT metadata FROM collection_versions WHERE collection_id=? AND revision<=? ORDER BY revision DESC LIMIT 1", [.text(id), .integer(revision)], limit: 1).first?.data("metadata") }
    let metadata = (try meta.map { try JSONSerialization.jsonObject(with: $0) }) as? [String: Any] ?? [:]
    let started = metadata["startedAt"] as? String ?? row.string("started_at")!
    let bounds = try store.read { db in try db.rows(Self.endBoundQuery, [.text(id), .integer(revision)], limit: 1).first }
    let ended = metadata["endedAt"] as? String
    let end = min(WorkoutDistancePolicy.maximumSeconds, max(0, (metadata["stopElapsedSeconds"] as? Double) ?? ended.flatMap { try? WorkoutCoding.date($0).timeIntervalSince(WorkoutCoding.date(started)) } ?? bounds?.double("end") ?? 0))
    var active: [[Double]] = [], open: Double? = 0, cursor = -1.0, observation: Int64 = 0, lifecycleCount = 0
    while true {
      let rows = try store.read(priority: .background) { db in try db.rows("SELECT m.elapsed_seconds,m.observation_id,l.action FROM collection_memberships m INDEXED BY membership_stream_time JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND (m.elapsed_seconds,m.observation_id)>(?,?) ORDER BY m.elapsed_seconds,m.observation_id LIMIT 256", [.text(id), .integer(revision), .integer(revision), .integer(revision), .real(cursor), .integer(observation)], limit: 256) }
      for item in rows {
        lifecycleCount += 1; guard lifecycleCount <= 10000 else { throw WorkoutDistanceError.invalid("Too many lifecycle boundaries") }
        let time = min(end, max(0, item.double("elapsed_seconds") ?? 0)), action = item.string("action") ?? ""
        if action == "start" && active.isEmpty { open = time }
        else if action == "resume", open == nil { open = time }
        if ["pause", "stop", "discard", "interruption"].contains(action), let a = open { if time > a { active.append([a,time]) }; open = nil }
      }
      guard let last = rows.last else { break }; cursor = last.double("elapsed_seconds")!; observation = last.int("observation_id")!
      if rows.count < 256 { break }
    }
    if let open, end > open { active.append([open,end]) }
    if lifecycleCount == 0 && end > 0 { active = [[0,end]] }
    let keys = ["startedAt", "endedAt", "stopElapsedSeconds", "indoor", "watchEnabled", "healthKitUUID", "distanceSource"]
    let fixed = Dictionary(uniqueKeysWithValues: keys.compactMap { key in metadata[key].map { (key,$0) } })
    let fingerprint = String(data: try JSONSerialization.data(withJSONObject: fixed, options: [.sortedKeys]), encoding: .utf8)!
    return Context(id: id, revision: revision, kind: row.string("kind")!, started: started, metadata: metadata, end: end, active: active,
      owner: metadata["watchEnabled"] as? Bool == true ? "watch" : "phone", indoor: metadata["indoor"] as? Bool ?? false, fingerprint: fingerprint)
  }
  private func canAppend(_ state: State, _ context: Context) throws -> Bool {
    guard state.fingerprint == context.fingerprint else { return false }
    let count = try store.read(priority: .background) { db in
      try db.scalarInt("SELECT count(*) FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=?", [.text(context.id), .integer(state.revision), .integer(context.revision)]) ?? 0
    }
    guard count == context.revision - state.revision, count <= 512 else { return false }
    var cursorRevision = state.revision, cursorID: Int64 = 0
    while true {
      let rows = try store.read(priority: .background) { db in
        try db.rows("SELECT m.id,m.revision,m.kind,m.source,m.elapsed_seconds,m.deleted,o.extra,o.representation,o.original_timestamp FROM collection_memberships m INDEXED BY membership_snapshot JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.revision>? AND m.revision<=? AND (m.revision,m.id)>(?,?) ORDER BY m.revision,m.id LIMIT 128", [.text(context.id), .integer(state.revision), .integer(context.revision), .integer(cursorRevision), .integer(cursorID)], limit: 128)
      }
      for row in rows {
        let extra = try decoder.decode([String: WorkoutJSON].self, from: row.data("extra")!)
        if row.string("kind") == "lifecycle" || row.int("deleted") == 1 || extra["supersedesEventId"] != nil || row.string("representation") == "workoutAssociation" { return false }
        let source: String
        if row.string("kind") == "location" { source = "gps:" + (row.string("source") ?? "") }
        else if row.string("kind") == "telemetry" { source = "controller" }
        else {
          if extra["healthKitIdentifier"]?.string == "HKQuantityTypeIdentifierDistanceCycling",
             let start = extra["sampleStart"]?.string, let date = try? WorkoutCoding.date(start),
             let timestamp = row.string("original_timestamp"), let original = try? WorkoutCoding.date(timestamp),
             let p = state.profiles["health:" + (row.string("source") ?? "")],
             (row.double("elapsed_seconds") ?? 0) + date.timeIntervalSince(original) < p.lastHealthClusterEnd { return false }
          if let p = state.profiles["health:" + (row.string("source") ?? "")], (row.double("elapsed_seconds") ?? -1) <= p.lastTime { return false }
          continue
        }
        if let p = state.profiles[source], (row.double("elapsed_seconds") ?? -1) <= p.lastTime { return false }
      }
      guard let last = rows.last else { return true }
      cursorRevision = last.int("revision")!; cursorID = last.int("id")!
      if rows.count < 128 { return true }
    }
  }

  private func buildPhysical(source: String, context: Context, lowerRevision: Int64, state: inout State) throws {
    let gps = source.hasPrefix("gps:"), kind = gps ? "location" : "telemetry", originalSource = gps ? String(source.dropFirst(4)) : "cyc"
    let table = gps ? "locations" : "telemetry_frames"
    var profile = state.profiles[source]!
    while true {
      let rows = try store.read(priority: .background) { db in
        try store.requireWorkoutAvailable(id: context.id)
        return try db.rows("SELECT m.elapsed_seconds,m.observation_id,m.event_id,m.mapping,o.extra,o.original_timestamp,o.clock_epoch,o.monotonic_seconds,t.* FROM collection_memberships m JOIN observations o ON o.id=m.observation_id JOIN \(table) t ON t.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind=? AND m.source=? AND m.revision>? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND (m.elapsed_seconds,m.observation_id)>(?,?) ORDER BY m.elapsed_seconds,m.observation_id LIMIT ?", [.text(context.id), .text(kind), .text(originalSource), .integer(lowerRevision), .integer(context.revision), .integer(context.revision), .integer(context.revision), .real(profile.lastTime), .integer(profile.lastObservation), .integer(Int64(pageSize))], limit: pageSize)
      }
      if !rows.isEmpty { Self.inputPageObserverForTesting?(rows.count) }
      var points: [WorkoutDistancePoint] = []
      for row in rows {
        let extra = try decoder.decode([String: WorkoutJSON].self, from: row.data("extra")!)
        let time = row.double("elapsed_seconds")!, anchor = row.string("event_id")!, timestamp = row.string("original_timestamp")!
        let interval: WorkoutDistanceInterval?
        if gps {
          interval = profile.gps.append(WorkoutGPSFix(time: time, latitude: row.double("latitude") ?? .nan, longitude: row.double("longitude") ?? .nan,
            horizontalAccuracy: row.double("horizontalAccuracyM") ?? .nan, speed: row.double("speedMps"),
            speedAccuracy: row.double("speedAccuracyMps") ?? extra["speedAccuracyMps"]?.number, epoch: row.string("clock_epoch"),
            activeInterval: context.activeInterval(time), identity: anchor, timestamp: timestamp,
            barrier: (extra["distanceBarrier"] == .bool(true) || extra["distanceContinuityBarrier"] == .bool(true)) || extra["clockDiscontinuitySeconds"] != nil))
        } else {
          let identity = extra["controllerIdentity"]?.string ?? extra["peripheralId"]?.string ?? extra["captureSessionID"]?.string
          let connection = extra["connectionEpoch"]?.string
          if connection == nil { profile.legacy = true }
          let continuity = (connection ?? extra["captureSessionID"]?.string).map { $0 + ":" + (row.string("clock_epoch") ?? "unknown-epoch") }
          interval = profile.controller.append(WorkoutControllerDistanceSample(time: time, speed: row.double("controllerSpeedMps"),
            model: extra["controllerModel"]?.string, controllerProtocol: extra["controllerProtocol"]?.string, identity: identity,
            continuity: continuity, activeInterval: context.activeInterval(time), anchor: anchor, timestamp: timestamp,
            monotonic: row.double("monotonic_seconds"), counter: extra["observationSequence"]?.string.flatMap(Double.init),
            barrier: extra["clockDiscontinuitySeconds"] != nil || (extra["distanceBarrier"] == .bool(true) || extra["distanceContinuityBarrier"] == .bool(true))))
        }
        if let interval { emit(interval, source: source, state: &state, profile: &profile, points: &points) }
        profile.lastTime = time; profile.lastObservation = row.int("observation_id")!
      }
      try write(points, id: context.id, generation: state.generation, source: source)
      if rows.count < pageSize { break }
    }
    state.profiles[source] = profile
  }
  private func emit(_ interval: WorkoutDistanceInterval, source: String, state: inout State, profile: inout Profile, points: inout [WorkoutDistancePoint]) {
    func point(_ time: Double, _ timestamp: String, _ increment: Double, _ start: Double, _ end: Double, _ total: Double, _ covered: Double) -> WorkoutDistancePoint {
      state.maximumPointID += 1
      return WorkoutDistancePoint(pointID: state.maximumPointID, identity: "distance:" + state.generation + ":" + String(state.maximumPointID), timestamp: timestamp,
        elapsedSeconds: time, distanceMeters: total, incrementMeters: increment, startSeconds: start, endSeconds: end, segment: interval.segment,
        startAnchor: interval.startAnchor, endAnchor: interval.endAnchor, startSpeed: interval.startSpeed, endSpeed: interval.endSpeed,
        indivisible: interval.indivisible, cumulativeCoveredSeconds: covered)
    }
    if profile.lastSegment != interval.segment || profile.lastEnd != interval.startSeconds {
      points.append(point(interval.startSeconds, interval.startTimestamp, 0, interval.startSeconds, interval.startSeconds, profile.total, profile.coverage))
    }
    profile.total += interval.meters; profile.coverage += interval.coveredSeconds; profile.count += 1
    points.append(point(interval.endSeconds, interval.endTimestamp, interval.meters, interval.startSeconds, interval.endSeconds, profile.total, profile.coverage))
    profile.lastSegment = interval.segment; profile.lastStart = interval.startSeconds; profile.lastEnd = interval.endSeconds
  }
  private func write(_ points: [WorkoutDistancePoint], id: String, generation: String, source: String) throws {
    guard !points.isEmpty else { return }
    try store.transaction(priority: .background) { db in
      try store.requireWorkoutAvailable(id: id)
      guard let generationID = try db.scalarInt("SELECT storage_id FROM distance_generations WHERE collection_id=? AND generation=?", [.text(id), .text(generation)]) else { throw WorkoutDistanceError.expired }
      for point in points {
        try db.execute("INSERT INTO distance_points(generation_id,source,point_id,elapsed_seconds,timestamp,distance,increment,start_seconds,end_seconds,segment,start_anchor,end_anchor,start_speed,end_speed,indivisible,covered) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", [.integer(generationID), .text(source), .integer(point.pointID), .real(point.elapsedSeconds), .text(point.timestamp), .real(point.distanceMeters), .real(point.incrementMeters), .real(point.startSeconds), .real(point.endSeconds), .integer(Int64(point.segment)), .text(point.startAnchor), .text(point.endAnchor), .optional(point.startSpeed), .optional(point.endSpeed), .integer(point.indivisible ? 1 : 0), .real(point.cumulativeCoveredSeconds)])
      }
    }
  }
  private func buildHealth(source: String, context: Context, lowerRevision: Int64, state: inout State) throws {
    let originalSource = String(source.dropFirst(7))
    let inputIndex = lowerRevision < 0 ? "membership_stream_time" : "membership_snapshot"
    var profile = state.profiles[source]!
    // Temporary numeric ordering avoids both whole-stream arrays and lossy SQL date parsing.
    try deletePages(table: "distance_health_inputs", id: context.id, condition: " AND generation=? AND source=?", values: [.text(state.generation), .text(source)])
    while true {
      let rows = try store.read(priority: .background) { db in
        try store.requireWorkoutAvailable(id: context.id)
        return try db.rows("SELECT m.elapsed_seconds,m.observation_id,m.event_id,o.extra,o.original_timestamp,o.representation,h.* FROM collection_memberships m INDEXED BY \(inputIndex) JOIN health_samples h ON h.observation_id=m.observation_id JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.kind='health' AND m.source=? AND m.revision>? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND h.identifier='HKQuantityTypeIdentifierDistanceCycling' AND (m.elapsed_seconds,m.observation_id)>(?,?) ORDER BY m.elapsed_seconds,m.observation_id LIMIT ?", [.text(context.id), .text(originalSource), .integer(lowerRevision), .integer(context.revision), .integer(context.revision), .integer(context.revision), .real(profile.lastTime), .integer(profile.lastObservation), .integer(Int64(pageSize))], limit: pageSize)
      }
      if !rows.isEmpty { Self.inputPageObserverForTesting?(rows.count) }
      var inputs: [(Int64, WorkoutDistanceInterval, Data)] = []
      for row in rows {
        let extra = try decoder.decode([String: WorkoutJSON].self, from: row.data("extra")!)
        profile.lastTime = row.double("elapsed_seconds")!; profile.lastObservation = row.int("observation_id")!
        guard let startString = row.string("start_timestamp"), let endString = row.string("end_timestamp"), let value = row.double("value"),
          let startDate = try? WorkoutCoding.date(startString), let endDate = try? WorkoutCoding.date(endString) else { continue }
        let original = try WorkoutCoding.date(row.string("original_timestamp")!)
        let start = profile.lastTime + startDate.timeIntervalSince(original), end = profile.lastTime + endDate.timeIntervalSince(original)
        let associated = try associated(row, extra: extra, context: context, source: originalSource)
        let input = WorkoutHealthDistanceInput(start: start, end: end, meters: value, identifier: row.string("identifier") ?? "", unit: row.string("unit") ?? "",
          representation: row.string("representation") ?? "", sampleCount: row.double("sampleCount"), associated: associated,
          anchor: row.string("event_id")!, startTimestamp: startString, endTimestamp: endString)
        if let interval = input.interval(activeIntervals: context.active) { inputs.append((profile.lastObservation, interval, try encoder.encode(interval))) }
      }
      try store.transaction(priority: .background) { db in
        try store.requireWorkoutAvailable(id: context.id)
        for (inputID, input, data) in inputs {
          try db.execute("INSERT INTO distance_health_inputs(collection_id,generation,source,input_id,start_seconds,end_seconds,value) VALUES(?,?,?,?,?,?,?)", [.text(context.id), .text(state.generation), .text(source), .integer(inputID), .real(input.startSeconds), .real(input.endSeconds), .blob(data)])
        }
      }
      if rows.count < pageSize { break }
    }
    var cursor = -1.0, inputID: Int64 = 0, pending: WorkoutDistanceInterval?, clusterEnd = -1.0, overlapping = false
    func segment(_ input: WorkoutDistanceInterval) -> WorkoutDistanceInterval {
      var interval = input
      let adjacent = profile.lastEnd == interval.startSeconds && profile.lastStart.map { previousStart in
        context.active.contains { previousStart >= $0[0] && interval.endSeconds <= $0[1] }
      } == true
      interval.segment = adjacent ? (profile.lastSegment ?? 0) : (profile.lastSegment ?? -1) + 1
      return interval
    }
    while true {
      let rows = try store.read(priority: .background) { db in try db.rows("SELECT input_id,start_seconds,value FROM distance_health_inputs WHERE collection_id=? AND generation=? AND source=? AND (start_seconds,input_id)>(?,?) ORDER BY start_seconds,input_id LIMIT 128", [.text(context.id), .text(state.generation), .text(source), .real(cursor), .integer(inputID)], limit: 128) }
      var points: [WorkoutDistancePoint] = []
      for row in rows {
        let interval = try decoder.decode(WorkoutDistanceInterval.self, from: row.data("value")!)
        if let old = pending {
          if interval.startSeconds < clusterEnd { overlapping = true; clusterEnd = max(clusterEnd, interval.endSeconds) }
          else {
            if !overlapping { emit(old, source: source, state: &state, profile: &profile, points: &points) }
            pending = segment(interval); clusterEnd = interval.endSeconds; overlapping = false
          }
        } else { pending = segment(interval); clusterEnd = interval.endSeconds }
        cursor = row.double("start_seconds")!; inputID = row.int("input_id")!
      }
      if rows.count < 128 {
        if let pending, !overlapping { emit(pending, source: source, state: &state, profile: &profile, points: &points) }
        try write(points, id: context.id, generation: state.generation, source: source); break
      }
      try write(points, id: context.id, generation: state.generation, source: source)
    }
    profile.lastHealthClusterEnd = max(profile.lastHealthClusterEnd, clusterEnd)
    state.profiles[source] = profile
    try deletePages(table: "distance_health_inputs", id: context.id, condition: " AND generation=? AND source=?", values: [.text(state.generation), .text(source)])
  }
  private func associated(_ row: PowerLogRow, extra: [String: WorkoutJSON], context: Context, source: String) throws -> Bool {
    let expected = (context.metadata["healthKitUUID"] as? String)?.lowercased()
    if let direct = extra["associatedWorkoutUUID"]?.string?.lowercased(), direct == expected { return true }
    guard let sample = row.string("external_id"), let expected else { return false }
    return try store.read(priority: .background) { db in
      try db.scalarInt("SELECT 1 FROM health_samples h INDEXED BY health_external CROSS JOIN observations o ON o.id=h.observation_id CROSS JOIN collection_memberships m INDEXED BY membership_observation ON m.observation_id=o.id WHERE h.external_id=? AND m.collection_id=? AND m.source=? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND o.representation='workoutAssociation' AND lower(json_extract(CAST(o.extra AS TEXT),'$.associatedWorkoutUUID'))=? LIMIT 1", [.text(sample), .text(context.id), .text(source), .integer(context.revision), .integer(context.revision), .integer(context.revision), .text(expected)]) != nil
    }
  }
  private func healthReported(_ context: Context, lowerRevision: Int64, state: inout State) throws -> (Double?, String?, Bool, String?) {
    // A cold reverse scan chooses the initial report. Every subsequent admitted append
    // searches only its <=512 new memberships, including the common absent-final case.
    let index = lowerRevision < 0 ? "membership_stream_time" : "membership_snapshot"
    for final in [true, false] {
      for source in [context.owner, other(context.owner)] {
        let key = source + (final ? ":final" : ":provisional")
        let exists = try store.read { db in try db.scalarInt("SELECT 1 FROM collection_channels WHERE collection_id=? AND metric='distanceMeters' AND source=? LIMIT 1", [.text(context.id), .text(source)]) != nil }
        guard exists else { continue }
        Self.reportRevisionObserverForTesting?(lowerRevision, context.revision)
        let row = try store.read(priority: .background) { db in
          try db.rows("SELECT h.distanceMeters,m.elapsed_seconds,m.observation_id,o.original_timestamp FROM collection_memberships m INDEXED BY \(index) JOIN observations o ON o.id=m.observation_id JOIN health_samples h ON h.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='health' AND m.source=? AND m.revision>? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND h.distanceMeters>=0 AND (?=1 OR m.raw_heart=0) AND o.representation \(final ? "IN ('finalWorkoutTotal','finalTotal')" : "IN ('','builderSnapshot','builderStatistics','cumulativeWorkoutTotal')") AND (?=1 OR m.elapsed_seconds<=?) ORDER BY m.elapsed_seconds DESC,m.observation_id DESC LIMIT 1", [.text(context.id), .text(source), .integer(lowerRevision), .integer(context.revision), .integer(context.revision), .integer(context.revision), .integer(final ? 1 : 0), .integer(final ? 1 : 0), .real(context.end)], limit: 1).first
        }
        if let row, let value = row.double("distanceMeters"), value.isFinite {
          let report = Report(meters: value, elapsed: row.double("elapsed_seconds")!, timestamp: row.string("original_timestamp")!, observationID: row.int("observation_id")!)
          if let old = state.reports[key], old.elapsed > report.elapsed || (old.elapsed == report.elapsed && old.observationID > report.observationID) { continue }
          state.reports[key] = report
        }
      }
    }
    for final in [true, false] {
      for source in [context.owner, other(context.owner)] {
        if let report = state.reports[source + (final ? ":final" : ":provisional")] { return (report.meters, source, !final, report.timestamp) }
      }
    }
    return (nil, nil, false, nil)
  }
  private func removeUnpublished(id: String, generation: String, after pointID: Int64) throws {
    try deletePages(table: "distance_points", id: id, condition: " AND generation=?", values: [.text(generation)], pointAfter: pointID)
  }
  private func deletePages(table: String, id: String, condition: String = "", values: [PowerLogSQLValue] = [], pointAfter: Int64? = nil) throws {
    while true {
      let count = try store.transaction(priority: .background) { db -> Int in
        let predicate = table == "distance_points" ? "generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=?\(condition))" : "collection_id=?\(condition)"
        let suffix = pointAfter == nil ? "" : " AND point_id>?"
        let rows = try db.rows("SELECT rowid AS rid FROM \(table) WHERE \(predicate)\(suffix) LIMIT 256", [.text(id)] + values + (pointAfter.map { [.integer($0)] } ?? []), limit: 256)
        for row in rows { try db.execute("DELETE FROM \(table) WHERE rowid=?", [row["rid"]]) }
        return rows.count
      }
      if count < 256 { break }
    }
  }
  private func prune(id: String) throws {
    // Keep at most three coherent generations and sixteen small revision headers.
    let keep = try store.read { db in try db.rows("SELECT generation FROM distance_generations WHERE collection_id=? ORDER BY revision DESC LIMIT 3", [.text(id)], limit: 3).compactMap { $0.string("generation") } }
    guard !keep.isEmpty else { return }
    let condition = " AND generation NOT IN (" + Array(repeating: "?", count: keep.count).joined(separator: ",") + ")"
    for table in ["distance_points", "distance_health_inputs", "distance_snapshots", "distance_generations"] { try deletePages(table: table, id: id, condition: condition, values: keep.map(PowerLogSQLValue.text)) }
    let cutoff = try store.read { db in try db.rows("SELECT revision FROM distance_snapshots WHERE collection_id=? ORDER BY revision DESC LIMIT 1 OFFSET 15", [.text(id)], limit: 1).first?.int("revision") }
    if let cutoff { try deletePages(table: "distance_snapshots", id: id, condition: " AND revision<?", values: [.integer(cutoff)]) }
  }
}
