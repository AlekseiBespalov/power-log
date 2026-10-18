import Foundation
import PowerLogCore

var checks = 0
func check(_ value: Bool) {
  precondition(value)
  checks += 1
}
func rejects(_ work: () throws -> Void) {
  do {
    try work()
    fatalError("Invalid telemetry evidence was admitted")
  } catch {
    check((error as NSError).domain == "KotlinException")
  }
}

let fixture =
  try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
  as! [String: Any]
let retained = fixture["retainedOriginal"] as! [String: Any]
let directory = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-telemetry-\(UUID())")
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: directory) }
let originalURL = directory.appendingPathComponent("original.json")
try JSONSerialization.data(withJSONObject: retained).write(to: originalURL)

func reopen(_ url: URL, decoded: Bool = true) throws -> TelemetryCaptureEvidence {
  let row = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
  let profile = row["identity"] as! [String: Any]
  let identity: ControllerIdentity
  let reading: TelemetryReading
  let metrics = row["metrics"] as! [String: Double]
  if decoded {
    identity = try CycProtocol.shared.restoreIdentity(
      controllerModel: profile["controllerModel"] as! String, firmwareLabel: profile["firmwareLabel"] as! String,
      controllerProtocol: profile["controllerProtocol"] as! String)
    reading = try CycProtocol.shared.restoreReading(
      metrics: metrics.mapValues { KotlinDouble(value: $0) }, identity: identity)
  } else {
    let capabilities = profile["capabilities"] as! [String: Any]
    identity = try ControllerIdentity(
      controllerModel: profile["controllerModel"] as! String, firmwareLabel: profile["firmwareLabel"] as! String,
      controllerProtocol: profile["controllerProtocol"] as! String,
      capabilities: ControllerCapabilities(
        suppliedMetrics: Set([TelemetryMetric.humanpowerw, TelemetryMetric.controllerspeedmps]),
        hasKnownSpeedUnit: capabilities["hasKnownSpeedUnit"] as! Bool))
    reading = try TelemetryReading(
      values: [
        .humanpowerw: KotlinDouble(value: metrics["humanPowerW"]!),
        .controllerspeedmps: KotlinDouble(value: metrics["controllerSpeedMps"]!),
      ], identity: identity)
  }
  return try TelemetryCaptureEvidence(
    rideId: row["rideId"] as! String, observationId: row["observationId"] as! String,
    captureSessionId: row["captureSessionId"] as! String, clockEpoch: row["clockEpoch"] as! String,
    connectionEpoch: row["connectionEpoch"] as! String, sequence: Int64(row["sequence"] as! String)!,
    timestamp: Timestamps.shared.parse(original: row["timestamp"] as! String),
    acquisitionMonotonic: row["acquisitionMonotonic"] as! Double,
    sourceElapsedSeconds: row["sourceElapsedSeconds"] as! Double, telemetry: reading,
    clockDiscontinuitySeconds: (row["clockDiscontinuitySeconds"] as? Double).map { KotlinDouble(value: $0) })
}

let first = try reopen(originalURL)
let incoming = try reopen(originalURL)
check(first !== incoming)
check(first.telemetry !== incoming.telemetry)
check(first.telemetry.identity !== incoming.telemetry.identity)
check(first.sequence == 9_007_199_254_740_993)
let initial = try TelemetryCaptureState(rideId: first.rideId, latest: nil)
let live = TelemetryCaptureAdmission.shared.admit(state: initial, evidence: first, storedOriginal: nil).next
let replay = TelemetryCaptureAdmission.shared.admit(
  state: live, evidence: incoming, storedOriginal: try reopen(originalURL))
check(replay.outcome == .replay)
check(replay.next.age(now: 110)?.doubleValue == 10)
let reopened = try TelemetryCaptureState(rideId: first.rideId, latest: nil)
let restoredReplay = TelemetryCaptureAdmission.shared.admit(
  state: reopened, evidence: try reopen(originalURL), storedOriginal: try reopen(originalURL))
check(restoredReplay.outcome == .replay)
check(restoredReplay.next.latest == nil)
var changed = retained
var changedMetrics = changed["metrics"] as! [String: Double]
changedMetrics["humanPowerW"] = 2
changed["metrics"] = changedMetrics
let changedURL = directory.appendingPathComponent("incoming.json")
try JSONSerialization.data(withJSONObject: changed).write(to: changedURL)
let conflict = TelemetryCaptureAdmission.shared.admit(
  state: live, evidence: try reopen(changedURL), storedOriginal: try reopen(originalURL))
