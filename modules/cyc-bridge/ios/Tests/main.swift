import Foundation

var assertions = 0
func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  assertions += 1
  if !condition() { fatalError(message) }
}
func rejects(_ body: () throws -> Void, _ message: String) {
  assertions += 1
  do { try body(); fatalError(message) } catch { }
}
func bytes(_ hex: String) -> [UInt8] {
  stride(from: 0, to: hex.count, by: 2).map {
    let start = hex.index(hex.startIndex, offsetBy: $0)
    let end = hex.index(start, offsetBy: 2)
    return UInt8(hex[start..<end], radix: 16)!
  }
}
func u16(_ packet: Data, _ index: Int) -> Int { Int(packet[index]) | (Int(packet[index + 1]) << 8) }

// Synthetic scalars encoded by the read-only Python prototype's verified layout.
let payload = bytes("3203c0fb8f016301a7000004d200000237000009c4020b000030d400231860000d59f800000078000008ba00000000cd000009ab0103")
let frame = bytes("02363203c0fb8f016301a7000004d200000237000009c4020b000030d400231860000d59f800000078000008ba00000000cd000009ab0103975203")
expect(Array(CycRequest.identity.frame) == bytes("02016f9d4903"), "identity request must match Python CRC fixture")
expect(Array(CycRequest.selective.frame) == bytes("02053203c0fb8f5a1a03"), "selective request must match Python CRC fixture")
expect(CycProtocol.crc(Array("123456789".utf8)) == 0x31c3, "XMODEM known vector")

for split in 0...frame.count {
  var decoder = CycFrameDecoder()
  let first = decoder.feed(Data(frame.prefix(split)))
  let second = decoder.feed(Data(frame.dropFirst(split)))
  expect(first + second == [payload], "every BLE split boundary must reassemble")
}
var coalesced = CycFrameDecoder()
expect(coalesced.feed(Data([99, 88] + frame + frame)) == [payload, payload], "coalesced frames/noise")
for index in 2..<(frame.count - 1) {
  var corrupted = frame
  corrupted[index] ^= 1
  var decoder = CycFrameDecoder()
  expect(decoder.feed(Data(corrupted)).isEmpty, "corrupt payload/CRC must be rejected")
  expect(decoder.feed(Data(frame)) == [payload], "valid frame must recover after corrupt frame")
}
var bounded = CycFrameDecoder()
_ = bounded.feed(Data(repeating: 3, count: 30_000))
expect(bounded.buffer.count <= 1028, "untrusted fragments must not grow buffer forever")

// Cross-language fixtures are shared with the TypeScript core and sanitized Python source vectors.
let fixtureURL = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("tests/fixtures/protocol.json")
let fixtures = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as! [String: Any]
let fixtureIdentity = fixtures["identity"] as! [String: Any]
try CycProtocol.validateIdentity(bytes(fixtureIdentity["payloadHex"] as! String))
let framingFixtures = fixtures["framing"] as! [String: Any]
var longDecoder = CycFrameDecoder()
expect(longDecoder.feed(Data(bytes(framingFixtures["longFrameHex"] as! String))) == [bytes(framingFixtures["longPayloadHex"] as! String)], "shared long frame")
for fixture in fixtures["telemetry"] as! [[String: Any]] {
  let data = bytes(fixture["payloadHex"] as! String)
  if (fixture["mask"] as! NSNumber).uint32Value == CycProtocol.selectedMask {
    let decoded = try CycProtocol.decodeTelemetry(data)
    for (key, number) in fixture["expected"] as! [String: NSNumber] {
      expect(abs(decoded[key]! - number.doubleValue) < 1e-8, "shared field parity: \(key)")
    }
  } else { rejects({ _ = try CycProtocol.decodeTelemetry(data) }, "live Swift decoder must reject partial shared masks") }
}

