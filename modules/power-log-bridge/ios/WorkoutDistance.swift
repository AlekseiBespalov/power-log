import Foundation
import PowerLogCore

enum WorkoutDistancePolicy {
  static let maximumSeconds = 2_678_400.0
  static let sources = ["gps:watch", "gps:phone", "controller"]
  static func validSpeed(_ speed: Double?, accuracy: Double? = nil) -> Double? {
    Metrics.shared.validSpeed(
      speed: speed.map(KotlinDouble.init(value:)), accuracy: accuracy.map(KotlinDouble.init(value:))) ? speed : nil
  }
  static func method(_ source: String) -> String {
    source.hasPrefix("gps:") ? "gpsGeometry" : "controllerEstimate"
  }
  static func timeUs(_ seconds: Double) -> Int64 { FixedPoint.shared.timeUs(seconds: seconds) }
  static func storedUs(_ seconds: Double) -> Int64 {
    FixedPoint.shared.quantize(value: Scalar.Real64(value: seconds), scale: 1_000_000)
  }
  static func metersQ(_ meters: Double) -> Int64 {
    FixedPoint.shared.quantize(value: Scalar.Real64(value: meters), scale: 1_000_000)
  }
  static func seconds(_ us: Int64) -> Double { FixedPoint.shared.divide(numerator: us, denominator: 1_000_000) }
  static func original(_ identity: String, _ timestamp: String, _ time: Double, _ value: Double) -> Original {
    Original(
      observationId: identity, eventId: identity, timestamp: timestamp, elapsedSeconds: time,
      value: Scalar.Real64(value: value), originalElapsedSeconds: nil)
  }
}

struct WorkoutDistanceInterval: Codable, Equatable {
  var startSeconds: Double
  var endSeconds: Double
  var meters: Double
  var segment: Int
  var startAnchor: String
  var endAnchor: String
  var startTimestamp: String
  var endTimestamp: String
  var startSpeed: Double? = nil
  var endSpeed: Double? = nil
  var indivisible = false
  var activeInterval = 0
  var coveredSeconds: Double { WorkoutDistancePolicy.seconds(core.endUs - core.startUs) }
  var core: DistanceInterval {
    return DistanceInterval(
      startUs: WorkoutDistancePolicy.storedUs(startSeconds), endUs: WorkoutDistancePolicy.storedUs(endSeconds),
      distanceQ: WorkoutDistancePolicy.metersQ(meters), segment: Int32(segment), activeInterval: Int32(activeInterval),
      start: WorkoutDistancePolicy.original(startAnchor, startTimestamp, startSeconds, startSpeed ?? meters),
      end: WorkoutDistancePolicy.original(endAnchor, endTimestamp, endSeconds, endSpeed ?? meters),
      speed0Q: startSpeed.map { KotlinLong(value: WorkoutDistancePolicy.metersQ($0)) },
      speed1Q: endSpeed.map { KotlinLong(value: WorkoutDistancePolicy.metersQ($0)) }, indivisible: indivisible,
      originalMeters: nil)
  }
  static func from(_ interval: DistanceInterval) -> Self {
    Self(
      startSeconds: WorkoutDistancePolicy.seconds(interval.startUs),
      endSeconds: WorkoutDistancePolicy.seconds(interval.endUs),
      meters: WorkoutDistancePolicy.seconds(interval.distanceQ), segment: Int(interval.segment),
      startAnchor: interval.start.eventId, endAnchor: interval.end.eventId,
      startTimestamp: interval.start.timestamp, endTimestamp: interval.end.timestamp,
      startSpeed: interval.speed0Q.map { _ in interval.start.value.real() },
      endSpeed: interval.speed1Q.map { _ in interval.end.value.real() },
      indivisible: interval.indivisible, activeInterval: Int(interval.activeInterval))
  }
  func clipped(start: Double, end: Double) -> WorkoutDistanceRange {
    let reducer = DistanceReducer(
      range: AnalysisRange(startUs: WorkoutDistancePolicy.timeUs(start), endUs: WorkoutDistancePolicy.timeUs(end)))
    reducer.append(interval: core)
    return WorkoutDistanceRange.from(reducer.result(timerUs: 0))
  }
}

