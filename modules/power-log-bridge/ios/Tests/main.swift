import Foundation
import PowerLogCore

var assertions = 0
func expect(_ condition: @autoclosure () throws -> Bool, _ message: String) {
  assertions += 1
  if !(try! condition()) { fatalError(message) }
}
func bytes(_ hex: String) -> [UInt8] {
  stride(from: 0, to: hex.count, by: 2).map {
    let start = hex.index(hex.startIndex, offsetBy: $0)
    let end = hex.index(start, offsetBy: 2)
    return UInt8(hex[start..<end], radix: 16)!
  }
}

func framed(_ payload: [UInt8]) -> Data {
  let crc = CycProtocol.shared.crc(bytes: WorkoutCoreCodec.bytes(Data(payload)))
  let header: [UInt8] =
    payload.count <= 255 ? [2, UInt8(payload.count)] : [3, UInt8(payload.count >> 8), UInt8(payload.count & 255)]
  return Data(header + payload + [UInt8(crc >> 8), UInt8(crc & 255), 3])
}
func setup(_ session: TelemetrySession, now: Double = 0) throws {
  expect(try session.begin(now: now, systemInitiated: false), "start one session")
  for (from, to) in [
    (TelemetryStage.connecting, TelemetryStage.services), (.services, .characteristics),
    (.characteristics, .notifications), (.notifications, .identity),
  ] {
    expect(
      try session.advance(generation: session.state.generation, expected: from, next: to, now: now) == .advanced,
      "ordered transport setup")
  }
}
@discardableResult
func identified(_ payload: [UInt8]) throws -> ControllerIdentity {
  let session = TelemetrySession()
  try setup(session)
  let result = try session.receive(
    framed(payload), generation: session.state.generation, request: .identity, sentAt: 0, now: 0.1)
  guard let reply = result.replies.first as? ControllerIdentityReply else {
    if let rejection = result.replies.first as? ControllerRejectedReply {
      throw ControllerIOError.invalid(ControllerErrorPresentation.message(rejection))
    }
    throw ControllerIOError.invalid("Unsupported controller. Connect a CYC X6 or X12.")
  }
  return reply.identity
}
func decoded(_ payload: [UInt8], identity: ControllerIdentity? = nil) throws -> [String: Double] {
  let decoder = CycControllerDecoder()
  let result = decoder.feed(notification: WorkoutCoreCodec.bytes(framed(payload)), identity: identity)
  guard let reply = result.replies.first as? ControllerReadingReply else {
    throw ControllerIOError.invalid("Rejected telemetry")
  }
  return TelemetryRecords.metrics(reply.reading)
}
func recovery(
  attempt: Int, active: Bool = false, completed: Bool = true, peer: Bool = true,
  stable: Double? = 95, system: Bool = false, systemPending: Bool = false, disconnected: Bool = true
) -> TelemetryRetryPlan? {
  try! TelemetryReconnectPolicy.shared.plan(
    attempt: Int32(attempt), activeRide: active, callbackCompleted: completed, peerOrSystem: peer,
    stableTelemetrySeconds: stable.map { KotlinDouble(value: $0) }, systemIsReconnecting: system,
    systemAttemptPending: systemPending, peripheralIsDisconnected: disconnected)
}

let payload = bytes(
  "3203c0fb8f016301a7000004d200000237000009c4020b000030d400231860000d59f800000078000008ba00000000cd000009ab0103")
let frame = bytes(
  "02363203c0fb8f016301a7000004d200000237000009c4020b000030d400231860000d59f800000078000008ba00000000cd000009ab0103975203"
)
expect(
  CycProtocol.shared.crc(bytes: WorkoutCoreCodec.bytes(Data("123456789".utf8))) == 0x31c3,
  "byte bridge reaches the core CRC")
let splitDecoder = CycControllerDecoder()
let firstHalf = splitDecoder.feed(notification: WorkoutCoreCodec.bytes(Data(frame.prefix(9))), identity: nil).replies
let secondHalf = splitDecoder.feed(notification: WorkoutCoreCodec.bytes(Data(frame.dropFirst(9))), identity: nil)
  .replies
expect(
  (firstHalf + secondHalf).first is ControllerReadingReply, "split notifications reassemble across the byte bridge")
