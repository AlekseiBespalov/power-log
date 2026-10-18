import Foundation
import CoreFoundation
import CryptoKit
import PowerLogCore

#if canImport(HealthKit)
  import HealthKit
#endif

enum WorkoutHealthObservation {
  static func tombstone(
    id: String, source: String, parentID: String, observedAt: Date,
    elapsed: Double? = nil
  ) throws -> WorkoutEvent {
    let parent = try HealthIds.shared.uuid(value: parentID)
    return try WorkoutEvent(
      workoutId: id, kind: "health", source: source, timestamp: observedAt,
      elapsedSeconds: elapsed,
      payload: [
        "representation": .string("healthTombstone"),
        "sampleUUID": .string(parent), "supersedesEventId": .string(parent), "deleted": .bool(true),
      ],
      eventId: WorkoutStableIdentity.uuid("deleted:" + parent))
  }

  static func input(start: Date, end: Date, metric: String, unit: String, value: Double) throws -> MemberInput {
    guard value.isFinite else { throw WorkoutDataError.invalid("Nonfinite Health member") }
    let first = WorkoutCoding.preciseTimestamp(start)
    let last = WorkoutCoding.preciseTimestamp(end)
    let tuple = MemberTuple(
      startUs: try WorkoutCoding.timestampMicroseconds(first),
      startSubUsNs: Int32(String(first.dropLast().suffix(3))) ?? 0,
      endUs: try WorkoutCoding.timestampMicroseconds(last),
      endSubUsNs: Int32(String(last.dropLast().suffix(3))) ?? 0, metric: metric, unit: unit,
      valueBits: String(format: "%016llx", value.bitPattern))
    return MemberInput(tuple: tuple, originalStart: first, originalEnd: last)
  }

  static func builderID(id: String, input: MemberInput, representation: String) throws -> String {
    WorkoutStableIdentity.uuid(
      id + ":" + representation + ":" + (try HealthCodec.shared.encodeMemberTuple(value: input.tuple)))
  }

  static func append(_ event: WorkoutEvent, archive: WorkoutArchive, dataOrigin: String) throws -> Bool {
    try archive.store.transaction(priority: .capture) { _ in
      if try archive.hasEvent(id: event.workoutId, eventID: event.eventId) { return false }
      guard event.source == "watch" || event.source == "phone" else {
        throw WorkoutDataError.invalid("Invalid Health producer")
      }
      var original = event
      let previous = try archive.sourceProgress(id: event.workoutId, producer: event.source).lastSequence
      let (sequence, overflow) = previous.addingReportingOverflow(1)
      guard !overflow && sequence > 0 else { throw WorkoutDataError.invalid("Health source sequence exhausted") }
      let origin = Origin(
        provider: .applehealth, dataOrigin: dataOrigin,
        providerObservationId: event.payload["parentSampleUUID"]?.string ?? event.payload["sampleUUID"]?.string,
        originEventId: event.eventId, producer: event.source == "watch" ? .watch : .phone, sequence: sequence,
        originRevision: nil)
      original.payload["origin"] = try JSONDecoder().decode(
        WorkoutJSON.self,
        from: Data(HealthCodec.shared.encodeOrigin(value: origin).utf8))
      original.payload["provider"] = .string("appleHealth")
      original.payload["dataOrigin"] = .string(dataOrigin)
      return try archive.appendBatch([original]) > 0
    }
  }
}