check(conflict.outcome == .conflict)
check(conflict.next.age(now: 110)?.doubleValue == 10)
rejects {
  _ = try CycProtocol.shared.restoreIdentity(
    controllerModel: "X60", firmwareLabel: "20250725", controllerProtocol: "5.3")
}
rejects {
  _ = try CycProtocol.shared.restoreIdentity(
    controllerModel: "X6", firmwareLabel: "20250725", controllerProtocol: "256.3")
}
rejects { _ = try CycProtocol.shared.restoreReading(metrics: [:], identity: first.telemetry.identity!) }
var invalid = (retained["metrics"] as! [String: Double]).mapValues { KotlinDouble(value: $0) }
invalid["humanPowerW"] = KotlinDouble(value: .nan)
rejects { _ = try CycProtocol.shared.restoreReading(metrics: invalid, identity: first.telemetry.identity!) }
rejects { _ = try TelemetryCaptureState(rideId: "invalid", latest: nil) }
for row in fixture["clockCases"] as! [[String: Any]] {
  let previous = Date(timeIntervalSince1970: row["firstUnixSeconds"] as! Double)
  let next = Date(timeIntervalSince1970: row["lastUnixSeconds"] as! Double)
  check(previous.timeIntervalSinceReferenceDate == row["firstWall"] as! Double)
  check(next.timeIntervalSinceReferenceDate == row["lastWall"] as! Double)
  let difference = try TelemetryCaptureAdmission.shared.discontinuity(
    previousWallClockSeconds: previous.timeIntervalSinceReferenceDate,
    wallClockSeconds: next.timeIntervalSinceReferenceDate,
    previousAcquisitionMonotonic: row["previousMonotonic"] as! Double,
    acquisitionMonotonic: row["monotonic"] as! Double)
  check(difference?.doubleValue == (row["expected"] as? Double))
  let rawDifference = next.timeIntervalSince(previous) - 1
  check(difference?.doubleValue == (abs(rawDifference) > 0.25 ? rawDifference : nil))
}
let neutralFixture =
  try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2])))
  as! [String: Any]
var neutral = neutralFixture["original"] as! [String: Any]
let neutralURL = directory.appendingPathComponent("neutral.json")
try JSONSerialization.data(withJSONObject: neutral).write(to: neutralURL)
let neutralFirst = try reopen(neutralURL, decoded: false)
let neutralIncoming = try reopen(neutralURL, decoded: false)
check(neutralFirst !== neutralIncoming)
check(neutralFirst.telemetry !== neutralIncoming.telemetry)
check(neutralFirst.telemetry.identity !== neutralIncoming.telemetry.identity)
check(neutralIncoming.telemetry.identity!.controllerModel == "Example controller")
check(neutralIncoming.telemetry.identity!.capabilities.supplies(metric: .controllerspeedmps))
check(neutralIncoming.telemetry.value(metric: .controllerspeedmps)?.doubleValue == 2.6)
let neutralState = TelemetryCaptureAdmission.shared.admit(
  state: try TelemetryCaptureState(rideId: neutralFirst.rideId, latest: nil), evidence: neutralFirst,
  storedOriginal: nil
).next
let neutralReplay = TelemetryCaptureAdmission.shared.admit(
  state: neutralState, evidence: neutralIncoming, storedOriginal: try reopen(neutralURL, decoded: false))
check(neutralReplay.outcome == .replay)
check(neutralReplay.next.age(now: 110)?.doubleValue == 10)
let restarted = TelemetryCaptureAdmission.shared.admit(
  state: try TelemetryCaptureState(rideId: neutralFirst.rideId, latest: nil),
  evidence: try reopen(neutralURL, decoded: false), storedOriginal: try reopen(neutralURL, decoded: false))
check(restarted.outcome == .replay)
check(restarted.next.latest == nil)
var neutralMetrics = neutral["metrics"] as! [String: Double]
neutralMetrics["humanPowerW"] = 2.75
neutral["metrics"] = neutralMetrics
try JSONSerialization.data(withJSONObject: neutral).write(to: neutralURL)
check(
  TelemetryCaptureAdmission.shared.admit(
    state: neutralState, evidence: try reopen(neutralURL, decoded: false), storedOriginal: neutralFirst
  ).outcome == .conflict)
