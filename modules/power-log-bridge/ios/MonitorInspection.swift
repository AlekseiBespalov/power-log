import Foundation
import PowerLogCore

final class MonitorInspectionGate {
  private let requests = InspectionRequests()
  private var pending = false

  func begin(source: String, revision: String, selection: String = "auto", epoch: Int64 = 0, sequence: Int64 = 0)
    throws -> InspectionTicket
  {
    let ticket = try requests.begin(
      context: InspectionContext(
        sourceId: source, revision: revision, distanceSelection: selection,
        interactionEpoch: epoch, interactionSequence: sequence))
    pending = true
    return ticket
  }

  func accepts(_ ticket: InspectionTicket) -> Bool { requests.accepts(ticket: ticket) }
  func complete(_ ticket: InspectionTicket) -> Bool {
    guard accepts(ticket) else { return false }
    pending = false
    return true
  }
  @discardableResult
  func cancel() throws -> Bool {
    try requests.cancel()
    let cancelled = pending
    pending = false
    return cancelled
  }
}

enum MonitorInspection {
  static func scalar(_ value: Double?, integer: Int64? = nil) -> Scalar? {
    if let integer { return Scalar.Integer64(value: integer) }
    return value.map { Scalar.Real64(value: $0) }
  }

  static func lifecycle(
    timeUs: Int64, id: String, producer: String, sequence: Int64, action: String,
    clock: String? = nil, interrupted: Bool = false, controllerSequence: Int64? = nil
  ) -> AnalysisItem {
    AnalysisItem(
      collectionUs: timeUs, collectionElapsedSeconds: WorkoutDistancePolicy.seconds(timeUs),
      eventId: id, observationId: id, producer: producer, sequence: sequence, kind: "lifecycle", source: producer,
      timestamp: "", values: [:], originalElapsedSeconds: nil, representation: nil,
      continuity: Continuity(clockEpoch: clock, connectionEpoch: nil, interruptionIndex: 0), action: action,
      distanceBarrier: false, interrupted: interrupted,
      controllerSequence: controllerSequence.map(KotlinLong.init(value:)),
      hasElapsedMapping: true, origin: nil, declaredMetrics: nil)
  }

  static func target(time: Double, identity: String? = nil) -> InspectionTarget {
    let us = WorkoutDistancePolicy.timeUs(time)
    return InspectionTarget(
      timeUs: us, anchor: identity.map { InspectionAnchor(observationId: $0, timeUs: us) })
  }

  static func plot(
    metric: String, start: Int64, end: Int64, buckets: Int, limit: Int = 16_384,
    owned: Bool = false, slot: Int64? = nil, identity: @escaping (KotlinLong) -> String
  ) throws -> InspectionPlotReducer {
    try InspectionPlotReducer(
      metric: metric, range: AnalysisRange(startUs: start, endUs: end), buckets: Int32(buckets),
      pointLimit: Int32(limit), owned: owned, identity: identity, alignedSlotUs: slot.map(KotlinLong.init(value:)))
  }

  static func query(
    metric: String, start: Int64, end: Int64, timeline: ActiveTimeline,
    selection: InspectionTarget? = nil, reference: InspectionTarget? = nil, trailing: Bool = false,
    bucket: Bool = false,
    predecessor: InspectionCompactPoint? = nil, identity: @escaping (KotlinLong) -> String
  ) throws -> InspectionQuery {
    try InspectionQuery(
      metric: metric, range: AnalysisRange(startUs: start, endUs: end), timeline: timeline,
      selection: selection, reference: reference, allowTrailing: trailing, bucket: bucket,
      predecessor: nil, compactPredecessor: predecessor, identity: identity)
  }

  static func distancePoint(
    original: Original, timeUs: Int64, segment: Int32, startUs: Int64, endUs: Int64,
    tieKey: Int64, plotSegment: Int32
  ) -> DistanceInspectionPoint {
    DistanceInspectionPoint(
      point: InspectionPoint(
        timeUs: timeUs, original: original,
        continuity: Continuity(clockEpoch: nil, connectionEpoch: nil, interruptionIndex: 0), producer: "", sequence: 0,
        interruptionBoundary: false), segment: segment, startUs: startUs, endUs: endUs, tieKey: tieKey,
      plotSegment: plotSegment)
  }

  static func heldTail(
    metric: String, identity: String, time: Double, value: Double,
    evidence: String, acquired: Double?, now: Double, elapsed: Double
  ) throws -> InspectionTail? {
    try HeldTail.shared.render(
      metric: metric,
      original: WorkoutDistancePolicy.original(identity, "", time, value),
      evidenceObservationId: evidence, acquisitionMonotonic: acquired.map(KotlinDouble.init(value:)),
      monotonicNow: now, elapsedNow: elapsed)
  }
}
