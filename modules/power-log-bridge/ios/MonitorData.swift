import Foundation
import CryptoKit
import PowerLogCore

enum MonitorError: Error, LocalizedError {
  case invalid(String)
  case chartCapacity
  case admission(String), cacheContention(String)
  var bridgeCode: String? {
    switch self {
    case .chartCapacity: return "ERR_CHART_CAPACITY"
    case .admission: return "ERR_READ_DEFERRED"
    case .cacheContention: return "ERR_READ_CONTENTION"
    case .invalid: return nil
    }
  }
  var bridgeMessage: String? {
    if case .chartCapacity = self { return errorDescription }
    return nil
  }
  var errorDescription: String? {
    switch self {
    case .invalid(let value): return value
    case .chartCapacity: return "Choose a smaller range with fewer chart discontinuities."
    case .admission(let lane): return "MONITOR_ADMISSION_\(lane.uppercased()): monitor work is deferred."
    case .cacheContention(let lane): return "MONITOR_CACHE_\(lane.uppercased()): another direct read owns this cache."
    }
  }
}

func readErrorCode(_ error: Error) -> String? {
  if let monitor = error as? MonitorError { return monitor.bridgeCode }
  return (error as? PowerLogStorageError)?.bridgeCode
}

struct MonitorObservationAnchor {
  let metric: String
  let observationId: String

  func validate(metrics: [String], seconds: Double?) throws {
    guard seconds != nil, metrics.contains(metric), MonitorDataStore.metrics.contains(metric),
      !observationId.isEmpty, observationId.utf8.count <= 512,
      observationId.rangeOfCharacter(from: .controlCharacters) == nil
    else {
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
      DistancePolicy.shared.validSelection(selection: distanceSource)
    else { throw MonitorError.invalid("Invalid monitor request.") }
    for value in [startSeconds, endSeconds, seconds, pixelWidth].compactMap({ $0 }) {
      guard value.isFinite, value >= 0, value <= MonitorDataStore.maximumSeconds else {
        throw MonitorError.invalid("Invalid monitor range.")
      }
    }
    if let startSeconds, let endSeconds, startSeconds > endSeconds {
      throw MonitorError.invalid("Invalid monitor range.")
    }
    for revision in [expectedRevision, sinceRevision].compactMap({ $0 }) {
      guard MonitorRevisionToken.parse(revision) != nil else { throw MonitorError.invalid("Invalid source revision.") }
    }
    try anchor?.validate(metrics: metrics, seconds: seconds)
    try startAnchor?.validate(metrics: metrics, seconds: startSeconds)
    try endAnchor?.validate(metrics: metrics, seconds: endSeconds)
  }
}

private enum MonitorRevisionToken {
  static func value(_ revision: Int64, selection: String) -> String { String(revision) }
  static func parse(_ value: String) -> (revision: Int64, selection: String)? {
    guard let revision = Int64(value), revision >= 0 else { return nil }
    return (revision, "auto")
  }
}

enum MonitorReadOperation {
  case describe, plot, inspect, stats, changes, latest
  var analytical: Bool {
    switch self {
    case .plot, .stats: return true
    default: return false
    }
  }
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
    var result: [String: Any] = [
      "observationId": identity, "elapsedSeconds": time, "timestamp": timestamp, "value": value,
    ]
    if let integer { result["exactValue"] = String(integer) }
    return result
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

private enum MonitorScalarKind: UInt8, Codable { case real, integer, missing }
private final class MonitorPointEvidence: Codable, Equatable {
  let interruption: Int
  let clockEpoch: String?
  let connectionEpoch: String?
  let kind: MonitorScalarKind
  let accuracyBits: UInt64
  var producer: String?
  var sequence: Int64?
  var boundary = false
  var bytes: Int { 128 + (clockEpoch?.utf8.count ?? 0) + (connectionEpoch?.utf8.count ?? 0) }
  init(
    interruption: Int, clockEpoch: String?, connectionEpoch: String?, kind: MonitorScalarKind = .real,
    accuracyBits: UInt64 = Double.nan.bitPattern
  ) {
    self.interruption = interruption
    self.clockEpoch = clockEpoch
    self.connectionEpoch = connectionEpoch
    self.kind = kind
    self.accuracyBits = accuracyBits
  }
  static func == (a: MonitorPointEvidence, b: MonitorPointEvidence) -> Bool {
    a.interruption == b.interruption && a.clockEpoch == b.clockEpoch && a.connectionEpoch == b.connectionEpoch
      && a.producer == b.producer && a.sequence == b.sequence && a.boundary == b.boundary
      && a.kind == b.kind && a.accuracyBits == b.accuracyBits
  }
}
private struct MonitorNumericOriginal {
  let id: Int64
  let producer: String
  let sequence: Int64
  let time: Double
  let timeUs: Int64
  let clockEpoch: String?
  let connectionEpoch: String?
  let accuracy: Double?
  let value: Double?
  let integer: Int64?
}
private struct MonitorProjectedPoint {
  let id: Int64
  let timeUs: Int64
  private let bits: UInt64
  var continuity: MonitorPointEvidence?
  let time: Double
  var producer: String { continuity?.producer ?? "" }
  var sequence: Int64 { continuity?.sequence ?? 0 }
  var accuracy: Double? {
    let value = Double(bitPattern: continuity?.accuracyBits ?? Double.nan.bitPattern)
    return value.isNaN ? nil : value
  }
  var missing: Bool { continuity?.kind == .missing }
  var boundary: Bool { continuity?.boundary ?? false }
  var integer: Int64? { continuity?.kind == .integer ? Int64(bitPattern: bits) : nil }
  var value: Double { integer.map(Double.init) ?? Double(bitPattern: bits) }
  init(
    id: Int64, time: Double, value: Double, integer: Int64? = nil, continuity: MonitorPointEvidence? = nil,
    timeUs: Int64? = nil, producer: String = "", sequence: Int64 = 0, accuracy: Double? = nil,
    missing: Bool = false, boundary: Bool = false
  ) {
    self.id = id
    self.time = time
    self.timeUs = timeUs ?? WorkoutDistancePolicy.timeUs(time)
    bits = integer.map(UInt64.init(bitPattern:)) ?? value.bitPattern
    let kind: MonitorScalarKind = missing ? .missing : (integer == nil ? .real : .integer)
    let accuracyBits = (accuracy ?? .nan).bitPattern
    if !boundary, continuity?.kind ?? .real == kind,
      continuity?.accuracyBits ?? Double.nan.bitPattern == accuracyBits
    {
      self.continuity = continuity
    } else {
      let evidence = MonitorPointEvidence(
        interruption: continuity?.interruption ?? 0, clockEpoch: continuity?.clockEpoch,
        connectionEpoch: continuity?.connectionEpoch, kind: kind, accuracyBits: accuracyBits)
      evidence.producer = boundary ? producer : continuity?.producer
      evidence.sequence = boundary ? sequence : continuity?.sequence
      evidence.boundary = boundary || continuity?.boundary == true
      self.continuity = evidence
    }
  }
  var compact: InspectionCompactPoint {
    MonitorDataStore.compactPointObserverForTesting?()
    return InspectionCompactPoint(
      reference: id, timeUs: timeUs,
      value: missing ? nil : MonitorInspection.scalar(value, integer: integer),
      continuity: Continuity(
        clockEpoch: continuity?.clockEpoch, connectionEpoch: continuity?.connectionEpoch,
        interruptionIndex: Int32(continuity?.interruption ?? 0)),
      producer: producer, sequence: sequence, accuracy: MonitorInspection.scalar(accuracy),
      interruptionBoundary: boundary)
  }
  static func from(_ point: InspectionPoint, displayTime: Double? = nil) -> Self {
    let reference = point.reference!
    let evidence = point.continuity
    let continuity =
      evidence.interruptionIndex == 0 && evidence.clockEpoch == nil && evidence.connectionEpoch == nil
      ? nil
      : MonitorPointEvidence(
        interruption: Int(evidence.interruptionIndex), clockEpoch: evidence.clockEpoch,
        connectionEpoch: evidence.connectionEpoch)
    return Self(
      id: reference.reference, time: displayTime ?? WorkoutDistancePolicy.seconds(point.timeUs),
      value: point.value.real(),
      integer: (point.value as? Scalar.Integer64)?.value, continuity: continuity, timeUs: point.timeUs,
      producer: point.producer, sequence: point.sequence, boundary: point.interruptionBoundary)
  }
}
private final class MonitorNumericPage {
  private var data = Data()
  private var count = 0
  private var packingSeconds = 0.0
  private var indices: [ObjectIdentifier: Int] = [:]
  private var retainedEvidence: [MonitorPointEvidence] = []
  private var evidence = [Continuity(clockEpoch: nil, connectionEpoch: nil, interruptionIndex: 0)]
  private var producers = [""]
  private let plot: InspectionPlotReducer?
  private let query: InspectionQuery?
  private let cache: MonitorWorkspace?
  init(plot: InspectionPlotReducer, cache: MonitorWorkspace? = nil) {
    self.cache = cache
    self.plot = plot
    query = nil
  }
  init(query: InspectionQuery, cache: MonitorWorkspace? = nil) {
    self.cache = cache
    self.query = query
    plot = nil
  }
  func append(_ point: MonitorProjectedPoint, distanceSegment: Int? = nil) throws {
    let packingBegan = MonitorDataStore.detailedTimings ? ProcessInfo.processInfo.systemUptime : 0
    var index = 0
    if let run = point.continuity {
      let key = ObjectIdentifier(run)
      if let found = indices[key] {
        index = found
      } else {
        index = evidence.count
        indices[key] = index
        retainedEvidence.append(run)
        evidence.append(
          Continuity(
            clockEpoch: run.clockEpoch, connectionEpoch: run.connectionEpoch, interruptionIndex: Int32(run.interruption)
          ))
        producers.append(run.producer ?? "")
      }
    }
    let flags: UInt64 =
      (point.missing ? 2 : point.integer == nil ? 0 : 1) | (point.boundary ? 4 : 0) | (distanceSegment == nil ? 0 : 8)
    struct Record {
      let reference: UInt64
      let time: UInt64
      let scalar: UInt64
      let flags: UInt64
      let evidence: UInt64
      let sequence: UInt64
      let accuracy: UInt64
      let segment: UInt64
    }
    var record = Record(
      reference: UInt64(bitPattern: point.id), time: UInt64(bitPattern: point.timeUs),
      scalar: point.integer.map(UInt64.init(bitPattern:)) ?? point.value.bitPattern, flags: flags,
      evidence: UInt64(index), sequence: UInt64(bitPattern: point.sequence),
      accuracy: (point.accuracy ?? .nan).bitPattern, segment: UInt64(distanceSegment ?? 0))
    withUnsafeBytes(of: &record) { data.append(contentsOf: $0) }
    count += 1
    if MonitorDataStore.detailedTimings { packingSeconds += ProcessInfo.processInfo.systemUptime - packingBegan }
    if count == 256 { try flush() }
  }
  func flush() throws {
    guard count > 0 else { return }
    let began = ProcessInfo.processInfo.systemUptime
    if let plot {
      try AppleInspectionPages.shared.plot(reducer: plot, data: data, evidence: evidence, producers: producers)
    }
    if let query {
      try AppleInspectionPages.shared.query(reducer: query, data: data, evidence: evidence, producers: producers)
    }
    cache?.counters["compactReductionMicros", default: 0] += Int(
      (ProcessInfo.processInfo.systemUptime - began) * 1_000_000)
    cache?.counters["compactPages", default: 0] += 1
    if MonitorDataStore.detailedTimings {
      cache?.counters["compactPackingMicros", default: 0] += Int(packingSeconds * 1_000_000)
      packingSeconds = 0
    }
    data.removeAll(keepingCapacity: true)
    count = 0
    indices.removeAll(keepingCapacity: true)
    retainedEvidence.removeAll(keepingCapacity: true)
    evidence.removeSubrange(1...)
    producers.removeSubrange(1...)
  }
}
private final class MonitorProjectionBlock {
  static let capacity = 1_024
  // Include a fixed allowance for the allocation, object and block-array bookkeeping.
  static let bytes = capacity * MemoryLayout<MonitorProjectedPoint>.stride + 128
  let points = UnsafeMutableBufferPointer<MonitorProjectedPoint>.allocate(capacity: capacity)
  var count = 0
  deinit {
    points.baseAddress?.deinitialize(count: count)
    points.deallocate()
  }
  func append(_ point: MonitorProjectedPoint) {
    precondition(count < Self.capacity)
    points.baseAddress!.advanced(by: count).initialize(to: point)
    count += 1
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
  var continuityBytes = 0
  private var lastContinuity: MonitorPointEvidence?
  var bytes: Int { Self.overheadBytes + blocks.count * MonitorProjectionBlock.bytes + continuityBytes }
  func additionalContinuityBytes(_ point: MonitorProjectedPoint) -> Int {
    guard let continuity = point.continuity, continuity !== lastContinuity else { return 0 }
    return continuity.bytes
  }
  func retainContinuity(_ point: MonitorProjectedPoint) {
    continuityBytes += additionalContinuityBytes(point)
    lastContinuity = point.continuity
  }
  init(key: String, start: Double, end: Double, revision: Int64) {
    self.key = key
    self.start = start
    self.end = end
    self.revision = revision
  }
  subscript(index: Int) -> MonitorProjectedPoint {
    blocks[index / MonitorProjectionBlock.capacity].points[index % MonitorProjectionBlock.capacity]
  }
  func lowerBound(_ time: Double) -> Int {
    let key = WorkoutDistancePolicy.timeUs(time)
    var low = 0
    var high = count
    while low < high {
      let mid = (low + high) / 2
      if self[mid].timeUs < key { low = mid + 1 } else { high = mid }
    }
    return low
  }
}
private struct MonitorPlotReference {
  private let id: Int64
  private let timeUs: Int64
  private let originalTime: Double
  private let bits: UInt64
  let continuity: MonitorPointEvidence?
  private let flags: UInt64
  var segment: Int { Int(flags & 0xffff_ffff) }
  var point: MonitorProjectedPoint {
    let integer = flags & (1 << 32) == 0 ? nil : Int64(bitPattern: bits)
    return MonitorProjectedPoint(
      id: id, time: originalTime,
      value: integer.map(Double.init) ?? Double(bitPattern: bits), integer: integer, continuity: continuity,
      timeUs: timeUs, producer: continuity?.producer ?? "", sequence: continuity?.sequence ?? 0,
      accuracy: Double(bitPattern: continuity?.accuracyBits ?? Double.nan.bitPattern).isNaN
        ? nil : Double(bitPattern: continuity!.accuracyBits), missing: continuity?.kind == .missing,
      boundary: continuity?.boundary ?? false)
  }
  init(point: MonitorProjectedPoint, segment: Int) {
    id = point.id
    timeUs = point.timeUs
    originalTime = point.time
    bits = point.integer.map(UInt64.init(bitPattern:)) ?? point.value.bitPattern
    continuity = point.continuity
    flags = UInt64(segment) | (point.integer == nil ? 0 : 1 << 32)
  }
  func core(identity: @escaping (KotlinLong) -> String, distance: Bool = false) throws -> InspectionPlotPoint {
    guard id > 0, timeUs >= 0, segment <= Int(Int32.max), flags < (1 << 33),
      (0...Int(Int32.max)).contains(continuity?.interruption ?? 0)
    else {
      throw MonitorError.invalid("Invalid retained chart reference.")
    }
    return InspectionPlotPoint(
      point: InspectionPoint(point: point.compact, identity: identity), segment: Int32(segment),
      tieKey: distance ? KotlinLong(value: id) : nil)
  }
}
private struct MonitorPlotNode {
  var admitted: InspectionPlotAggregate?
  var points: [MonitorPlotReference] = []
  var firstRawUs: Int64?
  var lastRawUs: Int64?
  var leadingBarrier = false
  var trailingBarrier = false
  var completeSegments = true
  var bytes: Int {
    MemoryLayout<Self>.stride + (points.isEmpty ? 0 : 32 + points.capacity * MemoryLayout<MonitorPlotReference>.stride)
  }
  init() {}
  init(_ aggregate: InspectionPlotAggregate, lookup: MonitorIdentityLookup, retainingPoints: Bool = true) {
    admitted = aggregate
    if retainingPoints {
      MonitorDataStore.aggregateProjectionObserverForTesting?()
      points = aggregate.points.map { MonitorPlotReference(point: lookup.project($0.point), segment: Int($0.segment)) }
    }
    firstRawUs = aggregate.firstRawUs?.int64Value
    lastRawUs = aggregate.lastRawUs?.int64Value
    leadingBarrier = aggregate.leadingBarrier
    trailingBarrier = aggregate.trailingBarrier
    completeSegments = aggregate.completeSegments
  }
  func core(metric: String, start: Int64, end: Int64, identity: @escaping (KotlinLong) -> String) throws
    -> InspectionPlotAggregate
  {
    MonitorDataStore.aggregateAdmissionObserverForTesting?()
    return try InspectionPlotAggregate(
      metric: metric, range: AnalysisRange(startUs: start, endUs: end),
      points: try points.map {
        try $0.core(identity: identity, distance: WorkoutDistanceStore.derivedMetrics.contains(metric))
      },
      firstRawUs: firstRawUs.map(KotlinLong.init(value:)),
      lastRawUs: lastRawUs.map(KotlinLong.init(value:)), leadingBarrier: leadingBarrier,
      trailingBarrier: trailingBarrier, completeSegments: completeSegments)
  }
}
private final class MonitorPyramid {
  static let seconds = 8.0
  let key: String
  let leafCount: Int
  var nodes: [MonitorPlotNode]
  var revision: Int64
  var lastRead: MonitorProjectedPoint?
  var used: UInt64 = 0
  private(set) var bytes = 512
  private var evidenceReferences: [ObjectIdentifier: (count: Int, evidence: MonitorPointEvidence)] = [:]
  func put(_ node: MonitorPlotNode, at index: Int) {
    let old = nodes[index]
    bytes += node.bytes - old.bytes
    for point in old.points {
      guard let evidence = point.continuity else { continue }
      let key = ObjectIdentifier(evidence)
      let reference = evidenceReferences[key]!
      if reference.count == 1 {
        evidenceReferences.removeValue(forKey: key)
        bytes -= evidence.bytes + 64
      } else {
        evidenceReferences[key] = (reference.count - 1, evidence)
      }
    }
    for point in node.points {
      guard let evidence = point.continuity else { continue }
      let key = ObjectIdentifier(evidence)
      if let reference = evidenceReferences[key] {
        evidenceReferences[key] = (reference.count + 1, evidence)
      } else {
        evidenceReferences[key] = (1, evidence)
        bytes += evidence.bytes + 64
      }
    }
    nodes[index] = node
  }
  static func leaves(_ end: Double) -> Int {
    var count = 1
    while Double(count) * seconds <= end { count *= 2 }
    return count
  }
  static func estimate(_ end: Double) -> Int {
    let capacity = leaves(end)
    let populated = min(capacity, Int(end / seconds) + 1)
    return capacity * 2 * MemoryLayout<MonitorPlotNode>.stride
      + populated * (32 + 4 * MemoryLayout<MonitorPlotReference>.stride) + 512
  }
  init(key: String, end: Double, revision: Int64) {
    self.key = key
    self.revision = revision
    leafCount = Self.leaves(end)
    nodes = Array(repeating: MonitorPlotNode(), count: leafCount * 2)
    bytes += nodes.count * MemoryLayout<MonitorPlotNode>.stride
  }
  func rebuild(
    from firstLeaf: Int, through lastLeaf: Int, metric: String,
    lookup: MonitorIdentityLookup, admit: () -> Bool
  ) throws {
    let identity = lookup.identity
    var first = (leafCount + firstLeaf) / 2
    var last = (leafCount + lastLeaf) / 2
    var span: Int64 = 16_000_000
    while first > 0 {
      let base = Int64(leafCount) * 8_000_000 / span
      for index in first...last {
        let start = (Int64(index) - base) * span
        let reducer = try MonitorInspection.plot(
          metric: metric, start: start, end: start + span, buckets: 1,
          owned: true, identity: identity)
        for child in [index * 2, index * 2 + 1] {
          let lower = start + Int64(child % 2) * span / 2
          let aggregate =
            try nodes[child].admitted
            ?? nodes[child].core(metric: metric, start: lower, end: lower + span / 2, identity: identity)
          try reducer.merge(aggregate: aggregate)
        }
        put(MonitorPlotNode(try reducer.aggregate(), lookup: lookup, retainingPoints: false), at: index)
        guard admit() else { throw MonitorPyramidCapacityExceeded() }
      }
      first /= 2
      last /= 2
      span *= 2
    }
  }
}
private struct MonitorPyramidCapacityExceeded: Error {}
private final class MonitorIdentityLookup {
  let store: PowerLogStore
  var identities: [Int64: String] = [:]
  var error: Error?
  private struct RunKey: Hashable {
    let clock: String?
    let connection: String?
    let interruption: Int
    let producer: String?
    let sequence: Int64?
    let boundary: Bool
    let kind: MonitorScalarKind
    let accuracyBits: UInt64
  }
  private var runs: [RunKey: MonitorPointEvidence] = [:]
  var displayTimes: [Int64: Double] = [:]
  func project(_ point: InspectionPoint) -> MonitorProjectedPoint {
    var result = MonitorProjectedPoint.from(point, displayTime: displayTimes[point.reference!.reference])
    if let run = result.continuity {
      if run.clockEpoch == nil && run.connectionEpoch == nil && run.interruption == 0 && !run.boundary
        && run.kind == .real && Double(bitPattern: run.accuracyBits).isNaN
      {
        result.continuity = nil
      } else {
        let key = RunKey(
          clock: run.clockEpoch, connection: run.connectionEpoch, interruption: run.interruption,
          producer: run.producer, sequence: run.sequence, boundary: run.boundary, kind: run.kind,
          accuracyBits: run.accuracyBits)
        if runs[key] == nil { runs[key] = run }
        result.continuity = runs[key]
      }
    }
    return result
  }
  init(_ store: PowerLogStore) { self.store = store }
  func identity(_ reference: KotlinLong) -> String {
    let id = reference.int64Value
    if let identity = identities[id] { return identity }
    do {
      guard
        let identity = try store.read({ db in
          try db.rows("SELECT physical_id FROM observations WHERE id=?", [.integer(id)], limit: 1).first?.string(
            "physical_id")
        })
      else { throw MonitorError.invalid("An original chart observation is unavailable.") }
      if identities.count >= MonitorDataStore.pageSize { identities.removeAll(keepingCapacity: true) }
      identities[id] = identity
      return identity
    } catch {
      self.error = error
      return ""
    }
  }
  func check() throws { if let error { throw error } }
}

/// Mutable caches never cross analytical executors. The two limits sum to one budget.
private final class MonitorWorkspace {
  let lock = NSLock()
  let byteLimit: Int
  var projections: [String: MonitorProjection] = [:]
  var pyramids: [String: MonitorPyramid] = [:]
  var tick: UInt64 = 0
  var counters: [String: Int] = [:]
  var distanceHydrationPin = ""
  var distanceHydrated: [Int64: WorkoutDistancePoint] = [:]
  var hydrationPin = ""
  var hydrated: [Int64: MonitorOriginal] = [:]
  var hydrationOrder: [Int64] = []
  var hydrationCursor = 0
  var hydrationBytes = 0
  var bytes: Int {
    projections.values.reduce(0) { $0 + $1.bytes } + pyramids.values.reduce(0) { $0 + $1.bytes } + hydrationBytes
      + distanceHydrated.count * 512
  }
  init(byteLimit: Int) { self.byteLimit = byteLimit }
}

final class MonitorDataStore {
  static let detailedTimings = ProcessInfo.processInfo.environment["POWER_LOG_MONITOR_TIMINGS"] == "1"
  static let shared = MonitorDataStore()
  static var compactPointObserverForTesting: (() -> Void)?
  static var aggregateAdmissionObserverForTesting: (() -> Void)?
  static var aggregateProjectionObserverForTesting: (() -> Void)?
  static let maximumSeconds = 2_678_400.0
  static let metrics = Set(PowerLogStore.telemetryColumns).union([
    "heartRateBpm", "speedMps", "altitudeMeters", "activeEnergyKcal", "basalEnergyKcal",
    "distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters",
    "controllerEnergyWh", "controllerChargeAh", "calculatedEnergyWh", "calculatedChargeAh",
    "horizontalAccuracyM", "verticalAccuracyM", "courseDegrees",
  ])
  static let pageSize = 256
  static let maximumPlotPoints = 16_384
  static let maximumCachedBytes = 24 * 1024 * 1024
  // Estimates cover native adapter storage; retained core graphs and runtime heaps are excluded.
  static let plotWorkingBytes = 8 * 1024 * 1024
  static let maximumProjectionBytes = maximumCachedBytes - plotWorkingBytes
  private let queue = DispatchQueue(label: "app.powerlog.monitor.read", qos: .userInitiated)
  private let statisticsQueue = DispatchQueue(label: "app.powerlog.monitor.statistics", qos: .utility)
  private let fastQueue = DispatchQueue(label: "app.powerlog.monitor.inspect", qos: .userInteractive)
  private let readAdmission = NSLock()
  private var readJobs: [String: Int] = [:]
  // Client arbitration normally admits only one per lane. These bound direct callers too.
  static let maximumAnalyticalJobs = 2
  static let maximumFastJobs = 4
  private let captureQueue = DispatchQueue(label: "app.powerlog.monitor.capture", qos: .utility)
  private var liveID = UUID().uuidString.lowercased()
  private var liveStartedAt = WorkoutCoding.timestamp(Date())
  private var liveOrigin = ProcessInfo.processInfo.systemUptime
  private let monotonicNow: () -> Double
  private var captureError: Error?
  private var initializedLive = false
  private var background = true
  private let database: Result<PowerLogStore, Error>
  private let preview: MonitorDataStore?
  private var previewCount = 0
  private var store: PowerLogStore { get throws { try database.get() } }
  private let channelLock = NSLock()
  private var admittedChannels: [String: MonitorChannel] = [:]
  private let plotWorkspace: MonitorWorkspace
  private let statisticsWorkspace: MonitorWorkspace
  private let projectionPageObserver: (() -> Void)?
  private let distanceQueue = DispatchQueue(label: "app.powerlog.monitor.distance", qos: .utility)
  private let distanceLock = NSLock()
  private var distanceBuilds = Set<String>()
  private var distanceFailures: [String: String] = [:]
  private var distanceStore: WorkoutDistanceStore { get throws { WorkoutDistanceStore(store: try store) } }

