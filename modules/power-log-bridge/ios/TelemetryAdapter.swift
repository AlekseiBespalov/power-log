import Foundation
import CoreFoundation
import PowerLogCore

enum ControllerIOError: LocalizedError {
  case invalid(String)
  var errorDescription: String? {
    if case .invalid(let message) = self { return message }
    return nil
  }
}

extension TelemetrySession {
  func frame(_ request: ControllerRequest) -> Data { WorkoutCoreCodec.data(self.request(kind: request)) }
  func receive(
    _ data: Data, generation: Int64, request: ControllerRequest, sentAt: Double, now: Double
  ) throws -> TelemetryNotificationDecision {
    try notifications(
      generation: generation, request: request, notification: WorkoutCoreCodec.bytes(data), sentAt: sentAt, now: now)
  }
}

struct ControllerProfileRecord: Codable {
  let controllerModel: String
  let firmwareLabel: String
  let controllerProtocol: String

  init(_ identity: ControllerIdentity) {
    controllerModel = identity.controllerModel
    firmwareLabel = identity.firmwareLabel
    controllerProtocol = identity.controllerProtocol
  }

  func restore(restorers: [any ControllerRestorer] = []) throws -> ControllerIdentity {
    try TelemetryRestoration.shared.restore(
      controllerModel: controllerModel, firmwareLabel: firmwareLabel, controllerProtocol: controllerProtocol,
      metrics: nil, restorers: restorers
    ).identity
  }
}

enum TelemetryRecords {
  static func metrics(_ reading: TelemetryReading) -> [String: Double] {
    Dictionary(uniqueKeysWithValues: reading.metrics.map { ($0.key.name, $0.value.doubleValue) })
  }

  static func sample(_ evidence: TelemetryCaptureEvidence) -> [String: Any] {
    let identity = evidence.telemetry.identity!
    var value: [String: Any] = metrics(evidence.telemetry)
    value.merge([
      "controllerModel": identity.controllerModel, "firmwareLabel": identity.firmwareLabel,
      "controllerProtocol": identity.controllerProtocol, "timestamp": evidence.timestamp.original,
      "observationId": evidence.observationId, "captureSessionID": evidence.captureSessionId,
      "observationSequence": String(evidence.sequence), "clockEpoch": evidence.clockEpoch,
      "connectionEpoch": evidence.connectionEpoch, "acquisitionMonotonic": evidence.acquisitionMonotonic,
      "sourceElapsedSeconds": evidence.sourceElapsedSeconds,
    ]) { _, new in new }
    if let discontinuity = evidence.clockDiscontinuitySeconds {
      value["clockDiscontinuitySeconds"] = discontinuity.doubleValue
    }
    return value
  }

  static func restore(_ sample: [String: Any], rideID: String, restorers: [any ControllerRestorer] = []) throws
    -> TelemetryCaptureEvidence
  {
    func string(_ key: String) throws -> String {
      guard let value = sample[key] as? String else { throw ControllerIOError.invalid("Missing telemetry \(key).") }
      return value
    }
    func number(_ key: String) throws -> Double {
      guard let value = sample[key] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() else {
        throw ControllerIOError.invalid("Missing telemetry \(key).")
      }
      return value.doubleValue
    }
    var metrics: [String: KotlinDouble] = [:]
    for metric in MetricCatalog.shared.all where sample[metric.id] != nil {
      metrics[metric.id] = KotlinDouble(value: try number(metric.id))
    }
    let reading = try TelemetryRestoration.shared.restore(
      controllerModel: string("controllerModel"), firmwareLabel: string("firmwareLabel"),
      controllerProtocol: string("controllerProtocol"), metrics: metrics, restorers: restorers
    ).reading!
    let originalSequence = try string("observationSequence")
    guard let sequence = Int64(originalSequence), String(sequence) == originalSequence else {
      throw ControllerIOError.invalid("Invalid telemetry observationSequence.")
    }
    let discontinuity: KotlinDouble?
    if sample["clockDiscontinuitySeconds"] != nil && !(sample["clockDiscontinuitySeconds"] is NSNull) {
      discontinuity = KotlinDouble(value: try number("clockDiscontinuitySeconds"))
    } else {
      discontinuity = nil
    }
    return try TelemetryCaptureEvidence(
      rideId: rideID, observationId: string("observationId"), captureSessionId: string("captureSessionID"),
      clockEpoch: string("clockEpoch"), connectionEpoch: string("connectionEpoch"), sequence: sequence,
      timestamp: Timestamps.shared.parse(original: string("timestamp")),
      acquisitionMonotonic: number("acquisitionMonotonic"), sourceElapsedSeconds: number("sourceElapsedSeconds"),
      telemetry: reading,
      clockDiscontinuitySeconds: discontinuity)
  }
}
