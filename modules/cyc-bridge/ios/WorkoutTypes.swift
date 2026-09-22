import Foundation
import CryptoKit

enum WorkoutDataError: Error, LocalizedError {
  case invalid(String)
  var errorDescription: String? {
    if case .invalid(let message) = self { return message }
    return "Invalid workout data"
  }
}

/// JSON values are retained without linking the archive to CoreLocation, HealthKit or CYC.
enum WorkoutJSON: Codable, Equatable {
  case integer(Int64), unsigned(UInt64), number(Double), string(String), bool(Bool), object([String: WorkoutJSON]), array([WorkoutJSON]), null
  init(from decoder: Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() { self = .null }
    else if let v = try? c.decode(Bool.self) { self = .bool(v) }
    else if let v = try? c.decode(Int64.self) { self = .integer(v) }
    else if let v = try? c.decode(UInt64.self) { self = .unsigned(v) }
    else if let v = try? c.decode(Double.self), v.isFinite { self = .number(v) }
    else if let v = try? c.decode(String.self) { self = .string(v) }
    else if let v = try? c.decode([String: WorkoutJSON].self) { self = .object(v) }
    else if let v = try? c.decode([WorkoutJSON].self) { self = .array(v) }
    else { throw WorkoutDataError.invalid("Unsupported JSON value") }
  }
  func encode(to encoder: Encoder) throws {
    var c = encoder.singleValueContainer()
    switch self {
    case .integer(let v): try c.encode(v)
    case .unsigned(let v): try c.encode(v)
    case .number(let v): try c.encode(v)
    case .string(let v): try c.encode(v)
    case .bool(let v): try c.encode(v)
    case .object(let v): try c.encode(v)
    case .array(let v): try c.encode(v)
    case .null: try c.encodeNil()
    }
  }
  /// Only analytical consumers use this conversion. Original integer storage never does.
  var number: Double? {
    switch self { case .number(let v): return v; case .integer(let v): return Double(v); case .unsigned(let v): return Double(v); default: return nil }
  }
  var integer: Int64? { if case .integer(let value) = self { return value }; return nil }
  var string: String? { if case .string(let value) = self { return value }; return nil }
  var any: Any {
    switch self {
    case .integer(let v): return v
    case .unsigned(let v): return v
    case .number(let v): return v
    case .string(let v): return v
    case .bool(let v): return v
    case .object(let v): return v.mapValues(\.any)
    case .array(let v): return v.map(\.any)
    case .null: return NSNull()
    }
  }
}

enum WorkoutCoding {
  private final class Dates: @unchecked Sendable {
    let lock = NSLock()
    let fractional = ISO8601DateFormatter()
    let whole = ISO8601DateFormatter()
    init() {
      fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
      whole.formatOptions = [.withInternetDateTime]
    }
  }
  private static let dates = Dates()
  private static let millisecondDates = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
  static func encoder() -> JSONEncoder {
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    return encoder
  }
  static func timestamp(_ date: Date) -> String {
    dates.lock.lock(); defer { dates.lock.unlock() }
    return dates.fractional.string(from: date)
  }
  static func date(_ string: String) throws -> Date {
    guard string.count <= 40, string.hasSuffix("Z") || string.hasSuffix("+00:00") else {
      throw WorkoutDataError.invalid("Workout timestamp must specify UTC")
    }
    // The native writer's fixed millisecond form dominates capture and transfer.
    // FormatStyle parses it without the shared formatter lock. Normalize via epoch
    // milliseconds to match ISO8601DateFormatter's exact Date rounding; simply
    // returning FormatStyle's Date can shift the indexed microsecond by one.
    let bytes = Array(string.utf8)
    if bytes.count == 24, bytes[4] == 45, bytes[7] == 45, bytes[10] == 84,
      bytes[13] == 58, bytes[16] == 58, bytes[19] == 46, bytes[23] == 90,
      (0..<24).contains(Int(bytes[11]) * 10 + Int(bytes[12]) - 528),
      (0..<60).contains(Int(bytes[14]) * 10 + Int(bytes[15]) - 528),
      (0..<60).contains(Int(bytes[17]) * 10 + Int(bytes[18]) - 528),
      let parsed = try? millisecondDates.parse(string) {
      let seconds = (parsed.timeIntervalSince1970 * 1000).rounded() / 1000
      if seconds >= 946684800, seconds < 4102444800 { return Date(timeIntervalSince1970: seconds) }
    }
    dates.lock.lock(); defer { dates.lock.unlock() }
    let result = dates.fractional.date(from: string) ?? dates.whole.date(from: string)
    guard let date = result, date.timeIntervalSince1970 >= 946684800,
          date.timeIntervalSince1970 < 4102444800 else {
      throw WorkoutDataError.invalid("Invalid workout timestamp")
    }
    return date
  }
  static func id(_ string: String) throws -> String {
    guard let id = UUID(uuidString: string) else { throw WorkoutDataError.invalid("Invalid workout identifier") }
    return id.uuidString.lowercased()
  }
  static func dictionary<T: Encodable>(_ value: T) -> [String: Any] {
    guard let data = try? encoder().encode(value),
          let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
    return value
  }
}