try CycProtocol.validateIdentity([111, 1, 2] + Array("X6_Pro 20260101A".utf8) + [0])
try CycProtocol.validateIdentity([0, 1, 2] + Array("X6 260101".utf8) + [0])
rejects({ try CycProtocol.validateIdentity([111, 1, 2] + Array("X1 20260101".utf8) + [0]) }, "unknown controller")
rejects({ try CycProtocol.validateIdentity([111, 1, 2] + Array("X6".utf8) + [0]) }, "unverified firmware label")
rejects({ try CycProtocol.validateIdentity([111, 1, 2] + Array("X6 20260101".utf8)) }, "unterminated identity")
rejects({ try CycProtocol.validateIdentity([111, 1, 2] + Array("X6 20260101 ".utf8) + Array(repeating: 65, count: 130) + [0]) }, "oversized identity")
// Synthetic suffix bytes reproduce the binary-before-NUL shape, never a real identity/serial.
for command: UInt8 in [0, 111] {
  try CycProtocol.validateIdentity([command, 5, 3] + Array("X6        20250604 ".utf8) + [0x80, 0xff, 0x1f, 0x7f, 65, 0, 0xfe, 0xdc])
  assertions += 1
}
for label in ["X1 20250604 ", "X60 20250604 ", "X120 20250604 ", "X12 202506041 ", " X6 20250604 ", "X6-Other 20250604 ", "X6 20250 ", "X6 202506041 ", "X6 20250604a ", "X6 20250604/extra ", "X6 20250604\n", "X6\t20250604 ", "X6" + String(repeating: "A", count: 31) + " 20250604 ", "X6 20250604" + String(repeating: "A", count: 9) + " "] {
  rejects({ try CycProtocol.validateIdentity([111, 5, 3] + Array(label.utf8) + [0x80, 0xff, 0]) }, "unsupported prefix must not be accepted because of opaque suffix")
}
rejects({ try CycProtocol.validateIdentity([111, 5, 3, 88, 0x80, 54, 32] + Array("20250604".utf8) + [0]) }, "non-ASCII model")
rejects({ try CycProtocol.validateIdentity([111, 5, 3] + Array("X6 20250604".utf8) + [0xff, 0]) }, "binary without separating space")
for model in ["X6", "X12"] {
  let identity = try CycProtocol.validateIdentity([111, 5, 3] + Array("\(model) 20250604 ".utf8) + [0x80, 0xff, 0])
  expect(identity.controllerModel == model && identity.firmwareLabel == "20250604", "sanitized controller label")
  expect(identity.adapter.rawValue == model && identity.major == 5 && identity.minor == 3, "explicit family adapter")
  var highVoltage = payload
  highVoltage[21] = 0x02; highVoltage[22] = 0xd0 // Synthetic 72.0 V.
  let values = try identity.adapter.decodeTelemetry(highVoltage)
  expect(values["batteryVoltageV"] == 72, "X12 battery voltage is not capped to X6 voltage")
  expect(values["humanPowerW"] == 205 && abs(values["motorInputPowerW"]! - 72 * 5.67) < 1e-8, "rider and electrical watts remain separate")
  rejects({ _ = try identity.adapter.decodeTelemetry(Array(highVoltage.dropLast())) }, "adapter rejects wrong layout")
  let restored = try JSONDecoder().decode(CycControllerIdentity.self, from: JSONEncoder().encode(identity))
  expect(restored.controllerModel == model && restored.adapter == identity.adapter, "sanitized remembered identity roundtrip")
}
let identityPrefix = Array("X6 20250604 ".utf8)
let boundedIdentity: [UInt8] = [111, 5, 3] + identityPrefix + Array(repeating: 0xff, count: 128 - identityPrefix.count) + [0]
try CycProtocol.validateIdentity(boundedIdentity)
assertions += 1
rejects({ try CycProtocol.validateIdentity(Array(boundedIdentity.dropLast()) + [0xff, 0]) }, "opaque label size limit")
rejects({ try CycProtocol.validateIdentity(Array(boundedIdentity.dropLast())) }, "opaque label still requires NUL")
rejects({ try CycProtocol.validateIdentity([50] + Array(boundedIdentity.dropFirst())) }, "identity command limit")
rejects({ try CycProtocol.validateIdentity(boundedIdentity + Array(repeating: 0, count: 1025 - boundedIdentity.count)) }, "identity payload size limit")
do { try CycProtocol.validateIdentity([111, 5, 3, 88, 49, 0]); fatalError("must reject unknown model") }
catch { expect(error.localizedDescription == "Unsupported controller. Connect a CYC X6 or X12.", "plain unsupported-controller copy") }