struct WorkoutGPSFix: Codable, Equatable {
  var time: Double
  var latitude: Double
  var longitude: Double
  var horizontalAccuracy: Double
  var speed: Double? = nil
  var speedAccuracy: Double? = nil
  var epoch: String? = nil
  var activeInterval: Int? = 0
  var identity: String = ""
  var timestamp: String = ""
  var barrier = false
  var altitude: Double? = nil
  var verticalAccuracy: Double? = nil
  var observationID: String = ""
  var producer: String = "phone"
  var sequence: Int64 = 1
  var originalElapsedSeconds: Double? = nil
  var collectionUs: Int64? = nil
  var core: GPSFix {
    var values: [String: Scalar] = [
      "latitude": Scalar.Real64(value: latitude), "longitude": Scalar.Real64(value: longitude),
      "horizontalAccuracyM": Scalar.Real64(value: horizontalAccuracy),
    ]
    for (key, value) in [
      ("speedMps", speed), ("speedAccuracyMps", speedAccuracy), ("altitudeMeters", altitude),
      ("verticalAccuracyM", verticalAccuracy),
    ] {
      if let value { values[key] = Scalar.Real64(value: value) }
    }
    let item = AnalysisItem(
      collectionUs: collectionUs ?? WorkoutDistancePolicy.timeUs(time), collectionElapsedSeconds: time,
      eventId: identity, observationId: observationID.isEmpty ? identity : observationID, producer: producer,
      sequence: sequence,
      kind: "location", source: producer, timestamp: timestamp, values: values,
      originalElapsedSeconds: originalElapsedSeconds.map(KotlinDouble.init(value:)), representation: nil,
      continuity: Continuity(clockEpoch: epoch, connectionEpoch: nil, interruptionIndex: 0), action: nil,
      distanceBarrier: barrier, interrupted: false, controllerSequence: nil, hasElapsedMapping: true, origin: nil,
      declaredMetrics: nil)
    return GPSFix(item: item, activeInterval: activeInterval.map { KotlinInt(value: Int32($0)) })
  }
  static func from(_ fix: GPSFix) -> Self {
    let item = fix.item
    func number(_ key: String) -> Double? { item.number(metric: key)?.doubleValue }
    return Self(
      time: item.collectionElapsedSeconds, latitude: number("latitude")!, longitude: number("longitude")!,
      horizontalAccuracy: number("horizontalAccuracyM")!, speed: number("speedMps"),
      speedAccuracy: number("speedAccuracyMps"),
      epoch: item.continuity.clockEpoch, activeInterval: fix.activeInterval.map { Int($0.int32Value) },
      identity: item.eventId,
      timestamp: item.timestamp, barrier: item.distanceBarrier, altitude: number("altitudeMeters"),
      verticalAccuracy: number("verticalAccuracyM"),
      observationID: item.observationId, producer: item.producer, sequence: item.sequence,
      originalElapsedSeconds: item.originalElapsedSeconds?.doubleValue, collectionUs: item.collectionUs)
  }
  var valid: Bool { core.valid() }
}

struct WorkoutDistanceSum: Codable {
  var positive: Int64
  var negative: Int64
  var positiveOverflow: Bool
  var negativeOverflow: Bool
  init(_ sum: SignedSum) {
    positive = sum.positive
    negative = sum.negative
    positiveOverflow = sum.positiveOverflow
    negativeOverflow = sum.negativeOverflow
  }
  var core: SignedSum {
    SignedSum(
      positive: positive, negative: negative, positiveOverflow: positiveOverflow, negativeOverflow: negativeOverflow)
  }
}