let values = try decoded(payload)
expect(values["humanPowerW"] == 205 && values["cadenceRpm"] == 87.5, "decoded readings cross into Swift")
do {
  try identified([111, 5, 3, 88, 49, 0])
  fatalError("must reject unknown model")
} catch {
  expect(
    error.localizedDescription == "Unsupported controller. Connect a CYC X6 or X12.",
    "a Kotlin rejection reaches Swift as plain copy")
}

let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-swift-tests-" + UUID().uuidString, isDirectory: true)
defer { try? FileManager.default.removeItem(at: directory) }
for duration: Double? in [nil, .nan] {
  expect(recovery(attempt: 1, stable: duration)?.delaySeconds == 1, "optional and NaN stability cross into the core")
}

let recoveryOnly = ControllerErrorPresentation.displayError(
  storage: nil, other: nil, recovery: "reconnecting", reconnecting: true)
expect(
  recoveryOnly.message == "reconnecting" && recoveryOnly.recoverable,
  "UI may delay only the explicit transport recovery error")
let storageAndRecovery = ControllerErrorPresentation.displayError(
  storage: "disk failure", other: nil, recovery: "reconnecting", reconnecting: true)
expect(
  storageAndRecovery.message == "disk failure" && !storageAndRecovery.recoverable,
  "storage failure takes precedence and cannot be hidden by a held sample")
let otherAndRecovery = ControllerErrorPresentation.displayError(
  storage: nil, other: "advertising failed", recovery: "reconnecting", reconnecting: true)
expect(
  otherAndRecovery.message == "advertising failed" && !otherAndRecovery.recoverable,
  "bridge errors are not transient connection errors")
let recoveredDisplay = ControllerErrorPresentation.displayError(
  storage: nil, other: nil, recovery: nil, reconnecting: false)
expect(
  recoveredDisplay.message == nil && !recoveredDisplay.recoverable, "first valid telemetry clears the recovery error")
expect(
  !ControllerErrorPresentation.displayError(storage: nil, other: nil, recovery: "failed", reconnecting: false)
    .recoverable,
  "terminal failures are never marked recoverable")

var metrics = TelemetryDiagnosticMetrics()
metrics.beginSession(at: 0)
metrics.beginAttempt(at: 1)
metrics.connected(at: 2)
_ = metrics.receivedSample(at: 3, responseSeconds: 0.02)
_ = metrics.receivedSample(at: 3.125, responseSeconds: 0.04)
expect(metrics.recentSampleHz(at: 3.2) == 8, "diagnostic cadence must measure actual sample spacing")
expect(
  metrics.responseLatencyMs?["mean"] == 30 && metrics.responseLatencyMs?["max"] == 40, "diagnostic response latency")
expect(metrics.recentSampleHz(at: 6) == nil, "diagnostic cadence must not present stale rate as live")
metrics.timedOut()
metrics.requestedCancellation(reason: .responseTimeout, at: 5.625)
metrics.requestedCancellation(reason: .retryLimit, at: 5.75)
let cancelled = metrics.disconnected(
  at: 6,
  error: NSError(domain: "CBErrorDomain", code: 6, userInfo: [NSLocalizedDescriptionKey: "private device marker"]))
expect(
  cancelled["initiator"] as? String == "app" && cancelled["reason"] as? String == "response_timeout",
  "CB callback must retain first app cancellation origin")
expect(
  metrics.cancellationReason == nil && metrics.lastDisconnect?.initiator == "app",
  "completed teardown retains its app origin for reconnect policy")
expect(
  cancelled["connectionSeconds"] as? Double == 4 && cancelled["linkSamples"] as? Int == 2,
  "disconnect includes per-link duration/count")
expect(
  cancelled["sampleAgeSecondsAtCancel"] as? Double == 2.5
    && cancelled["cancellationCallbackSeconds"] as? Double == 0.375, "cancel timing is captured before callback")
expect(
  cancelled["errorDomain"] as? String == "CBErrorDomain" && cancelled["errorCode"] as? Int == 6,
  "actual NSError domain/code retained")
expect(cancelled["errorDescription"] == nil, "NSError userInfo/description excluded")
metrics.scheduledReconnect()
metrics.beginAttempt(at: 7)
metrics.connected(at: 8)
let measuredGap = metrics.receivedSample(at: 9, responseSeconds: 0.01)
expect(
  measuredGap == 5.875 && metrics.lastGapSeconds == 5.875,
  "diagnostic sample clock survives reconnect to measure actual gap")
