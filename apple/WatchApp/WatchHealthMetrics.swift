import Foundation
import HealthKit
import CoreFoundation

/// Explicit units avoid silently turning energy, distance or percentage quantities into another measurement.
enum WatchHealthMetrics {
  struct Metric {
    let identifier: HKQuantityTypeIdentifier
    let unit: HKUnit
    let unitName: String
    let cumulative: Bool
    var type: HKQuantityType? { HKObjectType.quantityType(forIdentifier: identifier) }
  }

  static let supported: [Metric] = {
    var metrics: [Metric] = [
      Metric(identifier: .heartRate, unit: .count().unitDivided(by: .minute()), unitName: "bpm", cumulative: false),
      Metric(identifier: .activeEnergyBurned, unit: .kilocalorie(), unitName: "kcal", cumulative: true),
      Metric(identifier: .basalEnergyBurned, unit: .kilocalorie(), unitName: "kcal", cumulative: true),
      Metric(identifier: .distanceCycling, unit: .meter(), unitName: "m", cumulative: true),
      Metric(identifier: .cyclingPower, unit: .watt(), unitName: "W", cumulative: false),
      Metric(
        identifier: .cyclingCadence, unit: .count().unitDivided(by: .minute()), unitName: "rpm", cumulative: false),
      Metric(identifier: .cyclingSpeed, unit: .meter().unitDivided(by: .second()), unitName: "m/s", cumulative: false),
      Metric(
        identifier: .respiratoryRate, unit: .count().unitDivided(by: .minute()), unitName: "breaths/min",
        cumulative: false),
      Metric(identifier: .oxygenSaturation, unit: .percent(), unitName: "%", cumulative: false),
      Metric(identifier: .heartRateVariabilitySDNN, unit: .secondUnit(with: .milli), unitName: "ms", cumulative: false),
      Metric(
        identifier: .physicalEffort,
        unit: .kilocalorie().unitDivided(by: .gramUnit(with: .kilo).unitMultiplied(by: .hour())),
        unitName: "kcal/(kg*hr)", cumulative: false),
      Metric(identifier: .cyclingFunctionalThresholdPower, unit: .watt(), unitName: "W", cumulative: false),
    ]
    if #available(watchOS 11.0, *) {
      metrics.append(
        Metric(
          identifier: .workoutEffortScore, unit: .appleEffortScore(), unitName: "appleEffortScore", cumulative: false))
      metrics.append(
        Metric(
          identifier: .estimatedWorkoutEffortScore, unit: .appleEffortScore(), unitName: "appleEffortScore",
          cumulative: false))
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
    let identifiers: [HKQuantityTypeIdentifier] = [
      .heartRate, .activeEnergyBurned, .basalEnergyBurned,
      .distanceCycling, .cyclingPower, .cyclingCadence, .cyclingSpeed, .respiratoryRate,
      .oxygenSaturation, .heartRateVariabilitySDNN,
    ]
    return Set(identifiers.compactMap { HKQuantityType.quantityType(forIdentifier: $0) as HKSampleType? })
      .union([HKObjectType.workoutType(), HKSeriesType.workoutRoute()])
  }

  static func metadataValue(_ value: Any, depth: Int = 0) -> WorkoutJSON? {
    guard depth <= 6 else { return nil }
    if let date = value as? Date {
      return .object(["type": .string("date"), "value": .string(WorkoutCoding.timestamp(date))])
    }
    if let quantity = value as? HKQuantity {
      let candidates =
        supported.map { ($0.unit, $0.unitName) } + [
          (.meter(), "m"), (.second(), "s"),
          (.degreeCelsius(), "degC"), (.pascal(), "Pa"), (.count(), "count"),
        ]
      if let (unit, name) = candidates.first(where: { quantity.is(compatibleWith: $0.0) }) {
        let number = quantity.doubleValue(for: unit)
        if number.isFinite {
          return .object([
            "type": .string("quantity"), "value": .number(number),
            "unit": .string(name), "originalDescription": .string(String(quantity.description.prefix(1024))),
          ])
        }
      }
      return .object([
        "type": .string("quantityDescription"), "value": .string(String(quantity.description.prefix(4096))),
      ])
    }
    if let number = value as? NSNumber {
      if CFGetTypeID(number) == CFBooleanGetTypeID() {
        return .object(["type": .string("boolean"), "value": .bool(number.boolValue)])
      }
      guard number.doubleValue.isFinite else { return nil }
      return .object(["type": .string("number"), "value": .number(number.doubleValue)])
    }
    if let text = value as? String {
      return .object(["type": .string("string"), "value": .string(String(text.prefix(4096)))])
    }
    if let values = value as? [Any], values.count <= 128 {
      return .array(values.compactMap { metadataValue($0, depth: depth + 1) })
    }
    if let values = value as? [String: Any], values.count <= 128 {
      return .object(values.compactMapValues { metadataValue($0, depth: depth + 1) })
    }
    return nil
  }
}
