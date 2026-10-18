import Foundation
import PowerLogCore

extension TelemetrySession {
  convenience init() { self.init(protocol: CycControllerDecoder()) }
}

func testTelemetryReading(_ overrides: [String: Double] = [:]) throws -> TelemetryReading {
  let url = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    .appendingPathComponent("tests/fixtures/contract/telemetry/admission.json")
  let fixture = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
  let original = fixture["retainedOriginal"] as! [String: Any]
  let profile = original["identity"] as! [String: String]
  var metrics = original["metrics"] as! [String: Double]
  metrics.merge(overrides) { _, new in new }
  return try TelemetryRestoration.shared.restore(
    controllerModel: profile["controllerModel"]!, firmwareLabel: profile["firmwareLabel"]!,
    controllerProtocol: profile["controllerProtocol"]!, metrics: metrics.mapValues { KotlinDouble(value: $0) },
    restorers: []
  ).reading!
}

func testGeneratedTelemetryReading(_ values: [String: Double]) throws -> TelemetryReading {
  let entries = WorkoutCoreCodec.values(TelemetryMetric.values())
  let metrics = try Dictionary(
    uniqueKeysWithValues: values.map { key, value in
      guard let metric = entries.first(where: { $0.name == key }) else {
        throw ControllerIOError.invalid("Unknown generated metric")
      }
      return (metric, KotlinDouble(value: value))
    })
  let identity = try ControllerIdentity(
    controllerModel: "Example controller", firmwareLabel: "generated",
    controllerProtocol: "test-v2",
    capabilities: ControllerCapabilities(
      suppliedMetrics: Set(metrics.keys),
      hasKnownSpeedUnit: false))
  return try TelemetryReading(values: metrics, identity: identity)
}

extension PowerLogCaptureFrame {
  func mappedRide(timeline: WorkoutTimelineAnchor? = nil) throws -> WorkoutEvent? {
    guard let elapsed = try rideElapsed(timeline: timeline) else { return nil }
    return try PowerLogCaptureRecord(frame: self, rideElapsed: elapsed).ride
  }
}

final class FixtureControllerProtocol: NSObject, ControllerProtocol, ControllerRestorer {
  let fixture: [String: Any]
  let identity: ControllerIdentity
  private var buffer = Data()
  private var discarded: Int64 = 0
  init(_ fixture: [String: Any], suppliedMetrics: Set<TelemetryMetric> = [.controllerspeedmps]) throws {
    self.fixture = fixture
    identity = try ControllerIdentity(
      controllerModel: fixture["model"] as! String, firmwareLabel: "release-1",
      controllerProtocol: "test-v2",
      capabilities: ControllerCapabilities(
        suppliedMetrics: suppliedMetrics, hasKnownSpeedUnit: true))
  }
  func request(kind: ControllerRequest) -> KotlinByteArray {
    WorkoutCoreCodec.bytes(Data((fixture[kind == .identity ? "identityRequest" : "telemetryRequest"] as! String).utf8))
  }
  func feed(notification: KotlinByteArray, identity: ControllerIdentity?) -> ControllerDecodeResult {
    buffer.append(WorkoutCoreCodec.data(notification))
    var replies: [ControllerReply] = []
    while let newline = buffer.firstIndex(of: 10) {
      let frame = buffer.prefix(through: newline)
      if frame == Data((fixture["identityNotification"] as! String).utf8) {
        replies.append(ControllerIdentityReply(identity: self.identity))
      } else if frame == Data((fixture["telemetryNotification"] as! String).utf8) {
        let decoded = try! JSONSerialization.jsonObject(with: Data(frame)) as! [String: Any]
        let metrics = Dictionary(
          uniqueKeysWithValues: self.identity.capabilities.suppliedMetrics.compactMap { metric in
            (decoded[metric.name] as? Double).map { (metric, KotlinDouble(value: $0)) }
          })
        replies.append(ControllerReadingReply(reading: try! TelemetryReading(values: metrics, identity: identity)))
      } else {
        discarded += Int64(frame.count)
      }
      buffer.removeFirst(frame.count)
    }
    return try! ControllerDecodeResult(replies: replies, discardedBytes: discarded, pendingBytes: Int32(buffer.count))
  }
  func reset() -> ControllerDecodeResult {
    discarded += Int64(buffer.count)
    buffer.removeAll()
    return try! ControllerDecodeResult(replies: [], discardedBytes: discarded, pendingBytes: 0)
  }
  func supportsProtocol(controllerProtocol: String) -> Bool { controllerProtocol == identity.controllerProtocol }
  func restoreIdentity(controllerModel: String, firmwareLabel: String, controllerProtocol: String) throws
    -> ControllerIdentity
  {
    guard controllerModel == identity.controllerModel, firmwareLabel == identity.firmwareLabel,
      controllerProtocol == identity.controllerProtocol
    else { throw ControllerIOError.invalid("Invalid fixture profile") }
    return try ControllerIdentity(
      controllerModel: controllerModel, firmwareLabel: firmwareLabel, controllerProtocol: controllerProtocol,
      capabilities: identity.capabilities)
  }
  func restoreReading(metrics: [String: KotlinDouble], identity: ControllerIdentity) throws -> TelemetryReading {
    guard Set(metrics.keys) == Set(identity.capabilities.suppliedMetrics.map(\.name)) else {
      throw ControllerIOError.invalid("Invalid fixture metrics")
    }
    return try TelemetryReading(
      values: Dictionary(uniqueKeysWithValues: identity.capabilities.suppliedMetrics.map { ($0, metrics[$0.name]!) }),
      identity: identity)
  }
}
