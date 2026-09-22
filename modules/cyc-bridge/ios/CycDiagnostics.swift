import Foundation

enum CycReconnectPolicy {
  static let stablePeriod: Double = 30
  static func mayRetry(attempt: Int, activeRide: Bool) -> Bool { attempt <= 5 || activeRide }
  static func recoveryDelay(attempt: Int, activeRide: Bool, confirmedPeerDisconnect: Bool, stableTelemetrySeconds: Double?) -> Double {
    if activeRide, attempt > 5 { return 30 }
    return delay(attempt: attempt, confirmedPeerDisconnect: confirmedPeerDisconnect, stableTelemetrySeconds: stableTelemetrySeconds)
  }

  static func delay(attempt: Int, confirmedPeerDisconnect: Bool, stableTelemetrySeconds: Double?) -> Double {
    if attempt == 1, confirmedPeerDisconnect, let stableTelemetrySeconds,
      stableTelemetrySeconds.isFinite, stableTelemetrySeconds >= stablePeriod { return 0 }
    return pow(2, Double(max(1, attempt) - 1))
  }

  static func adoptSystemReconnect(attempt: Int, confirmedPeerDisconnect: Bool, stableTelemetrySeconds: Double?) -> Bool {
    delay(attempt: attempt, confirmedPeerDisconnect: confirmedPeerDisconnect, stableTelemetrySeconds: stableTelemetrySeconds) == 0
  }

  static func confirmedPeerDisconnect(callbackCompleted: Bool, initiator: String?) -> Bool {
    callbackCompleted && initiator == "peer_or_system"
  }

  static func mustCancelBeforeRetry(callbackCompleted: Bool, systemIsReconnecting: Bool,
    systemAttemptPending: Bool, peripheralIsDisconnected: Bool, adoptingSystemReconnect: Bool) -> Bool {
    guard !adoptingSystemReconnect else { return false }
    // A completed callback is stronger evidence than the asynchronously changing peripheral state.
    return systemIsReconnecting || systemAttemptPending || (!callbackCompleted && !peripheralIsDisconnected)
  }

  static func displayError(storage: String?, other: String?, recovery: String?, reconnecting: Bool) -> (message: String?, recoverable: Bool) {
    if let storage { return (storage, false) }
    if let other { return (other, false) }
    return (recovery, reconnecting && recovery != nil)
  }
}

enum CycPollingSchedule {
  static func delay(now: Double, poweredOn: Bool, verified: Bool, scanning: Bool,
    scanDeadline: Double?, reconnectDue: Double?, connectionDeadline: Double?, cancellationPending: Bool,
    responseDeadline: Double?, writeDeadline: Double?, nextPoll: Double?, sampleDeadline: Double?) -> Double {
    guard poweredOn else { return 5 }
    let reconnect = cancellationPending && (reconnectDue ?? .infinity) <= now ? nil : reconnectDue
    var deadline = now + (verified || scanning ? 0.25 : 5)
    for value in [scanDeadline, reconnect, connectionDeadline, responseDeadline, writeDeadline, nextPoll,
      sampleDeadline.flatMap { $0 > now ? $0 : nil }].compactMap({ $0 }) { deadline = min(deadline, value) }
    return max(0.005, deadline - now)
  }
}

/// Owns one attempt, including a connection already initiated by CoreBluetooth. Cancellation
/// remains pending until the central callback, even if CBPeripheral.state briefly says disconnected.
struct CycConnectionAttempt {
  enum Stage: String { case idle, connecting, services, characteristics, notifications, identity, ready }
  private(set) var generation: UInt64 = 0
  private(set) var stage: Stage = .idle
  private(set) var systemInitiated = false
  private(set) var cancellationPending = false

  mutating func begin(systemInitiated: Bool = false) -> Bool {
    guard !cancellationPending, stage == .idle else { return false }
    generation &+= 1
    stage = .connecting
    self.systemInitiated = systemInitiated
    return true
  }

  mutating func advance(from expected: Stage, to next: Stage) -> Bool {
    guard !cancellationPending, stage == expected else { return false }
    stage = next
    if expected == .connecting { systemInitiated = false }
    return true
  }

  mutating func invalidate() {
    generation &+= 1
    stage = .idle
    systemInitiated = false
  }

  mutating func requestedCancellation() {
    invalidate()
    cancellationPending = true
  }

  mutating func disconnected() {
    invalidate()
    cancellationPending = false
  }
}