struct WorkoutGPSDistanceAccumulator: Codable {
  private var kernel = GPSKernel(fullContinuity: false)
  var previous: WorkoutGPSFix? { kernel.distanceCheckpoint().previous.map(WorkoutGPSFix.from) }
  var ascentMeters: Double? {
    kernel.ascentQ().map { FixedPoint.shared.divide(numerator: $0.int64Value, denominator: 1000) }
  }
  var descentMeters: Double? {
    kernel.descentQ().map { FixedPoint.shared.divide(numerator: $0.int64Value, denominator: 1000) }
  }
  mutating func reset() { kernel.reset() }
  mutating func append(_ fix: WorkoutGPSFix) -> WorkoutDistanceInterval? {
    kernel.append(fix: fix.core).interval.map(WorkoutDistanceInterval.from)
  }
  mutating func appendPage(_ fixes: [WorkoutGPSFix], totalQ: Int64, coverageUs: Int64) throws
    -> WorkoutDistancePageProjection
  {
    let numbers = NSMutableData(length: fixes.count * 9 * MemoryLayout<Double>.stride)!
    let keys = NSMutableData(length: fixes.count * 5 * MemoryLayout<Int64>.stride)!
    let values = numbers.mutableBytes.assumingMemoryBound(to: Double.self)
    let integers = keys.mutableBytes.assumingMemoryBound(to: Int64.self)
    var text: [String] = []
    text.reserveCapacity(fixes.count * 5)
    for (index, fix) in fixes.enumerated() {
      let n = index * 9
      let k = index * 5
      values[n] = fix.time
      values[n + 1] = fix.latitude
      values[n + 2] = fix.longitude
      values[n + 3] = fix.horizontalAccuracy
      values[n + 4] = fix.speed ?? .nan
      values[n + 5] = fix.speedAccuracy ?? .nan
      values[n + 6] = fix.altitude ?? .nan
      values[n + 7] = fix.verticalAccuracy ?? .nan
      values[n + 8] = fix.originalElapsedSeconds ?? .nan
      integers[k] = fix.collectionUs ?? WorkoutDistancePolicy.timeUs(fix.time)
      integers[k + 1] = Int64(fix.activeInterval ?? -1)
      integers[k + 2] = fix.sequence
      integers[k + 3] = fix.barrier ? 1 : 0
      integers[k + 4] = fix.epoch == nil ? 0 : 1
      text.append(fix.identity)
      text.append(fix.timestamp)
      text.append(fix.observationID.isEmpty ? fix.identity : fix.observationID)
      text.append(fix.producer)
      text.append(fix.epoch ?? "")
    }
    return try WorkoutDistancePageProjection(
      AppleDistancePages.shared.gps(
        kernel: kernel, numbers: numbers, keys: keys, text: text, totalQ: totalQ, coveredUs: coverageUs))
  }
  static func meters(_ a: WorkoutGPSFix, _ b: WorkoutGPSFix) -> Double {
    PureMath.shared.haversine(lat0: a.latitude, lon0: a.longitude, lat1: b.latitude, lon1: b.longitude)
  }
  init() {}
  private struct Checkpoint: Codable {
    var previous: WorkoutGPSFix?
    var geometryPrevious: WorkoutGPSFix?
    var segment: Int
    var altitudeAnchorMeters: Double?
    var altitudeAnchorQ: Int64?
    var ascent: WorkoutDistanceSum
    var descent: WorkoutDistanceSum
    var distance: WorkoutDistanceSum
    var covered: WorkoutDistanceSum
    var altitudeSegments: Int64
    var acceptedIntervals: Int64
    var rejected: Int64
  }
  init(from decoder: Decoder) throws {
    let value = try Checkpoint(from: decoder)
    kernel.restoreDistanceCheckpoint(
      checkpoint: GPSDistanceCheckpoint(
        previous: value.previous?.core, geometryPrevious: value.geometryPrevious?.core,
        segment: Int32(value.segment), altitudeAnchorMeters: value.altitudeAnchorMeters.map(KotlinDouble.init(value:)),
        altitudeAnchorQ: value.altitudeAnchorQ.map(KotlinLong.init(value:)), ascent: value.ascent.core,
        descent: value.descent.core,
        distance: value.distance.core, covered: value.covered.core, altitudeSegments: value.altitudeSegments,
        acceptedIntervals: value.acceptedIntervals, rejected: value.rejected))
  }
  func encode(to encoder: Encoder) throws {
    let value = kernel.distanceCheckpoint()
    try Checkpoint(
      previous: value.previous.map(WorkoutGPSFix.from),
      geometryPrevious: value.geometryPrevious.map(WorkoutGPSFix.from),
      segment: Int(value.segment), altitudeAnchorMeters: value.altitudeAnchorMeters?.doubleValue,
      altitudeAnchorQ: value.altitudeAnchorQ?.int64Value,
      ascent: WorkoutDistanceSum(value.ascent), descent: WorkoutDistanceSum(value.descent),
      distance: WorkoutDistanceSum(value.distance),
      covered: WorkoutDistanceSum(value.covered), altitudeSegments: value.altitudeSegments,
      acceptedIntervals: value.acceptedIntervals, rejected: value.rejected
    ).encode(to: encoder)
  }
}