rejects { _ = try ControllerCapabilities(suppliedMetrics: [], hasKnownSpeedUnit: false) }
rejects {
  _ = try ControllerIdentity(
    controllerModel: "", firmwareLabel: "release", controllerProtocol: "v2",
    capabilities: neutralFirst.telemetry.identity!.capabilities)
}
rejects {
  _ = try TelemetryReading(values: [.motorrpm: KotlinDouble(value: 1)], identity: neutralFirst.telemetry.identity!)
}
rejects {
  _ = try TelemetryReading(
    values: [.humanpowerw: KotlinDouble(value: .nan)], identity: neutralFirst.telemetry.identity!)
}
rejects { _ = try MetricCatalog.shared.metric(id: "unknown") }
rejects { _ = try MetricCatalog.shared.format(id: "unknown", value: KotlinDouble(value: 1)) }
rejects { _ = try MetricCatalog.shared.format(id: "unknown", value: nil) }

func bytes(_ values: [UInt8]) -> KotlinByteArray {
  let result = KotlinByteArray(size: Int32(values.count))
  for (index, value) in values.enumerated() { result.set(index: Int32(index), value: Int8(bitPattern: value)) }
  return result
}
func unhex(_ value: String) -> [UInt8] {
  let digits = Array(value)
  return stride(from: 0, to: digits.count, by: 2).map { UInt8(String(digits[$0...($0 + 1)]), radix: 16)! }
}
func frame(_ payload: [UInt8]) -> [UInt8] {
  let crc = CycProtocol.shared.crc(bytes: bytes(payload))
  return [2, UInt8(payload.count)] + payload + [UInt8(crc >> 8), UInt8(crc & 255), 3]
}
let wire =
  try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[3])))
  as! [String: Any]
let identityPayload = unhex((wire["identity"] as! [String: Any])["payloadHex"] as! String)
let telemetryRow = (wire["telemetry"] as! [[String: Any]]).last!
let telemetryFrame = unhex(telemetryRow["frameHex"] as! String)
var badMask = unhex(telemetryRow["payloadHex"] as! String)
badMask[1] = 0
let decoder: any ControllerProtocol = CycControllerDecoder()
let partial = decoder.feed(notification: bytes([9, 8, 7] + Array(frame(identityPayload).prefix(3))), identity: nil)
check(partial.discardedBytes == 3 && partial.pendingBytes == 3)
let reset = decoder.reset()
check(reset.discardedBytes == 6 && reset.pendingBytes == 0 && reset.replies.isEmpty)
let malformed = decoder.feed(notification: bytes(frame(badMask)), identity: nil)
check(malformed.replies.count == 1)
let reason = malformed.replies.first as! ControllerRejectedReply
check(reason.kind == .telemetry && reason.reason == "telemetryMask")
let session = TelemetrySession(protocol: CycControllerDecoder())
check(try session.begin(now: 0, systemInitiated: false))
for (from, to) in [
  (TelemetryStage.connecting, TelemetryStage.services), (.services, .characteristics),
  (.characteristics, .notifications), (.notifications, .identity),
] { check(try session.advance(generation: session.state.generation, expected: from, next: to, now: 0) == .advanced) }
let identified = try session.notifications(
  generation: session.state.generation, request: .identity,
  notification: bytes(frame(identityPayload) + telemetryFrame), sentAt: 0, now: 0.1)
check(identified.replies.count == 1 && identified.replies.first is ControllerIdentityReply)
check(
  try session.advance(generation: session.state.generation, expected: .identity, next: .ready, now: 0.1) == .advanced)
let measured = try session.notifications(
  generation: session.state.generation, request: .telemetry, notification: bytes(telemetryFrame + telemetryFrame),
  sentAt: 1, now: 1.1)
check(measured.action == .replyaccepted && measured.replies.count == 1)
check(measured.pendingBytes == 0 && measured.discardedBytes == 0)
let duplicate = try session.notifications(
  generation: session.state.generation, request: .telemetry, notification: bytes(telemetryFrame), sentAt: 1, now: 1.2)
check(duplicate.action == .none && duplicate.replies.isEmpty)
let rejected = try session.notifications(
  generation: session.state.generation, request: .telemetry,
  notification: bytes(frame(badMask) + telemetryFrame), sentAt: 2, now: 2.1)
check(rejected.action == .replyrejected && rejected.replies.count == 1)
check((rejected.replies.first as! ControllerRejectedReply).reason == "telemetryMask")
check(session.state.stage == .idle && session.identity == nil)
print("Telemetry Swift framework: \(checks) checks passed")
