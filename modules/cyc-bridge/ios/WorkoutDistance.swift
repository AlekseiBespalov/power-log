import Foundation

/// Pure distance interpretation. This file is shared with Watch; it never writes originals or Health data.
enum WorkoutDistancePolicy {
  static let version = 1
  static let maximumSeconds = 2_678_400.0
  static let sources = ["gps:watch", "gps:phone", "health:watch", "health:phone", "controller"]
  static func validSpeed(_ speed: Double?, accuracy: Double? = nil) -> Double? {
    guard let speed, speed.isFinite, (0...40).contains(speed), accuracy.map({ $0.isFinite && $0 >= 0 }) ?? true else { return nil }
    return speed
  }
  static func label(_ source: String) -> String {
    switch source {
    case "gps:watch": return "GPS · Watch"
    case "gps:phone": return "GPS · iPhone"
    case "health:watch": return "Health intervals · Watch"
    case "health:phone": return "Health intervals · iPhone"
    default: return "Controller estimate"
    }
  }
  static func method(_ source: String) -> String { source.hasPrefix("gps:") ? "gpsGeometry" : source.hasPrefix("health:") ? "healthIntervals" : "controllerEstimate" }
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
  var coveredSeconds: Double { endSeconds - startSeconds }

  func clipped(start: Double, end: Double) -> WorkoutDistanceRange {
    let a = max(start, startSeconds), b = min(end, endSeconds)
    guard b > a else { return WorkoutDistanceRange(distanceMeters: 0, coveredSeconds: 0, unresolvedBoundary: false, partial: false) }
    if indivisible && (a > startSeconds || b < endSeconds) {
      return WorkoutDistanceRange(distanceMeters: 0, coveredSeconds: 0, unresolvedBoundary: true, partial: true)
    }
    let meters: Double
    if let u = startSpeed, let v = endSpeed {
      let rate = (v - u) / coveredSeconds
      meters = (u + rate * ((a + b) / 2 - startSeconds)) * (b - a)
    } else { meters = self.meters * (b - a) / coveredSeconds }
    return WorkoutDistanceRange(distanceMeters: max(0, meters), coveredSeconds: b - a, unresolvedBoundary: false, partial: false)
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

  var valid: Bool {
    !barrier && time.isFinite && time >= 0 && latitude.isFinite && longitude.isFinite &&
      (-90...90).contains(latitude) && (-180...180).contains(longitude) && horizontalAccuracy.isFinite &&
      (0...50).contains(horizontalAccuracy) && activeInterval != nil
  }
}

struct WorkoutGPSDistanceAccumulator: Codable {
  private(set) var previous: WorkoutGPSFix?
  private(set) var segment = 0
  private(set) var distanceMeters = 0.0
  private(set) var coveredSeconds = 0.0
  private(set) var acceptedIntervals = 0
  private(set) var rejected = 0
  mutating func reset() { previous = nil; segment += 1 }
  mutating func append(_ fix: WorkoutGPSFix) -> WorkoutDistanceInterval? {
    guard fix.valid else { rejected += 1; reset(); return nil }
    guard let old = previous else { previous = fix; return nil }
    previous = fix
    let dt = fix.time - old.time
    if dt <= 0 { rejected += 1; reset(); return nil }
    guard dt > 0, dt <= 10, old.activeInterval == fix.activeInterval, old.epoch == fix.epoch else {
      rejected += 1; segment += 1; return nil
    }
    let chord = Self.meters(old, fix)
    guard chord / dt <= 40 else { rejected += 1; segment += 1; return nil }
    let stationary = WorkoutDistancePolicy.validSpeed(old.speed, accuracy: old.speedAccuracy).map { $0 < 0.5 } == true &&
      WorkoutDistancePolicy.validSpeed(fix.speed, accuracy: fix.speedAccuracy).map { $0 < 0.5 } == true
    let distance = stationary ? 0 : chord
    distanceMeters += distance; coveredSeconds += dt; acceptedIntervals += 1
    return WorkoutDistanceInterval(startSeconds: old.time, endSeconds: fix.time, meters: distance, segment: segment,
      startAnchor: old.identity, endAnchor: fix.identity, startTimestamp: old.timestamp, endTimestamp: fix.timestamp)
  }
  static func meters(_ a: WorkoutGPSFix, _ b: WorkoutGPSFix) -> Double {
    let p1 = a.latitude * .pi / 180, p2 = b.latitude * .pi / 180
    let dp = p2 - p1, dl = (b.longitude - a.longitude) * .pi / 180
    let h = pow(sin(dp / 2), 2) + cos(p1) * cos(p2) * pow(sin(dl / 2), 2)
    return 6_371_008.8 * 2 * atan2(sqrt(max(0, min(1, h))), sqrt(max(0, 1 - h)))
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
  var valid: Bool {
    !barrier && time.isFinite && time >= 0 && WorkoutDistancePolicy.validSpeed(speed) != nil &&
      ["X6", "X12"].contains(model ?? "") && controllerProtocol == "5.3" &&
      !(identity ?? "").isEmpty && !(continuity ?? "").isEmpty && activeInterval != nil
  }
}
struct WorkoutControllerDistanceAccumulator: Codable {
  private(set) var previous: WorkoutControllerDistanceSample?
  private(set) var segment = 0
  mutating func reset() { previous = nil; segment += 1 }
  mutating func append(_ sample: WorkoutControllerDistanceSample) -> WorkoutDistanceInterval? {
    guard sample.valid else { reset(); return nil }
    guard let old = previous else { previous = sample; return nil }
    previous = sample
    let dt = sample.time - old.time
    if dt <= 0 { reset(); return nil }
    guard dt > 0, dt <= 2.5, sample.model == old.model, sample.controllerProtocol == old.controllerProtocol,
      sample.identity == old.identity, sample.continuity == old.continuity, sample.activeInterval == old.activeInterval,
      !(sample.monotonic != nil && old.monotonic != nil && sample.monotonic! <= old.monotonic!),
      !(sample.counter != nil && old.counter != nil && sample.counter! < old.counter!) else { segment += 1; return nil }
    return WorkoutDistanceInterval(startSeconds: old.time, endSeconds: sample.time, meters: (old.speed! + sample.speed!) * dt / 2,
      segment: segment, startAnchor: old.anchor, endAnchor: sample.anchor, startTimestamp: old.timestamp, endTimestamp: sample.timestamp,
      startSpeed: old.speed, endSpeed: sample.speed)
  }
}

struct WorkoutHealthDistanceInput: Codable {
  var start: Double
  var end: Double
  var meters: Double
  var identifier: String
  var unit: String
  var representation: String
  var sampleCount: Double?
  var associated: Bool
  var anchor: String
  var startTimestamp: String
  var endTimestamp: String
  func interval(activeIntervals: [[Double]], segment: Int = 0) -> WorkoutDistanceInterval? {
    guard associated, identifier == "HKQuantityTypeIdentifierDistanceCycling", unit == "m", meters.isFinite, meters >= 0,
      start.isFinite, end.isFinite, start >= 0, end > start, meters / (end - start) <= 40,
      representation == "rawSeries" || (representation == "rawQuantity" && sampleCount == 1),
      activeIntervals.contains(where: { $0.count == 2 && start >= $0[0] && end <= $0[1] }) else { return nil }
    return WorkoutDistanceInterval(startSeconds: start, endSeconds: end, meters: meters, segment: segment,
      startAnchor: anchor, endAnchor: anchor, startTimestamp: startTimestamp, endTimestamp: endTimestamp, indivisible: true)
  }
}

struct WorkoutDistanceCursor: Codable, Equatable { var time: Double; var pointID: Int64 }
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
  var cursor: WorkoutDistanceCursor { WorkoutDistanceCursor(time: elapsedSeconds, pointID: pointID) }
  var interval: WorkoutDistanceInterval { WorkoutDistanceInterval(startSeconds: startSeconds, endSeconds: endSeconds,
    meters: incrementMeters, segment: segment, startAnchor: startAnchor, endAnchor: endAnchor, startTimestamp: timestamp,
    endTimestamp: timestamp, startSpeed: startSpeed, endSpeed: endSpeed, indivisible: indivisible) }
  var dictionary: [String: Any] { ["observationId": identity, "timestamp": timestamp, "elapsedSeconds": elapsedSeconds,
    "value": distanceMeters, "segment": segment, "startsSegment": startSeconds == endSeconds, "derived": true] }
}
struct WorkoutDistanceRange: Codable {
  var distanceMeters: Double?
  var coveredSeconds: Double
  var unresolvedBoundary: Bool
  var partial: Bool
}
struct WorkoutDistanceSource: Codable {
  var source: String
  var label: String
  var estimated: Bool
  var partial: Bool
  var coveredSeconds: Double
  var uncoveredSeconds: Double
  var policyVersion: Int = WorkoutDistancePolicy.version
  var distanceMeters: Double
  var dictionary: [String: Any] { ["source": source, "label": label, "estimated": estimated, "partial": partial,
    "coveredSeconds": coveredSeconds, "uncoveredSeconds": uncoveredSeconds, "policyVersion": policyVersion, "distanceMeters": distanceMeters] }
}
struct WorkoutDistanceInfo {
  var selection: String
  var selected: WorkoutDistanceSource?
  var available: [WorkoutDistanceSource]
  var dictionary: [String: Any] { ["selection": selection, "selected": selected?.dictionary ?? NSNull() as Any, "available": available.map(\.dictionary)] }
}
struct WorkoutDistanceSnapshot: Codable {
  var id: String
  var revision: Int64
  var policyVersion: Int = WorkoutDistancePolicy.version
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
  var healthReportedMeters: Double?
  var healthReportedSource: String?
  var healthReportedProvisional: Bool
  var healthReportedAt: String? = nil
  var maximumPointID: Int64
  var endSeconds: Double
  var activeIntervals: [[Double]]
  var info: WorkoutDistanceInfo { WorkoutDistanceInfo(selection: selection, selected: sources.first { $0.source == source }, available: sources) }
  var dictionary: [String: Any] { info.dictionary }
}

enum WorkoutDistanceError: Error, LocalizedError {
  case pending, expired, invalid(String)
  var errorDescription: String? {
    switch self { case .pending: return "Distance calculation is pending."; case .expired: return "Distance snapshot expired; retry the read."; case .invalid(let message): return message }
  }
}
