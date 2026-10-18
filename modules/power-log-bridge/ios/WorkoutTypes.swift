import Foundation
import CryptoKit
import PowerLogCore

enum WorkoutDataError: Error, LocalizedError {
  case invalid(String)
  var errorDescription: String? {
    if case .invalid(let message) = self { return message }
    return "Invalid workout data"
  }
}

enum WorkoutJSON: Codable, Equatable {
  case integer(Int64), unsigned(UInt64), number(Double), string(String), bool(Bool), object([String: WorkoutJSON]),
    array([WorkoutJSON]), null
  init(from decoder: Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() {
      self = .null
    } else if let v = try? c.decode(Bool.self) {
      self = .bool(v)
    } else if let v = try? c.decode(Int64.self) {
      self = .integer(v)
    } else if let v = try? c.decode(UInt64.self) {
      self = .unsigned(v)
    } else if let v = try? c.decode(Double.self), v.isFinite {
      self = .number(v)
    } else if let v = try? c.decode(String.self) {
      self = .string(v)
    } else if let v = try? c.decode([String: WorkoutJSON].self) {
      self = .object(v)
    } else if let v = try? c.decode([WorkoutJSON].self) {
      self = .array(v)
    } else {
      throw WorkoutDataError.invalid("Unsupported JSON value")
    }
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
    switch self {
    case .number(let v): return v
    case .integer(let v): return Double(v)
    case .unsigned(let v): return Double(v)
    default: return nil
    }
  }
  var integer: Int64? {
    if case .integer(let value) = self { return value }
    return nil
  }
  var string: String? {
    if case .string(let value) = self { return value }
    return nil
  }
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
    init() {
      fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    }
  }
  private static let dates = Dates()
  static func encoder() -> JSONEncoder {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    return encoder
  }
  static func timestamp(_ date: Date) -> String {
    dates.lock.lock()
    defer { dates.lock.unlock() }
    return dates.fractional.string(from: date)
  }
  static func preciseTimestamp(_ date: Date) -> String {
    let seconds = floor(date.timeIntervalSince1970)
    var nanos = Int64(((date.timeIntervalSince1970 - seconds) * 1_000_000_000).rounded())
    let whole = seconds + (nanos == 1_000_000_000 ? 1 : 0)
    if nanos == 1_000_000_000 { nanos = 0 }
    return String(timestamp(Date(timeIntervalSince1970: whole)).prefix(19)) + String(format: ".%09lldZ", nanos)
  }
  static func timestampMicroseconds(_ string: String) throws -> Int64 {
    try Timestamps.shared.parse(original: string).comparisonUs
  }

  static func date(_ string: String) throws -> Date {
    Date(timeIntervalSince1970: Double(try timestampMicroseconds(string)) / 1_000_000)
  }
  static func id(_ string: String) throws -> String {
    guard let id = UUID(uuidString: string) else { throw WorkoutDataError.invalid("Invalid workout identifier") }
    return id.uuidString.lowercased()
  }
  static func dictionary<T: Encodable>(_ value: T) -> [String: Any] {
    guard let data = try? encoder().encode(value),
      let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return [:] }
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

  init(
    storedEventID: String, workoutID: String, kind: String, source: String,
    originalTimestamp: String, elapsedSeconds: Double?, payload: [String: WorkoutJSON]
  ) throws {
    guard kind == "health" || elapsedSeconds != nil else {
      throw WorkoutDataError.invalid("Stored app observation has no collection elapsed time")
    }
    self.eventId = storedEventID
    self.workoutId = workoutID
    self.kind = kind
    self.source = source
    self.timestamp = originalTimestamp
    self.elapsedSeconds = elapsedSeconds
    self.payload = payload
  }

  init(dictionary: [String: Any]) throws {
    guard JSONSerialization.isValidJSONObject(dictionary) else {
      throw WorkoutDataError.invalid("Workout event is not finite JSON")
    }
    let data = try JSONSerialization.data(withJSONObject: dictionary)
    guard data.count <= 32_768 else { throw WorkoutDataError.invalid("Workout event exceeds 32 KiB") }
    self = try JSONDecoder().decode(Self.self, from: data)
    try validate()
    eventId = try WorkoutCoding.id(eventId)
    workoutId = try WorkoutCoding.id(workoutId)
    // Retain the original UTC spelling/precision. The indexed microsecond key is derived.
  }
  init(
    workoutId: String, kind: String, source: String, timestamp: Date,
    elapsedSeconds: Double? = nil, payload: [String: WorkoutJSON], eventId: String = UUID().uuidString
  ) throws {
    self.eventId = try WorkoutCoding.id(eventId)
    self.workoutId = try WorkoutCoding.id(workoutId)
    self.kind = kind
    self.source = source
    self.timestamp = WorkoutCoding.timestamp(timestamp)
    self.elapsedSeconds = elapsedSeconds
    self.payload = payload
    try validate()
  }
  /// Builds controller telemetry from a decoded capture sample without the event schema check.
  init(
    capturedEventID: String, workoutID: String, timestamp: String, elapsedSeconds: Double,
    payload: [String: WorkoutJSON]
  ) throws {
    guard elapsedSeconds.isFinite, (0...2_678_400).contains(elapsedSeconds), payload.count <= 64 else {
      throw WorkoutDataError.invalid("Invalid captured telemetry")
    }
    eventId = try WorkoutCoding.id(capturedEventID)
    workoutId = try WorkoutCoding.id(workoutID)
    kind = "telemetry"
    source = "controller"
    self.timestamp = timestamp
    self.elapsedSeconds = elapsedSeconds
    self.payload = payload
  }
  var dictionary: [String: Any] { WorkoutCoding.dictionary(self) }
  var date: Date { get throws { try WorkoutCoding.date(timestamp) } }
  func number(_ key: String) -> Double? { payload[key]?.number }
  @discardableResult
  func validate() throws -> Int {
    let event = try PowerLogCanonicalization.run { try WorkoutCoreCodec.event(self) }
    return Int(event.canonicalBytes.size)
  }
}