let values = try CycProtocol.decodeTelemetry(payload)
expect(values["humanPowerW"] == 205 && values["cadenceRpm"] == 87.5, "rider scalars")
expect(abs(values["motorInputPowerW"]! - 296.541) < 0.000001, "input power must use battery current")
expect(values["controllerTempC"] == 35.5 && values["motorCurrentA"] == 12.34, "signed scaling")
expect(values["speedRaw"] == 24.75 && values["assistLevel"] == 3 && values["raceMode"] == 1, "tail layout")
for model in ["X6", "X12"] {
  let identity = try CycProtocol.validateIdentity([111, 5, 3] + Array("\(model) 20250604 ".utf8) + [0x80, 0xff, 0])
  for raw in [0.0, 24.75, -12.5] {
    let annotated = identity.annotate(["speedRaw": raw])
    expect(annotated["controllerSpeedMps"] as? Double == raw / 3.6, "known speed converts km/h to m/s")
    expect(annotated["speedRaw"] as? Double == raw && annotated["speedMps"] == nil, "original and GPS channel stay separate")
    expect(annotated["controllerModel"] as? String == model && annotated["firmwareLabel"] as? String == "20250604" && annotated["controllerProtocol"] as? String == "5.3", "sanitized speed provenance")
  }
}
for payload in [[111, 5, 4] + Array("X6 20250604".utf8) + [0], [111, 6, 3] + Array("X12 20250604".utf8) + [0], [111, 5, 3] + Array("X6_Pro 20250604".utf8) + [0]] {
  let unknown = try CycProtocol.validateIdentity(payload).annotate(["speedRaw": 36.0, "controllerSpeedMps": 10.0])
  expect(unknown["controllerSpeedMps"] == nil && unknown["speedRaw"] as? Double == 36, "unknown profile cannot inherit normalized speed")
}
var negative = payload
negative.replaceSubrange(13..<17, with: [255, 255, 255, 156]) // battery current = -1A
let negativeValues = try CycProtocol.decodeTelemetry(negative)
expect(negativeValues["batteryCurrentA"] == -1, "signed current decoding")
for length in 0..<payload.count {
  rejects({ _ = try CycProtocol.decodeTelemetry(Array(payload.prefix(length))) }, "truncated telemetry")
}
rejects({ _ = try CycProtocol.decodeTelemetry(payload + [0]) }, "unknown trailing layout")
var wrongMask = payload
wrongMask[1] ^= 1
rejects({ _ = try CycProtocol.decodeTelemetry(wrongMask) }, "different selective mask")

let directory = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-swift-tests-" + UUID().uuidString, isDirectory: true)
defer { try? FileManager.default.removeItem(at: directory) }
expect(CycReconnectPolicy.delay(attempt: 1, confirmedPeerDisconnect: true, stableTelemetrySeconds: 95) == 0, "confirmed loss of stable link reconnects without an app delay")
expect(CycReconnectPolicy.delay(attempt: 1, confirmedPeerDisconnect: true, stableTelemetrySeconds: 30) == 0, "stable recovery boundary")
for duration: Double? in [nil, 0, 29.999, .nan, .infinity] {
  expect(CycReconnectPolicy.delay(attempt: 1, confirmedPeerDisconnect: true, stableTelemetrySeconds: duration) == 1, "short/unverified links must not spin immediate retries")
}
expect(CycReconnectPolicy.delay(attempt: 1, confirmedPeerDisconnect: false, stableTelemetrySeconds: 95) == 1, "app cancellation or pending disconnect keeps normal backoff")
for attempt in 2...5 {
  expect(CycReconnectPolicy.delay(attempt: attempt, confirmedPeerDisconnect: true, stableTelemetrySeconds: 95) == pow(2, Double(attempt - 1)), "repeat failures retain bounded exponential recovery")
}

