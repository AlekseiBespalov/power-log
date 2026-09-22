import Foundation

struct PowerLogCaptureFrame {
  let sample: [String: Any]
  let liveID: String
  let liveStartedAt: String
  let liveOrigin: Double
  let liveElapsed: Double
  var ride: PowerLogCaptureDestination? = nil

  func mappedRide(timeline override: WorkoutTimelineAnchor? = nil) throws -> WorkoutEvent? {
    guard let ride, let timestamp = sample["timestamp"] as? String,
      let observationID = sample["observationId"] as? String else { return nil }
    let mapping = try (override ?? ride.timeline).map(epoch: sample["clockEpoch"] as? String,
      acquisition: sample["acquisitionMonotonic"] as? Double, timestamp: WorkoutCoding.date(timestamp))
    guard mapping.eligible else { return nil }
    var payload = sample
    if let uncertainty = mapping.uncertainty { payload["timelineMappingUncertainty"] = uncertainty }
    return try WorkoutEvent(dictionary: ["schemaVersion": 1, "eventId": observationID, "workoutId": ride.id,
      "kind": "telemetry", "source": "cyc", "timestamp": timestamp, "elapsedSeconds": mapping.elapsed, "payload": payload])
  }
}

struct PowerLogCaptureDestination {
  let id: String
  let generation: UUID
  let timeline: WorkoutTimelineAnchor
}

enum PowerLogCaptureCutoff {
  static func owner(_ timeline: WorkoutTimelineAnchor, at date: Date, elapsedSeconds: Double?) throws -> WorkoutTimelineAnchor {
    if timeline.stopMonotonic != nil, timeline.stopUTC != nil { return timeline }
    let wallElapsed = date.timeIntervalSince(try WorkoutCoding.date(timeline.startedAt))
    guard wallElapsed.isFinite, wallElapsed >= 0, wallElapsed <= 2_678_400 else {
      throw PowerLogStorageError.invalid("Owner stop precedes the ride or exceeds its supported duration")
    }
    let elapsed = elapsedSeconds ?? wallElapsed
    guard elapsed.isFinite, elapsed >= 0, elapsed <= 2_678_400 else {
      throw PowerLogStorageError.invalid("Owner stop has an invalid elapsed time")
    }
    var closed = timeline
    closed.stopMonotonic = timeline.monotonicOrigin + elapsed
    closed.stopUTC = WorkoutCoding.timestamp(date)
    return closed
  }
}

struct PowerLogCaptureFault: Codable {
  let id: String
  let workoutID: String?
  let timestamp: String
  let observationID: String
  let message: String
  func persist(archive: WorkoutArchive) throws {
    try archive.store.transaction { db in
      try db.put(namespace: "capture-faults", key: id, value: WorkoutCoding.encoder().encode(self), immutable: true)
      if let workoutID, try !archive.store.isWorkoutDeleted(id: workoutID) {
        var notices = try archive.metadata(id: workoutID).warnings
        if !notices.contains(message) { notices.append(message) }
        try archive.update(id: workoutID, warnings: Array(notices.suffix(64)))
      }
    }
  }
}

/// Admission never waits for SQLite. The recorder drains this mailbox on its own serial executor.
final class PowerLogCaptureInbox {
  static let maximumFrames = 64
  private let lock = NSLock()
  private var frames: [PowerLogCaptureFrame] = []
  private var destination: PowerLogCaptureDestination?
  private var admissionFault: PowerLogCaptureFault?

  func admit(_ frame: PowerLogCaptureFrame) throws -> Bool {
    lock.lock(); defer { lock.unlock() }
    guard frames.count < Self.maximumFrames else {
      if admissionFault == nil {
        admissionFault = PowerLogCaptureFault(id: UUID().uuidString.lowercased(), workoutID: destination?.id,
          timestamp: frame.sample["timestamp"] as? String ?? WorkoutCoding.timestamp(Date()),
          observationID: frame.sample["observationId"] as? String ?? "",
          message: "Bike recording was interrupted because storage could not keep up.")
      }
      destination = nil
      throw PowerLogStorageError.busy
    }
    let wake = frames.isEmpty
    var admitted = frame; admitted.ride = destination
    frames.append(admitted)
    return wake
  }