enum CycDiagnosticEvent: String {
  case engineStarted = "engine_started"
  case connectRequested = "connect_requested"
  case connectionAttempt = "connection_attempt"
  case linkConnected = "link_connected"
  case identityVerified = "identity_verified"
  case transportStage = "transport_stage"
  case transportError = "transport_error"
  case disconnectRequested = "disconnect_requested"
  case disconnected
  case connectionFailed = "connection_failed"
  case reconnectScheduled = "reconnect_scheduled"
  case sessionStopped = "session_stopped"
  case bluetoothState = "bluetooth_state"
  case telemetrySummary = "telemetry_summary"
  case telemetryGap = "telemetry_gap"
  case backgroundChanged = "background_changed"
  case recordingError = "recording_error"
  case restored
  case resumed
}

enum CycDiagnosticReason: String {
  case manualDisconnect = "manual_disconnect"
  case connectionDeadline = "connection_deadline"
  case responseTimeout = "response_timeout"
  case expiredReply = "expired_reply"
  case writeReadyTimeout = "write_ready_timeout"
  case linkDisconnected = "link_disconnected"
  case connectionFailed = "connection_failed"
  case notificationSetup = "notification_setup"
  case notificationError = "notification_error"
  case serviceDiscovery = "service_discovery"
  case characteristicDiscovery = "characteristic_discovery"
  case servicesInvalidated = "services_invalidated"
  case identityRejected = "identity_rejected"
  case telemetryRejected = "telemetry_rejected"
  case writeCapacity = "write_capacity"
  case retryLimit = "retry_limit"
  case cancellationDeadline = "cancellation_deadline"
  case bluetoothUnavailable = "bluetooth_unavailable"
  case unexpectedConnection = "unexpected_connection"
  case pendingReconnect = "pending_reconnect"
  case restorationCleanup = "restoration_cleanup"
  case storageFailure = "storage_failure"
  case appTerminating = "app_terminating"
}

struct CycDisconnectDiagnostic {
  let initiator: String
  let reason: CycDiagnosticReason
  let errorDomain: String?
  let errorCode: Int?
  let connectionSeconds: Double?
  let sampleAgeSeconds: Double?

  var dictionary: [String: Any] {
    ["initiator": initiator, "reason": reason.rawValue,
      "errorDomain": errorDomain as Any? ?? NSNull(), "errorCode": errorCode as Any? ?? NSNull(),
      "connectionSeconds": connectionSeconds as Any? ?? NSNull(), "sampleAgeSeconds": sampleAgeSeconds as Any? ?? NSNull()]
  }
}

// No CoreBluetooth objects or device identity: deterministic connection accounting for tests.
struct CycDiagnosticMetrics {
  private(set) var sampleCount = 0
  private(set) var connectionAttempts = 0
  private(set) var reconnects = 0
  private(set) var requestTimeouts = 0
  private(set) var linkSamples = 0
  private(set) var linkStarted: Double?
  private(set) var lastSample: Double?
  private(set) var lastGapSeconds: Double?
  private(set) var lastDisconnect: CycDisconnectDiagnostic?
  private(set) var cancellationReason: CycDiagnosticReason?
  private(set) var cancellationStarted: Double?
  private(set) var cancelledConnectionSeconds: Double?
  private(set) var cancelledSampleAgeSeconds: Double?
  private var windowFirstSample: Double?
  private var windowLastSample: Double?
  private var windowSamples = 0
  private var latencySumMs = 0.0
  private var latencyMaxMs = 0.0
  private var latencyCount = 0
  private(set) var nextSummary: Double = 0

  mutating func beginSession(at now: Double) {
    self = CycDiagnosticMetrics()
    nextSummary = now + 30
  }

  mutating func beginAttempt(at now: Double) {
    connectionAttempts += 1
    linkStarted = nil
    linkSamples = 0
    cancellationReason = nil
    cancellationStarted = nil
    cancelledConnectionSeconds = nil
    cancelledSampleAgeSeconds = nil
    resetWindow(at: now)
  }

  mutating func connected(at now: Double) { linkStarted = now }
  mutating func scheduledReconnect() { reconnects += 1 }
  mutating func timedOut() { requestTimeouts += 1 }

  func connectionSeconds(at now: Double) -> Double? { linkStarted.map { max(0, now - $0) } }
  func sampleAge(at now: Double) -> Double? { lastSample.map { max(0, now - $0) } }

  // Keep the first app cancellation reason until its CB callback, even after transport fields are cleared.
  mutating func requestedCancellation(reason: CycDiagnosticReason, at now: Double) {
    guard cancellationReason == nil else { return }
    cancellationReason = reason
    cancellationStarted = now
    cancelledConnectionSeconds = connectionSeconds(at: now)
    cancelledSampleAgeSeconds = sampleAge(at: now)
  }

