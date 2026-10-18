import Foundation
import PowerLogCore

struct WorkoutOwnerProjection {
  let timing: WorkoutOwnerTiming
  let phase: String

  static func adoptedPhase(incoming: String, current: String?, rebase: Bool) -> String {
    guard !rebase, ["running", "paused"].contains(incoming), let current,
      ["running", "paused"].contains(current)
    else { return incoming }
    return current
  }

  func runningOrigin(at now: Double) -> Double? { phase == "running" ? now : nil }

  static func timing(
    elapsedBase: Double, timerBase: Double, elapsedOrigin: Double?, runningOrigin: Double?, now: Double, at: Date
  ) throws -> WorkoutOwnerTiming {
    let elapsed = elapsedBase + (elapsedOrigin.map { max(0, now - $0) } ?? 0)
    let timer = timerBase + (runningOrigin.map { max(0, now - $0) } ?? 0)
    return try WorkoutOwnerTiming(
      timestamp: WorkoutCoding.timestamp(at), elapsedSeconds: elapsed, timerSeconds: min(timer, elapsed))
  }

  static func stopCutoff(
    startedAt: String, elapsedBase: Double, timerBase: Double, elapsedOrigin: Double?, runningOrigin: Double?,
    now: Double
  ) throws -> WorkoutOwnerTiming {
    let elapsed = elapsedBase + (elapsedOrigin.map { max(0, now - $0) } ?? 0)
    return try timing(
      elapsedBase: elapsedBase, timerBase: timerBase, elapsedOrigin: elapsedOrigin, runningOrigin: runningOrigin,
      now: now, at: WorkoutCoding.date(startedAt).addingTimeInterval(elapsed))
  }

}

struct WorkoutOwnerTiming: Codable, Equatable {
  static var recoveryPageObserverForTesting: ((Int) -> Void)?
  static var recoveryWorkObserverForTesting: ((Int) -> Void)?
  let timestamp: String
  let elapsedSeconds: Double
  let timerSeconds: Double

  init(timestamp: String, elapsedSeconds: Double, timerSeconds: Double) throws {
    self.timestamp = timestamp
    self.elapsedSeconds = elapsedSeconds
    self.timerSeconds = timerSeconds
    try validate()
  }
  func validate() throws {
    _ = try WorkoutCoding.date(timestamp)
    guard elapsedSeconds.isFinite, elapsedSeconds >= 0, elapsedSeconds <= 2_678_400,
      timerSeconds.isFinite, timerSeconds >= 0, timerSeconds <= elapsedSeconds
    else { throw WorkoutDataError.invalid("Invalid retained owner timing") }
  }
  static func remote(
    id: String, timestamp: String, elapsed: Double?, timer: Double?, archive: WorkoutArchive,
    phase: String = "paused", receivedElapsed: Double? = nil
  ) throws -> WorkoutOwnerProjection {
    guard let elapsed, let timer else { throw WorkoutDataError.invalid("Remote owner has no measured timing") }
    let reported = try Self(timestamp: timestamp, elapsedSeconds: elapsed, timerSeconds: timer)
    return try projection(
      id: id, checkpoint: reported, archive: archive, initialPhase: phase, projectedElapsed: receivedElapsed)
  }

  static func terminal(_ metadata: WorkoutMetadata) throws -> Self {
    guard let timing = metadata.ownerTiming, timing.timestamp == metadata.endedAt,
      timing.elapsedSeconds == metadata.stopElapsedSeconds
    else { throw WorkoutDataError.invalid("The stopped owner has no complete retained timing") }
    try timing.validate()
    return timing
  }

  static func checkpoint(_ value: [String: Any]) throws -> Self {
    guard let utc = value["checkpointUTC"] as? String,
      let elapsed = value["elapsedSeconds"] as? Double, let timer = value["timerSeconds"] as? Double
    else { throw WorkoutDataError.invalid("The recording checkpoint has no complete retained timing") }
    return try Self(timestamp: utc, elapsedSeconds: elapsed, timerSeconds: timer)
  }
  static func lastOriginal(id: String, startedAt: String, archive: WorkoutArchive) throws -> Self {
    let zero = try Self(timestamp: startedAt, elapsedSeconds: 0, timerSeconds: 0)
    return try archive.store.read { db in
      guard
        let row = try db.rows(
          "SELECT m.elapsed_seconds,o.original_timestamp FROM collection_memberships m JOIN observations o ON o.id=m.observation_id LEFT JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.deleted=0 AND (m.kind IN ('telemetry','location','health') OR (m.kind='lifecycle' AND l.action IN ('start','pause','resume','lap') AND coalesce(json_extract(o.extra,'$.interrupted'),0)!=1)) AND o.representation!='parentCorrection' AND m.elapsed_seconds>=0 ORDER BY m.elapsed_seconds DESC,m.ordinal DESC LIMIT 1",
          [.text(id)], limit: 1
        ).first,
        let elapsed = row.double("elapsed_seconds"), let utc = row.string("original_timestamp")
      else { return zero }
      let app = try retained(id: id, checkpoint: zero, archive: archive, throughElapsed: elapsed)
      let active = try WorkoutTimelineAnchor.activeInterval(id: id, elapsed: elapsed, archive: archive) != nil
      return try Self(
        timestamp: utc, elapsedSeconds: elapsed,
        timerSeconds: min(elapsed, app.timerSeconds + (active ? elapsed - app.elapsedSeconds : 0)))
    }
  }

