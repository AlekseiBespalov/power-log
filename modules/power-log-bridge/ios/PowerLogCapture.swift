import Foundation
import PowerLogCore

struct PowerLogCaptureFrame {
  let sample: [String: Any]
  let liveID: String
  let liveElapsed: Double
  var ride: PowerLogCaptureDestination? = nil
  var admission: Int64 = 0

  var acquisition: Double? { sample["acquisitionMonotonic"] as? Double }

  func rideElapsed(timeline override: WorkoutTimelineAnchor? = nil) throws -> Double? {
    guard let ride, sample["timestamp"] is String, sample["observationId"] is String else { return nil }
    return try (override ?? ride.timeline).gpsTimeline().elapsedForAcquisition(
      epoch: sample["clockEpoch"] as? String, acquisition: acquisition.map(KotlinDouble.init(value:))
    )?.doubleValue
  }
}

struct PowerLogCaptureDestination: Equatable {
  let id: String
  let generation: UUID
  let timeline: WorkoutTimelineAnchor
}

/// A single bounded capture queue. Each admitted frame retains its recording destination.
final class PowerLogCaptureInbox {
  static let maximumFrames = Int(CaptureBatch.companion.queuedFrames)
  private let lock = NSLock()
  private var frames: [PowerLogCaptureFrame] = []
  private var destination: PowerLogCaptureDestination?
  private var stopped: (id: String, generation: UUID, elapsed: Double)?
  func admit(_ frame: PowerLogCaptureFrame) throws -> Bool {
    lock.lock()
    defer { lock.unlock() }
    guard frames.count < Self.maximumFrames else { throw PowerLogStorageError.busy }
    let wake = frames.isEmpty
    var frame = frame
    frame.ride = destination
    frames.append(frame)
    return wake
  }
  func take(upTo limit: Int) -> [PowerLogCaptureFrame] {
    lock.lock()
    defer { lock.unlock() }
    let count = min(max(0, limit), frames.count)
    let result = Array(frames.prefix(count))
    frames.removeFirst(count)
    return result
  }
  var first: PowerLogCaptureFrame? {
    lock.lock()
    defer { lock.unlock() }
    return frames.first
  }
  var count: Int {
    lock.lock()
    defer { lock.unlock() }
    return frames.count
  }
  var isEmpty: Bool { count == 0 }
  @discardableResult func setDestination(_ value: PowerLogCaptureDestination?) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    guard destination != value else { return false }
    destination = value
    return true
  }
  /// Retain the fence after clearing the destination: an earlier batch may still be waiting on storage.
  func stopDestination(id: String, generation: UUID, elapsed: Double) {
    lock.lock()
    defer { lock.unlock() }
    stopped = (id, generation, elapsed)
    destination = nil
  }
  func keepsRide(_ frame: PowerLogCaptureFrame) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    guard let stopped, let ride = frame.ride,
      stopped.id == ride.id, stopped.generation == ride.generation
    else { return true }
    return frame.acquisition.map { $0 <= ride.timeline.monotonicOrigin + stopped.elapsed } ?? false
  }
  var currentDestination: PowerLogCaptureDestination? {
    lock.lock()
    defer { lock.unlock() }
    return destination
  }
}

struct PowerLogCaptureRecord {
  let frame: PowerLogCaptureFrame
  let live: WorkoutEvent
  let identity: PowerLogObservationIdentity
  private(set) var rideEntry: (event: WorkoutEvent, identity: PowerLogObservationIdentity)?
  var ride: WorkoutEvent? { rideEntry?.event }

  init(frame: PowerLogCaptureFrame, rideElapsed: Double?) throws {
    let original = try Self.original(frame)
    let payload = try Self.payload(frame.sample)
    self.frame = frame
    live = try WorkoutEvent(
      capturedEventID: original.id, workoutID: frame.liveID, timestamp: original.timestamp,
      elapsedSeconds: frame.liveElapsed, payload: payload)
    identity = try PowerLogObservationIdentity(live)
    guard let rideElapsed else { return }
    guard let destination = frame.ride else { throw PowerLogStorageError.invalid("Capture has no ride destination") }
    let ride = try WorkoutEvent(
      capturedEventID: original.id, workoutID: destination.id, timestamp: original.timestamp,
      elapsedSeconds: rideElapsed, payload: payload)
    rideEntry = (ride, identity)
  }