  init(
    root: URL? = nil, memoryPreview: Bool = false, projectionCacheBytes: Int = MonitorDataStore.maximumProjectionBytes,
    projectionPageObserver: (() -> Void)? = nil,
    monotonicNow: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime }
  ) {
    self.monotonicNow = monotonicNow
    preview = root == nil && !memoryPreview ? MonitorDataStore(memoryPreview: true) : nil
    let directory =
      root
      ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(
        "PowerLog", isDirectory: true)
    let budget = max(0, min(Self.maximumProjectionBytes, projectionCacheBytes))
    plotWorkspace = MonitorWorkspace(byteLimit: budget / 2)
    statisticsWorkspace = MonitorWorkspace(byteLimit: budget - budget / 2)
    self.projectionPageObserver = projectionPageObserver
    database = Result {
      try memoryPreview
        ? PowerLogStore.memory()
        : PowerLogStore.shared(databaseURL: directory.appendingPathComponent("power-log.sqlite3"))
    }
    for reads in [queue, statisticsQueue, fastQueue] {
      reads.setSpecific(key: PowerLogStore.interactiveQueue, value: true)
    }
  }
  @discardableResult
  func submit(
    _ operation: MonitorReadOperation, request: MonitorRequest,
    completion: @escaping (Result<[String: Any], Error>) -> Void
  ) -> Bool {
    if request.source == "live", let preview {
      return preview.submit(operation, request: request, completion: completion)
    }
    do { try request.validate() } catch {
      completion(.failure(error))
      return false
    }
    readAdmission.lock()
    let admitted =
      readJobs[operation.lane, default: 0] < (operation.analytical ? Self.maximumAnalyticalJobs : Self.maximumFastJobs)
    if admitted { readJobs[operation.lane, default: 0] += 1 }
    readAdmission.unlock()
    guard admitted else {
      completion(.failure(MonitorError.admission(operation.lane)))
      return false
    }
    let source: MonitorDescription
    do { source = try description(request) } catch {
      releaseReadSlot(operation)
      completion(.failure(error))
      return false
    }
    // Capture once when admitted, not when a queued analytic job finally executes.
    // Otherwise continuous capture would make the queued half of every plot/stats pair stale.
    if operation != .describe && operation != .latest, let retry = mismatch(request, source) {
      releaseReadSlot(operation)
      completion(.success(retry))
      return true
    }
    let readLease: PowerLogReadLease
    do { readLease = try PowerLogReadLease(store: store, id: source.id) } catch {
      releaseReadSlot(operation)
      completion(.failure(error))
      return false
    }
    (operation == .plot ? queue : operation == .stats ? statisticsQueue : fastQueue).async { [self] in
      defer {
        releaseReadSlot(operation)
        readLease.release()
      }
      completion(
        Result {
          try retryDistance(request, source: source) {
            switch operation {
            case .describe: return try describeSource(request, source: source)
            case .plot: return try readPlot(request, source: source)
            case .inspect: return try inspectAt(request, source: source)
            case .stats: return try rangeStats(request, source: source)
            case .changes: return try changesSince(request, source: source)
            case .latest: return try readLatest(request, source: source)
            }
          }
        })
    }
    return true
  }
  private func releaseReadSlot(_ operation: MonitorReadOperation) {
    readAdmission.lock()
    defer { readAdmission.unlock() }
    readJobs[operation.lane, default: 0] -= 1
  }
  private func retryDistance(_ request: MonitorRequest, source: MonitorDescription, _ body: () throws -> [String: Any])
    throws -> [String: Any]
  {
    do { return try body() } catch WorkoutDistanceError.pending {
      scheduleDistance(source)
      return envelope(request, source, status: "retry")
    } catch WorkoutDistanceError.expired {
      scheduleDistance(source)
      return envelope(request, source, status: "retry")
    } catch {
      if (error as NSError).kotlinException is InspectionCapacityException { throw MonitorError.chartCapacity }
      throw error
    }
  }
  private func scheduleDistance(_ source: MonitorDescription) {
    distanceLock.lock()
    guard distanceBuilds.count < 8, distanceBuilds.insert(source.id).inserted else {
      distanceLock.unlock()
      return
    }
    distanceLock.unlock()
    distanceQueue.async { [self] in
      var failure: String?
      do {
        _ = try distanceStore.pacedSnapshot(
          id: source.id, revision: source.revision, selection: source.distanceSelection)
      } catch WorkoutDistanceError.pending {} catch WorkoutDistanceError.expired {} catch {
        failure = String(error.localizedDescription.prefix(1_024))
      }
      distanceLock.lock()
      distanceBuilds.remove(source.id)
      if let failure {
        if distanceFailures.count >= 8 { distanceFailures.removeAll(keepingCapacity: true) }
        distanceFailures[source.id] = failure
      } else {
        distanceFailures.removeValue(forKey: source.id)
      }
      distanceLock.unlock()
    }
  }
  func drainDistanceForTesting() { distanceQueue.sync {} }
  private func distanceSnapshot(_ source: MonitorDescription, build: Bool) throws -> WorkoutDistanceSnapshot? {
    if build {
      return try distanceStore.pacedSnapshot(
        id: source.id, revision: source.revision, selection: source.distanceSelection)
    }
    let cached =
      try WorkoutDistanceStore.pacedPhases.contains(source.phase)
      ? distanceStore.latestCachedSnapshot(id: source.id, selection: source.distanceSelection)
      : distanceStore.cachedSnapshot(id: source.id, revision: source.revision, selection: source.distanceSelection)
    if let cached, cached.revision >= source.revision { return cached }
    scheduleDistance(source)
    distanceLock.lock()
    let failure = distanceFailures[source.id]
    distanceLock.unlock()
    if let failure { throw MonitorError.invalid(failure) }
    return cached
  }
  private func distanceMetricSources(_ snapshot: WorkoutDistanceSnapshot) -> [String: Any] {
    var sources: [String: Any] = [:]
    if let selected = snapshot.info.selected { sources["distanceMeters"] = selected.dictionary }
    for (metric, selection) in [("gpsDistanceMeters", "gps:phone"), ("controllerDistanceMeters", "controller")] {
      if let value = snapshot.sources.first(where: { $0.source == selection }) { sources[metric] = value.dictionary }
    }
    for (metric, value) in snapshot.battery {
      sources[metric] = [
        "source": metric.hasPrefix("calculated") ? "calculated" : "controller",
        "label": metric.hasPrefix("calculated") ? "Calculated" : "Controller counters",
        "estimated": metric.hasPrefix("calculated"), "partial": value.partial(activeSeconds: snapshot.activeSeconds),
        "coveredSeconds": value.coveredSeconds,
      ]
    }
    return sources
  }
  static func gap(_ metric: String) throws -> Double {
    WorkoutDistancePolicy.seconds(try InspectionMetrics.shared.plotGapUs(metric: metric))
  }
  func beginLive(startedAt: String, monotonic: Double, id: String = UUID().uuidString.lowercased()) {
    if let preview {
      preview.beginLive(startedAt: startedAt, monotonic: monotonic, id: id)
      return
    }
    captureQueue.async {
      do {
        try self.store.liveEvidence.clear(id: self.liveID)
        self.liveID = try WorkoutCoding.id(id)
        self.liveStartedAt = startedAt
        self.liveOrigin = monotonic
        self.initializedLive = false
        self.captureError = nil
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
      }
    }
  }
  func appendLive(_ sample: [String: Any], elapsedSeconds: Double) throws {
    if let preview {
      try preview.appendLive(sample, elapsedSeconds: elapsedSeconds)
      return
    }
    try captureQueue.sync {
      try commitLive([(sample, elapsedSeconds)])
    }
  }
  func setBackground(_ background: Bool) {
    preview?.setBackground(background)
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
        for frame in frames {
          _ = try store.appendTelemetry(frame.sample, collectionID: liveID, elapsedSeconds: frame.elapsed)
        }
      }
      previewCount += frames.count
      if previewCount >= 256 {
        try store.prunePreview(id: liveID)
        previewCount = 0
      }
      captureError = nil
    } catch {
      captureError = error
      throw error
    }
  }
  private func description(_ request: MonitorRequest) throws -> MonitorDescription {
    try request.validate()
    let id: String
    if request.source == "live" {
      id = try captureQueue.sync {
        if let captureError { throw captureError }
        try ensureLive()
        return liveID
      }
    } else if let requested = request.id {
      id = try WorkoutCoding.id(requested)
    } else {
      throw MonitorError.invalid("Choose a chart source.")
    }
    return try description(request, id: id)
  }
  private func description(_ request: MonitorRequest, id: String) throws -> MonitorDescription {
    return try store.read { db in
      if try store.isWorkoutDeleted(id: id) { throw PowerLogStorageError.deleted(id) }
      guard
        let row = try db.rows(
          "SELECT * FROM collections WHERE id=? AND kind=?", [.text(id), .text(request.source)], limit: 1
        ).first,
        let started = row.string("started_at"), let revision = row.int("revision")
      else { throw MonitorError.invalid("Chart source is unavailable.") }
      var metadata =
        (row.data("metadata").flatMap { try? JSONSerialization.jsonObject(with: $0) }) as? [String: Any] ?? [:]
      if let elapsed = metadata["elapsedSeconds"] as? Double {
        metadata["elapsedSeconds"] =
          try (metadata["stopElapsedSeconds"] as? Double)
          ?? max(elapsed, store.appElapsed(id: id, revision: revision))
      }
      let available = (row.data("channels").flatMap { try? JSONDecoder().decode([String].self, from: $0) }) ?? []
      return MonitorDescription(
        id: id, sourceID: request.source + ":" + id, revision: revision,
        startedAt: started, end: max(10, row.double("max_elapsed") ?? 0, metadata["elapsedSeconds"] as? Double ?? 0),
        phase: row.string("phase") ?? "",
        metadata: metadata, available: available,
        distanceSelection: metadata["distanceSource"] as? String ?? request.distanceSource)
    }
  }
  private func envelope(_ request: MonitorRequest, _ source: MonitorDescription, status: String = "ok") -> [String: Any]
  {
    [
      "status": status, "generation": request.generation, "sourceId": source.sourceID,
      "revision": source.responseRevision,
    ]
  }
  private func mismatch(_ request: MonitorRequest, _ source: MonitorDescription) -> [String: Any]? {
    if let expected = request.expectedRevision, expected != source.responseRevision {
      return envelope(request, source, status: "retry")
    }
    return nil
  }
  private func published(_ value: [String: Any], request: MonitorRequest, source: MonitorDescription) throws -> [String:
    Any]
  {
    let current = try description(request)
    let context = try InspectionContext(
      sourceId: current.sourceID, revision: source.responseRevision,
      distanceSelection: current.distanceSelection, interactionEpoch: 0, interactionSequence: 0)
    let result = try InspectionContext(
      sourceId: value["sourceId"] as! String, revision: value["revision"] as! String,
      distanceSelection: source.distanceSelection, interactionEpoch: 0, interactionSequence: 0)
    let accepted = InspectionAcceptance.shared.accepts(
      current: InspectionTicket(context: context, generation: Int64(request.generation)),
      result: InspectionTicket(context: result, generation: Int64(value["generation"] as! Int)))
    return accepted ? value : envelope(request, current, status: "retry")
  }
  func describeSource(_ request: MonitorRequest) throws -> [String: Any] {
    if request.source == "live", let preview { return try preview.describeSource(request) }
    return try describeSource(request, source: description(request))
  }
  private func describeSource(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    var result = envelope(request, source)
    result["startedAt"] = source.startedAt
    result["domain"] = ["start": 0.0, "end": source.end]
    var available = Set(
      source.available.filter { Self.metrics.contains($0) && !WorkoutDistanceStore.derivedMetrics.contains($0) })
    var metricSources: [String: Any] = [:]
    let distance = try distanceSnapshot(source, build: false)
    if let distance {
      do {
        for metric in WorkoutDistanceStore.derivedMetrics {
          if try distanceStore.selecting(distance, metric: metric, main: source.distanceSelection).totalMeters != nil {
            available.insert(metric)
          }
        }
      }
      metricSources.merge(distanceMetricSources(distance)) { _, new in new }
    } else {
      available.insert("distanceMeters")
      metricSources["distanceMeters"] = ["source": "pending", "label": "Calculating distance", "estimated": false]
    }
    result["metricSources"] = metricSources
    result["availableMetrics"] = available.sorted()
    let pending =
      distance == nil
    result["outcome"] =
      pending
      ? "pending" : (distance?.info.selected?.partial == true ? "partial" : "available")
    result = try published(result, request: request, source: source)
    result.merge(try presentationTiming(request, source)) { _, new in new }
    return result
  }
  private func presentationTiming(_ request: MonitorRequest, _ source: MonitorDescription) throws -> [String: Any] {
    let origin: Double
    if request.source == "live" {
      origin = captureQueue.sync { liveOrigin }
    } else {
      guard ["running", "paused"].contains(source.phase),
        try ["running", "paused"].contains(store.collection(id: source.id).string("phase") ?? "")
      else { return [:] }
      guard let anchor = try store.liveEvidence.timeline(id: source.id),
        anchor.epoch == TelemetryCaptureClock.processEpoch, anchor.stopMonotonic == nil
      else { return [:] }
      origin = anchor.monotonicOrigin
    }
    let measured = max(
      source.metadata["elapsedSeconds"] as? Double ?? 0,
      (source.metadata["ownerTiming"] as? [String: Any])?["elapsedSeconds"] as? Double ?? 0)
    let now = monotonicNow()
    return ["nowSeconds": max(0, measured, now - origin), "monotonicAt": now]
  }

