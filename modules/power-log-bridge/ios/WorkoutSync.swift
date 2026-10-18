import Foundation
import PowerLogCore

enum WorkoutCoreCodec {
  static func bytes(_ data: Data) -> KotlinByteArray { AppleBytes.shared.bytes(data: data) }
  static func data(_ bytes: KotlinByteArray) -> Data { AppleBytes.shared.data(bytes: bytes) }
  static func array<T: AnyObject>(_ values: [T]) -> KotlinArray<T> {
    KotlinArray<T>(size: Int32(values.count)) { values[Int($0.intValue)] }
  }
  static func values<T: AnyObject>(_ array: KotlinArray<T>) -> [T] {
    (0..<array.size).map { array.get(index: $0)! }
  }
  static func time(_ text: String) throws -> ProtocolTimestamp { try Timestamps.shared.parse(original: text) }
  static func event(_ value: WorkoutEvent) throws -> Event {
    try WatchProtocol.shared.decodeEvent(bytes: bytes(WorkoutCoding.encoder().encode(value)), rideId: value.workoutId)
  }
  static func event(_ value: Event) throws -> WorkoutEvent {
    try JSONDecoder().decode(WorkoutEvent.self, from: Data(value.canonicalUtf8.utf8))
  }
  static func telemetryPayload(_ sample: [String: Any]) throws -> [String: Any] {
    var payload = sample
    if let sequence = sample["sequence"] as? NSNumber {
      let value = try JSONDecoder().decode(
        WorkoutJSON.self, from: JSONSerialization.data(withJSONObject: sequence, options: [.fragmentsAllowed]))
      switch value {
      case .integer(let number): payload["sequence"] = String(number)
      case .unsigned(let number): payload["sequence"] = String(number)
      default: throw WorkoutDataError.invalid("Native telemetry sequence is not an integer")
      }
    }
    return payload
  }
}

struct WorkoutRideFailure: Error, LocalizedError, Equatable {
  let code: String
  init(_ code: String) { self.code = Failures.shared.present(code: code).name }
  var message: String { Failures.shared.present(code: code).text }
  var errorDescription: String? { message }
  var wire: [String: String] { ["code": code, "message": message] }

  /// The vocabulary code for a native failure; `stage` names the operation that failed.
  static func classify(_ error: Error, stage: String? = nil) -> Self {
    if let failure = error as? Self { return failure }
    if stage == "watchLaunch" { return Self("watchLaunchFailed") }
    if let text = (error as? LocalizedError)?.errorDescription, RideFailure.entries.contains(where: { $0.name == text })
    {
      return Self(text)
    }
    switch error {
    case PowerLogStorageError.busy: return Self("storageBusy")
    case PowerLogStorageError.deleted: return Self("rideDeleted")
    case PowerLogStorageError.sqlite(13, _): return Self("storageFull")
    case PowerLogStorageError.sqlite: return Self("storageFailed")
    default: break
    }
    let native = error as NSError
    if native.domain == NSCocoaErrorDomain, native.code == NSFileWriteOutOfSpaceError { return Self("storageFull") }
    // HKError.errorAuthorizationDenied (4) and .errorDatabaseInaccessible (6).
    if native.domain == "com.apple.healthkit", native.code == 4 { return Self("healthDenied") }
    if native.domain == "com.apple.healthkit", native.code == 6 { return Self("healthLocked") }
    switch stage {
    case "storage": return Self("storageFailed")
    case "session": return Self("sessionUnavailable")
    case "start": return Self("startInterrupted")
    case "bike": return Self("bikeUnavailable")
    default: return Self("unknown")
    }
  }
}
