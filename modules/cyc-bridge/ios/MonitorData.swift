import Foundation
import CryptoKit

/// One query boundary over the canonical store. Plot reduction never changes originals.
enum MonitorError: Error, LocalizedError {
  case invalid(String)
  case admission(String), cacheContention(String)
  var errorDescription: String? {
    switch self {
    case .invalid(let value): return value
    case .admission(let lane): return "MONITOR_ADMISSION_\(lane.uppercased()): monitor work is deferred."
    case .cacheContention(let lane): return "MONITOR_CACHE_\(lane.uppercased()): another direct read owns this cache."
    }
  }
}

struct MonitorObservationAnchor {
  let metric: String
  let observationId: String

  func validate(metrics: [String], seconds: Double?) throws {
    guard seconds != nil, metrics.contains(metric), MonitorDataStore.metrics.contains(metric),
          !observationId.isEmpty, observationId.utf8.count <= 512,
          observationId.rangeOfCharacter(from: .controlCharacters) == nil else {
      throw MonitorError.invalid("Invalid monitor observation anchor.")
    }
  }
}

struct MonitorRequest {
  var source: String = "live"
  var id: String?
  var generation: Int = 0
  var expectedRevision: String?
  var sinceRevision: String?
  var startSeconds: Double?
  var endSeconds: Double?
  var seconds: Double?
  var metrics: [String] = []
  var buckets: Int = 128
  var pixelWidth: Double?
  var includeEndpoints: Bool = false
  var anchor: MonitorObservationAnchor?
  var startAnchor: MonitorObservationAnchor?
  var endAnchor: MonitorObservationAnchor?
  var distanceSource: String = "auto"
  func validate() throws {
    guard ["live", "workout"].contains(source), generation >= 0,
      (1...512).contains(buckets), metrics.count <= MonitorDataStore.metrics.count,
      metrics.allSatisfy({ MonitorDataStore.metrics.contains($0) }),
      distanceSource == "auto" || WorkoutDistancePolicy.sources.contains(distanceSource) else { throw MonitorError.invalid("Invalid monitor request.") }
    for value in [startSeconds, endSeconds, seconds, pixelWidth].compactMap({ $0 }) {
      guard value.isFinite, value >= 0, value <= MonitorDataStore.maximumSeconds else { throw MonitorError.invalid("Invalid monitor range.") }
    }
    if let startSeconds, let endSeconds, startSeconds > endSeconds { throw MonitorError.invalid("Invalid monitor range.") }
    for revision in [expectedRevision, sinceRevision].compactMap({ $0 }) {
      guard MonitorRevisionToken.parse(revision) != nil else { throw MonitorError.invalid("Invalid source revision.") }
    }
    try anchor?.validate(metrics: metrics, seconds: seconds)
    try startAnchor?.validate(metrics: metrics, seconds: startSeconds)
    try endAnchor?.validate(metrics: metrics, seconds: endSeconds)
  }
}

/// Original revisions remain SQL integers; a response also identifies its interpretation.
private enum MonitorRevisionToken {
  static func value(_ revision: Int64, selection: String) -> String {
    selection == "auto" ? String(revision) : "distance:v1:\(selection):\(revision)"
  }
  static func parse(_ value: String) -> (revision: Int64, selection: String)? {
    if let revision = Int64(value), revision >= 0 { return (revision, "auto") }
    for selection in WorkoutDistancePolicy.sources {
      let prefix = "distance:v1:\(selection):"
      if value.hasPrefix(prefix), let revision = Int64(value.dropFirst(prefix.count)), revision >= 0 { return (revision, selection) }
    }
    return nil
  }
}

enum MonitorReadOperation {
  case describe, plot, inspect, stats, changes, latest
  var analytical: Bool { switch self { case .plot, .stats: return true; default: return false } }
  var lane: String { self == .plot ? "plot" : self == .stats ? "statistics" : "fast" }
}

private struct MonitorOriginal {
  let id: Int64
  let identity: String
  let time: Double
  let timestamp: String
  let value: Double
  var integer: Int64? = nil
  var dictionary: [String: Any] {
    var result: [String: Any] = ["observationId": identity, "elapsedSeconds": time, "timestamp": timestamp, "value": value]
    if let integer { result["exactValue"] = String(integer) }
    return result
  }
  static func before(_ a: Self, _ b: Self) -> Bool { a.time != b.time ? a.time < b.time : a.identity < b.identity }
}
/// Compare original SQLite integers without routing them through a rounded Double.
func monitorValueOrder(_ a: Double, _ ai: Int64?, _ b: Double, _ bi: Int64?) -> Int {
  if let ai, let bi { return ai == bi ? 0 : (ai < bi ? -1 : 1) }
  func integerOrder(_ integer: Int64, _ real: Double) -> Int {
    if real >= 9_223_372_036_854_775_808.0 { return -1 }
    if real < -9_223_372_036_854_775_808.0 { return 1 }
    let whole = Int64(real)
    if integer != whole { return integer < whole ? -1 : 1 }
    let converted = Double(integer)
    return converted == real ? 0 : (converted < real ? -1 : 1)
  }
  if let ai { return integerOrder(ai, b) }
  if let bi { return -integerOrder(bi, a) }
  return a == b ? 0 : (a < b ? -1 : 1)
}
private struct MonitorChannel {
  let table: String
  var column: String
  let kind: String
  let source: String
  var rawHeart = false
  var healthSpeed = false
  var rawSpeed = false
  var metric: String { healthSpeed ? "healthSpeedMps" : column }
  var conditions: String {
    var clause = "m.kind=? AND m.source=?" + (column == "heartRateBpm" ? " AND m.raw_heart=\(rawHeart ? 1 : 0)" : "")
    if healthSpeed {
      clause += " AND h.identifier='HKQuantityTypeIdentifierCyclingSpeed' AND h.unit='m/s'"
      clause += rawSpeed ? " AND (o.representation='rawSeries' OR (o.representation='rawQuantity' AND h.sampleCount=1))" : " AND o.representation NOT IN ('rawQuantity','rawSeries')"
    }
    return clause
  }
  var observationJoin: String { healthSpeed ? " JOIN observations o ON o.id=m.observation_id" : "" }
  var values: [PowerLogSQLValue] { [.text(kind), .text(source)] }
  var latestFallback: MonitorChannel? {
    guard rawHeart || rawSpeed else { return nil }
    var fallback = self
    fallback.rawHeart = false
    if rawSpeed { fallback.rawSpeed = false; fallback.column = "speedMps" }
    return fallback
  }
}
private struct MonitorDescription {
  let id: String
  let sourceID: String
  let revision: Int64
  let startedAt: String
  let end: Double
  let phase: String
  let metadata: [String: Any]
  let available: [String]
  let distanceSelection: String
  var responseRevision: String { MonitorRevisionToken.value(revision, selection: distanceSelection) }
}

/// Plot caches hold only exact numeric projections. Originals are hydrated after reduction.
private struct MonitorProjectedPoint {
  let id: Int64
  let time: Double
  private let bits: UInt64
  private let isInteger: Bool
  var integer: Int64? { isInteger ? Int64(bitPattern: bits) : nil }
  var value: Double { integer.map(Double.init) ?? Double(bitPattern: bits) }
  init(id: Int64, time: Double, value: Double, integer: Int64? = nil) {
    self.id = id; self.time = time; isInteger = integer != nil
    bits = integer.map(UInt64.init(bitPattern:)) ?? value.bitPattern
  }
}
private final class MonitorProjectionBlock {
  static let capacity = 1_024
  // Include a fixed allowance for the allocation, object and block-array bookkeeping.
  static let bytes = capacity * MemoryLayout<MonitorProjectedPoint>.stride + 128
  let points = UnsafeMutableBufferPointer<MonitorProjectedPoint>.allocate(capacity: capacity)
  var count = 0
  deinit { points.baseAddress?.deinitialize(count: count); points.deallocate() }
  func append(_ point: MonitorProjectedPoint) {
    precondition(count < Self.capacity)
    points.baseAddress!.advanced(by: count).initialize(to: point); count += 1
  }
}
private final class MonitorProjection {
  static let overheadBytes = 512
  let key: String
  let start: Double
  var end: Double
  var revision: Int64
  var used: UInt64 = 0
  var blocks: [MonitorProjectionBlock] = []
  var count = 0
  var bytes: Int { Self.overheadBytes + blocks.count * MonitorProjectionBlock.bytes }
  init(key: String, start: Double, end: Double, revision: Int64) {
    self.key = key; self.start = start; self.end = end; self.revision = revision
  }
  subscript(index: Int) -> MonitorProjectedPoint {
    blocks[index / MonitorProjectionBlock.capacity].points[index % MonitorProjectionBlock.capacity]
  }
  /// First point at/after the exact boundary, without scanning earlier history while panning.
  func lowerBound(_ time: Double) -> Int {
    var low = 0, high = count
    while low < high { let mid = (low + high) / 2; if self[mid].time < time { low = mid + 1 } else { high = mid } }
    return low
  }
}
private struct MonitorPlotReference {
  let point: MonitorProjectedPoint
  let segment: Int
}
private struct MonitorPlotBucket {
  var first: MonitorPlotReference?
  var last: MonitorPlotReference?
  var low: MonitorPlotReference?
  var high: MonitorPlotReference?
  var points: [MonitorPlotReference] { [first, low, high, last].compactMap { $0 } }
  mutating func add(_ p: MonitorPlotReference, before: (MonitorProjectedPoint, MonitorProjectedPoint) throws -> Bool) rethrows {
    if try first == nil || before(p.point, first!.point) { first = p }
    if try last == nil || before(last!.point, p.point) { last = p }
    let lowOrder = low.map { monitorValueOrder(p.point.value, p.point.integer, $0.point.value, $0.point.integer) } ?? -1
    let highOrder = high.map { monitorValueOrder(p.point.value, p.point.integer, $0.point.value, $0.point.integer) } ?? 1
    if try lowOrder < 0 || (lowOrder == 0 && before(p.point, low!.point)) { low = p }
    if try highOrder > 0 || (highOrder == 0 && before(p.point, high!.point)) { high = p }
  }
}