expect(CycReconnectPolicy.adoptSystemReconnect(attempt: 1, confirmedPeerDisconnect: true, stableTelemetrySeconds: 95), "first stable peer recovery adopts the system's already pending connection")
expect(!CycReconnectPolicy.adoptSystemReconnect(attempt: 1, confirmedPeerDisconnect: false, stableTelemetrySeconds: 95), "app cancellation cannot adopt automatic reconnection")
// Physical regression: didDisconnect reported peer/system + no automatic retry, but the
// CBPeripheral state snapshot had not become disconnected. The callback must win.
let completedPeer = CycReconnectPolicy.confirmedPeerDisconnect(callbackCompleted: true, initiator: "peer_or_system")
expect(completedPeer, "completed unsolicited disconnect is confirmed independently of peripheral state")
expect(CycReconnectPolicy.delay(attempt: 1, confirmedPeerDisconnect: completedPeer, stableTelemetrySeconds: 94.92) == 0, "observed 95-second disconnect schedules immediate fallback")
for disconnectedSnapshot in [false, true] {
  expect(!CycReconnectPolicy.mustCancelBeforeRetry(callbackCompleted: true, systemIsReconnecting: false,
    systemAttemptPending: false, peripheralIsDisconnected: disconnectedSnapshot, adoptingSystemReconnect: false), "completed non-system disconnect must not be cancelled again even with a stale state snapshot")
}
expect(!CycReconnectPolicy.confirmedPeerDisconnect(callbackCompleted: false, initiator: "peer_or_system"), "last disconnect metadata cannot confirm a later timeout before its callback")
expect(!CycReconnectPolicy.confirmedPeerDisconnect(callbackCompleted: true, initiator: "app"), "app cancellation keeps normal retry backoff after acknowledgement")
expect(CycReconnectPolicy.mustCancelBeforeRetry(callbackCompleted: false, systemIsReconnecting: false,
  systemAttemptPending: false, peripheralIsDisconnected: false, adoptingSystemReconnect: false), "live timeout still cancels before retry")
expect(CycReconnectPolicy.mustCancelBeforeRetry(callbackCompleted: true, systemIsReconnecting: true,
  systemAttemptPending: false, peripheralIsDisconnected: true, adoptingSystemReconnect: false), "unadopted system attempt must be cancelled despite a disconnected state snapshot")
expect(CycReconnectPolicy.mustCancelBeforeRetry(callbackCompleted: false, systemIsReconnecting: false,
  systemAttemptPending: true, peripheralIsDisconnected: true, adoptingSystemReconnect: false), "deadline still cancels an owned pending system attempt")
expect(!CycReconnectPolicy.mustCancelBeforeRetry(callbackCompleted: true, systemIsReconnecting: true,
  systemAttemptPending: false, peripheralIsDisconnected: false, adoptingSystemReconnect: true), "adopted system recovery must keep its existing connection attempt")
expect(!CycReconnectPolicy.adoptSystemReconnect(attempt: 1, confirmedPeerDisconnect: true, stableTelemetrySeconds: 29.9), "short links cancel system recovery and use backoff")
for attempt in 2...6 {
  expect(!CycReconnectPolicy.adoptSystemReconnect(attempt: attempt, confirmedPeerDisconnect: true, stableTelemetrySeconds: 95), "repeat system recovery cannot bypass retry backoff or limit")
}
var connection = CycConnectionAttempt()
expect(connection.begin(systemInitiated: true), "adopt system connection as one owned attempt")
let adoptedGeneration = connection.generation
expect(connection.systemInitiated && connection.stage == .connecting, "system attempt remains identifiable before didConnect")
expect(!connection.begin(), "duplicate start must not replace an in-flight connection")
expect(!connection.advance(from: .identity, to: .ready), "late identity callback cannot bypass service setup")
expect(connection.advance(from: .connecting, to: .services), "didConnect starts discovery once")
expect(!connection.systemInitiated, "connected link no longer has a pending system connect")
expect(!connection.advance(from: .connecting, to: .services), "duplicate didConnect cannot restart discovery")
expect(connection.advance(from: .services, to: .characteristics), "UART services before characteristics")
expect(!connection.advance(from: .notifications, to: .identity), "notification callback before characteristic discovery is ignored")
expect(connection.advance(from: .characteristics, to: .notifications), "characteristics before notification subscription")
expect(connection.advance(from: .notifications, to: .identity), "notification readiness permits only the identity request")
expect(connection.advance(from: .identity, to: .ready), "verified identity unlocks telemetry")
connection.requestedCancellation()
expect(connection.cancellationPending && connection.stage == .idle && connection.generation > adoptedGeneration, "cancellation invalidates the old transport generation")
expect(!connection.advance(from: .ready, to: .services), "late callbacks cannot act after cancellation")
connection.invalidate()
expect(connection.cancellationPending && !connection.begin(), "clearing transport never treats a pending cancellation as acknowledged")
connection.disconnected()
expect(!connection.cancellationPending && connection.begin(), "confirmed cancellation permits the next bounded attempt")
connection.disconnected()
expect(connection.begin(systemInitiated: true), "a new system attempt can be adopted after confirmed teardown")
connection.requestedCancellation()
expect(!connection.systemInitiated && !connection.begin(systemInitiated: true), "manual stop or connection deadline cancels the system attempt before another owner can start")

