import Foundation
import PowerLogCore

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

final class WorkoutDistanceStore {
  struct Job {
    let name: String
    let examined: Int
    let steps: Int
    let reprepares: Int
  }
  static let batteryMetrics = ["controllerEnergyWh", "controllerChargeAh", "calculatedEnergyWh", "calculatedChargeAh"]
  static let derivedMetrics = Set(["distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters"] + batteryMetrics)
  static let jobRows = 128
  static let writeRows = 1024
  static let pacedBuildSeconds = 1.0
  static let pacedRebuildSeconds = 10.0
  static let pacedPhases: Set<String> = ["running", "paused"]
  /// Statements carry literal kinds and limits: a bound kind or LIMIT makes SQLite re-plan them on every job.
  static let endBoundQuery =
    "SELECT elapsed_seconds,observation_id,revision<=? FROM collection_memberships INDEXED BY membership_time WHERE collection_id=? AND (elapsed_seconds,observation_id)<(?,?) ORDER BY elapsed_seconds DESC,observation_id DESC LIMIT \(jobRows)"
  static let lifecycleQuery =
    "SELECT m.elapsed_seconds,m.producer,m.sequence,CASE WHEN l.observation_id IS NOT NULL AND m.revision<=? AND \(selected) THEN 1 ELSE 0 END,l.action,o.clock_epoch,json_extract(o.extra,'$.controllerSequence'),json_extract(o.extra,'$.interrupted')=1 OR l.action='interruption' FROM collection_memberships m INDEXED BY membership_lifecycle_time JOIN observations o ON o.id=m.observation_id LEFT JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND (m.elapsed_seconds,m.producer,m.sequence)>(?,?,?) ORDER BY m.elapsed_seconds,m.producer,m.sequence LIMIT \(jobRows)"
  static let locationQuery = physicalQuery("location")
  static let telemetryQuery = physicalQuery("telemetry")
  static let changeQuery =
    "SELECT m.id,m.revision,m.kind,m.source,m.deleted,m.elapsed_us,json_type(\(text("o.extra")),'$.supersedesEventId') IS NOT NULL FROM collection_memberships m INDEXED BY membership_snapshot JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.revision>? AND m.revision<=? AND (m.revision,m.id)>(?,?) ORDER BY m.revision,m.id LIMIT \(jobRows)"
  private static let selected = PowerLogStore.selectedMembershipSQL
  private static func text(_ blob: String) -> String { "CAST(\(blob) AS TEXT)" }
  /// Candidates come in index order; the CASE column carries every other predicate, so rejected rows still
  /// advance the job cursor.
  private static func physicalQuery(_ kind: String) -> String {
    let gps = kind == "location"
    let extra = text("o.extra")
    let values =
      gps
      ? "t.latitude,t.longitude,t.horizontalAccuracyM,t.speedMps,t.speedAccuracyMps,t.speedAccuracyMps IS NULL AND json_type(\(extra),'$.speedAccuracyMps') IN ('integer','real'),t.altitudeMeters,t.verticalAccuracyM,m.original_elapsed_seconds,m.elapsed_us,o.physical_id"
      : "t.controllerSpeedMps,o.monotonic_seconds"
        + ["controllerModel", "controllerProtocol", "captureSessionID", "connectionEpoch", "observationSequence"].map {
          ",json_extract(\(extra),'$.\($0)')"
        }.joined() + ",m.elapsed_us,t.consumedAh,t.consumedWh,t.batteryCurrentA,t.batteryVoltageV"
    return
      "SELECT m.elapsed_seconds,m.observation_id,m.producer,m.sequence,CASE WHEN t.observation_id IS NOT NULL AND m.revision>? AND m.revision<=? AND \(selected) THEN 1 ELSE 0 END,m.event_id,o.original_timestamp,o.clock_epoch,json_type(\(extra)),json_type(\(extra),'$.distanceBarrier') IS 'true' OR json_type(\(extra),'$.clockDiscontinuitySeconds') IS NOT NULL,o.extra,\(values) FROM collection_memberships m INDEXED BY membership_distance_time JOIN observations o ON o.id=m.observation_id LEFT JOIN \(gps ? "locations" : "telemetry_frames") t ON t.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='\(kind)' AND m.source=? AND (m.elapsed_us,m.producer,m.sequence)>(?,?,?) ORDER BY m.elapsed_us,m.producer,m.sequence LIMIT \(jobRows)"
  }
  static func exportIntervalQuery(limit: Int) -> String {
    "SELECT point_id,start_seconds,end_seconds,increment,segment,start_speed,end_speed,elapsed_seconds FROM distance_points INDEXED BY distance_point_time WHERE generation_id=? AND source=? AND (elapsed_seconds,point_id)>(?,?) ORDER BY elapsed_seconds,point_id LIMIT \(limit)"
  }
  private let store: PowerLogStore
  fileprivate static let admission = NSLock()
  private static var building = Set<String>()
  fileprivate static var leases: [String: [String: Int]] = [:]
  private struct Pace {
    var built = -Double.infinity
    var rebuilt = -Double.infinity
  }
  private struct PacedBuild {
    let key: String
    let now: Double
    let fallback: WorkoutDistanceSnapshot?
  }
  private static var paces: [String: Pace] = [:]
  static var inputPageObserverForTesting: ((Int) -> Void)?
  static var jobObserverForTesting: ((Job) -> Void)?
  static var clockForTesting: (() -> Double)?
  private var encoder: JSONEncoder { WorkoutCoding.encoder() }
  private var decoder: JSONDecoder { JSONDecoder() }
  private struct Profile: Codable {
    var gps = WorkoutGPSDistanceAccumulator()
    var controller = WorkoutControllerDistanceAccumulator()
    var totalQ: Int64 = 0
    var coverageUs: Int64 = 0
    var total: Double { WorkoutDistancePolicy.seconds(totalQ) }
    var coverage: Double { WorkoutDistancePolicy.seconds(coverageUs) }
    var lastTime = -1.0
    var lastUs: Int64 = -1
    var lastProducer = ""
    var lastSequence: Int64 = 0
    var lastSegment: Int? = nil
    var plotSegment = 0
    var lastEnd = -1.0
  }
  private struct BatteryState: Codable {
    var total: Double = 0
    var coveredUs: Int64 = 0
    var available = false
    var coverage: Double { WorkoutDistancePolicy.seconds(coveredUs) }
  }
  private struct State: Codable {
    var revision: Int64 = 0
    var generation = UUID().uuidString.lowercased()
    var storageID: Int64 = 0
    var fingerprint = ""
    var hasActiveIntervals = false
    var profiles = Dictionary(uniqueKeysWithValues: WorkoutDistancePolicy.sources.map { ($0, Profile()) })
    var maximumPointID: Int64 = 0
    var batteryCheckpoint: String? = nil
    var battery = Dictionary(uniqueKeysWithValues: WorkoutDistanceStore.batteryMetrics.map { ($0, BatteryState()) })
  }
  private struct Context {
    var id: String, revision: Int64, kind: String, started: String
    var end: Double, active: [[Double]], owner: String, indoor: Bool
    var fingerprint: String
    var interruptions: [WorkoutInterruptionBoundary]
    var activeUs: Int64 {
      active.reduce(0) {
        FixedPoint.shared.add(
          a: $0,
          b: FixedPoint.shared.subtract(
            a: WorkoutDistancePolicy.timeUs($1[1]), b: WorkoutDistancePolicy.timeUs($1[0])))
      }
    }
    var activeSeconds: Double { WorkoutDistancePolicy.seconds(activeUs) }
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
    var timeUs: Int64
    var producer: String
    var sequence: Int64
    var epoch: String?
    var fix: WorkoutGPSFix?
    var sample: WorkoutControllerDistanceSample?
    var chargeAh: Double?
    var energyWh: Double?
    var currentA: Double?
    var voltageV: Double?
    /// Set when a value depends on `extra` decoding that SQL cannot reproduce.
    var extra: Data?
  }
  private let checkCancellation: () throws -> Void
  private let publishCheckpoint: (() throws -> Void) throws -> Void
  init(
    store: PowerLogStore, checkCancellation: @escaping () throws -> Void = {},
    publishCheckpoint: @escaping (() throws -> Void) throws -> Void = { try $0() }
  ) {
    self.store = store
    self.checkCancellation = checkCancellation
    self.publishCheckpoint = publishCheckpoint
  }
  private func read<T>(priority: PowerLogJobPriority = .normal, _ body: (PowerLogDatabase) throws -> T) throws -> T {
    try checkCancellation()
    return try store.read(priority: priority) { db in
      try self.checkCancellation()
      return try body(db)
    }
  }
  private func transaction<T>(
    priority: PowerLogJobPriority = .capture, commitFence: ((() throws -> Void) throws -> Void)? = nil,
    _ body: (PowerLogDatabase) throws -> T
  ) throws -> T {
    try checkCancellation()
    return try store.transaction(priority: priority, commitFence: commitFence) { db in
      try self.checkCancellation()
      return try body(db)
    }
  }