/// A bounded tree of exact first/min/max/last observations. Leaf windows are eight
/// seconds; parents cover 16, 32, 64... seconds. No averaged or invented samples.
private final class MonitorPyramid {
  static let seconds = 8.0
  let key: String
  let leafCount: Int
  var nodes: [MonitorPlotBucket]
  var revision: Int64
  var lastRead: MonitorProjectedPoint?
  var segment = 0
  var used: UInt64 = 0
  var bytes: Int { nodes.capacity * MemoryLayout<MonitorPlotBucket>.stride + 512 }
  static func leaves(_ end: Double) -> Int {
    var count = 1
    while Double(count) * seconds <= end { count *= 2 }
    return count
  }
  static func estimate(_ end: Double) -> Int { leaves(end) * 2 * MemoryLayout<MonitorPlotBucket>.stride + 512 }
  init(key: String, end: Double, revision: Int64) {
    self.key = key; self.revision = revision; leafCount = Self.leaves(end)
    nodes = Array(repeating: MonitorPlotBucket(), count: leafCount * 2)
  }
  func append(_ p: MonitorProjectedPoint, gap: Double, course: Bool = false, before: (MonitorProjectedPoint, MonitorProjectedPoint) throws -> Bool) rethrows {
    if let lastRead, p.time - lastRead.time + 1e-9 >= gap || (course && abs(p.value - lastRead.value) > 180) { segment += 1 }
    let leaf = Int(p.time / Self.seconds)
    try nodes[leafCount + leaf].add(MonitorPlotReference(point: p, segment: segment), before: before)
    lastRead = p
  }
  func rebuild(from firstLeaf: Int, through lastLeaf: Int, before: (MonitorProjectedPoint, MonitorProjectedPoint) throws -> Bool) rethrows {
    var first = (leafCount + firstLeaf) / 2, last = (leafCount + lastLeaf) / 2
    while first > 0 {
      for index in first...last {
        var merged = MonitorPlotBucket()
        for point in nodes[index * 2].points { try merged.add(point, before: before) }
        for point in nodes[index * 2 + 1].points { try merged.add(point, before: before) }
        nodes[index] = merged
      }
      first /= 2; last /= 2
    }
  }
}

/// Mutable caches never cross analytical executors. The two limits sum to one budget.
private final class MonitorWorkspace {
  let lock = NSLock()
  let byteLimit: Int
  var projections: [String: MonitorProjection] = [:]
  var pyramids: [String: MonitorPyramid] = [:]
  var tick: UInt64 = 0
  var counters: [String: Int] = [:]
  var bytes: Int { projections.values.reduce(0) { $0 + $1.bytes } + pyramids.values.reduce(0) { $0 + $1.bytes } }
  init(byteLimit: Int) { self.byteLimit = byteLimit }
}

final class MonitorDataStore {
  static let shared = MonitorDataStore()
  static let maximumSeconds = 2_678_400.0
  static let metrics = Set(PowerLogStore.telemetryColumns).union([
    "heartRateBpm", "speedMps", "healthSpeedMps", "altitudeMeters", "activeEnergyKcal", "basalEnergyKcal", "distanceMeters",
    "horizontalAccuracyM", "verticalAccuracyM", "courseDegrees"])
  static let pageSize = 256
  static let maximumPlotPoints = 16_384
  static let maximumCachedBytes = 24 * 1024 * 1024
  // Leave bounded read pages, retained geometry, serialization and bridge work headroom.
  // This is an allocation budget, not a guarantee about Foundation/OS resident memory.
  static let plotWorkingBytes = 8 * 1024 * 1024
  static let maximumProjectionBytes = maximumCachedBytes - plotWorkingBytes
  static let interpretation = 6
  private let queue = DispatchQueue(label: "app.powerlog.monitor.read", qos: .userInitiated)
  private let statisticsQueue = DispatchQueue(label: "app.powerlog.monitor.statistics", qos: .utility)
  private let fastQueue = DispatchQueue(label: "app.powerlog.monitor.inspect", qos: .userInteractive)
  private let readAdmission = NSLock()
  private var readJobs: [String: Int] = [:]
  // Client arbitration normally admits only one per lane. These bound direct callers too.
  static let maximumAnalyticalJobs = 2
  static let maximumFastJobs = 4
  private let captureQueue = DispatchQueue(label: "app.powerlog.monitor.capture", qos: .utility)
  private let root: URL
  private var liveID = UUID().uuidString.lowercased()
  private var liveStartedAt = WorkoutCoding.timestamp(Date())
  private var liveOrigin = ProcessInfo.processInfo.systemUptime
  private var captureError: Error?
  private var initializedLive = false
  private var background = true
  private let database: Result<PowerLogStore, Error>
  private var store: PowerLogStore { get throws { try database.get() } }
  private let plotWorkspace: MonitorWorkspace
  private let statisticsWorkspace: MonitorWorkspace
  private let projectionPageObserver: (() -> Void)?
  private let distanceQueue = DispatchQueue(label: "app.powerlog.monitor.distance", qos: .utility)
  private let distanceLock = NSLock()
  private var distanceBuilds = Set<String>()
  private var distanceFailures: [String: String] = [:]
  private var distanceStore: WorkoutDistanceStore { get throws { WorkoutDistanceStore(store: try store) } }