  init(frame: PowerLogCaptureFrame, ride: WorkoutEvent?) throws {
    let original = try Self.original(frame)
    self.frame = frame
    live = try WorkoutEvent(
      capturedEventID: original.id, workoutID: frame.liveID, timestamp: original.timestamp,
      elapsedSeconds: frame.liveElapsed, payload: Self.payload(frame.sample))
    identity = try PowerLogObservationIdentity(live)
    rideEntry = try ride.map { ride in
      let shared =
        ride.payload == live.payload && ride.timestamp == live.timestamp && ride.kind == live.kind
        && ride.source == live.source
      return (ride, shared ? identity : try PowerLogObservationIdentity(ride))
    }
  }

  private static func original(_ frame: PowerLogCaptureFrame) throws -> (id: String, timestamp: String) {
    guard let timestamp = frame.sample["timestamp"] as? String, let id = frame.sample["observationId"] as? String
    else { throw PowerLogStorageError.invalid("Capture has no original identity or timestamp") }
    return (id, timestamp)
  }

  private static func payload(_ sample: [String: Any]) throws -> [String: WorkoutJSON] {
    let payload = try WorkoutCoreCodec.telemetryPayload(sample)
    guard JSONSerialization.isValidJSONObject(payload) else {
      throw WorkoutDataError.invalid("Workout event is not finite JSON")
    }
    return try JSONDecoder().decode([String: WorkoutJSON].self, from: JSONSerialization.data(withJSONObject: payload))
  }

  func withoutRide() -> PowerLogCaptureRecord {
    var record = self
    record.rideEntry = nil
    return record
  }

  var entries: [(event: WorkoutEvent, identity: PowerLogObservationIdentity)] {
    rideEntry.map { [$0] } ?? []
  }
}

/// Destination and mapping are frozen before buffering. Failed commits retain the identical batch.
final class PowerLogCaptureBatch {
  static let targetFrames = Int(CaptureBatch.companion.targetFrames)
  static let maximumFrames = Int(CaptureBatch.companion.maximumFrames)
  private(set) var records: [PowerLogCaptureRecord] = []
  private(set) var oldestAdmission: Double?
  var isEmpty: Bool { records.isEmpty }
  var available: Int { Self.maximumFrames - records.count }

  func append(_ record: PowerLogCaptureRecord, at now: Double) throws {
    guard records.count < Self.maximumFrames else { throw PowerLogStorageError.busy }
    if records.isEmpty { oldestAdmission = now }
    records.append(record)
  }

  /// `keepsRide` runs inside the transaction, so a cutoff that arrives while the flush waits still applies.
  @discardableResult
  func flush(
    store: PowerLogStore, register: (String) throws -> Void = { _ in },
    keepsRide: (PowerLogCaptureRecord) -> Bool = { _ in true },
    checkpoint: (PowerLogDatabase) throws -> Void = { _ in }
  ) throws -> [WorkoutEvent] {
    guard !records.isEmpty else { return [] }
    var batch = records
    try store.transaction(priority: .capture) { db in
      batch = batch.map { $0.ride == nil || keepsRide($0) ? $0 : $0.withoutRide() }
      let rides = batch.compactMap(\.ride)
      // Pre-Start preview is memory-only; recorded samples have exactly one persistent membership.
      let entries = batch.flatMap(\.entries)
      let acquisitions = Dictionary(
        entries.compactMap { entry in
          entry.event.payload["acquisitionMonotonic"]?.number.map { (entry.event.eventId, $0) }
        }, uniquingKeysWith: { first, _ in first })
      _ = try store.appendCaptured(entries, producer: "controller", liveAcquisitions: acquisitions)
      for id in Set(rides.map(\.workoutId)) { try register(id) }
      try checkpoint(db)
    }
    records.removeFirst(batch.count)
    if records.isEmpty { oldestAdmission = nil }
    return batch.compactMap(\.ride)
  }

  func discardRide(_ id: String) {
    records = records.map { $0.ride?.workoutId == id ? $0.withoutRide() : $0 }
  }
}