  private func liveChannels(_ metric: String, _ source: MonitorDescription) throws -> [MonitorChannel] {
    let channel = try InspectionMetrics.shared.channel(metric: metric)
    let controller = channel == .telemetry
    let location = channel == .location
    let preferred = source.metadata["watchEnabled"] as? Bool == true ? ["watch", "phone"] : ["phone", "watch"]
    let sources = controller ? ["controller"] : preferred
    let table = controller ? "telemetry_frames" : (location ? "locations" : "health_samples")
    let kind = controller ? "telemetry" : (location ? "location" : "health")
    return sources.map { MonitorChannel(table: table, column: metric, kind: kind, source: $0) }
  }

  private func liveNeighbor(_ metric: String, _ source: MonitorDescription) throws -> (
    MonitorOriginal, Double
  )? {
    var newest: (channel: MonitorChannel, evidence: WorkoutLiveEvidence.Observation)?
    for channel in try liveChannels(metric, source) {
      guard let evidence = try store.liveEvidence.observation(id: source.id, channel: channel),
        evidence.acquiredAt > (newest?.evidence.acquiredAt ?? -.infinity)
      else { continue }
      newest = (channel, evidence)
    }
    guard let (channel, evidence) = newest else { return nil }
    return try store.read { db in
      let rows = try db.rows(
        "\(selectSQL(channel, anchored: true)) WHERE \(baseWhere(channel)) AND o.physical_id=? LIMIT 1",
        values(channel, source) + [.text(evidence.identity)], limit: 1)
      guard let point = rows.first.flatMap({ original($0) }) else {
        return nil
      }
      return (point, evidence.acquiredAt)
    }
  }
  private func liveAcquisition(anchor: String, source: MonitorDescription) throws -> Double? {
    try store.liveEvidence.acquisition(id: source.id, identity: anchor)
  }

  private func channel(_ metric: String, source: MonitorDescription) throws -> MonitorChannel? {
    let key = source.sourceID + "|" + String(source.revision) + "|" + metric
    channelLock.lock()
    let cached = admittedChannels[key]
    channelLock.unlock()
    if let cached { return cached }
    guard let admitted = try findChannel(metric, source: source) else { return nil }
    channelLock.lock()
    if admittedChannels.count >= 128 { admittedChannels.removeAll(keepingCapacity: true) }
    admittedChannels[key] = admitted
    channelLock.unlock()
    return admitted
  }
  private func findChannel(_ metric: String, source: MonitorDescription) throws -> MonitorChannel? {
    if WorkoutDistanceStore.derivedMetrics.contains(metric) { return nil }
    let kind = try InspectionMetrics.shared.channel(metric: metric)
    if kind == .health && !PowerLogStore.healthColumns.contains(metric) { return nil }
    let controller = kind == .telemetry
    let location = kind == .location
    let preferred = source.metadata["watchEnabled"] as? Bool == true ? ["watch", "phone"] : ["phone", "watch"]
    let sources = controller ? ["controller"] : preferred
    let table = controller ? "telemetry_frames" : (location ? "locations" : "health_samples")
    let storageKind = controller ? "telemetry" : (location ? "location" : "health")
    let channels = try store.read { db in
      try db.rows(
        "SELECT source,representation FROM collection_channels WHERE collection_id=? AND metric=? AND count>0",
        [.text(source.id), .text(metric)], limit: 32)
    }
    for owner in sources {
      let rawChoices = metric == "heartRateBpm" ? [true, false] : [false]
      for raw in rawChoices {
        let exists = channels.contains {
          $0.string("source") == owner && (metric != "heartRateBpm" || ($0.string("representation") == "raw") == raw)
        }
        if exists {
          let c = MonitorChannel(table: table, column: metric, kind: storageKind, source: owner, rawHeart: raw)
          if !(try neighboringRows(c, source, time: 0, before: false)).isEmpty { return c }
        }
      }
    }
    return nil
  }
  private func tableColumn(_ c: MonitorChannel) -> String {
    // Column names come solely from the native allowlist, never arbitrary input.
    "h.\(c.column)"
  }
  private func accuracyColumn(_ c: MonitorChannel) -> String {
    if c.kind == "location" && c.metric == "speedMps" { return "h.speedAccuracyMps" }
    if c.kind == "location" && c.metric == "courseDegrees" { return "h.courseAccuracyDegrees" }
    return "NULL"
  }
  private func selectSQL(_ c: MonitorChannel, anchored: Bool = false) -> String {
    let membershipIndex = anchored ? " INDEXED BY membership_observation" : ""
    return
      "SELECT m.observation_id,m.producer,m.sequence,m.elapsed_seconds,m.elapsed_us,o.physical_id,o.original_timestamp,o.clock_epoch,json_extract(o.extra,'$.connectionEpoch') AS connection_epoch,\(accuracyColumn(c)) AS accuracy,\(tableColumn(c)) AS value FROM collection_memberships m\(membershipIndex) JOIN observations o ON o.id=m.observation_id JOIN \(c.table) h ON h.observation_id=o.id"
  }
  private func baseWhere(_ c: MonitorChannel) -> String {
    var result = "m.collection_id=? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND \(c.conditions)"
    let identifiers = [
      "heartRateBpm": "HKQuantityTypeIdentifierHeartRate",
      "activeEnergyKcal": "HKQuantityTypeIdentifierActiveEnergyBurned",
      "basalEnergyKcal": "HKQuantityTypeIdentifierBasalEnergyBurned",
    ]
    if c.kind == "health", let identifier = identifiers[c.metric] {
      result += " AND (h.identifier IS NULL OR h.identifier='\(identifier)')"
    }
    return result
  }
  private func values(_ c: MonitorChannel, _ source: MonitorDescription) -> [PowerLogSQLValue] {
    [.text(source.id), .integer(source.revision), .integer(source.revision), .integer(source.revision)] + c.values
  }
  private func interruptions(_ source: MonitorDescription) throws -> [WorkoutInterruptionBoundary] {
    try WorkoutInterruptionBoundary.load(store: store, id: source.id, revision: source.revision)
  }
  private func interruptionTimeline(_ interruptions: [WorkoutInterruptionBoundary]) -> ActiveTimeline? {
    guard !interruptions.isEmpty else { return nil }
    let events = interruptions.enumerated().map { offset, boundary in
      MonitorInspection.lifecycle(
        timeUs: WorkoutDistancePolicy.timeUs(boundary.time), id: String(offset),
        producer: boundary.producer, sequence: boundary.sequence, action: "pause", clock: boundary.clockEpoch,
        interrupted: true, controllerSequence: boundary.controllerSequence)
    }
    return ActiveTimeline(endUs: WorkoutDistancePolicy.timeUs(Self.maximumSeconds), lifecycle: events)
  }
  private func continuity(
    _ row: PowerLogRow, interruptions: [WorkoutInterruptionBoundary], timeline: ActiveTimeline? = nil
  ) -> MonitorPointEvidence? {
    continuity(
      timeUs: row.int("elapsed_us")!, producer: row.string("producer")!, sequence: row.int("sequence")!,
      clockEpoch: row.string("clock_epoch"), connectionEpoch: row.string("connection_epoch"),
      timeline: timeline ?? interruptionTimeline(interruptions))
  }
  private func continuity(
    timeUs: Int64, producer: String, sequence: Int64, clockEpoch: String?, connectionEpoch: String?,
    timeline: ActiveTimeline?
  ) -> MonitorPointEvidence? {
    let index =
      timeline.map {
        Int($0.interruptionIndex(t: timeUs, producer: producer, sequence: sequence, clockEpoch: clockEpoch))
      } ?? 0
    guard index > 0 || clockEpoch != nil || connectionEpoch != nil else { return nil }
    return MonitorPointEvidence(interruption: index, clockEpoch: clockEpoch, connectionEpoch: connectionEpoch)
  }