  func cachedSnapshot(id: String, revision: Int64, selection: String = "auto") throws -> WorkoutDistanceSnapshot? {
    try validate(selection)
    let value = try read { db -> Data? in
      try store.requireWorkoutAvailable(id: id)
      return try db.rows(
        "SELECT value FROM distance_snapshots WHERE collection_id=? AND revision=? AND json_type(CAST(value AS TEXT),'$.battery') IS 'object'",
        [.text(id), .integer(revision)],
        limit: 1
      ).first?.data("value")
    }
    guard let value else { return nil }
    return select(try decoder.decode(WorkoutDistanceSnapshot.self, from: value), selection)
  }
  func latestCachedSnapshot(id: String, selection: String = "auto") throws -> WorkoutDistanceSnapshot? {
    try validate(selection)
    let value = try read { db -> Data? in
      try store.requireWorkoutAvailable(id: id)
      return try db.rows(
        "SELECT value FROM distance_snapshots WHERE collection_id=? AND json_type(CAST(value AS TEXT),'$.battery') IS 'object' ORDER BY revision DESC LIMIT 1",
        [.text(id)],
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
  /// While a ride is running or paused, its paced readers build at most once per `pacedBuildSeconds` and from
  /// scratch at most once per `pacedRebuildSeconds`; in between, or while another build holds the ride, they get
  /// the newest cached snapshot, which may be older or newer than `revision`.
  func pacedSnapshot(id: String, revision: Int64, selection: String = "auto") throws -> WorkoutDistanceSnapshot {
    try validate(selection)
    let (phase, value) = try read { db -> (String?, Data?) in
      try store.requireWorkoutAvailable(id: id)
      let phase = try db.rows("SELECT phase FROM collections WHERE id=?", [.text(id)], limit: 1).first?.string("phase")
      let newest = try db.rows(
        "SELECT value FROM distance_snapshots WHERE collection_id=? AND json_type(CAST(value AS TEXT),'$.battery') IS 'object' ORDER BY revision DESC LIMIT 1",
        [.text(id)],
        limit: 1
      ).first?.data("value")
      return (phase, newest)
    }
    let newest = try value.map { try decoder.decode(WorkoutDistanceSnapshot.self, from: $0) }
    if let newest, newest.revision == revision { return select(newest, selection) }
    guard let phase, Self.pacedPhases.contains(phase) else {
      return try snapshot(id: id, revision: revision, selection: selection)
    }
    if let newest, newest.revision > revision { return select(newest, selection) }
    let pace = PacedBuild(
      key: key(id), now: Self.clockForTesting?() ?? ProcessInfo.processInfo.systemUptime, fallback: newest)
    if !Self.claim(\.built, of: pace, every: Self.pacedBuildSeconds), let newest { return select(newest, selection) }
    do {
      return try admitted(id: id) {
        try build(id: id, target: revision, selection: selection, leased: false, pace: pace).snapshot
      }
    } catch WorkoutDistanceError.pending {
      guard let newest else { throw WorkoutDistanceError.pending }
      return select(newest, selection)
    }
  }
  private static func claim(_ slot: WritableKeyPath<Pace, Double>, of pace: PacedBuild, every seconds: Double) -> Bool {
    admission.lock()
    defer { admission.unlock() }
    guard pace.now - (paces[pace.key]?[keyPath: slot] ?? -.infinity) >= seconds else { return false }
    paces[pace.key, default: Pace()][keyPath: slot] = pace.now
    return true
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
          try read { try require(cached, $0) }
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
  private func build(id: String, target: Int64, selection: String, leased: Bool, pace: PacedBuild? = nil) throws -> (
    snapshot: WorkoutDistanceSnapshot, lease: WorkoutDistanceLease?
  ) {
    let context = try context(id: id, revision: target)
    let previous = try previousState(id: id, before: target)
    let appending = try previous.map { try canAppend($0, context) } ?? false
    if !appending, let pace, !Self.claim(\.rebuilt, of: pace, every: Self.pacedRebuildSeconds),
      let fallback = pace.fallback
    {
      return (select(fallback, selection), nil)
    }
    let state = appending ? previous : nil
    var reuse: (state: State, sources: Set<String>)?
    if let old = previous, !appending {
      let sources = try reusableProfiles(old, context)
      if !sources.isEmpty { reuse = (old, sources) }
    }
    var next = state ?? State()
    next.fingerprint = context.fingerprint
    next.hasActiveIntervals = !context.active.isEmpty
    if next.storageID == 0 {
      try prune(id: id)
      let initial = try encoder.encode(next)
      next.storageID = try transaction(priority: .background) { db in
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
    for source in WorkoutDistancePolicy.sources {
      if let reuse, reuse.sources.contains(source) {
        try copyProfile(source: source, from: reuse.state, to: &next, id: id)
        next.profiles[source] = reuse.state.profiles[source]
        if source == "controller" {
          next.batteryCheckpoint = reuse.state.batteryCheckpoint
          next.battery = reuse.state.battery
          for metric in Self.batteryMetrics { try copyProfile(source: metric, from: reuse.state, to: &next, id: id) }
        }
      } else {
        try buildPhysical(source: source, context: context, lowerRevision: lowerRevision, state: &next)
      }
    }
    next.revision = target
    var profiles: [DistanceProfile] = []
    for source in WorkoutDistancePolicy.sources {
      let p = next.profiles[source]!
      let range = DistanceRange(distanceQ: p.totalQ, coveredUs: p.coverageUs, unresolvedBoundary: false, partial: false)
      if let profile = DistanceKt.distanceProfile(
        source: source, range: range,
        timerUs: context.activeUs, descriptors: [])
      {
        profiles.append(profile)
      }
    }
    let decision = DistanceKt.selectDistance(
      selection: "auto",
      available: DistancePolicy.shared.orderAvailable(
        available: profiles, watchEnabled: context.owner == "watch", indoor: context.indoor),
      watchEnabled: context.owner == "watch", indoor: context.indoor)
    let sources = decision.available.map(WorkoutDistanceSource.from)
    let automatic = decision.selected?.source
    var result = WorkoutDistanceSnapshot(
      id: id, revision: target, generation: next.generation, storageID: next.storageID, selection: "auto",
      source: automatic,
      method: automatic.map(WorkoutDistancePolicy.method), estimated: automatic == "controller",
      totalMeters: sources.first { $0.source == automatic }?.distanceMeters,
      coveredSeconds: sources.first { $0.source == automatic }?.coveredSeconds ?? 0,
      activeSeconds: context.activeSeconds,
      outcome: automatic == nil ? "unavailable" : "ready", sources: sources, maximumPointID: next.maximumPointID,
      endSeconds: context.end, activeIntervals: context.active)
    result.battery = next.battery.compactMapValues { profile in
      profile.available ? WorkoutBatteryProfile(total: profile.total, coveredSeconds: profile.coverage) : nil
    }
    let stateData = try encoder.encode(next)
    let resultData = try encoder.encode(result)
    try transaction(priority: .background, commitFence: publishCheckpoint) { db in
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
    try read { try require(result, $0) }
    result = select(result, selection)
    return (result, lease)
  }

  struct ChartReference {
    let pointID: Int64
    let seconds: Double
    let value: Double
    let plotSegment: Int
  }
  func chartPage(
    snapshot: WorkoutDistanceSnapshot, after: WorkoutDistanceCursor? = nil, start: Double, end: Double,
    observeWork: ((Int) -> Void)? = nil
  )
    throws -> [ChartReference]
  {
    guard let source = snapshot.source else { return [] }
    return try store.read { db in
      try require(snapshot, db)
      return try db.rows(
        "SELECT point_id,elapsed_seconds,distance,plot_segment FROM distance_points WHERE generation_id=? AND source=? AND point_id<=? AND elapsed_seconds>=? AND elapsed_seconds<=? AND (elapsed_seconds,point_id)>(?,?) ORDER BY elapsed_seconds,point_id LIMIT 256",
        [
          .integer(snapshot.storageID), .text(source), .integer(snapshot.maximumPointID),
          .real(max(start, after?.time ?? start)), .real(end),
          .real(after?.time ?? -1), .integer(after?.pointID ?? 0),
        ], limit: 256, observeWork: observeWork
      ).map {
        ChartReference(
          pointID: $0.int("point_id")!, seconds: $0.double("elapsed_seconds")!, value: $0.double("distance")!,
          plotSegment: Int($0.int("plot_segment")!))
      }
    }
  }
  func chartNeighbor(snapshot: WorkoutDistanceSnapshot, seconds: Double, before: Bool) throws -> ChartReference? {
    guard let source = snapshot.source else { return nil }
    return try store.read { db in
      try require(snapshot, db)
      return try db.rows(
        "SELECT point_id,elapsed_seconds,distance,plot_segment FROM distance_points WHERE generation_id=? AND source=? AND point_id<=? AND elapsed_seconds\(before ? "<" : ">")? ORDER BY elapsed_seconds \(before ? "DESC" : "ASC"),point_id \(before ? "DESC" : "ASC") LIMIT 1",
        [.integer(snapshot.storageID), .text(source), .integer(snapshot.maximumPointID), .real(seconds)], limit: 1
      ).first.map {
        ChartReference(
          pointID: $0.int("point_id")!, seconds: $0.double("elapsed_seconds")!, value: $0.double("distance")!,
          plotSegment: Int($0.int("plot_segment")!))
      }
    }
  }
  func chartPoints(snapshot: WorkoutDistanceSnapshot, ids: [Int64]) throws -> [WorkoutDistancePoint] {
    guard let source = snapshot.source, !ids.isEmpty else { return [] }
    precondition(ids.count <= 256)
    return try store.read { db in
      try require(snapshot, db)
      return try db.rows(
        "SELECT * FROM distance_points WHERE generation_id=? AND source=? AND point_id<=? AND point_id IN (\(Array(repeating: "?", count: ids.count).joined(separator: ",")))",
        [.integer(snapshot.storageID), .text(source), .integer(snapshot.maximumPointID)] + ids.map { .integer($0) },
        limit: 256
      ).map { point($0, snapshot: snapshot) }
    }
  }

  func page(
    snapshot: WorkoutDistanceSnapshot, after: WorkoutDistanceCursor? = nil, start: Double = 0,
    end: Double = WorkoutDistancePolicy.maximumSeconds, limit: Int = 256
  ) throws -> [WorkoutDistancePoint] {
    guard (1...256).contains(limit), start.isFinite, end.isFinite else {
      throw WorkoutDistanceError.invalid("Invalid distance page")
    }
    guard let source = snapshot.source else { return [] }
    let rows = try read { db -> [PowerLogRow] in
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
    return try read(priority: .background) { db in
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
    let row = try read { db -> PowerLogRow? in
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
    let row = try read { db -> PowerLogRow? in
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
    let bounds = AnalysisRange(startUs: WorkoutDistancePolicy.timeUs(start), endUs: WorkoutDistancePolicy.timeUs(end))
    let first = try neighbor(
      snapshot: snapshot, seconds: WorkoutDistancePolicy.seconds(bounds.startUs), before: false, strict: true)
    let last = try neighbor(snapshot: snapshot, seconds: WorkoutDistancePolicy.seconds(bounds.endUs), before: true)
    var initial = DistanceRange(distanceQ: 0, coveredUs: 0, unresolvedBoundary: false, partial: false)
    if let first, let last, first.pointID < last.pointID {
      initial = DistanceRange(
        distanceQ: FixedPoint.shared.subtract(
          a: WorkoutDistancePolicy.metersQ(last.distanceMeters), b: WorkoutDistancePolicy.metersQ(first.distanceMeters)),
        coveredUs: FixedPoint.shared.subtract(
          a: WorkoutDistancePolicy.storedUs(last.cumulativeCoveredSeconds),
          b: WorkoutDistancePolicy.storedUs(first.cumulativeCoveredSeconds)),
        unresolvedBoundary: false, partial: false)
    }
    let reducer = DistanceReducer(
      range: bounds, initial: initial)
    if let first { reducer.append(interval: first.interval.core) }
    if let next = try neighbor(
      snapshot: snapshot, seconds: WorkoutDistancePolicy.seconds(bounds.endUs), before: false, strict: true),
      next.pointID != first?.pointID
    {
      reducer.append(interval: next.interval.core)
    }
    let active = snapshot.activeIntervals.reduce(Int64(0)) { total, interval in
      total
        + max(
          0,
          min(bounds.endUs, WorkoutDistancePolicy.timeUs(interval[1]))
            - max(bounds.startUs, WorkoutDistancePolicy.timeUs(interval[0])))
    }
    return WorkoutDistanceRange.from(reducer.result(timerUs: active))
  }

  func batteryRange(snapshot: WorkoutDistanceSnapshot, start: Double, end: Double) throws -> [String: Any] {
    guard start.isFinite, end.isFinite, start >= 0, start <= end else {
      throw WorkoutDistanceError.invalid("Invalid consumption range")
    }
    let first = try neighbor(snapshot: snapshot, seconds: start, before: false, strict: true)
    let last = try neighbor(snapshot: snapshot, seconds: end, before: true)
    var amount = 0.0
    var coveredUs: Int64 = 0
    var available = false
    if let first, let last, first.pointID < last.pointID {
      available = true
      amount = last.distanceMeters - first.distanceMeters
      coveredUs = WorkoutDistancePolicy.storedUs(last.cumulativeCoveredSeconds - first.cumulativeCoveredSeconds)
    }
    let reducer = BatteryConsumptionReducer(
      metric: snapshot.selection, startUs: WorkoutDistancePolicy.timeUs(start),
      endUs: WorkoutDistancePolicy.timeUs(end),
      initial: BatteryConsumptionRange(
        amount: amount, coveredUs: coveredUs, available: available,
        unresolvedBoundary: false, partial: false))
    func append(_ point: WorkoutDistancePoint) {
      reducer.append(
        interval: BatteryConsumptionInterval(
          metric: snapshot.selection, startUs: WorkoutDistancePolicy.timeUs(point.startSeconds),
          endUs: WorkoutDistancePolicy.timeUs(point.endSeconds),
          startValue: point.distanceMeters - point.incrementMeters, endValue: point.distanceMeters,
          rate0: point.startSpeed.map(KotlinDouble.init(value:)), rate1: point.endSpeed.map(KotlinDouble.init(value:)),
          indivisible: point.indivisible, segment: Int32(point.segment), activeInterval: 0,
          baseline: point.startSeconds == point.endSeconds))
    }
    // A baseline establishes zero only where its observation lies in the requested range.
    if let baseline = try neighbor(snapshot: snapshot, seconds: start, before: true),
      baseline.startSeconds == baseline.endSeconds,
      WorkoutDistancePolicy.timeUs(baseline.elapsedSeconds) == WorkoutDistancePolicy.timeUs(start)
    {
      append(baseline)
    }
    if let first { append(first) }
    if let next = try neighbor(snapshot: snapshot, seconds: end, before: false, strict: true),
      next.pointID != first?.pointID
    {
      append(next)
    }
    let active = snapshot.activeIntervals.reduce(0.0) { $0 + max(0, min(end, $1[1]) - max(start, $1[0])) }
    let range = reducer.result(timerUs: WorkoutDistancePolicy.timeUs(active))
    var result: [String: Any] = [
      "coveredSeconds": range.coveredSeconds, "unresolvedBoundary": range.unresolvedBoundary,
      "partial": !range.available || range.partial,
    ]
    if range.available { result["amount"] = range.amount }
    return result
  }

  private func validate(_ selection: String) throws {
    if Self.batteryMetrics.contains(selection) { return }
    guard DistancePolicy.shared.validSelection(selection: selection) else {
      throw WorkoutDistanceError.invalid("Unknown distance source")
    }
  }
  func selecting(_ input: WorkoutDistanceSnapshot, metric: String, main: String) -> WorkoutDistanceSnapshot {
    switch metric {
    case "gpsDistanceMeters": return select(input, "gps:phone")
    case "controllerDistanceMeters": return select(input, "controller")
    case "distanceMeters": return select(input, main)
    default: return select(input, metric)
    }
  }
  private func select(_ input: WorkoutDistanceSnapshot, _ selection: String) -> WorkoutDistanceSnapshot {
    guard selection != "auto" else { return input }
    var result = input
    if Self.batteryMetrics.contains(selection) {
      let profile = input.battery[selection]
      result.selection = selection
      result.source = profile == nil ? nil : selection
      result.totalMeters = profile?.total
      result.coveredSeconds = profile?.coveredSeconds ?? 0
      result.method = selection.hasPrefix("calculated") ? "integration" : "counter"
      result.estimated = selection.hasPrefix("calculated")
      result.outcome = profile == nil ? "unavailable" : "ready"
      return result
    }
    let decision = DistanceKt.selectDistance(
      selection: selection, available: input.sources.map(\.core), watchEnabled: false, indoor: false)
    let chosen = decision.selected.map(WorkoutDistanceSource.from)
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
  private func previousState(id: String, before revision: Int64) throws -> State? {
    let data = try read { db in
      try db.rows(
        "SELECT state FROM distance_generations WHERE collection_id=? AND revision>=0 AND revision<? ORDER BY revision DESC LIMIT 1",
        [.text(id), .integer(revision)], limit: 1
      ).first?.data("state")
    }
    return data.flatMap { try? decoder.decode(State.self, from: $0) }
  }
  private func context(id: String, revision: Int64) throws -> Context {
    let row = try store.collection(id: id)
    guard revision <= row.int("revision")!, revision >= 0 else { throw WorkoutDistanceError.expired }
    let meta = try read { db in
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
          controller: String?
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
        if let retained = item.controller.flatMap(Int64.init), retained >= 0 {
          interruptions.append(
            WorkoutInterruptionBoundary(
              time: item.time, producer: item.producer, sequence: item.sequence, clockEpoch: item.epoch,
              controllerSequence: retained))
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
      "startedAt", "endedAt", "stopElapsedSeconds", "indoor", "watchEnabled", "distanceSource",
      "batteryConsumptionSource",
    ]
    let fixed = Dictionary(uniqueKeysWithValues: keys.compactMap { key in metadata[key].map { (key, $0) } })
    let fingerprint = String(
      data: try JSONSerialization.data(withJSONObject: fixed, options: [.sortedKeys]), encoding: .utf8)!
    return Context(
      id: id, revision: revision, kind: row.string("kind")!, started: started, end: end, active: active,
      owner: metadata["watchEnabled"] as? Bool == true ? "watch" : "phone",
      indoor: metadata["indoor"] as? Bool ?? false, fingerprint: fingerprint, interruptions: interruptions)
  }
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
  private func job(
    _ name: String, _ sql: String, _ values: [PowerLogSQLValue], available id: String? = nil,
    _ body: (PowerLogStatementRow) throws -> Bool, then finish: (PowerLogDatabase) throws -> Void = { _ in }
  ) throws -> Int {
    let observer = Self.jobObserverForTesting
    var work = (steps: 0, reprepares: 0)
    let examined = try read(priority: .background) { db -> Int in
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
    guard state.fingerprint == context.fingerprint, state.hasActiveIntervals || context.active.isEmpty else {
      return false
    }
    let count = try read(priority: .background) { db in
      try db.scalarInt(
        "SELECT count(*) FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=?",
        [.text(context.id), .integer(state.revision), .integer(context.revision)]) ?? 0
    }
    guard count == context.revision - state.revision, count <= 512 else { return false }
    var cursor = (revision: state.revision, id: Int64(0))
    var appends = true
    while appends {
      let examined = try job(
        "changes", Self.changeQuery,
        [
          .text(context.id), .integer(state.revision), .integer(context.revision), .integer(cursor.revision),
          .integer(cursor.id),
        ]
      ) { row in
        cursor = (row.integer(1)!, row.integer(0)!)
        appends = Self.appends(row, to: state)
        return appends
      }
      if examined < Self.jobRows { break }
    }
    return appends
  }
  /// Columns of `changeQuery`: 0 membership, 1 revision, 2 kind, 3 source, 4 deleted, 5 elapsed, 6 replaces an
  /// original.
  private static func appends(_ row: PowerLogStatementRow, to state: State) -> Bool {
    let kind = row.text(2)
    guard kind != "lifecycle", row.integer(4) != 1, row.integer(6) != 1 else { return false }
    let source = row.text(3) ?? ""
    let profile: Profile?
    switch kind {
    case "location": profile = state.profiles["gps:" + source]
    case "telemetry": profile = state.profiles["controller"]
    default: return true
    }
    return profile.map { (row.integer(5) ?? -1) > $0.lastUs } ?? true
  }

  private func reusableProfiles(_ old: State, _ context: Context) throws -> Set<String> {
    guard old.fingerprint == context.fingerprint else { return [] }
    let previous = try read(priority: .background) { db in
      try db.rows(
        "SELECT value FROM distance_snapshots WHERE collection_id=? AND revision=?",
        [.text(context.id), .integer(old.revision)], limit: 1
      ).first?.data("value")
    }
    guard let previous, let snapshot = try? decoder.decode(WorkoutDistanceSnapshot.self, from: previous),
      snapshot.endSeconds == context.end, snapshot.activeIntervals == context.active
    else { return [] }
    guard context.revision > old.revision, context.revision - old.revision <= 512 else { return [] }
    var journalRevision = old.revision
    while journalRevision < context.revision {
      let changes = try read(priority: .background) { db in
        try db.rows(
          "SELECT revision,kind FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT 128",
          [.text(context.id), .integer(journalRevision), .integer(context.revision)], limit: 128)
      }
      guard !changes.isEmpty else { return [] }
      for change in changes {
        guard change.int("revision") == journalRevision + 1,
          ["append", "semantics"].contains(change.string("kind") ?? "")
        else { return [] }
        journalRevision += 1
      }
    }
    var changed = Set<String>()
    var representedRevisions = Set<Int64>()
    var cursor = (revision: old.revision, id: Int64(0))
    func invalidate(_ kind: String, _ source: String) -> Bool {
      switch kind {
      case "telemetry": changed.insert("controller")
      case "location": changed.insert("gps:" + source)
      case "health": break
      default: return false
      }
      return true
    }
    while true {
      let rows = try read(priority: .background) { db in
        try db.rows(
          "SELECT m.id,m.revision,m.kind,m.source,json_extract(CAST(o.extra AS TEXT),'$.supersedesEventId') AS replaced FROM collection_memberships m INDEXED BY membership_snapshot JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.revision>? AND m.revision<=? AND (m.revision,m.id)>(?,?) ORDER BY m.revision,m.id LIMIT 128",
          [
            .text(context.id), .integer(old.revision), .integer(context.revision), .integer(cursor.revision),
            .integer(cursor.id),
          ], limit: 128)
      }
      for row in rows {
        representedRevisions.insert(row.int("revision")!)
        guard let kind = row.string("kind"), let source = row.string("source"), invalidate(kind, source) else {
          return []
        }
        if let replaced = row.string("replaced") {
          let original = try read(priority: .background) { db in
            try db.rows(
              "SELECT kind,source FROM collection_memberships WHERE collection_id=? AND event_id=?",
              [.text(context.id), .text(replaced)], limit: 1
            ).first
          }
          guard let original, let kind = original.string("kind"), let source = original.string("source"),
            invalidate(kind, source)
          else { return [] }
        }
      }
      guard let last = rows.last else { break }
      cursor = (last.int("revision")!, last.int("id")!)
      if rows.count < Self.jobRows { break }
    }
    guard representedRevisions.count == context.revision - old.revision else { return [] }
    return Set(WorkoutDistancePolicy.sources).subtracting(changed)
  }

  private func copyProfile(source: String, from old: State, to next: inout State, id: String) throws {
    var cursor = (time: -Double.greatestFiniteMagnitude, point: Int64(0))
    let generationID = next.storageID
    while true {
      let pointOffset = next.maximumPointID
      let count = try transaction(priority: .background) { db in
        try store.requireWorkoutAvailable(id: id)
        let rows = try db.rows(
          "SELECT elapsed_seconds,point_id FROM distance_points INDEXED BY distance_point_time WHERE generation_id=? AND source=? AND point_id<=? AND (elapsed_seconds,point_id)>(?,?) ORDER BY elapsed_seconds,point_id LIMIT 128",
          [
            .integer(old.storageID), .text(source), .integer(old.maximumPointID), .real(cursor.time),
            .integer(cursor.point),
          ], limit: 128)
        guard let last = rows.last else { return 0 }
        let end = (time: last.double("elapsed_seconds")!, point: last.int("point_id")!)
        try db.execute(
          "INSERT INTO distance_points SELECT ?,source,?+row_number() OVER (ORDER BY elapsed_seconds,point_id),elapsed_seconds,timestamp,distance,increment,start_seconds,end_seconds,segment,start_anchor,end_anchor,start_speed,end_speed,indivisible,covered,plot_segment FROM distance_points INDEXED BY distance_point_time WHERE generation_id=? AND source=? AND point_id<=? AND (elapsed_seconds,point_id)>(?,?) AND (elapsed_seconds,point_id)<=(?,?)",
          [
            .integer(generationID), .integer(pointOffset), .integer(old.storageID), .text(source),
            .integer(old.maximumPointID),
            .real(cursor.time), .integer(cursor.point), .real(end.time), .integer(end.point),
          ])
        cursor = end
        return rows.count
      }
      next.maximumPointID += Int64(count)
      Self.jobObserverForTesting?(Job(name: "reuse:" + source, examined: count, steps: 0, reprepares: 0))
      if count < Self.jobRows { break }
    }
  }

  private func buildPhysical(source: String, context: Context, lowerRevision: Int64, state: inout State) throws {
    let gps = source.hasPrefix("gps:")
    let originalSource = gps ? String(source.dropFirst(4)) : "controller"
    var profile = state.profiles[source]!
    var cursor = (us: profile.lastUs, producer: profile.lastProducer, sequence: profile.lastSequence)
    var points: [Point] = []
    var batteryPoints: [String: [Point]] = [:]
    let batteryKernel = try gps ? nil : BatteryConsumptionKernel(checkpoint: state.batteryCheckpoint)
    while true {
      var inputs: [PhysicalInput] = []
      let examined = try job(
        source, gps ? Self.locationQuery : Self.telemetryQuery,
        [
          .integer(lowerRevision), .integer(context.revision), .integer(context.revision), .integer(context.revision),
          .text(context.id), .text(originalSource), .integer(cursor.us), .text(cursor.producer),
          .integer(cursor.sequence),
        ],
        available: context.id
      ) { row in
        cursor = (row.integer(gps ? 20 : 18)!, row.text(2)!, row.integer(3)!)
        if row.integer(4) == 1 { inputs.append(Self.physicalInput(row, gps: gps, context: context)) }
        return true
      }
      if !inputs.isEmpty {
        Self.inputPageObserverForTesting?(inputs.count)
        for index in inputs.indices {
          if let extra = inputs[index].extra { try resolve(&inputs[index], extra: extra) }
        }
        let projection =
          try gps
          ? profile.gps.appendPage(inputs.map { $0.fix! }, totalQ: profile.totalQ, coverageUs: profile.coverageUs)
          : profile.controller.appendPage(
            inputs.map { $0.sample! }, totalQ: profile.totalQ, coverageUs: profile.coverageUs)
        emit(projection, context: context, state: &state, profile: &profile, points: &points)
        if let batteryKernel, context.kind == "workout" {
          for input in inputs {
            guard let sample = input.sample else { continue }
            let continuity =
              sample.barrier
              ? nil
              : sample.identity.flatMap { identity in
                sample.continuity.map { identity + ":" + $0 }
              }
            let active = sample.activeInterval ?? (context.end == 0 && input.time == 0 ? 0 : nil)
            let intervals = batteryKernel.append(
              timeUs: input.timeUs, activeInterval: active.map { KotlinInt(value: Int32($0)) },
              continuityIdentity: continuity, chargeAh: input.chargeAh.map(KotlinDouble.init(value:)),
              energyWh: input.energyWh.map(KotlinDouble.init(value:)),
              currentA: input.currentA.map(KotlinDouble.init(value:)),
              voltageV: input.voltageV.map(KotlinDouble.init(value:)))
            for interval in intervals {
              var p = state.battery[interval.metric]!
              let start = WorkoutDistancePolicy.seconds(interval.startUs)
              let end = WorkoutDistancePolicy.seconds(interval.endUs)
              p.total = interval.endValue
              if !interval.baseline { p.coveredUs += interval.endUs - interval.startUs }
              state.maximumPointID += 1
              batteryPoints[interval.metric, default: []].append(
                Point(
                  id: state.maximumPointID, time: end, timestamp: sample.timestamp, distance: interval.endValue,
                  increment: interval.endValue - interval.startValue, start: start, end: end,
                  segment: Int(interval.segment),
                  startAnchor: sample.anchor, endAnchor: sample.anchor,
                  startSpeed: interval.rate0?.doubleValue, endSpeed: interval.rate1?.doubleValue,
                  indivisible: interval.indivisible, covered: p.coverage, plotSegment: Int(interval.segment)))
              p.available = true
              state.battery[interval.metric] = p
            }
          }
          state.batteryCheckpoint = batteryKernel.checkpoint()
        }
        profile.lastTime = max(profile.lastTime, inputs.reduce(profile.lastTime) { max($0, $1.time) })
        let last = inputs.last!
        profile.lastUs = last.timeUs
        profile.lastProducer = last.producer
        profile.lastSequence = last.sequence
      }
      if points.count >= Self.writeRows {
        try write(points, id: context.id, generation: state.generation, source: source)
        points.removeAll(keepingCapacity: true)
      }
      for metric in Self.batteryMetrics where (batteryPoints[metric]?.count ?? 0) >= Self.writeRows {
        try write(batteryPoints[metric]!, id: context.id, generation: state.generation, source: metric)
        batteryPoints[metric] = []
      }
      if examined < Self.jobRows { break }
    }
    for (metric, values) in batteryPoints {
      try write(values, id: context.id, generation: state.generation, source: metric)
    }
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
    var input = PhysicalInput(
      time: time, timeUs: row.integer(gps ? 20 : 18)!, producer: producer, sequence: sequence, epoch: epoch)
    let object = row.text(8) == "object"
    if gps {
      input.fix = WorkoutGPSFix(
        time: time, latitude: row.double(11) ?? .nan, longitude: row.double(12) ?? .nan,
        horizontalAccuracy: row.double(13) ?? .nan, speed: row.double(14), speedAccuracy: row.double(15), epoch: epoch,
        activeInterval: active, identity: row.text(5)!, timestamp: row.text(6)!, barrier: row.integer(9) == 1,
        altitude: row.double(17), verticalAccuracy: row.double(18), observationID: row.text(21)!,
        producer: producer, sequence: sequence, originalElapsedSeconds: row.double(19), collectionUs: row.integer(20))
      if !object || row.integer(16) == 1 { input.extra = row.data(10) ?? Data() }
    } else {
      input.chargeAh = row.double(19)
      input.energyWh = row.double(20)
      input.currentA = row.double(21)
      input.voltageV = row.double(22)
      let strings = (13...16).map { row.string(Int32($0)) }
      let counter = row.string(17)
      let counterExact = counter.flatMap(Int64.init)
      input.sample = WorkoutControllerDistanceSample(
        time: time, speed: row.double(11), model: strings[0], controllerProtocol: strings[1], identity: strings[2],
        continuity: continuity(strings[3], epoch), activeInterval: active, anchor: row.text(5)!,
        timestamp: row.text(6)!, monotonic: row.double(12),
        counter: counterExact == nil ? counter.flatMap(Double.init) : nil,
        barrier: row.integer(9) == 1, counterExact: counterExact, collectionUs: row.integer(18))
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
  private func resolve(_ input: inout PhysicalInput, extra data: Data) throws {
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
      sample.counterExact = extra["observationSequence"]?.string.flatMap(Int64.init)
      sample.counter = nil
      sample.barrier = barrier
      input.sample = sample
    }
  }
  private func emit(
    _ page: WorkoutDistancePageProjection, context: Context, state: inout State, profile: inout Profile,
    points: inout [Point]
  ) {
    for projected in page.intervals {
      let interval = projected.value
      func point(
        _ time: Double, _ timestamp: String, _ increment: Double, _ start: Double, _ end: Double,
        _ total: Double, _ covered: Double
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
          interval.startSeconds - profile.lastEnd >= TelemetryTiming.shared.displayHoldSeconds
            || context.interruptions.contains(where: { $0.time >= profile.lastEnd && $0.time <= interval.startSeconds })
        {
          profile.plotSegment += 1
        }
        points.append(
          point(
            interval.startSeconds, interval.startTimestamp, 0, interval.startSeconds,
            interval.startSeconds, projected.priorTotal, projected.priorCoverage))
      }
      points.append(
        point(
          interval.endSeconds, interval.endTimestamp, interval.meters, interval.startSeconds,
          interval.endSeconds, projected.total, projected.coverage))
      profile.lastSegment = interval.segment
      profile.lastEnd = interval.endSeconds
    }
    profile.totalQ = page.totalQ
    profile.coverageUs = page.coverageUs
  }
  private func write(_ points: [Point], id: String, generation: String, source: String) throws {
    for first in stride(from: 0, to: points.count, by: Self.writeRows) {
      let batch = points[first..<min(first + Self.writeRows, points.count)]
      try transaction(priority: .background) { db in
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
  private func removeUnpublished(id: String, generation: String, after pointID: Int64) throws {
    try deletePages(
      "distance_points",
      where:
        "generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=? AND generation=?) AND point_id>?",
      [.text(id), .text(generation), .integer(pointID)])
  }
  private func deletePages(_ table: String, where predicate: String, _ values: [PowerLogSQLValue]) throws {
    let page =
      "DELETE FROM \(table) WHERE rowid IN (SELECT rowid FROM \(table) WHERE \(predicate) LIMIT \(Self.writeRows))"
    while try transaction(priority: .background, { try $0.execute(page, values) }) == Self.writeRows {}
  }
  /// Deletes only when a generation outside the kept set or a surplus snapshot exists.
  private func prune(id: String) throws {
    Self.admission.lock()
    let leased = Self.leases[key(id)].map { Array($0.keys) } ?? []
    Self.admission.unlock()
    func snapshotCutoff(_ db: PowerLogDatabase) throws -> Int64? {
      try db.rows(
        "SELECT revision FROM distance_snapshots WHERE collection_id=? ORDER BY revision DESC LIMIT 1 OFFSET 15",
        [.text(id)], limit: 1
      ).first?.int("revision")
    }
    let found = try read { db -> (keep: [String], stale: Bool, cutoff: Int64?)? in
      var keep = try db.rows(
        "SELECT generation FROM distance_generations WHERE collection_id=? ORDER BY revision DESC LIMIT 3", [.text(id)],
        limit: 3
      ).compactMap { $0.string("generation") }
      guard !keep.isEmpty else { return nil }
      for generation in leased.sorted() where !keep.contains(generation) { keep.append(generation) }
      let stale =
        try db.scalarInt(
          "SELECT 1 FROM distance_generations WHERE collection_id=? AND generation NOT IN ("
            + Array(repeating: "?", count: keep.count).joined(separator: ",") + ") LIMIT 1",
          [.text(id)] + keep.map(PowerLogSQLValue.text)) != nil
      return (keep, stale, try stale ? nil : snapshotCutoff(db))
    }
    guard let found else { return }
    var cutoff = found.cutoff
    if found.stale {
      let kept = "generation NOT IN (" + Array(repeating: "?", count: found.keep.count).joined(separator: ",") + ")"
      let generations = [PowerLogSQLValue.text(id)] + found.keep.map(PowerLogSQLValue.text)
      try deletePages(
        "distance_points",
        where: "generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=? AND \(kept))",
        generations)
      for table in ["distance_snapshots", "distance_generations"] {
        try deletePages(table, where: "collection_id=? AND \(kept)", generations)
      }
      cutoff = try read(snapshotCutoff)
    }
    if let cutoff {
      try deletePages("distance_snapshots", where: "collection_id=? AND revision<?", [.text(id), .integer(cutoff)])
    }
  }
}