struct WorkoutEvent: Codable, Equatable {
  var schemaVersion: Int = 1
  var eventId: String
  var workoutId: String
  var kind: String
  var source: String
  var timestamp: String
  var elapsedSeconds: Double?
  var payload: [String: WorkoutJSON]

  /// Reconstruct only previously validated canonical database rows. Keep the original
  /// timestamp spelling and absent elapsed value; input admission still calls validate().
  init(storedEventID: String, workoutID: String, kind: String, source: String,
       originalTimestamp: String, elapsedSeconds: Double?, payload: [String: WorkoutJSON]) {
    self.eventId = storedEventID; self.workoutId = workoutID
    self.kind = kind; self.source = source; self.timestamp = originalTimestamp
    self.elapsedSeconds = elapsedSeconds; self.payload = payload
  }

  init(dictionary: [String: Any]) throws {
    guard JSONSerialization.isValidJSONObject(dictionary) else { throw WorkoutDataError.invalid("Workout event is not finite JSON") }
    let data = try JSONSerialization.data(withJSONObject: dictionary)
    guard data.count <= 32_768 else { throw WorkoutDataError.invalid("Workout event exceeds 32 KiB") }
    self = try JSONDecoder().decode(Self.self, from: data)
    try validate()
    eventId = try WorkoutCoding.id(eventId); workoutId = try WorkoutCoding.id(workoutId)
    // Retain the original UTC spelling/precision. The indexed microsecond key is derived.
  }
  init(workoutId: String, kind: String, source: String, timestamp: Date,
       elapsedSeconds: Double? = nil, payload: [String: WorkoutJSON], eventId: String = UUID().uuidString) throws {
    self.eventId = try WorkoutCoding.id(eventId); self.workoutId = try WorkoutCoding.id(workoutId)
    self.kind = kind; self.source = source; self.timestamp = WorkoutCoding.timestamp(timestamp)
    self.elapsedSeconds = elapsedSeconds; self.payload = payload
    try validate()
  }
  var dictionary: [String: Any] { WorkoutCoding.dictionary(self) }
  var date: Date { get throws { try WorkoutCoding.date(timestamp) } }
  func number(_ key: String) -> Double? { payload[key]?.number }
  @discardableResult
  func validate() throws -> Int {
    guard schemaVersion == 1 else { throw WorkoutDataError.invalid("Unsupported workout event version") }
    _ = try WorkoutCoding.id(eventId); _ = try WorkoutCoding.id(workoutId); _ = try WorkoutCoding.date(timestamp)
    guard ["telemetry", "location", "health", "lifecycle"].contains(kind),
          ["cyc", "phone", "watch"].contains(source), payload.count <= 64 else {
      throw WorkoutDataError.invalid("Invalid workout event kind/source/payload")
    }
    if let elapsedSeconds, !elapsedSeconds.isFinite || elapsedSeconds < 0 || elapsedSeconds > 2_678_400 {
      throw WorkoutDataError.invalid("Invalid workout elapsed time")
    }
    func validateJSON(_ value: WorkoutJSON, depth: Int) throws {
      guard depth <= 8 else { throw WorkoutDataError.invalid("Workout JSON nesting exceeds limit") }
      switch value {
      case .number(let n): guard n.isFinite, abs(n) <= 1e15 else { throw WorkoutDataError.invalid("Invalid workout number") }
      case .string(let s): guard s.utf8.count <= 4096 else { throw WorkoutDataError.invalid("Workout string exceeds limit") }
      case .object(let o): guard o.count <= 128 else { throw WorkoutDataError.invalid("Workout object exceeds limit") }; for v in o.values { try validateJSON(v, depth: depth + 1) }
      case .array(let a): guard a.count <= 256 else { throw WorkoutDataError.invalid("Workout array exceeds limit") }; for v in a { try validateJSON(v, depth: depth + 1) }
      default: break
      }
    }
    try validateJSON(.object(payload), depth: 0)
    switch kind {
    case "telemetry":
      guard source == "cyc", number("humanPowerW") != nil, number("cadenceRpm") != nil else { throw WorkoutDataError.invalid("Telemetry requires CYC rider power and cadence") }
    case "location":
      guard source != "cyc", let lat = number("latitude"), let lon = number("longitude"),
            (-90...90).contains(lat), (-180...180).contains(lon) else { throw WorkoutDataError.invalid("Invalid workout coordinates") }
    case "health":
      guard source != "cyc", !payload.isEmpty else { throw WorkoutDataError.invalid("Invalid health event") }
      for key in ["activeEnergyKcal", "basalEnergyKcal", "distanceMeters"] {
        if let n = number(key), n < 0 { throw WorkoutDataError.invalid("Negative cumulative health quantity") }
      }
    case "lifecycle":
      guard source != "cyc", let action = payload["action"]?.string,
            ["start", "pause", "resume", "lap", "stop"].contains(action) else { throw WorkoutDataError.invalid("Invalid lifecycle action") }
    default: break
    }
    let bytes = try WorkoutCoding.encoder().encode(self).count
    guard bytes <= 32_768 else { throw WorkoutDataError.invalid("Workout event exceeds 32 KiB") }
    return bytes
  }
}

