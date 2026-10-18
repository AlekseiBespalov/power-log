import Foundation
import HealthKit
import CoreLocation

@MainActor
enum PowerLogWatchLab {
  static var isRequested: Bool { UserDefaults.standard.string(forKey: "PowerLogLabCase")?.hasPrefix("watch-") == true }
  static func observeRestart() {}
  static func run() async {
    let name = UserDefaults.standard.string(forKey: "PowerLogLabCase") ?? ""
    let run = UserDefaults.standard.string(forKey: "PowerLogLabRun") ?? ""
    guard run.range(of: "^[a-zA-Z0-9_-]{1,120}$", options: .regularExpression) != nil else { return }
    var result: [String: Any] = ["case": name, "run": run, "pid": ProcessInfo.processInfo.processIdentifier]
    do {
      guard let url = Bundle.main.url(forResource: "watch-build", withExtension: "json"),
        let stamp = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: String],
        let digest = stamp["sourceDigest"]
      else { throw WorkoutCompanionError.invalid("Missing Watch Lab source stamp.") }
      result["sourceDigest"] = digest
      let health = WorkoutNativeHealth(queue: .main)
      if name == "watch-cleanup" {
        let recovered: String? = await withCheckedContinuation { continuation in
          health.recoverForCleanup { continuation.resume(returning: $0) }
        }
        result["recoveredRide"] = recovered ?? NSNull()
      } else if name == "watch-health-save" {
        result["health"] = try await saveAndReadback(health)
      } else if name == "watch-native-finish" || name == "watch-cancel-start" {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
          health.authorize { continuation.resume(with: $0) }
        }
        let options = WorkoutCompanionOptions(
          id: UUID().uuidString.lowercased(), start: Date(), indoor: true, saveToHealth: false, recordGPS: false)
        if name == "watch-cancel-start" {
          var startSettled = false
          health.start(options) { _ in startSettled = true }
          await withCheckedContinuation { continuation in
            health.finish(at: Date(), discard: true) { continuation.resume() }
          }
          try await Task.sleep(nanoseconds: 500_000_000)
          guard startSettled && !health.busy else {
            throw WorkoutCompanionError.invalid("Cancelled native Start remained pending or restarted.")
          }
        } else {
          try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            health.start(options) { continuation.resume(with: $0) }
          }
        }
        health.pause()
        health.resume()
        health.lap(at: Date())
        await withCheckedContinuation { continuation in
          health.finish(at: Date(), discard: true) { continuation.resume() }
        }
        guard !health.busy else { throw WorkoutCompanionError.invalid("Native session remained busy after Finish.") }
      } else {
        throw WorkoutCompanionError.invalid("Unknown Watch Lab case.")
      }
      result["status"] = "passed"
    } catch {
      result["status"] = "error"
      result["error"] = error.localizedDescription
    }
    let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(
      "lab-results")
    do {
      try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
      try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]).write(
        to: root.appendingPathComponent(run + ".json"), options: .atomic)
    } catch { NSLog("Watch Lab result failed: %@", error.localizedDescription) }
  }
  private static func saveAndReadback(_ health: WorkoutNativeHealth) async throws -> [String: Any] {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      health.authorize { continuation.resume(with: $0) }
    }
    let start = Date(timeIntervalSince1970: floor(Date().timeIntervalSince1970) - 4)
    let options = WorkoutCompanionOptions(
      id: UUID().uuidString.lowercased(), start: start, indoor: false,
      saveToHealth: true, recordGPS: true, distanceSource: "gps:phone")
    var status = "pending"
    var savedID: String?
    health.status = { _, value, id, _ in
      status = value
      if let id { savedID = id }
    }
    let own = HKQuery.predicateForObjects(
      withMetadataKey: WorkoutNativeHealth.rideMetadataKey, allowedValues: [options.id])
    do {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        health.start(options) { continuation.resume(with: $0) }
      }
      health.publish(
        readings: [
          "riderPowerW": 137.25, "cadenceRpm": 81, "gpsSpeedMps": 5.5,
          "distanceDeltaMeters": 2.75, "distanceStartTime": start.timeIntervalSince1970,
          "distanceEndTime": start.addingTimeInterval(0.5).timeIntervalSince1970,
        ], at: start, locations: [])
      health.publish(
        readings: ["riderPowerW": 9_000, "cadenceRpm": 900, "gpsSpeedMps": 900],
        at: start.addingTimeInterval(1), locations: [])
      health.recordLocations(
        (0..<3).map { index in
          WorkoutCompanionLocation(
            timestamp: start.addingTimeInterval(Double(index)), latitude: 0,
            longitude: Double(index) * 0.00001, altitude: 0, accuracy: 3, verticalAccuracy: 3, speed: 1, course: 90)
        })
      health.lap(at: start.addingTimeInterval(2))
      await withCheckedContinuation { continuation in
        health.finish(at: Date(), discard: false) { continuation.resume() }
      }
      guard status == "saved", let savedID, let uuid = UUID(uuidString: savedID), !health.busy else {
        throw WorkoutCompanionError.invalid("Health save was not confirmed: \(status).")
      }
      let workouts = try await samples(
        health.store, type: HKObjectType.workoutType(), predicate: HKQuery.predicateForObject(with: uuid))
      guard let workout = workouts.first as? HKWorkout else {
        throw WorkoutCompanionError.invalid("Saved workout was not readable.")
      }
      var result: [String: Any] = ["workout": savedID]
      for (identifier, unit, expected) in [
        (HKQuantityTypeIdentifier.cyclingPower, HKUnit.watt(), 137.25),
        (.cyclingCadence, HKUnit.count().unitDivided(by: .minute()), 81.0),
        (.cyclingSpeed, HKUnit.meter().unitDivided(by: .second()), 5.5),
        (.distanceCycling, HKUnit.meter(), 2.75),
      ] {
        let values = try await samples(
          health.store, type: HKQuantityType.quantityType(forIdentifier: identifier)!, predicate: own
        )
        .compactMap { ($0 as? HKQuantitySample)?.quantity.doubleValue(for: unit) }
        guard values.count == 1, abs(values[0] - expected) < 0.000001 else {
          throw WorkoutCompanionError.invalid("Health quantity mismatch: \(identifier.rawValue) \(values).")
        }
        result[identifier.rawValue] = values[0]
      }
      let routes = try await samples(
        health.store, type: HKSeriesType.workoutRoute(), predicate: HKQuery.predicateForObjects(from: workout))
      guard routes.count == 1, let route = routes.first as? HKWorkoutRoute,
        workout.workoutEvents?.contains(where: { $0.type == .lap }) == true
      else {
        throw WorkoutCompanionError.invalid("Saved route or lap was missing.")
      }
      let points = try await locations(health.store, route: route)
      guard points.count == 3,
        points.enumerated().allSatisfy({ index, point in
          abs(point.coordinate.latitude) < 0.000000001
            && abs(point.coordinate.longitude - Double(index) * 0.00001) < 0.000000001
            && abs(point.timestamp.timeIntervalSince(start) - Double(index)) < 0.001
        })
      else { throw WorkoutCompanionError.invalid("Saved route coordinates or timestamps changed.") }
      result["routeCount"] = routes.count
      result["routePoints"] = points.count
      try await cleanup(health.store, predicate: own)
      return result
    } catch {
      try? await cleanup(health.store, predicate: own)
      throw error
    }
  }
  private static func samples(_ store: HKHealthStore, type: HKSampleType, predicate: NSPredicate) async throws
    -> [HKSample]
  {
    try await withCheckedThrowingContinuation { continuation in
      store.execute(
        HKSampleQuery(sampleType: type, predicate: predicate, limit: 100, sortDescriptors: nil) { _, samples, error in
          if let error { continuation.resume(throwing: error) } else { continuation.resume(returning: samples ?? []) }
        })
    }
  }
  private static func locations(_ store: HKHealthStore, route: HKWorkoutRoute) async throws -> [CLLocation] {
    try await withCheckedThrowingContinuation { continuation in
      var points: [CLLocation] = []
      store.execute(
        HKWorkoutRouteQuery(route: route) { _, batch, done, error in
          if let error {
            continuation.resume(throwing: error)
            return
          }
          points.append(contentsOf: batch ?? [])
          if done { continuation.resume(returning: points) }
        })
    }
  }
  private static func cleanup(_ store: HKHealthStore, predicate: NSPredicate) async throws {
    for type in WorkoutAppleHealthMetrics.shareTypes {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        store.deleteObjects(of: type, predicate: predicate) { success, _, error in
          if success {
            continuation.resume()
          } else {
            continuation.resume(throwing: error ?? WorkoutCompanionError.invalid("Synthetic Health cleanup failed."))
          }
        }
      }
    }
  }

}