  private func original(_ row: PowerLogRow) -> MonitorOriginal? {
    guard let id = row.int("observation_id"), let time = row.double("elapsed_seconds"),
      let identity = row.string("physical_id"),
      let timestamp = row.string("original_timestamp"), let value = row.double("value"), time.isFinite, value.isFinite
    else { return nil }
    return MonitorOriginal(
      id: id, identity: identity, time: time, timestamp: timestamp, value: value, integer: row.int("value"))
  }
  private func neighbor(
    _ c: MonitorChannel, _ source: MonitorDescription, time: Double, before: Bool, strict: Bool = false,
    admitted: Bool = true
  ) throws -> MonitorOriginal? {
    let rows = try neighboringRows(c, source, time: time, before: before, strict: strict, admitted: admitted)
    return rows.first.flatMap { original($0) }
  }
  private func neighboringRows(
    _ c: MonitorChannel, _ source: MonitorDescription, time: Double, before: Bool, strict: Bool = false,
    admitted: Bool = true
  ) throws -> [PowerLogRow] {
    let key = WorkoutDistancePolicy.timeUs(time)
    let comparison = before ? "<" : ">="
    let keyComparison = before ? (strict ? "<" : "<=") : (strict ? ">" : ">=")
    // Widen the raw index bound by one ULP; stored integer keys decide bin membership.
    let boundary = WorkoutDistancePolicy.seconds(key + (before == strict ? 0 : 1))
    var cursor = before ? boundary.nextUp : boundary.nextDown
    var initial = true
    var pageLimit = 1
    while true {
      let rows = try store.read { db in
        try db.rows(
          "\(selectSQL(c)) WHERE \(baseWhere(c)) AND m.elapsed_us\(keyComparison)? AND m.elapsed_seconds\(initial ? comparison : (before ? "<=" : ">="))? ORDER BY m.elapsed_seconds \(before ? "DESC" : "ASC"),o.physical_id ASC LIMIT \(pageLimit)",
          values(c, source) + [.integer(key), .real(cursor)], limit: pageLimit)
      }
      guard !rows.isEmpty else { return [] }
      if !admitted { return [rows[0]] }
      for row in rows {
        if Metrics.shared.plotValid(
          metric: c.metric, value: MonitorInspection.scalar(row.double("value"), integer: row.int("value")),
          accuracy: MonitorInspection.scalar(row.double("accuracy")))
        {
          return [row]
        }
      }
      if pageLimit == 1 {
        pageLimit = 256
        continue
      }
      guard rows.count == pageLimit else { return [] }
      let last = rows.last!.double("elapsed_seconds")!
      let coincident = try store.read { db in
        try db.rows(
          "\(selectSQL(c)) WHERE \(baseWhere(c)) AND m.elapsed_seconds=? ORDER BY o.physical_id LIMIT 256",
          values(c, source) + [.real(last)], limit: 256)
      }
      guard coincident.count < 256 else { throw MonitorError.invalid("Too many coincident chart observations.") }
      for row in coincident {
        if Metrics.shared.plotValid(
          metric: c.metric, value: MonitorInspection.scalar(row.double("value"), integer: row.int("value")),
          accuracy: MonitorInspection.scalar(row.double("accuracy")))
        {
          return [row]
        }
      }
      cursor = before ? last.nextDown : last.nextUp
      initial = false
    }
  }