struct WorkoutNativeHealthEvidence: Codable {
  var outcome: String
  var reason: String?
}

struct RideRecord: Codable {
  @propertyWrapper
  struct DecimalCount: Codable {
    var wrappedValue: Int
    init(wrappedValue: Int) { self.wrappedValue = wrappedValue }
    init(from decoder: Decoder) throws {
      let text = try decoder.singleValueContainer().decode(String.self)
      guard let value = Int(text), value >= 0, String(value) == text else {
        throw WorkoutDataError.invalid("Invalid canonical ride count")
      }
      wrappedValue = value
    }
    func encode(to encoder: Encoder) throws {
      guard wrappedValue >= 0 else { throw WorkoutDataError.invalid("Invalid ride count") }
      var container = encoder.singleValueContainer()
      try container.encode(String(wrappedValue))
    }
  }
  var schemaVersion = 1
  var id: String
  var startedAt: String
  var endedAt: String?
  var stopElapsedSeconds: Double?
  var elapsedSeconds: Double = 0
  var ownerTiming: WorkoutOwnerTiming?
  var phase = "running"
  var indoor: Bool
  var watchEnabled: Bool
  var saveToHealth: Bool
  var recordGPS: Bool
  var sampleHz: Int
  var distanceSource: String = "controller"
  var batteryConsumptionSource: String = "controller"
  @DecimalCount var eventCount = 0
  var interrupted = false
  var healthKitState = "notSaved"
  var healthKitUUID: String?
  var healthReason: String?
  var nativeHealthEvidence: WorkoutNativeHealthEvidence? = nil
  @CanonicalOptionalInt64 var collectionRevision: Int64? = nil
  var example: Bool?
  enum CodingKeys: String, CodingKey {
    case schemaVersion, id, startedAt, endedAt, stopElapsedSeconds, elapsedSeconds, ownerTiming, phase, indoor
    case watchEnabled, saveToHealth, recordGPS, sampleHz, interrupted, distanceSource, healthKitState, healthReason
    case healthKitUUID, nativeHealthEvidence, collectionRevision, example, batteryConsumptionSource
  }
  func validate() throws {
    guard try WorkoutCoding.id(id) == id else { throw WorkoutDataError.invalid("Invalid stored ride identity") }
    _ = try WorkoutCoding.timestampMicroseconds(startedAt)
    if let endedAt { _ = try WorkoutCoding.timestampMicroseconds(endedAt) }
    _ = try WorkoutRecordingPolicy.sampleHz(Double(sampleHz))
    guard ["controller", "calculated"].contains(batteryConsumptionSource) else {
      throw WorkoutDataError.invalid("Invalid battery consumption source")
    }
    if let nativeHealthEvidence {
      guard ["unavailable", "discarded"].contains(nativeHealthEvidence.outcome),
        nativeHealthEvidence.reason.map({ !$0.isEmpty && $0.utf8.count <= 2_000 })
          ?? (nativeHealthEvidence.outcome != "unavailable")
      else { throw WorkoutDataError.invalid("Invalid native Health evidence") }
    }
    guard ["preparing", "running", "paused", "finishing", "interrupted", "completed", "failed"].contains(phase),
      elapsedSeconds.isFinite, elapsedSeconds >= 0
    else { throw WorkoutDataError.invalid("Invalid stored ride") }
  }
  func validateCheckpoint(_ checkpoint: [String: Any]) throws {
    guard let rate = checkpoint["sampleHz"] as? Double,
      try WorkoutRecordingPolicy.sampleHz(rate) == Double(sampleHz)
    else { throw WorkoutDataError.invalid("The checkpoint sampling rate conflicts with the frozen ride") }
  }
  static func decode(_ data: Data) throws -> RideRecord {
    let record = try JSONDecoder().decode(RideRecord.self, from: data)
    try record.validate()
    return record
  }
  var dictionary: [String: Any] {
    var result = WorkoutCoding.dictionary(self)
    result["eventCount"] = String(eventCount)
    result["healthKitState"] = healthKitState
    result["healthReason"] = healthReason
    return result
  }
}