struct WorkoutControllerDistanceSample: Codable {
  var time: Double
  var speed: Double?
  var model: String?
  var controllerProtocol: String?
  var identity: String?
  var continuity: String?
  var activeInterval: Int?
  var anchor: String
  var timestamp: String
  var monotonic: Double? = nil
  var counter: Double? = nil
  var barrier = false
  var counterExact: Int64? = nil
  var collectionUs: Int64? = nil
  var core: ControllerDistanceEvidence {
    ControllerDistanceEvidence(
      timeUs: collectionUs ?? WorkoutDistancePolicy.timeUs(time), originalTime: time,
      speedMps: speed.map(KotlinDouble.init(value:)), model: model, protocol: controllerProtocol,
      deviceIdentity: identity, continuityIdentity: continuity,
      activeInterval: activeInterval.map { KotlinInt(value: Int32($0)) },
      monotonicSeconds: monotonic.map(KotlinDouble.init(value:)),
      counter: counterExact.map { Scalar.Integer64(value: $0) } ?? counter.map { Scalar.Real64(value: $0) },
      barrier: barrier, anchor: WorkoutDistancePolicy.original(anchor, timestamp, time, speed ?? 0))
  }
}
struct WorkoutControllerDistanceAccumulator: Codable {
  private var kernel = ControllerDistanceKernel()
  init() {}
  private struct Checkpoint: Codable {
    var previous: WorkoutControllerDistanceSample?
    var segment: Int
  }
  init(from decoder: Decoder) throws {
    let value = try Checkpoint(from: decoder)
    kernel = ControllerDistanceKernel(
      checkpoint: ControllerDistanceCheckpoint(previous: value.previous?.core, segment: Int32(value.segment)))
  }
  func encode(to encoder: Encoder) throws {
    let value = kernel.checkpoint()
    let previous = value.previous.map { old in
      WorkoutControllerDistanceSample(
        time: old.originalTime, speed: old.speedMps?.doubleValue, model: old.model,
        controllerProtocol: old.protocol, identity: old.deviceIdentity, continuity: old.continuityIdentity,
        activeInterval: old.activeInterval.map { Int($0.int32Value) }, anchor: old.anchor.eventId,
        timestamp: old.anchor.timestamp,
        monotonic: old.monotonicSeconds?.doubleValue, counter: (old.counter as? Scalar.Real64)?.value,
        barrier: old.barrier,
        counterExact: (old.counter as? Scalar.Integer64)?.value, collectionUs: old.timeUs)
    }
    try Checkpoint(previous: previous, segment: Int(value.segment)).encode(to: encoder)
  }
  mutating func append(_ sample: WorkoutControllerDistanceSample) -> WorkoutDistanceInterval? {
    kernel.append(current: sample.core).map(WorkoutDistanceInterval.from)
  }
  mutating func appendPage(_ samples: [WorkoutControllerDistanceSample], totalQ: Int64, coverageUs: Int64) throws
    -> WorkoutDistancePageProjection
  {
    let numbers = NSMutableData(length: samples.count * 4 * MemoryLayout<Double>.stride)!
    let keys = NSMutableData(length: samples.count * 6 * MemoryLayout<Int64>.stride)!
    let values = numbers.mutableBytes.assumingMemoryBound(to: Double.self)
    let integers = keys.mutableBytes.assumingMemoryBound(to: Int64.self)
    var text: [String] = []
    text.reserveCapacity(samples.count * 6)
    for (index, sample) in samples.enumerated() {
      let n = index * 4
      let k = index * 6
      values[n] = sample.time
      values[n + 1] = sample.speed ?? .nan
      values[n + 2] = sample.monotonic ?? .nan
      values[n + 3] = sample.counter ?? .nan
      integers[k] = sample.collectionUs ?? WorkoutDistancePolicy.timeUs(sample.time)
      integers[k + 1] = Int64(sample.activeInterval ?? -1)
      integers[k + 2] = sample.counterExact ?? 0
      integers[k + 3] = sample.counterExact != nil ? 1 : (sample.counter == nil ? 0 : 2)
      integers[k + 4] = sample.barrier ? 1 : 0
      integers[k + 5] =
        (sample.model == nil ? 0 : 1) | (sample.controllerProtocol == nil ? 0 : 2)
        | (sample.identity == nil ? 0 : 4) | (sample.continuity == nil ? 0 : 8)
      text.append(sample.model ?? "")
      text.append(sample.controllerProtocol ?? "")
      text.append(sample.identity ?? "")
      text.append(sample.continuity ?? "")
      text.append(sample.anchor)
      text.append(sample.timestamp)
    }
    return try WorkoutDistancePageProjection(
      AppleDistancePages.shared.controller(
        kernel: kernel, numbers: numbers, keys: keys, text: text, totalQ: totalQ, coveredUs: coverageUs))
  }

}