  init(root: URL? = nil, cacheRoot: URL? = nil, projectionCacheBytes: Int = MonitorDataStore.maximumProjectionBytes,
    projectionPageObserver: (() -> Void)? = nil) {
    let directory = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("PowerLog", isDirectory: true)
    self.root = directory
    let budget = max(0, min(Self.maximumProjectionBytes, projectionCacheBytes))
    plotWorkspace = MonitorWorkspace(byteLimit: budget / 2)
    statisticsWorkspace = MonitorWorkspace(byteLimit: budget - budget / 2)
    self.projectionPageObserver = projectionPageObserver
    database = Result { try PowerLogStore.shared(databaseURL: directory.appendingPathComponent("power-log.sqlite3")) }
  }
  /// Bridge admission occurs before dispatch. Indexed reads never queue behind analytics;
  /// both lanes cap their current job plus pending closures instead of growing a backlog.
  @discardableResult
  func submit(_ operation: MonitorReadOperation, request: MonitorRequest,
    completion: @escaping (Result<[String: Any], Error>) -> Void) -> Bool {
    do { try request.validate() } catch { completion(.failure(error)); return false }
    readAdmission.lock()
    let admitted = readJobs[operation.lane, default: 0] < (operation.analytical ? Self.maximumAnalyticalJobs : Self.maximumFastJobs)
    if admitted { readJobs[operation.lane, default: 0] += 1 }
    readAdmission.unlock()
    guard admitted else { completion(.failure(MonitorError.admission(operation.lane))); return false }
    let source: MonitorDescription
    do { source = try description(request) }
    catch { releaseReadSlot(operation); completion(.failure(error)); return false }
    // Capture once when admitted, not when a queued analytic job finally executes.
    // Otherwise continuous capture would make the queued half of every plot/stats pair stale.
    if operation != .describe && operation != .latest, let retry = mismatch(request, source) {
      releaseReadSlot(operation); completion(.success(retry)); return true
    }
    (operation == .plot ? queue : operation == .stats ? statisticsQueue : fastQueue).async { [self] in
      defer { releaseReadSlot(operation) }
      completion(Result { try retryDistance(request, source: source) {
        switch operation {
        case .describe: return try describeSource(request, source: source)
        case .plot: return try readPlot(request, source: source)
        case .inspect: return try inspectAt(request, source: source)
        case .stats: return try rangeStats(request, source: source)
        case .changes: return try changesSince(request, source: source)
        case .latest: return try readLatest(request, source: source)
        }
      } })
    }
    return true
  }
  private func releaseReadSlot(_ operation: MonitorReadOperation) {
    readAdmission.lock(); defer { readAdmission.unlock() }
    readJobs[operation.lane, default: 0] -= 1
  }
  private func retryDistance(_ request: MonitorRequest, source: MonitorDescription, _ body: () throws -> [String: Any]) throws -> [String: Any] {
    do { return try body() }
    catch WorkoutDistanceError.pending { scheduleDistance(source); return envelope(request, source, status: "retry") }
    catch WorkoutDistanceError.expired { scheduleDistance(source); return envelope(request, source, status: "retry") }
  }
  /// Only one background calculation per collection; repeated cursor reads cannot queue full rebuilds.
  private func scheduleDistance(_ source: MonitorDescription) {
    distanceLock.lock()
    guard distanceBuilds.count < 8, distanceBuilds.insert(source.id).inserted else { distanceLock.unlock(); return }
    distanceLock.unlock()
    distanceQueue.async { [self] in
      var failure: String?
      do { _ = try distanceStore.snapshot(id: source.id, revision: source.revision, selection: source.distanceSelection) }
      catch WorkoutDistanceError.pending { }
      catch WorkoutDistanceError.expired { }
      catch { failure = String(error.localizedDescription.prefix(1_024)) }
      distanceLock.lock()
      distanceBuilds.remove(source.id)
      if let failure {
        if distanceFailures.count >= 8 { distanceFailures.removeAll(keepingCapacity: true) }
        distanceFailures[source.id] = failure
      } else { distanceFailures.removeValue(forKey: source.id) }
      distanceLock.unlock()
    }
  }
  private func distanceSnapshot(_ source: MonitorDescription, build: Bool) throws -> WorkoutDistanceSnapshot? {
    if build { return try distanceStore.snapshot(id: source.id, revision: source.revision, selection: source.distanceSelection) }
    if let snapshot = try distanceStore.cachedSnapshot(id: source.id, revision: source.revision, selection: source.distanceSelection) { return snapshot }
    scheduleDistance(source)
    distanceLock.lock(); let failure = distanceFailures[source.id]; distanceLock.unlock()
    if let failure { throw MonitorError.invalid(failure) }
    return nil
  }
  private func distanceMetricSources(_ snapshot: WorkoutDistanceSnapshot) -> [String: Any] {
    if let selected = snapshot.info.selected { return ["distanceMeters": selected.dictionary] }
    return [:]
  }
  static func gap(_ metric: String) -> Double {
    if metric == "heartRateBpm" { return 15 }
    if ["activeEnergyKcal", "basalEnergyKcal", "distanceMeters"].contains(metric) { return 120 }
    if ["speedMps", "healthSpeedMps", "altitudeMeters", "horizontalAccuracyM", "verticalAccuracyM", "courseDegrees"].contains(metric) { return 10 }
    return 6
  }
  func beginLive(startedAt: String, monotonic: Double, id: String = UUID().uuidString.lowercased()) {
    captureQueue.async {
      do {
        self.liveID = try WorkoutCoding.id(id); self.liveStartedAt = startedAt; self.liveOrigin = monotonic
        self.initializedLive = false; self.captureError = nil
        try self.ensureLive()
        if !self.background { self.prunePreviousLive(keeping: self.liveID) }
      } catch { self.captureError = error }
    }
  }
  private func ensureLive() throws {
    guard !initializedLive else { return }
    try store.ensureLiveCollection(id: liveID, startedAt: liveStartedAt, monotonicOrigin: liveOrigin)
    initializedLive = true
  }
  private func prunePreviousLive(keeping id: String) {
    DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.05) {
      guard self.captureQueue.sync(execute: { self.liveID == id && !self.background }) else { return }
      do {
        if try self.store.pruneLivePage(keeping: id) { self.prunePreviousLive(keeping: id) }
      } catch {
        // Disposable old-live cleanup can retry on the next connection; it never removes durable memberships.
      }
    }
  }
  func appendLive(_ sample: [String: Any], elapsedSeconds: Double) throws {
    try captureQueue.sync {
      try commitLive([(sample, elapsedSeconds)])
    }
  }
  func setBackground(_ background: Bool) {
    captureQueue.async {
      guard self.background != background else { return }
      self.background = background
      if !background { self.prunePreviousLive(keeping: self.liveID) }
    }
  }
  private func commitLive(_ frames: [(sample: [String: Any], elapsed: Double)]) throws {
    do {
      try ensureLive()
      try store.transaction(priority: .capture) { _ in
        for frame in frames { _ = try store.appendTelemetry(frame.sample, collectionID: liveID, elapsedSeconds: frame.elapsed) }
      }
      captureError = nil
    } catch { captureError = error; throw error }
  }
  private func description(_ request: MonitorRequest) throws -> MonitorDescription {
    try request.validate()
    let id: String
    if request.source == "live" {
      id = try captureQueue.sync { if let captureError { throw captureError }; try ensureLive(); return liveID }
    } else if let requested = request.id { id = try WorkoutCoding.id(requested) }
    else { throw MonitorError.invalid("Choose a chart source.") }
    return try store.read { db in
      if try store.isWorkoutDeleted(id: id) { throw PowerLogStorageError.deleted(id) }
      guard let row = try db.rows("SELECT * FROM collections WHERE id=? AND kind=?", [.text(id), .text(request.source)], limit: 1).first,
        let started = row.string("started_at"), let revision = row.int("revision") else { throw MonitorError.invalid("Chart source is unavailable.") }
      let metadata = (row.data("metadata").flatMap { try? JSONSerialization.jsonObject(with: $0) }) as? [String: Any] ?? [:]
      let available = (row.data("channels").flatMap { try? JSONDecoder().decode([String].self, from: $0) }) ?? []
      return MonitorDescription(id: id, sourceID: request.source + ":" + id, revision: revision,
        startedAt: started, end: max(10, row.double("max_elapsed") ?? 0), phase: row.string("phase") ?? "",
        metadata: metadata, available: available, distanceSelection: request.distanceSource)
    }
  }
  private func envelope(_ request: MonitorRequest, _ source: MonitorDescription, status: String = "ok") -> [String: Any] {
    ["status": status, "generation": request.generation, "sourceId": source.sourceID, "revision": source.responseRevision]
  }
  private func mismatch(_ request: MonitorRequest, _ source: MonitorDescription) -> [String: Any]? {
    if let expected = request.expectedRevision, expected != source.responseRevision { return envelope(request, source, status: "retry") }
    return nil
  }
  private func published(_ value: [String: Any], request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    let current = try description(request)
    // Every page and source choice is bound to the admitted revision. Continuous capture
    // may advance it while reading; publish that coherent snapshot rather than starve UI.
    return current.sourceID == source.sourceID ? value : envelope(request, current, status: "retry")
  }
  func describeSource(_ request: MonitorRequest) throws -> [String: Any] {
    try describeSource(request, source: description(request))
  }
  private func describeSource(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    var result = envelope(request, source)
    result["startedAt"] = source.startedAt
    result["domain"] = ["start": 0.0, "end": source.end]
    var available = Set(source.available.filter { Self.metrics.contains($0) && $0 != "distanceMeters" })
    var metricSources: [String: Any] = [:]
    if let health = try channel("healthSpeedMps", source: source) {
      available.insert("healthSpeedMps")
      metricSources["healthSpeedMps"] = ["source": "health:\(health.source)", "label": health.source == "watch" ? "Health · Watch" : "Health · iPhone"]
    }
    let distance = try distanceSnapshot(source, build: false)
    if let distance {
      if distance.totalMeters != nil { available.insert("distanceMeters") }
      metricSources.merge(distanceMetricSources(distance)) { _, new in new }
    } else {
      // Advertise the requested capability while its small metadata/profile is being prepared.
      available.insert("distanceMeters")
      metricSources["distanceMeters"] = ["source": "pending", "label": "Calculating distance", "estimated": false]
    }
    result["metricSources"] = metricSources
    result["availableMetrics"] = available.sorted()
    let warnings = source.metadata["warnings"] as? [String] ?? []
    let finalization = source.metadata["finalizationState"] as? String
    let pending = distance == nil || finalization == "pending" || source.metadata["watchSyncState"] as? String == "pending" || source.phase == "finishing"
    result["outcome"] = pending ? "pending" : (distance?.info.selected?.partial == true || finalization == "partial" || !warnings.isEmpty ? "partial" : "available")
    result["warnings"] = warnings
    if request.source == "live" { result["nowSeconds"] = captureQueue.sync { max(0, ProcessInfo.processInfo.systemUptime - liveOrigin) } }
    else if ["running", "paused"].contains(source.phase) {
      result["nowSeconds"] = max(source.end, Date().timeIntervalSince(try WorkoutCoding.date(source.startedAt)))
    } else { result["nowSeconds"] = source.end }
    return try published(result, request: request, source: source)
  }
  private func channel(_ metric: String, source: MonitorDescription) throws -> MonitorChannel? {
    // Ride distance has a dedicated, versioned derivation; Health snapshots are not its curve.
    if metric == "distanceMeters" { return nil }
    let controller = PowerLogStore.telemetryColumns.contains(metric)
    let location = ["speedMps", "altitudeMeters", "horizontalAccuracyM", "verticalAccuracyM", "courseDegrees"].contains(metric)
    let preferred = source.metadata["watchEnabled"] as? Bool == true ? ["watch", "phone"] : ["phone", "watch"]
    if metric == "healthSpeedMps" {
      for owner in preferred {
        for raw in [true, false] {
          let candidate = MonitorChannel(table: "health_samples", column: raw ? "value" : "speedMps", kind: "health", source: owner, healthSpeed: true, rawSpeed: raw)
          let exists = try store.read { db in
            try db.scalarInt("SELECT 1 FROM health_samples h INDEXED BY health_identifier CROSS JOIN observations o ON o.id=h.observation_id CROSS JOIN collection_memberships m INDEXED BY membership_observation ON m.observation_id=o.id WHERE \(baseWhere(candidate)) LIMIT 1", values(candidate, source)) != nil
          }
          if exists { return candidate }
        }
      }
      return nil
    }
    let sources = controller ? ["cyc"] : preferred
    let table = controller ? "telemetry_frames" : (location ? "locations" : "health_samples")
    let kind = controller ? "telemetry" : (location ? "location" : "health")
    let channels = try store.read { db in
      try db.rows("SELECT source,representation FROM collection_channels WHERE collection_id=? AND metric=? AND count>0", [.text(source.id), .text(metric)], limit: 32)
    }
    for owner in sources {
      let rawChoices = metric == "heartRateBpm" ? [true, false] : [false]
      for raw in rawChoices {
        let exists = channels.contains { $0.string("source") == owner && (metric != "heartRateBpm" || ($0.string("representation") == "raw") == raw) }
        if exists {
          let c = MonitorChannel(table: table, column: metric, kind: kind, source: owner, rawHeart: raw)
          if try neighbor(c, source, time: Self.maximumSeconds, before: true) != nil { return c }
        }
      }
    }
    return nil
  }
  private func tableColumn(_ c: MonitorChannel) -> String {
    // Column names come solely from the native allowlist, never arbitrary input.
    "h.\(c.column)"
  }
  private func validClause(_ c: MonitorChannel) -> String {
    var clause = "\(tableColumn(c)) IS NOT NULL"
    if ["speedMps", "horizontalAccuracyM", "verticalAccuracyM", "courseDegrees"].contains(c.column) { clause += " AND \(tableColumn(c))>=0" }
    if c.column == "heartRateBpm" { clause += " AND \(tableColumn(c)) BETWEEN 1 AND 254" }
    if c.healthSpeed { clause += " AND \(tableColumn(c))>=0" }
    if c.kind == "location", c.column == "speedMps" { clause += " AND (h.speedAccuracyMps IS NULL OR h.speedAccuracyMps>=0)" }
    if c.kind == "location", c.column == "courseDegrees" { clause += " AND h.courseDegrees<360 AND (h.courseAccuracyDegrees IS NULL OR h.courseAccuracyDegrees>=0)" }
    return clause
  }
  private func selectSQL(_ c: MonitorChannel, anchored: Bool = false) -> String {
    let membershipIndex = anchored ? " INDEXED BY membership_observation" : ""
    return "SELECT m.observation_id,m.elapsed_seconds,m.elapsed_us,o.physical_id,o.original_timestamp,\(tableColumn(c)) AS value FROM collection_memberships m\(membershipIndex) JOIN observations o ON o.id=m.observation_id JOIN \(c.table) h ON h.observation_id=o.id"
  }
  private func baseWhere(_ c: MonitorChannel) -> String { "m.collection_id=? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND \(c.conditions) AND \(validClause(c))" }
  private func values(_ c: MonitorChannel, _ source: MonitorDescription) -> [PowerLogSQLValue] { [.text(source.id), .integer(source.revision), .integer(source.revision), .integer(source.revision)] + c.values }
  private func original(_ row: PowerLogRow) -> MonitorOriginal? {
    guard let id = row.int("observation_id"), let time = row.double("elapsed_seconds"), let identity = row.string("physical_id"),
      let timestamp = row.string("original_timestamp"), let value = row.double("value"), time.isFinite, value.isFinite else { return nil }
    return MonitorOriginal(id: id, identity: identity, time: time, timestamp: timestamp, value: value, integer: row.int("value"))
  }
  private func neighbor(_ c: MonitorChannel, _ source: MonitorDescription, time: Double, before: Bool, strict: Bool = false) throws -> MonitorOriginal? {
    let comparison = before ? (strict ? "<" : "<=") : (strict ? ">" : ">=")
    return try store.read { db in
      let rows = try db.rows("\(selectSQL(c)) WHERE \(baseWhere(c)) AND m.elapsed_seconds\(comparison)? ORDER BY m.elapsed_seconds \(before ? "DESC" : "ASC"),o.physical_id ASC LIMIT 1", values(c, source) + [.real(time)], limit: 1)
      return rows.first.flatMap(original)
    }
  }

  private func anchoredOriginal(_ anchor: MonitorObservationAnchor, _ c: MonitorChannel,
                                _ source: MonitorDescription, time: Double) throws -> MonitorOriginal? {
    try store.read { db in
      // physical_id is UNIQUE; force its observation membership index rather than scanning a time range.
      // All externally supplied identity/time values remain bound parameters and the result is bounded to one row.
      let rows = try db.rows("\(selectSQL(c, anchored: true)) WHERE \(baseWhere(c)) AND o.physical_id=? AND m.elapsed_seconds>=? AND m.elapsed_seconds<=? LIMIT 1",
        values(c, source) + [.text(anchor.observationId), .real(time - 1e-6), .real(time + 1e-6)], limit: 1)
      guard let point = rows.first.flatMap(original), abs(point.time - time) <= 1e-6 else { return nil }
      return point
    }
  }
  /// Sum both workspaces; the working reserve is shared, not allocated per reader.
  func projectionDiagnostics() -> [String: Int] {
    plotWorkspace.lock.lock(); defer { plotWorkspace.lock.unlock() }
    statisticsWorkspace.lock.lock(); defer { statisticsWorkspace.lock.unlock() }
    var result: [String: Int] = [:]
    for cache in [plotWorkspace, statisticsWorkspace] {
      for (key, value) in cache.counters { result[key, default: 0] += value }
      result["allocatedBytes", default: 0] += cache.bytes
      result["byteLimit", default: 0] += cache.byteLimit
      result["pyramidBytes", default: 0] += cache.pyramids.values.reduce(0) { $0 + $1.bytes }
      result["pyramidEntries", default: 0] += cache.pyramids.count
      result["entries", default: 0] += cache.projections.count
      result["points", default: 0] += cache.projections.values.reduce(0) { $0 + $1.count }
    }
    result["workingReserveBytes"] = Self.plotWorkingBytes
    return result
  }
  /// Cleanup retries on its existing bounded tick if an analytic lane is still active.
  /// Never block the engine or add eviction work to the native read queues.
  @discardableResult
  func discardWorkout(id: String) -> Bool {
    var drained = true
    for cache in [plotWorkspace, statisticsWorkspace] {
      guard cache.lock.try() else { drained = false; continue }
      discardWorkout(id: id, from: cache)
      cache.lock.unlock()
    }
    return drained
  }
  private func discardWorkout(id: String, from cache: MonitorWorkspace) {
    let prefix = "workout:\(id)|"
    cache.projections = cache.projections.filter { !$0.key.hasPrefix(prefix) }
    cache.pyramids = cache.pyramids.filter { !$0.key.hasPrefix(prefix) }
  }
  private func releaseWorkspace(_ cache: MonitorWorkspace, source: MonitorDescription) {
    if (try? store.isWorkoutDeleted(id: source.id)) == true { discardWorkout(id: source.id, from: cache) }
    cache.lock.unlock()
  }
  private func makeProjectionRoom(_ cache: MonitorWorkspace, _ bytes: Int, keeping key: String, evictPyramids: Bool = true) -> Bool {
    guard bytes <= cache.byteLimit else { return false }
    while cache.bytes + bytes > cache.byteLimit || (cache.projections[key] == nil && cache.projections.count >= 16) {
      let raw = cache.projections.values.filter({ $0.key != key }).min(by: { $0.used < $1.used })
      let reduced = evictPyramids ? cache.pyramids.values.filter({ $0.key != key }).min(by: { $0.used < $1.used }) : nil
      if let raw, reduced == nil || raw.used <= reduced!.used { cache.projections.removeValue(forKey: raw.key) }
      else if let reduced { cache.pyramids.removeValue(forKey: reduced.key) }
      else { return false }
      cache.counters["evictions", default: 0] += 1
    }
    return true
  }
  private func appendProjection(_ cache: MonitorWorkspace, _ p: MonitorProjectedPoint, to projection: MonitorProjection) -> Bool {
    if projection.blocks.last?.count ?? MonitorProjectionBlock.capacity == MonitorProjectionBlock.capacity {
      guard makeProjectionRoom(cache, MonitorProjectionBlock.bytes, keeping: projection.key, evictPyramids: false) else { return false }
      projection.blocks.append(MonitorProjectionBlock())
      cache.counters["peakAllocatedBytes"] = max(cache.counters["peakAllocatedBytes", default: 0], cache.bytes)
    }
    projection.blocks.last!.append(p); projection.count += 1
    return true
  }
  private func projectionKey(_ c: MonitorChannel, _ source: MonitorDescription) -> String {
    [source.sourceID, c.column, c.source, c.rawHeart ? "raw" : "fallback", c.healthSpeed ? (c.rawSpeed ? "health-speed-raw" : "health-speed-latest") : "", String(Self.interpretation)].joined(separator: "|")
  }
  private func projectionStillValid(_ projection: MonitorProjection, _ c: MonitorChannel, _ source: MonitorDescription) throws -> Bool {
    if projection.revision == source.revision { return true }
    guard projection.revision < source.revision else { return false }
    let changes = try store.read { db in
      try db.rows("SELECT revision,kind,metrics FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT 512",
        [.text(source.id), .integer(projection.revision), .integer(source.revision)], limit: 512)
    }
    guard changes.count == source.revision - projection.revision,
      changes.first?.int("revision") == projection.revision + 1, changes.last?.int("revision") == source.revision else { return false }
    var changed = false
    for row in changes {
      guard let bytes = row.data("metrics"), let metrics = try? JSONDecoder().decode([String].self, from: bytes) else { return false }
      guard c.healthSpeed || metrics.isEmpty || metrics.contains(c.column) else { continue }
      guard row.string("kind") == "append" else { return false }
      changed = true
    }
    if changed {
      // Change journal bounds include adjacent edges and are quantized conservatively.
      // Refine new observations at original precision before deciding that this is a tail.
      let firstNew = try store.read { db in
        try db.rows("SELECT min(m.elapsed_seconds) AS time FROM collection_memberships m INDEXED BY membership_snapshot JOIN \(c.table) h ON h.observation_id=m.observation_id\(c.observationJoin) WHERE \(baseWhere(c)) AND m.revision>?",
          values(c, source) + [.integer(projection.revision)], limit: 1).first?.double("time")
      }
      if let firstNew {
        let last = projection.count > 0 ? projection[projection.count - 1].time : projection.start
        // Equal-time append can alter stable-identity ties, so rebuild it as a late insertion.
        guard firstNew > last else { return false }
        projection.end = min(projection.end, firstNew.nextDown)
      }
    }
    projection.revision = source.revision
    return true
  }
  /// No original strings/dictionaries survive a page. Capture can run between every page.
  private func scanProjected(_ cache: MonitorWorkspace, _ c: MonitorChannel, _ source: MonitorDescription, start: Double, end: Double,
    after: MonitorProjectedPoint? = nil, _ body: (MonitorProjectedPoint) throws -> Void) throws {
    var afterTime = after?.time ?? start, afterID = after?.id ?? -1
    while afterTime <= end {
      let rows = try store.read { db in
        if try store.isWorkoutDeleted(id: source.id) { throw PowerLogStorageError.deleted(source.id) }
        return try db.rows("SELECT m.observation_id,m.elapsed_seconds,\(tableColumn(c)) AS value FROM collection_memberships m JOIN \(c.table) h ON h.observation_id=m.observation_id\(c.observationJoin) WHERE \(baseWhere(c)) AND m.elapsed_seconds<=? AND (m.elapsed_seconds,m.observation_id)>(?,?) ORDER BY m.elapsed_seconds,m.observation_id LIMIT \(Self.pageSize)",
          values(c, source) + [.real(end), .real(afterTime), .integer(afterID)], limit: Self.pageSize)
      }
      // Instrumentation/test seam runs after SQLite ownership has been released.
      projectionPageObserver?()
      guard let last = rows.last else { return }
      cache.counters["scannedPoints", default: 0] += rows.count
      for row in rows {
        guard let id = row.int("observation_id"), let time = row.double("elapsed_seconds"), let value = row.double("value"), time.isFinite, value.isFinite else { continue }
        if time >= start && time <= end { try body(MonitorProjectedPoint(id: id, time: time, value: value, integer: row.int("value"))) }
      }
      afterTime = last.double("elapsed_seconds")!; afterID = last.int("observation_id")!
      if rows.count < Self.pageSize { return }
    }
  }
  private func project(_ cache: MonitorWorkspace, _ c: MonitorChannel, _ source: MonitorDescription, start: Double, end: Double,
    _ body: (MonitorProjectedPoint) throws -> Void) throws {
    let key = projectionKey(c, source)
    var cached = cache.projections[key]
    if let existing = cached, try start < existing.start || !projectionStillValid(existing, c, source) {
      cache.projections.removeValue(forKey: key); cached = nil; cache.counters["invalidations", default: 0] += 1
    }
    cache.tick &+= 1
    if let existing = cached {
      existing.used = cache.tick; cache.counters["projectionHits", default: 0] += 1
      let lower = existing.lowerBound(start)
      if lower < existing.count { for i in lower..<existing.count { let p = existing[i]; if p.time > end { break }; try body(p) } }
      guard end > existing.end else { return }
      // Keep the last cached point as the keyset cursor, including equal-time membership IDs.
      let after = existing.count > 0 ? existing[existing.count - 1] : nil
      let suffixStart = after?.time ?? existing.start
      var retaining = true
      do {
        try scanProjected(cache, c, source, start: suffixStart, end: end, after: after) { p in
          if retaining && !appendProjection(cache, p, to: existing) {
            cache.projections.removeValue(forKey: key); retaining = false; cache.counters["streamingFallbacks", default: 0] += 1
          }
          cache.counters["appendedPoints", default: 0] += 1
          if p.time >= start { try body(p) }
        }
        if retaining { existing.end = end }
      } catch { cache.projections.removeValue(forKey: key); throw error }
      return
    }
    // A full channel cardinality is a cheap conservative admission bound, even for a narrow
    // viewport. Oversized histories stay paged instead of repeatedly constructing doomed caches.
    let channelCount = try store.read { db in
      if c.healthSpeed { return try db.scalarInt("SELECT event_count FROM collections WHERE id=?", [.text(source.id)]) ?? 0 }
      return try db.scalarInt("SELECT COALESCE(SUM(count),0) FROM collection_channels WHERE collection_id=? AND metric=? AND source=? AND representation=?",
        [.text(source.id), .text(c.column), .text(c.source), .text(c.rawHeart ? "raw" : "")]) ?? 0
    }
    let blocks = (channelCount + Int64(MonitorProjectionBlock.capacity - 1)) / Int64(MonitorProjectionBlock.capacity)
    let estimate = blocks * Int64(MonitorProjectionBlock.bytes) + Int64(MonitorProjection.overheadBytes)
    var building: MonitorProjection?
    // Exact statistics must not evict the small navigation summaries and cause a
    // full rebuild on the next gesture, especially while a long ride is recording.
    let summaryBytes = cache.pyramids.values.reduce(0) { $0 + $1.bytes }
    if estimate + Int64(summaryBytes) <= cache.byteLimit && makeProjectionRoom(cache, MonitorProjection.overheadBytes, keeping: key, evictPyramids: false) {
      let projection = MonitorProjection(key: key, start: start, end: end, revision: source.revision)
      projection.used = cache.tick; cache.projections[key] = projection; building = projection
    } else { cache.counters["streamingFallbacks", default: 0] += 1 }
    do {
      try scanProjected(cache, c, source, start: start, end: end) { p in
        if let projection = building, !appendProjection(cache, p, to: projection) {
          cache.projections.removeValue(forKey: key); building = nil; cache.counters["streamingFallbacks", default: 0] += 1
        }
        try body(p)
      }
    } catch { cache.projections.removeValue(forKey: key); throw error }
  }
  private func projectedBefore(_ a: MonitorProjectedPoint, _ b: MonitorProjectedPoint, identities: inout [Int64: String]) throws -> Bool {
    if a.time != b.time { return a.time < b.time }
    if a.id == b.id { return false }
    if identities[a.id] == nil || identities[b.id] == nil {
      if identities.count >= Self.pageSize { identities.removeAll(keepingCapacity: true) }
      let rows = try store.read { try $0.rows("SELECT id,physical_id FROM observations WHERE id IN (?,?)", [.integer(a.id), .integer(b.id)], limit: 2) }
      for row in rows { if let id = row.int("id"), let identity = row.string("physical_id") { identities[id] = identity } }
    }
    guard let left = identities[a.id], let right = identities[b.id] else { throw MonitorError.invalid("An original chart observation is unavailable.") }
    return left < right
  }
  private func hydrate(_ cache: MonitorWorkspace, _ points: [MonitorProjectedPoint]) throws -> [Int64: MonitorOriginal] {
    var result: [Int64: MonitorOriginal] = [:]
    for offset in stride(from: 0, to: points.count, by: Self.pageSize) {
      let page = Array(points[offset..<min(points.count, offset + Self.pageSize)])
      let rows = try store.read { db in
        try db.rows("SELECT id,physical_id,original_timestamp FROM observations WHERE id IN (\(Array(repeating: "?", count: page.count).joined(separator: ",")))",
          page.map { .integer($0.id) }, limit: Self.pageSize)
      }
      cache.counters["hydratedPoints", default: 0] += rows.count
      let byID = Dictionary(uniqueKeysWithValues: page.map { ($0.id, $0) })
      for row in rows {
        if let id = row.int("id"), let p = byID[id], let identity = row.string("physical_id"), let timestamp = row.string("original_timestamp") {
          result[id] = MonitorOriginal(id: id, identity: identity, time: p.time, timestamp: timestamp, value: p.value, integer: p.integer)
        }
      }
    }
    return result
  }
  private func pyramidCacheKey(_ key: String) -> String { "lod1|" + key }
  /// Versioned, little-endian numeric cache. Only leaves are persisted; parents
  /// rebuild cheaply. Original identities/timestamps remain in canonical SQLite.
  private func savePyramid(_ value: MonitorPyramid, _ source: MonitorDescription) throws {
    guard source.phase == "completed", let last = value.lastRead else { return }
    var bytes = Data()
    bytes.reserveCapacity((9 + value.leafCount * 20) * 8 + 32)
    func word(_ number: UInt64) { var little = number.littleEndian; withUnsafeBytes(of: &little) { bytes.append(contentsOf: $0) } }
    func point(_ reference: MonitorPlotReference?) {
      guard let reference else { for _ in 0..<5 { word(0) }; return }
      let p = reference.point
      word(UInt64(p.id)); word(p.time.bitPattern)
      word(p.integer.map(UInt64.init(bitPattern:)) ?? p.value.bitPattern)
      word(p.integer == nil ? 0 : 1); word(UInt64(reference.segment))
    }
    word(0x504C4F474C4F4431); word(UInt64(Self.interpretation)); word(UInt64(value.leafCount)); word(UInt64(source.revision))
    point(MonitorPlotReference(point: last, segment: value.segment))
    for leaf in value.leafCount..<(value.leafCount * 2) {
      let bucket = value.nodes[leaf]
      point(bucket.first); point(bucket.last); point(bucket.low); point(bucket.high)
    }
    let checksum = SHA256.hash(data: bytes)
    bytes.append(contentsOf: checksum)
    try cacheBytes(bytes, key: pyramidCacheKey(value.key), source: source)
  }
  private func loadPyramid(_ cache: MonitorWorkspace, _ key: String, _ source: MonitorDescription) throws -> MonitorPyramid? {
    guard source.phase == "completed" else { return nil }
    let count = MonitorPyramid.leaves(source.end), size = (9 + count * 20) * 8 + 32
    guard let bytes = try store.read({ db in
      try db.rows("SELECT value FROM derived_cache WHERE key=? AND revision=? AND interpretation=? AND length(value)=?",
        [.text(pyramidCacheKey(key)), .integer(source.revision), .text(String(Self.interpretation)), .integer(Int64(size))], limit: 1).first?.data("value")
    }) else { return nil }
    guard Data(SHA256.hash(data: bytes.dropLast(32))) == bytes.suffix(32) else { return nil }
    var offset = 0, invalid = false
    func word() -> UInt64 {
      defer { offset += 8 }
      return bytes.withUnsafeBytes { UInt64(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self)) }
    }
    guard word() == 0x504C4F474C4F4431, word() == UInt64(Self.interpretation), word() == UInt64(count), word() == UInt64(source.revision) else { return nil }
    func point() -> MonitorPlotReference? {
      let id = word(), time = Double(bitPattern: word()), bits = word(), integer = word(), segment = word()
      if id == 0 { return nil }
      let number = integer == 1 ? Double(Int64(bitPattern: bits)) : Double(bitPattern: bits)
      guard id <= UInt64(Int64.max), time.isFinite, time >= 0, time <= source.end, integer <= 1, number.isFinite, segment <= UInt64(Int.max) else { invalid = true; return nil }
      return MonitorPlotReference(point: MonitorProjectedPoint(id: Int64(id), time: time, value: number, integer: integer == 1 ? Int64(bitPattern: bits) : nil), segment: Int(segment))
    }
    guard let last = point() else { return nil }
    let value = MonitorPyramid(key: key, end: source.end, revision: source.revision)
    value.lastRead = last.point; value.segment = last.segment
    for leaf in count..<(count * 2) {
      let bucket = MonitorPlotBucket(first: point(), last: point(), low: point(), high: point())
      let lower = Double(leaf - count) * MonitorPyramid.seconds
      for ref in bucket.points { if ref.point.time < lower || ref.point.time >= lower + MonitorPyramid.seconds { invalid = true } }
      value.nodes[leaf] = bucket
    }
    guard !invalid else { return nil }
    var identities: [Int64: String] = [:]
    try value.rebuild(from: 0, through: count - 1) { try projectedBefore($0, $1, identities: &identities) }
    cache.counters["pyramidDiskHits", default: 0] += 1
    return value
  }

  private func pyramid(_ cache: MonitorWorkspace, _ c: MonitorChannel, _ source: MonitorDescription, width: Double) throws -> MonitorPyramid? {
    guard width >= MonitorPyramid.seconds else { return nil }
    let key = projectionKey(c, source)
    var existing = cache.pyramids[key]
    if let value = existing, value.revision != source.revision {
      // Reuse only a complete append-only proof. Late/corrected samples and changed
      // source semantics rebuild the tree; never silently keep stale extrema.
      let changes = try store.read { db in
        try db.rows("SELECT revision,kind,metrics FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT 512",
          [.text(source.id), .integer(value.revision), .integer(source.revision)], limit: 512)
      }
      var valid = changes.count == source.revision - value.revision && changes.first?.int("revision") == value.revision + 1 && changes.last?.int("revision") == source.revision
      for row in changes {
        guard let data = row.data("metrics"), let metrics = try? JSONDecoder().decode([String].self, from: data) else { valid = false; break }
        if (c.healthSpeed || metrics.isEmpty || metrics.contains(c.column)) && row.string("kind") != "append" { valid = false }
      }
      if valid, let last = value.lastRead {
        let firstNew = try store.read { db in
          try db.rows("SELECT min(m.elapsed_seconds) AS time FROM collection_memberships m INDEXED BY membership_snapshot JOIN \(c.table) h ON h.observation_id=m.observation_id\(c.observationJoin) WHERE \(baseWhere(c)) AND m.revision>?",
            values(c, source) + [.integer(value.revision)], limit: 1).first?.double("time")
        }
        if let firstNew, firstNew <= last.time { valid = false }
      }
      if !valid { cache.pyramids.removeValue(forKey: key); existing = nil; cache.counters["pyramidInvalidations", default: 0] += 1 }
    }
    if let value = existing, source.end >= Double(value.leafCount) * MonitorPyramid.seconds {
      cache.pyramids.removeValue(forKey: key); existing = nil
    }
    if existing == nil {
      let count = try store.read { db in
        if c.healthSpeed { return try db.scalarInt("SELECT event_count FROM collections WHERE id=?", [.text(source.id)]) ?? 0 }
        return try db.scalarInt("SELECT COALESCE(SUM(count),0) FROM collection_channels WHERE collection_id=? AND metric=? AND source=? AND representation=?",
          [.text(source.id), .text(c.column), .text(c.source), .text(c.rawHeart ? "raw" : "")]) ?? 0
      }
      guard count >= 16_384, makeProjectionRoom(cache, MonitorPyramid.estimate(source.end), keeping: key) else { return nil }
      let value = try loadPyramid(cache, key, source) ?? MonitorPyramid(key: key, end: source.end, revision: source.revision)
      guard cache.bytes + value.bytes <= cache.byteLimit else { return nil }
      cache.pyramids[key] = value; existing = value; cache.counters["pyramidBuilds", default: 0] += 1
      cache.counters["peakAllocatedBytes"] = max(cache.counters["peakAllocatedBytes", default: 0], cache.bytes)
    }
    guard let value = existing else { return nil }
    cache.tick &+= 1; value.used = cache.tick
    if value.lastRead == nil || value.revision != source.revision {
      let after = value.lastRead, firstLeaf = Int((after?.time ?? 0) / MonitorPyramid.seconds)
      var identities: [Int64: String] = [:]
      do {
        try scanProjected(cache, c, source, start: after?.time ?? 0, end: source.end, after: after) { p in
          try value.append(p, gap: Self.gap(c.metric), course: c.metric == "courseDegrees") { try projectedBefore($0, $1, identities: &identities) }
          cache.counters["pyramidOriginalPoints", default: 0] += 1
        }
        if let last = value.lastRead {
          try value.rebuild(from: firstLeaf, through: Int(last.time / MonitorPyramid.seconds)) { try projectedBefore($0, $1, identities: &identities) }
        }
        value.revision = source.revision
        try savePyramid(value, source)
      } catch { cache.pyramids.removeValue(forKey: key); throw error }
    }
    return value
  }
  private func reduced(_ cache: MonitorWorkspace, _ pyramid: MonitorPyramid, _ c: MonitorChannel, _ source: MonitorDescription,
    start: Double, end: Double, count: Int) throws -> [Int64: MonitorPlotReference] {
    var identities: [Int64: String] = [:]
    func merge(_ into: inout MonitorPlotBucket, _ points: [MonitorPlotReference]) throws {
      for point in points { try into.add(point) { try projectedBefore($0, $1, identities: &identities) } }
    }
    func collect(_ node: Int, lower: Double, span: Double, into: inout MonitorPlotBucket) throws {
      let bucket = pyramid.nodes[node]
      guard let first = bucket.first, let last = bucket.last, last.point.time >= start, first.point.time <= end else { return }
      if first.point.time >= start && last.point.time <= end {
        cache.counters["pyramidNodes", default: 0] += 1
        try merge(&into, bucket.points); return
      }
      if node < pyramid.leafCount {
        try collect(node * 2, lower: lower, span: span / 2, into: &into)
        try collect(node * 2 + 1, lower: lower + span / 2, span: span / 2, into: &into)
      } else {

        var previous: MonitorProjectedPoint?, segment = first.segment
        try scanProjected(cache, c, source, start: lower, end: min(source.end, (lower + span).nextDown)) { p in
          if let previous, p.time - previous.time + 1e-9 >= Self.gap(c.metric) || (c.metric == "courseDegrees" && abs(p.value - previous.value) > 180) { segment += 1 }
          previous = p
          if p.time >= start && p.time <= end { try merge(&into, [MonitorPlotReference(point: p, segment: segment)]) }
          cache.counters["pyramidEdgePoints", default: 0] += 1
        }
      }
    }
    var span = MonitorPyramid.seconds, base = pyramid.leafCount
    while span < (end - start) / Double(count) && base > 1 { span *= 2; base /= 2 }
    let first = min(base - 1, Int(start / span)), last = min(base - 1, Int(end / span))
    var retained: [Int64: MonitorPlotReference] = [:]
    if first <= last {
      for index in first...last {
        var bucket = MonitorPlotBucket()
        try collect(base + index, lower: Double(index) * span, span: span, into: &bucket)
        for point in bucket.points { retained[point.point.id] = point }
      }
    }
    cache.counters["pyramidReads", default: 0] += 1
    return retained
  }
  func readLatest(_ request: MonitorRequest) throws -> [String: Any] {
    let source = try description(request)
    return try retryDistance(request, source: source) { try readLatest(request, source: source) }
  }
  private func readLatest(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    var source = source
    var distance: WorkoutDistanceSnapshot?
    if request.metrics.contains("distanceMeters") {
      distance = try distanceSnapshot(source, build: false)
      if distance == nil { distance = try distanceStore.latestCachedSnapshot(id: source.id, selection: source.distanceSelection) }
      guard let distance else { throw WorkoutDistanceError.pending }
      if distance.revision != source.revision {
        // A live producer may advance on every read. Return one coherent older snapshot for
        // all requested metrics instead of mixing the old distance with newer sensor points.
        let metadata = try store.read { db in
          let bytes = try db.rows("SELECT metadata FROM collection_versions WHERE collection_id=? AND revision<=? ORDER BY revision DESC LIMIT 1", [.text(source.id), .integer(distance.revision)], limit: 1).first?.data("metadata")
          return (bytes.flatMap { try? JSONSerialization.jsonObject(with: $0) }) as? [String: Any] ?? [:]
        }
        source = MonitorDescription(id: source.id, sourceID: source.sourceID, revision: distance.revision, startedAt: source.startedAt,
          end: max(10, distance.endSeconds), phase: metadata["phase"] as? String ?? source.phase, metadata: metadata, available: source.available, distanceSelection: source.distanceSelection)
      }
    }
    var points: [String: Any] = [:]
    for metric in Set(request.metrics).sorted() {
      if metric == "distanceMeters", let distance {
        points[metric] = try distanceStore.neighbor(snapshot: distance, seconds: Self.maximumSeconds, before: true)?.dictionary ?? NSNull() as Any
        continue
      }
      guard let channel = try channel(metric, source: source) else { points[metric] = NSNull(); continue }
      var point = try neighbor(channel, source, time: Self.maximumSeconds, before: true)
      if let fallback = channel.latestFallback {
        if let newer = try neighbor(fallback, source, time: Self.maximumSeconds, before: true), newer.time > (point?.time ?? -.infinity) { point = newer }
      }
      points[metric] = point?.dictionary ?? NSNull() as Any
    }
    var result = envelope(request, source); result["points"] = points
    if let distance { result["metricSources"] = distanceMetricSources(distance) }
    return try published(result, request: request, source: source)
  }
  func inspectAt(_ request: MonitorRequest) throws -> [String: Any] {
    let source = try description(request)
    return try retryDistance(request, source: source) { try inspectAt(request, source: source) }
  }
  private func inspectAt(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    if let retry = mismatch(request, source) { return retry }
    guard let time = request.seconds else { throw MonitorError.invalid("Choose an inspection time.") }
    var points: [String: Any] = [:], gaps: [String: Bool] = [:]
    var distance: WorkoutDistanceSnapshot?
    if request.metrics.contains("distanceMeters") {
      distance = try distanceSnapshot(source, build: false)
      guard distance != nil else { throw WorkoutDistanceError.pending }
    }
    for metric in Set(request.metrics).sorted() {
      if metric == "distanceMeters", let distance {
        let selected = try inspectDistance(distance, time: time, anchor: request.anchor?.metric == metric ? request.anchor : nil)
        points[metric] = selected?.dictionary ?? NSNull() as Any; gaps[metric] = selected == nil
        continue
      }
      var selected: MonitorOriginal?
      if let c = try channel(metric, source: source) {
        if let anchor = request.anchor, anchor.metric == metric {
          selected = try anchoredOriginal(anchor, c, source, time: time)
        } else {
          let before = try neighbor(c, source, time: time, before: true)
          let after = try neighbor(c, source, time: time, before: false)
          let nearest = [before, after].compactMap { $0 }.min {
            abs($0.time - time) == abs($1.time - time) ? MonitorOriginal.before($0, $1) : abs($0.time - time) < abs($1.time - time)
          }
          if let nearest, abs(nearest.time - time) <= 1e-6 { selected = nearest }
          else if let before {
            let trailing = request.source == "live" || ["running", "paused"].contains(source.phase)
            if (after != nil || trailing), (after.map { $0.time - before.time + 1e-9 < Self.gap(metric) } ?? true) {
              selected = [before, after].compactMap { $0 }.filter { abs($0.time - time) + 1e-9 < Self.gap(metric) }.min {
                abs($0.time - time) == abs($1.time - time) ? MonitorOriginal.before($0, $1) : abs($0.time - time) < abs($1.time - time)
              }
            }
          }
        }
      }
      points[metric] = selected?.dictionary ?? NSNull() as Any; gaps[metric] = selected == nil
    }
    var result = envelope(request, source); result["seconds"] = time; result["points"] = points; result["gaps"] = gaps
    if let distance { result["metricSources"] = distanceMetricSources(distance) }
    return try published(result, request: request, source: source)
  }
  private func inspectDistance(_ snapshot: WorkoutDistanceSnapshot, time: Double, anchor: MonitorObservationAnchor?) throws -> WorkoutDistancePoint? {
    if let anchor {
      guard let point = try distanceStore.anchor(snapshot: snapshot, identity: anchor.observationId), abs(point.elapsedSeconds - time) <= 1e-6 else { return nil }
      return point
    }
    let before = try distanceStore.neighbor(snapshot: snapshot, seconds: time, before: true)
    let after = try distanceStore.neighbor(snapshot: snapshot, seconds: time, before: false)
    func nearer(_ left: WorkoutDistancePoint, _ right: WorkoutDistancePoint) -> Bool {
      let a = abs(left.elapsedSeconds - time), b = abs(right.elapsedSeconds - time)
      return a != b ? a < b : (left.elapsedSeconds != right.elapsedSeconds ? left.elapsedSeconds < right.elapsedSeconds : left.pointID < right.pointID)
    }
    if let exact = [before, after].compactMap({ $0 }).filter({ abs($0.elapsedSeconds - time) <= 1e-6 }).min(by: nearer) { return exact }
    guard let before, let after, before.segment == after.segment,
      after.endSeconds > after.startSeconds, time >= after.startSeconds, time <= after.endSeconds else { return nil }
    // Inspect actual derived boundaries with their true time; never interpolate a Health amount.
    return nearer(before, after) ? before : after
  }
  private func distancePlot(_ snapshot: WorkoutDistanceSnapshot, start: Double, end: Double, count: Int) throws -> [[String: Any]] {
    guard start <= end else { return [] }
    let width = (end - start) / Double(count)
    var retained: [Int64: WorkoutDistancePoint] = [:]
    // Nonnegative increments make the cumulative profile monotone: exact M4 extrema
    // are the first/last points. Two indexed boundary reads per pixel bucket avoid
    // scanning an eight-hour profile whenever the visible range changes.
    for index in 0..<(width > 0 ? count : 1) {
      let left = start + Double(index) * width
      let right = width == 0 || index == count - 1 ? end : (start + Double(index + 1) * width).nextDown
      if let first = try distanceStore.neighbor(snapshot: snapshot, seconds: left, before: false), first.elapsedSeconds <= right { retained[first.pointID] = first }
      if let last = try distanceStore.neighbor(snapshot: snapshot, seconds: right, before: true), last.elapsedSeconds >= left { retained[last.pointID] = last }
    }
    if let before = try distanceStore.neighbor(snapshot: snapshot, seconds: start, before: true, strict: true) { retained[before.pointID] = before }
    if let after = try distanceStore.neighbor(snapshot: snapshot, seconds: end, before: false, strict: true) { retained[after.pointID] = after }
    var previousSegment: Int?
    return retained.values.sorted { $0.elapsedSeconds != $1.elapsedSeconds ? $0.elapsedSeconds < $1.elapsedSeconds : $0.pointID < $1.pointID }.map { point in
      var row = point.dictionary
      row["startsSegment"] = previousSegment == nil || previousSegment != point.segment
      previousSegment = point.segment
      return row
    }
  }
  func readPlot(_ request: MonitorRequest) throws -> [String: Any] {
    let source = try description(request)
    return try retryDistance(request, source: source) { try readPlot(request, source: source) }
  }
  private func readPlot(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    // Direct native callers share the bridge's one-active-plot admission rule.
    let cache = plotWorkspace
    guard cache.lock.try() else { throw MonitorError.cacheContention("plot") }
    defer { releaseWorkspace(cache, source: source) }
    if let retry = mismatch(request, source) { return retry }
    let start = request.startSeconds ?? 0, end = min(source.end, request.endSeconds ?? source.end)
    let metrics = Set(request.metrics).sorted()
    let distance = metrics.contains("distanceMeters") ? try distanceSnapshot(source, build: true) : nil
    let count = max(1, min(request.buckets, Self.maximumPlotPoints / max(1, metrics.count) / 4 - 2))
    let key = [source.id, String(source.revision), String(Self.interpretation), distance?.generation ?? "", distance?.selection ?? "", String(start), String(end), String(count), metrics.joined(separator: ",")].joined(separator: "|")
    let persistOverview = source.phase == "completed" && start == 0 && end == source.end
    if persistOverview, let cached = try cachedPlot(key, source: source) {
      var result = cached; result["generation"] = request.generation
      return try published(result, request: request, source: source)
    }
    var series: [String: [[String: Any]]] = [:], latest: [String: Any] = [:]
    for metric in metrics {
      series[metric] = []; latest[metric] = NSNull()
      if metric == "distanceMeters", let distance {
        series[metric] = try distancePlot(distance, start: start, end: end, count: count)
        latest[metric] = try distanceStore.neighbor(snapshot: distance, seconds: Self.maximumSeconds, before: true)?.dictionary ?? NSNull() as Any
        continue
      }
      guard let c = try channel(metric, source: source) else { continue }
      var latestPoint = try neighbor(c, source, time: Self.maximumSeconds, before: true)
      if let snapshots = c.latestFallback {
        if let p = try neighbor(snapshots, source, time: Self.maximumSeconds, before: true), p.time > (latestPoint?.time ?? -.infinity) { latestPoint = p }
      }
      latest[metric] = latestPoint?.dictionary ?? NSNull() as Any
      guard start <= end else { continue }
      let predecessor = try neighbor(c, source, time: start, before: true, strict: true)
      var previousTime = predecessor?.time, previousValue = predecessor?.value, segment = 0, predecessorSegment = 0
      let width = (end - start) / Double(count)
      var retained: [Int64: MonitorPlotReference] = [:]
      if let hierarchy = try pyramid(cache, c, source, width: width) {
        retained = try reduced(cache, hierarchy, c, source, start: start, end: end, count: count)
        let ordered = retained.values.sorted { $0.point.time < $1.point.time }
        if let first = ordered.first { predecessorSegment = first.segment - (predecessor.map { first.point.time - $0.time + 1e-9 >= Self.gap(metric) || (metric == "courseDegrees" && abs(first.point.value - $0.value) > 180) } == true ? 1 : 0) }
        if let last = ordered.last { segment = last.segment; previousTime = last.point.time; previousValue = last.point.value }
      } else {
        var bins = Array(repeating: MonitorPlotBucket(), count: count)
        var tieIdentities: [Int64: String] = [:]
        try project(cache, c, source, start: start, end: end) { p in
          if let previousTime, p.time - previousTime + 1e-9 >= Self.gap(metric) || (metric == "courseDegrees" && previousValue.map { abs(p.value - $0) > 180 } == true) { segment += 1 }
          let slot = width > 0 ? min(count - 1, max(0, Int((p.time - start) / width))) : 0
          try bins[slot].add(MonitorPlotReference(point: p, segment: segment)) { try projectedBefore($0, $1, identities: &tieIdentities) }
          previousTime = p.time; previousValue = p.value
        }
        for bin in bins { for p in bin.points { retained[p.point.id] = p } }
      }
      var originals = try hydrate(cache, retained.values.map(\.point))
      var segments = retained.mapValues(\.segment)
      if let predecessor { originals[predecessor.id] = predecessor; segments[predecessor.id] = predecessorSegment }
      if let successor = try neighbor(c, source, time: end, before: false, strict: true) {
        if let previousTime, successor.time - previousTime + 1e-9 >= Self.gap(metric) || (metric == "courseDegrees" && previousValue.map { abs(successor.value - $0) > 180 } == true) { segment += 1 }
        originals[successor.id] = successor; segments[successor.id] = segment
      }
      var previousSegment: Int?
      series[metric] = originals.values.sorted(by: MonitorOriginal.before).map { p in
        var row = p.dictionary
        row["startsSegment"] = previousSegment == nil || previousSegment != segments[p.id]
        previousSegment = segments[p.id]
        return row
      }
    }
    var result = envelope(request, source); result["series"] = series; result["latest"] = latest
    if let distance { result["metricSources"] = distanceMetricSources(distance) }
    let published = try published(result, request: request, source: source)
    if persistOverview, published["status"] as? String == "ok" { try cachePlot(result, key: key, source: source) }
    return published
  }
  private func cachedPlot(_ key: String, source: MonitorDescription) throws -> [String: Any]? {
    try store.read { db in
      guard let bytes = try db.rows("SELECT value FROM derived_cache WHERE key=? AND revision=? AND interpretation=?", [.text(key), .integer(source.revision), .text(String(Self.interpretation))], limit: 1).first?.data("value"), bytes.count <= 4 * 1024 * 1024 else { return nil }
      return try JSONSerialization.jsonObject(with: bytes) as? [String: Any]
    }
  }
  private func cachePlot(_ value: [String: Any], key: String, source: MonitorDescription) throws {
    let bytes = try JSONSerialization.data(withJSONObject: value)
    try cacheBytes(bytes, key: key, source: source)
  }
  private func cacheBytes(_ bytes: Data, key: String, source: MonitorDescription) throws {
    guard bytes.count <= 4 * 1024 * 1024 else { return }
    try store.transaction(priority: .background) { db in
      if try store.isWorkoutDeleted(id: source.id) { throw PowerLogStorageError.deleted(source.id) }
      try db.execute("DELETE FROM derived_cache WHERE collection_id=? AND revision<>?", [.text(source.id), .integer(source.revision)])
      let used = try db.scalarInt("SELECT COALESCE(SUM(length(value)),0) FROM derived_cache") ?? 0
      if used + Int64(bytes.count) > Int64(Self.maximumCachedBytes) { try db.execute("DELETE FROM derived_cache") }
      try db.execute("INSERT OR REPLACE INTO derived_cache(key,collection_id,revision,interpretation,value) VALUES(?,?,?,?,?)", [.text(key), .text(source.id), .integer(source.revision), .text(String(Self.interpretation)), .blob(bytes)])
    }
  }
  func rangeStats(_ request: MonitorRequest) throws -> [String: Any] {
    let source = try description(request)
    return try retryDistance(request, source: source) { try rangeStats(request, source: source) }
  }
  private func rangeStats(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    let cache = statisticsWorkspace
    guard cache.lock.try() else { throw MonitorError.cacheContention("statistics") }
    defer { releaseWorkspace(cache, source: source) }
    if let retry = mismatch(request, source) { return retry }
    guard let start = request.startSeconds, let end = request.endSeconds else { throw MonitorError.invalid("Choose the statistics interval.") }
    let intervals = try activeIntervals(source, sourceKind: request.source)
    let distance = request.metrics.contains("distanceMeters") ? try distanceSnapshot(source, build: true) : nil
    var stats: [String: Any] = [:]
    for metric in Set(request.metrics).sorted() {
      if metric == "distanceMeters", let distance {
        let range = try distanceStore.range(snapshot: distance, start: start, end: end)
        var value: [String: Any] = ["coveredSeconds": range.coveredSeconds, "unresolvedBoundary": range.unresolvedBoundary, "partial": range.partial]
        if let meters = range.distanceMeters { value["distance"] = meters }
        stats[metric] = value
        continue
      }
      guard let c = try channel(metric, source: source) else { continue }
      var bounds = MonitorPlotBucket(), count = 0, total = 0.0, covered = 0.0, integral = 0.0
      var identities: [Int64: String] = [:]
      var previous = try neighbor(c, source, time: start, before: true, strict: true).map {
        MonitorProjectedPoint(id: $0.id, time: $0.time, value: $0.value, integer: $0.integer)
      }
      let maximumEdge = integrationGap(metric)
      let discrete = ["assistLevel", "raceMode", "faultCode"].contains(metric)
      var activeIndex = 0, edgeIndex = 0
      func active(_ time: Double) -> Bool {
        guard !intervals.isEmpty else { return false }
        while activeIndex + 1 < intervals.count && time >= intervals[activeIndex].1 { activeIndex += 1 }
        let interval = intervals[activeIndex]
        return time >= interval.0 && (time < interval.1 || (time == source.end && time == interval.1))
      }
      func addEdge(_ p: MonitorProjectedPoint) {
        defer { previous = p }
        guard let previous, p.time > previous.time, p.time - previous.time <= maximumEdge, !intervals.isEmpty else { return }
        // Both streams are ordered; lifecycle work is linear in transitions plus points.
        while edgeIndex + 1 < intervals.count && p.time > intervals[edgeIndex].1 { edgeIndex += 1 }
        let interval = intervals[edgeIndex]
        guard previous.time >= interval.0 && p.time <= interval.1 else { return }
        let a = max(start, previous.time, interval.0), b = min(end, p.time, interval.1)
        guard b > a else { return }
        let va = previous.value + (p.value - previous.value) * (a - previous.time) / (p.time - previous.time)
        let vb = previous.value + (p.value - previous.value) * (b - previous.time) / (p.time - previous.time)
        covered += b - a
        integral += (discrete ? previous.value : (va + vb) / 2) * (b - a)
      }
      try project(cache, c, source, start: start, end: end) { p in
        if active(p.time) {
          try bounds.add(MonitorPlotReference(point: p, segment: 0)) { try projectedBefore($0, $1, identities: &identities) }
          count += 1; total += p.value
        }
        addEdge(p)
      }
      if let after = try neighbor(c, source, time: end, before: false, strict: true) {
        addEdge(MonitorProjectedPoint(id: after.id, time: after.time, value: after.value, integer: after.integer))
      }
      if let low = bounds.low?.point, let high = bounds.high?.point {
        let originals = try hydrate(cache, low.id == high.id ? [low] : [low, high])
        guard let minimum = originals[low.id], let maximum = originals[high.id] else { throw MonitorError.invalid("An original chart extremum is unavailable.") }
        var value: [String: Any] = ["min": minimum.dictionary, "max": maximum.dictionary, "count": count]
        // A cumulative/counter mean, degree average, or category integral has no ride meaning.
        if !["activeEnergyKcal", "basalEnergyKcal", "consumedAh", "consumedWh", "assistLevel", "raceMode", "faultCode", "courseDegrees"].contains(metric) {
          value["sampleMean"] = total / Double(count); value["coveredSeconds"] = covered; value["integral"] = integral
        }
        stats[metric] = value
      }
    }
    var result = envelope(request, source); result["statistics"] = stats
    if let distance { result["metricSources"] = distanceMetricSources(distance) }
    if request.includeEndpoints {
      var endpoint = request; endpoint.seconds = start; endpoint.anchor = request.startAnchor
      let first = try inspectAt(endpoint, source: source)["points"] ?? [:]
      endpoint.seconds = end; endpoint.anchor = request.endAnchor
      let last = try inspectAt(endpoint, source: source)["points"] ?? [:]
      result["endpoints"] = ["start": first, "end": last]
    }
    return try published(result, request: request, source: source)
  }
  private func integrationGap(_ metric: String) -> Double {
    if metric == "humanPowerW" || metric == "cadenceRpm" { return 2.5 }
    if metric == "heartRateBpm" { return 10 }
    return Self.gap(metric)
  }
  private func activeIntervals(_ source: MonitorDescription, sourceKind: String) throws -> [(Double, Double)] {
    guard sourceKind == "workout" else { return [(0, Self.maximumSeconds)] }
    var events: [(Double, String, String)] = []
    var after: Int64 = 0
    while true {
      let rows = try store.read { db in
        try db.rows("SELECT m.id,m.elapsed_seconds,m.event_id,l.action FROM collection_memberships m INDEXED BY membership_stream_time JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND m.id>? ORDER BY m.id LIMIT 256", [.text(source.id), .integer(source.revision), .integer(source.revision), .integer(source.revision), .integer(after)], limit: 256)
      }
      guard let last = rows.last else { break }
      for row in rows {
        guard events.count < 10_000 else { throw MonitorError.invalid("Too many lifecycle transitions.") }
        events.append((row.double("elapsed_seconds") ?? 0, row.string("action")!, row.string("event_id")!))
      }
      after = last.int("id")!
      if rows.count < 256 { break }
    }
    events.sort { $0.0 != $1.0 ? $0.0 < $1.0 : $0.2 < $1.2 }
    var cutoff = source.end
    if let stop = source.metadata["stopElapsedSeconds"] as? Double { cutoff = min(cutoff, stop) }
    else if let ended = source.metadata["endedAt"] as? String {
      cutoff = min(cutoff, max(0, try WorkoutCoding.date(ended).timeIntervalSince(WorkoutCoding.date(source.startedAt))))
    }
    var open: Double? = 0, result: [(Double, Double)] = []
    for (time, action, _) in events {
      if action == "pause", let start = open { result.append((start, time)); open = nil }
      if action == "resume", open == nil { open = time }
      if action == "stop" { cutoff = min(cutoff, time) }
    }
    if let start = open { result.append((start, cutoff)) }
    return result.compactMap { let end = min(cutoff, $0.1); return end >= $0.0 ? ($0.0, end) : nil }
  }
  func changesSince(_ request: MonitorRequest) throws -> [String: Any] {
    try changesSince(request, source: description(request))
  }
  private func changesSince(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    guard let text = request.sinceRevision, let token = MonitorRevisionToken.parse(text) else { throw MonitorError.invalid("A source revision is required.") }
    if token.selection != source.distanceSelection {
      var result = envelope(request, source); result["resetRequired"] = true; result["changes"] = [[String: Any]](); return result
    }
    let since = token.revision
    let rows = try store.read { db in
      try db.rows("SELECT revision,min_us,max_us,kind,metrics FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT 512", [.text(source.id), .integer(since), .integer(source.revision)], limit: 512)
    }
    var result = envelope(request, source)
    result["resetRequired"] = since > source.revision || (since < source.revision && ((rows.first?.int("revision") ?? source.revision + 1) > since + 1 || (rows.last?.int("revision") ?? -1) < source.revision))
    result["changes"] = rows.map { row -> [String: Any] in
      var change: [String: Any] = ["startSeconds": Double(row.int("min_us") ?? 0) / 1e6, "endSeconds": Double(row.int("max_us") ?? Int64(source.end * 1e6)) / 1e6, "kind": ["append", "correction"].contains(row.string("kind") ?? "") ? row.string("kind")! : "semantics"]
      if let bytes = row.data("metrics"), let metrics = try? JSONDecoder().decode([String].self, from: bytes) {
        // Empty is the existing all-metrics sentinel, including metadata/lifecycle changes.
        // Preserve it instead of narrowing a broad invalidation to only derived metrics.
        guard !metrics.isEmpty else { change["metrics"] = [String](); return change }
        var affected = Set(metrics)
        // Derivations depend on source columns; callers need not know that dependency graph.
        if metrics.isEmpty || !affected.isDisjoint(with: ["latitude", "longitude", "horizontalAccuracyM", "speedMps", "speedAccuracyMps", "controllerSpeedMps", "distanceMeters"]) { affected.insert("distanceMeters") }
        if metrics.isEmpty || affected.contains("speedMps") { affected.insert("healthSpeedMps") }
        if affected.contains("courseAccuracyDegrees") { affected.insert("courseDegrees") }
        if affected.contains("speedAccuracyMps") { affected.insert("speedMps") }
        change["metrics"] = affected.sorted()
      }
      return change
    }
    return try published(result, request: request, source: source)
  }
}
