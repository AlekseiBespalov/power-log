import Foundation
import HealthKit

enum WorkoutAppleHealthMetrics {
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
      Metric(
        identifier: .cyclingSpeed, unit: .meter().unitDivided(by: .second()), unitName: "m/s", cumulative: false),
      Metric(
        identifier: .respiratoryRate, unit: .count().unitDivided(by: .minute()), unitName: "breaths/min",
        cumulative: false),
      Metric(identifier: .oxygenSaturation, unit: .percent(), unitName: "%", cumulative: false),
      Metric(
        identifier: .heartRateVariabilitySDNN, unit: .secondUnit(with: .milli), unitName: "ms", cumulative: false),
      Metric(
        identifier: .physicalEffort,
        unit: .kilocalorie().unitDivided(by: .gramUnit(with: .kilo).unitMultiplied(by: .hour())),
        unitName: "kcal/(kg*hr)", cumulative: false),
      Metric(identifier: .cyclingFunctionalThresholdPower, unit: .watt(), unitName: "W", cumulative: false),
    ]
    if #available(watchOS 11.0, *) {
      metrics.append(
        Metric(
          identifier: .workoutEffortScore, unit: .appleEffortScore(), unitName: "appleEffortScore", cumulative: false)
      )
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
    let quantities = Set(quantityTypes.map { $0 as HKObjectType })
    #if os(watchOS)
      return quantities
    #else
      return quantities.union([HKObjectType.workoutType()])
    #endif
  }
  static var shareTypes: Set<HKSampleType> {
    let identifiers: [HKQuantityTypeIdentifier] = [
      .heartRate, .activeEnergyBurned, .basalEnergyBurned,
      .distanceCycling, .cyclingPower, .cyclingCadence, .cyclingSpeed,
    ]
    return Set(identifiers.compactMap { HKQuantityType.quantityType(forIdentifier: $0) as HKSampleType? })
      .union([HKObjectType.workoutType(), HKSeriesType.workoutRoute()])
  }

}