  func projectionDiagnostics() -> [String: Int] {
    plotWorkspace.lock.lock()
    defer { plotWorkspace.lock.unlock() }
    statisticsWorkspace.lock.lock()
    defer { statisticsWorkspace.lock.unlock() }
    var result: [String: Int] = [:]
    for cache in [plotWorkspace, statisticsWorkspace] {
      let lane = cache === plotWorkspace ? "plot" : "statistics"
      result[lane + "ScannedPoints"] = cache.counters["scannedPoints", default: 0]
      for (key, value) in cache.counters { result[key, default: 0] += value }
      result["nativeAdapterBytes", default: 0] += cache.bytes
      result["nativeAdapterByteLimit", default: 0] += cache.byteLimit
      result["nativePyramidBytes", default: 0] += cache.pyramids.values.reduce(0) { $0 + $1.bytes }
      result["pyramidEntries", default: 0] += cache.pyramids.count
      result["entries", default: 0] += cache.projections.count
      result["points", default: 0] += cache.projections.values.reduce(0) { $0 + $1.count }
    }
    result["nativeWorkingReserveBytes"] = Self.plotWorkingBytes
    return result
  }
  /// Cleanup retries on its existing bounded tick if an analytic lane is still active.
  /// Never block the engine or add eviction work to the native read queues.
  @discardableResult
  func discardWorkout(id: String) -> Bool {
    var drained = true
    for cache in [plotWorkspace, statisticsWorkspace] {
      guard cache.lock.try() else {
        drained = false
        continue
      }
      discardWorkout(id: id, from: cache)
      cache.lock.unlock()
    }
    return drained
  }
  private func discardWorkout(id: String, from cache: MonitorWorkspace) {
    let prefix = "workout:\(id)|"
    cache.projections = cache.projections.filter { !$0.key.hasPrefix(prefix) }
    cache.pyramids = cache.pyramids.filter { !$0.key.hasPrefix(prefix) && !$0.key.hasPrefix("distance|" + prefix) }
    if cache.hydrationPin.hasPrefix(prefix) {
      cache.hydrated.removeAll(keepingCapacity: true)
      cache.hydrationOrder.removeAll(keepingCapacity: true)
      cache.hydrationCursor = 0
      cache.hydrationBytes = 0
      cache.hydrationPin = ""
    }
    if cache.distanceHydrationPin.hasPrefix("distance|" + prefix) {
      cache.distanceHydrated.removeAll(keepingCapacity: true)
      cache.distanceHydrationPin = ""
    }
  }
  private func releaseWorkspace(_ cache: MonitorWorkspace, source: MonitorDescription) {
    if (try? store.isWorkoutDeleted(id: source.id)) == true { discardWorkout(id: source.id, from: cache) }
    cache.counters["peakNativeAdapterBytes"] = max(cache.counters["peakNativeAdapterBytes", default: 0], cache.bytes)
    cache.lock.unlock()
  }
  private func makeProjectionRoom(
    _ cache: MonitorWorkspace, _ bytes: Int, keeping key: String, evictPyramids: Bool = true
  ) -> Bool {
    if cache.bytes + bytes > cache.byteLimit && (!cache.hydrated.isEmpty || !cache.distanceHydrated.isEmpty) {
      cache.hydrated.removeAll(keepingCapacity: true)
      cache.distanceHydrated.removeAll(keepingCapacity: true)
      cache.hydrationOrder.removeAll(keepingCapacity: true)
      cache.hydrationBytes = 0
      cache.hydrationCursor = 0
    }
    guard bytes <= cache.byteLimit else { return false }
    while cache.bytes + bytes > cache.byteLimit || (cache.projections[key] == nil && cache.projections.count >= 16) {
      let raw = cache.projections.values.filter({ $0.key != key }).min(by: { $0.used < $1.used })
      let reduced = evictPyramids ? cache.pyramids.values.filter({ $0.key != key }).min(by: { $0.used < $1.used }) : nil
      if let raw, reduced == nil || raw.used <= reduced!.used {
        cache.projections.removeValue(forKey: raw.key)
      } else if let reduced {
        cache.pyramids.removeValue(forKey: reduced.key)
      } else {
        return false
      }
      cache.counters["evictions", default: 0] += 1
    }
    return true
  }
  private func appendProjection(_ cache: MonitorWorkspace, _ p: MonitorProjectedPoint, to projection: MonitorProjection)
    -> Bool
  {
    if projection.blocks.last?.count ?? MonitorProjectionBlock.capacity == MonitorProjectionBlock.capacity {
      guard makeProjectionRoom(cache, MonitorProjectionBlock.bytes, keeping: projection.key, evictPyramids: false)
      else { return false }
      projection.blocks.append(MonitorProjectionBlock())
      cache.counters["peakNativeAdapterBytes"] = max(cache.counters["peakNativeAdapterBytes", default: 0], cache.bytes)
    }
    guard
      makeProjectionRoom(cache, projection.additionalContinuityBytes(p), keeping: projection.key, evictPyramids: false)
    else { return false }
    projection.retainContinuity(p)
    projection.blocks.last!.append(p)
    projection.count += 1
    return true
  }
  private func projectionKey(_ c: MonitorChannel, _ source: MonitorDescription) -> String {
    [
      source.sourceID, c.column, c.source, c.rawHeart ? "raw" : "fallback",
    ].joined(separator: "|")
  }
  private func projectionStillValid(_ projection: MonitorProjection, _ c: MonitorChannel, _ source: MonitorDescription)
    throws -> Bool
  {
    if projection.revision == source.revision { return true }
    guard projection.revision < source.revision else { return false }
    let changes = try store.read { db in
      try db.rows(
        "SELECT revision,kind,metrics FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT 512",
        [.text(source.id), .integer(projection.revision), .integer(source.revision)], limit: 512)
    }
    guard changes.count == source.revision - projection.revision,
      changes.first?.int("revision") == projection.revision + 1, changes.last?.int("revision") == source.revision
    else { return false }
    var changed = false
    for row in changes {
      guard let bytes = row.data("metrics"), let metrics = try? JSONDecoder().decode([String].self, from: bytes) else {
        return false
      }
      guard metrics.isEmpty || metrics.contains(c.column) else { continue }
      guard row.string("kind") == "append" else { return false }
      changed = true
    }
    if changed {
      // Change journal bounds include adjacent edges and are quantized conservatively.
      // Refine new observations at original precision before deciding that this is a tail.
      let firstNew = try store.read { db in
        try db.rows(
          "SELECT min(m.elapsed_seconds) AS time FROM collection_memberships m INDEXED BY membership_snapshot JOIN \(c.table) h ON h.observation_id=m.observation_id WHERE \(baseWhere(c)) AND m.revision>?",
          values(c, source) + [.integer(projection.revision)], limit: 1
        ).first?.double("time")
      }
      if let firstNew {
        let last =
          projection.count > 0
          ? projection[projection.count - 1].timeUs : WorkoutDistancePolicy.timeUs(projection.start)
        // Equal-time append can alter stable-identity ties, so rebuild it as a late insertion.
        guard WorkoutDistancePolicy.timeUs(firstNew) > last else { return false }
        projection.end = min(projection.end, firstNew.nextDown)
      }
    }
    projection.revision = source.revision
    return true
  }
  private func scanProjected(
    _ cache: MonitorWorkspace, _ c: MonitorChannel, _ source: MonitorDescription, start: Double, end: Double,
    after: MonitorProjectedPoint? = nil, _ body: (MonitorProjectedPoint) throws -> Void
  ) throws {
    let boundaries = try interruptions(source)
    let timeline = interruptionTimeline(boundaries)
    let boundaryTimes = Set(boundaries.map { WorkoutDistancePolicy.timeUs($0.time) })
    let startUs = WorkoutDistancePolicy.timeUs(start)
    let endUs = WorkoutDistancePolicy.timeUs(end)
    let upper = WorkoutDistancePolicy.seconds(endUs + 1).nextUp
    var lastContinuity = after?.continuity
    var afterTime = after?.time ?? WorkoutDistancePolicy.seconds(startUs).nextDown
    var afterProducer = ""
    var afterSequence: Int64 = 0
    if let after {
      let retained = try store.read { db in
        try db.rows(
          "SELECT producer,sequence,elapsed_seconds FROM collection_memberships WHERE collection_id=? AND observation_id=?",
          [.text(source.id), .integer(after.id)], limit: 1
        ).first
      }
      guard let retained else { throw MonitorError.invalid("The chart continuation is unavailable") }
      afterTime = retained.double("elapsed_seconds")!
      afterProducer = retained.string("producer")!
      afterSequence = retained.int("sequence")!
    }
    while afterTime < upper {
      let readBegan = ProcessInfo.processInfo.systemUptime
      let rows = try store.read { db in
        if try store.isWorkoutDeleted(id: source.id) { throw PowerLogStorageError.deleted(source.id) }
        var page: [MonitorNumericOriginal] = []
        page.reserveCapacity(Self.pageSize)
        try db.scan(
          "SELECT m.observation_id,m.producer,m.sequence,m.elapsed_seconds,m.elapsed_us,o.clock_epoch,json_extract(o.extra,'$.connectionEpoch') AS connection_epoch,\(accuracyColumn(c)) AS accuracy,\(tableColumn(c)) AS value FROM collection_memberships m JOIN observations o ON o.id=m.observation_id JOIN \(c.table) h ON h.observation_id=m.observation_id WHERE \(baseWhere(c)) AND m.elapsed_seconds<? AND (m.elapsed_seconds,m.producer,m.sequence)>(?,?,?) ORDER BY m.elapsed_seconds,m.producer,m.sequence LIMIT \(Self.pageSize)",
          values(c, source) + [.real(upper), .real(afterTime), .text(afterProducer), .integer(afterSequence)],
          limit: Self.pageSize
        ) { row in
          guard let id = row.integer(0), let producer = row.text(1), let sequence = row.integer(2),
            let time = row.double(3), time.isFinite, let timeUs = row.integer(4)
          else { throw MonitorError.invalid("Invalid chart observation time.") }
          page.append(
            MonitorNumericOriginal(
              id: id, producer: producer, sequence: sequence, time: time, timeUs: timeUs,
              clockEpoch: row.text(5), connectionEpoch: row.text(6), accuracy: row.double(7),
              value: row.double(8), integer: row.integer(8)))
          return true
        }
        return page
      }
      cache.counters["originalReadMicros", default: 0] += Int(
        (ProcessInfo.processInfo.systemUptime - readBegan) * 1_000_000)
      projectionPageObserver?()
      guard let last = rows.last else { return }
      cache.counters["scannedPoints", default: 0] += rows.count
      var consumerSeconds = 0.0
      let mappingBegan = MonitorDataStore.detailedTimings ? ProcessInfo.processInfo.systemUptime : 0
      for row in rows {
        if row.timeUs >= startUs && row.timeUs <= endUs {
          var point = MonitorProjectedPoint(
            id: row.id, time: row.time, value: row.value ?? 0, integer: row.integer,
            continuity: continuity(
              timeUs: row.timeUs, producer: row.producer, sequence: row.sequence, clockEpoch: row.clockEpoch,
              connectionEpoch: row.connectionEpoch, timeline: timeline),
            timeUs: row.timeUs, producer: row.producer, sequence: row.sequence,
            accuracy: row.accuracy, missing: row.value == nil,
            boundary: boundaryTimes.contains(row.timeUs))
          if point.continuity == lastContinuity {
            point.continuity = lastContinuity
          } else {
            lastContinuity = point.continuity
          }
          let consumerBegan = MonitorDataStore.detailedTimings ? ProcessInfo.processInfo.systemUptime : 0
          try body(point)
          if MonitorDataStore.detailedTimings {
            consumerSeconds += ProcessInfo.processInfo.systemUptime - consumerBegan
          }
        }
      }
      if MonitorDataStore.detailedTimings {
        cache.counters["originalMappingMicros", default: 0] +=
          Int((ProcessInfo.processInfo.systemUptime - mappingBegan - consumerSeconds) * 1_000_000)
      }
      afterTime = last.time
      afterProducer = last.producer
      afterSequence = last.sequence
      if rows.count < Self.pageSize { return }
    }
  }
  private func plotBoundary(
    _ cache: MonitorWorkspace, _ c: MonitorChannel, _ source: MonitorDescription, start: Double, end: Double,
    _ body: (MonitorProjectedPoint) throws -> Void
  ) throws {
    if let cached = cache.projections[projectionKey(c, source)], start >= cached.start, end <= cached.end,
      try projectionStillValid(cached, c, source)
    {
      let lower = cached.lowerBound(start)
      if lower < cached.count {
        for index in lower..<cached.count {
          let point = cached[index]
          if point.timeUs > WorkoutDistancePolicy.timeUs(end) { break }
          try body(point)
        }
      }
    } else {
      try scanProjected(cache, c, source, start: start, end: end, body)
    }
  }
  private func project(
    _ cache: MonitorWorkspace, _ c: MonitorChannel, _ source: MonitorDescription, start: Double, end: Double,
    _ body: (MonitorProjectedPoint) throws -> Void
  ) throws {
    let key = projectionKey(c, source)
    var cached = cache.projections[key]
    if let existing = cached, try start < existing.start || !projectionStillValid(existing, c, source) {
      cache.projections.removeValue(forKey: key)
      cached = nil
      cache.counters["invalidations", default: 0] += 1
    }
    cache.tick &+= 1
    if let existing = cached {
      existing.used = cache.tick
      cache.counters["projectionHits", default: 0] += 1
      let lower = existing.lowerBound(start)
      if lower < existing.count {
        for i in lower..<existing.count {
          let p = existing[i]
          if p.timeUs > WorkoutDistancePolicy.timeUs(end) { break }
          try body(p)
        }
      }
      guard end > existing.end else { return }
      // Resolve the retained point back to its producer sequence for equal-time continuation.
      let after = existing.count > 0 ? existing[existing.count - 1] : nil
      let suffixStart = after?.time ?? existing.start
      var retaining = true
      do {
        try scanProjected(cache, c, source, start: suffixStart, end: end, after: after) { p in
          if retaining && !appendProjection(cache, p, to: existing) {
            cache.projections.removeValue(forKey: key)
            retaining = false
            cache.counters["streamingFallbacks", default: 0] += 1
          }
          cache.counters["appendedPoints", default: 0] += 1
          if p.timeUs >= WorkoutDistancePolicy.timeUs(start) { try body(p) }
        }
        if retaining { existing.end = end }
      } catch {
        cache.projections.removeValue(forKey: key)
        throw error
      }
      return
    }
    let channelCount = try store.read { db in
      try db.scalarInt(
        "SELECT COALESCE(SUM(count),0) FROM collection_channels WHERE collection_id=? AND metric=? AND source=? AND representation=?",
        [.text(source.id), .text(c.column), .text(c.source), .text(c.rawHeart ? "raw" : "")]) ?? 0
    }
    let projectedCount =
      start == 0 && end >= source.end
      ? channelCount : Int64(ceil(Double(channelCount) * min(1, (end - start) / max(1, source.end))))
    let blocks = (projectedCount + Int64(MonitorProjectionBlock.capacity - 1)) / Int64(MonitorProjectionBlock.capacity)
    let estimate = blocks * Int64(MonitorProjectionBlock.bytes) + Int64(MonitorProjection.overheadBytes)
    var building: MonitorProjection?
    // Exact statistics must not evict the small navigation summaries and cause a
    // full rebuild on the next gesture, especially while a long ride is recording.
    let summaryBytes = cache.pyramids.values.reduce(0) { $0 + $1.bytes }
    if estimate + Int64(summaryBytes) <= cache.byteLimit
      && makeProjectionRoom(cache, MonitorProjection.overheadBytes, keeping: key, evictPyramids: false)
    {
      let projection = MonitorProjection(key: key, start: start, end: end, revision: source.revision)
      projection.used = cache.tick
      cache.projections[key] = projection
      building = projection
    } else {
      cache.counters["streamingFallbacks", default: 0] += 1
    }
    do {
      try scanProjected(cache, c, source, start: start, end: end) { p in
        if let projection = building, !appendProjection(cache, p, to: projection) {
          cache.projections.removeValue(forKey: key)
          building = nil
          cache.counters["streamingFallbacks", default: 0] += 1
        }
        try body(p)
      }
    } catch {
      cache.projections.removeValue(forKey: key)
      throw error
    }
  }
  static func retainedObservationSQL(_ count: Int) -> String {
    precondition((1...pageSize).contains(count))
    return
      "SELECT o.id,o.physical_id,o.original_timestamp,m.elapsed_seconds FROM observations o JOIN collection_memberships m INDEXED BY membership_observation ON m.observation_id=o.id WHERE m.collection_id=? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND o.id IN (\(Array(repeating: "?", count: count).joined(separator: ",")))"
  }
  private func hydrate(_ cache: MonitorWorkspace, _ points: [MonitorProjectedPoint], source: MonitorDescription) throws
    -> [Int64: MonitorOriginal]
  {
    let began = ProcessInfo.processInfo.systemUptime
    defer {
      cache.counters["retainedHydrationMicros", default: 0] += Int(
        (ProcessInfo.processInfo.systemUptime - began) * 1_000_000)
    }
    let pin = source.sourceID + "|" + String(source.revision)
    if cache.hydrationPin != pin {
      cache.hydrationPin = pin
      cache.hydrated.removeAll(keepingCapacity: true)
      cache.hydrationOrder.removeAll(keepingCapacity: true)
      cache.hydrationCursor = 0
      cache.hydrationBytes = 0
    }
    var result: [Int64: MonitorOriginal] = [:]
    var missing: [MonitorProjectedPoint] = []
    for point in points {
      if let original = cache.hydrated[point.id] {
        result[point.id] = MonitorOriginal(
          id: point.id, identity: original.identity, time: original.time, timestamp: original.timestamp,
          value: point.value, integer: point.integer)
        cache.counters["hydrationHits", default: 0] += 1
      } else {
        missing.append(point)
      }
    }
    for offset in stride(from: 0, to: missing.count, by: Self.pageSize) {
      let page = Array(missing[offset..<min(missing.count, offset + Self.pageSize)])
      let rows = try store.read { db in
        try db.rows(
          Self.retainedObservationSQL(page.count),
          [.text(source.id), .integer(source.revision), .integer(source.revision), .integer(source.revision)]
            + page.map { .integer($0.id) }, limit: Self.pageSize)
      }
      cache.counters["hydratedPoints", default: 0] += rows.count
      let byID = Dictionary(uniqueKeysWithValues: page.map { ($0.id, $0) })
      for row in rows {
        if let id = row.int("id"), let p = byID[id], let identity = row.string("physical_id"),
          let timestamp = row.string("original_timestamp")
        {
          result[id] = MonitorOriginal(
            id: id, identity: identity, time: row.double("elapsed_seconds")!, timestamp: timestamp, value: p.value,
            integer: p.integer)
          guard !cache.projections.isEmpty || !cache.pyramids.isEmpty else { continue }
          let bytes = 192 + identity.utf8.count + timestamp.utf8.count
          if cache.hydrationOrder.count == 4096 {
            let oldID = cache.hydrationOrder[cache.hydrationCursor]
            if let old = cache.hydrated.removeValue(forKey: oldID) {
              cache.hydrationBytes -= 192 + old.identity.utf8.count + old.timestamp.utf8.count
            }
            cache.hydrationOrder[cache.hydrationCursor] = id
            cache.hydrationCursor = (cache.hydrationCursor + 1) % 4096
          } else if cache.bytes + bytes <= cache.byteLimit {
            cache.hydrationOrder.append(id)
          }
          if cache.bytes + bytes <= cache.byteLimit {
            cache.hydrated[id] = result[id]
            cache.hydrationBytes += bytes
          }
        }
      }
    }
    return result
  }
  private func pyramidCacheKey(_ key: String) -> String { "lod|" + key }
  private func savePyramid(_ value: MonitorPyramid, _ source: MonitorDescription, cache: MonitorWorkspace) throws {
    guard source.phase == "completed", let last = value.lastRead else { return }
    let began = ProcessInfo.processInfo.systemUptime
    defer {
      cache.counters["pyramidPersistenceMicros", default: 0] += Int(
        (ProcessInfo.processInfo.systemUptime - began) * 1_000_000)
    }
    var bytes = Data()
    var indices: [ObjectIdentifier: Int] = [:]
    var evidence: [MonitorPointEvidence] = []
    func word(_ value: UInt64) {
      var value = value.littleEndian
      withUnsafeBytes(of: &value) { bytes.append(contentsOf: $0) }
    }
    func reference(_ reference: MonitorPlotReference) {
      let point = reference.point
      word(UInt64(bitPattern: point.id))
      word(UInt64(bitPattern: point.timeUs))
      word(point.integer.map(UInt64.init(bitPattern:)) ?? point.value.bitPattern)
      word(UInt64(reference.segment) | (point.integer == nil ? 0 : 1 << 32))
      if let run = point.continuity {
        let identity = ObjectIdentifier(run)
        if indices[identity] == nil {
          evidence.append(run)
          indices[identity] = evidence.count
        }
        word(UInt64(indices[identity]!))
      } else {
        word(0)
      }
      word(point.time.bitPattern)
    }
    word(0x504c4f474c4f4400)
    word(UInt64(value.leafCount))
    word(UInt64(source.revision))
    word(0)
    reference(MonitorPlotReference(point: last, segment: 0))
    for node in value.nodes[value.leafCount...] {
      word(UInt64(node.points.count))
      word(node.firstRawUs.map(UInt64.init(bitPattern:)) ?? UInt64.max)
      word(node.lastRawUs.map(UInt64.init(bitPattern:)) ?? UInt64.max)
      word((node.leadingBarrier ? 1 : 0) | (node.trailingBarrier ? 2 : 0) | (node.completeSegments ? 4 : 0))
      for point in node.points { reference(point) }
    }
    var numericSize = UInt64(bytes.count).littleEndian
    withUnsafeBytes(of: &numericSize) { bytes.replaceSubrange(24..<32, with: $0) }
    bytes.append(try JSONEncoder().encode(evidence))
    bytes.append(contentsOf: SHA256.hash(data: bytes))
    do {
      try cacheBytes(bytes, key: pyramidCacheKey(value.key), source: source)
      cache.counters["pyramidSavedBytes", default: 0] += bytes.count
    } catch {
      cache.counters["pyramidSaveFailures", default: 0] += 1
    }
  }
  private func loadPyramid(_ cache: MonitorWorkspace, _ key: String, _ source: MonitorDescription, metric: String)
    throws -> MonitorPyramid?
  {
    guard source.phase == "completed",
      let bytes = try store.read({ db in
        try db.rows(
          "SELECT value FROM derived_cache WHERE key=? AND revision=? AND length(value)<=4194304",
          [.text(pyramidCacheKey(key)), .integer(source.revision)], limit: 1
        ).first?.data("value")
      }), bytes.count >= 112, Data(SHA256.hash(data: bytes.dropLast(32))) == bytes.suffix(32)
    else { return nil }
    var offset = 0
    var limit = bytes.count - 32
    func word() throws -> UInt64 {
      guard offset <= limit - 8 else { throw MonitorError.invalid("Truncated chart cache") }
      defer { offset += 8 }
      return bytes.withUnsafeBytes { UInt64(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self)) }
    }
    do {
      let count = MonitorPyramid.leaves(source.end)
      guard try word() == 0x504c4f474c4f4400, try word() == UInt64(count), try word() == UInt64(source.revision) else {
        return nil
      }
      let size = try word()
      guard size >= 80, size <= UInt64(limit), size % 8 == 0 else { return nil }
      limit = Int(size)
      let evidence = try JSONDecoder().decode([MonitorPointEvidence].self, from: bytes[limit..<(bytes.count - 32)])
      guard evidence.allSatisfy({ (0...Int(Int32.max)).contains($0.interruption) }) else { return nil }
      func reference() throws -> MonitorPlotReference {
        let id = try word()
        let time = try word()
        let bits = try word()
        let flags = try word()
        let continuity = try word()
        let originalTime = Double(bitPattern: try word())
        guard id > 0, id <= UInt64(Int64.max), time <= UInt64(Int64.max), flags < 1 << 33,
          flags & 0xffff_ffff <= UInt64(Int32.max), continuity <= evidence.count, originalTime.isFinite,
          originalTime >= 0, originalTime <= source.end
        else { throw MonitorError.invalid("Invalid retained chart reference") }
        let integer = flags & (1 << 32) == 0 ? nil : Int64(bitPattern: bits)
        let point = MonitorProjectedPoint(
          id: Int64(id), time: originalTime, value: integer.map(Double.init) ?? Double(bitPattern: bits),
          integer: integer, continuity: continuity == 0 ? nil : evidence[Int(continuity) - 1], timeUs: Int64(time),
          missing: continuity > 0 && evidence[Int(continuity) - 1].kind == .missing)
        return MonitorPlotReference(point: point, segment: Int(flags & 0xffff_ffff))
      }
      let result = MonitorPyramid(key: key, end: source.end, revision: source.revision)
      result.lastRead = try reference().point
      let lookup = MonitorIdentityLookup(try store)
      for index in 0..<count {
        let pointCount = try word()
        let first = try word()
        let last = try word()
        let flags = try word()
        guard pointCount <= 16384, flags < 8, first == UInt64.max || first <= UInt64(Int64.max),
          last == UInt64.max || last <= UInt64(Int64.max)
        else { return nil }
        var node = MonitorPlotNode()
        node.firstRawUs = first == UInt64.max ? nil : Int64(first)
        node.lastRawUs = last == UInt64.max ? nil : Int64(last)
        node.leadingBarrier = flags & 1 != 0
        node.trailingBarrier = flags & 2 != 0
        node.completeSegments = flags & 4 != 0
        for _ in 0..<pointCount { node.points.append(try reference()) }
        let start = Int64(index) * 8_000_000
        node.admitted = try node.core(metric: metric, start: start, end: start + 8_000_000, identity: lookup.identity)
        result.put(node, at: result.leafCount + index)
        guard cache.bytes + result.bytes <= cache.byteLimit else { return nil }
      }
      guard offset == limit else { return nil }
      try result.rebuild(
        from: 0, through: count - 1, metric: metric, lookup: lookup,
        admit: { cache.bytes + result.bytes <= cache.byteLimit })
      try lookup.check()
      cache.counters["pyramidDiskHits", default: 0] += 1
      return result
    } catch { return nil }
  }
  private func pyramid(_ cache: MonitorWorkspace, _ c: MonitorChannel, _ source: MonitorDescription, width: Double)
    throws -> MonitorPyramid?
  {
    guard width >= MonitorPyramid.seconds else { return nil }
    let key = projectionKey(c, source)
    var existing = cache.pyramids[key]
    if let value = existing, value.revision != source.revision {
      // Reuse only a complete append-only proof. Late/corrected samples and changed
      // source semantics rebuild the tree; never silently keep stale extrema.
      let changes = try store.read { db in
        try db.rows(
          "SELECT revision,kind,metrics FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT 512",
          [.text(source.id), .integer(value.revision), .integer(source.revision)], limit: 512)
      }
      var valid =
        changes.count == source.revision - value.revision && changes.first?.int("revision") == value.revision + 1
        && changes.last?.int("revision") == source.revision
      for row in changes {
        guard let data = row.data("metrics"), let metrics = try? JSONDecoder().decode([String].self, from: data) else {
          valid = false
          break
        }
        if (metrics.isEmpty || metrics.contains(c.column)) && row.string("kind") != "append" {
          valid = false
        }
      }
      if valid, let last = value.lastRead {
        let firstNew = try store.read { db in
          try db.rows(
            "SELECT min(m.elapsed_seconds) AS time FROM collection_memberships m INDEXED BY membership_snapshot JOIN \(c.table) h ON h.observation_id=m.observation_id WHERE \(baseWhere(c)) AND m.revision>?",
            values(c, source) + [.integer(value.revision)], limit: 1
          ).first?.double("time")
        }
        if let firstNew, WorkoutDistancePolicy.timeUs(firstNew) <= last.timeUs { valid = false }
      }
      if !valid {
        cache.pyramids.removeValue(forKey: key)
        existing = nil
        cache.counters["pyramidInvalidations", default: 0] += 1
      }
    }
    if let value = existing, source.end >= Double(value.leafCount) * MonitorPyramid.seconds {
      cache.pyramids.removeValue(forKey: key)
      existing = nil
    }
    if existing == nil {
      let count = try store.read { db in
        try db.scalarInt(
          "SELECT COALESCE(SUM(count),0) FROM collection_channels WHERE collection_id=? AND metric=? AND source=? AND representation=?",
          [.text(source.id), .text(c.column), .text(c.source), .text(c.rawHeart ? "raw" : "")]) ?? 0
      }
      guard count >= 16_384, makeProjectionRoom(cache, MonitorPyramid.estimate(source.end), keeping: key) else {
        return nil
      }
      let value =
        try loadPyramid(cache, key, source, metric: c.metric)
        ?? MonitorPyramid(key: key, end: source.end, revision: source.revision)
      guard cache.bytes + value.bytes <= cache.byteLimit else { return nil }
      cache.pyramids[key] = value
      existing = value
      cache.counters["pyramidBuilds", default: 0] += 1
      cache.counters["peakNativeAdapterBytes"] = max(cache.counters["peakNativeAdapterBytes", default: 0], cache.bytes)
    }
    guard let value = existing else { return nil }
    cache.tick &+= 1
    value.used = cache.tick
    if value.lastRead == nil || value.revision != source.revision {
      let after = value.lastRead
      let firstLeaf = Int((after?.time ?? 0) / MonitorPyramid.seconds)
      let lookup = MonitorIdentityLookup(try store)
      var leaf = firstLeaf
      var reducer = try MonitorInspection.plot(
        metric: c.metric, start: Int64(leaf) * 8_000_000,
        end: Int64(leaf + 1) * 8_000_000, buckets: 1, owned: true, identity: lookup.identity)
      var input = MonitorNumericPage(plot: reducer, cache: cache)
      do {
        try scanProjected(cache, c, source, start: Double(firstLeaf) * MonitorPyramid.seconds, end: source.end) { p in
          let next = Int(p.timeUs / 8_000_000)
          if next != leaf {
            try input.flush()
            let leafBegan = ProcessInfo.processInfo.systemUptime
            value.put(MonitorPlotNode(try reducer.aggregate(), lookup: lookup), at: value.leafCount + leaf)
            cache.counters["pyramidLeafProjectionMicros", default: 0] += Int(
              (ProcessInfo.processInfo.systemUptime - leafBegan) * 1_000_000)
            guard cache.bytes <= cache.byteLimit else { throw MonitorPyramidCapacityExceeded() }
            lookup.displayTimes.removeAll(keepingCapacity: true)
            leaf = next
            reducer = try MonitorInspection.plot(
              metric: c.metric, start: Int64(leaf) * 8_000_000,
              end: Int64(leaf + 1) * 8_000_000, buckets: 1, owned: true, identity: lookup.identity)
            input = MonitorNumericPage(plot: reducer, cache: cache)
          }
          lookup.displayTimes[p.id] = p.time
          try input.append(p)
          value.lastRead = p
          if after == nil || p.time > after!.time { cache.counters["pyramidOriginalPoints", default: 0] += 1 }
        }
        try input.flush()
        let leafBegan = ProcessInfo.processInfo.systemUptime
        value.put(MonitorPlotNode(try reducer.aggregate(), lookup: lookup), at: value.leafCount + leaf)
        cache.counters["pyramidLeafProjectionMicros", default: 0] += Int(
          (ProcessInfo.processInfo.systemUptime - leafBegan) * 1_000_000)
        guard cache.bytes <= cache.byteLimit else { throw MonitorPyramidCapacityExceeded() }
        lookup.displayTimes.removeAll(keepingCapacity: true)
        let parentBegan = ProcessInfo.processInfo.systemUptime
        try value.rebuild(
          from: firstLeaf, through: leaf, metric: c.metric, lookup: lookup,
          admit: { cache.bytes <= cache.byteLimit })
        cache.counters["pyramidParentMicros", default: 0] += Int(
          (ProcessInfo.processInfo.systemUptime - parentBegan) * 1_000_000)
        try lookup.check()
        guard cache.bytes <= cache.byteLimit else { throw MonitorPyramidCapacityExceeded() }
        value.revision = source.revision
        try savePyramid(value, source, cache: cache)
      } catch is MonitorPyramidCapacityExceeded {
        cache.pyramids.removeValue(forKey: key)
        cache.counters["streamingFallbacks", default: 0] += 1
        return nil
      } catch {
        cache.pyramids.removeValue(forKey: key)
        if (error as NSError).kotlinException is InspectionCapacityException {
          cache.counters["streamingFallbacks", default: 0] += 1
          return nil
        }
        throw error
      }
    }
    return value
  }
  private func reducePyramid(
    _ cache: MonitorWorkspace, _ pyramid: MonitorPyramid, _ c: MonitorChannel,
    _ source: MonitorDescription, start: Double, end: Double, reducer: InspectionPlotReducer,
    lookup: MonitorIdentityLookup
  ) throws {
    func collect(_ node: Int, lower: Int64, span: Int64) throws {
      let value = pyramid.nodes[node]
      guard let first = value.firstRawUs, let last = value.lastRawUs,
        last >= reducer.range.startUs, first <= reducer.range.endUs
      else { return }
      guard let aggregate = value.admitted else { return }
      if reducer.canMerge(aggregate: aggregate) {
        try reducer.merge(aggregate: aggregate)
        cache.counters["pyramidNodes", default: 0] += 1
      } else if node < pyramid.leafCount {
        try collect(node * 2, lower: lower, span: span / 2)
        try collect(node * 2 + 1, lower: lower + span / 2, span: span / 2)
      } else {
        let input = MonitorNumericPage(plot: reducer, cache: cache)
        try plotBoundary(
          cache, c, source, start: max(start, WorkoutDistancePolicy.seconds(lower)),
          end: min(end, WorkoutDistancePolicy.seconds(lower + span).nextDown)
        ) { point in
          try input.append(point)
          cache.counters["pyramidEdgePoints", default: 0] += 1
        }
        try input.flush()
      }
    }
    try collect(1, lower: 0, span: Int64(pyramid.leafCount) * 8_000_000)
    cache.counters["pyramidReads", default: 0] += 1
  }
  func readLatest(_ request: MonitorRequest) throws -> [String: Any] {
    if request.source == "live", let preview { return try preview.readLatest(request) }
    let source = try description(request)
    return try retryDistance(request, source: source) { try readLatest(request, source: source) }
  }
  private func readLatest(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    let live = !(try presentationTiming(request, source)).isEmpty
    var acquisitions: [String: Any] = [:]
    // Admission and evidence publication share this executor, so identity lookups see the same revision.
    var (result, source) = try store.read { _ in
      var source = try description(request, id: source.id)
      var distance: WorkoutDistanceSnapshot?
      if !WorkoutDistanceStore.derivedMetrics.isDisjoint(with: request.metrics) {
        distance = try distanceSnapshot(source, build: false)
        if distance == nil, !live {
          distance = try distanceStore.latestCachedSnapshot(id: source.id, selection: source.distanceSelection)
        }
        if distance == nil, !live { throw WorkoutDistanceError.pending }
        if let distance, distance.revision != source.revision, !live {
          let metadata = try store.read { db in
            let bytes = try db.rows(
              "SELECT metadata FROM collection_versions WHERE collection_id=? AND revision<=? ORDER BY revision DESC LIMIT 1",
              [.text(source.id), .integer(distance.revision)], limit: 1
            ).first?.data("metadata")
            return (bytes.flatMap { try? JSONSerialization.jsonObject(with: $0) }) as? [String: Any] ?? [:]
          }
          source = MonitorDescription(
            id: source.id, sourceID: source.sourceID, revision: distance.revision, startedAt: source.startedAt,
            end: max(10, distance.endSeconds), phase: metadata["phase"] as? String ?? source.phase, metadata: metadata,
            available: source.available, distanceSelection: source.distanceSelection)
        }
      }
      var points: [String: Any] = [:]
      for metric in Set(request.metrics).sorted() {
        acquisitions[metric] = NSNull()
        if WorkoutDistanceStore.derivedMetrics.contains(metric) {
          guard let base = distance else {
            points[metric] = NSNull()
            continue
          }
          let distance = try distanceStore.selecting(base, metric: metric, main: source.distanceSelection)
          let point = try distanceStore.neighbor(snapshot: distance, seconds: Self.maximumSeconds, before: true)
          points[metric] = point?.dictionary ?? NSNull() as Any
          if live, let point {
            acquisitions[metric] = try liveAcquisition(anchor: point.endAnchor, source: source) as Any? ?? NSNull()
          }
          continue
        }
        if live, let newest = try liveNeighbor(metric, source) {
          points[metric] = newest.0.dictionary
          acquisitions[metric] = newest.1
          continue
        }
        // Charts may show the last recorded sample without claiming it is a fresh live reading.
        guard let channel = try channel(metric, source: source) else {
          points[metric] = NSNull()
          continue
        }
        var point = try neighbor(channel, source, time: Self.maximumSeconds, before: true)
        if let fallback = channel.latestFallback {
          if let newer = try neighbor(fallback, source, time: Self.maximumSeconds, before: true),
            newer.time > (point?.time ?? -.infinity)
          {
            point = newer
          }
        }
        points[metric] = point?.dictionary ?? NSNull() as Any
      }
      var result = envelope(request, source)
      result["points"] = points
      if let distance {
        result["metricSources"] = distanceMetricSources(distance)
      } else if !WorkoutDistanceStore.derivedMetrics.isDisjoint(with: request.metrics) {
        result["metricSources"] = [
          "distanceMeters": ["source": "pending", "label": "Calculating distance", "estimated": false]
        ]
      }
      return (result, source)
    }
    result = try published(result, request: request, source: source)
    let timing = try presentationTiming(request, source)
    result.merge(timing) { _, new in new }
    if !timing.isEmpty { result["liveAcquiredAt"] = acquisitions }
    return result
  }
  func inspectAt(_ request: MonitorRequest) throws -> [String: Any] {
    if request.source == "live", let preview { return try preview.inspectAt(request) }
    let source = try description(request)
    return try retryDistance(request, source: source) { try inspectAt(request, source: source) }
  }
  private func inspectAt(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    if let retry = mismatch(request, source) { return retry }
    guard let time = request.seconds else { throw MonitorError.invalid("Choose an inspection time.") }
    var points: [String: Any] = [:]
    var gaps: [String: Bool] = [:]
    var distance: WorkoutDistanceSnapshot?
    if !WorkoutDistanceStore.derivedMetrics.isDisjoint(with: request.metrics) {
      distance = try distanceSnapshot(source, build: false)
      guard distance != nil else { throw WorkoutDistanceError.pending }
    }
    for metric in Set(request.metrics).sorted() {
      if WorkoutDistanceStore.derivedMetrics.contains(metric), let base = distance {
        let distance = try distanceStore.selecting(base, metric: metric, main: source.distanceSelection)
        let selected = try inspectDistance(
          distance, time: time, anchor: request.anchor?.metric == metric ? request.anchor : nil)
        points[metric] = selected?.dictionary ?? NSNull() as Any
        gaps[metric] = selected == nil
        continue
      }
      var selected: MonitorOriginal?
      if let c = try channel(metric, source: source) {
        let target = MonitorInspection.target(
          time: time, identity: request.anchor?.metric == metric ? request.anchor?.observationId : nil)
        let timeline = try activeTimeline(source, sourceKind: request.source)
        let lookup = MonitorIdentityLookup(try store)
        let selector = try MonitorInspection.query(
          metric: metric, start: 0, end: timeline.endUs,
          timeline: timeline, selection: target,
          trailing: request.source == "live" || ["running", "paused"].contains(source.phase),
          identity: lookup.identity)
        var originals: [String: MonitorOriginal] = [:]
        var rows = try inspectionRows(c, source, time: time, before: true)
        rows += try inspectionRows(c, source, time: time, before: false)
        if let anchor = request.anchor, anchor.metric == metric {
          rows += try store.read { db in
            try db.rows(
              "\(selectSQL(c, anchored: true)) WHERE \(baseWhere(c)) AND o.physical_id=? LIMIT 1",
              values(c, source) + [.text(anchor.observationId)], limit: 1)
          }
        }
        for row in rows {
          if let point = original(row) { originals[point.identity] = point }
          try selector.appendInspection(item: analysisItem(row, channel: c), selected: true)
        }
        let result = try selector.result().selection
        selected = result.flatMap { originals[$0.observationId] }
      }
      points[metric] = selected?.dictionary ?? NSNull() as Any
      gaps[metric] = selected == nil
    }
    var result = envelope(request, source)
    result["seconds"] = time
    result["points"] = points
    result["gaps"] = gaps
    if let distance { result["metricSources"] = distanceMetricSources(distance) }
    return try published(result, request: request, source: source)
  }
  private func inspectDistance(_ snapshot: WorkoutDistanceSnapshot, time: Double, anchor: MonitorObservationAnchor?)
    throws -> WorkoutDistancePoint?
  {
    let before = try distanceStore.neighbor(snapshot: snapshot, seconds: time, before: true)
    let after = try distanceStore.neighbor(snapshot: snapshot, seconds: time, before: false)
    let anchored = try anchor.flatMap { try distanceStore.anchor(snapshot: snapshot, identity: $0.observationId) }
    let result = try DistanceInspection.shared.select(
      target: MonitorInspection.target(time: time, identity: anchor?.observationId),
      before: before.map(distancePoint), after: after.map(distancePoint), anchored: anchored.map(distancePoint))
    return [before, after, anchored].compactMap { $0 }.first {
      $0.dictionary["observationId"] as? String == result?.observationId
    }
  }
  private func distancePoint(_ point: WorkoutDistancePoint) -> DistanceInspectionPoint {
    let identity = point.dictionary["observationId"] as! String
    return MonitorInspection.distancePoint(
      original: WorkoutDistancePolicy.original(
        identity, point.dictionary["timestamp"] as! String, point.elapsedSeconds, point.distanceMeters),
      timeUs: WorkoutDistancePolicy.timeUs(point.elapsedSeconds), segment: Int32(point.segment),
      startUs: WorkoutDistancePolicy.timeUs(point.startSeconds), endUs: WorkoutDistancePolicy.timeUs(point.endSeconds),
      tieKey: point.pointID, plotSegment: Int32(point.plotSegment))
  }
  private func distancePlot(
    _ cache: MonitorWorkspace, source: MonitorDescription, snapshot: WorkoutDistanceSnapshot, start: Double,
    end: Double, count: Int, metric: String
  ) throws -> [[String: Any]] {
    guard start <= end else { return [] }
    let key =
      "distance|" + source.sourceID + "|" + metric + "|" + snapshot.generation + "|" + snapshot.selection + "|"
      + String(snapshot.revision)
    let lookup = MonitorIdentityLookup(try store)
    let identity: (KotlinLong) -> String = { "distance:" + snapshot.generation + ":" + String($0.int64Value) }
    func scan(_ start: Double, _ end: Double, _ body: (WorkoutDistanceStore.ChartReference) throws -> Void) throws {
      var after: WorkoutDistanceCursor?
      while true {
        let began = ProcessInfo.processInfo.systemUptime
        let page = try distanceStore.chartPage(snapshot: snapshot, after: after, start: start, end: end) { steps in
          cache.counters["distanceReadVMSteps", default: 0] += steps
          cache.counters["distanceReadVMMaxSteps"] = max(cache.counters["distanceReadVMMaxSteps", default: 0], steps)
        }
        cache.counters["distanceReadMicros", default: 0] += Int(
          (ProcessInfo.processInfo.systemUptime - began) * 1_000_000)
        cache.counters["distanceScannedPoints", default: 0] += page.count
        for point in page { try body(point) }
        guard let last = page.last, page.count == 256 else { return }
        after = WorkoutDistanceCursor(time: last.seconds, pointID: last.pointID)
      }
    }
    func numeric(_ point: WorkoutDistanceStore.ChartReference) -> MonitorProjectedPoint {
      MonitorProjectedPoint(id: point.pointID, time: point.seconds, value: point.value)
    }
    var hierarchy = cache.pyramids[key]
    if hierarchy == nil, (end - start) / Double(count) >= MonitorPyramid.seconds,
      makeProjectionRoom(cache, MonitorPyramid.estimate(source.end), keeping: key)
    {
      let pyramid =
        try loadPyramid(cache, key, source, metric: metric)
        ?? MonitorPyramid(key: key, end: source.end, revision: source.revision)
      if pyramid.lastRead == nil {
        var leaf = 0
        var reducer = try MonitorInspection.plot(
          metric: metric, start: 0, end: 8_000_000, buckets: 1, owned: true, identity: identity)
        var input = MonitorNumericPage(plot: reducer, cache: cache)
        try scan(0, source.end) { point in
          let next = Int(WorkoutDistancePolicy.timeUs(point.seconds) / 8_000_000)
          if next != leaf {
            try input.flush()
            pyramid.put(MonitorPlotNode(try reducer.aggregate(), lookup: lookup), at: pyramid.leafCount + leaf)
            lookup.displayTimes.removeAll(keepingCapacity: true)
            leaf = next
            reducer = try MonitorInspection.plot(
              metric: metric, start: Int64(leaf) * 8_000_000, end: Int64(leaf + 1) * 8_000_000, buckets: 1,
              owned: true, identity: identity)
            input = MonitorNumericPage(plot: reducer, cache: cache)
          }
          let pointValue = numeric(point)
          try input.append(pointValue, distanceSegment: point.plotSegment)
          pyramid.lastRead = pointValue
        }
        try input.flush()
        pyramid.put(MonitorPlotNode(try reducer.aggregate(), lookup: lookup), at: pyramid.leafCount + leaf)
        lookup.displayTimes.removeAll(keepingCapacity: true)
        let parentBegan = ProcessInfo.processInfo.systemUptime
        try pyramid.rebuild(
          from: 0, through: leaf, metric: metric, lookup: lookup,
          admit: { cache.bytes + pyramid.bytes <= cache.byteLimit })
        cache.counters["pyramidParentMicros", default: 0] += Int(
          (ProcessInfo.processInfo.systemUptime - parentBegan) * 1_000_000)
        try savePyramid(pyramid, source, cache: cache)
        cache.counters["distancePyramidBuilds", default: 0] += 1
      }
      if cache.bytes + pyramid.bytes <= cache.byteLimit {
        cache.pyramids[key] = pyramid
        hierarchy = pyramid
      }
    }
    var slot: Int64?
    if hierarchy != nil {
      var width: Int64 = 8_000_000
      while Double(width) < (end - start) / Double(count) * 1_000_000 { width *= 2 }
      slot = width
    }
    let reducer = try MonitorInspection.plot(
      metric: metric, start: WorkoutDistancePolicy.timeUs(start), end: WorkoutDistancePolicy.timeUs(end),
      buckets: count, slot: slot, identity: identity)
    let input = MonitorNumericPage(plot: reducer, cache: cache)
    if let before = try distanceStore.chartNeighbor(snapshot: snapshot, seconds: start, before: true) {
      try input.append(numeric(before), distanceSegment: before.plotSegment)
    }
    try input.flush()
    if let hierarchy {
      func collect(_ node: Int, lower: Int64, span: Int64) throws {
        let value = hierarchy.nodes[node]
        guard let first = value.firstRawUs, let last = value.lastRawUs, last >= reducer.range.startUs,
          first <= reducer.range.endUs
        else { return }
        guard let aggregate = value.admitted else { return }
        if reducer.canMerge(aggregate: aggregate) {
          try reducer.merge(aggregate: aggregate)
          cache.counters["distancePyramidNodes", default: 0] += 1
        } else if node < hierarchy.leafCount {
          try collect(node * 2, lower: lower, span: span / 2)
          try collect(node * 2 + 1, lower: lower + span / 2, span: span / 2)
        } else {
          try scan(
            max(start, WorkoutDistancePolicy.seconds(lower)),
            min(end, WorkoutDistancePolicy.seconds(lower + span).nextDown)
          ) { try input.append(numeric($0), distanceSegment: $0.plotSegment) }
          try input.flush()
        }
      }
      try collect(1, lower: 0, span: Int64(hierarchy.leafCount) * 8_000_000)
    } else {
      try scan(start, end) { try input.append(numeric($0), distanceSegment: $0.plotSegment) }
    }
    try input.flush()
    if let after = try distanceStore.chartNeighbor(snapshot: snapshot, seconds: end, before: false) {
      try input.append(numeric(after), distanceSegment: after.plotSegment)
    }
    try input.flush()
    let retained = try reducer.result()
    let hydrationBegan = ProcessInfo.processInfo.systemUptime
    defer {
      cache.counters["retainedHydrationMicros", default: 0] += Int(
        (ProcessInfo.processInfo.systemUptime - hydrationBegan) * 1_000_000)
    }
    let pin = key
    if cache.distanceHydrationPin != pin {
      cache.distanceHydrationPin = pin
      cache.distanceHydrated.removeAll(keepingCapacity: true)
    }
    let ids = retained.map { $0.point.reference!.reference }
    let missing = ids.filter { cache.distanceHydrated[$0] == nil }
    var hydrated = cache.distanceHydrated
    for offset in stride(from: 0, to: missing.count, by: 256) {
      let page = try distanceStore.chartPoints(
        snapshot: snapshot, ids: Array(missing[offset..<min(missing.count, offset + 256)]))
      cache.counters["distanceHydratedPoints", default: 0] += page.count
      for point in page { hydrated[point.pointID] = point }
    }
    cache.distanceHydrated.removeAll(keepingCapacity: true)
    let retainedLimit = min(2048, max(0, (cache.byteLimit - cache.bytes) / 512))
    cache.distanceHydrated = Dictionary(uniqueKeysWithValues: ids.suffix(retainedLimit).map { ($0, hydrated[$0]!) })
    var previous: Int32?
    return try retained.map { point in
      guard let original = hydrated[point.point.reference!.reference] else {
        throw MonitorError.invalid("Distance chart reference is unavailable")
      }
      var row = original.dictionary
      row["value"] = original.distanceMeters
      row["startsSegment"] = previous == nil || previous != point.segment
      previous = point.segment
      return row
    }
  }
  func readPlot(_ request: MonitorRequest) throws -> [String: Any] {
    if request.source == "live", let preview { return try preview.readPlot(request) }
    let source = try description(request)
    return try retryDistance(request, source: source) { try readPlot(request, source: source) }
  }
  private func readPlot(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    let cache = plotWorkspace
    guard cache.lock.try() else { throw MonitorError.cacheContention("plot") }
    defer { releaseWorkspace(cache, source: source) }
    if let retry = mismatch(request, source) { return retry }
    let start = request.startSeconds ?? 0
    let end = min(source.end, request.endSeconds ?? source.end)
    let metrics = Set(request.metrics).sorted()
    let pointLimit = Self.maximumPlotPoints / max(1, metrics.count)
    let distance =
      !WorkoutDistanceStore.derivedMetrics.isDisjoint(with: metrics) ? try distanceSnapshot(source, build: true) : nil
    let count = max(1, min(request.buckets, Self.maximumPlotPoints / max(1, metrics.count) / 4 - 2))
    let key = [
      source.id, String(source.revision), distance?.generation ?? "", distance?.selection ?? "", String(start),
      String(end), String(count), metrics.joined(separator: ","),
    ].joined(separator: "|")
    let persistOverview = source.phase == "completed" && start == 0 && end == source.end
    if persistOverview, let cached = try cachedPlot(key, source: source) {
      var result = cached
      result["generation"] = request.generation
      return try published(result, request: request, source: source)
    }
    var series: [String: [[String: Any]]] = [:]
    for metric in metrics {
      series[metric] = []
      if WorkoutDistanceStore.derivedMetrics.contains(metric), let base = distance {
        let distance = try distanceStore.selecting(base, metric: metric, main: source.distanceSelection)
        series[metric] = try distancePlot(
          cache, source: source, snapshot: distance, start: start, end: end, count: count, metric: metric)
        continue
      }
      guard let c = try channel(metric, source: source) else { continue }
      guard start <= end else { continue }
      let lookup = MonitorIdentityLookup(try store)
      let width = (end - start) / Double(count)
      let hierarchy = try pyramid(cache, c, source, width: width)
      var slot: Int64?
      if hierarchy != nil {
        var span: Int64 = 8_000_000
        while Double(span) < width * 1_000_000 { span *= 2 }
        slot = span
      }
      let reducer = try MonitorInspection.plot(
        metric: metric, start: WorkoutDistancePolicy.timeUs(start),
        end: WorkoutDistancePolicy.timeUs(end), buckets: count, limit: pointLimit, slot: slot, identity: lookup.identity
      )
      let input = MonitorNumericPage(plot: reducer, cache: cache)
      let predecessor = try neighbor(c, source, time: start, before: true, strict: true)
      let successor = try neighbor(c, source, time: end, before: false, strict: true)
      if let predecessor {
        try plotBoundary(
          cache, c, source, start: predecessor.time,
          end: WorkoutDistancePolicy.seconds(WorkoutDistancePolicy.timeUs(start) - 1)
        ) {
          try input.append($0)
        }
      }
      try input.flush()
      if let hierarchy {
        try reducePyramid(cache, hierarchy, c, source, start: start, end: end, reducer: reducer, lookup: lookup)
      } else {
        try project(cache, c, source, start: start, end: end) {
          try input.append($0)
        }
      }
      try input.flush()
      if let successor {
        try plotBoundary(
          cache, c, source, start: WorkoutDistancePolicy.seconds(WorkoutDistancePolicy.timeUs(end) + 1),
          end: successor.time
        ) {
          try input.append($0)
        }
      }
      try input.flush()
      let retained = try reducer.result()
      try lookup.check()
      let originals = try hydrate(cache, retained.map { .from($0.point) }, source: source)
      var previousSegment: Int32?
      series[metric] = try retained.map { point in
        guard let original = originals[point.point.reference!.reference] else {
          throw MonitorError.invalid("A chart observation is unavailable.")
        }
        var row = original.dictionary
        row["startsSegment"] = previousSegment == nil || previousSegment != point.segment
        previousSegment = point.segment
        return row
      }
    }
    var result = envelope(request, source)
    result["series"] = series
    if let distance { result["metricSources"] = distanceMetricSources(distance) }
    let published = try published(result, request: request, source: source)
    if persistOverview, published["status"] as? String == "ok" { try cachePlot(result, key: key, source: source) }
    return published
  }
  private func cachedPlot(_ key: String, source: MonitorDescription) throws -> [String: Any]? {
    try store.read { db in
      guard
        let bytes = try db.rows(
          "SELECT value FROM derived_cache WHERE key=? AND revision=?", [.text(key), .integer(source.revision)],
          limit: 1
        ).first?.data("value"), bytes.count <= 4 * 1024 * 1024
      else { return nil }
      return try JSONSerialization.jsonObject(with: bytes) as? [String: Any]
    }
  }
  private func cachePlot(_ value: [String: Any], key: String, source: MonitorDescription) throws {
    let bytes = try JSONSerialization.data(withJSONObject: value)
    try cacheBytes(bytes, key: key, source: source)
  }
  private func cacheBytes(_ bytes: Data, key: String, source: MonitorDescription) throws {
    guard bytes.count <= 4 * 1024 * 1024 else {
      throw MonitorError.invalid("Derived chart cache exceeds the persisted byte limit")
    }
    try store.transaction(priority: .background) { db in
      if try store.isWorkoutDeleted(id: source.id) { throw PowerLogStorageError.deleted(source.id) }
      try db.execute(
        "DELETE FROM derived_cache WHERE collection_id=? AND revision<>?",
        [.text(source.id), .integer(source.revision)])
      let used = try db.scalarInt("SELECT COALESCE(SUM(length(value)),0) FROM derived_cache") ?? 0
      if used + Int64(bytes.count) > Int64(Self.maximumCachedBytes) { try db.execute("DELETE FROM derived_cache") }
      try db.execute(
        "INSERT OR REPLACE INTO derived_cache(key,collection_id,revision,value) VALUES(?,?,?,?)",
        [.text(key), .text(source.id), .integer(source.revision), .blob(bytes)])
    }
  }
  func rangeStats(_ request: MonitorRequest) throws -> [String: Any] {
    if request.source == "live", let preview { return try preview.rangeStats(request) }
    let source = try description(request)
    return try retryDistance(request, source: source) { try rangeStats(request, source: source) }
  }
  private func rangeStats(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    let cache = statisticsWorkspace
    guard cache.lock.try() else { throw MonitorError.cacheContention("statistics") }
    defer { releaseWorkspace(cache, source: source) }
    if let retry = mismatch(request, source) { return retry }
    guard let start = request.startSeconds, let end = request.endSeconds else {
      throw MonitorError.invalid("Choose the statistics interval.")
    }
    let timeline = try activeTimeline(source, sourceKind: request.source)
    let distance =
      !WorkoutDistanceStore.derivedMetrics.isDisjoint(with: request.metrics)
      ? try distanceSnapshot(source, build: true) : nil
    var stats: [String: Any] = [:]
    for metric in Set(request.metrics).sorted() {
      if WorkoutDistanceStore.derivedMetrics.contains(metric), let base = distance {
        let distance = try distanceStore.selecting(base, metric: metric, main: source.distanceSelection)
        if WorkoutDistanceStore.batteryMetrics.contains(metric) {
          stats[metric] = try distanceStore.batteryRange(snapshot: distance, start: start, end: end)
          continue
        }
        let range = try distanceStore.range(snapshot: distance, start: start, end: end)
        var value: [String: Any] = [
          "coveredSeconds": range.coveredSeconds, "unresolvedBoundary": range.unresolvedBoundary,
          "partial": range.partial,
        ]
        if let meters = range.distanceMeters { value["distance"] = meters }
        stats[metric] = value
        continue
      }
      guard let c = try channel(metric, source: source) else { continue }
      let lookup = MonitorIdentityLookup(try store)
      let reducer = try MonitorInspection.query(
        metric: metric, start: WorkoutDistancePolicy.timeUs(start),
        end: WorkoutDistancePolicy.timeUs(end), timeline: timeline, identity: lookup.identity)
      let input = MonitorNumericPage(query: reducer, cache: cache)
      let boundaries = try interruptions(source)
      for row in try inspectionRows(c, source, time: start, before: true, strict: true, admitted: false) {
        try input.append(projected(row, boundaries: boundaries))
      }
      try project(cache, c, source, start: start, end: end) {
        try input.append($0)
      }
      for row in try inspectionRows(c, source, time: end, before: false, strict: true, admitted: false) {
        try input.append(projected(row, boundaries: boundaries))
      }
      try input.flush()
      let result = try reducer.result()
      try lookup.check()
      guard let outcome = result.statistics else { continue }
      guard let complete = outcome as? MetricOutcome.Complete else {
        throw MonitorError.invalid("Chart statistics numeric overflow.")
      }
      let summary = complete.result
      let statistics = summary.statistics
      var value: [String: Any] = ["count": Int(statistics.count)]
      let retained = [summary.minimum, summary.maximum].compactMap { $0?.reading }
      let projectedExtrema = retained.map { reading in
        MonitorProjectedPoint(
          id: reading.reference!.reference, time: WorkoutDistancePolicy.seconds(reading.timeUs),
          value: reading.value!.real(), integer: (reading.value as? Scalar.Integer64)?.value)
      }
      let hydrated = try hydrate(
        cache, Array(Dictionary(projectedExtrema.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a }).values),
        source: source)
      if let minimum = summary.minimum?.reading.reference { value["min"] = hydrated[minimum.reference]?.dictionary }
      if let maximum = summary.maximum?.reading.reference { value["max"] = hydrated[maximum.reference]?.dictionary }
      if let mean = statistics.sampleMean { value["sampleMean"] = mean.doubleValue }
      if let covered = statistics.coveredSeconds { value["coveredSeconds"] = covered.doubleValue }
      if let integral = statistics.integral { value["integral"] = integral.doubleValue }
      stats[metric] = value
    }
    var result = envelope(request, source)
    result["statistics"] = stats
    if let distance { result["metricSources"] = distanceMetricSources(distance) }
    if request.includeEndpoints {
      var endpoint = request
      endpoint.seconds = start
      endpoint.anchor = request.startAnchor
      let first = try inspectAt(endpoint, source: source)["points"] ?? [:]
      endpoint.seconds = end
      endpoint.anchor = request.endAnchor
      let last = try inspectAt(endpoint, source: source)["points"] ?? [:]
      result["endpoints"] = ["start": first, "end": last]
    }
    return try published(result, request: request, source: source)
  }
  private func activeTimeline(_ source: MonitorDescription, sourceKind: String) throws -> ActiveTimeline {
    var events: [AnalysisItem] = []
    var after: Int64 = 0
    if sourceKind == "workout" {
      while true {
        let rows = try store.read { db in
          try db.rows(
            "SELECT m.id,m.elapsed_seconds,m.elapsed_us,m.producer,m.sequence,l.action,o.physical_id,o.original_timestamp,o.clock_epoch,json_extract(o.extra,'$.interrupted') AS interrupted,json_extract(o.extra,'$.controllerSequence') AS controller_sequence FROM collection_memberships m INDEXED BY membership_stream_time JOIN observations o ON o.id=m.observation_id JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND m.id>? ORDER BY m.id LIMIT 256",
            [
              .text(source.id), .integer(source.revision), .integer(source.revision), .integer(source.revision),
              .integer(after),
            ], limit: 256)
        }
        guard let last = rows.last else { break }
        for row in rows {
          guard events.count < 10_000 else { throw MonitorError.invalid("Too many lifecycle transitions.") }
          events.append(
            AnalysisItem(
              collectionUs: row.int("elapsed_us")!, collectionElapsedSeconds: row.double("elapsed_seconds")!,
              eventId: row.string("physical_id")!, observationId: row.string("physical_id")!,
              producer: row.string("producer")!,
              sequence: row.int("sequence")!, kind: "lifecycle", source: "phone",
              timestamp: row.string("original_timestamp")!,
              values: [:], originalElapsedSeconds: nil, representation: nil,
              continuity: Continuity(clockEpoch: row.string("clock_epoch"), connectionEpoch: nil, interruptionIndex: 0),
              action: row.string("action"), distanceBarrier: false, interrupted: row.int("interrupted") == 1,
              controllerSequence: row.string("controller_sequence").flatMap(Int64.init).map(KotlinLong.init(value:)),
              hasElapsedMapping: true, origin: nil, declaredMetrics: nil))
        }
        after = last.int("id")!
        if rows.count < 256 { break }
      }
    }
    var end = sourceKind == "workout" ? source.end : Self.maximumSeconds
    if let stop = source.metadata["stopElapsedSeconds"] as? Double { end = min(end, stop) }
    if let elapsed = source.metadata["elapsedSeconds"] as? Double, source.metadata["endedAt"] != nil {
      end = min(end, elapsed)
    }
    for event in events where event.action == "stop" { end = min(end, event.collectionElapsedSeconds) }
    return ActiveTimeline(endUs: WorkoutDistancePolicy.timeUs(end), lifecycle: events)
  }
  private func analysisItem(_ row: PowerLogRow, channel: MonitorChannel) -> AnalysisItem {
    var values: [String: Scalar] = [:]
    if let value = MonitorInspection.scalar(row.double("value"), integer: row.int("value")) {
      values[channel.metric] = value
    }
    if let accuracy = MonitorInspection.scalar(row.double("accuracy")) {
      values[channel.metric == "courseDegrees" ? "courseAccuracyDegrees" : "speedAccuracyMps"] = accuracy
    }
    return AnalysisItem(
      collectionUs: row.int("elapsed_us")!, collectionElapsedSeconds: row.double("elapsed_seconds")!,
      eventId: row.string("physical_id")!, observationId: row.string("physical_id")!, producer: row.string("producer")!,
      sequence: row.int("sequence")!, kind: channel.kind, source: channel.source,
      timestamp: row.string("original_timestamp")!,
      values: values, originalElapsedSeconds: nil, representation: nil,
      continuity: Continuity(
        clockEpoch: row.string("clock_epoch"), connectionEpoch: row.string("connection_epoch"), interruptionIndex: 0),
      action: nil, distanceBarrier: false, interrupted: false, controllerSequence: nil,
      hasElapsedMapping: true, origin: nil, declaredMetrics: [channel.metric])
  }
  private func projected(_ row: PowerLogRow, boundaries: [WorkoutInterruptionBoundary]) -> MonitorProjectedPoint {
    let time = row.double("elapsed_seconds")!
    return MonitorProjectedPoint(
      id: row.int("observation_id")!, time: time, value: row.double("value") ?? 0,
      integer: row.int("value"), continuity: continuity(row, interruptions: boundaries), timeUs: row.int("elapsed_us"),
      producer: row.string("producer")!, sequence: row.int("sequence")!, accuracy: row.double("accuracy"),
      missing: row.double("value") == nil,
      boundary: boundaries.contains { WorkoutDistancePolicy.timeUs($0.time) == row.int("elapsed_us")! })
  }
  private func inspectionRows(
    _ c: MonitorChannel, _ source: MonitorDescription, time: Double, before: Bool,
    strict: Bool = false, admitted: Bool = true
  ) throws -> [PowerLogRow] {
    guard
      let nearest = try neighboringRows(c, source, time: time, before: before, strict: strict, admitted: admitted)
        .first,
      let at = nearest.int("elapsed_us")
    else { return [] }
    let rows = try store.read { db in
      try db.rows(
        "\(selectSQL(c)) WHERE \(baseWhere(c)) AND m.elapsed_us=? AND m.elapsed_seconds>=? AND m.elapsed_seconds<? ORDER BY m.producer,m.sequence LIMIT 256",
        values(c, source) + [
          .integer(at), .real(WorkoutDistancePolicy.seconds(at).nextDown),
          .real(WorkoutDistancePolicy.seconds(at + 1).nextUp),
        ],
        limit: 256)
    }
    guard rows.count < 256 else { throw MonitorError.invalid("Too many coincident chart observations.") }
    return admitted
      ? rows.filter {
        Metrics.shared.plotValid(
          metric: c.metric,
          value: MonitorInspection.scalar($0.double("value"), integer: $0.int("value")),
          accuracy: MonitorInspection.scalar($0.double("accuracy")))
      } : rows
  }
  func changesSince(_ request: MonitorRequest) throws -> [String: Any] {
    if request.source == "live", let preview { return try preview.changesSince(request) }
    return try changesSince(request, source: description(request))
  }
  private func changesSince(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    guard let text = request.sinceRevision, let token = MonitorRevisionToken.parse(text) else {
      throw MonitorError.invalid("A source revision is required.")
    }
    let since = token.revision
    let rows = try store.read { db in
      try db.rows(
        "SELECT revision,min_us,max_us,kind,metrics FROM collection_changes WHERE collection_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT 512",
        [.text(source.id), .integer(since), .integer(source.revision)], limit: 512)
    }
    var result = envelope(request, source)
    result["resetRequired"] =
      since > source.revision
      || (since < source.revision
        && ((rows.first?.int("revision") ?? source.revision + 1) > since + 1
          || (rows.last?.int("revision") ?? -1) < source.revision))
    result["changes"] = rows.map { row -> [String: Any] in
      var change: [String: Any] = [
        "startSeconds": Double(row.int("min_us") ?? 0) / 1e6,
        "endSeconds": Double(row.int("max_us") ?? Int64(source.end * 1e6)) / 1e6,
        "kind": ["append", "correction"].contains(row.string("kind") ?? "") ? row.string("kind")! : "semantics",
      ]
      if let bytes = row.data("metrics"), let metrics = try? JSONDecoder().decode([String].self, from: bytes) {
        // Empty is the existing all-metrics sentinel, including metadata/lifecycle changes.
        // Preserve it instead of narrowing a broad invalidation to only derived metrics.
        guard !metrics.isEmpty else {
          change["metrics"] = [String]()
          return change
        }
        var affected = Set(metrics)
        if metrics.isEmpty
          || !affected.isDisjoint(with: [
            "latitude", "longitude", "horizontalAccuracyM", "speedMps", "speedAccuracyMps", "controllerSpeedMps",
            "distanceMeters",
          ])
        {
          affected.formUnion(["distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters"])
        }
        if !affected.isDisjoint(with: ["consumedAh", "consumedWh", "batteryCurrentA", "batteryVoltageV"]) {
          affected.formUnion(WorkoutDistanceStore.batteryMetrics)
        }
        if affected.contains("courseAccuracyDegrees") { affected.insert("courseDegrees") }
        if affected.contains("speedAccuracyMps") { affected.insert("speedMps") }
        change["metrics"] = affected.sorted()
      }
      return change
    }
    return try published(result, request: request, source: source)
  }
}
