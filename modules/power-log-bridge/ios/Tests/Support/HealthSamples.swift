import Foundation
import PowerLogCore

/// Synthetic immutable provider samples; no provider cursor, manifest or repair ledger.
final class HealthSamplesFixture {
  let archive: WorkoutArchive
  let source: String
  private var staged: [String: [MemberInput]] = [:]
  init(archive: WorkoutArchive, source: String) {
    self.archive = archive
    self.source = source
  }
  func key(dataOrigin: String, parentID: String) throws -> String { dataOrigin + ":" + parentID }
  func begin(id: String, key: String) throws { staged[key] = [] }
  func stage(id: String, key: String, inputs: [MemberInput]) throws { staged[key, default: []] += inputs }
  func finish(
    id: String, key: String, dataOrigin: String, parentID: String, metric: String,
    originalStart: String, originalEnd: String, count: Int64, base: [String: WorkoutJSON] = [:]
  ) throws {
    let inputs = (staged.removeValue(forKey: key) ?? []).sorted { $0.originalEnd < $1.originalEnd }
    for (index, input) in inputs.enumerated() {
      let logical = try HealthCodec.shared.encodeMemberTuple(value: input.tuple)
      let eventID = WorkoutStableIdentity.uuid(parentID + ":" + logical)
      let value = Double(bitPattern: UInt64(input.tuple.valueBits, radix: 16)!)
      let field =
        metric.contains("HeartRate")
        ? "heartRateBpm" : metric.contains("Basal") ? "basalEnergyKcal" : "activeEnergyKcal"
      var payload = base
      payload.merge([
        "healthKitIdentifier": .string(metric), "sampleUUID": .string(parentID), "parentSampleUUID": .string(parentID),
        "representation": base["representation"] ?? .string(count > 1 ? "rawSeries" : "rawQuantity"),
        "sampleStart": .string(input.originalStart), "sampleEnd": .string(input.originalEnd),
        "unit": .string(input.tuple.unit),
        "value": .number(value), field: .number(value), "memberIndex": .string(String(index)),
        "logicalKey": .string(logical),
        "valueBits": .string(input.tuple.valueBits),
      ]) { _, new in new }
      var event = try WorkoutEvent(
        workoutId: id, kind: "health", source: source, timestamp: WorkoutCoding.date(input.originalEnd),
        payload: payload, eventId: eventID)
      event.timestamp = input.originalEnd
      _ = try WorkoutHealthObservation.append(event, archive: archive, dataOrigin: dataOrigin)
    }
  }
}