  mutating func disconnected(at now: Double, error: Error?, defaultReason: CycDiagnosticReason = .linkDisconnected) -> [String: Any] {
    let nsError = error as NSError?
    let detail = CycDisconnectDiagnostic(initiator: cancellationReason == nil ? "peer_or_system" : "app",
      reason: cancellationReason ?? defaultReason,
      errorDomain: nsError.map { CycDiagnosticLog.safeErrorDomain($0.domain) }, errorCode: nsError?.code,
      connectionSeconds: connectionSeconds(at: now), sampleAgeSeconds: sampleAge(at: now))
    var fields = detail.dictionary
    fields["linkSamples"] = linkSamples
    if let cancellationStarted { fields["cancellationCallbackSeconds"] = max(0, now - cancellationStarted) }
    if let cancelledConnectionSeconds { fields["connectionSecondsAtCancel"] = cancelledConnectionSeconds }
    if let cancelledSampleAgeSeconds { fields["sampleAgeSecondsAtCancel"] = cancelledSampleAgeSeconds }
    lastDisconnect = detail
    linkStarted = nil
    cancellationReason = nil
    cancellationStarted = nil
    resetWindow(at: now)
    return fields
  }

  mutating func receivedSample(at now: Double, responseSeconds: Double) -> Double? {
    let gap = lastSample.flatMap { now - $0 > CycProtocol.maximumGap ? now - $0 : nil }
    if let gap { lastGapSeconds = gap; resetWindow(at: now) }
    sampleCount += 1
    linkSamples += 1
    lastSample = now
    if windowFirstSample == nil { windowFirstSample = now }
    windowLastSample = now
    windowSamples += 1
    if responseSeconds.isFinite, responseSeconds >= 0 {
      let milliseconds = responseSeconds * 1000
      latencySumMs += milliseconds
      latencyMaxMs = max(latencyMaxMs, milliseconds)
      latencyCount += 1
    }
    return gap
  }

  func recentSampleHz(at now: Double) -> Double? {
    guard let first = windowFirstSample, let last = windowLastSample,
      windowSamples > 1, last > first, now - last <= CycProtocol.maximumGap else { return nil }
    return Double(windowSamples - 1) / (last - first)
  }

  var responseLatencyMs: [String: Double]? {
    guard latencyCount > 0 else { return nil }
    return ["mean": latencySumMs / Double(latencyCount), "max": latencyMaxMs]
  }

  mutating func resetWindow(at now: Double) {
    windowFirstSample = nil
    windowLastSample = nil
    windowSamples = 0
    latencySumMs = 0
    latencyMaxMs = 0
    latencyCount = 0
    nextSummary = now + 30
  }
}

/// Bounded, private JSONL. The serial log queue avoids making diagnostics part of BLE latency.
final class CycDiagnosticLog {
  static let maximumFileBytes = 256 * 1024
  private let queue = DispatchQueue(label: "app.powerlog.cyc.diagnostics", qos: .utility)
  private let directory: URL
  private let currentURL: URL
  private let previousURL: URL
  private let byteLimit: Int
  private var handle: FileHandle?
  private var currentBytes = 0
  private var writeError: Error?

  init(directory: URL? = nil, byteLimit: Int = CycDiagnosticLog.maximumFileBytes) throws {
    self.directory = try directory ?? FileManager.default.url(for: .applicationSupportDirectory,
      in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("PowerLog/diagnostics", isDirectory: true)
    self.currentURL = self.directory.appendingPathComponent("events.jsonl")
    self.previousURL = self.directory.appendingPathComponent("events.previous.jsonl")
    self.byteLimit = max(512, min(byteLimit, Self.maximumFileBytes))
    try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
    #if os(iOS)
    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: self.directory.path)
    #endif
    var resourceURL = self.directory
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    try resourceURL.setResourceValues(values)
    // Recover only bounded complete lines after a process interruption.
    let recovered = try readBounded(currentURL)
    try recovered.write(to: currentURL, options: .atomic)
    let previous = try readBounded(previousURL)
    if FileManager.default.fileExists(atPath: previousURL.path) { try previous.write(to: previousURL, options: .atomic) }
    currentBytes = recovered.count
    handle = try FileHandle(forWritingTo: currentURL)
    try handle?.seekToEnd()
  }

  static func safeErrorDomain(_ domain: String) -> String {
    let allowed: Set<String> = ["CBErrorDomain", "CBATTErrorDomain", "NSCocoaErrorDomain", "NSPOSIXErrorDomain", "NSOSStatusErrorDomain"]
    return allowed.contains(domain) ? domain : "OtherErrorDomain"
  }

  static func errorFields(_ error: Error?) -> [String: Any] {
    guard let error = error as NSError? else { return [:] }
    return ["errorDomain": safeErrorDomain(error.domain), "errorCode": error.code]
  }

