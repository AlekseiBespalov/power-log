import Foundation

enum WatchProtocol {
  static let version = 1
  static let maxMessageBytes = 60_000
  static let maxQuantities = 32
  static let maxRoutePoints = 32
  static let maxLaps = 256
  static let maxHealthSamples = 64
  static let rideIntervalUs: Int64 = 2_000_000
  static let routineMinIntervalUs: Int64 = 1_000_000
  static let commandExpiryUs: Int64 = 5_000_000
  static let resendUs: Int64 = 1_000_000
  static let replayUs: Int64 = 60_000_000
  static let startWindowUs: Int64 = 15_000_000
  static let heartRateDisplayHoldUs: Int64 = 15_000_000
  static let ownSyncIDPrefix = "powerlog:"
}

enum RideAction: String, Codable { case pause, resume, lap, finish, discard }
enum WatchPhase: String, Codable { case preparing, running, paused, interrupted, completed, discarded }
enum UnitSystem: String, Codable { case metric, imperial }
enum DistanceSource: String, Codable {
  case controller
  case phoneGps = "gps:phone"
}
enum HealthOutcome: String, Codable {
  case pending, saved, failed, notSaved, unavailable, unknown, discarded, notRequested
}
enum HealthReason: String, Codable {
  case permission, locked, quota, invalid, ambiguous, builderLost, readbackUnavailable, noValidReading
  case transientFailure, clock
}
enum TimerState: String, Codable { case running, frozen, unavailable }

struct RideTimer: Codable, Equatable {
  let state: TimerState
  let baseUs: Int64
  let originUtcUs: Int64?
}

struct WatchStartOptions: Codable, Equatable {
  let indoor: Bool
  let saveToHealth: Bool
  let recordGps: Bool?
}

struct WatchRideOptions: Codable, Equatable {
  let indoor: Bool
  let saveToHealth: Bool
  let recordGps: Bool
  let distanceSource: DistanceSource
}

struct WorkoutIDs: Codable, Equatable {
  let syncID: String
  let syncVersion: Int
  private enum CodingKeys: String, CodingKey {
    case syncID = "syncId"
    case syncVersion
  }
}

struct WatchDisplay: Codable, Equatable {
  let powerW: Double?
  let cadenceRpm: Double?
  let distanceM: Double?
  let freshUntilUtcUs: Int64
}

struct IndexedRoutePoint: Codable, Equatable {
  let index: Int64
  let point: RoutePoint
}

struct StartRequestMessage: Codable, Equatable {
  let requestID: String
  let options: WatchStartOptions
  private enum CodingKeys: String, CodingKey {
    case requestID = "requestId"
    case options
  }
}

struct CancelStartMessage: Codable, Equatable {
  let eventID: String?
  let requestID: String?
  let rideID: String?
  private enum CodingKeys: String, CodingKey {
    case eventID = "eventId"
    case requestID = "requestId"
    case rideID = "rideId"
  }
}

struct CommandMessage: Codable, Equatable {
  let rideID: String
  let action: RideAction
  let expiresUtcUs: Int64
  private enum CodingKeys: String, CodingKey {
    case rideID = "rideId"
    case action, expiresUtcUs
  }
}

struct HealthSamplesMessage: Codable, Equatable {
  let rideID: String
  let throughSequence: Int64
  let samples: [HealthOriginal]
  let heartRate: LiveHeartRate?
  private enum CodingKeys: String, CodingKey {
    case rideID = "rideId"
    case throughSequence, samples, heartRate
  }
}

struct HealthOutcomeMessage: Codable, Equatable {
  let rideID: String
  let outcome: HealthOutcome
  let workoutID: String?
  let reason: HealthReason?
  private enum CodingKeys: String, CodingKey {
    case rideID = "rideId"
    case outcome
    case workoutID = "workoutId"
    case reason
  }
}

struct StartMessage: Codable, Equatable {
  let eventID: String
  let requestID: String?
  let rideID: String
  let startedUtcUs: Int64
  let expiresUtcUs: Int64
  let options: WatchRideOptions
  let workout: WorkoutIDs
  private enum CodingKeys: String, CodingKey {
    case eventID = "eventId"
    case requestID = "requestId"
    case rideID = "rideId"
    case startedUtcUs, expiresUtcUs, options, workout
  }
}

struct RideMessage: Codable, Equatable {
  let rideID: String
  let stateRevision: Int64
  let phase: WatchPhase
  let timer: RideTimer
  let lapsUtcUs: [Int64]
  let lapCount: Int
  let units: UnitSystem
  let display: WatchDisplay?
  let quantities: [HealthPlanQuantity]
  let routeStride: Int
  let route: [IndexedRoutePoint]
  let endedUtcUs: Int64?
  private enum CodingKeys: String, CodingKey {
    case rideID = "rideId"
    case stateRevision, phase, timer, lapsUtcUs, lapCount, units, display, quantities, routeStride, route, endedUtcUs
  }
}