  func take(upTo limit: Int) -> [PowerLogCaptureFrame] {
    lock.lock(); defer { lock.unlock() }
    let count = min(max(0, limit), frames.count)
    let result = Array(frames.prefix(count))
    frames.removeFirst(count)
    return result
  }

  var isEmpty: Bool { lock.lock(); defer { lock.unlock() }; return frames.isEmpty }
  var first: PowerLogCaptureFrame? { lock.lock(); defer { lock.unlock() }; return frames.first }
  var count: Int { lock.lock(); defer { lock.unlock() }; return frames.count }
  var fault: PowerLogCaptureFault? { lock.lock(); defer { lock.unlock() }; return admissionFault }
  func acknowledgeFault(_ id: String) {
    lock.lock(); defer { lock.unlock() }; if admissionFault?.id == id { admissionFault = nil }
  }
  func setDestination(_ value: PowerLogCaptureDestination?) {
    lock.lock(); defer { lock.unlock() }; destination = admissionFault == nil ? value : nil
  }
}

struct PowerLogCaptureRecord {
  let frame: PowerLogCaptureFrame
  let live: WorkoutEvent
  let ride: WorkoutEvent?

  init(frame: PowerLogCaptureFrame, ride: WorkoutEvent?) throws {
    guard let timestamp = frame.sample["timestamp"] as? String,
      let observationID = frame.sample["observationId"] as? String else {
      throw PowerLogStorageError.invalid("Capture has no original identity or timestamp")
    }
    self.frame = frame; self.ride = ride
    live = try WorkoutEvent(dictionary: ["schemaVersion": 1, "eventId": observationID,
      "workoutId": frame.liveID, "kind": "telemetry", "source": "cyc", "timestamp": timestamp,
      "elapsedSeconds": frame.liveElapsed, "payload": frame.sample])
  }
}

/// Destination and mapping are frozen before buffering. Failed commits retain the identical batch.
final class PowerLogCaptureBatch {
  static let targetFrames = 8
  static let maximumFrames = 64
  private(set) var records: [PowerLogCaptureRecord] = []
  private(set) var oldestAdmission: Double?
  var isEmpty: Bool { records.isEmpty }
  var available: Int { Self.maximumFrames - records.count }

  func append(_ record: PowerLogCaptureRecord, at now: Double) throws {
    guard records.count < Self.maximumFrames else { throw PowerLogStorageError.busy }
    if records.isEmpty { oldestAdmission = now }
    records.append(record)
  }

  @discardableResult
  func flush(store: PowerLogStore, register: (String) throws -> Void = { _ in }) throws -> [WorkoutEvent] {
    guard !records.isEmpty else { return [] }
    let batch = records
    let rides = batch.compactMap(\.ride)
    try store.transaction(priority: .capture) { _ in
      var liveIDs = Set<String>()
      for record in batch where liveIDs.insert(record.frame.liveID).inserted {
        try store.ensureLiveCollection(id: record.frame.liveID, startedAt: record.frame.liveStartedAt,
          monotonicOrigin: record.frame.liveOrigin)
      }
      _ = try store.appendBatch(batch.flatMap { [$0.live] + ($0.ride.map { [$0] } ?? []) }, producer: "cyc")
      for id in Set(rides.map(\.workoutId)) { try register(id) }
    }
    records.removeFirst(batch.count)
    if records.isEmpty { oldestAdmission = nil }
    return rides
  }

  func discardRide(_ id: String) throws {
    records = try records.map { $0.ride?.workoutId == id ? try PowerLogCaptureRecord(frame: $0.frame, ride: nil) : $0 }
  }
}