struct WorkoutDistancePageProjection {
  struct Interval {
    var value: WorkoutDistanceInterval
    var total: Double
    var coverage: Double
    var priorTotal: Double
    var priorCoverage: Double
  }
  var intervals: [Interval]
  var totalQ: Int64
  var coverageUs: Int64
  init(_ page: AppleDistancePageProjection) {
    let text = page.text
    totalQ = page.totalQ
    coverageUs = page.coveredUs
    intervals = withExtendedLifetime((page.numbers, page.keys)) {
      let numbers = page.numbers.bytes.assumingMemoryBound(to: Double.self)
      let keys = page.keys.bytes.assumingMemoryBound(to: Int64.self)
      return stride(from: 0, to: Int(page.keys.length) / (2 * MemoryLayout<Int64>.stride), by: 1).map { index in
        let n = index * 9
        let k = index * 2
        let t = index * 4
        return Interval(
          value: WorkoutDistanceInterval(
            startSeconds: numbers[n], endSeconds: numbers[n + 1], meters: numbers[n + 2], segment: Int(keys[k]),
            startAnchor: text[t], endAnchor: text[t + 1], startTimestamp: text[t + 2], endTimestamp: text[t + 3],
            startSpeed: numbers[n + 7].isNaN ? nil : numbers[n + 7],
            endSpeed: numbers[n + 8].isNaN ? nil : numbers[n + 8], indivisible: keys[k + 1] != 0),
          total: numbers[n + 3], coverage: numbers[n + 4], priorTotal: numbers[n + 5], priorCoverage: numbers[n + 6])
      }
    }
  }
}

struct WorkoutDistanceCursor: Codable, Equatable {
  var time: Double
  var pointID: Int64
}
struct WorkoutDistancePoint: Codable {
  var pointID: Int64
  var identity: String
  var timestamp: String
  var elapsedSeconds: Double
  var distanceMeters: Double
  var incrementMeters: Double
  var startSeconds: Double
  var endSeconds: Double
  var segment: Int
  var startAnchor: String
  var endAnchor: String
  var startSpeed: Double?
  var endSpeed: Double?
  var indivisible: Bool
  var cumulativeCoveredSeconds: Double = 0
  var plotSegment: Int = 0
  var cursor: WorkoutDistanceCursor { WorkoutDistanceCursor(time: elapsedSeconds, pointID: pointID) }
  var interval: WorkoutDistanceInterval {
    WorkoutDistanceInterval(
      startSeconds: startSeconds, endSeconds: endSeconds,
      meters: incrementMeters, segment: segment, startAnchor: startAnchor, endAnchor: endAnchor,
      startTimestamp: timestamp,
      endTimestamp: timestamp, startSpeed: startSpeed, endSpeed: endSpeed, indivisible: indivisible)
  }
  var dictionary: [String: Any] {
    [
      "observationId": identity, "timestamp": timestamp, "elapsedSeconds": elapsedSeconds,
      "value": distanceMeters, "segment": segment, "startsSegment": startSeconds == endSeconds, "derived": true,
    ]
  }
}
struct WorkoutDistanceRange: Codable {
  static func from(_ range: DistanceRange) -> Self {
    Self(
      distanceMeters: range.distanceMeters, coveredSeconds: range.coveredSeconds,
      unresolvedBoundary: range.unresolvedBoundary, partial: range.partial)
  }

