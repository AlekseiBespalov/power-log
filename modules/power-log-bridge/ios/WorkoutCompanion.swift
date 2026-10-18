import Foundation

/// The companion stream is deliberately transient. The phone's ride is the only application archive.
struct WorkoutCompanionOptions: Codable, Equatable {
  var id: String
  var start: Date
  var indoor: Bool
  var saveToHealth: Bool
  var recordGPS: Bool
  var distanceSource: String = "controller"
}

struct WorkoutCompanionObservation: Codable, Equatable {
  var id: String
  var timestamp: Date
  var start: Date
  var metric: String
  var identifier: String
  var unit: String
  var value: Double
  var cumulative: Bool
  var dataOrigin: String? = nil
  var parentID: String? = nil
}

struct WorkoutCompanionLocation: Codable {
  var timestamp: Date
  var latitude: Double
  var longitude: Double
  var altitude: Double
  var accuracy: Double
  var verticalAccuracy: Double
  var speed: Double
  var course: Double
}

struct WorkoutCompanionSecond: Codable {
  var time: Date
  var readings: [String: Double]
}

struct WorkoutCompanionMessage: Codable {
  enum Kind: String, Codable {
    case start, startRequest, cancelStart, command, state, measurements, telemetry, acknowledgement, health
  }
  var kind: Kind
  var rideID: String?
  var commandID: String?
  var startRequestID: String?
  var expiresAt: Date?
  var sampleTime: Date?
  var options: WorkoutCompanionOptions?
  var action: String?
  var phase: String?
  var stateRevision: Int64?
  var elapsed: Double?
  var active: Double?
  var observations: [WorkoutCompanionObservation]?
  var readings: [String: Double]?
  var healthSeconds: [WorkoutCompanionSecond]?
  var locations: [WorkoutCompanionLocation]?
  var sequence: Int64?
  var acknowledgement: Int64?
  var healthState: String?
  var healthID: String?
  var error: String?

  func mayApplyState(after revision: Int64, ended: Bool) -> Bool {
    guard let phase else { return true }
    if ended && phase != "completed" && phase != "idle" { return false }
    return (stateRevision ?? 0) >= revision
  }

  func encoded() throws -> Data {
    let data = try JSONEncoder().encode(self)
    guard data.count <= 60_000 else { throw WorkoutCompanionError.invalid("Companion message exceeds its byte limit.") }
    return data
  }
  static func decode(_ data: Data) throws -> Self {
    guard data.count <= 60_000 else { throw WorkoutCompanionError.invalid("Companion message exceeds its byte limit.") }
    let message = try JSONDecoder().decode(Self.self, from: data)
    guard message.rideID.map({ UUID(uuidString: $0) != nil }) ?? true,
      message.commandID.map({ UUID(uuidString: $0) != nil }) ?? true,
      message.startRequestID.map({ UUID(uuidString: $0) != nil }) ?? true,
      (message.observations?.count ?? 0) <= 64, (message.locations?.count ?? 0) <= 32,
      message.readings?.values.allSatisfy(\.isFinite) ?? true,
      (message.healthSeconds?.count ?? 0) <= 8,
      message.healthSeconds?.allSatisfy({ $0.readings.values.allSatisfy(\.isFinite) }) ?? true,
      message.observations?.allSatisfy({
        UUID(uuidString: $0.id) != nil && $0.value.isFinite && $0.start <= $0.timestamp
      }) ?? true
    else { throw WorkoutCompanionError.invalid("Invalid companion message.") }
    return message
  }
}

enum WorkoutCompanionError: Error, LocalizedError {
  case invalid(String)
  var errorDescription: String? {
    if case .invalid(let value) = self { return value }
    return nil
  }
}

/// A bounded retransmission window, not a durable delivery guarantee. Expired observations are honest gaps.
struct WorkoutCompanionBuffer {
  struct Entry {
    let sequence: Int64
    let admitted: Date
    let observation: WorkoutCompanionObservation
    let bytes: Int
  }
  static let maximumAge: TimeInterval = 60
  static let maximumCount = 2048
  static let maximumBytes = 512 * 1024
  private(set) var entries: [Entry] = []
  private(set) var nextSequence: Int64 = 1
  private(set) var dropped = 0
  mutating func append(_ observation: WorkoutCompanionObservation, now: Date) throws {
    let bytes = try JSONEncoder().encode(observation).count
    guard bytes <= Self.maximumBytes else { return }
    entries.append(Entry(sequence: nextSequence, admitted: now, observation: observation, bytes: bytes))
    nextSequence += 1
    trim(now: now)
  }
  mutating func trim(now: Date) {
    var bytes = entries.reduce(0) { $0 + $1.bytes }
    while let first = entries.first,
      entries.count > Self.maximumCount || bytes > Self.maximumBytes
        || now.timeIntervalSince(first.admitted) > Self.maximumAge
    {
      bytes -= first.bytes
      entries.removeFirst()
      dropped += 1
    }
  }
  mutating func acknowledge(_ sequence: Int64) { entries.removeAll { $0.sequence <= sequence } }
  mutating func page(now: Date) -> [Entry] {
    trim(now: now)
    return Array(entries.prefix(64))
  }
}

/// Uniformly thins the whole traversed route when its memory budget fills. Raw phone GPS is unaffected.
struct WorkoutCompanionRoute {
  private let capacity: Int
  private var stride = 1
  private var count = 0
  private var retained: [WorkoutCompanionLocation] = []
  private var latest: WorkoutCompanionLocation?
  init(capacity: Int = 30_000) {
    precondition(capacity >= 4)
    self.capacity = capacity
  }
  mutating func append(_ points: [WorkoutCompanionLocation]) {
    for point in points where point.accuracy >= 0 && point.accuracy <= 50 {
      guard latest.map({ point.timestamp > $0.timestamp }) ?? true else { continue }
      latest = point
      if count % stride == 0 { retained.append(point) }
      count += 1
      if retained.count + (retained.last?.timestamp == latest?.timestamp ? 0 : 1) > capacity {
        retained = retained.enumerated().compactMap { $0.offset.isMultiple(of: 2) ? $0.element : nil }
        stride *= 2
      }
    }
  }
  var points: [WorkoutCompanionLocation] {
    guard let latest, retained.last?.timestamp != latest.timestamp else { return retained }
    return retained + [latest]
  }
}

/// Short-lived start tombstones prevent a cancelled request overtaken in transit from starting later.
struct WorkoutCompanionCancellations {
  private var attempts: [String: Date] = [:]
  mutating func insert(_ id: String?, now: Date = Date()) {
    guard let id else { return }
    attempts = attempts.filter { $0.value > now }
    if attempts.count >= 64, let oldest = attempts.min(by: { $0.value < $1.value })?.key {
      attempts.removeValue(forKey: oldest)
    }
    attempts[id] = now.addingTimeInterval(60)
  }
  func contains(_ id: String?, now: Date = Date()) -> Bool {
    id.flatMap { attempts[$0] }.map { $0 > now } ?? false
  }
}
