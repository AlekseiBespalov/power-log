// Host substitutes only platform IO. WorkoutEngine, capture, archive and lifecycle code remain production sources.
import Foundation
import CoreLocation

final class HKHealthStore {
  static func isHealthDataAvailable() -> Bool { true }
  func startWatchApp(with configuration: HKWorkoutConfiguration, completion: (Bool, Error?) -> Void) {
    completion(true, nil)
  }
}
final class HKWorkoutConfiguration {
  enum Activity { case cycling }
  enum Location { case indoor, outdoor }
  var activityType = Activity.cycling
  var locationType = Location.indoor
}
final class WorkoutCompanionTransport {
  static var latest: WorkoutCompanionTransport!
  var reachable = true
  var installed = true
  var received: ((WorkoutCompanionMessage, @escaping (WorkoutCompanionMessage) -> Void) -> Void)?
  var changed: (() -> Void)?
  var sent: [WorkoutCompanionMessage] = []
  var deferStart = false
  var startError: Error?
  var startCompletion: ((Result<WorkoutCompanionMessage, Error>) -> Void)?
  init(queue: DispatchQueue) { Self.latest = self }
  func send(_ message: WorkoutCompanionMessage, completion: ((Result<WorkoutCompanionMessage, Error>) -> Void)? = nil) {
    sent.append(message)
    guard reachable else {
      completion?(.failure(WorkoutCompanionError.invalid("Disconnected")))
      return
    }
    if message.kind == .start, let startError {
      completion?(.failure(startError))
      return
    }
    if message.kind == .start && deferStart {
      startCompletion = completion
      return
    }
    completion?(
      .success(
        WorkoutCompanionMessage(
          kind: .state, rideID: message.rideID, commandID: message.commandID,
          phase: message.kind == .start ? "running" : nil)))
  }
}
final class WorkoutNativeHealth {
  static var latest: WorkoutNativeHealth!
  let store = HKHealthStore()
  var busy = false
  var authorizationRequests = 0
  var startRequests = 0
  var permissions: [String: Any] { [:] }
  var onPause: (() -> Void)?
  var observation: ((String, WorkoutCompanionObservation) -> Void)?
  var status: ((String, String, String?, String?) -> Void)?
  var liveReadings: ((String, [String: Double], Date) -> Void)?
  init(queue: DispatchQueue) { Self.latest = self }
  func authorize(writing: Bool = true, completion: (Result<Void, Error>) -> Void) {
    authorizationRequests += 1
    completion(.success(()))
  }
  func start(_ options: WorkoutCompanionOptions, completion: (Result<Void, Error>) -> Void) {
    startRequests += 1
    busy = true
    completion(.success(()))
  }
  func recoverForCleanup(completion: (String?) -> Void) { completion(nil) }
  func pause() { onPause?() }
  func resume() {}
  func lap(at: Date) {}
  func recordLocations(_ locations: [WorkoutCompanionLocation]) {}
  func publish(readings: [String: Double], at: Date, locations: [WorkoutCompanionLocation]) {}
  func finish(at: Date, discard: Bool, completion: () -> Void) {
    busy = false
    completion()
  }
  func readback(
    start: Date, end: Date, receive: ([WorkoutCompanionObservation]) throws -> Void,
    completion: (Result<Void, Error>) -> Void
  ) { completion(.success(())) }
}
final class WorkoutLocation {
  static var latest: WorkoutLocation!
  var permissionRequests = 0
  var deferPermission = false
  var permissionCompletion: ((String) -> Void)?
  var onLocation: (([CLLocation], Date, Double) -> Void)?
  var onStatus: ((String) -> Void)?
  init() { Self.latest = self }
  func permissionStatus(_ completion: ([String: Any]) -> Void) { completion([:]) }
  func requestPermission(_ completion: @escaping (String) -> Void) {
    permissionRequests += 1
    if deferPermission { permissionCompletion = completion } else { completion("authorized") }
  }
  var paused: Bool?
  func start() {}
  func setPaused(_ paused: Bool) { self.paused = paused }
  func stop() {}
}
final class ControllerEngine {
  static let shared = ControllerEngine()
  let queue = DispatchQueue(label: "test.controller")
  var status = "idle"
  var requestedRates: [Double] = []
  var samplingOwner: String?
  func state() -> [String: Any] { ["status": status] }
  func setSampleRate(_ rate: Double) throws { requestedRates.append(rate) }
  func setWorkoutSamplingOwner(_ id: String?, rate: Double?) { samplingOwner = id }
  func captureResult(_ error: Error?) {}
}
final class WorkoutLiveActivity {
  static let shared = WorkoutLiveActivity()
  private let lock = NSLock()
  private var counts: [String: Int] = [:]
  var publishes = true
  func needsPublication(_ key: WorkoutActivityKey) -> Bool { publishes }
  func publish(_ snapshot: WorkoutActivitySnapshot) {
    guard let id = snapshot.rideID else { return }
    lock.lock()
    defer { lock.unlock() }
    counts[id, default: 0] += 1
  }
  func published(_ id: String) -> Int {
    lock.lock()
    defer { lock.unlock() }
    return counts[id, default: 0]
  }
}