  var distanceMeters: Double?
  var coveredSeconds: Double
  var unresolvedBoundary: Bool
  var partial: Bool
}
struct WorkoutDistanceSource: Codable {
  var core: DistanceProfile {
    DistanceProfile(
      source: source, label: label, estimated: estimated, partial: partial,
      coveredUs: WorkoutDistancePolicy.storedUs(coveredSeconds),
      uncoveredUs: WorkoutDistancePolicy.storedUs(uncoveredSeconds),
      distanceQ: WorkoutDistancePolicy.metersQ(distanceMeters))
  }
  static func from(_ profile: DistanceProfile) -> Self {
    Self(
      source: profile.source, label: profile.label, estimated: profile.estimated, partial: profile.partial,
      coveredSeconds: profile.coveredSeconds, uncoveredSeconds: profile.uncoveredSeconds,
      distanceMeters: profile.distanceMeters)
  }

  var source: String
  var label: String
  var estimated: Bool
  var partial: Bool
  var coveredSeconds: Double
  var uncoveredSeconds: Double
  var distanceMeters: Double
  var dictionary: [String: Any] {
    [
      "source": source, "label": label, "estimated": estimated, "partial": partial,
      "coveredSeconds": coveredSeconds, "uncoveredSeconds": uncoveredSeconds, "distanceMeters": distanceMeters,
    ]
  }
}
struct WorkoutDistanceInfo {
  var selection: String
  var selected: WorkoutDistanceSource?
  var available: [WorkoutDistanceSource]
  var dictionary: [String: Any] {
    [
      "selection": selection, "selected": selected?.dictionary ?? NSNull() as Any,
      "available": available.map(\.dictionary),
    ]
  }
}
struct WorkoutBatteryProfile: Codable {
  var total: Double
  var coveredSeconds: Double
  func partial(activeSeconds: Double) -> Bool {
    WorkoutDistancePolicy.timeUs(activeSeconds) - WorkoutDistancePolicy.storedUs(coveredSeconds) > 1_000
  }
}

struct WorkoutDistanceSnapshot: Codable {
  var id: String
  var revision: Int64
  var generation: String
  var storageID: Int64 = 0
  var selection: String
  var source: String?
  var method: String?
  var estimated: Bool
  var totalMeters: Double?
  var coveredSeconds: Double
  var activeSeconds: Double
  var outcome: String
  var sources: [WorkoutDistanceSource]
  var maximumPointID: Int64
  var endSeconds: Double
  var activeIntervals: [[Double]]
  var battery: [String: WorkoutBatteryProfile] = [:]
  var counterEdgeUs = BatteryConsumptionPolicy.shared.maximumCounterEdgeUs
  var controllerEdgeUs = DistancePolicy.shared.maximumControllerEdgeUs
  var info: WorkoutDistanceInfo {
    WorkoutDistanceInfo(selection: selection, selected: sources.first { $0.source == source }, available: sources)
  }
  var dictionary: [String: Any] { info.dictionary }
}

enum WorkoutDistanceError: Error, LocalizedError {
  case pending, expired, invalid(String)
  var errorDescription: String? {
    switch self {
    case .pending: return "Distance calculation is pending."
    case .expired: return "Distance snapshot expired; retry the read."
    case .invalid(let message): return message
    }
  }
}