struct WorkoutMetadata: Codable {
  var schemaVersion = 1
  var id: String
  var startedAt: String
  var endedAt: String?
  var stopElapsedSeconds: Double?
  var phase = "running"
  var indoor: Bool
  var watchEnabled: Bool
  // Optional storage preserves legacy decoding; new rides always freeze explicit values.
  var saveToHealth: Bool? = nil
  var recordGPS: Bool? = nil
  var savesToHealth: Bool { saveToHealth ?? true }
  var recordsGPS: Bool { recordGPS ?? !indoor }
  var sport = "cycling"
  var subSport = "e_biking"
  var eventCount = 0
  var interrupted = false
  var healthKitState = "notSaved"
  var healthKitUUID: String?
  var watchSyncState: String?
  var warnings: [String] = []
  var collectionRevision: Int64?
  var sealRevision: Int64?
  var verifiedSealRevision: Int64?
  var finalizationState: String?
  var example: Bool?
  var sealVerified: Bool { (sealRevision ?? 0) > 0 && verifiedSealRevision == sealRevision }
  var dictionary: [String: Any] {
    var result = WorkoutCoding.dictionary(self)
    result["saveToHealth"] = savesToHealth; result["recordGPS"] = recordsGPS
    result["watchSyncState"] = watchSyncState ?? (watchEnabled ? "pending" : "notRequired")
    return result
  }
}

struct WorkoutSummary: Codable {
  var schemaVersion = 1
  var id: String
  var startedAt: String
  var endedAt: String
  var elapsedSeconds: Double = 0
  var timerSeconds: Double = 0
  var distanceMeters: Double?
  var gpsDistanceMeters: Double?
  var healthDistanceMeters: Double?
  var healthDistanceProvisional: Bool?
  var healthDistanceSource: String?
  var healthDistanceReportedAt: String?
  var distance: [String: WorkoutJSON]?
  var averageSpeedMps: Double?
  var maximumSpeedMps: Double?
  var ascentMeters: Double?
  var descentMeters: Double?
  var averageHeartRateBpm: Double?
  var maximumHeartRateBpm: Double?
  var averageRiderPowerW: Double?
  var maximumRiderPowerW: Double?
  var averageCadenceRpm: Double?
  var maximumCadenceRpm: Double?
  var activeEnergyKcal: Double?
  var basalEnergyKcal: Double?
  var riderWorkJoules: Double?
  var telemetryCoveredSeconds: Double = 0
  var heartRateCoveredSeconds: Double = 0
  var eventCount = 0
  var telemetryCount = 0
  var locationCount = 0
  var healthCount = 0
  var lapCount = 0
  var routePreview: [[String: Double]] = []
  var warnings: [String] = []
  var provenance: [String: String] = [:]
  var completeness: [String: String] = [:]
  var dictionary: [String: Any] { WorkoutCoding.dictionary(self) }
}

/// Frozen collection intent is consulted again at every delayed native Health effect.
enum WorkoutRecordingPolicy {
  static func sampleHz(_ value: Double) throws -> Double {
    guard value.isFinite, [2.0, 4.0, 8.0].contains(value) else { throw WorkoutDataError.invalid("Choose 2, 4, or 8 samples per second.") }
    return value
  }
  static func requireHealthWrite(id: String, archive: WorkoutArchive) throws {
    let metadata = try archive.metadata(id: id)
    guard metadata.savesToHealth, metadata.healthKitState != "notRequested", metadata.healthKitState != "discarded" else {
      throw WorkoutDataError.invalid("Health saving was not requested for this ride")
    }
  }
  static func requireOptions(_ metadata: WorkoutMetadata, saveToHealth: Bool, recordGPS: Bool) throws {
    guard metadata.savesToHealth == saveToHealth, metadata.recordsGPS == recordGPS else {
      throw WorkoutDataError.invalid("Recording options cannot change after a ride starts")
    }
  }
}

/// New connection defaults cannot change the rate admitted by an active ride.
struct WorkoutSamplingOwner {
  private(set) var id: String?
  private(set) var rate: Double?
  mutating func update(id: String?, rate: Double) {
    guard self.id != id else { return }
    self.id = id; self.rate = id == nil ? nil : rate
  }
  func connectionRate(_ requested: Double) -> Double { rate ?? requested }
}

/// Parent Health samples may span the ride boundary; retain only their actual in-window series points.
struct WorkoutSensorWindow {
  let start: Date
  let cutoff: Date
  func contains(start sampleStart: Date, end sampleEnd: Date) -> Bool { sampleStart >= start && sampleEnd <= cutoff && sampleEnd >= sampleStart }
}

/// Deterministic external operation identity, without truncating the durable replay ledger.
enum WorkoutStableIdentity {
  static func uuid(_ value: String) -> String {
    var bytes = Array(SHA256.hash(data: Data(value.utf8)).prefix(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x50; bytes[8] = (bytes[8] & 0x3f) | 0x80
    return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15])).uuidString.lowercased()
  }
}