  static func retained(
    id: String, checkpoint: Self, archive: WorkoutArchive, preserveActive: Bool = false,
    throughElapsed: Double = Double.greatestFiniteMagnitude, initialPhase: String? = nil,
    projectedElapsed: Double? = nil
  ) throws
    -> Self
  {
    try projection(
      id: id, checkpoint: checkpoint, archive: archive, preserveActive: preserveActive,
      throughElapsed: throughElapsed, initialPhase: initialPhase, projectedElapsed: projectedElapsed
    ).timing
  }

  private static func projection(
    id: String, checkpoint: Self, archive: WorkoutArchive, preserveActive: Bool = false,
    throughElapsed: Double = Double.greatestFiniteMagnitude, initialPhase: String? = nil,
    projectedElapsed: Double? = nil
  ) throws -> WorkoutOwnerProjection {
    try checkpoint.validate()
    if let projectedElapsed, !projectedElapsed.isFinite || projectedElapsed < 0 {
      throw WorkoutDataError.invalid("Invalid mapped owner observation time")
    }
    return try archive.store.read { db in
      let latest = try db.rows(
        "SELECT m.elapsed_seconds,o.original_timestamp FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.deleted=0 AND m.original_elapsed_seconds IS NOT NULL AND m.kind IN ('telemetry','location','lifecycle') AND m.elapsed_seconds>? AND m.elapsed_seconds<=? ORDER BY m.elapsed_seconds DESC,m.ordinal DESC LIMIT 1",
        [.text(id), .real(checkpoint.elapsedSeconds), .real(throughElapsed)], limit: 1
      ).first
      let latestElapsed = latest?.double("elapsed_seconds") ?? checkpoint.elapsedSeconds
      let elapsed = max(latestElapsed, projectedElapsed ?? checkpoint.elapsedSeconds)
      guard elapsed > checkpoint.elapsedSeconds else {
        return WorkoutOwnerProjection(timing: checkpoint, phase: initialPhase ?? "running")
      }
      let originalUTC = latest?.string("original_timestamp") ?? checkpoint.timestamp
      let utc =
        try elapsed == latestElapsed
        ? originalUTC
        : WorkoutCoding.timestamp(WorkoutCoding.date(originalUTC).addingTimeInterval(elapsed - latestElapsed))
      if preserveActive {
        return WorkoutOwnerProjection(
          timing: try Self(timestamp: utc, elapsedSeconds: elapsed, timerSeconds: checkpoint.timerSeconds),
          phase: initialPhase ?? "paused")
      }
      var active: Bool
      if let initialPhase {
        active = initialPhase == "running"
      } else {
        let prior = try db.rows(
          "SELECT l.action FROM collection_memberships m INDEXED BY membership_lifecycle_time JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.deleted=0 AND m.original_elapsed_seconds IS NOT NULL AND m.elapsed_seconds<=? AND l.action IN ('start','resume','pause','stop','discard','interruption') ORDER BY m.elapsed_seconds DESC,m.producer DESC,m.sequence DESC LIMIT 1",
          [.text(id), .real(checkpoint.elapsedSeconds)], limit: 1
        ).first
        active = prior.map { ["start", "resume"].contains($0.string("action")!) } ?? true
      }
      var position = checkpoint.elapsedSeconds
      var timer = checkpoint.timerSeconds
      var cursor = checkpoint.elapsedSeconds
      var producer = ""
      var sequence: Int64 = 0
      while true {
        let lowerBound = producer.isEmpty ? "m.elapsed_seconds>?" : "(m.elapsed_seconds,m.producer,m.sequence)>(?,?,?)"
        let lowerValues: [PowerLogSQLValue] =
          producer.isEmpty
          ? [.real(cursor)] : [.real(cursor), .text(producer), .integer(sequence)]
        let rows = try db.rows(
          "SELECT m.elapsed_seconds,m.producer,m.sequence,l.action FROM collection_memberships m INDEXED BY membership_lifecycle_time JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.deleted=0 AND m.original_elapsed_seconds IS NOT NULL AND \(lowerBound) AND m.elapsed_seconds<=? ORDER BY m.elapsed_seconds,m.producer,m.sequence LIMIT 512",
          [.text(id)] + lowerValues + [.real(elapsed)], limit: 512, observeWork: Self.recoveryWorkObserverForTesting)
        Self.recoveryPageObserverForTesting?(rows.count)
        for row in rows {
          let at = row.double("elapsed_seconds") ?? 0
          if at > position {
            if active { timer += at - position }
            position = at
          }
          if ["start", "resume"].contains(row.string("action") ?? "") { active = true }
          if ["pause", "stop", "discard", "interruption"].contains(row.string("action") ?? "") { active = false }
        }
        if rows.count < 512 { break }
        cursor = rows.last!.double("elapsed_seconds")!
        producer = rows.last!.string("producer")!
        sequence = rows.last!.int("sequence")!
      }
      if active { timer += elapsed - position }
      return WorkoutOwnerProjection(
        timing: try Self(timestamp: utc, elapsedSeconds: elapsed, timerSeconds: min(elapsed, timer)),
        phase: active ? "running" : "paused")
    }
  }

}
struct WorkoutInterruptionBoundary {
  let time: Double
  let producer: String
  let sequence: Int64
  let clockEpoch: String?
  let controllerSequence: Int64