typealias WorkoutMetadata = RideRecord

enum WorkoutAltitudeCapture {
  static func apple(altitude: Double, verticalAccuracy: Double) -> [String: WorkoutJSON] {
    var value: [String: WorkoutJSON] = ["originalAltitudeDatum": .string("msl")]
    if altitude.isFinite { value["originalAltitudeMeters"] = .number(altitude) }
    if verticalAccuracy.isFinite { value["originalVerticalAccuracyM"] = .number(verticalAccuracy) }
    if altitude.isFinite, verticalAccuracy.isFinite, verticalAccuracy >= 0 {
      value["altitudeMeters"] = .number(altitude)
      value["verticalAccuracyM"] = .number(verticalAccuracy)
    }
    return value
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
  var batteryConsumption: [String: WorkoutJSON]?
  var telemetryCoveredSeconds: Double = 0
  var heartRateCoveredSeconds: Double = 0
  @RideRecord.DecimalCount var eventCount = 0
  @RideRecord.DecimalCount var telemetryCount = 0
  @RideRecord.DecimalCount var locationCount = 0
  @RideRecord.DecimalCount var healthCount = 0
  @RideRecord.DecimalCount var lifecycleCount = 0
  var lapCount = 0
  var routePreview: [[String: Double]] = []
  var provenance: [String: String] = [:]
  var completeness: [String: String] = [:]
  var dictionary: [String: Any] { WorkoutCoding.dictionary(self) }
}

enum WorkoutRecordingPolicy {
  static func resolve(
    capabilities: RideCapabilities, indoor: Bool, useWatch: Bool = false, saveToHealth: Bool = true,
    recordGPS: Bool? = nil
  ) throws -> FrozenRideOptions {
    try RideOptions.shared.resolve(
      requested: RequestedRideOptions(
        indoor: indoor, useWatch: KotlinBoolean(value: useWatch), saveToHealth: KotlinBoolean(value: saveToHealth),
        recordGPS: recordGPS.map { KotlinBoolean(value: $0) }, sampleHz: nil),
      capabilities: capabilities)
  }
  static func sampleHz(_ value: Double) throws -> Double {
    guard SampleRates.shared.accepts(value: KotlinDouble(value: value)) else {
      throw WorkoutDataError.invalid("Choose 2, 4, or 8 samples per second.")
    }
    return value
  }
  static func requireHealthWrite(id: String, archive: WorkoutArchive) throws {
    let metadata = try archive.metadata(id: id)
    guard metadata.saveToHealth, metadata.healthKitState != "notRequested", metadata.healthKitState != "discarded",
      metadata.healthKitState != "unavailable"
    else {
      throw WorkoutDataError.invalid("Health saving was not requested for this ride")
    }
  }
  static func requireOptions(_ metadata: WorkoutMetadata, saveToHealth: Bool, recordGPS: Bool) throws {
    guard metadata.saveToHealth == saveToHealth, metadata.recordGPS == recordGPS else {
      throw WorkoutDataError.invalid("Recording options cannot change after a ride starts")
    }
  }
}

struct WorkoutSamplingOwner {
  private(set) var id: String?
  private(set) var rate: Double?
  mutating func update(id: String?, rate: Double) {
    guard self.id != id else { return }
    self.id = id
    self.rate = id == nil ? nil : rate
  }
  func connectionRate(_ requested: Double) -> Double { rate ?? requested }
}

/// Parent Health samples may span the ride boundary; retain only their actual in-window series points.
struct WorkoutSensorWindow {
  let start: Date
  let cutoff: Date
  func contains(start sampleStart: Date, end sampleEnd: Date) -> Bool {
    sampleStart >= start && sampleEnd <= cutoff && sampleEnd >= sampleStart
  }
}

enum WorkoutStableIdentity {
  static func uuid(_ value: String) -> String {
    var bytes = Array(SHA256.hash(data: Data(value.utf8)).prefix(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x50
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    return UUID(
      uuid: (
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7], bytes[8], bytes[9], bytes[10],
        bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
      )
    ).uuidString.lowercased()
  }
}

struct WorkoutLiveFreshness {
  private(set) var observationID: String?
  private(set) var sequence: Int64?
  private(set) var acquiredAt: Double?