expect(
  metrics.sampleCount == 3 && metrics.linkSamples == 1 && metrics.connectionAttempts == 2 && metrics.reconnects == 1,
  "per-link and session counters remain separate")
let peer = metrics.disconnected(at: 10, error: NSError(domain: "CBErrorDomain", code: 7))
expect(
  peer["initiator"] as? String == "peer_or_system" && peer["reason"] as? String == "link_disconnected",
  "unsolicited disconnect must not be relabelled as app timeout")
expect(peer["connectionSeconds"] as? Double == 2, "reconnect starts a new link-duration clock")
expect(metrics.requestTimeouts == 1, "peer disconnect must not increment app request timeouts")

let countedDecoder = CycControllerDecoder()
let noiseCount = countedDecoder.feed(notification: WorkoutCoreCodec.bytes(Data([99, 88] + frame)), identity: nil)
expect(noiseCount.discardedBytes == 2, "decoder noise accounting excludes valid frame bytes")
_ = countedDecoder.feed(notification: WorkoutCoreCodec.bytes(Data(frame.prefix(3))), identity: nil)
let resetCount = countedDecoder.reset()
expect(resetCount.discardedBytes == 5, "discarded partial frame counted on transport reset")

let logDirectory = directory.appendingPathComponent("diagnostics", isDirectory: true)
var log: TelemetryDiagnosticLog? = try TelemetryDiagnosticLog(directory: logDirectory, byteLimit: 1024)
for sampleIndex in 0..<50 {
  log?.append(.telemetrySummary, elapsedSeconds: Double(sampleIndex), fields: ["sampleCount": sampleIndex])
}
let rolloverText = try log!.read()
expect(rolloverText.utf8.count <= 2048, "diagnostic disk export is bounded to two files")
let rolloverRows = try rolloverText.split(separator: "\n").map {
  try JSONSerialization.jsonObject(with: Data($0.utf8)) as! [String: Any]
}
expect(
  (rolloverRows.first?["sampleCount"] as? Int ?? 0) > 0 && rolloverRows.last?["sampleCount"] as? Int == 49,
  "rotation keeps the newest diagnostic entries")
let secretMarker = "PRIVATE_DEVICE_IDENTIFIER_SHOULD_NEVER_BE_EXPORTED"
log?.append(
  .transportError, elapsedSeconds: 51,
  fields: [
    "deviceId": secretMarker, "rawPayload": secretMarker,
    "errorDescription": secretMarker, "errorDomain": secretMarker, "reason": secretMarker, "sampleCount": Double.nan,
  ])
let redactedText = try log!.read()
expect(!redactedText.contains(secretMarker), "diagnostics redact unknown fields/descriptions/domains/reasons")
expect(redactedText.contains("OtherErrorDomain"), "unknown NSError domains use a safe category")
expect(!redactedText.contains("NaN"), "diagnostic JSON excludes nonfinite numbers")
log?.append(.telemetrySummary, elapsedSeconds: 52, fields: ["cpuSeconds": 12.5, "thermalState": 2])
let summaryRow = try log!.read().split(separator: "\n").last.map {
  try JSONSerialization.jsonObject(with: Data($0.utf8)) as! [String: Any]
}
expect(
  summaryRow?["cpuSeconds"] as? Double == 12.5 && summaryRow?["thermalState"] as? Int == 2,
  "telemetry summaries keep process CPU time and thermal state")
log = nil
let diagnosticFile = logDirectory.appendingPathComponent("events.jsonl")
let partialDiagnostic = try FileHandle(forWritingTo: diagnosticFile)
try partialDiagnostic.seekToEnd()
try partialDiagnostic.write(contentsOf: Data("{\"event\":\"torn".utf8))
try partialDiagnostic.close()
let reopenedLog = try TelemetryDiagnosticLog(directory: logDirectory, byteLimit: 1024)
let recoveredLog = try reopenedLog.read()
expect(!recoveredLog.contains("torn"), "process recovery drops a torn diagnostic line")
expect(recoveredLog.utf8.count <= 2048, "diagnostic recovery preserves the storage bound")
for row in recoveredLog.split(separator: "\n") { _ = try JSONSerialization.jsonObject(with: Data(row.utf8)) }
expect(true, "recovered diagnostic output remains parseable JSONL")