let recoveryOnly = CycReconnectPolicy.displayError(storage: nil, other: nil, recovery: "reconnecting", reconnecting: true)
expect(recoveryOnly.message == "reconnecting" && recoveryOnly.recoverable, "UI may delay only the explicit transport recovery error")
let storageAndRecovery = CycReconnectPolicy.displayError(storage: "disk failure", other: nil, recovery: "reconnecting", reconnecting: true)
expect(storageAndRecovery.message == "disk failure" && !storageAndRecovery.recoverable, "storage failure takes precedence and cannot be hidden by a held sample")
let otherAndRecovery = CycReconnectPolicy.displayError(storage: nil, other: "advertising failed", recovery: "reconnecting", reconnecting: true)
expect(otherAndRecovery.message == "advertising failed" && !otherAndRecovery.recoverable, "bridge errors are not transient connection errors")
let recoveredDisplay = CycReconnectPolicy.displayError(storage: nil, other: nil, recovery: nil, reconnecting: false)
expect(recoveredDisplay.message == nil && !recoveredDisplay.recoverable, "first valid telemetry clears the recovery error")
expect(!CycReconnectPolicy.displayError(storage: nil, other: nil, recovery: "failed", reconnecting: false).recoverable, "terminal failures are never marked recoverable")

var metrics = CycDiagnosticMetrics()
metrics.beginSession(at: 0)
metrics.beginAttempt(at: 1)
metrics.connected(at: 2)
_ = metrics.receivedSample(at: 3, responseSeconds: 0.02)
_ = metrics.receivedSample(at: 3.125, responseSeconds: 0.04)
expect(metrics.recentSampleHz(at: 3.2) == 8, "diagnostic cadence must measure actual sample spacing")
expect(metrics.responseLatencyMs?["mean"] == 30 && metrics.responseLatencyMs?["max"] == 40, "diagnostic response latency")
expect(metrics.recentSampleHz(at: 6) == nil, "diagnostic cadence must not present stale rate as live")
metrics.timedOut()
metrics.requestedCancellation(reason: .responseTimeout, at: 5.625)
metrics.requestedCancellation(reason: .retryLimit, at: 5.75)
let cancelled = metrics.disconnected(at: 6, error: NSError(domain: "CBErrorDomain", code: 6, userInfo: [NSLocalizedDescriptionKey: "private device marker"]))
expect(cancelled["initiator"] as? String == "app" && cancelled["reason"] as? String == "response_timeout", "CB callback must retain first app cancellation origin")
expect(metrics.cancellationReason == nil && metrics.lastDisconnect?.initiator == "app", "completed teardown retains its app origin for reconnect policy")
expect(cancelled["connectionSeconds"] as? Double == 4 && cancelled["linkSamples"] as? Int == 2, "disconnect includes per-link duration/count")
expect(cancelled["sampleAgeSecondsAtCancel"] as? Double == 2.5 && cancelled["cancellationCallbackSeconds"] as? Double == 0.375, "cancel timing is captured before callback")
expect(cancelled["errorDomain"] as? String == "CBErrorDomain" && cancelled["errorCode"] as? Int == 6, "actual NSError domain/code retained")
expect(cancelled["errorDescription"] == nil, "NSError userInfo/description excluded")
metrics.scheduledReconnect()
metrics.beginAttempt(at: 7)
metrics.connected(at: 8)
let measuredGap = metrics.receivedSample(at: 9, responseSeconds: 0.01)
expect(measuredGap == 5.875 && metrics.lastGapSeconds == 5.875, "diagnostic sample clock survives reconnect to measure actual gap")
expect(metrics.sampleCount == 3 && metrics.linkSamples == 1 && metrics.connectionAttempts == 2 && metrics.reconnects == 1, "per-link and session counters remain separate")
let peer = metrics.disconnected(at: 10, error: NSError(domain: "CBErrorDomain", code: 7))
expect(peer["initiator"] as? String == "peer_or_system" && peer["reason"] as? String == "link_disconnected", "unsolicited disconnect must not be relabelled as app timeout")
expect(peer["connectionSeconds"] as? Double == 2, "reconnect starts a new link-duration clock")
expect(metrics.requestTimeouts == 1, "peer disconnect must not increment app request timeouts")