  func age(at uptime: Double) -> Double? {
    acquiredAt.map { max(0, uptime - $0) }
  }

  @discardableResult
  mutating func receive(id: String, sequence: Int64? = nil, age: Double?, at uptime: Double, isNew: Bool = true) -> Bool
  {
    guard let age, age.isFinite, age >= 0, uptime.isFinite else { return false }
    let acquisition = uptime - age
    if observationID == id {
      acquiredAt = min(acquiredAt ?? acquisition, acquisition)
      return false
    }
    guard isNew else { return false }
    if let sequence, let previous = self.sequence {
      guard sequence > previous else { return false }
    } else if let acquiredAt, acquisition <= acquiredAt {
      return false
    }
    observationID = id
    self.sequence = sequence
    acquiredAt = acquisition
    return true
  }

  static func admitHealth(
    duplicate: () throws -> Bool, record: () -> Bool, storageFailed: (Error) -> Void
  ) -> Bool {
    do {
      guard try !duplicate() else { return false }
      return record()
    } catch {
      storageFailed(error)
      return false
    }
  }

  static func unrecorded(_ events: [WorkoutEvent], archive: WorkoutArchive) throws -> Set<String> {
    Set(try events.filter { try !archive.hasEvent(id: $0.workoutId, eventID: $0.eventId) }.map(\.eventId))
  }

  static func age(acquisition: Double?, now: Double) -> Double? {
    guard let acquisition, acquisition.isFinite, now.isFinite else { return nil }
    let age = max(0, now - acquisition)
    return age.isFinite ? age : nil
  }

  static func sampleEvent(_ sample: [String: Any]) -> [String: Any]? {
    guard let acquisition = sample["acquisitionMonotonic"] as? Double, acquisition.isFinite else { return nil }
    var value = sample
    value["acquiredAtMonotonic"] = acquisition
    return value
  }

}

enum WorkoutStreamStatus {
  static func normalized(_ value: Any?) -> String {
    StreamStatus.shared.normalized(status: value as? String)
  }
  static func accuracy(_ value: Any?) -> Double? {
    guard let value = value as? Double, value.isFinite, value >= 0 else { return nil }
    return value
  }
  static func location(_ accuracy: Double?) -> String {
    StreamStatus.shared.location(accuracy: self.accuracy(accuracy).map { KotlinDouble(value: $0) })
  }
  static func resolve(requested: Bool, phase: String, age: Double?, staleAfter: Double, status: String? = nil) -> String
  {
    StreamStatus.shared.resolve(
      requested: requested, phase: phase, age: age.map { KotlinDouble(value: $0) },
      staleAfter: staleAfter, status: status)
  }
}

struct WorkoutStreamSelection {
  let phase: String
  let controller: Bool
  let heartRate: Bool
  let gps: Bool
}

struct WorkoutLivePresentation {
  var id: String? {
    didSet {
      guard id != oldValue else { return }
      gpsStatus = "off"
      gpsAccuracy = nil
      controllerFreshness = WorkoutLiveFreshness()
      heartFreshness = WorkoutLiveFreshness()
      gpsFreshness = WorkoutLiveFreshness()
      metrics = [:]
    }
  }
  var gpsStatus = "off"
  var gpsAccuracy: Double?
  var controllerFreshness = WorkoutLiveFreshness()
  var heartFreshness = WorkoutLiveFreshness()
  var gpsFreshness = WorkoutLiveFreshness()
  var metrics: [String: Double] = [:]