  func precedes(time: Double, producer: String?, sequence: Int64?, clockEpoch: String?) -> Bool {
    if self.time != time { return self.time < time }
    if self.producer == producer, let sequence { return self.sequence < sequence }
    if producer == "controller", let sequence { return controllerSequence < sequence }
    return self.clockEpoch != nil && clockEpoch != nil && self.clockEpoch != clockEpoch
  }

  static func index(
    _ boundaries: [Self], time: Double, producer: String? = nil, sequence: Int64? = nil, clockEpoch: String? = nil
  ) -> Int {
    var low = 0
    var high = boundaries.count
    while low < high {
      let mid = (low + high) / 2
      if boundaries[mid].time < time { low = mid + 1 } else { high = mid }
    }
    while low < boundaries.count,
      boundaries[low].precedes(time: time, producer: producer, sequence: sequence, clockEpoch: clockEpoch)
    {
      low += 1
    }
    return low
  }

  static func resumedAtBoundary(
    _ boundaries: [Self], time: Double, producer: String?, sequence: Int64?, clockEpoch: String?
  ) -> Bool? {
    let first = index(boundaries, time: time)
    guard first < boundaries.count, boundaries[first].time == time else { return nil }
    return index(boundaries, time: time, producer: producer, sequence: sequence, clockEpoch: clockEpoch) > first
  }

  static func load(store: PowerLogStore, id: String, revision: Int64) throws -> [Self] {
    var result: [Self] = []
    var cursor = -1.0
    var producer = ""
    var sequence: Int64 = 0
    while true {
      let rows = try store.read(priority: .background) { db in
        try db.rows(
          "SELECT m.elapsed_seconds,m.producer,m.sequence,m.source,o.clock_epoch,json_extract(o.extra,'$.controllerSequence') AS controller_sequence FROM collection_memberships m INDEXED BY membership_lifecycle_time JOIN observations o ON o.id=m.observation_id JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND (json_extract(o.extra,'$.interrupted')=1 OR l.action='interruption') AND (m.elapsed_seconds,m.producer,m.sequence)>(?,?,?) ORDER BY m.elapsed_seconds,m.producer,m.sequence LIMIT 256",
          [
            .text(id), .integer(revision), .integer(revision), .integer(revision), .real(cursor), .text(producer),
            .integer(sequence),
          ], limit: 256)
      }
      guard result.count + rows.count <= 10_000 else {
        throw WorkoutDataError.invalid("Too many workout interruptions")
      }
      for row in rows {
        let time = row.double("elapsed_seconds")!
        let epoch = row.string("clock_epoch")
        guard let retainedSequence = row.string("controller_sequence").flatMap(Int64.init), retainedSequence >= 0 else {
          throw WorkoutDataError.invalid("Interruption has no retained telemetry sequence")
        }
        result.append(
          Self(
            time: time, producer: row.string("producer")!, sequence: row.int("sequence")!, clockEpoch: epoch,
            controllerSequence: retainedSequence))
      }
      guard rows.count == 256, let last = rows.last else { return result }
      cursor = last.double("elapsed_seconds")!
      producer = last.string("producer")!
      sequence = last.int("sequence")!
    }
  }
}

