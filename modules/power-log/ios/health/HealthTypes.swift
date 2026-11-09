import Foundation
import HealthKit

private func micros(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1_000_000).rounded(.down)) }
private func date(_ micros: Int64) -> Date { Date(timeIntervalSince1970: Double(micros) / 1_000_000) }

struct HealthPlanWorkout: Codable, Equatable {
  let rideID: String
  let syncID: String
  let syncVersion: Int
  let start: Date

  init(rideID: String, syncID: String, syncVersion: Int, start: Date) {
    self.rideID = rideID
    self.syncID = syncID
    self.syncVersion = syncVersion
    self.start = start
  }

  private enum CodingKeys: String, CodingKey {
    case rideID = "rideId", syncID = "syncId", syncVersion, start = "startUtcUs"
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    rideID = try c.decode(String.self, forKey: .rideID)
    syncID = try c.decode(String.self, forKey: .syncID)
    syncVersion = try c.decode(Int.self, forKey: .syncVersion)
    start = date(try c.decode(Int64.self, forKey: .start))
  }

  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: CodingKeys.self)
    try c.encode(rideID, forKey: .rideID)
    try c.encode(syncID, forKey: .syncID)
    try c.encode(syncVersion, forKey: .syncVersion)
    try c.encode(micros(start), forKey: .start)
  }
}

struct HealthPlanQuantity: Codable, Equatable {
  let syncID: String
  let quantity: String
  let value: Double
  let start: Date
  let end: Date

  init(syncID: String, quantity: String, value: Double, start: Date, end: Date) {
    self.syncID = syncID
    self.quantity = quantity
    self.value = value
    self.start = start
    self.end = end
  }

  private enum CodingKeys: String, CodingKey {
    case syncID = "syncId", quantity, value, start = "startUtcUs", end = "endUtcUs"
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    syncID = try c.decode(String.self, forKey: .syncID)
    quantity = try c.decode(String.self, forKey: .quantity)
    value = try c.decode(Double.self, forKey: .value)
    start = date(try c.decode(Int64.self, forKey: .start))
    end = date(try c.decode(Int64.self, forKey: .end))
  }

  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: CodingKeys.self)
    try c.encode(syncID, forKey: .syncID)
    try c.encode(quantity, forKey: .quantity)
    try c.encode(value, forKey: .value)
    try c.encode(micros(start), forKey: .start)
    try c.encode(micros(end), forKey: .end)
  }
}

struct RoutePoint: Codable, Equatable {
  let at: Date
  let latitude: Double
  let longitude: Double
  let altitudeM: Double?
  let horizontalAccuracyM: Double
  let verticalAccuracyM: Double?
  let speedMps: Double?
  let courseDeg: Double?

  init(
    at: Date, latitude: Double, longitude: Double, altitudeM: Double?, horizontalAccuracyM: Double,
    verticalAccuracyM: Double?, speedMps: Double?, courseDeg: Double?
  ) {
    self.at = at
    self.latitude = latitude
    self.longitude = longitude
    self.altitudeM = altitudeM
    self.horizontalAccuracyM = horizontalAccuracyM
    self.verticalAccuracyM = verticalAccuracyM
    self.speedMps = speedMps
    self.courseDeg = courseDeg
  }

  private enum CodingKeys: String, CodingKey {
    case at = "utcUs", latitude, longitude, altitudeM, horizontalAccuracyM, verticalAccuracyM, speedMps, courseDeg
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    at = date(try c.decode(Int64.self, forKey: .at))
    latitude = try c.decode(Double.self, forKey: .latitude)
    longitude = try c.decode(Double.self, forKey: .longitude)
    altitudeM = try c.decodeIfPresent(Double.self, forKey: .altitudeM)
    horizontalAccuracyM = try c.decode(Double.self, forKey: .horizontalAccuracyM)
    verticalAccuracyM = try c.decodeIfPresent(Double.self, forKey: .verticalAccuracyM)
    speedMps = try c.decodeIfPresent(Double.self, forKey: .speedMps)
    courseDeg = try c.decodeIfPresent(Double.self, forKey: .courseDeg)
  }

  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: CodingKeys.self)
    try c.encode(micros(at), forKey: .at)
    try c.encode(latitude, forKey: .latitude)
    try c.encode(longitude, forKey: .longitude)
    try c.encodeIfPresent(altitudeM, forKey: .altitudeM)
    try c.encode(horizontalAccuracyM, forKey: .horizontalAccuracyM)
    try c.encodeIfPresent(verticalAccuracyM, forKey: .verticalAccuracyM)
    try c.encodeIfPresent(speedMps, forKey: .speedMps)
    try c.encodeIfPresent(courseDeg, forKey: .courseDeg)
  }
}

struct HealthPlanRoute: Codable, Equatable {
  let syncID: String
  let points: [RoutePoint]

  init(syncID: String, points: [RoutePoint]) {
    self.syncID = syncID
    self.points = points
  }

  private enum CodingKeys: String, CodingKey { case syncID = "syncId", points }
}

struct HealthOriginal: Codable, Equatable {
  let sampleID: String
  let member: Int?
  let metric: String
  let value: Double
  let start: Date
  let end: Date
  let dataOrigin: String
  let syncID: String?

  init(
    sampleID: String, member: Int?, metric: String, value: Double, start: Date, end: Date, dataOrigin: String,
    syncID: String?
  ) {
    self.sampleID = sampleID
    self.member = member
    self.metric = metric
    self.value = value
    self.start = start
    self.end = end
    self.dataOrigin = dataOrigin
    self.syncID = syncID
  }