var countedDecoder = CycFrameDecoder()
_ = countedDecoder.feed(Data([99, 88] + frame))
expect(countedDecoder.discardedBytes == 2, "decoder noise accounting excludes valid frame bytes")
_ = countedDecoder.feed(Data(frame.prefix(3)))
countedDecoder.reset()
expect(countedDecoder.discardedBytes == 5, "discarded partial frame counted on transport reset")

let logDirectory = directory.appendingPathComponent("diagnostics", isDirectory: true)
var log: CycDiagnosticLog? = try CycDiagnosticLog(directory: logDirectory, byteLimit: 1024)
for sampleIndex in 0..<50 { log?.append(.telemetrySummary, elapsedSeconds: Double(sampleIndex), fields: ["sampleCount": sampleIndex]) }
let rolloverText = try log!.read()
expect(rolloverText.utf8.count <= 2048, "diagnostic disk export is bounded to two files")
let rolloverRows = try rolloverText.split(separator: "\n").map { try JSONSerialization.jsonObject(with: Data($0.utf8)) as! [String: Any] }
expect((rolloverRows.first?["sampleCount"] as? Int ?? 0) > 0 && rolloverRows.last?["sampleCount"] as? Int == 49, "rotation keeps the newest diagnostic entries")
let secretMarker = "PRIVATE_DEVICE_IDENTIFIER_SHOULD_NEVER_BE_EXPORTED"
log?.append(.transportError, elapsedSeconds: 51, fields: ["deviceId": secretMarker, "rawPayload": secretMarker,
  "errorDescription": secretMarker, "errorDomain": secretMarker, "reason": secretMarker, "sampleCount": Double.nan])
let redactedText = try log!.read()
expect(!redactedText.contains(secretMarker), "diagnostics redact unknown fields/descriptions/domains/reasons")
expect(redactedText.contains("OtherErrorDomain"), "unknown NSError domains use a safe category")
expect(!redactedText.contains("NaN"), "diagnostic JSON excludes nonfinite numbers")
log = nil
let diagnosticFile = logDirectory.appendingPathComponent("events.jsonl")
let partialDiagnostic = try FileHandle(forWritingTo: diagnosticFile)
try partialDiagnostic.seekToEnd()
try partialDiagnostic.write(contentsOf: Data("{\"event\":\"torn".utf8))
try partialDiagnostic.close()
let reopenedLog = try CycDiagnosticLog(directory: logDirectory, byteLimit: 1024)
let recoveredLog = try reopenedLog.read()
expect(!recoveredLog.contains("torn"), "process recovery drops a torn diagnostic line")
expect(recoveredLog.utf8.count <= 2048, "diagnostic recovery preserves the storage bound")
for row in recoveredLog.split(separator: "\n") { _ = try JSONSerialization.jsonObject(with: Data(row.utf8)) }
expect(true, "recovered diagnostic output remains parseable JSONL")
expect(WorkoutStorageFaultPolicy.freezesRide(PowerLogStorageError.sqlite(13, "database or disk is full")), "SQLite faults stop the ride")
expect(WorkoutStorageFaultPolicy.freezesRide(NSError(domain: NSPOSIXErrorDomain, code: 28)), "file system faults stop the ride")
let rejectedOperations: [Error] = [PowerLogStorageError.busy, PowerLogStorageError.invalid("policy"), PowerLogStorageError.conflict("conflict"),
  PowerLogStorageError.missing("missing"), PowerLogStorageError.deleted("id"), PowerLogStorageError.revision(expected: 1, actual: 2),
  WorkoutDataError.invalid("bad"), CycError.invalid("bad")]
for rejected in rejectedOperations { expect(!WorkoutStorageFaultPolicy.freezesRide(rejected), "rejected or transient operations keep the ride running: \(rejected)") }

print("Power Log Swift core checks passed: \(assertions) assertions.")
