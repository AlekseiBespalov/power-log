import Foundation

/// Keeps one generation readable until released, whatever later rebuilds prune.
final class WorkoutDistanceLease {
  let generation: String
  private let key: String
  private var held = true
  fileprivate init(key: String, generation: String) {
    self.key = key
    self.generation = generation
    WorkoutDistanceStore.admission.lock()
    WorkoutDistanceStore.leases[key, default: [:]][generation, default: 0] += 1
    WorkoutDistanceStore.admission.unlock()
  }
  deinit { release() }
  func release() {
    WorkoutDistanceStore.admission.lock()
    defer { WorkoutDistanceStore.admission.unlock() }
    guard held else { return }
    held = false
    let count = (WorkoutDistanceStore.leases[key]?[generation] ?? 1) - 1
    WorkoutDistanceStore.leases[key]?[generation] = count > 0 ? count : nil
    if WorkoutDistanceStore.leases[key]?.isEmpty == true { WorkoutDistanceStore.leases[key] = nil }
  }
}

struct WorkoutDistanceExportInterval {
  var cursor: WorkoutDistanceCursor
  var start: Double
  var end: Double
  var meters: Double
  var segment: Int64
  var startSpeed: Double?
  var endSpeed: Double?
}

/// Disposable, indexed profiles. Computation and JSON work stay outside the capture executor;
/// each database closure examines at most one bounded batch of candidates or writes one bounded batch.
final class WorkoutDistanceStore {
  struct Job {
    let name: String
    let examined: Int
    let steps: Int
    let reprepares: Int
  }
  static let jobRows = 128
  static let writeRows = 1024
  /// Statements carry literal kinds and limits: a bound kind or LIMIT makes SQLite re-plan them on every job.
  static let endBoundQuery =
    "SELECT elapsed_seconds,observation_id,revision<=? FROM collection_memberships INDEXED BY membership_time WHERE collection_id=? AND (elapsed_seconds,observation_id)<(?,?) ORDER BY elapsed_seconds DESC,observation_id DESC LIMIT \(jobRows)"
  static let lifecycleQuery =
    "SELECT m.elapsed_seconds,m.producer,m.sequence,CASE WHEN l.observation_id IS NOT NULL AND m.revision<=? AND \(selected) THEN 1 ELSE 0 END,l.action,o.clock_epoch,json_extract(o.extra,'$.cycSequence'),json_extract(o.extra,'$.interrupted')=1 OR l.action='interruption' FROM collection_memberships m INDEXED BY membership_lifecycle_time JOIN observations o ON o.id=m.observation_id LEFT JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND (m.elapsed_seconds,m.producer,m.sequence)>(?,?,?) ORDER BY m.elapsed_seconds,m.producer,m.sequence LIMIT \(jobRows)"
  static let locationQuery = physicalQuery("location")
  static let telemetryQuery = physicalQuery("telemetry")
  static let healthInputQuery =
    "SELECT m.elapsed_seconds,m.observation_id,CASE WHEN \(distanceSample) AND m.revision>? AND m.revision<=? AND \(selected) THEN 1 ELSE 0 END,\(healthColumns) FROM collection_memberships m INDEXED BY membership_stream_time LEFT JOIN health_samples h ON h.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='health' AND m.source=? AND (m.elapsed_seconds,m.observation_id)>(?,?) ORDER BY m.elapsed_seconds,m.observation_id LIMIT \(jobRows)"
  static let healthAppendQuery =
    "SELECT m.elapsed_seconds,m.observation_id,CASE WHEN m.kind='health' AND m.source=? AND \(distanceSample) AND (m.elapsed_seconds,m.observation_id)>(?,?) AND \(selected) THEN 1 ELSE 0 END,\(healthColumns),m.revision,m.id FROM collection_memberships m INDEXED BY membership_snapshot LEFT JOIN health_samples h ON h.observation_id=m.observation_id WHERE m.collection_id=? AND m.revision<=? AND (m.revision,m.id)>(?,?) ORDER BY m.revision,m.id LIMIT \(jobRows)"
  static let healthObservationQuery =
    "SELECT original_timestamp,representation,json_type(\(text("extra"))),json_extract(\(text("extra")),'$.associatedWorkoutUUID'),extra FROM observations WHERE id=?"
  static let finalReportQuery = reportQuery(final: true, append: false)
  static let provisionalReportQuery = reportQuery(final: false, append: false)
  static let finalReportAppendQuery = reportQuery(final: true, append: true)
  static let provisionalReportAppendQuery = reportQuery(final: false, append: true)
  static let associationQuery =
    "SELECT h.observation_id,CASE WHEN o.representation='workoutAssociation' AND lower(json_extract(\(text("o.extra")),'$.associatedWorkoutUUID'))=? AND EXISTS(SELECT 1 FROM collection_memberships m INDEXED BY membership_observation WHERE m.observation_id=h.observation_id AND m.collection_id=? AND m.source=? AND m.revision<=? AND \(selected)) THEN 1 ELSE 0 END FROM health_samples h INDEXED BY health_external JOIN observations o ON o.id=h.observation_id WHERE h.external_id=? AND h.observation_id>? ORDER BY h.observation_id LIMIT \(jobRows)"
  static let healthInputPageQuery =
    "SELECT input_id,start_seconds,value FROM distance_health_inputs WHERE collection_id=? AND generation=? AND source=? AND (start_seconds,input_id)>(?,?) ORDER BY start_seconds,input_id LIMIT \(jobRows)"
  private static let selected = PowerLogStore.selectedMembershipSQL
  private static let distanceSample = "h.identifier='HKQuantityTypeIdentifierDistanceCycling'"
  private static let healthColumns =
    "m.event_id,h.start_timestamp,h.end_timestamp,h.value,h.unit,h.sampleCount,h.external_id"
  private static func text(_ blob: String) -> String { "CAST(\(blob) AS TEXT)" }
  /// Candidates come in index order; the CASE column carries every other predicate, so rejected rows still
  /// advance the job cursor.
  private static func physicalQuery(_ kind: String) -> String {
    let gps = kind == "location"
    let extra = text("o.extra")
    let values =
      gps
      ? "t.latitude,t.longitude,t.horizontalAccuracyM,t.speedMps,t.speedAccuracyMps,t.speedAccuracyMps IS NULL AND json_type(\(extra),'$.speedAccuracyMps') IN ('integer','real')"
      : "t.controllerSpeedMps,o.monotonic_seconds"
        + ["controllerModel", "controllerProtocol", "captureSessionID", "connectionEpoch", "observationSequence"].map {
          ",json_extract(\(extra),'$.\($0)')"
        }.joined()
    return
      "SELECT m.elapsed_seconds,m.observation_id,m.producer,m.sequence,CASE WHEN t.observation_id IS NOT NULL AND m.revision>? AND m.revision<=? AND \(selected) THEN 1 ELSE 0 END,m.event_id,o.original_timestamp,o.clock_epoch,json_type(\(extra)),json_type(\(extra),'$.distanceBarrier') IS 'true' OR json_type(\(extra),'$.clockDiscontinuitySeconds') IS NOT NULL,o.extra,\(values) FROM collection_memberships m INDEXED BY membership_stream_time JOIN observations o ON o.id=m.observation_id LEFT JOIN \(gps ? "locations" : "telemetry_frames") t ON t.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='\(kind)' AND m.source=? AND (m.elapsed_seconds,m.observation_id)>(?,?) ORDER BY m.elapsed_seconds,m.observation_id LIMIT \(jobRows)"
  }
  private static func reportQuery(final: Bool, append: Bool) -> String {
    let qualifies =
      "h.distanceMeters>=0" + (final ? "" : " AND m.raw_heart=0")
      + " AND (SELECT o.representation FROM observations o WHERE o.id=m.observation_id) IN "
      + (final
        ? "('finalWorkoutTotal','finalTotal')" : "('','builderSnapshot','builderStatistics','cumulativeWorkoutTotal')")
    if append {
      return
        "SELECT m.elapsed_seconds,m.observation_id,CASE WHEN m.kind='health' AND m.source=?\(final ? "" : " AND m.elapsed_seconds<=?") AND \(qualifies) AND \(selected) THEN 1 ELSE 0 END,h.distanceMeters,m.revision,m.id FROM collection_memberships m INDEXED BY membership_snapshot LEFT JOIN health_samples h ON h.observation_id=m.observation_id WHERE m.collection_id=? AND m.revision<=? AND (m.revision,m.id)>(?,?) ORDER BY m.revision,m.id LIMIT \(jobRows)"
    }
    return
      "SELECT m.elapsed_seconds,m.observation_id,CASE WHEN \(qualifies) AND m.revision>? AND m.revision<=? AND \(selected) THEN 1 ELSE 0 END,h.distanceMeters FROM collection_memberships m INDEXED BY membership_stream_time LEFT JOIN health_samples h ON h.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='health' AND m.source=? AND (m.elapsed_seconds,m.observation_id)<(?,?) ORDER BY m.elapsed_seconds DESC,m.observation_id DESC LIMIT \(jobRows)"
  }
  static func exportIntervalQuery(limit: Int) -> String {
    "SELECT point_id,start_seconds,end_seconds,increment,segment,start_speed,end_speed,elapsed_seconds FROM distance_points INDEXED BY distance_point_time WHERE generation_id=? AND source=? AND (elapsed_seconds,point_id)>(?,?) ORDER BY elapsed_seconds,point_id LIMIT \(limit)"
  }
  private let store: PowerLogStore
  fileprivate static let admission = NSLock()
  private static var building = Set<String>()
  fileprivate static var leases: [String: [String: Int]] = [:]
  /// Test instrumentation counts relevant input pages; indexed derived reads never invoke it.
  static var inputPageObserverForTesting: ((Int) -> Void)?
  static var reportRevisionObserverForTesting: ((Int64, Int64) -> Void)?
  /// Test instrumentation sees every bounded read job of a build and every point write.
  static var jobObserverForTesting: ((Job) -> Void)?
  private var encoder: JSONEncoder { WorkoutCoding.encoder() }
  private var decoder: JSONDecoder { JSONDecoder() }
  private struct Profile: Codable {
    var gps = WorkoutGPSDistanceAccumulator()
    var controller = WorkoutControllerDistanceAccumulator()
    var total = 0.0, coverage = 0.0
    var count = 0
    var lastTime = -1.0
    var lastObservation: Int64 = 0
    var lastProducer = ""
    var lastSequence: Int64 = 0
    var lastSegment: Int? = nil
    var plotSegment = 0
    var lastEnd = -1.0
    var lastStart: Double? = nil
    var lastHealthClusterEnd = -1.0
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
    var interruptions: [WorkoutInterruptionBoundary]
    var activeSeconds: Double { active.reduce(0) { $0 + $1[1] - $1[0] } }
    func activeInterval(time: Double, producer: String, sequence: Int64, clockEpoch: String?) -> Int? {
      let resumed = WorkoutInterruptionBoundary.resumedAtBoundary(
        interruptions, time: time, producer: producer, sequence: sequence, clockEpoch: clockEpoch)
      if resumed == true { return active.lastIndex { time >= $0[0] && time <= $0[1] } }
      return active.firstIndex { time >= $0[0] && time <= $0[1] }
    }
  }
  private struct Point {
    var id: Int64
    var time: Double
    var timestamp: String
    var distance: Double
    var increment: Double
    var start: Double
    var end: Double
    var segment: Int
    var startAnchor: String
    var endAnchor: String
    var startSpeed: Double?
    var endSpeed: Double?
    var indivisible: Bool
    var covered: Double
    var plotSegment: Int
  }
  private struct PhysicalInput {
    var time: Double
    var producer: String
    var sequence: Int64
    var epoch: String?
    var fix: WorkoutGPSFix?
    var sample: WorkoutControllerDistanceSample?
    /// Set when a value depends on `extra` decoding that SQL cannot reproduce.
    var extra: Data?
  }
  private struct HealthInput {
    var time: Double
    var observation: Int64
    var anchor: String
    var start: String?
    var end: String?
    var value: Double?
    var unit: String?
    var sampleCount: Double?
    var external: String?
    var original = ""
    var representation = ""
    var association: String?
    var extra: Data?
  }
  init(store: PowerLogStore) { self.store = store }