  func append(_ event: CycDiagnosticEvent, elapsedSeconds: Double, fields: [String: Any] = [:]) {
    guard elapsedSeconds.isFinite, elapsedSeconds >= 0 else { return }
    var entry = Self.sanitized(fields)
    entry["schemaVersion"] = 1
    entry["timestamp"] = CycProtocol.timestamp()
    entry["elapsedSeconds"] = elapsedSeconds
    entry["event"] = event.rawValue
    guard var bytes = try? JSONSerialization.data(withJSONObject: entry, options: [.sortedKeys]), bytes.count + 1 <= byteLimit else { return }
    bytes.append(10)
    let payload = bytes
    queue.async {
      do {
        if self.currentBytes + payload.count > self.byteLimit { try self.rotate() }
        try self.handle?.write(contentsOf: payload)
        try self.handle?.synchronize()
        self.currentBytes += payload.count
      } catch { self.writeError = error }
    }
  }

  func read() throws -> String {
    try queue.sync {
      try handle?.synchronize()
      if writeError != nil { throw CycError.invalid("Private diagnostic storage is unavailable.") }
      let bytes = try readBounded(previousURL) + readBounded(currentURL)
      guard let text = String(data: bytes, encoding: .utf8) else { throw CycError.invalid("Private diagnostic log is not UTF-8.") }
      return text
    }
  }

  private func rotate() throws {
    try handle?.synchronize()
    try handle?.close()
    handle = nil
    if FileManager.default.fileExists(atPath: previousURL.path) { try FileManager.default.removeItem(at: previousURL) }
    try FileManager.default.moveItem(at: currentURL, to: previousURL)
    try Data().write(to: currentURL, options: .atomic)
    handle = try FileHandle(forWritingTo: currentURL)
    currentBytes = 0
  }

  private func readBounded(_ url: URL) throws -> Data {
    guard FileManager.default.fileExists(atPath: url.path) else { return Data() }
    let reader = try FileHandle(forReadingFrom: url)
    defer { try? reader.close() }
    let size = try reader.seekToEnd()
    let offset = size > UInt64(byteLimit) ? size - UInt64(byteLimit) : 0
    try reader.seek(toOffset: offset)
    var data = try reader.read(upToCount: byteLimit) ?? Data()
    // A retained tail starts in an unknown line; omit that line rather than emit malformed JSON.
    if offset > 0, let newline = data.firstIndex(of: 10) { data.removeSubrange(...newline) }
    else if offset > 0 { return Data() }
    guard let lastNewline = data.lastIndex(of: 10) else { return Data() }
    data.removeSubrange(data.index(after: lastNewline)..<data.endIndex)
    return data
  }

  private static func sanitized(_ fields: [String: Any]) -> [String: Any] {
    let numbers: Set<String> = ["requestedHz", "sampleCount", "connectionAttempts", "reconnects", "requestTimeouts",
      "decoderDiscardedBytes", "lastSampleAgeSeconds", "gapSeconds", "linkSamples", "connectionSeconds", "sampleAgeSeconds",
      "cancellationCallbackSeconds", "connectionSecondsAtCancel", "sampleAgeSecondsAtCancel", "requestAgeSeconds",
      "writeBlockedSeconds", "retryAttempt", "retryDelaySeconds", "errorCode", "recentSampleHz", "latencyMeanMs", "latencyMaxMs",
      "notificationErrors", "unexpectedReplies", "pendingBytes", "queueDelaySeconds", "storageWriteMs", "connectionGeneration"]
    var safe: [String: Any] = [:]
    for (key, value) in fields {
      if numbers.contains(key), let number = value as? NSNumber, number.doubleValue.isFinite { safe[key] = number }
      else if ["background", "systemReconnect"].contains(key), let value = value as? Bool { safe[key] = value }
      else if key == "transportStage", let value = value as? String, CycConnectionAttempt.Stage(rawValue: value) != nil { safe[key] = value }
      else if key == "reason", let value = value as? String, CycDiagnosticReason(rawValue: value) != nil { safe[key] = value }
      else if key == "errorDomain", let value = value as? String { safe[key] = safeErrorDomain(value) }
      else if key == "initiator", let value = value as? String, ["app", "peer_or_system"].contains(value) { safe[key] = value }
      else if key == "request", let value = value as? String, ["identity", "selective"].contains(value) { safe[key] = value }
      else if key == "manager", let value = value as? String, value == "central" { safe[key] = value }
      else if key == "status", let value = value as? String, ["idle", "scanning", "connecting", "connected", "reconnecting", "error"].contains(value) { safe[key] = value }
      else if key == "bluetoothState", let value = value as? String,
        ["unknown", "resetting", "unsupported", "unauthorized", "poweredOff", "poweredOn"].contains(value) { safe[key] = value }
    }
    return safe
  }
}
