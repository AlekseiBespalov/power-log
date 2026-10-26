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
  var energySmoothingSeconds: Int = 0
  var energyTotals: Bool = true
  var energyCurrent: Bool = false
  func validate() throws {
    guard ["live", "workout"].contains(source), generation >= 0,
      (1...512).contains(buckets), metrics.count <= MonitorDataStore.metrics.count,
      metrics.allSatisfy({ MonitorDataStore.metrics.contains($0) }),
      DistancePolicy.shared.validSelection(selection: distanceSource),
      EnergyPerDistancePolicy.shared.validSmoothing(seconds: Int32(energySmoothingSeconds))
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
  case describe, plot, inspect, stats, changes, latest, energyPerDistance, currentEnergyPerDistance
  var analytical: Bool {
    switch self {
    case .plot, .stats, .energyPerDistance: return true
    default: return false
    }
  }
  var lane: String {
    switch self {
    case .plot: return "plot"
    case .stats: return "statistics"
    case .energyPerDistance: return "energy"
    case .currentEnergyPerDistance: return "currentEnergy"
    default: return "fast"
    }
  }
  func routed(_ request: MonitorRequest) -> Self {
    guard self == .energyPerDistance || self == .currentEnergyPerDistance else { return self }
    return request.energyCurrent && !request.energyTotals ? .currentEnergyPerDistance : .energyPerDistance
  }
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
  private(set) var summary: InspectionPlotSummary?
  private let identity: ((KotlinLong) -> String)?
  private let cache: MonitorWorkspace?
  init(plot: InspectionPlotReducer, cache: MonitorWorkspace? = nil) {
    self.cache = cache
    self.plot = plot
    query = nil
    identity = nil
  }
  init(query: InspectionQuery, cache: MonitorWorkspace? = nil) {
    self.cache = cache
    self.query = query
    plot = nil
    identity = nil
  }
  init(
    plot: InspectionPlotReducer?, summary: InspectionPlotSummary, identity: @escaping (KotlinLong) -> String,
    cache: MonitorWorkspace
  ) {
    self.cache = cache
    self.plot = plot
    self.summary = summary
    self.identity = identity
    query = nil
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
      cache?.counters["reducedPoints", default: 0] += count
    }
    if let query {
      try AppleInspectionPages.shared.query(reducer: query, data: data, evidence: evidence, producers: producers)
    }
    if let summary, let identity {
      do {
        try AppleInspectionPages.shared.summary(
          summary: summary, data: data, evidence: evidence, producers: producers, identity: identity)
      } catch {
        guard plot != nil else { throw error }
        self.summary = nil
      }
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
private protocol MonitorSummaryProgress: AnyObject {
  var revision: Int64 { get }
  var last: MonitorProjectedPoint? { get }
  var generation: String { get }
}
private final class MonitorExhaustedSummary: MonitorSummaryProgress {
  let generation: String
  var revision: Int64
  var last: MonitorProjectedPoint?
  var used: UInt64
  init(revision: Int64, last: MonitorProjectedPoint?, generation: String, used: UInt64) {
    self.revision = revision
    self.last = last
    self.generation = generation
    self.used = used
  }
}
private final class MonitorPlotSummary: MonitorSummaryProgress {
  // Kotlin heap per retained point measured with its slot bookkeeping at two to four points per slot.
  static let pointBytes = 700
  static let overheadBytes = 2_048
  let key: String
  let core: InspectionPlotSummary
  let generation: String
  var revision: Int64
  var last: MonitorProjectedPoint?
  var used: UInt64 = 0
  var originals: [Int64: MonitorOriginal] = [:]
  var points: [Int64: WorkoutDistancePoint] = [:]
  private(set) var bytes = 0
  init(key: String, metric: String, revision: Int64, generation: String = "") throws {
    self.key = key
    core = try InspectionPlotSummary(metric: metric)
    self.revision = revision
    self.generation = generation
    measure()
  }
  func measure() {
    bytes =
      Self.overheadBytes + Int(core.retainedPoints) * Self.pointBytes + points.count * 512
      + originals.values.reduce(0) { $0 + 192 + $1.identity.utf8.count + $1.timestamp.utf8.count }
  }
}
private final class MonitorIdentityLookup {
  let store: PowerLogStore
  var identities: [Int64: String] = [:]
  var known: [Int64: MonitorOriginal] = [:]
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
    if let identity = identities[id] ?? known[id]?.identity { return identity }
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
private struct MonitorTimelinePrefix: Equatable {
  let intervals: [ActiveInterval]
  let interruptions: [InterruptionClose]
  init(_ timeline: ActiveTimeline, before endUs: Int64) {
    intervals = timeline.intervals.filter { $0.startUs < endUs }.map {
      ActiveInterval(startUs: $0.startUs, endUs: min($0.endUs, endUs))
    }
    interruptions = timeline.interruptions.filter { $0.cutoffUs < endUs }
  }
  var bytes: Int { intervals.count * 48 + interruptions.count * 256 }
}
private final class MonitorStatisticsPrefix {
  // A fixed allowance for the checkpoint's retained readings and the entry's bookkeeping.
  static let overheadBytes = 2_048
  let lookup: MonitorIdentityLookup
  let endUs: Int64
  let revision: Int64
  let checkpoint: MetricCheckpoint?
  let timeline: MonitorTimelinePrefix
  var used: UInt64 = 0
  init(
    lookup: MonitorIdentityLookup, endUs: Int64, revision: Int64, checkpoint: MetricCheckpoint?,
    timeline: MonitorTimelinePrefix
  ) {
    self.lookup = lookup
    self.endUs = endUs
    self.revision = revision
    self.checkpoint = checkpoint
    self.timeline = timeline
  }
  static func bytes(_ timeline: MonitorTimelinePrefix, identities: Int) -> Int {
    overheadBytes + timeline.bytes + identities * 128
  }
  var bytes: Int { Self.bytes(timeline, identities: lookup.identities.count) }
}

/// Mutable caches never cross analytical executors. The two limits sum to one budget.
private final class MonitorWorkspace {
  let lock = NSLock()
  let byteLimit: Int
  var projections: [String: MonitorProjection] = [:]
  var pyramids: [String: MonitorPyramid] = [:]
  var summaries: [String: MonitorPlotSummary] = [:]
  var exhausted: [String: MonitorExhaustedSummary] = [:]
  var tick: UInt64 = 0
  var counters: [String: Int] = [:]
  var distanceHydrationPin = ""
  var distanceHydrated: [Int64: WorkoutDistancePoint] = [:]
  var hydrationPin = ""
  var hydrated: [Int64: MonitorOriginal] = [:]
  var hydrationOrder: [Int64] = []
  var hydrationCursor = 0
  var hydrationBytes = 0
  var statisticsPrefixes: [String: MonitorStatisticsPrefix] = [:]
  var bytes: Int {
    projections.values.reduce(0) { $0 + $1.bytes } + pyramids.values.reduce(0) { $0 + $1.bytes } + hydrationBytes
      + distanceHydrated.count * 512 + summaries.values.reduce(0) { $0 + $1.bytes }
      + statisticsPrefixes.values.reduce(0) { $0 + $1.bytes }
  }
  init(byteLimit: Int) { self.byteLimit = byteLimit }
  func timed<T>(_ counter: String, _ body: () throws -> T) rethrows -> T {
    let began = PowerLogStore.threadCPUMilliseconds()
    defer { counters[counter, default: 0] += Int((PowerLogStore.threadCPUMilliseconds() - began) * 1_000) }
    return try body()
  }
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
  static let maximumProjections = 64
  static let maximumPlotPoints = 16_384
  static let maximumCachedBytes = 24 * 1024 * 1024
  // Estimates cover native adapter storage; retained core graphs and runtime heaps are excluded.
  static let plotWorkingBytes = 8 * 1024 * 1024
  static let maximumProjectionBytes = maximumCachedBytes - plotWorkingBytes
  private let queue = DispatchQueue(label: "app.powerlog.monitor.read", qos: .userInitiated)
  private let statisticsQueue = DispatchQueue(label: "app.powerlog.monitor.statistics", qos: .utility)
  private let energyQueue = DispatchQueue(label: "app.powerlog.monitor.energy", qos: .utility)
  private let currentEnergyQueue = DispatchQueue(label: "app.powerlog.monitor.energy.current", qos: .userInteractive)
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
    // Holds 25 whole-range slot summaries of 512 slots with their originals, about 37 MB for continuous data.
    plotWorkspace = MonitorWorkspace(byteLimit: budget / 2 + budget * 2)
    statisticsWorkspace = MonitorWorkspace(byteLimit: budget - budget / 2)
    self.projectionPageObserver = projectionPageObserver
    database = Result {
      try memoryPreview
        ? PowerLogStore.memory()
        : PowerLogStore.shared(databaseURL: directory.appendingPathComponent("power-log.sqlite3"))
    }
    for reads in [queue, statisticsQueue, fastQueue, energyQueue, currentEnergyQueue] {
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
    let operation = operation.routed(request)
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
    let executor: DispatchQueue
    switch operation {
    case .plot: executor = queue
    case .stats: executor = statisticsQueue
    case .energyPerDistance: executor = energyQueue
    case .currentEnergyPerDistance: executor = currentEnergyQueue
    default: executor = fastQueue
    }
    executor.async { [self] in
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
            case .energyPerDistance, .currentEnergyPerDistance: return try energyPerDistance(request, source: source)
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
      result["summaryBytes", default: 0] += cache.summaries.values.reduce(0) { $0 + $1.bytes }
      result["summaryEntries", default: 0] += cache.summaries.count
      result["summaryPoints", default: 0] += cache.summaries.values.reduce(0) { $0 + Int($1.core.retainedPoints) }
      result["summaryExhausted", default: 0] += cache.exhausted.count
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
    cache.statisticsPrefixes = cache.statisticsPrefixes.filter { !$0.key.hasPrefix(prefix) }
    cache.projections = cache.projections.filter { !$0.key.hasPrefix(prefix) }
    cache.pyramids = cache.pyramids.filter { !$0.key.hasPrefix(prefix) && !$0.key.hasPrefix("distance|" + prefix) }
    cache.summaries = cache.summaries.filter { !$0.key.hasPrefix(prefix) && !$0.key.hasPrefix("distance|" + prefix) }
    cache.exhausted = cache.exhausted.filter { !$0.key.hasPrefix(prefix) && !$0.key.hasPrefix("distance|" + prefix) }
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
    _ cache: MonitorWorkspace, _ bytes: Int, keeping key: String, evictPyramids: Bool = true, projection: Bool = true
  ) -> Bool {
    if cache.bytes + bytes > cache.byteLimit && (!cache.hydrated.isEmpty || !cache.distanceHydrated.isEmpty) {
      cache.hydrated.removeAll(keepingCapacity: true)
      cache.distanceHydrated.removeAll(keepingCapacity: true)
      cache.hydrationOrder.removeAll(keepingCapacity: true)
      cache.hydrationBytes = 0
      cache.hydrationCursor = 0
    }
    guard bytes <= cache.byteLimit else { return false }
    while cache.bytes + bytes > cache.byteLimit
      || (projection && cache.projections[key] == nil && cache.projections.count >= Self.maximumProjections)
    {
      let raw = cache.projections.values.filter({ $0.key != key }).min(by: { $0.used < $1.used })
      let reduced = evictPyramids ? cache.pyramids.values.filter({ $0.key != key }).min(by: { $0.used < $1.used }) : nil
      let full = cache.bytes + bytes > cache.byteLimit
      let summary = full ? cache.summaries.values.filter({ $0.key != key }).min(by: { $0.used < $1.used }) : nil
      let prefix =
        full ? cache.statisticsPrefixes.filter({ $0.key != key }).min(by: { $0.value.used < $1.value.used }) : nil
      let oldest = [raw?.used, reduced?.used, summary?.used].compactMap({ $0 }).min()
      if let prefix, oldest.map({ prefix.value.used < $0 }) ?? true {
        cache.statisticsPrefixes.removeValue(forKey: prefix.key)
      } else if let summary, raw.map({ summary.used < $0.used }) ?? true,
        reduced.map({ summary.used < $0.used }) ?? true
      {
        cache.summaries.removeValue(forKey: summary.key)
      } else if let raw, reduced == nil || raw.used <= reduced!.used {
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
      (try? InspectionMetrics.shared.plotGapUs(metric: c.metric)).map(String.init) ?? "",
    ].joined(separator: "|")
  }
  private func semanticsChanged(_ c: MonitorChannel, _ source: MonitorDescription, after revision: Int64) throws
    -> Bool
  {
    try store.read { db in
      try db.scalarInt(
        "SELECT 1 FROM collection_change_marks WHERE collection_id=? AND metric IN (?,'') AND kind NOT IN ('append','timing') AND revision>? LIMIT 1",
        [.text(source.id), .text(c.column), .integer(revision)]) != nil
    }
  }
  private func firstAppended(_ c: MonitorChannel, _ source: MonitorDescription, after revision: Int64) throws
    -> Double?
  {
    try store.read { db in
      try db.rows(
        "SELECT min(m.elapsed_seconds) AS time FROM collection_memberships m INDEXED BY membership_snapshot JOIN \(c.table) h ON h.observation_id=m.observation_id WHERE \(baseWhere(c)) AND m.revision>?",
        values(c, source) + [.integer(revision)], limit: 1
      ).first?.double("time")
    }
  }
  private func projectionStillValid(_ projection: MonitorProjection, _ c: MonitorChannel, _ source: MonitorDescription)
    throws -> Bool
  {
    if projection.revision == source.revision { return true }
    guard projection.revision < source.revision, try !semanticsChanged(c, source, after: projection.revision) else {
      return false
    }
    if let firstNew = try firstAppended(c, source, after: projection.revision) {
      let last =
        projection.count > 0
        ? projection[projection.count - 1].timeUs : WorkoutDistancePolicy.timeUs(projection.start)
      // Equal-time append can alter stable-identity ties, so rebuild it as a late insertion.
      guard WorkoutDistancePolicy.timeUs(firstNew) > last else { return false }
      projection.end = min(projection.end, firstNew.nextDown)
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
  private func summaryStillValid(
    _ summary: MonitorSummaryProgress, _ c: MonitorChannel, _ source: MonitorDescription
  ) throws -> Bool {
    guard summary.revision < source.revision else { return true }
    guard try !semanticsChanged(c, source, after: summary.revision) else { return false }
    guard let firstNew = try firstAppended(c, source, after: summary.revision), let last = summary.last else {
      return true
    }
    // A row at the last summarized time may order before it, so only strictly later rows extend the summary.
    return WorkoutDistancePolicy.timeUs(firstNew) > last.timeUs
  }
  private func extendSummary(
    _ cache: MonitorWorkspace, _ summary: MonitorPlotSummary, revision: Int64,
    identity: @escaping (KotlinLong) -> String,
    scan: (MonitorProjectedPoint?, (MonitorProjectedPoint, Int?) throws -> Void) throws -> Void
  ) throws -> Bool {
    let input = MonitorNumericPage(plot: nil, summary: summary.core, identity: identity, cache: cache)
    var appended = 0
    do {
      try cache.timed("summaryExtensionCPUMicros") {
        try scan(summary.last) { point, segment in
          try input.append(point, distanceSegment: segment)
          summary.last = point
          appended += 1
        }
        try input.flush()
      }
    } catch {
      guard (error as NSError).kotlinException != nil else { throw error }
      return false
    }
    summary.revision = revision
    cache.counters["summaryAppendedPoints", default: 0] += appended
    return true
  }
  /// `scan` feeds the originals of the whole range after the given one (all of them for nil) in plot order.
  private func summarize(
    _ cache: MonitorWorkspace, key: String, revision: Int64, slot: Int64, into reducer: InspectionPlotReducer,
    identity: @escaping (KotlinLong) -> String, behind: (MonitorSummaryProgress) -> Bool,
    reusable: (MonitorSummaryProgress) throws -> Bool, merging: (MonitorPlotSummary) -> Void = { _ in },
    make: () throws -> MonitorPlotSummary,
    scan: (MonitorProjectedPoint?, (MonitorProjectedPoint, Int?) throws -> Void) throws -> Void
  ) throws -> MonitorPlotSummary? {
    cache.tick &+= 1
    if let exhausted = cache.exhausted[key] {
      if behind(exhausted) {
        cache.counters["summaryFallbacks", default: 0] += 1
        _ = try reduceOriginals(cache, into: reducer, scan: scan)
        return nil
      }
      if try reusable(exhausted) {
        cache.counters["summaryFallbacks", default: 0] += 1
        exhausted.last = try reduceOriginals(cache, into: reducer, scan: scan)
        exhausted.revision = revision
        exhausted.used = cache.tick
        return nil
      }
      cache.exhausted.removeValue(forKey: key)
    }
    if let existing = cache.summaries[key] {
      guard !behind(existing) else {
        cache.counters["summaryFallbacks", default: 0] += 1
        _ = try reduceOriginals(cache, into: reducer, scan: scan)
        return nil
      }
      cache.summaries.removeValue(forKey: key)
      if slot % existing.core.slotUs == 0, try reusable(existing) {
        let extended = try extendSummary(cache, existing, revision: revision, identity: identity, scan: scan)
        if extended, existing.core.canMergeInto(reducer: reducer) {
          existing.used = cache.tick
          cache.summaries[key] = existing
          merging(existing)
          try cache.timed("summaryMergeCPUMicros") { try existing.core.mergeInto(reducer: reducer) }
          cache.counters["summaryMerges", default: 0] += 1
          return existing
        }
        if extended || existing.core.overflowed {
          cache.counters["summaryInvalidations", default: 0] += 1
          cache.counters["summaryFallbacks", default: 0] += 1
          exhaust(
            cache, key: key, generation: existing.generation, revision: revision,
            last: try reduceOriginals(cache, into: reducer, scan: scan))
          return nil
        }
      }
      cache.counters["summaryInvalidations", default: 0] += 1
    }
    let summary = try make()
    let input = MonitorNumericPage(plot: reducer, summary: summary.core, identity: identity, cache: cache)
    try scan(nil) { point, segment in
      try input.append(point, distanceSegment: segment)
      summary.last = point
    }
    try input.flush()
    cache.counters["summaryRebuilds", default: 0] += 1
    guard input.summary != nil else {
      cache.counters["summaryFallbacks", default: 0] += 1
      exhaust(cache, key: key, generation: summary.generation, revision: revision, last: summary.last)
      return nil
    }
    summary.used = cache.tick
    cache.summaries[key] = summary
    return summary
  }
  private func reduceOriginals(
    _ cache: MonitorWorkspace, into reducer: InspectionPlotReducer,
    scan: (MonitorProjectedPoint?, (MonitorProjectedPoint, Int?) throws -> Void) throws -> Void
  ) throws -> MonitorProjectedPoint? {
    let input = MonitorNumericPage(plot: reducer, cache: cache)
    var last: MonitorProjectedPoint?
    try scan(nil) { point, segment in
      try input.append(point, distanceSegment: segment)
      last = point
    }
    try input.flush()
    return last
  }
  private func exhaust(
    _ cache: MonitorWorkspace, key: String, generation: String, revision: Int64, last: MonitorProjectedPoint?
  ) {
    if cache.exhausted.count >= Self.maximumProjections,
      let oldest = cache.exhausted.min(by: { $0.value.used < $1.value.used })?.key
    {
      cache.exhausted.removeValue(forKey: oldest)
    }
    cache.exhausted[key] = MonitorExhaustedSummary(
      revision: revision, last: last, generation: generation, used: cache.tick)
  }
  private func admitSummary(_ cache: MonitorWorkspace, _ summary: MonitorPlotSummary) {
    summary.measure()
    guard cache.summaries[summary.key] === summary else { return }
    if !makeProjectionRoom(cache, 0, keeping: summary.key, projection: false) {
      cache.summaries.removeValue(forKey: summary.key)
      cache.counters["evictions", default: 0] += 1
    }
    cache.counters["peakNativeAdapterBytes"] = max(cache.counters["peakNativeAdapterBytes", default: 0], cache.bytes)
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
    try fetchOriginals(cache, missing, source: source) { original in
      let id = original.id
      result[id] = original
      guard !cache.projections.isEmpty || !cache.pyramids.isEmpty else { return }
      let bytes = 192 + original.identity.utf8.count + original.timestamp.utf8.count
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
        cache.hydrated[id] = original
        cache.hydrationBytes += bytes
      }
    }
    return result
  }
  private func hydrate(
    _ cache: MonitorWorkspace, _ points: [MonitorProjectedPoint], source: MonitorDescription,
    summary: MonitorPlotSummary
  ) throws -> [Int64: MonitorOriginal] {
    let began = ProcessInfo.processInfo.systemUptime
    defer {
      cache.counters["retainedHydrationMicros", default: 0] += Int(
        (ProcessInfo.processInfo.systemUptime - began) * 1_000_000)
    }
    var result: [Int64: MonitorOriginal] = [:]
    var missing: [MonitorProjectedPoint] = []
    for point in points {
      if let original = summary.originals[point.id] {
        result[point.id] = MonitorOriginal(
          id: point.id, identity: original.identity, time: original.time, timestamp: original.timestamp,
          value: point.value, integer: point.integer)
      } else {
        missing.append(point)
      }
    }
    cache.counters["hydrationHits", default: 0] += points.count - missing.count
    try fetchOriginals(cache, missing, source: source) { result[$0.id] = $0 }
    summary.originals = result
    return result
  }
  private func fetchOriginals(
    _ cache: MonitorWorkspace, _ points: [MonitorProjectedPoint], source: MonitorDescription,
    _ body: (MonitorOriginal) -> Void
  ) throws {
    for offset in stride(from: 0, to: points.count, by: Self.pageSize) {
      let page = Array(points[offset..<min(points.count, offset + Self.pageSize)])
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
          body(
            MonitorOriginal(
              id: id, identity: identity, time: row.double("elapsed_seconds")!, timestamp: timestamp, value: p.value,
              integer: p.integer))
        }
      }
    }
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
      // Reuse only an append-only proof. Late/corrected samples and changed
      // source semantics rebuild the tree; never silently keep stale extrema.
      var valid = try value.revision < source.revision && !semanticsChanged(c, source, after: value.revision)
      if valid, let last = value.lastRead, let firstNew = try firstAppended(c, source, after: value.revision),
        WorkoutDistancePolicy.timeUs(firstNew) <= last.timeUs
      {
        valid = false
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
    end: Double, count: Int, metric: String, wholeRangeSlot: Int64?
  ) throws -> [[String: Any]] {
    guard start <= end else { return [] }
    let key =
      "distance|" + source.sourceID + "|" + metric + "|" + snapshot.generation + "|" + snapshot.selection + "|"
      + String(snapshot.revision)
    let lookup = MonitorIdentityLookup(try store)
    let identity: (KotlinLong) -> String = { "distance:" + snapshot.generation + ":" + String($0.int64Value) }
    func scan(
      _ start: Double, _ end: Double, after: WorkoutDistanceCursor? = nil,
      _ body: (WorkoutDistanceStore.ChartReference) throws -> Void
    ) throws {
      var after = after
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
    let before = try distanceStore.chartNeighbor(snapshot: snapshot, seconds: start, before: true)
    var hierarchy = wholeRangeSlot == nil ? cache.pyramids[key] : nil
    if wholeRangeSlot == nil, hierarchy == nil, (end - start) / Double(count) >= MonitorPyramid.seconds,
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
    var slot = wholeRangeSlot
    if hierarchy != nil {
      var width: Int64 = 8_000_000
      while Double(width) < (end - start) / Double(count) * 1_000_000 { width *= 2 }
      slot = width
    }
    let reducer = try MonitorInspection.plot(
      metric: metric, start: WorkoutDistancePolicy.timeUs(start), end: WorkoutDistancePolicy.timeUs(end),
      buckets: count, slot: slot, identity: identity)
    let input = MonitorNumericPage(plot: reducer, cache: cache)
    if let before {
      try input.append(numeric(before), distanceSegment: before.plotSegment)
    }
    try input.flush()
    var summary: MonitorPlotSummary?
    if let slot = wholeRangeSlot, before == nil, let physical = snapshot.source {
      let generation = snapshot.generation + "|" + physical
      let summaryKey = "distance|" + source.sourceID + "|" + metric + "|" + snapshot.selection
      summary = try summarize(
        cache, key: summaryKey, revision: source.revision, slot: slot, into: reducer, identity: identity,
        behind: {
          $0.generation == generation && (($0.last?.id ?? 0) > snapshot.maximumPointID || ($0.last?.time ?? 0) > end)
        },
        reusable: { summary in
          guard summary.generation == generation else { return false }
          guard let last = summary.last else { return true }
          return try distanceStore.chartAppendsFollow(
            snapshot: snapshot, after: WorkoutDistanceCursor(time: last.time, pointID: last.id))
        },
        make: {
          try MonitorPlotSummary(key: summaryKey, metric: metric, revision: source.revision, generation: generation)
        }
      ) { after, body in
        try scan(start, end, after: after.map { WorkoutDistanceCursor(time: $0.time, pointID: $0.id) }) {
          try body(numeric($0), $0.plotSegment)
        }
      }
    } else if let hierarchy {
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
    let retained = try cache.timed("plotResultCPUMicros") { try reducer.result() }
    let hydrationBegan = ProcessInfo.processInfo.systemUptime
    defer {
      cache.counters["retainedHydrationMicros", default: 0] += Int(
        (ProcessInfo.processInfo.systemUptime - hydrationBegan) * 1_000_000)
    }
    let ids = retained.map { $0.point.reference!.reference }
    var hydrated: [Int64: WorkoutDistancePoint] = [:]
    var missing: [Int64] = []
    if let summary {
      for id in ids {
        if let point = summary.points[id] { hydrated[id] = point } else { missing.append(id) }
      }
      cache.counters["distanceHydrationHits", default: 0] += ids.count - missing.count
    } else {
      let pin = key
      if cache.distanceHydrationPin != pin {
        cache.distanceHydrationPin = pin
        cache.distanceHydrated.removeAll(keepingCapacity: true)
      }
      missing = ids.filter { cache.distanceHydrated[$0] == nil }
      hydrated = cache.distanceHydrated
    }
    for offset in stride(from: 0, to: missing.count, by: 256) {
      let page = try distanceStore.chartPoints(
        snapshot: snapshot, ids: Array(missing[offset..<min(missing.count, offset + 256)]))
      cache.counters["distanceHydratedPoints", default: 0] += page.count
      for point in page { hydrated[point.pointID] = point }
    }
    if let summary {
      summary.points = hydrated
      admitSummary(cache, summary)
    } else {
      cache.distanceHydrated.removeAll(keepingCapacity: true)
      let retainedLimit = min(2048, max(0, (cache.byteLimit - cache.bytes) / 512))
      cache.distanceHydrated = Dictionary(uniqueKeysWithValues: ids.suffix(retainedLimit).map { ($0, hydrated[$0]!) })
    }
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
    let pointLimit = Int(InspectionPlotSlots.shared.pointLimit(metrics: Int32(metrics.count)))
    let distance =
      !WorkoutDistanceStore.derivedMetrics.isDisjoint(with: metrics) ? try distanceSnapshot(source, build: true) : nil
    let count = Int(
      try InspectionPlotSlots.shared.buckets(requested: Int32(request.buckets), metrics: Int32(metrics.count)))
    let wholeRange = start == 0 && end == source.end
    let wholeRangeSlot =
      wholeRange
      ? try InspectionPlotSlots.shared.alignedSlotUs(
        range: AnalysisRange(startUs: 0, endUs: WorkoutDistancePolicy.timeUs(end)), buckets: Int32(count))?.int64Value
      : nil
    let key = [
      source.id, String(source.revision), distance?.generation ?? "", distance?.selection ?? "", String(start),
      String(end), String(count),
      try metrics.map { $0 + "@" + String(try InspectionMetrics.shared.plotGapUs(metric: $0)) }.joined(separator: ","),
    ].joined(separator: "|")
    let persistOverview = source.phase == "completed" && wholeRange
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
          cache, source: source, snapshot: distance, start: start, end: end, count: count, metric: metric,
          wholeRangeSlot: wholeRangeSlot)
        continue
      }
      guard let c = try channel(metric, source: source) else { continue }
      guard start <= end else { continue }
      let lookup = MonitorIdentityLookup(try store)
      let predecessor = try neighbor(c, source, time: start, before: true, strict: true)
      let successor = try neighbor(c, source, time: end, before: false, strict: true)
      let summarized = wholeRangeSlot != nil && predecessor == nil
      let width = (end - start) / Double(count)
      let hierarchy = summarized ? nil : try pyramid(cache, c, source, width: width)
      var slot = wholeRangeSlot
      if !wholeRange, hierarchy != nil {
        var span: Int64 = 8_000_000
        while Double(span) < width * 1_000_000 { span *= 2 }
        slot = span
      }
      let reducer = try MonitorInspection.plot(
        metric: metric, start: WorkoutDistancePolicy.timeUs(start),
        end: WorkoutDistancePolicy.timeUs(end), buckets: count, limit: pointLimit, slot: slot, identity: lookup.identity
      )
      let input = MonitorNumericPage(plot: reducer, cache: cache)
      if let predecessor {
        try plotBoundary(
          cache, c, source, start: predecessor.time,
          end: WorkoutDistancePolicy.seconds(WorkoutDistancePolicy.timeUs(start) - 1)
        ) {
          try input.append($0)
        }
      }
      try input.flush()
      var summary: MonitorPlotSummary?
      if summarized, let slot {
        let summaryKey = projectionKey(c, source)
        summary = try summarize(
          cache, key: summaryKey, revision: source.revision, slot: slot, into: reducer, identity: lookup.identity,
          behind: { $0.revision > source.revision || ($0.last?.time ?? 0) > end },
          reusable: { try summaryStillValid($0, c, source) },
          merging: { lookup.known = $0.originals },
          make: { try MonitorPlotSummary(key: summaryKey, metric: metric, revision: source.revision) }
        ) { after, body in
          try scanProjected(cache, c, source, start: after?.time ?? start, end: end, after: after) { try body($0, nil) }
        }
      } else if let hierarchy {
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
      let retained = try cache.timed("plotResultCPUMicros") { try reducer.result() }
      try lookup.check()
      let points = retained.map { MonitorProjectedPoint.from($0.point) }
      let originals = try cache.timed("plotHydrationCPUMicros") {
        try summary.map { try hydrate(cache, points, source: source, summary: $0) }
          ?? hydrate(cache, points, source: source)
      }
      if let summary { admitSummary(cache, summary) }
      var previousSegment: Int32?
      series[metric] = try cache.timed("plotRowCPUMicros") {
        try retained.map { point in
          guard let original = originals[point.point.reference!.reference] else {
            throw MonitorError.invalid("A chart observation is unavailable.")
          }
          var row = original.dictionary
          row["startsSegment"] = previousSegment == nil || previousSegment != point.segment
          previousSegment = point.segment
          return row
        }
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
      let boundaries = try interruptions(source)
      let reducer: InspectionQuery
      if start == 0, end >= source.end,
        let prefixed = try prefixedStatistics(cache, c, source, end: end, timeline: timeline, boundaries: boundaries)
      {
        reducer = prefixed
      } else {
        reducer = try MonitorInspection.query(
          metric: metric, start: WorkoutDistancePolicy.timeUs(start),
          end: WorkoutDistancePolicy.timeUs(end), timeline: timeline, identity: lookup.identity)
        let input = MonitorNumericPage(query: reducer, cache: cache)
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
      }
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
  static let statisticsPrefixBlockUs: Int64 = 16_000_000
  // Watch heart rate is committed up to 60 s after it was measured, so live appends land after the prefix.
  static let statisticsPrefixLagUs: Int64 = 64_000_000
  static let maximumStatisticsPrefixes = 64
  private func statisticsPrefixValid(
    _ prefix: MonitorStatisticsPrefix, _ c: MonitorChannel, _ source: MonitorDescription, timeline: ActiveTimeline,
    endUs: Int64
  ) throws -> Bool {
    guard prefix.revision <= source.revision, prefix.endUs <= min(endUs, timeline.endUs),
      prefix.timeline == MonitorTimelinePrefix(timeline, before: prefix.endUs)
    else { return false }
    guard prefix.revision < source.revision else { return true }
    guard try !semanticsChanged(c, source, after: prefix.revision) else { return false }
    guard let firstNew = try firstAppended(c, source, after: prefix.revision) else { return true }
    return WorkoutDistancePolicy.timeUs(firstNew) >= prefix.endUs
  }
  private func prefixedStatistics(
    _ cache: MonitorWorkspace, _ c: MonitorChannel, _ source: MonitorDescription, end: Double,
    timeline: ActiveTimeline, boundaries: [WorkoutInterruptionBoundary]
  ) throws -> InspectionQuery? {
    let key = projectionKey(c, source)
    let endUs = WorkoutDistancePolicy.timeUs(end)
    var reused = cache.statisticsPrefixes.removeValue(forKey: key)
    if let prefix = reused, try !statisticsPrefixValid(prefix, c, source, timeline: timeline, endUs: endUs) {
      reused = nil
      cache.counters["statisticsPrefixInvalidations", default: 0] += 1
    }
    let settled =
      (min(WorkoutDistancePolicy.timeUs(source.end), timeline.endUs) - Self.statisticsPrefixLagUs)
      / Self.statisticsPrefixBlockUs
    let foldStart = reused?.endUs ?? 0
    let foldEnd = max(foldStart, max(0, settled) * Self.statisticsPrefixBlockUs)
    guard foldEnd > 0 else { return nil }
    let lookup = try reused?.lookup ?? MonitorIdentityLookup(store)
    let signature = MonitorTimelinePrefix(timeline, before: foldEnd)
    // The lookup holds at most one page of identities, and this read can fill it.
    let entryBytes = MonitorStatisticsPrefix.bytes(signature, identities: Self.pageSize)
    guard makeProjectionRoom(cache, entryBytes, keeping: key, evictPyramids: false, projection: false) else {
      cache.counters["statisticsPrefixFallbacks", default: 0] += 1
      return nil
    }
    do {
      let readLookup = MonitorIdentityLookup(try store)
      let query = try MonitorInspection.query(
        metric: c.metric, start: 0, end: endUs, timeline: timeline, identity: readLookup.identity)
      let input = MonitorNumericPage(query: query, cache: cache)
      for row in try inspectionRows(c, source, time: 0, before: true, strict: true, admitted: false) {
        try input.append(projected(row, boundaries: boundaries))
      }
      var checkpoint = reused?.checkpoint
      var folding: (query: InspectionQuery, input: MonitorNumericPage)?
      if foldEnd > foldStart {
        let prefix = try MonitorInspection.query(
          metric: c.metric, start: 0, end: foldEnd, timeline: timeline, bucket: true, identity: lookup.identity)
        if let checkpoint { try prefix.mergeOwned(checkpoint: checkpoint) }
        folding = (prefix, MonitorNumericPage(query: prefix, cache: cache))
      }
      var merged = false
      var folded = 0
      // Rows before zero are never active, so after the rows before the prefix end a direct reduction holds exactly
      // the checkpoint's state; the query merges it after the predecessors and before the first later row.
      func mergePrefix() throws {
        if let folding {
          try folding.input.flush()
          checkpoint = folding.query.checkpoint()
        }
        try input.flush()
        if let checkpoint { try query.mergeOwned(checkpoint: checkpoint) }
        merged = true
      }
      try scanProjected(cache, c, source, start: WorkoutDistancePolicy.seconds(foldStart), end: end) { point in
        if point.timeUs < foldEnd, let folding {
          try folding.input.append(point)
          folded += 1
          return
        }
        if !merged { try mergePrefix() }
        try input.append(point)
      }
      if !merged { try mergePrefix() }
      cache.counters["statisticsPrefixFoldedPoints", default: 0] += folded
      for row in try inspectionRows(c, source, time: end, before: false, strict: true, admitted: false) {
        try input.append(projected(row, boundaries: boundaries))
      }
      try input.flush()
      try lookup.check()
      try readLookup.check()
      if cache.statisticsPrefixes.count >= Self.maximumStatisticsPrefixes,
        let oldest = cache.statisticsPrefixes.min(by: { $0.value.used < $1.value.used })
      {
        cache.statisticsPrefixes.removeValue(forKey: oldest.key)
      }
      let entry = MonitorStatisticsPrefix(
        lookup: lookup, endUs: foldEnd, revision: source.revision, checkpoint: checkpoint, timeline: signature)
      cache.tick &+= 1
      entry.used = cache.tick
      cache.statisticsPrefixes[key] = entry
      cache.counters[reused == nil ? "statisticsPrefixBuilds" : "statisticsPrefixHits", default: 0] += 1
      return query
    } catch {
      guard (error as NSError).kotlinException != nil else { throw error }
      cache.counters["statisticsPrefixFallbacks", default: 0] += 1
      return nil
    }
  }
  enum EnergyInput: Equatable {
    case distance(startUs: Int64, endUs: Int64, meters: Double, speed0: Double?, speed1: Double?)
    case battery(
      startUs: Int64, endUs: Int64, wattHours: Double, rate0: Double?, rate1: Double?, indivisible: Bool,
      timestamp: String)
    case speed(timeUs: Int64, mps: Double?, accuracy: Double?, activeInterval: KotlinInt?)
    case telemetry(
      timeUs: Int64, watts: Double?, batteryWatts: Double?, speedMps: Double?, activeInterval: KotlinInt?,
      continuity: String, timestamp: String)
  }
  static var energyInputObserverForTesting: ((EnergyInput) -> Void)?
  static var energySeriesPointBudgetForTesting: Int32?
  private struct EnergyRow {
    let channel: Int
    let id: Int64
    let time: Double
    var start = 0.0
    var end = 0.0
    var amount = 0.0
    var rate0: Double?
    var rate1: Double?
    var indivisible = false
    var timestamp = ""
    var producer = ""
    var sequence: Int64 = 0
    var clock: String?
    var connection: String?
    var watts: Double?
    var power: Double?
    var speed: Double?
    var accuracy: Double?
  }
  private struct EnergyBranch {
    let channel: Int
    let sql: String
    let values: [PowerLogSQLValue]
    var rows: [EnergyRow] = []
    var next = 0
    var more = false
  }
  private struct EnergyTotalsKey: Hashable {
    let id: String
    let battery: String
    let distance: String
    let smoothing: Int32
    let includeTotals: Bool
    let controllerSpeed: Bool
  }
  /// The fold of every row at or before `cut`, read at `revision` with the generation's points through `pointID`.
  private struct EnergyTotalsState {
    var checkpoint: EnergyPerDistanceCheckpoint
    var cut: Double
    var revision: Int64
    var generation: Int64
    var pointID: Int64
  }
  private struct EnergySeriesKey: Hashable {
    let id: String
    let distance: String
    let smoothing: Int32
    let controllerSpeed: Bool
  }
  /// A whole-ride series holding every row earlier than `threshold` µs as read at `revision`: one microsecond after
  /// the last row fed before its cut. Without a series it overflowed its summaries, and reads use a fresh aligned
  /// query instead.
  private struct EnergySeriesState {
    let series: EnergyPerDistanceSeries?
    let threshold: Int64
    let revision: Int64
  }
  private final class EnergyStates {
    private let lock = NSLock()
    private var totals: [EnergyTotalsKey: (state: EnergyTotalsState, used: UInt64)] = [:]
    private var series: [EnergySeriesKey: (state: EnergySeriesState, used: UInt64)] = [:]
    private var tick: UInt64 = 0
    private var counters: [String: Int] = [:]
    func state(_ key: EnergyTotalsKey) -> EnergyTotalsState? {
      lock.lock()
      defer { lock.unlock() }
      return totals[key]?.state
    }
    func save(_ state: EnergyTotalsState?, for key: EnergyTotalsKey) {
      lock.lock()
      defer { lock.unlock() }
      guard let state else {
        totals.removeValue(forKey: key)
        return
      }
      keep(state, for: key, in: &totals, capacity: 8)
    }
    func takeSeries(_ key: EnergySeriesKey) -> EnergySeriesState? {
      lock.lock()
      defer { lock.unlock() }
      return series.removeValue(forKey: key)?.state
    }
    func save(_ state: EnergySeriesState, for key: EnergySeriesKey) {
      lock.lock()
      defer { lock.unlock() }
      keep(state, for: key, in: &series, capacity: 2)
    }
    private func keep<Key: Hashable, State>(
      _ state: State, for key: Key, in states: inout [Key: (state: State, used: UInt64)], capacity: Int
    ) {
      tick += 1
      states[key] = (state, tick)
      if states.count > capacity, let oldest = states.min(by: { $0.value.used < $1.value.used })?.key {
        states.removeValue(forKey: oldest)
      }
    }
    func count(_ name: String, _ amount: Int = 1) {
      lock.lock()
      defer { lock.unlock() }
      counters[name, default: 0] += amount
    }
    var diagnostics: [String: Int] {
      lock.lock()
      defer { lock.unlock() }
      return counters
    }
  }
  private let energyStates = EnergyStates()
  private static let energyChunkSeconds = 16.0
  private static let energyPageRows = 512
  private static let energyEdgeSQL =
    "SELECT point_id,elapsed_seconds,start_seconds,end_seconds,increment,start_speed,end_speed,indivisible,timestamp FROM distance_points INDEXED BY distance_point_time WHERE generation_id=? AND source=? AND point_id<=? AND elapsed_seconds>=? AND (elapsed_seconds,point_id)>(?,?) AND elapsed_seconds<=? ORDER BY elapsed_seconds,point_id LIMIT \(energyPageRows)"
  private static let energyLocationSQL =
    "SELECT m.observation_id,m.elapsed_seconds,m.producer,m.sequence,o.clock_epoch,CASE WHEN json_extract(o.extra,'$.distanceBarrier')=1 THEN NULL ELSE t.speedMps END,t.speedAccuracyMps FROM collection_memberships m INDEXED BY membership_stream_time JOIN observations o ON o.id=m.observation_id JOIN locations t ON t.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='location' AND m.source=? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND m.elapsed_seconds>=? AND (m.elapsed_seconds,m.observation_id)>(?,?) AND m.elapsed_seconds<=? ORDER BY m.elapsed_seconds,m.observation_id LIMIT \(energyPageRows)"
  private static let energyTelemetrySQL =
    "SELECT m.observation_id,m.elapsed_seconds,o.original_timestamp,m.producer,m.sequence,o.clock_epoch,json_extract(o.extra,'$.connectionEpoch'),t.humanPowerW,t.motorInputPowerW,t.controllerSpeedMps FROM collection_memberships m INDEXED BY membership_time JOIN observations o ON o.id=m.observation_id JOIN telemetry_frames t ON t.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='telemetry' AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND m.elapsed_seconds>=? AND (m.elapsed_seconds,m.observation_id)>(?,?) AND m.elapsed_seconds<=? ORDER BY m.elapsed_seconds,m.observation_id LIMIT \(energyPageRows)"
  private static let energyMarksSQL =
    "SELECT 1 FROM collection_change_marks WHERE collection_id=? AND metric IN ('','humanPowerW','motorInputPowerW','controllerSpeedMps',?,?) AND kind NOT IN ('append','timing') AND revision>? LIMIT 1"
  private static let energyCorrectionsSQL =
    "SELECT 1 FROM collection_corrections WHERE collection_id=? AND revision>? AND revision<=? LIMIT 1"
  private static let energyAppendedSQL =
    "SELECT min(CASE WHEN kind='telemetry' THEN elapsed_seconds END) AS telemetry,min(CASE WHEN kind='location' AND source=? THEN elapsed_seconds END) AS location,max(kind='lifecycle') AS lifecycle FROM collection_memberships INDEXED BY membership_snapshot WHERE collection_id=? AND revision>? AND revision<=?"
  private static let energyPointsSQL =
    "SELECT min(elapsed_seconds) AS time FROM distance_points WHERE generation_id=? AND +source IN (?,?) AND point_id>? AND point_id<=?"
  func energyDiagnostics() -> [String: Int] { energyStates.diagnostics }
  func readEnergyPerDistance(_ request: MonitorRequest) throws -> [String: Any] {
    if request.source == "live", let preview { return try preview.readEnergyPerDistance(request) }
    let source = try description(request)
    if let retry = mismatch(request, source) { return retry }
    return try energyPerDistance(request, source: source)
  }
  private func energyPerDistance(_ request: MonitorRequest, source: MonitorDescription) throws -> [String: Any] {
    let wholeSeries =
      !request.energyTotals && !request.energyCurrent && request.startSeconds == 0
      && (request.endSeconds ?? source.end) >= source.end
    let end =
      wholeSeries ? source.end : request.endSeconds ?? (source.metadata["elapsedSeconds"] as? Double ?? source.end)
    let start = request.energyCurrent ? end : (request.startSeconds ?? end)
    let timeline = try activeTimeline(source, sourceKind: request.source)
    let startUs = WorkoutDistancePolicy.timeUs(start)
    let endUs = WorkoutDistancePolicy.timeUs(end)
    let timer = timeline.intervals.reduce(Int64(0)) { $0 + max(0, min(endUs, $1.endUs) - max(startUs, $1.startUs)) }
    let controllerSpeed = request.source == "live" || source.distanceSelection == "controller"
    let smoothing = Int32(request.energySmoothingSeconds)
    // Preview has telemetry only. It can graph power / controller speed without accumulating ride totals.
    let held =
      request.source == "workout" && request.energyTotals
      ? try distanceStore.leasedSnapshot(id: source.id, revision: source.revision, selection: source.distanceSelection)
      : nil
    defer { held?.lease.release() }
    let distance = held?.snapshot.source ?? (request.source == "live" ? "controller" : source.distanceSelection)
    let battery = (source.metadata["batteryConsumptionSource"] as? String ?? "controller") + "EnergyWh"
    var branches: [EnergyBranch] = []
    if let snapshot = held?.snapshot {
      for (channel, name) in [(0, distance), (1, battery)] {
        branches.append(
          EnergyBranch(
            channel: channel, sql: Self.energyEdgeSQL,
            values: [.integer(snapshot.storageID), .text(name), .integer(snapshot.maximumPointID)]))
      }
    }
    if distance.hasPrefix("gps:") {
      branches.append(
        EnergyBranch(
          channel: 2, sql: Self.energyLocationSQL,
          values: [
            .text(source.id), .text(String(distance.dropFirst(4))), .integer(source.revision),
            .integer(source.revision), .integer(source.revision),
          ]))
    }
    branches.append(
      EnergyBranch(
        channel: 3, sql: Self.energyTelemetrySQL,
        values: [.text(source.id), .integer(source.revision), .integer(source.revision), .integer(source.revision)]))
    func result(_ json: String) throws -> [String: Any] {
      let payload = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
      return try published(
        envelope(request, source).merging(payload) { _, new in new }, request: request, source: source)
    }
    let from = max(0, start - Double(EnergyPerDistancePolicy.shared.contextSeconds(seconds: smoothing)))
    let until = end + 10
    guard request.energyTotals else {
      guard wholeSeries else {
        let query = try EnergyPerDistanceQuery(
          startUs: startUs, endUs: endUs, buckets: Int32(request.buckets), timerUs: timer,
          controllerSpeed: controllerSpeed, smoothingSeconds: smoothing, includeTotals: false)
        try feedEnergy(query, &branches, timeline: timeline, from: from, until: until)
        return try result(query.resultJson())
      }
      let buckets = Int32(request.buckets)
      let slot = try InspectionPlotSlots.shared.alignedSlotUs(
        range: AnalysisRange(startUs: 0, endUs: endUs), buckets: buckets)
      func aligned() throws -> [String: Any] {
        let query = try EnergyPerDistanceQuery(
          startUs: 0, endUs: endUs, buckets: buckets, alignedSlotUs: slot, timerUs: timer,
          controllerSpeed: controllerSpeed, smoothingSeconds: smoothing, includeTotals: false)
        try feedEnergy(query, &branches, timeline: timeline, from: from, until: until)
        return try result(query.resultJson())
      }
      guard request.source == "workout" else { return try aligned() }
      let key = EnergySeriesKey(
        id: source.id, distance: distance, smoothing: smoothing, controllerSpeed: controllerSpeed)
      let margin = EnergyPerDistancePolicy.shared.checkpointMarginUs
      let cutUs = min(endUs - margin, timeline.endUs)
      func read(_ series: EnergyPerDistanceSeries, after threshold: Int64) throws -> String {
        let session = try series.begin(endUs: endUs, timerUs: timer, buckets: buckets, alignedSlotUs: slot)
        var last: Int64?
        var committed: Int64?
        func cut() throws {
          guard committed == nil, cutUs > 0 else { return }
          try session.cut()
          committed = last.map { $0 + 1 } ?? threshold
        }
        try feedEnergy(
          session, &branches, timeline: timeline,
          from: threshold == .min ? from : WorkoutDistancePolicy.seconds(threshold - 1), until: until,
          admit: {
            guard $0 >= threshold else { return false }
            if $0 >= cutUs { try cut() }
            if committed == nil { last = $0 }
            return true
          })
        try cut()
        let json = try session.resultJson()
        energyStates.save(
          EnergySeriesState(
            series: series.valid ? series : nil, threshold: committed ?? threshold, revision: source.revision),
          for: key)
        return json
      }
      if let saved = energyStates.takeSeries(key) {
        // An overflowed series, or one that cannot begin a read this close to its cut, is kept and not read.
        guard let series = saved.series, !series.valid || endUs - series.throughUs > margin else {
          energyStates.save(saved, for: key)
          energyStates.count("energySeriesFallbacks")
          return try aligned()
        }
        if series.valid,
          (try? energySeriesResumable(saved, source: source, distance: distance, timelineEndUs: timeline.endUs)) == true
        {
          do {
            let json = try read(series, after: saved.threshold)
            energyStates.count("energySeriesResumedReads")
            return try result(json)
          } catch let failure where (failure as NSError).kotlinException is InspectionCapacityException && series.valid
          {
            energyStates.save(saved, for: key)
            throw failure
          } catch {}
        }
        energyStates.count("energySeriesInvalidations")
      }
      let json = try read(
        EnergyPerDistanceSeries(
          controllerSpeed: controllerSpeed, smoothingSeconds: smoothing, includeTotals: false,
          pointBudget: Self.energySeriesPointBudgetForTesting ?? InspectionPlotSlots.shared.maximumPoints),
        after: .min)
      energyStates.count("energySeriesBuilds")
      return try result(json)
    }
    guard let snapshot = held?.snapshot, start == 0 else {
      let query = try EnergyPerDistanceQuery(
        startUs: startUs, endUs: endUs, timerUs: timer, controllerSpeed: controllerSpeed, smoothingSeconds: smoothing,
        includeTotals: true)
      try feedEnergy(query, &branches, timeline: timeline, from: from, until: until)
      return try result(query.resultJson())
    }
    let key = EnergyTotalsKey(
      id: source.id, battery: battery, distance: distance, smoothing: smoothing, includeTotals: true,
      controllerSpeed: controllerSpeed)
    let cut = Self.energyCut(endUs: endUs, timelineEndUs: timeline.endUs)
    func resume(_ saved: EnergyTotalsState) throws -> EnergyPerDistanceQuery? {
      guard
        try energyResumable(
          saved, source: source, snapshot: snapshot, distance: distance, battery: battery, endUs: endUs,
          timelineEndUs: timeline.endUs)
      else { return nil }
      let query = try EnergyPerDistanceQuery(
        checkpoint: saved.checkpoint, startUs: startUs, endUs: endUs, timerUs: timer, controllerSpeed: controllerSpeed,
        smoothingSeconds: smoothing, includeTotals: true)
      var next = saved
      next.revision = source.revision
      next.pointID = snapshot.maximumPointID
      try feedEnergy(query, &branches, timeline: timeline, from: saved.cut, resuming: true, until: until) {
        guard $0 == cut else { return }
        next.checkpoint = try query.checkpoint()
        next.cut = $0
        energyStates.count("energyCheckpoints")
      }
      energyStates.save(next, for: key)
      return query
    }
    if let saved = energyStates.state(key) {
      if let query = try? resume(saved) {
        energyStates.count("energyResumedReads")
        return try result(query.resultJson())
      }
      energyStates.count("energyInvalidations")
    }
    let query = try EnergyPerDistanceQuery(
      startUs: startUs, endUs: endUs, timerUs: timer, controllerSpeed: controllerSpeed, smoothingSeconds: smoothing,
      includeTotals: true)
    var next: EnergyTotalsState?
    try feedEnergy(query, &branches, timeline: timeline, from: from, until: until) {
      guard $0 == cut else { return }
      next = EnergyTotalsState(
        checkpoint: try query.checkpoint(), cut: $0, revision: source.revision, generation: snapshot.storageID,
        pointID: snapshot.maximumPointID)
      energyStates.count("energyCheckpoints")
    }
    energyStates.save(next, for: key)
    energyStates.count("energyFullReads")
    return try result(query.resultJson())
  }
  /// The newest chunk boundary a checkpoint can follow. Every row at or before it is more than the kernel's margin
  /// before the end and keeps its active interval while the timeline end stays after it.
  private static func energyCut(endUs: Int64, timelineEndUs: Int64) -> Double? {
    let chunkUs = Int64(energyChunkSeconds * 1_000_000)
    let chunks = (min(endUs - EnergyPerDistancePolicy.shared.checkpointMarginUs, timelineEndUs) - 1) / chunkUs
    return chunks > 0 ? Double(chunks) * energyChunkSeconds : nil
  }
  private func energyResumable(
    _ saved: EnergyTotalsState, source: MonitorDescription, snapshot: WorkoutDistanceSnapshot, distance: String,
    battery: String, endUs: Int64, timelineEndUs: Int64
  ) throws -> Bool {
    guard saved.generation == snapshot.storageID, saved.revision <= source.revision,
      saved.pointID <= snapshot.maximumPointID,
      endUs - saved.checkpoint.throughUs > EnergyPerDistancePolicy.shared.checkpointMarginUs,
      WorkoutDistancePolicy.timeUs(saved.cut) < timelineEndUs
    else { return false }
    return try store.read { db in
      if saved.revision < source.revision,
        try !energyRowsUnchanged(
          db, source: source, distance: distance, since: saved.revision, committed: { $0 <= saved.cut })
      {
        return false
      }
      guard saved.pointID < snapshot.maximumPointID else { return true }
      let first = try db.rows(
        Self.energyPointsSQL,
        [
          .integer(snapshot.storageID), .text(distance), .text(battery), .integer(saved.pointID),
          .integer(snapshot.maximumPointID),
        ], limit: 1
      ).first?.double("time")
      return first.map { $0 > saved.cut } != false
    }
  }
  private func energySeriesResumable(
    _ saved: EnergySeriesState, source: MonitorDescription, distance: String, timelineEndUs: Int64
  ) throws -> Bool {
    guard saved.revision <= source.revision, saved.threshold <= timelineEndUs else { return false }
    guard saved.revision < source.revision else { return true }
    return try store.read { db in
      try energyRowsUnchanged(db, source: source, distance: distance, since: saved.revision) {
        WorkoutDistancePolicy.timeUs($0) < saved.threshold
      }
    }
  }
  /// Whether everything stored after `revision` leaves the active timeline and every row that `committed` admits as
  /// they were.
  private func energyRowsUnchanged(
    _ db: PowerLogDatabase, source: MonitorDescription, distance: String, since revision: Int64,
    committed: (Double) -> Bool
  ) throws -> Bool {
    let gps = distance.hasPrefix("gps:") ? String(distance.dropFirst(4)) : nil
    let window: [PowerLogSQLValue] = [.text(source.id), .integer(revision), .integer(source.revision)]
    let speed: [PowerLogSQLValue] =
      gps == nil ? [.text(""), .text("")] : [.text("speedMps"), .text("speedAccuracyMps")]
    if try db.scalarInt(Self.energyMarksSQL, [.text(source.id)] + speed + [.integer(revision)]) != nil { return false }
    if try db.scalarInt(Self.energyCorrectionsSQL, window) != nil { return false }
    guard let appended = try db.rows(Self.energyAppendedSQL, [.text(gps ?? "")] + window, limit: 1).first,
      appended.int("lifecycle") != 1
    else { return false }
    return appended.double("telemetry").map(committed) != true && appended.double("location").map(committed) != true
  }
  private func feedEnergy(
    _ sink: EnergyPerDistanceInputs, _ branches: inout [EnergyBranch], timeline: ActiveTimeline, from: Double,
    resuming: Bool = false, until: Double, boundary: (Double) throws -> Void = { _ in },
    admit: ((Int64) throws -> Bool)? = nil
  ) throws {
    var chunkStart = from
    var after = resuming ? from : from.nextDown
    var fed = 0
    defer { energyStates.count("energyRows", fed) }
    while true {
      let chunkEnd = min(until, chunkStart + Self.energyChunkSeconds)
      try store.read { db in
        for index in branches.indices {
          try Self.readEnergyPage(db, &branches[index], after: after, id: .max, through: chunkEnd)
        }
      }
      while let row = try nextEnergyRow(&branches, through: chunkEnd) {
        if let admit, try !admit(WorkoutDistancePolicy.timeUs(row.time)) { continue }
        fed += 1
        if let input = Self.energyInput(row, timeline: timeline) { try Self.append(input, to: sink) }
      }
      try boundary(chunkEnd)
      if chunkEnd == until { return }
      chunkStart = chunkEnd
      after = chunkEnd
    }
  }
  private func nextEnergyRow(_ branches: inout [EnergyBranch], through upper: Double) throws -> EnergyRow? {
    var best: Int?
    for index in branches.indices {
      if branches[index].next == branches[index].rows.count {
        guard branches[index].more, let last = branches[index].rows.last else { continue }
        try store.read { db in
          try Self.readEnergyPage(db, &branches[index], after: last.time, id: last.id, through: upper)
        }
        if branches[index].rows.isEmpty { continue }
      }
      // Branches are in channel order, so an equal time keeps the lower channel.
      if let current = best,
        branches[index].rows[branches[index].next].time >= branches[current].rows[branches[current].next].time
      {
        continue
      }
      best = index
    }
    guard let best else { return nil }
    branches[best].next += 1
    return branches[best].rows[branches[best].next - 1]
  }
  private static func readEnergyPage(
    _ db: PowerLogDatabase, _ branch: inout EnergyBranch, after time: Double, id: Int64, through upper: Double
  ) throws {
    var rows: [EnergyRow] = []
    swap(&rows, &branch.rows)
    rows.removeAll(keepingCapacity: true)
    branch.next = 0
    let channel = branch.channel
    let count = try db.scan(
      branch.sql, branch.values + [.real(time), .real(time), .integer(id), .real(upper)], limit: energyPageRows
    ) { row in
      rows.append(energyRow(row, channel: channel))
      return true
    }
    branch.rows = rows
    branch.more = count == energyPageRows
  }
  private static func energyRow(_ row: PowerLogStatementRow, channel: Int) -> EnergyRow {
    switch channel {
    case 0, 1:
      return EnergyRow(
        channel: channel, id: row.integer(0)!, time: row.double(1)!, start: row.double(2)!, end: row.double(3)!,
        amount: row.double(4)!, rate0: row.double(5), rate1: row.double(6), indivisible: row.integer(7) == 1,
        timestamp: channel == 1 ? row.text(8)! : "")
    case 2:
      return EnergyRow(
        channel: channel, id: row.integer(0)!, time: row.double(1)!, producer: row.text(2)!,
        sequence: row.integer(3)!, clock: row.text(4), speed: row.double(5), accuracy: row.double(6))
    default:
      return EnergyRow(
        channel: channel, id: row.integer(0)!, time: row.double(1)!, timestamp: row.text(2)!, producer: row.text(3)!,
        sequence: row.integer(4)!, clock: row.text(5), connection: row.text(6), watts: row.double(7),
        power: row.double(8), speed: row.double(9))
    }
  }
  private static func energyInput(_ row: EnergyRow, timeline: ActiveTimeline) -> EnergyInput? {
    switch row.channel {
    case 0:
      let a = WorkoutDistancePolicy.timeUs(row.start)
      let b = WorkoutDistancePolicy.timeUs(row.end)
      return b > a ? .distance(startUs: a, endUs: b, meters: row.amount, speed0: row.rate0, speed1: row.rate1) : nil
    case 1:
      return .battery(
        startUs: WorkoutDistancePolicy.timeUs(row.start), endUs: WorkoutDistancePolicy.timeUs(row.end),
        wattHours: row.amount, rate0: row.rate0, rate1: row.rate1, indivisible: row.indivisible,
        timestamp: row.timestamp)
    default:
      let time = WorkoutDistancePolicy.timeUs(row.time)
      let active = timeline.membership(t: time, producer: row.producer, sequence: row.sequence, clockEpoch: row.clock)
      guard row.channel == 3 else {
        return .speed(timeUs: time, mps: row.speed, accuracy: row.accuracy, activeInterval: active)
      }
      return .telemetry(
        timeUs: time, watts: row.watts, batteryWatts: row.power, speedMps: row.speed, activeInterval: active,
        continuity: "\(row.clock ?? "")|\(row.connection ?? "")", timestamp: row.timestamp)
    }
  }
  private static func append(_ input: EnergyInput, to query: EnergyPerDistanceInputs) throws {
    energyInputObserverForTesting?(input)
    switch input {
    case .distance(let startUs, let endUs, let meters, let speed0, let speed1):
      try query.appendDistance(
        startUs: startUs, endUs: endUs, meters: meters, speed0: speed0.map(KotlinDouble.init(value:)),
        speed1: speed1.map(KotlinDouble.init(value:)))
    case .battery(let startUs, let endUs, let wattHours, let rate0, let rate1, let indivisible, let timestamp):
      try query.appendBattery(
        startUs: startUs, endUs: endUs, wattHours: wattHours, rate0: rate0.map(KotlinDouble.init(value:)),
        rate1: rate1.map(KotlinDouble.init(value:)), indivisible: indivisible, timestamp: timestamp)
    case .speed(let timeUs, let mps, let accuracy, let activeInterval):
      try query.appendSpeed(
        timeUs: timeUs, mps: mps.map(KotlinDouble.init(value:)), accuracy: accuracy.map(KotlinDouble.init(value:)),
        activeInterval: activeInterval)
    case .telemetry(
      let timeUs, let watts, let batteryWatts, let speedMps, let activeInterval, let continuity, let timestamp):
      try query.appendTelemetry(
        timeUs: timeUs, watts: watts.map(KotlinDouble.init(value:)),
        batteryWatts: batteryWatts.map(KotlinDouble.init(value:)), speedMps: speedMps.map(KotlinDouble.init(value:)),
        activeInterval: activeInterval, continuity: continuity, timestamp: timestamp)
    }
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
    result["changes"] = rows.filter { $0.string("kind") != "timing" }.map { row -> [String: Any] in
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

private protocol EnergyPerDistanceInputs {
  func appendDistance(startUs: Int64, endUs: Int64, meters: Double, speed0: KotlinDouble?, speed1: KotlinDouble?) throws
  func appendBattery(
    startUs: Int64, endUs: Int64, wattHours: Double, rate0: KotlinDouble?, rate1: KotlinDouble?, indivisible: Bool,
    timestamp: String) throws
  func appendSpeed(timeUs: Int64, mps: KotlinDouble?, accuracy: KotlinDouble?, activeInterval: KotlinInt?) throws
  func appendTelemetry(
    timeUs: Int64, watts: KotlinDouble?, batteryWatts: KotlinDouble?, speedMps: KotlinDouble?,
    activeInterval: KotlinInt?, continuity: String?, timestamp: String) throws
}
extension EnergyPerDistanceQuery: EnergyPerDistanceInputs {}
extension EnergyPerDistanceSeriesRead: EnergyPerDistanceInputs {}