  func cachedSnapshot(id: String, revision: Int64, selection: String = "auto") throws -> WorkoutDistanceSnapshot? {
    try validate(selection)
    let value = try store.read { db -> Data? in
      try store.requireWorkoutAvailable(id: id)
      return try db.rows(
        "SELECT value FROM distance_snapshots WHERE collection_id=? AND revision=?", [.text(id), .integer(revision)],
        limit: 1
      ).first?.data("value")
    }
    guard let value else { return nil }
    return select(try decoder.decode(WorkoutDistanceSnapshot.self, from: value), selection)
  }
  func latestCachedSnapshot(id: String, selection: String = "auto") throws -> WorkoutDistanceSnapshot? {
    try validate(selection)
    let value = try store.read { db -> Data? in
      try store.requireWorkoutAvailable(id: id)
      return try db.rows(
        "SELECT value FROM distance_snapshots WHERE collection_id=? ORDER BY revision DESC LIMIT 1", [.text(id)],
        limit: 1
      ).first?.data("value")
    }
    return try value.map { select(try decoder.decode(WorkoutDistanceSnapshot.self, from: $0), selection) }
  }
  func snapshot(id: String, revision: Int64? = nil, selection: String = "auto") throws -> WorkoutDistanceSnapshot {
    try validate(selection)
    let target = try revision ?? store.collection(id: id).int("revision")!
    if let cached = try cachedSnapshot(id: id, revision: target, selection: selection) { return cached }
    return try admitted(id: id) { try build(id: id, target: target, selection: selection, leased: false).snapshot }
  }
  /// Prunes of a ride run only inside its admission, so a lease taken here cannot race one.
  func leasedSnapshot(id: String, revision: Int64, selection: String = "auto") throws -> (
    snapshot: WorkoutDistanceSnapshot, lease: WorkoutDistanceLease
  ) {
    try validate(selection)
    return try admitted(id: id) {
      if let cached = try cachedSnapshot(id: id, revision: revision, selection: selection) {
        let lease = WorkoutDistanceLease(key: key(id), generation: cached.generation)
        do {
          try store.read { try require(cached, $0) }
          return (cached, lease)
        } catch WorkoutDistanceError.expired { lease.release() }
      }
      let built = try build(id: id, target: revision, selection: selection, leased: true)
      return (built.snapshot, built.lease!)
    }
  }
  private func key(_ id: String) -> String { store.databaseURL.path + ":" + id }
  private func admitted<T>(id: String, _ body: () throws -> T) throws -> T {
    let key = key(id)
    Self.admission.lock()
    let acquired = Self.building.insert(key).inserted
    Self.admission.unlock()
    guard acquired else { throw WorkoutDistanceError.pending }
    defer {
      Self.admission.lock()
      Self.building.remove(key)
      Self.admission.unlock()
    }
    return try body()
  }
  private func build(id: String, target: Int64, selection: String, leased: Bool) throws -> (
    snapshot: WorkoutDistanceSnapshot, lease: WorkoutDistanceLease?
  ) {
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
        try db.execute(
          "INSERT INTO distance_generations(collection_id,generation,revision,state) VALUES(?,?,-1,?)",
          [.text(id), .text(next.generation), .blob(initial)])
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
      sources.append(
        WorkoutDistanceSource(
          source: source, label: WorkoutDistancePolicy.label(source), estimated: source == "controller",
          partial: uncovered > 0.001, coveredSeconds: p.coverage, uncoveredSeconds: uncovered, distanceMeters: p.total))
    }
    // Keep sources in automatic preference order in the persisted header.
    let order =
      (context.indoor ? [] : ["gps:" + context.owner, "gps:" + other(context.owner)]) + [
        "health:" + context.owner, "health:" + other(context.owner), "controller",
      ]
    sources.sort { (order.firstIndex(of: $0.source) ?? 99) < (order.firstIndex(of: $1.source) ?? 99) }
    let automatic = order.first { source in sources.contains { $0.source == source } }
    let reported = try healthReported(context, lowerRevision: lowerRevision, state: &next)
    var result = WorkoutDistanceSnapshot(
      id: id, revision: target, generation: next.generation, storageID: next.storageID, selection: "auto",
      source: automatic,
      method: automatic.map(WorkoutDistancePolicy.method), estimated: automatic == "controller",
      totalMeters: sources.first { $0.source == automatic }?.distanceMeters,
      coveredSeconds: sources.first { $0.source == automatic }?.coveredSeconds ?? 0,
      activeSeconds: context.activeSeconds,
      outcome: automatic == nil ? "unavailable" : "ready", sources: sources, healthReportedMeters: reported.0,
      healthReportedSource: reported.1,
      healthReportedProvisional: reported.2, healthReportedAt: reported.3, maximumPointID: next.maximumPointID,
      endSeconds: context.end, activeIntervals: context.active)
    let stateData = try encoder.encode(next)
    let resultData = try encoder.encode(result)
    try store.transaction(priority: .background) { db in
      try store.requireWorkoutAvailable(id: id)
      try db.execute(
        "INSERT INTO distance_generations(collection_id,generation,revision,state) VALUES(?,?,?,?) ON CONFLICT(collection_id,generation) DO UPDATE SET revision=excluded.revision,state=excluded.state",
        [.text(id), .text(next.generation), .integer(target), .blob(stateData)])
      try db.execute(
        "INSERT OR REPLACE INTO distance_snapshots(collection_id,revision,generation,value) VALUES(?,?,?,?)",
        [.text(id), .integer(target), .text(next.generation), .blob(resultData)])
    }
    // Otherwise the final prune keeps only the three newest generations, which may all be later than `target`.
    let lease = leased ? WorkoutDistanceLease(key: key(id), generation: next.generation) : nil
    try prune(id: id)
    try store.read { try require(result, $0) }
    result = select(result, selection)
    return (result, lease)
  }

  func page(
    snapshot: WorkoutDistanceSnapshot, after: WorkoutDistanceCursor? = nil, start: Double = 0,
    end: Double = WorkoutDistancePolicy.maximumSeconds, limit: Int = 256
  ) throws -> [WorkoutDistancePoint] {
    guard (1...256).contains(limit), start.isFinite, end.isFinite else {
      throw WorkoutDistanceError.invalid("Invalid distance page")
    }
    guard let source = snapshot.source else { return [] }
    let rows = try store.read { db -> [PowerLogRow] in
      try require(snapshot, db)
      return try db.rows(
        "SELECT * FROM distance_points WHERE generation_id=? AND source=? AND point_id<=? AND elapsed_seconds>=? AND elapsed_seconds<=? AND (elapsed_seconds,point_id)>(?,?) ORDER BY elapsed_seconds,point_id LIMIT ?",
        [
          .integer(snapshot.storageID), .text(source), .integer(snapshot.maximumPointID), .real(start), .real(end),
          .real(after?.time ?? -1), .integer(after?.pointID ?? 0), .integer(Int64(limit)),
        ], limit: limit)
    }
    return rows.map { point($0, snapshot: snapshot) }
  }
  /// Each interval is its end point; zero-length points are segment-start anchors, and points above the
  /// snapshot belong to later appends. `body` returns false to end the job after that interval.
  @discardableResult
  func intervals(
    snapshot: WorkoutDistanceSnapshot, after: WorkoutDistanceCursor?, limit: Int,
    observeWork: ((_ steps: Int, _ reprepares: Int) -> Void)? = nil,
    _ body: (WorkoutDistanceExportInterval) throws -> Bool
  ) throws -> (examined: Int, last: WorkoutDistanceCursor?, stopped: Bool) {
    guard let source = snapshot.source else { return (0, nil, false) }
    return try store.read(priority: .background) { db in
      try require(snapshot, db)
      var last: WorkoutDistanceCursor?
      var stopped = false
      let examined = try db.scan(
        Self.exportIntervalQuery(limit: limit),
        [.integer(snapshot.storageID), .text(source), .real(after?.time ?? -1), .integer(after?.pointID ?? 0)],
        limit: limit, observeWork: observeWork
      ) { row in
        let cursor = WorkoutDistanceCursor(time: row.double(7)!, pointID: row.integer(0)!)
        last = cursor
        let start = row.double(1)!
        let end = row.double(2)!
        guard cursor.pointID <= snapshot.maximumPointID, end > start else { return true }
        stopped = try !body(
          WorkoutDistanceExportInterval(
            cursor: cursor, start: start, end: end, meters: row.double(3)!, segment: row.integer(4)!,
            startSpeed: row.double(5), endSpeed: row.double(6)))
        return !stopped
      }
      return (examined, last, stopped)
    }
  }
  func neighbor(snapshot: WorkoutDistanceSnapshot, seconds: Double, before: Bool, strict: Bool = false) throws
    -> WorkoutDistancePoint?
  {
    guard seconds.isFinite else { throw WorkoutDistanceError.invalid("Invalid distance time") }
    guard let source = snapshot.source else { return nil }
    let comparator = before ? (strict ? "<" : "<=") : (strict ? ">" : ">=")
    let order = before ? "DESC" : "ASC"
    let row = try store.read { db -> PowerLogRow? in
      try require(snapshot, db)
      return try db.rows(
        "SELECT * FROM distance_points WHERE generation_id=? AND source=? AND point_id<=? AND elapsed_seconds\(comparator)? ORDER BY elapsed_seconds \(order),point_id \(order) LIMIT 1",
        [.integer(snapshot.storageID), .text(source), .integer(snapshot.maximumPointID), .real(seconds)], limit: 1
      ).first
    }
    return row.map { point($0, snapshot: snapshot) }
  }
  func anchor(snapshot: WorkoutDistanceSnapshot, identity: String) throws -> WorkoutDistancePoint? {
    guard let source = snapshot.source else { return nil }
    let prefix = "distance:" + snapshot.generation + ":"
    guard identity.hasPrefix(prefix), let number = Int64(identity.dropFirst(prefix.count)), number > 0,
      number <= snapshot.maximumPointID
    else { return nil }
    let row = try store.read { db -> PowerLogRow? in
      try require(snapshot, db)
      return try db.rows(
        "SELECT * FROM distance_points WHERE generation_id=? AND point_id=? AND source=?",
        [.integer(snapshot.storageID), .integer(number), .text(source)], limit: 1
      ).first
    }
    return row.map { point($0, snapshot: snapshot) }
  }
  private func point(_ row: PowerLogRow, snapshot: WorkoutDistanceSnapshot) -> WorkoutDistancePoint {
    let number = row.int("point_id")!
    return WorkoutDistancePoint(
      pointID: number, identity: "distance:" + snapshot.generation + ":" + String(number),
      timestamp: row.string("timestamp")!,
      elapsedSeconds: row.double("elapsed_seconds")!, distanceMeters: row.double("distance")!,
      incrementMeters: row.double("increment")!,
      startSeconds: row.double("start_seconds")!, endSeconds: row.double("end_seconds")!,
      segment: Int(row.int("segment")!), startAnchor: row.string("start_anchor")!,
      endAnchor: row.string("end_anchor")!, startSpeed: row.double("start_speed"), endSpeed: row.double("end_speed"),
      indivisible: row.int("indivisible") == 1,
      cumulativeCoveredSeconds: row.double("covered")!, plotSegment: Int(row.int("plot_segment")!))
  }
  func range(snapshot: WorkoutDistanceSnapshot, start: Double, end: Double) throws -> WorkoutDistanceRange {
    guard start.isFinite, end.isFinite, start <= end else {
      throw WorkoutDistanceError.invalid("Invalid distance range")
    }
    guard snapshot.source != nil else {
      return WorkoutDistanceRange(distanceMeters: nil, coveredSeconds: 0, unresolvedBoundary: false, partial: true)
    }
    func boundary(_ seconds: Double, lower: Bool) throws -> (Double, Double, Bool) {
      let previous = try neighbor(snapshot: snapshot, seconds: seconds, before: true)
      let next = try neighbor(snapshot: snapshot, seconds: seconds, before: false, strict: true)
      var meters = previous?.distanceMeters ?? 0
      var covered = previous?.cumulativeCoveredSeconds ?? 0
      guard let next, next.startSeconds < seconds, next.endSeconds > seconds else { return (meters, covered, false) }
      if next.indivisible {
        if lower {
          meters += next.incrementMeters
          covered += next.endSeconds - next.startSeconds
        }
        return (meters, covered, true)
      }
      let contribution = next.interval.clipped(start: next.startSeconds, end: seconds)
      meters += contribution.distanceMeters ?? 0
      covered += contribution.coveredSeconds
      return (meters, covered, false)
    }
    let a = try boundary(start, lower: true)
    let b = try boundary(end, lower: false)
    let coverage = max(0, b.1 - a.1)
    let unresolved = a.2 || b.2
    let active = snapshot.activeIntervals.reduce(0) { $0 + max(0, min(end, $1[1]) - max(start, $1[0])) }
    return WorkoutDistanceRange(
      distanceMeters: max(0, b.0 - a.0), coveredSeconds: coverage, unresolvedBoundary: unresolved,
      partial: unresolved || active - coverage > 0.001)
  }

  private func validate(_ selection: String) throws {
    guard selection == "auto" || WorkoutDistancePolicy.sources.contains(selection) else {
      throw WorkoutDistanceError.invalid("Unknown distance source")
    }
  }
  private func select(_ input: WorkoutDistanceSnapshot, _ selection: String) -> WorkoutDistanceSnapshot {
    guard selection != "auto" else { return input }
    var result = input
    let chosen = input.sources.first { $0.source == selection }
    result.selection = selection
    result.source = chosen?.source
    result.method = chosen.map { WorkoutDistancePolicy.method($0.source) }
    result.totalMeters = chosen?.distanceMeters
    result.coveredSeconds = chosen?.coveredSeconds ?? 0
    result.estimated = chosen?.estimated ?? false
    result.outcome = chosen == nil ? "unavailable" : "ready"
    return result
  }
  private func require(_ snapshot: WorkoutDistanceSnapshot, _ db: PowerLogDatabase) throws {
    try store.requireWorkoutAvailable(id: snapshot.id)
    guard
      try db.scalarInt(
        "SELECT 1 FROM distance_generations WHERE collection_id=? AND generation=?",
        [.text(snapshot.id), .text(snapshot.generation)]) != nil
    else { throw WorkoutDistanceError.expired }
  }
  private func other(_ source: String) -> String { source == "watch" ? "phone" : "watch" }
  private func previousState(id: String, before revision: Int64) throws -> State? {
    let data = try store.read { db in
      try db.rows(
        "SELECT state FROM distance_generations WHERE collection_id=? AND revision>=0 AND revision<? ORDER BY revision DESC LIMIT 1",
        [.text(id), .integer(revision)], limit: 1
      ).first?.data("state")
    }
    return try data.map { try decoder.decode(State.self, from: $0) }
  }
  private func context(id: String, revision: Int64) throws -> Context {
    let row = try store.collection(id: id)
    guard revision <= row.int("revision")!, revision >= 0 else { throw WorkoutDistanceError.expired }
    let meta = try store.read { db in
      try db.rows(
        "SELECT metadata FROM collection_versions WHERE collection_id=? AND revision<=? ORDER BY revision DESC LIMIT 1",
        [.text(id), .integer(revision)], limit: 1
      ).first?.data("metadata")
    }
    let metadata = (try meta.map { try JSONSerialization.jsonObject(with: $0) }) as? [String: Any] ?? [:]
    let started = metadata["startedAt"] as? String ?? row.string("started_at")!
    let stop = metadata["stopElapsedSeconds"] as? Double
    let end = min(
      WorkoutDistancePolicy.maximumSeconds,
      max(0, try stop ?? max(metadata["elapsedSeconds"] as? Double ?? 0, endBound(id: id, revision: revision) ?? 0)))
    var active: [[Double]] = []
    var open: Double? = 0
    var lifecycleCount = 0
    var interruptions: [WorkoutInterruptionBoundary] = []
    var unretained = false
    var cursor = (time: -1.0, producer: "", sequence: Int64(0))
    while true {
      var rows:
        [(
          time: Double, producer: String, sequence: Int64, action: String?, interruption: Bool, epoch: String?,
          cyc: String?
        )] = []
      let examined = try job(
        "lifecycle", Self.lifecycleQuery,
        [
          .integer(revision), .integer(revision), .integer(revision), .text(id), .real(cursor.time),
          .text(cursor.producer), .integer(cursor.sequence),
        ]
      ) { row in
        cursor = (row.double(0)!, row.text(1)!, row.integer(2)!)
        if row.integer(3) == 1 {
          rows.append(
            (cursor.time, cursor.producer, cursor.sequence, row.text(4), row.integer(7) == 1, row.text(5), row.text(6)))
        }
        return true
      }
      for item in rows {
        lifecycleCount += 1
        guard lifecycleCount <= 10000 else { throw WorkoutDistanceError.invalid("Too many lifecycle boundaries") }
        let time = min(end, max(0, item.time))
        let action = item.action ?? ""
        if action == "start" && active.isEmpty { open = time } else if action == "resume", open == nil { open = time }
        if ["pause", "stop", "discard", "interruption"].contains(action), let a = open {
          if time > a { active.append([a, time]) }
          open = nil
        }
        guard item.interruption else { continue }
        if let retained = item.cyc.flatMap(Int64.init), retained >= 0 {
          interruptions.append(
            WorkoutInterruptionBoundary(
              time: item.time, producer: item.producer, sequence: item.sequence, clockEpoch: item.epoch,
              cycSequence: retained))
        } else {
          unretained = true
        }
      }
      if examined < Self.jobRows { break }
    }
    if let open, end > open { active.append([open, end]) }
    if lifecycleCount == 0 && end > 0 { active = [[0, end]] }
    if unretained { throw WorkoutDataError.invalid("Interruption has no retained telemetry sequence") }
    let keys = [
      "startedAt", "endedAt", "stopElapsedSeconds", "indoor", "watchEnabled", "healthKitUUID", "distanceSource",
    ]
    let fixed = Dictionary(uniqueKeysWithValues: keys.compactMap { key in metadata[key].map { (key, $0) } })
    let fingerprint = String(
      data: try JSONSerialization.data(withJSONObject: fixed, options: [.sortedKeys]), encoding: .utf8)!
    return Context(
      id: id, revision: revision, kind: row.string("kind")!, started: started, metadata: metadata, end: end,
      active: active,
      owner: metadata["watchEnabled"] as? Bool == true ? "watch" : "phone",
      indoor: metadata["indoor"] as? Bool ?? false, fingerprint: fingerprint, interruptions: interruptions)
  }
  /// The largest elapsed time of any membership at the revision, selected or not.
  private func endBound(id: String, revision: Int64) throws -> Double? {
    var cursor = (time: Double.greatestFiniteMagnitude, observation: Int64.max)
    var bound: Double?
    while true {
      let examined = try job(
        "end", Self.endBoundQuery, [.integer(revision), .text(id), .real(cursor.time), .integer(cursor.observation)]
      ) { row in
        cursor = (row.double(0)!, row.integer(1)!)
        if row.integer(2) == 1 { bound = cursor.time }
        return bound == nil
      }
      if bound != nil || examined < Self.jobRows { return bound }
    }
  }
  /// One executor job that examines at most `jobRows` candidates; `body` returns false to stop early.
  private func job(
    _ name: String, _ sql: String, _ values: [PowerLogSQLValue], available id: String? = nil,
    _ body: (PowerLogStatementRow) throws -> Bool, then finish: (PowerLogDatabase) throws -> Void = { _ in }
  ) throws -> Int {
    let observer = Self.jobObserverForTesting
    var work = (steps: 0, reprepares: 0)
    let examined = try store.read(priority: .background) { db -> Int in
      if let id { try store.requireWorkoutAvailable(id: id) }
      let examined = try db.scan(
        sql, values, limit: Self.jobRows, observeWork: observer == nil ? nil : { work = ($0, $1) }, body)
      try finish(db)
      return examined
    }
    observer?(Job(name: name, examined: examined, steps: work.steps, reprepares: work.reprepares))
    return examined
  }
  private func canAppend(_ state: State, _ context: Context) throws -> Bool {
    guard state.fingerprint == context.fingerprint else { return false }
    let count = try store.read(priority: .background) { db in
      try db.scalarInt(
        "SELECT count(*) FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=?",
        [.text(context.id), .integer(state.revision), .integer(context.revision)]) ?? 0
    }
    guard count == context.revision - state.revision, count <= 512 else { return false }
    var cursorRevision = state.revision
    var cursorID: Int64 = 0
    while true {
      let rows = try store.read(priority: .background) { db in
        try db.rows(
          "SELECT m.id,m.revision,m.kind,m.source,m.elapsed_seconds,m.deleted,o.extra,o.representation,o.original_timestamp FROM collection_memberships m INDEXED BY membership_snapshot JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.revision>? AND m.revision<=? AND (m.revision,m.id)>(?,?) ORDER BY m.revision,m.id LIMIT 128",
          [
            .text(context.id), .integer(state.revision), .integer(context.revision), .integer(cursorRevision),
            .integer(cursorID),
          ], limit: 128)
      }
      for row in rows {
        let extra = try decoder.decode([String: WorkoutJSON].self, from: row.data("extra")!)
        if row.string("kind") == "lifecycle" || row.int("deleted") == 1 || extra["supersedesEventId"] != nil
          || row.string("representation") == "workoutAssociation"
        {
          return false
        }
        let source: String
        if row.string("kind") == "location" {
          source = "gps:" + (row.string("source") ?? "")
        } else if row.string("kind") == "telemetry" {
          source = "controller"
        } else {
          if extra["healthKitIdentifier"]?.string == "HKQuantityTypeIdentifierDistanceCycling",
            let start = extra["sampleStart"]?.string, let date = try? WorkoutCoding.date(start),
            let timestamp = row.string("original_timestamp"), let original = try? WorkoutCoding.date(timestamp),
            let p = state.profiles["health:" + (row.string("source") ?? "")],
            (row.double("elapsed_seconds") ?? 0) + date.timeIntervalSince(original) < p.lastHealthClusterEnd
          {
            return false
          }
          if let p = state.profiles["health:" + (row.string("source") ?? "")],
            (row.double("elapsed_seconds") ?? -1) <= p.lastTime
          {
            return false
          }
          continue
        }
        if let p = state.profiles[source], (row.double("elapsed_seconds") ?? -1) <= p.lastTime { return false }
      }
      guard let last = rows.last else { return true }
      cursorRevision = last.int("revision")!
      cursorID = last.int("id")!
      if rows.count < 128 { return true }
    }
  }

  /// The stream index orders a source by (elapsed, observation); inputs are accumulated in the store's tie
  /// order (elapsed, producer, sequence), so each run of equal elapsed times is held until it is complete.
  private func buildPhysical(source: String, context: Context, lowerRevision: Int64, state: inout State) throws {
    let gps = source.hasPrefix("gps:")
    let originalSource = gps ? String(source.dropFirst(4)) : "cyc"
    var profile = state.profiles[source]!
    let resume = (time: profile.lastTime, producer: profile.lastProducer, sequence: profile.lastSequence)
    var cursor = (time: profile.lastTime, observation: Int64.min)
    var tie: [PhysicalInput] = []
    var points: [Point] = []
    func accumulate() throws {
      tie.sort { Self.precedes($0.producer, $0.sequence, $1.producer, $1.sequence) }
      for input in tie {
        let interval = gps ? profile.gps.append(input.fix!) : profile.controller.append(input.sample!)
        if let interval { emit(interval, context: context, state: &state, profile: &profile, points: &points) }
        profile.lastTime = input.time
        profile.lastProducer = input.producer
        profile.lastSequence = input.sequence
      }
      tie.removeAll(keepingCapacity: true)
      if points.count >= Self.writeRows {
        try write(points, id: context.id, generation: state.generation, source: source)
        points.removeAll(keepingCapacity: true)
      }
    }
    while true {
      var inputs: [PhysicalInput] = []
      let examined = try job(
        source, gps ? Self.locationQuery : Self.telemetryQuery,
        [
          .integer(lowerRevision), .integer(context.revision), .integer(context.revision), .integer(context.revision),
          .text(context.id), .text(originalSource), .real(cursor.time), .integer(cursor.observation),
        ], available: context.id
      ) { row in
        cursor = (row.double(0)!, row.integer(1)!)
        if row.integer(4) == 1 {
          let input = Self.physicalInput(row, gps: gps, context: context)
          if Self.follows(input, resume) { inputs.append(input) }
        }
        return true
      }
      if !inputs.isEmpty { Self.inputPageObserverForTesting?(inputs.count) }
      for var input in inputs {
        if let extra = input.extra { try resolve(&input, extra: extra, context: context) }
        if let first = tie.first, first.time != input.time { try accumulate() }
        tie.append(input)
      }
      if examined < Self.jobRows { break }
    }
    try accumulate()
    try write(points, id: context.id, generation: state.generation, source: source)
    state.profiles[source] = profile
  }
  /// Columns of `physicalQuery`: 0 elapsed, 1 observation, 2 producer, 3 sequence, 4 selected, 5 event, 6 timestamp,
  /// 7 clock epoch, 8 `extra` root type, 9 barrier, 10 `extra`, then the kind's values from 11.
  private static func physicalInput(_ row: PowerLogStatementRow, gps: Bool, context: Context) -> PhysicalInput {
    let time = row.double(0)!
    let producer = row.text(2)!
    let sequence = row.integer(3)!
    let epoch = row.text(7)
    let active = context.activeInterval(time: time, producer: producer, sequence: sequence, clockEpoch: epoch)
    var input = PhysicalInput(time: time, producer: producer, sequence: sequence, epoch: epoch)
    let object = row.text(8) == "object"
    if gps {
      input.fix = WorkoutGPSFix(
        time: time, latitude: row.double(11) ?? .nan, longitude: row.double(12) ?? .nan,
        horizontalAccuracy: row.double(13) ?? .nan, speed: row.double(14), speedAccuracy: row.double(15), epoch: epoch,
        activeInterval: active, identity: row.text(5)!, timestamp: row.text(6)!, barrier: row.integer(9) == 1)
      if !object || row.integer(16) == 1 { input.extra = row.data(10) ?? Data() }
    } else {
      let strings = (13...16).map { row.string(Int32($0)) }
      input.sample = WorkoutControllerDistanceSample(
        time: time, speed: row.double(11), model: strings[0], controllerProtocol: strings[1], identity: strings[2],
        continuity: continuity(strings[3], epoch), activeInterval: active, anchor: row.text(5)!,
        timestamp: row.text(6)!, monotonic: row.double(12), counter: row.string(17).flatMap(Double.init),
        barrier: row.integer(9) == 1)
      if !object || strings.contains(where: composite) { input.extra = row.data(10) ?? Data() }
    }
    return input
  }
  /// `json_extract` renders an object or array member as JSON text, which only a leading bracket tells from a string.
  private static func composite(_ value: String?) -> Bool {
    value?.utf8.first == UInt8(ascii: "{") || value?.utf8.first == UInt8(ascii: "[")
  }
  private static func continuity(_ connection: String?, _ epoch: String?) -> String? {
    guard let connection, !connection.isEmpty, let epoch, !epoch.isEmpty else { return nil }
    return connection + ":" + epoch
  }
  private func resolve(_ input: inout PhysicalInput, extra data: Data, context: Context) throws {
    let extra = try decoder.decode([String: WorkoutJSON].self, from: data)
    let barrier = extra["distanceBarrier"] == .bool(true) || extra["clockDiscontinuitySeconds"] != nil
    if var fix = input.fix {
      fix.speedAccuracy = fix.speedAccuracy ?? extra["speedAccuracyMps"]?.number
      fix.barrier = barrier
      input.fix = fix
    } else if var sample = input.sample {
      sample.model = extra["controllerModel"]?.string
      sample.controllerProtocol = extra["controllerProtocol"]?.string
      sample.identity = extra["captureSessionID"]?.string
      sample.continuity = Self.continuity(extra["connectionEpoch"]?.string, input.epoch)
      sample.counter = extra["observationSequence"]?.string.flatMap(Double.init)
      sample.barrier = barrier
      input.sample = sample
    }
  }
  /// The row-value order `(time, producer, sequence) > key` of SQLite, whose BINARY collation compares UTF-8 bytes.
  private static func follows(_ input: PhysicalInput, _ key: (time: Double, producer: String, sequence: Int64)) -> Bool
  {
    if input.time != key.time { return input.time > key.time }
    return precedes(key.producer, key.sequence, input.producer, input.sequence)
  }
  private static func precedes(_ a: String, _ x: Int64, _ b: String, _ y: Int64) -> Bool {
    if a.utf8.elementsEqual(b.utf8) { return x < y }
    return a.utf8.lexicographicallyPrecedes(b.utf8)
  }
  private func emit(
    _ interval: WorkoutDistanceInterval, context: Context, state: inout State, profile: inout Profile,
    points: inout [Point]
  ) {
    func point(
      _ time: Double, _ timestamp: String, _ increment: Double, _ start: Double, _ end: Double, _ total: Double,
      _ covered: Double
    ) -> Point {
      state.maximumPointID += 1
      return Point(
        id: state.maximumPointID, time: time, timestamp: timestamp, distance: total, increment: increment,
        start: start, end: end, segment: interval.segment, startAnchor: interval.startAnchor,
        endAnchor: interval.endAnchor, startSpeed: interval.startSpeed, endSpeed: interval.endSpeed,
        indivisible: interval.indivisible, covered: covered, plotSegment: profile.plotSegment)
    }
    if profile.lastSegment != interval.segment || profile.lastEnd != interval.startSeconds {
      if profile.lastSegment != nil,
        interval.startSeconds - profile.lastEnd >= MonitorDisplayPolicy.reconnectGapSeconds
          || context.interruptions.contains(where: { $0.time >= profile.lastEnd && $0.time <= interval.startSeconds })
      {
        profile.plotSegment += 1
      }
      points.append(
        point(
          interval.startSeconds, interval.startTimestamp, 0, interval.startSeconds, interval.startSeconds,
          profile.total, profile.coverage))
    }
    profile.total += interval.meters
    profile.coverage += interval.coveredSeconds
    profile.count += 1
    points.append(
      point(
        interval.endSeconds, interval.endTimestamp, interval.meters, interval.startSeconds, interval.endSeconds,
        profile.total, profile.coverage))
    profile.lastSegment = interval.segment
    profile.lastStart = interval.startSeconds
    profile.lastEnd = interval.endSeconds
  }
  private func write(_ points: [Point], id: String, generation: String, source: String) throws {
    for first in stride(from: 0, to: points.count, by: Self.writeRows) {
      let batch = points[first..<min(first + Self.writeRows, points.count)]
      try store.transaction(priority: .background) { db in
        try store.requireWorkoutAvailable(id: id)
        guard
          let generationID = try db.scalarInt(
            "SELECT storage_id FROM distance_generations WHERE collection_id=? AND generation=?",
            [.text(id), .text(generation)])
        else { throw WorkoutDistanceError.expired }
        for point in batch {
          try db.execute(
            "INSERT INTO distance_points(generation_id,source,point_id,elapsed_seconds,timestamp,distance,increment,start_seconds,end_seconds,segment,start_anchor,end_anchor,start_speed,end_speed,indivisible,covered,plot_segment) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            [
              .integer(generationID), .text(source), .integer(point.id), .real(point.time), .text(point.timestamp),
              .real(point.distance), .real(point.increment), .real(point.start), .real(point.end),
              .integer(Int64(point.segment)), .text(point.startAnchor), .text(point.endAnchor),
              .optional(point.startSpeed), .optional(point.endSpeed), .integer(point.indivisible ? 1 : 0),
              .real(point.covered), .integer(Int64(point.plotSegment)),
            ])
        }
      }
      Self.jobObserverForTesting?(Job(name: "points", examined: batch.count, steps: 0, reprepares: 0))
    }
  }
  private func buildHealth(source: String, context: Context, lowerRevision: Int64, state: inout State) throws {
    let originalSource = String(source.dropFirst(7))
    var profile = state.profiles[source]!
    // Temporary numeric ordering avoids both whole-stream arrays and lossy SQL date parsing.
    try deletePages(
      "distance_health_inputs", where: "collection_id=? AND generation=? AND source=?",
      [.text(context.id), .text(state.generation), .text(source)])
    func admit(_ rows: [HealthInput]) throws {
      var inputs: [(Int64, WorkoutDistanceInterval, Data)] = []
      for row in rows {
        let association =
          try row.extra.map {
            try decoder.decode([String: WorkoutJSON].self, from: $0)["associatedWorkoutUUID"]?.string
          } ?? row.association
        profile.lastTime = row.time
        profile.lastObservation = row.observation
        guard let startString = row.start, let endString = row.end, let value = row.value,
          let startDate = try? WorkoutCoding.date(startString), let endDate = try? WorkoutCoding.date(endString)
        else { continue }
        let original = try WorkoutCoding.date(row.original)
        let start = profile.lastTime + startDate.timeIntervalSince(original)
        let end = profile.lastTime + endDate.timeIntervalSince(original)
        let associated = try associated(association, sample: row.external, context: context, source: originalSource)
        let input = WorkoutHealthDistanceInput(
          start: start, end: end, meters: value, identifier: "HKQuantityTypeIdentifierDistanceCycling",
          unit: row.unit ?? "", representation: row.representation, sampleCount: row.sampleCount,
          associated: associated, anchor: row.anchor, startTimestamp: startString, endTimestamp: endString)
        if let interval = input.interval(activeIntervals: context.active) {
          inputs.append((profile.lastObservation, interval, try encoder.encode(interval)))
        }
      }
      guard !inputs.isEmpty else { return }
      try store.transaction(priority: .background) { db in
        try store.requireWorkoutAvailable(id: context.id)
        for (inputID, input, data) in inputs {
          try db.execute(
            "INSERT INTO distance_health_inputs(collection_id,generation,source,input_id,start_seconds,end_seconds,value) VALUES(?,?,?,?,?,?,?)",
            [
              .text(context.id), .text(state.generation), .text(source), .integer(inputID), .real(input.startSeconds),
              .real(input.endSeconds), .blob(data),
            ])
        }
      }
    }
    if lowerRevision < 0 {
      var cursor = (time: profile.lastTime, observation: profile.lastObservation)
      while true {
        var rows: [HealthInput] = []
        let examined = try healthCandidates(
          source, Self.healthInputQuery,
          [
            .integer(lowerRevision), .integer(context.revision), .integer(context.revision),
            .integer(context.revision), .text(context.id), .text(originalSource), .real(cursor.time),
            .integer(cursor.observation),
          ], id: context.id, rows: &rows
        ) { row in cursor = (row.double(0)!, row.integer(1)!) }
        if !rows.isEmpty { Self.inputPageObserverForTesting?(rows.count) }
        try admit(rows)
        if examined < Self.jobRows { break }
      }
    } else {
      // An admitted append has at most 512 new memberships; they are ordered once they are all read.
      var cursor = (revision: lowerRevision, id: Int64.max)
      var appended: [HealthInput] = []
      while true {
        var rows: [HealthInput] = []
        let examined = try healthCandidates(
          source, Self.healthAppendQuery,
          [
            .text(originalSource), .real(profile.lastTime), .integer(profile.lastObservation),
            .integer(context.revision), .integer(context.revision), .text(context.id), .integer(context.revision),
            .integer(cursor.revision), .integer(cursor.id),
          ], id: context.id, rows: &rows
        ) { row in cursor = (row.integer(10)!, row.integer(11)!) }
        if !rows.isEmpty { Self.inputPageObserverForTesting?(rows.count) }
        appended += rows
        if examined < Self.jobRows { break }
      }
      appended.sort { ($0.time, $0.observation) < ($1.time, $1.observation) }
      for first in stride(from: 0, to: appended.count, by: Self.jobRows) {
        try admit(Array(appended[first..<min(first + Self.jobRows, appended.count)]))
      }
    }
    var cursor = -1.0
    var inputID: Int64 = 0
    var pending: WorkoutDistanceInterval?
    var clusterEnd = -1.0
    var overlapping = false
    func segment(_ input: WorkoutDistanceInterval) -> WorkoutDistanceInterval {
      var interval = input
      let adjacent =
        profile.lastEnd == interval.startSeconds
        && profile.lastStart.map { previousStart in
          context.active.contains { previousStart >= $0[0] && interval.endSeconds <= $0[1] }
        } == true
      interval.segment = adjacent ? (profile.lastSegment ?? 0) : (profile.lastSegment ?? -1) + 1
      return interval
    }
    var points: [Point] = []
    while true {
      var values: [(id: Int64, start: Double, value: Data)] = []
      let examined = try job(
        source + " intervals", Self.healthInputPageQuery,
        [.text(context.id), .text(state.generation), .text(source), .real(cursor), .integer(inputID)]
      ) { row in
        values.append((row.integer(0)!, row.double(1)!, row.data(2)!))
        return true
      }
      for row in values {
        let interval = try decoder.decode(WorkoutDistanceInterval.self, from: row.value)
        if let old = pending {
          if interval.startSeconds < clusterEnd {
            overlapping = true
            clusterEnd = max(clusterEnd, interval.endSeconds)
          } else {
            if !overlapping { emit(old, context: context, state: &state, profile: &profile, points: &points) }
            pending = segment(interval)
            clusterEnd = interval.endSeconds
            overlapping = false
          }
        } else {
          pending = segment(interval)
          clusterEnd = interval.endSeconds
        }
        cursor = row.start
        inputID = row.id
      }
      if examined < Self.jobRows {
        if let pending, !overlapping {
          emit(pending, context: context, state: &state, profile: &profile, points: &points)
        }
        break
      }
      if points.count >= Self.writeRows {
        try write(points, id: context.id, generation: state.generation, source: source)
        points.removeAll(keepingCapacity: true)
      }
    }
    try write(points, id: context.id, generation: state.generation, source: source)
    profile.lastHealthClusterEnd = max(profile.lastHealthClusterEnd, clusterEnd)
    state.profiles[source] = profile
    try deletePages(
      "distance_health_inputs", where: "collection_id=? AND generation=? AND source=?",
      [.text(context.id), .text(state.generation), .text(source)])
  }
  /// Only selected distance samples load their observation, in the same job. Columns: 0 elapsed, 1 observation,
  /// 2 selected, then `healthColumns` from 3.
  private func healthCandidates(
    _ name: String, _ sql: String, _ values: [PowerLogSQLValue], id: String, rows: inout [HealthInput],
    cursor: (PowerLogStatementRow) -> Void
  ) throws -> Int {
    var selected: [HealthInput] = []
    let examined = try job(name, sql, values, available: id) { row in
      cursor(row)
      if row.integer(2) == 1 {
        selected.append(
          HealthInput(
            time: row.double(0)!, observation: row.integer(1)!, anchor: row.text(3)!, start: row.text(4),
            end: row.text(5), value: row.double(6), unit: row.text(7), sampleCount: row.double(8),
            external: row.text(9)))
      }
      return true
    } then: { db in
      for var input in selected {
        try db.scan(Self.healthObservationQuery, [.integer(input.observation)], limit: 1) { row in
          input.original = row.text(0)!
          input.representation = row.text(1)!
          let association = row.string(3)
          if row.text(2) == "object" && !Self.composite(association) {
            input.association = association
          } else {
            input.extra = row.data(4) ?? Data()
          }
          rows.append(input)
          return true
        }
      }
    }
    return examined
  }
  private func associated(_ association: String?, sample: String?, context: Context, source: String) throws -> Bool {
    let expected = (context.metadata["healthKitUUID"] as? String)?.lowercased()
    if let direct = association?.lowercased(), direct == expected { return true }
    guard let sample, let expected else { return false }
    var cursor = Int64.min
    while true {
      var found = false
      let examined = try job(
        "association", Self.associationQuery,
        [
          .text(expected), .text(context.id), .text(source), .integer(context.revision), .integer(context.revision),
          .integer(context.revision), .text(sample), .integer(cursor),
        ]
      ) { row in
        cursor = row.integer(0)!
        found = row.integer(1) == 1
        return !found
      }
      if found { return true }
      if examined < Self.jobRows { return false }
    }
  }
  private func healthReported(_ context: Context, lowerRevision: Int64, state: inout State) throws -> (
    Double?, String?, Bool, String?
  ) {
    for final in [true, false] {
      for source in [context.owner, other(context.owner)] {
        let key = source + (final ? ":final" : ":provisional")
        let exists = try store.read { db in
          try db.scalarInt(
            "SELECT 1 FROM collection_channels WHERE collection_id=? AND metric='distanceMeters' AND source=? LIMIT 1",
            [.text(context.id), .text(source)]) != nil
        }
        guard exists else { continue }
        Self.reportRevisionObserverForTesting?(lowerRevision, context.revision)
        let row = try latestReport(source: source, final: final, context: context, lowerRevision: lowerRevision)
        if let row, let value = row.meters, value.isFinite {
          let timestamp = try store.read(priority: .background) { db in
            try db.rows(
              "SELECT original_timestamp FROM observations WHERE id=?", [.integer(row.observation)], limit: 1
            ).first?.string("original_timestamp")
          }
          let report = Report(
            meters: value, elapsed: row.elapsed, timestamp: timestamp!, observationID: row.observation)
          if let old = state.reports[key],
            old.elapsed > report.elapsed || (old.elapsed == report.elapsed && old.observationID > report.observationID)
          {
            continue
          }
          state.reports[key] = report
        }
      }
    }
    for final in [true, false] {
      for source in [context.owner, other(context.owner)] {
        if let report = state.reports[source + (final ? ":final" : ":provisional")] {
          return (report.meters, source, !final, report.timestamp)
        }
      }
    }
    return (nil, nil, false, nil)
  }
  /// The qualifying report with the largest (elapsed, observation): a cold build walks the source backwards from
  /// the end, an append examines only its new memberships.
  private func latestReport(source: String, final: Bool, context: Context, lowerRevision: Int64) throws -> (
    elapsed: Double, observation: Int64, meters: Double?
  )? {
    var latest: (elapsed: Double, observation: Int64, meters: Double?)?
    if lowerRevision < 0 {
      var cursor = (time: final ? Double.greatestFiniteMagnitude : context.end, observation: Int64.max)
      while true {
        let examined = try job(
          "report", final ? Self.finalReportQuery : Self.provisionalReportQuery,
          [
            .integer(lowerRevision), .integer(context.revision), .integer(context.revision),
            .integer(context.revision), .text(context.id), .text(source), .real(cursor.time),
            .integer(cursor.observation),
          ]
        ) { row in
          cursor = (row.double(0)!, row.integer(1)!)
          if row.integer(2) == 1 { latest = (cursor.time, cursor.observation, row.double(3)) }
          return latest == nil
        }
        if latest != nil || examined < Self.jobRows { return latest }
      }
    }
    var cursor = (revision: lowerRevision, id: Int64.max)
    while true {
      let examined = try job(
        "report append", final ? Self.finalReportAppendQuery : Self.provisionalReportAppendQuery,
        [.text(source)] + (final ? [] : [.real(context.end)]) + [
          .integer(context.revision), .integer(context.revision), .text(context.id), .integer(context.revision),
          .integer(cursor.revision), .integer(cursor.id),
        ]
      ) { row in
        cursor = (row.integer(4)!, row.integer(5)!)
        let key = (row.double(0)!, row.integer(1)!)
        if row.integer(2) == 1, latest.map({ ($0.elapsed, $0.observation) < key }) ?? true {
          latest = (key.0, key.1, row.double(3))
        }
        return true
      }
      if examined < Self.jobRows { return latest }
    }
  }
  private func removeUnpublished(id: String, generation: String, after pointID: Int64) throws {
    try deletePages(
      "distance_points",
      where:
        "generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=? AND generation=?) AND point_id>?",
      [.text(id), .text(generation), .integer(pointID)])
  }
  private func deletePages(_ table: String, where predicate: String, _ values: [PowerLogSQLValue]) throws {
    while true {
      let count = try store.transaction(priority: .background) { db -> Int in
        let rows = try db.rows("SELECT rowid AS rid FROM \(table) WHERE \(predicate) LIMIT 256", values, limit: 256)
        for row in rows { try db.execute("DELETE FROM \(table) WHERE rowid=?", [row["rid"]]) }
        return rows.count
      }
      if count < 256 { break }
    }
  }
  private func prune(id: String) throws {
    // Keep at most three coherent generations, every leased generation and sixteen small revision headers.
    var keep = try store.read { db in
      try db.rows(
        "SELECT generation FROM distance_generations WHERE collection_id=? ORDER BY revision DESC LIMIT 3", [.text(id)],
        limit: 3
      ).compactMap { $0.string("generation") }
    }
    guard !keep.isEmpty else { return }
    Self.admission.lock()
    let leased = Self.leases[key(id)].map { Array($0.keys) } ?? []
    Self.admission.unlock()
    for generation in leased.sorted() where !keep.contains(generation) { keep.append(generation) }
    let kept = "generation NOT IN (" + Array(repeating: "?", count: keep.count).joined(separator: ",") + ")"
    let generations = [PowerLogSQLValue.text(id)] + keep.map(PowerLogSQLValue.text)
    try deletePages(
      "distance_points",
      where: "generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=? AND \(kept))",
      generations)
    try deletePages(
      "distance_health_inputs",
      where:
        "collection_id=? AND generation IN (SELECT generation FROM distance_generations WHERE collection_id=? AND \(kept))",
      [.text(id)] + generations)
    for table in ["distance_snapshots", "distance_generations"] {
      try deletePages(table, where: "collection_id=? AND \(kept)", generations)
    }
    let cutoff = try store.read { db in
      try db.rows(
        "SELECT revision FROM distance_snapshots WHERE collection_id=? ORDER BY revision DESC LIMIT 1 OFFSET 15",
        [.text(id)], limit: 1
      ).first?.int("revision")
    }
    if let cutoff {
      try deletePages("distance_snapshots", where: "collection_id=? AND revision<?", [.text(id), .integer(cutoff)])
    }
  }
}