  func controllerStatus(_ selection: WorkoutStreamSelection, now: Double) -> String {
    StreamStatus.shared.controller(
      requested: selection.controller, phase: selection.phase,
      age: controllerFreshness.age(at: now).map { KotlinDouble(value: $0) })
  }
  func heartRateStatus(_ selection: WorkoutStreamSelection, now: Double) -> String {
    WorkoutStreamStatus.resolve(
      requested: selection.heartRate, phase: selection.phase, age: heartFreshness.age(at: now),
      staleAfter: StreamStatus.shared.heartStaleSeconds)
  }
  func gpsStreamStatus(_ selection: WorkoutStreamSelection, now: Double) -> String {
    WorkoutStreamStatus.resolve(
      requested: selection.gps, phase: selection.phase, age: gpsFreshness.age(at: now),
      staleAfter: StreamStatus.shared.gpsStaleSeconds, status: gpsStatus)
  }
  func statuses(_ selection: WorkoutStreamSelection, now: Double) -> [String] {
    [controllerStatus(selection, now: now), heartRateStatus(selection, now: now), gpsStreamStatus(selection, now: now)]
  }

  mutating func receiveHeartRate(
    _ values: [String: Any], identity: String, acquiredAt: Double?, sequence: Int64? = nil, isNew: Bool = true,
    now: Double
  ) {
    guard let value = values["heartRateBpm"] as? Double, value.isFinite, value >= 0,
      heartFreshness.receive(
        id: identity, sequence: sequence, age: WorkoutLiveFreshness.age(acquisition: acquiredAt, now: now), at: now,
        isNew: isNew)
    else { return }
    metrics["heartRateBpm"] = value
  }

}

@propertyWrapper
struct CanonicalInt64: Codable, Equatable {
  var wrappedValue: Int64
  init(wrappedValue: Int64) { self.wrappedValue = wrappedValue }
  init(from decoder: Decoder) throws {
    let text = try decoder.singleValueContainer().decode(String.self)
    guard let value = Int64(text), String(value) == text else {
      throw WorkoutDataError.invalid("Invalid canonical Int64")
    }
    wrappedValue = value
  }
  func encode(to encoder: Encoder) throws {
    var container = encoder.singleValueContainer()
    try container.encode(String(wrappedValue))
  }
}

@propertyWrapper
struct CanonicalOptionalInt64: Codable {
  var wrappedValue: Int64?
  init(wrappedValue: Int64?) { self.wrappedValue = wrappedValue }
  init(from decoder: Decoder) throws {
    let container = try decoder.singleValueContainer()
    wrappedValue = try container.decodeNil() ? nil : CanonicalInt64(from: decoder).wrappedValue
  }
  func encode(to encoder: Encoder) throws {
    if let wrappedValue {
      try CanonicalInt64(wrappedValue: wrappedValue).encode(to: encoder)
    } else {
      var container = encoder.singleValueContainer()
      try container.encodeNil()
    }
  }
}
extension KeyedDecodingContainer {
  func decode(_ type: CanonicalOptionalInt64.Type, forKey key: Key) throws -> CanonicalOptionalInt64 {
    try decodeIfPresent(type, forKey: key) ?? CanonicalOptionalInt64(wrappedValue: nil)
  }
}
extension KeyedEncodingContainer {
  mutating func encode(_ value: CanonicalOptionalInt64, forKey key: Key) throws {
    if let value = value.wrappedValue { try encode(CanonicalInt64(wrappedValue: value), forKey: key) }
  }
}

struct CatalogState: Codable, Equatable {
  @CanonicalInt64 var revision: Int64
  var lastDeletedWorkoutId: String?
}
