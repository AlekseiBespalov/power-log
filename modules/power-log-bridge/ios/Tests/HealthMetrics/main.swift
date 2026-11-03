import Foundation
import HealthKit

func check(_ condition: Bool, _ message: String) { precondition(condition, message) }
let workout = HKObjectType.workoutType().identifier
let writes = Set(WorkoutAppleHealthMetrics.shareTypes.map(\.identifier))
let written = Set(
  [
    HKQuantityTypeIdentifier.heartRate, .activeEnergyBurned, .basalEnergyBurned, .distanceCycling, .cyclingPower,
    .cyclingCadence, .cyclingSpeed,
  ].map(\.rawValue)
).union([workout, HKSeriesType.workoutRoute().identifier])
check(writes == written, "Health write access names only what the writer and its workout session save: \(writes)")
let reads = Set(WorkoutAppleHealthMetrics.readTypes.map(\.identifier))
let measurements = Set(WorkoutAppleHealthMetrics.supported.map(\.identifier.rawValue))
check(reads == measurements.union([workout]), "iPhone reads ride measurements and workouts, never routes: \(reads)")
print("Apple Health access: \(writes.count) written and \(reads.count) read types passed")
