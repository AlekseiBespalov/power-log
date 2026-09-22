import Foundation
import HealthKit
import CryptoKit
import CoreFoundation

/// Explicit units avoid silently turning energy, distance or percentage quantities into another measurement.
enum WatchHealthMetrics {
  struct Metric {
    let identifier: HKQuantityTypeIdentifier
    let key: String
    let unit: HKUnit
    let unitName: String
    let cumulative: Bool
    var type: HKQuantityType? { HKObjectType.quantityType(forIdentifier: identifier) }
  }

  static let supported: [Metric] = {
    var metrics: [Metric] = [
    Metric(identifier: .heartRate, key: "heartRate", unit: .count().unitDivided(by: .minute()), unitName: "bpm", cumulative: false),
    Metric(identifier: .activeEnergyBurned, key: "activeEnergy", unit: .kilocalorie(), unitName: "kcal", cumulative: true),
    Metric(identifier: .basalEnergyBurned, key: "basalEnergy", unit: .kilocalorie(), unitName: "kcal", cumulative: true),
    Metric(identifier: .distanceCycling, key: "distance", unit: .meter(), unitName: "m", cumulative: true),
    Metric(identifier: .cyclingPower, key: "riderPower", unit: .watt(), unitName: "W", cumulative: false),
    Metric(identifier: .cyclingCadence, key: "cadence", unit: .count().unitDivided(by: .minute()), unitName: "rpm", cumulative: false),
    Metric(identifier: .cyclingSpeed, key: "speed", unit: .meter().unitDivided(by: .second()), unitName: "m/s", cumulative: false),
    Metric(identifier: .respiratoryRate, key: "respiratoryRate", unit: .count().unitDivided(by: .minute()), unitName: "breaths/min", cumulative: false),
    Metric(identifier: .oxygenSaturation, key: "oxygenSaturation", unit: .percent(), unitName: "%", cumulative: false),
    Metric(identifier: .heartRateVariabilitySDNN, key: "heartRateVariabilitySDNN", unit: .secondUnit(with: .milli), unitName: "ms", cumulative: false),
    Metric(identifier: .physicalEffort, key: "physicalEffortMET", unit: .kilocalorie().unitDivided(by: .gramUnit(with: .kilo).unitMultiplied(by: .hour())), unitName: "kcal/(kg*hr)", cumulative: false),
    Metric(identifier: .cyclingFunctionalThresholdPower, key: "cyclingFunctionalThresholdPowerW", unit: .watt(), unitName: "W", cumulative: false)
    ]
    if #available(watchOS 11.0, *) {
      metrics.append(Metric(identifier: .workoutEffortScore, key: "workoutEffortScore", unit: .appleEffortScore(), unitName: "appleEffortScore", cumulative: false))
      metrics.append(Metric(identifier: .estimatedWorkoutEffortScore, key: "estimatedWorkoutEffortScore", unit: .appleEffortScore(), unitName: "appleEffortScore", cumulative: false))
    }
    return metrics
  }()

  static func metric(for type: HKQuantityType) -> Metric? {
    supported.first { $0.identifier.rawValue == type.identifier }
  }

  static var quantityTypes: Set<HKQuantityType> { Set(supported.compactMap(\.type)) }
  static var readTypes: Set<HKObjectType> {
    Set(quantityTypes.map { $0 as HKObjectType }).union([HKObjectType.workoutType(), HKSeriesType.workoutRoute()])
  }
  static var shareTypes: Set<HKSampleType> {
    // The live data source owns physiological measurements. Only CYC rider metrics are manually inserted.
    let identifiers: [HKQuantityTypeIdentifier] = [.heartRate, .activeEnergyBurned, .basalEnergyBurned,
      .distanceCycling, .cyclingPower, .cyclingCadence, .cyclingSpeed, .respiratoryRate,
      .oxygenSaturation, .heartRateVariabilitySDNN]
    return Set(identifiers.compactMap { HKQuantityType.quantityType(forIdentifier: $0) as HKSampleType? })
      .union([HKObjectType.workoutType(), HKSeriesType.workoutRoute()])
  }

  static func stableEventID(_ identity: String) -> String {
    let bytes = Array(SHA256.hash(data: Data(identity.utf8)).prefix(16))
    return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
      bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15])).uuidString.lowercased()
  }

  static func metadataValue(_ value: Any, depth: Int = 0) -> WorkoutJSON? {
    guard depth <= 6 else { return nil }
    if let date = value as? Date { return .object(["type": .string("date"), "value": .string(WorkoutCoding.timestamp(date))]) }
    if let quantity = value as? HKQuantity {
      let candidates = supported.map { ($0.unit, $0.unitName) } + [(.meter(), "m"), (.second(), "s"),
        (.degreeCelsius(), "degC"), (.pascal(), "Pa"), (.count(), "count")]
      if let (unit, name) = candidates.first(where: { quantity.is(compatibleWith: $0.0) }) {
        let number = quantity.doubleValue(for: unit)
        if number.isFinite { return .object(["type": .string("quantity"), "value": .number(number),
          "unit": .string(name), "originalDescription": .string(String(quantity.description.prefix(1024)))]) }
      }
      return .object(["type": .string("quantityDescription"), "value": .string(String(quantity.description.prefix(4096)))])
    }
    if let number = value as? NSNumber {
      if CFGetTypeID(number) == CFBooleanGetTypeID() { return .object(["type": .string("boolean"), "value": .bool(number.boolValue)]) }
      guard number.doubleValue.isFinite else { return nil }
      return .object(["type": .string("number"), "value": .number(number.doubleValue)])
    }
    if let text = value as? String { return .object(["type": .string("string"), "value": .string(String(text.prefix(4096)))]) }
    if let values = value as? [Any], values.count <= 128 { return .array(values.compactMap { metadataValue($0, depth: depth + 1) }) }
    if let values = value as? [String: Any], values.count <= 128 { return .object(values.compactMapValues { metadataValue($0, depth: depth + 1) }) }
    return nil
  }
}