  private enum CodingKeys: String, CodingKey {
    case sampleID = "sampleId", member, metric, value, start = "startUtcUs", end = "endUtcUs", dataOrigin,
      syncID = "syncId"
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    sampleID = try c.decode(String.self, forKey: .sampleID)
    member = try c.decodeIfPresent(Int.self, forKey: .member)
    metric = try c.decode(String.self, forKey: .metric)
    value = try c.decode(Double.self, forKey: .value)
    start = date(try c.decode(Int64.self, forKey: .start))
    end = date(try c.decode(Int64.self, forKey: .end))
    dataOrigin = try c.decode(String.self, forKey: .dataOrigin)
    syncID = try c.decodeIfPresent(String.self, forKey: .syncID)
  }

  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: CodingKeys.self)
    try c.encode(sampleID, forKey: .sampleID)
    try c.encodeIfPresent(member, forKey: .member)
    try c.encode(metric, forKey: .metric)
    try c.encode(value, forKey: .value)
    try c.encode(micros(start), forKey: .start)
    try c.encode(micros(end), forKey: .end)
    try c.encode(dataOrigin, forKey: .dataOrigin)
    try c.encodeIfPresent(syncID, forKey: .syncID)
  }
}

struct LiveHeartRate: Codable, Equatable {
  let bpm: Double
  let at: Date

  init(bpm: Double, at: Date) {
    self.bpm = bpm
    self.at = at
  }

  private enum CodingKeys: String, CodingKey { case bpm, at = "utcUs" }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    bpm = try c.decode(Double.self, forKey: .bpm)
    at = date(try c.decode(Int64.self, forKey: .at))
  }

  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: CodingKeys.self)
    try c.encode(bpm, forKey: .bpm)
    try c.encode(micros(at), forKey: .at)
  }
}

enum AppleHealthTypes {
  struct Metric {
    let key: String
    let identifier: HKQuantityTypeIdentifier
    let unit: HKUnit
    var type: HKQuantityType? { HKObjectType.quantityType(forIdentifier: identifier) }
  }

  static let readback: [Metric] = {
    let perMinute = HKUnit.count().unitDivided(by: .minute())
    var metrics = [
      Metric(key: "heartRateBpm", identifier: .heartRate, unit: perMinute),
      Metric(key: "activeEnergyKcal", identifier: .activeEnergyBurned, unit: .kilocalorie()),
      Metric(key: "basalEnergyKcal", identifier: .basalEnergyBurned, unit: .kilocalorie()),
      Metric(key: "cyclingDistanceMeters", identifier: .distanceCycling, unit: .meter()),
      Metric(key: "cyclingPowerW", identifier: .cyclingPower, unit: .watt()),
      Metric(key: "cyclingCadenceRpm", identifier: .cyclingCadence, unit: perMinute),
      Metric(key: "cyclingSpeedMps", identifier: .cyclingSpeed, unit: .meter().unitDivided(by: .second())),
      Metric(key: "respiratoryRateBrpm", identifier: .respiratoryRate, unit: perMinute),
      Metric(key: "oxygenSaturationFraction", identifier: .oxygenSaturation, unit: .percent()),
      Metric(key: "heartRateVariabilitySDNNMs", identifier: .heartRateVariabilitySDNN, unit: .secondUnit(with: .milli)),
      Metric(
        key: "physicalEffortMET", identifier: .physicalEffort,
        unit: .kilocalorie().unitDivided(by: .gramUnit(with: .kilo).unitMultiplied(by: .hour()))),
      Metric(key: "cyclingFunctionalThresholdPowerW", identifier: .cyclingFunctionalThresholdPower, unit: .watt()),
    ]
    if #available(watchOS 11.0, *) {
      metrics.append(Metric(key: "workoutEffortScore", identifier: .workoutEffortScore, unit: .appleEffortScore()))
      metrics.append(
        Metric(key: "estimatedWorkoutEffortScore", identifier: .estimatedWorkoutEffortScore, unit: .appleEffortScore()))
    }
    return metrics
  }()

  static func quantity(_ name: String) -> (identifier: HKQuantityTypeIdentifier, unit: HKUnit)? {
    switch name {
    case "rider-power": return (.cyclingPower, .watt())
    case "cycling-cadence": return (.cyclingCadence, .count().unitDivided(by: .minute()))
    case "cycling-speed": return (.cyclingSpeed, .meter().unitDivided(by: .second()))
    case "cycling-distance": return (.distanceCycling, .meter())
    default: return nil
    }
  }

  static let liveQuantities: [HKQuantityTypeIdentifier] = [
    .cyclingPower, .cyclingCadence, .cyclingSpeed, .distanceCycling,
  ]

  static var shareTypes: Set<HKSampleType> {
    let identifiers: [HKQuantityTypeIdentifier] = [
      .heartRate, .activeEnergyBurned, .basalEnergyBurned, .distanceCycling, .cyclingPower, .cyclingCadence,
      .cyclingSpeed,
    ]
    return Set(identifiers.compactMap { HKQuantityType.quantityType(forIdentifier: $0) as HKSampleType? })
      .union([HKObjectType.workoutType(), HKSeriesType.workoutRoute()])
  }

  static var readTypes: Set<HKObjectType> {
    let quantities = Set(readback.compactMap { $0.type as HKObjectType? })
    #if os(watchOS)
      return quantities
    #else
      return quantities.union([HKObjectType.workoutType()])
    #endif
  }
}