struct AckMessage: Codable, Equatable {
  let error: String?
  let acknowledgedSequence: Int64?

  init(error: String? = nil, acknowledgedSequence: Int64? = nil) {
    self.error = error
    self.acknowledgedSequence = acknowledgedSequence
  }
}

enum WatchMessage: Equatable {
  case startRequest(StartRequestMessage)
  case cancelStart(CancelStartMessage)
  case command(CommandMessage)
  case healthSamples(HealthSamplesMessage)
  case healthOutcome(HealthOutcomeMessage)
  case start(StartMessage)
  case ride(RideMessage)
  case ack(AckMessage)

  var kind: String {
    switch self {
    case .startRequest: "startRequest"
    case .cancelStart: "cancelStart"
    case .command: "command"
    case .healthSamples: "healthSamples"
    case .healthOutcome: "healthOutcome"
    case .start: "start"
    case .ride: "ride"
    case .ack: "ack"
    }
  }

  fileprivate var fields: Encodable {
    switch self {
    case .startRequest(let message): message
    case .cancelStart(let message): message
    case .command(let message): message
    case .healthSamples(let message): message
    case .healthOutcome(let message): message
    case .start(let message): message
    case .ride(let message): message
    case .ack(let message): message
    }
  }
}

enum WatchDecodeResult: Equatable {
  case valid(WatchMessage)
  case versionMismatch
  case invalid
}

enum WatchMessages {
  static func encode(_ message: WatchMessage) -> Data? {
    guard let data = unlimited(message), data.count <= WatchProtocol.maxMessageBytes else { return nil }
    return data
  }

  static func decode(_ data: Data) -> WatchDecodeResult {
    guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return .invalid }
    guard let version = object["v"] else { return .invalid }
    guard isVersion(version) else { return .versionMismatch }
    guard let kind = object["kind"] as? String, let message = message(kind, data) else { return .invalid }
    guard let encoded = unlimited(message), let written = try? JSONSerialization.jsonObject(with: encoded),
      known(object, within: written)
    else { return .invalid }
    return .valid(message)
  }

  // Kotlin's codec accepts only the integer literal 1; JSONSerialization keeps 1.0 a double and true a Boolean.
  private static func isVersion(_ value: Any) -> Bool {
    guard let number = value as? NSNumber, CFGetTypeID(number) == CFNumberGetTypeID(), !CFNumberIsFloatType(number)
    else { return false }
    return number.int64Value == Int64(WatchProtocol.version)
  }

  private static func unlimited(_ message: WatchMessage) -> Data? {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    return try? encoder.encode(Envelope(message: message))
  }

  private static func message(_ kind: String, _ data: Data) -> WatchMessage? {
    let decoder = JSONDecoder()
    switch kind {
    case "startRequest": return (try? decoder.decode(StartRequestMessage.self, from: data)).map { .startRequest($0) }
    case "cancelStart": return (try? decoder.decode(CancelStartMessage.self, from: data)).map { .cancelStart($0) }
    case "command": return (try? decoder.decode(CommandMessage.self, from: data)).map { .command($0) }
    case "healthSamples": return (try? decoder.decode(HealthSamplesMessage.self, from: data)).map { .healthSamples($0) }
    case "healthOutcome": return (try? decoder.decode(HealthOutcomeMessage.self, from: data)).map { .healthOutcome($0) }
    case "start": return (try? decoder.decode(StartMessage.self, from: data)).map { .start($0) }
    case "ride": return (try? decoder.decode(RideMessage.self, from: data)).map { .ride($0) }
    case "ack": return (try? decoder.decode(AckMessage.self, from: data)).map { .ack($0) }
    default: return nil
    }
  }

  // Decoding ignores keys a type doesn't know, so a received object must not hold a key its own re-encoding lacks;
  // an explicit null stands for an absent optional field.
  private static func known(_ received: Any, within written: Any) -> Bool {
    if let object = received as? [String: Any] {
      guard let fields = written as? [String: Any] else { return false }
      return object.allSatisfy { key, value in
        if let match = fields[key] { return known(value, within: match) }
        return value is NSNull
      }
    }
    if let items = received as? [Any] {
      guard let written = written as? [Any], items.count == written.count else { return false }
      return zip(items, written).allSatisfy { known($0, within: $1) }
    }
    return true
  }

  private struct Envelope: Encodable {
    let message: WatchMessage

    private enum CodingKeys: String, CodingKey { case v, kind }

    func encode(to encoder: Encoder) throws {
      try message.fields.encode(to: encoder)
      var envelope = encoder.container(keyedBy: CodingKeys.self)
      try envelope.encode(WatchProtocol.version, forKey: .v)
      try envelope.encode(message.kind, forKey: .kind)
    }
  }
}

enum HealthInstant {
  static func date(_ us: Int64) -> Date { Date(timeIntervalSince1970: Double(us) / 1_000_000) }

  static func us(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1_000_000).rounded()) }
}