func contractFixture(_ name: String) throws -> [String: Any] {
  let url = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    .appendingPathComponent("tests/fixtures/contract/telemetry/" + name + ".json")
  return try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
}
let metricsContract = try contractFixture("metrics")
expect(
  try MetricCatalog.shared.format(id: "humanPowerW", value: nil) == metricsContract["unavailableText"] as! String,
  "an absent Kotlin value formats as unavailable")

let sessionContract = try contractFixture("session")
let neutralSession = TelemetrySession(protocol: try FixtureControllerProtocol(sessionContract))
expect(
  neutralSession.frame(.identity) == Data((sessionContract["identityRequest"] as! String).utf8),
  "neutral controller request crosses the production byte adapter")
try setup(neutralSession)
let neutralIdentity = Data((sessionContract["identityNotification"] as! String).utf8)
let partial = try neutralSession.receive(
  neutralIdentity.prefix(5), generation: neutralSession.state.generation,
  request: .identity, sentAt: 0, now: 0.1)
expect(partial.replies.isEmpty && partial.pendingBytes == 5, "neutral fragmented identity remains pending")
let neutralIdentified = try neutralSession.receive(
  neutralIdentity.dropFirst(5), generation: neutralSession.state.generation,
  request: .identity, sentAt: 0, now: 0.2)
expect(
  (neutralIdentified.replies.first as? ControllerIdentityReply)?.identity.controllerModel
    == (sessionContract["model"] as! String), "neutral controller uses the production notification seam")
expect(
  try neutralSession.advance(generation: neutralSession.state.generation, expected: .identity, next: .ready, now: 0.2)
    == .advanced, "neutral identity unlocks ready")
let neutralMeasured = try neutralSession.receive(
  Data((sessionContract["telemetryNotification"] as! String).utf8),
  generation: neutralSession.state.generation, request: .telemetry, sentAt: 1, now: 1.1)
let neutralReading = (neutralMeasured.replies.first as! ControllerReadingReply).reading
expect(
  try ControllerProfileRecord(neutralReading.identity!).restore(restorers: [
    try FixtureControllerProtocol(sessionContract)
  ])
    == neutralReading.identity,
  "neutral remembered identity uses the production restoration dispatcher")
expect(
  neutralReading.value(metric: .controllerspeedmps)?.doubleValue == sessionContract["speedMps"] as? Double,
  "neutral reading stays in its controller-supplied unit")
let readingContract = try contractFixture("reading")
let neutralOriginal = readingContract["original"] as! [String: Any]
let neutralProfile = neutralOriginal["identity"] as! [String: Any]
let neutralCapabilities = neutralProfile["capabilities"] as! [String: Any]
let metricNames = neutralCapabilities["suppliedMetrics"] as! [String]
let supplied = Set(WorkoutCoreCodec.values(TelemetryMetric.values()).filter { metricNames.contains($0.name) })
let neutralMetrics = neutralOriginal["metrics"] as! [String: Double]
let restoredReading = try TelemetryRestoration.shared.restore(
  controllerModel: neutralProfile["controllerModel"] as! String,
  firmwareLabel: neutralProfile["firmwareLabel"] as! String,
  controllerProtocol: neutralProfile["controllerProtocol"] as! String,
  metrics: neutralMetrics.mapValues { KotlinDouble(value: $0) },
  restorers: [try FixtureControllerProtocol(sessionContract, suppliedMetrics: supplied)]
).reading!
var neutralClock = TelemetryCaptureClock(
  origin: 90, sessionID: neutralOriginal["captureSessionId"] as! String,
  epochID: neutralOriginal["clockEpoch"] as! String)
let neutralSample = try neutralClock.observation(
  restoredReading, connectionEpoch: neutralOriginal["connectionEpoch"] as! String,
  monotonic: neutralOriginal["acquisitionMonotonic"] as! Double,
  wall: WorkoutCoding.date(neutralOriginal["timestamp"] as! String))
expect(
  neutralSample["humanPowerW"] as? Double == neutralMetrics["humanPowerW"]
    && neutralSample["controllerSpeedMps"] as? Double == neutralMetrics["controllerSpeedMps"],
  "neutral reading fixture crosses the production capture clock without wire conversion")

print("Power Log Swift core checks passed: \(assertions) assertions.")