/// Full-rate originals drain independently of UI and companion traffic; only the preview uses memory storage.
final class PowerLogCaptureRecorder {
  let inbox: PowerLogCaptureInbox
  let queue: DispatchQueue
  private let store: PowerLogStore
  private let batch = PowerLogCaptureBatch()
  private let now: () -> Double
  private var discarded = Set<String>()
  private var due: DispatchWorkItem?
  var failed: ((Error) -> Void)?
  var flushed: (() -> Void)?
  var mapped: ((WorkoutEvent) throws -> Void)?
  init(
    store: PowerLogStore, inbox: PowerLogCaptureInbox = PowerLogCaptureInbox(),
    queue: DispatchQueue = DispatchQueue(label: "app.powerlog.capture", qos: .userInitiated),
    now: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime }
  ) {
    self.store = store
    self.inbox = inbox
    self.queue = queue
    self.now = now
  }
  @discardableResult func admit(_ frame: PowerLogCaptureFrame) throws -> Bool {
    if try inbox.admit(frame) { queue.async { self.pump() } }
    return false
  }
  func setDestination(_ value: PowerLogCaptureDestination?) { inbox.setDestination(value) }
  func stopDestination(id: String, generation: UUID, elapsed: Double) {
    inbox.stopDestination(id: id, generation: generation, elapsed: elapsed)
  }
  func barrier() throws {
    try queue.sync {
      defer { if !inbox.isEmpty { queue.async { self.pump() } } }
      try drain(limit: inbox.count)
      try flush()
    }
  }
  func discardRide(_ id: String) {
    queue.sync {
      discarded.insert(id)
      batch.discardRide(id)
    }
  }
  private func pump() {
    do {
      try drain()
      guard let oldest = batch.oldestAdmission else { return }
      let delay = CaptureBatch.companion.flushDelaySeconds(
        frames: Int32(batch.records.count), ageSeconds: now() - oldest)
      if delay == 0 { try flush() } else if due == nil { schedule(after: delay) }
    } catch {
      failed?(error)
      schedule(after: 1)
    }
  }
  private func schedule(after delay: Double) {
    due?.cancel()
    let work = DispatchWorkItem { [weak self] in
      guard let self else { return }
      self.due = nil
      do {
        try self.drain()
        try self.flush()
      } catch {
        self.failed?(error)
        self.schedule(after: 1)
      }
    }
    due = work
    queue.asyncAfter(deadline: .now() + delay, execute: work)
  }
  private func drain(limit: Int = .max) throws {
    var remaining = limit
    while remaining > 0, let frame = inbox.first {
      if batch.available == 0 { try flush() }
      do {
        try stage(frame)
      } catch PowerLogStorageError.busy {
        throw PowerLogStorageError.busy
      } catch {
        failed?(error)
      }
      _ = inbox.take(upTo: 1)
      remaining -= 1
      if batch.records.count >= PowerLogCaptureBatch.targetFrames { try flush() }
    }
  }
  private func stage(_ frame: PowerLogCaptureFrame) throws {
    var timeline = frame.ride?.timeline
    if let latest = inbox.currentDestination, latest.id == frame.ride?.id, latest.generation == frame.ride?.generation {
      timeline = latest.timeline
    }
    if frame.ride.map({ !discarded.contains($0.id) }) == true, let elapsed = try frame.rideElapsed(timeline: timeline) {
      try batch.append(PowerLogCaptureRecord(frame: frame, rideElapsed: elapsed), at: now())
    } else if frame.ride == nil {
      try MonitorDataStore.shared.appendLive(frame.sample, elapsedSeconds: frame.liveElapsed)
    }
  }
  private func flush() throws {
    guard !batch.isEmpty else { return }
    due?.cancel()
    due = nil
    let committed = try batch.flush(
      store: store,
      keepsRide: { record in
        guard let admitted = record.frame.ride, !self.discarded.contains(admitted.id),
          self.inbox.keepsRide(record.frame)
        else { return false }
        guard let latest = self.inbox.currentDestination, latest.id == admitted.id,
          latest.generation == admitted.generation,
          let stop = latest.timeline.stopMonotonic, let acquired = record.frame.acquisition
        else { return true }
        return acquired <= stop
      })
    for event in committed { try mapped?(event) }
    flushed?()
  }
}