struct WorkoutTimelineAnchor: Codable, Equatable {
  let epoch: String
  let monotonicOrigin: Double
  let startedAt: String
  var stopMonotonic: Double?
  var stopUTC: String?
  var uncertain: Bool? = nil
  var epochStart: Double? = nil
  func resuming(timing: WorkoutOwnerTiming, epoch: String, uptime: Double) -> Self {
    Self(
      epoch: epoch, monotonicOrigin: uptime - timing.elapsedSeconds, startedAt: startedAt, uncertain: true,
      epochStart: uptime)
  }

}

extension WorkoutTimelineAnchor {
  struct LocationClock {
    private let core = GPSAcquisition()
    struct Fix {
      let core: GPSAcquisitionFix
      var elapsed: Double { core.elapsedSeconds }
      var acquisition: Double { core.acquisitionMonotonic }
      var epoch: String { core.epoch }
      var uncertain: Bool { core.uncertain }
    }
    struct Batch {
      let fixes: [(index: Int, fix: Fix)]
      let discontinuity: Bool
    }
    static func timestamp(_ date: Date) throws -> ProtocolTimestamp {
      let seconds = date.timeIntervalSinceReferenceDate
      let whole = floor(seconds)
      let nanos = Int((seconds - whole) * 1_000_000_000)
      let epochWhole = whole + Date.timeIntervalBetween1970AndReferenceDate
      let prefix = WorkoutCoding.timestamp(Date(timeIntervalSince1970: epochWhole)).prefix(19)
      return try Timestamps.shared.parse(original: String(prefix) + String(format: ".%09dZ", nanos))
    }
    static func original(timestamp: Date, latitude: Double, longitude: Double, accuracy: Double) throws -> GPSOriginal {
      GPSOriginal(
        timestamp: try Self.timestamp(timestamp), latitude: latitude, longitude: longitude,
        horizontalAccuracyM: accuracy)
    }
    func mapBatch(originals: [GPSOriginal], received: GPSReceipt, anchor: WorkoutTimelineAnchor) throws -> Batch {
      let timeline = try anchor.gpsTimeline()
      let batch = try core.mapBatch(originals: originals, received: received, timeline: timeline)
      return Batch(
        fixes: batch.fixes.map { value in (originals.firstIndex { $0 === value.original }!, Fix(core: value)) },
        discontinuity: batch.discontinuity)
    }
    func map(
      timestamp: Date, latitude: Double, longitude: Double, accuracy: Double,
      receivedAt: Date, uptime: Double, anchor: WorkoutTimelineAnchor
    ) throws -> Fix? {
      let original = try Self.original(
        timestamp: timestamp, latitude: latitude, longitude: longitude, accuracy: accuracy)
      return try mapBatch(
        originals: [original], received: GPSReceipt(timestamp: Self.timestamp(receivedAt), monotonicSeconds: uptime),
        anchor: anchor
      ).fixes.first?.fix
    }
    func admit(_ fix: Fix) -> Bool { core.admit(fix: fix.core) }
  }

  func gpsTimeline() throws -> GPSTimeline {
    let start = try Timestamps.shared.parse(original: startedAt)
    let receipt = GPSReceipt(timestamp: start, monotonicSeconds: 0)
    return GPSTimeline(
      epoch: epoch, startedAtUs: start.comparisonUs, monotonicOrigin: monotonicOrigin,
      epochStart: epochStart.map(KotlinDouble.init(value:)),
      stopMonotonic: stopMonotonic.map(KotlinDouble.init(value:)),
      uncertain: uncertain == true, startedAtSubmicrosecondNanos: receipt.submicrosecondNanos)
  }

  static func activeInterval(id: String, elapsed: Double, archive: WorkoutArchive) throws -> Int64? {
    try archive.store.read { db in
      let metadata = try archive.metadata(id: id)
      if let cutoff = metadata.stopElapsedSeconds, elapsed >= cutoff { return nil }
      let row = try db.rows(
        "SELECT m.id,l.action FROM collection_memberships m JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.deleted=0 AND m.elapsed_seconds<=? AND l.action IN ('start','resume','pause','stop','interruption') ORDER BY m.elapsed_seconds DESC,m.producer DESC,m.sequence DESC LIMIT 1",
        [.text(id), .real(elapsed)], limit: 1
      ).first
      guard let row, ["start", "resume"].contains(row.string("action") ?? "") else { return nil }
      return row.int("id")
    }
  }
}
